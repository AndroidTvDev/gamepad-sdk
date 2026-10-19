@file:Suppress("MissingPermission", "DEPRECATION")

package dev.gamepad.sdk.android

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import dev.gamepad.sdk.BleLink
import dev.gamepad.sdk.BleProfile
import dev.gamepad.sdk.BleTransport
import dev.gamepad.sdk.BufferOverflowException
import dev.gamepad.sdk.ConnectionTarget
import dev.gamepad.sdk.GamepadException
import dev.gamepad.sdk.GattEndpoint
import dev.gamepad.sdk.GattNotification
import dev.gamepad.sdk.GattWrite
import dev.gamepad.sdk.LinkClosedException
import dev.gamepad.sdk.LinkLimits
import dev.gamepad.sdk.SubscriptionMode
import dev.gamepad.sdk.WriteMode
import java.util.IdentityHashMap
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class AndroidBleConfig(
    val connectionTimeoutMillis: Long = 30_000,
    val operationTimeoutMillis: Long = 10_000,
    val bondTimeoutMillis: Long = 60_000,
    val notificationBufferCapacity: Int = 128,
    /** Vendor-documented limits smaller than the ATT/platform ceiling. */
    val characteristicWriteLimits: Map<GattEndpoint, Int> = emptyMap(),
    val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    init {
        require(connectionTimeoutMillis > 0 && operationTimeoutMillis > 0 && bondTimeoutMillis > 0)
        require(notificationBufferCapacity > 0)
        require(characteristicWriteLimits.values.all { it in 1..512 })
    }
}

class GattOperationException(val operation: String, val status: Int? = null) :
    GamepadException("GATT $operation failed${status?.let { " (status=$it)" }.orEmpty()}")

/** Each successful open transfers ownership of exactly one link to the caller. */
class AndroidBleTransport(
    context: Context,
    config: AndroidBleConfig = AndroidBleConfig(),
) : BleTransport {
    private val appContext = context.applicationContext
    private val config = config.copy(characteristicWriteLimits = config.characteristicWriteLimits.toMap())

    override suspend fun open(target: ConnectionTarget, profile: BleProfile): BleLink {
        var opening: AndroidGattLink? = null
        try {
            return withContext(config.ioDispatcher) {
                validate(profile)
                AndroidBlePermissions.requireConnect(appContext)
                val adapter = AndroidBlePermissions.enabledAdapter(appContext)
                require(android.bluetooth.BluetoothAdapter.checkBluetoothAddress(target.address)) {
                    "Invalid Bluetooth address"
                }
                val device = adapter.getRemoteDevice(target.address)
                if (profile.requireBond) ensureBonded(device)
                val link = AndroidGattLink(appContext, config)
                opening = link
                withTimeout(config.connectionTimeoutMillis) {
                    link.connect(device)
                    link.initialize(profile)
                }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                link
            }
        } catch (error: Throwable) {
            // Includes cancellation while withContext dispatches the successful
            // result back to the caller; resource-returning handoffs need this.
            opening?.failOpening(error)
            throw error
        }
    }

    private fun validate(profile: BleProfile) {
        require(profile.preferredMtu in 23..517)
        val endpoints = profile.requiredEndpoints + profile.subscriptions.map { it.endpoint }
        require(endpoints.all { it.serviceInstance >= 0 && it.characteristicInstance >= 0 })
        require(profile.subscriptions.map { it.endpoint }.distinct().size == profile.subscriptions.size) {
            "An endpoint can have only one subscription mode"
        }
    }

    private suspend fun ensureBonded(device: BluetoothDevice) {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return
        val complete = CompletableDeferred<Unit>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                try {
                    val changed = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (changed?.address != device.address) return
                    when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                        BluetoothDevice.BOND_BONDED -> complete.complete(Unit)
                        BluetoothDevice.BOND_NONE -> complete.completeExceptionally(
                            GattOperationException("bonding rejected or cancelled"),
                        )
                    }
                } catch (error: Exception) {
                    complete.completeExceptionally(error)
                }
            }
        }
        // Bluetooth broadcasts may originate in the privileged Bluetooth process.
        // Match the exact device and action; use EXPORTED to receive that process.
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), Context.RECEIVER_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        }
        try {
            withTimeout(config.bondTimeoutMillis) {
                when (device.bondState) {
                    BluetoothDevice.BOND_BONDED -> return@withTimeout
                    BluetoothDevice.BOND_BONDING -> Unit
                    else -> if (!device.createBond()) throw GattOperationException("start bonding")
                }
                complete.await()
            }
        } finally {
            runCatching { appContext.unregisterReceiver(receiver) }
            // Android exposes no public cancelBondProcess API. Cancellation stops
            // this wait; an already-started system pairing dialog remains system-owned.
        }
    }
}

