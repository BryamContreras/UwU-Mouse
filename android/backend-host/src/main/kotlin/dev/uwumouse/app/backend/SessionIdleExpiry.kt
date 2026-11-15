package dev.uwumouse.app.backend

/**
 * Owner-serialized inactivity budget. Activity records only an acknowledged operation's
 * time; the owner keeps one timer and asks for the remaining delay when that timer fires.
 * IDs never repeat, and neither late activity nor a regressing clock can revive an expiry.
 */
class SessionIdleExpiry(
    private val clockMillis:()->Long,
    val budgetMillis:Long=30*60*1000L,
) {
    init { require(budgetMillis>0L) }

    var sessionId=0L
        private set
    private var newestSessionId=0L
    private var lastActivityAt=0L
    private var lastObservedAt=0L
    private var expired=false

    /** Only an explicit new session arms a timer; the returned delay is its first wait. */
    fun begin(id:Long):Long {
        require(id>newestSessionId) { "Session ID must advance" }
        val now=clockMillis()
        require(now>=0L) { "Invalid monotonic time" }
        sessionId=id;newestSessionId=id
        lastActivityAt=now;lastObservedAt=now;expired=false
        return budgetMillis
    }

    /** No timer work or allocation; rejected, stale and already idle sessions stay unchanged. */
    fun acceptedActivity(id:Long):Boolean {
        if(id<=0L || id!=sessionId || expired) return false
        val now=clockMillis()
        if(!observe(now) || now-lastActivityAt>=budgetMillis) {
            expired=true
            return false
        }
        lastActivityAt=now
        return true
    }

    /** Null means another/closed session; zero means this session must expire now. */
    fun remainingMillis(id:Long):Long? {
        if(id<=0L || id!=sessionId) return null
        if(expired) return 0L
        val now=clockMillis()
        if(!observe(now) || now-lastActivityAt>=budgetMillis) {
            expired=true
            return 0L
        }
        return budgetMillis-(now-lastActivityAt)
    }

    fun clear() {
        sessionId=0L;lastActivityAt=0L;lastObservedAt=0L;expired=false
    }

    private fun observe(now:Long):Boolean {
        if(now<0L || now<lastObservedAt) return false
        lastObservedAt=now
        return true
    }
}
