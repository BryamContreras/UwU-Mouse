package dev.uwumouse.app.backend

import org.junit.Assert.*
import org.junit.Test

class DisplayResolutionInheritedProofTest {
    private fun proof(producers:()->Set<Int>?,dispatcher:()->Boolean)=
        DisplayResolutionInheritedProof(producers,{setOf(999)},dispatcher,999)

    @Test fun anEmptyNewJournalCannotAuthorizeResizeWhileAnOldInjectorLives() {
        var dispatcherQueries=0
        val verifier=proof({setOf(123)}) { dispatcherQueries++;true }
        assertFalse(verifier.verified(null))
        assertEquals("A no-down snapshot is insufficient while a producer still exists",0,dispatcherQueries)
    }

    @Test fun producerQueryFailureAndDispatcherUnknownBothFailClosed() {
        assertFalse(proof({null},{true}).verified(null))
        assertFalse(proof({emptySet()},{false}).verified(null))
        assertFalse(proof({setOf(-1)},{true}).verified(null))
    }

    @Test fun absenceOfProducersIsConfirmedBeforeAndAfterTheNoDownObservation() {
        val order=mutableListOf<String>()
        val verifier=proof({order+="producers";emptySet()}) { order+="dispatcher";true }
        assertTrue(verifier.verified(null))
        assertEquals(listOf("producers","dispatcher","producers"),order)
    }

    @Test fun onlyTheOwnedIdleWorkerMayRemainAndASecondProducerBlocks() {
        assertTrue(proof({setOf(456)},{true}).verified(456))
        assertFalse(proof({setOf(123,456)},{true}).verified(456))
        assertFalse(proof({setOf(123)},{true}).verified(456))
        assertFalse(proof({emptySet()},{true}).verified(456))
        assertFalse(proof({emptySet()},{true}).verified(0))
    }

    @Test fun aProducerAppearingDuringTheDispatcherDumpInvalidatesTheProof() {
        var queries=0
        val verifier=proof({if(queries++==0) emptySet() else setOf(123)}) { true }
        assertFalse(verifier.verified(null))
        assertEquals(2,queries)
    }

    @Test fun anotherGuardianOrAnUnavailableGuardianQueryCannotAuthorizeRestoration() {
        var dumps=0
        assertFalse(DisplayResolutionInheritedProof({emptySet()},{setOf(998,999)},{dumps++;true},999).verified(null))
        assertFalse(DisplayResolutionInheritedProof({emptySet()},{null},{dumps++;true},999).verified(null))
        assertFalse(DisplayResolutionInheritedProof({emptySet()},{emptySet()},{dumps++;true},999).verified(null))
        assertEquals(0,dumps)
    }

    @Test fun anotherGuardianAppearingDuringTheDispatcherDumpInvalidatesRestoration() {
        var queries=0
        val verifier=DisplayResolutionInheritedProof({emptySet()},
            {if(queries++==0) setOf(999) else setOf(998,999)},{true},999)
        assertFalse(verifier.verified(null))
        assertEquals(2,queries)
    }
}
