package dev.uwumouse.app.session

enum class LocalPointerTarget { NONE, MENU, BUBBLE, MONITOR }

/** Local widgets own a complete press; release clears ownership before a widget can close itself. */
class LocalPointerGestureGate {
    var owner=LocalPointerTarget.NONE
        private set
    fun press(target:LocalPointerTarget):LocalPointerTarget {
        if(owner!=LocalPointerTarget.NONE) return LocalPointerTarget.NONE
        owner=target
        return owner
    }
    fun release():LocalPointerTarget=owner.also { owner=LocalPointerTarget.NONE }
    fun cancel():LocalPointerTarget=release()
}
