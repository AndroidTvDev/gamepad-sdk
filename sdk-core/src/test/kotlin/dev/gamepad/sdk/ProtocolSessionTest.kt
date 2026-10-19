package dev.gamepad.sdk

import java.io.IOException
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield

@OptIn(ExperimentalCoroutinesApi::class)
class ProtocolSessionTest {
    @Test
    fun `reply delivered before write returns is not lost`() = runTest {
        val link = FakeLink()
        val session = DefaultProtocolSession(link, ByteDecoder(), backgroundScope)
        val allowWriteReturn = CompletableDeferred<Unit>()
        link.onWrite = {
            link.emit(7)
            allowWriteReturn.await()
        }
        val result = async { session.request(request(7)) }
        runCurrent()
        assertFalse(result.isCompleted, "A transport write still needs to finish")
        allowWriteReturn.complete(Unit)
        assertEquals(7, result.await())
        assertFalse(session.termination.isCompleted)
        session.close()
    }

    @Test
    fun `only one request sends at a time`() = runTest {
        val link = FakeLink()
        val session = DefaultProtocolSession(link, ByteDecoder(), backgroundScope)
        val first = async { session.request(request(1)) }
        runCurrent()
        val second = async { session.request(request(2)) }
        runCurrent()
        assertEquals(listOf(1), link.writes.map { it.value[0].toInt() })
        link.emit(1)
        assertEquals(1, first.await())
        runCurrent()
        assertEquals(listOf(1, 2), link.writes.map { it.value[0].toInt() })
        link.emit(2)
        assertEquals(2, second.await())
        session.close()
    }

    @Test
    fun `timeout while queued does not retire another request's connection`() = runTest {
        val link = FakeLink()
        val session = DefaultProtocolSession(link, ByteDecoder(), backgroundScope)
        val first = async { session.request(request(1, timeoutMillis = 1_000)) }
        runCurrent()
        val second = async {
            runCatching { session.request(request(2, timeoutMillis = 10)) }
        }
        runCurrent()
        advanceTimeBy(10)
        runCurrent()
        assertIs<TimeoutCancellationException>(second.await().exceptionOrNull())
        assertFalse(session.termination.isCompleted)
        assertEquals(1, link.writes.size)
        link.emit(1)
        assertEquals(1, first.await())
        session.close()
    }

    @Test
    fun `deadline includes a suspended write and invalidates before next request`() = runTest {
        val link = FakeLink()
        val session = DefaultProtocolSession(link, ByteDecoder(), backgroundScope)
        link.onWrite = { CompletableDeferred<Unit>().await() }
        val first = async {
            runCatching { session.request(request(1, timeoutMillis = 10)) }
        }
        runCurrent()
        val second = async {
            runCatching { session.request(request(2, timeoutMillis = 100)) }
        }
        runCurrent()
        advanceTimeBy(10)
        runCurrent()
        assertIs<TimeoutCancellationException>(first.await().exceptionOrNull())
        assertIs<LinkClosedException>(second.await().exceptionOrNull())
        assertTrue(session.termination.isCompleted)
        assertEquals(1, link.writes.size)
        assertEquals(1, link.closeCalls)
    }

    @Test
    fun `caller cancellation after sending closes session and preserves cancellation`() = runTest {
        val link = FakeLink()
        val session = DefaultProtocolSession(link, ByteDecoder(), backgroundScope)
        val result = async { session.request(request(1)) }
        runCurrent()
        result.cancelAndJoin()
        assertFailsWith<CancellationException> { result.await() }
        assertTrue(session.termination.isCompleted)
        assertEquals(1, link.closeCalls)
        assertFailsWith<LinkClosedException> { session.request(request(2)) }
    }

