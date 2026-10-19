@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.gamepad.sdk

import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test

class RepositoryTest {
    @Test fun initializingIsNotReadyAndConcurrentCommandsAreBusy() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val adapter = TestAdapter(initializeBlock = { entered.complete(Unit); release.await(); INFO })
        val fixture = Fixture(backgroundScope, adapter)
        try {
            val connect = async { fixture.repo.connect(TARGET, "test") }
            entered.await()
            assertEquals(ConnectionState.Initializing, fixture.repo.connectionState.value)
            assertFailsWith<BusyException> { fixture.repo.readInfo() }
            release.complete(Unit)
            connect.await()
            assertIs<ConnectionState.Ready>(fixture.repo.connectionState.value)
        } finally { fixture.repo.close() }
    }

    @Test fun disconnectedAndUnsupportedOperationsFailExplicitly() = runTest {
        val fixture = Fixture(backgroundScope, TestAdapter(capabilities = CAPABILITIES.copy(light = false)))
        try {
            assertFailsWith<NotReadyException> { fixture.repo.readInfo() }
            fixture.repo.connect(TARGET, "test")
            assertFailsWith<UnsupportedFeatureException> { fixture.repo.setLight(LightSetting(1, 2, 3)) }
            assertIs<ConnectionState.Ready>(fixture.repo.connectionState.value)
        } finally { fixture.repo.close() }
    }

    @Test fun regularCommandsDoNotOverlap() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fixture = Fixture(backgroundScope, TestAdapter(readBlock = { entered.complete(Unit); release.await(); INFO }))
        try {
            fixture.repo.connect(TARGET, "test")
            val first = async { fixture.repo.readInfo() }
            entered.await()
            assertFailsWith<BusyException> { fixture.repo.setLight(LightSetting(1, 2, 3)) }
            release.complete(Unit)
            assertEquals(INFO, first.await())
        } finally { fixture.repo.close() }
    }

    @Test fun disconnectPreemptsUpgradeAndRejectsNewCommands() = runTest {
        val entered = CompletableDeferred<Unit>()
        val fixture = Fixture(backgroundScope, TestAdapter(updater = object : FirmwareUpdater {
            override suspend fun upgrade(context: UpgradeContext, firmware: FirmwareSource,
                report: (UpgradeState) -> Unit): UpgradeResult {
                entered.complete(Unit)
                awaitCancellation()
            }
        }))
        try {
            fixture.repo.connect(TARGET, "test")
            val update = async {
                assertFailsWith<CancellationException> { fixture.repo.upgrade(firmware()) }
            }
            entered.await()
            assertEquals(ConnectionState.Upgrading, fixture.repo.connectionState.value)
            assertFailsWith<BusyException> { fixture.repo.readInfo() }
            fixture.repo.disconnect()
            update.await()
            assertEquals(ConnectionState.Disconnected, fixture.repo.connectionState.value)
            assertEquals(UpgradeState.Cancelled, fixture.repo.upgradeState.value)
            assertTrue(fixture.transport.links.single().closed.get())
        } finally { fixture.repo.close() }
    }

    @Test fun cleanupDoesNotAdmitANewConnection() = runTest {
        val entered = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fixture = Fixture(backgroundScope, TestAdapter(readBlock = {
            entered.complete(Unit)
            try { awaitCancellation() } finally {
                withContext(NonCancellable) { cleanup.complete(Unit); release.await() }
            }
        }))
        try {
            fixture.repo.connect(TARGET, "test")
            val command = async { assertFailsWith<CancellationException> { fixture.repo.readInfo() } }
            entered.await()
            val disconnect = async { fixture.repo.disconnect() }
            cleanup.await()
            assertEquals(ConnectionState.Disconnecting, fixture.repo.connectionState.value)
            assertFailsWith<BusyException> { fixture.repo.connect(TARGET, "test") }
            release.complete(Unit)
            disconnect.await()
            command.await()
            assertEquals(ConnectionState.Disconnected, fixture.repo.connectionState.value)
        } finally { fixture.repo.close() }
    }

    @Test fun idleDisconnectRevokesReadyAndOldSessionCannotOverwriteReconnect() = runTest {
        val fixture = Fixture(backgroundScope, TestAdapter())
        try {
            fixture.repo.connect(TARGET, "test")
            val old = fixture.transport.links.single()
            old.lose()
            runCurrent()
            assertIs<ConnectionState.Failed>(fixture.repo.connectionState.value)
            fixture.repo.connect(TARGET, "test")
            runCurrent()
            assertIs<ConnectionState.Ready>(fixture.repo.connectionState.value)
            assertEquals(2, fixture.transport.links.size)
        } finally { fixture.repo.close() }
    }

    @Test fun unknownProtocolDoesNotOpenRadio() = runTest {
        val fixture = Fixture(backgroundScope, TestAdapter())
        try {
            assertFailsWith<IllegalArgumentException> { fixture.repo.connect(TARGET, "unknown") }
            assertEquals(0, fixture.transport.links.size)
        } finally { fixture.repo.close() }
    }

    @Test fun unconfirmedInstallIsNotReportedAsSuccess() = runTest {
        val fixture = Fixture(backgroundScope, TestAdapter(updater = updater {
            UpgradeResult.NeedsConfirmation("Device reboot proof unavailable")
        }))
        try {
            fixture.repo.connect(TARGET, "test")
            assertIs<UpgradeResult.NeedsConfirmation>(fixture.repo.upgrade(firmware()))
            assertIs<UpgradeState.PendingConfirmation>(fixture.repo.upgradeState.value)
            assertNull(assertIs<ConnectionState.Ready>(fixture.repo.connectionState.value).info.firmwareVersion)
        } finally { fixture.repo.close() }
    }

    @Test fun badHashNeverCallsVendorUpdater() = runTest {
        var invoked = false
        val fixture = Fixture(backgroundScope, TestAdapter(updater = updater {
            invoked = true
            UpgradeResult.Verified("2")
        }))
        try {
            fixture.repo.connect(TARGET, "test")
            val good = firmware()
            val bad = ByteArrayFirmwareSource(byteArrayOf(9), good.metadata)
            assertFailsWith<VerificationException> { fixture.repo.upgrade(bad) }
            assertFalse(invoked)
            assertIs<UpgradeState.Failed>(fixture.repo.upgradeState.value)
            assertIs<ConnectionState.Failed>(fixture.repo.connectionState.value)
            assertTrue(fixture.transport.links.single().closed.get())
        } finally { fixture.repo.close() }
    }

    @Test fun ownerCancellationClosesAnIdleDevice() = runTest {
        val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val fixture = Fixture(owner, TestAdapter())
        fixture.repo.connect(TARGET, "test")
        owner.cancel()
        runCurrent()
        assertTrue(fixture.transport.links.single().closed.get())
        assertEquals(ConnectionState.Closed, fixture.repo.connectionState.value)
    }

    @Test fun closeIsPermanentAndIdempotent() = runTest {
        val fixture = Fixture(backgroundScope, TestAdapter())
        fixture.repo.connect(TARGET, "test")
        fixture.repo.close()
        fixture.repo.close()
        runCurrent()
        fixture.repo.disconnect()
        assertEquals(ConnectionState.Closed, fixture.repo.connectionState.value)
        assertFailsWith<SdkClosedException> { fixture.repo.connect(TARGET, "test") }
    }

    @Test fun cancellingCallerCancelsConnectionAndReleasesOperationSlot() = runTest {
        val fixture = Fixture(backgroundScope, TestAdapter())
        val opening = CompletableDeferred<Unit>()
        val cleaned = CompletableDeferred<Unit>()
        fixture.transport.beforeOpen = {
            opening.complete(Unit)
            try { awaitCancellation() } finally { cleaned.complete(Unit) }
        }
        try {
            val connect = launch { fixture.repo.connect(TARGET, "test") }
            opening.await()
            connect.cancelAndJoin()
            assertTrue(cleaned.isCompleted)
            assertEquals(ConnectionState.Disconnected, fixture.repo.connectionState.value)
            fixture.transport.beforeOpen = null
            fixture.repo.connect(TARGET, "test")
            assertIs<ConnectionState.Ready>(fixture.repo.connectionState.value)
        } finally { fixture.repo.close() }
    }

    @Test fun internalUpgradeReconnectKeepsExclusivityAndCreatesFreshSession() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fixture = Fixture(backgroundScope, TestAdapter(updater = object : FirmwareUpdater {
            override suspend fun upgrade(context: UpgradeContext, firmware: FirmwareSource,
                report: (UpgradeState) -> Unit): UpgradeResult {
                context.reconnect(TARGET, SPEC)
                entered.complete(Unit)
                release.await()
                return UpgradeResult.Verified("2")
            }
        }))
        try {
            fixture.repo.connect(TARGET, "test")
            val upgrade = async { fixture.repo.upgrade(firmware()) }
            entered.await()
            assertFailsWith<BusyException> { fixture.repo.connect(TARGET, "test") }
            assertEquals(2, fixture.transport.links.size)
            assertTrue(fixture.transport.links.first().closed.get())
            release.complete(Unit)
            assertEquals(UpgradeResult.Verified("2"), upgrade.await())
            assertEquals("2", assertIs<ConnectionState.Ready>(fixture.repo.connectionState.value).info.firmwareVersion)
        } finally { fixture.repo.close() }
    }

    @Test fun candidateDiscoveryKeepsAllUniqueDevicesInsteadOfPickingFirst() = runTest {
        var candidates: List<Advertisement> = emptyList()
        val adapter = TestAdapter(updater = object : FirmwareUpdater {
            override suspend fun upgrade(context: UpgradeContext, firmware: FirmwareSource,
                report: (UpgradeState) -> Unit): UpgradeResult {
                candidates = context.findCandidates(100) { true }
                return UpgradeResult.NeedsConfirmation("Teaching test")
            }
        })
        val scanner = object : BleScanner {
            override fun scan() = flow {
                listOf("A", "A", "B").forEach { emit(Advertisement(ConnectionTarget(it), null,
                    emptySet(), emptyMap(), emptyMap())) }
            }
        }
        val fixture = Fixture(backgroundScope, adapter, scanner)
        try {
            fixture.repo.connect(TARGET, "test")
            fixture.repo.upgrade(firmware())
            assertEquals(listOf("A", "B"), candidates.map { it.target.address })
        } finally { fixture.repo.close() }
    }

    @Test fun equalGattProfileWithDifferentSessionIsNotAssumedToBeApplicationMode() = runTest {
        val fixture = Fixture(backgroundScope, TestAdapter(updater = object : FirmwareUpdater {
            override suspend fun upgrade(context: UpgradeContext, firmware: FirmwareSource,
                report: (UpgradeState) -> Unit): UpgradeResult {
                // Bootloader reuses the same UUIDs but owns a different decoder/spec.
                val differentProtocol = SessionSpec(SPEC.profile, SPEC.newDecoder)
                context.reconnect(TARGET, differentProtocol)
                return UpgradeResult.Verified("2")
            }
        }))
        try {
            fixture.repo.connect(TARGET, "test")
            assertEquals(UpgradeResult.Verified("2"), fixture.repo.upgrade(firmware()))
            assertIs<UpgradeState.Success>(fixture.repo.upgradeState.value)
            assertEquals(ConnectionState.Disconnected, fixture.repo.connectionState.value)
            assertTrue(fixture.transport.links.last().closed.get())
        } finally { fixture.repo.close() }
    }

    private class Fixture(scope: CoroutineScope, adapter: GamepadAdapter, scanner: BleScanner = EmptyScanner) {
        val transport = TestTransport()
        val repo = DefaultGamepadRepository(transport, scanner, AdapterRegistry(setOf(object : GamepadAdapterFactory {
            override val protocolId = "test"
            override fun isCandidate(advertisement: Advertisement) = true
            override fun create() = adapter
        })), scope)
    }

    private class TestAdapter(
        override val capabilities: GamepadCapabilities = CAPABILITIES,
        override val updater: FirmwareUpdater? = null,
        val initializeBlock: suspend () -> DeviceInfo = { INFO },
        val readBlock: suspend () -> DeviceInfo = { INFO },
    ) : GamepadAdapter {
        override val normalSession = SPEC
        override suspend fun initialize(session: ProtocolSession) = initializeBlock()
        override suspend fun readInfo(session: ProtocolSession) = readBlock()
        override suspend fun setLight(session: ProtocolSession, setting: LightSetting) = Unit
    }

    private class TestTransport : BleTransport {
        val links = mutableListOf<TestLink>()
        var beforeOpen: (suspend () -> Unit)? = null
        override suspend fun open(target: ConnectionTarget, profile: BleProfile): BleLink {
            beforeOpen?.invoke()
            return TestLink().also { links += it }
        }
    }

    private class TestLink : BleLink {
        val closed = AtomicBoolean(false)
        private val ended = CompletableDeferred<Throwable?>()
        private val input = Channel<GattNotification>(16)
        override val limits = LinkLimits(23, mapOf(ENDPOINT to 20))
        override val termination = ended
        override val notifications = input
        override suspend fun write(frame: GattWrite) = Unit
        override suspend fun read(endpoint: GattEndpoint) = byteArrayOf()
        override fun close() { if (closed.compareAndSet(false, true)) { ended.complete(null); input.close() } }
        fun lose() {
            if (closed.compareAndSet(false, true)) {
                val error = LinkClosedException("Test radio lost")
                ended.complete(error)
                input.close(error)
            }
        }
    }

    private object EmptyScanner : BleScanner { override fun scan(): Flow<Advertisement> = emptyFlow() }

    companion object {
        private val ENDPOINT = GattEndpoint(UUID(1, 1), UUID(2, 2))
        private val SPEC = SessionSpec(BleProfile(setOf(ENDPOINT), emptyList())) {
            object : MessageDecoder { override fun feed(notification: GattNotification): List<ProtocolMessage> = emptyList() }
        }
        private val INFO = DeviceInfo("TEST", "HW1", "1", "serial")
        private val CAPABILITIES = GamepadCapabilities(true, true, true)
        private val TARGET = ConnectionTarget("test://1")
        private fun firmware(): FirmwareSource {
            val bytes = byteArrayOf(1, 2, 3)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            return ByteArrayFirmwareSource(bytes, FirmwareMetadata(setOf("TEST"), setOf("HW1"), "2", hash))
        }
        private fun updater(block: suspend () -> UpgradeResult) = object : FirmwareUpdater {
            override suspend fun upgrade(context: UpgradeContext, firmware: FirmwareSource,
                report: (UpgradeState) -> Unit) = block()
        }
    }
}
