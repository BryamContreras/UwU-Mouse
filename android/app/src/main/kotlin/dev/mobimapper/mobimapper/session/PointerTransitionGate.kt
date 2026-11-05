package dev.mobimapper.mobimapper.session

/** One physical handoff at a time; fresh requests retain the latest desired mode until its ACK. */
class PointerTransitionGate {
    @Volatile var pending=false
        private set
    @Volatile var baseCursor=false
        private set
    @Volatile var temporaryCursorHeld=false
        private set
    private var ticket=0
    private var enabled=false
    private var appliedCursor=false

    /** A session always starts with an empty hold, even if the physical key is still down. */
    @Synchronized fun begin(cursor:Boolean) {
        ++ticket;pending=false;enabled=true;appliedCursor=cursor;baseCursor=cursor;temporaryCursorHeld=false
    }
    @Synchronized fun toggleBase():Int? {
        if(!enabled) return null
        baseCursor=!baseCursor
        return requestDesired()
    }
    /** A mapped one-shot tap must own a handoff even when a held override keeps cursor visible. */
    @Synchronized fun requestCommand():Int? {
        if(!enabled || pending) return null
        baseCursor=!baseCursor
        return request()
    }
    @Synchronized fun hold(down:Boolean):Int? {
        if(!enabled || temporaryCursorHeld==down) return null
        temporaryCursorHeld=down
        return requestDesired()
    }
    @Synchronized fun requestedCursor(value:Int):Boolean? =
        if(enabled && current(value)) baseCursor || temporaryCursorHeld else null

    /** The worker used [cursor]; a release received during the handoff must still be applied. */
    @Synchronized fun acknowledge(value:Int,cursor:Boolean):Int? {
        if(!enabled || !current(value)) return null
        appliedCursor=cursor;pending=false
        return requestDesired()
    }
    private fun requestDesired():Int? =
        if(pending || appliedCursor==(baseCursor || temporaryCursorHeld)) null else request()

    // Kept as the small ticket primitive for callers/tests that do not manage a pointer mode.
    @Synchronized fun request():Int? {
        if(pending) return null
        pending=true
        return ++ticket
    }
    @Synchronized fun current(value:Int)=pending && value==ticket
    @Synchronized fun complete(value:Int):Boolean {
        if(!current(value)) return false
        pending=false
        return true
    }
    @Synchronized fun invalidate() {
        ++ticket;pending=false;enabled=false;baseCursor=false;temporaryCursorHeld=false
    }
}
