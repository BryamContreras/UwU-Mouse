package dev.mobimapper.mobimapper.engine

import dev.mobimapper.mobimapper.backend.recovery.RecoveryJournal
import org.junit.Assert.*
import org.junit.Test

class TouchMoveBatchTest {
    private data class Signature(val action:Int,val ids:List<Int>,val xs:List<Float>,val ys:List<Float>)
    private fun signature(frame:TouchFrame)=Signature(frame.action,frame.ids.toList(),frame.xs.toList(),frame.ys.toList())
    private class Harness(bindings:MappingBindings=MappingBindings()) {
        val frames=mutableListOf<TouchFrame>()
        var now=1_000L
        val journal=RecoveryJournal().apply { check(begin(1,1000,2000,10001)) }
        val composer=TouchComposer({ ++now },{ frame ->
            frames+=frame
            check(journal.prepare(1,frame)) { "Rejected transition ${frame.action}" }
            check(journal.acknowledged(1,frame,true))
            true
        })
        val engine=MappingEngine(composer,MappingGeometry(1000f,2000f),bindings).apply { setCameraEnabled(true) }
    }

    @Test fun boundedSegmentationAndHandoffKeepEveryContactEdgeAndFinalPosition() {
        for(overlap in listOf(false,true)) {
            val bindings=MappingBindings(distinctCameraContacts=true,overlapCameraHandoff=overlap)
            val exact=Harness(bindings);val batched=Harness(bindings)
            for(h in listOf(exact,batched)) {
                assertTrue(h.engine.key(MappingEngine.W,true))
                assertTrue(h.engine.key(MappingEngine.SPACE,true))
            }
            val moves=List(64) { 1f to .5f }+listOf(160f to 0f,20f to 0f,-360f to 0f,
                420f to 50f,0f to -100f,500f to 800f,-900f to -1600f,.25f to .75f)
            assertTrue(batched.composer.beginMoveBatch())
            for((x,y) in moves) {
                assertTrue(exact.engine.relativeMouse(x,y))
                assertTrue(batched.engine.relativeMouse(x,y))
            }
            assertTrue(batched.composer.endMoveBatch())
            assertEquals(exact.composer.snapshot(),batched.composer.snapshot())
            assertEquals(exact.engine.cameraRestarts,batched.engine.cameraRestarts)
            assertEquals(exact.frames.filter {it.action!=TouchComposer.MOVE}.map(::signature),
                batched.frames.filter {it.action!=TouchComposer.MOVE}.map(::signature))
            assertTrue(batched.frames.size<exact.frames.size)
            for(h in listOf(exact,batched)) {
                assertTrue(h.engine.pause())
                assertEquals(0,h.journal.snapshot().possibleContacts)
                assertFalse(h.composer.releaseUnverified)
            }
        }
    }

    @Test fun fireClampsAndReversalsIntegrateEachRawSampleBeforeOneFinalMove() {
        val fire=PreparedTouch("touch:fire",850f,1600f,button=1,mouseAim=true)
        val bindings=MappingBindings(actionButton=null,touches=listOf(fire),distinctCameraContacts=true,
            fireDragBounds=AimBounds(50f,100f,949f,1899f))
        val exact=Harness(bindings);val batched=Harness(bindings)
        for(h in listOf(exact,batched)) {
            assertTrue(h.engine.key(MappingEngine.W,true))
            assertTrue(h.engine.mouseButton(1,true))
        }
        assertTrue(batched.composer.beginMoveBatch())
        for((x,y) in listOf(1_600f to -4_100f,-10f to 20f,4f to -2f,-3f to 1f)) {
            assertTrue(exact.engine.relativeMouse(x,y))
            assertTrue(batched.engine.relativeMouse(x,y))
        }
        assertEquals(exact.engine.fireBoundaryClamps,batched.engine.fireBoundaryClamps)
        assertEquals(exact.composer.snapshot(),batched.composer.snapshot())
        for(h in listOf(exact,batched)) assertTrue(h.engine.mouseButton(1,false))
        assertTrue(batched.composer.endMoveBatch())
        assertEquals(exact.composer.snapshot(),batched.composer.snapshot())
        assertEquals(exact.frames.filter {it.action!=TouchComposer.MOVE}.map(::signature),
            batched.frames.filter {it.action!=TouchComposer.MOVE}.map(::signature))
        assertTrue(batched.frames.size<exact.frames.size)
    }

