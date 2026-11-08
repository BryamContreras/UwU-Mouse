package dev.mobimapper.mobimapper.profile

import dev.mobimapper.mobimapper.engine.MappingEngine
import dev.mobimapper.mobimapper.engine.TouchComposer
import org.junit.Assert.*
import org.junit.Test

class ProfileValidationTest {
    @Test fun configuredActionBudgetAllowsTwentyFourAndRejectsTwentyFiveOrConflictingTriggers() {
        val p=confirmed()
        val extras=(100..122).mapIndexed { index,key -> ActionSpec(.4f+index*.01f,.6f,"key",key) }
        assertEquals(24,ProfileCompiler.prepare(p.copy(additionalActions=extras),2400,1080,1).bindings.touches.size)
        rejects { ProfileCompiler.prepare(p.copy(additionalActions=extras+ActionSpec(.5f,.5f,"key",123)),2400,1080,1) }
        rejects { ProfileCompiler.prepare(p.copy(additionalActions=listOf(ActionSpec(.5f,.5f,"key",0))),2400,1080,1) }
        rejects { ProfileCompiler.prepare(p.copy(additionalActions=listOf(p.action.copy(behavior=ActionSpec.REPEAT))),2400,1080,1) }
    }
    private fun macro(id:String="macro-a",code:Int=35) = MacroSpec(id,"Macro",.5f,.5f,code=code,steps=listOf(MacroStep(.4f,.6f)))
    private fun profile(): GameProfile = GameProfile.create("com.dts.freefireth", "Entrenamiento", 2400, 1080, 1)
    private fun confirmed(): GameProfile = profile().let { it.copy(calibration = it.calibration.copy(confirmed = true)) }
    private fun rejects(operation: () -> Unit) {
        try { operation(); fail("Expected invalid profile to be rejected") } catch (_: IllegalArgumentException) { }
    }

    @Test fun macrosCompileCalibratedPointsOnceAndParticipateInCaptureWithoutBecomingActions() {
        val p=confirmed().let { it.copy(calibration=it.calibration.copy(area=GameArea(100f,50f,2300f,1000f)),
            camera=it.camera.copy(suspendKey=57),macros=listOf(macro().copy(steps=listOf(MacroStep(.25f,.75f,250),MacroStep(.9f,.1f,0))),
                macro("macro-mouse",4).copy(triggerKind="mouse"))) }
        val prepared=ProfileCompiler.prepare(p,2400,1080,1)
        assertEquals(1,prepared.bindings.touches.size)
        assertEquals(setOf(35),prepared.bindings.macroKeys)
        assertEquals(setOf(4),prepared.bindings.macroButtons)
        assertTrue(prepared.bindings.handlesKey(35));assertTrue(prepared.bindings.handlesButton(4))
        assertFalse(prepared.bindings.handlesKey(36));assertFalse(prepared.bindings.handlesButton(2))
        val macro=prepared.macros.first()
        assertEquals("macro-a",macro.id);assertEquals("macro:macro-a",macro.owner)
        assertEquals("key",macro.triggerKind);assertEquals(35,macro.code)
        assertEquals(650f,macro.steps[0].x,.001f);assertEquals(762.5f,macro.steps[0].y,.001f)
        assertEquals(250,macro.steps[0].waitMs)
        assertEquals(2080f,macro.steps[1].x,.001f);assertEquals(145f,macro.steps[1].y,.001f)
        assertEquals(0,macro.steps[1].waitMs)
        assertEquals(.25f,p.macros[0].steps[0].x,0f)
    }

    @Test fun macroBudgetsCoverTotalBindingsStepCountWaitsAndWholeSequenceDuration() {
        val p=confirmed().copy(macros=(0..7).map { macro("macro-$it",200+it) },
            additionalActions=(100..114).map { ActionSpec(.5f,.5f,code=it) })
        assertTrue(ProfileValidator.validate(p).isEmpty())
        rejects { ProfileValidator.requireValid(p.copy(additionalActions=p.additionalActions+ActionSpec(.5f,.5f,code=115))) }
        val macrosOnly=confirmed().copy(macros=(0..22).map { macro("macro-$it",200+it) })
        assertTrue(ProfileValidator.validate(macrosOnly).isEmpty())
        rejects { ProfileValidator.requireValid(macrosOnly.copy(macros=macrosOnly.macros+macro("macro-24",223))) }
        val exact=macro().copy(steps=List(16) { MacroStep(.5f,.5f,1815) }) // 16 × (60 + 1815) = 30 s.
        assertTrue(ProfileValidator.validate(confirmed().copy(macros=listOf(exact))).isEmpty())
        val invalid=listOf(exact.copy(steps=exact.steps+MacroStep(.5f,.5f)),exact.copy(steps=emptyList()),
            exact.copy(steps=List(16) { MacroStep(.5f,.5f,1816) }),
            macro().copy(steps=listOf(MacroStep(.5f,.5f,-1))),macro().copy(steps=listOf(MacroStep(.5f,.5f,5001))))
        for(m in invalid) rejects { ProfileCompiler.prepare(confirmed().copy(macros=listOf(m)),2400,1080,1) }
    }

