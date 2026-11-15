package dev.uwumouse.app.session

import org.junit.Assert.*
import org.junit.Test

class NativeMouseReportQueueTest {
    @Test fun movementAndEveryButtonEdgeArePublishedInSourceOrder() {
        val queue=InputQueueBuffer().apply { configureCameraMerge(1000.0,1000.0,true) }
        assertTrue(queue.offerMouseReport(4f,-2f,5,7,12,8,10,42))
        val entry=InputQueueEntry()
        assertTrue(queue.pollInto(entry))
        assertEquals(3,entry.type);assertEquals(42,entry.code);assertEquals(4.0,entry.x,0.0)
        assertEquals(10L,entry.lastSampleTimeNanos)
        for((button,down) in listOf(1 to 1.0,2 to 0.0,4 to 1.0)) {
            assertTrue(queue.pollInto(entry))
            assertEquals(2,entry.type);assertEquals(button,entry.code);assertEquals(down,entry.x,0.0)
            assertEquals(8L,entry.generation);assertEquals(12L,entry.enqueuedAtNanos)
        }
        assertFalse(queue.pollInto(entry))
    }

    @Test fun insufficientCapacityCannotAdmitOnlyTheMoveAndLoseItsButton() {
        val queue=InputQueueBuffer()
        repeat(InputQueueBuffer.CAPACITY-1) { assertTrue(queue.offer(1,it,0f,0f,1,1)) }
        assertFalse(queue.offerMouseReport(4f,0f,1,1,2,1,2,42))
        assertEquals(InputQueueBuffer.CAPACITY-1,queue.size)
        val entry=InputQueueEntry()
        repeat(InputQueueBuffer.CAPACITY-1) { assertTrue(queue.pollInto(entry));assertEquals(1,entry.type) }
        assertFalse(queue.pollInto(entry))
    }

    @Test fun stationaryButtonFitsWithoutManufacturingAMovementEntry() {
        val queue=InputQueueBuffer()
        repeat(InputQueueBuffer.CAPACITY-1) { assertTrue(queue.offer(1,it,0f,0f,1,1)) }
        assertTrue(queue.offerMouseReport(0f,0f,1,1,2,1,2,42))
        assertEquals(InputQueueBuffer.CAPACITY,queue.size)
    }

    @Test fun invalidMotionAndMasksCannotPartiallyChangeTheQueue() {
        val queue=InputQueueBuffer()
        assertFalse(queue.offerMouseReport(Float.NaN,0f,1,1,1,1,1,42))
        assertFalse(queue.offerMouseReport(1f,0f,32,1,1,1,1,42))
        assertFalse(queue.offerMouseReport(1f,0f,1,32,1,1,1,42))
        assertEquals(0,queue.size)
    }
}
