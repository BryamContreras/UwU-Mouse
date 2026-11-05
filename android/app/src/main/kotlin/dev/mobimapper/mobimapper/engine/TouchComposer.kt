package dev.mobimapper.mobimapper.engine

import kotlin.math.abs

/** Milliseconds use Android's uptimeMillis base, supplied by the Android adapter. */
data class TouchFrame(
    val action: Int,
    val downTime: Long,
    val eventTime: Long,
    val ids: IntArray,
    val xs: FloatArray,
    val ys: FloatArray,
)

data class Contact(val owner: String, val id: Int, val x: Float, val y: Float)

/**
 * Sole owner of the injected pointer stream. All changes and the synchronous sink are serialized.
 * Each sink call receives a detached frame; Android callbacks must never pass recycled MotionEvents.
 * A failed sink is ambiguous: cancel the complete possible stream and pause. If cancellation also
 * fails, retain the possible contacts and expose releaseUnverified until recovery is confirmed.
 */
class TouchComposer(
    private val clock: () -> Long,
    private val sink: (TouchFrame) -> Boolean,
    private val maxContacts: Int = 10,
    val continuousCamera: Boolean = false,
) {
    init { require(maxContacts in 1..10) }

    private val contacts = ArrayList<Contact>(maxContacts)
    private var downTime = 0L
    private var lastEventTime = 0L
    var paused: Boolean = false
        private set
    var releaseUnverified: Boolean = false
        private set
    var lastError: String? = null
        private set
    val activeCount: Int @Synchronized get() = contacts.size

    @Synchronized fun snapshot(): List<Contact> = contacts.toList()
    @Synchronized fun hasOwner(owner: String): Boolean = contacts.any { it.owner == owner }
    /** Retain an exact CANCEL candidate across total privileged-process loss; never manufacture DOWN. */
    @Synchronized fun recoveryFrame(): TouchFrame? = if(contacts.isEmpty()) null else frame(CANCEL)

    @Synchronized fun begin(owner: String, x: Float, y: Float, avoidId: Int? = null, preferredId: Int? = null, beforeOwner:String?=null): Boolean {
        if (!ready(owner, x, y)) return false
        if (contacts.any { it.owner == owner }) return move(owner, x, y)
        if (contacts.size == maxContacts) { lastError = "CONTACT_LIMIT"; return false }
        // A replacement camera finger should have a different identity even when the game
        // samples both edges in one rendered frame. Pointer count and the legacy ID range stay fixed.
        val free = freeIds(owner)
        if (free.isEmpty()) { lastError = "CONTACT_LIMIT"; return false }
        val id = preferredId?.takeIf { it in free && it != avoidId }
            ?: free.firstOrNull { it != avoidId } ?: free.first()
        if (contacts.isEmpty()) downTime = now()
        val index=beforeOwner?.let { name -> contacts.indexOfFirst { it.owner==name }.takeIf { it>=0 } } ?: contacts.size
        contacts.add(index,Contact(owner, id, x, y))
        val action = if (contacts.size == 1) DOWN else POINTER_DOWN or (index shl 8)
        return deliver(action)
    }

    @Synchronized fun move(owner: String, x: Float, y: Float): Boolean {
        if (!ready(owner, x, y, continuingMove = true)) return false
        val index = contacts.indexOfFirst { it.owner == owner }
        if (index < 0) { lastError = "UNKNOWN_OWNER"; return false }
        val old = contacts[index]
        if (old.x == x && old.y == y) return true
        contacts[index] = old.copy(x = x, y = y)
        return deliver(MOVE)
    }

    @Synchronized fun end(owner: String): Boolean {
        if (paused) return false
        val index = contacts.indexOfFirst { it.owner == owner }
        if (index < 0) return true // Idempotent release after a duplicate physical up.
        val action = if (contacts.size == 1) UP else POINTER_UP or (index shl 8)
        if (!deliver(action)) return false
        contacts.removeAt(index)
        return true
    }

    /** Overlap just the two camera fingers so the game never sees an empty camera interval. */
    @Synchronized fun handoff(owner:String,x:Float,y:Float,preferredId:Int?=null):Boolean {
        if(!ready(owner,x,y)) return false
        if(continuousCamera && owner==MappingEngine.CAMERA_OWNER) {
            lastError="CONTINUOUS_CAMERA_HANDOFF";return false
        }
        val old=contacts.firstOrNull { it.owner==owner } ?: return begin(owner,x,y,preferredId=preferredId)
        if(contacts.size==maxContacts) return end(owner) && begin(owner,x,y,avoidId=old.id,preferredId=preferredId)
        val replacement="handoff:$owner"
        check(contacts.none { it.owner==replacement })
        val free=freeIds(replacement)
        if(free.isEmpty()) { lastError="CONTACT_LIMIT";return false }
        val id=preferredId?.takeIf { it in free } ?: free.first()
        val oldIndex=contacts.indexOfFirst { it.owner==owner }
        // Keep the new camera at its established pointer index. Ending the old finger
        // at the following index avoids briefly assigning a third/fourth index to camera.
        contacts.add(oldIndex,Contact(replacement,id,x,y))
        if(!deliver(POINTER_DOWN or (oldIndex shl 8)) || !end(owner)) return false
        val index=contacts.indexOfFirst { it.owner==replacement }
        contacts[index]=contacts[index].copy(owner=owner)
        return true
    }

    /** Global cancellation is reserved for stopping/recovering the entire session. */
    @Synchronized fun cancelAll(): Boolean {
        paused = true
        if (contacts.isEmpty()) return !releaseUnverified
        val accepted = send(frame(CANCEL))
        releaseUnverified = !accepted
        if (accepted) { contacts.clear(); lastError = null }
        else lastError = "RELEASE_UNVERIFIED"
        return accepted
    }

    /** Resume only from an empty, confirmed stream; never reconstruct held physical inputs. */
    @Synchronized fun resume(): Boolean {
        if (contacts.isNotEmpty() || releaseUnverified) return false
        paused = false
        lastError = null
        return true
    }

    /** Only a verified reset of the injection device/stream may acknowledge an unreachable backend. */
    @Synchronized fun acknowledgeBackendReset() {
        contacts.clear()
        releaseUnverified = false
        paused = true
        lastError = null
    }

    /** Stop instead of recycling the camera at the finite continuation limit. */
    @Synchronized fun stopForCameraCoordinateLimit(): Boolean {
        cancelAll()
        lastError=if(releaseUnverified) "RELEASE_UNVERIFIED" else "CAMERA_COORDINATE_LIMIT"
        return false
    }

    private fun freeIds(owner: String): List<Int> {
        if(continuousCamera && owner==MappingEngine.CAMERA_OWNER)
            return listOf(TouchCoordinatePolicy.CAMERA_ID).filter { id -> contacts.none { it.id==id } }
        return (0 until maxContacts).filter { id ->
            (!continuousCamera || id!=TouchCoordinatePolicy.CAMERA_ID) && contacts.none { it.id==id }
        }
    }

    private fun ready(owner: String, x: Float, y: Float, continuingMove:Boolean=false): Boolean {
        if (paused) return false
        val continuedCamera=continuingMove && continuousCamera && owner==MappingEngine.CAMERA_OWNER &&
            contacts.any { it.owner==owner && it.id==TouchCoordinatePolicy.CAMERA_ID }
        if(continuedCamera) {
            if(!x.isFinite() || !y.isFinite() || abs(x)>TouchCoordinatePolicy.LIMIT || abs(y)>TouchCoordinatePolicy.LIMIT)
                return stopForCameraCoordinateLimit()
            return true
        }
        if (owner.isBlank() || owner.length > 128 || !x.isFinite() || !y.isFinite() || x < 0 || y < 0) {
            lastError = "INVALID_CONTACT"
            return false
        }
        return true
    }

    private fun deliver(action: Int): Boolean {
        if (send(frame(action))) { lastError = null; return true }
        paused = true
        // A binder failure can occur after delivery. Cancel the possible full pointer set.
        val cancelled = send(frame(CANCEL))
        releaseUnverified = !cancelled
        if (cancelled) contacts.clear()
        lastError = if (cancelled) "INJECTION_FAILED_CANCELLED" else "RELEASE_UNVERIFIED"
        return false
    }

    private fun send(frame: TouchFrame): Boolean = try { sink(frame) } catch (_: Exception) { false }

    private fun frame(action: Int): TouchFrame = TouchFrame(
        action, downTime, now(),
        IntArray(contacts.size) { contacts[it].id },
        FloatArray(contacts.size) { contacts[it].x },
        FloatArray(contacts.size) { contacts[it].y },
    )

    private fun now(): Long {
        lastEventTime = maxOf(lastEventTime, clock())
        return lastEventTime
    }

    companion object {
        const val DOWN = 0
        const val UP = 1
        const val MOVE = 2
        const val CANCEL = 3
        const val POINTER_DOWN = 5
        const val POINTER_UP = 6
    }
}