    @Test fun contactEdgesFlushThePreviousMoveBeforeChangingItsPointerSet() {
        val frames=mutableListOf<TouchFrame>()
        val c=TouchComposer({1L},{frames+=it;true})
        assertTrue(c.begin("camera",10f,20f))
        assertTrue(c.beginMoveBatch())
        assertTrue(c.move("camera",30f,40f))
        assertEquals(listOf(0),frames.map {it.action})
        assertTrue(c.begin("fire",50f,60f))
        assertEquals(listOf(0,2,261),frames.map {it.action})
        assertArrayEquals(floatArrayOf(30f),frames[1].xs,0f)
        assertTrue(c.move("camera",70f,80f))
        assertTrue(c.end("fire"))
        assertEquals(listOf(0,2,261,2,262),frames.map {it.action})
        assertTrue(c.endMoveBatch())
        assertEquals(5,frames.size)
    }

    @Test fun discardedAndCancelledMovementCannotReplayOrChangeTheRecoveryCandidate() {
        val frames=mutableListOf<TouchFrame>()
        val c=TouchComposer({100L},{frames+=it;true})
        assertTrue(c.begin("camera",10f,20f))
        assertTrue(c.beginMoveBatch())
        assertTrue(c.move("camera",30f,40f))
        val candidate=requireNotNull(c.recoveryFrame())
        assertArrayEquals(floatArrayOf(10f),candidate.xs,0f)
        assertArrayEquals(floatArrayOf(20f),candidate.ys,0f)
        assertEquals(1,frames.size)
        // Reading recovery metadata must not deactivate delivery batching.
        assertTrue(c.move("camera",50f,60f))
        assertEquals(1,frames.size)
        c.discardMoveBatch()
        assertEquals(listOf(Contact("camera",0,10f,20f)),c.snapshot())
        assertTrue(c.endMoveBatch())
        assertEquals(1,frames.size)
        assertTrue(c.beginMoveBatch())
        assertTrue(c.move("camera",70f,80f))
        assertTrue(c.cancelAll())
        assertEquals(listOf(0,3),frames.map {it.action})
        assertArrayEquals(floatArrayOf(10f),frames.last().xs,0f)
        assertFalse(c.endMoveBatch())
    }

    @Test fun failedAttemptRetainsItsNewCoordinatesWhenCancellationIsAlsoUnverified() {
        val frames=mutableListOf<TouchFrame>()
        var accept=true
        val c=TouchComposer({100L},{frames+=it;accept})
        assertTrue(c.begin("camera",10f,20f))
        assertTrue(c.beginMoveBatch())
        assertTrue(c.move("camera",30f,40f))
        accept=false
        assertFalse(c.endMoveBatch())
        assertEquals(listOf(0,2,3),frames.map {it.action})
        assertTrue(c.paused);assertTrue(c.releaseUnverified)
        c.discardMoveBatch()
        assertEquals(listOf(Contact("camera",0,30f,40f)),c.snapshot())
        val candidate=requireNotNull(c.recoveryFrame())
        assertArrayEquals(floatArrayOf(30f),candidate.xs,0f)
        assertArrayEquals(floatArrayOf(40f),candidate.ys,0f)
        accept=true
        assertTrue(c.cancelAll())
        assertArrayEquals(floatArrayOf(30f),frames.last().xs,0f)
        assertFalse(c.releaseUnverified)
    }

    @Test fun failedFlushPreventsTheFollowingUpAndDoesNotEmitAnythingOnLaterBatchEnd() {
        val frames=mutableListOf<TouchFrame>()
        val c=TouchComposer({100L},{frames+=it;it.action!=TouchComposer.MOVE})
        assertTrue(c.begin("camera",10f,20f))
        assertTrue(c.beginMoveBatch())
        assertTrue(c.move("camera",30f,40f))
        assertFalse(c.end("camera"))
        assertEquals(listOf(0,2,3),frames.map {it.action})
        assertEquals(0,c.activeCount)
        assertFalse(c.endMoveBatch())
        assertEquals(3,frames.size)
    }
}
