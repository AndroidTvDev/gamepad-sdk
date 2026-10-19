package dev.gamepad.sdk

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * One connection, one decoder and one notification consumer.
 *
 * [unsolicitedMessages] is explicitly best-effort telemetry: it has no replay,
 * drops the oldest of 64 buffered messages for slow collectors, and keeps no
 * messages when nobody collects. Replies claimed by a request or mailbox never
 * enter this flow. Observe [termination] for loss of the connection.
 */
class DefaultProtocolSession(
    private val link: BleLink,
    private val decoder: MessageDecoder,
    parentScope: CoroutineScope,
) : ProtocolSession {
    private val stateLock = Any()
    private val operationMutex = Mutex()
    private val terminated = CompletableDeferred<Throwable?>()
    private val telemetry = MutableSharedFlow<ProtocolMessage>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private var closed = false
    private var closeCause: Throwable? = null
    private var pending: PendingReply? = null
    private var mailbox: Mailbox? = null
    private val sessionJob = SupervisorJob(parentScope.coroutineContext[kotlinx.coroutines.Job])
    private val sessionScope = CoroutineScope(parentScope.coroutineContext + sessionJob)

    override val limits: LinkLimits get() = link.limits
    override val termination: Deferred<Throwable?> get() = terminated
    override val unsolicitedMessages: Flow<ProtocolMessage> = telemetry.asSharedFlow()

    init {
        // Also covers construction with an already cancelled parent scope.
        sessionJob.invokeOnCompletion { cause -> terminate(cause) }
        sessionScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                terminate(link.termination.await())
            } catch (error: CancellationException) {
                terminate(error)
            } catch (error: Throwable) {
                terminate(error)
            }
        }
        // UNDISPATCHED installs the sole receiver before the factory returns.
        sessionScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                for (notification in link.notifications) {
                    val messages = try {
                        decoder.feed(notification)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        throw ProtocolException("Could not decode a device notification", error)
                    }
                    for (message in messages) route(message)
                }
                val cause = if (link.termination.isCompleted) {
                    link.termination.await()
                } else {
                    LinkClosedException("Device notification stream ended")
                }
                terminate(cause)
            } catch (error: CancellationException) {
                terminate(error)
            } catch (error: Throwable) {
                terminate(error)
            }
        }
    }

    override suspend fun <R> request(request: ProtocolRequest<R>): R {
        // Snapshot caller-owned mutable byte arrays before the first suspension.
        val frames = request.frames.map { it.snapshot() }
        return withTimeout(request.timeoutMillis) {
            operationMutex.withLock {
                val reply = TypedPendingReply(request.match)
                synchronized(stateLock) {
                    checkOpenLocked()
                    if (mailbox != null) throw BusyException()
                    check(pending == null)
                    pending = reply
                }
                var sendingMayHaveStarted = false
                try {
                    // The waiter is already registered, including when a fake
                    // or a fast peripheral replies before write() returns.
                    for (frame in frames) {
                        sendingMayHaveStarted = true
                        try {
                            whileOpen { link.write(frame) }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            terminate(error)
                            throw error
                        }
                    }
                    reply.result.await()
                } catch (error: CancellationException) {
                    // Do this BEFORE releasing operationMutex: otherwise a
                    // queued request could start on the ambiguous connection.
                    if (sendingMayHaveStarted) terminate(error)
                    throw error
                } finally {
                    synchronized(stateLock) {
                        if (pending === reply) pending = null
                    }
                    reply.result.cancel()
                }
            }
        }
    }

    override fun openMailbox(
        accepts: (ProtocolMessage) -> Boolean,
        capacity: Int,
    ): ProtocolMailbox {
        require(capacity > 0) { "Mailbox capacity must be positive and bounded" }
        return synchronized(stateLock) {
            checkOpenLocked()
            // Deliberately support one exclusive reliable route. Matchers do
            // not get to compete for a reply or inspect another request's ACK.
            if (pending != null || mailbox != null) throw BusyException()
            Mailbox(accepts, capacity).also { mailbox = it }
        }
    }

    override suspend fun write(frame: GattWrite) {
        val snapshot = frame.snapshot()
        operationMutex.withLock {
            checkOpen()
            runRawOperation { link.write(snapshot) }
        }
    }

    override suspend fun read(endpoint: GattEndpoint): ByteArray =
        operationMutex.withLock {
            checkOpen()
            runRawOperation { link.read(endpoint).copyOf() }
        }

    override fun close() {
        terminate(null)
    }

    private suspend fun <T> runRawOperation(block: suspend () -> T): T {
        try {
            return whileOpen(block)
        } catch (error: Throwable) {
            // An interrupted GATT operation can have a callback still in
            // flight. Conservatively retire this connection, never retry it.
            terminate(error)
            throw error
        }
    }

    /** Stop a suspended read/write promptly even when its caller owns another scope. */
    private suspend fun <T> whileOpen(block: suspend () -> T): T = coroutineScope {
        checkOpen()
        val operation = async(start = CoroutineStart.UNDISPATCHED) {
            checkOpen()
            block()
        }
        try {
            select {
                terminated.onAwait { cause -> throw operationFailure(cause) }
                operation.onAwait { value ->
                    checkOpen()
                    value
                }
            }
        } finally {
            operation.cancel()
        }
    }

    private fun route(message: ProtocolMessage) {
        val routes = synchronized(stateLock) {
            if (closed) return
            pending to mailbox
        }
        val waiting = routes.first
        if (waiting != null) {
            // Vendor callbacks execute outside the state lock, so close()
            // cannot be blocked by a matcher or a decoder.
            val delivery = waiting.match(message)
            if (delivery != null) {
                val stillOwned = synchronized(stateLock) { !closed && pending === waiting }
                // Completing a Deferred can resume an unconfined continuation
                // inline, so never do it while holding the session state lock.
                if (stillOwned) delivery()
                return
            }
        }
        val window = routes.second
        if (window != null && window.accepts(message)) {
            val stillOwned = synchronized(stateLock) { !closed && mailbox === window }
            if (!stillOwned) return
            val failed = window.channel.trySend(message).isFailure
            // A mailbox closed concurrently is not a queue overflow.
            val overflow = failed && synchronized(stateLock) { !closed && mailbox === window }
            if (overflow) {
                terminate(BufferOverflowException("Protocol mailbox capacity exceeded"))
            }
            return
        }
        // Only unmatched, non-critical messages may use this lossy stream.
        if (synchronized(stateLock) { !closed }) telemetry.tryEmit(message)
    }

    private fun terminate(cause: Throwable?) {
        val owned = synchronized(stateLock) {
            if (closed) return
            closed = true
            closeCause = cause
            val captured = pending to mailbox
            pending = null
            mailbox = null
            captured
        }
        val failure = operationFailure(cause)
        // These signals do not depend on collectors or on the pump completing.
        owned.first?.fail(failure)
        owned.second?.channel?.close(failure)
        terminated.complete(cause)
        try {
            // BleLink.close is contractually non-blocking and idempotent.
            link.close()
        } finally {
            sessionScope.cancel(
                CancellationException("Protocol session terminated").also {
                    if (cause != null) it.initCause(cause)
                },
            )
        }
    }

    private fun checkOpen() = synchronized(stateLock) { checkOpenLocked() }

    private fun checkOpenLocked() {
        if (closed) throw LinkClosedException("Protocol session is closed", closeCause)
    }

    private fun operationFailure(cause: Throwable?): Throwable = when (cause) {
        null -> LinkClosedException("Protocol session was closed")
        is CancellationException -> LinkClosedException("Protocol session was invalidated", cause)
        else -> cause
    }

    private fun GattWrite.snapshot(): GattWrite = copy(value = value.copyOf())

    private interface PendingReply {
        fun match(message: ProtocolMessage): (() -> Unit)?
        fun fail(error: Throwable)
    }

    private class TypedPendingReply<R>(
        private val matcher: (ProtocolMessage) -> ReplyMatch<R>,
    ) : PendingReply {
        val result = CompletableDeferred<R>()

        override fun match(message: ProtocolMessage): (() -> Unit)? =
            when (val reply = matcher(message)) {
                ReplyMatch.Ignore -> null
                is ReplyMatch.Accept -> ({ result.complete(reply.value); Unit })
                is ReplyMatch.Reject -> ({ result.completeExceptionally(reply.error); Unit })
            }

        override fun fail(error: Throwable) {
            result.completeExceptionally(error)
        }
    }

    private inner class Mailbox(
        val accepts: (ProtocolMessage) -> Boolean,
        capacity: Int,
    ) : ProtocolMailbox {
        val channel = Channel<ProtocolMessage>(capacity)
        override val messages: ReceiveChannel<ProtocolMessage> get() = channel

        override fun close() {
            synchronized(stateLock) {
                if (mailbox === this) mailbox = null
            }
            channel.close()
        }
    }
}

class DefaultProtocolSessionFactory(
    private val parentScope: CoroutineScope,
) : ProtocolSessionFactory {
    override fun create(link: BleLink, decoder: MessageDecoder): ProtocolSession =
        DefaultProtocolSession(link, decoder, parentScope)
}
