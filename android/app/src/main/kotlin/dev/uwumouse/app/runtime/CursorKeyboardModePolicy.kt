package dev.uwumouse.app.runtime

/**
 * Runtime text-input choice for one cursor cycle. Showing a cursor never opts in.
 * A menu's temporary mapping pause can suspend availability without losing an
 * existing manual choice; camera/stop/context loss ends the cycle permanently.
 */
internal class CursorKeyboardModePolicy {
    data class Request internal constructor(internal val revision:Long,internal val enabled:Boolean)
    private data class Owner(val profileId:String,val packageName:String,val displayId:Int)

    private var revision=0L
    private var cycleOpen=false
    private var captureReady=false
    private var captureReplacementAuthorized=false
    private var pendingManualChoice:Boolean?=null
    private var owner:Owner?=null
    @Volatile var available=false; private set
    @Volatile var enabled=false; private set

    /** The menu may finish arming its engine before the posted source replacement runs. */
    @Synchronized fun authorizeCaptureReplacement() {
        if(cycleOpen && captureReady) captureReplacementAuthorized=true
    }

    /** Called immediately before deliberately retiring an existing native lease. */
    @Synchronized fun beginCaptureReplacement() {
        val retain=captureReplacementAuthorized && cycleOpen && captureReady
        if(!retain) return
        if(pendingManualChoice==null) pendingManualChoice=enabled
        revision++
        captureReady=false;available=false;enabled=false
    }

    /** A no-op source update has completed the menu rearm without replacing a lease. */
    @Synchronized fun finishUnchangedCapture() {
        if(pendingManualChoice==null) captureReplacementAuthorized=false
    }

    @Synchronized fun continuationPending():Boolean=captureReplacementAuthorized || pendingManualChoice!=null

    /** Real loss, failed acquisition and Android-owned held input must never restore a choice. */
    @Synchronized fun captureUnavailable() {
        captureReplacementAuthorized=false;pendingManualChoice=null
        observe(false,captureReady=false)
    }

    /** A same-profile menu rearm may continue; another profile/display never inherits text mode. */
    @Synchronized fun selectOwner(profileId:String,packageName:String,displayId:Int) {
        val next=Owner(profileId,packageName,displayId)
        if(owner==next) return
        observe(false,endCycle=true,captureReady=captureReady)
        owner=next
    }

    @Synchronized fun observe(cursorAvailable:Boolean,endCycle:Boolean=false,captureReady:Boolean=true) {
        if(this.captureReady!=captureReady) revision++
        this.captureReady=captureReady
        if(endCycle) {
            if(cycleOpen || available || enabled) revision++
            captureReplacementAuthorized=false;pendingManualChoice=null
            cycleOpen=false;available=false;enabled=false
            return
        }
        if(!captureReady) {
            if(available || enabled) revision++
            if(pendingManualChoice==null) captureReplacementAuthorized=false
            available=false;enabled=false
            return
        }
        if(cursorAvailable && !cycleOpen) {
            revision++;cycleOpen=true;enabled=false
        }
        if(cursorAvailable) pendingManualChoice?.let {
            enabled=it;pendingManualChoice=null;captureReplacementAuthorized=false;revision++
        }
        if(available!=cursorAvailable) revision++
        available=cursorAvailable
    }

    /** A UI intent cannot enable a later cursor cycle after its original one hid. */
    @Synchronized fun requestToggle():Request? =
        if(available) Request(revision,!enabled) else null

    @Synchronized fun apply(request:Request):Boolean {
        if(!available || request.revision!=revision) return false
        enabled=request.enabled
        revision++
        return true
    }

    /** Call only after existing DOWN owners and session shortcuts have been handled. */
    @Synchronized fun blocksNewOrdinaryKeys():Boolean=cycleOpen && captureReady && !enabled

    @Synchronized fun snapshot():Map<String,Any> = mapOf(
        "available" to available,"enabled" to enabled,"captureReady" to captureReady,
        "captureReplacementAuthorized" to captureReplacementAuthorized,
        "captureReplacementPending" to (pendingManualChoice!=null),
        "pendingManualChoice" to (pendingManualChoice ?: false),
        "cursorCycleOpen" to cycleOpen,"revision" to revision,
        "profileId" to (owner?.profileId ?: ""),"packageName" to (owner?.packageName ?: ""),
        "displayId" to (owner?.displayId ?: -1))
}
