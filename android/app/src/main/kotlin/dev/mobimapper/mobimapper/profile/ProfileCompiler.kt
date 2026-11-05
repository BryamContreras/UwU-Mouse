package dev.mobimapper.mobimapper.profile

import dev.mobimapper.mobimapper.engine.MappingBindings
import dev.mobimapper.mobimapper.engine.MappingGeometry
import dev.mobimapper.mobimapper.engine.PreparedTouch
import dev.mobimapper.mobimapper.engine.PreparedMacro
import dev.mobimapper.mobimapper.engine.PreparedMacroStep
import dev.mobimapper.mobimapper.engine.PreparedRepeat
import dev.mobimapper.mobimapper.engine.PreparedCursorAction

/** Immutable execution snapshot; building this is control-plane work, never input-event work. */
data class PreparedProfile(val profile: GameProfile, val geometry: MappingGeometry, val bindings: MappingBindings,
                           val macros: List<PreparedMacro> = emptyList(),
                           val repeats: List<PreparedRepeat> = emptyList(),
                           val cursorActions: List<PreparedCursorAction> = emptyList())

object ProfileCompiler {
    fun prepare(profile: GameProfile, currentWidth: Int, currentHeight: Int, rotation: Int): PreparedProfile {
        ProfileValidator.requireValid(profile)
        val c = profile.calibration
        require(c.confirmed) { "Abre el editor y confirma el área de juego antes de iniciar" }
        require(c.screenWidth == currentWidth && c.screenHeight == currentHeight && c.rotation == rotation) {
            "Cambió la orientación o geometría de pantalla; recalibra el perfil"
        }
        val area = c.area
        val j = profile.joystick
        val camera = profile.camera
        val action = profile.actions.firstOrNull() ?: ActionSpec(.5f, .5f, code=0, enabled=false)
        val actions = profile.actions.withIndex().toList()
        val commands = actions.filter { it.value.behavior != ActionSpec.TAP }
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
            continuousCamera = camera.enabled && camera.mode == CameraSpec.CONTINUOUS,
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
            PreparedRepeat("repeat:$index",area.displayX(action.x),area.displayY(action.y),action.triggerKind,action.code,action.repeatIntervalMs) }
        val cursorActions = actions.filter { it.value.behavior == ActionSpec.CURSOR_TOGGLE }.map { (index,action) ->
            PreparedCursorAction("cursor:$index",area.displayX(action.x),area.displayY(action.y),action.triggerKind,action.code) }
        return PreparedProfile(profile, geometry, bindings, macros, repeats, cursorActions)
    }
}
