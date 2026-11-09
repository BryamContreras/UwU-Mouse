package dev.uwumouse.app.backend

import java.io.DataInputStream
import java.io.DataOutputStream

/** Private worker pipe payload. It never changes the touch stream or its journal. */
internal data class WorkerKeyFrame(
    val action: Int,
    val downTime: Long,
    val eventTime: Long,
    val keyCode: Int,
    val scanCode: Int,
    val metaState: Int,
    val repeats: Int,
) {
    fun requireValid(nowUptimeMillis: Long, maxKeyCode: Int): WorkerKeyFrame {
        require(action == 0 || action == 1) { "Unsupported worker key action" }
        require(keyCode in 1..maxKeyCode && scanCode >= 0) { "Invalid worker key identity" }
        require(repeats in 0..10_000) { "Invalid worker key repeat count" }
        require(downTime in 0..eventTime && eventTime <= nowUptimeMillis) { "Invalid worker key time" }
        return this
    }

    fun writeTo(output: DataOutputStream) {
        output.writeInt(action)
        output.writeLong(downTime)
        output.writeLong(eventTime)
        output.writeInt(keyCode)
        output.writeInt(scanCode)
        output.writeInt(metaState)
        output.writeInt(repeats)
    }

    companion object {
        const val COMMAND_MODE = 3
        fun readFrom(input: DataInputStream, nowUptimeMillis: Long, maxKeyCode: Int): WorkerKeyFrame =
            WorkerKeyFrame(input.readInt(), input.readLong(), input.readLong(), input.readInt(),
                input.readInt(), input.readInt(), input.readInt()).requireValid(nowUptimeMillis, maxKeyCode)
    }
}
