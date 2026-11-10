package dev.uwumouse.app.backend.recovery

/**
 * Recognizes explicit empty-dispatcher markers from AOSP Android 14–17 and the bounded
 * Android 14 hover-only form. Dump text is not an SDK contract; unknown forms fail closed.
 */
object DispatcherTouchStateParser {
    private const val EMPTY_TOUCH_STATE = "  TouchStates: <no displays touched>"
    // Android 16 splits touch and cursor streams. Neither empty map alone proves that
    // the dispatcher is untouched: a held mouse button can belong to the cursor map.
    // InputDispatcher::DispatcherTouchState::dump(), android16-release/android17-release.
    private const val EMPTY_MODERN_TOUCH_STATE = "  TouchStatesByDisplay: <no displays touched>"
    private const val EMPTY_CURSOR_STATE = "  CursorStatesByDisplay: <no displays touched by cursor>"
    private const val CURSOR_STATES = "  CursorStatesByDisplay:"
    private const val TOUCH_STATES = "  TouchStatesByDisplay:"
    private const val MAX_DISPLAYS = 8
    private const val MAX_WINDOWS = 128
    private const val MAX_CURSOR_DEVICE_STATES = 1024
    private const val UINT = "(0|[1-9][0-9]{0,8})"
    private val display = Regex("^    (0|[1-9][0-9]{0,8}) :     deviceId=(0|[1-9][0-9]{0,8}), source=(MOUSE|TOUCHSCREEN \\| MOUSE)$")
    // A completed injected gesture may leave this virtual-device entry after evdev
    // capture removes mouse hover. Only an explicitly empty window list is accepted.
    private val virtualTouchDisplay = Regex("^    (0|[1-9][0-9]{0,8}) :     deviceId=(-1), source=(TOUCHSCREEN)$")
    private val followingDisplay = Regex("^  Display: (0|[1-9][0-9]{0,8})$")
    // Audited DispatcherTouchState::dump + TouchState::dump in android16-release and
    // android17-release. Removed displays can remain as explicitly empty cursor entries.
    private val cursorDisplay17 = Regex("^    LogicalDisplayId (0|[1-9][0-9]{0,8}):$")
    private val cursorDisplay16 = Regex("^    $UINT :     (Windows: <none>|Windows:)$")
    // TouchedWindow::dump and PrintTools.h, android16-release/android17-release:
    // accept only explicit zero device vectors, no down time or pilfering, and the
    // non-forwarding zero-flags window subset. Window names cannot inject syntax.
    private const val EMPTY_DEVICE = "\\[touchingPointers=\\[\\], downTimeInTarget=<not set>, hoveringPointers=\\[\\], pilferingPointerIds=<none>\\]"
    private const val EMPTY_WINDOW_FIELDS = "name='[^'\\p{Cc}]*', targetFlags=0x0, forwardingWindowToken=0x0, mDeviceStates="
    private val cursorWindow17 = Regex("^        $UINT: $EMPTY_WINDOW_FIELDS$")
    private val cursorDevice17 = Regex("^          DeviceId\\($UINT\\):$EMPTY_DEVICE$")
    private val cursorWindow16 = Regex("^        $UINT : $EMPTY_WINDOW_FIELDS$UINT:$EMPTY_DEVICE$")
    private val cursorDevice16 = Regex("^    $UINT:$EMPTY_DEVICE$")
    private val window = Regex(
        "^        (0|[1-9][0-9]{0,8}) : name='.*', pointerIds=0{32}, " +
            "targetFlags=([A-Z_]+(?: \\| [A-Z_]+)*), firstDownTimeInTarget=<not set>, " +
            "mHoveringPointerIdsByDevice=(0|[1-9][0-9]{0,8}):([01]{32}), pilferedPointerIds=0{32}$",
    )
    private val hoverFlags = setOf("FOREGROUND", "DISPATCH_AS_IS", "WINDOW_IS_OBSCURED", "WINDOW_IS_PARTIALLY_OBSCURED")

    fun isDefinitelyUntouched(dump: String): Boolean = isDefinitelyUntouched(dump.lineSequence())

