package dev.mobimapper.mobimapper.session

/** Arrays transfer to one queue entry and must not be mutated after construction. */
class RawMouseMotionBatch(
    val timesNanos: LongArray,
    val xs: FloatArray,
    val ys: FloatArray,
) {
    val size: Int get() = timesNanos.size
    val movingSampleCount:Int
    init {
        require(size in 1..256 && xs.size == size && ys.size == size)
        var moving=0
        for(index in timesNanos.indices) {
            require(timesNanos[index]>=0L && xs[index].isFinite() && ys[index].isFinite())
            if(xs[index]!=0f || ys[index]!=0f) moving++
        }
        movingSampleCount=moving
    }
    inline fun filteredXSum(filter: (Float, Long) -> Float): Float {
        var sum=0.0
        for(index in timesNanos.indices) if(xs[index]!=0f || ys[index]!=0f)
            sum+=filter(xs[index],timesNanos[index]).toDouble()
        return sum.toFloat()
    }
    fun ySum():Float {
        var sum=0.0
        for(value in ys) sum+=value.toDouble()
        return sum.toFloat()
    }
    /** Stateful compensation/filtering runs on original samples before MOVE delivery coalesces. */
    inline fun deliverMotion(deliver:(Float,Float,Long)->Boolean):Boolean {
        for(index in timesNanos.indices) if(xs[index]!=0f || ys[index]!=0f) {
            if(!deliver(xs[index],ys[index],timesNanos[index])) return false
        }
        return true
    }
    fun moveCursor(cursor:CursorPositionPolicy,untilExclusive:Int=size,sensitivity:Float=1f) {
        require(untilExclusive in 0..size)
        // Native records are unique. Different devices may legitimately share a
        // timestamp or arrive in a different timestamp order.
        for(index in 0 until untilExclusive) cursor.moveInput(xs[index],ys[index],sensitivity)
    }
}
