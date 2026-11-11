package dev.uwumouse.app.game

/** Presentation disposal cancels starts, but must not discard an existing game's exit evidence. */
internal class GameTaskClosureOwner {
    var identity:GameTaskClosureGate.Identity?=null; private set

    fun reconcile(requested:GameTaskClosureGate.Identity?,targetPackage:String,targetDisplayId:Int,
        stopped:Boolean):GameTaskClosureGate.Identity? {
        if(requested!=null) identity=requested
        else if(stopped || identity?.let { it.packageName!=targetPackage || it.displayId!=targetDisplayId }==true)
            identity=null
        return identity
    }

    fun clear() { identity=null }
}

/** Task existence is separate from focus: a background or minimized game is still open. */
internal class GameTaskClosureGate {
    data class Identity(val operation:Long,val packageName:String,val displayId:Int)
    data class Observation(val packageName:String?,val taskCapturedUptimeMs:Long,
        val taskQueryValid:Boolean,val taskPresent:Boolean,val contextCapturedUptimeMs:Long,
        val contextValid:Boolean,val gameWindowPresent:Boolean,val gameFocused:Boolean)
    private var identity:Identity?=null
    private var seenTask=false
    private var lastTaskSnapshot=Long.MIN_VALUE
    private var absentSince:Long?=null
    private var absentSnapshots=0
    private var closed=false
    private var observations=0L
    private var identityChanges=0L
    private var lastObservedUptimeMs=-1L
    private var lastSampleTaskUptimeMs=-1L
    private var lastSampleContextUptimeMs=-1L
    private var lastTaskQueryValid=false
    private var lastTaskPresent=false
    private var lastContextValid=false
    private var lastGameWindowPresent=false
    private var lastGameFocused=false
    private var lastMayClose=false
    private var reason="not_observed"
    private var absenceResetReason="not_observed"
    private var absenceResetUptimeMs=-1L
    private var absenceResets=0L

    fun observe(current:Identity?,sample:Observation,now:Long,mayClose:Boolean):Boolean {
        observations++;lastObservedUptimeMs=now;lastSampleTaskUptimeMs=sample.taskCapturedUptimeMs
        lastSampleContextUptimeMs=sample.contextCapturedUptimeMs;lastTaskQueryValid=sample.taskQueryValid
        lastTaskPresent=sample.taskPresent;lastContextValid=sample.contextValid
        lastGameWindowPresent=sample.gameWindowPresent;lastGameFocused=sample.gameFocused;lastMayClose=mayClose
        if(current!=identity) {
            identityChanges++
            identity=current;seenTask=false;lastTaskSnapshot=Long.MIN_VALUE
            absentSince=null;absentSnapshots=0;closed=false
            absenceResetReason="owner_changed";absenceResetUptimeMs=now
        }
        if(current==null) { reason="no_owner";return false }
        if(closed) { reason="already_closed";return false }
        val taskAge=now-sample.taskCapturedUptimeMs
        val invalid=when {
            sample.packageName!=current.packageName -> "task_package_mismatch"
            !sample.taskQueryValid -> "task_query_unknown"
            taskAge<0 -> "future_task_snapshot"
            taskAge>MAX_AGE_MILLIS -> "stale_task_snapshot"
            else -> null
        }
        if(invalid!=null) {
            reason=invalid;clearAbsence(reason,now);return false
        }
        if(sample.taskPresent) {
            reason="task_present"
            seenTask=true;clearAbsence(reason,now);lastTaskSnapshot=sample.taskCapturedUptimeMs;return false
        }
        // A complete global task inventory proves task absence independently of WMS focus
        // metadata, which can be unavailable on a removed display or an OEM desktop. A
        // fresh contradictory window/focus observation still vetoes closure. Neither this
        // fallback nor task absence authorizes input or replaces the injection context gate.
        val freshContext=sample.contextValid && now-sample.contextCapturedUptimeMs in 0..MAX_AGE_MILLIS
        if(!mayClose || (freshContext && (sample.gameWindowPresent || sample.gameFocused))) {
            reason=when { !mayClose -> "closure_deferred";sample.gameFocused -> "game_focused";else -> "game_window_present" }
            clearAbsence(reason,now);lastTaskSnapshot=sample.taskCapturedUptimeMs;return false
        }
        if(!seenTask) { reason="task_never_observed";return false }
        if(sample.taskCapturedUptimeMs<=lastTaskSnapshot) {
            reason=if(sample.taskCapturedUptimeMs<lastTaskSnapshot) "task_snapshot_regressed" else "cached_task_snapshot"
            if(sample.taskCapturedUptimeMs<lastTaskSnapshot) clearAbsence(reason,now)
            return false // Re-reading one cached result cannot confirm a task's disappearance.
        }
        lastTaskSnapshot=sample.taskCapturedUptimeMs
        if(absentSince==null) absentSince=sample.taskCapturedUptimeMs
        absentSnapshots++
        if(absentSnapshots<2 || sample.taskCapturedUptimeMs-requireNotNull(absentSince)<CLOSE_GRACE_MILLIS) {
            reason="confirming_task_absence";return false
        }
        reason="task_exit_confirmed"
        closed=true
        return true
    }

    private fun clearAbsence(resetReason:String,now:Long) {
        if(absentSince!=null) absenceResets++
        absenceResetReason=resetReason;absenceResetUptimeMs=now;absentSince=null;absentSnapshots=0
    }

    /** Allocates only for an explicit diagnostic snapshot; observation stores bounded scalars. */
    fun diagnostics():Map<String,Any?> = mapOf(
        "reason" to reason,"observations" to observations,"identityChanges" to identityChanges,
        "operation" to identity?.operation,"packageName" to identity?.packageName,"displayId" to identity?.displayId,
        "seenTask" to seenTask,"closed" to closed,"absentSinceUptimeMs" to absentSince,
        "absenceResets" to absenceResets,"absenceResetReason" to absenceResetReason,
        "absenceResetUptimeMs" to absenceResetUptimeMs,
        "absentSnapshots" to absentSnapshots,"lastDistinctTaskUptimeMs" to lastTaskSnapshot.takeUnless { it==Long.MIN_VALUE },
        "observedUptimeMs" to lastObservedUptimeMs,"taskCapturedUptimeMs" to lastSampleTaskUptimeMs,
        "taskAgeMs" to (lastObservedUptimeMs-lastSampleTaskUptimeMs),"taskQueryValid" to lastTaskQueryValid,
        "taskPresent" to lastTaskPresent,"contextCapturedUptimeMs" to lastSampleContextUptimeMs,
        "contextAgeMs" to (lastObservedUptimeMs-lastSampleContextUptimeMs),"contextValid" to lastContextValid,
        "gameWindowPresent" to lastGameWindowPresent,"gameFocused" to lastGameFocused,"mayClose" to lastMayClose)

    companion object {
        private const val MAX_AGE_MILLIS=1_500L
        private const val CLOSE_GRACE_MILLIS=2_000L
    }
}

/** Visual disposal cannot prevent native closure or another visual owner's cleanup. */
internal fun stopSessionThenCleanUp(requestStop:()->Unit,cleanup:List<()->Unit>,failed:(Exception)->Unit) {
    try { requestStop() }
    finally {
        for(close in cleanup) try { close() } catch(failure:Exception) { failed(failure) }
    }
}
