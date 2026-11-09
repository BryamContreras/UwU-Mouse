package dev.uwumouse.app.session

import java.util.Collections
import java.util.Locale

/** Key declarations only. HID usage/axis declarations require metadata outside EV_KEY scan codes. */
object KeyLayoutParser {
    private val fields=Regex("\\s+")
    private val label=Regex("[A-Z0-9_]+")
    private val decimal=Regex("[0-9]+")
    private val hexadecimal=Regex("0[xX][0-9a-fA-F]+")

    fun parse(text:String):Map<Int,String> {
        val keys=LinkedHashMap<Int,String>()
        for(line in text.lineSequence()) {
            val row=line.substringBefore('#').trim()
            if(row.isEmpty()) continue
            val tokens=row.split(fields)
            if(tokens.size<3 || tokens[0]!="key" || !label.matches(tokens[2])) continue
            val token=tokens[1]
            val scan=when {
                hexadecimal.matches(token) -> token.substring(2).toLongOrNull(16)
                decimal.matches(token) -> token.toLongOrNull()
                else -> null
            } ?: continue
            if(scan !in 0L..65_535L) continue
            // Optional flags follow the key name; they are not part of the Android key label.
            keys.putIfAbsent(scan.toInt(),tokens[2])
        }
        return immutable(keys)
    }

    internal fun <K,V> immutable(source:Map<K,V>):Map<K,V> =
        Collections.unmodifiableMap(LinkedHashMap(source))
}

data class ResolvedKeyLayout(val fileName:String,val keys:Map<Int,String>)

/** Cold layout selection. The loader receives a generated basename, never an arbitrary path. */
object DeviceKeyLayoutPolicy {
    fun fileNames(vendor:Int,product:Int,deviceName:String):List<String> {
        val names=ArrayList<String>(3)
        if(vendor in 1..65_535 && product in 1..65_535)
            names+=String.format(Locale.ROOT,"Vendor_%04x_Product_%04x.kl",vendor,product)
        val safeName=buildString(deviceName.length.coerceAtMost(240)) {
            for(character in deviceName) append(if(character in '0'..'9' || character in 'a'..'z' ||
                character in 'A'..'Z' || character=='-' || character=='_') character else '_')
        }
        if(safeName.length in 1..240 && safeName!="Generic") names+="$safeName.kl"
        names+="Generic.kl"
        return Collections.unmodifiableList(names)
    }

    fun resolve(vendor:Int,product:Int,deviceName:String,generic:Map<Int,String>,
                read:(String)->String?):ResolvedKeyLayout {
        for(name in fileNames(vendor,product,deviceName)) {
            if(name=="Generic.kl") break
            val text=read(name) ?: continue
            val merged=LinkedHashMap(generic)
            merged.putAll(KeyLayoutParser.parse(text))
            return ResolvedKeyLayout(name,KeyLayoutParser.immutable(merged))
        }
        return ResolvedKeyLayout("Generic.kl",KeyLayoutParser.immutable(generic))
    }
}
