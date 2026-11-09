package dev.uwumouse.app.engine

/** One-shot map commands serialized through the pointer gate; no held input can replay them. */
class CursorActionRunner(
    actions:List<PreparedCursorAction>,
    private val reserveTransition:(PreparedCursorAction)->Boolean,
    private val schedule:(Long,()->Unit)->(()->Unit),
    private val begin:(String,Float,Float)->Boolean,
    private val end:(String)->Boolean,
    private val changed:()->Unit={},
) {
    private val keys=actions.filter { it.triggerKind=="key" }.associateBy { it.code }
    private val buttons=actions.filter { it.triggerKind=="mouse" }.associateBy { it.code }
    private val held=HashSet<String>()
    private val waiting=ArrayDeque<PreparedCursorAction>()
    private var current:PreparedCursorAction?=null
    private var cancelTask:(()->Unit)?=null
    private var epoch=0L
    private var started=false
    private var contactDown=false
    var completed=0L;private set
    var rejected=0L;private set
    var lastError:String?=null;private set
    val currentOwner:String? get()=current?.owner
    val queued:Int get()=waiting.size
    val pending:Boolean get()=current!=null || waiting.isNotEmpty()

    fun input(kind:String,code:Int,down:Boolean):Boolean {
        val action=(if(kind=="key") keys else buttons)[code] ?: return false
        if(!down) { held.remove(action.owner);return true }
        if(!held.add(action.owner)) return true
        if(current!=null || waiting.isNotEmpty()) { rejected++;lastError="CURSOR_COMMAND_BUSY";changed();return true }
        waiting.addLast(action);retry();return true
    }
    /** Called after an ACK, never by a ticker. A busy gate retains this explicit fresh press. */
    fun retry() {
        if(current!=null || waiting.isEmpty()) return
        val action=waiting.first();val token=++epoch
        current=action;started=false
        val reserved=reserveTransition(action)
        if(token!=epoch || current!==action) return
        if(reserved) waiting.removeFirst() else current=null
    }
    /** The transition already released all old contacts; complete its presentation after this UP. */
    fun tap(finished:(Boolean)->Unit):Boolean {
        val action=current ?: return false
        if(started) return false
        started=true;val token=epoch
        if(!begin(action.owner,action.x,action.y)) {
            rejected++;lastError="CURSOR_COMMAND_CONTACT_UNAVAILABLE";finish(token,false,finished);return true
        }
        contactDown=true;changed()
        if(token!=epoch || current==null) return true
        cancelTask=schedule(20L) {
            if(token==epoch && current!=null) {
                cancelTask=null;contactDown=false
                val released=end(action.owner)
                if(!released) { rejected++;lastError="CURSOR_COMMAND_RELEASE_FAILED" }
                finish(token,released,finished)
            }
        }
        return true
    }
    private fun finish(token:Long,success:Boolean,finished:(Boolean)->Unit) {
        if(token!=epoch) return
        current=null;started=false
        if(success) completed++
        changed()
        if(token==epoch) finished(success)
    }
    fun cancel(releaseContact:Boolean=false) {
        epoch++;cancelTask?.invoke();cancelTask=null;waiting.clear()
        val owner=current?.owner;current=null;started=false
        val down=contactDown;contactDown=false
        if(down && releaseContact && owner!=null && !end(owner)) lastError="CURSOR_COMMAND_RELEASE_FAILED"
    }
}
