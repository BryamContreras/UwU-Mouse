package dev.uwumouse.app.activation.adb

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class AdbShellProtocolTest {
    @Test fun decodesMultipleChannelsInOneAdbPacket() {
        val output = StringBuilder()
        val decoder = AdbShellProtocol { output.append(it.toString(Charsets.UTF_8)) }
        decoder.feed(frame(1, "started\n".toByteArray()) + frame(2, "warning\n".toByteArray()) + frame(3, byteArrayOf(0)))
        decoder.finish()
        assertEquals("started\nwarning\n", output.toString())
    }

    @Test fun everyHeaderAndBodyByteCanCrossAdbPackets() {
        val output = StringBuilder()
        val decoder = AdbShellProtocol { output.append(it.toString(Charsets.UTF_8)) }
        val stream = frame(1, "UWU_START_REQUESTED\n".toByteArray()) + frame(3, byteArrayOf(0))
        stream.forEach { decoder.feed(byteArrayOf(it)) }
        decoder.finish()
        assertEquals("UWU_START_REQUESTED\n", output.toString())
    }

    @Test fun headerAndFollowingRecordCanShareAdbBoundary() {
        val output = StringBuilder()
        val decoder = AdbShellProtocol { output.append(it.toString(Charsets.UTF_8)) }
        val stream = frame(1, "alpha".toByteArray()) + frame(2, "beta".toByteArray()) + frame(3, byteArrayOf(0))
        decoder.feed(stream.copyOfRange(0, 12))
        decoder.feed(stream.copyOfRange(12, stream.size))
        decoder.finish()
        assertEquals("alphabeta", output.toString())
    }

    @Test fun rejectsNonzeroExitAfterPreservingOutput() {
        val output = StringBuilder()
        val decoder = AdbShellProtocol { output.append(it.toString(Charsets.UTF_8)) }
        decoder.feed(frame(2, "flock failed\n".toByteArray()) + frame(3, byteArrayOf(127)))
        try {
            decoder.finish()
            throw AssertionError("Nonzero exit must throw")
        } catch (failure: AdbShellExitException) {
            assertEquals(127, failure.exitCode)
            assertEquals("flock failed\n", output.toString())
        }
    }

    @Test(expected = IOException::class) fun rejectsOversizeBeforeReceivingBody() {
        AdbShellProtocol(null).feed(header(1, AdbShellProtocol.MAX_FRAME_BYTES + 1))
    }

    @Test(expected = IOException::class) fun rejectsUnsignedLengthOverflow() {
        AdbShellProtocol(null).feed(header(1, -1))
    }

    @Test(expected = IOException::class) fun requiresExitStatus() {
        val decoder = AdbShellProtocol(null)
        decoder.feed(frame(1, "partial".toByteArray()))
        decoder.finish()
    }

    @Test(expected = IOException::class) fun rejectsTruncatedPayload() {
        val decoder = AdbShellProtocol(null)
        decoder.feed(frame(1, "abc".toByteArray()).dropLast(1).toByteArray())
        decoder.finish()
    }

    @Test(expected = IOException::class) fun rejectsTruncatedHeader() {
        val decoder = AdbShellProtocol(null)
        decoder.feed(byteArrayOf(1, 0, 0))
        decoder.finish()
    }

    @Test(expected = IOException::class) fun rejectsDataAfterExit() {
        AdbShellProtocol(null).feed(frame(3, byteArrayOf(0)) + frame(1, byteArrayOf(1)))
    }

    @Test(expected = IOException::class) fun boundsTotalOutputAcrossFrames() {
        val decoder = AdbShellProtocol(null)
        decoder.feed(frame(1, ByteArray(AdbShellProtocol.MAX_OUTPUT_BYTES)))
        decoder.feed(header(1, 1))
    }

    @Test fun emptyOutputFramesAreAllowed() {
        val decoder = AdbShellProtocol { throw AssertionError("Empty output need not be delivered") }
        decoder.feed(frame(1, byteArrayOf()) + frame(2, byteArrayOf()) + frame(3, byteArrayOf(0)))
        decoder.finish()
    }

    @Test(expected = IOException::class) fun rejectsMalformedExitLength() {
        AdbShellProtocol(null).feed(header(3, 0))
    }

    private fun frame(channel: Int, body: ByteArray): ByteArray = header(channel, body.size) + body
    private fun header(channel: Int, length: Int): ByteArray = ByteBuffer.allocate(5)
        .order(ByteOrder.LITTLE_ENDIAN).put(channel.toByte()).putInt(length).array()
}
