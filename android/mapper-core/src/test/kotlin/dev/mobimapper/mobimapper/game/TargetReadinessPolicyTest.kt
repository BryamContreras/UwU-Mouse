package dev.mobimapper.mobimapper.game

import dev.mobimapper.mobimapper.profile.Calibration
import dev.mobimapper.mobimapper.profile.GameArea
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetReadinessPolicyTest {
    private val window = TargetSnapshot("com.example.game", 2316, 1080, 1, 75, 24, 2316, 1040)
    private val calibration = Calibration(2316, 1080, 1, GameArea(75f, 24f, 2316f, 1040f), true)
    private fun reuse(current: TargetSnapshot? = window, settled: TargetSnapshot? = window,
                      configured: Calibration = calibration, authorized: Boolean = true,
                      invalidated: Boolean = false) =
        TargetReadinessPolicy.mayReuse(settled, current, configured, authorized, invalidated)

    @Test fun unchangedAuthorizedWindowCanReuseItsSettlingProof() {
        assertTrue(reuse(current = window.copy()))
        assertTrue(reuse(configured = calibration.copy(area = GameArea(100f, 80f, 2200f, 1000f))))
    }

    @Test fun missingProofAuthorizationOrConfirmedCalibrationRequiresSettling() {
        assertFalse(reuse(current = null))
        assertFalse(reuse(settled = null))
        assertFalse(reuse(authorized = false))
        assertFalse(reuse(invalidated = true))
        assertFalse(reuse(configured = calibration.copy(confirmed = false)))
    }

    @Test fun packageDisplayRotationAndEveryWindowEdgeArePartOfTheProof() {
        val changed = listOf(
            window.copy(packageName = "another.game"),
            window.copy(displayId = 2),
            window.copy(width = 2400), window.copy(height = 1200), window.copy(rotation = 3),
            window.copy(left = 76), window.copy(top = 25),
            window.copy(right = 2315), window.copy(bottom = 1039),
        )
        for (candidate in changed) assertFalse("Changed snapshot $candidate", reuse(current = candidate))
    }

    @Test fun matchingSnapshotsStillRequireExactCalibrationDimensionsAndRotation() {
        assertFalse(reuse(configured = calibration.copy(screenWidth = 2400)))
        assertFalse(reuse(configured = calibration.copy(screenHeight = 1200)))
        assertFalse(reuse(configured = calibration.copy(rotation = 3)))
    }

    @Test fun confirmedExternalCalibrationCanReuseTheExactDisplayAndWindowFrame() {
        val external=window.copy(displayId=2)
        val frame=GameArea(window.left.toFloat(),window.top.toFloat(),window.right.toFloat(),window.bottom.toFloat())
        val configured=calibration.copy(displayScope="external",windowFrame=frame,
            area=GameArea(100f,80f,2200f,1000f))
        assertTrue(reuse(current=external.copy(),settled=external,configured=configured))
        assertFalse(reuse(current=external,settled=external,configured=calibration))
        assertFalse(reuse(current=window,settled=window,configured=configured))
    }

    @Test fun otherwiseIdenticalExternalWindowsCannotReuseProofFromAnotherDisplay() {
        val external=window.copy(displayId=2)
        val configured=calibration.copy(displayScope="external",windowFrame=calibration.area)
        assertTrue(reuse(current=external,settled=external,configured=configured))
        assertFalse(reuse(current=external.copy(displayId=3),settled=external,configured=configured))
        assertFalse(reuse(current=external,settled=external.copy(displayId=3),configured=configured))
    }

    @Test fun externalProofRequiresASavedFrameMatchingEveryWindowEdge() {
        val external=window.copy(displayId=2)
        val configured=calibration.copy(displayScope="external",windowFrame=calibration.area)
        val invalidFrames=listOf(null,calibration.area.copy(left=76f),calibration.area.copy(top=25f),
            calibration.area.copy(right=2315f),calibration.area.copy(bottom=1039f))
        for(frame in invalidFrames) assertFalse("Mismatched external frame $frame",
            reuse(current=external,settled=external,configured=configured.copy(windowFrame=frame)))
    }

    @Test fun newCalibrationMustFitEverySettledWindowEdgeIncludingInsets() {
        val outside = listOf(
            calibration.area.copy(left = 74f), calibration.area.copy(top = 23f),
            calibration.area.copy(right = 2317f), calibration.area.copy(bottom = 1041f),
        )
        for (area in outside) assertFalse("Outside area $area", reuse(configured = calibration.copy(area = area)))
    }

    @Test fun nonfiniteAndEmptyOrTooSmallAreasCannotUseFastReadiness() {
        val invalid = listOf(
            calibration.area.copy(left = Float.NaN), calibration.area.copy(top = Float.POSITIVE_INFINITY),
            calibration.area.copy(right = Float.NEGATIVE_INFINITY), calibration.area.copy(bottom = Float.NaN),
            calibration.area.copy(right = 75f), calibration.area.copy(bottom = 24f),
            calibration.area.copy(right = 74f), calibration.area.copy(bottom = 23f),
            calibration.area.copy(right = 106f), calibration.area.copy(bottom = 55f),
        )
        for (area in invalid) assertFalse("Invalid area $area", reuse(configured = calibration.copy(area = area)))
    }

    @Test fun malformedSnapshotsCannotAuthorizeEvenWhenTheyAreEqual() {
        val invalid = listOf(
            window.copy(packageName = ""), window.copy(width = 0), window.copy(height = 0),
            window.copy(width = 8193), window.copy(rotation = -1), window.copy(rotation = 4),
            window.copy(displayId = -1),
            window.copy(right = window.left), window.copy(bottom = window.top),
        )
        for (candidate in invalid) {
            val matching = calibration.copy(screenWidth = candidate.width, screenHeight = candidate.height,
                rotation = candidate.rotation)
            assertFalse("Malformed snapshot $candidate", reuse(candidate, candidate, matching))
        }
    }

    @Test fun calibrationCannotEscapeTheDisplayEvenIfWindowBoundsExtendBeyondIt() {
        val expanded = window.copy(left = -100, top = -100, right = 2400, bottom = 1200)
        val invalid = listOf(
            calibration.area.copy(left = -1f), calibration.area.copy(top = -1f),
            calibration.area.copy(right = 2317f), calibration.area.copy(bottom = 1081f),
        )
        for (area in invalid) assertFalse(reuse(expanded, expanded, calibration.copy(area = area)))
    }
}
