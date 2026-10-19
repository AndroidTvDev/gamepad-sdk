package dev.gamepad.sdk.android

import android.content.Context
import android.net.Uri
import dev.gamepad.sdk.FirmwareMetadata
import dev.gamepad.sdk.FirmwareSource
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Stable random access for SAF/content URIs, including non-seekable providers.
 * An app-owned temporary snapshot is streamed to disk, never loaded wholesale.
 * The owner must close it after the upgrade has completed or been cancelled.
 */
class AndroidUriFirmwareSource private constructor(
    private val snapshot: File,
    override val size: Long,
    override val metadata: FirmwareMetadata,
    private val dispatcher: CoroutineDispatcher,
) : FirmwareSource, Closeable {
    private val closed = AtomicBoolean()

    override suspend fun read(offset: Long, length: Int): ByteArray = withContext(dispatcher) {
        check(!closed.get()) { "Firmware source is closed" }
        require(offset >= 0 && length >= 0 && offset <= size && length.toLong() <= size - offset) {
            "Read range is outside the firmware snapshot"
        }
        val result = ByteArray(length)
        RandomAccessFile(snapshot, "r").use {
            it.seek(offset)
            it.readFully(result)
        }
        currentCoroutineContext().ensureActive()
        result
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) snapshot.delete()
    }

    companion object {
        /**
         * maxBytes bounds disk use. Metadata authenticity/signature verification
         * remains a vendor updater concern; a SHA-256 field alone is not trust.
         */
        suspend fun create(
            context: Context,
            uri: Uri,
            metadata: FirmwareMetadata,
            maxBytes: Long = 256L * 1024 * 1024,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): AndroidUriFirmwareSource {
            require(maxBytes > 0)
            var snapshot: File? = null
            try {
                return withContext(dispatcher) {
                    val file = File.createTempFile("gamepad-firmware-", ".bin", context.applicationContext.cacheDir)
                    snapshot = file
                    var total = 0L
                    val input = context.applicationContext.contentResolver.openInputStream(uri)
                        ?: throw java.io.IOException("Cannot open firmware URI")
                    input.use { source ->
                        file.outputStream().use { destination ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = source.read(buffer)
                                if (count == -1) break
                                if (count == 0) continue
                                if (count.toLong() > maxBytes - total) {
                                    throw java.io.IOException("Firmware exceeds configured $maxBytes byte limit")
                                }
                                destination.write(buffer, 0, count)
                                total += count
                            }
                        }
                    }
                    AndroidUriFirmwareSource(file, total, metadata, dispatcher)
                }
            } catch (error: Throwable) {
                // Includes cancellation during the resource-return dispatcher handoff.
                snapshot?.delete()
                throw error
            }
        }
    }
}