    @Test fun macroAndSuspendTriggersCannotCollideWithAnyConfiguredInput() {
        val p=confirmed().let { it.copy(camera=it.camera.copy(suspendKey=57),macros=listOf(macro())) }
        assertTrue(ProfileValidator.validate(p).isEmpty())
        for(code in listOf(p.joystick.upKey,p.action.code,p.shortcuts.cameraToggle,p.camera.suspendKey,0,341)) {
            rejects { ProfileCompiler.prepare(p.copy(macros=listOf(macro(code=code))),2400,1080,1) }
        }
        for(code in listOf(p.joystick.upKey,p.action.code,p.shortcuts.cameraToggle,35,-1,341)) {
            rejects { ProfileCompiler.prepare(p.copy(camera=p.camera.copy(suspendKey=code)),2400,1080,1) }
        }
        assertTrue(ProfileValidator.validate(p.copy(camera=p.camera.copy(suspendKey=0))).isEmpty())
        rejects { ProfileValidator.requireValid(p.copy(macros=listOf(macro(),macro("macro-b")))) }
        rejects { ProfileValidator.requireValid(p.copy(macros=listOf(macro(),macro("macro-a",36)))) }
        val mouse=p.copy(action=p.action.copy(triggerKind="mouse",code=1),macros=listOf(macro(code=1).copy(triggerKind="mouse")))
        rejects { ProfileValidator.requireValid(mouse) }
    }

    @Test fun invalidMacroIdentityPositionsAndPendingBindingsCannotReachExecution() {
        val invalid=listOf(macro().copy(id="../macro"),macro().copy(name="\n"),macro().copy(x=Float.NaN),
            macro().copy(y=1f),macro().copy(markerSizeDp=23f),macro().copy(triggerKind="script"),
            macro().copy(steps=listOf(MacroStep(Float.POSITIVE_INFINITY,.5f))),
            macro().copy(steps=listOf(MacroStep(.5f,-.1f))))
        for(m in invalid) rejects { ProfileCompiler.prepare(confirmed().copy(macros=listOf(m)),2400,1080,1) }
    }

    @Test fun aDraftCanBeStoredButCannotBeExecutedUntilAreaIsConfirmed() {
        val draft = profile()
        assertEquals("landscape", draft.orientation)
        assertTrue(ProfileValidator.validate(draft).isEmpty())
        rejects { ProfileCompiler.prepare(draft, 2400, 1080, 1) }
        val prepared = ProfileCompiler.prepare(confirmed(), 2400, 1080, 1)
        assertEquals(2400f, prepared.geometry.width, 0f)
    }

    @Test fun transformsTheCalibratedAreaWithOffsetsAndIndependentCameraAxes() {
        val profile = confirmed().let { it.copy(
            calibration = it.calibration.copy(area = GameArea(100f, 50f, 2300f, 1000f)),
            joystick = it.joystick.copy(x = .25f, y = .75f, radius = .1f),
            camera = CameraSpec(.65f, .45f, .4f, .5f, 1.25f, .75f),
            action = ActionSpec(.9f, .8f)) }
        val prepared = ProfileCompiler.prepare(profile, 2400, 1080, 1)
        val g = prepared.geometry
        assertEquals(650f, g.joystickX, .001f)
        assertEquals(762.5f, g.joystickY, .001f)
        assertEquals(95f, g.joystickRadius, .001f)
        assertEquals(1530f, g.cameraX, .001f)
        assertEquals(477.5f, g.cameraY, .001f)
        assertEquals(440f, g.cameraRadiusX, .001f)
        assertEquals(237.5f, g.cameraRadiusY, .001f)
        assertEquals(2080f, g.actionX, .001f)
        assertEquals(810f, g.actionY, .001f)
        assertEquals(g.actionX, g.fireX, 0f)
        assertEquals(1.25f, g.sensitivityX, 0f)
        assertEquals(.75f, g.sensitivityY, 0f)
        assertEquals(.25f, profile.calibration.area.normalizedX(650f), .00001f)
        assertEquals(.75f, profile.calibration.area.normalizedY(762.5f), .00001f)
    }

