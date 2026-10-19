package dev.gamepad.sdk

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** One controller owns one physical device session. All vendor behavior is injected. */
class DefaultGamepadRepository(
    private val transport: BleTransport,
    private val scanner: BleScanner,
    private val registry: AdapterRegistry,
    parentScope: CoroutineScope,
    sessionFactory: ProtocolSessionFactory? = null,
    private val cleanupTimeoutMillis: Long = 5_000,
) : GamepadRepository {
    private val ownerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + ownerJob)
    private val sessions = sessionFactory ?: DefaultProtocolSessionFactory(scope)
    private val guard = Any()
    private var closed = false
    private var disconnecting = false
    private var serial = 0L
    private var generation = 0L
    private var active: Operation? = null
    private var connection: Connection? = null
    private var disconnectTask: Deferred<Unit>? = null

    private val mutableConnection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    private val mutableUpgrade = MutableStateFlow<UpgradeState>(UpgradeState.Idle)
    override val connectionState: StateFlow<ConnectionState> = mutableConnection.asStateFlow()
    override val upgradeState: StateFlow<UpgradeState> = mutableUpgrade.asStateFlow()

    private enum class Kind { CONNECT, COMMAND, UPGRADE }
    private class Operation(val id: Long, val kind: Kind) { lateinit var job: Deferred<*> }
    private class Connection(
        val generation: Long,
        val target: ConnectionTarget,
        val adapter: GamepadAdapter,
        val normalSpec: SessionSpec,
        val spec: SessionSpec,
        val session: ProtocolSession,
        var info: DeviceInfo?,
    )

    init {
        require(cleanupTimeoutMillis > 0)
        // Also handles an idle controller when its owning service/ViewModel scope ends.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { close() }
        }
    }

    override suspend fun connect(target: ConnectionTarget, protocolId: String) =
        perform(Kind.CONNECT) { operation ->
            val adapter = registry.create(protocolId)
            val normalSpec = adapter.normalSession
            val old = synchronized(guard) {
                ensureOperation(operation)
                val previous = connection
                connection = null
                generation++
                mutableConnection.value = ConnectionState.Connecting
                mutableUpgrade.value = UpgradeState.Idle
                previous
            }
            old?.session?.close()
            var link: BleLink? = null
            var session: ProtocolSession? = null
            try {
                link = transport.open(target, normalSpec.profile)
                currentCoroutineContext().ensureActive()
                session = sessions.create(link, normalSpec.newDecoder())
                val bound = synchronized(guard) {
                    ensureOperation(operation)
                    Connection(++generation, target, adapter, normalSpec, normalSpec, session, null).also {
                        connection = it
                        mutableConnection.value = ConnectionState.Initializing
                    }
                }
                observeTermination(bound)
                val info = adapter.initialize(session)
                synchronized(guard) {
                    ensureBound(operation, bound)
                    bound.info = info
                    mutableConnection.value = ConnectionState.Ready(info, adapter.capabilities)
                }
            } catch (error: Throwable) {
                session?.close() ?: link?.close()
                synchronized(guard) {
                    if (isCurrent(operation)) {
                        connection = null
                        generation++
                        mutableConnection.value = if (error is CancellationException) {
                            ConnectionState.Disconnected
                        } else ConnectionState.Failed(error.message ?: "Connect failed")
                    }
                }
                throw error
            }
        }

    override suspend fun readInfo(): DeviceInfo = perform(Kind.COMMAND) { operation ->
        val bound = readyConnection(operation)
        if (!bound.adapter.capabilities.readInfo) throw UnsupportedFeatureException("readInfo")
        val info = bound.adapter.readInfo(bound.session)
        synchronized(guard) {
            ensureBound(operation, bound)
            bound.info = info
            mutableConnection.value = ConnectionState.Ready(info, bound.adapter.capabilities)
        }
        info
    }

    override suspend fun setLight(setting: LightSetting): Unit = perform(Kind.COMMAND) { operation ->
        val bound = readyConnection(operation)
        if (!bound.adapter.capabilities.light) throw UnsupportedFeatureException("light")
        bound.adapter.setLight(bound.session, setting)
        synchronized(guard) { ensureBound(operation, bound) }
    }

    override suspend fun upgrade(firmware: FirmwareSource): UpgradeResult = perform(Kind.UPGRADE) { operation ->
        val original = readyConnection(operation)
        val updater = original.adapter.updater
        if (!original.adapter.capabilities.firmwareUpdate || updater == null) {
            throw UnsupportedFeatureException("firmwareUpdate")
        }
        val originalInfo = requireNotNull(original.info)
        synchronized(guard) {
            ensureBound(operation, original)
            mutableConnection.value = ConnectionState.Upgrading
            mutableUpgrade.value = UpgradeState.Checking
        }
        try {
            // Common file integrity/compatibility, before sending any upgrade command.
            // Vendor signatures, containers and anti-rollback rules remain in the updater.
            FirmwareChecks.verify(firmware, originalInfo)
            var confirmed = 0L
            val context = object : UpgradeContext {
                override val originalDevice = originalInfo
                override val currentSession: ProtocolSession
                    get() = synchronized(guard) {
                        ensureOperation(operation)
                        val bound = connection ?: throw NotReadyException()
                        if (bound.session.termination.isCompleted) throw LinkClosedException()
                        bound.session
                    }

                override suspend fun findCandidates(
                    timeoutMillis: Long,
                    predicate: (Advertisement) -> Boolean,
                ): List<Advertisement> {
                    require(timeoutMillis > 0)
                    synchronized(guard) { ensureOperation(operation) }
                    val found = linkedMapOf<String, Advertisement>()
                    withTimeoutOrNull(timeoutMillis) {
                        scanner.scan().collect {
                            if (predicate(it)) found[it.target.address] = it
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    synchronized(guard) { ensureOperation(operation) }
                    return found.values.toList()
                }

                override suspend fun reconnect(target: ConnectionTarget, spec: SessionSpec): ProtocolSession {
                    val old = synchronized(guard) {
                        ensureOperation(operation)
                        val previous = connection
                        connection = null
                        generation++
                        previous
                    }
                    old?.session?.close()
                    var link: BleLink? = null
                    var opened: ProtocolSession? = null
                    try {
                        link = transport.open(target, spec.profile)
                        currentCoroutineContext().ensureActive()
                        opened = sessions.create(link, spec.newDecoder())
                        val bound = synchronized(guard) {
                            ensureOperation(operation)
                            Connection(++generation, target, original.adapter, original.normalSpec,
                                spec, opened, originalInfo).also { connection = it }
                        }
                        observeTermination(bound)
                        return opened
                    } catch (error: Throwable) {
                        opened?.close() ?: link?.close()
                        throw error
                    }
                }
            }
            val result = updater.upgrade(context, firmware) { state ->
                when (state) {
                    is UpgradeState.Success, is UpgradeState.PendingConfirmation,
                    is UpgradeState.Failed, UpgradeState.Cancelled, UpgradeState.Idle ->
                        throw ProtocolException("Updater must return its terminal outcome")
                    is UpgradeState.Transferring -> {
                        if (state.totalBytes != firmware.size || state.confirmedBytes !in confirmed..firmware.size) {
                            throw ProtocolException("Invalid device-confirmed firmware progress")
                        }
                        confirmed = state.confirmedBytes
                    }
                    else -> Unit
                }
                synchronized(guard) {
                    ensureOperation(operation)
                    mutableUpgrade.value = state
                }
            }
            var closeAfter: ProtocolSession? = null
            synchronized(guard) {
                ensureOperation(operation)
                mutableUpgrade.value = when (result) {
                    is UpgradeResult.Verified -> UpgradeState.Success(result.installedVersion)
                    is UpgradeResult.NeedsConfirmation -> UpgradeState.PendingConfirmation(result.reason)
                }
                val latest = connection
                if (latest != null && !latest.session.termination.isCompleted &&
                    latest.spec === original.normalSpec) {
                    val info = originalInfo.copy(firmwareVersion = (result as? UpgradeResult.Verified)?.installedVersion)
                    latest.info = info
                    mutableConnection.value = ConnectionState.Ready(info, original.adapter.capabilities)
                } else {
                    closeAfter = latest?.session
                    connection = null
                    generation++
                    mutableConnection.value = ConnectionState.Disconnected
                }
            }
            closeAfter?.close()
            result
        } catch (error: Throwable) {
            val closeAfter = synchronized(guard) {
                if (isCurrent(operation)) {
                    val bound = connection
                    connection = null
                    generation++
                    mutableUpgrade.value = if (error is CancellationException) UpgradeState.Cancelled
                        else UpgradeState.Failed(error.message ?: "Upgrade failed")
                    mutableConnection.value = if (error is CancellationException) ConnectionState.Disconnected
                        else ConnectionState.Failed(error.message ?: "Upgrade failed")
                    bound?.session
                } else null
            }
            closeAfter?.close()
            throw error
        }
    }

    /** Disconnect cleanup is controller-owned, so cancelling the caller does not abandon it. */
    override suspend fun disconnect() {
        val task = synchronized(guard) {
            if (closed) return
            disconnectTask ?: run {
                disconnecting = true
                generation++
                val bound = connection
                connection = null
                val operation = active
                mutableConnection.value = ConnectionState.Disconnecting
                if (operation?.kind == Kind.UPGRADE) mutableUpgrade.value = UpgradeState.Cancelled
                scope.async<Unit>(start = CoroutineStart.LAZY) {
                    try {
                        operation?.job?.cancel(CancellationException("Controller disconnected"))
                        bound?.session?.close()
                        withTimeout(cleanupTimeoutMillis) { operation?.job?.join() }
                        Unit
                    } catch (error: Throwable) {
                        // A non-cooperating plugin cannot safely share a new connection.
                        close()
                        throw error
                    } finally {
                        synchronized(guard) {
                            disconnectTask = null
                            if (!closed) {
                                active = null
                                disconnecting = false
                                mutableConnection.value = ConnectionState.Disconnected
                            }
                        }
                    }
                }.also { disconnectTask = it }
            }
        }
        task.start()
        task.await()
    }

    override fun close() {
        val resources = synchronized(guard) {
            if (closed) return
            closed = true
            generation++
            val owned = connection?.session to active?.job
            connection = null
            if (active?.kind == Kind.UPGRADE) mutableUpgrade.value = UpgradeState.Cancelled
            active = null
            mutableConnection.value = ConnectionState.Closed
            owned
        }
        resources.first?.close()
        resources.second?.cancel(CancellationException("Controller closed"))
        ownerJob.cancel()
    }

    private suspend fun <T> perform(kind: Kind, block: suspend (Operation) -> T): T {
        val operation: Operation
        val task: Deferred<T>
        synchronized(guard) {
            if (closed || !ownerJob.isActive) throw SdkClosedException()
            if (disconnecting || active != null) throw BusyException()
            operation = Operation(++serial, kind)
            task = scope.async(start = CoroutineStart.LAZY) {
                try { block(operation) } finally { finish(operation) }
            }
            operation.job = task
            active = operation
        }
        task.start()
        try {
            return task.await()
        } catch (cancelled: CancellationException) {
            task.cancel(cancelled)
            // Do not wait indefinitely for a badly implemented vendor plugin.
            withContext(NonCancellable) {
                if (withTimeoutOrNull(cleanupTimeoutMillis) { task.join(); true } != true) close()
            }
            throw cancelled
        } finally {
            // Lazy operations cancelled before execution do not run their finally block.
            if (task.isCompleted) finish(operation)
        }
    }

    private fun finish(operation: Operation) = synchronized(guard) {
        if (active === operation) active = null
    }

    private fun isCurrent(operation: Operation): Boolean =
        !closed && !disconnecting && active === operation

    private fun ensureOperation(operation: Operation) {
        if (closed) throw SdkClosedException()
        if (!isCurrent(operation) || !operation.job.isActive) throw CancellationException("Operation is no longer active")
    }

    private fun readyConnection(operation: Operation): Connection = synchronized(guard) {
        ensureOperation(operation)
        val bound = connection ?: throw NotReadyException()
        if (mutableConnection.value !is ConnectionState.Ready || bound.info == null) throw NotReadyException()
        ensureBound(operation, bound)
        bound
    }

    private fun ensureBound(operation: Operation, bound: Connection) {
        ensureOperation(operation)
        if (connection !== bound || generation != bound.generation || bound.session.termination.isCompleted) {
            throw LinkClosedException("Device session changed or disconnected")
        }
    }

    private fun observeTermination(bound: Connection) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val cause = bound.session.termination.await()
            synchronized(guard) {
                if (connection === bound && generation == bound.generation) {
                    connection = null
                    generation++
                    if (!closed && !disconnecting) {
                        // An updater may intentionally lose the application-mode link.
                        // It retains operation ownership and may reconnect through its context.
                        mutableConnection.value = if (active?.kind == Kind.UPGRADE) ConnectionState.Upgrading
                            else if (cause == null) ConnectionState.Disconnected
                            else ConnectionState.Failed(cause.message ?: "Link lost")
                    }
                }
            }
        }
    }
}
