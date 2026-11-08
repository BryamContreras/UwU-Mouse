package dev.mobimapper.mobimapper.session

import dev.mobimapper.mobimapper.profile.PreparedProfile

/** Classifies a prepared profile's inputs without capture, Android events or storage. */
object ProfileInputPolicy {
    fun consumesKey(profile: PreparedProfile, code: Int, cursorMode: Boolean,
                    pointerTransitionPending: Boolean): Boolean {
        if (code <= 0) return false
        val shortcuts = profile.profile.shortcuts
        // Session controls remain available in both pointer modes.
        if (code == shortcuts.stop || code == shortcuts.edit) return true
        if (profile.bindings.cameraMappingEnabled &&
            (code == shortcuts.cameraToggle || code == profile.profile.camera.suspendKey)) return true
        if (cursorMode && !pointerTransitionPending) {
            return profile.cursorActions.any { it.triggerKind == "key" && it.code == code }
        }
        return profile.bindings.handlesKey(code)
    }

    fun consumesButton(profile:PreparedProfile,code:Int,cursorMode:Boolean,
                       pointerTransitionPending:Boolean):Boolean {
        if(code !in listOf(1,2,4,8,16)) return false
        // Own both edges of the temporary hold in every pointer mode, including its transition.
        if(profile.bindings.cameraMappingEnabled && code==profile.profile.camera.suspendMouseButton) return true
        if(cursorMode && !pointerTransitionPending)
            return profile.cursorActions.any { it.triggerKind=="mouse" && it.code==code }
        return profile.bindings.handlesButton(code)
    }
}
