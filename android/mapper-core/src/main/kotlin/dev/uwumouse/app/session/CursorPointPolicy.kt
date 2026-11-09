package dev.uwumouse.app.session

import dev.uwumouse.app.engine.TouchCoordinatePolicy
import kotlin.math.abs

data class PointerBounds(val left:Float,val top:Float,val right:Float,val bottom:Float) {
    fun contains(x:Float,y:Float)=x>=left && x<right && y>=top && y<bottom
}
object CursorPointPolicy {
    fun allows(x:Float,y:Float,target:PointerBounds?,blocked:List<PointerBounds>):Boolean =
        x.isFinite() && y.isFinite() && target?.contains(x,y)==true && blocked.none { it.contains(x,y) }
    fun allowsFrame(action:Int,xs:FloatArray,ys:FloatArray,target:PointerBounds?,blocked:List<PointerBounds>,
                    ids:IntArray=intArrayOf(),virtualCameraAdmitted:Boolean=false):Boolean {
        // Release must remain possible even after the system covers the former contact.
        val kind=action and 255
        if(kind==1 || kind==3 || kind==6) return true
        if(target==null || xs.isEmpty() || xs.size!=ys.size) return false
        val newPointer=if(kind==5) action ushr 8 else -1
        return xs.indices.all { index ->
            // Only the already admitted camera continues its UID-targeted gesture outside.
            // A new DOWN/POINTER_DOWN remains inside the verified game window.
            val continuation=virtualCameraAdmitted && ids.size==xs.size && ids[index]==TouchCoordinatePolicy.CAMERA_ID &&
                (kind==2 || (kind==5 && index!=newPointer))
            if(continuation) xs[index].isFinite() && ys[index].isFinite() &&
                abs(xs[index])<=TouchCoordinatePolicy.LIMIT && abs(ys[index])<=TouchCoordinatePolicy.LIMIT
            else allows(xs[index],ys[index],target,blocked)
        }
    }
}
