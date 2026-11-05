package dev.mobimapper.mobimapper.profile

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProfileRepositoryTest {
    @Test fun legacyVersionsMigrateOnlyNewMetadataAndCommitCurrentSchemaOnlyOnExplicitSave() {
        for((version,mode) in listOf(1 to CameraSpec.BOUNDED,2 to CameraSpec.BOUNDED,
            3 to CameraSpec.BOUNDED,3 to CameraSpec.CONTINUOUS)) {
            val p=profile().let { it.copy(
                calibration=it.calibration.copy(area=GameArea(75f,0f,2400f,1080f),confirmed=true),
                camera=it.camera.copy(mode=mode,sensitivityX=.75f,sensitivityY=1.25f),
                shortcuts=SessionShortcuts(135,0,0),
                additionalActions=if(version==1) emptyList() else listOf(ActionSpec(.8f,.6f,"mouse",1))) }
            val row=legacyRow(p,version)
            val storage=Storage().apply { document=JSONObject().put("schemaVersion",version)
                .put("profiles",org.json.JSONArray().put(row)).put("selectedProfileId",p.id).toString() }
            val originalBytes=storage.document
            val repository=ProfileRepository(storage)
            val migrated=requireNotNull(repository.selected())
            assertEquals(GameProfile.SCHEMA_VERSION,migrated.schemaVersion)
            assertEquals(mode,migrated.camera.mode)
            assertEquals(0,migrated.camera.updateRateHz)
            assertEquals(52f,migrated.camera.markerSizeDp,0f)
            assertTrue(migrated.actions.all { it.markerSizeDp==52f })
            assertEquals(p,migrated)
            assertEquals(0,storage.writes)
            assertEquals(originalBytes,storage.document)
            repository.save(migrated)
            val committed=JSONObject(storage.document!!)
            assertEquals(GameProfile.SCHEMA_VERSION,committed.getInt("schemaVersion"))
            val camera=committed.getJSONArray("profiles").getJSONObject(0).getJSONObject("camera")
            assertEquals(mode,camera.getString("mode"))
            assertEquals(0,camera.getInt("updateRateHz"))
            assertEquals(52.0,camera.getDouble("markerSizeDp"),0.0)
            assertEquals(migrated,ProfileRepository(storage).selected())
        }
    }

    @Test fun multipleActionsRoundTripAndConflictingAdditionalBindingDoesNotReplaceSavedDocument() {
        val p=profile().copy(additionalActions=listOf(ActionSpec(.6f,.7f,"key",35),ActionSpec(.8f,.6f,"mouse",1)))
        val storage=Storage(); val store=ProfileRepository(storage); store.save(p)
        assertEquals(p,ProfileRepository(storage).selected())
        val bytes=storage.document
        rejects { store.save(p.copy(additionalActions=p.additionalActions + p.additionalActions.first())) }
        assertEquals(bytes,storage.document)
        assertEquals(p,store.selected())
    }
    private class Storage : ProfileStorage {
        var document: String? = null
        var reads = 0
        var writes = 0
        var failWrite = false
        override fun read(): String? { reads++; return document }
        override fun write(document: String) {
            if (failWrite) throw IOException("Simulated atomic commit failure")
            this.document = document
            writes++
        }
    }
    private fun profile(id: String = "profile-a") = GameProfile.create("com.dts.freefireth", "Entrenamiento", 2400, 1080, 1).copy(id = id)
    private fun legacyRow(profile: GameProfile,version: Int): JSONObject = ProfileCodec.toJson(profile).put("schemaVersion",version).apply {
        getJSONObject("camera").apply { remove("precisionAim");remove("updateRateHz");remove("markerSizeDp");if(version<3) remove("mode") }
        getJSONObject("action").remove("markerSizeDp")
        if(version==1) remove("additionalActions") else getJSONArray("additionalActions").let { rows ->
            for(index in 0 until rows.length()) rows.getJSONObject(index).remove("markerSizeDp")
        }
    }
    private fun rejects(operation: () -> Unit) {
        try { operation(); fail("Expected invalid document to be rejected") } catch (_: IllegalArgumentException) { }
    }

    @Test fun codecRoundTripKeepsCalibrationBindingsAndFractionalSensitivity() {
        val p = profile().let { it.copy(
            calibration = it.calibration.copy(area = GameArea(10f, 20f, 2390f, 1060f), confirmed = true),
            joystick = it.joystick.copy(upKey = 37, downKey = 39, leftKey = 38, rightKey = 40),
            action = it.action.copy(triggerKind = "mouse", code = 4),
            camera = it.camera.copy(sensitivityX = .75f, sensitivityY = 1.5f)) }
        for(mode in listOf(CameraSpec.BOUNDED,CameraSpec.CONTINUOUS)) {
            val chosen=p.copy(camera=p.camera.copy(mode=mode))
            assertEquals(chosen, ProfileCodec.decode(ProfileCodec.encode(chosen)))
        }
    }

    @Test fun schemaFourMigrationPreservesAllControlsAndKeepsPrecisionOffUntilExplicitSave() {
        val p=profile().copy(camera=profile().camera.copy(updateRateHz=144,markerSizeDp=38f),
            additionalActions=listOf(ActionSpec(.8f,.6f,"mouse",1,28f),ActionSpec(.7f,.5f,"mouse",2,32f)))
        val row=ProfileCodec.toJson(p).put("schemaVersion",4).apply { getJSONObject("camera").put("precisionAim",true) }
        val storage=Storage().apply { document=JSONObject().put("schemaVersion",4)
            .put("profiles",org.json.JSONArray().put(row)).put("selectedProfileId",p.id).toString() }
        val bytes=storage.document;val repository=ProfileRepository(storage)
        assertEquals(p,repository.selected())
        assertFalse(requireNotNull(repository.selected()).camera.precisionAim)
        assertEquals(bytes,storage.document);assertEquals(0,storage.writes)
        repository.save(requireNotNull(repository.selected()))
        assertEquals(GameProfile.SCHEMA_VERSION,JSONObject(storage.document!!).getInt("schemaVersion"))
        assertEquals(p,ProfileRepository(storage).selected())
    }

    @Test fun precisionIsStrictBooleanPersistsAndCannotLeakFromAFailedCommit() {
        val p=profile()
        val missing=ProfileCodec.toJson(p).apply { getJSONObject("camera").remove("precisionAim") }
        rejects { ProfileCodec.decode(missing.toString()) }
        for(value in listOf<Any>(JSONObject.NULL,1,"true")) {
            val invalid=ProfileCodec.toJson(p).apply { getJSONObject("camera").put("precisionAim",value) }
            rejects { ProfileCodec.decode(invalid.toString()) }
        }
        val chosen=p.copy(camera=p.camera.copy(precisionAim=true))
        val storage=Storage();val repository=ProfileRepository(storage)
        repository.save(chosen)
        assertEquals(chosen,ProfileRepository(storage).selected())
        val bytes=storage.document;storage.failWrite=true
        try { repository.save(p);fail("Expected failed commit") } catch(_:IOException) { }
        assertEquals(bytes,storage.document)
        assertEquals(chosen,repository.selected())
        assertEquals(chosen,ProfileRepository(storage).selected())
    }

    @Test fun fractionalIntegerStringNumberAndContradictoryOrientationAreNotCoerced() {
        val source = JSONObject(ProfileCodec.encode(profile()))
        source.getJSONObject("calibration").put("rotation", 1.5)
        rejects { ProfileCodec.decode(source.toString()) }
        val stringNumber = JSONObject(ProfileCodec.encode(profile()))
        stringNumber.getJSONObject("joystick").put("upKey", "51")
        rejects { ProfileCodec.decode(stringNumber.toString()) }
        val orientation = JSONObject(ProfileCodec.encode(profile())).put("orientation", "portrait")
        rejects { ProfileCodec.decode(orientation.toString()) }
    }

    @Test fun savedProfileAndSelectionSurviveRepositoryRecreationAndModification() {
        val storage = Storage()
        val first = ProfileRepository(storage)
        val p = profile()
        first.save(p)
        val restarted = ProfileRepository(storage)
        assertEquals(p, restarted.selected())
        val modified = p.copy(name = "Sesión local", action = p.action.copy(x = .77f, code = 35))
        restarted.save(modified)
        val again = ProfileRepository(storage)
        assertEquals(modified, again.get(p.id))
        assertEquals(modified, again.selected())
        assertEquals(1, again.list().size)
    }

    @Test fun failedWriteDoesNotPublishNewProfileOrReplaceTheOldDocument() {
        val storage = Storage()
        val store = ProfileRepository(storage)
        val p = profile()
        store.save(p)
        val previousBytes = storage.document
        storage.failWrite = true
        try { store.save(p.copy(name = "Not committed",camera=p.camera.copy(mode=CameraSpec.BOUNDED,updateRateHz=120,markerSizeDp=80f))); fail("Expected storage error") } catch (_: IOException) { }
        assertEquals(p, store.selected())
        assertEquals(previousBytes, storage.document)
        assertEquals(p, ProfileRepository(storage).selected())
    }

    @Test fun customSessionShortcutsSurviveRestartAndPreparationWithoutChangingControls() {
        val storage = Storage()
        val original = profile().let { it.copy(calibration=it.calibration.copy(confirmed=true)) }
        val chosen = original.copy(shortcuts=SessionShortcuts(cameraToggle=57,stop=123,edit=136))
        ProfileRepository(storage).save(chosen)
        val restored = requireNotNull(ProfileRepository(storage).selected())
        val prepared = ProfileCompiler.prepare(restored,2400,1080,1)
        assertEquals(chosen.shortcuts,prepared.profile.shortcuts)
        assertEquals(original.joystick,restored.joystick)
        assertEquals(original.camera,restored.camera)
        assertEquals(original.action,restored.action)
        assertEquals(1,storage.writes)
    }

    @Test fun selectionAndDeletionAreCommittedTogetherAndRejectUnknownIds() {
        val storage = Storage()
        val store = ProfileRepository(storage)
        val a = profile("a"); val b = profile("b")
        store.save(a); store.save(b)
        store.select(a.id)
        assertEquals(a, ProfileRepository(storage).selected())
        store.delete(a.id)
        val restarted = ProfileRepository(storage)
        assertNull(restarted.selected())
        assertEquals(listOf(b), restarted.list())
        rejects { restarted.select("absent") }
    }

    @Test fun repeatedCachedQueriesDoNotReadStorageOrWriteDuringSessionPreparation() {
        val storage = Storage()
        val repository = ProfileRepository(storage)
        val p = profile()
        repository.save(p)
        repeat(1000) {
            assertEquals(p, repository.get(p.id))
            assertEquals(p, repository.selected())
            assertEquals(1, repository.list().size)
        }
        assertEquals(1, storage.reads)
        assertEquals(1, storage.writes)
    }

    @Test fun corruptUnsupportedOrDuplicatedDocumentsNeverSilentlyResetToAnEmptyStore() {
        val storage = Storage().apply { document = "{invalid" }
        val repository = ProfileRepository(storage)
        rejects { repository.list() }
        rejects { repository.save(profile()) }
        assertEquals("{invalid", storage.document)
        assertEquals(0, storage.writes)
        rejects { ProfileCodec.decodeDocument(JSONObject().put("schemaVersion",GameProfile.SCHEMA_VERSION+1)
            .put("profiles",org.json.JSONArray()).put("selectedProfileId",JSONObject.NULL).toString()) }
        val p = profile()
        rejects { ProfileCodec.encodeDocument(ProfileDocument(listOf(p, p), p.id)) }
        rejects { ProfileCodec.encodeDocument(ProfileDocument(listOf(p), "absent")) }
    }

    @Test fun incompleteProfileAndOverlargeDocumentAreRejected() {
        val objectWithMissingField = JSONObject(ProfileCodec.encode(profile()))
        objectWithMissingField.remove("shortcuts")
        rejects { ProfileCodec.decode(objectWithMissingField.toString()) }
        rejects { ProfileCodec.decodeDocument(" ".repeat(ProfileCodec.MAX_DOCUMENT_CHARS + 1)) }
    }

    @Test fun schemaThreeAndFourRequireAnExplicitValidCameraMode() {
        for(version in listOf(3,4)) {
            val missing=if(version==3) legacyRow(profile(),3) else ProfileCodec.toJson(profile())
            missing.getJSONObject("camera").remove("mode")
            rejects { ProfileCodec.decode(missing.toString()) }
            rejects { ProfileCodec.decodeDocument(JSONObject().put("schemaVersion",version)
                .put("profiles",org.json.JSONArray().put(missing)).put("selectedProfileId",profile().id).toString()) }
            for(mode in listOf<Any>("unknown", "Continuous", true, 1, JSONObject.NULL)) {
                val invalid=if(version==3) legacyRow(profile(),3) else ProfileCodec.toJson(profile())
                invalid.getJSONObject("camera").put("mode",mode)
                rejects { ProfileCodec.decode(invalid.toString()) }
            }
        }
    }

    @Test fun legacyPayloadCannotEnableContinuousByAddingAnUnversionedField() {
        for(version in listOf(1,2)) {
            val legacy=legacyRow(profile(),version).apply {
                getJSONObject("camera").put("mode",CameraSpec.CONTINUOUS)
                if(version==1) remove("additionalActions")
            }
            val restored=ProfileCodec.decode(legacy.toString())
            assertEquals(CameraSpec.BOUNDED,restored.camera.mode)
            assertEquals(profile().calibration,restored.calibration)
            assertEquals(profile().shortcuts,restored.shortcuts)
        }
    }

    @Test fun explicitCameraModeSaveSurvivesRestartWithoutChangingAnyOtherProfileData() {
        val storage=Storage();val store=ProfileRepository(storage)
        val original=profile().let { it.copy(calibration=it.calibration.copy(confirmed=true),camera=it.camera.copy(mode=CameraSpec.BOUNDED),
            shortcuts=SessionShortcuts(135,0,0),additionalActions=listOf(ActionSpec(.8f,.6f,"mouse",1))) }
        store.save(original)
        val changed=original.copy(camera=original.camera.copy(mode=CameraSpec.CONTINUOUS))
        store.save(changed)
        val restored=requireNotNull(ProfileRepository(storage).selected())
        assertEquals(changed,restored)
        assertEquals(original,restored.copy(camera=restored.camera.copy(mode=CameraSpec.BOUNDED)))
        assertTrue(ProfileCompiler.prepare(restored,2400,1080,1).bindings.continuousCamera)
        val bytes=storage.document
        rejects { store.save(restored.copy(camera=restored.camera.copy(mode="unsupported"))) }
        assertEquals(bytes,storage.document)
        assertEquals(changed,ProfileRepository(storage).selected())
        assertEquals(2,storage.writes)
    }

    @Test fun schemaFourRateAndAllMarkerFieldsAreRequiredWithoutNumericCoercion() {
        for(field in listOf("mode","updateRateHz","markerSizeDp")) {
            val missing=ProfileCodec.toJson(profile()).apply { getJSONObject("camera").remove(field) }
            rejects { ProfileCodec.decode(missing.toString()) }
        }
        val missingAction=ProfileCodec.toJson(profile()).apply { getJSONObject("action").remove("markerSizeDp") }
        rejects { ProfileCodec.decode(missingAction.toString()) }
        val missingExtra=ProfileCodec.toJson(profile().copy(additionalActions=listOf(ActionSpec(.5f,.6f,"mouse",2)))).apply {
            getJSONArray("additionalActions").getJSONObject(0).remove("markerSizeDp")
        }
        rejects { ProfileCodec.decode(missingExtra.toString()) }
        for(value in listOf<Any>("120",60.5,59,JSONObject.NULL)) {
            val invalid=ProfileCodec.toJson(profile()).apply { getJSONObject("camera").put("updateRateHz",value) }
            rejects { ProfileCodec.decode(invalid.toString()) }
        }
        for(value in listOf<Any>("52",true,23,121,JSONObject.NULL)) {
            val invalid=ProfileCodec.toJson(profile()).apply { getJSONObject("action").put("markerSizeDp",value) }
            rejects { ProfileCodec.decode(invalid.toString()) }
        }
    }

    @Test fun cameraRateAndVisualSizesSurviveSaveReopenWithoutMovingCoordinatesOrBindings() {
        val storage=Storage()
        val original=profile().let { it.copy(calibration=it.calibration.copy(confirmed=true),
            additionalActions=listOf(ActionSpec(.8f,.6f,"mouse",1),ActionSpec(.7f,.5f,"mouse",2))) }
        val changed=original.copy(camera=original.camera.copy(updateRateHz=144,markerSizeDp=80f),
            action=original.action.copy(markerSizeDp=32f),additionalActions=original.additionalActions.map { it.copy(markerSizeDp=100f) })
        ProfileRepository(storage).save(changed)
        val restored=requireNotNull(ProfileRepository(storage).selected())
        assertEquals(changed,restored)
        assertEquals(ProfileCompiler.prepare(original,2400,1080,1).geometry,ProfileCompiler.prepare(restored,2400,1080,1).geometry)
        assertEquals(original.shortcuts,restored.shortcuts)
        assertEquals(original.joystick,restored.joystick)
        assertEquals(1,storage.writes)
    }

    @Test fun legacyPayloadMetadataCannotOptIntoAnUnversionedRateOrMarkerSize() {
        for(version in listOf(1,2,3)) {
            val wire=legacyRow(profile(),version).apply {
                getJSONObject("camera").put("updateRateHz",240).put("markerSizeDp",100f)
                getJSONObject("action").put("markerSizeDp",100f)
            }
            val restored=ProfileCodec.decode(wire.toString())
            assertEquals(if(version<3) CameraSpec.BOUNDED else CameraSpec.CONTINUOUS,restored.camera.mode)
            assertEquals(0,restored.camera.updateRateHz)
            assertEquals(52f,restored.camera.markerSizeDp,0f)
            assertTrue(restored.actions.all { it.markerSizeDp==52f })
        }
    }

    @Test fun twentyFourConfiguredActionsRoundTripAndAnInvalidTwentyFifthCannotReplaceTheStore() {
        val original=profile().copy(additionalActions=(100..122).map { ActionSpec(.5f,.6f,"key",it,64f) })
        val storage=Storage();val repository=ProfileRepository(storage)
        repository.save(original)
        val bytes=storage.document
        assertEquals(original,ProfileRepository(storage).selected())
        rejects { repository.save(original.copy(additionalActions=original.additionalActions+ActionSpec(.5f,.6f,"key",123))) }
        assertEquals(bytes,storage.document)
        assertEquals(original,ProfileRepository(storage).selected())
    }

    @Test fun schemaFiveMigrationAddsOnlyDisabledSuspendAndEmptyMacrosWithoutWriting() {
        val p = profile().let { it.copy(camera=it.camera.copy(mode=CameraSpec.BOUNDED,precisionAim=true,
            sensitivityX=.3f,sensitivityY=2f,updateRateHz=144,markerSizeDp=36f),
            additionalActions=listOf(ActionSpec(.75f,.6f,"mouse",1,32f)),shortcuts=SessionShortcuts(135,0,0)) }
        for (spoof in listOf(false,true)) {
            val row=ProfileCodec.toJson(p).put("schemaVersion",5).apply {
                if(spoof) {
                    getJSONObject("camera").put("suspendKey",57)
                    put("macros",org.json.JSONArray().put("unversioned data must be ignored"))
                } else { getJSONObject("camera").remove("suspendKey");remove("macros") }
            }
            val storage=Storage().apply { document=JSONObject().put("schemaVersion",5)
                .put("profiles",org.json.JSONArray().put(row)).put("selectedProfileId",p.id).toString() }
            val bytes=storage.document
            val repository=ProfileRepository(storage)
            assertEquals(p,repository.selected())
            assertEquals(0,storage.writes);assertEquals(bytes,storage.document)
            repository.save(requireNotNull(repository.selected()))
            val committed=JSONObject(storage.document!!).getJSONArray("profiles").getJSONObject(0)
            assertEquals(GameProfile.SCHEMA_VERSION,committed.getInt("schemaVersion"))
            assertEquals(0,committed.getJSONObject("camera").getInt("suspendKey"))
            assertEquals(0,committed.getJSONArray("macros").length())
            assertEquals(p,ProfileRepository(storage).selected())
        }
    }

    @Test fun schemaSixRequiresTypedSuspendAndMacroFieldsAndPersistsSequenceAtomically() {
        val p=profile().let { it.copy(camera=it.camera.copy(suspendKey=57),macros=listOf(
            MacroSpec("macro-1","Inventario",.6f,.7f,code=35,steps=listOf(MacroStep(.3f,.4f,0),MacroStep(.8f,.2f,250))))) }
        assertEquals(p,ProfileCodec.decode(ProfileCodec.encode(p)))
        val storage=Storage();val repository=ProfileRepository(storage);repository.save(p)
        assertEquals(p,ProfileRepository(storage).selected())
        val bytes=storage.document
        val conflicting=p.copy(macros=p.macros.map { it.copy(code=p.joystick.upKey) })
        rejects { repository.save(conflicting) }
        assertEquals(bytes,storage.document);assertEquals(p,repository.selected())
        storage.failWrite=true
        try { repository.save(p.copy(macros=emptyList()));fail("Expected failed atomic commit") } catch(_:IOException) { }
        assertEquals(p,repository.selected());assertEquals(p,ProfileRepository(storage).selected())
        for(field in listOf("suspendKey","macros")) {
            val missing=ProfileCodec.toJson(p).apply { if(field=="macros") remove(field) else getJSONObject("camera").remove(field) }
            rejects { ProfileCodec.decode(missing.toString()) }
        }
        for(value in listOf<Any>(JSONObject.NULL,"57",57.5,true)) {
            val invalid=ProfileCodec.toJson(p).apply { getJSONObject("camera").put("suspendKey",value) }
            rejects { ProfileCodec.decode(invalid.toString()) }
        }
        for(value in listOf<Any>(JSONObject.NULL,JSONObject(),"[]")) {
            rejects { ProfileCodec.decode(ProfileCodec.toJson(p).put("macros",value).toString()) }
        }
        for(field in listOf("id","name","x","y","triggerKind","code","markerSizeDp","steps")) {
            val missing=ProfileCodec.toJson(p).apply { getJSONArray("macros").getJSONObject(0).remove(field) }
            rejects { ProfileCodec.decode(missing.toString()) }
        }
        for(value in listOf<Any>(JSONObject.NULL,"100",1.5,true)) {
            val invalid=ProfileCodec.toJson(p).apply { getJSONArray("macros").getJSONObject(0).getJSONArray("steps").getJSONObject(0).put("waitMs",value) }
            rejects { ProfileCodec.decode(invalid.toString()) }
        }
    }

    @Test fun concurrentExplicitSavesUseOneWriterWithoutLosingAnotherProfile() {
        val storage = Storage()
        val store = ProfileRepository(storage)
        val go = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val workers = (0 until 16).map { index -> Thread {
            try { go.await(); store.save(profile("profile-$index")) } catch (e: Throwable) { failure.compareAndSet(null, e) }
        }.apply { start() } }
        go.countDown()
        workers.forEach { it.join(5000); assertFalse(it.isAlive) }
        failure.get()?.let { throw AssertionError(it) }
        val restored = ProfileRepository(storage)
        assertEquals(16, restored.list().size)
        assertNotNull(restored.selected())
        assertEquals(16, storage.writes)
    }
}
