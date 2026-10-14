package com.sc2079.mdp.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import com.sc2079.mdp.bluetooth.BluetoothController

/**
 * Debug-build stand-in for the robot, driven from a laptop over USB:
 *
 *     adb shell am broadcast -n com.sc2079.mdp/.debug.SimulatorReceiver \
 *         --es cmd receive --es data <base64 of one JSON line>
 *
 * `cmd` is one of `connect` (optional `name`), `drop`, `disconnect` or
 * `receive`. The line is base64 encoded so adb's shell quoting can't mangle
 * the JSON. tools/simulator/sim.sh wraps all of this.
 */
class SimulatorReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val bluetooth = BluetoothController.getInstance(context)
        when (val cmd = intent.getStringExtra(EXTRA_CMD)) {
            "connect" -> bluetooth.simulateConnection(intent.getStringExtra(EXTRA_NAME) ?: DEFAULT_NAME)
            "drop" -> bluetooth.simulateLinkLoss()
            "disconnect" -> bluetooth.disconnect()
            "receive" -> {
                val line = decode(intent.getStringExtra(EXTRA_DATA)) ?: run {
                    Log.w(BluetoothController.SIMULATOR_TAG, "receive needs a base64 \"data\" extra")
                    return
                }
                Log.i(BluetoothController.SIMULATOR_TAG, "received: $line")
                bluetooth.simulateIncoming(line)
            }
            else -> Log.w(BluetoothController.SIMULATOR_TAG, "Unknown simulator cmd: $cmd")
        }
    }

    private fun decode(data: String?): String? =
        data?.let { runCatching { String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull() }

    private companion object {
        const val EXTRA_CMD = "cmd"
        const val EXTRA_NAME = "name"
        const val EXTRA_DATA = "data"
        const val DEFAULT_NAME = "MDP-Group6-RPi"
    }
}
