package dev.mobimapper.mobimapper.activation.adb

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbMessageTest {
    @Test fun shellServiceHasNullTerminatorAndRoundTrips() {
        val message = AdbMessage(AdbProtocol.A_OPEN, 1, 0, "shell:id -u")
        val decoded = AdbMessage.readFrom(DataInputStream(ByteArrayInputStream(message.toByteArray())))
        assertEquals(message, decoded)
        assertArrayEquals("shell:id -u\u0000".toByteArray(), decoded.data)
        assertTrue(decoded.validate())
    }

    @Test fun checksumUsesUnsignedByteValues() {
        val message = AdbMessage(AdbProtocol.A_WRTE, 1, 2, byteArrayOf(-1, -128, 0, 1))
        assertEquals(384, message.data_crc32)
        assertTrue(message.validate())
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedPayloadIsRejectedBeforeAnyBodyRead() {
        AdbMessage.readFrom(DataInputStream(ByteArrayInputStream(headerWithLength(AdbMessage.MAX_MESSAGE_BYTES + 1))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativePayloadIsRejected() {
        AdbMessage.readFrom(DataInputStream(ByteArrayInputStream(headerWithLength(-1))))
    }

    @Test(expected = IllegalArgumentException::class)
    fun corruptedPayloadIsRejected() {
        val frame = AdbMessage(AdbProtocol.A_WRTE, 1, 2, byteArrayOf(42)).toByteArray()
        frame[24] = 43
        AdbMessage.readFrom(DataInputStream(ByteArrayInputStream(frame)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun corruptedMagicIsRejected() {
        val frame = AdbMessage(AdbProtocol.A_OKAY, 1, 2, null as ByteArray?).toByteArray()
        frame[20] = (frame[20].toInt() xor 1).toByte()
        AdbMessage.readFrom(DataInputStream(ByteArrayInputStream(frame)))
    }

    private fun headerWithLength(length: Int): ByteArray = ByteBuffer.allocate(24)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(AdbProtocol.A_WRTE).putInt(1).putInt(2).putInt(length)
        .putInt(0).putInt(AdbProtocol.A_WRTE xor -1).array()
}
