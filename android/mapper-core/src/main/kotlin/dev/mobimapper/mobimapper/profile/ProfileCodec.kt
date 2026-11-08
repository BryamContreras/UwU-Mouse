package dev.mobimapper.mobimapper.profile

import dev.mobimapper.mobimapper.serialization.putValue

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64

data class ProfileDocument(val profiles: List<GameProfile>, val selectedProfileId: String?)

/** Versioned data only. No class names, commands or executable expressions are accepted. */
object ProfileCodec {
    const val MAX_DOCUMENT_CHARS = 1_048_576
    private const val MAX_PROFILE_CHARS = 65_536
    const val MAX_PROFILES = 128
    const val MAX_IMPORT_BASE64_CHARS = ((MAX_DOCUMENT_CHARS + 2) / 3) * 4

    /** Bounded shell transport only: strict UTF-8 and one complete JSON object, never a file path. */
    fun decodeImportPayload(encoded: String): ProfileDocument {
        require(encoded.length in 4..MAX_IMPORT_BASE64_CHARS && encoded.length % 4 == 0) { "Documento codificado demasiado grande o no válido" }
        val bytes = try { Base64.getDecoder().decode(encoded) }
            catch (failure: IllegalArgumentException) { throw IllegalArgumentException("Base64 de perfiles no válido", failure) }
        require(bytes.size <= MAX_DOCUMENT_CHARS && Base64.getEncoder().encodeToString(bytes) == encoded) { "Documento codificado demasiado grande o no canónico" }
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        } catch (failure: CharacterCodingException) { throw IllegalArgumentException("Documento de perfiles no es UTF-8 válido", failure) }
        jsonRead {
            val tokens = JSONTokener(text)
            require(tokens.nextValue() is JSONObject && tokens.nextClean() == '\u0000') { "Se necesita un único objeto JSON completo" }
        }
        return decodeDocument(text)
    }

    fun encode(profile: GameProfile): String = toJson(ProfileValidator.requireValid(profile)).toString()

    fun decode(json: String): GameProfile {
        require(json.length <= MAX_PROFILE_CHARS) { "Perfil demasiado grande" }
        return jsonRead { fromJson(JSONObject(json)) }
    }

    fun encodeDocument(document: ProfileDocument): String {
        require(document.profiles.size <= MAX_PROFILES) { "Demasiados perfiles" }
        require(document.profiles.map { it.id }.distinct().size == document.profiles.size) { "Perfiles duplicados" }
        ProfileSwitchKeys.requireValid(document.profiles)
        require(document.selectedProfileId == null || document.profiles.any { it.id == document.selectedProfileId }) { "Perfil seleccionado inexistente" }
        val rows = JSONArray()
        document.profiles.forEach { rows.put(toJson(ProfileValidator.requireValid(it))) }
        return JSONObject().putValue("schemaVersion", GameProfile.SCHEMA_VERSION).putValue("profiles", rows)
            .putValue("selectedProfileId", document.selectedProfileId ?: JSONObject.NULL).toString()
            .also {
                require(it.length <= MAX_DOCUMENT_CHARS && it.toByteArray(Charsets.UTF_8).size <= MAX_DOCUMENT_CHARS) {
                    "Almacén de perfiles demasiado grande"
                }
            }
    }

    fun decodeDocument(json: String): ProfileDocument {
        require(json.length <= MAX_DOCUMENT_CHARS) { "Almacén de perfiles demasiado grande" }
        return jsonRead {
            val root = JSONObject(json)
            require(int(root, "schemaVersion") in 1..GameProfile.SCHEMA_VERSION) { "Versión del almacén no compatible" }
            val rows = root.getJSONArray("profiles")
            require(rows.length() <= MAX_PROFILES) { "Demasiados perfiles" }
            val profiles = (0 until rows.length()).map { fromJson(rows.getJSONObject(it)) }
            require(profiles.map { it.id }.distinct().size == profiles.size) { "Perfiles duplicados" }
            ProfileSwitchKeys.requireValid(profiles)
            val selected = if (root.isNull("selectedProfileId")) null else string(root, "selectedProfileId")
            require(selected == null || profiles.any { it.id == selected }) { "Perfil seleccionado inexistente" }
            ProfileDocument(profiles, selected)
        }
    }

    fun toJson(profile: GameProfile): JSONObject {
        ProfileValidator.requireValid(profile)
        val c = profile.calibration; val a = c.area; val j = profile.joystick
        val camera = profile.camera; val action = profile.action; val shortcuts = profile.shortcuts
        return JSONObject().putValue("schemaVersion", profile.schemaVersion).putValue("id", profile.id)
            .putValue("name", profile.name).putValue("packageName", profile.packageName).putValue("orientation", profile.orientation).putValue("switchKey",profile.switchKey)
            .putValue("calibration", JSONObject().putValue("screenWidth", c.screenWidth).putValue("screenHeight", c.screenHeight)
                .putValue("rotation", c.rotation).putValue("confirmed", c.confirmed)
                .putValue("displayScope",c.displayScope)
                .putValue("windowFrame",c.windowFrame?.let(::areaToJson) ?: JSONObject.NULL)
                .putValue("area", JSONObject().putValue("left", a.left).putValue("top", a.top).putValue("right", a.right).putValue("bottom", a.bottom)))
            .putValue("joystick", JSONObject().putValue("x", j.x).putValue("y", j.y).putValue("radius", j.radius)
                .putValue("upKey", j.upKey).putValue("downKey", j.downKey).putValue("leftKey", j.leftKey).putValue("rightKey", j.rightKey).putValue("enabled", j.enabled))
            .putValue("camera", JSONObject().putValue("x", camera.x).putValue("y", camera.y).putValue("width", camera.width).putValue("height", camera.height)
                .putValue("sensitivityX", camera.sensitivityX).putValue("sensitivityY", camera.sensitivityY).putValue("mode", camera.mode)
                .putValue("updateRateHz",camera.updateRateHz).putValue("markerSizeDp",camera.markerSizeDp)
                .putValue("precisionAim",camera.precisionAim).putValue("suspendKey",camera.suspendKey).putValue("enabled",camera.enabled)
                .putValue("suspendMouseButton",camera.suspendMouseButton))
            .putValue("action", actionToJson(action))
            .putValue("additionalActions", JSONArray().apply { profile.additionalActions.forEach { a -> put(actionToJson(a)) } })
            .putValue("macros", JSONArray().apply { profile.macros.forEach { macro -> put(macroToJson(macro)) } })
            .putValue("shortcuts", JSONObject().putValue("cameraToggle", shortcuts.cameraToggle).putValue("stop", shortcuts.stop).putValue("edit", shortcuts.edit))
    }

    fun fromJson(root: JSONObject): GameProfile {
        val schema=int(root,"schemaVersion")
        require(schema in 1..GameProfile.SCHEMA_VERSION) { "Versión del perfil no compatible" }
        val additional=if(schema==1) emptyList() else root.getJSONArray("additionalActions").let { rows ->
            require(rows.length()<=GameProfile.MAX_ACTIONS) { "Demasiadas acciones" }
            (0 until rows.length()).map { index -> actionFromJson(rows.getJSONObject(index),schema) }
        }
        val macros = if (schema < 6) emptyList() else root.getJSONArray("macros").let { rows ->
            require(rows.length() <= GameProfile.MAX_MACROS) { "Demasiadas macros" }
            (0 until rows.length()).map { index -> macroFromJson(rows.getJSONObject(index)) }
        }
        val c = root.getJSONObject("calibration"); val area = c.getJSONObject("area")
        val j = root.getJSONObject("joystick"); val camera = root.getJSONObject("camera")
        val action = root.getJSONObject("action"); val shortcuts = root.getJSONObject("shortcuts")
        val displayScope=if(schema>=9) string(c,"displayScope") else "phone"
        val windowFrame=if(schema>=9) {
            require(c.has("windowFrame")) { "Falta windowFrame en la calibración" }
            if(c.isNull("windowFrame")) null else areaFromJson(c.getJSONObject("windowFrame"))
        } else null
        val profile = GameProfile(string(root, "id"), string(root, "name"), string(root, "packageName"), GameProfile.SCHEMA_VERSION,
            Calibration(int(c, "screenWidth"), int(c, "screenHeight"), int(c, "rotation"),
                GameArea(float(area, "left"), float(area, "top"), float(area, "right"), float(area, "bottom")), bool(c, "confirmed"),displayScope,windowFrame),
            JoystickSpec(float(j, "x"), float(j, "y"), float(j, "radius"), int(j, "upKey"), int(j, "downKey"), int(j, "leftKey"), int(j, "rightKey"),
                if(schema>=7) bool(j,"enabled") else true),
            CameraSpec(float(camera, "x"), float(camera, "y"), float(camera, "width"), float(camera, "height"),
                float(camera, "sensitivityX"), float(camera, "sensitivityY"),
                if(schema>=3) string(camera,"mode") else CameraSpec.BOUNDED,
                if(schema>=4) int(camera,"updateRateHz") else 0,
                if(schema>=4) float(camera,"markerSizeDp") else MarkerStyle.DEFAULT_DP,
                if(schema>=5) bool(camera,"precisionAim") else false,
                if(schema>=6) int(camera,"suspendKey") else 0,
                if(schema>=7) bool(camera,"enabled") else true,
                if(schema>=6 && camera.has("suspendMouseButton")) int(camera,"suspendMouseButton") else 0),
            actionFromJson(action,schema),
            SessionShortcuts(int(shortcuts, "cameraToggle"), int(shortcuts, "stop"), int(shortcuts, "edit")), additional, macros,
            if(schema>=8) int(root,"switchKey") else 0)
        require(string(root, "orientation") == profile.orientation) { "Orientación y dimensiones del perfil no coinciden" }
        return ProfileValidator.requireValid(profile)
    }

    private fun areaToJson(area:GameArea):JSONObject = JSONObject().putValue("left",area.left).putValue("top",area.top)
        .putValue("right",area.right).putValue("bottom",area.bottom)
    private fun areaFromJson(area:JSONObject):GameArea = GameArea(float(area,"left"),float(area,"top"),float(area,"right"),float(area,"bottom"))

    private fun actionToJson(action: ActionSpec): JSONObject = JSONObject().putValue("x",action.x).putValue("y",action.y)
        .putValue("triggerKind",action.triggerKind).putValue("code",action.code).putValue("markerSizeDp",action.markerSizeDp)
        .putValue("behavior",action.behavior).putValue("repeatIntervalMs",action.repeatIntervalMs).putValue("enabled",action.enabled)
        .putValue("cadenceEnabled",action.cadenceEnabled).putValue("cadenceYPercent",action.cadenceYPercent)

    private fun actionFromJson(action: JSONObject,schema: Int): ActionSpec = ActionSpec(
        float(action,"x"),float(action,"y"),string(action,"triggerKind"),int(action,"code"),
        if(schema>=4) float(action,"markerSizeDp") else MarkerStyle.DEFAULT_DP,
        if(schema>=7) string(action,"behavior") else ActionSpec.TAP,
        if(schema>=7) int(action,"repeatIntervalMs") else 100,
        if(schema>=7) bool(action,"enabled") else true,
        if(action.has("cadenceEnabled")) bool(action,"cadenceEnabled") else false,
        if(action.has("cadenceYPercent")) int(action,"cadenceYPercent") else 0)

    private fun macroToJson(macro: MacroSpec): JSONObject = JSONObject()
        .putValue("id", macro.id).putValue("name", macro.name).putValue("x", macro.x).putValue("y", macro.y)
        .putValue("triggerKind", macro.triggerKind).putValue("code", macro.code).putValue("markerSizeDp", macro.markerSizeDp)
        .putValue("steps", JSONArray().apply { macro.steps.forEach { step ->
            put(JSONObject().putValue("x", step.x).putValue("y", step.y).putValue("waitMs", step.waitMs))
        } })

    private fun macroFromJson(macro: JSONObject): MacroSpec {
        val steps = macro.getJSONArray("steps")
        require(steps.length() in 1..GameProfile.MAX_MACRO_STEPS) { "Cada macro necesita entre 1 y 16 puntos" }
        return MacroSpec(string(macro, "id"), string(macro, "name"), float(macro, "x"), float(macro, "y"),
            string(macro, "triggerKind"), int(macro, "code"), float(macro, "markerSizeDp"),
            (0 until steps.length()).map { index -> steps.getJSONObject(index).let {
                MacroStep(float(it, "x"), float(it, "y"), int(it, "waitMs"))
            } })
    }

    private fun int(value: JSONObject, key: String): Int {
        val number = value.get(key)
        require(number is Number) { "$key necesita un número entero" }
        val d = number.toDouble()
        require(d.isFinite() && d >= Int.MIN_VALUE && d <= Int.MAX_VALUE && d % 1.0 == 0.0) { "$key necesita un número entero válido" }
        return number.toInt()
    }

    private fun float(value: JSONObject, key: String): Float {
        val number = value.get(key)
        require(number is Number && number.toDouble().isFinite() && number.toFloat().isFinite()) { "$key necesita un número finito" }
        return number.toFloat()
    }

    private fun string(value: JSONObject, key: String): String = (value.get(key) as? String)
        ?: throw IllegalArgumentException("$key necesita texto")
    private fun bool(value: JSONObject, key: String): Boolean = (value.get(key) as? Boolean)
        ?: throw IllegalArgumentException("$key necesita verdadero o falso")
    private fun <T> jsonRead(read: () -> T): T = try { read() }
        catch (e: JSONException) { throw IllegalArgumentException("Documento de perfiles no válido: ${e.message}", e) }
}
