package dev.uwumouse.app.profile

/**
 * Projects a saved calibration into the currently verified game window for execution only.
 * Controls are already normalized to the calibrated area. The repository is never updated
 * here, and the regular compiler still validates the resulting dimensions and window frame.
 */
object RuntimeProfileGeometry {
    fun adapt(profile:GameProfile,currentWidth:Int,currentHeight:Int,rotation:Int,
              displayScope:String,windowFrame:GameArea):GameProfile {
        ProfileValidator.requireValid(profile)
        val saved=profile.calibration
        require(saved.confirmed) { "Confirma el área de juego antes de iniciar" }
        require(displayScope in setOf("phone","external") && saved.displayScope==displayScope) {
            "El perfil pertenece a otra pantalla; elige su mapeo"
        }
        require(currentWidth in 100..8192 && currentHeight in 100..8192 && rotation in 0..3) {
            "No se pudo confirmar la geometría de la pantalla"
        }
        require(validWindow(windowFrame,currentWidth,currentHeight)) {
            "La ventana del juego no tiene un área válida dentro de la pantalla"
        }
        val source=saved.windowFrame ?: GameArea(0f,0f,saved.screenWidth.toFloat(),saved.screenHeight.toFloat())
        if(saved.screenWidth==currentWidth && saved.screenHeight==currentHeight && saved.rotation==rotation &&
            source==windowFrame) return profile

        fun x(value:Float):Float=(windowFrame.left.toDouble()+
            (value.toDouble()-source.left)/source.width*windowFrame.width).toFloat()
            .coerceIn(windowFrame.left,windowFrame.right)
        fun y(value:Float):Float=(windowFrame.top.toDouble()+
            (value.toDouble()-source.top)/source.height*windowFrame.height).toFloat()
            .coerceIn(windowFrame.top,windowFrame.bottom)
        val area=saved.area
        val projected=GameArea(x(area.left),y(area.top),x(area.right),y(area.bottom))
        require(projected.width>=32f && projected.height>=32f) {
            "Amplía la ventana del juego para conservar su área de controles"
        }
        return ProfileValidator.requireValid(profile.copy(calibration=saved.copy(
            screenWidth=currentWidth,screenHeight=currentHeight,rotation=rotation,
            area=projected,windowFrame=windowFrame)))
    }

    private fun validWindow(frame:GameArea,width:Int,height:Int):Boolean =
        listOf(frame.left,frame.top,frame.right,frame.bottom).all { it.isFinite() } &&
            frame.left>=0f && frame.top>=0f && frame.right<=width && frame.bottom<=height &&
            frame.width>=32f && frame.height>=32f
}
