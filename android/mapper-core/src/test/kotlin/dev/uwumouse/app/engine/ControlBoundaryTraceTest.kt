package dev.uwumouse.app.engine

import dev.uwumouse.app.profile.ActionSpec
import dev.uwumouse.app.profile.GameProfile
import dev.uwumouse.app.profile.ProfileCompiler
import org.junit.Assert.*
import org.junit.Test

class ControlBoundaryTraceTest {
    private class Harness(val compact:Boolean) {
        private val profile=GameProfile.create("com.dts.freefireth","Control trajectory",1920,1080,0).let {
            it.copy(calibration=it.calibration.copy(confirmed=true),action=it.action.copy(triggerKind="mouse",code=1))
        }
        private val prepared=ProfileCompiler.prepare(profile,1920,1080,0)
        val frames=mutableListOf<TouchFrame>()
        var compactCalls=0
        val composer=TouchComposer({1000L},{ frames+=it;true },continuousCamera=true,orderedContacts=true,
            transitionSink=if(!compact) null else ({ sequence ->
                compactCalls++;frames+=sequence.frames;TouchTransitionResult(2,2)
            }))
        val engine=MappingEngine(composer,prepared.geometry,prepared.bindings)
        init { check(engine.setCameraEnabled(true));check(engine.relativeMouse(1f,0f)) }

        fun camera(vararg dx:Float) {
            check(composer.beginMoveBatch())
            for(value in dx) check(engine.relativeMouse(value,2f))
        }

        fun control(key:Int?=null,button:Int?=null,down:Boolean) {
            if(compact) check(composer.beginControlBoundary()) else check(composer.endMoveBatch())
            try {
                check(if(key!=null) engine.key(key,down) else engine.mouseButton(requireNotNull(button),down))
                check(engine.lastOperationSucceeded)
            } finally {
                if(compact) composer.endControlBoundary()
            }
            check(composer.endMoveBatch())
        }
    }

    private fun sameTrace(legacy:Harness,compact:Harness) {
        assertEquals(legacy.frames.size,compact.frames.size)
        for((expected,actual) in legacy.frames.zip(compact.frames)) {
            assertEquals(expected.action,actual.action)
            assertEquals(expected.downTime,actual.downTime);assertEquals(expected.eventTime,actual.eventTime)
            assertArrayEquals(expected.ids,actual.ids);assertArrayEquals(expected.xs,actual.xs,0f);assertArrayEquals(expected.ys,actual.ys,0f)
        }
        assertEquals(legacy.composer.snapshot(),compact.composer.snapshot())
    }

    @Test fun realJoystickDownDiagonalChangesAndReleasePreserveTheCompleteLegacyTrace() {
        val legacy=Harness(false);val compact=Harness(true)
        for((key,down) in listOf(51 to true,29 to true,29 to false,32 to true,32 to false,51 to false)) {
            val before=legacy.frames.size
            for(h in listOf(legacy,compact)) { h.camera(7f,-2f,11f);h.control(key=key,down=down) }
            sameTrace(legacy,compact)
            if(key!=51) {
                // Existing joystick contact moves: first publish camera at the old joystick
                // position, then publish the new diagonal/cardinal joystick position.
                val changes=compact.frames.drop(before)
                assertEquals(listOf(TouchComposer.MOVE,TouchComposer.MOVE),changes.map { it.action })
                assertArrayEquals(changes[0].ids,changes[1].ids)
                val joystick=changes[0].ids.indexOfFirst { it!=TouchCoordinatePolicy.CAMERA_ID }
                assertTrue(changes[0].xs[joystick]!=changes[1].xs[joystick] || changes[0].ys[joystick]!=changes[1].ys[joystick])
                val camera=changes[0].ids.indexOf(TouchCoordinatePolicy.CAMERA_ID)
                assertEquals(changes[0].xs[camera],changes[1].xs[camera],0f)
                assertEquals(changes[0].ys[camera],changes[1].ys[camera],0f)
            }
        }
        assertEquals(2,compact.compactCalls) // First joystick DOWN and its final UP still use MOVE/edge transactions.
    }