    @Test
    fun `NACK fails its request without destroying a synchronized connection`() = runTest {
        val link = FakeLink()
        val session = DefaultProtocolSession(link, ByteDecoder(), backgroundScope)
        val rejection = ProtocolException("Device refused the command")
        link.onWrite = { link.emit(9) }
        val error = assertFailsWith<ProtocolException> {
            session.request(
                ProtocolRequest<Int>(listOf(frame(9))) {
                    if (it == ByteReply(9)) ReplyMatch.Reject(rejection) else ReplyMatch.Ignore
                },
            )
        }
        assertSame(rejection, error)
        assertFalse(session.termination.isCompleted)
        session.close()
    }

    @Test
    fun `mailbox receives early window ACKs without collecting telemetry`() = runTest {
        val link = FakeLink()
        val session = DefaultProtocolSession(link, ByteDecoder(), backgroundScope)
        val mailbox = session.openMailbox({ it is ByteReply }, capacity = 4)
        link.onWrite = { link.emit(it.value[0].toInt()) }
        session.write(frame(1))
        session.write(frame(2))
        assertEquals(ByteReply(1), mailbox.messages.receive())
        assertEquals(ByteReply(2), mailbox.messages.receive())
        assertFailsWith<BusyException> { session.request(request(3)) }
        assertFailsWith<BusyException> { session.openMailbox({ true }, 1) }
        mailbox.close()
        assertEquals(3, session.request(request(3)))
        session.close()
    }

    @Test
    fun `request blocks synchronous mailbox registration`() = runTest {
        val link = FakeLink()
        val session = DefaultProtocolSession(link, ByteDecoder(), backgroundScope)
        val request = async { session.request(request(5)) }
        runCurrent()
        assertFailsWith<BusyException> { session.openMailbox({ true }, 1) }
        link.emit(5)
        assertEquals(5, request.await())
        session.close()
    }

    @Test
    fun `mailbox overflow explicitly fails the connection`() = runTest {
        val link = FakeLink()
        val session = DefaultProtocolSession(link, ByteDecoder(), backgroundScope)
        val mailbox = session.openMailbox({ true }, capacity = 1)
        link.emit(1)
        link.emit(2)
        runCurrent()
        assertIs<BufferOverflowException>(session.termination.await())
        assertEquals(1, link.closeCalls)
        assertEquals(ByteReply(1), mailbox.messages.receive())
        assertFailsWith<BufferOverflowException> { mailbox.messages.receive() }
    }

    @Test
    fun `idle disconnect is visible without a message flow collector`() = runTest {
        val link = FakeLink()
        val session = DefaultProtocolSessionFactory(backgroundScope).create(link, ByteDecoder())
        val cause = IOException("radio disconnected")
        link.fail(cause)
        assertSame(cause, session.termination.await())
        assertFailsWith<LinkClosedException> { session.write(frame(1)) }
        assertFailsWith<LinkClosedException> { session.read(ENDPOINT) }
        assertTrue(link.writes.isEmpty())
        assertEquals(0, link.readCalls)
    }

    @Test
    fun `disconnect fails a pending request while its write is still suspended`() = runTest {
        val link = FakeLink()
        val session = DefaultProtocolSession(link, ByteDecoder(), backgroundScope)
        link.onWrite = { CompletableDeferred<Unit>().await() }
        val request = async { runCatching { session.request(request(1)) } }
        runCurrent()
        val cause = IOException("link lost during write")
        link.fail(cause)
        runCurrent()
        assertSame(cause, request.await().exceptionOrNull())
        assertSame(cause, session.termination.await())
    }

    @Test
    fun `decoder failure fails the waiter and releases the link`() = runTest {
        val link = FakeLink()
        val decoder = object : MessageDecoder {
            override fun feed(notification: GattNotification): List<ProtocolMessage> =
                error("invalid vendor frame")
        }
        val session = DefaultProtocolSession(link, decoder, backgroundScope)
        val request = async { runCatching { session.request(request(1)) } }
        runCurrent()
        link.emit(1)
        assertIs<ProtocolException>(request.await().exceptionOrNull())
        assertIs<ProtocolException>(session.termination.await())
        assertEquals(1, link.closeCalls)
    }

