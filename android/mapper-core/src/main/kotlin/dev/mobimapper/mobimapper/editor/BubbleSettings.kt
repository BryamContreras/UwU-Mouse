package dev.mobimapper.mobimapper.editor

import dev.mobimapper.mobimapper.serialization.putValue

import org.json.JSONObject

/** UI preferences, separate from a game's calibrated mapping profile. */
data class BubbleSettings(val sizeDp: Int = 44, val opacity: Float = .85f, val x: Float = 1f, val y: Float = 0f,
                          val dockEdge:String="none",val showControls:Boolean=false,val controlsOpacity:Float=.55f,
                          val cursorSensitivity:Float=1f,val cursorSizeDp:Float=DEFAULT_CURSOR_SIZE_DP) {
    init {
        require(sizeDp in 32..72)
        require(opacity.isFinite() && opacity in .30f..1f)
        require(x.isFinite() && x in 0f..1f && y.isFinite() && y in 0f..1f)
        require(dockEdge in setOf("none","left","right","top","bottom"))
        require(controlsOpacity.isFinite() && controlsOpacity in .10f..1f)
        require(cursorSensitivity.isFinite() && cursorSensitivity in MIN_CURSOR_SENSITIVITY..MAX_CURSOR_SENSITIVITY)
        require(cursorSizeDp.isFinite() && cursorSizeDp in MIN_CURSOR_SIZE_DP..MAX_CURSOR_SIZE_DP)
    }
    fun encode(): String = JSONObject().putValue("schemaVersion", 2).putValue("sizeDp", sizeDp)
        .putValue("opacity", opacity).putValue("x", x).putValue("y", y).putValue("dockEdge",dockEdge)
        .putValue("showControls",showControls).putValue("controlsOpacity",controlsOpacity)
        .putValue("cursorSensitivity",cursorSensitivity).putValue("cursorSizeDp",cursorSizeDp).toString()
    companion object {
        const val MIN_CURSOR_SENSITIVITY=.10f
        const val MAX_CURSOR_SENSITIVITY=4f
        const val MIN_CURSOR_SIZE_DP=16f
        const val MAX_CURSOR_SIZE_DP=48f
        const val DEFAULT_CURSOR_SIZE_DP=24f

        fun decode(text: String): BubbleSettings {
            val value = JSONObject(text)
            val schema=value.getInt("schemaVersion")
            require(schema in 1..2) { "Versión de ajustes desconocida" }
            if(schema==2) {
                require(value.get("showControls") is Boolean && value.get("dockEdge") is String)
                require(value.get("controlsOpacity") is Number)
            }
            // Optional additive fields preserve schema 1/2 documents and their existing appearance.
            // Present malformed fields still fail rather than silently resetting a saved preference.
            fun cursorNumber(name:String,fallback:Float):Float = if(value.has(name)) {
                require(value.get(name) is Number) { "Ajuste de cursor no válido" }
                value.getDouble(name).toFloat()
            } else fallback
            return BubbleSettings(value.getInt("sizeDp"), value.getDouble("opacity").toFloat(),
                value.getDouble("x").toFloat(), value.getDouble("y").toFloat(),
                if(schema==2) value.getString("dockEdge") else "none",
                if(schema==2) value.getBoolean("showControls") else false,
                if(schema==2) value.getDouble("controlsOpacity").toFloat() else .55f,
                cursorNumber("cursorSensitivity",1f),cursorNumber("cursorSizeDp",DEFAULT_CURSOR_SIZE_DP))
        }
    }
}
