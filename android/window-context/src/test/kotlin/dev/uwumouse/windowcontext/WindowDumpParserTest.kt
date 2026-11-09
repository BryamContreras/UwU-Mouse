package dev.uwumouse.windowcontext

import org.junit.Assert.*
import org.junit.Test

class WindowDumpParserTest {
    @Test fun onlyTheNonTouchableTrustedShellCursorIsExcludedFromObstructionMetadata() {
        val cursor=window(index=0,id="c0ffee",name="com.android.shell",type="APPLICATION_OVERLAY")
            .replace("private title ignored by metadata parser","UwU Mouse · Cursor opaco")
            .replace("mOwnerUid=10123","mOwnerUid=2000")
            .replace("fmt=TRANSLUCENT}","fmt=TRANSLUCENT fl=NOT_TOUCHABLE pfl=TRUSTED_OVERLAY}")
        val game=window(index=1,id="123abc")
        val safe=parse(windows(cursor,game),displays(0 to "123abc"))
        assertTrue(safe.valid);assertEquals(1,safe.windows.size)
        val elevated=cursor.replace("ty=APPLICATION_OVERLAY", "ty=SYSTEM_ALERT")
        assertEquals(1,parse(windows(elevated,game),displays(0 to "123abc")).windows.size)
        assertEquals(2,parse(windows(elevated.replace("Cursor opaco","Monitor visual"),game),displays(0 to "123abc")).windows.size)
        assertEquals(2,parse(windows(elevated.replace("TRUSTED_OVERLAY","UNTRUSTED"),game),displays(0 to "123abc")).windows.size)
        val hexCursor=cursor.replace(" fl=NOT_TOUCHABLE pfl=TRUSTED_OVERLAY}","\n      fl=1000138\n      pfl=32000000\n    }")
        assertEquals(1,parse(windows(hexCursor,game),displays(0 to "123abc")).windows.size)
        assertEquals(2,parse(windows(hexCursor.replace("pfl=32000000","pfl=12000000"),game),displays(0 to "123abc")).windows.size)
        assertEquals(2,parse(windows(hexCursor.replace("fl=1000138","fl=1000128"),game),displays(0 to "123abc")).windows.size)
        for(other in listOf(cursor.replace("mOwnerUid=2000","mOwnerUid=10123"),
            cursor.replace("TRUSTED_OVERLAY","UNTRUSTED"),cursor.replace("NOT_TOUCHABLE","NOT_FOCUSABLE"))) {
            val result=parse(windows(other,game),displays(0 to "123abc"))
            assertTrue(result.valid);assertEquals(2,result.windows.size)
        }
    }
    @Test fun trustedStatusVisualsRequireShellTrustAndNeverReceiveInput() {
        val game=window(index=1,id="123abc")
        for(name in listOf("UwU Mouse · Burbuja visual","UwU Mouse · Monitor visual")) {
            val visual=window(index=0,id="c0ffee",name="com.android.shell",type="APPLICATION_OVERLAY")
                .replace("private title ignored by metadata parser",name)
                .replace("mOwnerUid=10123","mOwnerUid=2000")
                .replace("fmt=TRANSLUCENT}","fmt=TRANSLUCENT fl=NOT_TOUCHABLE pfl=TRUSTED_OVERLAY}")
            assertEquals(1,parse(windows(visual,game),displays(0 to "123abc")).windows.size)
            assertEquals(2,parse(windows(visual.replace("TRUSTED_OVERLAY","UNTRUSTED"),game),displays(0 to "123abc")).windows.size)
            assertEquals(2,parse(windows(visual.replace("NOT_TOUCHABLE","NOT_FOCUSABLE"),game),displays(0 to "123abc")).windows.size)
            assertEquals(2,parse(windows(visual.replace("mOwnerUid=2000","mOwnerUid=10123"),game),displays(0 to "123abc")).windows.size)
        }
    }
    @Test fun regexLiteralClosersAreExplicitForAndroidIcuCompatibility() {
        // The desktop JDK accepts literal } and ] that Android ICU rejects during
        // class initialization. Keep this stricter invariant in addition to fixture tests.
        val patterns = WindowDumpParser::class.java.declaredFields.filter { it.type == Regex::class.java }
        assertTrue(patterns.isNotEmpty())
        for (field in patterns) {
            field.isAccessible = true
            val pattern = (field.get(WindowDumpParser) as Regex).pattern
            var escaped = false
            var characterClass = false
            var quantifier = false
            for ((index, character) in pattern.withIndex()) {
                if (escaped) { escaped = false; continue }
                if (character == '\\') { escaped = true; continue }
                if (characterClass) {
                    if (character == ']') characterClass = false
                    continue
                }
                when (character) {
                    '[' -> characterClass = true
                    '{' -> {
                        val suffix = pattern.substring(index)
                        assertTrue("${field.name}: literal opening brace must be escaped", Regex("^\\{\\d+(?:,\\d*)?\\}").containsMatchIn(suffix))
                        quantifier = true
                    }
                    '}' -> {
                        assertTrue("${field.name}: literal closing brace must be escaped", quantifier)
                        quantifier = false
                    }
                    ']' -> fail("${field.name}: literal closing bracket must be escaped")
                }
            }
        }
    }

