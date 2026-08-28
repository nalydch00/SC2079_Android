package com.sc2079.mdp.ui

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.sc2079.mdp.R
import com.sc2079.mdp.bluetooth.BluetoothController
import com.sc2079.mdp.bluetooth.ConnectionState
import com.sc2079.mdp.bluetooth.DeviceListDialogFragment
import com.sc2079.mdp.databinding.ActivityConnectionBinding
import com.sc2079.mdp.util.BluetoothPermissions
import com.sc2079.mdp.util.Prefs
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The launcher screen. Its only job is getting a Bluetooth link up (checklist
 * C.2, C.8) - no map, no movement controls, nothing else competing for
 * attention. The moment the link reaches [ConnectionState.CONNECTED] this
 * hands off to [ControlActivity] automatically.
 */
class ConnectionActivity : AppCompatActivity(), DeviceListDialogFragment.DeviceChosenListener {

    private lateinit var binding: ActivityConnectionBinding
    private lateinit var bluetooth: BluetoothController
    private lateinit var prefs: Prefs

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.all { it }) {
            showDevicePicker()
        } else {
            toast(getString(R.string.permissions_required))
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (bluetooth.isBluetoothEnabled) showDevicePicker()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConnectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bluetooth = BluetoothController.getInstance(this)
        prefs = Prefs(this)

        binding.connectButton.setOnClickListener { requestConnect() }
        binding.listenButton.setOnClickListener { reconnectToLastDevice() }

        observeState()
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { bluetooth.state.collectLatest(::renderConnectionState) }
                launch {
                    bluetooth.remoteName.collectLatest { name ->
                        binding.connectionDevice.text = name ?: getString(R.string.no_device)
                    }
                }
                launch { bluetooth.events.collect { toast(it) } }
            }
        }
    }

    private fun renderConnectionState(state: ConnectionState) {
        val (labelRes, colorRes) = when (state) {
            ConnectionState.CONNECTED -> R.string.state_connected to R.color.state_connected
            ConnectionState.CONNECTING -> R.string.state_connecting to R.color.state_connecting
            ConnectionState.RECONNECTING -> R.string.state_reconnecting to R.color.state_connecting
            ConnectionState.DISCONNECTED -> R.string.state_disconnected to R.color.state_disconnected
        }
        binding.connectionState.setText(labelRes)
        binding.connectionState.setTextColor(getColor(colorRes))

        if (state == ConnectionState.CONNECTED) {
            startActivity(Intent(this, ControlActivity::class.java))
            finish()
        }
    }

    private fun requestConnect() {
        if (!ensureReady()) return
        showDevicePicker()
    }

    /**
     * Checks adapter, permissions and radio state, launching whichever prompt is
     * missing. Returns true only when the app can talk to Bluetooth right now.
     */
    private fun ensureReady(): Boolean {
        if (!bluetooth.isBluetoothSupported) {
            toast(getString(R.string.bluetooth_unsupported))
            return false
        }
        if (!BluetoothPermissions.allGranted(this)) {
            permissionLauncher.launch(BluetoothPermissions.missing(this))
            return false
        }
        if (!bluetooth.isBluetoothEnabled) {
            toast(getString(R.string.enable_bluetooth))
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return false
        }
        return true
    }

    private fun showDevicePicker() {
        if (!ensureReady()) return
        if (supportFragmentManager.findFragmentByTag(DeviceListDialogFragment.TAG) != null) return
        DeviceListDialogFragment().show(supportFragmentManager, DeviceListDialogFragment.TAG)
    }

    /**
     * Checklist C.8: re-open the link to the device we used last. Falls back to
     * listening for an incoming connection when that device is no longer paired,
     * which is the case the AMD tool exercises when it reconnects on its own.
     */
    private fun reconnectToLastDevice() {
        if (!ensureReady()) return
        val address = prefs.lastDeviceAddress
        val device = address?.let { stored -> bluetooth.bondedDevices().firstOrNull { it.address == stored } }
        if (device != null) bluetooth.connect(device) else bluetooth.listenForIncoming()
    }

    override fun onDeviceChosen(device: BluetoothDevice) {
        prefs.lastDeviceAddress = device.address
        bluetooth.connect(device)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
