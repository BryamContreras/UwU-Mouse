package dev.mobimapper.mobimapper.session

/** Own a complete physical press across asynchronous profile/configuration and context changes. */
class ProfileSwitchGate {
    private val held = HashSet<Long>()
    val isEmpty:Boolean get()=held.isEmpty()
    fun owns(identity:Long)=identity in held
    fun claim(identity:Long) { held.add(identity) }
    fun release(identity:Long)=held.remove(identity)
    fun clear()=held.clear()
    fun removeDevice(deviceId:Int) { held.removeAll { (it shr 32).toInt()==deviceId } }
    fun press(identity:Long,code:Int,fresh:Boolean,enabled:Boolean,bindings:Map<Int,String>):String? {
        if(!fresh || !enabled || identity in held) return null
        val profile=bindings[code] ?: return null
        held.add(identity)
        return profile
    }
}
