package dev.mobimapper.mobimapper.profile

/** Storage is invoked only by explicit repository operations, never by the prepared engine. */
interface ProfileStorage {
    fun read(): String?
    /** Must either commit the complete document or retain the previous complete document. */
    fun write(document: String)
}

/** One serialized writer and one cached, validated source of truth per application file. */
class ProfileRepository(private val storage: ProfileStorage) {
    private var loaded = false
    private var profiles = linkedMapOf<String, GameProfile>()
    private var selectedId: String? = null

    @Synchronized fun list(): List<GameProfile> { load(); return profiles.values.toList() }
    @Synchronized fun get(id: String): GameProfile? { load(); return profiles[id] }
    @Synchronized fun selected(): GameProfile? { load(); return selectedId?.let(profiles::get) }

    /** Saving also selects that profile, committed together in the same atomic document. */
    @Synchronized fun save(profile: GameProfile) {
        ProfileValidator.requireValid(profile)
        load()
        val next = LinkedHashMap(profiles)
        next[profile.id] = profile
        commit(next, profile.id)
    }

    /** A shortcut edit on another row must not select or activate that profile. */
    @Synchronized fun update(profile: GameProfile) {
        ProfileValidator.requireValid(profile)
        load()
        require(profile.id in profiles) { "Perfil no encontrado" }
        val next = LinkedHashMap(profiles)
        next[profile.id] = profile
        commit(next, selectedId)
    }

    @Synchronized fun delete(id: String) {
        load()
        if (id !in profiles) return
        val next = LinkedHashMap(profiles)
        next.remove(id)
        commit(next, if (selectedId == id) null else selectedId)
    }

    @Synchronized fun select(id: String?) {
        load()
        require(id == null || id in profiles) { "Perfil seleccionado inexistente" }
        if (id != selectedId) commit(profiles, id)
    }

    private fun load() {
        if (loaded) return
        val text = storage.read()
        val document = if (text == null) ProfileDocument(emptyList(), null) else ProfileCodec.decodeDocument(text)
        profiles = document.profiles.associateByTo(linkedMapOf()) { it.id }
        selectedId = document.selectedProfileId
        loaded = true
    }

    private fun commit(next: Map<String, GameProfile>, selection: String?) {
        val document = ProfileCodec.encodeDocument(ProfileDocument(next.values.toList(), selection))
        storage.write(document) // Commit before publishing: a failed write never changes in-memory state.
        profiles = LinkedHashMap(next)
        selectedId = selection
    }
}
