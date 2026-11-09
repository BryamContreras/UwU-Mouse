package dev.uwumouse.app.profile

import dev.uwumouse.app.editor.EditorDraft
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DisplayCalibrationTest {
    private val frame=GameArea(100f,50f,1800f,1000f)
    private fun profile()=GameProfile.create("game.target","Personal",1920,1080,1).let { it.copy(
        id="personal",calibration=it.calibration.copy(area=GameArea(150f,100f,1750f,950f),confirmed=true),
        camera=it.camera.copy(sensitivityX=.35f,sensitivityY=.9f,updateRateHz=120,precisionAim=true,suspendKey=130),
        additionalActions=listOf(ActionSpec(.8f,.6f,"mouse",1),ActionSpec(.7f,.5f,code=35,behavior=ActionSpec.REPEAT,repeatIntervalMs=75)),
        macros=listOf(MacroSpec("macro","Curación",.6f,.5f,code=39,steps=listOf(MacroStep(.3f,.4f,150),MacroStep(.6f,.7f,5)))),
        switchKey=90)
    }
    private fun external()=profile().let { it.copy(id="dex",calibration=it.calibration.copy(displayScope="external",windowFrame=frame)) }
    private fun rejects(action:()->Unit) { try { action();fail("Expected rejection") } catch(_:IllegalArgumentException) { } }
    private class Storage:ProfileStorage {
        var text:String?=null;var writes=0
        override fun read()=text
        override fun write(document:String) { text=document;writes++ }
    }

    @Test fun schemaEightMigrationPreservesEveryMappingAndSelectionWithoutWriting() {
        val expected=profile()
        val row=ProfileCodec.toJson(expected).put("schemaVersion",8).apply {
            getJSONObject("calibration").apply { remove("displayScope");remove("windowFrame") }
        }
        val storage=Storage().apply { text=JSONObject().put("schemaVersion",8).put("profiles",JSONArray().put(row))
            .put("selectedProfileId",expected.id).toString() }
        val old=storage.text;val repository=ProfileRepository(storage)
        assertEquals(expected,repository.selected());assertEquals(0,storage.writes);assertEquals(old,storage.text)
        repository.save(requireNotNull(repository.selected()))
        val saved=JSONObject(storage.text!!).getJSONArray("profiles").getJSONObject(0)
        assertEquals(9,saved.getInt("schemaVersion"));assertEquals("phone",saved.getJSONObject("calibration").getString("displayScope"))
        assertTrue(saved.getJSONObject("calibration").has("windowFrame"));assertTrue(saved.getJSONObject("calibration").isNull("windowFrame"))
        assertEquals(expected,ProfileRepository(storage).selected())
    }

    @Test fun legacySchemasCannotOptIntoExternalCalibrationWithUnversionedFields() {
        for(version in 1..8) {
            val row=ProfileCodec.toJson(profile()).put("schemaVersion",version).apply {
                getJSONObject("calibration").put("displayScope","external").put("windowFrame","ignored")
            }
            val migrated=ProfileCodec.decode(row.toString())
            assertEquals("phone",migrated.calibration.displayScope);assertNull(migrated.calibration.windowFrame)
        }
    }

    @Test fun schemaNineRequiresTypedScopeAndAnExplicitNullableWindowFrame() {
        for(bad in listOf<Any>(JSONObject.NULL,true,1,"desktop","")) {
            rejects { ProfileCodec.decode(ProfileCodec.toJson(profile()).apply { getJSONObject("calibration").put("displayScope",bad) }.toString()) }
        }
        for(key in listOf("displayScope","windowFrame")) {
            rejects { ProfileCodec.decode(ProfileCodec.toJson(profile()).apply { getJSONObject("calibration").remove(key) }.toString()) }
        }
        for(bad in listOf<Any>(true,1,"frame",JSONArray())) {
            rejects { ProfileCodec.decode(ProfileCodec.toJson(profile()).apply { getJSONObject("calibration").put("windowFrame",bad) }.toString()) }
        }
    }

    @Test fun externalConfirmedCalibrationNeedsAFiniteContainingFrameInsideTheDisplay() {
        val p=external()
        assertEquals(p,ProfileCodec.decode(ProfileCodec.encode(p)))
        rejects { ProfileValidator.requireValid(p.copy(calibration=p.calibration.copy(windowFrame=null))) }
        val invalid=listOf(frame.copy(left=Float.NaN),frame.copy(left=-1f),frame.copy(top=-1f),
            frame.copy(right=1921f),frame.copy(bottom=1081f),frame.copy(right=120f),frame.copy(bottom=70f),
            frame.copy(left=160f),frame.copy(top=110f),frame.copy(right=1740f),frame.copy(bottom=940f))
        for(bad in invalid) rejects { ProfileValidator.requireValid(p.copy(calibration=p.calibration.copy(windowFrame=bad))) }
        assertNotNull(ProfileValidator.requireValid(p.copy(calibration=p.calibration.copy(confirmed=false,windowFrame=null))))
    }

    @Test fun compilerRejectsMovedWindowEvenWhenTheAreaStillFitsAndPhoneLegacyStaysCompatible() {
        val p=external()
        assertNotNull(ProfileCompiler.prepare(p,1920,1080,1,"external",frame))
        val moved=frame.copy(left=110f,right=1810f)
        assertTrue(p.calibration.area.left>=moved.left && p.calibration.area.right<=moved.right)
        rejects { ProfileCompiler.prepare(p,1920,1080,1,"external",moved) }
        rejects { ProfileCompiler.prepare(p,1920,1080,1,"external") }
        rejects { ProfileCompiler.prepare(p,1920,1080,1,"phone",frame) }
        rejects { ProfileCompiler.prepare(profile(),1920,1080,1,"external",frame) }
        assertNotNull(ProfileCompiler.prepare(profile(),1920,1080,1))
        assertNotNull(ProfileCompiler.prepare(profile(),1920,1080,1,"phone",frame))
    }

    @Test fun editingRetainsScopeAndFrameAndDoesNotReuseAreaAfterTheWindowMoves() {
        val p=external()
        val unchanged=EditorDraft(p,1920,1080,1)
        assertEquals(p.calibration.area,unchanged.value.calibration.area)
        assertEquals(p,unchanged.save())
        val moved=frame.copy(left=110f,right=1810f)
        val edited=EditorDraft(p,1920,1080,1,suggestedArea=moved,displayScope="external",windowFrame=moved)
        assertEquals(moved,edited.value.calibration.area);assertEquals(moved,edited.save().calibration.windowFrame)
        assertEquals("external",edited.save().calibration.displayScope)
        rejects { edited.replace(edited.value.copy(calibration=edited.value.calibration.copy(displayScope="phone"))) }
        rejects { edited.replace(edited.value.copy(calibration=edited.value.calibration.copy(windowFrame=frame))) }
        assertSame(p,edited.cancel())
    }

    @Test fun profileSwitchKeysAreIndependentBetweenPhoneAndExternalButStillConflictWithinEach() {
        val phone=profile();val dex=external()
        assertEquals(listOf(phone,dex),ProfileCodec.decodeDocument(ProfileCodec.encodeDocument(ProfileDocument(listOf(phone,dex),phone.id))).profiles)
        val mapped=dex.copy(id="mapped",switchKey=0,action=dex.action.copy(code=90))
        assertNotNull(ProfileCodec.encodeDocument(ProfileDocument(listOf(phone,mapped),phone.id)))
        rejects { ProfileCodec.encodeDocument(ProfileDocument(listOf(dex,mapped),dex.id)) }
    }
}
