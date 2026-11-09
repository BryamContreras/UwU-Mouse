package dev.uwumouse.app.engine

import dev.uwumouse.app.backend.recovery.RecoveryJournal
import org.junit.Assert.*
import org.junit.Test

class ContinuousCameraTest {
    @Test fun continuousComposerReservesNineBeforeCameraAndStillSupportsTenContacts() {
        val frames=mutableListOf<TouchFrame>();var now=1000L
        val composer=TouchComposer({++now},{frames.add(it);true},continuousCamera=true)
        repeat(9) { index -> assertTrue(composer.begin("ordinary:$index",100f+index,200f,preferredId=9)) }
        assertEquals((0..8).toList(),composer.snapshot().map { it.id })
        val ordinary=composer.snapshot()
        assertFalse(composer.begin("ordinary:overflow",10f,10f))
        assertEquals("CONTACT_LIMIT",composer.lastError)
        assertEquals(ordinary,composer.snapshot())
        assertTrue(composer.begin(MappingEngine.CAMERA_OWNER,600f,500f,preferredId=0))
        assertEquals(10,composer.activeCount)
        assertEquals(9,composer.snapshot().last().id)
        assertTrue(composer.move(MappingEngine.CAMERA_OWNER,-4000f,7000f))
        ordinary.forEach { assertEquals(it,composer.snapshot().single { contact -> contact.owner==it.owner }) }
        assertTrue(frames.all { it.ids.size<=10 && it.ids.distinct().size==it.ids.size })
        assertTrue(composer.cancelAll());assertEquals(0,composer.activeCount)
    }

    @Test fun onlyAnAlreadyActiveCameraNineCanMoveNegativeAndHandoffCannotRecycleIt() {
        val frames=mutableListOf<TouchFrame>()
        val composer=TouchComposer({1000L},{frames.add(it);true},continuousCamera=true)
        assertFalse(composer.begin(MappingEngine.CAMERA_OWNER,-1f,100f))
        assertFalse(composer.move(MappingEngine.CAMERA_OWNER,-1f,100f))
        assertTrue(frames.isEmpty())
        assertTrue(composer.begin("ordinary",100f,200f,preferredId=9))
        assertEquals(0,composer.snapshot().single().id)
        assertFalse(composer.move("ordinary",-1f,200f))
        assertTrue(composer.begin(MappingEngine.CAMERA_OWNER,600f,500f))
        assertFalse(composer.begin(MappingEngine.CAMERA_OWNER,-200f,-300f)) // DOWN API stays strict.
        assertTrue(composer.move(MappingEngine.CAMERA_OWNER,-200f,-300f))
        val before=composer.snapshot();val count=frames.size
        assertFalse(composer.handoff(MappingEngine.CAMERA_OWNER,600f,500f,8))
        assertEquals("CONTINUOUS_CAMERA_HANDOFF",composer.lastError)
        assertEquals(before,composer.snapshot());assertEquals(count,frames.size)
        assertTrue(composer.cancelAll())
    }

    @Test fun continuationLimitCancelsAtLastValidOutsideCoordinatesInsteadOfResetting() {
        val frames=mutableListOf<TouchFrame>();var now=1000L
        val composer=TouchComposer({++now},{frames.add(it);true},continuousCamera=true)
        composer.begin("joystick",100f,200f);composer.begin(MappingEngine.CAMERA_OWNER,600f,500f)
        assertTrue(composer.move(MappingEngine.CAMERA_OWNER,-TouchCoordinatePolicy.LIMIT,TouchCoordinatePolicy.LIMIT))
        val lastValid=frames.last()
        assertFalse(composer.move(MappingEngine.CAMERA_OWNER,-TouchCoordinatePolicy.LIMIT-1f,200f))
        assertEquals(TouchComposer.CANCEL,frames.last().action)
        assertArrayEquals(lastValid.ids,frames.last().ids)
        assertArrayEquals(lastValid.xs,frames.last().xs,0f)
        assertArrayEquals(lastValid.ys,frames.last().ys,0f)
        assertEquals("CAMERA_COORDINATE_LIMIT",composer.lastError)
        assertTrue(composer.paused);assertFalse(composer.releaseUnverified);assertEquals(0,composer.activeCount)
    }

