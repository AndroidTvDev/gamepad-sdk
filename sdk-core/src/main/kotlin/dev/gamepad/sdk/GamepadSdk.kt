package dev.gamepad.sdk

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class DiscoveredGamepad(val advertisement: Advertisement, val protocolIds: Set<String>)

/** Own this instance in a service or ViewModel. One newController() per managed device. */
class GamepadSdk(
    private val transport: BleTransport,
    private val scanner: BleScanner,
    adapters: Set<GamepadAdapterFactory>,
    parentScope: CoroutineScope,
) : AutoCloseable {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val registry = AdapterRegistry(adapters)
    private val guard = Any()
    private val closeSignal = CompletableDeferred<Unit>()
    private var closed = false
    private val controllers = mutableSetOf<GamepadRepository>()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { close() }
        }
    }

    fun discover(): Flow<DiscoveredGamepad> = flow {
        synchronized(guard) { if (closed || !job.isActive) throw SdkClosedException() }
        // This scan belongs to the collecting caller; cancellation stops it.
        coroutineScope {
            val watcher = launch {
                closeSignal.await()
                this@coroutineScope.cancel(CancellationException("SDK closed"))
            }
            try {
                scanner.scan().collect { advertisement ->
                    val matches = registry.candidates(advertisement)
                    if (matches.isNotEmpty()) emit(DiscoveredGamepad(advertisement, matches))
                }
            } finally { watcher.cancel() }
        }
    }

    fun newController(): GamepadRepository = synchronized(guard) {
        if (closed || !job.isActive) throw SdkClosedException()
        DefaultGamepadRepository(transport, scanner, registry, scope).also { controllers += it }
    }

    /** Release a closed controller early when an SDK manages many transient devices. */
    fun release(controller: GamepadRepository) {
        val owned = synchronized(guard) { controllers.remove(controller) }
        if (owned) controller.close()
    }

    override fun close() {
        val owned = synchronized(guard) {
            if (closed) return
            closed = true
            controllers.toList().also { controllers.clear() }
        }
        // A plugin that delays cancellation must not keep external scans alive.
        closeSignal.complete(Unit)
        owned.forEach { it.close() }
        job.cancel()
    }
}
