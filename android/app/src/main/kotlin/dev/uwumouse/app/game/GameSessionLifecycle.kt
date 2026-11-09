package dev.uwumouse.app.game

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

    fun observe(current:Identity?,sample:Observation,now:Long,mayClose:Boolean):Boolean {
        if(current!=identity) {
            identity=current;seenTask=false;lastTaskSnapshot=Long.MIN_VALUE
            absentSince=null;absentSnapshots=0;closed=false
        }
        if(current==null || closed) return false
        if(sample.packageName!=current.packageName || !sample.taskQueryValid || !sample.contextValid ||
            now-sample.taskCapturedUptimeMs !in 0..MAX_AGE_MILLIS ||
            now-sample.contextCapturedUptimeMs !in 0..MAX_AGE_MILLIS) {
            clearAbsence();return false
        }
        if(sample.taskPresent) {
            seenTask=true;clearAbsence();lastTaskSnapshot=sample.taskCapturedUptimeMs;return false
        }
        if(!mayClose || sample.gameWindowPresent || sample.gameFocused) {
            clearAbsence();lastTaskSnapshot=sample.taskCapturedUptimeMs;return false
        }
        if(!seenTask) return false
        if(sample.taskCapturedUptimeMs<=lastTaskSnapshot) {
            if(sample.taskCapturedUptimeMs<lastTaskSnapshot) clearAbsence()
            return false // Re-reading one cached result cannot confirm a task's disappearance.
        }
        lastTaskSnapshot=sample.taskCapturedUptimeMs
        if(absentSince==null) absentSince=sample.taskCapturedUptimeMs
        absentSnapshots++
        if(absentSnapshots<2 || sample.taskCapturedUptimeMs-requireNotNull(absentSince)<CLOSE_GRACE_MILLIS) return false
        closed=true
        return true
    }

    private fun clearAbsence() { absentSince=null;absentSnapshots=0 }

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
