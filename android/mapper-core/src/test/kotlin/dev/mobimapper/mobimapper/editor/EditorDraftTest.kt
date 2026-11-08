package dev.mobimapper.mobimapper.editor

import dev.mobimapper.mobimapper.profile.*
import org.junit.Assert.*
import org.junit.Test

class EditorDraftTest {
    @Test fun deletingEveryControlIsPersistentAndAddingRepeatReusesTheDisabledBase() {
        val p=original();val d=EditorDraft(p,1000,600,0)
        d.removeAction();d.removeJoystick();d.removeCamera()
        val empty=d.save()
        assertTrue(empty.actions.isEmpty());assertFalse(empty.action.enabled)
        assertFalse(empty.joystick.enabled);assertFalse(empty.camera.enabled)
        assertEquals(p.joystick.copy(enabled=false),empty.joystick)
        assertEquals(p.camera.copy(mode=CameraSpec.SMOOTH,enabled=false),empty.camera)
        assertEquals(empty,ProfileCodec.decode(ProfileCodec.encode(empty)))
        d.addRepeat(500f,300f)
        assertTrue(d.value.additionalActions.isEmpty());assertEquals(ActionSpec.REPEAT,d.action().behavior)
        try { d.save();fail("Unassigned repeat must not save") } catch(_:IllegalArgumentException) { }
        d.bindKey("action",35)
        d.replaceAction(d.action().copy(repeatIntervalMs=50))
        d.addCursorAction(700f,400f);d.bindKey("action",36)
        assertEquals(2,d.value.actions.size)
        d.selectAction(0);d.removeAction()
        assertEquals(1,d.value.actions.size);assertEquals(ActionSpec.CURSOR_TOGGLE,d.action().behavior)
        assertEquals(36,d.value.action.code);assertTrue(d.value.additionalActions.isEmpty())
        d.replace(d.value.copy(joystick=d.value.joystick.copy(enabled=true),camera=d.value.camera.copy(enabled=true)))
        assertEquals(p.joystick,d.save().joystick);assertEquals(p.camera.copy(mode=CameraSpec.SMOOTH),d.save().camera)
        assertSame(p,d.cancel())
    }

    @Test fun fireAndAimExplicitlyReuseAndConvertAnExistingCommandInsteadOfDuplicatingItsTrigger() {
        val d=EditorDraft(original(),1000,600,0)
        d.addRepeat(500f,300f);d.replaceAction(d.action().copy(triggerKind="mouse",code=1))
        d.addAction(600f,350f,fire=true)
        assertEquals(2,d.value.actions.size);assertEquals(ActionSpec.TAP,d.action().behavior)
        assertEquals(1,d.action().code)
        d.addCursorAction(500f,300f);d.replaceAction(d.action().copy(triggerKind="mouse",code=2))
        d.addAction(650f,400f,aim=true)
        assertEquals(3,d.value.actions.size);assertEquals(ActionSpec.TAP,d.action().behavior)
        assertEquals(2,d.action().code)
        assertTrue(ProfileValidator.validate(d.save()).isEmpty())
    }

