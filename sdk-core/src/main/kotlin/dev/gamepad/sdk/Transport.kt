package dev.gamepad.sdk

import java.util.UUID
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow

data class ConnectionTarget(val address: String)

data class Advertisement(
    val target: ConnectionTarget,
    val name: String?,
    val serviceUuids: Set<UUID>,
    val manufacturerData: Map<Int, ByteArray>,
    val serviceData: Map<UUID, ByteArray>,
)

data class GattEndpoint(
    val serviceUuid: UUID,
    val characteristicUuid: UUID,
    // Zero-based occurrence selectors within the discovered UUID matches.
    // Not BluetoothGattService/Characteristic attribute handles.
    val serviceInstance: Int = 0,
    val characteristicInstance: Int = 0,
)

enum class WriteMode { WITH_RESPONSE, WITHOUT_RESPONSE }
enum class SubscriptionMode { NOTIFY, INDICATE }

data class Subscription(
    val endpoint: GattEndpoint,
    val mode: SubscriptionMode,
)

data class BleProfile(
    val requiredEndpoints: Set<GattEndpoint>,
    val subscriptions: List<Subscription>,
    val preferredMtu: Int = 247,
    val requireBond: Boolean = false,
)

data class GattWrite(
    val endpoint: GattEndpoint,
    val value: ByteArray,
    val mode: WriteMode,
)

data class GattNotification(val endpoint: GattEndpoint, val value: ByteArray)

data class LinkLimits(
    val negotiatedMtu: Int,
    // Must respect negotiated ATT limits, characteristic/platform limits,
    // and the actual write operation. This is not the OTA payload size.
    val maxWriteValueBytes: Map<GattEndpoint, Int>,
)

interface BleScanner {
    // Cold scan session; collection cancellation stops the owned scan.
    // Implementations must not block the caller thread.
    fun scan(): Flow<Advertisement>
}

interface BleTransport {
    /**
     * Return only after connection, service/property validation, notification
     * callback installation and successful CCCD writes. Return actual MTU.
     * Buffer early notifications; never silently drop control/ACK traffic.
     * On cancellation/failure, release the partially opened connection.
     */
    suspend fun open(target: ConnectionTarget, profile: BleProfile): BleLink
}

interface BleLink {
    val limits: LinkLimits

    // Completes exactly once, even on idle disconnect: null for local close,
    // a cause for link failure. Complete with a value; do not cancel this signal.
    val termination: Deferred<Throwable?>

    /**
     * Exactly one consumer: ProtocolSession. Close with a cause on link loss.
     * Use a bounded ingress queue and fail the link on overflow. Callback
     * bytes must be copied before enqueueing; old-GATT callbacks are ignored.
     */
    val notifications: ReceiveChannel<GattNotification>

    // Read/write/CCCD/MTU operations share the same GATT operation queue.
    // A completed write is NOT a firmware/application ACK.
    // Do not automatically split protocol frames or retry commands.
    suspend fun write(frame: GattWrite)
    suspend fun read(endpoint: GattEndpoint): ByteArray

    /**
     * Idempotently invalidate this generation, fail pending work, detach
     * callbacks and release resources. Must be bounded and non-blocking.
     */
    fun close()
}
