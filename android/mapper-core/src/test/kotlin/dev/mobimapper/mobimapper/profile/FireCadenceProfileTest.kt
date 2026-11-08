package dev.mobimapper.mobimapper.profile

import dev.mobimapper.mobimapper.editor.EditorDraft
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FireCadenceProfileTest {
    private fun profile():GameProfile = GameProfile.create("com.example.game","Cadencia",1000,600,0).let {
        it.copy(calibration=it.calibration.copy(area=GameArea(100f,50f,900f,550f),confirmed=true),
            action=ActionSpec(.8f,.8f,"mouse",1))
    }
    private fun rejects(block:()->Unit) {
        try { block();fail("Invalid cadence must be rejected") } catch(_:IllegalArgumentException) { }
    }
    private fun prepared(profile:GameProfile) = ProfileCompiler.prepare(profile,1000,600,0)

    @Test fun absentCadenceFieldsRemainDisabledAcrossEverySupportedSchema() {
        for(schema in 1..GameProfile.SCHEMA_VERSION) {
            val wire=ProfileCodec.toJson(profile()).put("schemaVersion",schema)
            wire.getJSONObject("action").apply { remove("cadenceEnabled");remove("cadenceYPercent") }
            val restored=ProfileCodec.decode(wire.toString())
            assertFalse(restored.action.cadenceEnabled);assertEquals(0,restored.action.cadenceYPercent)
            assertNull(prepared(restored).fireCadence)
        }
    }

    @Test fun signedCadenceRoundTripsOnEitherThePrimaryOrAnAdditionalM1() {
        for(percent in listOf(-100,-37,0,42,100)) for(additional in listOf(false,true)) {
            val fire=profile().action.copy(cadenceEnabled=true,cadenceYPercent=percent)
            val p=if(additional) profile().copy(action=ActionSpec(.4f,.5f,code=62),additionalActions=listOf(fire))
                else profile().copy(action=fire)
            assertEquals(p,ProfileCodec.decode(ProfileCodec.encode(p)))
            val restored=ProfileCodec.decodeDocument(ProfileCodec.encodeDocument(ProfileDocument(listOf(p),p.id)))
            assertEquals(p,restored.profiles.single());assertEquals(p.id,restored.selectedProfileId)
        }
    }

    @Test fun malformedOptionalFieldsCannotBeIgnoredEvenOnOlderSchemasOrAdditionalActions() {
        val p=profile().copy(action=ActionSpec(.4f,.5f,code=62),additionalActions=listOf(profile().action))
        for(schema in 1..GameProfile.SCHEMA_VERSION) {
            for(value in listOf<Any>(JSONObject.NULL,"false",0,JSONObject())) {
                val wire=ProfileCodec.toJson(profile()).put("schemaVersion",schema)
                wire.getJSONObject("action").put("cadenceEnabled",value)
                rejects { ProfileCodec.decode(wire.toString()) }
            }
            for(value in listOf<Any>(JSONObject.NULL,"20",true,1.5,101,-101,Long.MAX_VALUE)) {
                val wire=ProfileCodec.toJson(profile()).put("schemaVersion",schema)
                wire.getJSONObject("action").put("cadenceYPercent",value)
                rejects { ProfileCodec.decode(wire.toString()) }
            }
        }
        for(field in listOf("cadenceEnabled","cadenceYPercent")) {
            val wire=ProfileCodec.toJson(p)
            wire.getJSONArray("additionalActions").getJSONObject(0).put(field,"1")
            rejects { ProfileCodec.decode(wire.toString()) }
        }
    }

    @Test fun onlyEnabledM1TapCanCarryCadenceSettings() {
        val fire=profile().action.copy(cadenceEnabled=true,cadenceYPercent=20)
        val ineligible=listOf(fire.copy(triggerKind="key",code=62),fire.copy(code=2),
            fire.copy(behavior=ActionSpec.REPEAT),fire.copy(behavior=ActionSpec.CURSOR_TOGGLE),
            fire.copy(enabled=false,code=0))
        for(action in ineligible) {
            assertFalse(action.isM1Tap)
            rejects { ProfileValidator.requireValid(profile().copy(action=action)) }
            rejects { ProfileValidator.requireValid(profile().copy(action=action.copy(cadenceEnabled=false))) }
            rejects { ProfileValidator.requireValid(profile().copy(action=action.copy(cadenceYPercent=0))) }
        }
        for(percent in listOf(-101,101,Int.MIN_VALUE,Int.MAX_VALUE)) {
            rejects { ProfileValidator.requireValid(profile().copy(action=fire.copy(cadenceYPercent=percent))) }
        }
        assertTrue(fire.isM1Tap)
        assertEquals(fire.copy(cadenceEnabled=false),
            ProfileValidator.requireValid(profile().copy(action=fire.copy(cadenceEnabled=false))).action)
    }

    @Test fun compilerUsesCalibratedAreaHeightAndDirectionWithoutChangingTheHeldFireTouch() {
        val normal=prepared(profile())
        for(percent in listOf(-100,-50,50,100)) {
            val p=profile().copy(action=profile().action.copy(cadenceEnabled=true,cadenceYPercent=percent))
            val compiled=prepared(p)
            val cadence=requireNotNull(compiled.fireCadence)
            assertEquals("mouse",cadence.triggerKind);assertEquals(1,cadence.code)
            assertEquals(500L,cadence.delayMillis)
            assertEquals(-percent.toFloat(),cadence.verticalPixelsPerSecond,.0001f)
            assertEquals(normal.geometry,compiled.geometry);assertEquals(normal.bindings,compiled.bindings)
            assertTrue(compiled.repeats.isEmpty());assertTrue(compiled.cursorActions.isEmpty())
            assertEquals(1,compiled.bindings.touches.single().button)
            assertFalse(compiled.bindings.touches.single().mouseAim)
        }
        val p=profile().copy(calibration=profile().calibration.copy(area=GameArea(0f,0f,1000f,600f)),
            action=profile().action.copy(cadenceEnabled=true,cadenceYPercent=100))
        assertEquals(-120f,requireNotNull(prepared(p).fireCadence).verticalPixelsPerSecond,.0001f)
    }

    @Test fun centeredOrDisabledCadenceHasNoPreparedRuntimeButPreservesTheUserSetting() {
        for(action in listOf(profile().action.copy(cadenceEnabled=true,cadenceYPercent=0),
            profile().action.copy(cadenceEnabled=false,cadenceYPercent=75))) {
            val p=profile().copy(action=action)
            assertNull(prepared(p).fireCadence)
            assertEquals(p,ProfileCodec.decode(ProfileCodec.encode(p)))
        }
    }

    @Test fun disabledCameraPreservesTheFireOptionWithoutPreparingCameraMovement() {
        val p=profile().let { it.copy(camera=it.camera.copy(enabled=false),
            action=it.action.copy(cadenceEnabled=true,cadenceYPercent=100)) }
        assertNull(prepared(p).fireCadence)
        assertEquals(p,ProfileCodec.decode(ProfileCodec.encode(p)))
        assertTrue(prepared(p).bindings.touches.single().button==1)
        assertFalse(prepared(p).bindings.cameraMappingEnabled)
    }

    @Test fun draftSettersPreserveIntensityWhileOffAndRejectInvalidChangesTransactionally() {
        val original=profile();val draft=EditorDraft(original,1000,600,0)
        draft.setActionCadenceYPercent(-35);draft.setActionCadenceEnabled(true)
        val saved=draft.save()
        assertTrue(saved.action.cadenceEnabled);assertEquals(-35,saved.action.cadenceYPercent)
        draft.setActionCadenceEnabled(false)
        assertEquals(-35,draft.save().action.cadenceYPercent)
        val before=draft.value
        rejects { draft.setActionCadenceYPercent(101) }
        assertEquals(before,draft.value);assertSame(original,draft.cancel())
    }

    @Test fun retargetingBehaviorOrDeletingM1ClearsCadenceWithoutAffectingOtherControls() {
        val original=profile().copy(action=profile().action.copy(cadenceEnabled=true,cadenceYPercent=60))
        val edits:List<(EditorDraft)->Unit> = listOf(
            { it.bindKey("action",62) },
            { it.replaceAction(it.action().copy(code=2)) },
            { it.replaceAction(it.action().copy(behavior=ActionSpec.REPEAT)) },
            { it.replaceAction(it.action().copy(behavior=ActionSpec.CURSOR_TOGGLE)) },
            { it.removeAction() },
        )
        for(edit in edits) {
            val draft=EditorDraft(original,1000,600,0);edit(draft)
            assertFalse(draft.action().cadenceEnabled);assertEquals(0,draft.action().cadenceYPercent)
            assertEquals(original.camera,draft.value.camera);assertEquals(original.joystick,draft.value.joystick)
            assertSame(original,draft.cancel())
            rejects { draft.setActionCadenceEnabled(true) }
            rejects { draft.setActionCadenceYPercent(10) }
            ProfileValidator.requireValid(draft.save())
        }
    }

    @Test fun additionalActionEditsAndRemovalKeepTheOtherActionConfiguration() {
        val original=profile().copy(action=ActionSpec(.3f,.5f,code=62),
            additionalActions=listOf(profile().action))
        val draft=EditorDraft(original,1000,600,0)
        draft.selectAction(1);draft.setActionCadenceYPercent(80);draft.setActionCadenceEnabled(true)
        assertEquals(original.action,draft.value.action)
        assertTrue(draft.save().additionalActions.single().cadenceEnabled)
        draft.bindKey("action",35)
        assertEquals(0,draft.action().cadenceYPercent);assertFalse(draft.action().cadenceEnabled)
        draft.removeAction()
        assertEquals(original.action,draft.value.action);assertTrue(draft.save().additionalActions.isEmpty())
    }
}
