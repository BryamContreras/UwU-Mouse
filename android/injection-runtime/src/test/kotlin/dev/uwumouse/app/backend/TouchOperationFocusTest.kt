package dev.uwumouse.app.backend

import org.junit.Assert.*
import org.junit.Test

class TouchOperationFocusTest {
    @Test fun twoReadyStagesShareOnlyAYoungPositiveQuery() {
        var now=0L
        val gate=TouchOperationFocus({now},{now+=1_000_000L;true})
        assertTrue(gate.allowsStage())
        now+=2_000_000L
        assertTrue(gate.allowsStage())
        assertEquals(1L,gate.queries);assertEquals(1L,gate.reused)
    }

    @Test fun slowQueriesStillAdmitTheirOwnStagesWithoutBecomingYoungOrAddingPrefetch() {
        var now=0L
        val gate=TouchOperationFocus({now},{now+=30_000_000L;true})
        assertTrue(gate.allowsStage())
        now+=1_000_000L
        assertTrue(gate.allowsStage())
        assertEquals(2L,gate.queries);assertEquals(0L,gate.reused)
        assertEquals(61_000_000L,now)
    }

    @Test fun anAckLongerThanTheObservationBudgetRequiresANewQuery() {
        var now=0L
        val gate=TouchOperationFocus({now},{true})
        assertTrue(gate.allowsStage())
        now=8_000_001L
        assertTrue(gate.allowsStage())
        assertEquals(2L,gate.queries)
    }

    @Test fun deniedAndFailedQueriesCannotBeRenewedWithinTheOperation() {
        for(throws in listOf(false,true)) {
            var calls=0
            val gate=TouchOperationFocus({0L},{calls++;if(throws) error("query failed") else false})
            assertFalse(gate.allowsStage());assertFalse(gate.allowsStage())
            assertEquals(1,calls)
        }
    }

    @Test fun expiryCanRevealFocusLossBeforeTheSecondCommand() {
        var now=0L;var focused=true
        val gate=TouchOperationFocus({now},{focused})
        assertTrue(gate.allowsStage())
        now=9_000_000L;focused=false
        assertFalse(gate.allowsStage())
        focused=true
        assertFalse(gate.allowsStage())
        assertEquals(2L,gate.queries)
    }

    @Test fun clockRegressionCannotResurrectAnExpiredSlowObservation() {
        var now=0L
        val gate=TouchOperationFocus({now},{now=30_000_000L;true})
        assertTrue(gate.allowsStage())
        now=5_000_000L
        assertFalse(gate.allowsStage())
        now=40_000_000L
        assertFalse(gate.allowsStage())
        assertEquals(1L,gate.queries)
    }

    @Test fun aRegressionDuringTheQueryOrNegativeTimeFailsClosed() {
        var now=10L
        val gate=TouchOperationFocus({now},{now=9L;true})
        assertFalse(gate.allowsStage())
        val negative=TouchOperationFocus({-1L},{error("must not query")})
        assertFalse(negative.allowsStage())
        assertEquals(0L,negative.queries)
    }

    @Test fun metadataIsReadAfterTheFreshQueryAndCanVetoTheStage() {
        var now=0L;var blocked=false
        val gate=TouchOperationFocus({now},{now+=30_000_000L;blocked=true;true})
        assertFalse(gate.allowsStage { !blocked })
        blocked=false
        assertFalse(gate.allowsStage { true })
        assertEquals(1L,gate.queries)
    }

    @Test fun slowMetadataCannotKeepAnExpiredCachedObservationAlive() {
        var now=0L;var reads=0
        val gate=TouchOperationFocus({now},{true})
        assertTrue(gate.allowsStage())
        assertTrue(gate.allowsStage {
            reads++
            if(reads==1) now+=20_000_000L
            true
        })
        assertEquals(2,reads) // Refreshed metadata after the one necessary fresh query.
        assertEquals(2L,gate.queries);assertEquals(0L,gate.reused)
    }
}
