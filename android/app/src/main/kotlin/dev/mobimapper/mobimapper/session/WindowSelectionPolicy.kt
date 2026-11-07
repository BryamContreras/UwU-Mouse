package dev.mobimapper.mobimapper.session

/** Metadata only; package ownership comes from a resolved root, never window titles or text. */
data class WindowOwner(val id:Int,val packageName:String?)

data class ApplicationWindowCandidate(
    val id:Int,
    val packageName:String?,
    val frame:ContextWindowFrame,
    val focused:Boolean=false,
    val active:Boolean=false,
) {
    val area:Long get()=(frame.right.toLong()-frame.left)*(frame.bottom.toLong()-frame.top)
}

object WindowSelectionPolicy {
    /** A missing active root may retain editing only through an actually focused own overlay. */
    fun ownsFocusedEditorOverlay(editorVisible:Boolean,type:Int,owner:String?,ownPackage:String,
                                 frame:ContextWindowFrame,focused:Boolean,displayId:Int=0):Boolean =
        editorVisible && type==4 && owner==ownPackage && focused && frame.displayId==displayId &&
            frame.right>frame.left && frame.bottom>frame.top

    /** An unresolved root can retain ownership only while the same ID remains continuously visible. */
    fun resolveOwners(visible:List<WindowOwner>,previous:Map<Int,String>):Map<Int,String> =
        visible.mapNotNull { window -> (window.packageName ?: previous[window.id])?.let { window.id to it } }.toMap()

    /**
     * A focused popup must not replace the main game surface. Prefer largest valid display-0
     * application area, retaining the previous ID among equal-area candidates. Remaining ties
     * are deterministic and prefer focus/activity, then the higher visible layer and lower ID.
     */
    fun selectTarget(windows:List<ApplicationWindowCandidate>,target:String,previousId:Int?,displayId:Int=0):ApplicationWindowCandidate? =
        windows.filter { it.packageName==target && it.frame.displayId==displayId &&
            it.frame.right>it.frame.left && it.frame.bottom>it.frame.top }
            .maxWithOrNull(compareBy<ApplicationWindowCandidate> { it.area }
                .thenBy { it.id==previousId }.thenBy { it.focused }.thenBy { it.active }
                .thenBy { it.frame.layer }.thenByDescending { it.id })
}
