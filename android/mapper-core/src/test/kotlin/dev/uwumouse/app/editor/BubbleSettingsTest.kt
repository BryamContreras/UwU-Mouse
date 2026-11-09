package dev.uwumouse.app.editor

import org.junit.Assert.*
import org.junit.Test

class BubbleSettingsTest {
    @Test fun monitorPreferenceRoundTripsAndDefaultsOffForExistingSettings() {
        val enabled=BubbleSettings(monitorEnabled=true)
        assertEquals(enabled,BubbleSettings.decode(enabled.encode()))
        val old=org.json.JSONObject(enabled.encode()).apply { remove("monitorEnabled") }
        assertFalse(BubbleSettings.decode(old.toString()).monitorEnabled)
        old.put("monitorEnabled","true")
        assertThrows(IllegalArgumentException::class.java) { BubbleSettings.decode(old.toString()) }
    }
    @Test fun monitorAppearanceRoundTripsAndOldDocumentsKeepDefaults() {
        val configured=BubbleSettings(monitorEnabled=true,monitorOpacity=.45f,monitorX=.32f,monitorY=.89f)
        assertEquals(configured,BubbleSettings.decode(configured.encode()))
        val old=org.json.JSONObject(configured.encode()).apply {
            remove("monitorOpacity");remove("monitorX");remove("monitorY")
        }
        val decoded=BubbleSettings.decode(old.toString())
        assertEquals(1f,decoded.monitorOpacity,0f)
        assertNull(decoded.monitorX);assertNull(decoded.monitorY)
        assertTrue(decoded.monitorEnabled)
    }
    @Test fun monitorAppearanceRejectsInvalidOpacityAndPosition() {
        for(value in listOf(.09f,1.01f,Float.NaN,Float.POSITIVE_INFINITY))
            assertThrows(IllegalArgumentException::class.java) { BubbleSettings(monitorOpacity=value) }
        assertThrows(IllegalArgumentException::class.java) { BubbleSettings(monitorX=.3f) }
        assertThrows(IllegalArgumentException::class.java) { BubbleSettings(monitorX=1.01f,monitorY=.2f) }
        assertThrows(IllegalArgumentException::class.java) { BubbleSettings(monitorX=.3f,monitorY=Float.NaN) }
        for(key in listOf("monitorOpacity","monitorX","monitorY")) {
            val malformed=org.json.JSONObject(BubbleSettings().encode()).put(key,"0.5")
            assertThrows(IllegalArgumentException::class.java) { BubbleSettings.decode(malformed.toString()) }
        }
    }
    @Test fun customizedAppearanceAndPositionRoundTrip() {
        val settings = BubbleSettings(60, .45f, .25f, .12f,cursorSensitivity=.35f,cursorSizeDp=36f)
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
    @Test fun legacyDocumentsKeepEverySavedSettingAndSupplyIndependentCursorDefaults() {
        val old="""{"schemaVersion":2,"sizeDp":52,"opacity":0.42,"x":0.8,"y":0.2,"dockEdge":"right","showControls":true,"controlsOpacity":0.7}"""
        val decoded=BubbleSettings.decode(old)
        assertEquals(BubbleSettings(52,.42f,.8f,.2f,"right",true,.7f),decoded)
        assertEquals(1f,decoded.cursorSensitivity,0f)
        assertEquals(24f,decoded.cursorSizeDp,0f)
        assertEquals(decoded,BubbleSettings.decode(decoded.encode()))
        assertEquals(2,org.json.JSONObject(decoded.encode()).getInt("schemaVersion"))
    }
    @Test fun cursorLimitsRoundTripWithoutChangingBubbleAppearanceOrPosition() {
        val base=BubbleSettings(37,.49f,.62f,0f,"top",true,.27f)
        for(sensitivity in listOf(.10f,1f,4f)) for(size in listOf(16f,24f,48f)) {
            val updated=base.copy(cursorSensitivity=sensitivity,cursorSizeDp=size)
            assertEquals(updated,BubbleSettings.decode(updated.encode()))
            assertEquals(base,updated.copy(cursorSensitivity=1f,cursorSizeDp=24f))
        }
    }
    @Test fun cursorPreferencesRejectOutOfRangeNonfiniteAndMalformedValues() {
        for(value in listOf(.09f,4.01f,Float.NaN,Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { BubbleSettings(cursorSensitivity=value) }
        }
        for(value in listOf(15.9f,48.1f,Float.NaN,Float.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { BubbleSettings(cursorSizeDp=value) }
        }
        for(key in listOf("cursorSensitivity","cursorSizeDp")) for(value in listOf<Any?>("1",true,null)) {
            val malformed=org.json.JSONObject(BubbleSettings().encode()).put(key,value ?: org.json.JSONObject.NULL)
            assertThrows(IllegalArgumentException::class.java) { BubbleSettings.decode(malformed.toString()) }
        }
    }
}
