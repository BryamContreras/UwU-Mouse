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
    private val resetSink:((CameraResetSequence)->CameraResetResult)?=null,
    private val orderedContacts:Boolean=false,
) {
    init { require(maxContacts in 1..10) }

    // Private mutable storage avoids allocating a replacement Contact on every MOVE.
    // Only detached Contact snapshots and frame arrays are exposed to callers.
    private class StoredContact(var owner:String,val id:Int,var x:Float,var y:Float) {
        var submittedX=x
        var submittedY=y
    }
    private val contacts = ArrayList<StoredContact>(maxContacts)
    private var moveBatch=false
    private var pendingMove=false
    private var downTime = 0L
    private var lastEventTime = 0L
    var paused: Boolean = false
        private set
    var releaseUnverified: Boolean = false
        private set
    var lastError: String? = null
        private set
    var recoveryCandidateKnown:Boolean = true
        private set
    val supportsCameraReset:Boolean get()=resetSink!=null
    val activeCount: Int @Synchronized get() = contacts.size

    @Synchronized fun snapshot(): List<Contact> = contacts.map { Contact(it.owner,it.id,it.x,it.y) }
    @Synchronized fun hasOwner(owner: String): Boolean = contacts.any { it.owner == owner }
    /** Retain an exact CANCEL candidate across total privileged-process loss; never manufacture DOWN. */
    @Synchronized fun recoveryFrame(): TouchFrame? =
        if(!recoveryCandidateKnown || contacts.isEmpty()) null else frame(CANCEL,submitted=moveBatch && pendingMove)

    @Synchronized fun canResetCamera(owner:String):Boolean = resetSink!=null && !paused &&
        !releaseUnverified && recoveryCandidateKnown && owner==MappingEngine.CAMERA_OWNER &&
        contacts.lastOrNull()?.let { it.owner==owner && it.id in 8..9 }==true

    val canInterlaceCamera:Boolean @Synchronized get()= !continuousCamera && canResetCamera(MappingEngine.CAMERA_OWNER) &&
        contacts.size<maxContacts && (1 until contacts.size).all { contacts[it-1].id<contacts[it].id } &&
        contacts.none { it.id==if(contacts.last().id==8) 9 else 8 }

    /** Compact transport keeps the same per-frame ACK and possible-contact recovery semantics. */
    @Synchronized fun resetCameraAndMove(owner:String,anchorX:Float,anchorY:Float,
                                         targetX:Float,targetY:Float,preferredId:Int?=null):Boolean {
        if(!canResetCamera(owner) || !ready(owner,anchorX,anchorY) ||
            !ready(owner,targetX,targetY,continuingMove=true)) return false
        if(!flushPendingMove()) return false
        val old=contacts.last()
        val ordinary=contacts.dropLast(1)
        val possibleIds=if(continuousCamera) listOf(TouchCoordinatePolicy.CAMERA_ID) else listOf(8,9)
        val free=possibleIds.filter { id -> ordinary.none { it.id==id } }
        val id=preferredId?.takeIf { it in free } ?: free.firstOrNull { it!=old.id } ?: free.firstOrNull()
            ?: return false
        val up=frame(if(contacts.size==1) UP else POINTER_UP or (contacts.lastIndex shl 8))
        val nextDownTime=if(ordinary.isEmpty()) now() else downTime
        fun nextFrame(action:Int,x:Float,y:Float)=TouchFrame(action,nextDownTime,now(),
            IntArray(contacts.size) { if(it==contacts.lastIndex) id else contacts[it].id },
            FloatArray(contacts.size) { if(it==contacts.lastIndex) x else contacts[it].x },
            FloatArray(contacts.size) { if(it==contacts.lastIndex) y else contacts[it].y })
        val plannedFrames=listOf(up,
            nextFrame(if(ordinary.isEmpty()) DOWN else POINTER_DOWN or (contacts.lastIndex shl 8),anchorX,anchorY),
            nextFrame(MOVE,targetX,targetY))
        return performCameraReset(owner,old.id,id,plannedFrames)
    }

    /** Diagnostic bounded handoff: add the other camera ID before lifting the old one. */
    @Synchronized fun interlaceCameraAndMove(owner:String,anchorX:Float,anchorY:Float,
                                             targetX:Float,targetY:Float,preferredId:Int?=null):Boolean {
        if(!canInterlaceCamera) return resetCameraAndMove(owner,anchorX,anchorY,targetX,targetY,preferredId)
        if(owner!=MappingEngine.CAMERA_OWNER || !ready(owner,anchorX,anchorY) ||
            !ready(owner,targetX,targetY,continuingMove=true)) return false
        if(!flushPendingMove()) return false
        val old=contacts.last()
        val id=if(old.id==8) 9 else 8
        val overlapping=(contacts+StoredContact("handoff:$owner",id,anchorX,anchorY)).sortedBy { it.id }
        val newIndex=overlapping.indexOfFirst { it.id==id }
        val oldIndex=overlapping.indexOfFirst { it.id==old.id }
        val handoffTime=now()
        val down=TouchFrame(POINTER_DOWN or (newIndex shl 8),downTime,handoffTime,
            IntArray(overlapping.size) { overlapping[it].id },FloatArray(overlapping.size) { overlapping[it].x },
            FloatArray(overlapping.size) { overlapping[it].y })
        val up=down.copy(action=POINTER_UP or (oldIndex shl 8),ids=down.ids.copyOf(),xs=down.xs.copyOf(),ys=down.ys.copyOf())
        val post=requireNotNull(CameraResetSequence.postEvent(up))
        val movedIndex=post.ids.indexOf(id)
        val move=post.copy(eventTime=now(),xs=post.xs.copyOf().apply { this[movedIndex]=targetX },
            ys=post.ys.copyOf().apply { this[movedIndex]=targetY })
        return performCameraReset(owner,old.id,id,listOf(down,up,move))
    }

    private fun performCameraReset(owner:String,oldId:Int,newId:Int,plannedFrames:List<TouchFrame>):Boolean {
        val previousOwners=contacts.associate { it.id to it.owner }
        val sequence=CameraResetSequence(plannedFrames)
        val result=try { requireNotNull(resetSink).invoke(sequence) }
            catch(_:Exception) { CameraResetResult(0,0,progressKnown=false) }
        pendingMove=false
        if(!result.progressKnown) {
            // No stage can be inferred from a lost final reply without the app-owned progress record.
            // The guardian must resolve its journal; never manufacture a local CANCEL candidate.
            moveBatch=false;paused=true;releaseUnverified=true;recoveryCandidateKnown=false
            lastError="CAMERA_RESET_PROGRESS_UNKNOWN"
            return false
        }
        val possible=result.attemptedCount.takeIf { it>result.acceptedCount }
        val frameIndex=(possible ?: result.acceptedCount)-1
        if(frameIndex>=0) {
            val attempted=plannedFrames[frameIndex]
            val candidate=if(possible!=null) attempted else CameraResetSequence.postEvent(attempted)
            contacts.clear()
            val oldReleaseAccepted=result.acceptedCount>=if(sequence.kind==CameraResetKind.SEQUENTIAL) 1 else 2
            if(candidate!=null) for(index in candidate.ids.indices) {
                val id=candidate.ids[index]
                val contactOwner=if(id==newId && (id!=oldId || oldReleaseAccepted)) {
                    if(sequence.kind==CameraResetKind.INTERLACED && !oldReleaseAccepted) "handoff:$owner" else owner
                } else requireNotNull(previousOwners[id])
                contacts+=StoredContact(contactOwner,id,candidate.xs[index],candidate.ys[index])
            }
            downTime=attempted.downTime
        }
        recordSubmittedPositions()
        if(result.acceptedCount==3) { lastError=null;return true }
        return failPossibleStream()
    }

    /** Opt-in delivery batching; the mapping engine still integrates every original sample. */
    @Synchronized fun beginMoveBatch():Boolean {
        if(paused || releaseUnverified || moveBatch) return false
        recordSubmittedPositions()
        moveBatch=true;pendingMove=false
        return true
    }

    /** The last position is delivered now; semantic contact edges flush before changing IDs. */
    @Synchronized fun endMoveBatch():Boolean {
        if(!moveBatch) return !paused && !releaseUnverified
        val accepted=flushPendingMove()
        moveBatch=false
        return accepted
    }

    /** Publish a semantic movement checkpoint without ending the caller's delivery batch. */
    @Synchronized fun flushMoveBatch():Boolean = flushPendingMove()

    /** Pause/lost context must not replay a position that never entered the injection sink. */
    @Synchronized fun discardMoveBatch() {
        if(moveBatch && pendingMove) for(contact in contacts) {
            contact.x=contact.submittedX;contact.y=contact.submittedY
        }
        pendingMove=false;moveBatch=false
    }

    @Synchronized fun begin(owner: String, x: Float, y: Float, avoidId: Int? = null, preferredId: Int? = null, beforeOwner:String?=null): Boolean {
        if (!ready(owner, x, y)) return false
        if(!flushPendingMove()) return false
        if (contacts.any { it.owner == owner }) return move(owner, x, y)
        if (contacts.size == maxContacts) { lastError = "CONTACT_LIMIT"; return false }
        // A replacement camera finger should have a different identity even when the game
        // samples both edges in one rendered frame. Pointer count and the legacy ID range stay fixed.
        val free = freeIds(owner)
        if (free.isEmpty()) { lastError = "CONTACT_LIMIT"; return false }
        val id = preferredId?.takeIf { it in free && it != avoidId }
            ?: free.firstOrNull { it != avoidId } ?: free.first()
        if (contacts.isEmpty()) downTime = now()
        val index=if(orderedContacts) contacts.indexOfFirst { it.id>id }.takeIf { it>=0 } ?: contacts.size
            else beforeOwner?.let { name -> contacts.indexOfFirst { it.owner==name }.takeIf { it>=0 } } ?: contacts.size
        contacts.add(index,StoredContact(owner, id, x, y))
        val action = if (contacts.size == 1) DOWN else POINTER_DOWN or (index shl 8)
        return deliver(action)
    }

    @Synchronized fun move(owner: String, x: Float, y: Float): Boolean {
        if (!ready(owner, x, y, continuingMove = true)) return false
        val index = contacts.indexOfFirst { it.owner == owner }
        if (index < 0) { lastError = "UNKNOWN_OWNER"; return false }
        val old = contacts[index]
        if (old.x == x && old.y == y) return true
        old.x=x;old.y=y
        if(moveBatch) { pendingMove=true;return true }
        return deliver(MOVE)
    }

    @Synchronized fun end(owner: String): Boolean {
        if (paused) return false
        if(!flushPendingMove()) return false
        val index = contacts.indexOfFirst { it.owner == owner }
        if (index < 0) return true // Idempotent release after a duplicate physical up.
        val action = if (contacts.size == 1) UP else POINTER_UP or (index shl 8)
        if (!deliver(action)) return false
        contacts.removeAt(index)
        return true
    }

    /** GG ends an edge-crossing drag at its candidate, without a preceding MOVE. */
    @Synchronized fun endAt(owner:String,x:Float,y:Float):Boolean {
        if(!ready(owner,x,y,continuingMove=true) || !flushPendingMove()) return false
        val contact=contacts.firstOrNull { it.owner==owner } ?: return true
        contact.x=x;contact.y=y
        return end(owner)
    }

    /** Overlap just the two camera fingers so the game never sees an empty camera interval. */
    @Synchronized fun handoff(owner:String,x:Float,y:Float,preferredId:Int?=null):Boolean {
        if(!ready(owner,x,y)) return false
        if(!flushPendingMove()) return false
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
        contacts.add(oldIndex,StoredContact(replacement,id,x,y))
        if(!deliver(POINTER_DOWN or (oldIndex shl 8)) || !end(owner)) return false
        val index=contacts.indexOfFirst { it.owner==replacement }
        contacts[index].owner=owner
        return true
    }

    /** Global cancellation is reserved for stopping/recovering the entire session. */
    @Synchronized fun cancelAll(): Boolean {
        if(!recoveryCandidateKnown) { paused=true;releaseUnverified=true;return false }
        discardMoveBatch()
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
        discardMoveBatch()
        paused = false
        lastError = null
        return true
    }

    /** Only a verified reset of the injection device/stream may acknowledge an unreachable backend. */
    @Synchronized fun acknowledgeBackendReset() {
        discardMoveBatch()
        contacts.clear()
        releaseUnverified = false
        recoveryCandidateKnown = true
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

    private fun recordSubmittedPositions() {
        for(contact in contacts) { contact.submittedX=contact.x;contact.submittedY=contact.y }
    }

    private fun flushPendingMove():Boolean {
        if(!pendingMove) return !paused
        // Clear before entering the sink: an ambiguous reply must retain the attempted
        // position, and a reentrant cancellation must never roll it back or replay it.
        pendingMove=false
        return deliver(MOVE)
    }

    private fun deliver(action: Int): Boolean {
        if(moveBatch) recordSubmittedPositions()
        if (send(frame(action))) { lastError = null; return true }
        return failPossibleStream()
    }

    private fun failPossibleStream():Boolean {
        pendingMove=false;moveBatch=false
        paused = true
        // A binder failure can occur after delivery. Cancel the possible full pointer set.
        val cancelled = contacts.isEmpty() || send(frame(CANCEL))
        releaseUnverified = !cancelled
        if (cancelled) contacts.clear()
        lastError = if (cancelled) "INJECTION_FAILED_CANCELLED" else "RELEASE_UNVERIFIED"
        return false
    }

    private fun send(frame: TouchFrame): Boolean = try { sink(frame) } catch (_: Exception) { false }

    private fun frame(action: Int,submitted:Boolean=false): TouchFrame = TouchFrame(
        action, downTime, now(),
        IntArray(contacts.size) { contacts[it].id },
        FloatArray(contacts.size) { if(submitted) contacts[it].submittedX else contacts[it].x },
        FloatArray(contacts.size) { if(submitted) contacts[it].submittedY else contacts[it].y },
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
