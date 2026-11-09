package dev.uwumouse.app.engine

import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

class TouchComposerTest {
    private class Harness {
        val frames = mutableListOf<TouchFrame>()
        val time = AtomicLong(100)
        var accepting = true
        val composer = TouchComposer({ time.getAndIncrement() }, { frame ->
            frames.add(frame)
            accepting
        })
    }

    @Test fun threeIndependentContactsRetainIdsAndPartialRelease() {
        val h = Harness()
        val c = h.composer
        assertTrue(c.begin("joystick", 100f, 200f))
        assertTrue(c.begin("camera", 400f, 500f))
        assertTrue(c.begin("action", 700f, 800f))
        val ids = c.snapshot().associate { it.owner to it.id }
        assertTrue(c.end("camera"))
        assertEquals(2, c.activeCount)
        assertEquals(ids["joystick"], c.snapshot().first { it.owner == "joystick" }.id)
        assertEquals(ids["action"], c.snapshot().first { it.owner == "action" }.id)
        assertTrue(c.move("action", 710f, 810f))
        assertTrue(c.end("joystick"))
        assertTrue(c.end("action"))
        assertEquals(0, c.activeCount)
        assertEquals(listOf(0, 261, 517, 262, 2, 6, 1), h.frames.map { it.action })
        assertArrayEquals(intArrayOf(0, 1, 2), h.frames[3].ids)
        assertArrayEquals(intArrayOf(0, 2), h.frames[4].ids)
        validate(h.frames)
    }

    @Test fun failedDeliveryAttemptsCancelAndRetainsUnverifiedContacts() {
        val h = Harness()
        assertTrue(h.composer.begin("joystick", 1f, 2f))
        h.accepting = false
        assertFalse(h.composer.begin("camera", 3f, 4f))
        assertTrue(h.composer.paused)
        assertTrue(h.composer.releaseUnverified)
        assertEquals(2, h.composer.activeCount)
        assertEquals("RELEASE_UNVERIFIED", h.composer.lastError)
        assertEquals(TouchComposer.CANCEL, h.frames.last().action)
        assertFalse(h.composer.resume())
        h.accepting = true
        assertTrue(h.composer.cancelAll())
        assertFalse(h.composer.releaseUnverified)
        assertTrue(h.composer.resume())
        assertTrue(h.composer.begin("new", 5f, 6f))
        assertEquals(1, h.composer.activeCount)
    }

    @Test fun ambiguousInjectionFollowedByAcceptedCancelStaysPausedButEmpty() {
        var calls = 0
        val c = TouchComposer({ 42L }, { ++calls != 2 })
        assertTrue(c.begin("one", 1f, 1f))
        assertFalse(c.begin("two", 2f, 2f))
        assertEquals(3, calls)
        assertTrue(c.paused)
        assertFalse(c.releaseUnverified)
        assertEquals(0, c.activeCount)
        assertEquals("INJECTION_FAILED_CANCELLED", c.lastError)
        assertTrue(c.resume())
    }

    @Test fun contactLimitRejectsWithoutDisturbingExistingOwners() {
        val h = Harness()
        repeat(10) { assertTrue(h.composer.begin("p$it", it.toFloat(), 20f)) }
        val before = h.composer.snapshot()
        assertFalse(h.composer.begin("overflow", 50f, 50f))
        assertEquals("CONTACT_LIMIT", h.composer.lastError)
        assertEquals(before, h.composer.snapshot())
        assertFalse(h.composer.paused)
        assertTrue(h.composer.end("p4"))
        assertTrue(h.composer.begin("replacement", 5f, 5f))
        assertEquals(4, h.composer.snapshot().last().id)
        assertTrue(h.composer.cancelAll())
        validate(h.frames)
    }

    @Test fun invalidCoordinatesAndOwnersNeverReachSink() {
        val h = Harness()
        assertFalse(h.composer.begin("", 1f, 1f))
        assertFalse(h.composer.begin("x", Float.NaN, 1f))
        assertFalse(h.composer.begin("x", 1f, Float.POSITIVE_INFINITY))
        assertFalse(h.composer.begin("x", -1f, 1f))
        assertTrue(h.frames.isEmpty())
        assertTrue(h.composer.begin("x", 1f, 1f))
        assertFalse(h.composer.move("x", Float.NaN, 2f))
        assertEquals(1f, h.composer.snapshot().single().x, 0f)
    }

    @Test fun retainedSnapshotsAndFramesCannotChangeAfterMoveOrCameraHandoff() {
        val h=Harness()
        assertTrue(h.composer.begin("camera",10f,20f))
        val initial=h.composer.snapshot()
        val initialFrame=h.frames.single()
        assertTrue(h.composer.move("camera",30f,40f))
        val moved=h.composer.snapshot()
        val moveFrame=h.frames.last()
        assertTrue(h.composer.handoff("camera",50f,60f))
        assertEquals(listOf(Contact("camera",0,10f,20f)),initial)
        assertEquals(listOf(Contact("camera",0,30f,40f)),moved)
        assertArrayEquals(floatArrayOf(10f),initialFrame.xs,0f)
        assertArrayEquals(floatArrayOf(20f),initialFrame.ys,0f)
        assertArrayEquals(floatArrayOf(30f),moveFrame.xs,0f)
        assertArrayEquals(floatArrayOf(40f),moveFrame.ys,0f)
        assertTrue(h.composer.cancelAll())
        assertEquals(10f,initial.single().x,0f)
        assertEquals(30f,moved.single().x,0f)
        validate(h.frames)
    }

