package dev.uwumouse.app.session

import dev.uwumouse.app.profile.PreparedProfile

/** Only ordinary held touches and joystick directions are eligible for native gameplay ingress. */
data class NativeGameplayPolicy(val keys:Set<Int>,val buttons:Int) {
    companion object {
        val NONE=NativeGameplayPolicy(emptySet(),0)
        // Android modifier key codes. These must still update the main-owned passthrough meta state.
        private val modifiers=setOf(57,58,59,60,63,113,114,117,118,119)

        fun prepare(prepared:PreparedProfile):NativeGameplayPolicy {
            val bindings=prepared.bindings
            val profile=prepared.profile
            val keys=HashSet<Int>()
            if(bindings.joystickEnabled) {
                keys.add(bindings.upKey);keys.add(bindings.downKey)
                keys.add(bindings.leftKey);keys.add(bindings.rightKey)
            }
            bindings.actionKey?.let(keys::add)
            for(touch in bindings.touches) touch.key?.let(keys::add)
            keys.removeAll(bindings.commandKeys);keys.removeAll(bindings.macroKeys)
            keys.removeAll(modifiers)
            keys.remove(profile.shortcuts.stop);keys.remove(profile.shortcuts.edit)
            keys.remove(profile.shortcuts.cameraToggle);keys.remove(profile.camera.suspendKey)
            for(action in prepared.repeats) if(action.triggerKind=="key") keys.remove(action.code)
            for(action in prepared.cursorActions) if(action.triggerKind=="key") keys.remove(action.code)
            var buttons=bindings.actionButton ?: 0
            for(touch in bindings.touches) if(!touch.mouseAim) buttons=buttons or (touch.button ?: 0)
            for(button in bindings.commandButtons+bindings.macroButtons) buttons=buttons and button.inv()
            for(action in prepared.repeats) if(action.triggerKind=="mouse") buttons=buttons and action.code.inv()
            for(action in prepared.cursorActions) if(action.triggerKind=="mouse") buttons=buttons and action.code.inv()
            buttons=buttons and profile.camera.suspendMouseButton.inv()
            prepared.fireCadence?.let { buttons=buttons and it.code.inv() }
            return NativeGameplayPolicy(keys.toSet(),buttons)
        }
    }
}
