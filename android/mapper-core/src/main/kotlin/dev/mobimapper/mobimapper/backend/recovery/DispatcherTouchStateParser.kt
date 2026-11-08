package dev.mobimapper.mobimapper.backend.recovery

/** Recognizes only the Android 14/Samsung diagnostic format observed on the test phone. */
object DispatcherTouchStateParser {
    private const val EMPTY_TOUCH_STATE = "  TouchStates: <no displays touched>"
    private const val TOUCH_STATES = "  TouchStatesByDisplay:"
    private const val MAX_DISPLAYS = 8
    private const val MAX_WINDOWS = 128
    private val display = Regex("^    (0|[1-9][0-9]{0,8}) :     deviceId=(0|[1-9][0-9]{0,8}), source=(MOUSE|TOUCHSCREEN \\| MOUSE)$")
    // A completed injected gesture may leave this virtual-device entry after evdev
    // capture removes mouse hover. Only an explicitly empty window list is accepted.
    private val virtualTouchDisplay = Regex("^    (0|[1-9][0-9]{0,8}) :     deviceId=(-1), source=(TOUCHSCREEN)$")
    private val followingDisplay = Regex("^  Display: (0|[1-9][0-9]{0,8})$")
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
        val displays = mutableSetOf<Int>()
        var deviceId: Int? = null
        var source: String? = null
        var expectingWindows = false
        var emptyWindows = false
        var windowCount = 0
        var totalWindows = 0
        fun displayComplete() = deviceId != null && !expectingWindows && (emptyWindows || windowCount > 0)
        for (line in lines) {
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
                if (deviceId == null || emptyWindows || source != "MOUSE") return false
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
                TOUCH_STATES -> inSection = true
                else -> return false
            }
        }
        return found && completed && !inSection
    }
}
