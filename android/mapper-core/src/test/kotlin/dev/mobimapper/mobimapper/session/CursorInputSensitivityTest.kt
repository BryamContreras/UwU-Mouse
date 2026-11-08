package dev.mobimapper.mobimapper.session

import org.junit.Assert.*
import org.junit.Test

class CursorInputSensitivityTest {
    private fun cursor(width:Int=100,height:Int=100)=CursorPositionPolicy().apply {
        assertTrue(begin(PointerBounds(0f,0f,width.toFloat(),height.toFloat()),width,height))
    }

    @Test fun gainChangesOnlyLaterPhysicalMovementAndNeverTheAbsoluteHotspotPlacement() {
        val cursor=cursor()
        cursor.moveInput(10f,-2f,1f)
        assertEquals(60f,cursor.x,0f);assertEquals(48f,cursor.y,0f)
        cursor.moveInput(10f,-2f,2f)
        assertEquals(80f,cursor.x,0f);assertEquals(44f,cursor.y,0f)
        // Entry/restoration offsets are display coordinates, not another physical movement.
        cursor.move(-30f,6f)
        assertEquals(50f,cursor.x,0f);assertEquals(50f,cursor.y,0f)
    }

    @Test fun everyScaledRawSampleClampsBeforeTheFollowingReversal() {
        val cursor=cursor()
        val batch=RawMouseMotionBatch(longArrayOf(1,2),floatArrayOf(100f,-10f),floatArrayOf(-100f,10f))
        batch.moveCursor(cursor,sensitivity=2f)
        assertEquals(Math.nextDown(100f)-20f,cursor.x,0f)
        assertEquals(20f,cursor.y,0f)
        // Aggregating first would incorrectly put the pointer at the opposite display edges.
        assertTrue(cursor.x<90f);assertTrue(cursor.y>0f)
    }

    @Test fun optimizedHoverPrefixAndFinalPhysicalFrameUseTheSameGainAndClipping() {
        val all=cursor();val split=cursor()
        val batch=RawMouseMotionBatch(longArrayOf(3,2,2),floatArrayOf(100f,-10f,-2f),floatArrayOf(-100f,10f,2f))
        batch.moveCursor(all,sensitivity=.5f)
        batch.moveCursor(split,untilExclusive=batch.size-1,sensitivity=.5f)
        split.moveInput(batch.xs.last(),batch.ys.last(),.5f)
        assertEquals(all.x,split.x,0f);assertEquals(all.y,split.y,0f)
    }

    @Test fun lowGainFractionalMotionDoesNotAccumulateFloatRoundingAtEachSample() {
        val cursor=cursor(500,400)
        repeat(1_000) { cursor.moveInput(.1f,-.1f,.1f) }
        assertEquals(260f,cursor.x,.0001f);assertEquals(190f,cursor.y,.0001f)
    }

    @Test fun invalidGainOrAxesCannotCorruptThePositionAndLargeFiniteMovementStillClamps() {
        val cursor=cursor()
        for(gain in listOf(0f,-1f,Float.NaN,Float.POSITIVE_INFINITY)) cursor.moveInput(10f,10f,gain)
        assertEquals(50f,cursor.x,0f);assertEquals(50f,cursor.y,0f)
        cursor.moveInput(Float.NaN,2f,2f)
        assertEquals(50f,cursor.x,0f);assertEquals(54f,cursor.y,0f)
        cursor.moveInput(Float.MAX_VALUE,-Float.MAX_VALUE,4f)
        assertEquals(Math.nextDown(100f),cursor.x,0f);assertEquals(0f,cursor.y,0f)
    }
}
