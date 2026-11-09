package dev.uwumouse.app.session

import org.junit.Assert.*
import org.junit.Test

class CursorPointPolicyTest {
    private val game=PointerBounds(75f,0f,2316f,1080f)
    @Test fun onlyAnAdmittedCameraCanContinueOutsideAnAuthorizedWindow() {
        val x=floatArrayOf(-5000f);val y=floatArrayOf(2000f);val ids=intArrayOf(9)
        assertTrue(CursorPointPolicy.allowsFrame(2,x,y,game,emptyList(),ids,true))
        assertFalse(CursorPointPolicy.allowsFrame(2,x,y,game,emptyList(),ids,false))
        assertFalse(CursorPointPolicy.allowsFrame(0,x,y,game,emptyList(),ids,true))
        assertFalse(CursorPointPolicy.allowsFrame(2,x,y,null,emptyList(),ids,true))
        assertFalse(CursorPointPolicy.allowsFrame(2,x,y,game,emptyList(),intArrayOf(0),true))
    }
    @Test fun addingAnActionPreservesTheVirtualCameraButDoesNotAuthorizeAnOutsideNewContact() {
        val x=floatArrayOf(300f,400f,-5000f);val y=floatArrayOf(700f,600f,-2000f)
        val ids=intArrayOf(0,1,9)
        assertTrue(CursorPointPolicy.allowsFrame(5 or (1 shl 8),x,y,game,emptyList(),ids,true))
        assertFalse(CursorPointPolicy.allowsFrame(5 or (2 shl 8),x,y,game,emptyList(),ids,true))
        assertFalse(CursorPointPolicy.allowsFrame(5 or (1 shl 8),floatArrayOf(300f,-1f,-5000f),y,game,emptyList(),ids,true))
        assertFalse(CursorPointPolicy.allowsFrame(2,x,y,game,listOf(PointerBounds(200f,500f,500f,800f)),ids,true))
    }
    @Test fun virtualContinuationHasAnExplicitFiniteLimitAndReleasesRemainPossible() {
        for(value in listOf(Float.NaN,Float.POSITIVE_INFINITY,1_000_001f,-1_000_001f)) {
            assertFalse(CursorPointPolicy.allowsFrame(2,floatArrayOf(value),floatArrayOf(500f),game,emptyList(),intArrayOf(9),true))
        }
        assertTrue(CursorPointPolicy.allowsFrame(3,floatArrayOf(-4000f),floatArrayOf(-2000f),null,emptyList(),intArrayOf(9),true))
        assertTrue(CursorPointPolicy.allowsFrame(6,floatArrayOf(300f,-4000f),floatArrayOf(700f,-2000f),null,emptyList(),intArrayOf(0,9),true))
    }
    @Test fun displayCoordinatesRespectWindowOffsetAndExclusiveEdges() {
        val game=PointerBounds(75f,20f,2316f,1080f)
        assertTrue(CursorPointPolicy.allows(75f,20f,game,emptyList()))
        assertFalse(CursorPointPolicy.allows(74f,20f,game,emptyList()))
        assertFalse(CursorPointPolicy.allows(2316f,100f,game,emptyList()))
        assertFalse(CursorPointPolicy.allows(Float.NaN,100f,game,emptyList()))
        assertFalse(CursorPointPolicy.allows(100f,100f,null,emptyList()))
    }
    @Test fun aCoveredPointCannotBeInjectedIntoTheGame() {
        val game=PointerBounds(0f,0f,2316f,1080f)
        val cover=listOf(PointerBounds(0f,0f,2316f,68f),PointerBounds(2000f,100f,2200f,300f))
        assertFalse(CursorPointPolicy.allows(100f,30f,game,cover))
        assertFalse(CursorPointPolicy.allows(2100f,200f,game,cover))
        assertTrue(CursorPointPolicy.allows(1000f,500f,game,cover))
    }
}
