package dev.gamepad.sdk.demo

import dev.gamepad.sdk.*
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * In-memory, fictional raw image + out-of-band metadata. This is not a signed
 * vendor firmware format; SHA-256 detects corruption, not authenticity.
 */
class DemoFirmwareSource(bytes: ByteArray, metadata: FirmwareMetadata) : FirmwareSource {
    private val image = bytes.copyOf()
    override val metadata: FirmwareMetadata = metadata.copy(
        compatibleModels = metadata.compatibleModels.toSet(),
        compatibleHardwareRevisions = metadata.compatibleHardwareRevisions.toSet(),
    )
    override val size: Long get() = image.size.toLong()

    override suspend fun read(offset: Long, length: Int): ByteArray {
        currentCoroutineContext().ensureActive()
        require(offset >= 0 && length >= 0 && offset <= size && length.toLong() <= size - offset)
        return image.copyOfRange(offset.toInt(), offset.toInt() + length)
    }

    companion object {
        suspend fun create(
            bytes: ByteArray,
            expectedVersion: String,
            model: String = DemoProtocol.MODEL,
            hardwareRevision: String = DemoProtocol.HARDWARE,
        ): DemoFirmwareSource = withContext(Dispatchers.Default) {
            val snapshot = bytes.copyOf()
            DemoFirmwareSource(
                snapshot,
                FirmwareMetadata(
                    compatibleModels = setOf(model),
                    compatibleHardwareRevisions = setOf(hardwareRevision),
                    expectedVersion = expectedVersion,
                    sha256 = MessageDigest.getInstance("SHA-256").digest(snapshot).hex(),
                ),
            )
        }
    }
}

/**
 * A deliberately simple demo: same-session, sequential offset ACKs, no retry,
 * resume or safe-abort promise. Real devices require their actual bootloader,
 * identity, package signature and reboot/reconnect rules in their own plugin.
 */
class DemoFirmwareUpdater(private val adapter: DemoAdapter) : FirmwareUpdater {
    override suspend fun upgrade(
        context: UpgradeContext,
        firmware: FirmwareSource,
        report: (UpgradeState) -> Unit,
    ): UpgradeResult {
        report(UpgradeState.Checking)
        val session = context.currentSession
        val device = adapter.readInfo(session)
        val metadata = firmware.metadata
        if (device.model != context.originalDevice.model || device.serialNumber != context.originalDevice.serialNumber) {
            throw VerificationException("Device identity changed before demo update")
        }
        if (device.model !in metadata.compatibleModels ||
            device.hardwareRevision !in metadata.compatibleHardwareRevisions
        ) {
            throw VerificationException("Firmware is incompatible with demo model/hardware")
        }
        if (firmware.size !in 1..DemoProtocol.MAX_FIRMWARE_BYTES.toLong()) {
            throw VerificationException("Demo firmware size must be 1..${DemoProtocol.MAX_FIRMWARE_BYTES} bytes")
        }
        val versionBytes = metadata.expectedVersion.toByteArray(Charsets.UTF_8)
        if (versionBytes.isEmpty() || versionBytes.size > 64) {
            throw VerificationException("Demo version must contain 1..64 UTF-8 bytes")
        }
        if (!metadata.sha256.matches(Regex("[0-9a-fA-F]{64}"))) {
            throw VerificationException("Invalid firmware SHA-256 metadata")
        }
        val actualDigest = withContext(Dispatchers.Default) {
            val digest = MessageDigest.getInstance("SHA-256")
            var offset = 0L
            while (offset < firmware.size) {
                ensureActive()
                val requested = minOf(16_384L, firmware.size - offset).toInt()
                val bytes = firmware.read(offset, requested)
                if (bytes.size != requested) throw VerificationException("Firmware source returned a short read")
                digest.update(bytes)
                offset += bytes.size
            }
            digest.digest()
        }
        if (!actualDigest.hex().equals(metadata.sha256, ignoreCase = true)) {
            throw VerificationException("Firmware SHA-256 mismatch")
        }
        val writeLimit = session.limits.maxWriteValueBytes[DemoProtocol.command]
            ?: throw ProtocolException("Missing negotiated demo write limit")
        // One full OTA_CHUNK fits one GATT value. Subtract the protocol frame
        // and the four-byte image offset, NOT just ATT's three-byte overhead.
        val chunkBytes = minOf(
            writeLimit - DemoProtocol.FRAME_OVERHEAD - 4,
            DemoProtocol.MAX_PAYLOAD - 4,
        )
        if (chunkBytes <= 0) throw ProtocolException("Negotiated write limit too small for demo OTA")

        report(UpgradeState.EnteringBootloader)
        // This simulator only changes logical mode; no disconnect is expected.
        val start = DemoCodec.uint32(firmware.size) +
            DemoCodec.strings(metadata.expectedVersion) + actualDigest
        expectOffset(adapter.command(session, DemoProtocol.OTA_START, start), 0L)
        var confirmed = 0L
        report(UpgradeState.Transferring(confirmed, firmware.size))
        while (confirmed < firmware.size) {
            currentCoroutineContext().ensureActive()
            val count = minOf(chunkBytes.toLong(), firmware.size - confirmed).toInt()
            val data = firmware.read(confirmed, count)
            if (data.size != count) throw VerificationException("Firmware source returned a short read")
            val nextOffset = confirmed + count
            val ack = adapter.command(session, DemoProtocol.OTA_CHUNK, DemoCodec.uint32(confirmed) + data)
            expectOffset(ack, nextOffset)
            confirmed = nextOffset
            report(UpgradeState.Transferring(confirmed, firmware.size))
        }
        report(UpgradeState.Validating)
        val finishReply = adapter.command(session, DemoProtocol.OTA_FINISH)
        if (finishReply.isNotEmpty()) throw ProtocolException("Invalid demo finish response")
        report(UpgradeState.Confirming)
        val installed = adapter.readInfo(session)
        if (installed.model != device.model || installed.serialNumber != device.serialNumber ||
            installed.hardwareRevision != device.hardwareRevision ||
            installed.firmwareVersion != metadata.expectedVersion
        ) {
            throw VerificationException("Demo post-install version/identity verification failed")
        }
        // The Repository owns publication of Success/Failed/Cancelled.
        return UpgradeResult.Verified(metadata.expectedVersion)
    }

    private fun expectOffset(payload: ByteArray, expected: Long) {
        if (payload.size != 4 || DemoCodec.u32(payload) != expected) {
            throw ProtocolException("Unexpected demo firmware acknowledgment offset")
        }
    }
}

internal fun ByteArray.hex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
