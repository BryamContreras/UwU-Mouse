package dev.uwumouse.app.backend

import org.junit.Assert.*
import org.junit.Test

class FrameStatsParserTest {
    @Test fun picksOnlyUnambiguousExactActivitySurface() {
        val component="com.example.game/com.example.game.Main"
        val expected="SurfaceView[$component]@0(BLAST)#25"
        val output="SurfaceView[com.example.game.fake/com.example.game.Main]@0(BLAST)#1\n"+
            "SurfaceView[$component]@0(BLAST) Background#2\n"+expected+"\n"
        assertEquals(expected,FrameStatsParser.chooseLayer(output,component,"com.example.game/.Main"))
        assertNull(FrameStatsParser.chooseLayer(output+"SurfaceView[$component]@1(BLAST)#26\n",component,component))
    }

    @Test fun computesFpsFromUniqueRecentPresentedFrames() {
        val now=10_000_000_000L
        val values=(0..60).map { now-1_000_000_000L+it*16_666_666L }
        val output="16666666\n"+values.joinToString("\n") { "1 $it 3" }+
            "\n0 0 0\n0 ${Long.MAX_VALUE} 0\n1 ${values.last()} 3\n"
        assertEquals(60.0,FrameStatsParser.fps(output,now)!!,.01)
        assertNull(FrameStatsParser.fps(output,now+3_000_000_000L))
        assertNull(FrameStatsParser.fps("16666666\n0 0 0",now))
    }

    @Test fun validatesPackageWithoutAllowingCommandArguments() {
        assertTrue(FrameRateSampler.validPackage("com.dts.freefireth"))
        assertFalse(FrameRateSampler.validPackage("com.dts.freefireth; id"))
    }
}
