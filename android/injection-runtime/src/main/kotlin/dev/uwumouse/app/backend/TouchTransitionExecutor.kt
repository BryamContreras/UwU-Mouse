package dev.uwumouse.app.backend

import dev.uwumouse.app.engine.TouchFrame
import dev.uwumouse.app.engine.TouchTransitionSequence

/** One synchronous MOVE/contact-edge operation; the caller owns and closes the progress pipe. */
object TouchTransitionExecutor {
    interface Port {
        fun contextAllowsStage():Boolean
        fun admissionLost()
        fun prepare(frame:TouchFrame):Boolean
        fun writeAttempt(stage:Int,acceptedBefore:Int)
        fun abortUnsent(frame:TouchFrame):Boolean
        /** Includes the current worker acknowledgement; never enqueue the next frame early. */
        fun send(frame:TouchFrame):Boolean
        fun acknowledge(frame:TouchFrame,accepted:Boolean):Boolean
        fun admitted(frame:TouchFrame)
        fun recoveryNeeded(reason:String)
    }

    /** Nonnegative replies confirm an admitted prefix; negatives identify a possible failed attempt. */
    fun execute(sequence:TouchTransitionSequence,port:Port):Int {
        for((index,frame) in sequence.frames.withIndex()) {
            val allowed=try { port.contextAllowsStage() } catch(_:Exception) { false }
            if(!allowed) {
                // Cleanup failures propagate so the caller's finally still closes its descriptor.
                port.admissionLost()
                return index
            }
            if(!port.prepare(frame)) return index
            try {
                port.writeAttempt(index+1,index)
            } catch(_:Exception) {
                // This path is valid only before send() is entered. No send/ACK failure rolls back.
                val restored=try { port.abortUnsent(frame) } catch(_:Exception) { false }
                if(!restored) port.recoveryNeeded("PROGRESS_ROLLBACK_FAILED")
                return index
            }
            val accepted=try { port.send(frame) } catch(_:Exception) {
                port.recoveryNeeded("PIPE")
                return -(index+1)
            }
            val acknowledged=try { port.acknowledge(frame,accepted) } catch(_:Exception) {
                port.recoveryNeeded("PIPE")
                return -(index+1)
            }
            if(!acknowledged) {
                port.recoveryNeeded("JOURNAL_ACK_MISMATCH")
                return -(index+1)
            }
            if(!accepted) {
                port.recoveryNeeded("INJECTION_REJECTED")
                return -(index+1)
            }
            port.admitted(frame)
        }
        return sequence.frames.size
    }
}
