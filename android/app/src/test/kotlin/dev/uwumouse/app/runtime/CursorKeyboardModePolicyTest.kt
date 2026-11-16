package dev.uwumouse.app.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class CursorKeyboardModePolicyTest {
    private fun CursorKeyboardModePolicy.toggle()=apply(requireNotNull(requestToggle()))

    @Test fun firstAndRepeatedCursorPresentationNeverEnableOrdinaryText() {
        val policy=CursorKeyboardModePolicy()
        assertNull(policy.requestToggle())
        repeat(100) { policy.observe(true);assertFalse(policy.enabled);assertTrue(policy.blocksNewOrdinaryKeys()) }
        assertTrue(policy.toggle())
        assertTrue(policy.enabled)
        assertFalse(policy.blocksNewOrdinaryKeys())
    }

    @Test fun menuPausePreservesManualChoiceButCannotAcceptNewRequestsUntilCursorReturns() {
        val policy=CursorKeyboardModePolicy()
        policy.observe(true);assertTrue(policy.toggle())
        policy.observe(false)
        assertFalse(policy.available);assertTrue(policy.enabled);assertNull(policy.requestToggle())
        policy.observe(true)
        assertTrue(policy.enabled)
        assertTrue(policy.toggle());assertFalse(policy.enabled)
    }

    @Test fun temporaryMenuPauseDoesNotTurnAnOffChoiceIntoTextInput() {
        val policy=CursorKeyboardModePolicy()
        policy.observe(true);policy.observe(false)
        assertFalse(policy.enabled);assertTrue(policy.blocksNewOrdinaryKeys())
        policy.observe(true)
        assertFalse(policy.enabled);assertTrue(policy.blocksNewOrdinaryKeys())
    }

    @Test fun hidingAlwaysEndsTheChoiceWhetherItWasEnabledOrAlreadyOff() {
        for(enabled in listOf(false,true)) {
            val policy=CursorKeyboardModePolicy()
            policy.observe(true)
            if(enabled) assertTrue(policy.toggle())
            policy.observe(false,endCycle=true)
            assertFalse(policy.enabled);assertFalse(policy.available)
            assertFalse(policy.blocksNewOrdinaryKeys()) // Ordinary gameplay routing owns input again.
            policy.observe(true)
            assertFalse(policy.enabled);assertTrue(policy.blocksNewOrdinaryKeys())
        }
    }

    @Test fun manualIntentCapturedBeforeHideCannotEnableTheNextCursorCycle() {
        val policy=CursorKeyboardModePolicy()
        policy.observe(true)
        val old=requireNotNull(policy.requestToggle())
        policy.observe(false,endCycle=true);policy.observe(true)
        assertFalse(policy.apply(old));assertFalse(policy.enabled)
        assertTrue(policy.toggle());assertTrue(policy.enabled)
    }

    @Test fun pendingIntentIsInvalidatedByMenuAvailabilityChangeWithoutLosingExistingChoice() {
        val policy=CursorKeyboardModePolicy()
        policy.observe(true);assertTrue(policy.toggle())
        val oldOff=requireNotNull(policy.requestToggle())
        policy.observe(false);policy.observe(true)
        assertFalse(policy.apply(oldOff));assertTrue(policy.enabled)
    }

    @Test fun onlyOneOfTwoIntentsFromTheSameStateCanApply() {
        val policy=CursorKeyboardModePolicy()
        policy.observe(true)
        val first=requireNotNull(policy.requestToggle());val duplicate=requireNotNull(policy.requestToggle())
        assertTrue(policy.apply(first));assertFalse(policy.apply(duplicate));assertTrue(policy.enabled)
    }

    @Test fun sameOwnerMenuRearmPreservesManualChoiceAcrossItsAvailabilityGap() {
        val policy=CursorKeyboardModePolicy()
        policy.selectOwner("profile-a","example.game",9)
        policy.observe(true);assertTrue(policy.toggle())
        policy.observe(false)
        policy.selectOwner("profile-a","example.game",9)
        policy.observe(true)
        assertTrue(policy.enabled);assertFalse(policy.blocksNewOrdinaryKeys())
    }

    @Test fun pendingAcquisitionNeverClaimsToBlockAndroidOwnedKeysOrAcceptsManualEnable() {
        val policy=CursorKeyboardModePolicy()
        policy.observe(true,captureReady=false)
        assertFalse(policy.available);assertFalse(policy.enabled)
        assertFalse(policy.blocksNewOrdinaryKeys());assertNull(policy.requestToggle())
        policy.observe(true,captureReady=true)
        assertTrue(policy.available);assertFalse(policy.enabled);assertTrue(policy.blocksNewOrdinaryKeys())
    }

    @Test fun losingCaptureDisablesTextAndInvalidatesQueuedIntentWithoutRestoringItOnReacquisition() {
        val policy=CursorKeyboardModePolicy()
        policy.observe(true);assertTrue(policy.toggle())
        val old=requireNotNull(policy.requestToggle())
        policy.observe(true,captureReady=false)
        assertFalse(policy.available);assertFalse(policy.enabled);assertFalse(policy.blocksNewOrdinaryKeys())
        policy.observe(true,captureReady=true)
        assertFalse(policy.enabled);assertFalse(policy.apply(old));assertTrue(policy.blocksNewOrdinaryKeys())
        assertTrue(policy.toggle());assertTrue(policy.enabled)
    }

    @Test fun anotherProfilePackageOrDisplayCannotInheritManualChoiceOrPendingIntent() {
        for((profile,pkg,display) in listOf(Triple("profile-b","example.game",9),
            Triple("profile-a","another.game",9),Triple("profile-a","example.game",13))) {
            val policy=CursorKeyboardModePolicy()
            policy.selectOwner("profile-a","example.game",9)
            policy.observe(true);assertTrue(policy.toggle())
            val stale=requireNotNull(policy.requestToggle())
            policy.selectOwner(profile,pkg,display);policy.observe(true)
            assertFalse(policy.enabled);assertFalse(policy.apply(stale));assertTrue(policy.blocksNewOrdinaryKeys())
            assertTrue(policy.toggle());assertTrue(policy.enabled)
        }
    }

    @Test fun hideWinsAgainstAQueuedManualEnableAcrossThreads() {
        repeat(100) {
            val policy=CursorKeyboardModePolicy()
            policy.observe(true)
            val requested=CountDownLatch(1);val hidden=CountDownLatch(1)
            val applied=AtomicBoolean(true)
            val input=Thread {
                val request=requireNotNull(policy.requestToggle())
                requested.countDown()
                if(hidden.await(2,TimeUnit.SECONDS)) applied.set(policy.apply(request))
            }
            input.start()
            assertTrue(requested.await(2,TimeUnit.SECONDS))
            policy.observe(false,endCycle=true);policy.observe(true)
            hidden.countDown();input.join(2_000)
            assertFalse(input.isAlive);assertFalse(applied.get());assertFalse(policy.enabled)
        }
    }
}
