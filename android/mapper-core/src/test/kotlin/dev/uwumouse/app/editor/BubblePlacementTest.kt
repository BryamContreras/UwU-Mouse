package dev.uwumouse.app.editor

import org.junit.Assert.*
import org.junit.Test

class BubblePlacementTest {
    @Test fun largerDesktopSideSharesDockGeometryAndFitsTheDisplay() {
        val style=BubbleSettings(dockEdge="right")
        val phoneSide=BubblePlacement.sideInPixels(style.sizeDp,1f,1000,500)
        val desktopSide=BubblePlacement.sideInPixels(style.sizeDp,1.35f,1000,500)
        assertEquals(44,phoneSide)
        assertEquals(59,desktopSide)
        assertEquals(1000-desktopSide/2,BubblePlacement.position(style,1000,500,desktopSide).x)
        assertEquals(1000-desktopSide/2,BubblePlacement.drag(1000,0,1000,500,desktopSide).x)
        assertEquals(31,BubblePlacement.sideInPixels(72,1.35f,32,120))
    }

    @Test fun desktopRestoresSavedTopDockWithItsLargerBubblePartlyVisible() {
        val style = BubbleSettings(x=.65f,dockEdge="top")
        val side = BubblePlacement.sideInPixels(style.sizeDp,1.35f,2560,1440)
        val position = BubblePlacement.position(style,2560,1440,side)
        assertEquals(-29,position.y)
        assertEquals(30,position.y+side)
        assertEquals(-29,BubblePlacement.drag(position.x,-side,2560,1440,side).y)
        assertEquals("top",BubblePlacement.settle(style,position.x,position.y,2560,1440,side,10).dockEdge)
    }

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
