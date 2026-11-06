package dev.mobimapper.mobimapper.profile

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** App-private AtomicFile adapter. Multiple Activities/services share the same repository cache. */
class ProfileStore(context: Context) {
    private val repository: ProfileRepository
    init {
        val file = File(context.applicationContext.filesDir, "profiles-v1.json")
        repository = synchronized(repositories) {
            repositories.getOrPut(file.absolutePath) { ProfileRepository(AtomicProfileStorage(file)) }
        }
    }
    fun list(): List<GameProfile> = repository.list()
    fun get(id: String): GameProfile? = repository.get(id)
    fun selected(): GameProfile? = repository.selected()
    fun save(profile: GameProfile) = repository.save(profile)
    fun update(profile: GameProfile) = repository.update(profile)
    fun importDocument(document: ProfileDocument, selectImported: Boolean = false) = repository.importDocument(document, selectImported)
    fun delete(id: String) = repository.delete(id)
    fun select(id: String?) = repository.select(id)

    companion object { private val repositories = mutableMapOf<String, ProfileRepository>() }
}

private class AtomicProfileStorage(private val file: File) : ProfileStorage {
    private val atomic = AtomicFile(file)
    override fun read(): String? = try {
        atomic.openRead().use { input ->
            val bytes = input.readNBytes(ProfileCodec.MAX_DOCUMENT_CHARS + 1)
            if (bytes.size > ProfileCodec.MAX_DOCUMENT_CHARS) throw IOException("Almacén de perfiles demasiado grande")
            bytes.toString(Charsets.UTF_8)
        }
    } catch (e: FileNotFoundException) {
        // An unreadable existing document is an error, never a new empty repository.
        if (file.exists() || File(file.path + ".bak").exists()) throw e
        null
    }

    override fun write(document: String) {
        val output = atomic.startWrite()
        try {
            output.write(document.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (e: Exception) {
            atomic.failWrite(output)
            throw e
        }
    }
}
