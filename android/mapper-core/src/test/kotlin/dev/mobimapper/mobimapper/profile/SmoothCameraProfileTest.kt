package dev.mobimapper.mobimapper.profile

import dev.mobimapper.mobimapper.backend.recovery.RecoveryJournal
import dev.mobimapper.mobimapper.editor.EditorDraft
import dev.mobimapper.mobimapper.engine.MappingEngine
import dev.mobimapper.mobimapper.engine.TouchComposer
import dev.mobimapper.mobimapper.engine.TouchCoordinatePolicy
import org.junit.Assert.*
import org.junit.Test

class SmoothCameraProfileTest {
    private fun profile(mode:String=CameraSpec.CONTINUOUS):GameProfile=
        GameProfile.create("com.example.game","Camera modes",1000,2000,0).let {
            it.copy(calibration=it.calibration.copy(confirmed=true),camera=it.camera.copy(mode=mode))
        }
    private fun prepare(profile:GameProfile)=ProfileCompiler.prepare(profile,1000,2000,0)

    @Test fun threeExplicitModesRoundTripWithTheirOwnRuntimePoliciesAndIdenticalGeometry() {
        val base=profile()
        val expectedGeometry=prepare(base).geometry
        for(mode in listOf(CameraSpec.BOUNDED,CameraSpec.CONTINUOUS,CameraSpec.SMOOTH)) {
            val p=base.copy(camera=base.camera.copy(mode=mode))
            val restored=ProfileCodec.decode(ProfileCodec.encode(p))
            val compiled=prepare(restored)
            assertEquals(p,restored);assertEquals(9,restored.schemaVersion)
            assertEquals(mode!=CameraSpec.BOUNDED,compiled.bindings.continuousCamera)
            assertEquals(mode==CameraSpec.SMOOTH,compiled.bindings.retireBoundedCameraOnIdle)
            assertNull(compiled.bindings.heldFireCameraBounds)
            assertFalse(compiled.bindings.overlapCameraHandoff);assertFalse(compiled.bindings.interlacedCameraHandoff)
            assertEquals(expectedGeometry,compiled.geometry)
            assertEquals(base.actions,compiled.profile.actions)
        }
    }

    @Test fun newDefaultsAndLegacyBoundedOrContinuousProfilesNeverSelectSmoothImplicitly() {
        assertEquals(CameraSpec.CONTINUOUS,GameProfile.create("com.example.other","Default",1000,2000,0).camera.mode)
        for(mode in listOf(CameraSpec.BOUNDED,CameraSpec.CONTINUOUS)) {
            val p=profile(mode)
            val compiled=prepare(ProfileCodec.decode(ProfileCodec.encode(p)))
            assertEquals(mode,compiled.profile.camera.mode)
            assertFalse(compiled.bindings.retireBoundedCameraOnIdle)
        }
        val legacy=ProfileCodec.toJson(profile()).put("schemaVersion",2).apply { getJSONObject("camera").remove("mode") }
        val migrated=ProfileCodec.decode(legacy.toString())
        assertEquals(CameraSpec.BOUNDED,migrated.camera.mode)
        assertFalse(prepare(migrated).bindings.retireBoundedCameraOnIdle)
    }

    @Test fun smoothNeedsNeitherAMouseBindingNorATapAction() {
        val p=profile(CameraSpec.SMOOTH)
        for(candidate in listOf(p,p.copy(action=p.action.copy(behavior=ActionSpec.REPEAT)),
            p.copy(action=p.action.copy(enabled=false,code=0)))) {
            val compiled=prepare(candidate)
            assertTrue(compiled.bindings.continuousCamera)
            assertTrue(compiled.bindings.retireBoundedCameraOnIdle)
            assertFalse(compiled.bindings.handlesButton(1))
        }
    }

    @Test fun primaryTapStaysFixedAndDoesNotEnableTheRejectedHardClampStrategy() {
        val base=profile(CameraSpec.SMOOTH)
        val p=base.copy(additionalActions=listOf(ActionSpec(.85f,.8f,"mouse",1)))
        val compiled=prepare(p)
        assertFalse(compiled.bindings.touches.single { it.button==1 }.mouseAim)
        assertNull(compiled.bindings.fireDragBounds)
        assertNull(compiled.bindings.heldFireCameraBounds)
        assertTrue(compiled.bindings.retireBoundedCameraOnIdle)
    }

    @Test fun disabledSmoothRetainsItsSavedChoiceWithoutArmingAnyCameraPolicy() {
        val base=profile(CameraSpec.SMOOTH)
        val p=base.copy(camera=base.camera.copy(enabled=false))
        val restored=ProfileCodec.decode(ProfileCodec.encode(p))
        val compiled=prepare(restored)
        assertEquals(CameraSpec.SMOOTH,restored.camera.mode)
        assertFalse(compiled.bindings.cameraMappingEnabled)
        assertFalse(compiled.bindings.continuousCamera)
        assertFalse(compiled.bindings.retireBoundedCameraOnIdle)
    }

    @Test fun reloadingSmoothRecreatesIdNineAndRegionalIdleWithoutAdditionalWireFields() {
        val p=profile(CameraSpec.SMOOTH)
        val json=ProfileCodec.encode(p)
        val cameraJson=ProfileCodec.toJson(p).getJSONObject("camera")
        assertFalse(cameraJson.has("retireBoundedCameraOnIdle"));assertFalse(cameraJson.has("heldFireCameraBounds"))
        repeat(2) {
            val compiled=prepare(ProfileCodec.decode(json))
            val journal=RecoveryJournal().apply { check(begin(1,1000,2000,10001,TouchCoordinatePolicy.CONTINUOUS_CAMERA_9)) }
            var now=1000L
            val composer=TouchComposer({++now},{frame -> journal.prepare(1,frame) && journal.acknowledged(1,frame,true) },continuousCamera=true)
            val engine=MappingEngine(composer,compiled.geometry,compiled.bindings)
            assertTrue(engine.setCameraEnabled(true));assertTrue(engine.relativeMouse(100_000f,-100_000f))
            assertEquals(9,composer.snapshot().single().id)
            assertTrue(engine.canRetireIdleCamera)
            assertEquals(0L,engine.cameraRestarts);assertEquals(0L,engine.heldFireCameraClamps)
            assertTrue(engine.retireIdleCamera())
            assertTrue(engine.relativeMouse(3f,-2f))
            assertEquals(compiled.geometry.cameraX+3f,composer.snapshot().single().x,0f)
            assertEquals(compiled.geometry.cameraY-2f,composer.snapshot().single().y,0f)
            assertEquals(9,composer.snapshot().single().id)
            assertTrue(engine.pause());assertEquals(0,journal.snapshot().possibleContacts)
        }
    }

    @Test fun openingLegacyEditorNormalizesOnlyTheDraftAndCancelLeavesTheOldModeUntouched() {
        val original=profile(CameraSpec.BOUNDED)
        val draft=EditorDraft(original,1000,2000,0)
        assertEquals(CameraSpec.SMOOTH,draft.value.camera.mode)
        assertEquals(CameraSpec.SMOOTH,draft.save().camera.mode)
        assertSame(original,draft.cancel())
        assertEquals(CameraSpec.BOUNDED,original.camera.mode)
    }
}
