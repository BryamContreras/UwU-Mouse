package dev.uwumouse.app.profile

import java.io.IOException
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProfileImportTest {
    private class Storage:ProfileStorage {
        var document:String?=null
        var writes=0
        var failWrite=false
        override fun read()=document
        override fun write(document:String) {
            if(failWrite) throw IOException("Disk full")
            this.document=document;writes++
        }
    }
    private fun profile(id:String)=GameProfile.create("game.target","Perfil $id",2400,1080,1).let {
        it.copy(id=id,calibration=it.calibration.copy(confirmed=true))
    }
    private fun backup():ProfileDocument {
        val first=profile("restored-a").let { it.copy(camera=it.camera.copy(precisionAim=true,sensitivityX=.3f,sensitivityY=2f),
            additionalActions=listOf(ActionSpec(.8f,.5f,"mouse",2)),
            macros=listOf(MacroSpec("macro","Secuencia",.5f,.6f,code=39,steps=listOf(MacroStep(.3f,.4f,5))))) }
        return ProfileDocument(listOf(first,first.copy(id="restored-b",name="Otro mapeo",switchKey=36,
            action=first.action.copy(x=.75f,y=.35f))),first.id)
    }
    private fun encoded(text:String)=Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
    private fun reject(action:()->Unit) { try { action();fail("Expected rejection") } catch(_:IllegalArgumentException) { } }

    @Test fun mergesTwoCompleteMappingsInOneCommitWithoutReplacingCurrentOrItsSelectionAndReopens() {
        val current=profile("current")
        val storage=Storage();val repository=ProfileRepository(storage)
        repository.save(current);val writes=storage.writes;val incoming=backup()
        val decoded=ProfileCodec.decodeImportPayload(encoded(ProfileCodec.encodeDocument(incoming)))
        val result=repository.importDocument(decoded)
        assertEquals(writes+1,storage.writes)
        assertEquals(current,repository.get(current.id));assertEquals(current,repository.selected())
        assertEquals(incoming.profiles,result.importedProfiles);assertEquals(3,result.profileCount)
        val reopened=ProfileRepository(storage)
        assertEquals(listOf(current)+incoming.profiles,reopened.list());assertEquals(current,reopened.selected())
    }

    @Test fun selectingImportedExplicitlyCommitsItsSelectionTogetherWithAllMappings() {
        val current=profile("current");val storage=Storage();val repository=ProfileRepository(storage)
        repository.save(current);val incoming=backup()
        val result=repository.importDocument(incoming,selectImported=true)
        assertEquals(incoming.selectedProfileId,result.selectedProfileId)
        assertEquals(incoming.profiles[0],ProfileRepository(storage).selected())
        assertEquals(current,ProfileRepository(storage).get(current.id))
    }

    @Test fun selectingImportedRequiresAnIncomingSelectionAndEmptyImportCannotWrite() {
        val current=profile("current");val storage=Storage();val repository=ProfileRepository(storage)
        repository.save(current);val before=storage.document;val writes=storage.writes
        reject { repository.importDocument(backup().copy(selectedProfileId=null),selectImported=true) }
        reject { repository.importDocument(ProfileDocument(emptyList(),null)) }
        assertEquals(before,storage.document);assertEquals(writes,storage.writes);assertEquals(current,repository.selected())
    }

    @Test fun existingAndIncomingDuplicateIdsRejectTheWholeImportBeforeAnyWrite() {
        val current=profile("current");val storage=Storage();val repository=ProfileRepository(storage)
        repository.save(current);val before=storage.document;val writes=storage.writes;val a=profile("a")
        reject { repository.importDocument(ProfileDocument(listOf(a,current),a.id)) }
        reject { repository.importDocument(ProfileDocument(listOf(a,a),a.id)) }
        assertEquals(before,storage.document);assertEquals(writes,storage.writes)
        assertEquals(listOf(current),repository.list())
    }

    @Test fun conflictsWithCurrentMappingsRejectEveryIncomingProfileAndPreserveSelection() {
        val current=profile("current").let { it.copy(action=it.action.copy(code=36)) }
        val storage=Storage();val repository=ProfileRepository(storage)
        repository.save(current);val before=storage.document;val writes=storage.writes
        reject { repository.importDocument(backup(),selectImported=true) }
        assertEquals(before,storage.document);assertEquals(writes,storage.writes)
        assertEquals(listOf(current),repository.list());assertEquals(current,repository.selected())
    }

    @Test fun combinedProfileLimitIsValidatedBeforeWriting() {
        val existing=(0 until ProfileCodec.MAX_PROFILES).map { profile("existing-$it") }
        val storage=Storage().apply { document=ProfileCodec.encodeDocument(ProfileDocument(existing,existing[0].id)) }
        val repository=ProfileRepository(storage);val before=storage.document
        reject { repository.importDocument(ProfileDocument(listOf(profile("one-too-many")),null)) }
        assertEquals(before,storage.document);assertEquals(0,storage.writes)
        assertEquals(existing,repository.list());assertEquals(existing[0],repository.selected())
    }

    @Test fun combinedUtf8ByteLimitRejectsUnicodeMergeWithoutChangingReloadableStorage() {
        val richMacros=(0 until 24).map { MacroSpec("macro-$it","界".repeat(80),.5f,.5f,
            code=100+it,steps=listOf(MacroStep(.5f,.5f,0))) }
        fun rich(id:String)=profile(id).let { it.copy(action=it.action.copy(code=0,enabled=false),macros=richMacros) }
        val existing=(0 until 60).map { rich("current-$it") }
        val incoming=(0 until 60).map { rich("incoming-$it") }
        val incomingDocument=ProfileDocument(incoming,incoming[0].id)
        ProfileCodec.encodeDocument(incomingDocument) // Each half fits independently in the byte-bounded store.
        val storage=Storage().apply { document=ProfileCodec.encodeDocument(ProfileDocument(existing,existing[0].id)) }
        val combined=JSONObject().put("schemaVersion",GameProfile.SCHEMA_VERSION)
            .put("profiles",JSONArray().apply { (existing+incoming).forEach { put(ProfileCodec.toJson(it)) } })
            .put("selectedProfileId",incoming[0].id).toString()
        assertTrue(combined.length < ProfileCodec.MAX_DOCUMENT_CHARS)
        assertTrue(combined.toByteArray(Charsets.UTF_8).size > ProfileCodec.MAX_DOCUMENT_CHARS)
        val before=storage.document;val repository=ProfileRepository(storage)
        reject { repository.importDocument(incomingDocument,selectImported=true) }
        assertEquals(before,storage.document);assertEquals(0,storage.writes)
        assertEquals(existing,repository.list());assertEquals(existing[0],repository.selected())
        assertEquals(existing,ProfileRepository(storage).list())
    }

    @Test fun failedAtomicStorageWriteLeavesMemoryDiskAndSelectionUnchanged() {
        val current=profile("current");val storage=Storage();val repository=ProfileRepository(storage)
        repository.save(current);val before=storage.document;val writes=storage.writes;storage.failWrite=true
        try { repository.importDocument(backup(),selectImported=true);fail("Expected write failure") } catch(_:IOException) { }
        assertEquals(before,storage.document);assertEquals(writes,storage.writes)
        assertEquals(listOf(current),repository.list());assertEquals(current,repository.selected())
        assertEquals(listOf(current),ProfileRepository(storage).list())
    }

    @Test fun importTransportRejectsMalformedOversizeUtf8AndTrailingJsonWithoutMutation() {
        val current=profile("current");val storage=Storage();val repository=ProfileRepository(storage)
        repository.save(current);val before=storage.document;val writes=storage.writes
        val valid=ProfileCodec.encodeDocument(backup())
        val bad=listOf("????", "Zh==", encoded("not-json"),encoded("[]"),encoded(valid+" {}"),
            Base64.getEncoder().encodeToString(byteArrayOf(0xc3.toByte(),0x28)),
            encoded(" ".repeat(ProfileCodec.MAX_DOCUMENT_CHARS+1)))
        for(payload in bad) reject { repository.importDocument(ProfileCodec.decodeImportPayload(payload)) }
        assertEquals(backup(),ProfileCodec.decodeImportPayload(encoded("\uFEFF"+valid)))
        assertEquals(before,storage.document);assertEquals(writes,storage.writes);assertEquals(current,repository.selected())
    }
}
