package dev.uwumouse.app.session

/** Synthetic InputLab schedule; elapsed time is supplied by its independent producer. */
class TouchTransitionsLabSource(val durationMillis:Int=4_000) {
    data class Step(val tick:Int?,val buttonHeld:Boolean?,val directionKey:Int?,
                    val nextAtMillis:Long,val finished:Boolean=false)
    init { require(durationMillis in 100..10_000) }
    val expectedTicks=(durationMillis+3)/4
    val expectedSamples=expectedTicks*4
    val expectedButtonEdges=((durationMillis+49)/50).let { it+it%2 }
    val expectedKeyEdges=((durationMillis+124)/125)*2
    private var lastElapsed=-1L
    private var lastTick=-1
    private var buttonPhase=-1L
    private var directionPhase=-1L
    private var held=false
    private var direction=0
    private var finished=false
    private var ticks=0
    private var missedTicks=0
    private var missedButtonPhases=0L
    private var missedDirectionPhases=0L

    /** Late wakeups skip old reports instead of fabricating a burst to catch up. */
    @Synchronized fun advance(elapsedMillis:Long):Step {
        require(elapsedMillis>=0L && elapsedMillis>=lastElapsed)
        lastElapsed=elapsedMillis
        if(finished) return Step(null,null,null,durationMillis.toLong(),true)
        if(elapsedMillis>=durationMillis) {
            missedTicks+=expectedTicks-lastTick-1
            missedButtonPhases+=((durationMillis+49)/50-1L-buttonPhase).coerceAtLeast(0L)
            missedDirectionPhases+=((durationMillis+124)/125-1L-directionPhase).coerceAtLeast(0L)
            finished=true
            return Step(null,null,null,durationMillis.toLong(),true)
        }
        val currentTick=(elapsedMillis/4).toInt()
        val tick=if(currentTick>lastTick) {
            missedTicks+=currentTick-lastTick-1
            lastTick=currentTick;ticks++
            currentTick
        } else null
        val nextButtonPhase=elapsedMillis/50
        var button:Boolean?=null
        if(nextButtonPhase!=buttonPhase) {
            missedButtonPhases+=(nextButtonPhase-buttonPhase-1).coerceAtLeast(0L)
            buttonPhase=nextButtonPhase
            val nextHeld=buttonPhase%2L==0L
            if(nextHeld!=held) { held=nextHeld;button=held }
        }
        val nextDirectionPhase=elapsedMillis/125
        var key:Int?=null
        if(nextDirectionPhase!=directionPhase) {
            missedDirectionPhases+=(nextDirectionPhase-directionPhase-1).coerceAtLeast(0L)
            directionPhase=nextDirectionPhase
            val nextDirection=when(directionPhase%4L) { 0L->51;1L->29;2L->47;else->32 }
            if(nextDirection!=direction) { direction=nextDirection;key=direction }
        }
        val next=minOf((lastTick+1L)*4,(buttonPhase+1)*50,(directionPhase+1)*125,durationMillis.toLong())
        return Step(tick,button,key,next)
    }

    /** Fixed source counters, distinct from mapper admissions and independent receiver events. */
    @Synchronized fun snapshot():Map<String,Any> = mapOf(
        "sourceTicks" to ticks,"missedTicks" to missedTicks,"missedButtonPhases" to missedButtonPhases,
        "missedDirectionPhases" to missedDirectionPhases,"lastElapsedMs" to lastElapsed,
        "expectedTicks" to expectedTicks,"expectedSamples" to expectedSamples,
        "expectedButtonEdgesIncludingCleanup" to expectedButtonEdges,"expectedKeyEdgesIncludingCleanup" to expectedKeyEdges)
}
