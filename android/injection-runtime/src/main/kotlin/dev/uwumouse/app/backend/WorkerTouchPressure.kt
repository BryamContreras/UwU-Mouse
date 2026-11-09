package dev.uwumouse.app.backend

/** Pressure describes each pointer's state in this frame, including the pointer being lifted. */
internal object WorkerTouchPressure {
    fun forPointer(action:Int,pointerIndex:Int):Float {
        require(pointerIndex in 0..9)
        return when(action and 0xff) {
            1,3 -> 0f // UP/CANCEL release the complete pointer set.
            6 -> if(pointerIndex==action ushr 8) 0f else 1f
            0,2,5 -> 1f
            else -> throw IllegalArgumentException("Unsupported touch pressure action")
        }
    }
}
