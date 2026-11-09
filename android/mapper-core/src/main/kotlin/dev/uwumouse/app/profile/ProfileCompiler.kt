package dev.uwumouse.app.profile

import dev.uwumouse.app.engine.MappingBindings
import dev.uwumouse.app.engine.MappingGeometry
import dev.uwumouse.app.engine.PreparedTouch
import dev.uwumouse.app.engine.PreparedMacro
import dev.uwumouse.app.engine.PreparedMacroStep
import dev.uwumouse.app.engine.PreparedRepeat
import dev.uwumouse.app.engine.PreparedCursorAction
import dev.uwumouse.app.engine.AimBounds
import dev.uwumouse.app.engine.GgCameraPolicy

/** Immutable execution snapshot; building this is control-plane work, never input-event work. */
data class PreparedProfile(val profile: GameProfile, val geometry: MappingGeometry, val bindings: MappingBindings,
                           val macros: List<PreparedMacro> = emptyList(),
                           val repeats: List<PreparedRepeat> = emptyList(),
                           val cursorActions: List<PreparedCursorAction> = emptyList(),
                           val fireCadence: PreparedFireCadence? = null)

/** Resolution-aware camera speed. It neither taps nor owns a separate game contact. */
data class PreparedFireCadence(val triggerKind: String = "mouse", val code: Int = 1,
                               val verticalPixelsPerSecond: Float,
                               val delayMillis: Long = START_DELAY_MILLIS,
                               val durationMillis: Long = 0L) {
    init {
        require(triggerKind == "mouse" && code == 1)
        require(verticalPixelsPerSecond.isFinite())
        require(delayMillis in 0L..5_000L && durationMillis in 0L..10_000L)
    }
    companion object { const val START_DELAY_MILLIS=500L }
}

object ProfileCompiler {
    fun prepare(profile: GameProfile, currentWidth: Int, currentHeight: Int, rotation: Int,
                displayScope: String = "phone", windowFrame: GameArea? = null): PreparedProfile {
        ProfileValidator.requireValid(profile)
        val c = profile.calibration
        require(c.confirmed) { "Abre el editor y confirma el área de juego antes de iniciar" }
        require(displayScope in setOf("phone","external") && c.displayScope==displayScope) {
            "El perfil pertenece a otra pantalla; elige o calibra su mapeo"
        }
        if(c.displayScope=="external" || c.windowFrame!=null) require(c.windowFrame!=null && c.windowFrame==windowFrame) {
            "Cambió la ventana del juego; recalibra el perfil"
        }
        require(c.screenWidth == currentWidth && c.screenHeight == currentHeight && c.rotation == rotation) {
            "Cambió la orientación o geometría de pantalla; recalibra el perfil"
        }
        val area = c.area
        val j = profile.joystick
        // Old mode tags remain readable in stored documents; every game profile runs Smooth.
        val camera = profile.camera.copy(mode=CameraSpec.SMOOTH)
        val action = profile.actions.firstOrNull() ?: ActionSpec(.5f, .5f, code=0, enabled=false)
        val actions = profile.actions.withIndex().toList()
        val commands = actions.filter { it.value.behavior != ActionSpec.TAP }
        val ggCameraPolicy=if(camera.enabled) {
            val freeFire=CameraSpec.isGgFreeFirePackage(profile.packageName)
            val bounds=if(camera.customRegion && !freeFire) AimBounds(
                area.displayX(camera.x-camera.width/2),area.displayY(camera.y-camera.height/2),
                area.displayX(camera.x+camera.width/2),area.displayY(camera.y+camera.height/2))
            else {
                val insetX=if(freeFire) 0f else minOf(50f,(area.width-1f)/2)
                val insetY=if(freeFire) 0f else minOf(50f,(area.height-1f)/2)
                AimBounds(area.left+insetX,area.top+insetY,
                    Math.nextDown(area.right-insetX),Math.nextDown(area.bottom-insetY))
            }
            GgCameraPolicy(freeFire,bounds,maxOf(area.width,area.height))
        } else null
        val geometry = MappingGeometry(currentWidth.toFloat(), currentHeight.toFloat(),
            joystickX = area.displayX(j.x), joystickY = area.displayY(j.y),
            joystickRadius = j.radius * minOf(area.width, area.height),
            cameraX = area.displayX(camera.x), cameraY = area.displayY(camera.y),
            cameraRadiusX = camera.width * area.width / 2, cameraRadiusY = camera.height * area.height / 2,
            actionX = area.displayX(action.x), actionY = area.displayY(action.y),
            fireX = area.displayX(action.x), fireY = area.displayY(action.y),
            sensitivityX = camera.sensitivityX, sensitivityY = camera.sensitivityY)
        val bindings = MappingBindings(j.upKey, j.downKey, j.leftKey, j.rightKey,
            actionKey = null, actionButton = null, distinctCameraContacts = true, overlapCameraHandoff = false, cameraLast = true,
            continuousCamera = camera.enabled,
            ggCameraPolicy = ggCameraPolicy,
            joystickEnabled = j.enabled, cameraMappingEnabled = camera.enabled,
            commandKeys = commands.filter { it.value.triggerKind == "key" }.map { it.value.code }.toSet(),
            commandButtons = commands.filter { it.value.triggerKind == "mouse" }.map { it.value.code }.toSet(),
            macroKeys = profile.macros.filter { it.triggerKind == "key" }.map { it.code }.toSet(),
            macroButtons = profile.macros.filter { it.triggerKind == "mouse" }.map { it.code }.toSet(),
            touches = actions.filter { it.value.behavior == ActionSpec.TAP }.map { (index,touch) -> PreparedTouch("touch:$index",area.displayX(touch.x),area.displayY(touch.y),
                key=if(touch.triggerKind=="key") touch.code else null,button=if(touch.triggerKind=="mouse") touch.code else null,
                mouseAim=false) })
        val macros = profile.macros.map { macro -> PreparedMacro(macro.id, "macro:${macro.id}", macro.triggerKind, macro.code,
            macro.steps.map { step -> PreparedMacroStep(area.displayX(step.x), area.displayY(step.y), step.waitMs) }) }
        val repeats = actions.filter { it.value.behavior == ActionSpec.REPEAT }.map { (index,action) ->
            PreparedRepeat("repeat:$index",area.displayX(action.x),area.displayY(action.y),action.triggerKind,action.code,
                action.repeatIntervalMs,action.repeatToggle,action.repeatDurationSeconds) }
        val cursorActions = actions.filter { it.value.behavior == ActionSpec.CURSOR_TOGGLE }.map { (index,action) ->
            PreparedCursorAction("cursor:$index",area.displayX(action.x),area.displayY(action.y),action.triggerKind,action.code) }
        val fireCadence = profile.actions.singleOrNull {
            camera.enabled && it.isM1Tap && it.cadenceEnabled && it.cadenceYPercent != 0
        }?.let {
            PreparedFireCadence(verticalPixelsPerSecond = -it.cadenceYPercent / 100f * area.height * .20f,
                delayMillis=it.cadenceStartMs.toLong(),durationMillis=if(it.cadenceDurationEnabled) it.cadenceDurationMs.toLong() else 0L)
        }
        return PreparedProfile(profile.copy(camera=camera), geometry, bindings, macros, repeats, cursorActions, fireCadence)
    }
}