    @Test fun macroMarkersPointsAndWaitsEditTransactionallyWithoutMovingOtherControls() {
        val p=original();val d=EditorDraft(p,1000,600,0)
        d.addMacro(500f,300f)
        assertEquals("Macro 1",d.macro().name)
        assertEquals(0,d.macro().code)
        assertEquals(listOf(MacroStep(.5f,.5f,100)),d.macro().steps)
        try { d.save();fail("Pending macro trigger must not save") } catch(_:IllegalArgumentException) { }
        d.bindKey("macro",35)
        d.addMacroStep(700f,400f)
        d.macroWait(0,250)
        d.moveMacroStep(1,300f,150f)
        d.reorderMacroSteps(1,0)
        assertEquals(listOf(MacroStep(.25f,.2f,100),MacroStep(.5f,.5f,250)),d.macro().steps)
        val steps=d.macro().steps
        d.move(EditorDraft.Control.MACRO,600f,350f)
        d.markerSize(EditorDraft.Control.MACRO,100f)
        d.bindKey("cameraSuspend",57)
        val saved=d.save()
        assertEquals(.625f,saved.macros.single().x,0f);assertEquals(.6f,saved.macros.single().y,0f)
        assertEquals(steps,saved.macros.single().steps)
        assertEquals(100f,saved.macros.single().markerSizeDp,0f)
        assertEquals(p.action,saved.action);assertEquals(p.joystick,saved.joystick)
        assertEquals(p.camera.copy(mode=CameraSpec.SMOOTH,suspendKey=57),saved.camera)
        assertSame(p,d.cancel());assertTrue(p.macros.isEmpty());assertEquals(0,p.camera.suspendKey)
        d.bindKey("cameraSuspend",0);assertEquals(0,d.save().camera.suspendKey)
        d.removeMacroStep(0);assertEquals(1,d.macro().steps.size)
        try { d.removeMacroStep(0);fail("Last point must remain") } catch(_:IllegalArgumentException) { }
        d.addMacro(400f,250f);d.bindKey("macro",36)
        assertEquals("Macro 2",d.macro().name)
        d.selectMacro(0);d.removeMacro()
        assertEquals("Macro 2",d.macro().name)
        d.removeMacro();assertTrue(d.value.macros.isEmpty())
    }

    @Test fun macroBudgetAndInvalidPointChangesLeaveTheDraftUnchanged() {
        val d=EditorDraft(original(),1000,600,0)
        repeat(23) { index -> d.addMacro(500f,300f);d.bindKey("macro",200+index) }
        val before=d.value
        try { d.addMacro(500f,300f);fail("Twenty-fifth combined control must not be added") } catch(_:IllegalArgumentException) { }
        try { d.moveMacroStep(0,Float.NaN,300f);fail("NaN must be rejected") } catch(_:IllegalArgumentException) { }
        try { d.macroWait(0,5001);fail("Invalid wait must be rejected") } catch(_:IllegalArgumentException) { }
        assertEquals(before,d.value)
        repeat(15) { d.addMacroStep(500f,300f) }
        val full=d.value
        try { d.addMacroStep(500f,300f);fail("Seventeenth point must not be added") } catch(_:IllegalArgumentException) { }
        assertEquals(full,d.value)
        assertEquals(24,d.value.actions.size+d.value.macros.size)
        val capacity=d.value
        try { d.addAction(500f,300f);fail("Twenty-fifth combined control must not be added") } catch(_:IllegalArgumentException) { }
        assertEquals(capacity,d.value)
        assertTrue(ProfileValidator.validate(d.save()).isEmpty())
    }

    @Test fun macroOrSuspendCollisionCannotCommitAndCancelKeepsOriginal() {
        val p=original();val d=EditorDraft(p,1000,600,0)
        d.addMacro(500f,300f);d.bindKey("macro",p.joystick.upKey)
        try { d.save();fail("Macro colliding with WASD must not save") } catch(_:IllegalArgumentException) { }
        d.bindKey("macro",35);d.bindKey("cameraSuspend",35)
        try { d.save();fail("Suspend colliding with macro must not save") } catch(_:IllegalArgumentException) { }
        assertSame(p,d.cancel())
    }

    @Test fun addingActionsAndDefaultFireKeepsExistingControlsAndRequiresEveryKey() {
        val p=original(); val d=EditorDraft(p,1000,600,0)
        d.addAction(650f,400f)
        try { d.save(); fail("Unassigned action must not save") } catch(_:IllegalArgumentException) { }
        d.bindKey("action",35)
        d.addAction(700f,350f,true)
        val saved=d.save()
        assertEquals(3,saved.actions.size)
        assertEquals(p.action,saved.actions.first())
        assertEquals(35,saved.actions[1].code)
        assertEquals("mouse",saved.actions[2].triggerKind)
        assertEquals(1,saved.actions[2].code)
        d.addAction(600f,300f,true)
        assertEquals(3,d.value.actions.size)
        assertSame(p,d.cancel())
    }
    // A legacy profile opens as Smooth in the disposable draft, preserving its region geometry.
    private fun original(): GameProfile = GameProfile.create("com.example.game", "Entrenamiento", 1000, 600, 0).let { it.copy(
        calibration = Calibration(1000, 600, 0, GameArea(100f, 50f, 900f, 550f), true),
        camera = it.camera.copy(mode=CameraSpec.BOUNDED),
    ) }

