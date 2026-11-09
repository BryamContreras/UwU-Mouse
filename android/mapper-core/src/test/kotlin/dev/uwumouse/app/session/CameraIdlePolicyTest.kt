package dev.uwumouse.app.session

import java.util.concurrent.CountDownLatch
import org.junit.Assert.*
import org.junit.Test

class CameraIdlePolicyTest {
    private val ms=1_000_000L

    @Test fun quietDeadlineUsesTheLatestReceiptOrCompletedAckAndItsExactBoundary() {
        val policy=CameraIdlePolicy()
        assertNull(policy.remainingIdleNanos(0))
        policy.motionReceived(100*ms)
        assertEquals(20*ms,policy.remainingIdleNanos(100*ms))
        assertEquals(1L,policy.remainingIdleNanos(120*ms-1))
        assertEquals(0L,policy.remainingIdleNanos(120*ms))
        policy.motionDelivered(150*ms)
        assertEquals(10*ms,policy.remainingIdleNanos(160*ms))
        assertEquals(0L,policy.remainingIdleNanos(170*ms))
    }

    @Test fun arrivalDuringAnAckWaitAndItsLaterDeliveryBothPostponeQuiet() {
        val policy=CameraIdlePolicy()
        policy.motionReceived(100*ms)
        // Another motion arrives while the first injection is still awaiting its ACK.
        policy.motionReceived(170*ms)
        policy.motionDelivered(180*ms)
        assertEquals(10*ms,policy.remainingIdleNanos(190*ms))
        policy.motionReceived(220*ms)
        assertEquals(20*ms,policy.remainingIdleNanos(220*ms))
        policy.motionDelivered(240*ms)
        assertEquals(1L,policy.remainingIdleNanos(260*ms-1))
        assertEquals(0L,policy.remainingIdleNanos(260*ms))
    }

    @Test fun bufferedSourceTimeCannotMakeFreshReceiptOrDeliveryAlreadyIdle() {
        val policy=CameraIdlePolicy()
        // Device/source time can precede these local callbacks by an arbitrary queue delay.
        policy.motionReceived(1_000*ms)
        policy.motionDelivered(1_020*ms)
        assertEquals(10*ms,policy.remainingIdleNanos(1_030*ms))
    }

    @Test fun resetDoesNotCreateADeadlineOrKeepThePreviousOne() {
        val policy=CameraIdlePolicy()
        policy.motionReceived(100*ms)
        policy.motionDelivered(120*ms)
        policy.reset()
        assertNull(policy.remainingIdleNanos(130*ms))
        assertNull(policy.remainingIdleNanos(10_000*ms))
        policy.motionReceived(10_001*ms)
        assertEquals(20*ms,policy.remainingIdleNanos(10_001*ms))
    }

    @Test fun regressionsCannotMoveEitherBaselineBackwardAndBackwardChecksNeverProveQuiet() {
        val policy=CameraIdlePolicy()
        policy.motionReceived(200*ms)
        policy.motionReceived(100*ms)
        policy.motionDelivered(250*ms)
        policy.motionDelivered(150*ms)
        assertEquals(20*ms,policy.remainingIdleNanos(240*ms))
        assertEquals(19*ms,policy.remainingIdleNanos(251*ms))
        assertEquals(0L,policy.remainingIdleNanos(270*ms))
    }

    @Test fun denseMotionCannotBecomeIdleBetweenReceiptsAndCompletedDeliveries() {
        val policy=CameraIdlePolicy()
        for(index in 0 until 1_000) {
            val now=index*ms
            policy.motionReceived(now)
            if(index%4==0) policy.motionDelivered(now+ms/2)
            assertTrue(requireNotNull(policy.remainingIdleNanos(now))>0L)
        }
        policy.motionDelivered(1_000*ms)
        assertEquals(1L,policy.remainingIdleNanos(1_020*ms-1))
        assertEquals(0L,policy.remainingIdleNanos(1_020*ms))
    }