    private fun window(index: Int = 0, id: String = "123abc", display: Int = 0,
                       name: String = "dev.example.game", type: String = "BASE_APPLICATION",
                       visible: Boolean = true, frame: String = "[0,30][1080,2100]"): String = listOf(
        "  Window #$index Window{$id u0 private title ignored by metadata parser}:",
        "    mDisplayId=$display rootTaskId=1",
        "    mOwnerUid=10123 showForAllUsers=true package=$name appop=NONE",
        "    mAttrs={(0,0)(fillxfill) ty=$type fmt=TRANSLUCENT}",
        "    mHasSurface=$visible isReadyForDisplay()=$visible",
        "    Frames: parent=[0,0][1080,2160] display=[0,0][1080,2160] frame=$frame last=$frame",
        "    isOnScreen=$visible",
        "    isVisible=$visible",
        "    mPrepareSyncSeqId=0",
    ).joinToString("\n")

    private fun windows(vararg entries: String, topDisplay: Int = 0) =
        "WINDOW MANAGER WINDOWS (dumpsys window windows)\n" + entries.joinToString("\n") +
            "\n\n  mGlobalConfiguration={}\n  mTopFocusedDisplayId=$topDisplay\n"

    private fun displays(vararg focus: Pair<Int, String?>): String =
        "WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)\n" + focus.joinToString("\n") { (id, token) ->
            "  Display: mDisplayId=$id (organized)\n" +
                "  mCurrentFocus=" + (token?.let { "Window{$it u0 arbitrary title}" } ?: "null") + "\n" +
                "  mFocusedApp=" + (token?.let { "ActivityRecord{100 u0 dev.example/.Game t1}" } ?: "null") + "\n"
        }

    private fun parse(text: String = windows(window()), displayText: String = displays(0 to "123abc")) =
        WindowDumpParser.parse(text, displayText)

    @Test fun parsesApplicationMetadataWithoutRetainingTitleOrComponents() {
        val result = parse()
        assertTrue(result.valid)
        assertEquals("dev.example.game", result.focusedPackage)
        assertEquals(0, result.focusedDisplayId)
        val entry = result.windows.single()
        assertEquals(0x123abc, entry.id)
        assertEquals(1, entry.type)
        assertEquals(1, entry.layoutType)
        assertEquals(10123, entry.ownerUid)
        assertEquals(30, entry.top)
        assertEquals(1080, entry.right)
        assertEquals(2100, entry.bottom)
        assertTrue(entry.focused)
        assertFalse(result.toString().contains("private title"))
        assertFalse(result.toString().contains(".Game"))
    }

    @Test fun topFirstOrderAndMultipleDisplaysDetermineFocusIndependently() {
        val result = parse(windows(
            window(0, "aaa", type = "APPLICATION_OVERLAY", name = "dev.example.tools"),
            window(1, "bbb", display = 2),
            window(2, "ccc"), topDisplay = 2,
        ), displays(0 to "ccc", 2 to "bbb", 12 to null))
        assertTrue(result.valid)
        assertEquals(2, result.focusedDisplayId)
        assertEquals(listOf(3, 2, 1), result.windows.map { it.layer })
        assertEquals(3, result.windows.first().type)
        assertEquals(2038, result.windows.first().layoutType)
        assertTrue(result.windows[1].focused)
        assertTrue(result.windows[2].focused)
    }

    @Test fun explicitlyHiddenWindowsAreIgnoredButDoNotChangeLayerOrder() {
        val result = parse(windows(window(0, "aaa", visible = false), window(1)))
        assertTrue(result.valid)
        assertEquals(1, result.windows.size)
        assertEquals(1, result.windows.single().layer)
    }

    @Test fun everyVisibilityFieldIsRequiredEvenForAHiddenWindow() {
        for (field in listOf("mHasSurface", "isOnScreen", "isVisible")) {
            val bad = window(0, "aaa", visible = false).replace(Regex("\\b$field=false"), "unknownField=false")
            assertFalse(parse(windows(bad, window(1))).valid)
        }
    }

