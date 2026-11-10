package dev.uwumouse.app.backend

import org.junit.Assert.*
import org.junit.Test

class DisplayResolutionControllerTest {
    private class Journal:DisplayResolutionJournal {
        var records=emptyList<OwnedDisplayResolution>()
        var failSave=false
        override fun load()=records.toList()
        override fun save(records:List<OwnedDisplayResolution>) {
            check(!failSave)
            this.records=records.toList()
        }
    }
    private class Displays:DisplayResolutionAccess {
        override var supported=true
        val values=linkedMapOf(
            0 to DisplayResolutionSnapshot(0,"local:phone","Phone",1,1080,2316,1080,2316),
            2 to DisplayResolutionSnapshot(2,"local:monitor","Monitor",0,1920,1080,1920,1080))
        val writes=mutableListOf<String>()
        var nextSet:((Int,String,Int,Int)->Unit)?=null
        var reads=0
        var beforeRead:((Int)->Unit)?=null
        override fun read(displayId:Int):DisplayResolutionSnapshot? {
            reads++;beforeRead?.invoke(reads)
            return values[displayId]
        }
        override fun setSize(displayId:Int,expectedUniqueId:String,width:Int,height:Int) {
            val old=requireNotNull(values[displayId]);check(old.uniqueId==expectedUniqueId)
            writes+="set:$displayId:$width:$height"
            val intercepted=nextSet;nextSet=null
            if(intercepted!=null) intercepted(displayId,expectedUniqueId,width,height)
            else values[displayId]=old.copy(width=width,height=height)
        }
        override fun clearSize(displayId:Int,expectedUniqueId:String) {
            val old=requireNotNull(values[displayId]);check(old.uniqueId==expectedUniqueId)
            writes+="clear:$displayId"
            values[displayId]=old.copy(width=old.physicalWidth,height=old.physicalHeight)
        }
    }

    @Test fun applyingAndRestoringExternalDisplayNeverMutatesThePhone() {
        val displays=Displays();val phone=displays.values.getValue(0);val journal=Journal()
        val controller=DisplayResolutionController(displays,journal)
        val changed=controller.apply(2,"local:monitor",1280,720)
        assertTrue(changed.operationSucceeded);assertTrue(changed.changed);assertTrue(changed.canRestore)
        assertEquals(1280,changed.snapshot!!.width)
        assertEquals(phone,displays.values[0])
        assertTrue(controller.restore(2,"local:monitor").operationSucceeded)
        assertEquals(phone,displays.values[0])
        assertEquals(listOf("set:2:1280:720","clear:2"),displays.writes)
        assertTrue(journal.records.isEmpty())
    }

    @Test fun primaryDisplayUsesItsExplicitIdentityAndNaturalSize() {
        val displays=Displays();val external=displays.values.getValue(2)
        val controller=DisplayResolutionController(displays,Journal())
        val result=controller.apply(0,"local:phone",1080,1920)
        assertTrue(result.operationSucceeded)
        assertEquals(1,result.snapshot!!.rotation)
        assertEquals(1080,result.snapshot.width);assertEquals(1920,result.snapshot.height)
        assertEquals(external,displays.values[2])
    }

    @Test fun reusedDisplayIdAndMissingDisplayCannotFallBackToThePrimary() {
        val displays=Displays();val phone=displays.values.getValue(0)
        val controller=DisplayResolutionController(displays,Journal())
        assertFalse(controller.apply(2,"a-disconnected-monitor",1280,720).operationSucceeded)
        displays.values.remove(2)
        assertFalse(controller.apply(2,"local:monitor",1280,720).available)
        assertFalse(controller.restore(2,"local:monitor").available)
        assertEquals(phone,displays.values[0]);assertTrue(displays.writes.isEmpty())
    }

    @Test fun restorePreservesTheOverrideThatExistedBeforeUwuMouse() {
        val displays=Displays()
        displays.values[2]=displays.values.getValue(2).copy(width=1600,height=900)
        val journal=Journal();val controller=DisplayResolutionController(displays,journal)
        assertTrue(controller.apply(2,"local:monitor",1280,720).operationSucceeded)
        assertTrue(controller.restore(2,"local:monitor").operationSucceeded)
        assertEquals(1600,displays.values.getValue(2).width)
        assertEquals(900,displays.values.getValue(2).height)
        assertFalse(displays.writes.any { it.startsWith("clear:") })
    }

