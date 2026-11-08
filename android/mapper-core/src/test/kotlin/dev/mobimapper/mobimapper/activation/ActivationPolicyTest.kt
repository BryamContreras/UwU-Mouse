package dev.mobimapper.mobimapper.activation

import dev.mobimapper.mobimapper.session.SessionMode
import org.junit.Assert.*
import org.junit.Test

class ActivationPolicyTest {
    @Test fun compiledPreviewUsesItsOwnApkAuthorityProcessAndPrivateBootstrapFiles() {
        assertEquals(ActivationPolicy.bootstrap,ActivationPolicy.bootstrapFor("dev.mobimapper.mobimapper"))
        val text=ActivationPolicy.bootstrapFor("dev.mobimapper.mobimapper.nativepreview")
        assertTrue(text.contains("pm path dev.mobimapper.mobimapper.nativepreview"))
        assertTrue(text.contains("pidof mobimapper_backend_nativepreview "))
        assertTrue(text.contains("--nice-name=mobimapper_backend_nativepreview"))
        assertTrue(text.contains("dev.mobimapper.mobimapper.backend.ShellMain dev.mobimapper.mobimapper.nativepreview </dev/null"))
        assertTrue(text.contains("uwu-mouse-start-nativepreview.lock"))
        assertFalse(text.contains("mobimapper-backend.log"))
        for(value in listOf("another.app","dev.mobimapper.mobimapper.x;id","dev.mobimapper.mobimapper.x.y"))
            assertThrows(IllegalArgumentException::class.java) { ActivationPolicy.bootstrapFor(value) }
    }
    @Test fun onlySixAsciiDigitsAndValidPorts() {
        assertEquals("012345",ActivationPolicy.pairingCode(" 012345 "))
        for(value in listOf("12345","1234567","１２３４５６","12345;","12\n3456"))
            assertThrows(IllegalArgumentException::class.java) { ActivationPolicy.pairingCode(value) }
        for(port in listOf(0,-1,65536)) assertThrows(IllegalArgumentException::class.java) { ActivationPolicy.port(port) }
        assertEquals(65535,ActivationPolicy.port(65535))
    }
    @Test fun refusesActivationForEveryOwnedOrUnreleasedSessionState() {
        fun allowed(mode:SessionMode,active:Boolean=false,transition:Boolean=false,contacts:Int=0,
                    release:Boolean=false,session:Boolean=false,editor:Boolean=false,captured:Boolean=false) =
            ActivationPolicy.requireIdle(mode,active,transition,contacts,release,session,editor,captured)
        for(mode in listOf(SessionMode.STOPPED,SessionMode.PAUSED)) {
            val attempts=listOf<()->Unit>({allowed(mode,active=true)},{allowed(mode,transition=true)},
                {allowed(mode,contacts=1)},{allowed(mode,release=true)},{allowed(mode,session=true)},
                {allowed(mode,editor=true)},{allowed(mode,captured=true)})
            attempts.forEach { attempt -> assertThrows(IllegalStateException::class.java) { attempt() } }
        }
    }
    @Test fun closedEmptySessionCanReactivateAfterBackendLossPausesIt() {
        for(mode in listOf(SessionMode.STOPPED,SessionMode.PAUSED))
            ActivationPolicy.requireIdle(mode,false,false,0,false,false,false,false)
    }
    @Test fun playingEditingRecoveryAndBlockedModesCannotActivateEvenWithoutContacts() {
        for(mode in listOf(SessionMode.PLAYING,SessionMode.EDITING,SessionMode.RECOVERING,SessionMode.BLOCKED))
            assertThrows(IllegalStateException::class.java) {
                ActivationPolicy.requireIdle(mode,false,false,0,false,false,false,false)
            }
    }
}
