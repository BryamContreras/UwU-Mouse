package dev.uwumouse.app.profile

import java.util.UUID

/** Storage is invoked only by explicit repository operations, never by the prepared engine. */
interface ProfileStorage {
    fun read(): String?
    /** Must either commit the complete document or retain the previous complete document. */
    fun write(document: String)
}

data class ProfileImportResult(val importedProfiles: List<GameProfile>, val selectedProfileId: String?, val profileCount: Int)

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

    @Synchronized fun duplicate(id: String): GameProfile {
        load()
        val original = requireNotNull(profiles[id]) { "Perfil no encontrado" }
        // Profile activation keys must be unique within the same game and display scope.
        val duplicate = original.copy(
            id = newProfileId(profiles.keys),
            name = availableCopyName(original, profiles.values),
            switchKey = 0,
            additionalActions = original.additionalActions.toList(),
            macros = original.macros.map { it.copy(steps = it.steps.toList()) },
        )
        val next = LinkedHashMap(profiles)
        next[duplicate.id] = duplicate
        commit(next, selectedId)
        return duplicate
    }

    /** Merge a complete backup in one commit, giving each colliding profile an independent ID. */
    @Synchronized fun importDocument(document: ProfileDocument, selectImported: Boolean = false): ProfileImportResult {
        require(document.profiles.isNotEmpty()) { "El documento no contiene perfiles" }
        ProfileCodec.encodeDocument(document) // Validate the complete incoming document before changing anything.
        if (selectImported) require(document.selectedProfileId != null) { "La importación necesita un perfil seleccionado" }
        load()
        val next = LinkedHashMap(profiles)
        val occupiedIds = profiles.keys.toMutableSet().apply { document.profiles.forEach { add(it.id) } }
        val remappedIds = mutableMapOf<String, String>()
        val imported = document.profiles.map { incoming ->
            val profile = if (incoming.id in profiles) {
                val newId = newProfileId(occupiedIds)
                occupiedIds.add(newId)
                remappedIds[incoming.id] = newId
                incoming.copy(
                    id = newId,
                    name = availableCopyName(incoming, next.values + document.profiles),
                    switchKey = if (activationKeyConflicts(incoming, profiles.values)) 0 else incoming.switchKey,
                )
            } else incoming
            next[profile.id] = profile
            profile
        }
        val selection = if (selectImported) requireNotNull(document.selectedProfileId).let { remappedIds[it] ?: it }
            else selectedId
        commit(next, selection) // Also validates combined limits and same-game shortcut conflicts before writing.
        return ProfileImportResult(imported, selection, next.size)
    }

    /** Deletion and an optional same-game selection replacement share one atomic document. */
    @Synchronized fun delete(id: String, replacementId: String? = null) {
        load()
        val removed = profiles[id] ?: return
        val next = LinkedHashMap(profiles)
        next.remove(id)
        if (replacementId != null) {
            val replacement = requireNotNull(next[replacementId]) { "Perfil de reemplazo inexistente" }
            require(replacement.packageName == removed.packageName) { "El perfil de reemplazo debe ser del mismo juego" }
            require(replacement.calibration.displayScope == removed.calibration.displayScope) { "El perfil de reemplazo debe ser de la misma pantalla" }
        }
        commit(next, if (selectedId == id) replacementId else selectedId)
    }

    @Synchronized fun select(id: String?) {
        load()
        require(id == null || id in profiles) { "Perfil seleccionado inexistente" }
        if (id != selectedId) commit(profiles, id)
    }

    private fun newProfileId(occupied: Set<String>): String = generateSequence { UUID.randomUUID().toString() }
        .first { it !in occupied }

    private fun availableCopyName(original: GameProfile, existing: Collection<GameProfile>): String {
        val names = existing.filter { it.packageName == original.packageName }.map { it.name.lowercase() }.toSet()
        val base = original.name.trim()
        return generateSequence(1) { it + 1 }.map { number ->
            val suffix = if (number == 1) " (copia)" else " (copia $number)"
            val truncated = base.take(GameProfile.MAX_NAME_LENGTH - suffix.length)
                .trimEnd { it.isWhitespace() || it.isHighSurrogate() }
            "$truncated$suffix"
        }.first { it.lowercase() !in names }
    }

    private fun activationKeyConflicts(incoming: GameProfile, existing: Collection<GameProfile>): Boolean =
        incoming.switchKey != 0 && existing.any { profile ->
            profile.packageName == incoming.packageName &&
                profile.calibration.displayScope == incoming.calibration.displayScope &&
                (profile.switchKey == incoming.switchKey || incoming.switchKey in ProfileSwitchKeys.mappedKeys(profile))
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
