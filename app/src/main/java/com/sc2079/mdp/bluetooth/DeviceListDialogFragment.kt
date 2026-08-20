package com.sc2079.mdp.bluetooth

import android.annotation.SuppressLint
import android.app.Dialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import com.sc2079.mdp.R

/**
 * The device picker behind the Connect button (checklist C.2).
 *
 * Paired devices are listed immediately and a scan appends anything else that is
 * discoverable nearby. Picking a row hands the device back to the host activity,
 * which starts the connection.
 */
@SuppressLint("MissingPermission")
class DeviceListDialogFragment : DialogFragment() {

    /** Implemented by the hosting activity. */
    fun interface DeviceChosenListener {
        fun onDeviceChosen(device: BluetoothDevice)
    }

    private var listener: DeviceChosenListener? = null
    private val devices = mutableListOf<BluetoothDevice>()
    private lateinit var adapter: DeviceAdapter
    private lateinit var controller: BluetoothController
    private var progress: ProgressBar? = null
    private var scanButton: Button? = null
    private var emptyLabel: TextView? = null
    private var receiverRegistered = false

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    device?.let(::addDevice)
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> setScanning(true)
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> setScanning(false)
            }
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = context as? DeviceChosenListener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        controller = BluetoothController.getInstance(context)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_device_list, null, false)
        val list = view.findViewById<ListView>(R.id.device_list)
        progress = view.findViewById(R.id.scan_progress)
        scanButton = view.findViewById(R.id.scan_button)
        emptyLabel = view.findViewById(R.id.empty_label)

        adapter = DeviceAdapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            stopDiscovery()
            listener?.onDeviceChosen(devices[position])
            dismiss()
        }
        scanButton?.setOnClickListener { startDiscovery() }

        devices.addAll(controller.bondedDevices())
        adapter.notifyDataSetChanged()
        updateEmptyState()
        registerReceiver()

        return AlertDialog.Builder(context)
            .setTitle(R.string.select_device)
            .setView(view)
            .setNegativeButton(R.string.cancel) { _, _ -> dismiss() }
            .create()
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ContextCompat.registerReceiver(
            requireContext(),
            discoveryReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
        receiverRegistered = true
    }

    private fun startDiscovery() {
        val adapterService = (requireContext().getSystemService(Context.BLUETOOTH_SERVICE)
            as? android.bluetooth.BluetoothManager)?.adapter ?: return
        if (adapterService.isDiscovering) adapterService.cancelDiscovery()
        adapterService.startDiscovery()
        setScanning(true)
    }

    private fun stopDiscovery() {
        controller.cancelDiscovery()
        setScanning(false)
    }

    private fun setScanning(scanning: Boolean) {
        progress?.visibility = if (scanning) View.VISIBLE else View.GONE
        scanButton?.isEnabled = !scanning
        scanButton?.setText(if (scanning) R.string.scanning else R.string.scan)
    }

    private fun addDevice(device: BluetoothDevice) {
        if (devices.any { it.address == device.address }) return
        devices += device
        adapter.notifyDataSetChanged()
        updateEmptyState()
    }

    private fun updateEmptyState() {
        emptyLabel?.visibility = if (devices.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        stopDiscovery()
        if (receiverRegistered) {
            runCatching { requireContext().unregisterReceiver(discoveryReceiver) }
            receiverRegistered = false
        }
        super.onDestroyView()
    }

    override fun onDetach() {
        listener = null
        super.onDetach()
    }

    private inner class DeviceAdapter : BaseAdapter() {
        override fun getCount(): Int = devices.size

        override fun getItem(position: Int): BluetoothDevice = devices[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            val device = devices[position]
            view.findViewById<TextView>(android.R.id.text1).text =
                runCatching { device.name }.getOrNull() ?: getString(R.string.unnamed_device)
            view.findViewById<TextView>(android.R.id.text2).text = device.address
            return view
        }
    }

    companion object {
        const val TAG = "DeviceListDialog"
    }
}
