package dev.gamepad.sdk.demo

import dev.gamepad.sdk.*
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Pure JVM fake. No Android Bluetooth API and no real peripheral involved. */
class DemoBleTransport(
    val device: DemoDevice = DemoDevice(),
    private val negotiatedMtu: Int = 23,
    private val notificationCapacity: Int = 64,
) : BleTransport {
    init {
        require(negotiatedMtu in 23..517)
        require(notificationCapacity > 0)
    }

    private val active = AtomicReference<DemoBleLink?>(null)
    val activeLinkCount: Int get() = if (active.get() == null) 0 else 1

    override suspend fun open(target: ConnectionTarget, profile: BleProfile): BleLink {
        currentCoroutineContext().ensureActive()
        if (target != device.target) throw ProtocolException("Unknown fictional demo target")
        val supported = setOf(DemoProtocol.command, DemoProtocol.notification)
        if (!supported.containsAll(profile.requiredEndpoints) ||
            profile.subscriptions != listOf(Subscription(DemoProtocol.notification, SubscriptionMode.NOTIFY)) ||
            profile.requireBond
        ) {
            throw ProtocolException("Unsupported fictional demo GATT profile")
        }
        val mtu = minOf(negotiatedMtu, profile.preferredMtu.coerceAtLeast(23))
        val link = DemoBleLink(
            device,
            LinkLimits(mtu, mapOf(DemoProtocol.command to minOf(mtu - 3, 512))),
            notificationCapacity,
        ) { closed -> active.compareAndSet(closed, null) }
        if (!active.compareAndSet(null, link)) {
            link.close()
            throw ProtocolException("Fictional demo device is already connected")
        }
        try {
            currentCoroutineContext().ensureActive()
            return link
        } catch (error: Throwable) {
            link.close()
            throw error
        }
    }

    /** Test hook for an unsolicited remote disconnect, including while idle. */
    fun simulateLinkLoss(cause: Throwable = LinkClosedException("Simulated demo radio loss")) {
        active.get()?.fail(cause)
    }
}

/** Cold scan: cancelling collection stops it immediately. */
class DemoBleScanner(private val devices: List<DemoDevice>) : BleScanner {
    override fun scan(): Flow<Advertisement> = flow {
        devices.forEach { emit(it.advertisement()) }
        awaitCancellation()
    }
}

class DemoDevice(
    val target: ConnectionTarget = ConnectionTarget("demo://controller-1"),
    initialVersion: String = "1.0.0-demo",
) {
    private var currentInfo = DeviceInfo(DemoProtocol.MODEL, DemoProtocol.HARDWARE, initialVersion, "DEMO-SERIAL-001")
    private var currentLight = LightSetting(0, 0, 0)
    private var image = byteArrayOf()
    private var update: PendingUpdate? = null

    val info: DeviceInfo @Synchronized get() = currentInfo
    val light: LightSetting @Synchronized get() = currentLight
    val installedImage: ByteArray @Synchronized get() = image.copyOf()

    fun advertisement(): Advertisement = Advertisement(
        target = target,
        name = "Fictional Demo Gamepad",
        serviceUuids = setOf(DemoProtocol.serviceUuid),
        manufacturerData = emptyMap(),
        serviceData = mapOf(DemoProtocol.serviceUuid to DemoProtocol.greeting),
    )

    @Synchronized
    internal fun handle(request: DemoMessage): DemoMessage {
        val payload = request.payload
        fun reply(bytes: ByteArray = byteArrayOf()) = DemoMessage(
            request.transaction,
            DemoProtocol.responseOpcode(request.opcode),
            bytes,
        )
        fun nack(reason: String) = DemoMessage(request.transaction, DemoProtocol.NACK, reason.toByteArray())
        return when (request.opcode) {
            DemoProtocol.HELLO -> if (payload.contentEquals(DemoProtocol.greeting)) reply(DemoProtocol.greeting) else nack("bad greeting")
            DemoProtocol.INFO -> {
                if (payload.isNotEmpty()) return nack("info payload must be empty")
                reply(DemoCodec.strings(currentInfo.model, currentInfo.hardwareRevision.orEmpty(),
                    currentInfo.firmwareVersion.orEmpty(), currentInfo.serialNumber.orEmpty()))
            }
            DemoProtocol.LIGHT -> {
                if (payload.size != 3) return nack("light requires RGB")
                if (update != null) return nack("update active")
                currentLight = LightSetting(payload[0].toInt() and 0xff, payload[1].toInt() and 0xff, payload[2].toInt() and 0xff)
                reply()
            }
            DemoProtocol.OTA_START -> {
                if (payload.size < 38) return nack("invalid update header")
                val size = DemoCodec.u32(payload)
                val versionLength = payload[4].toInt() and 0xff
                if (size !in 1..DemoProtocol.MAX_FIRMWARE_BYTES.toLong() ||
                    versionLength !in 1..64 || payload.size != 5 + versionLength + 32
                ) return nack("invalid image metadata")
                val version = payload.copyOfRange(5, 5 + versionLength).toString(Charsets.UTF_8)
                val digest = payload.copyOfRange(5 + versionLength, payload.size)
                // This explicitly starts over. There is no implicit retry/resume.
                update = PendingUpdate(ByteArray(size.toInt()), version, digest)
                reply(DemoCodec.uint32(0))
            }
            DemoProtocol.OTA_CHUNK -> {
                val pending = update ?: return nack("no active update")
                if (payload.size <= 4) return nack("empty update chunk")
                val offset = DemoCodec.u32(payload)
                val count = payload.size - 4
                if (offset != pending.confirmed.toLong() || count > pending.bytes.size - pending.confirmed) {
                    return nack("unexpected update offset")
                }
                payload.copyInto(pending.bytes, pending.confirmed, 4)
                pending.confirmed += count
                reply(DemoCodec.uint32(pending.confirmed.toLong()))
            }
            DemoProtocol.OTA_FINISH -> {
                if (payload.isNotEmpty()) return nack("finish payload must be empty")
                val pending = update ?: return nack("no active update")
                if (pending.confirmed != pending.bytes.size) return nack("image incomplete")
                val digest = MessageDigest.getInstance("SHA-256").digest(pending.bytes)
                if (!MessageDigest.isEqual(digest, pending.digest)) return nack("image checksum mismatch")
                image = pending.bytes.copyOf()
                currentInfo = currentInfo.copy(firmwareVersion = pending.version)
                update = null
                reply()
            }
            else -> nack("unknown opcode")
        }
    }

    private class PendingUpdate(val bytes: ByteArray, val version: String, val digest: ByteArray) {
        var confirmed: Int = 0
    }
}

