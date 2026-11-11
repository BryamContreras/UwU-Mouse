package dev.uwumouse.windowcontext

/** Metadata only: no window titles, component names, nodes or raw dump text are retained. */
data class WindowMetadata(
    val id: Int,
    val type: Int,
    val layoutType: Int,
    val packageName: String,
    val ownerUid: Int,
    val displayId: Int,
    val layer: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val focused: Boolean,
)

data class ParsedWindowSnapshot(
    val valid: Boolean,
    val focusedPackage: String? = null,
    val focusedDisplayId: Int = -1,
    val windows: List<WindowMetadata> = emptyList(),
    val reason: String = "unavailable",
)

/**
 * WMS text is not a public API. Android 14–17 retain the consumed metadata fields;
 * only complete, recognized metadata is accepted and unknown formats fail closed.
 * Window enumeration is top-first; the larger derived layer is above a smaller layer.
 */
object WindowDumpParser {
    private const val MAX_CHARS = 4 * 1024 * 1024
    private const val MAX_WINDOWS = 512
    private val header = Regex("(?m)^  Window #(\\d+) Window\\{([0-9a-fA-F]{1,8})\\s+u\\d+\\s+[^\\r\\n]*\\}:[ \\t]*\\r?$")
    private val broadHeader = Regex("(?m)^  Window #")
    private val footer = Regex("(?m)^  mGlobalConfiguration=")
    private val auxiliaryWindows = Regex("(?m)^  Hiding System Alert Windows:[ \\t]*\\r?$")
    private val topDisplay = Regex("(?m)^  mTopFocusedDisplayId=(\\d+)[ \\t]*\\r?$")
    private val displayHeader = Regex("(?m)^  Display: mDisplayId=(\\d+)[^\\r\\n]*\\r?$")
    private val focus = Regex("(?m)^  mCurrentFocus=(null|Window\\{([0-9a-fA-F]{1,8})\\s+[^\\r\\n]*\\})[ \\t]*\\r?$")
    private val focusedApp = Regex("(?m)^  mFocusedApp=(?:null|ActivityRecord\\{[^\\r\\n]*\\})[ \\t]*\\r?$")
    private val display = Regex("\\bmDisplayId=(\\d+)\\b")
    private val owner = Regex("(?m)^    mOwnerUid=(\\d+)[^\\r\\n]*\\bpackage=([a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)*)(?=[ \\t\\r\\n]|$)")
    private val layout = Regex("\\bty=([^\\s}]+)")
    private val tokenType = Regex("\\bmToken=WindowToken\\{[^\\r\\n]*\\btype=(\\d+)\\b")
    private val frames = Regex("(?m)^\\s*Frames:[^\\r\\n]*\\bframe=\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]")
    private val legacyFrame = Regex("(?m)^\\s*mFrame=\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]")
    private val trustedOverlayFlag=Regex("\\bTRUSTED_OVERLAY\\b")
    private val nonTouchableFlag=Regex("\\bNOT_TOUCHABLE\\b")
    private val hexWindowFlags=Regex("(?m)^\\s+fl=([0-9a-fA-F]{1,8})[ \\t]*\\r?$")
    private val hexPrivateFlags=Regex("(?m)^\\s+pfl=([0-9a-fA-F]{1,8})[ \\t]*\\r?$")