    @Test fun fireAndJoystickTogetherKeepEveryContactEdgeAndCameraPositionInOrder() {
        val legacy=Harness(false);val compact=Harness(true)
        for(h in listOf(legacy,compact)) { h.camera(5f);h.control(key=51,down=true) }
        sameTrace(legacy,compact)
        for(h in listOf(legacy,compact)) { h.camera(12f,-3f);h.control(button=1,down=true) }
        sameTrace(legacy,compact)
        for(h in listOf(legacy,compact)) { h.camera(-7f,2f);h.control(key=29,down=true) }
        sameTrace(legacy,compact)
        for(h in listOf(legacy,compact)) { h.camera(8f);h.control(button=1,down=false) }
        sameTrace(legacy,compact)
        for(h in listOf(legacy,compact)) { h.camera(-6f);h.control(key=29,down=false) }
        sameTrace(legacy,compact)
        for(h in listOf(legacy,compact)) { h.camera(10f);h.control(key=51,down=false) }
        sameTrace(legacy,compact)
        assertEquals(4,compact.compactCalls)
    }

    @Test fun leavingTheBoundaryKeepsPhysicalCameraSamplesCoalescedAsBefore() {
        val h=Harness(true)
        assertTrue(h.composer.beginControlBoundary())
        assertFalse(h.composer.beginControlBoundary()) // A nested scope cannot steal the outer owner.
        h.composer.endControlBoundary();h.composer.endControlBoundary()
        val before=h.frames.size
        h.camera(1f,2f,3f,4f)
        assertEquals(before,h.frames.size)
        assertTrue(h.composer.endMoveBatch())
        assertEquals(before+1,h.frames.size)
        assertEquals(TouchComposer.MOVE,h.frames.last().action)
    }

    @Test fun cancellationDiscardAndAcknowledgedResetClearTheBoundaryWithoutReplayingMotion() {
        val h=Harness(true)
        h.camera(10f)
        assertTrue(h.composer.beginControlBoundary())
        h.composer.discardMoveBatch()
        assertTrue(h.composer.beginControlBoundary());h.composer.endControlBoundary()
        assertTrue(h.composer.beginControlBoundary());assertTrue(h.composer.cancelAll())
        h.composer.endControlBoundary();h.composer.endControlBoundary()
        assertFalse(h.composer.beginControlBoundary())
        assertTrue(h.composer.resume())
        assertTrue(h.composer.beginControlBoundary())
        h.composer.acknowledgeBackendReset()
        assertFalse(h.composer.beginControlBoundary())
        assertTrue(h.composer.resume())
        assertTrue(h.composer.beginControlBoundary());h.composer.endControlBoundary()
        assertEquals(TouchComposer.CANCEL,h.frames.last().action)
    }

    @Test fun failedPreControlMoveCannotMutateTheJoystickBeforeItsRecoveryCandidateIsFixed() {
        val sent=mutableListOf<TouchFrame>()
        var reject=false
        val composer=TouchComposer({1000L},{ sent+=it;!reject },continuousCamera=true,orderedContacts=true,
            transitionSink={ error("A joystick MOVE must not become a contact-edge transaction") })
        assertTrue(composer.begin(MappingEngine.CAMERA_OWNER,500f,300f,preferredId=9))
        assertTrue(composer.begin(MappingEngine.JOYSTICK_OWNER,100f,200f))
        assertTrue(composer.beginMoveBatch());assertTrue(composer.move(MappingEngine.CAMERA_OWNER,520f,310f))
        assertTrue(composer.beginControlBoundary());reject=true
        assertFalse(composer.move(MappingEngine.JOYSTICK_OWNER,130f,170f))
        composer.endControlBoundary();composer.endControlBoundary()
        assertTrue(composer.paused);assertTrue(composer.releaseUnverified)
        val recovery=requireNotNull(composer.recoveryFrame())
        val joystick=recovery.ids.indexOfFirst { it!=9 }
        assertEquals(100f,recovery.xs[joystick],0f);assertEquals(200f,recovery.ys[joystick],0f)
        val camera=recovery.ids.indexOf(9)
        assertEquals(520f,recovery.xs[camera],0f);assertEquals(310f,recovery.ys[camera],0f)
        assertEquals(listOf(TouchComposer.MOVE,TouchComposer.CANCEL),sent.takeLast(2).map { it.action })
        assertFalse(composer.beginControlBoundary())
    }
}
