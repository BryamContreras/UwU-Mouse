package dev.uwumouse.app.profile

import java.util.UUID
import dev.uwumouse.app.engine.MACRO_TAP_MS

/** Positions are normalized to [Calibration.area], never to an assumed fullscreen canvas. */
data class GameProfile(
    val id: String,
    val name: String,
    val packageName: String,
    val schemaVersion: Int = SCHEMA_VERSION,
    val calibration: Calibration,
    val joystick: JoystickSpec,
    val camera: CameraSpec,
    val action: ActionSpec,
    val shortcuts: SessionShortcuts = SessionShortcuts(),
    val additionalActions: List<ActionSpec> = emptyList(),
    val macros: List<MacroSpec> = emptyList(),
    /** Optional physical keyboard shortcut selecting this profile. Zero means unassigned. */
    val switchKey: Int = 0,
) {
    val actions: List<ActionSpec> get() = (if (action.enabled) listOf(action) else emptyList()) + additionalActions
    val orientation: String get() = if (calibration.screenWidth > calibration.screenHeight) "landscape" else "portrait"

    companion object {
        const val SCHEMA_VERSION = 9
        const val MAX_ACTIONS = 24 // Configured bindings; the live injector still has ten contacts.
        const val MAX_MACROS = 24
        const val MAX_MACRO_STEPS = 16
        const val MAX_MACRO_WAIT_MS = 5_000
        const val MAX_MACRO_DURATION_MS = 30_000
        fun create(packageName: String, name: String, width: Int, height: Int, rotation: Int,
                   displayScope: String = "phone"): GameProfile =
            GameProfile(UUID.randomUUID().toString(), name.trim(), packageName,
                calibration = Calibration(width, height, rotation, GameArea(0f, 0f, width.toFloat(), height.toFloat()), displayScope=displayScope),
                joystick = JoystickSpec(.15f, .73f, .09f),
                camera = CameraSpec(.67f, .43f, .38f, .50f),
                action = ActionSpec(.87f, .80f))
    }
}

data class Calibration(
    val screenWidth: Int,
    val screenHeight: Int,
    val rotation: Int,
    val area: GameArea,
    val confirmed: Boolean = false,
    val displayScope: String = "phone",
    val windowFrame: GameArea? = null,
)

/** Logical display pixels; right/bottom are the exclusive edges of the calibrated game area. */
data class GameArea(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun displayX(normalized: Float): Float = left + normalized * width
    fun displayY(normalized: Float): Float = top + normalized * height
    fun normalizedX(display: Float): Float = (display - left) / width
    fun normalizedY(display: Float): Float = (display - top) / height
}

data class JoystickSpec(
    val x: Float,
    val y: Float,
    /** Fraction of min(area.width, area.height). */
    val radius: Float,
    val upKey: Int = 51,
    val downKey: Int = 47,
    val leftKey: Int = 29,
    val rightKey: Int = 32,
    val enabled: Boolean = true,
)

data class CameraSpec(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val sensitivityX: Float = .70f,
    val sensitivityY: Float = .70f,
    val mode: String = SMOOTH,
    /** Native motion capture delivery cap; zero preserves unrestricted delivery. */
    val updateRateHz: Int = 250,
    val markerSizeDp: Float = MarkerStyle.DEFAULT_DP,
    /** Opt-in input smoothing, active only while the mapped physical fire button is held. */
    val precisionAim: Boolean = false,
    /** Zero disables the optional hold-to-show-cursor key. */
    val suspendKey: Int = 0,
    val enabled: Boolean = true,
    /** Optional physical mouse hold-to-show-cursor button; distinct from Android key codes. */
    val suspendMouseButton: Int = 0,
    /** Smooth otherwise uses the calibrated game area with a 50-pixel inset. */
    val customRegion: Boolean = false,
) {
    companion object {
        const val BOUNDED = "bounded"
        const val CONTINUOUS = "continuous"
        const val SMOOTH = "smooth"
        val CAPTURE_RATE_CHOICES_HZ: List<Int> = listOf(100,125,250,500,1000,0)
        // Existing profiles remain valid and retain their saved value until explicitly edited.
        val UPDATE_RATES_HZ: Set<Int> = (CAPTURE_RATE_CHOICES_HZ + listOf(60,90,120,144,240)).toSet()
        fun isSupportedMode(mode: String): Boolean = mode == BOUNDED || mode == CONTINUOUS || mode == SMOOTH
        fun isGgFreeFirePackage(packageName:String):Boolean =
            packageName.contains("com.dts.freefire") || packageName.contains("com.garena.game.ffi")
        fun isSupportedUpdateRate(hz: Int): Boolean = hz in UPDATE_RATES_HZ
        fun isSupportedSuspendMouseButton(button:Int):Boolean = button in setOf(0,1,2,4,8,16)
    }
}

