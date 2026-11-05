package dev.mobimapper.mobimapper.profile

import dev.mobimapper.mobimapper.backend.recovery.RecoveryJournal
import dev.mobimapper.mobimapper.engine.*
import org.junit.Assert.*
import org.junit.Test

class CompiledFireCameraTest {
    @Test fun twentyFourConfiguredActionsShareTheExistingTenContactBudgetWithoutBorrowingCameraIdentity() {
        val profile=GameProfile.create("com.example.game","24 controls",1000,2000,0).let {
            it.copy(calibration=it.calibration.copy(confirmed=true),action=ActionSpec(.85f,.6f,"key",70),
                additionalActions=(71..93).map { key -> ActionSpec(.85f,.65f,"key",key) })
        }
        val prepared=ProfileCompiler.prepare(profile,1000,2000,0)
        assertEquals(24,prepared.bindings.touches.size)
        assertTrue(prepared.bindings.continuousCamera)
        val journal=RecoveryJournal().apply { check(begin(1,1000,2000,10001,TouchCoordinatePolicy.CONTINUOUS_CAMERA_9)) }
        var now=1000L
        val composer=TouchComposer({++now},{ frame -> journal.prepare(1,frame) && journal.acknowledged(1,frame,true) },continuousCamera=true)
        val engine=MappingEngine(composer,prepared.geometry,prepared.bindings)
        engine.setCameraEnabled(true);assertTrue(engine.relativeMouse(5000f,-6000f))
        val camera=composer.snapshot().single { it.id==9 }
        for(key in 70..93) {
            assertTrue(engine.key(key,true));assertTrue(engine.lastOperationSucceeded)
            assertEquals(camera,composer.snapshot().single { it.id==9 })
            assertEquals(2,composer.activeCount)
            assertTrue(composer.snapshot().filter { it.owner!=MappingEngine.CAMERA_OWNER }.all { it.id in 0..8 })
            engine.key(key,false);assertEquals(1,composer.activeCount)
        }
        for(key in 70..78) { engine.key(key,true);assertTrue(engine.lastOperationSucceeded) }
        assertEquals(10,composer.activeCount)
        engine.key(79,true)
        assertFalse(engine.lastOperationSucceeded)
        assertEquals("CONTACT_LIMIT",composer.lastError)
        assertEquals(10,composer.activeCount)
        assertEquals(camera,composer.snapshot().single { it.id==9 })
        assertTrue(engine.pause());assertEquals(0,journal.snapshot().possibleContacts)
    }
    @Test fun compiledContinuousCameraKeepsOneFingerAcrossAllEdgesWithHeldMovementFireAndPartialActionUp() {
        val profile=GameProfile.create("com.example.game","Continuous camera",1000,2000,0).let {
            it.copy(calibration=it.calibration.copy(confirmed=true),camera=it.camera.copy(mode="continuous"),
                additionalActions=listOf(ActionSpec(.85f,.65f,"mouse",1)))
        }
        val prepared=ProfileCompiler.prepare(profile,1000,2000,0)
        assertTrue(prepared.bindings.continuousCamera)
        assertTrue(prepared.bindings.cameraLast)
        val journal=RecoveryJournal().apply { check(begin(1,1000,2000,10001,TouchCoordinatePolicy.CONTINUOUS_CAMERA_9)) }
        val frames=mutableListOf<TouchFrame>();var now=1000L
        val composer=TouchComposer({++now},{ frame ->
            frames.add(frame);journal.prepare(1,frame) && journal.acknowledged(1,frame,true)
        },continuousCamera=true)
        val engine=MappingEngine(composer,prepared.geometry,prepared.bindings)
        engine.key(MappingEngine.W,true);engine.mouseButton(1,true);engine.key(MappingEngine.SPACE,true)
        assertTrue(composer.snapshot().none { it.id==9 })
        val held=composer.snapshot()
        val heldIndices=held.associate { it.id to composer.snapshot().indexOf(it) }
        val fire=held.single { it.owner==prepared.bindings.touches.single { touch -> touch.button==1 }.owner }
        val joystick=held.single { it.owner==MappingEngine.JOYSTICK_OWNER }
        engine.setCameraEnabled(true)
        var expectedX=prepared.geometry.cameraX.toDouble();var expectedY=prepared.geometry.cameraY.toDouble()
        fun move(dx:Float,dy:Float) {
            expectedX+=dx.toDouble()*prepared.geometry.sensitivityX
            expectedY+=dy.toDouble()*prepared.geometry.sensitivityY
            assertTrue(engine.relativeMouse(dx,dy))
            val camera=composer.snapshot().single { it.owner==MappingEngine.CAMERA_OWNER }
            assertEquals(9,camera.id)
            assertEquals(expectedX.toFloat(),camera.x,0f);assertEquals(expectedY.toFloat(),camera.y,0f)
            assertEquals(fire,composer.snapshot().single { it.owner==fire.owner })
            assertEquals(joystick,composer.snapshot().single { it.owner==joystick.owner })
        }
        move(100f,100f)
        val cameraStart=frames.indexOfFirst { frame ->
            frame.action and 255 in listOf(0,5) && frame.ids[frame.action ushr 8]==9
        }
        val cycle=listOf(5000f to 6000f,-12000f to 0f,0f to -15000f,8000f to 10000f)
        repeat(240) { index ->
            val (dx,dy)=cycle[index%cycle.size]
            move(dx,dy)
            if(index==80) {
                val cameraBefore=composer.snapshot().single { it.id==9 }
                engine.key(MappingEngine.SPACE,false)
                assertTrue(engine.lastOperationSucceeded)
                assertEquals(cameraBefore,composer.snapshot().single { it.id==9 })
            }
        }
        assertEquals(0L,engine.cameraRestarts)
        assertFalse(composer.paused);assertFalse(composer.releaseUnverified)
        val cameraFrames=frames.drop(cameraStart)
        assertEquals(1,cameraFrames.count { frame -> frame.action and 255 in listOf(0,5) && frame.ids[frame.action ushr 8]==9 })
        assertTrue(cameraFrames.any { it.xs[it.ids.indexOf(9)]<0f })
        assertTrue(cameraFrames.any { it.xs[it.ids.indexOf(9)]>=1000f })
        assertTrue(cameraFrames.any { it.ys[it.ids.indexOf(9)]<0f })
        assertTrue(cameraFrames.any { it.ys[it.ids.indexOf(9)]>=2000f })
        for(frame in cameraFrames) {
            assertEquals(1,frame.ids.count { it==9 })
            for(contact in listOf(fire,joystick)) {
                val index=frame.ids.indexOf(contact.id)
                assertEquals(heldIndices[contact.id],index)
                assertEquals(contact.x,frame.xs[index],0f);assertEquals(contact.y,frame.ys[index],0f)
            }
            if(frame.action and 255 in listOf(1,6)) assertNotEquals(9,frame.ids[frame.action ushr 8])
        }
        assertEquals(1L,engine.fireHoldStarts);assertEquals(0L,engine.fireHoldEnds)
        val cameraBeforeFireUp=composer.snapshot().single { it.id==9 }
        engine.mouseButton(1,false)
        assertEquals(cameraBeforeFireUp,composer.snapshot().single { it.id==9 })
        assertEquals(joystick,composer.snapshot().single { it.owner==joystick.owner })
        moveAfterFireRelease(engine,composer,-3500f,-3000f)
        engine.key(MappingEngine.W,false)
        val lastCamera=composer.snapshot().single()
        assertEquals(9,lastCamera.id)
        assertTrue(engine.pause());assertEquals(0,journal.snapshot().possibleContacts)
        val cancel=frames.last()
        assertEquals(TouchComposer.CANCEL,cancel.action);assertArrayEquals(intArrayOf(9),cancel.ids)
        assertEquals(lastCamera.x,cancel.xs.single(),0f);assertEquals(lastCamera.y,cancel.ys.single(),0f)
        assertEquals(1L,engine.fireHoldEnds);assertEquals(0L,engine.cameraRestarts)
    }

