package dev.uwumouse.app.profile

import org.junit.Assert.*
import org.junit.Test

class RuntimeProfileGeometryTest {
    private fun profile(scope:String="external",frame:GameArea?=GameArea(0f,0f,2560f,1440f),
                        area:GameArea=frame ?: GameArea(0f,0f,2560f,1440f)) =
        GameProfile.create("dev.example.game","Original",2560,1440,0,scope).let {
            it.copy(calibration=it.calibration.copy(confirmed=true,windowFrame=frame,area=area))
        }

    @Test fun exactGeometryReusesTheSavedObject() {
        val saved=profile()
        assertSame(saved,RuntimeProfileGeometry.adapt(saved,2560,1440,0,"external",saved.calibration.windowFrame!!))
    }

    @Test fun smallWindowToFullscreenUsesNormalizedControlsAndDoesNotMutateSavedCalibration() {
        val oldFrame=GameArea(200f,100f,1400f,900f)
        val saved=profile(frame=oldFrame)
        val serialized=ProfileCodec.encode(saved)
        val actualFrame=GameArea(0f,0f,1920f,1024f)
        val adapted=RuntimeProfileGeometry.adapt(saved,1920,1080,0,"external",actualFrame)
        assertEquals(actualFrame,adapted.calibration.area)
        assertEquals(actualFrame,adapted.calibration.windowFrame)
        assertEquals(saved.id,adapted.id)
        assertEquals(saved.joystick,adapted.joystick)
        assertEquals(saved.camera,adapted.camera)
        assertEquals(saved.actions,adapted.actions)
        assertEquals(serialized,ProfileCodec.encode(saved))
        val compiled=ProfileCompiler.prepare(adapted,1920,1080,0,"external",actualFrame)
        assertEquals(actualFrame.displayX(saved.joystick.x),compiled.geometry.joystickX,0.001f)
        assertEquals(actualFrame.displayY(saved.joystick.y),compiled.geometry.joystickY,0.001f)
    }

    @Test fun movedAndResizedWindowsPreserveCalibratedInsetsProportionally() {
        val oldFrame=GameArea(100f,50f,1900f,950f)
        val saved=profile(frame=oldFrame,area=GameArea(190f,140f,1810f,860f))
        val next=GameArea(20f,30f,920f,630f)
        val adapted=RuntimeProfileGeometry.adapt(saved,1920,1080,0,"external",next)
        assertEquals(GameArea(65f,90f,875f,570f),adapted.calibration.area)
        ProfileCompiler.prepare(adapted,1920,1080,0,"external",next)
        assertEquals(GameArea(190f,140f,1810f,860f),saved.calibration.area)
    }

    @Test fun adaptationAlwaysStartsFromTheSavedCalibrationAndNeverAccumulatesResizeDrift() {
        val saved=profile(area=GameArea(128f,72f,2432f,1368f))
        val first=RuntimeProfileGeometry.adapt(saved,1920,1080,0,"external",GameArea(100f,100f,1700f,1000f))
        val second=RuntimeProfileGeometry.adapt(saved,1920,1080,0,"external",GameArea(0f,0f,1920f,1080f))
        assertEquals(GameArea(180f,145f,1620f,955f),first.calibration.area)
        assertEquals(GameArea(96f,54f,1824f,1026f),second.calibration.area)
        assertEquals(GameArea(128f,72f,2432f,1368f),saved.calibration.area)
    }

    @Test fun legacyPhoneProfileCanAdaptToItsVerifiedApplicationWindowWithoutBecomingExternal() {
        val saved=profile(scope="phone",frame=null)
        val frame=GameArea(0f,32f,2560f,1400f)
        val adapted=RuntimeProfileGeometry.adapt(saved,2560,1440,0,"phone",frame)
        assertEquals("phone",adapted.calibration.displayScope)
        assertEquals(frame,adapted.calibration.area)
        assertNull(saved.calibration.windowFrame)
        ProfileCompiler.prepare(adapted,2560,1440,0,"phone",frame)
    }

    @Test fun runtimeDisplayDimensionsAndRotationAreUpdatedOnlyInTheExecutionCopy() {
        val saved=profile()
        val frame=GameArea(0f,0f,1080f,1920f)
        val adapted=RuntimeProfileGeometry.adapt(saved,1080,1920,1,"external",frame)
        assertEquals(1080,adapted.calibration.screenWidth)
        assertEquals(1920,adapted.calibration.screenHeight)
        assertEquals(1,adapted.calibration.rotation)
        assertEquals(2560,saved.calibration.screenWidth)
        assertEquals(0,saved.calibration.rotation)
        ProfileCompiler.prepare(adapted,1080,1920,1,"external",frame)
    }

    @Test fun unconfirmedOrDifferentDisplayScopeNeverProducesAnExecutableProfile() {
        val saved=profile()
        val frame=GameArea(0f,0f,1920f,1080f)
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeProfileGeometry.adapt(saved,1920,1080,0,"phone",frame)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeProfileGeometry.adapt(saved.copy(calibration=saved.calibration.copy(confirmed=false)),1920,1080,0,"external",frame)
        }
    }

    @Test fun invalidOffscreenOrTinyTargetFramesNeverAuthorizeAdaptation() {
        for(frame in listOf(GameArea(-1f,0f,1900f,1000f),GameArea(0f,0f,1921f,1080f),
            GameArea(0f,0f,1920f,1081f),GameArea(0f,0f,31f,100f),
            GameArea(0f,0f,100f,31f),GameArea(100f,0f,0f,100f),
            GameArea(Float.NaN,0f,1900f,1000f),GameArea(0f,0f,Float.POSITIVE_INFINITY,1000f))) {
            assertThrows(IllegalArgumentException::class.java) {
                RuntimeProfileGeometry.adapt(profile(),1920,1080,0,"external",frame)
            }
        }
    }

    @Test fun targetTooSmallForTheCalibratedSubareaRemainsPausedInsteadOfClippingControls() {
        val saved=profile(area=GameArea(500f,400f,1500f,1000f))
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeProfileGeometry.adapt(saved,1920,1080,0,"external",GameArea(0f,0f,40f,40f))
        }
    }

    @Test fun regularCompilerStillRejectsMismatchedDimensionsAndFramesAfterAdaptation() {
        val frame=GameArea(100f,40f,1820f,1040f)
        val adapted=RuntimeProfileGeometry.adapt(profile(),1920,1080,0,"external",frame)
        assertThrows(IllegalArgumentException::class.java) {
            ProfileCompiler.prepare(adapted,1920,1080,0,"external",GameArea(0f,0f,1920f,1080f))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProfileCompiler.prepare(adapted,1920,1080,1,"external",frame)
        }
    }
}
