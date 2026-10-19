package dev.gamepad.sdk.demo

import dev.gamepad.sdk.*
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class DemoEndToEndTest {
    @Test
    fun `public SDK discovers connects updates and closes without vendor types in controller API`() = runTest {
        val transport = DemoBleTransport()
        val sdk = GamepadSdk(
            transport,
            DemoBleScanner(listOf(transport.device)),
            setOf(DemoAdapterFactory()),
            backgroundScope,
        )
        try {
            val discovered = sdk.discover().first()
            val controller = sdk.newController()
            controller.connect(discovered.advertisement.target, discovered.protocolIds.single())
            assertIs<ConnectionState.Ready>(controller.connectionState.value)
            controller.setLight(LightSetting(8, 16, 32))
            val source = DemoFirmwareSource.create(ByteArray(41) { it.toByte() }, "3.0.0-demo")
            assertEquals(UpgradeResult.Verified("3.0.0-demo"), controller.upgrade(source))
            assertEquals(UpgradeState.Success("3.0.0-demo"), controller.upgradeState.value)
            assertEquals("3.0.0-demo", controller.readInfo().firmwareVersion)
            controller.disconnect()
            assertEquals(ConnectionState.Disconnected, controller.connectionState.value)
            assertEquals(0, transport.activeLinkCount)
            controller.close()
        } finally {
            sdk.close()
        }
        assertEquals(0, transport.activeLinkCount)
    }

    @Test
    fun `MTU23 fragmented handshake info and lights work with early replies`() = runTest {
        val fixture = fixture()
        try {
            val advertisement = DemoBleScanner(listOf(fixture.transport.device)).scan().first()
            assertTrue(DemoAdapterFactory().isCandidate(advertisement))
            val info = fixture.adapter.initialize(fixture.session)
            assertEquals(DemoProtocol.MODEL, info.model)
            assertEquals("1.0.0-demo", info.firmwareVersion)
            assertEquals(23, fixture.session.limits.negotiatedMtu)
            fixture.adapter.setLight(fixture.session, LightSetting(255, 12, 42))
            assertEquals(LightSetting(255, 12, 42), fixture.transport.device.light)
            assertFalse(fixture.session.termination.isCompleted)
        } finally {
            fixture.session.close()
        }
        assertEquals(0, fixture.transport.activeLinkCount)
    }

    @Test
    fun `OTA subtracts protocol overhead at MTU23 reports ACK offsets and verifies version`() = runTest {
        val fixture = fixture()
        try {
            val info = fixture.adapter.initialize(fixture.session)
            val image = ByteArray(73) { (it * 7).toByte() }
            val source = DemoFirmwareSource.create(image, "2.0.0-demo")
            // Source owns its bytes, including while callers mutate the original.
            image.fill(0)
            val states = mutableListOf<UpgradeState>()
            val result = fixture.adapter.updater.upgrade(context(info, fixture.session), source, states::add)
            assertEquals(UpgradeResult.Verified("2.0.0-demo"), result)
            assertEquals("2.0.0-demo", fixture.adapter.readInfo(fixture.session).firmwareVersion)
            assertContentEquals(ByteArray(73) { (it * 7).toByte() }, fixture.transport.device.installedImage)
            // 20-byte characteristic value - 9-byte frame - 4-byte offset = 7.
            val offsets = states.filterIsInstance<UpgradeState.Transferring>().map { it.confirmedBytes }
            assertEquals(listOf(0L, 7L, 14L, 21L, 28L, 35L, 42L, 49L, 56L, 63L, 70L, 73L), offsets)
            assertEquals(UpgradeState.Confirming, states.last())
            assertFalse(fixture.adapter.capabilities.resumeUpdate)
            assertFalse(fixture.adapter.capabilities.safeAbortUpdate)
        } finally {
            fixture.session.close()
        }
    }

    @Test
    fun `wrong SHA or incompatible model fails preflight without poisoning communication`() = runTest {
        val fixture = fixture()
        try {
            val info = fixture.adapter.initialize(fixture.session)
            val valid = DemoFirmwareSource.create(ByteArray(31) { it.toByte() }, "2.0.0-demo")
            val invalidHash = DemoFirmwareSource(ByteArray(31) { it.toByte() }, valid.metadata.copy(sha256 = "00".repeat(32)))
            assertFailsWith<VerificationException> {
                fixture.adapter.updater.upgrade(context(info, fixture.session), invalidHash) {}
            }
            val wrongModel = DemoFirmwareSource.create(ByteArray(31), "2.0.0-demo", model = "OTHER-MODEL")
            assertFailsWith<VerificationException> {
                fixture.adapter.updater.upgrade(context(info, fixture.session), wrongModel) {}
            }
            assertEquals("1.0.0-demo", fixture.adapter.readInfo(fixture.session).firmwareVersion)
            fixture.adapter.setLight(fixture.session, LightSetting(1, 2, 3))
            assertTrue(fixture.transport.device.installedImage.isEmpty())
        } finally {
            fixture.session.close()
        }
    }

    @Test
    fun `device rejects image changed after preflight and no verified result is returned`() = runTest {
        val fixture = fixture()
        try {
            val info = fixture.adapter.initialize(fixture.session)
            val valid = DemoFirmwareSource.create(ByteArray(31) { it.toByte() }, "2.0.0-demo")
            val changingSource = object : FirmwareSource {
                override val size = valid.size
                override val metadata = valid.metadata
                override suspend fun read(offset: Long, length: Int): ByteArray = valid.read(offset, length).also {
                    // Preflight reads all 31 bytes; the transfer reads chunks <=7.
                    if (length <= 7) it[0] = (it[0].toInt() xor 1).toByte()
                }
            }
            val error = assertFailsWith<ProtocolException> {
                fixture.adapter.updater.upgrade(context(info, fixture.session), changingSource) {}
            }
            assertTrue(error.message.orEmpty().contains("checksum mismatch"))
            assertEquals("1.0.0-demo", fixture.adapter.readInfo(fixture.session).firmwareVersion)
            assertTrue(fixture.transport.device.installedImage.isEmpty())
        } finally {
            fixture.session.close()
        }
    }

    @Test
    fun `idle simulated disconnect reaches session and releases transport`() = runTest {
        val fixture = fixture()
        fixture.adapter.initialize(fixture.session)
        fixture.transport.simulateLinkLoss()
        assertIs<LinkClosedException>(fixture.session.termination.await())
        assertEquals(0, fixture.transport.activeLinkCount)
        fixture.session.close()
        assertFailsWith<LinkClosedException> { fixture.adapter.readInfo(fixture.session) }
    }

    @Test
    fun `local link close publishes null cause before waking an unconfined receiver`() = runTest {
        val transport = DemoBleTransport()
        val adapter = DemoAdapter()
        val link = transport.open(transport.device.target, adapter.normalSession.profile)
        val scope = CoroutineScope(backgroundScope.coroutineContext + Dispatchers.Unconfined)
        val session = DefaultProtocolSessionFactory(scope).create(link, adapter.normalSession.newDecoder())
        link.close()
        assertEquals(null, session.termination.await())
        assertEquals(0, transport.activeLinkCount)
        session.close()
    }

    @Test
    fun `decoder accepts split and concatenated frames and rejects damaged CRC`() {
        assertEquals(0x29b1, DemoCodec.crc16("123456789".toByteArray(), 9))
        val first = DemoCodec.encode(1, 0x81, byteArrayOf(4, 5, 6))
        val second = DemoCodec.encode(2, 0x82, byteArrayOf(7))
        val decoder = DemoDecoder()
        assertTrue(decoder.feed(notification(first.copyOfRange(0, 1))).isEmpty())
        val messages = decoder.feed(notification(first.copyOfRange(1, first.size) + second))
        assertEquals(listOf(1, 2), messages.map { (it as DemoMessage).transaction })
        assertContentEquals(byteArrayOf(4, 5, 6), (messages[0] as DemoMessage).payload)
        val corrupted = first.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertFailsWith<ProtocolException> { DemoDecoder().feed(notification(corrupted)) }
    }

    private suspend fun TestScope.fixture(): Fixture {
        val transport = DemoBleTransport()
        val adapter = DemoAdapter()
        val link = transport.open(transport.device.target, adapter.normalSession.profile)
        val session = DefaultProtocolSessionFactory(backgroundScope).create(link, adapter.normalSession.newDecoder())
        return Fixture(transport, adapter, session)
    }

    private fun context(info: DeviceInfo, session: ProtocolSession) = object : UpgradeContext {
        override val originalDevice: DeviceInfo = info
        override val currentSession: ProtocolSession = session
        override suspend fun findCandidates(timeoutMillis: Long, predicate: (Advertisement) -> Boolean): List<Advertisement> =
            error("Demo same-session update must not scan")
        override suspend fun reconnect(target: ConnectionTarget, spec: SessionSpec): ProtocolSession =
            error("Demo same-session update must not reconnect")
    }

    private fun notification(bytes: ByteArray) = GattNotification(DemoProtocol.notification, bytes)

    private data class Fixture(val transport: DemoBleTransport, val adapter: DemoAdapter, val session: ProtocolSession)
}