    @Test fun repeatedChangesRetainTheOriginalBeforeImageAcrossProcessRestart() {
        val displays=Displays();val journal=Journal()
        displays.values[2]=displays.values.getValue(2).copy(width=1600,height=900)
        val controller=DisplayResolutionController(displays,journal)
        assertTrue(controller.apply(2,"local:monitor",1280,720).operationSucceeded)
        assertTrue(controller.apply(2,"local:monitor",1024,768).operationSucceeded)
        val restarted=DisplayResolutionController(displays,journal)
        assertTrue(restarted.status(2).managed)
        assertTrue(restarted.restore(2,"local:monitor").operationSucceeded)
        assertEquals(1600,displays.values.getValue(2).width)
        assertEquals(900,displays.values.getValue(2).height)
    }

    @Test fun firmwareClampingIsRejectedAndTheImmediateBeforeImageIsRecovered() {
        val displays=Displays();val journal=Journal();val controller=DisplayResolutionController(displays,journal)
        displays.nextSet={ id,_,_,_ -> displays.values[id]=displays.values.getValue(id).copy(width=2048,height=1152) }
        val result=controller.apply(2,"local:monitor",3200,1800)
        assertFalse(result.operationSucceeded);assertFalse(result.changed)
        assertEquals(1920,displays.values.getValue(2).width)
        assertEquals(1080,displays.values.getValue(2).height)
        assertTrue(journal.records.isEmpty())
    }

    @Test fun aClampedSecondChangeRestoresTheManagedSizeAndKeepsItsOriginalRestoreTarget() {
        val displays=Displays();val journal=Journal();val controller=DisplayResolutionController(displays,journal)
        displays.values[2]=displays.values.getValue(2).copy(width=1600,height=900)
        assertTrue(controller.apply(2,"local:monitor",1280,720).operationSucceeded)
        displays.nextSet={ id,_,_,_ -> displays.values[id]=displays.values.getValue(id).copy(width=2048,height=1152) }
        val rejected=controller.apply(2,"local:monitor",3200,1800)
        assertFalse(rejected.operationSucceeded);assertTrue(rejected.canRestore)
        assertEquals(1280,displays.values.getValue(2).width)
        assertTrue(controller.restore(2,"local:monitor").operationSucceeded)
        assertEquals(1600,displays.values.getValue(2).width)
    }

    @Test fun firmwareNoOpDoesNotClaimSuccessOrAnOwnedOverride() {
        val displays=Displays();val journal=Journal();val controller=DisplayResolutionController(displays,journal)
        displays.nextSet={ _,_,_,_ -> }
        val result=controller.apply(2,"local:monitor",1280,720)
        assertFalse(result.operationSucceeded);assertFalse(result.managed);assertFalse(result.changed)
        assertTrue(journal.records.isEmpty())
        assertEquals(listOf("set:2:1280:720"),displays.writes)
    }

    @Test fun ambiguousSetterFailureAfterMutationRestoresThePriorConfiguration() {
        val displays=Displays();val journal=Journal();val controller=DisplayResolutionController(displays,journal)
        displays.nextSet={ id,_,width,height ->
            displays.values[id]=displays.values.getValue(id).copy(width=width,height=height)
            error("Reply failed after applying")
        }
        val result=controller.apply(2,"local:monitor",1280,720)
        assertFalse(result.operationSucceeded);assertEquals(1920,displays.values.getValue(2).width)
        assertTrue(journal.records.isEmpty())
    }

    @Test fun externalChangesArePreservedWhenRestoringAnOldLease() {
        val displays=Displays();val journal=Journal();val controller=DisplayResolutionController(displays,journal)
        assertTrue(controller.apply(2,"local:monitor",1280,720).operationSucceeded)
        displays.values[2]=displays.values.getValue(2).copy(width=1600,height=900)
        val result=controller.restore(2,"local:monitor")
        assertFalse(result.operationSucceeded);assertFalse(result.canRestore)
        assertEquals(1600,displays.values.getValue(2).width)
        assertEquals(listOf("set:2:1280:720"),displays.writes)
        assertTrue(journal.records.isEmpty())
    }

