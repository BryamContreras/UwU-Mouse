package dev.uwumouse.inputlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TouchSequenceValidatorTest {
    @Test
    fun threeContactsReleaseIndependentlyAndKeepDownTime() {
        val validator = TouchSequenceValidator()
        assertTrue(validator.accept(0, 0, 123L, intArrayOf(7)).isEmpty())
        assertTrue(validator.accept(5, 1, 123L, intArrayOf(7, 2)).isEmpty())
        assertTrue(validator.accept(5, 2, 123L, intArrayOf(7, 2, 19)).isEmpty())
        assertTrue(validator.accept(2, 0, 123L, intArrayOf(7, 2, 19)).isEmpty())
        assertTrue(validator.accept(6, 1, 123L, intArrayOf(7, 2, 19)).isEmpty())
        assertEquals(setOf(7, 19), validator.pendingIds)
        assertTrue(validator.accept(6, 0, 123L, intArrayOf(7, 19)).isEmpty())
        assertEquals(setOf(19), validator.pendingIds)
        assertTrue(validator.accept(1, 0, 123L, intArrayOf(19)).isEmpty())
        assertTrue(validator.pendingIds.isEmpty())
    }

    @Test
    fun pointerDownIndexMustIdentifyNewContact() {
        val validator = TouchSequenceValidator()
        validator.accept(0, 0, 123L, intArrayOf(7))
        val failures = validator.accept(5, 0, 123L, intArrayOf(7, 2))
        assertTrue(failures.any { "ya activo" in it })
    }

    @Test
    fun pointerUpCannotBeTheLastContact() {
        val validator = TouchSequenceValidator()
        validator.accept(0, 0, 123L, intArrayOf(7))
        assertTrue(validator.accept(6, 0, 123L, intArrayOf(7)).isNotEmpty())
    }

    @Test
    fun upCannotSilentlyReleaseSeveralContacts() {
        val validator = TouchSequenceValidator()
        validator.accept(0, 0, 123L, intArrayOf(7))
        validator.accept(5, 1, 123L, intArrayOf(7, 2))
        assertTrue(validator.accept(1, 0, 123L, intArrayOf(7, 2)).isNotEmpty())
    }

    @Test
    fun checksIdsMissingDownAndDownTime() {
        val validator = TouchSequenceValidator()
        assertTrue(validator.accept(2, 0, 123L, intArrayOf(7)).isNotEmpty())
        assertTrue(validator.accept(2, 0, 124L, intArrayOf(7)).isNotEmpty())
        assertTrue(validator.accept(5, 1, 124L, intArrayOf(7, 7)).isNotEmpty())
        assertTrue(validator.accept(0, 0, 125L, intArrayOf(32)).isNotEmpty())
    }

    @Test
    fun cancelResetsWholeSequenceAndNewDownUsesNewTimebase() {
        val validator = TouchSequenceValidator()
        validator.accept(0, 0, 123L, intArrayOf(7))
        validator.accept(5, 1, 123L, intArrayOf(7, 2))
        assertTrue(validator.accept(3, 0, 123L, intArrayOf(7, 2)).isEmpty())
        assertTrue(validator.pendingIds.isEmpty())
        assertTrue(validator.accept(0, 0, 456L, intArrayOf(31)).isEmpty())
    }

    @Test
    fun cancelWithThreeContactsEndsAllAndAcceptsANewGestureWithoutReset() {
        val validator = TouchSequenceValidator()
        assertTrue(validator.accept(0, 0, 100L, intArrayOf(4)).isEmpty())
        assertTrue(validator.accept(5, 1, 100L, intArrayOf(4, 11)).isEmpty())
        assertTrue(validator.accept(5, 0, 100L, intArrayOf(29, 4, 11)).isEmpty())
        assertEquals(setOf(4, 11, 29), validator.pendingIds)
        assertTrue(validator.accept(3, 0, 100L, intArrayOf(29, 4, 11)).isEmpty())
        assertTrue(validator.pendingIds.isEmpty())
        assertTrue(validator.accept(0, 0, 200L, intArrayOf(11)).isEmpty())
        assertTrue(validator.accept(1, 0, 200L, intArrayOf(11)).isEmpty())
        assertTrue(validator.pendingIds.isEmpty())
    }

    @Test
    fun cancelAfterPartialReleaseUsesOnlyRemainingContacts() {
        val validator = TouchSequenceValidator()
        validator.accept(0, 0, 100L, intArrayOf(4))
        validator.accept(5, 1, 100L, intArrayOf(4, 11))
        validator.accept(5, 2, 100L, intArrayOf(4, 11, 29))
        assertTrue(validator.accept(6, 1, 100L, intArrayOf(4, 11, 29)).isEmpty())
        assertEquals(setOf(4, 29), validator.pendingIds)
        assertTrue(validator.accept(3, 0, 100L, intArrayOf(4, 29)).isEmpty())
        assertTrue(validator.pendingIds.isEmpty())
    }

    @Test
    fun randomizedValidStreamsDoNotReportErrorsOrLosePendingIds() {
        val random = Random(929)
        repeat(100) { trial ->
            val validator = TouchSequenceValidator()
            val active = mutableListOf<Int>()
            val downTime = trial.toLong() * 1000
            repeat(100) {
                val action: Int
                val index: Int
                if (active.isEmpty() || (active.size < 10 && random.nextBoolean())) {
                    val nextId = (0..31).filter { it !in active }.random(random)
                    active.add(nextId)
                    action = if (active.size == 1) 0 else 5
                    index = if (active.size == 1) 0 else active.lastIndex
                } else {
                    action = if (active.size == 1) 1 else 6
                    index = random.nextInt(active.size)
                }
                val failures = validator.accept(action, index, downTime, active.toIntArray())
                assertTrue("trial=$trial action=$action failures=$failures", failures.isEmpty())
                if (action == 1 || action == 6) active.removeAt(index)
                assertEquals(active.toSet(), validator.pendingIds)
            }
            assertTrue(validator.accept(3, 0, downTime, active.toIntArray()).isEmpty())
            assertTrue(validator.pendingIds.isEmpty())
        }
    }
}
