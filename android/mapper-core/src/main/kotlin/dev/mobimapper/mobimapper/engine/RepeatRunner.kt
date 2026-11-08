package dev.mobimapper.mobimapper.engine

/** Worker-owned held repeats; pulse boundaries alone schedule work on the shared composer. */
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

    init { require(repeats.all { it.intervalMs in 50..5000 }) }

    fun input(kind:String,code:Int,down:Boolean,canStart:Boolean=true):Boolean {
        val state=(if(kind=="key") keys else buttons)[code] ?: return false
        if(!down) {
            state.held=false;stop(state,true);changed();return true
        }
        if(state.held) return true
        state.held=true
        if(!canStart) return true
        state.running=true;state.epoch++;lastError=null
        press(state,state.epoch)
        return true
    }
    /** Keep latches until physical UP; a mode change cannot reassert a held repeat. */
    fun cancel(releaseContact:Boolean=false) {
        states.forEach { stop(it,releaseContact) }
    }
    private fun stop(state:State,releaseContact:Boolean) {
        state.epoch++;state.running=false
        state.cancelTask?.invoke();state.cancelTask=null
        val down=state.contactDown;state.contactDown=false
        if(down && releaseContact && !end(state.spec.owner)) lastError="REPEAT_RELEASE_FAILED"
    }
    private fun valid(state:State,token:Long)=state.running && state.held && token==state.epoch
    private fun press(state:State,token:Long) {
        if(!valid(state,token)) return
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
        state.cancelTask=schedule(delay) {
            if(valid(state,token)) { state.cancelTask=null;action() }
        }
    }
}
