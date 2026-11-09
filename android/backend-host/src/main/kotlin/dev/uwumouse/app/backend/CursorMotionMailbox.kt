package dev.uwumouse.app.backend

/** Only visual positions coalesce. One pending frame owns the latest position for its token. */
internal class CursorMotionMailbox {
    data class Position(val token:Long,val x:Float,val y:Float,val size:Float)
    private var token=0L
    private var x=0f
    private var y=0f
    private var size=0f
    private var pending=false
    private var dirty=false

    @Synchronized fun activate(token:Long,x:Float,y:Float,size:Float) {
        require(token>0L)
        this.token=token;this.x=x;this.y=y;this.size=size;dirty=false
        // An already queued frame remains the sole owner, even across replacement.
    }

    @Synchronized fun deactivate() { token=0L;dirty=false }

    /** True asks the caller to schedule one frame; further offers only replace primitives. */
    @Synchronized fun offer(token:Long,x:Float,y:Float,size:Float):Boolean {
        if(token==0L || token!=this.token || (this.x==x && this.y==y && this.size==size)) return false
        this.x=x;this.y=y;this.size=size;dirty=true
        if(pending) return false
        pending=true
        return true
    }

    @Synchronized fun drain():Position? {
        pending=false
        if(!dirty || token==0L) return null
        dirty=false
        return Position(token,x,y,size)
    }
}
