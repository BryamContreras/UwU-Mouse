package dev.uwumouse.app.profile

import dev.uwumouse.app.editor.EditorDraft
import dev.uwumouse.app.session.ProfileInputPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MouseCameraSuspendProfileTest {
    private fun profile():GameProfile=GameProfile.create("com.example.game","Mouse hold",2400,1080,1).let {
        it.copy(calibration=it.calibration.copy(confirmed=true))
    }
    private fun prepare(profile:GameProfile)=ProfileCompiler.prepare(profile,2400,1080,1)

    @Test fun legacyKey125RemainsAKeyAndLoadingDoesNotRewriteTheStore() {
        val p=profile().let { it.copy(camera=it.camera.copy(suspendKey=125)) }
        val row=ProfileCodec.toJson(p).apply { getJSONObject("camera").remove("suspendMouseButton") }
        val text=JSONObject().put("schemaVersion",9).put("profiles",JSONArray().put(row))
            .put("selectedProfileId",p.id).toString()
        var writes=0
        val repository=ProfileRepository(object:ProfileStorage {
            override fun read()=text
            override fun write(document:String) { writes++ }
        })
        val restored=requireNotNull(repository.selected())
        assertEquals(p,restored)
        assertEquals(125,restored.camera.suspendKey)
        assertEquals(0,restored.camera.suspendMouseButton)
        assertEquals(0,writes)
        assertFalse(ProfileInputPolicy.consumesButton(prepare(restored),16,false,false))
        assertTrue(ProfileInputPolicy.consumesKey(prepare(restored),125,false,false))
    }

    @Test fun allowedMouseMasksRoundTripAndCompileWithoutCreatingTouchBindings() {
        for(button in listOf(0,1,2,4,8,16)) {
            val p=profile().let { it.copy(camera=it.camera.copy(suspendMouseButton=button)) }
            assertEquals(9,p.schemaVersion)
            assertEquals(p,ProfileCodec.decode(ProfileCodec.encode(p)))
            val prepared=prepare(p)
            assertEquals(button,prepared.profile.camera.suspendMouseButton)
            if(button!=0) assertFalse(prepared.bindings.handlesButton(button))
            assertEquals(p.actions.size,prepared.bindings.touches.size)
        }
    }

    @Test fun malformedMasksAndSimultaneousKeyAndMouseAssignmentsAreRejected() {
        for(button in listOf(-1,3,5,15,17,32,125)) {
            val p=profile().let { it.copy(camera=it.camera.copy(suspendMouseButton=button)) }
            assertTrue(ProfileValidator.validate(p).isNotEmpty())
            assertThrows(IllegalArgumentException::class.java) { prepare(p) }
        }
        for(enabled in listOf(false,true)) {
            val p=profile().let { it.copy(camera=it.camera.copy(enabled=enabled,suspendKey=125,suspendMouseButton=16)) }
            assertThrows(IllegalArgumentException::class.java) { ProfileValidator.requireValid(p) }
        }
    }

    @Test fun holdMouseButtonCannotAlsoOwnTapRepeatCursorCommandOrMacro() {
        val p=profile().let { it.copy(camera=it.camera.copy(suspendMouseButton=8)) }
        for(behavior in listOf(ActionSpec.TAP,ActionSpec.REPEAT,ActionSpec.CURSOR_TOGGLE)) {
            val conflict=p.copy(additionalActions=listOf(ActionSpec(.6f,.5f,"mouse",8,behavior=behavior)))
            assertThrows(IllegalArgumentException::class.java) { prepare(conflict) }
        }
        val macro=MacroSpec("hold-conflict","Sequence",.6f,.5f,"mouse",8,steps=listOf(MacroStep(.5f,.5f)))
        assertThrows(IllegalArgumentException::class.java) { prepare(p.copy(macros=listOf(macro))) }
        assertTrue(ProfileValidator.validate(p.copy(camera=p.camera.copy(enabled=false),
            additionalActions=listOf(ActionSpec(.6f,.5f,"mouse",8)))).isEmpty())
    }

    @Test fun keyboardAndMouseNumericCodesRemainSeparateNamespaces() {
        val p=profile()
        val mouseHold=p.copy(camera=p.camera.copy(suspendMouseButton=8),action=p.action.copy(code=8))
        assertTrue(ProfileValidator.validate(mouseHold).isEmpty())
        assertTrue(ProfileInputPolicy.consumesButton(prepare(mouseHold),8,true,false))
        val keyHold=p.copy(camera=p.camera.copy(suspendKey=8),action=p.action.copy(triggerKind="mouse",code=8))
        assertTrue(ProfileValidator.validate(keyHold).isEmpty())
        assertTrue(ProfileInputPolicy.consumesKey(prepare(keyHold),8,true,false))
        val profileSwitch=p.copy(camera=p.camera.copy(suspendMouseButton=16),switchKey=16)
        ProfileSwitchKeys.requireValid(listOf(profileSwitch))
    }

    @Test fun mouseHoldRetainsBothEdgesInCameraCursorAndEveryTransition() {
        for(button in listOf(8,16)) {
            val p=profile().let { it.copy(camera=it.camera.copy(suspendMouseButton=button)) }
            val prepared=prepare(p)
            for(cursor in listOf(false,true)) for(transition in listOf(false,true)) {
                assertTrue(ProfileInputPolicy.consumesButton(prepared,button,cursor,transition))
                assertFalse(ProfileInputPolicy.consumesButton(prepared,0,cursor,transition))
                assertFalse(ProfileInputPolicy.consumesButton(prepared,125,cursor,transition))
            }
            val disabled=prepare(p.copy(camera=p.camera.copy(enabled=false)))
            for(cursor in listOf(false,true)) for(transition in listOf(false,true))
                assertFalse(ProfileInputPolicy.consumesButton(disabled,button,cursor,transition))
        }
    }

    @Test fun cursorAndTransitionKeepTheirExistingMouseActionOwnership() {
        val p=profile().let { it.copy(camera=it.camera.copy(suspendMouseButton=8),
            additionalActions=listOf(ActionSpec(.6f,.5f,"mouse",1),
                ActionSpec(.7f,.5f,"mouse",4,behavior=ActionSpec.CURSOR_TOGGLE),
                ActionSpec(.8f,.5f,"mouse",16,behavior=ActionSpec.REPEAT)),
            macros=listOf(MacroSpec("secondary","Sequence",.6f,.4f,"mouse",2,
                steps=listOf(MacroStep(.5f,.5f))))) }
        val prepared=prepare(p)
        for(button in listOf(1,2,16)) {
            assertTrue(ProfileInputPolicy.consumesButton(prepared,button,false,false))
            assertTrue(ProfileInputPolicy.consumesButton(prepared,button,true,true))
            assertFalse(ProfileInputPolicy.consumesButton(prepared,button,true,false))
        }
        for(button in listOf(4,8)) assertTrue(ProfileInputPolicy.consumesButton(prepared,button,true,false))
    }

    @Test fun optionalMouseFieldIsStrictlyTypedAndOldPreSuspendSchemasIgnoreIt() {
        val p=profile()
        for(value in listOf<Any>(JSONObject.NULL,"16",16.5,true)) {
            val json=ProfileCodec.toJson(p).apply { getJSONObject("camera").put("suspendMouseButton",value) }
            assertThrows(IllegalArgumentException::class.java) { ProfileCodec.decode(json.toString()) }
        }
        val legacy=ProfileCodec.toJson(p).put("schemaVersion",5).apply {
            getJSONObject("camera").put("suspendMouseButton",16)
        }
        assertEquals(0,ProfileCodec.decode(legacy.toString()).camera.suspendMouseButton)
        val schemaSix=ProfileCodec.toJson(p).put("schemaVersion",6).apply {
            getJSONObject("camera").put("suspendMouseButton",16)
        }
        assertEquals(16,ProfileCodec.decode(schemaSix.toString()).camera.suspendMouseButton)
    }

    @Test fun assigningEitherKindInDraftClearsTheOtherAndCancelPreservesOriginal() {
        val p=profile().let { it.copy(camera=it.camera.copy(suspendKey=125)) }
        val draft=EditorDraft(p,2400,1080,1)
        draft.bindCameraSuspendMouse(16)
        assertEquals(0,draft.value.camera.suspendKey)
        assertEquals(16,draft.save().camera.suspendMouseButton)
        draft.bindKey("cameraSuspend",140)
        assertEquals(140,draft.save().camera.suspendKey)
        assertEquals(0,draft.value.camera.suspendMouseButton)
        draft.bindCameraSuspendMouse(0)
        assertEquals(0,draft.save().camera.suspendKey)
        assertEquals(0,draft.value.camera.suspendMouseButton)
        val before=draft.value
        assertThrows(IllegalArgumentException::class.java) { draft.bindCameraSuspendMouse(3) }
        assertEquals(before,draft.value)
        assertSame(p,draft.cancel())
        assertEquals(125,p.camera.suspendKey)
        assertEquals(0,p.camera.suspendMouseButton)
    }
}