    @Test fun directActionAndCameraAssignmentShareDraftAndCancelKeepsPreviousProfile() {
        val original=original()
        val draft=EditorDraft(original,1000,600,0)
        draft.move(EditorDraft.Control.ACTION,500f,300f)
        draft.bindKey("action",35)
        draft.bindKey("cameraToggle",57)
        val saved=draft.save()
        assertEquals(35,saved.action.code)
        assertEquals("key",saved.action.triggerKind)
        assertEquals(57,saved.shortcuts.cameraToggle)
        assertEquals(original.camera.copy(mode=CameraSpec.SMOOTH),saved.camera)
        assertSame(original,draft.cancel())
        assertEquals(62,original.action.code)
        assertEquals(138,original.shortcuts.cameraToggle)
    }

    @Test fun directAssignmentCannotCommitConflictsOrAcceptUnknownField() {
        val original=original()
        val draft=EditorDraft(original,1000,600,0)
        draft.bindKey("cameraToggle",original.action.code)
        try { draft.save(); fail("Duplicate mapping must be rejected") } catch(_:IllegalArgumentException) { }
        try { draft.bindKey("unknown",35); fail("Unknown target must be rejected") } catch(_:IllegalStateException) { }
        assertSame(original,draft.cancel())
    }

    @Test fun dragUsesTheCalibratedAreaOriginAndScale() {
        val original = original()
        val draft = EditorDraft(original, 1000, 600, 0)
        draft.move(EditorDraft.Control.ACTION, 500f, 300f)
        val saved = draft.save()
        val prepared = ProfileCompiler.prepare(saved, 1000, 600, 0)
        assertEquals(.5f, saved.action.x, .0001f)
        assertEquals(.5f, saved.action.y, .0001f)
        assertEquals(500f, prepared.geometry.actionX, .0001f)
        assertEquals(300f, prepared.geometry.actionY, .0001f)
        assertTrue(saved.calibration.confirmed)
        assertEquals(.87f, original.action.x, .0001f)
    }

    @Test fun cancelReturnsUntouchedProfileAfterGeometryAndBindingChanges() {
        val original = original()
        val draft = EditorDraft(original, 1000, 600, 0)
        draft.area(GameArea(20f, 10f, 980f, 590f))
        draft.move(EditorDraft.Control.JOYSTICK, 600f, 400f)
        draft.replace(draft.value.copy(action = draft.value.action.copy(triggerKind = "mouse", code = 2)))
        assertSame(original, draft.cancel())
        assertEquals(GameArea(100f, 50f, 900f, 550f), original.calibration.area)
        assertEquals("key", original.action.triggerKind)
    }

    @Test fun newOrientationIsOnlyConfirmedBySavingTheNewDraft() {
        val original = original()
        val hint = GameArea(40f, 30f, 1160f, 670f)
        val draft = EditorDraft(original, 1200, 700, 1, hint)
        assertEquals(hint, draft.value.calibration.area)
        assertFalse(draft.value.calibration.confirmed)
        assertSame(original, draft.cancel())
        val saved = draft.save()
        assertEquals(1, saved.calibration.rotation)
        assertEquals(1200, saved.calibration.screenWidth)
        assertTrue(saved.calibration.confirmed)
        assertNotNull(ProfileCompiler.prepare(saved, 1200, 700, 1))
    }

