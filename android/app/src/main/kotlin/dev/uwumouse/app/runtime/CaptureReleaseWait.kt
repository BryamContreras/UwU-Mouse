package dev.uwumouse.app.runtime

/** A finite, cold acquisition wait. Failed attempts never extend its original deadline. */
internal class CaptureReleaseWait(private val retryMillis:Long=1_000L,private val timeoutMillis:Long=30_000L) {
    private var startedAt=-1L
    private var nextAttemptAt=0L
    val pending:Boolean get()=startedAt>=0L
    init { require(retryMillis>0 && timeoutMillis>=retryMillis) }

    fun held(now:Long) {
        require(now>=0)
        if(!pending) startedAt=now
        nextAttemptAt=now+retryMillis
    }
    fun expired(now:Long):Boolean = pending && (now<startedAt || now-startedAt>=timeoutMillis)
    fun mayAttempt(now:Long):Boolean = !pending || (!expired(now) && now>=nextAttemptAt)
    fun clear() { startedAt=-1L;nextAttemptAt=0L }
    companion object { const val HELD_INPUT="release_all_external_keys_and_buttons_before_capture" }
}
