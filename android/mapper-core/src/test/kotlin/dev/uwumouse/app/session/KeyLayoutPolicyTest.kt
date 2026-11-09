package dev.uwumouse.app.session

import org.junit.Assert.*
import org.junit.Test

class KeyLayoutPolicyTest {
    @Test fun decimalHexadecimalCommentsAndFlagsRetainOnlyTheKeyLabel() {
        val parsed=KeyLayoutParser.parse("""
            # keyboard
            key 30 A
            key 0x1f S WAKE_DROPPED FUNCTION # optional flags
            key 0X020 D VIRTUAL
            key 15 TAB
            key usage 0x0c006f BRIGHTNESS_UP
            axis 0x00 X
        """.trimIndent())
        assertEquals(mapOf(30 to "A",31 to "S",32 to "D",15 to "TAB"),parsed)
    }

    @Test fun invalidOrOutOfRangeScansAreIgnoredWithoutGuessingBindings() {
        assertEquals(mapOf(1 to "ESCAPE"),KeyLayoutParser.parse("""
            key -1 A
            key 65536 B
            key 999999999999999999999999 C
            key 0xFFFFFFFFFFFF D
            key not_a_number E
            key 30 invalid-label
            key 1 ESCAPE
            key 1 HOME
        """.trimIndent()))
    }

    @Test fun vendorProductPrecedesDeviceNameAndMissingScansRetainGenericFallback() {
        val generic=mapOf(30 to "A",31 to "S")
        val files=mapOf("Vendor_046d_Product_c08b.kl" to "key 30 BUTTON_A",
            "Gaming_Mouse_Keyboard.kl" to "key 30 B")
        val queried=mutableListOf<String>()
        val selected=DeviceKeyLayoutPolicy.resolve(0x046d,0xc08b,"Gaming Mouse Keyboard",generic) {
            queried+=it;files[it]
        }
        assertEquals("Vendor_046d_Product_c08b.kl",selected.fileName)
        assertEquals(mapOf(30 to "BUTTON_A",31 to "S"),selected.keys)
        assertEquals(listOf(selected.fileName),queried)
    }

    @Test fun nameSpecificLayoutAndGenericFallbackRemainDeviceIndependent() {
        val generic=mapOf(30 to "A",31 to "S")
        val named=DeviceKeyLayoutPolicy.resolve(123,456,"Compact Keyboard",generic) {
            if(it=="Compact_Keyboard.kl") "key 31 BUTTON_1" else null
        }
        val fallback=DeviceKeyLayoutPolicy.resolve(999,999,"Other Keyboard",generic) { null }
        assertEquals(mapOf(30 to "A",31 to "BUTTON_1"),named.keys)
        assertEquals(generic,fallback.keys)
        assertEquals("Generic.kl",fallback.fileName)
        assertEquals(mapOf(30 to "A",31 to "S"),generic)
    }

    @Test fun unknownVendorLabelStillOverridesGenericRatherThanInventingKnownKey() {
        val selected=DeviceKeyLayoutPolicy.resolve(1,2,"Keyboard",mapOf(30 to "A")) {
            if(it.startsWith("Vendor_")) "key 30 FUTURE_UNSUPPORTED_KEY" else null
        }
        assertEquals("FUTURE_UNSUPPORTED_KEY",selected.keys[30])
    }

    @Test fun generatedNamesCannotEscapeAWhitelistedDirectory() {
        val names=DeviceKeyLayoutPolicy.fileNames(0x046d,0xc08b,"../../日本語 /Keyboard:1")
        assertEquals("Vendor_046d_Product_c08b.kl",names.first())
        assertEquals("Generic.kl",names.last())
        assertTrue(names.all { Regex("[A-Za-z0-9_-]+\\.kl").matches(it) })
        assertEquals(listOf("Generic.kl"),DeviceKeyLayoutPolicy.fileNames(-1,100_000,""))
        assertEquals(listOf("Generic.kl"),DeviceKeyLayoutPolicy.fileNames(0,0,"x".repeat(241)))
    }

    @Test fun publishedKeyTablesCannotBeMutatedByCallers() {
        val parsed=KeyLayoutParser.parse("key 30 A")
        assertThrows(UnsupportedOperationException::class.java) { (parsed as MutableMap)[30]="B" }
        val resolved=DeviceKeyLayoutPolicy.resolve(0,0,"Keyboard",parsed) { null }
        assertThrows(UnsupportedOperationException::class.java) { (resolved.keys as MutableMap)[30]="B" }
    }
}
