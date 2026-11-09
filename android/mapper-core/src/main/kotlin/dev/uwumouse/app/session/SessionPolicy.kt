package dev.uwumouse.app.session

enum class SessionMode(val wire:String) {
    STOPPED("stopped"), PAUSED("paused"), EDITING("editing"), PLAYING("playing"), RECOVERING("recovering"), BLOCKED("blocked")
}

/** Bounds/layer metadata only; no window title or view content is part of context policy. */
data class ContextWindowFrame(
    val displayId:Int, val layer:Int,
    val left:Int, val top:Int, val right:Int, val bottom:Int,
)

/** Only metadata needed to classify an external window; unknown types remain conservative. */
data class ContextWindowCandidate(val type:Int,val packageName:String?,val frame:ContextWindowFrame,
                                  val focused:Boolean=false,val rootResolved:Boolean=false)

/** A real application window observed before this exact editor presentation covered it. */
data class EditorPresentationAnchor(val token:Long,val packageName:String,val frame:ContextWindowFrame)

/** Shared by context monitoring and the editor release barrier, independent of Android UI. */
object SessionPolicy {
    fun mayEdit(contacts:Int,releaseUnverified:Boolean,active:Boolean)=contacts==0 && !releaseUnverified && !active
    fun mayInstallConfiguration(mode:SessionMode,contacts:Int,releaseUnverified:Boolean)=
        mode in setOf(SessionMode.STOPPED,SessionMode.PAUSED,SessionMode.EDITING) && contacts==0 && !releaseUnverified
    fun authorizedContext(target:String,foregroundApplication:String?,ownEditorFocused:Boolean,targetWindowVisible:Boolean,
                          systemWindowCoversTarget:Boolean=false):Boolean =
        targetWindowVisible && !systemWindowCoversTarget &&
            (foregroundApplication==target || (ownEditorFocused && targetWindowVisible && foregroundApplication==null))

    /**
     * Retains only an idle editor's presentation when Android omits its covered application.
     * This never authorizes injection, supplies target bounds or replaces authorizedContext.
     * The caller also rejects a locked/disconnected display or a focused foreign window.
     */
    fun mayRetainEditorPresentation(mode:SessionMode,active:Boolean,contacts:Int,releaseUnverified:Boolean,
                                   targetPackage:String,targetDisplayId:Int,ownPackage:String,
                                   editorToken:Long,anchor:EditorPresentationAnchor?,
                                   brokerPackage:String?,brokerDisplayId:Int,brokerTrusted:Boolean,brokerFocusAgeMs:Long,
                                   focusedOwnWindow:ContextWindowCandidate?):Boolean {
        if(mode!=SessionMode.EDITING || active || contacts!=0 || releaseUnverified ||
            targetPackage.isBlank() || ownPackage.isBlank() || targetDisplayId<0 || editorToken<=0) return false
        if(anchor==null || anchor.token!=editorToken || anchor.packageName!=targetPackage ||
            !validFrameOnDisplay(anchor.frame,targetDisplayId)) return false
        if(!brokerTrusted || brokerPackage!=targetPackage || brokerDisplayId!=targetDisplayId ||
            brokerFocusAgeMs !in 0L..500L) return false
        val overlay=focusedOwnWindow ?: return false
        return overlay.focused && overlay.rootResolved && overlay.packageName==ownPackage &&
            overlay.type in 3..4 && validFrameOnDisplay(overlay.frame,targetDisplayId)
    }

    /**
     * WMS can publish its new focused editor before publishing that window as visible.
     * This short, non-renewable handoff retains presentation only; input remains unconfirmed.
     * The independent task query must still identify the admitted game on its original display.
     */
    fun mayRetainEditorFocusHandoff(mode:SessionMode,active:Boolean,armed:Boolean,contacts:Int,
                                   releaseUnverified:Boolean,captureStopped:Boolean,
                                   targetPackage:String,targetDisplayId:Int,editorToken:Long,
                                   anchor:EditorPresentationAnchor?,admittedUptimeMs:Long,nowUptimeMs:Long,
                                   metadataValid:Boolean,metadataError:String,metadataAgeMs:Long,
                                   brokerPackage:String?,brokerDisplayId:Int,brokerValid:Boolean,brokerQueryAgeMs:Long,
                                   displayReady:Boolean,locked:Boolean,foreignContext:Boolean):Boolean {
        if(mode!=SessionMode.EDITING || active || armed || contacts!=0 || releaseUnverified || !captureStopped ||
            !displayReady || locked || foreignContext || targetPackage.isBlank() || targetDisplayId<0 || editorToken<=0) return false
        if(anchor==null || anchor.token!=editorToken || anchor.packageName!=targetPackage ||
            !validFrameOnDisplay(anchor.frame,targetDisplayId)) return false
        if(admittedUptimeMs<0L || nowUptimeMs<admittedUptimeMs || nowUptimeMs-admittedUptimeMs>EDITOR_FOCUS_HANDOFF_MS) return false
        if(metadataValid || metadataError!="focused_window_not_visible" || metadataAgeMs !in 0L..500L) return false
        return brokerValid && brokerPackage==targetPackage && brokerDisplayId==targetDisplayId &&
            brokerQueryAgeMs in 0L..500L
    }

    const val EDITOR_FOCUS_HANDOFF_MS=500L

    private fun validFrameOnDisplay(frame:ContextWindowFrame,displayId:Int)=
        frame.displayId==displayId && frame.left>=0 && frame.top>=0 &&
            frame.right>frame.left && frame.bottom>frame.top

    /** Same eligibility drives blocked touch points and full-window context barriers. */
    fun potentiallyBlockingWindow(target:ContextWindowFrame,candidate:ContextWindowCandidate,
                                  targetPackage:String,ownPackage:String):Boolean =
        target.displayId>=0 && candidate.frame.displayId==target.displayId && candidate.frame.layer>target.layer &&
            candidate.packageName!=ownPackage && candidate.packageName!=targetPackage

    fun coveringWindow(target:ContextWindowFrame,candidates:List<ContextWindowCandidate>,
                       targetPackage:String,ownPackage:String):ContextWindowCandidate? = candidates.firstOrNull {
        potentiallyBlockingWindow(target,it,targetPackage,ownPackage) && windowFullyCoversTarget(target,it.frame)
    }

    private fun windowFullyCoversTarget(target:ContextWindowFrame,cover:ContextWindowFrame):Boolean =
        target.displayId>=0 && cover.displayId==target.displayId && cover.layer>target.layer &&
            target.right>target.left && target.bottom>target.top && cover.right>cover.left && cover.bottom>cover.top &&
            cover.left<=target.left && cover.top<=target.top && cover.right>=target.right && cover.bottom>=target.bottom

    /**
     * Compatibility predicate for known system-window callers. The general selector above also
     * handles unknown external types: accessibility exposes neither opacity nor touchable regions.
     * Full bounds are a conservative barrier, not proof that every covered pixel consumes input.
     */
    fun systemWindowCoversTarget(target:ContextWindowFrame, cover:ContextWindowFrame,
                                 isSystemWindow:Boolean, isOwnEditor:Boolean=false):Boolean =
        isSystemWindow && !isOwnEditor && windowFullyCoversTarget(target,cover)

    private const val APPLICATION_WINDOW_TYPE=1 // AccessibilityWindowInfo.TYPE_APPLICATION; keep this policy Android-free.
}
