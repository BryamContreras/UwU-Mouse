package dev.mobimapper.mobimapper.session

import dev.mobimapper.mobimapper.profile.ActionSpec
import dev.mobimapper.mobimapper.profile.GameProfile
import dev.mobimapper.mobimapper.profile.PreparedProfile
import dev.mobimapper.mobimapper.profile.ProfileCompiler
import dev.mobimapper.mobimapper.profile.SessionShortcuts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileInputPolicyTest {
    private fun profile(cameraEnabled: Boolean = true): PreparedProfile {
        val base = GameProfile.create("dev.example.game", "Controls", 1080, 1920, 0)
        return ProfileCompiler.prepare(base.copy(
            calibration = base.calibration.copy(confirmed = true),
            camera = base.camera.copy(enabled = cameraEnabled, suspendKey = 140),
            shortcuts = SessionShortcuts(cameraToggle = 138, stop = 139, edit = 141),
            additionalActions = listOf(ActionSpec(.5f, .5f, code = 61,
                behavior = ActionSpec.CURSOR_TOGGLE)),
        ), 1080, 1920, 0)
    }

    @Test fun editAndStopStayOwnedInCameraCursorAndDuringHandoff() {
        val profile = profile()
        for (cursor in listOf(false, true)) for (pending in listOf(false, true)) {
            assertTrue(ProfileInputPolicy.consumesKey(profile, 141, cursor, pending))
            assertTrue(ProfileInputPolicy.consumesKey(profile, 139, cursor, pending))
        }
    }

    @Test fun cursorKeepsModeCommandsButPassesOrdinaryGameplayKeys() {
        val profile = profile()
        for (command in listOf(138, 140, 61)) {
            assertTrue(ProfileInputPolicy.consumesKey(profile, command, true, false))
        }
        for (gameKey in listOf(profile.profile.joystick.upKey, profile.profile.action.code)) {
            assertFalse(ProfileInputPolicy.consumesKey(profile, gameKey, true, false))
            assertTrue(ProfileInputPolicy.consumesKey(profile, gameKey, false, false))
        }
        assertFalse(ProfileInputPolicy.consumesKey(profile, 44, true, false))
    }

    @Test fun disabledCameraDoesNotOwnItsToggleOrHoldKey() {
        val profile = profile(cameraEnabled = false)
        for (cursor in listOf(false, true)) for (code in listOf(138, 140)) {
            assertFalse(ProfileInputPolicy.consumesKey(profile, code, cursor, false))
        }
        assertTrue(ProfileInputPolicy.consumesKey(profile, 141, true, false))
        assertTrue(ProfileInputPolicy.consumesKey(profile, 139, true, false))
    }

    @Test fun handoffRetainsExistingGameplayOwnershipUntilItsBarrierCompletes() {
        val profile = profile()
        val up = profile.profile.joystick.upKey
        assertTrue(ProfileInputPolicy.consumesKey(profile, up, true, true))
        assertFalse(ProfileInputPolicy.consumesKey(profile, up, true, false))
    }

    @Test fun unassignedAndUnknownCodesDoNotBecomeSessionCommands() {
        val base = GameProfile.create("dev.example.game", "Optional controls", 1080, 1920, 0)
        val profile = ProfileCompiler.prepare(base.copy(
            calibration = base.calibration.copy(confirmed = true),
            shortcuts = SessionShortcuts(cameraToggle = 138, stop = 0, edit = 0),
        ), 1080, 1920, 0)
        for (code in listOf(-1, 0, 44, 141)) {
            assertFalse(ProfileInputPolicy.consumesKey(profile, code, false, false))
            assertFalse(ProfileInputPolicy.consumesKey(profile, code, true, false))
        }
    }
}
