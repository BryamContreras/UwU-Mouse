package dev.mobimapper.mobimapper.session

import dev.mobimapper.mobimapper.engine.CameraResetResult
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class CameraResetProgressTest {
    private fun records(stages: Int): ByteArray = ByteBuffer
        .allocate(stages * CameraResetProgress.RECORD_BYTES)
        .order(ByteOrder.BIG_ENDIAN)
        .apply {
            for (stage in 1..stages) {
                putInt(CameraResetProgress.MAGIC); putInt(stage); putInt(stage - 1)
            }
        }.array()

    @Test fun lostReplyRetainsExactlyTheLastPossibleAttemptForEveryPrefix() {
        for (stage in 0..3) {
            assertEquals(CameraResetResult(maxOf(0, stage - 1), stage),
                CameraResetProgress.resolve(records(stage), null))
        }
    }

    @Test fun positiveReplyConfirmsEveryAcknowledgedPrefixIncludingTheWholeReset() {
        for (accepted in 0..3) {
            assertEquals(CameraResetResult(accepted, accepted),
                CameraResetProgress.resolve(records(accepted), accepted))
        }
    }

    @Test fun negativeReplyKeepsTheFailingStagePossibleAndOnlyEarlierStagesAcknowledged() {
        for (attempted in 1..3) {
            assertEquals(CameraResetResult(attempted - 1, attempted),
                CameraResetProgress.resolve(records(attempted), -attempted))
        }
    }

    @Test fun binderReplyCannotClaimMoreOrFewerAttemptsThanThePipe() {
        for (attempted in 0..3) for (reply in -5..5) {
            if (reply == attempted || (reply < 0 && -reply == attempted)) continue
            assertUnknown(CameraResetProgress.resolve(records(attempted), reply))
        }
        assertUnknown(CameraResetProgress.resolve(records(3), Int.MIN_VALUE))
        assertUnknown(CameraResetProgress.resolve(records(3), Int.MAX_VALUE))
    }

    @Test fun everyTruncatedRecordAndTrailingByteIsUnknownDespiteAValidBinderReply() {
        val complete = records(3)
        for (size in 1..CameraResetProgress.MAX_BYTES) {
            if (size % CameraResetProgress.RECORD_BYTES == 0) continue
            assertUnknown(CameraResetProgress.resolve(complete.copyOf(size), null))
            assertUnknown(CameraResetProgress.resolve(complete.copyOf(size), 3))
        }
        assertUnknown(CameraResetProgress.resolve(complete + byteArrayOf(0), 3))
        assertUnknown(CameraResetProgress.resolve(complete + records(1), null))
    }

    @Test fun wrongMagicSkippedRepeatedAndReorderedStagesInvalidateTheEntireCandidate() {
        val variants = listOf(
            records(3).changedInt(0, 0),
            records(3).changedInt(1, 0),
            records(3).changedInt(4, 1),
            records(3).changedInt(4, 3),
            records(3).changedInt(7, 2),
            records(3).changedInt(7, 4),
            records(3).changedInt(2, 1),
            records(3).changedInt(5, 0),
            records(3).changedInt(8, 3),
        )
        for (variant in variants) {
            assertUnknown(CameraResetProgress.resolve(variant, null))
            assertUnknown(CameraResetProgress.resolve(variant, 3))
        }
    }

    @Test fun bytesWithTheWrongEndiannessAreNotInterpretedAsProgress() {
        val input = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(CameraResetProgress.MAGIC).putInt(1).putInt(0).array()
        assertUnknown(CameraResetProgress.resolve(input, -1))
    }

    @Test fun lostReplyAfterTheThirdAttemptDoesNotInventTheFinalAck() {
        val result = CameraResetProgress.resolve(records(3), null)
        assertTrue(result.progressKnown)
        assertEquals(2, result.acceptedCount)
        assertEquals(3, result.attemptedCount)
        assertNotEquals(CameraResetProgress.resolve(records(3), 3), result)
    }

    private fun ByteArray.changedInt(index: Int, value: Int): ByteArray = copyOf().also {
        ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putInt(index * 4, value)
    }

    private fun assertUnknown(result: CameraResetResult) {
        assertFalse(result.progressKnown)
        assertEquals(0, result.acceptedCount)
        assertEquals(0, result.attemptedCount)
    }
}