    @Test fun externalChangeDuringRejectedApplyIsNotOverwrittenByRollback() {
        val displays=Displays();val journal=Journal();val controller=DisplayResolutionController(displays,journal)
        displays.nextSet={ id,_,_,_ -> displays.values[id]=displays.values.getValue(id).copy(width=2048,height=1152) }
        displays.beforeRead={ count -> if(count==3) displays.values[2]=displays.values.getValue(2).copy(width=1600,height=900) }
        val result=controller.apply(2,"local:monitor",3200,1800)
        assertFalse(result.operationSucceeded);assertFalse(result.canRestore)
        assertEquals(1600,displays.values.getValue(2).width)
        assertEquals(1,displays.writes.size)
        controller.restore(2,"local:monitor")
        assertEquals(1,displays.writes.size)
    }

    @Test fun aReconnectedDifferentMonitorIsNeverRestoredUsingAnOldId() {
        val displays=Displays();val journal=Journal();val controller=DisplayResolutionController(displays,journal)
        assertTrue(controller.apply(2,"local:monitor",1280,720).operationSucceeded)
        displays.values[2]=displays.values.getValue(2).copy(uniqueId="local:replacement",width=1600,height=900)
        val result=DisplayResolutionController(displays,journal).restore(2,"local:monitor")
        assertFalse(result.operationSucceeded)
        assertEquals("local:replacement",displays.values.getValue(2).uniqueId)
        assertEquals(1600,displays.values.getValue(2).width)
        assertEquals(1,displays.writes.size)
    }

    @Test fun failureToPersistTheBeforeImagePreventsAnyDisplayWrite() {
        val displays=Displays();val journal=Journal().apply { failSave=true }
        val result=DisplayResolutionController(displays,journal).apply(2,"local:monitor",1280,720)
        assertFalse(result.operationSucceeded);assertTrue(displays.writes.isEmpty())
    }

    @Test fun invalidOrUnsupportedRequestsCannotMutateAnyDisplay() {
        val displays=Displays();val controller=DisplayResolutionController(displays,Journal())
        for(size in listOf(0,499,8193,Int.MAX_VALUE))
            assertFalse(controller.apply(2,"local:monitor",size,720).operationSucceeded)
        assertFalse(controller.apply(2,"",1280,720).operationSucceeded)
        displays.supported=false
        assertFalse(controller.status(2).available)
        assertFalse(controller.apply(2,"local:monitor",1280,720).operationSucceeded)
        assertTrue(displays.writes.isEmpty())
    }

    @Test fun corruptRecoveryDataDisablesOnlyTheResolutionFeature() {
        val displays=Displays();val journal=Journal().apply {
            records=listOf(OwnedDisplayResolution(2,"local:monitor",0,1080,false,1280,720))
        }
        val controller=DisplayResolutionController(displays,journal)
        assertFalse(controller.supported);assertFalse(controller.status(2).available)
        assertFalse(controller.apply(2,"local:monitor",1280,720).operationSucceeded)
        assertTrue(displays.writes.isEmpty())
    }

    @Test fun admissionRejectsAnArmedEmptyStreamAndEveryUnreleasedInputOwner() {
        fun allowed(closed:Boolean=true,possible:Int=0,confirmed:Int=0,release:Boolean=false,keys:Int=0,
            keyRelease:Boolean=false,capture:Boolean=false,recovery:Boolean=false)=
            DisplayResolutionAdmission.mayChange(closed,possible,confirmed,release,keys,keyRelease,capture,recovery)
        assertTrue(allowed())
        assertFalse(allowed(closed=false));assertFalse(allowed(possible=1));assertFalse(allowed(confirmed=1))
        assertFalse(allowed(release=true));assertFalse(allowed(keys=1));assertFalse(allowed(keyRelease=true))
        assertFalse(allowed(capture=true));assertFalse(allowed(recovery=true))
    }
}