    @Test fun largeResizeAndEdgeDragsKeepWholeControlsInsideArea() {
        val draft = EditorDraft(original(), 1000, 600, 0)
        draft.joystickRadius(9000f)
        draft.move(EditorDraft.Control.JOYSTICK, 9000f, 9000f)
        draft.cameraSize(9000f, 9000f)
        draft.move(EditorDraft.Control.CAMERA, -9000f, -9000f)
        assertTrue(ProfileValidator.validate(draft.save()).isEmpty())
        val prepared = ProfileCompiler.prepare(draft.save(), 1000, 600, 0)
        val a = draft.value.calibration.area
        assertTrue(prepared.geometry.joystickX + prepared.geometry.joystickRadius < a.right)
        assertTrue(prepared.geometry.joystickY + prepared.geometry.joystickRadius < a.bottom)
        assertTrue(prepared.geometry.cameraX - prepared.geometry.cameraRadiusX >= a.left)
        assertTrue(prepared.geometry.cameraY - prepared.geometry.cameraRadiusY >= a.top)
    }

    @Test fun conflictingBindingCannotBeSavedButCanBeCancelled() {
        val original = original()
        val draft = EditorDraft(original, 1000, 600, 0)
        draft.replace(draft.value.copy(action = draft.value.action.copy(code = original.shortcuts.stop)))
        try { draft.save(); fail("Conflicting action must not be saved") }
        catch (_: IllegalArgumentException) { }
        assertSame(original, draft.cancel())
    }

    @Test fun invalidDragDoesNotPoisonTheDraft() {
        val draft = EditorDraft(original(), 1000, 600, 0)
        val before = draft.value
        try { draft.move(EditorDraft.Control.ACTION, Float.NaN, 100f); fail("NaN must be rejected") }
        catch (_: IllegalArgumentException) { }
        assertEquals(before, draft.value)
        assertTrue(ProfileValidator.validate(draft.save()).isEmpty())
    }

    @Test fun continuousCameraModeIsTransactionalAndDoesNotMoveControlsOrChangeBindings() {
        val original=original()
        val draft=EditorDraft(original,1000,600,0)
        val before=draft.value
        draft.cameraMode(CameraSpec.CONTINUOUS)
        val saved=draft.save()
        assertEquals(before.copy(camera=before.camera.copy(mode=CameraSpec.CONTINUOUS),calibration=before.calibration.copy(confirmed=true)),saved)
        assertTrue(ProfileCompiler.prepare(saved,1000,600,0).bindings.continuousCamera)
        assertSame(original,draft.cancel())
        assertEquals(CameraSpec.BOUNDED,original.camera.mode)
        draft.cameraMode(CameraSpec.BOUNDED)
        assertEquals(before.copy(camera=before.camera.copy(mode=CameraSpec.BOUNDED)),draft.value)
    }

    @Test fun invalidCameraModeDoesNotChangeTheDraftAndExistingContinuousModeCanBeCancelled() {
        val original=original().let { it.copy(camera=it.camera.copy(mode=CameraSpec.CONTINUOUS)) }
        val draft=EditorDraft(original,1000,600,0)
        val before=draft.value
        try { draft.cameraMode("unknown"); fail("Unknown camera mode must be rejected") }
        catch(_:IllegalArgumentException) { }
        assertEquals(before,draft.value)
        draft.cameraMode(CameraSpec.BOUNDED)
        assertSame(original,draft.cancel())
        assertEquals(CameraSpec.CONTINUOUS,original.camera.mode)
    }

    @Test fun aimAndFirePaletteReuseTheirExistingActionsEvenAtConfiguredCapacity() {
        val draft=EditorDraft(original(),1000,600,0)
        draft.addAction(650f,350f,aim=true)
        assertEquals("mouse",draft.action().triggerKind)
        assertEquals(2,draft.action().code)
        val aimIndex=draft.selectedAction
        draft.addAction(700f,400f,fire=true)
        val fireIndex=draft.selectedAction
        assertEquals(1,draft.action().code)
        repeat(21) { index -> draft.addAction(500f,300f);draft.bindKey("action",100+index) }
        assertEquals(24,draft.value.actions.size)
        val actionBefore=draft.value.action
        draft.addAction(600f,300f,aim=true)
        assertEquals(aimIndex,draft.selectedAction)
        assertEquals(2,draft.action().code)
        assertEquals(24,draft.value.actions.size)
        draft.addAction(650f,350f,fire=true)
        assertEquals(fireIndex,draft.selectedAction)
        assertEquals(1,draft.action().code)
        assertEquals(actionBefore,draft.value.action)
        assertTrue(ProfileValidator.validate(draft.save()).isEmpty())
        val before=draft.value
        try { draft.addAction(500f,300f);fail("A twenty-fifth configured action must be rejected") }
        catch(_:IllegalArgumentException) { }
        assertEquals(before,draft.value)
        try { draft.addAction(500f,300f,fire=true,aim=true);fail("Ambiguous palette action must be rejected") }
        catch(_:IllegalArgumentException) { }
        assertEquals(before,draft.value)
    }

