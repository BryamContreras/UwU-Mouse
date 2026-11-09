package dev.mobimapper.mobimapper.backend.recovery

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