/** Marker dimensions change the editor appearance, never a game touch point or joystick radius. */
object MarkerStyle {
    const val DEFAULT_DP = 52f
    const val MIN_DP = 24f
    const val MAX_DP = 120f
    fun isValid(diameterDp: Float): Boolean = diameterDp.isFinite() && diameterDp in MIN_DP..MAX_DP
}

data class ActionSpec(val x: Float, val y: Float, val triggerKind: String = "key", val code: Int = 62,
                      val markerSizeDp: Float = MarkerStyle.DEFAULT_DP,
                      val behavior: String = TAP,
                      val repeatIntervalMs: Int = 100,
                      val enabled: Boolean = true,
                      /** Optional vertical camera motion while the physical M1 tap remains held. */
                      val cadenceEnabled: Boolean = false,
                      /** Positive moves up; zero adds no movement. This never repeats the fire tap. */
                      val cadenceYPercent: Int = 0,
                      /** Delay before the movement starts; zero starts immediately. */
                      val cadenceStartMs: Int = DEFAULT_CADENCE_START_MS,
                      /** Zero keeps moving until M1 is released. */
                      val cadenceDurationMs: Int = DEFAULT_CADENCE_DURATION_MS,
                      val cadenceDurationEnabled: Boolean = cadenceDurationMs > 0,
                      val repeatToggle: Boolean = false,
                      /** Zero leaves a toggled repeat running until its next physical press. */
                      val repeatDurationSeconds: Int = 0) {
    val isM1Tap: Boolean get() = enabled && triggerKind == "mouse" && code == 1 && behavior == TAP
    companion object {
        const val TAP = "tap"
        const val REPEAT = "repeat"
        const val CURSOR_TOGGLE = "cursorToggle"
        const val DEFAULT_CADENCE_START_MS = 500
        const val DEFAULT_CADENCE_DURATION_MS = 0
        const val MAX_REPEAT_DURATION_SECONDS = 86_400
        fun isSupportedBehavior(value: String) = value in setOf(TAP, REPEAT, CURSOR_TOGGLE)
        fun isSupportedInterval(value: Int) = value in 30..5_000
    }
}

/** A finite sequence of taps. Each wait starts after the corresponding fixed-duration tap ends. */
data class MacroSpec(
    val id: String,
    val name: String,
    val x: Float,
    val y: Float,
    val triggerKind: String = "key",
    val code: Int = 0,
    val markerSizeDp: Float = MarkerStyle.DEFAULT_DP,
    val steps: List<MacroStep>,
)
data class MacroStep(val x: Float, val y: Float, val waitMs: Int = 100)
data class SessionShortcuts(val cameraToggle: Int = 138, val stop: Int = 0, val edit: Int = 0)

object ProfileValidator {
    private val idPattern = Regex("^[A-Za-z0-9._-]{1,80}$")
    private val packagePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val mouseButtons = setOf(1, 2, 4, 8, 16)
    private fun keyValid(code: Int) = code in 1..340

