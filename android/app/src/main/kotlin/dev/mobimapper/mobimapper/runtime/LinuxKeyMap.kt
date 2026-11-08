package dev.mobimapper.mobimapper.runtime

import android.view.KeyEvent
import dev.mobimapper.mobimapper.session.DeviceKeyLayoutPolicy
import dev.mobimapper.mobimapper.session.KeyLayoutParser
import java.io.File
import java.util.Collections

/** Resolve installed layouts during capture configuration; key lookup performs no I/O. */
class LinuxKeyMap private constructor(private val genericNames:Map<Int,String>) {
    private val genericKeys=resolveCodes(genericNames)
    @Volatile private var deviceKeys:Map<Int,Map<Int,Int>> = emptyMap()
    @Volatile private var layouts:Map<Int,String> = emptyMap()

    fun keyCode(scanCode:Int,deviceIdMinor:Int=-1):Int =
        (deviceKeys[deviceIdMinor] ?: genericKeys)[scanCode] ?: KeyEvent.KEYCODE_UNKNOWN

    /** Identities come from the fresh native inventory, not Android InputDevice IDs. */
    fun configureDevices(paths:Array<String>,names:Array<String>,vendors:IntArray,products:IntArray):Boolean {
        if(paths.size>1_024 || names.size!=paths.size || vendors.size!=paths.size || products.size!=paths.size) {
            deviceKeys=emptyMap();layouts=emptyMap();return false
        }
        val configured=HashMap<Int,Map<Int,Int>>()
        val selected=HashMap<Int,String>()
        val cache=HashMap<Triple<Int,Int,String>,Pair<Map<Int,Int>,String>>()
        for(index in paths.indices) {
            val minor=eventNode.matchEntire(paths[index])?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val identity=Triple(vendors[index],products[index],names[index])
            val entry=cache.getOrPut(identity) {
                val layout=DeviceKeyLayoutPolicy.resolve(identity.first,identity.second,identity.third,genericNames,::readLayout)
                resolveCodes(layout.keys) to layout.fileName
            }
            configured[minor]=entry.first;selected[minor]=entry.second
        }
        deviceKeys=Collections.unmodifiableMap(configured)
        layouts=Collections.unmodifiableMap(selected)
        return true
    }

    fun diagnostics():Map<String,Any> = mapOf("configuredDevices" to layouts.size,
        "layouts" to layouts.mapKeys { it.key.toString() })

    companion object {
        private val eventNode=Regex("/dev/input/event([0-9]{1,4})")
        private val fileName=Regex("[A-Za-z0-9_-]+\\.kl")
        private val roots=listOf("/odm/usr/keylayout","/vendor/usr/keylayout",
            "/product/usr/keylayout","/system/usr/keylayout").map(::File)
        private val canonicalRoots=roots.mapNotNull { runCatching { it.canonicalFile }.getOrNull() }.toSet()
        private const val MAX_LAYOUT_BYTES=1_048_576L

        fun load():LinuxKeyMap = LinuxKeyMap(KeyLayoutParser.parse(readLayout("Generic.kl") ?: ""))

        private fun resolveCodes(names:Map<Int,String>):Map<Int,Int> {
            val resolved=HashMap<Int,Int>(names.size)
            for((scan,name) in names) {
                // Keep UNKNOWN entries: an unsupported vendor override must not become
                // an unrelated Generic binding merely because Android cannot name it.
                resolved[scan]=KeyEvent.keyCodeFromString("KEYCODE_$name")
            }
            return Collections.unmodifiableMap(resolved)
        }

        private fun readLayout(name:String):String? {
            if(!fileName.matches(name) || name.length>243) return null
            for(root in roots) {
                val text=runCatching {
                    val file=File(root,name)
                    if(!file.isFile || file.length() !in 1..MAX_LAYOUT_BYTES ||
                        file.canonicalFile.parentFile !in canonicalRoots) null else file.readText()
                }.getOrNull()
                if(text!=null) return text
            }
            return null
        }
    }
}
