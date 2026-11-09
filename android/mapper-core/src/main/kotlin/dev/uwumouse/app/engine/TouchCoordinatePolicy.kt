package dev.uwumouse.app.engine

/** Immutable per-session capability. Display dimensions always remain the real dimensions. */
enum class TouchCoordinatePolicy {
    STRICT,
    CONTINUOUS_CAMERA_9;

    fun allowsPosition(id:Int,x:Float,y:Float,width:Int,height:Int,continuation:Boolean=false):Boolean {
        if(!x.isFinite() || !y.isFinite()) return false
        if(x>=0f && y>=0f && x<width && y<height) return true
        return this==CONTINUOUS_CAMERA_9 && id==CAMERA_ID && continuation &&
            x>=-LIMIT && x<=LIMIT && y>=-LIMIT && y<=LIMIT
    }

    /**
     * An outside camera must already belong to this gesture. New DOWN/POINTER_DOWN coordinates
     * remain inside. Only a journal-authorized recovery CANCEL can lack local admitted state.
     */
    fun allowsCoordinates(frame:TouchFrame,width:Int,height:Int,previous:TouchFrame?=null,
                          recoveryCancellation:Boolean=false):Boolean {
        val count=frame.ids.size
        if(count !in 1..10 || frame.xs.size!=count || frame.ys.size!=count) return false
        val action=frame.action and 255
        val actionIndex=frame.action ushr 8
        val sameGesture=previous!=null && previous.downTime==frame.downTime
        val importedCancel=recoveryCancellation && frame.action==TouchComposer.CANCEL
        return frame.ids.indices.all { index ->
            val id=frame.ids[index]
            val newPointer=action==TouchComposer.DOWN ||
                (action==TouchComposer.POINTER_DOWN && index==actionIndex)
            val continued=!newPointer && sameGesture && previous!!.ids.contains(id)
            allowsPosition(id,frame.xs[index],frame.ys[index],width,height,continued || importedCancel)
        }
    }

    companion object {
        const val CAMERA_ID=9
        const val LIMIT=1_000_000f
    }
}
