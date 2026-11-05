package dev.mobimapper.mobimapper.profile

/** Control-plane validation for the whole game library, also used when editing an old profile. */
object ProfileSwitchKeys {
    fun mappedKeys(profile: GameProfile): Set<Int> = buildSet {
        if (profile.joystick.enabled) with(profile.joystick) { addAll(listOf(upKey, downKey, leftKey, rightKey)) }
        if (profile.camera.enabled) { add(profile.shortcuts.cameraToggle); add(profile.camera.suspendKey) }
        add(profile.shortcuts.stop); add(profile.shortcuts.edit)
        addAll(profile.actions.filter { it.triggerKind == "key" }.map { it.code })
        addAll(profile.macros.filter { it.triggerKind == "key" }.map { it.code })
        remove(0)
    }

    fun requireValid(profiles: Collection<GameProfile>) {
        for (game in profiles.groupBy { it.packageName }.values) {
            val assigned = game.filter { it.switchKey != 0 }
            require(assigned.map { it.switchKey }.distinct().size == assigned.size) { "Dos perfiles usan la misma tecla" }
            val occupied = game.flatMap { mappedKeys(it) }.toSet()
            require(assigned.none { it.switchKey in occupied }) { "La tecla de perfil ya está asignada en un mapeo de este juego" }
        }
    }
}
