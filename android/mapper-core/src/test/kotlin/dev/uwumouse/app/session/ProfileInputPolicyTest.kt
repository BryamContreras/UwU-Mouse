package dev.uwumouse.app.session

import dev.uwumouse.app.profile.ActionSpec
import dev.uwumouse.app.profile.GameProfile
import dev.uwumouse.app.profile.PreparedProfile
import dev.uwumouse.app.profile.ProfileCompiler
import dev.uwumouse.app.profile.SessionShortcuts
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

    @Test fun sessionShortcutsStayOwnedWhileTyping() {
        val profile = profile()
        for (textMode in listOf(false, true)) for (shortcut in listOf(138, 139, 140, 141)) {
            assertTrue(ProfileInputPolicy.consumesKey(profile, shortcut, textMode))
        }
    }

    @Test fun mappedGameKeysAndCursorActionsAreForwardedWhenTyping() {
        val profile = profile()
        for (gameKey in listOf(profile.profile.joystick.upKey, profile.profile.action.code, 61)) {
            assertTrue(ProfileInputPolicy.consumesKey(profile, gameKey, textMode = false))
            assertFalse(ProfileInputPolicy.consumesKey(profile, gameKey, textMode = true))
        }
    }

    @Test fun disabledCameraDoesNotOwnItsToggleOrHoldKey() {
        val profile = profile(cameraEnabled = false)
        for (textMode in listOf(false, true)) for (code in listOf(138, 140)) {
            assertFalse(ProfileInputPolicy.consumesKey(profile, code, textMode))
        }
    }

    @Test fun unassignedAndUnknownCodesDoNotBecomeSessionCommands() {
        val base = GameProfile.create("dev.example.game", "Optional controls", 1080, 1920, 0)
        val profile = ProfileCompiler.prepare(base.copy(
            calibration = base.calibration.copy(confirmed = true),
            shortcuts = SessionShortcuts(cameraToggle = 138, stop = 0, edit = 0),
        ), 1080, 1920, 0)
        for (code in listOf(-1, 0, 44, 141)) {
            assertFalse(ProfileInputPolicy.consumesKey(profile, code, textMode = false))
            assertFalse(ProfileInputPolicy.consumesKey(profile, code, textMode = true))
        }
    }
}