private class AndroidGattLink(
    private val context: Context,
    private val config: AndroidBleConfig,
) : BleLink {
    private val guard = Any()
    private val operationMutex = Mutex()
    private var gatt: BluetoothGatt? = null
    private var closed = false
    private var pending: Pending? = null
    private val connected = CompletableDeferred<Unit>()
    private val ended = CompletableDeferred<Throwable?>()
    private val incoming = Channel<GattNotification>(config.notificationBufferCapacity)
    private val endpoints = linkedMapOf<GattEndpoint, BluetoothGattCharacteristic>()
    private val notificationEndpoints = IdentityHashMap<BluetoothGattCharacteristic, GattEndpoint>()
    private var mtu = 23

    override val termination: Deferred<Throwable?> get() = ended
    override val notifications: ReceiveChannel<GattNotification> get() = incoming
    override val limits: LinkLimits get() = synchronized(guard) {
        LinkLimits(mtu, endpoints.filterValues { it.isWritable() }.mapValues { (endpoint, _) ->
            minOf(mtu - 3, 512, config.characteristicWriteLimits[endpoint] ?: 512)
        })
    }

    suspend fun connect(device: BluetoothDevice) {
        synchronized(guard) {
            check(!closed)
            // Hold the guard through assignment: an immediate Binder callback cannot
            // arrive before the owned GATT identity has been installed.
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                ?: throw GattOperationException("connectGatt returned null")
        }
        connected.await()
    }

    suspend fun initialize(profile: BleProfile) {
        operation<Unit>(Kind.SERVICES) { it.discoverServices() }
        synchronized(guard) {
            val active = requireGatt()
            for (endpoint in profile.requiredEndpoints + profile.subscriptions.map { it.endpoint }) {
                val service = active.services.filter { it.uuid == endpoint.serviceUuid }
                    .getOrNull(endpoint.serviceInstance)
                    ?: throw GattOperationException("missing service $endpoint")
                val characteristic = service.characteristics.filter { it.uuid == endpoint.characteristicUuid }
                    .getOrNull(endpoint.characteristicInstance)
                    ?: throw GattOperationException("missing characteristic $endpoint")
                val usable = BluetoothGattCharacteristic.PROPERTY_READ or
                    BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                    BluetoothGattCharacteristic.PROPERTY_INDICATE
                if (characteristic.properties and usable == 0) {
                    throw GattOperationException("endpoint has no supported property: $endpoint")
                }
                endpoints[endpoint] = characteristic
            }
            for (subscription in profile.subscriptions) {
                val characteristic = endpoints.getValue(subscription.endpoint)
                val property = if (subscription.mode == SubscriptionMode.NOTIFY) {
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY
                } else BluetoothGattCharacteristic.PROPERTY_INDICATE
                if (characteristic.properties and property == 0) {
                    throw GattOperationException("subscription property missing: ${subscription.endpoint}")
                }
                if (characteristic.descriptors.count { it.uuid == CCCD_UUID } != 1) {
                    throw GattOperationException("missing or ambiguous CCCD: ${subscription.endpoint}")
                }
                notificationEndpoints[characteristic] = subscription.endpoint
            }
        }
        if (profile.preferredMtu > 23) {
            operation<Int>(Kind.MTU) { it.requestMtu(profile.preferredMtu) }
        }
        for (subscription in profile.subscriptions) {
            val characteristic = characteristic(subscription.endpoint)
            val descriptor = characteristic.getDescriptor(CCCD_UUID)
                ?: throw GattOperationException("missing CCCD")
            val value = if (subscription.mode == SubscriptionMode.NOTIFY) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE.copyOf()
            } else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE.copyOf()
            operation<Unit>(Kind.DESCRIPTOR, descriptor = descriptor) { active ->
                if (!active.setCharacteristicNotification(characteristic, true)) {
                    false
                } else if (Build.VERSION.SDK_INT >= 33) {
                    active.writeDescriptor(descriptor, value) == 0
                } else {
                    descriptor.value = value
                    active.writeDescriptor(descriptor)
                }
            }
        }
    }

    override suspend fun write(frame: GattWrite) = withContext(config.ioDispatcher) {
        val bytes = frame.value.copyOf()
        val characteristic = characteristic(frame.endpoint)
        val property = if (frame.mode == WriteMode.WITH_RESPONSE) {
            BluetoothGattCharacteristic.PROPERTY_WRITE
        } else BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        require(characteristic.properties and property != 0) { "Write mode not supported by ${frame.endpoint}" }
        require(bytes.size <= limits.maxWriteValueBytes.getValue(frame.endpoint)) {
            "Frame exceeds negotiated write limit; protocol must construct its own chunks"
        }
        val type = if (frame.mode == WriteMode.WITH_RESPONSE) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        operation<Unit>(Kind.WRITE, characteristic = characteristic) { active ->
            if (Build.VERSION.SDK_INT >= 33) {
                active.writeCharacteristic(characteristic, bytes, type) == 0
            } else {
                characteristic.writeType = type
                characteristic.value = bytes
                active.writeCharacteristic(characteristic)
            }
        }
    }

    override suspend fun read(endpoint: GattEndpoint): ByteArray = withContext(config.ioDispatcher) {
        val characteristic = characteristic(endpoint)
        require(characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
            "Read not supported by $endpoint"
        }
        operation<ByteArray>(Kind.READ, characteristic = characteristic) { it.readCharacteristic(characteristic) }
    }

    private fun characteristic(endpoint: GattEndpoint): BluetoothGattCharacteristic = synchronized(guard) {
        requireGatt()
        endpoints[endpoint] ?: throw IllegalArgumentException("Endpoint was not declared in BleProfile: $endpoint")
    }

    /** Queue lock is held until the real platform callback completes. */
    private suspend fun <T> operation(
        kind: Kind,
        characteristic: BluetoothGattCharacteristic? = null,
        descriptor: BluetoothGattDescriptor? = null,
        start: (BluetoothGatt) -> Boolean,
    ): T {
        var mine: Pending? = null
        try {
            return withTimeout(config.operationTimeoutMillis) {
                operationMutex.withLock {
                    val work = Pending(kind, characteristic, descriptor)
                    try {
                        val (accepted, knownMtu) = synchronized(guard) {
                            val active = requireGatt()
                            check(pending == null)
                            pending = work
                            mine = work
                            start(active) to mtu
                        }
                        if (!accepted) {
                            if (kind == Kind.MTU) {
                                // No operation was enqueued. Complete outside guard:
                                // an Unconfined awaiter may resume inline here.
                                work.result.complete(knownMtu)
                            } else {
                                throw GattOperationException("start ${kind.name}")
                            }
                        }
                        @Suppress("UNCHECKED_CAST")
                        (work.result.await() as T)
                    } catch (error: Throwable) {
                        // Invalidate before releasing the operation mutex: another
                        // queued operation must not start between failure and close.
                        if (mine === work) terminate(error)
                        throw error
                    } finally {
                        synchronized(guard) { if (pending === work) pending = null }
                    }
                }
            }
        } catch (error: Throwable) {
            // A queued caller that never started cannot invalidate somebody else's
            // operation. Once submitted, timeout/cancel makes callback identity unsafe.
            if (mine != null) terminate(error)
            throw error
        }
    }

    override fun close() = terminate(null)
    fun failOpening(error: Throwable) = terminate(error)

    private fun terminate(cause: Throwable?) {
        val old: BluetoothGatt?
        val outstanding: Pending?
        synchronized(guard) {
            if (closed) return
            closed = true
            old = gatt
            gatt = null
            outstanding = pending
            pending = null
            notificationEndpoints.clear()
        }
        // Release the platform handle before publishing signals; an arbitrary
        // Unconfined observer cannot postpone hardware cleanup. No callback wait.
        runCatching { old?.disconnect() }
        runCatching { old?.close() }
        // Deferred/Channel completions may execute Unconfined consumers inline.
        // Publish the authoritative reason before waking the receive pump, and
        // never invoke those consumers while holding our state guard.
        ended.complete(cause)
        val failure = cause ?: LinkClosedException()
        outstanding?.result?.completeExceptionally(failure)
        connected.completeExceptionally(failure)
        incoming.close(cause)
    }

    private fun requireGatt(): BluetoothGatt =
        gatt?.takeUnless { closed } ?: throw LinkClosedException()

    private fun accepts(candidate: BluetoothGatt): Boolean = !closed && gatt === candidate

    private fun complete(
        candidate: BluetoothGatt,
        kind: Kind,
        status: Int,
        value: Any? = Unit,
        characteristic: BluetoothGattCharacteristic? = null,
        descriptor: BluetoothGattDescriptor? = null,
    ) {
        val result = synchronized(guard) {
            if (!accepts(candidate)) return
            val work = pending ?: return
            if (work.kind != kind || work.characteristic !== characteristic || work.descriptor !== descriptor) {
                return
            }
            work.result
        }
        if (status == BluetoothGatt.GATT_SUCCESS) result.complete(value)
        else result.completeExceptionally(GattOperationException(kind.name, status))
    }

    private fun changed(candidate: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        val notification = synchronized(guard) {
            if (!accepts(candidate)) return
            val endpoint = notificationEndpoints[characteristic] ?: return
            GattNotification(endpoint, value.copyOf())
        }
        // A close racing this already-captured notification is authoritative.
        // The pump observes termination before channel closure; a closed channel
        // is normal teardown and must never be misreported as queue overflow.
        if (ended.isCompleted) return
        val sent = incoming.trySend(notification)
        if (sent.isFailure && !sent.isClosed) {
            val stillActive = synchronized(guard) { accepts(candidate) }
            if (stillActive) terminate(BufferOverflowException("BLE notification ingress overflow"))
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            synchronized(guard) { if (!accepts(gatt)) return }
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                terminate(LinkClosedException("Bluetooth disconnected (status=$status, state=$newState)"))
            } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected.complete(Unit)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) =
            complete(gatt, Kind.SERVICES, status)

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val knownMtu = synchronized(guard) {
                if (!accepts(gatt)) return
                if (status == BluetoothGatt.GATT_SUCCESS && mtu in 23..517) this@AndroidGattLink.mtu = mtu
                this@AndroidGattLink.mtu
            }
            // MTU negotiation failure leaves the last known negotiated MTU.
            complete(gatt, Kind.MTU, BluetoothGatt.GATT_SUCCESS, knownMtu)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) =
            complete(gatt, Kind.WRITE, status, characteristic = characteristic)

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) =
            complete(gatt, Kind.DESCRIPTOR, status, descriptor = descriptor)

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) {
                complete(gatt, Kind.READ, status, characteristic.value?.copyOf() ?: byteArrayOf(), characteristic)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) = complete(gatt, Kind.READ, status, value.copyOf(), characteristic)

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) changed(gatt, characteristic, characteristic.value?.copyOf() ?: byteArrayOf())
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) = changed(gatt, characteristic, value)

        override fun onServiceChanged(gatt: BluetoothGatt) {
            synchronized(guard) { if (!accepts(gatt)) return }
            terminate(LinkClosedException("GATT services changed; reconnect and revalidate profile"))
        }
    }

    private enum class Kind { SERVICES, MTU, DESCRIPTOR, WRITE, READ }
    private class Pending(
        val kind: Kind,
        val characteristic: BluetoothGattCharacteristic?,
        val descriptor: BluetoothGattDescriptor?,
        val result: CompletableDeferred<Any?> = CompletableDeferred(),
    )

    private fun BluetoothGattCharacteristic.isWritable(): Boolean = properties and
        (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

    companion object {
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
