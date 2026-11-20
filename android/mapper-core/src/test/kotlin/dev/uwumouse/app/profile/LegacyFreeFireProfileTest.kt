package dev.uwumouse.app.profile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LegacyFreeFireProfileTest {
    @Test fun importsProfilesWithRetiredCameraSettingAndPreservesTheirSensitivity() {
        val original = GameProfile.create("com.dts.freefireth", "Camera", 2560, 1440, 1).let {
            it.copy(camera = it.camera.copy(sensitivityX = .45f, sensitivityY = 1.35f))
        }
        val document = ProfileDocument(listOf(original), original.id)
        val wire = JSONObject(ProfileCodec.encodeDocument(document))
        val legacyField = "enhancedFreeFireOptimization"
        val camera = wire.getJSONArray("profiles").getJSONObject(0).getJSONObject("camera")

        for (priorValue in listOf(false, true)) {
            camera.put(legacyField, priorValue)
            val imported = ProfileCodec.decodeDocument(wire.toString())
            assertEquals(document, imported)
            assertFalse(ProfileCodec.toJson(imported.profiles.single()).getJSONObject("camera").has(legacyField))
        }
    }
}
