package dev.gamepad.sdk.android

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import dev.gamepad.sdk.GamepadException

class BlePermissionException(val missingPermissions: List<String>) :
    GamepadException("Missing Bluetooth permissions: ${missingPermissions.joinToString()}")

class BluetoothUnavailableException(message: String) : GamepadException(message)

/** Checks only: the host Activity owns permission requests and enable-Bluetooth UI. */
object AndroidBlePermissions {
    fun scanPermissions(): List<String> = if (Build.VERSION.SDK_INT >= 31) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun connectPermissions(): List<String> = if (Build.VERSION.SDK_INT >= 31) {
        listOf(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.BLUETOOTH)
    }

    fun missing(context: Context, permissions: List<String>): List<String> =
        permissions.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    fun requireScan(context: Context) {
        requireGranted(context, scanPermissions())
        // Android 6–11 normally require the location switch as well as permission.
        if (Build.VERSION.SDK_INT < 31) {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val enabled = manager != null && if (Build.VERSION.SDK_INT >= 28) {
                manager.isLocationEnabled
            } else {
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
            if (!enabled) throw BluetoothUnavailableException("Enable location services before BLE scanning on Android 6–11")
        }
    }

    fun requireConnect(context: Context) = requireGranted(context, connectPermissions())

    private fun requireGranted(context: Context, permissions: List<String>) {
        val missing = missing(context, permissions)
        if (missing.isNotEmpty()) throw BlePermissionException(missing)
    }

    internal fun enabledAdapter(context: Context): BluetoothAdapter {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: throw BluetoothUnavailableException("Bluetooth LE is unavailable on this device")
        if (!adapter.isEnabled) throw BluetoothUnavailableException("Bluetooth is disabled")
        return adapter
    }
}