    @Test fun failedMoveAndCancelRetainExactOutsideRecoveryFrameAndHeldOwners() {
        val frames=mutableListOf<TouchFrame>();var now=1000L;var accepting=true
        val composer=TouchComposer({++now},{frames.add(it);accepting},continuousCamera=true)
        composer.begin("joystick",100f,200f);composer.begin("fire",800f,1500f)
        composer.begin(MappingEngine.CAMERA_OWNER,600f,500f)
        assertTrue(composer.move(MappingEngine.CAMERA_OWNER,-4000f,5000f))
        accepting=false
        assertFalse(composer.move(MappingEngine.CAMERA_OWNER,-4500f,5500f))
        val attempted=frames[frames.lastIndex-1]
        val cancel=frames.last();val recovery=requireNotNull(composer.recoveryFrame())
        assertEquals(TouchComposer.CANCEL,cancel.action)
        assertEquals(TouchComposer.CANCEL,recovery.action)
        assertArrayEquals(attempted.ids,recovery.ids)
        assertArrayEquals(attempted.xs,recovery.xs,0f);assertArrayEquals(attempted.ys,recovery.ys,0f)
        assertEquals(-4500f,recovery.xs[recovery.ids.indexOf(9)],0f)
        assertEquals(5500f,recovery.ys[recovery.ids.indexOf(9)],0f)
        assertEquals(attempted.downTime,recovery.downTime)
        assertTrue(composer.paused);assertTrue(composer.releaseUnverified);assertEquals(3,composer.activeCount)
        assertFalse(composer.resume())
        accepting=true;assertTrue(composer.cancelAll());assertEquals(0,composer.activeCount)
    }

    @Test fun rejectedLimitCancelRetainsLastValidOutsidePointRatherThanTheRejectedCoordinate() {
        val frames=mutableListOf<TouchFrame>();var now=1000L;var accepting=true
        val composer=TouchComposer({++now},{frames.add(it);accepting},continuousCamera=true)
        composer.begin("joystick",100f,200f);composer.begin(MappingEngine.CAMERA_OWNER,600f,500f)
        assertTrue(composer.move(MappingEngine.CAMERA_OWNER,-4000f,5000f))
        val lastValid=frames.last();val before=frames.size
        accepting=false
        assertFalse(composer.move(MappingEngine.CAMERA_OWNER,TouchCoordinatePolicy.LIMIT+1f,5000f))
        assertEquals(before+1,frames.size)
        assertEquals(TouchComposer.CANCEL,frames.last().action)
        val recovery=requireNotNull(composer.recoveryFrame())
        assertArrayEquals(lastValid.ids,recovery.ids)
        assertArrayEquals(lastValid.xs,recovery.xs,0f);assertArrayEquals(lastValid.ys,recovery.ys,0f)
        assertTrue(composer.paused);assertTrue(composer.releaseUnverified)
        assertEquals("RELEASE_UNVERIFIED",composer.lastError);assertEquals(2,composer.activeCount)
        accepting=true;assertTrue(composer.cancelAll());assertEquals(0,composer.activeCount)
    }