    // AOSP WindowManager.LayoutParams debug labels. Unrecognized labels stay system windows.
    private val layoutLabels = mapOf(
        "BASE_APPLICATION" to 1, "APPLICATION" to 2, "APPLICATION_STARTING" to 3,
        "DRAWN_APPLICATION" to 4, "APPLICATION_PANEL" to 1000, "APPLICATION_MEDIA" to 1001,
        "APPLICATION_SUB_PANEL" to 1002, "APPLICATION_ATTACHED_DIALOG" to 1003,
        "APPLICATION_MEDIA_OVERLAY" to 1004, "APPLICATION_ABOVE_SUB_PANEL" to 1005,
        "STATUS_BAR" to 2000, "SEARCH_BAR" to 2001, "PHONE" to 2002,
        "SYSTEM_ALERT" to 2003, "KEYGUARD" to 2004, "TOAST" to 2005,
        "SYSTEM_OVERLAY" to 2006, "PRIORITY_PHONE" to 2007, "SYSTEM_DIALOG" to 2008,
        "KEYGUARD_DIALOG" to 2009, "SYSTEM_ERROR" to 2010, "INPUT_METHOD" to 2011,
        "INPUT_METHOD_DIALOG" to 2012, "WALLPAPER" to 2013, "STATUS_BAR_PANEL" to 2014,
        "SECURE_SYSTEM_OVERLAY" to 2015, "DRAG" to 2016, "STATUS_BAR_SUB_PANEL" to 2017,
        "POINTER" to 2018, "NAVIGATION_BAR" to 2019, "VOLUME_OVERLAY" to 2020,
        "BOOT_PROGRESS" to 2021, "INPUT_CONSUMER" to 2022, "NAVIGATION_BAR_PANEL" to 2024,
        "DISPLAY_OVERLAY" to 2026, "MAGNIFICATION_OVERLAY" to 2027, "PRESENTATION" to 2037,
        "PRIVATE_PRESENTATION" to 2030, "VOICE_INTERACTION" to 2031,
        "ACCESSIBILITY_OVERLAY" to 2032, "VOICE_INTERACTION_STARTING" to 2033,
        "DOCK_DIVIDER" to 2034, "QS_DIALOG" to 2035, "SCREENSHOT" to 2036,
        "APPLICATION_OVERLAY" to 2038, "ACCESSIBILITY_MAGNIFICATION_OVERLAY" to 2039,
        "NOTIFICATION_SHADE" to 2040, "STATUS_BAR_ADDITIONAL" to 2041,
    )

