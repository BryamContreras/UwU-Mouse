package dev.uwumouse.app.engine

/** Worker-owned repeats; one pending boundary also enforces a toggled repeat's deadline. */
class RepeatRunner(
    repeats:List<PreparedRepeat>,
    private val clockMillis:()->Long,
    private val schedule:(Long,()->Unit)->(()->Unit),
    private val begin:(String,Float,Float)->Boolean,
    private val end:(String)->Boolean,
    private val changed:()->Unit={},
) {
    private class State(val spec:PreparedRepeat) {
        var held=false;var running=false;var contactDown=false;var epoch=0L
        var deadline=0L
        var cancelTask:(()->Unit)?=null
    }
    private val states=repeats.map(::State)
    private val keys=states.filter { it.spec.triggerKind=="key" }.associateBy { it.spec.code }
    private val buttons=states.filter { it.spec.triggerKind=="mouse" }.associateBy { it.spec.code }
    var pulses=0L;private set
    var rejected=0L;private set
    var lastError:String?=null;private set
    val activeCount:Int get()=states.count { it.running }
    val pending:Boolean get()=states.any { it.cancelTask!=null }

    init { require(repeats.all { it.intervalMs in 30..5000 && it.durationSeconds in 0..86_400 }) }

    fun input(kind:String,code:Int,down:Boolean,canStart:Boolean=true):Boolean {
        val state=(if(kind=="key") keys else buttons)[code] ?: return false
        // Input can reach the worker before a delayed boundary callback.
        if(state.running && expired(state)) { stop(state,true);changed() }
        if(!down) {
            state.held=false
            if(!state.spec.toggle) stop(state,true)
            changed();return true
        }
        if(state.held) return true
        state.held=true
        if(state.spec.toggle && state.running) {
            stop(state,true);changed();return true
        }
        if(!canStart) return true
        state.running=true;state.epoch++;lastError=null
        state.deadline=if(state.spec.toggle && state.spec.durationSeconds>0)
            clockMillis()+state.spec.durationSeconds*1000L else 0L
        press(state,state.epoch)
        return true
    }
    /** Keep latches until physical UP; a mode change cannot reassert a held repeat. */
    fun cancel(releaseContact:Boolean=false) {
        states.forEach { stop(it,releaseContact) }
    }
    private fun stop(state:State,releaseContact:Boolean) {
        state.epoch++;state.running=false;state.deadline=0L
        state.cancelTask?.invoke();state.cancelTask=null
        val down=state.contactDown;state.contactDown=false
        if(down && releaseContact && !end(state.spec.owner)) lastError="REPEAT_RELEASE_FAILED"
    }
    private fun valid(state:State,token:Long)=state.running && (state.held || state.spec.toggle) && token==state.epoch
    private fun expired(state:State)=state.deadline!=0L && clockMillis()>=state.deadline
    private fun press(state:State,token:Long) {
        if(!valid(state,token)) return
        if(expired(state)) { stop(state,true);changed();return }
        val start=clockMillis()
        if(!begin(state.spec.owner,state.spec.x,state.spec.y)) {
            rejected++;lastError="REPEAT_CONTACT_UNAVAILABLE";stop(state,false);changed();return
        }
        state.contactDown=true;pulses++;changed()
        if(!valid(state,token)) return
        later(state,20L,token) {
            state.contactDown=false
            if(!end(state.spec.owner)) {
                rejected++;lastError="REPEAT_RELEASE_FAILED";stop(state,false);changed();return@later
            }
            changed()
            if(!valid(state,token)) return@later
            val now=clockMillis();val due=start+state.spec.intervalMs
            // If the release itself was late, skip missed periods; never emit a catch-up burst.
            val delay=if(due>now) due-now else state.spec.intervalMs.toLong()
            later(state,delay,token) { press(state,token) }
        }
    }
    private fun later(state:State,delay:Long,token:Long,action:()->Unit) {
        val bounded=if(state.deadline==0L) delay else minOf(delay,maxOf(0L,state.deadline-clockMillis()))
        state.cancelTask=schedule(bounded) {
            if(valid(state,token)) {
                state.cancelTask=null
                if(expired(state)) { stop(state,true);changed() } else action()
            }
        }
    }
}
