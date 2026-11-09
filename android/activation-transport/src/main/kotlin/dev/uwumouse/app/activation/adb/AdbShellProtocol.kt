package dev.uwumouse.app.activation.adb

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Incremental ADB shell_v2 decoder: channel byte, uint32 little-endian length, body.
 * A shell record may cross ADB WRTE boundaries, and one WRTE may contain many records.
 * Protocol reference: AOSP adb/shell_protocol.h and daemon/shell_service.cpp.
 */
internal class AdbShellProtocol(private val listener: ((ByteArray) -> Unit)?) {
    private val header = ByteArray(5)
    private var headerBytes = 0
    private var channel = 0
    private var body = ByteArray(0)
    private var bodyBytes = 0
    private var outputBytes = 0
    private var exitCode: Int? = null

    fun feed(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            if (exitCode != null) throw IOException("Data received after shell exit")
            if (headerBytes < header.size) {
                val count = minOf(header.size - headerBytes, bytes.size - offset)
                bytes.copyInto(header, headerBytes, offset, offset + count)
                offset += count
                headerBytes += count
                if (headerBytes < header.size) continue
                channel = header[0].toInt() and 0xff
                val length = ByteBuffer.wrap(header, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (channel !in 1..3 || length !in 0..MAX_FRAME_BYTES || (channel == 3 && length != 1)) {
                    throw IOException("Invalid shell protocol frame")
                }
                if (channel != 3) {
                    if (length > MAX_OUTPUT_BYTES - outputBytes) throw IOException("Shell output limit exceeded")
                    outputBytes += length
                }
                body = ByteArray(length)
                bodyBytes = 0
                if (length == 0) {
                    completeFrame()
                    continue
                }
            }
            val count = minOf(body.size - bodyBytes, bytes.size - offset)
            bytes.copyInto(body, bodyBytes, offset, offset + count)
            offset += count
            bodyBytes += count
            if (bodyBytes == body.size) completeFrame()
        }
    }

    private fun completeFrame() {
        if (channel == 3) exitCode = body[0].toInt() and 0xff
        else if (body.isNotEmpty()) listener?.invoke(body)
        headerBytes = 0
        bodyBytes = 0
        body = ByteArray(0)
    }

    fun finish() {
        if (headerBytes != 0 || bodyBytes != 0) throw IOException("Truncated shell protocol frame")
        val code = exitCode ?: throw IOException("Shell closed without exit status")
        if (code != 0) throw AdbShellExitException(code)
    }

    companion object {
        internal const val MAX_FRAME_BYTES = 1024 * 1024
        internal const val MAX_OUTPUT_BYTES = 1024 * 1024
    }
}
