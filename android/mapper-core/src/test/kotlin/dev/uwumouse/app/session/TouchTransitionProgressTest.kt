package dev.uwumouse.app.session

import dev.uwumouse.app.engine.TouchTransitionResult
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class TouchTransitionProgressTest {
    private fun records(stages:Int)=ByteBuffer.allocate(stages*TouchTransitionProgress.RECORD_BYTES).apply {
        for(stage in 1..stages) putInt(TouchTransitionProgress.MAGIC).putInt(stage).putInt(stage-1)
    }.array()

    @Test fun normalReplyConfirmsTheExactRecordedPrefixIncludingNoAttempt() {
        for(stage in 0..2) assertEquals(TouchTransitionResult(stage,stage),TouchTransitionProgress.resolve(records(stage),stage))
        for(stage in 1..2) assertEquals(TouchTransitionResult(stage-1,stage),TouchTransitionProgress.resolve(records(stage),-stage))
    }

    @Test fun lostReplyNeverAssumesTheLastStageWasAcknowledgedIncludingFullSuccess() {
        for(stage in 0..2) assertEquals(TouchTransitionResult(maxOf(0,stage-1),stage),TouchTransitionProgress.resolve(records(stage),null))
    }

    @Test fun truncatedOversizedWrongMagicOrOutOfOrderRecordsCannotSupplyACandidate() {
        val bad=listOf(records(1).copyOf(11),records(2)+byteArrayOf(0),records(3),
            records(1).apply { this[0]=0 },records(2).apply { ByteBuffer.wrap(this).putInt(16,1) },
            records(2).apply { ByteBuffer.wrap(this).putInt(20,0) })
        for(bytes in bad) for(reply in listOf(null,0,1,2,-1,-2)) {
            assertFalse(TouchTransitionProgress.resolve(bytes,reply).progressKnown)
        }
    }

    @Test fun inconsistentBinderResultCannotConfirmUnrecordedStages() {
        for(stage in 0..2) for(reply in listOf(-3,-2,-1,0,1,2,3)) {
            val valid=reply==stage || (stage>0 && reply == -stage)
            assertEquals(valid,TouchTransitionProgress.resolve(records(stage),reply).progressKnown)
        }
    }
}