    /** Consume through EOF: a later active/unknown section contradicts an earlier empty one. */
    fun isDefinitelyUntouched(lines: Sequence<String>): Boolean {
        var found = false
        var inSection = false
        var completed = false
        var modernTouchEmpty = false
        var modernCursorEmpty = false
        var inModernCursor = false
        var cursorWindowsExpected = false
        var cursorFormat = 0
        var cursorDisplayEmpty = false
        var cursorWindowCount = 0
        var cursorDeviceStates = 0
        val cursorDevices = mutableSetOf<Int>()
        val cursorDisplays = mutableSetOf<Int>()
        val displays = mutableSetOf<Int>()
        var deviceId: Int? = null
        var source: String? = null
        var expectingWindows = false
        var emptyWindows = false
        var windowCount = 0
        var totalWindows = 0
        fun displayComplete() = deviceId != null && !expectingWindows && (emptyWindows || windowCount > 0)
        fun cursorDisplayComplete() = !cursorWindowsExpected &&
            (cursorDisplayEmpty || (cursorWindowCount > 0 && cursorDevices.isNotEmpty()))
        fun addCursorDevice(id: String): Boolean = id.toIntOrNull()?.let {
            cursorDevices.add(it) && ++cursorDeviceStates <= MAX_CURSOR_DEVICE_STATES
        } == true
        for (line in lines) {
            if (inModernCursor) {
                if (followingDisplay.matches(line) || line == "  Displays: <none>") {
                    if (cursorDisplays.isEmpty() || !cursorDisplayComplete()) return false
                    inModernCursor = false
                    modernCursorEmpty = true
                    completed = true
                    continue
                }
                if (cursorWindowsExpected) {
                    when (line) {
                        "      TouchedWindows: <none>" -> cursorDisplayEmpty = true
                        "      TouchedWindows:" -> cursorDisplayEmpty = false
                        else -> return false
                    }
                    cursorWindowsExpected = false
                    continue
                }
                val entry17 = cursorDisplay17.matchEntire(line)
                val entry16 = cursorDisplay16.matchEntire(line)
                val entry = entry17 ?: entry16
                if (entry != null) {
                    if (cursorDisplays.isNotEmpty() && !cursorDisplayComplete()) return false
                    val format = if (entry17 != null) 17 else 16
                    if (cursorFormat != 0 && cursorFormat != format) return false
                    cursorFormat = format
                    val id = entry.groupValues[1].toIntOrNull() ?: return false
                    // The privileged reader bounds the entire dump. Retired display entries
                    // can accumulate across desktop reconnects; they are not active monitors.
                    if (!cursorDisplays.add(id)) return false
                    cursorWindowsExpected = entry17 != null
                    cursorDisplayEmpty = entry16?.groupValues?.get(2) == "Windows: <none>"
                    cursorWindowCount = 0
                    cursorDevices.clear()
                    continue
                }
                if (cursorDisplays.isEmpty() || cursorDisplayEmpty) return false
                val nextWindow = (if (cursorFormat == 17) cursorWindow17 else cursorWindow16).matchEntire(line)
                if (nextWindow != null) {
                    if (cursorWindowCount > 0 && cursorDevices.isEmpty()) return false
                    if (nextWindow.groupValues[1].toIntOrNull() != cursorWindowCount || ++totalWindows > MAX_WINDOWS) return false
                    cursorWindowCount++
                    cursorDevices.clear()
                    if (cursorFormat == 16 && !addCursorDevice(nextWindow.groupValues[2])) return false
                    continue
                }
                if (cursorWindowCount == 0) return false
                val nextDevice = (if (cursorFormat == 17) cursorDevice17 else cursorDevice16).matchEntire(line) ?: return false
                if (!addCursorDevice(nextDevice.groupValues[1])) return false
                continue
            }
            if (modernTouchEmpty && !modernCursorEmpty) {
                when (line) {
                    EMPTY_CURSOR_STATE -> { modernCursorEmpty = true; completed = true }
                    CURSOR_STATES -> inModernCursor = true
                    else -> return false
                }
                continue
            }
            if (line.contains("CursorState", ignoreCase = true)) return false
            if (inSection) {
                // A known next section is mandatory: an EOF or an unknown line could be a
                // truncated diagnostic hiding another display/window with a pointer down.
                if (followingDisplay.matches(line)) {
                    if (!displayComplete()) return false
                    inSection = false
                    completed = true
                    continue
                }
                val nextDisplay = display.matchEntire(line) ?: virtualTouchDisplay.matchEntire(line)
                if (nextDisplay != null) {
                    if (deviceId != null && !displayComplete()) return false
                    val id = nextDisplay.groupValues[1].toIntOrNull() ?: return false
                    if (!displays.add(id) || displays.size > MAX_DISPLAYS) return false
                    deviceId = nextDisplay.groupValues[2].toIntOrNull() ?: return false
                    source = nextDisplay.groupValues[3]
                    expectingWindows = true
                    emptyWindows = false
                    windowCount = 0
                    continue
                }
                if (expectingWindows) {
                    when (line) {
                        "      Windows: <none>" -> emptyWindows = true
                        "      Windows:" -> if (deviceId == -1) return false
                        else -> return false
                    }
                    expectingWindows = false
                    continue
                }
                // Samsung DeX also labels external mouse hover TOUCHSCREEN | MOUSE.
                // Both forms require the same explicit zero touching/pilfered pointers,
                // no down time and device-matched hover in every complete window row.
                if (deviceId == null || emptyWindows || (source != "MOUSE" && source != "TOUCHSCREEN | MOUSE")) return false
                val row = window.matchEntire(line) ?: return false
                if (row.groupValues[1].toIntOrNull() != windowCount) return false
                val flags = row.groupValues[2].split(" | ")
                if (flags.toSet().size != flags.size || !hoverFlags.containsAll(flags) || "DISPATCH_AS_IS" !in flags) return false
                if (row.groupValues[3].toIntOrNull() != deviceId || '1' !in row.groupValues[4]) return false
                if (++totalWindows > MAX_WINDOWS) return false
                windowCount++
                continue
            }
            if (!line.contains("TouchState", ignoreCase = true)) continue
            if (found) return false
            found = true
            when (line) {
                EMPTY_TOUCH_STATE -> completed = true
                EMPTY_MODERN_TOUCH_STATE -> modernTouchEmpty = true
                TOUCH_STATES -> inSection = true
                else -> return false
            }
        }
        return found && completed && !inSection && !inModernCursor && (!modernTouchEmpty || modernCursorEmpty)
    }
}
