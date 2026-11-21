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
        val sample=FrameStatsParser.sample(output,now)!!
        assertEquals(60.0,sample.fps,.01)
        assertEquals(FrameStatsParser.Source.PRESENTED,sample.source)
        assertNull(FrameStatsParser.sample(output,now+3_000_000_000L))
        assertNull(FrameStatsParser.sample("16666666\n0 0 0",now))
    }

    @Test fun estimatesFrameRateWhenReadyTimestampsAdvanceButPresentFenceIsStuck() {
        val now=16_629_560_000_000L
        val stuckPresent=15_539_545_786_881L
        val ready=(0..60).map { now-1_000_000_000L+it*16_666_666L }
        val output="8333333\n"+ready.joinToString("\n") { "${it-1000} $stuckPresent $it" }+
            "\n${now-1} ${Long.MAX_VALUE} $now" // A pending fence does not invalidate the usable history.
        val sample=FrameStatsParser.sample(output,now)!!
        assertEquals(60.0,sample.fps,.01)
        assertEquals(FrameStatsParser.Source.FRAME_READY,sample.source)
        assertNull(FrameStatsParser.sample(output,now+600_000_000L))
    }

    @Test fun rejectsFrameReadyFallbackWithoutAnOldAndFixedPresentFence() {
        val now=10_000_000_000L
        val ready=(0..60).map { now-1_000_000_000L+it*16_666_666L }
        for(fence in listOf(0L,Long.MAX_VALUE,now-1_000L)) {
            val output="16666666\n"+ready.joinToString("\n") { "${it-1000} $fence $it" }
            assertNull(FrameStatsParser.sample(output,now))
        }
        val varyingOldFences="16666666\n"+ready.joinToString("\n") { "${it-1000} ${it-3_000_000_000L} $it" }
        assertNull(FrameStatsParser.sample(varyingOldFences,now))
    }

    @Test fun prefersPresentationRateWhenBothColumnsAreCurrent() {
        val now=10_000_000_000L
        val output="8333333\n"+(0..60).joinToString("\n") {
            val ready=now-1_000_000_000L+it*16_666_666L
            val present=if(it%2==0) ready else ready-16_666_666L
            "$ready $present $ready"
        }
        val sample=FrameStatsParser.sample(output,now)!!
        assertEquals(30.0,sample.fps,.01)
        assertEquals(FrameStatsParser.Source.PRESENTED,sample.source)
    }

    @Test fun validatesPackageWithoutAllowingCommandArguments() {
        assertTrue(FrameRateSampler.validPackage("com.dts.freefireth"))
        assertFalse(FrameRateSampler.validPackage("com.dts.freefireth; id"))
    }
}