    @Test fun rejectedMoveKeepsItsExactCancellationCandidateWithoutMutatingOldSnapshot() {
        val h=Harness()
        assertTrue(h.composer.begin("camera",10f,20f))
        val initial=h.composer.snapshot()
        h.accepting=false
        assertFalse(h.composer.move("camera",30f,40f))
        assertTrue(h.composer.releaseUnverified)
        assertEquals(listOf(Contact("camera",0,30f,40f)),h.composer.snapshot())
        assertEquals(listOf(Contact("camera",0,10f,20f)),initial)
        assertEquals(TouchComposer.CANCEL,h.frames.last().action)
        assertArrayEquals(floatArrayOf(30f),h.frames.last().xs,0f)
        assertArrayEquals(floatArrayOf(40f),h.frames.last().ys,0f)
        h.accepting=true
        assertTrue(h.composer.cancelAll())
        assertEquals(0,h.composer.activeCount)
    }

    @Test fun sequenceTimesStayCompatibleWhenClockMovesBackwards() {
        var now = 1000L
        val frames = mutableListOf<TouchFrame>()
        val c = TouchComposer({ now }, { frames.add(it); true })
        c.begin("one", 1f, 1f)
        now = 900L
        c.move("one", 2f, 2f)
        now = 1001L
        c.end("one")
        now = 2000L
        c.begin("two", 3f, 3f)
        c.end("two")
        assertEquals(listOf(1000L, 1000L, 1000L, 2000L, 2000L), frames.map { it.downTime })
        validate(frames)
    }

    @Test fun randomSequencesNeverLeaveIllegalPointerTransitions() {
        val random = Random(88217)
        val h = Harness()
        repeat(12_000) {
            val owner = "owner${random.nextInt(13)}"
            when (random.nextInt(10)) {
                in 0..3 -> h.composer.begin(owner, random.nextFloat() * 1000, random.nextFloat() * 2000)
                in 4..6 -> if (h.composer.hasOwner(owner))
                    h.composer.move(owner, random.nextFloat() * 1000, random.nextFloat() * 2000)
                7, 8 -> h.composer.end(owner)
                9 -> { assertTrue(h.composer.cancelAll()); assertTrue(h.composer.resume()) }
            }
        }
        assertTrue(h.composer.cancelAll())
        assertEquals(0, h.composer.activeCount)
        validate(h.frames)
    }

    @Test fun concurrentOwnersAreSerializedIntoOneValidStream() {
        val h = Harness()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = (0..3).map { worker -> pool.submit {
                repeat(200) {
                    assertTrue(h.composer.begin("worker$worker", worker.toFloat(), 5f))
                    assertTrue(h.composer.move("worker$worker", worker + .5f, 6f))
                    assertTrue(h.composer.end("worker$worker"))
                }
            } }
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally { pool.shutdownNow() }
        assertEquals(0, h.composer.activeCount)
        validate(h.frames)
    }

    private fun validate(frames: List<TouchFrame>) {
        var active = emptyList<Int>()
        var start = 0L
        var lastTime = Long.MIN_VALUE
        for (f in frames) {
            val ids = f.ids.toList()
            assertEquals(ids.size, ids.toSet().size)
            assertTrue(ids.all { it in 0..9 })
            assertEquals(ids.size, f.xs.size)
            assertEquals(ids.size, f.ys.size)
            assertTrue(f.eventTime >= lastTime)
            assertTrue(f.eventTime >= f.downTime)
            val action = f.action and 255
            val index = f.action shr 8
            when (action) {
                TouchComposer.DOWN -> {
                    assertTrue(active.isEmpty()); assertEquals(1, ids.size)
                    start = f.downTime; active = ids
                }
                TouchComposer.POINTER_DOWN -> {
                    assertEquals(active.size + 1, ids.size)
                    assertTrue(index in ids.indices)
                    assertEquals(active, ids.filterIndexed { i, _ -> i != index })
                    active = ids
                }
                TouchComposer.MOVE -> assertEquals(active, ids)
                TouchComposer.POINTER_UP -> {
                    assertEquals(active, ids); assertTrue(ids.size >= 2)
                    assertTrue(index in ids.indices)
                    active = ids.filterIndexed { i, _ -> i != index }
                }
                TouchComposer.UP -> { assertEquals(active, ids); assertEquals(1, ids.size); active = emptyList() }
                TouchComposer.CANCEL -> { assertEquals(active, ids); active = emptyList() }
                else -> fail("Unknown action $action")
            }
            assertEquals(start, f.downTime)
            lastTime = f.eventTime
        }
        assertTrue(active.isEmpty())
    }
}
