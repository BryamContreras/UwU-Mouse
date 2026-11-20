package dev.uwumouse.app.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraSensitivityScaleTest {
    @Test fun cameraButtonsMoveInHundredthsWithAThreeTimesNormalLimit() {
        assertEquals(1, CameraSensitivityScale.MINIMUM)
        assertEquals(300, CameraSensitivityScale.maximum(.35f))
        assertEquals(300, CameraSensitivityScale.maximum(1.25f))
        val start = CameraSensitivityScale.position(.35f)
        assertEquals(.34f, CameraSensitivityScale.gain(start - 1), 0f)
        assertEquals(.36f, CameraSensitivityScale.gain(start + 1), 0f)
        assertEquals(3f, CameraSensitivityScale.gain(300), 0f)
    }

    @Test fun legacyGainAboveTheNormalLimitIsPreservedUntilTheUserEdits() {
        var gain = 10.053f
        val current = CameraSensitivityScale.position(gain)
        val slider = SliderValueController(CameraSensitivityScale.MINIMUM, CameraSensitivityScale.maximum(gain),
            current, { gain = CameraSensitivityScale.gain(it) }, {})
        slider.synchronize(current)
        assertEquals(10.053f, gain, 0f)
        slider.step(-1)
        assertEquals(10.04f, gain, 0f)
    }
}
