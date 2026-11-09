package dev.uwumouse.app.engine

import org.junit.Assert.*
import org.junit.Test
import dev.uwumouse.app.backend.recovery.RecoveryJournal
import dev.uwumouse.app.backend.recovery.RecoveryInjectionState

class GameCursorTest {
    private class Harness(rejectCancel:Boolean=false) {
        var clock=1000L
        val frames=mutableListOf<TouchFrame>()
        val composer=TouchComposer({++clock},{ frames.add(it);!rejectCancel || it.action and 0xff !in listOf(1,3,6) })
        val engine=MappingEngine(composer,MappingGeometry(1000f,2000f))
    }
    @Test fun cursorClickAndDragUseOneStableOwnerAndHoverDoesNotInject() {
        val h=Harness();assertTrue(h.engine.enterCursorMode())
        assertTrue(h.engine.cursorMove(120f,340f));assertTrue(h.frames.isEmpty())
        assertTrue(h.engine.cursorButton(true,120f,340f))
        val first=h.composer.snapshot().single()
        assertEquals(MappingEngine.CURSOR_OWNER,first.owner)
        assertTrue(h.engine.cursorMove(220f,540f))
        assertEquals(first.id,h.composer.snapshot().single().id)
        assertEquals(220f,h.composer.snapshot().single().x,.001f)
        // Releasing beyond the display must still terminate the last valid contact.
        assertTrue(h.engine.cursorButton(false,5000f,5000f))
        assertEquals(0,h.composer.activeCount)
        assertEquals(listOf(0,2,1),h.frames.map { it.action })
        assertEquals(1,h.frames.map { it.downTime }.distinct().size)
    }
    @Test fun changingModesEndsOldControlsAndNeverRestoresHeldKeysOrButtons() {
        val h=Harness()
        h.engine.key(MappingEngine.W,true);h.engine.mouseButton(1,true)
        h.engine.setCameraEnabled(true);h.engine.relativeMouse(5f,2f)
        assertEquals(3,h.composer.activeCount)
        assertTrue(h.engine.enterCursorMode());assertEquals(0,h.composer.activeCount)
        assertFalse(h.engine.key(MappingEngine.W,true));assertFalse(h.engine.mouseButton(1,true))
        h.engine.cursorButton(true,300f,400f)
        assertTrue(h.engine.leaveCursorMode());assertEquals(0,h.composer.activeCount)
        assertTrue(h.engine.cameraEnabled);assertFalse(h.engine.cursorEnabled)
        h.engine.key(MappingEngine.W,false)
        assertEquals(0,h.composer.activeCount)
    }
    @Test fun rejectedHandoffRetainsUnverifiedContactAndCannotEnableCursor() {
        val h=Harness(true);h.engine.key(MappingEngine.W,true)
        assertFalse(h.engine.enterCursorMode())
        assertFalse(h.engine.cursorEnabled)
        assertTrue(h.composer.releaseUnverified)
        assertEquals(1,h.composer.activeCount)
        assertFalse(h.engine.cursorButton(true,100f,200f))
    }
    @Test fun repeatedModeChangesRemainAdmissibleToTheActualGuardianJournal() {
        val journal=RecoveryJournal();assertTrue(journal.begin(1,1000,2000,10001))
        var clock=1000L
        val frames=mutableListOf<TouchFrame>()
        val composer=TouchComposer({++clock},{ frame ->
            frames.add(frame)
            journal.prepare(1,frame) && journal.acknowledged(1,frame,true)
        })
        val engine=MappingEngine(composer,MappingGeometry(1000f,2000f))
        engine.setCameraEnabled(true)
        repeat(5) {
            engine.key(MappingEngine.W,true);engine.relativeMouse(2f,3f)
            assertTrue(engine.enterCursorMode())
            assertTrue(engine.cursorButton(true,300f,400f))
            assertTrue(engine.cursorButton(false,300f,400f))
            assertTrue(engine.leaveCursorMode())
            assertEquals(RecoveryInjectionState.ACTIVE,journal.snapshot().state)
            assertEquals(0,journal.snapshot().possibleContacts)
        }
        assertFalse(frames.any { it.action==TouchComposer.CANCEL })
        assertFalse(composer.releaseUnverified)
    }
}
