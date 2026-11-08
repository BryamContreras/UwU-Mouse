package dev.mobimapper.mobimapper.profile

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SmoothCameraSettingsTest {
    private fun profile()=GameProfile.create("com.example.game","Camera",1000,2000,0)

    @Test fun freshProfilesUseSmoothWithPrimaryCameraDefaults() {
        val camera=profile().camera
        assertEquals(CameraSpec.SMOOTH,camera.mode)
        assertEquals(.70f,camera.sensitivityX,0f)
        assertEquals(.70f,camera.sensitivityY,0f)
        assertEquals(250,camera.updateRateHz)
        assertFalse(camera.customRegion)
        assertFalse(camera.precisionAim)
    }

    @Test fun optionalRegionChoiceRoundTripsWithoutMigratingSavedCameraSettings() {
        for(mode in listOf(CameraSpec.CONTINUOUS,CameraSpec.BOUNDED,CameraSpec.SMOOTH)) {
            val original=profile().let { it.copy(camera=it.camera.copy(mode=mode,
                sensitivityX=1.25f,sensitivityY=.35f,updateRateHz=100,customRegion=true)) }
            assertEquals(original,ProfileCodec.decode(ProfileCodec.encode(original)))
            val old=ProfileCodec.toJson(original).apply { getJSONObject("camera").remove("customRegion") }
            val restored=ProfileCodec.decode(old.toString())
            assertEquals(original.copy(camera=original.camera.copy(customRegion=false)),restored)
        }
    }

    @Test fun optionalRegionChoiceRejectsCoercionsAndIsIgnoredBeforeSchemaNine() {
        for(value in listOf<Any>(JSONObject.NULL,"true",0,1)) {
            val json=ProfileCodec.toJson(profile()).apply { getJSONObject("camera").put("customRegion",value) }
            assertThrows(IllegalArgumentException::class.java) { ProfileCodec.decode(json.toString()) }
        }
        val old=ProfileCodec.toJson(profile()).put("schemaVersion",8).apply {
            getJSONObject("camera").put("customRegion","ignored")
        }
        assertFalse(ProfileCodec.decode(old.toString()).camera.customRegion)
    }

    @Test fun ggCaptureRatesArePersistedAlongsideExistingChoices() {
        for(rate in listOf(125,250,100,1000)) {
            val original=profile().let { it.copy(camera=it.camera.copy(updateRateHz=rate)) }
            assertEquals(original,ProfileCodec.decode(ProfileCodec.encode(original)))
        }
    }
}
