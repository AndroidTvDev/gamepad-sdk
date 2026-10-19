@file:Suppress("MissingPermission")

package dev.gamepad.sdk.android

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import dev.gamepad.sdk.Advertisement
import dev.gamepad.sdk.BleScanner
import dev.gamepad.sdk.BufferOverflowException
import dev.gamepad.sdk.ConnectionTarget
import dev.gamepad.sdk.GamepadException
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn

class BleScanException(val errorCode: Int) : GamepadException("BLE scan failed (errorCode=$errorCode)")

data class AndroidBleScanConfig(
    val filters: List<ScanFilter> = emptyList(),
    val scanMode: Int = ScanSettings.SCAN_MODE_LOW_LATENCY,
    val bufferCapacity: Int = 128,
    val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    init {
        require(bufferCapacity > 0)
        require(scanMode in setOf(ScanSettings.SCAN_MODE_LOW_POWER, ScanSettings.SCAN_MODE_BALANCED, ScanSettings.SCAN_MODE_LOW_LATENCY))
    }
}

/** A collector owns only its own ScanCallback; cancelling it cannot stop other scans. */
class AndroidBleScanner(
    context: Context,
    config: AndroidBleScanConfig = AndroidBleScanConfig(),
) : BleScanner {
    private val appContext = context.applicationContext
    private val config = config.copy(filters = config.filters.toList())

    override fun scan(): Flow<Advertisement> = callbackFlow {
        AndroidBlePermissions.requireScan(appContext)
        val adapter = AndroidBlePermissions.enabledAdapter(appContext)
        val scanner = adapter.bluetoothLeScanner
            ?: throw BluetoothUnavailableException("Bluetooth LE scanner is unavailable")
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = publish(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::publish)
            override fun onScanFailed(errorCode: Int) { close(BleScanException(errorCode)) }

            private fun publish(result: ScanResult) {
                try {
                    val record = result.scanRecord
                    val manufacturers = linkedMapOf<Int, ByteArray>()
                    record?.manufacturerSpecificData?.let { data ->
                        for (index in 0 until data.size()) {
                            manufacturers[data.keyAt(index)] = data.valueAt(index).copyOf()
                        }
                    }
                    val services = linkedMapOf<UUID, ByteArray>()
                    record?.serviceData?.forEach { (uuid, value) -> services[uuid.uuid] = value.copyOf() }
                    val advertisement = Advertisement(
                        target = ConnectionTarget(result.device.address),
                        name = record?.deviceName,
                        serviceUuids = record?.serviceUuids?.mapTo(mutableSetOf()) { it.uuid }.orEmpty(),
                        manufacturerData = manufacturers,
                        serviceData = services,
                    )
                    val sent = trySend(advertisement)
                    if (sent.isFailure && !sent.isClosed) {
                        close(BufferOverflowException("BLE scan buffer overflow; narrow scan filters or consume faster"))
                    }
                } catch (error: Exception) {
                    close(error)
                }
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED &&
                    intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) != BluetoothAdapter.STATE_ON
                ) close(BluetoothUnavailableException("Bluetooth was disabled during scanning"))
            }
        }
        var registered = false
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), Context.RECEIVER_EXPORTED)
            } else {
                appContext.registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
            }
            registered = true
            val settings = ScanSettings.Builder().setScanMode(config.scanMode).build()
            scanner.startScan(config.filters.toList(), settings, callback)
            awaitClose { }
        } finally {
            // Stop only this callback, including partial-start or cancellation.
            // Permission revocation must not prevent receiver unregistration.
            runCatching { scanner.stopScan(callback) }
            if (registered) runCatching { appContext.unregisterReceiver(receiver) }
        }
    }.buffer(config.bufferCapacity).flowOn(config.dispatcher)
}
