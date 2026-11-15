package dev.uwumouse.app.engine

/** Exactly one pending MOVE followed by one ordinary contact edge, without merging either event. */
class TouchTransitionSequence(frames:List<TouchFrame>) {
    val frames:List<TouchFrame> = frames.map { it.copy(ids=it.ids.copyOf(),xs=it.xs.copyOf(),ys=it.ys.copyOf()) }

    init {
        require(this.frames.size==2) { "Touch transition requires exactly two frames" }
        for(frame in this.frames) {
            require(frame.ids.size in 1..10 && frame.xs.size==frame.ids.size && frame.ys.size==frame.ids.size)
            require(frame.ids.all { it in 0..9 } && frame.ids.distinct().size==frame.ids.size)
            require(frame.xs.all { it.isFinite() } && frame.ys.all { it.isFinite() })
            require(frame.downTime>=0L && frame.eventTime>=frame.downTime)
        }
        val (move,edge)=this.frames
        require(move.action==TouchComposer.MOVE)
        require(move.downTime==edge.downTime && move.eventTime<=edge.eventTime)
        val index=edge.action ushr 8
        when(edge.action and 255) {
            TouchComposer.POINTER_DOWN -> {
                require(edge.action==TouchComposer.POINTER_DOWN or (index shl 8))
                require(edge.ids.size==move.ids.size+1 && index in edge.ids.indices)
                require(edge.xs[index]>=0f && edge.ys[index]>=0f)
                requireSameContacts(move,without(edge,index))
            }
            TouchComposer.POINTER_UP -> {
                require(edge.action==TouchComposer.POINTER_UP or (index shl 8))
                require(edge.ids.size>1 && index in edge.ids.indices)
                requireSameContacts(move,edge)
            }
            TouchComposer.UP -> {
                require(edge.action==TouchComposer.UP && move.ids.size==1)
                requireSameContacts(move,edge)
            }
            // A pending MOVE has an existing finger. Starting a new gesture with DOWN
            // would omit the old gesture's UP, so it cannot be an ordinary edge here.
            else -> throw IllegalArgumentException("Unsupported touch transition edge")
        }
    }

    /** The pending MOVE may change positions, but must extend exactly the admitted gesture/IDs. */
    fun matchesPrevious(previous:TouchFrame):Boolean = frames.first().let { move ->
        move.downTime==previous.downTime && move.eventTime>=previous.eventTime &&
            move.ids.contentEquals(previous.ids)
    }

    companion object {
        /** A possible attempt retains its full frame; use this only for an acknowledged event. */
        fun postEvent(frame:TouchFrame):TouchFrame? = CameraResetSequence.postEvent(frame)

        private fun requireSameContacts(left:TouchFrame,right:TouchFrame) {
            require(left.ids.contentEquals(right.ids) && left.xs.contentEquals(right.xs) && left.ys.contentEquals(right.ys))
        }

        private fun without(frame:TouchFrame,index:Int)=frame.copy(action=TouchComposer.MOVE,
            ids=frame.ids.filterIndexed { i,_ -> i!=index }.toIntArray(),
            xs=frame.xs.filterIndexed { i,_ -> i!=index }.toFloatArray(),
            ys=frame.ys.filterIndexed { i,_ -> i!=index }.toFloatArray())
    }
}

/** Acknowledged prefix and last possible attempt; neither count authorizes replay. */
data class TouchTransitionResult(val acceptedCount:Int,val attemptedCount:Int,val progressKnown:Boolean=true) {
    init {
        require(acceptedCount in 0..2 && attemptedCount in 0..2)
        require(!progressKnown || attemptedCount in acceptedCount..minOf(2,acceptedCount+1))
    }
}
