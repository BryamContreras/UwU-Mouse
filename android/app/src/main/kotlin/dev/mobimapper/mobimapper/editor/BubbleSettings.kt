package dev.mobimapper.mobimapper.editor

import org.json.JSONObject

/** UI preferences, separate from a game's calibrated mapping profile. */
data class BubbleSettings(val sizeDp: Int = 44, val opacity: Float = .85f, val x: Float = 1f, val y: Float = 0f,
                          val dockEdge:String="none",val showControls:Boolean=false,val controlsOpacity:Float=.55f) {
    init {
        require(sizeDp in 32..72)
        require(opacity.isFinite() && opacity in .30f..1f)
        require(x.isFinite() && x in 0f..1f && y.isFinite() && y in 0f..1f)
        require(dockEdge in setOf("none","left","right","top","bottom"))
        require(controlsOpacity.isFinite() && controlsOpacity in .10f..1f)
    }
    fun encode(): String = JSONObject().put("schemaVersion", 2).put("sizeDp", sizeDp)
        .put("opacity", opacity).put("x", x).put("y", y).put("dockEdge",dockEdge)
        .put("showControls",showControls).put("controlsOpacity",controlsOpacity).toString()
    companion object {
        fun decode(text: String): BubbleSettings {
            val value = JSONObject(text)
            val schema=value.getInt("schemaVersion")
            require(schema in 1..2) { "Versión de ajustes desconocida" }
            if(schema==2) {
                require(value.get("showControls") is Boolean && value.get("dockEdge") is String)
                require(value.get("controlsOpacity") is Number)
            }
            return BubbleSettings(value.getInt("sizeDp"), value.getDouble("opacity").toFloat(),
                value.getDouble("x").toFloat(), value.getDouble("y").toFloat(),
                if(schema==2) value.getString("dockEdge") else "none",
                if(schema==2) value.getBoolean("showControls") else false,
                if(schema==2) value.getDouble("controlsOpacity").toFloat() else .55f)
        }
    }
}
