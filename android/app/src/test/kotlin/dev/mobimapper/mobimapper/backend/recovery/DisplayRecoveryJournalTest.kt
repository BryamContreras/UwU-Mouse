package dev.mobimapper.mobimapper.backend.recovery

import dev.mobimapper.mobimapper.engine.TouchComposer
import dev.mobimapper.mobimapper.engine.TouchCoordinatePolicy
import dev.mobimapper.mobimapper.engine.TouchFrame
import org.junit.Assert.*
import org.junit.Test

class DisplayRecoveryJournalTest {
    private val externalDisplay = 2
    private val sid = 17L
    private fun frame(action: Int, vararg ids: Int, time: Long = 100L) = TouchFrame(
        action, 100L, time, ids, FloatArray(ids.size) { 200f + ids[it] }, FloatArray(ids.size) { 300f + ids[it] },
    )
    private fun started(display: Int = externalDisplay, continuous: Boolean = false) = RecoveryJournal(displayId = display).also {
        assertTrue(it.begin(sid, 1920, 1080, 10498,
            if (continuous) TouchCoordinatePolicy.CONTINUOUS_CAMERA_9 else TouchCoordinatePolicy.STRICT))
    }
    private fun admit(journal: RecoveryJournal, event: TouchFrame) {
        assertTrue(journal.prepare(sid, event))
        assertTrue(journal.acknowledged(sid, event, true))
    }

    @Test fun missingFirstDownReplyRetainsOriginalDisplayAndCompleteGesture() {
        for (display in listOf(0, externalDisplay)) {
            val journal = started(display)
            val down = frame(TouchComposer.DOWN, 4)
            assertTrue(journal.prepare(sid, down))
            assertTrue(journal.fail(sid))
            val cancel = requireNotNull(journal.cancellationFrame(150))
            val state = journal.snapshot()
            assertEquals(display, journal.displayId)
            assertEquals(display, state.displayId)
            assertEquals(sid, state.sessionId)
            assertEquals(10498, state.targetUid)
            assertEquals(0x1002, state.source)
            assertEquals(0, state.deviceId)
            assertEquals(TouchComposer.CANCEL, cancel.action)
            assertEquals(100L, cancel.downTime)
            assertEquals(150L, cancel.eventTime)
            assertArrayEquals(intArrayOf(4), cancel.ids)
            assertArrayEquals(down.xs, cancel.xs, 0f)
            assertFalse(journal.begin(sid + 1, 1080, 2316, 10500))
            assertEquals(display, journal.snapshot().displayId)
            assertEquals(sid, journal.snapshot().sessionId)
        }
    }

    @Test fun ambiguousPointerAdditionKeepsExternalCancellationArraysDetached() {
        val journal = started()
        admit(journal, frame(TouchComposer.DOWN, 0))
        admit(journal, frame(TouchComposer.POINTER_DOWN or (1 shl 8), 0, 9, time = 101))
        val adding = frame(TouchComposer.POINTER_DOWN or (2 shl 8), 0, 9, 3, time = 102)
        assertTrue(journal.prepare(sid, adding))
        adding.ids[0] = 8
        adding.xs[1] = 999f
        assertTrue(journal.fail(sid))
        val cancel = requireNotNull(journal.cancellationFrame(200))
        assertArrayEquals(intArrayOf(0, 9, 3), cancel.ids)
        assertEquals(209f, cancel.xs[1], 0f)
        cancel.ids[0] = 7
        cancel.ys[0] = 999f
        val repeated = requireNotNull(journal.cancellationFrame(201))
        assertArrayEquals(intArrayOf(0, 9, 3), repeated.ids)
        assertEquals(300f, repeated.ys[0], 0f)
        assertEquals(externalDisplay, journal.snapshot().displayId)
        assertTrue(journal.cancelled(sid, true))
        assertEquals(externalDisplay, journal.snapshot().displayId)
        assertEquals(sid, journal.snapshot().sessionId)
        assertEquals(0, journal.snapshot().possibleContacts)
        assertFalse(journal.snapshot().receiverVerified)
    }

