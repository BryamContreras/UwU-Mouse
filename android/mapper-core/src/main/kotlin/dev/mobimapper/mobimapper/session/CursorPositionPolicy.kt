package dev.mobimapper.mobimapper.session

/** Visual and injected hotspot share one position; Android's old absolute cursor is never reused. */
class CursorPositionPolicy {
    var x=0f;private set
    var y=0f;private set
    private var preciseX=0.0
    private var preciseY=0.0
    private var maxX=0f
    private var maxY=0f
    private var lastSampleTimeNanos=Long.MIN_VALUE

    fun begin(area:PointerBounds,displayWidth:Int,displayHeight:Int):Boolean {
        if(displayWidth<=0 || displayHeight<=0 || !valid(area)) return false
        maxX=Math.nextDown(displayWidth.toFloat());maxY=Math.nextDown(displayHeight.toFloat())
        preciseX=((area.left.toDouble()+area.right)*.5).coerceIn(0.0,maxX.toDouble())
        preciseY=((area.top.toDouble()+area.bottom)*.5).coerceIn(0.0,maxY.toDouble())
        x=preciseX.toFloat();y=preciseY.toFloat()
        lastSampleTimeNanos=Long.MIN_VALUE
        return true
    }
    /** Button/hover callbacks can repeat the same physical movement and source timestamp. */
    fun moveSample(dx:Float,dy:Float,sampleTimeNanos:Long) {
        if(sampleTimeNanos>=0L) {
            if(sampleTimeNanos<=lastSampleTimeNanos) return
            lastSampleTimeNanos=sampleTimeNanos
        }
        move(dx,dy)
    }
    fun move(dx:Float,dy:Float) {
        moveBy(dx.toDouble(),dy.toDouble())
    }
    /** Physical cursor gain only. Absolute placement/restoration continues to use move(). */
    fun moveInput(dx:Float,dy:Float,sensitivity:Float=1f) {
        if(!sensitivity.isFinite() || sensitivity<=0f) return
        val gain=sensitivity.toDouble()
        moveBy(dx.toDouble()*gain,dy.toDouble()*gain)
    }
    private fun moveBy(dx:Double,dy:Double) {
        if(dx.isFinite()) { preciseX=(preciseX+dx).coerceIn(0.0,maxX.toDouble());x=preciseX.toFloat() }
        if(dy.isFinite()) { preciseY=(preciseY+dy).coerceIn(0.0,maxY.toDouble());y=preciseY.toFloat() }
    }
    companion object {
        private fun valid(area:PointerBounds)=area.left.isFinite() && area.top.isFinite() &&
            area.right.isFinite() && area.bottom.isFinite() && area.left<area.right && area.top<area.bottom

        /** Calibration may occupy only part of the verified target window. */
        fun entryArea(calibrated:PointerBounds?,target:PointerBounds):PointerBounds {
            if(calibrated==null || !valid(calibrated)) return target
            val left=maxOf(calibrated.left,target.left);val top=maxOf(calibrated.top,target.top)
            val right=minOf(calibrated.right,target.right);val bottom=minOf(calibrated.bottom,target.bottom)
            return if(left<right && top<bottom) PointerBounds(left,top,right,bottom) else target
        }
    }
}
