package dev.mobimapper.mobimapper.engine

import dev.mobimapper.mobimapper.backend.recovery.RecoveryJournal
import org.junit.Assert.*
import org.junit.Test

class CameraHandoffTest {
    private class Harness(val bindings: MappingBindings = MappingBindings(distinctCameraContacts = true)) {
        val geometry = MappingGeometry(1000f, 2000f)
        val journal = RecoveryJournal().apply { check(begin(1, 1000, 2000, 10001)) }
        var now = 1000L
        val frames = mutableListOf<TouchFrame>()
        val composer = TouchComposer({ ++now }, { frame ->
            frames.add(frame)
            journal.prepare(1, frame) && journal.acknowledged(1, frame, true)
        })
        val engine = MappingEngine(composer, geometry, bindings)
    }

    @Test fun replacementsHaveDistinctIdsWhileJoystickAndActionKeepTheirIdentity() {
        val h = Harness()
        h.engine.key(MappingEngine.W, true); h.engine.key(MappingEngine.SPACE, true)
        val others = h.composer.snapshot().associate { it.owner to it.id }
        h.engine.setCameraEnabled(true)
        repeat(12) { assertTrue(h.engine.relativeMouse(100f, -80f)) }
        val cameraDowns = h.frames.filter { it.action and 255 == TouchComposer.POINTER_DOWN }
            .drop(1).map { it.ids[it.action ushr 8] }
        assertTrue(cameraDowns.size > 3)
        assertTrue(cameraDowns.zipWithNext().all { (a,b) -> a != b })
        for ((owner,id) in others) assertEquals(id, h.composer.snapshot().first { it.owner == owner }.id)
        assertEquals(3, h.journal.snapshot().possibleContacts)
        assertFalse(h.frames.any { it.action == TouchComposer.CANCEL })
        assertTrue(h.engine.pause()); assertEquals(0, h.journal.snapshot().possibleContacts)
    }

    @Test fun allBoundaryDirectionsPreserveDisplacementWithoutMovingBackToCenter() {
        for ((dx,dy) in listOf(1300f to 0f, -1300f to 0f, 0f to 1700f,
                0f to -1700f, 1300f to 1700f, -1300f to -1700f)) {
            val h = Harness(); h.engine.setCameraEnabled(true)
            assertTrue(h.engine.relativeMouse(dx,dy))
            var x = 0f; var y = 0f
            var prior: TouchFrame? = null
            for (frame in h.frames) {
                if (frame.action == TouchComposer.MOVE) {
                    val old = requireNotNull(prior)
                    assertArrayEquals(old.ids,frame.ids)
                    x += frame.xs[0] - old.xs[0]; y += frame.ys[0] - old.ys[0]
                }
                prior = frame
            }
            assertEquals(dx,x,.002f); assertEquals(dy,y,.002f)
            assertTrue(h.engine.cameraRestarts > 0)
            assertFalse(h.composer.paused)
        }
    }

    @Test fun tenContactBudgetDoesNotStealAnActionIdWhenEveryOtherSlotIsHeld() {
        val touches = (0..7).map { PreparedTouch("touch:$it",800f,1600f,key=100+it) }
        val h = Harness(MappingBindings(actionKey=null,actionButton=null,touches=touches,distinctCameraContacts=true))
        h.engine.key(MappingEngine.W,true)
        touches.forEach { h.engine.key(it.key!!,true) }
        val others = h.composer.snapshot()
        h.engine.setCameraEnabled(true)
        assertTrue(h.engine.relativeMouse(900f,800f))
        assertEquals(10,h.composer.activeCount)
        others.forEach { assertEquals(it,h.composer.snapshot().first { next -> next.owner == it.owner }) }
        assertTrue(h.engine.pause()); assertEquals(0,h.journal.snapshot().possibleContacts)
    }

    @Test fun classicLaboratoryKeepsItsOriginalPointerAllocation() {
        val h = Harness(MappingBindings())
        h.engine.setCameraEnabled(true)
        assertTrue(h.engine.relativeMouse(900f,0f))
        val downIds = h.frames.filter { it.action == TouchComposer.DOWN }.map { it.ids.single() }
        assertTrue(downIds.size > 3); assertEquals(setOf(0),downIds.toSet())
    }
}
