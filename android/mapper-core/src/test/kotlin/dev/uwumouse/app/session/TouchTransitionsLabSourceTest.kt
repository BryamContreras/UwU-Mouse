package dev.uwumouse.app.session

import org.junit.Assert.*
import org.junit.Test

class TouchTransitionsLabSourceTest {
    @Test fun fourSecondsUse250BatchesOfFourPerSecondWithExactClickAndDirectionPhases() {
        val source=TouchTransitionsLabSource()
        var at=0L
        var ticks=0
        var buttonEdges=0
        var keyEdges=0
        var held=false
        var direction=0
        while(true) {
            val step=source.advance(at)
            if(step.finished) break
            if(step.tick!=null) ticks++
            step.buttonHeld?.let { assertNotEquals(held,it);held=it;buttonEdges++ }
            step.directionKey?.let { if(direction!=0) keyEdges++;direction=it;keyEdges++ }
            assertTrue(step.nextAtMillis>at)
            at=step.nextAtMillis
        }
        if(held) buttonEdges++
        if(direction!=0) keyEdges++
        assertEquals(1_000,ticks)
        assertEquals(4_000,source.expectedSamples)
        assertEquals(80,buttonEdges)
        assertEquals(64,keyEdges)
        assertEquals(0,source.snapshot()["missedTicks"])
        assertEquals(source.expectedButtonEdges,buttonEdges)
        assertEquals(source.expectedKeyEdges,keyEdges)
    }

    @Test fun lateProducerSkipsOldMotionAndWholeMissedPhasesWithoutReplayingThem() {
        val source=TouchTransitionsLabSource(1_000)
        val first=source.advance(0)
        assertEquals(0,first.tick);assertEquals(true,first.buttonHeld);assertEquals(51,first.directionKey)
        val late=source.advance(260)
        assertEquals(65,late.tick)
        assertEquals(false,late.buttonHeld)
        assertEquals(47,late.directionKey)
        assertEquals(264L,late.nextAtMillis)
        val sameInstant=source.advance(260)
        assertNull(sameInstant.tick);assertNull(sameInstant.buttonHeld);assertNull(sameInstant.directionKey)
        assertEquals(64,source.snapshot()["missedTicks"])
        assertEquals(4L,source.snapshot()["missedButtonPhases"])
        assertEquals(1L,source.snapshot()["missedDirectionPhases"])
    }

    @Test fun finishingCountsOmittedMotionAndCannotGenerateAnotherHeldInput() {
        val source=TouchTransitionsLabSource(100)
        source.advance(0)
        val finished=source.advance(100)
        assertTrue(finished.finished)
        assertNull(finished.tick);assertNull(finished.buttonHeld);assertNull(finished.directionKey)
        assertEquals(24,source.snapshot()["missedTicks"])
        assertEquals(1L,source.snapshot()["missedButtonPhases"])
        assertTrue(source.advance(1_000).finished)
        assertEquals(1,source.snapshot()["sourceTicks"])
    }

    @Test fun nonAlignedDurationsHaveExplicitCleanupTargetsAndRejectRegressingTime() {
        val source=TouchTransitionsLabSource(125)
        assertEquals(32,source.expectedTicks)
        assertEquals(4,source.expectedButtonEdges)
        assertEquals(2,source.expectedKeyEdges)
        source.advance(10)
        assertThrows(IllegalArgumentException::class.java) { source.advance(9) }
        assertThrows(IllegalArgumentException::class.java) { TouchTransitionsLabSource(99) }
        assertThrows(IllegalArgumentException::class.java) { TouchTransitionsLabSource(10_001) }
    }
}