    @Test fun rotationOrDimensionsChangeRequiresExplicitCalibration() {
        val p = confirmed()
        rejects { ProfileCompiler.prepare(p, 1080, 2400, 0) }
        rejects { ProfileCompiler.prepare(p, 2400, 1080, 3) }
        rejects { ProfileCompiler.prepare(p, 2399, 1080, 1) }
    }

    @Test fun invalidAreaAndNonFiniteCoordinatesAreRejectedBeforeCompilation() {
        val p = confirmed()
        assertTrue(ProfileValidator.validate(p.copy(calibration = p.calibration.copy(area = GameArea(-1f, 0f, 2400f, 1080f)))).isNotEmpty())
        assertTrue(ProfileValidator.validate(p.copy(calibration = p.calibration.copy(area = GameArea(100f, 10f, 90f, 1080f)))).isNotEmpty())
        assertTrue(ProfileValidator.validate(p.copy(joystick = p.joystick.copy(x = Float.NaN))).isNotEmpty())
        assertTrue(ProfileValidator.validate(p.copy(camera = p.camera.copy(sensitivityY = Float.POSITIVE_INFINITY))).isNotEmpty())
        assertTrue(ProfileValidator.validate(p.copy(action = p.action.copy(x = 1f))).isNotEmpty())
    }

    @Test fun completeControlRegionsMustStayWithinGameArea() {
        val p = confirmed()
        assertTrue(ProfileValidator.validate(p.copy(joystick = p.joystick.copy(y = .99f))).isNotEmpty())
        assertTrue(ProfileValidator.validate(p.copy(camera = p.camera.copy(x = .95f, width = .4f))).isNotEmpty())
        assertTrue(ProfileValidator.validate(p.copy(camera = p.camera.copy(width = .001f))).isNotEmpty())
        assertTrue(ProfileValidator.validate(p.copy(joystick = p.joystick.copy(radius = 0f))).isNotEmpty())
    }

    @Test fun keyAndShortcutConflictsAreRejectedAsOneConfiguration() {
        val p = confirmed()
        assertTrue(ProfileValidator.validate(p.copy(joystick = p.joystick.copy(upKey = p.joystick.downKey))).isNotEmpty())
        assertTrue(ProfileValidator.validate(p.copy(action = p.action.copy(code = p.joystick.upKey))).isNotEmpty())
        assertTrue(ProfileValidator.validate(p.copy(action = p.action.copy(code = p.shortcuts.stop))).isNotEmpty())
        assertTrue(ProfileValidator.validate(p.copy(shortcuts = p.shortcuts.copy(edit = p.shortcuts.cameraToggle))).isNotEmpty())
    }

    @Test fun schemaIdentityAndSensitivityHaveExplicitBounds() {
        val p = confirmed()
        assertTrue(ProfileValidator.validate(p.copy(schemaVersion = GameProfile.SCHEMA_VERSION + 1)).isNotEmpty())
        assertTrue(ProfileValidator.validate(p.copy(id = "../other")).isNotEmpty())
        assertTrue(ProfileValidator.validate(p.copy(packageName = "invalid package")).isNotEmpty())
        assertTrue(ProfileValidator.validate(p.copy(name = "\n")).isNotEmpty())
        assertTrue(ProfileValidator.validate(p.copy(camera = p.camera.copy(sensitivityX = 0f))).isNotEmpty())
        assertTrue(ProfileValidator.validate(p.copy(camera = p.camera.copy(sensitivityY = 20.1f))).isNotEmpty())
    }

    @Test fun oneConfiguredActionActivatesOnlyItsChosenTrigger() {
        val p = confirmed()
        val key = ProfileCompiler.prepare(p, 2400, 1080, 1)
        assertEquals(62, key.bindings.touches.single().key)
        assertNull(key.bindings.touches.single().button)
        val mouse = ProfileCompiler.prepare(p.copy(action = p.action.copy(triggerKind = "mouse", code = 2)), 2400, 1080, 1)
        assertNull(mouse.bindings.touches.single().key)
        assertEquals(2, mouse.bindings.touches.single().button)
        rejects { ProfileCompiler.prepare(p.copy(action = p.action.copy(triggerKind = "mouse", code = 3)), 2400, 1080, 1) }
    }