    private fun moveAfterFireRelease(engine:MappingEngine,composer:TouchComposer,dx:Float,dy:Float) {
        assertTrue(engine.relativeMouse(dx,dy))
        assertTrue(composer.hasOwner(MappingEngine.JOYSTICK_OWNER))
        assertEquals(2,composer.activeCount)
    }

    @Test fun sequentialDistinctCameraKeepsHeldFireAndLateJoystickWithoutOverlappingCameras() {
        verifySequentialCamera(distinct = true)
    }

    @Test fun sequentialSameIdCameraReusesItsOwnIdAndKeepsHeldFireAndLateJoystick() {
        verifySequentialCamera(distinct = false)
    }

    private fun verifySequentialCamera(distinct: Boolean) {
        val profile=GameProfile.create("com.example.game","Sequential camera",1000,2000,0).let {
            it.copy(calibration=it.calibration.copy(confirmed=true),camera=it.camera.copy(mode=CameraSpec.BOUNDED),additionalActions=listOf(ActionSpec(.85f,.65f,"mouse",1)))
        }
        val prepared=ProfileCompiler.prepare(profile,1000,2000,0)
        val bindings=prepared.bindings.copy(distinctCameraContacts=distinct,overlapCameraHandoff=false,cameraLast=true)
        val journal=RecoveryJournal().apply { check(begin(1,1000,2000,10001)) }
        val frames=mutableListOf<TouchFrame>();var now=1000L
        val composer=TouchComposer({++now},{ frame ->
            frames.add(frame);journal.prepare(1,frame) && journal.acknowledged(1,frame,true)
        })
        val engine=MappingEngine(composer,prepared.geometry,bindings)
        val g=prepared.geometry
        engine.setCameraEnabled(true)
        assertTrue(engine.relativeMouse((g.cameraRadiusX-2f)/g.sensitivityX,0f))
        assertEquals(8,composer.snapshot().single().id)
        assertTrue(engine.mouseButton(1,true))
        assertTrue(engine.key(MappingEngine.W,true)) // Joystick added after fire + camera.
        assertTrue(engine.key(MappingEngine.SPACE,true))
        assertTrue(engine.key(MappingEngine.SPACE,false)) // Free a lower ID before replacing camera.
        val held=composer.snapshot().filter { it.owner!=MappingEngine.CAMERA_OWNER }
        val heldIndices=held.associate { it.id to composer.snapshot().indexOf(it) }
        assertEquals(2,held.size)
        assertEquals(composer.activeCount-1,composer.snapshot().indexOfFirst { it.owner==MappingEngine.CAMERA_OWNER })
        frames.clear()

        // Both modes take the same preemptive path: UP, DOWN at anchor, one MOVE for all delta.
        assertTrue(engine.relativeMouse(4f/g.sensitivityX,0f))
        assertEquals(listOf(6,5,2),frames.map { it.action and 255 })
        assertEquals(g.cameraX+4f,composer.snapshot().single { it.owner==MappingEngine.CAMERA_OWNER }.x,.001f)
        assertEquals(if(distinct) 9 else 8,composer.snapshot().single { it.owner==MappingEngine.CAMERA_OWNER }.id)
        repeat(20) { index ->
            assertTrue(engine.relativeMouse(if(index%2==0) 400f else -400f,if(index%2==0) -700f else 700f))
            held.forEach { contact -> assertEquals(contact,composer.snapshot().single { it.owner==contact.owner }) }
        }
        assertTrue(engine.cameraRestarts>20)
        val heldIds=held.map { it.id }.toSet()
        for(frame in frames) {
            assertTrue(frame.ids.count { it !in heldIds }<=1) // No two camera fingers, even intermediate.
            for(contact in held) {
                val index=frame.ids.indexOf(contact.id)
                assertEquals(heldIndices[contact.id],index)
                assertEquals(contact.x,frame.xs[index],0f)
                assertEquals(contact.y,frame.ys[index],0f)
            }
            if(frame.action and 255 in listOf(1,6)) assertFalse(frame.ids[frame.action ushr 8] in heldIds)
        }
        val replacementIds=frames.filter { it.action and 255==5 }.map { it.ids[it.action ushr 8] }
        assertTrue(replacementIds.size>20)
        if(distinct) assertTrue(replacementIds.zipWithNext().all { (old,next) -> old!=next })
        else assertEquals(setOf(8),replacementIds.toSet())
        assertEquals(1L,engine.fireHoldStarts);assertEquals(0L,engine.fireHoldEnds)
        assertFalse(composer.paused);assertEquals(3,journal.snapshot().possibleContacts)
        engine.mouseButton(1,false);engine.key(MappingEngine.W,false)
        assertEquals(1L,engine.fireHoldEnds)
        assertTrue(engine.pause());assertEquals(0,journal.snapshot().possibleContacts)
    }

