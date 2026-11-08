package dev.mobimapper.mobimapper.editor

import dev.mobimapper.mobimapper.profile.*
import org.junit.Assert.*
import org.junit.Test

class LegacyCameraDraftTest {
    private fun profile(): GameProfile = GameProfile.create("com.example.game", "Legacy", 1000, 600, 0).let {
        it.copy(
            calibration = it.calibration.copy(confirmed = true),
            camera = it.camera.copy(mode = CameraSpec.BOUNDED, sensitivityX = 1.3f, sensitivityY = .8f,
                updateRateHz = 500, precisionAim = true, markerSizeDp = 80f, suspendMouseButton = 16),
            action = it.action.copy(triggerKind = "mouse", code = 1, cadenceEnabled = true, cadenceYPercent = -35),
            additionalActions = listOf(ActionSpec(.5f, .6f, "key", 35, behavior = ActionSpec.REPEAT, repeatIntervalMs = 175)),
            macros = listOf(MacroSpec("macro", "Sequence", .4f, .4f, code = 36,
                steps = listOf(MacroStep(.3f, .3f, 250), MacroStep(.4f, .4f, 450)))),
            switchKey = 37,
        )
    }

    @Test fun openingAndCancelingLegacyDraftPreservesStoredModeAndEveryUnrelatedField() {
        val original = profile()
        val encoded = ProfileCodec.encode(original)
        val decoded = ProfileCodec.decode(encoded)
        val draft = EditorDraft(decoded, 1000, 600, 0)
        val expected = decoded.copy(camera = decoded.camera.copy(mode = CameraSpec.SMOOTH))
        assertEquals(expected.copy(calibration = expected.calibration.copy(confirmed = false)), draft.value)
        assertSame(decoded, draft.cancel())
        assertEquals(encoded, ProfileCodec.encode(decoded))
        assertEquals(original, decoded)
        assertFalse(ProfileCompiler.prepare(decoded, 1000, 600, 0).bindings.continuousCamera)
    }

    @Test fun savingLegacyDraftChangesOnlyModeAndPreservesBothSuspendKindsRatesAndCadence() {
        val base = profile()
        for (camera in listOf(base.camera, base.camera.copy(suspendMouseButton = 0, suspendKey = 125, updateRateHz = 120))) {
            val original = base.copy(camera = camera)
            val draft = EditorDraft(original, 1000, 600, 0)
            val saved = draft.save()
            assertEquals(original.copy(camera = camera.copy(mode = CameraSpec.SMOOTH)), saved)
            assertEquals(saved, ProfileCodec.decode(ProfileCodec.encode(saved)))
            assertTrue(ProfileCompiler.prepare(saved, 1000, 600, 0).bindings.retireBoundedCameraOnIdle)
            assertSame(original, draft.cancel())
        }
    }

    @Test fun openingCurrentModesRetainsTheirSelectionIncludingDisabledCamera() {
        val base = profile()
        for (mode in listOf(CameraSpec.SMOOTH, CameraSpec.CONTINUOUS)) {
            val original = base.copy(camera = base.camera.copy(mode = mode, enabled = false))
            assertEquals(original, EditorDraft(original, 1000, 600, 0).save())
        }
    }
}
