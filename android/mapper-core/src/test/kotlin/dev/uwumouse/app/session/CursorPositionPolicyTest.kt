package dev.uwumouse.app.session

import org.junit.Assert.*
import org.junit.Test

class CursorPositionPolicyTest {
    @Test fun everyEntryStartsAtCurrentAreaCenterRatherThanTheOldPosition() {
        val cursor=CursorPositionPolicy()
        assertTrue(cursor.begin(PointerBounds(100f,40f,2100f,1040f),2316,1080))
        assertEquals(1100f,cursor.x,0f);assertEquals(540f,cursor.y,0f)
        cursor.move(800f,-400f)
        assertTrue(cursor.begin(PointerBounds(200f,80f,2200f,1000f),2316,1080))
        assertEquals(1200f,cursor.x,0f);assertEquals(540f,cursor.y,0f)
    }
    @Test fun calibrationIsIntersectedWithCurrentWindowBeforeCentering() {
        val target=PointerBounds(80f,30f,2200f,1080f)
        val entry=CursorPositionPolicy.entryArea(PointerBounds(0f,100f,2000f,1000f),target)
        assertEquals(PointerBounds(80f,100f,2000f,1000f),entry)
        val cursor=CursorPositionPolicy();assertTrue(cursor.begin(entry,2316,1080))
        assertEquals(1040f,cursor.x,0f);assertEquals(550f,cursor.y,0f)
    }
    @Test fun missingOrDisjointCalibrationFallsBackOnlyToTheVerifiedTarget() {
        val target=PointerBounds(100f,50f,900f,650f)
        assertEquals(target,CursorPositionPolicy.entryArea(null,target))
        assertEquals(target,CursorPositionPolicy.entryArea(PointerBounds(1000f,0f,1500f,500f),target))
    }
    @Test fun relativeMovementUsesTheNewCenterAndCanReachTheSystemEdge() {
        val cursor=CursorPositionPolicy();cursor.begin(PointerBounds(100f,100f,900f,700f),1000,800)
        cursor.move(25f,-12f);cursor.move(-4f,2f)
        assertEquals(521f,cursor.x,0f);assertEquals(390f,cursor.y,0f)
        cursor.move(-Float.MAX_VALUE,-Float.MAX_VALUE)
        assertEquals(0f,cursor.x,0f);assertEquals(0f,cursor.y,0f)
        cursor.move(Float.MAX_VALUE,Float.MAX_VALUE)
        assertTrue(cursor.x<1000f);assertTrue(cursor.y<800f)
        assertTrue(cursor.x>=999f);assertTrue(cursor.y>=799f)
    }
    @Test fun invalidAxesDoNotCorruptTheOtherCoordinate() {
        val cursor=CursorPositionPolicy();cursor.begin(PointerBounds(0f,0f,1000f,800f),1000,800)
        cursor.move(Float.NaN,5f);cursor.move(3f,Float.POSITIVE_INFINITY)
        assertEquals(503f,cursor.x,0f);assertEquals(405f,cursor.y,0f)
    }
    @Test fun invalidBoundsNeverInitializeAVisiblePosition() {
        val cursor=CursorPositionPolicy()
        assertFalse(cursor.begin(PointerBounds(0f,0f,0f,100f),1000,800))
        assertFalse(cursor.begin(PointerBounds(Float.NaN,0f,100f,100f),1000,800))
        assertFalse(cursor.begin(PointerBounds(0f,0f,100f,100f),0,800))
    }
    @Test fun distinctHistoricalSampleTimesRetainEveryRelativeDisplacement() {
        val cursor=CursorPositionPolicy();cursor.begin(PointerBounds(0f,0f,1000f,800f),1000,800)
        cursor.moveSample(10f,2f,100_000_000L)
        cursor.moveSample(4f,-1f,108_000_000L)
        cursor.moveSample(-3f,5f,116_000_000L)
        assertEquals(511f,cursor.x,0f);assertEquals(406f,cursor.y,0f)
    }
    @Test fun buttonHoverDuplicatesAndOverlappingHistoryDoNotMoveTheCursorTwice() {
        val cursor=CursorPositionPolicy();cursor.begin(PointerBounds(0f,0f,1000f,800f),1000,800)
        cursor.moveSample(10f,2f,100_000_000L)
        repeat(3) { cursor.moveSample(10f,2f,100_000_000L) }
        cursor.moveSample(7f,9f,92_000_000L)
        assertEquals(510f,cursor.x,0f);assertEquals(402f,cursor.y,0f)
        cursor.moveSample(3f,4f,108_000_000L)
        assertEquals(513f,cursor.x,0f);assertEquals(406f,cursor.y,0f)
    }
    @Test fun reentryResetsBothCenterAndSampleTimeOwnership() {
        val cursor=CursorPositionPolicy();val area=PointerBounds(0f,0f,1000f,800f)
        cursor.begin(area,1000,800);cursor.moveSample(20f,5f,200_000_000L)
        cursor.begin(area,1000,800);cursor.moveSample(-10f,3f,100_000_000L)
        assertEquals(490f,cursor.x,0f);assertEquals(403f,cursor.y,0f)
    }
}
