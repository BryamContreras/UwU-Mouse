package dev.uwumouse.app.backend.recovery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DispatcherTouchStateParserTest {
    private val empty = "  TouchStates: <no displays touched>"
    private val modernTouchEmpty = "  TouchStatesByDisplay: <no displays touched>"
    private val modernCursorEmpty = "  CursorStatesByDisplay: <no displays touched by cursor>"
    private val header = "  TouchStatesByDisplay:"
    private val mouseDisplay = "    0 :     deviceId=41, source=MOUSE"
    private val noWindows = "    2 :     deviceId=38, source=TOUCHSCREEN | MOUSE\n      Windows: <none>"
    private val virtualTouchDisplay = "    0 :     deviceId=-1, source=TOUCHSCREEN"
    // Anonymous reproduction of the observed native-capture/focus-loss diagnostic.
    private fun emptyVirtualTouchSection() =
        "$header\n$virtualTouchDisplay\n      Windows: <none>\n  Display: 12\n"
    private fun hoverWindow(index: Int = 0): String =
        "        $index : name='test window', pointerIds=${"0".repeat(32)}, " +
            "targetFlags=FOREGROUND | DISPATCH_AS_IS, firstDownTimeInTarget=<not set>, " +
            "mHoveringPointerIdsByDevice=41:${"0".repeat(31)}1, pilferedPointerIds=${"0".repeat(32)}"
    private fun hoverSection(rows: String = hoverWindow()): String =
        "$header\n$mouseDisplay\n      Windows:\n$rows\n  Display: 0\n"
    private val emptyModernDevice =
        "[touchingPointers=[], downTimeInTarget=<not set>, hoveringPointers=[], pilferingPointerIds=<none>]"
    private fun modernWindow(format: Int, index: Int = 0, devices: List<Int> = listOf(24),
                             name: String = "monitor"): String {
        val fields = "name='$name', targetFlags=0x0, forwardingWindowToken=0x0, mDeviceStates="
        return if (format == 17) "        $index: $fields\n" +
            devices.joinToString("\n") { "          DeviceId($it):$emptyModernDevice" }
        else "        $index : $fields" + devices.mapIndexed { i, device ->
            (if (i == 0) "" else "    ") + "$device:$emptyModernDevice"
        }.joinToString("\n")
    }
    private fun modernSection(format: Int, rows: String = modernWindow(format)): String {
        val display = if (format == 17) "    LogicalDisplayId 11:\n      TouchedWindows:"
            else "    11 :     Windows:"
        val retired = if (format == 17) "    LogicalDisplayId 9:\n      TouchedWindows: <none>"
            else "    9 :     Windows: <none>"
        return "$modernTouchEmpty\n  CursorStatesByDisplay:\n$display\n$rows\n$retired\n  Display: 11\n"
    }

    @Test fun acceptsExactObservedLineInCompleteDiagnostic() {
        assertTrue(DispatcherTouchStateParser.isDefinitelyUntouched(
            "Input Dispatcher State:\n  DispatchEnabled: true\n$empty\n  FocusedDisplayId: 0\n",
        ))
        assertTrue(DispatcherTouchStateParser.isDefinitelyUntouched("$empty\r\n"))
    }

    @Test fun android16And17RequireBothAuditedEmptyTouchAndCursorMaps() {
        // InputDispatcher::DispatcherTouchState::dump() uses the same empty markers in
        // android16-release and android17-release, independent of device/OEM identity.
        val dump = "Input Dispatcher State:\n$modernTouchEmpty\n$modernCursorEmpty\n  Display: 17\n"
        assertTrue(DispatcherTouchStateParser.isDefinitelyUntouched(dump))
        assertTrue(DispatcherTouchStateParser.isDefinitelyUntouched(dump.replace("\n", "\r\n")))
        assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched("$modernTouchEmpty\n"))
        assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched("$modernCursorEmpty\n"))
        assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched("$modernCursorEmpty\n$modernTouchEmpty\n"))
    }

    @Test fun modernEmptyTouchMapNeverHidesAnActiveOrUnknownCursorStream() {
        val activeCursor = "  CursorStatesByDisplay:\n    LogicalDisplayId 17:\n" +
            "      TouchedWindows:\n        0: name='game', mDeviceStates=pointerIds=1\n"
        for (tail in listOf(activeCursor, "  CursorStatesByDisplay: <unknown>\n",
            "  cursorstatesbydisplay: <no displays touched by cursor>\n",
            "  Display: 17\n$modernCursorEmpty\n", "$modernCursorEmpty extra\n")) {
            assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched("$modernTouchEmpty\n$tail"))
        }
        assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched(
            "  TouchStatesByDisplay:\n    LogicalDisplayId 17:\n      TouchedWindows: active\n$modernCursorEmpty\n"))
    }

    @Test fun android17RemovedDisplayCursorEntriesCanBeExplicitlyEmpty() {
        val dump = "$modernTouchEmpty\n  CursorStatesByDisplay:\n" +
            "    LogicalDisplayId 9:\n      TouchedWindows: <none>\n" +
            "    LogicalDisplayId 8:\n      TouchedWindows: <none>\n  Display: 11\n"
        assertTrue(DispatcherTouchStateParser.isDefinitelyUntouched(dump))
        assertTrue(DispatcherTouchStateParser.isDefinitelyUntouched(dump.replace("\n", "\r\n")))
        assertTrue(DispatcherTouchStateParser.isDefinitelyUntouched(dump.replace("  Display: 11", "  Displays: <none>")))
        assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched(dump.replace("  Display: 11\n", "")))
        assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched(dump.replace("LogicalDisplayId 8:", "LogicalDisplayId 9:")))
        assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched(dump + modernCursorEmpty))
        assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched(dump + modernTouchEmpty))
    }

    @Test fun android16RemovedDisplayCursorEntriesUseTheirAuditedDifferentLayout() {
        val dump = "$modernTouchEmpty\n  CursorStatesByDisplay:\n" +
            "    9 :     Windows: <none>\n    8 :     Windows: <none>\n  Display: 11\n"
        assertTrue(DispatcherTouchStateParser.isDefinitelyUntouched(dump))
        assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched(dump.replace("    8 :", "    9 :")))
        assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched(dump.replace("  Display: 11\n", "")))
        assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched(dump.replace("    8 :     Windows: <none>",
            "    LogicalDisplayId 8:\n      TouchedWindows: <none>")))
    }

    @Test fun modernPerDisplayCursorProofRejectsTouchHoverUnknownAndTruncatedRows() {
        val prefix = "$modernTouchEmpty\n  CursorStatesByDisplay:\n    LogicalDisplayId 9:\n"
        for (payload in listOf("", "      TouchedWindows:", "      TouchedWindows: <unknown>",
            "      TouchedWindows: <none> extra", "     TouchedWindows: <none>",
            "      TouchedWindows:\n        0: name='game', mDeviceStates=pointerIds=1",
            "      TouchedWindows:\n        0: name='hover', mDeviceStates=touchingPointers=[]")) {
            assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched(prefix + payload + "\n  Display: 11\n"))
        }
        assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched("$modernTouchEmpty\n  CursorStatesByDisplay:\n  Display: 11\n"))
        assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched(prefix + "  Display: 11\n"))
        val retiredDisplays = (0..31).joinToString("\n") { "    LogicalDisplayId $it:\n      TouchedWindows: <none>" }
        assertTrue(DispatcherTouchStateParser.isDefinitelyUntouched("$modernTouchEmpty\n  CursorStatesByDisplay:\n$retiredDisplays\n  Display: 40\n"))
    }

    @Test fun android17AcceptsCompleteObservedMonitorWindowsWithEveryDeviceExplicitlyEmpty() {
        // Anonymous full-shape reproduction of the live Android17 dispatcher: monitor
        // windows survive while all touch/hover/pilfer vectors are explicitly empty.
        val rows = (0..2).joinToString("\n") { modernWindow(17, it, listOf(24), "[Gesture Monitor] $it") }
        assertTrue(DispatcherTouchStateParser.isDefinitelyUntouched(modernSection(17, rows)))
        assertTrue(DispatcherTouchStateParser.isDefinitelyUntouched(modernSection(17, rows).replace("\n", "\r\n")))
        assertTrue(DispatcherTouchStateParser.isDefinitelyUntouched(modernSection(17,
            modernWindow(17, devices = listOf(0, 24, 51)))))
    }

    @Test fun android16UsesInlineFirstDeviceAndItsAuditedContinuationIndentation() {
        // TouchedWindow::dump uses dumpMap with numeric keys; DispatcherTouchState and
        // TouchState add different indentation from17. Never reinterpret one as the other.
        val rows = modernWindow(16, devices = listOf(0, 24, 51)) + "\n" + modernWindow(16, 1)
        assertTrue(DispatcherTouchStateParser.isDefinitelyUntouched(modernSection(16, rows)))
        assertTrue(DispatcherTouchStateParser.isDefinitelyUntouched(modernSection(16, rows).replace("\n", "\r\n")))
        assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched(modernSection(16, modernWindow(17))))
        assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched(modernSection(17, modernWindow(16))))
    }

    private fun modernHoverWindow(format: Int, index: Int = 0): String =
        modernWindow(format, index, listOf(20), "[Gesture Monitor] monitor $index")
            .replace("hoveringPointers=[]", "hoveringPointers=[Pointer(id=0, MOUSE) at (0, 1027.91)]")

    private fun retainedEmptyTouchAndMouseHover(format: Int): String {
        val retiredTouch = if (format == 17) "    LogicalDisplayId 8:\n      TouchedWindows: <none>"
            else "    8 :     Windows: <none>"
        val retiredCursor = if (format == 17) "    LogicalDisplayId 8:\n      TouchedWindows: <none>\n" +
            "    LogicalDisplayId 7:\n      TouchedWindows: <none>"
            else "    8 :     Windows: <none>\n    7 :     Windows: <none>"
        val current = if (format == 17) "    LogicalDisplayId 0:\n      TouchedWindows:"
            else "    0 :     Windows:"
        val monitors = (0..4).joinToString("\n") { modernHoverWindow(format, it) }
        return "$header\n$retiredTouch\n  CursorStatesByDisplay:\n$retiredCursor\n$current\n$monitors\n  Display: 0\n"
    }

    @Test fun modernRetiredTouchDisplaysAndMouseHoverProveNoPressedContact() {
        // Sanitized Android17 S25 reproduction: a disconnected game's display
        // remains explicitly empty while system monitors retain only mouse hover.
        for (format in listOf(16, 17)) {
            val observed = retainedEmptyTouchAndMouseHover(format)
            assertTrue(DispatcherTouchStateParser.isDefinitelyUntouched(observed))
            assertTrue(DispatcherTouchStateParser.isDefinitelyUntouched(observed.replace("\n", "\r\n")))
            assertTrue(DispatcherTouchStateParser.isDefinitelyUntouched(observed
                .replace("LogicalDisplayId 8", "LogicalDisplayId 19")
                .replace("    8 :", "    19 :")))
            assertTrue(DispatcherTouchStateParser.isDefinitelyUntouched(modernSection(format,
                modernHoverWindow(format))))
            assertTrue(DispatcherTouchStateParser.isDefinitelyUntouched(modernSection(format,
                modernHoverWindow(format).replace("(0, 1027.91)", "(-0.25, 1.2e+3)"))))
        }
    }

    @Test fun modernMouseHoverNeverHidesPressedPilferedUnknownOrMalformedState() {
        for (format in listOf(16, 17)) {
            val observed = retainedEmptyTouchAndMouseHover(format)
            val originalRow = modernHoverWindow(format, 3)
            val invalidRows = listOf(
                originalRow.replace("touchingPointers=[]", "touchingPointers=[Pointer(id=0, MOUSE) at (0, 1027.91)]"),
                originalRow.replace("downTimeInTarget=<not set>", "downTimeInTarget=12345"),
                originalRow.replace("pilferingPointerIds=<none>", "pilferingPointerIds=${"0".repeat(31)}1"),
                originalRow.replace("targetFlags=0x0", "targetFlags=0x1"),
                originalRow.replace("forwardingWindowToken=0x0", "forwardingWindowToken=0x12"),
                originalRow.replace("MOUSE", "STYLUS"),
                originalRow.replace("MOUSE", "UNKNOWN"),
                originalRow.replace("id=0", "id=32"),
                originalRow.replace("id=0", "id=-1"),
                originalRow.replace("(0, 1027.91)", "(NaN, 1027.91)"),
                originalRow.replace("(0, 1027.91)", "(Infinity, 1027.91)"),
                originalRow.replace("(0, 1027.91)", "(1e999, 1027.91)"),
                originalRow.replace("Pointer(id=0, MOUSE) at (0, 1027.91)",
                    "Pointer(id=0, MOUSE) at (0, 1027.91), Pointer(id=1, MOUSE) at (0, 0)"),
                originalRow.replace("(0, 1027.91)", "(0, 1027.91), unexpected=0"),
                originalRow.replace("pilferingPointerIds=<none>]", "pilferingPointerIds=<none>, unknown=0]"),
            )
            invalidRows.forEach { assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched(observed.replace(originalRow, it))) }
            assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched(observed.removeSuffix("  Display: 0\n")))
            assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched(observed + modernTouchEmpty))
        }
    }

    @Test fun modernRetainedTouchEntriesRequireExplicitlyEmptyWindowsAndCompleteCursorProof() {
        for (format in listOf(16, 17)) {
            val observed = retainedEmptyTouchAndMouseHover(format)
            val emptyTouch = if (format == 17) "    LogicalDisplayId 8:\n      TouchedWindows: <none>"
                else "    8 :     Windows: <none>"
            val nonemptyTouch = if (format == 17) "    LogicalDisplayId 8:\n      TouchedWindows:\n${modernWindow(format)}"
                else "    8 :     Windows:\n${modernWindow(format)}"
            val invalid = listOf(
                observed.replaceFirst(emptyTouch, nonemptyTouch),
                observed.replaceFirst(emptyTouch, emptyTouch.replace("<none>", "<unknown>")),
                observed.replaceFirst(emptyTouch, "$emptyTouch\n$emptyTouch"),
                "$header\n$emptyTouch\n  Display: 0\n",
                "$header\n$emptyTouch\n",
                observed.replace("  CursorStatesByDisplay:", "  UnknownCursorStates:"),
            )
            invalid.forEach { assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched(it)) }
            assertTrue(DispatcherTouchStateParser.isDefinitelyUntouched("$header\n$emptyTouch\n$modernCursorEmpty\n"))
            val secondTouch = if (format == 17) "    LogicalDisplayId 3:\n      TouchedWindows: <none>"
                else "    3 :     Windows: <none>"
            assertTrue(DispatcherTouchStateParser.isDefinitelyUntouched(observed.replaceFirst(emptyTouch, "$emptyTouch\n$secondTouch")))
        }
    }

    @Test fun modernDeviceProofRejectsAnyTouchHoverDownTimePilferForwardingOrUnknownField() {
        for (format in listOf(16, 17)) {
            val complete = modernSection(format)
            val invalid = listOf(
                complete.replace("touchingPointers=[]", "touchingPointers=[0]"),
                complete.replace("hoveringPointers=[]", "hoveringPointers=[0]"),
                complete.replace("downTimeInTarget=<not set>", "downTimeInTarget=12345"),
                complete.replace("pilferingPointerIds=<none>", "pilferingPointerIds=${"0".repeat(31)}1"),
                complete.replace("targetFlags=0x0", "targetFlags=FOREGROUND"),
                complete.replace("targetFlags=0x0", "targetFlags=UNKNOWN"),
                complete.replace("forwardingWindowToken=0x0", "forwardingWindowToken=0x1234"),
                complete.replace("touchingPointers=[]", "touchingPointers=<none>"),
                complete.replace("pilferingPointerIds=<none>", "pilferingPointerIds=<none>, extra=0"),
                complete.replace("downTimeInTarget=<not set>", "downTimeInTarget=<unknown>"),
            )
            invalid.forEach { assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched(it)) }
        }
    }

    @Test fun modernWindowProofRequiresCompleteSequentialUniqueDeviceRowsAndKnownBoundary() {
        for (format in listOf(16, 17)) {
            val complete = modernSection(format)
            val duplicateDevice = modernSection(format, modernWindow(format, devices = listOf(24, 24)))
            val secondWindow = modernSection(format, modernWindow(format) + "\n" + modernWindow(format, 1))
            assertTrue(DispatcherTouchStateParser.isDefinitelyUntouched(secondWindow))
            val invalid = listOf(duplicateDevice,
                modernSection(format, modernWindow(format, 1)),
                modernSection(format, modernWindow(format) + "\n" + modernWindow(format)),
                modernSection(format, modernWindow(format, devices = emptyList())),
                complete.replace("  Display: 11\n", ""),
                complete.replace("  Display: 11", "  UnknownNextSection:"),
                complete.substringBefore("pilferingPointerIds="),
                complete.replace("pilferingPointerIds=<none>]", "pilferingPointerIds=<none>"),
                complete + modernCursorEmpty,
                complete + modernTouchEmpty,
            )
            invalid.forEach { assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched(it)) }
        }
        val missingLastDevice = modernSection(17, modernWindow(17) +
            "\n        1: name='monitor2', targetFlags=0x0, forwardingWindowToken=0x0, mDeviceStates=")
        assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched(missingLastDevice))
        assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched(modernSection(17).replace("          DeviceId(24):", "        DeviceId(24):")))
        assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched(modernSection(17).replace("DeviceId(24)", "DeviceId(-1)")))
    }

    @Test fun modernWindowNamesCannotSpoofQuotesOrControlCharacters() {
        for (format in listOf(16, 17)) {
            for (name in listOf("bad'name", "bad\nname", "bad\rname", "bad\u0000name", "bad\tname", "bad\u007fname")) {
                assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched(modernSection(format,
                    modernWindow(format, name = name))))
            }
        }
    }

    @Test fun modernWindowAndDeviceStateCountsRemainBounded() {
        for (format in listOf(16, 17)) {
            assertTrue(DispatcherTouchStateParser.isDefinitelyUntouched(modernSection(format,
                (0 until 128).joinToString("\n") { modernWindow(format, it) })))
            assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched(modernSection(format,
                (0..128).joinToString("\n") { modernWindow(format, it) })))
            assertTrue(DispatcherTouchStateParser.isDefinitelyUntouched(modernSection(format,
                modernWindow(format, devices = (0 until 1024).toList()))))
            assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched(modernSection(format,
                modernWindow(format, devices = (0..1024).toList()))))
        }
    }

    @Test fun duplicateAndMixedLegacyModernStateSectionsRemainAmbiguous() {
        val modern = "$modernTouchEmpty\n$modernCursorEmpty\n"
        for (dump in listOf(modern + modernTouchEmpty, modern + modernCursorEmpty,
            modern + empty, modern + header, "$empty\n$modern", "$empty\n$modernCursorEmpty\n")) {
            assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched(dump))
        }
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

    @Test fun dexHybridMouseCaptionHoverProvesNoDownOnEitherDisplay() {
        for (displayId in listOf(0, 2)) {
            val observed = hoverSection().replace(mouseDisplay,
                "    $displayId :     deviceId=41, source=TOUCHSCREEN | MOUSE")
                .replace("test window", "Embedded{Caption of Task=89}")
                .replace("  Display: 0", "  Display: $displayId")
            assertTrue(DispatcherTouchStateParser.isDefinitelyUntouched(observed))
            assertTrue(DispatcherTouchStateParser.isDefinitelyUntouched(observed.replace("\n", "\r\n")))
            assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched(observed.removeSuffix("  Display: $displayId\n")))
        }
    }

    @Test fun dexHybridHoverCannotHideAPressedOrPilferedPointerOrUnknownSource() {
        val observed = hoverSection().replace("source=MOUSE", "source=TOUCHSCREEN | MOUSE")
        for (invalid in listOf(
            observed.replace("pointerIds=${"0".repeat(32)}", "pointerIds=${"0".repeat(31)}1"),
            observed.replace("firstDownTimeInTarget=<not set>", "firstDownTimeInTarget=12345"),
            observed.replace("pilferedPointerIds=${"0".repeat(32)}", "pilferedPointerIds=${"0".repeat(31)}1"),
            observed.replace("mHoveringPointerIdsByDevice=41:", "mHoveringPointerIdsByDevice=13:"),
            observed.replace("source=TOUCHSCREEN | MOUSE", "source=TOUCHSCREEN"),
            observed.replace("source=TOUCHSCREEN | MOUSE", "source=STYLUS | MOUSE"),
        )) assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched(invalid))
    }

    @Test fun dexIdleCursorStillRequiresEveryDisplayToHaveNoDownPointers() {
        val dex = "    2 :     deviceId=41, source=TOUCHSCREEN | MOUSE\n      Windows:\n${hoverWindow()}"
        val phone = "$mouseDisplay\n      Windows:\n${hoverWindow()}"
        val untouched = "$header\n$dex\n$phone\n  Display: 2\n"
        assertTrue(DispatcherTouchStateParser.isDefinitelyUntouched(untouched))
        val pressed = phone.replace("pointerIds=${"0".repeat(32)}", "pointerIds=${"0".repeat(31)}1")
        assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched(untouched.replace(phone, pressed)))
        assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched("$header\n$pressed\n$dex\n  Display: 2\n"))
    }

    @Test fun closureAfterPhysicalTouchAcceptsOnlyTheObservedCompleteHoverAndRetiredDisplayState() {
        // Anonymous shape from the blocked POINTER_UP session: the app and three
        // system gesture monitors retain hover, with no touching/pilfered pointers.
        val rows=(0..3).map { index ->
            hoverWindow(index).replace("test window",if(index==0) "controller" else "[Gesture Monitor] monitor $index")
                .let { if(index==0) it else it.replace("FOREGROUND | DISPATCH_AS_IS","DISPATCH_AS_IS") }
        }
        val observed="Input Dispatcher State:\n  Pointer Capture Requested: false\n" +
            "$header\n$mouseDisplay\n      Windows:\n${rows.joinToString("\n")}\n$noWindows\n  Display: 0\n"
        assertTrue(DispatcherTouchStateParser.isDefinitelyUntouched(observed))
        for(row in rows) {
            for(changed in listOf(
                row.replace("pointerIds=${"0".repeat(32)}","pointerIds=${"0".repeat(31)}1"),
                row.replace("pilferedPointerIds=${"0".repeat(32)}","pilferedPointerIds=1${"0".repeat(31)}"),
                row.replace("firstDownTimeInTarget=<not set>","firstDownTimeInTarget=123456"),
            )) assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched(observed.replace(row,changed)))
        }
        assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched(observed.removeSuffix("  Display: 0\n")))
        assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched(observed.replace(
            noWindows,noWindows.replace("Windows: <none>","Windows:\n${hoverWindow()}"))))
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

    @Test fun acceptsObservedVirtualTouchscreenOnlyWithAnExplicitlyEmptyWindowList() {
        val observed = "Input Dispatcher State:\n" + emptyVirtualTouchSection() +
            "    logicalSize=1920x1080\n"
        assertTrue(DispatcherTouchStateParser.isDefinitelyUntouched(observed))
        assertTrue(DispatcherTouchStateParser.isDefinitelyUntouched(observed.replace("\n", "\r\n")))
    }

    @Test fun virtualTouchscreenNeverAcceptsWindowRowsEvenWithZeroDownPointerBits() {
        val invalid = listOf(
            emptyVirtualTouchSection().replace("      Windows: <none>", "      Windows:\n${hoverWindow()}"),
            emptyVirtualTouchSection().replace("      Windows: <none>", "      Windows:\n" +
                hoverWindow().replace("pointerIds=${"0".repeat(32)}", "pointerIds=${"0".repeat(31)}1")),
            emptyVirtualTouchSection().replace("      Windows: <none>", "      Windows: <none>\n${hoverWindow()}"),
            emptyVirtualTouchSection().replace("      Windows: <none>", "      Windows:\n        unknown window"),
            emptyVirtualTouchSection().replace("      Windows: <none>", "      Windows: <none>, pointerIds=1"),
            emptyVirtualTouchSection().replace("      Windows: <none>", "      Windows:"),
        )
        invalid.forEach { assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched(it)) }
    }

    @Test fun virtualTouchscreenDoesNotBroadenAcceptedDeviceIdsSourcesOrFormatting() {
        val invalid = listOf(
            "deviceId=-2, source=TOUCHSCREEN",
            "deviceId=0, source=TOUCHSCREEN",
            "deviceId=41, source=TOUCHSCREEN",
            "deviceId=-1, source=MOUSE",
            "deviceId=-1, source=TOUCHSCREEN | MOUSE",
            "deviceId=-1, source=UNKNOWN",
            "deviceId=-1, source=touchscreen",
            "deviceId=-1, source=TOUCHSCREEN | UNKNOWN",
            "deviceId=-1, source=TOUCHSCREEN pointerIds=0",
        ).map { emptyVirtualTouchSection().replace("deviceId=-1, source=TOUCHSCREEN", it) } + listOf(
            emptyVirtualTouchSection().replace("      Windows: <none>", "      Windows: <unknown>"),
            emptyVirtualTouchSection().replace("      Windows: <none>", "      windows: <none>"),
            emptyVirtualTouchSection().replace("    0 :", "   0 :"),
        )
        invalid.forEach { assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched(it)) }
    }

    @Test fun virtualTouchscreenRequiresACompleteSectionWithoutContradictionsOrDuplicateDisplays() {
        val invalid = listOf(
            "$header\n$virtualTouchDisplay\n",
            emptyVirtualTouchSection().removeSuffix("  Display: 12\n"),
            emptyVirtualTouchSection().replace("  Display: 12", "  UnknownNextSection:"),
            emptyVirtualTouchSection().replace("  Display: 12", "$virtualTouchDisplay\n      Windows: <none>\n  Display: 12"),
            emptyVirtualTouchSection() + empty,
            emptyVirtualTouchSection() + header,
            "$empty\n${emptyVirtualTouchSection()}",
        )
        invalid.forEach { assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched(it)) }
    }

    @Test fun emptyVirtualTouchscreenMustNotHideAnotherActiveOrUnknownDisplay() {
        val virtualEntry = "$virtualTouchDisplay\n      Windows: <none>"
        val hoverEntry = "${mouseDisplay.replace("    0 :", "    3 :")}\n      Windows:\n${hoverWindow()}"
        for (entries in listOf("$virtualEntry\n$hoverEntry", "$hoverEntry\n$virtualEntry")) {
            val complete = "$header\n$entries\n  Display: 12\n"
            assertTrue(DispatcherTouchStateParser.isDefinitelyUntouched(complete))
            assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched(complete.replace(
                "pointerIds=${"0".repeat(32)}", "pointerIds=${"0".repeat(31)}1")))
            assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched(complete.replace("source=MOUSE", "source=UNKNOWN")))
        }
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
            hoverSection().replace("source=MOUSE", "source=STYLUS | MOUSE"),
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
