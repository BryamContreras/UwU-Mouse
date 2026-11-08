package dev.mobimapper.mobimapper.profile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ActionProfileTest {
    private fun profile() = GameProfile.create("com.example.game", "Sesión", 2400, 1080, 1).let {
        it.copy(calibration=it.calibration.copy(confirmed=true))
    }
    private fun rejects(block:()->Unit) {
        try { block();fail("Expected invalid configuration") } catch(_:IllegalArgumentException) { }
    }
    private class Storage : ProfileStorage {
        var text:String?=null
        var writes=0
        override fun read()=text
        override fun write(document:String) { text=document;writes++ }
    }

    @Test fun schemaSixMigrationIgnoresUnversionedBehaviorAndEnableFieldsWithoutWriting() {
        val original=profile().let { it.copy(camera=it.camera.copy(precisionAim=true,suspendKey=57),
            additionalActions=listOf(ActionSpec(.7f,.6f,"mouse",2)),
            macros=listOf(MacroSpec("one","Secuencia",.5f,.5f,code=39,steps=listOf(MacroStep(.4f,.3f,0))))) }
        val row=ProfileCodec.toJson(original).put("schemaVersion",6).apply {
            getJSONObject("joystick").put("enabled",false)
            getJSONObject("camera").put("enabled",false)
            getJSONObject("action").put("enabled",false).put("behavior","repeat").put("repeatIntervalMs",50)
            getJSONArray("additionalActions").getJSONObject(0).put("enabled",false).put("behavior","cursorToggle").put("repeatIntervalMs",5000)
        }
        val storage=Storage().apply { text=JSONObject().put("schemaVersion",6).put("profiles",JSONArray().put(row))
            .put("selectedProfileId",original.id).toString() }
        val bytes=storage.text
        val repository=ProfileRepository(storage)
        assertEquals(original,repository.selected());assertEquals(0,storage.writes);assertEquals(bytes,storage.text)
        repository.save(requireNotNull(repository.selected()))
        assertEquals(GameProfile.SCHEMA_VERSION,JSONObject(storage.text!!).getInt("schemaVersion"))
        assertEquals(original,ProfileRepository(storage).selected());assertEquals(1,storage.writes)
    }

    @Test fun repeatAndCursorActionsRoundTripAndCompileWithoutTapBindingsOrDisabledReservations() {
        val original=profile().let { it.copy(calibration=it.calibration.copy(area=GameArea(100f,50f,2300f,1000f)),
            joystick=it.joystick.copy(enabled=false),camera=it.camera.copy(enabled=false),
            action=it.action.copy(enabled=false,code=0),
            additionalActions=listOf(ActionSpec(.25f,.75f,code=51,behavior=ActionSpec.REPEAT,repeatIntervalMs=50),
                ActionSpec(.75f,.25f,code=138,behavior=ActionSpec.CURSOR_TOGGLE))) }
        val store=Storage();ProfileRepository(store).save(original)
        val restored=requireNotNull(ProfileRepository(store).selected())
        assertEquals(original,restored);assertEquals(2,restored.actions.size)
        val prepared=ProfileCompiler.prepare(restored,2400,1080,1)
        assertTrue(prepared.bindings.touches.isEmpty());assertTrue(prepared.macros.isEmpty())
        assertFalse(prepared.bindings.joystickEnabled);assertFalse(prepared.bindings.cameraMappingEnabled)
        assertFalse(prepared.bindings.continuousCamera)
        assertEquals(setOf(51,138),prepared.bindings.commandKeys)
        assertTrue(prepared.bindings.handlesKey(51));assertFalse(prepared.bindings.handlesKey(47))
        val repeat=prepared.repeats.single()
        assertEquals("repeat:0",repeat.owner);assertEquals(51,repeat.code);assertEquals(50,repeat.intervalMs)
        assertEquals(650f,repeat.x,.001f);assertEquals(762.5f,repeat.y,.001f)
        val cursor=prepared.cursorActions.single()
        assertEquals("cursor:1",cursor.owner);assertEquals(138,cursor.code)
        assertEquals(1750f,cursor.x,.001f);assertEquals(287.5f,cursor.y,.001f)
        rejects { ProfileValidator.requireValid(original.copy(joystick=original.joystick.copy(enabled=true))) }
        rejects { ProfileValidator.requireValid(original.copy(camera=original.camera.copy(enabled=true))) }
        rejects { ProfileValidator.requireValid(original.copy(shortcuts=original.shortcuts.copy(stop=51))) }
    }

    @Test fun emptyMappingsAndTwentyFourEnabledActionsShareTheSameBudgetWithMacros() {
        val empty=profile().let { it.copy(action=it.action.copy(enabled=false,code=0),
            joystick=it.joystick.copy(enabled=false),camera=it.camera.copy(enabled=false)) }
        val prepared=ProfileCompiler.prepare(empty,2400,1080,1)
        assertEquals(0,empty.actions.size);assertTrue(prepared.bindings.touches.isEmpty())
        assertTrue(prepared.bindings.commandKeys.isEmpty());assertFalse(prepared.bindings.handlesKey(51))
        assertEquals(1200f,prepared.geometry.actionX,0f);assertEquals(540f,prepared.geometry.actionY,0f)
        val full=empty.copy(additionalActions=(100..123).map { ActionSpec(.5f,.5f,code=it,
            behavior=if(it%2==0) ActionSpec.REPEAT else ActionSpec.CURSOR_TOGGLE) })
        assertEquals(24,ProfileCompiler.prepare(full,2400,1080,1).bindings.commandKeys.size)
        rejects { ProfileValidator.requireValid(full.copy(additionalActions=full.additionalActions+ActionSpec(.5f,.5f,code=124))) }
        rejects { ProfileValidator.requireValid(full.copy(macros=listOf(MacroSpec("x","Macro",.5f,.5f,code=200,steps=listOf(MacroStep(.5f,.5f)))))) }
        rejects { ProfileValidator.requireValid(empty.copy(additionalActions=listOf(ActionSpec(.5f,.5f,code=35,enabled=false)))) }
        assertEquals(empty,ProfileCodec.decode(ProfileCodec.encode(empty)))
    }

    @Test fun schemaSevenMetadataIsStrictAndUnsupportedBehaviorsOrIntervalsCannotExecute() {
        val p=profile()
        for(section in listOf("joystick","camera","action")) {
            val missing=ProfileCodec.toJson(p).apply { getJSONObject(section).remove("enabled") }
            rejects { ProfileCodec.decode(missing.toString()) }
            for(value in listOf<Any>(JSONObject.NULL,0,"true")) {
                val invalid=ProfileCodec.toJson(p).apply { getJSONObject(section).put("enabled",value) }
                rejects { ProfileCodec.decode(invalid.toString()) }
            }
        }
        for(field in listOf("behavior","repeatIntervalMs")) {
            rejects { ProfileCodec.decode(ProfileCodec.toJson(p).apply { getJSONObject("action").remove(field) }.toString()) }
        }
        for(value in listOf<Any>(JSONObject.NULL,"100",100.5,true,49,5001)) {
            rejects { ProfileCodec.decode(ProfileCodec.toJson(p).apply { getJSONObject("action").put("repeatIntervalMs",value) }.toString()) }
        }
        for(behavior in listOf("", "Repeat", "hold", "cursor")) {
            rejects { ProfileValidator.requireValid(p.copy(action=p.action.copy(behavior=behavior))) }
        }
        for(interval in listOf(50,100,5000)) {
            val valid=p.copy(action=p.action.copy(behavior=ActionSpec.REPEAT,repeatIntervalMs=interval))
            assertEquals(interval,ProfileCompiler.prepare(valid,2400,1080,1).repeats.single().intervalMs)
        }
    }
}
