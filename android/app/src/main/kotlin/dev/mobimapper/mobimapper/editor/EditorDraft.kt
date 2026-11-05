package dev.mobimapper.mobimapper.editor

import dev.mobimapper.mobimapper.profile.*
import kotlin.math.min
import java.util.UUID

/** A disposable editing transaction. It never writes the profile store. */
class EditorDraft(
    private val original: GameProfile,
    screenWidth: Int,
    screenHeight: Int,
    rotation: Int,
    suggestedArea: GameArea? = null,
) {
    enum class Control { JOYSTICK, CAMERA, ACTION, MACRO, AREA }

    var value: GameProfile
        private set
    var selectedAction: Int = 0
        private set
    var selectedMacro: Int = 0
        private set
    fun macro(): MacroSpec = value.macros[selectedMacro]
    fun selectMacro(index: Int) { require(index in value.macros.indices); selectedMacro = index }
    fun replaceMacro(macro: MacroSpec) {
        require(macro.id == this.macro().id) { "Identificador de macro no válido" }
        replace(value.copy(macros = value.macros.mapIndexed { index, old -> if (index == selectedMacro) macro else old }))
    }
    fun addMacro(screenX: Float, screenY: Float) {
        require(value.actions.size + value.macros.size < GameProfile.MAX_ACTIONS) { "Máximo 24 acciones y macros configuradas" }
        require(value.macros.size < GameProfile.MAX_MACROS) { "Máximo 24 macros configuradas" }
        val point = macroPoint(screenX, screenY)
        val usedNames = value.macros.map { it.name }.toSet()
        val number = (1..GameProfile.MAX_MACROS + 1).first { "Macro $it" !in usedNames }
        val macro = MacroSpec(UUID.randomUUID().toString(), "Macro $number", point.x, point.y, steps = listOf(point))
        replace(value.copy(macros = value.macros + macro))
        selectedMacro = value.macros.lastIndex
    }
    fun removeMacro() {
        require(value.macros.isNotEmpty()) { "Selecciona una macro" }
        replace(value.copy(macros = value.macros.filterIndexed { index, _ -> index != selectedMacro }))
        selectedMacro = selectedMacro.coerceAtMost((value.macros.size - 1).coerceAtLeast(0))
    }
    fun addMacroStep(screenX: Float, screenY: Float) {
        require(macro().steps.size < GameProfile.MAX_MACRO_STEPS) { "Máximo 16 puntos por macro" }
        replaceMacro(macro().copy(steps = macro().steps + macroPoint(screenX, screenY)))
    }
    fun moveMacroStep(index: Int, screenX: Float, screenY: Float) {
        require(index in macro().steps.indices)
        val point = macroPoint(screenX, screenY).copy(waitMs = macro().steps[index].waitMs)
        replaceMacro(macro().copy(steps = macro().steps.mapIndexed { i, old -> if (i == index) point else old }))
    }
    fun removeMacroStep(index: Int) {
        require(index in macro().steps.indices)
        require(macro().steps.size > 1) { "La macro necesita al menos un punto" }
        replaceMacro(macro().copy(steps = macro().steps.filterIndexed { i, _ -> i != index }))
    }
    fun macroWait(index: Int, waitMs: Int) {
        require(index in macro().steps.indices && waitMs in 0..GameProfile.MAX_MACRO_WAIT_MS)
        replaceMacro(macro().copy(steps = macro().steps.mapIndexed { i, old -> if (i == index) old.copy(waitMs = waitMs) else old }))
    }
    fun reorderMacroSteps(from: Int, to: Int) {
        require(from in macro().steps.indices && to in macro().steps.indices)
        val steps = macro().steps.toMutableList()
        steps.add(to, steps.removeAt(from))
        replaceMacro(macro().copy(steps = steps))
    }
    private fun macroPoint(screenX: Float, screenY: Float): MacroStep {
        require(screenX.isFinite() && screenY.isFinite())
        val area = value.calibration.area
        return MacroStep(area.normalizedX(screenX).coerceIn(.001f, .999f), area.normalizedY(screenY).coerceIn(.001f, .999f))
    }
    fun action():ActionSpec = value.actions.getOrNull(selectedAction) ?: value.action
    fun selectAction(index:Int) { require(index in value.actions.indices); selectedAction=index }
    fun replaceAction(action:ActionSpec) {
        val additionalIndex = selectedAction - if(value.action.enabled) 1 else 0
        replace(if(value.actions.isEmpty() || (value.action.enabled && selectedAction==0)) value.copy(action=action)
            else value.copy(additionalActions=value.additionalActions.mapIndexed { index,old -> if(index==additionalIndex) action else old }))
    }
    fun removeAction() {
        require(selectedAction in value.actions.indices) { "Selecciona una acción" }
        val remaining = value.actions.filterIndexed { index, _ -> index != selectedAction }
        replace(if(remaining.isEmpty()) value.copy(action=value.action.copy(x=.5f,y=.5f,code=0,enabled=false),additionalActions=emptyList())
            else value.copy(action=remaining.first(),additionalActions=remaining.drop(1)))
        selectedAction = selectedAction.coerceAtMost((value.actions.size - 1).coerceAtLeast(0))
    }
    fun removeJoystick() { replace(value.copy(joystick=value.joystick.copy(enabled=false))) }
    fun removeCamera() { replace(value.copy(camera=value.camera.copy(enabled=false))) }
    private fun addConfiguredAction(screenX:Float,screenY:Float,action:ActionSpec) {
        require(value.actions.size + value.macros.size < GameProfile.MAX_ACTIONS) { "Máximo 24 acciones y macros configuradas" }
        if(!value.action.enabled) {
            replace(value.copy(action=action.copy(enabled=true)))
            selectedAction=0
        } else {
            replace(value.copy(additionalActions=value.additionalActions + action.copy(enabled=true)))
            selectedAction=value.actions.lastIndex
        }
        move(Control.ACTION,screenX,screenY)
    }
    fun addRepeat(screenX:Float,screenY:Float) = addConfiguredAction(screenX,screenY,
        ActionSpec(.5f,.5f,code=0,behavior=ActionSpec.REPEAT))
    fun addCursorAction(screenX:Float,screenY:Float) = addConfiguredAction(screenX,screenY,
        ActionSpec(.5f,.5f,code=0,behavior=ActionSpec.CURSOR_TOGGLE))
    fun addAction(screenX:Float,screenY:Float,fire:Boolean=false,aim:Boolean=false) {
        require(!(fire && aim)) { "Elige disparo o apuntar" }
        val button=when { fire -> 1; aim -> 2; else -> null }
        val existing=if(button!=null) value.actions.indexOfFirst { it.triggerKind=="mouse" && it.code==button } else -1
        if(existing>=0) {
            selectAction(existing)
            if(action().behavior!=ActionSpec.TAP) replaceAction(action().copy(behavior=ActionSpec.TAP))
        } else {
            addConfiguredAction(screenX,screenY,ActionSpec(.5f,.5f,if(button!=null) "mouse" else "key",button ?: 0))
            return
        }
        move(Control.ACTION,screenX,screenY)
    }

    init {
        require(screenWidth >= 100 && screenHeight >= 100 && rotation in 0..3)
        val sameGeometry = original.calibration.screenWidth == screenWidth &&
            original.calibration.screenHeight == screenHeight && original.calibration.rotation == rotation
        val area = if (sameGeometry && original.calibration.confirmed) original.calibration.area else suggestedArea ?: GameArea(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat())
        value = original.copy(calibration = Calibration(screenWidth, screenHeight, rotation, boundedArea(area, screenWidth, screenHeight), false))
        value = boundedControls(value)
    }

    fun replace(profile: GameProfile) {
        require(profile.id == original.id && profile.packageName == original.packageName)
        require(profile.calibration.screenWidth == value.calibration.screenWidth &&
            profile.calibration.screenHeight == value.calibration.screenHeight && profile.calibration.rotation == value.calibration.rotation)
        value = boundedControls(profile)
    }

    fun move(control: Control, screenX: Float, screenY: Float) {
        require(screenX.isFinite() && screenY.isFinite())
        val a = value.calibration.area
        val x = ((screenX - a.left) / a.width).coerceIn(.001f, .999f)
        val y = ((screenY - a.top) / a.height).coerceIn(.001f, .999f)
        if(control==Control.ACTION) { replaceAction(action().copy(x=x,y=y)); return }
        if(control==Control.MACRO) { replaceMacro(macro().copy(x=x,y=y)); return }
        value = boundedControls(when (control) {
            Control.JOYSTICK -> value.copy(joystick = value.joystick.copy(x = x, y = y))
            Control.CAMERA -> value.copy(camera = value.camera.copy(x = x, y = y))
            Control.ACTION -> value
            Control.MACRO -> value
            Control.AREA -> value
        })
    }

    fun joystickRadius(pixels: Float) {
        require(pixels.isFinite())
        val a = value.calibration.area
        value = boundedControls(value.copy(joystick = value.joystick.copy(radius = (pixels / min(a.width, a.height)).coerceIn(.015f, .40f))))
    }

    fun cameraSize(widthPixels: Float, heightPixels: Float) {
        require(widthPixels.isFinite() && heightPixels.isFinite())
        val a = value.calibration.area
        value = boundedControls(value.copy(camera = value.camera.copy(
            width = (widthPixels / a.width).coerceIn(.04f, .95f),
            height = (heightPixels / a.height).coerceIn(.04f, .95f),
        )))
    }

    /** Mode changes neither recalibrate nor move the saved start point or other controls. */
    fun cameraMode(mode: String) {
        require(CameraSpec.isSupportedMode(mode)) { "Modo de cámara no válido" }
        value = value.copy(camera = value.camera.copy(mode = mode))
    }

    fun cameraUpdateRate(hz: Int) {
        require(CameraSpec.isSupportedUpdateRate(hz)) { "Frecuencia de movimiento no válida" }
        value=value.copy(camera=value.camera.copy(updateRateHz=hz))
    }

    /** Visual diameter only. Dragging clamps to the editor range; it never moves the touch. */
    fun markerSize(control: Control,diameterDp: Float) {
        require(diameterDp.isFinite()) { "Tamaño visual no válido" }
        require(control==Control.CAMERA || control==Control.ACTION || control==Control.MACRO) { "Este control no tiene marcador configurable" }
        val size=diameterDp.coerceIn(MarkerStyle.MIN_DP,MarkerStyle.MAX_DP)
        if(control==Control.ACTION) { replaceAction(action().copy(markerSizeDp=size));return }
        value=when(control) {
            Control.CAMERA -> value.copy(camera=value.camera.copy(markerSizeDp=size))
            Control.ACTION -> value
            Control.MACRO -> value.copy(macros=value.macros.mapIndexed { index, old ->
                if(index==selectedMacro) old.copy(markerSizeDp=size) else old })
            else -> value
        }
    }

    fun area(area: GameArea) {
        val c = value.calibration
        value = boundedControls(value.copy(calibration = c.copy(area = boundedArea(area, c.screenWidth, c.screenHeight), confirmed = false)))
    }

    /** Both the key panel and direct canvas assignment edit the same disposable draft. */
    fun bindKey(field: String, code: Int) {
        require(code in 1..340 || (field == "cameraSuspend" && code == 0)) { "Tecla no compatible" }
        val p = value
        replace(when(field) {
            "up" -> p.copy(joystick=p.joystick.copy(upKey=code))
            "down" -> p.copy(joystick=p.joystick.copy(downKey=code))
            "left" -> p.copy(joystick=p.joystick.copy(leftKey=code))
            "right" -> p.copy(joystick=p.joystick.copy(rightKey=code))
            "action" -> { replaceAction(action().copy(triggerKind="key",code=code)); return }
            "macro" -> { replaceMacro(macro().copy(triggerKind="key",code=code)); return }
            "cameraSuspend" -> p.copy(camera=p.camera.copy(suspendKey=code))
            "cameraToggle" -> p.copy(shortcuts=p.shortcuts.copy(cameraToggle=code))
            "stop" -> p.copy(shortcuts=p.shortcuts.copy(stop=code))
            "edit" -> p.copy(shortcuts=p.shortcuts.copy(edit=code))
            else -> error("Asignación desconocida")
        })
    }

    /** Saving confirms this exact orientation/geometry; cancel returns the untouched original. */
    fun save(): GameProfile = ProfileValidator.requireValid(value.copy(calibration = value.calibration.copy(confirmed = true)))
    fun cancel(): GameProfile = original

    companion object {
        private fun boundedArea(a: GameArea, width: Int, height: Int): GameArea {
            require(listOf(a.left, a.top, a.right, a.bottom).all { it.isFinite() })
            val minWidth = min(100f, width * .2f)
            val minHeight = min(100f, height * .2f)
            val left = a.left.coerceIn(0f, width - minWidth)
            val top = a.top.coerceIn(0f, height - minHeight)
            return GameArea(left, top, a.right.coerceIn(left + minWidth, width.toFloat()), a.bottom.coerceIn(top + minHeight, height.toFloat()))
        }

        private fun boundedControls(p: GameProfile): GameProfile {
            val a = p.calibration.area
            val radius = p.joystick.radius.coerceIn(.015f, .40f)
            val rx = radius * min(a.width, a.height) / a.width + .001f
            val ry = radius * min(a.width, a.height) / a.height + .001f
            val cw = p.camera.width.coerceIn(.04f, .95f)
            val ch = p.camera.height.coerceIn(.04f, .95f)
            return p.copy(
                joystick = p.joystick.copy(radius = radius, x = p.joystick.x.coerceIn(rx, 1f - rx), y = p.joystick.y.coerceIn(ry, 1f - ry)),
                camera = p.camera.copy(width = cw, height = ch, x = p.camera.x.coerceIn(cw / 2f + .001f, 1f - cw / 2f - .001f), y = p.camera.y.coerceIn(ch / 2f + .001f, 1f - ch / 2f - .001f)),
                action = p.action.copy(x = p.action.x.coerceIn(.001f, .999f), y = p.action.y.coerceIn(.001f, .999f)),
                additionalActions = p.additionalActions.map { it.copy(x=it.x.coerceIn(.001f,.999f),y=it.y.coerceIn(.001f,.999f)) },
                macros = p.macros.map { macro -> macro.copy(x=macro.x.coerceIn(.001f,.999f),y=macro.y.coerceIn(.001f,.999f),
                    steps=macro.steps.map { step -> step.copy(x=step.x.coerceIn(.001f,.999f),y=step.y.coerceIn(.001f,.999f)) }) },
            )
        }
    }
}
