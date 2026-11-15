package dev.uwumouse.app.session

import dev.uwumouse.app.engine.TouchTransitionResult
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Atomic, app-owned progress records for one MOVE/contact-edge transaction after EOF. */
object TouchTransitionProgress {
    const val MAGIC = 0x55545431
    const val RECORD_BYTES = 12
    const val MAX_STAGES = 2
    const val MAX_BYTES = RECORD_BYTES * MAX_STAGES

    /** Each stage is recorded after journal preparation and before sending it to the worker. */
    fun resolve(records:ByteArray,binderResult:Int?):TouchTransitionResult {
        if(records.size>MAX_BYTES || records.size%RECORD_BYTES!=0) return unknown()
        val attempted=records.size/RECORD_BYTES
        val input=ByteBuffer.wrap(records).order(ByteOrder.BIG_ENDIAN)
        for(stage in 1..attempted) {
            if(input.int!=MAGIC || input.int!=stage || input.int!=stage-1) return unknown()
        }
        if(binderResult==null) return TouchTransitionResult(maxOf(0,attempted-1),attempted)
        return when {
            binderResult in 0..MAX_STAGES && binderResult==attempted -> TouchTransitionResult(binderResult,attempted)
            binderResult in -MAX_STAGES..-1 && -binderResult==attempted -> TouchTransitionResult(attempted-1,attempted)
            else -> unknown()
        }
    }

    private fun unknown()=TouchTransitionResult(0,0,progressKnown=false)
}
