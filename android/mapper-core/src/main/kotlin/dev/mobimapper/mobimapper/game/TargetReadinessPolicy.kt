package dev.mobimapper.mobimapper.game

import dev.mobimapper.mobimapper.profile.Calibration

/** Display and target-window geometry established by the coordinator's settling barrier. */
data class TargetSnapshot(
    val packageName: String,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val displayId: Int = 0,
)

/** A previous settling result can shorten presentation transitions, never grant authorization. */
object TargetReadinessPolicy {
    fun mayReuse(settled: TargetSnapshot?, current: TargetSnapshot?, calibration: Calibration,
                 authorized: Boolean, geometryInvalidated: Boolean): Boolean {
        if (!authorized || geometryInvalidated || !calibration.confirmed ||
            settled == null || current == null || settled != current) return false
        if (current.packageName.isBlank() || current.width !in 100..8192 || current.height !in 100..8192 ||
            current.displayId<0 || calibration.displayScope!=(if(current.displayId==0) "phone" else "external") ||
            current.rotation !in 0..3 || current.width != calibration.screenWidth ||
            current.height != calibration.screenHeight || current.rotation != calibration.rotation ||
            current.right <= current.left || current.bottom <= current.top) return false

        if(calibration.displayScope=="external" && calibration.windowFrame!=
            dev.mobimapper.mobimapper.profile.GameArea(current.left.toFloat(),current.top.toFloat(),current.right.toFloat(),current.bottom.toFloat())) return false
        val area = calibration.area
        // Recheck even a newly saved calibration: a settled window is not proof its new area fits.
        return area.left.isFinite() && area.top.isFinite() && area.right.isFinite() && area.bottom.isFinite() &&
            area.left >= 0 && area.top >= 0 && area.right <= current.width && area.bottom <= current.height &&
            area.width >= 32 && area.height >= 32 &&
            area.left >= current.left && area.top >= current.top &&
            area.right <= current.right && area.bottom <= current.bottom
    }
}