    @Test fun pressingWasdAfterFireAndCameraAreAlreadyHeldRemainsAValidStream() {
        val profile=GameProfile.create("com.example.game","Late joystick",1000,2000,0).let {
            it.copy(calibration=it.calibration.copy(confirmed=true),camera=it.camera.copy(mode=CameraSpec.BOUNDED),additionalActions=listOf(ActionSpec(.85f,.65f,"mouse",1)))
        }
        val p=ProfileCompiler.prepare(profile,1000,2000,0)
        val journal=RecoveryJournal().apply { check(begin(1,1000,2000,10001)) }
        var now=1000L
        val composer=TouchComposer({++now},{ f -> journal.prepare(1,f) && journal.acknowledged(1,f,true) })
        val engine=MappingEngine(composer,p.geometry,p.bindings)
        engine.setCameraEnabled(true);assertTrue(engine.relativeMouse(20f,10f));engine.mouseButton(1,true)
        val fireOwner=p.bindings.touches.single { it.button==1 }.owner
        val fire=composer.snapshot().single { it.owner==fireOwner }
        repeat(12) {
            for(direction in listOf(MappingEngine.W,MappingEngine.A,MappingEngine.S,MappingEngine.D)) {
                assertTrue(engine.key(direction,true));assertTrue(engine.lastOperationSucceeded)
                assertTrue(engine.relativeMouse(350f,-520f))
                assertEquals(fire,composer.snapshot().single { it.owner==fireOwner })
                assertTrue(engine.key(direction,false));assertTrue(engine.lastOperationSucceeded)
                assertEquals(2,composer.activeCount)
            }
            engine.key(MappingEngine.W,true);engine.key(MappingEngine.D,true)
            assertTrue(engine.relativeMouse(-420f,610f))
            engine.key(MappingEngine.W,false);engine.key(MappingEngine.D,false)
        }
        assertFalse(composer.paused);assertFalse(composer.releaseUnverified)
        assertEquals(1L,engine.fireHoldStarts);assertEquals(0L,engine.fireHoldEnds)
        engine.mouseButton(1,false);assertTrue(engine.pause());assertEquals(0,journal.snapshot().possibleContacts)
    }
    @Test fun actualProfileKeepsFireDownAcrossUnlimitedCameraHandoffsWithMovementAndAction() {
        val profile=GameProfile.create("com.example.game","Test",1000,2000,0).let {
            it.copy(calibration=it.calibration.copy(confirmed=true),camera=it.camera.copy(mode=CameraSpec.BOUNDED),additionalActions=listOf(ActionSpec(.85f,.65f,"mouse",1)))
        }
        val prepared=ProfileCompiler.prepare(profile,1000,2000,0)
        assertFalse(prepared.bindings.touches.single { it.button==1 }.mouseAim)
        val journal=RecoveryJournal().apply { check(begin(1,1000,2000,10001)) }
        val frames=mutableListOf<TouchFrame>();var now=1000L
        val composer=TouchComposer({++now},{ f -> frames.add(f);journal.prepare(1,f) && journal.acknowledged(1,f,true) })
        val engine=MappingEngine(composer,prepared.geometry,prepared.bindings)
        engine.key(MappingEngine.W,true);engine.key(MappingEngine.SPACE,true)
        engine.setCameraEnabled(true);engine.relativeMouse(10f,0f);engine.mouseButton(1,true)
        val fire=composer.snapshot().single { it.owner==prepared.bindings.touches.single { t -> t.button==1 }.owner }
        val held=composer.snapshot().filter { it.owner!=MappingEngine.CAMERA_OWNER }
        val heldIndices=held.associate { c -> c.id to composer.snapshot().indexOf(c) }
        val cameraIndex=composer.snapshot().indexOfFirst { it.owner==MappingEngine.CAMERA_OWNER }
        assertEquals(composer.activeCount-1,cameraIndex)
        val start=frames.size
        repeat(30) {
            assertTrue(engine.relativeMouse(400f,-700f))
            held.forEach { c -> assertEquals(c,composer.snapshot().first { it.owner==c.owner }) }
            assertEquals(cameraIndex,composer.snapshot().indexOfFirst { it.owner==MappingEngine.CAMERA_OWNER })
        }
        assertTrue(engine.cameraRestarts>30)
        for(frame in frames.drop(start)) for((id,index) in heldIndices) assertEquals(index,frame.ids.indexOf(id))
        for(frame in frames.filter { fire.id in it.ids }) {
            val index=frame.ids.indexOf(fire.id)
            assertEquals(fire.x,frame.xs[index],.001f);assertEquals(fire.y,frame.ys[index],.001f)
            if(frame.action and 255 in listOf(1,6)) assertNotEquals(fire.id,frame.ids[frame.action ushr 8])
        }
        engine.mouseButton(1,false)
        assertEquals(1L,engine.fireHoldStarts);assertEquals(1L,engine.fireHoldEnds)
        assertEquals(0L,engine.fireBoundaryClamps)
        assertFalse(composer.hasOwner(fire.owner))
        assertEquals(3,composer.activeCount)
        assertTrue(engine.pause());assertEquals(0,journal.snapshot().possibleContacts)
    }
}
