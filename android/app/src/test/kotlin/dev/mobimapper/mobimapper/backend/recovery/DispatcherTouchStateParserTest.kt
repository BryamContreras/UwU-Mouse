package dev.mobimapper.mobimapper.backend.recovery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DispatcherTouchStateParserTest {
    private val empty = "  TouchStates: <no displays touched>"
    private val header = "  TouchStatesByDisplay:"
    private val mouseDisplay = "    0 :     deviceId=41, source=MOUSE"
    private val noWindows = "    2 :     deviceId=38, source=TOUCHSCREEN | MOUSE\n      Windows: <none>"
    private fun hoverWindow(index: Int = 0): String =
        "        $index : name='test window', pointerIds=${"0".repeat(32)}, " +
            "targetFlags=FOREGROUND | DISPATCH_AS_IS, firstDownTimeInTarget=<not set>, " +
            "mHoveringPointerIdsByDevice=41:${"0".repeat(31)}1, pilferedPointerIds=${"0".repeat(32)}"
    private fun hoverSection(rows: String = hoverWindow()): String =
        "$header\n$mouseDisplay\n      Windows:\n$rows\n  Display: 0\n"

    @Test fun acceptsExactObservedLineInCompleteDiagnostic() {
        assertTrue(DispatcherTouchStateParser.isDefinitelyUntouched(
            "Input Dispatcher State:\n  DispatchEnabled: true\n$empty\n  FocusedDisplayId: 0\n",
        ))
        assertTrue(DispatcherTouchStateParser.isDefinitelyUntouched("$empty\r\n"))
    }

    @Test fun missingLineOrPermissionFailureDoesNotEstablishAnEmptyDispatcher() {
        assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched(""))
        assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched("Permission Denial: DUMP required\n"))
        assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched("Input Dispatcher State:\n"))
    }

    @Test fun activeDisplaysAreRejected() {
        assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched(
            "  TouchStatesByDisplay:\n    0 : deviceId=-1 source=0x1002\n",
        ))
        assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched("  TouchStates: active\n"))
    }

    @Test fun contradictionsAfterOrBeforeEmptyLineAreRejected() {
        assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched("$empty\n  TouchStatesByDisplay:\n"))
        assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched("  TouchStates: active\n$empty\n"))
        assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched("$empty\n$empty\n"))
    }

    @Test fun changedIndentationCaseOrOemFormatFailsClosed() {
        assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched("TouchStates: <no displays touched>\n"))
        assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched("  touchstates: <no displays touched>\n"))
        assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched("$empty extra\n"))
        assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched("$empty\n  TouchStatesByDisplay: <none>\n"))
        assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched("  TouchState: none\n"))
    }

    @Test fun acceptsCompleteObservedMouseHoverWithoutAnyDownPointer() {
        assertTrue(DispatcherTouchStateParser.isDefinitelyUntouched(hoverSection()))
        assertTrue(DispatcherTouchStateParser.isDefinitelyUntouched(
            "Input Dispatcher State:\n" + hoverSection("${hoverWindow()}\n${hoverWindow(1)}") + "    logicalSize=2316x1080\n",
        ))
    }

    @Test fun acceptsObservedEmptyWindowsOnAnotherDisplayInEitherOrder() {
        assertTrue(DispatcherTouchStateParser.isDefinitelyUntouched(
            "$header\n$noWindows\n$mouseDisplay\n      Windows:\n${hoverWindow()}\n  Display: 0\n",
        ))
        assertTrue(DispatcherTouchStateParser.isDefinitelyUntouched(
            "$header\n$mouseDisplay\n      Windows:\n${hoverWindow()}\n$noWindows\n  Display: 0\n",
        ))
        assertTrue(DispatcherTouchStateParser.isDefinitelyUntouched(
            "$header\n$mouseDisplay\n      Windows: <none>\n  Display: 0\n",
        ))
    }

    @Test fun anyDownPointerOrPilferedPointerOrDownTimeRejectsTheWholeSnapshot() {
        val row = hoverWindow(1)
        val badRows = listOf(
            row.replace("pointerIds=${"0".repeat(32)}", "pointerIds=${"0".repeat(31)}1"),
            row.replace("pilferedPointerIds=${"0".repeat(32)}", "pilferedPointerIds=1${"0".repeat(31)}"),
            row.replace("firstDownTimeInTarget=<not set>", "firstDownTimeInTarget=123456"),
        )
        badRows.forEach { assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched(hoverSection("${hoverWindow()}\n$it"))) }
    }

    @Test fun completeSectionAndKnownBoundaryAreRequired() {
        val incomplete = listOf(
            header,
            "$header\n$mouseDisplay\n",
            "$header\n$mouseDisplay\n      Windows:\n",
            hoverSection().removeSuffix("  Display: 0\n"),
            hoverSection().replace("  Display: 0", "  UnknownNextSection:"),
            hoverSection().replace("      Windows:", "      UnknownWindows:"),
            hoverSection().replace("      Windows:", "      Windows: <none>"),
        )
        incomplete.forEach { assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched(it)) }
    }

    @Test fun unknownSourceFlagsAndHoverFormatFailClosed() {
        val unknown = listOf(
            hoverSection().replace("source=MOUSE", "source=UNKNOWN"),
            hoverSection().replace("source=MOUSE", "source=TOUCHSCREEN | MOUSE"),
            hoverSection().replace("FOREGROUND | DISPATCH_AS_IS", "FOREGROUND | DISPATCH_AS_IS | UNKNOWN"),
            hoverSection().replace("FOREGROUND | DISPATCH_AS_IS", "DISPATCH_AS_IS | DISPATCH_AS_IS"),
            hoverSection().replace("FOREGROUND | DISPATCH_AS_IS", "FOREGROUND"),
            hoverSection().replace("mHoveringPointerIdsByDevice=41:", "mHoveringPointerIdsByDevice=42:"),
            hoverSection().replace("mHoveringPointerIdsByDevice=41:${"0".repeat(31)}1", "mHoveringPointerIdsByDevice=<none>"),
            hoverSection().replace("mHoveringPointerIdsByDevice=41:${"0".repeat(31)}1", "mHoveringPointerIdsByDevice=41:${"0".repeat(32)}"),
        )
        unknown.forEach { assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched(it)) }
    }

    @Test fun indicesIndentationAndDuplicateSectionsMustMatchKnownFormat() {
        val malformed = listOf(
            hoverSection("${hoverWindow()}\n${hoverWindow()}"),
            hoverSection(hoverWindow(1)),
            hoverSection().replace(mouseDisplay, "$mouseDisplay\n      Windows: <none>\n$mouseDisplay"),
            hoverSection().replace("    0 :", "   0 :"),
            hoverSection().replace("      Windows:", "      windows:"),
            hoverSection() + empty,
            "$empty\n${hoverSection()}",
            hoverSection() + header,
        )
        malformed.forEach { assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched(it)) }
    }

    @Test fun tooManyDisplaysOrWindowsFailClosed() {
        val manyDisplays = (0..8).joinToString("\n") {
            "    $it :     deviceId=38, source=MOUSE\n      Windows: <none>"
        }
        assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched("$header\n$manyDisplays\n  Display: 0\n"))
        assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched(hoverSection((0..128).joinToString("\n") { hoverWindow(it) })))
    }
}