    @Test fun adoptedOrphanUsesItsOriginalExternalDisplayUntilCancellationIsAcknowledged() {
        val journal = RecoveryJournal(displayId = externalDisplay)
        val oldCancel = frame(TouchComposer.CANCEL, 2, 7, time = 150)
        assertTrue(journal.adoptForRecovery(sid, 1920, 1080, 10498, oldCancel))
        assertFalse(journal.begin(sid + 1, 1920, 1080, 10498))
        val cancel = requireNotNull(journal.cancellationFrame(200))
        assertEquals(100L, cancel.downTime)
        assertArrayEquals(intArrayOf(2, 7), cancel.ids)
        assertEquals(externalDisplay, journal.snapshot().displayId)
        assertEquals(sid, journal.snapshot().sessionId)
        assertTrue(journal.cancelled(sid, true))
        assertTrue(journal.begin(sid + 1, 1920, 1080, 10498))
        assertEquals(externalDisplay, journal.snapshot().displayId)
    }

    @Test fun rejectedExternalCancelDoesNotAuthorizeSwitchingToAnotherDestination() {
        val journal = started()
        admit(journal, frame(TouchComposer.DOWN, 0))
        assertTrue(journal.fail(sid))
        assertNotNull(journal.cancellationFrame(150))
        assertTrue(journal.cancelled(sid, false))
        assertFalse(DestinationChangePolicy.maySelect(journal.snapshot(), false))
        assertFalse(journal.reconciledTerminal(sid, true))
        assertFalse(journal.reconciledOrphan(sid, true))
        assertEquals(RecoveryInjectionState.RECOVERY_NEEDED, journal.snapshot().state)
        assertEquals(externalDisplay, journal.snapshot().displayId)
        assertEquals(1, journal.snapshot().possibleContacts)
    }

    @Test fun emptyStreamsMayChangeDestinationButRecoveryAndOutstandingRepliesCannot() {
        val journal = RecoveryJournal(displayId = externalDisplay)
        assertTrue(DestinationChangePolicy.maySelect(journal.snapshot(), false))
        assertFalse(DestinationChangePolicy.maySelect(journal.snapshot(), true))
        assertTrue(journal.begin(sid, 1920, 1080, 10498))
        assertTrue(DestinationChangePolicy.maySelect(journal.snapshot(), false))
        val down = frame(TouchComposer.DOWN, 0)
        assertTrue(journal.prepare(sid, down))
        assertFalse(DestinationChangePolicy.maySelect(journal.snapshot(), false))
        assertTrue(journal.acknowledged(sid, down, true))
        assertFalse(DestinationChangePolicy.maySelect(journal.snapshot(), false))
        assertTrue(journal.fail(sid))
        assertFalse(DestinationChangePolicy.maySelect(journal.snapshot(), false))
        assertTrue(journal.cancelled(sid, true))
        assertTrue(DestinationChangePolicy.maySelect(journal.snapshot(), false))
        assertFalse(DestinationChangePolicy.maySelect(journal.snapshot(), true))
    }

    @Test fun continuousCameraCancellationPreservesExteriorCoordinatesOnItsOriginalDisplay() {
        val journal = started(continuous = true)
        val down = TouchFrame(TouchComposer.DOWN, 100L, 100L, intArrayOf(9), floatArrayOf(960f), floatArrayOf(540f))
        admit(journal, down)
        val moving = TouchFrame(TouchComposer.MOVE, 100L, 101L, intArrayOf(9), floatArrayOf(5000f), floatArrayOf(-3000f))
        admit(journal, moving)
        val adding = TouchFrame(TouchComposer.POINTER_DOWN or (1 shl 8), 100L, 102L,
            intArrayOf(9, 0), floatArrayOf(5000f, 200f), floatArrayOf(-3000f, 850f))
        assertTrue(journal.prepare(sid, adding))
        assertTrue(journal.fail(sid))
        val cancel = requireNotNull(journal.cancellationFrame(200L))
        assertArrayEquals(intArrayOf(9, 0), cancel.ids)
        assertArrayEquals(floatArrayOf(5000f, 200f), cancel.xs, 0f)
        assertArrayEquals(floatArrayOf(-3000f, 850f), cancel.ys, 0f)
        assertEquals(100L, cancel.downTime)
        assertEquals(externalDisplay, journal.snapshot().displayId)
        assertEquals(sid, journal.snapshot().sessionId)
        assertEquals(TouchCoordinatePolicy.CONTINUOUS_CAMERA_9, journal.snapshot().coordinatePolicy)
        assertTrue(journal.cancelled(sid, true))
        assertEquals(0, journal.snapshot().possibleContacts)
    }
}