    fun parse(windowsText: String, displaysText: String): ParsedWindowSnapshot {
        if (windowsText.length.toLong() + displaysText.length > MAX_CHARS) return invalid("dump_too_large")
        if (!windowsText.trimStart('\uFEFF', ' ', '\t', '\r', '\n').startsWith("WINDOW MANAGER WINDOWS") ||
            !displaysText.trimStart('\uFEFF', ' ', '\t', '\r', '\n').startsWith("WINDOW MANAGER DISPLAY CONTENTS"))
            return invalid("unknown_dump_header")
        val ending = footer.findAll(windowsText).toList().singleOrNull()
            ?: return invalid("windows_missing_or_duplicate_footer")
        val beforeFooter = windowsText.substring(0, ending.range.first)
        val auxiliary = auxiliaryWindows.findAll(beforeFooter).toList()
        if (auxiliary.size > 1) return invalid("duplicate_auxiliary_window_section")
        // WMS can repeat full WindowState dumps in its auxiliary hiding-alert list.
        // Those #N entries are not part of the top-first Window #N enumeration.
        // Keep the inventory boundary explicit; metadata in a duplicate must never
        // complete or override the final primary window's visibility fields.
        val windowSection = beforeFooter.substring(0, auxiliary.singleOrNull()?.range?.first ?: beforeFooter.length)
        val blocks = header.findAll(windowSection).toList()
        if (blocks.isEmpty() || blocks.size > MAX_WINDOWS || blocks.size != broadHeader.findAll(beforeFooter).count())
            return invalid("unknown_window_headers")
        val focusedDisplay = topDisplay.findAll(windowsText).toList().singleOrNull()
            ?.groupValues?.get(1)?.toIntOrNull() ?: return invalid("missing_top_focused_display")
        val focusByDisplay = parseDisplays(displaysText) ?: return invalid("incomplete_display_focus")
        val focusedId = focusByDisplay[focusedDisplay] ?: return invalid("missing_focused_window")
        val identities = HashSet<Int>()
        val visible = ArrayList<WindowMetadata>()
        for ((index, start) in blocks.withIndex()) {
            if (start.groupValues[1].toIntOrNull() != index) return invalid("noncontiguous_window_order")
            val id = start.groupValues[2].toLongOrNull(16)?.toInt() ?: return invalid("invalid_window_id")
            if (!identities.add(id)) return invalid("duplicate_window_id")
            val end = blocks.getOrNull(index + 1)?.range?.first ?: windowSection.length
            val block = windowSection.substring(start.range.first, end)
            val hasSurface = boolean(block, "mHasSurface") ?: return invalid("incomplete_window_visibility")
            val onScreen = boolean(block, "isOnScreen") ?: return invalid("incomplete_window_visibility")
            val isVisible = boolean(block, "isVisible") ?: return invalid("incomplete_window_visibility")
            if (!hasSurface || !onScreen || !isVisible) continue
            val displayId = display.find(block)?.groupValues?.get(1)?.toIntOrNull()
                ?: return invalid("missing_window_display")
            if (!focusByDisplay.containsKey(displayId)) return invalid("unknown_window_display")
            val ownership = owner.find(block) ?: return invalid("missing_window_owner")
            val uid = ownership.groupValues[1].toIntOrNull() ?: return invalid("invalid_window_uid")
            val name = layout.find(block)?.groupValues?.get(1) ?: return invalid("missing_layout_type")
            val numericType = name.toIntOrNull()
            val knownType = numericType ?: layoutLabels[name]
            val layoutType = knownType ?: tokenType.find(block)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            if (knownType != null && knownType <= 0) return invalid("invalid_layout_type")
            val category = if (knownType == null) 3 else when (knownType) {
                in 1..99, in 1000..1999 -> 1
                2011, 2012 -> 2
                else -> 3
            }
            val bounds = frames.find(block) ?: legacyFrame.find(block) ?: return invalid("missing_window_frame")
            val coordinates = bounds.groupValues.drop(1).map { it.toIntOrNull() }
            if (coordinates.any { it == null }) return invalid("invalid_window_frame")
            val (left, top, right, bottom) = coordinates.map { requireNotNull(it) }
            if (left !in -100_000..100_000 || top !in -100_000..100_000 ||
                right !in -100_000..100_000 || bottom !in -100_000..100_000 ||
                right <= left || bottom <= top) return invalid("invalid_window_frame")
            // Only our passive shell-owned visuals are excluded. An ordinary application cannot
            // obtain TRUSTED_OVERLAY; matching a title alone never bypasses the context gate.
            val ownTrustedVisual=(layoutType==2003 && block.contains("UwU Mouse · Cursor opaco")) ||
                (layoutType==2038 && listOf("UwU Mouse · Cursor opaco","UwU Mouse · Burbuja visual","UwU Mouse · Monitor visual").any { block.contains(it) })
            if(uid==2000 && ownership.groupValues[2]=="com.android.shell" && ownTrustedVisual &&
                (trustedOverlayFlag.containsMatchIn(block) ||
                    hexPrivateFlags.find(block)?.groupValues?.get(1)?.toLongOrNull(16)?.let { it and 0x20000000L!=0L }==true) &&
                (nonTouchableFlag.containsMatchIn(block) ||
                    hexWindowFlags.find(block)?.groupValues?.get(1)?.toLongOrNull(16)?.let { it and 0x10L!=0L }==true) && focusByDisplay[displayId]!=id) continue
            visible.add(WindowMetadata(id, category, layoutType, ownership.groupValues[2], uid,
                displayId, blocks.size - index, left, top, right, bottom,
                focusByDisplay[displayId] == id))
        }
        if (visible.isEmpty()) return invalid("no_visible_windows")
        val current = visible.singleOrNull { it.id == focusedId && it.displayId == focusedDisplay }
            ?: return invalid("focused_window_not_visible")
        return ParsedWindowSnapshot(true, current.packageName, focusedDisplay, visible.toList(), "")
    }

    private fun parseDisplays(text: String): Map<Int, Int?>? {
        val starts = displayHeader.findAll(text).toList()
        if (starts.isEmpty() || starts.size > 32) return null
        val result = linkedMapOf<Int, Int?>()
        for ((index, start) in starts.withIndex()) {
            val id = start.groupValues[1].toIntOrNull() ?: return null
            if (result.containsKey(id)) return null
            val end = starts.getOrNull(index + 1)?.range?.first ?: text.length
            val block = text.substring(start.range.first, end)
            val focused = focus.findAll(block).toList().singleOrNull() ?: return null
            if (focusedApp.findAll(block).count() != 1) return null
            result[id] = if (focused.groupValues[1] == "null") null
                else focused.groupValues[2].toLongOrNull(16)?.toInt() ?: return null
        }
        return result
    }

    private fun boolean(block: String, field: String): Boolean? {
        val values = Regex("\\b${Regex.escape(field)}=(true|false)\\b").findAll(block).toList()
        return values.singleOrNull()?.groupValues?.get(1)?.toBooleanStrictOrNull()
    }

    private fun invalid(reason: String) = ParsedWindowSnapshot(false, reason = reason)
}
