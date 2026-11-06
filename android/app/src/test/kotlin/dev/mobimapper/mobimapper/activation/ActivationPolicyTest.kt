package dev.mobimapper.mobimapper.activation

import org.junit.Assert.*
import org.junit.Test

class ActivationPolicyTest {
    @Test fun onlySixAsciiDigitsAndValidPorts() {
        assertEquals("012345",ActivationPolicy.pairingCode(" 012345 "))
        for(value in listOf("12345","1234567","１２３４５６","12345;","12\n3456"))
            assertThrows(IllegalArgumentException::class.java) { ActivationPolicy.pairingCode(value) }
        for(port in listOf(0,-1,65536)) assertThrows(IllegalArgumentException::class.java) { ActivationPolicy.port(port) }
        assertEquals(65535,ActivationPolicy.port(65535))
    }
    @Test fun refusesActivationForEveryOwnedOrUnreleasedSessionState() {
        fun allowed(stopped:Boolean=true,active:Boolean=false,transition:Boolean=false,contacts:Int=0,
                    release:Boolean=false,session:Boolean=false,editor:Boolean=false,captured:Boolean=false) =
            ActivationPolicy.requireIdle(stopped,active,transition,contacts,release,session,editor,captured)
        allowed()
        val attempts=listOf<()->Unit>({allowed(stopped=false)},{allowed(active=true)},{allowed(transition=true)},
            {allowed(contacts=1)},{allowed(release=true)},{allowed(session=true)},{allowed(editor=true)},{allowed(captured=true)})
        attempts.forEach { attempt -> assertThrows(IllegalStateException::class.java) { attempt() } }
    }
}