    /** Drafts may be unconfirmed; only the compiler can authorize a calibrated execution. */
    fun validate(profile: GameProfile): List<String> {
        val errors = mutableListOf<String>()
        fun check(ok: Boolean, message: String) { if (!ok) errors.add(message) }
        check(profile.schemaVersion == GameProfile.SCHEMA_VERSION, "Versión de perfil no compatible")
        check(idPattern.matches(profile.id), "Identificador de perfil no válido")
        check(profile.name.isNotBlank() && profile.name.length <= 80 && profile.name.none { it.isISOControl() }, "Nombre de perfil no válido")
        check(profile.packageName.length <= 255 && packagePattern.matches(profile.packageName), "Paquete de destino no válido")
        check(profile.switchKey == 0 || keyValid(profile.switchKey), "Tecla de perfil no válida")
        val c = profile.calibration
        val a = c.area
        check(c.screenWidth in 100..8192 && c.screenHeight in 100..8192, "Dimensiones de calibración no válidas")
        check(c.rotation in 0..3, "Rotación de calibración no válida")
        val areaFinite = listOf(a.left, a.top, a.right, a.bottom).all { it.isFinite() }
        val areaValid = areaFinite && a.left >= 0 && a.top >= 0 && a.right <= c.screenWidth && a.bottom <= c.screenHeight && a.width >= 32 && a.height >= 32
        check(areaValid, "El área de juego debe quedar dentro de la pantalla")
        check(c.displayScope in setOf("phone", "external"), "Pantalla de calibración no válida")
        check(c.displayScope != "external" || !c.confirmed || c.windowFrame != null,
            "Confirma la ventana de DeX antes de iniciar")
        c.windowFrame?.let { frame ->
            val frameValid=listOf(frame.left,frame.top,frame.right,frame.bottom).all { it.isFinite() } &&
                frame.left>=0 && frame.top>=0 && frame.right<=c.screenWidth && frame.bottom<=c.screenHeight &&
                frame.width>=32 && frame.height>=32
            check(frameValid, "La ventana calibrada debe quedar dentro de la pantalla")
            check(frameValid && areaValid && a.left>=frame.left && a.top>=frame.top &&
                a.right<=frame.right && a.bottom<=frame.bottom, "El área de juego debe quedar dentro de la ventana calibrada")
        }
        val j = profile.joystick
        val camera = profile.camera
        check(CameraSpec.isSupportedMode(camera.mode), "Modo de cámara no válido")
        check(CameraSpec.isSupportedUpdateRate(camera.updateRateHz), "Frecuencia de captura no válida")
        check(MarkerStyle.isValid(camera.markerSizeDp), "Tamaño visual de cámara fuera del intervalo 24–120 dp")
        fun position(x: Float, y: Float) = x.isFinite() && y.isFinite() && x >= 0 && x < 1 && y >= 0 && y < 1
        check(position(j.x, j.y), "Centro del joystick fuera del área")
        check(j.radius.isFinite() && j.radius in .005f.. .45f, "Radio del joystick no válido")
        if (areaValid && position(j.x, j.y) && j.radius.isFinite()) {
            val r = j.radius * minOf(a.width, a.height)
            val x = a.displayX(j.x); val y = a.displayY(j.y)
            check(x - r >= a.left && x + r < a.right && y - r >= a.top && y + r < a.bottom, "El joystick completo debe quedar dentro del área")
        }
        check(position(camera.x, camera.y) && camera.width.isFinite() && camera.height.isFinite() &&
            camera.width >= .01f && camera.height >= .01f && camera.x - camera.width / 2 >= 0 &&
            camera.y - camera.height / 2 >= 0 && camera.x + camera.width / 2 < 1 && camera.y + camera.height / 2 < 1,
            "La región de cámara debe quedar dentro del área")
        check(camera.sensitivityX.isFinite() && camera.sensitivityY.isFinite() &&
            camera.sensitivityX in .01f..20f && camera.sensitivityY in .01f..20f, "Sensibilidad fuera del intervalo 0,01–20")
        val actions = profile.actions
        val macros = profile.macros
        check(profile.additionalActions.all { it.enabled }, "Las acciones adicionales deben estar activas")
        check(actions.size + macros.size <= GameProfile.MAX_ACTIONS, "Máximo 24 acciones y macros configuradas")
        for(action in listOf(profile.action) + profile.additionalActions) {
            check(position(action.x, action.y), "Acción táctil fuera del área")
            check(MarkerStyle.isValid(action.markerSizeDp), "Tamaño visual de acción fuera del intervalo 24–120 dp")
            check(action.triggerKind in setOf("key", "mouse"), "Tipo de asignación no válido")
            check(ActionSpec.isSupportedBehavior(action.behavior), "Comportamiento de acción no válido")
            check(ActionSpec.isSupportedInterval(action.repeatIntervalMs), "Intervalo de repetición fuera del intervalo 30–5000 ms")
            check(action.repeatDurationSeconds in 0..ActionSpec.MAX_REPEAT_DURATION_SECONDS,
                "Temporizador de repetición fuera del intervalo 0–86400 segundos")
            check(action.behavior == ActionSpec.REPEAT || (!action.repeatToggle && action.repeatDurationSeconds == 0),
                "El bucle y su temporizador sólo están disponibles en Repetir")
            check(action.cadenceYPercent in -100..100, "Intensidad de cadencia fuera del intervalo -100–100 %")
            check(action.cadenceStartMs in 0..5_000, "Tiempo de inicio de cadencia fuera del intervalo 0–5000 ms")
            check(action.cadenceDurationMs in 0..10_000, "Duración de cadencia fuera del intervalo 0–10000 ms")
            check(!action.cadenceDurationEnabled || action.cadenceDurationMs > 0, "La duración debe ser mayor que 0 ms")
            check(action.isM1Tap || (!action.cadenceEnabled && action.cadenceYPercent == 0 &&
                action.cadenceStartMs == ActionSpec.DEFAULT_CADENCE_START_MS &&
                action.cadenceDurationMs == ActionSpec.DEFAULT_CADENCE_DURATION_MS && !action.cadenceDurationEnabled),
                "La cadencia sólo está disponible en el disparo M1 de pulsación normal")
            check((!action.enabled && action.code == 0) || if (action.triggerKind == "mouse") action.code in mouseButtons else keyValid(action.code), "Asigna una tecla o botón a cada acción")
        }
        check(macros.size <= GameProfile.MAX_MACROS, "Máximo 24 macros configuradas")
        check(macros.map { it.id }.distinct().size == macros.size, "Identificadores de macro duplicados")
        for (macro in macros) {
            check(idPattern.matches(macro.id), "Identificador de macro no válido")
            check(macro.name.isNotBlank() && macro.name.length <= 80 && macro.name.none { it.isISOControl() }, "Nombre de macro no válido")
            check(position(macro.x, macro.y), "Macro fuera del área")
            check(MarkerStyle.isValid(macro.markerSizeDp), "Tamaño visual de macro fuera del intervalo 24–120 dp")
            check(macro.triggerKind in setOf("key", "mouse"), "Tipo de asignación de macro no válido")
            check(if (macro.triggerKind == "mouse") macro.code in mouseButtons else keyValid(macro.code), "Asigna una tecla o botón a cada macro")
            check(macro.steps.size in 1..GameProfile.MAX_MACRO_STEPS, "Cada macro necesita entre 1 y 16 puntos")
            check(macro.steps.all { position(it.x, it.y) }, "Punto de macro fuera del área")
            check(macro.steps.all { it.waitMs in 0..GameProfile.MAX_MACRO_WAIT_MS }, "Espera de macro fuera del intervalo 0–5000 ms")
            check(macro.steps.sumOf { MACRO_TAP_MS.toLong() + it.waitMs } <= GameProfile.MAX_MACRO_DURATION_MS, "La macro supera los 30 segundos")
        }
        val triggers = actions.map { it.triggerKind to it.code } + macros.map { it.triggerKind to it.code }
        val actionGroups=actions.groupBy { it.triggerKind to it.code }
        check(actionGroups.all { (trigger,group) -> group.size==1 ||
            trigger.first=="key" && group.all { it.behavior==ActionSpec.TAP } },
            "Sólo las acciones de pulsación normal pueden compartir una tecla")
        val macroTriggers=macros.map { it.triggerKind to it.code }
        check(macroTriggers.distinct().size==macroTriggers.size && macroTriggers.none { it in actionGroups },
            "Una macro comparte entrada con otra acción o macro")
        val configuredDirections = listOf(j.upKey, j.downKey, j.leftKey, j.rightKey)
        check(configuredDirections.all(::keyValid), "Teclas de dirección no válidas")
        val directions = if (j.enabled) configuredDirections else emptyList()
        check(!j.enabled || directions.distinct().size == 4, "Las cuatro direcciones necesitan teclas distintas")
        check(camera.suspendKey == 0 || keyValid(camera.suspendKey), "Tecla de suspensión no válida")
        check(CameraSpec.isSupportedSuspendMouseButton(camera.suspendMouseButton), "Botón de suspensión no válido")
        check(camera.suspendKey==0 || camera.suspendMouseButton==0, "Asigna una tecla o un botón de suspensión, no ambos")
        if(camera.enabled && camera.suspendMouseButton!=0) {
            check(triggers.none { it.first=="mouse" && it.second==camera.suspendMouseButton },
                "Una asignación coincide con el botón de suspensión")
        }
        val configured = listOf(profile.shortcuts.stop, profile.shortcuts.edit) +
            if (camera.enabled) listOf(profile.shortcuts.cameraToggle, camera.suspendKey) else emptyList()
        val shortcuts = configured.filter { it != 0 }
        check(keyValid(profile.shortcuts.cameraToggle) && configured.all { it == 0 || keyValid(it) } && shortcuts.distinct().size == shortcuts.size, "Los atajos de sesión deben ser distintos")
        val engineKeys = directions + actions.filter { it.triggerKind == "key" }.map { it.code } + macros.filter { it.triggerKind == "key" }.map { it.code }
        check((actions.filter { it.triggerKind=="key" }.map { it.code } + macros.filter { it.triggerKind=="key" }.map { it.code }).none { it in directions },
            "La acción coincide con una dirección")
        check(engineKeys.none { it in shortcuts }, "Una asignación coincide con un atajo de sesión")
        check(profile.switchKey == 0 || profile.switchKey !in engineKeys + shortcuts, "La tecla de perfil ya está asignada al mapeo")
        return errors
    }

    fun requireValid(profile: GameProfile): GameProfile {
        val errors = validate(profile)
        require(errors.isEmpty()) { errors.joinToString("; ") }
        return profile
    }
}
