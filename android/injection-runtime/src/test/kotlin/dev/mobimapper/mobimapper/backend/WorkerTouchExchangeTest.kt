package dev.mobimapper.mobimapper.backend

import dev.mobimapper.mobimapper.engine.TouchComposer
import dev.mobimapper.mobimapper.engine.TouchFrame
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.OutputStream
import org.junit.Assert.*
import org.junit.Test

class WorkerTouchExchangeTest {
    private val frame=TouchFrame(TouchComposer.MOVE,11,12,intArrayOf(0,9),floatArrayOf(25f,1200f),floatArrayOf(80f,-50f))

    private fun reply(generation:Long=7,session:Long=13,accepted:Boolean=true):ByteArray =
        ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use {
            it.writeInt(InjectionWorkerMain.MAGIC);it.writeLong(generation);it.writeLong(session)
            it.writeBoolean(accepted);it.writeLong(14)
        } }.toByteArray()

    @Test fun nextFocusWorkRunsAfterCompleteFlushAndBeforeReadingCurrentAck() {
        val order=ArrayList<String>()
        val bytes=ByteArrayOutputStream()
        val output=DataOutputStream(object:OutputStream() {
            override fun write(value:Int) { bytes.write(value) }
            override fun write(values:ByteArray,offset:Int,length:Int) { bytes.write(values,offset,length) }
            override fun flush() { order.add("flushed") }
        })
        val input=DataInputStream(object:ByteArrayInputStream(reply()) {
            override fun read():Int { order.add("ack");return super.read() }
        })
        assertTrue(WorkerConnection.exchangeTouch(input,output,7,13,frame,2560,1440,1,0) {
            order.add("focus")
            assertEquals(listOf("flushed","focus"),order)
            val command=DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
            assertEquals(InjectionWorkerMain.MAGIC,command.readInt());assertEquals(7L,command.readLong())
            assertEquals(13L,command.readLong());assertEquals(1,command.readInt());assertEquals(0,command.readInt())
            assertEquals(TouchComposer.MOVE,command.readInt());assertEquals(11L,command.readLong());assertEquals(12L,command.readLong())
            assertEquals(2560,command.readInt());assertEquals(1440,command.readInt());assertEquals(2,command.readInt())
            for(index in frame.ids.indices) {
                assertEquals(frame.ids[index],command.readInt());assertEquals(frame.xs[index],command.readFloat(),0f)
                assertEquals(frame.ys[index],command.readFloat(),0f)
            }
            assertEquals(-1,command.read())
        })
        assertTrue(order.indexOf("focus")<order.indexOf("ack"))
        assertEquals(-1,input.read())
    }

    @Test fun wrongGenerationOrSessionCannotAcknowledgeTheAttempt() {
        for(response in listOf(reply(generation=8),reply(session=14))) {
            assertThrows(IllegalStateException::class.java) {
                WorkerConnection.exchangeTouch(DataInputStream(ByteArrayInputStream(response)),
                    DataOutputStream(ByteArrayOutputStream()),7,13,frame,2560,1440,1,0)
            }
        }
    }

    @Test fun explicitRejectionIsReturnedAndMissingAckRemainsAnException() {
        assertFalse(WorkerConnection.exchangeTouch(DataInputStream(ByteArrayInputStream(reply(accepted=false))),
            DataOutputStream(ByteArrayOutputStream()),7,13,frame,2560,1440,1,0))
        val truncated=reply().copyOf(21)
        assertThrows(EOFException::class.java) {
            WorkerConnection.exchangeTouch(DataInputStream(ByteArrayInputStream(truncated)),
                DataOutputStream(ByteArrayOutputStream()),7,13,frame,2560,1440,1,0)
        }
    }

    @Test fun commandWriteFailureNeverRunsTheNextFocusWorkOrConsumesAnAck() {
        var focus=false
        val input=DataInputStream(ByteArrayInputStream(reply()))
        val output=DataOutputStream(object:OutputStream() {
            override fun write(value:Int) { throw java.io.IOException("closed worker pipe") }
        })
        assertThrows(java.io.IOException::class.java) {
            WorkerConnection.exchangeTouch(input,output,7,13,frame,2560,1440,1,0) { focus=true }
        }
        assertFalse(focus)
        assertEquals(InjectionWorkerMain.MAGIC,input.readInt())
    }
}
