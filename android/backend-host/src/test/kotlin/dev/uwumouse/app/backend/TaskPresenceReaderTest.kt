package dev.uwumouse.app.backend

import org.junit.Assert.*
import org.junit.Test

class TaskPresenceReaderTest {
    @Test fun focusOrDisplayChangesDoNotRemoveABackgroundTasksIdentity() {
        val reader=TaskPresenceReader({1000L}) {
            listOf(TaskIdentity(setOf("launcher")),TaskIdentity(setOf("game","external.login")),TaskIdentity(setOf("mapper")))
        }
        assertTrue(reader.snapshot("game").present)
        assertTrue(reader.snapshot("game").valid)
    }

    @Test fun cachedSamplesExpireAndTheirOriginalTimestampIsPreserved() {
        var now=1000L
        var queries=0
        val reader=TaskPresenceReader({now}) { queries++;emptyList() }
        assertEquals(1000L,reader.snapshot("game").capturedUptimeMs)
        now=1999L
        assertEquals(1000L,reader.snapshot("game").capturedUptimeMs)
        assertEquals(1,queries)
        now=2000L
        assertEquals(2000L,reader.snapshot("game").capturedUptimeMs)
        assertEquals(2,queries)
    }

    @Test fun failedOrTruncatedEnumerationCannotEstablishAbsence() {
        val failed=TaskPresenceReader({1L}) { error("Unavailable") }.snapshot("game")
        assertFalse(failed.valid)
        val truncated=TaskPresenceReader({1L}) {
            List(TaskPresenceReader.MAX_TASKS) { TaskIdentity(setOf("other")) }
        }.snapshot("game")
        assertFalse(truncated.valid)
        assertFalse(truncated.present)
        val found=TaskPresenceReader({1L}) {
            List(TaskPresenceReader.MAX_TASKS) { TaskIdentity(setOf("game")) }
        }.snapshot("game")
        assertTrue(found.valid)
        assertTrue(found.present)
    }

    @Test fun selectingAnotherPackageDoesNotReuseAnOldTargetsSnapshot() {
        var queries=0
        val reader=TaskPresenceReader({1000L}) { queries++;listOf(TaskIdentity(setOf("first"))) }
        assertTrue(reader.snapshot("first").present)
        assertFalse(reader.snapshot("second").present)
        assertEquals(2,queries)
    }

    @Test fun anUnidentifiedRunningTaskCannotEstablishAbsenceButAnEmptyStoppedTaskCan() {
        val unknown=TaskPresenceReader({1L}) {
            listOf(TaskIdentity(setOf("launcher")),TaskIdentity(emptySet()))
        }.snapshot("game")
        assertFalse(unknown.valid)
        assertFalse(unknown.present)
        val stopped=TaskPresenceReader({1L}) {
            listOf(TaskIdentity(setOf("launcher")),TaskIdentity(emptySet(),running=false))
        }.snapshot("game")
        assertTrue(stopped.valid)
        assertFalse(stopped.present)
    }

    @Test fun aStoppedTasksRetainedGameIntentDoesNotCountAsARunningGame() {
        val historical=TaskIdentity(setOf("game"),running=false)
        val stopped=TaskPresenceReader({1L}) {
            listOf(TaskIdentity(setOf("launcher")),historical)
        }.snapshot("game")
        assertTrue(stopped.valid)
        assertFalse(stopped.present)
        val minimized=TaskPresenceReader({1L}) {
            listOf(historical,TaskIdentity(setOf("game")))
        }.snapshot("game")
        assertTrue(minimized.valid)
        assertTrue(minimized.present)
    }
}