    @Test fun newProfilesDefaultSmoothButAnExplicitBoundedProfileKeepsItsCalibratedControls() {
        assertEquals(CameraSpec.SMOOTH,confirmed().camera.mode)
        assertEquals(CameraSpec.SMOOTH,GameProfile.create("com.example.othergame","Otro",2400,1080,1).camera.mode)
        val original=confirmed().let { it.copy(camera=it.camera.copy(mode=CameraSpec.BOUNDED)) }
        assertEquals(CameraSpec.BOUNDED,original.camera.mode)
        val bounded=ProfileCompiler.prepare(original,2400,1080,1)
        assertTrue(bounded.bindings.continuousCamera)
        assertEquals(CameraSpec.SMOOTH,bounded.profile.camera.mode)
        assertNotNull(bounded.bindings.ggCameraPolicy)
        val chosen=original.copy(camera=original.camera.copy(mode=CameraSpec.CONTINUOUS))
        val continuous=ProfileCompiler.prepare(chosen,2400,1080,1)
        assertTrue(continuous.bindings.continuousCamera)
        assertTrue(continuous.bindings.cameraLast)
        assertFalse(continuous.bindings.overlapCameraHandoff)
        assertEquals(bounded.geometry,continuous.geometry)
        assertEquals(bounded.bindings.touches,continuous.bindings.touches)
        assertEquals(original.calibration,continuous.profile.calibration)
        assertEquals(original.joystick,continuous.profile.joystick)
        assertEquals(original.actions,continuous.profile.actions)
        assertEquals(original.shortcuts,continuous.profile.shortcuts)
        assertEquals(CameraSpec.BOUNDED,original.camera.mode)
    }

    @Test fun unknownCameraModesCannotReachPreparedExecution() {
        for(mode in listOf("", "Continuous", "offscreen", "bounded ")) {
            val p=confirmed().let { it.copy(camera=it.camera.copy(mode=mode)) }
            assertTrue(ProfileValidator.validate(p).isNotEmpty())
            rejects { ProfileCompiler.prepare(p,2400,1080,1) }
        }
    }

    @Test fun configuredCapacityNeverExpandsTheLiveTenContactStreamOrStealsCameraIdNine() {
        val p=confirmed().let { it.copy(camera=it.camera.copy(mode=CameraSpec.CONTINUOUS),
            additionalActions=(100..122).map { ActionSpec(.5f,.6f,"key",it) }) }
        val prepared=ProfileCompiler.prepare(p,2400,1080,1)
        var delivered=0
        val composer=TouchComposer({100L},{ delivered++;true },continuousCamera=true)
        val engine=MappingEngine(composer,prepared.geometry,prepared.bindings)
        assertEquals(24,prepared.bindings.touches.size)
        assertEquals(0,composer.activeCount)
        assertEquals(0,delivered)
        assertTrue(engine.setCameraEnabled(true))
        assertTrue(engine.relativeMouse(1f,1f))
        assertTrue(engine.key(p.joystick.upKey,true))
        assertTrue(engine.key(p.action.code,true))
        for(code in 100..106) assertTrue(engine.key(code,true))
        assertEquals(10,composer.activeCount)
        assertEquals(9,composer.snapshot().single { it.owner==MappingEngine.CAMERA_OWNER }.id)
        val before=delivered
        assertTrue(engine.key(107,true)) // Binding recognized, but no eleventh pointer is admitted.
        assertFalse(engine.lastOperationSucceeded)
        assertEquals(before,delivered)
        assertEquals(10,composer.activeCount)
        assertTrue(engine.pause())
        assertEquals(0,composer.activeCount)
    }

    @Test fun cameraRateAndVisualMarkerValidationAreIndependentOfTouchGeometry() {
        val original=confirmed()
        assertEquals(250,original.camera.updateRateHz)
        assertEquals(52f,original.camera.markerSizeDp,0f)
        assertEquals(52f,original.action.markerSizeDp,0f)
        val geometry=ProfileCompiler.prepare(original,2400,1080,1).geometry
        for(rate in listOf(0,60,90,100,120,125,144,240,250,500,1000)) {
            val changed=original.copy(camera=original.camera.copy(updateRateHz=rate,markerSizeDp=120f),
                action=original.action.copy(markerSizeDp=24f))
            val prepared=ProfileCompiler.prepare(changed,2400,1080,1)
            assertEquals(rate,prepared.profile.camera.updateRateHz)
            assertEquals(geometry,prepared.geometry)
        }
        for(rate in listOf(-1,1,59,61,999,1001)) {
            rejects { ProfileCompiler.prepare(original.copy(camera=original.camera.copy(updateRateHz=rate)),2400,1080,1) }
        }
        for(size in listOf(23.9f,120.1f,Float.NaN,Float.POSITIVE_INFINITY)) {
            rejects { ProfileCompiler.prepare(original.copy(camera=original.camera.copy(markerSizeDp=size)),2400,1080,1) }
            rejects { ProfileCompiler.prepare(original.copy(action=original.action.copy(markerSizeDp=size)),2400,1080,1) }
            rejects { ProfileCompiler.prepare(original.copy(additionalActions=listOf(ActionSpec(.5f,.6f,"mouse",2,size))),2400,1080,1) }
        }
    }
}
