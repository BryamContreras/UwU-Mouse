package dev.uwumouse.app.editor

import org.junit.Assert.*
import org.junit.Test

class BubblePlacementTest {
    @Test fun allFourEdgesLeaveExactlyHalfVisible() {
        val base=BubbleSettings(x=.5f,y=.5f)
        assertEquals(BubblePlacement.Position(-50,200),BubblePlacement.position(base.copy(dockEdge="left"),1000,500,100))
        assertEquals(BubblePlacement.Position(950,200),BubblePlacement.position(base.copy(dockEdge="right"),1000,500,100))
        assertEquals(BubblePlacement.Position(450,-50),BubblePlacement.position(base.copy(dockEdge="top"),1000,500,100))
        assertEquals(BubblePlacement.Position(450,450),BubblePlacement.position(base.copy(dockEdge="bottom"),1000,500,100))
    }
    @Test fun releaseNearEdgeDocksWhileCenterRemainsFree() {
        assertEquals("top",BubblePlacement.settle(BubbleSettings(),450,2,1000,500,100,10).dockEdge)
        assertEquals("none",BubblePlacement.settle(BubbleSettings(),450,200,1000,500,100,10).dockEdge)
    }
    @Test fun cornerNeverLeavesOnlyQuarterAccessible() {
        val style=BubblePlacement.settle(BubbleSettings(),-50,-50,1000,500,100,10)
        val point=BubblePlacement.position(style,1000,500,100)
        assertTrue((point.x<0) xor (point.y<0))
        assertTrue(point.x>=-50 && point.y>=-50)
    }
    @Test fun resizingKeepsDockAndFreeAxisWithinScreen() {
        val style=BubbleSettings(x=.8f,y=1f,dockEdge="bottom")
        assertEquals(464,BubblePlacement.position(style,1000,500,72).y)
        assertEquals(450,BubblePlacement.position(style,1000,500,100).y)
        val p=BubblePlacement.drag(Int.MAX_VALUE,Int.MIN_VALUE,1000,500,100)
        assertEquals(BubblePlacement.Position(950,-50),p)
    }
}
