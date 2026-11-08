package dev.mobimapper.mobimapper.session

/** Public InputDevice properties copied only during inventory callbacks, never from input samples. */
data class InputDeviceSnapshot(
    val descriptor: String,
    val vendorId: Int,
    val productId: Int,
    val sources: Int,
    val keyboardType: Int,
    val external: Boolean,
    val virtual: Boolean,
    val enabled: Boolean,
    val motionRanges: Set<InputDeviceMotionRange> = emptySet(),
    val supportedKeys: Set<Int> = emptySet(),
    /** Diagnostic label only; a rename cannot change mapping capabilities. */
    val name: String = "",
) {
    val physicalExternal: Boolean get() = external && !virtual
}

data class InputDeviceMotionRange(
    val axis: Int, val source: Int,
    val min: Float, val max: Float, val flat: Float, val fuzz: Float, val resolution: Float,
)

data class InputDeviceChangeDecision(val pause: Boolean, val reason: String, val changedFields: List<String>)

/** A property callback is not an unplug. Only changes to a physical peripheral invalidate mapping. */
object InputDeviceChangePolicy {
    fun evaluate(before: InputDeviceSnapshot?, after: InputDeviceSnapshot?): InputDeviceChangeDecision {
        if (before == null && after == null)
            return InputDeviceChangeDecision(true, "device_unavailable_unknown", listOf("device"))
        val changed = if (before != null && after != null) materialDifferences(before, after) else listOf("device")
        if (before?.physicalExternal != true && after?.physicalExternal != true)
            return InputDeviceChangeDecision(false, "unrelated_internal_or_virtual", changed)
        if (before == null) return InputDeviceChangeDecision(true, "external_not_in_cache", changed)
        if (after == null) return InputDeviceChangeDecision(true, "external_unavailable", changed)
        return if (changed.isEmpty()) InputDeviceChangeDecision(false, "mapping_capabilities_unchanged", emptyList())
            else InputDeviceChangeDecision(true, "external_material_change", changed)
    }

    private fun materialDifferences(before: InputDeviceSnapshot, after: InputDeviceSnapshot): List<String> = buildList {
        if (before.descriptor != after.descriptor) add("descriptor")
        if (before.vendorId != after.vendorId) add("vendorId")
        if (before.productId != after.productId) add("productId")
        if (before.sources != after.sources) add("sources")
        if (before.keyboardType != after.keyboardType) add("keyboardType")
        if (before.external != after.external) add("external")
        if (before.virtual != after.virtual) add("virtual")
        if (before.enabled != after.enabled) add("enabled")
        if (before.motionRanges != after.motionRanges) add("motionRanges")
        if (before.supportedKeys != after.supportedKeys) add("supportedKeys")
    }
}
