package dev.uwumouse.app.session

import org.junit.Assert.*
import org.junit.Test

class RollingLatencySamplesTest {
    @Test fun resetExcludesPreviousSessionAndUnwrittenSlots() {
        val samples = RollingLatencySamples(512)
        repeat(512) { samples.record(90_000_000) }
        samples.clear()
        samples.record(2_000_000)
        assertEquals(listOf(2.0, 2.0, 2.0), RollingLatencySamples.percentilesMillis(samples.snapshot()))
        samples.clear()
        assertTrue(RollingLatencySamples.percentilesMillis(samples.snapshot()).isEmpty())
    }

    @Test fun cameraResetWindowRetainsExactly512SamplesAfterManyWraps() {
        val samples = RollingLatencySamples(512)
        repeat(10_000) { samples.record((it + 1) * 1_000_000L) }
        val snapshot = samples.snapshot()
        assertEquals(512, snapshot.size)
        assertEquals(9_489_000_000L, snapshot.min())
        assertEquals(10_000_000_000L, snapshot.max())
        assertEquals(listOf(9744.0, 9974.0, 9994.0), RollingLatencySamples.percentilesMillis(snapshot))
    }

    @Test fun snapshotIsDetachedFromLaterWritesAndSorting() {
        val samples = RollingLatencySamples(3)
        samples.record(3_000_000); samples.record(1_000_000); samples.record(2_000_000)
        val snapshot = samples.snapshot()
        RollingLatencySamples.percentilesMillis(snapshot)
        samples.record(4_000_000)
        assertArrayEquals(longArrayOf(1_000_000, 2_000_000, 3_000_000), snapshot)
        assertArrayEquals(longArrayOf(4_000_000, 1_000_000, 2_000_000), samples.snapshot())
    }

    @Test fun capacityMustBePositive() {
        assertThrows(IllegalArgumentException::class.java) { RollingLatencySamples(0) }
        assertThrows(IllegalArgumentException::class.java) { RollingLatencySamples(-1) }
    }
}
