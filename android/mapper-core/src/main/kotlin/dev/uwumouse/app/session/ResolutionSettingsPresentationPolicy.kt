package dev.uwumouse.app.session

/** Historical APP metadata is presentation-only; it must never replace live injection bounds. */
data class ResolutionSettingsPresentationAnchor(val packageName:String,val frame:ContextWindowFrame)

data class ResolutionSettingsPresentationState(
    val panelVisible:Boolean,val targetPackage:String,val ownPackage:String,val displayId:Int,
    val displayReady:Boolean,val locked:Boolean,val mode:SessionMode,val active:Boolean,val armed:Boolean,
    val contacts:Int,val releaseUnverified:Boolean,val metadataValid:Boolean,val metadataAgeMs:Long,
    val windows:List<ContextWindowCandidate>,val gameFrame:ContextWindowFrame?,
    val anchor:ResolutionSettingsPresentationAnchor?,
    val brokerValid:Boolean,val brokerPackage:String?,val brokerDisplayId:Int,val brokerAgeMs:Long,
    val taskQueryValid:Boolean,val taskPackage:String?,val taskPresent:Boolean,val taskAgeMs:Long,
)

/**
 * Own resolution widgets may retain focus while the game is paused/stopped. This result
 * only controls their visibility. It grants no target confirmation, target bounds,
 * physical capture or event injection, including while an IME temporarily hides APP.
 */
object ResolutionSettingsPresentationPolicy {
    fun allows(state:ResolutionSettingsPresentationState):Boolean {
        if(!state.panelVisible || state.displayId<0 || !state.displayReady || state.locked ||
            state.mode !in setOf(SessionMode.PAUSED,SessionMode.STOPPED) || state.active || state.armed ||
            state.contacts!=0 || state.releaseUnverified || state.targetPackage.isBlank() ||
            state.ownPackage.isBlank() || state.targetPackage==state.ownPackage ||
            !state.metadataValid || state.metadataAgeMs !in 0L..MAX_METADATA_AGE_MS) return false
        val windows=state.windows.filter { validFrame(it.frame,state.displayId) }
        val own=windows.filter { it.rootResolved && it.packageName==state.ownPackage && it.type in 3..4 }
        if(own.isEmpty()) return false
        val imePackages=windows.filter { it.type==2 && it.rootResolved && !it.packageName.isNullOrBlank() }
            .mapNotNull { it.packageName }.toSet()
        fun ownOverlay(window:ContextWindowCandidate)=window.packageName==state.ownPackage && window.type in 3..4 && window.rootResolved
        fun game(window:ContextWindowCandidate)=window.packageName==state.targetPackage && window.type==1 && window.rootResolved
        fun ime(window:ContextWindowCandidate)=window.type==2 && window.rootResolved && window.packageName in imePackages
        // An ordinary app, permission sheet or system shade is never treated as our field/IME.
        if(windows.any { it.focused && !ownOverlay(it) && !game(it) && !ime(it) }) return false
        if(state.brokerValid && state.brokerDisplayId==state.displayId && state.brokerAgeMs in 0L..MAX_METADATA_AGE_MS &&
            state.brokerPackage!=state.targetPackage && state.brokerPackage!=state.ownPackage && state.brokerPackage !in imePackages) return false

        if(state.gameFrame!=null && !validFrame(state.gameFrame,state.displayId)) return false
        val actualGame=state.gameFrame
        val reference=actualGame ?: own.maxByOrNull { it.frame.layer }?.frame ?: return false
        if(windows.any { !ownOverlay(it) && !game(it) && !ime(it) &&
                it.frame.layer>reference.layer && fullyCovers(reference,it.frame) }) return false
        if(actualGame!=null) return true

        // APP may be absent under a full-screen IME. Retain only the admitted game's
        // original display, with fresh task existence AND its actual broker identity.
        val anchor=state.anchor ?: return false
        return anchor.packageName==state.targetPackage && validFrame(anchor.frame,state.displayId) &&
            state.brokerValid && state.brokerPackage==state.targetPackage && state.brokerDisplayId==state.displayId &&
            state.brokerAgeMs in 0L..MAX_METADATA_AGE_MS && state.taskQueryValid && state.taskPresent &&
            state.taskPackage==state.targetPackage && state.taskAgeMs in 0L..MAX_TASK_AGE_MS
    }

    private fun validFrame(frame:ContextWindowFrame,displayId:Int)=frame.displayId==displayId &&
        frame.right>frame.left && frame.bottom>frame.top
    private fun fullyCovers(reference:ContextWindowFrame,cover:ContextWindowFrame)=
        cover.left<=reference.left && cover.top<=reference.top && cover.right>=reference.right && cover.bottom>=reference.bottom
    const val MAX_METADATA_AGE_MS=750L
    const val MAX_TASK_AGE_MS=2000L
}
