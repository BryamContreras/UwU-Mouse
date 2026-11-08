package dev.mobimapper.mobimapper.session

/** Owned by one queue entry. Source samples remain separate until the precision filter runs. */
class RawMouseMotionBatch(
    val timesNanos: LongArray,
    val xs: FloatArray,
    val ys: FloatArray,
) {
    val size: Int get() = timesNanos.size
    init {
        require(size in 1..256 && xs.size == size && ys.size == size)
        require(timesNanos.all { it >= 0L } && xs.all { it.isFinite() } && ys.all { it.isFinite() })
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
    fun moveCursor(cursor:CursorPositionPolicy,untilExclusive:Int=size,sensitivity:Float=1f) {
        require(untilExclusive in 0..size)
        // Native records are unique. Different devices may legitimately share a
        // timestamp or arrive in a different timestamp order.
        for(index in 0 until untilExclusive) cursor.moveInput(xs[index],ys[index],sensitivity)
    }
}
