package dev.gamepad.sdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.Deferred

// Vendor reply/event types implement this interface; no universal opcode format.
interface ProtocolMessage

interface MessageDecoder {
    /**
     * Fresh instance per connection. Owns buffering/framing/checksum validation.
     * One notification can yield zero, one, or multiple protocol messages.
     * BLE itself is not a TCP byte stream; framing depends on vendor protocol.
     */
    fun feed(notification: GattNotification): List<ProtocolMessage>
}

sealed interface ReplyMatch<out R> {
    data object Ignore : ReplyMatch<Nothing>
    data class Accept<R>(val value: R) : ReplyMatch<R>
    data class Reject(val error: Exception) : ReplyMatch<Nothing>
}

data class ProtocolRequest<R>(
    val frames: List<GattWrite>,
    val timeoutMillis: Long = 5_000,
    // Vendor-specific association: opcode + transaction ID + offsets, etc.
    val match: (ProtocolMessage) -> ReplyMatch<R>,
) {
    init {
        require(frames.isNotEmpty())
        require(timeoutMillis > 0)
    }
}

interface ProtocolMailbox {
    // Reliable protocol-control delivery; not a telemetry stream.
    val messages: ReceiveChannel<ProtocolMessage>
    // Unregister immediately. Session close also terminates this mailbox.
    fun close()
}

interface ProtocolSession {
    val limits: LinkLimits

    // Repository observes this without competing for the receive pipeline.
    // Completes once on close/link loss/decoder failure; null = local close.
    val termination: Deferred<Throwable?>

    // Unsolicited messages only. Critical ACK routing must not depend on
    // whether UI/Repository collects this Flow. Define overflow explicitly.
    val unsolicitedMessages: Flow<ProtocolMessage>

    /**
     * Reference implementation requirements:
     * 1. One receive pump per connection; install waiter BEFORE writing.
     * 2. Default to one in-flight request; deadline includes queue + write + ACK.
     * 3. Route matching replies directly to the waiter, including vendor NACK.
     * 4. Close/fail all waiters on disconnect or receive/decoder failure.
     * 5. Never automatically retry; preserve CancellationException.
     * 6. After timeout/cancellation once sending may have started, invalidate
     *    the session unless the protocol explicitly provides safe resync.
     *    A mutex alone cannot distinguish a late reply without a transaction ID.
     */
    suspend fun <R> request(request: ProtocolRequest<R>): R

    /**
     * Synchronously register BEFORE sending a window of frames. A vendor OTA
     * plugin can own ACK/window/offset tracking without parallel request().
     * Bound the queue and fail explicitly on overflow; no silent drops.
     * Do not overlap this matcher with a request matcher in the same operation.
     * Route claimed control messages here instead of unsolicitedMessages.
     */
    fun openMailbox(
        accepts: (ProtocolMessage) -> Boolean,
        capacity: Int = 64,
    ): ProtocolMailbox

    // Raw write. Application replies must be handled by an already registered
    // mailbox, or use request(). A write alone does not wait for a vendor ACK.
    suspend fun write(frame: GattWrite)
    suspend fun read(endpoint: GattEndpoint): ByteArray
    fun close()
}

data class SessionSpec(
    val profile: BleProfile,
    val newDecoder: () -> MessageDecoder,
)

interface ProtocolSessionFactory {
    // Create receive pump BEFORE returning, including already buffered data.
    fun create(link: BleLink, decoder: MessageDecoder): ProtocolSession
}
