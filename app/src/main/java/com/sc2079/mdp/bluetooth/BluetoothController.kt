package com.sc2079.mdp.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Owns the Bluetooth serial (RFCOMM/SPP) link to the robot.
 *
 * Two independent paths keep the link alive, which is what makes checklist C.8
 * work: an outgoing client loop that keeps retrying the last selected device,
 * and a server socket that accepts the remote side connecting back to the
 * tablet. Whichever succeeds first becomes the live connection, and an incoming
 * connection always replaces an older socket that may already be half dead.
 *
 * All socket I/O runs on plain threads; observers see the results through the
 * flows exposed here.
 */
@SuppressLint("MissingPermission")
class BluetoothController private constructor(context: Context) {

    private val appContext = context.applicationContext

    private val adapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _remoteName = MutableStateFlow<String?>(null)

    /** Friendly name of the device we are connected to, or last tried. */
    val remoteName: StateFlow<String?> = _remoteName.asStateFlow()

    private val _incoming = MutableSharedFlow<String>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** One event per line received from the robot. */
    val incoming: SharedFlow<String> = _incoming.asSharedFlow()

    private val _events = MutableSharedFlow<String>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Human readable link notices ("Connected to ...", "Link lost", ...). */
    val events: SharedFlow<String> = _events.asSharedFlow()