    @Test
    fun `parent cancellation closes link without requiring explicit close`() = runTest {
        val owner = SupervisorJob(coroutineContext[Job])
        val parent = CoroutineScope(coroutineContext + owner)
        val link = FakeLink()
        val session = DefaultProtocolSession(link, ByteDecoder(), parent)
        owner.cancelAndJoin()
        assertTrue(session.termination.isCompleted)
        assertIs<CancellationException>(session.termination.await())
        assertEquals(1, link.closeCalls)
        session.close()
        assertEquals(1, link.closeCalls)
    }

    @Test
    fun `explicit close is idempotent and closes mailbox`() = runTest {
        val link = FakeLink()
        val session = DefaultProtocolSession(link, ByteDecoder(), backgroundScope)
        val mailbox = session.openMailbox({ true }, 1)
        session.close()
        session.close()
        assertEquals(null, session.termination.await())
        assertEquals(1, link.closeCalls)
        assertFailsWith<LinkClosedException> { mailbox.messages.receive() }
        assertFailsWith<LinkClosedException> { session.openMailbox({ true }, 1) }
    }

    @Test
    fun `request snapshots mutable writes before suspending in queue`() = runTest {
        val link = FakeLink()
        val session = DefaultProtocolSession(link, ByteDecoder(), backgroundScope)
        val first = async { session.request(request(1)) }
        runCurrent()
        val bytes = byteArrayOf(2)
        val second = async {
            session.request(
                ProtocolRequest(listOf(GattWrite(ENDPOINT, bytes, WriteMode.WITH_RESPONSE))) {
                    if (it == ByteReply(2)) ReplyMatch.Accept(2) else ReplyMatch.Ignore
                },
            )
        }
        runCurrent()
        bytes[0] = 99
        link.emit(1)
        first.await()
        runCurrent()
        assertEquals(2, link.writes[1].value[0].toInt())
        link.emit(2)
        assertEquals(2, second.await())
        session.close()
    }

    private fun request(id: Int, timeoutMillis: Long = 5_000) = ProtocolRequest(
        frames = listOf(frame(id)),
        timeoutMillis = timeoutMillis,
    ) { message ->
        if (message == ByteReply(id)) ReplyMatch.Accept(id) else ReplyMatch.Ignore
    }

    private fun frame(id: Int) = GattWrite(ENDPOINT, byteArrayOf(id.toByte()), WriteMode.WITH_RESPONSE)

    private data class ByteReply(val id: Int) : ProtocolMessage

    private class ByteDecoder : MessageDecoder {
        override fun feed(notification: GattNotification): List<ProtocolMessage> =
            notification.value.map { ByteReply(it.toInt() and 0xff) }
    }

    private class FakeLink : BleLink {
        private val ended = CompletableDeferred<Throwable?>()
        private val incoming = Channel<GattNotification>(64)
        override val limits = LinkLimits(23, mapOf(ENDPOINT to 20))
        override val termination: Deferred<Throwable?> get() = ended
        override val notifications: ReceiveChannel<GattNotification> get() = incoming
        val writes = mutableListOf<GattWrite>()
        var closeCalls = 0
        var readCalls = 0
        var onWrite: suspend (GattWrite) -> Unit = {}

        override suspend fun write(frame: GattWrite) {
            writes += frame.copy(value = frame.value.copyOf())
            onWrite(frame)
        }

        override suspend fun read(endpoint: GattEndpoint): ByteArray {
            readCalls++
            return byteArrayOf(42)
        }

        suspend fun emit(id: Int) {
            incoming.send(GattNotification(ENDPOINT, byteArrayOf(id.toByte())))
            // Let the receive pump process the ACK before write returns.
            yield()
        }

        fun fail(error: Throwable) {
            ended.complete(error)
            incoming.close(error)
        }

        override fun close() {
            closeCalls++
            ended.complete(null)
            incoming.close()
        }
    }

    companion object {
        private val ENDPOINT = GattEndpoint(UUID(0, 1), UUID(0, 2))
    }
}
