package dev.uwumouse.app.profile

import dev.uwumouse.app.editor.EditorDraft
import org.junit.Assert.*
import org.junit.Test

class EnhancedFreeFireProfileTest {
    private fun profile()=GameProfile.create("com.dts.freefireth","Camera",2560,1440,1).let {
        it.copy(calibration=it.calibration.copy(confirmed=true))
    }

    @Test fun existingDocumentsDefaultOffAndBothExplicitBooleanValuesRoundTripWithoutASchemaChange() {
        val original=profile()
        assertFalse(original.camera.enhancedFreeFireOptimization)
        val old=ProfileCodec.toJson(original).apply { getJSONObject("camera").remove("enhancedFreeFireOptimization") }
        assertEquals(original,ProfileCodec.decode(old.toString()))
        for(enabled in listOf(false,true)) {
            val p=original.copy(camera=original.camera.copy(enhancedFreeFireOptimization=enabled))
            assertEquals(9,p.schemaVersion)
            assertEquals(p,ProfileCodec.decode(ProfileCodec.encode(p)))
            val document=ProfileDocument(listOf(p),p.id)
            assertEquals(document,ProfileCodec.decodeDocument(ProfileCodec.encodeDocument(document)))
        }
        for(value in listOf("true",1)) {
            val malformed=ProfileCodec.toJson(original).apply { getJSONObject("camera").put("enhancedFreeFireOptimization",value) }
            assertThrows(IllegalArgumentException::class.java) { ProfileCodec.decode(malformed.toString()) }
        }
    }

    @Test fun onlyEnabledFreeFireCamerasArmTheStrategyAndDensityIsExplicitExecutionMetadata() {
        val original=profile()
        val on=original.copy(camera=original.camera.copy(enhancedFreeFireOptimization=true))
        val prepared=ProfileCompiler.prepare(on,2560,1440,1,densityDpi=600f)
        val policy=requireNotNull(prepared.bindings.uwuCameraPolicy)
        assertTrue(policy.enhancedFreeFireOptimization);assertEquals(600f,policy.densityDpi,0f)
        assertEquals(on.camera.sensitivityX,prepared.geometry.sensitivityX,0f)
        assertEquals(on.camera.sensitivityY,prepared.geometry.sensitivityY,0f)
        val generic=ProfileCompiler.prepare(on.copy(packageName="com.example.game"),2560,1440,1,densityDpi=320f)
        assertFalse(requireNotNull(generic.bindings.uwuCameraPolicy).enhancedFreeFireOptimization)
        assertTrue(generic.profile.camera.enhancedFreeFireOptimization)
        assertNull(ProfileCompiler.prepare(on.copy(camera=on.camera.copy(enabled=false)),2560,1440,1).bindings.uwuCameraPolicy)
        assertEquals(160f,requireNotNull(ProfileCompiler.prepare(original,2560,1440,1).bindings.uwuCameraPolicy).densityDpi,0f)
    }

    @Test fun editingTheOptionIsDisposableAndPreservesOtherCameraSettings() {
        val original=profile()
        val draft=EditorDraft(original,2560,1440,1)
        draft.enhancedFreeFireOptimization(true)
        assertEquals(original.camera.copy(enhancedFreeFireOptimization=true),draft.value.camera)
        assertTrue(draft.save().camera.enhancedFreeFireOptimization)
        assertSame(original,draft.cancel());assertFalse(original.camera.enhancedFreeFireOptimization)
    }
}
