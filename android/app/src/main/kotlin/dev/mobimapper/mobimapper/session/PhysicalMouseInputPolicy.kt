package dev.mobimapper.mobimapper.session

/** Android source masks kept as integers so the hardware admission rule can be tested on the JVM. */
object PhysicalMouseInputPolicy {
    private const val SOURCE_MOUSE=0x00002002
    private const val SOURCE_MOUSE_RELATIVE=0x00020004
    private const val SOURCE_TOUCHSCREEN=0x00001002

    /** Null admits a physical mouse sample; every rejection is a constant diagnostic label. */
    fun rejectionReason(eventSources:Int,deviceSources:Int,external:Boolean,virtual:Boolean):String? {
        if(!external) return "internal"
        if(virtual) return "virtual"
        // Samsung DeX can combine MOUSE and TOUCHSCREEN. The mouse bit and a real mouse
        // device are authoritative here; the extra touchscreen bit is not a touch-only event.
        if(!containsMouse(eventSources)) return if(contains(eventSources,SOURCE_TOUCHSCREEN)) "touchscreen" else "non_mouse_source"
        if(!containsMouse(deviceSources)) return "device_no_mouse"
        return null
    }

    private fun containsMouse(sources:Int)=contains(sources,SOURCE_MOUSE) || contains(sources,SOURCE_MOUSE_RELATIVE)
    private fun contains(sources:Int,required:Int)=(sources and required)==required
}
