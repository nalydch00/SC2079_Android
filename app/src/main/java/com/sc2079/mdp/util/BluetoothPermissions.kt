package com.sc2079.mdp.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * The runtime permissions the app needs, which changed shape in Android 12.
 *
 * Up to Android 11 scanning for nearby devices counts as a location request, so
 * `ACCESS_FINE_LOCATION` is required; from Android 12 the dedicated `BLUETOOTH_SCAN`
 * and `BLUETOOTH_CONNECT` permissions replace it.
 */
object BluetoothPermissions {

    val required: Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }

    fun allGranted(context: Context): Boolean = required.all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun missing(context: Context): Array<String> = required.filter { permission ->
        ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
    }.toTypedArray()
}
