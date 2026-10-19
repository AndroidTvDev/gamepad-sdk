package dev.gamepad.sdk.demo

import dev.gamepad.sdk.*
import java.util.concurrent.atomic.AtomicInteger

class DemoAdapterFactory : GamepadAdapterFactory {
    override val protocolId: String = PROTOCOL_ID

    // Candidate matching is only a discovery hint, not authentication.
    override fun isCandidate(advertisement: Advertisement): Boolean =
        DemoProtocol.serviceUuid in advertisement.serviceUuids &&
            advertisement.serviceData[DemoProtocol.serviceUuid]?.contentEquals(DemoProtocol.greeting) == true

    override fun create(): GamepadAdapter = DemoAdapter()

    companion object {
        const val PROTOCOL_ID = DemoProtocol.ID
    }
}

/** Per-connection teaching adapter. Its handshake identifies a demo, not a secure device. */
class DemoAdapter : GamepadAdapter {
    private val transactions = AtomicInteger(0)

    override val normalSession = SessionSpec(
        profile = BleProfile(
            requiredEndpoints = setOf(DemoProtocol.command, DemoProtocol.notification),
            subscriptions = listOf(Subscription(DemoProtocol.notification, SubscriptionMode.NOTIFY)),
            preferredMtu = 247,
        ),
        newDecoder = { DemoDecoder() },
    )

    override val capabilities = GamepadCapabilities(
        readInfo = true,
        light = true,
        firmwareUpdate = true,
        resumeUpdate = false,
        safeAbortUpdate = false,
    )

    override val updater: FirmwareUpdater = DemoFirmwareUpdater(this)

    override suspend fun initialize(session: ProtocolSession): DeviceInfo {
        val reply = command(session, DemoProtocol.HELLO, DemoProtocol.greeting)
        if (!reply.contentEquals(DemoProtocol.greeting)) throw VerificationException("Not a fictional demo device")
        return readInfo(session).also { info ->
            if (info.model != DemoProtocol.MODEL || info.hardwareRevision != DemoProtocol.HARDWARE) {
                throw VerificationException("Unexpected fictional demo identity")
            }
        }
    }

    override suspend fun readInfo(session: ProtocolSession): DeviceInfo {
        val fields = DemoCodec.readStrings(command(session, DemoProtocol.INFO), 4)
        return DeviceInfo(fields[0], fields[1], fields[2], fields[3])
    }

    override suspend fun setLight(session: ProtocolSession, setting: LightSetting) {
        val reply = command(
            session,
            DemoProtocol.LIGHT,
            byteArrayOf(setting.red.toByte(), setting.green.toByte(), setting.blue.toByte()),
        )
        if (reply.isNotEmpty()) throw ProtocolException("Invalid demo light acknowledgment")
    }

    internal suspend fun command(
        session: ProtocolSession,
        opcode: Int,
        payload: ByteArray = byteArrayOf(),
    ): ByteArray {
        val transaction = transactions.incrementAndGet()
        if (transaction > 0xffff) {
            throw ProtocolException("Demo transaction range exhausted; reconnect with a fresh adapter")
        }
        val expectedOpcode = DemoProtocol.responseOpcode(opcode)
        return session.request(
            ProtocolRequest(
                frames = DemoCodec.writes(DemoCodec.encode(transaction, opcode, payload), session.limits),
                match = { message ->
                    when {
                        message !is DemoMessage || message.transaction != transaction -> ReplyMatch.Ignore
                        message.opcode == DemoProtocol.NACK -> ReplyMatch.Reject(
                            ProtocolException("Demo device rejected command: ${message.payload.toString(Charsets.UTF_8)}"),
                        )
                        message.opcode == expectedOpcode -> ReplyMatch.Accept(message.payload)
                        else -> ReplyMatch.Reject(ProtocolException("Unexpected demo response opcode"))
                    }
                },
            ),
        )
    }
}
