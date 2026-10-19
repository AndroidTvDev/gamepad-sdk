package dev.gamepad.sdk.demo

import dev.gamepad.sdk.*
import java.io.ByteArrayOutputStream
import java.util.UUID

/** Entirely fictional teaching protocol. NEVER use these bytes to update real hardware. */
object DemoProtocol {
    const val ID = "fictional-demo-v1"
    const val MODEL = "DEMO-PAD"
    const val HARDWARE = "DEMO-HW1"
    const val FRAME_OVERHEAD = 9
    const val MAX_PAYLOAD = 4_096
    const val MAX_FIRMWARE_BYTES = 16 * 1_024 * 1_024

    // Random, SDK-local teaching UUIDs. They are not assigned by any vendor.
    val serviceUuid: UUID = UUID.fromString("53d0f091-8718-4f95-91c4-d51b5aba9a01")
    val command = GattEndpoint(serviceUuid, UUID.fromString("53d0f091-8718-4f95-91c4-d51b5aba9a02"))
    val notification = GattEndpoint(serviceUuid, UUID.fromString("53d0f091-8718-4f95-91c4-d51b5aba9a03"))

    internal const val HELLO = 0x01
    internal const val INFO = 0x02
    internal const val LIGHT = 0x03
    internal const val OTA_START = 0x10
    internal const val OTA_CHUNK = 0x11
    internal const val OTA_FINISH = 0x12
    internal const val NACK = 0xff
    internal val greeting: ByteArray get() = "FICTIONAL-DEMO-V1".toByteArray(Charsets.US_ASCII)

    internal fun responseOpcode(opcode: Int): Int = opcode or 0x80
}

/** Payload ownership stays with the message; callers always get a copy. */
class DemoMessage(val transaction: Int, val opcode: Int, payload: ByteArray) : ProtocolMessage {
    private val bytes = payload.copyOf()
    val payload: ByteArray get() = bytes.copyOf()
}

/**
 * Frame: D7 4D | payloadLength:u16LE | transaction:u16LE | opcode:u8 |
 * payload | CRC16-CCITT-FALSE:u16LE (polynomial 0x1021, init 0xffff).
 * CRC covers every preceding frame byte. Explicit fragmentation belongs to
 * this fictional protocol; generic BLE transport must not split arbitrary frames.
 */
object DemoCodec {
    fun encode(transaction: Int, opcode: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        require(transaction in 1..0xffff)
        require(opcode in 0..255)
        require(payload.size <= DemoProtocol.MAX_PAYLOAD)
        val frame = ByteArray(DemoProtocol.FRAME_OVERHEAD + payload.size)
        frame[0] = 0xd7.toByte()
        frame[1] = 0x4d
        put16(frame, 2, payload.size)
        put16(frame, 4, transaction)
        frame[6] = opcode.toByte()
        payload.copyInto(frame, 7)
        put16(frame, frame.size - 2, crc16(frame, frame.size - 2))
        return frame
    }

    internal fun writes(frame: ByteArray, limits: LinkLimits): List<GattWrite> {
        val maxBytes = limits.maxWriteValueBytes[DemoProtocol.command]
            ?: throw ProtocolException("Demo command endpoint has no negotiated write limit")
        require(maxBytes > 0)
        return (frame.indices step maxBytes).map { offset ->
            GattWrite(DemoProtocol.command, frame.copyOfRange(offset, minOf(offset + maxBytes, frame.size)), WriteMode.WITH_RESPONSE)
        }
    }

    internal fun crc16(bytes: ByteArray, count: Int): Int {
        var crc = 0xffff
        for (index in 0 until count) {
            crc = crc xor ((bytes[index].toInt() and 0xff) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xffff
            }
        }
        return crc
    }

    internal fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun put16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    internal fun u32(bytes: ByteArray, offset: Int = 0): Long {
        if (offset < 0 || bytes.size - offset < 4) throw ProtocolException("Truncated demo u32")
        return (0..3).fold(0L) { result, index ->
            result or ((bytes[offset + index].toLong() and 0xff) shl (8 * index))
        }
    }

    internal fun uint32(value: Long): ByteArray {
        require(value in 0..0xffff_ffffL)
        return ByteArray(4) { index -> (value ushr (8 * index)).toByte() }
    }

    internal fun strings(vararg values: String): ByteArray {
        val output = ByteArrayOutputStream()
        values.forEach { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            require(bytes.size <= 255)
            output.write(bytes.size)
            output.write(bytes)
        }
        return output.toByteArray()
    }

    internal fun readStrings(bytes: ByteArray, count: Int): List<String> {
        var offset = 0
        val values = List(count) {
            if (offset >= bytes.size) throw ProtocolException("Truncated demo string length")
            val length = bytes[offset++].toInt() and 0xff
            if (offset + length > bytes.size) throw ProtocolException("Truncated demo string")
            bytes.copyOfRange(offset, offset + length).toString(Charsets.UTF_8).also { offset += length }
        }
        if (offset != bytes.size) throw ProtocolException("Unexpected demo payload suffix")
        return values
    }
}

/** Fresh per session. Supports complete, split, and concatenated demo frames. */
class DemoDecoder(
    private val expectedEndpoint: GattEndpoint = DemoProtocol.notification,
) : MessageDecoder {
    private val pending = ByteArray(DemoProtocol.MAX_PAYLOAD + DemoProtocol.FRAME_OVERHEAD)
    private var pendingSize = 0

    override fun feed(notification: GattNotification): List<ProtocolMessage> {
        if (notification.endpoint != expectedEndpoint) return emptyList()
        // Bound buffered partial data independently from notification length.
        // Process one byte at a time so many concatenated valid frames also work.
        val result = mutableListOf<ProtocolMessage>()
        notification.value.forEach { byte ->
            if (pendingSize >= pending.size) {
                throw ProtocolException("Demo frame exceeds decoder buffer limit")
            }
            pending[pendingSize++] = byte
            if (pendingSize == 1 && pending[0] != 0xd7.toByte()) {
                throw ProtocolException("Invalid demo frame header")
            }
            if (pendingSize == 2 && pending[1] != 0x4d.toByte()) {
                throw ProtocolException("Invalid demo frame header")
            }
            if (pendingSize >= 4) {
                val payloadLength = DemoCodec.u16(pending, 2)
                if (payloadLength > DemoProtocol.MAX_PAYLOAD) throw ProtocolException("Demo payload too large")
                val frameLength = payloadLength + DemoProtocol.FRAME_OVERHEAD
                if (pendingSize == frameLength) {
                    val receivedCrc = DemoCodec.u16(pending, frameLength - 2)
                    if (receivedCrc != DemoCodec.crc16(pending, frameLength - 2)) {
                        throw ProtocolException("Demo CRC mismatch")
                    }
                    val transaction = DemoCodec.u16(pending, 4)
                    if (transaction == 0) throw ProtocolException("Demo transaction 0 is reserved")
                    result += DemoMessage(
                        transaction,
                        pending[6].toInt() and 0xff,
                        pending.copyOfRange(7, frameLength - 2),
                    )
                    pendingSize = 0
                }
            }
        }
        return result
    }
}
