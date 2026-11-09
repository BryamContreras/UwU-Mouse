package dev.uwumouse.app.editor

import kotlin.math.roundToInt

/** Screen pixel geometry; at rest at most one edge clips the bubble, leaving a full half visible. */
object BubblePlacement {
    data class Position(val x:Int,val y:Int)
    fun position(style:BubbleSettings,width:Int,height:Int,side:Int):Position {
        require(width>side && height>side && side>0)
        val x=(style.x*(width-side)).roundToInt();val y=(style.y*(height-side)).roundToInt()
        return when(style.dockEdge) {
            "left" -> Position(-side/2,y)
            "right" -> Position(width-side/2,y)
            "top" -> Position(x,-side/2)
            "bottom" -> Position(x,height-side/2)
            else -> Position(x,y)
        }
    }
    fun drag(x:Int,y:Int,width:Int,height:Int,side:Int)=Position(
        x.coerceIn(-side/2,width-side/2),y.coerceIn(-side/2,height-side/2))
    fun settle(style:BubbleSettings,x:Int,y:Int,width:Int,height:Int,side:Int,threshold:Int):BubbleSettings {
        val centerX=x+side/2;val centerY=y+side/2
        val distances=listOf("left" to centerX,"right" to width-centerX,"top" to centerY,"bottom" to height-centerY)
        val edge=distances.minBy { it.second }.takeIf { it.second<=side/2+threshold }?.first ?: "none"
        return style.copy(x=(x.toFloat()/(width-side)).coerceIn(0f,1f),
            y=(y.toFloat()/(height-side)).coerceIn(0f,1f),dockEdge=edge)
    }
}