    /** Serialises writes so a blocking socket never stalls the UI thread. */
    private val writeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mdp-bt-write").apply { isDaemon = true }
    }

    private val lock = Any()
    private var clientThread: ClientThread? = null
    private var serverThread: ServerThread? = null
    private var connectedThread: ConnectedThread? = null
    private var lastDevice: BluetoothDevice? = null

    @Volatile
    private var keepAlive = false

    val isBluetoothSupported: Boolean get() = adapter != null

    val isBluetoothEnabled: Boolean get() = adapter?.isEnabled == true

    val isConnected: Boolean get() = _state.value == ConnectionState.CONNECTED

    /** Devices already paired with the tablet, shown first in the picker. */
    fun bondedDevices(): List<BluetoothDevice> = adapter?.bondedDevices?.toList().orEmpty()

    fun cancelDiscovery() {
        runCatching { adapter?.cancelDiscovery() }
    }

    /**
     * Connects to [device] and keeps the link alive until [disconnect] is called:
     * the client loop retries on failure and the server socket stays open so the
     * remote side can also reconnect on its own.
     */
    fun connect(device: BluetoothDevice) {
        val adapter = this.adapter ?: run {
            emitEvent("This device has no Bluetooth adapter")
            return
        }
        runCatching { adapter.cancelDiscovery() }
        synchronized(lock) {
            keepAlive = true
            lastDevice = device
            _remoteName.value = deviceLabel(device)
            closeConnection(notify = false)
            clientThread?.cancel()
            clientThread = ClientThread(device).also { it.start() }
            ensureServerLocked()
        }
        setState(ConnectionState.CONNECTING)
    }

    /**
     * Starts only the server side, so the AMD tool (or the RPi) can be the one
     * that opens the link. Safe to call repeatedly.
     */
    fun listenForIncoming() {
        if (adapter == null) {
            emitEvent("This device has no Bluetooth adapter")
            return
        }
        synchronized(lock) {
            keepAlive = true
            ensureServerLocked()
        }
        if (!isConnected) setState(ConnectionState.RECONNECTING)
        emitEvent("Waiting for an incoming connection")
    }

    /** Tears the link down and stops every retry. */
    fun disconnect() {
        synchronized(lock) {
            keepAlive = false
            clientThread?.cancel()
            clientThread = null
            serverThread?.cancel()
            serverThread = null
            closeConnection(notify = false)
        }
        setState(ConnectionState.DISCONNECTED)
        emitEvent("Disconnected")
    }

    /**
     * Queues [text] followed by a newline for transmission.
     *
     * Returns false straight away when there is no live link so the GUI can say
     * so; the write itself happens on a worker thread, and a write that fails
     * mid-flight is reported through [events].
     */
    fun send(text: String): Boolean {
        val thread = synchronized(lock) { connectedThread }
        if (thread == null) {
            emitEvent("Not connected - \"$text\" was not sent")
            return false
        }
        writeExecutor.execute { thread.write(text + "\n") }
        return true
    }

    private fun ensureServerLocked() {
        if (serverThread?.isAlive == true) return
        serverThread = ServerThread().also { it.start() }
    }

    private fun onSocketConnected(socket: BluetoothSocket, device: BluetoothDevice?) {
        synchronized(lock) {
            closeConnection(notify = false)
            connectedThread = ConnectedThread(socket).also { it.start() }
            device?.let {
                lastDevice = it
                _remoteName.value = deviceLabel(it)
            }
        }
        setState(ConnectionState.CONNECTED)
        emitEvent("Connected to ${_remoteName.value ?: "device"}")
    }

    /** Called by the reader thread when its socket dies. */
    private fun onConnectionLost(thread: ConnectedThread) {
        synchronized(lock) {
            if (connectedThread !== thread) return
            connectedThread = null
        }
        if (keepAlive) {
            setState(ConnectionState.RECONNECTING)
            emitEvent("Link lost - reconnecting")
            synchronized(lock) {
                ensureServerLocked()
                val device = lastDevice
                if (device != null && clientThread?.isAlive != true) {
                    clientThread = ClientThread(device).also { it.start() }
                }
            }
        } else {
            setState(ConnectionState.DISCONNECTED)
            emitEvent("Disconnected")
        }
    }

    private fun closeConnection(notify: Boolean) {
        connectedThread?.cancel()
        connectedThread = null
        if (notify) setState(ConnectionState.DISCONNECTED)
    }

    private fun setState(state: ConnectionState) {
        _state.value = state
    }

    private fun emitEvent(message: String) {
        _events.tryEmit(message)
    }

    private fun deviceLabel(device: BluetoothDevice): String =
        runCatching { device.name }.getOrNull() ?: device.address

    /** Keeps trying to open an outgoing socket until the link is up. */
    private inner class ClientThread(private val device: BluetoothDevice) : Thread("mdp-bt-client") {

        @Volatile
        private var cancelled = false

        @Volatile
        private var socket: BluetoothSocket? = null

        fun cancel() {
            cancelled = true
            runCatching { socket?.close() }
            interrupt()
        }

        override fun run() {
            var attempt = 0
            while (!cancelled && keepAlive) {
                if (isConnected) {
                    if (sleepQuietly(RETRY_DELAY_MS)) continue else return
                }
                attempt++
                val opened = openSocket()
                if (opened != null) {
                    onSocketConnected(opened, device)
                } else {
                    if (attempt == 1) emitEvent("Could not reach ${deviceLabel(device)} - retrying")
                    if (!sleepQuietly(RETRY_DELAY_MS)) return
                }
            }
        }

        /**
         * Opens an RFCOMM socket, falling back to the hidden channel-1 factory that
         * some HC-05 style modules need when the SPP service record is missing.
         */
        private fun openSocket(): BluetoothSocket? {
            runCatching { adapter?.cancelDiscovery() }
            try {
                val secure = device.createRfcommSocketToServiceRecord(SPP_UUID)
                socket = secure
                secure.connect()
                return secure
            } catch (e: IOException) {
                Log.d(TAG, "Secure RFCOMM connect failed", e)
                runCatching { socket?.close() }
            }
            if (cancelled) return null
            return try {
                @Suppress("DEPRECATION")
                val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                val fallback = method.invoke(device, 1) as BluetoothSocket
                socket = fallback
                fallback.connect()
                fallback
            } catch (e: Exception) {
                Log.d(TAG, "Fallback RFCOMM connect failed", e)
                runCatching { socket?.close() }
                null
            }
        }

        private fun sleepQuietly(millis: Long): Boolean = try {
            sleep(millis)
            true
        } catch (e: InterruptedException) {
            false
        }
    }

    /** Accepts connections opened by the robot or the AMD tool. */
    private inner class ServerThread : Thread("mdp-bt-server") {

        @Volatile
        private var cancelled = false

        private var serverSocket: BluetoothServerSocket? = null

        fun cancel() {
            cancelled = true
            runCatching { serverSocket?.close() }
            interrupt()
        }

        override fun run() {
            while (!cancelled && keepAlive) {
                val server = try {
                    adapter?.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
                } catch (e: IOException) {
                    Log.w(TAG, "Cannot open server socket", e)
                    null
                } ?: return
                serverSocket = server
                val socket = try {
                    server.accept()
                } catch (e: IOException) {
                    if (!cancelled) Log.d(TAG, "Server socket closed", e)
                    null
                }
                runCatching { server.close() }
                serverSocket = null
                if (cancelled || !keepAlive) {
                    runCatching { socket?.close() }
                    return
                }
                if (socket != null) {
                    // An incoming connection always wins: the previous socket is
                    // usually a stale half of a link the remote side already dropped.
                    onSocketConnected(socket, socket.remoteDevice)
                    // Wait for this link to end before listening again.
                    while (!cancelled && keepAlive && isConnected) {
                        try {
                            sleep(POLL_MS)
                        } catch (e: InterruptedException) {
                            return
                        }
                    }
                }
            }
        }
    }

    /** Reads lines from the live socket and writes outgoing commands. */
    private inner class ConnectedThread(private val socket: BluetoothSocket) : Thread("mdp-bt-io") {

        private val input: InputStream? = runCatching { socket.inputStream }.getOrNull()
        private val output: OutputStream? = runCatching { socket.outputStream }.getOrNull()

        @Volatile
        private var cancelled = false

        fun cancel() {
            cancelled = true
            runCatching { socket.close() }
            interrupt()
        }

        fun write(text: String): Boolean {
            val stream = output ?: return false
            return try {
                synchronized(stream) {
                    stream.write(text.toByteArray(Charsets.UTF_8))
                    stream.flush()
                }
                true
            } catch (e: IOException) {
                Log.w(TAG, "Write failed", e)
                emitEvent("Send failed - link dropped")
                cancel()
                false
            }
        }

        override fun run() {
            val stream = input ?: run {
                onConnectionLost(this)
                return
            }
            val buffer = ByteArray(1024)
            val pending = StringBuilder()
            try {
                while (!cancelled) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    pending.append(String(buffer, 0, read, Charsets.UTF_8))
                    // A sender that omits the newline still gets its message
                    // delivered once the stream goes quiet.
                    val quiet = runCatching { stream.available() == 0 }.getOrDefault(true)
                    drain(pending, flushRemainder = quiet)
                }
            } catch (e: IOException) {
                if (!cancelled) Log.d(TAG, "Read loop ended", e)
            }
            drain(pending, flushRemainder = true)
            onConnectionLost(this)
        }

        private fun drain(pending: StringBuilder, flushRemainder: Boolean) {
            var newline = pending.firstLineBreak()
            while (newline >= 0) {
                val line = pending.substring(0, newline)
                pending.delete(0, newline + 1)
                if (line.isNotBlank()) _incoming.tryEmit(line.trim())
                newline = pending.firstLineBreak()
            }
            if (flushRemainder && pending.isNotBlank()) {
                _incoming.tryEmit(pending.toString().trim())
                pending.setLength(0)
            }
        }

        private fun StringBuilder.firstLineBreak(): Int {
            for (i in indices) {
                if (this[i] in LINE_BREAKS) return i
            }
            return -1
        }
    }

    companion object {
        private const val TAG = "MdpBluetooth"
        private const val SERVICE_NAME = "MDP-ARCM"
        private const val RETRY_DELAY_MS = 3_000L
        private const val POLL_MS = 500L
        private val LINE_BREAKS = charArrayOf('\n', '\r')

        /** Well known Serial Port Profile UUID. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        @Volatile
        private var instance: BluetoothController? = null

        fun getInstance(context: Context): BluetoothController =
            instance ?: synchronized(this) {
                instance ?: BluetoothController(context).also { instance = it }
            }
    }
}