    @Test fun offscreenOrSurfaceLessWindowNeverQualifiesAsVisible() {
        for (field in listOf("mHasSurface", "isOnScreen", "isVisible")) {
            val bad = window().replace("$field=true", "$field=false")
            assertFalse(parse(windows(bad)).valid)
        }
    }

    @Test fun applicationDialogsAndImeTypesHaveExplicitCategories() {
        for ((label, expected) in listOf(
            "APPLICATION" to (1 to 2), "APPLICATION_STARTING" to (1 to 3),
            "APPLICATION_ATTACHED_DIALOG" to (1 to 1003), "1002" to (1 to 1002),
            "INPUT_METHOD" to (2 to 2011), "INPUT_METHOD_DIALOG" to (2 to 2012),
            "NAVIGATION_BAR" to (3 to 2019), "2038" to (3 to 2038),
            "PRESENTATION" to (3 to 2037), "PRIVATE_PRESENTATION" to (3 to 2030),
            "VOICE_INTERACTION" to (3 to 2031),
        )) {
            val result = parse(windows(window(type = label)))
            assertTrue("label=$label", result.valid)
            assertEquals(expected.first, result.windows.single().type)
            assertEquals(expected.second, result.windows.single().layoutType)
        }
    }

    @Test fun unknownOemLabelRemainsSystemEvenWithApplicationTokenNumber() {
        val entry = window(type = "OEM_DIALOG") + "\n    mToken=WindowToken{eee type=2 binder}"
        val result = parse(windows(entry))
        assertTrue(result.valid)
        assertEquals(3, result.windows.single().type)
        assertEquals(2, result.windows.single().layoutType)
    }

    @Test fun missingOrAmbiguousMetadataFailsClosedWithoutLeakingPartialWindows() {
        val valid = window()
        val invalidCases = listOf(
            valid.replace("mOwnerUid=10123", "missingUid=10123"),
            valid.replace("package=dev.example.game", "missingPackage=dev.example.game"),
            valid.replace("ty=BASE_APPLICATION", "missingType=BASE_APPLICATION"),
            valid.replace("mDisplayId=0", "missingDisplay=0"),
            valid.replace("frame=[0,30][1080,2100]", "missingFrame=[0,30][1080,2100]"),
            valid + "\n    isVisible=true",
        )
        for (entry in invalidCases) {
            val result = parse(windows(entry))
            assertFalse(result.valid)
            assertTrue(result.windows.isEmpty())
            assertNull(result.focusedPackage)
        }
    }

    @Test fun truncatedUnknownAndOversizedDumpsFailClosed() {
        assertFalse(parse(windows(window()).substringBefore("mGlobalConfiguration")).valid)
        assertFalse(parse(windows(window()) + "  mGlobalConfiguration={}\n").valid)
        assertFalse(parse("unknown windows").valid)
        assertFalse(parse(displayText = "unknown displays").valid)
        assertFalse(parse(windows(window()), displays(0 to "123abc").substringBefore("mCurrentFocus")).valid)
        assertFalse(parse("x".repeat(4 * 1024 * 1024 + 1)).valid)
    }

    @Test fun staleOrMissingFocusAcrossDumpsNeverAuthorizesAnEmptySnapshot() {
        assertFalse(parse(displayText = displays(0 to "ffffff")).valid)
        assertFalse(parse(displayText = displays(0 to null)).valid)
        assertFalse(parse(windows(window(), topDisplay = 2)).valid)
        assertFalse(parse(windows(window(visible = false))).valid)
    }

    @Test fun invalidIndicesDuplicateIdsAndDisplaysAreRejected() {
        assertFalse(parse(windows(window(index = 1))).valid)
        assertFalse(parse(windows(window(), window(1))).valid)
        assertFalse(parse(displayText = displays(0 to "123abc", 0 to "123abc")).valid)
        assertFalse(parse(windows(window(display = 99))).valid)
    }

    @Test fun invalidOrOverflowingBoundsAndIdsAreRejected() {
        for (frame in listOf("[0,0][0,100]", "[0,100][100,10]", "[0,0][999999999999,100]", "[0,0][100001,100]")) {
            assertFalse(parse(windows(window(frame = frame))).valid)
        }
        assertFalse(parse(windows(window(id = "123456789")), displays(0 to "123456789")).valid)
    }

    @Test fun crlfAndUnsignedWindowHashAreSupportedAndSystemPackageIsAllowed() {
        val result = parse(windows(window(id = "fedcba98", name = "android")).replace("\n", "\r\n"),
            displays(0 to "fedcba98").replace("\n", "\r\n"))
        assertTrue(result.valid)
        assertEquals(0xfedcba98.toInt(), result.windows.single().id)
        assertEquals("android", result.focusedPackage)
    }
}
