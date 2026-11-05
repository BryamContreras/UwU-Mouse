package dev.mobimapper.mobimapper.editor

import org.junit.Assert.*
import org.junit.Test

class BubbleSettingsTest {
    @Test fun customizedAppearanceAndPositionRoundTrip() {
        val settings = BubbleSettings(60, .45f, .25f, .12f)
        assertEquals(settings, BubbleSettings.decode(settings.encode()))
    }
    @Test fun defaultsRemainVisibleAndFitDeclaredLimits() {
        assertEquals(BubbleSettings(), BubbleSettings.decode(BubbleSettings().encode()))
        BubbleSettings(32, .30f, 0f, 1f)
        BubbleSettings(72, 1f, 1f, 0f)
    }
    @Test fun invalidSizeOrOpacityCannotBeSavedAsSettings() {
        listOf<() -> Unit>({ BubbleSettings(0) }, { BubbleSettings(73) },
            { BubbleSettings(opacity = .1f) }, { BubbleSettings(opacity = Float.NaN) }).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { invalid() }
        }
    }
    @Test fun invalidPositionCannotMoveBubbleOffscreen() {
        assertThrows(IllegalArgumentException::class.java) { BubbleSettings(x = -1f) }
        assertThrows(IllegalArgumentException::class.java) { BubbleSettings(y = Float.POSITIVE_INFINITY) }
    }
    @Test fun incompatibleOrIncompleteStoredDocumentIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            BubbleSettings.decode(BubbleSettings().encode().replace("\"schemaVersion\":2", "\"schemaVersion\":3"))
        }
        assertThrows(Exception::class.java) { BubbleSettings.decode("{}") }
    }
    @Test fun displayPreferencesAndDockSurviveReload() {
        val value=BubbleSettings(37,.49f,.62f,0f,"top",true,.27f)
        assertEquals(value,BubbleSettings.decode(value.encode()))
    }
    @Test fun legacySettingsKeepAppearanceWithoutEnablingControls() {
        val value=BubbleSettings.decode("""{"schemaVersion":1,"sizeDp":37,"opacity":0.49,"x":0.62,"y":0} """)
        assertEquals(37,value.sizeDp);assertEquals(.49f,value.opacity,0f)
        assertFalse(value.showControls);assertEquals("none",value.dockEdge)
    }
    @Test fun displayOpacityAndMalformedNewFieldsAreRejected() {
        assertThrows(IllegalArgumentException::class.java){BubbleSettings(controlsOpacity=Float.NaN)}
        assertThrows(IllegalArgumentException::class.java){BubbleSettings(controlsOpacity=.09f)}
        assertThrows(IllegalArgumentException::class.java){BubbleSettings(dockEdge="outside")}
        assertThrows(IllegalArgumentException::class.java){BubbleSettings.decode(BubbleSettings().encode().replace("\"showControls\":false","\"showControls\":\"true\""))}
    }
}
