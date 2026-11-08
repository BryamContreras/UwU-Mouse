package dev.mobimapper.mobimapper.session

import org.junit.Assert.assertEquals
import org.junit.Test

class MouseButtonGateTest {
    @org.junit.Test fun observedReleasedBaselineAcceptsTheFirstNewCursorClick() {
        val gate=MouseButtonGate();gate.rebase(0)
        org.junit.Assert.assertEquals(1,gate.transitions(1))
        org.junit.Assert.assertEquals(1,gate.transitions(0))
    }
    @org.junit.Test fun aHeldButtonDoesNotBecomeCursorClickOnModeChange() {
        val gate=MouseButtonGate();gate.rebase(1)
        org.junit.Assert.assertEquals(0,gate.transitions(1))
        org.junit.Assert.assertEquals(0,gate.transitions(0))
        org.junit.Assert.assertEquals(1,gate.transitions(1))
    }
    @Test fun heldAtActivationRequiresReleaseAndFreshPress() {
        val gate = MouseButtonGate()
        assertEquals(0, gate.transitions(1))
        assertEquals(0, gate.transitions(1))
        assertEquals(0, gate.transitions(0))
        assertEquals(1, gate.transitions(1))
        assertEquals(1, gate.transitions(0))
    }

    @Test fun captureOffOnDoesNotResumeAPreviousPress() {
        val gate = MouseButtonGate()
        gate.transitions(0)
        assertEquals(1, gate.transitions(1))
        gate.reset()
        assertEquals(0, gate.transitions(1))
        assertEquals(0, gate.transitions(0))
        assertEquals(1, gate.transitions(1))
        assertEquals(1, gate.transitions(0))
    }

    @Test fun restBaselinePreservesNewPressesAndReleases() {
        val gate = MouseButtonGate()
        assertEquals(0, gate.transitions(0))
        assertEquals(1, gate.transitions(1))
        assertEquals(0, gate.transitions(1))
        assertEquals(1, gate.transitions(0))
    }

    @Test fun heldUnmappedButtonDoesNotSuppressOtherFreshButtons() {
        val gate = MouseButtonGate()
        assertEquals(0, gate.transitions(2))
        assertEquals(1, gate.transitions(3))
        assertEquals(0, gate.transitions(1))
        assertEquals(1, gate.transitions(0))
        assertEquals(2, gate.transitions(2))
    }

    @Test fun allButtonsHeldAtBaselineRemainSuppressedIndependently() {
        val gate = MouseButtonGate()
        assertEquals(0, gate.transitions(1 or 2 or 4 or 8 or 16))
        assertEquals(0, gate.transitions(2 or 4 or 8 or 16))
        assertEquals(1, gate.transitions(1 or 2 or 4 or 8 or 16))
        assertEquals(1, gate.transitions(2 or 4 or 8 or 16))
        assertEquals(0, gate.transitions(0))
        assertEquals(16, gate.transitions(16))
    }
}
