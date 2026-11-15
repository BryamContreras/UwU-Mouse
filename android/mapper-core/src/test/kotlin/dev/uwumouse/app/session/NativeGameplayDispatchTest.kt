package dev.uwumouse.app.session

import dev.uwumouse.app.profile.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class NativeGameplayDispatchTest {
    private val ordinary=NativeGameplayPolicy(setOf(51,47,29,32,62),1)
    private fun route(buttons:Int=0,generation:Long=7,epoch:Int=42,policy:NativeGameplayPolicy=ordinary)=
        NativeMotionDispatchGate.Route(11,generation,buttons,epoch,policy)
    private fun gate()=NativeMotionDispatchGate().apply { resetButtons(true);publish(route()) }

    @Test fun repeatedMouseAndJoystickEdgesRemainOrderedWithoutAnyMainReservation() {
        val gate=gate()
        val expected=mutableListOf<String>()
        val received=mutableListOf<String>()
        repeat(1000) { cycle ->
            expected+="W:down:$cycle"
            assertEquals(NativeMotionDispatchGate.Dispatch.DIRECT,gate.dispatchKey(11,2,17,51,true,false) { snapshot,code,down ->
                assertEquals(7L,snapshot.generation);assertEquals(42,snapshot.pointerEpoch)
                received+="W:${if(down) "down" else "up"}:$cycle"
                assertEquals(51,code);NativeMotionDispatchGate.Admission.ACCEPTED
            })
            for(buttons in listOf(1,0)) {
                expected+="motion:$cycle:$buttons";expected+="M1:$buttons:$cycle"
                assertEquals(NativeMotionDispatchGate.Dispatch.DIRECT,gate.dispatchButtons(11,buttons) { snapshot,changed ->
                    assertEquals(1,changed)
                    assertEquals(1-buttons,snapshot.buttons)
                    received+="motion:$cycle:$buttons" // One report's motion precedes its edge.
                    received+="M1:$buttons:$cycle"
                    NativeMotionDispatchGate.Admission.ACCEPTED
                })
                assertEquals(buttons,gate.physicalButtons)
                assertNotNull(gate.directRoute(11,buttons))
                assertEquals(NativeMotionDispatchGate.Dispatch.DIRECT,gate.dispatch(11,buttons) { true })
            }
            expected+="W:up:$cycle"
            assertEquals(NativeMotionDispatchGate.Dispatch.DIRECT,gate.dispatchKey(11,2,17,51,false,false) { snapshot,code,down ->
                assertEquals(7L,snapshot.generation);assertEquals(42,snapshot.pointerEpoch)
                assertEquals(51,code);assertFalse(down)
                received+="W:up:$cycle";NativeMotionDispatchGate.Admission.ACCEPTED
            })
            assertEquals(0,gate.pendingCallbacks);assertEquals(0,gate.ownedKeyCount)
        }
        assertEquals(expected,received)
    }

    @Test fun anEarlierMainControlBlocksGameplayAndMotionAndItsOwnedKeyUpUsesTheOriginalPress() {
        val gate=gate()
        assertEquals(NativeMotionDispatchGate.Dispatch.DIRECT,gate.dispatchKey(11,2,17,51,true,false) { _,_,_->NativeMotionDispatchGate.Admission.ACCEPTED })
        assertTrue(gate.defer()) // A stop/editor/profile/control callback is ahead of all following input.
        assertEquals(NativeMotionDispatchGate.Dispatch.DEFERRED,gate.dispatchKey(11,2,17,51,false,false) { _,_,_->fail("UP overtook main");NativeMotionDispatchGate.Admission.ACCEPTED })
        assertEquals(NativeMotionDispatchGate.Dispatch.DEFERRED,gate.dispatchButtons(11,1) { _,_->fail("M1 overtook main");NativeMotionDispatchGate.Admission.ACCEPTED })
        assertNull(gate.directRoute(11,0))
        val owner=requireNotNull(gate.ownedKey(2,17,true)) // Deferred main path owns this physical UP.
        assertEquals(51,owner.code);assertEquals(route(),owner.route)
        assertEquals(0,gate.ownedKeyCount)
        assertNull(gate.ownedKey(2,17,true)) // No duplicate release.
        gate.complete(route())
        assertNotNull(gate.directRoute(11,0))
    }

    @Test fun aPausedOrReplacedSessionConsumesAnOwnedUpWithoutRelabelingItOrForwardingIt() {
        val gate=gate()
        assertEquals(NativeMotionDispatchGate.Dispatch.DIRECT,gate.dispatchKey(11,2,17,51,true,false) { _,_,_->NativeMotionDispatchGate.Admission.ACCEPTED })
        gate.publish(null) // UI/menu/pause takes the gameplay route away.
        val current=route(generation=8,epoch=47)
        var queuedToNewSession=0
        val outcome=gate.dispatchKey(11,2,17,51,false,false) { original,_,down ->
            assertFalse(down)
            assertEquals(7L,original.generation);assertEquals(42,original.pointerEpoch)
            if(original.generation==current.generation && original.pointerEpoch==current.pointerEpoch) {
                queuedToNewSession++;NativeMotionDispatchGate.Admission.ACCEPTED
            } else NativeMotionDispatchGate.Admission.STALE
        }
        // STALE is a consumed callback, not DEFERRED passthrough into the menu/new game.
        assertEquals(NativeMotionDispatchGate.Dispatch.STALE,outcome)
        assertEquals(0,queuedToNewSession);assertEquals(0,gate.ownedKeyCount)
        assertNull(gate.directRoute(11,0))
    }

    @Test fun cameraCursorRoundTripSuppressesTheHeldMouseTailButAllowsTheNextWholeClick() {
        val gate=gate()
        assertEquals(NativeMotionDispatchGate.Dispatch.DIRECT,gate.dispatchButtons(11,1) { _,changed ->
            assertEquals(1,changed);NativeMotionDispatchGate.Admission.ACCEPTED
        })
        gate.publish(null)
        gate.rebaseButtons(gate.physicalButtons) // Mode handoff releases mapped contacts, keeping a physical baseline.
        assertEquals(1,gate.observeButtons(0).released)
        assertEquals(0,gate.buttonTransitions(0)) // Held game's UP cannot become a cursor click.
        gate.publish(route(epoch=44))
        var edges=0
        for(buttons in listOf(1,0)) assertEquals(NativeMotionDispatchGate.Dispatch.DIRECT,
            gate.dispatchButtons(11,buttons) { snapshot,changed ->
                assertEquals(44,snapshot.pointerEpoch);assertEquals(1,changed)
                edges++;NativeMotionDispatchGate.Admission.ACCEPTED
            })
        assertEquals(2,edges)
    }

    @Test fun alreadyMainOwnedKeysAndUnassignedKeysNeverManufactureADirectUpOrPassthroughDown() {
        val gate=gate()
        for(code in listOf(51,44,138)) {
            assertEquals(NativeMotionDispatchGate.Dispatch.DEFERRED,gate.dispatchKey(11,2,17,code,false,false) { _,_,_->
                fail("No native owner admitted this DOWN");NativeMotionDispatchGate.Admission.ACCEPTED
            })
        }
        for(code in listOf(44,138)) assertEquals(NativeMotionDispatchGate.Dispatch.DEFERRED,
            gate.dispatchKey(11,2,17,code,true,false) { _,_,_->fail("Control/passthrough key bypassed main");NativeMotionDispatchGate.Admission.ACCEPTED })
        assertEquals(0,gate.ownedKeyCount)
    }

    @Test fun keyIdentityKeepsDevicesSeparateAndOwnedRepeatCannotStartAnAdditionalAction() {
        val gate=gate()
        var edges=0
        for(device in listOf(2,3)) assertEquals(NativeMotionDispatchGate.Dispatch.DIRECT,
            gate.dispatchKey(11,device,17,51,true,false) { _,_,_->edges++;NativeMotionDispatchGate.Admission.ACCEPTED })
        assertEquals(2,gate.ownedKeyCount)
        repeat(100) { assertEquals(NativeMotionDispatchGate.Dispatch.DIRECT,
            gate.dispatchKey(11,2,17,51,true,true) { _,_,_->fail("repeat duplicated DOWN");NativeMotionDispatchGate.Admission.ACCEPTED }) }
        assertEquals(2,edges)
        assertNotNull(gate.ownedKey(2,17,true));assertEquals(1,gate.ownedKeyCount)
        gate.removeDeviceKeys(3);assertEquals(0,gate.ownedKeyCount)
    }

    @Test fun sourceQuiescenceIncludesOwnedKeysAndPhysicalButtonsUntilBothTailsRetire() {
        val gate=gate()
        gate.dispatchKey(11,2,17,51,true,false) { _,_,_->NativeMotionDispatchGate.Admission.ACCEPTED }
        gate.dispatchButtons(11,1) { _,_->NativeMotionDispatchGate.Admission.ACCEPTED }
        gate.publish(null) // Source change waits; no new gameplay DOWN can be admitted.
        assertEquals(1,gate.ownedKeyCount);assertEquals(1,gate.physicalButtons)
        assertEquals(NativeMotionDispatchGate.Dispatch.STALE,gate.dispatchKey(11,2,17,51,false,false) { _,_,_->NativeMotionDispatchGate.Admission.STALE })
        assertEquals(0,gate.ownedKeyCount);assertEquals(1,gate.physicalButtons)
        gate.observeButtons(0);gate.buttonTransitions(0)
        assertEquals(0,gate.physicalButtons)
        gate.resetButtons();gate.clearKeyOwners()
        assertFalse(gate.physicalButtonsKnown)
    }

    @Test fun overflowIsReportedAfterOwnershipMonitorAndRetainsThePhysicalKeyTail() {
        val gate=gate()
        val result=gate.dispatchKey(11,2,17,51,true,false) { _,_,_->
            assertTrue(Thread.holdsLock(gate))
            NativeMotionDispatchGate.Admission.OVERFLOW // Queue never invokes pause here.
        }
        assertEquals(NativeMotionDispatchGate.Dispatch.OVERFLOW,result)
        assertFalse(Thread.holdsLock(gate)) // The host may now invoke its ordinary pause/release callback.
        assertEquals(1,gate.ownedKeyCount)
        assertNull(gate.directRoute(11,0))
        assertNotNull(gate.ownedKey(2,17,true))
    }

    @Test fun lifecycleInvalidationWaitsForQueueOnlyAdmissionAndPreventsSubsequentDowns() {
        val gate=gate()
        val entered=CountDownLatch(1);val finishOffer=CountDownLatch(1);val invalidated=CountDownLatch(1)
        val failure=AtomicReference<Throwable?>()
        val ordering=mutableListOf<String>()
        val producer=Thread {
            try {
                gate.dispatchButtons(11,1) { _,_->
                    entered.countDown();assertTrue(finishOffer.await(3,TimeUnit.SECONDS))
                    ordering+="enqueue";NativeMotionDispatchGate.Admission.ACCEPTED
                }
            } catch(error:Throwable) { failure.set(error) }
        }
        producer.start();assertTrue(entered.await(3,TimeUnit.SECONDS))
        val main=Thread { gate.publish(null);ordering+="invalidate";invalidated.countDown() }
        main.start()
        assertFalse(invalidated.await(30,TimeUnit.MILLISECONDS))
        finishOffer.countDown();producer.join(3000);main.join(3000)
        failure.get()?.let { throw it }
        assertFalse(producer.isAlive);assertFalse(main.isAlive)
        assertEquals(listOf("enqueue","invalidate"),ordering)
        assertEquals(NativeMotionDispatchGate.Dispatch.DEFERRED,gate.dispatchButtons(11,0) { _,_->fail("bypassed UI handoff");NativeMotionDispatchGate.Admission.ACCEPTED })
    }

    @Test fun policyAllowsOnlySimpleTapsAndDirectionsExcludingModifiersCommandsAndCadence() {
        val original=GameProfile.create("com.dts.freefireth","Ordinary controls",1920,1080,0)
        val profile=original.copy(calibration=original.calibration.copy(confirmed=true),
            action=original.action.copy(triggerKind="mouse",code=1),
            additionalActions=listOf(ActionSpec(.3f,.4f,code=62),ActionSpec(.4f,.4f,code=40,behavior=ActionSpec.REPEAT),
                ActionSpec(.5f,.5f,code=41,behavior=ActionSpec.CURSOR_TOGGLE)),
            shortcuts=SessionShortcuts(cameraToggle=138,stop=139,edit=140),camera=original.camera.copy(suspendKey=141))
        val prepared=ProfileCompiler.prepare(profile,1920,1080,0)
        val policy=NativeGameplayPolicy.prepare(prepared)
        assertEquals(ordinary,policy)
        assertEquals(0,NativeGameplayPolicy.prepare(prepared.copy(fireCadence=PreparedFireCadence(verticalPixelsPerSecond=10f))).buttons)
        // Defensive classification remains safe if a future compiler exposes a modified key/tap.
        val modifier=prepared.copy(bindings=prepared.bindings.copy(touches=listOf(
            dev.uwumouse.app.engine.PreparedTouch("modifier",100f,100f,key=59))))
        assertFalse(59 in NativeGameplayPolicy.prepare(modifier).keys)
    }
}
