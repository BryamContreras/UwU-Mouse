package dev.uwumouse.app.backend

import org.junit.Assert.*
import org.junit.Test

class CursorMotionMailboxTest {
    @Test fun burstSchedulesOneFrameAndDrawsTheLatestPosition() {
        val mailbox=CursorMotionMailbox()
        mailbox.activate(1L,0f,0f,20f)
        var scheduled=0
        for(index in 1..100_000) if(mailbox.offer(1L,index.toFloat(),-index.toFloat(),24f)) scheduled++
        assertEquals(1,scheduled)
        assertEquals(CursorMotionMailbox.Position(1L,100_000f,-100_000f,24f),mailbox.drain())
        assertNull(mailbox.drain())
        assertFalse(mailbox.offer(1L,100_000f,-100_000f,24f))
        assertTrue(mailbox.offer(1L,100_001f,0f,24f))
    }

    @Test fun pendingOldFrameCannotDrawAnObsoleteIdentity() {
        val mailbox=CursorMotionMailbox()
        mailbox.activate(1L,0f,0f,20f)
        assertTrue(mailbox.offer(1L,10f,10f,20f))
        mailbox.deactivate()
        mailbox.activate(2L,50f,50f,20f)
        assertFalse(mailbox.offer(1L,99f,99f,20f))
        assertNull(mailbox.drain())
        assertTrue(mailbox.offer(2L,60f,60f,20f))
        assertEquals(2L,mailbox.drain()?.token)
    }

    @Test fun replacementReusesTheAlreadyPendingFrame() {
        val mailbox=CursorMotionMailbox()
        mailbox.activate(1L,0f,0f,20f)
        assertTrue(mailbox.offer(1L,1f,1f,20f))
        mailbox.activate(2L,10f,10f,20f)
        assertFalse(mailbox.offer(2L,20f,20f,24f))
        assertEquals(CursorMotionMailbox.Position(2L,20f,20f,24f),mailbox.drain())
        mailbox.deactivate()
        assertFalse(mailbox.offer(2L,99f,99f,24f))
        assertNull(mailbox.drain())
    }
}
