package dev.mobimapper.mobimapper.engine

enum class CameraResetKind { SEQUENTIAL, INTERLACED }

/** A narrow three-frame camera plan; the backend still acknowledges every frame. */
class CameraResetSequence(frames:List<TouchFrame>) {
    val frames:List<TouchFrame> = frames.map { it.copy(ids=it.ids.copyOf(),xs=it.xs.copyOf(),ys=it.ys.copyOf()) }
    val kind:CameraResetKind

    init {
        require(this.frames.size==3) { "Camera reset requires exactly three frames" }
        for(frame in this.frames) {
            require(frame.ids.size in 1..10 && frame.xs.size==frame.ids.size && frame.ys.size==frame.ids.size)
            require(frame.ids.all { it in 0..9 } && frame.ids.distinct().size==frame.ids.size)
            require(frame.xs.all { it.isFinite() } && frame.ys.all { it.isFinite() })
            require(frame.downTime>=0L && frame.eventTime>=frame.downTime)
        }
        kind=when(this.frames.first().action and 255) {
            TouchComposer.UP,TouchComposer.POINTER_UP -> CameraResetKind.SEQUENTIAL
            TouchComposer.POINTER_DOWN -> CameraResetKind.INTERLACED
            else -> throw IllegalArgumentException("Unsupported camera reset shape")
        }
        if(kind==CameraResetKind.SEQUENTIAL) validateSequential() else validateInterlaced()
    }

    /** The first attempt must extend exactly the guardian's existing admitted contact state. */
    fun matchesPrevious(previous:TouchFrame):Boolean {
        val first=frames.first()
        val before=if(kind==CameraResetKind.SEQUENTIAL) first else without(first,first.action ushr 8)
        return first.downTime==previous.downTime && first.eventTime>=previous.eventTime &&
            before.ids.contentEquals(previous.ids) && before.xs.contentEquals(previous.xs) &&
            before.ys.contentEquals(previous.ys)
    }

    private fun validateSequential() {
        val (up,down,move)=frames
        val count=up.ids.size
        require(down.ids.size==count && move.ids.size==count)
        val index=count-1
        require(up.action==if(count==1) TouchComposer.UP else TouchComposer.POINTER_UP or (index shl 8))
        require(down.action==if(count==1) TouchComposer.DOWN else TouchComposer.POINTER_DOWN or (index shl 8))
        require(move.action==TouchComposer.MOVE)
        require(up.ids.last() in 8..9 && down.ids.last() in 8..9)
        require(down.ids.contentEquals(move.ids))
        require(up.eventTime<=down.eventTime && down.eventTime<=move.eventTime)
        require(move.downTime==down.downTime)
        require(if(count==1) down.downTime>=up.eventTime else down.downTime==up.downTime)
        require(down.xs.last()>=0f && down.ys.last()>=0f)
        for(i in 0 until index) {
            require(up.ids[i]==down.ids[i] && up.xs[i]==down.xs[i] && up.ys[i]==down.ys[i])
            require(down.xs[i]==move.xs[i] && down.ys[i]==move.ys[i])
            require(up.xs[i]>=0f && up.ys[i]>=0f)
        }
    }

    private fun validateInterlaced() {
        val (down,up,move)=frames
        val count=down.ids.size
        val newIndex=down.action ushr 8;val oldIndex=up.action ushr 8
        require(count>=2 && up.ids.size==count && move.ids.size==count-1)
        require(newIndex in 0 until count && oldIndex in 0 until count && newIndex!=oldIndex)
        require(down.action==TouchComposer.POINTER_DOWN or (newIndex shl 8))
        require(up.action==TouchComposer.POINTER_UP or (oldIndex shl 8))
        require(move.action==TouchComposer.MOVE)
        require(down.ids[newIndex] in 8..9 && down.ids[oldIndex] in 8..9)
        require(down.ids.contentEquals(up.ids) && down.xs.contentEquals(up.xs) && down.ys.contentEquals(up.ys))
        require((1 until count).all { down.ids[it-1]<down.ids[it] })
        require(move.ids.contentEquals(without(up,oldIndex).ids))
        require(down.downTime==up.downTime && up.downTime==move.downTime)
        require(down.eventTime==up.eventTime && up.eventTime<=move.eventTime)
        require(down.xs[newIndex]>=0f && down.ys[newIndex]>=0f)
        for(index in down.ids.indices) if(index!=oldIndex && index!=newIndex) {
            val movedIndex=if(index<oldIndex) index else index-1
            require(down.xs[index]>=0f && down.ys[index]>=0f)
            require(down.xs[index]==move.xs[movedIndex] && down.ys[index]==move.ys[movedIndex])
        }
    }

    companion object {
        /** Detached post-event state; an unacknowledged event must retain its full attempted frame. */
        fun postEvent(frame:TouchFrame):TouchFrame? = when(frame.action and 255) {
            TouchComposer.UP,TouchComposer.CANCEL -> null
            TouchComposer.POINTER_UP -> without(frame,frame.action ushr 8)
            else -> frame.copy(action=TouchComposer.MOVE,ids=frame.ids.copyOf(),xs=frame.xs.copyOf(),ys=frame.ys.copyOf())
        }

        private fun without(frame:TouchFrame,index:Int)=frame.copy(action=TouchComposer.MOVE,
            ids=frame.ids.filterIndexed { i,_ -> i!=index }.toIntArray(),
            xs=frame.xs.filterIndexed { i,_ -> i!=index }.toFloatArray(),
            ys=frame.ys.filterIndexed { i,_ -> i!=index }.toFloatArray())
    }
}

/** Counts describe the acknowledged prefix and the last possible attempted frame, never replay. */
data class CameraResetResult(val acceptedCount:Int,val attemptedCount:Int,val progressKnown:Boolean=true) {
    init {
        require(acceptedCount in 0..3 && attemptedCount in 0..3)
        require(!progressKnown || attemptedCount in acceptedCount..minOf(3,acceptedCount+1))
    }
}