    @Test fun independentCaptureAndWorkerThreadsRetainTheirLatestTimes() {
        val policy=CameraIdlePolicy()
        val start=CountDownLatch(1)
        val capture=Thread { start.await();for(now in 0L..10_000L) policy.motionReceived(now) }
        val worker=Thread { start.await();for(now in 0L..20_000L) policy.motionDelivered(now) }
        capture.start();worker.start();start.countDown();capture.join(5_000);worker.join(5_000)
        assertFalse(capture.isAlive);assertFalse(worker.isAlive)
        assertEquals(20*ms-1,policy.remainingIdleNanos(20_001L))
    }

    @Test fun invalidNegativeTimesAreRejectedAndPositiveLongBoundsDoNotOverflow() {
        assertThrows(IllegalArgumentException::class.java) { CameraIdlePolicy(0) }
        assertThrows(IllegalArgumentException::class.java) { CameraIdlePolicy(-1) }
        val policy=CameraIdlePolicy()
        assertThrows(IllegalArgumentException::class.java) { policy.motionReceived(-1) }
        assertThrows(IllegalArgumentException::class.java) { policy.motionDelivered(Long.MIN_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { policy.remainingIdleNanos(-1) }
        assertNull(policy.remainingIdleNanos(0))
        policy.motionReceived(Long.MAX_VALUE-10)
        assertEquals(20*ms-10,policy.remainingIdleNanos(Long.MAX_VALUE))
        val longest=CameraIdlePolicy(Long.MAX_VALUE)
        longest.motionDelivered(0)
        assertEquals(0L,longest.remainingIdleNanos(Long.MAX_VALUE))
    }

    @Test fun observedCadencesKeepFastMouseAtTwentyAndAdaptTo125100And60Hz() {
        for((period,expected) in listOf(1*ms to 20*ms,2*ms to 20*ms,
                8*ms to 24*ms,10*ms to 30*ms,16_666_667L to 50_000_001L)) {
            val policy=CameraIdlePolicy()
            repeat(40) { policy.sourceMotion(it*period) }
            assertEquals(expected,policy.quietThresholdNanos)
            policy.motionReceived(2_000*ms)
            assertEquals(1L,policy.remainingIdleNanos(2_000*ms+expected-1))
            assertEquals(0L,policy.remainingIdleNanos(2_000*ms+expected))
        }
    }

    @Test fun deliveryCapRequiresTwoFullPeriodsEvenWithoutSourceObservations() {
        for((rate,expected) in listOf(0 to 20*ms,1000 to 20*ms,500 to 20*ms,
                100 to 20*ms,90 to 22_222_224L,60 to 33_333_334L,1 to 2_000*ms)) {
            val policy=CameraIdlePolicy()
            policy.configureRate(rate)
            assertEquals(expected,policy.quietThresholdNanos)
            assertNull(policy.remainingIdleNanos(10_000*ms))
        }
    }

    @Test fun oldBufferedSourceClockOnlyChangesThresholdNotFreshLocalDeadline() {
        val policy=CameraIdlePolicy()
        policy.sourceMotion(0)
        policy.sourceMotion(8*ms)
        assertNull(policy.remainingIdleNanos(10_000*ms))
        policy.motionReceived(10_000*ms)
        policy.motionDelivered(10_010*ms)
        assertEquals(23*ms,policy.remainingIdleNanos(10_011*ms))
        assertEquals(0L,policy.remainingIdleNanos(10_034*ms))
    }

    @Test fun sourceRegressionsAndDuplicatesDoNotInventShorterIntervals() {
        val policy=CameraIdlePolicy()
        policy.sourceMotion(100*ms)
        policy.sourceMotion(108*ms)
        policy.sourceMotion(100*ms)
        policy.sourceMotion(108*ms)
        policy.sourceMotion(-1)
        policy.sourceMotion(Long.MIN_VALUE)
        policy.sourceMotion(116*ms)
        assertEquals(24*ms,policy.quietThresholdNanos)
        policy.sourceMotion(Long.MAX_VALUE)
        assertEquals(24*ms,policy.quietThresholdNanos)
    }

    @Test fun genuinePausesDoNotInflateTheMinimumAndTheNextAdjacentSampleStillCounts() {
        val policy=CameraIdlePolicy()
        policy.sourceMotion(0)
        policy.sourceMotion(8*ms)
        for(index in 1..40) policy.sourceMotion(index*1_000*ms)
        assertEquals(24*ms,policy.quietThresholdNanos)
        policy.sourceMotion(40_008*ms)
        assertEquals(24*ms,policy.quietThresholdNanos)
        val cold=CameraIdlePolicy()
        cold.sourceMotion(0)
        cold.sourceMotion(1_000*ms)
        assertEquals(20*ms,cold.quietThresholdNanos)
        cold.sourceMotion(1_010*ms)
        assertEquals(30*ms,cold.quietThresholdNanos)
    }

    @Test fun theMinimumAgesOutAfterTwoBlocksWhenMotionReallyBecomesSlower() {
        val policy=CameraIdlePolicy()
        var source=0L
        policy.sourceMotion(source)
        repeat(32) { source+=2*ms;policy.sourceMotion(source) }
        repeat(15) { source+=8*ms;policy.sourceMotion(source) }
        assertEquals(20*ms,policy.quietThresholdNanos)
        source+=8*ms;policy.sourceMotion(source)
        assertEquals(24*ms,policy.quietThresholdNanos)
        source+=1*ms;policy.sourceMotion(source)
        assertEquals(20*ms,policy.quietThresholdNanos)
    }

    @Test fun onePauseInsideTheWindowDoesNotReplaceTheActiveMotionEstimate() {
        val policy=CameraIdlePolicy()
        var source=0L
        policy.sourceMotion(source)
        repeat(16) { source+=8*ms;policy.sourceMotion(source) }
        source+=100*ms;policy.sourceMotion(source)
        assertEquals(24*ms,policy.quietThresholdNanos)
        repeat(14) { source+=8*ms;policy.sourceMotion(source) }
        assertEquals(24*ms,policy.quietThresholdNanos)
    }

    @Test fun resetForgetsCadenceAndDeadlineButKeepsTheConfiguredDeliveryFloor() {
        val policy=CameraIdlePolicy()
        policy.configureRate(60)
        policy.sourceMotion(0);policy.sourceMotion(16_666_667L)
        policy.motionReceived(100*ms)
        policy.reset()
        assertEquals(33_333_334L,policy.quietThresholdNanos)
        assertNull(policy.remainingIdleNanos(1_000*ms))
        policy.configureRate(100)
        assertEquals(20*ms,policy.quietThresholdNanos)
        assertNull(policy.remainingIdleNanos(1_000*ms))
        policy.sourceMotion(0);policy.sourceMotion(8*ms)
        assertEquals(24*ms,policy.quietThresholdNanos)
    }

    @Test fun changingTheDeliveryRateRecalculatesTheSameLocalDeadlineWithoutMotionReplay() {
        val policy=CameraIdlePolicy()
        policy.motionReceived(100*ms)
        policy.configureRate(60)
        assertEquals(13_333_334L,policy.remainingIdleNanos(120*ms))
        policy.configureRate(100)
        assertEquals(0L,policy.remainingIdleNanos(120*ms))
        policy.configureRate(1)
        assertEquals(1_980*ms,policy.remainingIdleNanos(120*ms))
        assertThrows(IllegalArgumentException::class.java) { policy.configureRate(-1) }
        assertThrows(IllegalArgumentException::class.java) { policy.configureRate(1001) }
    }

    @Test fun denseLowerRateMovementCannotAppearQuietBetweenLocalReceiptsOrAcks() {
        for(period in listOf(8*ms,16_666_667L,100*ms)) {
            val policy=CameraIdlePolicy()
            policy.sourceMotion(0)
            policy.sourceMotion(period)
            repeat(100) { index ->
                val now=(index+2)*period
                policy.sourceMotion(now)
                policy.motionReceived(now+2*ms)
                policy.motionDelivered(now+4*ms)
                assertTrue(requireNotNull(policy.remainingIdleNanos(now+period-1))>0L)
            }
        }
    }
}