    @Test fun visualCornerResizingAndCameraRateAreTransactionalWithoutChangingTouchGeometry() {
        val original=original()
        val draft=EditorDraft(original,1000,600,0)
        val before=draft.value
        draft.markerSize(EditorDraft.Control.CAMERA,90f)
        draft.markerSize(EditorDraft.Control.ACTION,32f)
        draft.cameraUpdateRate(120)
        draft.cameraMode(CameraSpec.CONTINUOUS)
        assertEquals(before.copy(camera=before.camera.copy(markerSizeDp=90f,updateRateHz=120,mode=CameraSpec.CONTINUOUS),
            action=before.action.copy(markerSizeDp=32f)),draft.value)
        val saved=draft.save()
        assertEquals(120,saved.camera.updateRateHz)
        assertEquals(90f,saved.camera.markerSizeDp,0f)
        assertEquals(32f,saved.action.markerSizeDp,0f)
        assertEquals(ProfileCompiler.prepare(original,1000,600,0).geometry,ProfileCompiler.prepare(saved,1000,600,0).geometry)
        assertSame(original,draft.cancel())
        assertEquals(52f,original.camera.markerSizeDp,0f)
        assertEquals(0,original.camera.updateRateHz)
    }

    @Test fun markerResizeOnlyChangesTheSelectedActionAndRejectsNonFiniteOrUnsupportedControls() {
        val draft=EditorDraft(original(),1000,600,0)
        draft.addAction(650f,350f,aim=true)
        val before=draft.value
        draft.markerSize(EditorDraft.Control.ACTION,100f)
        assertEquals(before.action,draft.value.action)
        assertEquals(100f,draft.value.additionalActions.single().markerSizeDp,0f)
        assertEquals(before.additionalActions.single().copy(markerSizeDp=100f),draft.value.additionalActions.single())
        draft.markerSize(EditorDraft.Control.CAMERA,9000f)
        assertEquals(120f,draft.value.camera.markerSizeDp,0f)
        draft.markerSize(EditorDraft.Control.ACTION,-100f)
        assertEquals(24f,draft.action().markerSizeDp,0f)
        val valid=draft.value
        try { draft.markerSize(EditorDraft.Control.ACTION,Float.NaN);fail("NaN must be rejected") }
        catch(_:IllegalArgumentException) { }
        try { draft.markerSize(EditorDraft.Control.JOYSTICK,80f);fail("Joystick uses its existing physical radius") }
        catch(_:IllegalArgumentException) { }
        assertEquals(valid,draft.value)
        assertEquals(before.joystick,draft.value.joystick)
    }

    @Test fun cameraRateAcceptsNativeCaptureChoicesAndSavedLegacyCapsWithoutChangingCancel() {
        val original=original()
        val draft=EditorDraft(original,1000,600,0)
        val before=draft.value
        for(rate in listOf(0,60,90,100,120,144,240,500,1000)) {
            draft.cameraUpdateRate(rate)
            assertEquals(before.copy(camera=before.camera.copy(updateRateHz=rate)),draft.value)
        }
        val valid=draft.value
        try { draft.cameraUpdateRate(59);fail("Unknown cap must be rejected") }
        catch(_:IllegalArgumentException) { }
        assertEquals(valid,draft.value)
        assertSame(original,draft.cancel())
    }
}
