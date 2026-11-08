package dev.mobimapper.mobimapper.engine

import dev.mobimapper.mobimapper.backend.recovery.RecoveryJournal
import org.junit.Assert.*
import org.junit.Test

class OverlappingCameraTest {
    private class Harness {
        val journal=RecoveryJournal().apply { check(begin(1,1000,2000,10001)) }
        var now=1000L
        val frames=mutableListOf<TouchFrame>()
        val composer=TouchComposer({++now},{ f -> frames.add(f);journal.prepare(1,f) && journal.acknowledged(1,f,true) })
        val geometry=MappingGeometry(1000f,2000f)
        val engine=MappingEngine(composer,geometry,MappingBindings(distinctCameraContacts=true,overlapCameraHandoff=true))
    }
    @Test fun newCameraFingerBeginsBeforeTheOldOneEndsWithoutAnEmptyInterval() {
        val h=Harness();h.engine.setCameraEnabled(true);h.engine.relativeMouse(160f,0f)
        val old=h.composer.snapshot().single().id;h.frames.clear()
        assertTrue(h.engine.relativeMouse(20f,0f))
        assertEquals(listOf(5,6,2),h.frames.map { it.action and 255 })
        assertEquals(2,h.frames.first().ids.size)
        assertTrue(old in h.frames.first().ids)
        val fresh=h.composer.snapshot().single()
        assertNotEquals(old,fresh.id);assertEquals(MappingEngine.CAMERA_OWNER,fresh.owner)
        assertEquals(h.geometry.cameraX+20f,fresh.x,.001f)
        assertEquals(1,h.journal.snapshot().possibleContacts)
    }
    @Test fun overlapDoesNotChangeHeldControlsAndFinalCancelCleansTheReplacement() {
        val h=Harness();h.engine.key(MappingEngine.W,true);h.engine.key(MappingEngine.SPACE,true)
        val held=h.composer.snapshot();h.engine.setCameraEnabled(true)
        repeat(20) { assertTrue(h.engine.relativeMouse(130f,-90f)) }
        held.forEach { c -> assertEquals(c,h.composer.snapshot().first { it.owner==c.owner }) }
        assertTrue(h.frames.all { it.ids.size<=4 })
        assertFalse(h.frames.any { it.action==TouchComposer.CANCEL })
        assertTrue(h.engine.pause());assertEquals(0,h.journal.snapshot().possibleContacts)
    }
    @Test fun replacementBeginsAtTheSameCameraIndexWhenJoystickIsHeld() {
        val h=Harness();h.engine.key(MappingEngine.W,true);h.engine.setCameraEnabled(true)
        h.engine.relativeMouse(160f,0f);val old=h.composer.snapshot().last().id;h.frames.clear()
        h.engine.relativeMouse(20f,0f)
        assertEquals(1,h.frames[0].action ushr 8)
        assertEquals(2,h.frames[1].action ushr 8)
        assertEquals(old,h.frames[1].ids[2])
        assertEquals(1,h.composer.snapshot().indexOfFirst { it.owner==MappingEngine.CAMERA_OWNER })
    }
    @Test fun fullTenContactStreamFallsBackWithoutEverExceedingItsBudget() {
        val h=Harness()
        repeat(9) { assertTrue(h.composer.begin("held:$it",100f,200f)) }
        h.engine.setCameraEnabled(true)
        assertTrue(h.engine.relativeMouse(900f,0f))
        assertEquals(10,h.composer.activeCount)
        assertTrue(h.frames.all { it.ids.size<=10 })
        assertTrue(h.engine.pause());assertEquals(0,h.journal.snapshot().possibleContacts)
    }
    @Test fun receiverRejectionDuringOverlapRetainsThePossibleFrameForRecovery() {
        var now=1000L;var reject=false
        val composer=TouchComposer({++now},{!reject})
        composer.begin("camera",600f,500f,preferredId=8);reject=true
        assertFalse(composer.handoff("camera",500f,500f,9))
        assertTrue(composer.paused);assertTrue(composer.releaseUnverified)
        assertArrayEquals(intArrayOf(9,8),composer.recoveryFrame()!!.ids)
    }
}