private class DemoBleLink(
    private val device: DemoDevice,
    override val limits: LinkLimits,
    notificationCapacity: Int,
    private val onClosed: (DemoBleLink) -> Unit,
) : BleLink {
    private val ended = AtomicBoolean(false)
    private val endedSignal = CompletableDeferred<Throwable?>()
    override val termination: Deferred<Throwable?> = endedSignal
    private val incoming = Channel<GattNotification>(notificationCapacity)
    override val notifications: ReceiveChannel<GattNotification> = incoming
    private val operations = Mutex()
    private val decoder = DemoDecoder(DemoProtocol.command)
    private var greeted = false

    override suspend fun write(frame: GattWrite) {
        // Copy before taking a suspending lock: caller does not own our bytes.
        val bytes = frame.value.copyOf()
        operations.withLock {
            currentCoroutineContext().ensureActive()
            requireOpen()
            if (frame.endpoint != DemoProtocol.command || frame.mode != WriteMode.WITH_RESPONSE) {
                throw ProtocolException("Unsupported demo write endpoint or mode")
            }
            val maxBytes = limits.maxWriteValueBytes.getValue(frame.endpoint)
            if (bytes.isEmpty() || bytes.size > maxBytes) throw ProtocolException("Demo GATT value exceeds negotiated limit")
            try {
                decoder.feed(GattNotification(frame.endpoint, bytes)).forEach { decoded ->
                    val message = decoded as DemoMessage
                    val response = if (!greeted && message.opcode != DemoProtocol.HELLO) {
                        DemoMessage(message.transaction, DemoProtocol.NACK, "handshake required".toByteArray())
                    } else {
                        device.handle(message).also { reply ->
                            if (message.opcode == DemoProtocol.HELLO && reply.opcode != DemoProtocol.NACK) greeted = true
                        }
                    }
                    publish(response)
                }
            } catch (error: Throwable) {
                fail(error)
                throw error
            }
            // All reply fragments are already buffered before write returns.
            // This exercises the session's register-before-write requirement.
        }
    }

    override suspend fun read(endpoint: GattEndpoint): ByteArray = operations.withLock {
        currentCoroutineContext().ensureActive()
        requireOpen()
        throw ProtocolException("Fictional demo uses command/notification; no GATT read endpoint")
    }

    private fun publish(message: DemoMessage) {
        val bytes = DemoCodec.encode(message.transaction, message.opcode, message.payload)
        val maxBytes = minOf(limits.negotiatedMtu - 3, 512)
        var offset = 0
        while (offset < bytes.size) {
            requireOpen()
            val end = minOf(offset + maxBytes, bytes.size)
            val fragment = GattNotification(DemoProtocol.notification, bytes.copyOfRange(offset, end))
            if (incoming.trySend(fragment).isFailure) {
                val error = BufferOverflowException("Demo notification ingress overflow")
                fail(error)
                throw error
            }
            offset = end
        }
    }

    private fun requireOpen() {
        if (ended.get()) throw LinkClosedException("Demo link is closed")
    }

    fun fail(cause: Throwable) = end(cause)
    override fun close() = end(null)

    private fun end(cause: Throwable?) {
        if (ended.compareAndSet(false, true)) {
            onClosed(this)
            // Publish the cause before closing ingress: an Unconfined receive
            // pump may observe channel completion synchronously inside close().
            endedSignal.complete(cause)
            incoming.close(cause)
        }
    }
}
