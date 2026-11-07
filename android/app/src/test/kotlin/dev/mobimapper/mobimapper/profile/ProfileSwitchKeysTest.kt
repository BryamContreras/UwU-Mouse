package dev.mobimapper.mobimapper.profile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ProfileSwitchKeysTest {
    private class Storage:ProfileStorage {
        var text:String?=null;var writes=0;var failWrite=false
        override fun read()=text
        override fun write(document:String) { if(failWrite) throw IOException("Full disk");text=document;writes++ }
    }
    private fun profile(id:String="first",game:String="game.target")=GameProfile.create(game,"Perfil $id",2400,1080,1).let {
        it.copy(id=id,calibration=it.calibration.copy(confirmed=true))
    }
    private fun rejects(action:()->Unit) { try { action();fail("Expected rejection") } catch(_:IllegalArgumentException) { } }

    @Test fun schemaSevenMigrationPreservesEveryMappingWithoutAssigningOrWritingAnUnversionedKey() {
        val original=profile().let { it.copy(camera=it.camera.copy(precisionAim=true,sensitivityX=.3f,sensitivityY=2f),
            additionalActions=listOf(ActionSpec(.2f,.3f,"mouse",2,behavior=ActionSpec.CURSOR_TOGGLE)),
            macros=listOf(MacroSpec("macro","Mochila",.5f,.4f,code=39,steps=listOf(MacroStep(.3f,.4f,5))))) }
        val row=ProfileCodec.toJson(original).put("schemaVersion",7).put("switchKey","ignored")
        val storage=Storage().apply { text=JSONObject().put("schemaVersion",7).put("profiles",JSONArray().put(row))
            .put("selectedProfileId",original.id).toString() }
        val bytes=storage.text;val repository=ProfileRepository(storage)
        assertEquals(original,repository.selected());assertEquals(0,repository.selected()!!.switchKey)
        assertEquals(bytes,storage.text);assertEquals(0,storage.writes)
        repository.save(requireNotNull(repository.selected()))
        assertEquals(GameProfile.SCHEMA_VERSION,JSONObject(storage.text!!).getInt("schemaVersion"));assertEquals(original,ProfileRepository(storage).selected())
    }

    @Test fun multipleMappingsAndProfileShortcutsRoundTripIndependently() {
        val first=profile().copy(switchKey=90)
        val second=first.copy(id="second",name="Otra mira",switchKey=91,action=first.action.copy(x=.42f,y=.24f,code=35),
            camera=first.camera.copy(sensitivityX=.8f))
        val storage=Storage();val repository=ProfileRepository(storage)
        repository.save(first);repository.save(second);repository.select(first.id)
        val restored=ProfileRepository(storage)
        assertEquals(listOf(first,second),restored.list());assertEquals(first,restored.selected())
        assertNotEquals(restored.get(first.id)!!.action,restored.get(second.id)!!.action)
        assertEquals(second,ProfileCodec.decode(ProfileCodec.encode(second)))
    }

    @Test fun anyMappingOfTheSameGameReservesItsKeyAcrossAllProfiles() {
        val shortcut=profile().copy(switchKey=90)
        val other=profile("second")
        val variants=listOf(
            other.copy(joystick=other.joystick.copy(upKey=90)),
            other.copy(shortcuts=other.shortcuts.copy(cameraToggle=90)),
            other.copy(camera=other.camera.copy(suspendKey=90)),
            other.copy(shortcuts=other.shortcuts.copy(stop=90)),
            other.copy(shortcuts=other.shortcuts.copy(edit=90)),
            other.copy(action=other.action.copy(code=90)),
            other.copy(action=other.action.copy(code=90,behavior=ActionSpec.REPEAT)),
            other.copy(action=other.action.copy(code=90,behavior=ActionSpec.CURSOR_TOGGLE)),
            other.copy(macros=listOf(MacroSpec("macro","Macro",.5f,.5f,code=90,steps=listOf(MacroStep(.5f,.5f)))))
        )
        for(mapped in variants) {
            ProfileValidator.requireValid(mapped)
            rejects { ProfileCodec.encodeDocument(ProfileDocument(listOf(shortcut,mapped),shortcut.id)) }
        }
        rejects { ProfileValidator.requireValid(other.copy(switchKey=other.joystick.upKey)) }
    }

    @Test fun duplicateShortcutIsRejectedWithinGameButAllowedInAnotherGame() {
        val first=profile().copy(switchKey=90)
        rejects { ProfileCodec.encodeDocument(ProfileDocument(listOf(first,profile("second").copy(switchKey=90)),first.id)) }
        val otherGame=profile("third","other.game").copy(switchKey=90)
        assertEquals(listOf(first,otherGame),ProfileCodec.decodeDocument(ProfileCodec.encodeDocument(ProfileDocument(listOf(first,otherGame),first.id))).profiles)
    }

    @Test fun editingMappingsCannotInvalidateExistingShortcutsAndFailedUpdateDoesNotChangeSelection() {
        val first=profile().copy(switchKey=90);val second=profile("second")
        val storage=Storage();val repository=ProfileRepository(storage)
        repository.save(first);repository.save(second);repository.select(first.id)
        val bytes=storage.text;val writes=storage.writes
        rejects { repository.update(second.copy(action=second.action.copy(code=90))) }
        assertEquals(bytes,storage.text);assertEquals(writes,storage.writes);assertEquals(first,repository.selected())
        repository.update(second.copy(switchKey=91))
        assertEquals(first,repository.selected());assertEquals(91,ProfileRepository(storage).get(second.id)!!.switchKey)
        val current=storage.text;storage.failWrite=true
        try { repository.update(second.copy(switchKey=92));fail("Expected IO failure") } catch(_:IOException) { }
        assertEquals(current,storage.text);assertEquals(91,repository.get(second.id)!!.switchKey)
        assertEquals(first,repository.selected())
    }

    @Test fun disabledControlsDoNotReserveKeysAndMissingOrMalformedSchemaEightKeyIsRejected() {
        val empty=profile().let { it.copy(joystick=it.joystick.copy(enabled=false),camera=it.camera.copy(enabled=false),
            action=it.action.copy(enabled=false,code=0),switchKey=51) }
        assertEquals(empty,ProfileCodec.decode(ProfileCodec.encode(empty)))
        for(value in listOf<Any>(JSONObject.NULL,"90",true,90.5,-1,341)) {
            rejects { ProfileCodec.decode(ProfileCodec.toJson(empty).put("switchKey",value).toString()) }
        }
        rejects { ProfileCodec.decode(ProfileCodec.toJson(empty).apply { remove("switchKey") }.toString()) }
    }
}
