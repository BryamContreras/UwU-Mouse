package dev.uwumouse.app.engine

import dev.uwumouse.app.backend.recovery.RecoveryJournal
import org.junit.Assert.*
import org.junit.Test

class BoundedCameraCheckpointTest {
    private class Harness(overlap:Boolean=false) {
        val geometry=MappingGeometry(1000f,2000f)
        val journal=RecoveryJournal().apply { check(begin(1,1000,2000,10001)) }
        var now=1000L
        val frames=mutableListOf<TouchFrame>()
        val composer=TouchComposer({++now},{frame ->
            frames+=frame
            check(journal.prepare(1,frame));check(journal.acknowledged(1,frame,true))
            true
        })
        val engine=MappingEngine(composer,geometry,MappingBindings(distinctCameraContacts=true,
            cameraLast=true,overlapCameraHandoff=overlap)).apply { check(setCameraEnabled(true)) }
    }

    @Test fun firstRawStepOfReplacementIsPublishedBeforeRestOfTransportBatch() {
        for(overlap in listOf(false,true)) {
            val h=Harness(overlap)
            assertTrue(h.engine.key(MappingEngine.W,true))
            assertTrue(h.engine.mouseButton(1,true))
            val controls=h.composer.snapshot()
            assertTrue(h.engine.relativeMouse(160f,0f))
            val initial=h.composer.snapshot().last()
            h.frames.clear()
            assertTrue(h.composer.beginMoveBatch())
            repeat(6) { assertTrue(h.engine.relativeMouse(2f,0f)) }
            assertEquals(1L,h.engine.cameraRestarts)
            val checkpoint=h.frames.last()
            assertEquals(TouchComposer.MOVE,checkpoint.action)
            assertEquals(h.geometry.cameraX+2f,checkpoint.xs.last(),0f)
            val replacementId=h.composer.snapshot().last().id
            assertNotEquals(initial.id,replacementId)
            val submittedCount=h.frames.size
            repeat(74) { assertTrue(h.engine.relativeMouse(2f,0f)) }
            assertEquals(submittedCount,h.frames.size) // The checkpoint kept batching enabled.
            assertTrue(h.composer.endMoveBatch())
            assertEquals(submittedCount+1,h.frames.size)
            assertEquals(h.geometry.cameraX+150f,h.frames.last().xs.last(),0f)
            assertEquals(controls,h.composer.snapshot().filter { it.owner!=MappingEngine.CAMERA_OWNER })
            val lastX=mutableMapOf(initial.id to initial.x)
            var integrated=0f
            for(frame in h.frames) {
                val action=frame.action and 255
                if(action==TouchComposer.DOWN || action==TouchComposer.POINTER_DOWN) {
                    val index=frame.action ushr 8
                    if(frame.ids[index] in 8..9) lastX[frame.ids[index]]=frame.xs[index]
                }
                if(action==TouchComposer.MOVE) for(index in frame.ids.indices) {
                    val id=frame.ids[index]
                    if(id in 8..9) {
                        integrated+=frame.xs[index]-requireNotNull(lastX[id])
                        lastX[id]=frame.xs[index]
                    }
                }
            }
            assertEquals(160f,integrated,0f)
            assertEquals(5,h.frames.size)
            assertTrue(h.engine.pause())
            assertEquals(0,h.journal.snapshot().possibleContacts)
        }
    }

    @Test fun explicitCheckpointRetainsBatchAndOnlyUnpublishedTailRollsBack() {
        val frames=mutableListOf<TouchFrame>()
        val composer=TouchComposer({1000L},{frames+=it;true})
        assertTrue(composer.begin("camera",10f,20f))
        assertTrue(composer.beginMoveBatch())
        assertTrue(composer.move("camera",30f,40f))
        assertTrue(composer.flushMoveBatch())
        assertEquals(listOf(0,2),frames.map { it.action })
        assertTrue(composer.move("camera",50f,60f))
        assertEquals(2,frames.size)
        assertArrayEquals(floatArrayOf(30f),requireNotNull(composer.recoveryFrame()).xs,0f)
        composer.discardMoveBatch()
        assertEquals(listOf(Contact("camera",0,30f,40f)),composer.snapshot())
        assertTrue(composer.cancelAll())
        assertArrayEquals(floatArrayOf(30f),frames.last().xs,0f)
        assertFalse(composer.flushMoveBatch())
    }

    @Test fun failedCheckpointPausesAtAttemptedNewStepAndNeverPublishesRemainingTail() {
        var now=1000L
        val frames=mutableListOf<TouchFrame>()
        var reject=false
        val composer=TouchComposer({++now},{frame -> frames+=frame;!reject || frame.action!=TouchComposer.MOVE && frame.action!=TouchComposer.CANCEL})
        val geometry=MappingGeometry(1000f,2000f)
        val engine=MappingEngine(composer,geometry,MappingBindings(distinctCameraContacts=true,cameraLast=true))
        assertTrue(engine.setCameraEnabled(true))
        assertTrue(engine.key(MappingEngine.W,true))
        assertTrue(engine.mouseButton(1,true))
        assertTrue(engine.relativeMouse(170f,0f))
        assertTrue(composer.beginMoveBatch())
        reject=true
        assertFalse(engine.relativeMouse(2f,0f))
        assertEquals(1L,engine.cameraRestarts)
        assertFalse(engine.lastOperationSucceeded)
        assertTrue(composer.paused);assertTrue(composer.releaseUnverified)
        val attempted=frames[frames.lastIndex-1]
        assertEquals(TouchComposer.MOVE,attempted.action)
        assertEquals(geometry.cameraX+2f,attempted.xs.last(),0f)
        val recovery=requireNotNull(composer.recoveryFrame())
        assertArrayEquals(attempted.ids,recovery.ids)
        assertArrayEquals(attempted.xs,recovery.xs,0f)
        assertArrayEquals(attempted.ys,recovery.ys,0f)
        val count=frames.size
        assertFalse(engine.relativeMouse(148f,0f))
        assertFalse(composer.endMoveBatch())
        assertEquals(count,frames.size)
        reject=false
        assertTrue(engine.pause())
        assertArrayEquals(attempted.xs,frames.last().xs,0f)
    }
}
