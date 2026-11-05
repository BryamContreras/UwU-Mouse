package dev.mobimapper.mobimapper.session

enum class SessionMode(val wire:String) {
    STOPPED("stopped"), PAUSED("paused"), EDITING("editing"), PLAYING("playing"), RECOVERING("recovering"), BLOCKED("blocked")
}

/** Bounds/layer metadata only; no window title or view content is part of context policy. */
data class ContextWindowFrame(
    val displayId:Int, val layer:Int,
    val left:Int, val top:Int, val right:Int, val bottom:Int,
)

/** Only metadata needed to classify an external window; unknown types remain conservative. */
data class ContextWindowCandidate(val type:Int,val packageName:String?,val frame:ContextWindowFrame)

/** Shared by context monitoring and the editor release barrier, independent of Android UI. */
object SessionPolicy {
    fun mayEdit(contacts:Int,releaseUnverified:Boolean,active:Boolean)=contacts==0 && !releaseUnverified && !active
    fun mayInstallConfiguration(mode:SessionMode,contacts:Int,releaseUnverified:Boolean)=
        mode in setOf(SessionMode.STOPPED,SessionMode.PAUSED,SessionMode.EDITING) && contacts==0 && !releaseUnverified
    fun authorizedContext(target:String,foregroundApplication:String?,ownEditorFocused:Boolean,targetWindowVisible:Boolean,
                          systemWindowCoversTarget:Boolean=false):Boolean =
        targetWindowVisible && !systemWindowCoversTarget &&
            (foregroundApplication==target || (ownEditorFocused && targetWindowVisible && foregroundApplication==null))

    /** Same eligibility drives blocked touch points and full-window context barriers. */
    fun potentiallyBlockingWindow(target:ContextWindowFrame,candidate:ContextWindowCandidate,
                                  targetPackage:String,ownPackage:String):Boolean =
        target.displayId==0 && candidate.frame.displayId==target.displayId && candidate.frame.layer>target.layer &&
            candidate.type!=APPLICATION_WINDOW_TYPE && candidate.packageName!=ownPackage && candidate.packageName!=targetPackage

    fun coveringWindow(target:ContextWindowFrame,candidates:List<ContextWindowCandidate>,
                       targetPackage:String,ownPackage:String):ContextWindowCandidate? = candidates.firstOrNull {
        potentiallyBlockingWindow(target,it,targetPackage,ownPackage) && windowFullyCoversTarget(target,it.frame)
    }

    private fun windowFullyCoversTarget(target:ContextWindowFrame,cover:ContextWindowFrame):Boolean =
        target.displayId==0 && cover.displayId==target.displayId && cover.layer>target.layer &&
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
