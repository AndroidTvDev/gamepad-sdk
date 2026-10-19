package dev.gamepad.sdk

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlinx.coroutines.*

class ByteArrayFirmwareSource(bytes: ByteArray, override val metadata: FirmwareMetadata) : FirmwareSource {
    private val content = bytes.copyOf()
    override val size: Long = content.size.toLong()
    override suspend fun read(offset: Long, length: Int): ByteArray {
        require(offset >= 0 && length >= 0 && offset <= size - length)
        currentCoroutineContext().ensureActive()
        return content.copyOfRange(offset.toInt(), offset.toInt() + length)
    }
}

/** Caller must keep this file unchanged until the upgrade finishes. No descriptor is retained. */
class FileFirmwareSource private constructor(
    private val file: File,
    override val size: Long,
    override val metadata: FirmwareMetadata,
    private val ioDispatcher: CoroutineDispatcher,
) : FirmwareSource {
    override suspend fun read(offset: Long, length: Int): ByteArray = withContext(ioDispatcher) {
        require(offset >= 0 && length >= 0 && offset <= size - length)
        RandomAccessFile(file, "r").use {
            check(it.length() == size) { "Firmware file changed during upgrade" }
            it.seek(offset)
            ByteArray(length).also(it::readFully)
        }
    }

    companion object {
        suspend fun open(
            file: File,
            metadata: FirmwareMetadata,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): FileFirmwareSource = withContext(ioDispatcher) {
            require(file.isFile && file.canRead()) { "Firmware file is not readable" }
            FileFirmwareSource(file, file.length(), metadata, ioDispatcher)
        }
    }
}

object FirmwareChecks {
    suspend fun verify(source: FirmwareSource, device: DeviceInfo) {
        if (source.size <= 0) throw VerificationException("Firmware is empty")
        val metadata = source.metadata
        if (device.model !in metadata.compatibleModels) throw VerificationException("Firmware model mismatch")
        if (metadata.compatibleHardwareRevisions.isNotEmpty() &&
            device.hardwareRevision !in metadata.compatibleHardwareRevisions) {
            throw VerificationException("Firmware hardware revision mismatch")
        }
        if (!metadata.sha256.matches(Regex("[0-9a-fA-F]{64}"))) throw VerificationException("Invalid SHA-256 metadata")
        if (metadata.expectedVersion.isBlank()) throw VerificationException("Missing expected version")
        val actual = sha256(source)
        if (!actual.equals(metadata.sha256, ignoreCase = true)) throw VerificationException("Firmware SHA-256 mismatch")
    }

    suspend fun sha256(source: FirmwareSource): String = withContext(Dispatchers.Default) {
        val digest = MessageDigest.getInstance("SHA-256")
        var offset = 0L
        while (offset < source.size) {
            ensureActive()
            val length = minOf(64 * 1024L, source.size - offset).toInt()
            val bytes = source.read(offset, length)
            if (bytes.size != length) throw VerificationException("Incomplete firmware read at $offset")
            digest.update(bytes)
            offset += length
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