    @Test fun engineLimitStopsAllHeldInputsWithoutInjectingTheRejectedCoordinate() {
        val frames=mutableListOf<TouchFrame>();var now=1000L
        val journal=RecoveryJournal().apply { check(begin(1,1000,2000,10001,TouchCoordinatePolicy.CONTINUOUS_CAMERA_9)) }
        val composer=TouchComposer({++now},{ frame ->
            frames.add(frame);journal.prepare(1,frame) && journal.acknowledged(1,frame,true)
        },continuousCamera=true)
        val engine=MappingEngine(composer,MappingGeometry(1000f,2000f),MappingBindings(continuousCamera=true,cameraLast=true))
        engine.key(MappingEngine.W,true);engine.mouseButton(1,true);engine.setCameraEnabled(true)
        assertTrue(engine.relativeMouse(-4000f,5000f))
        val lastValid=frames.last()
        assertFalse(engine.relativeMouse(TouchCoordinatePolicy.LIMIT*2,0f))
        assertFalse(engine.lastOperationSucceeded);assertFalse(engine.cameraEnabled)
        assertEquals(TouchComposer.CANCEL,frames.last().action)
        assertArrayEquals(lastValid.ids,frames.last().ids)
        assertArrayEquals(lastValid.xs,frames.last().xs,0f);assertArrayEquals(lastValid.ys,frames.last().ys,0f)
        assertEquals("CAMERA_COORDINATE_LIMIT",composer.lastError)
        assertEquals(0L,engine.cameraRestarts);assertEquals(0,journal.snapshot().possibleContacts)
        val count=frames.size
        assertFalse(engine.relativeMouse(10f,0f));assertEquals(count,frames.size)
    }

    @Test fun cursorTransitionLiftsOutsideCameraAndDoesNotReplayHeldKeysOrButtons() {
        val frames=mutableListOf<TouchFrame>();var now=1000L
        val journal=RecoveryJournal().apply { check(begin(1,1000,2000,10001,TouchCoordinatePolicy.CONTINUOUS_CAMERA_9)) }
        val composer=TouchComposer({++now},{ frame ->
            frames.add(frame);journal.prepare(1,frame) && journal.acknowledged(1,frame,true)
        },continuousCamera=true)
        val engine=MappingEngine(composer,MappingGeometry(1000f,2000f),MappingBindings(continuousCamera=true,cameraLast=true))
        engine.key(MappingEngine.W,true);engine.mouseButton(1,true);engine.setCameraEnabled(true)
        assertTrue(engine.relativeMouse(-4000f,5000f))
        assertTrue(engine.enterCursorMode());assertEquals(0,composer.activeCount)
        val cameraUp=frames.last()
        assertEquals(TouchComposer.UP,cameraUp.action);assertArrayEquals(intArrayOf(9),cameraUp.ids)
        assertTrue(cameraUp.xs.single()<0f);assertTrue(cameraUp.ys.single()>2000f)
        assertTrue(engine.cursorButton(true,300f,400f));assertTrue(engine.cursorButton(false,300f,400f))
        assertTrue(engine.leaveCursorMode());assertTrue(engine.relativeMouse(5f,0f))
        assertEquals(listOf(MappingEngine.CAMERA_OWNER),composer.snapshot().map { it.owner })
        assertTrue(engine.pause());assertEquals(0,journal.snapshot().possibleContacts)
    }

    @Test fun changingCoordinateCapabilityRequiresANewComposerSessionAndStrictDefaultRemainsStrict() {
        val geometry=MappingGeometry(1000f,2000f)
        val strict=TouchComposer({1000L},{true})
        val engine=MappingEngine(strict,geometry)
        assertFalse(engine.updateConfiguration(geometry,MappingBindings(continuousCamera=true,cameraLast=true)))
        assertThrows(IllegalArgumentException::class.java) {
            MappingEngine(strict,geometry,MappingBindings(continuousCamera=true))
        }
        assertTrue(strict.begin(MappingEngine.CAMERA_OWNER,600f,500f))
        assertEquals(0,strict.snapshot().single().id)
        assertFalse(strict.move(MappingEngine.CAMERA_OWNER,-200f,-300f))
        assertFalse(strict.paused);assertEquals(600f,strict.snapshot().single().x,0f)
        assertTrue(strict.cancelAll())
    }
}
