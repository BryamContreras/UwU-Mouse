package dev.uwumouse.app.backend

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

class WorkerKeyFrameTest {
    @Test fun keyPayloadPreservesLongTimesAndTheNextCommandBoundary() {
        val down=(1L shl 35)+73
        val frame=WorkerKeyFrame(0,down,down+51,29,30,0x1001,17)
        val bytes=ByteArrayOutputStream()
        DataOutputStream(bytes).use { output -> frame.writeTo(output);output.writeInt(0x4d4d5732) }
        assertEquals(40,bytes.size()) // 36-byte key payload and the following command header.
        val input=DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
        assertEquals(frame,WorkerKeyFrame.readFrom(input,down+100,400))
        assertEquals(0x4d4d5732,input.readInt())
        assertEquals(-1,input.read())
    }

    @Test fun invalidOrTruncatedPayloadCannotReachInjection() {
        val valid=WorkerKeyFrame(0,100,120,29,30,0,0)
        val invalid=listOf(valid.copy(action=2),valid.copy(keyCode=0),valid.copy(keyCode=401),
            valid.copy(scanCode=-1),valid.copy(repeats=-1),valid.copy(repeats=10_001),
            valid.copy(downTime=-1),valid.copy(downTime=121),valid.copy(eventTime=201))
        for(frame in invalid) {
            val bytes=ByteArrayOutputStream()
            DataOutputStream(bytes).use(frame::writeTo)
            assertThrows(IllegalArgumentException::class.java) {
                WorkerKeyFrame.readFrom(DataInputStream(ByteArrayInputStream(bytes.toByteArray())),200,400)
            }
        }
        val bytes=ByteArrayOutputStream()
        DataOutputStream(bytes).use(valid::writeTo)
        assertThrows(EOFException::class.java) {
            WorkerKeyFrame.readFrom(DataInputStream(ByteArrayInputStream(bytes.toByteArray().dropLast(1).toByteArray())),200,400)
        }
    }
}
