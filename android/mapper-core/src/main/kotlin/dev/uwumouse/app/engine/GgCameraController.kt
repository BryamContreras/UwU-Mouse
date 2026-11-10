package dev.uwumouse.app.engine

import kotlin.math.abs

/** The main camera policy; auxiliary Free Look uses different defaults in GG. */
data class GgCameraPolicy(val freeFire:Boolean,val bounds:AimBounds,val referenceWidth:Float) {
    init { require(referenceWidth.isFinite() && referenceWidth>0f) }
}

/**
 * GG-derived camera motion. Original samples are integrated before delivery coalesces;
 * Double coordinates retain fractional motion and every gesture uses the same anchor.
 * Free Fire integrates GG's standard curve reversibly over the calibrated game area.
 */
class GgCameraController(
    private val composer:TouchComposer,
    private val geometry:MappingGeometry,
    private val policy:GgCameraPolicy,
    private val clockMillis:()->Long,
) {
    init { require(composer.continuousCamera) }

    private var active=false
    private val anchorX=geometry.cameraX.coerceIn(0f,geometry.width-1f)
    private val anchorY=geometry.cameraY.coerceIn(0f,geometry.height-1f)
    // Keep fractional motion even when Android's final Float coordinate cannot represent it yet.
    private var cameraX=anchorX.toDouble()
    private var cameraY=anchorY.toDouble()
    private val horizontalCompensation=if(policy.freeFire) FreeFireCompensation(
        anchorX.toDouble(),policy.referenceWidth.toDouble(),FREE_FIRE_COORDINATE_LIMIT) else null
    private var lastActivityAt:Long?=null
    private var nextAllowedStartAt=0L
    private var pendingStartAt:Long?=null
    private var nextIdleCheckAt:Long?=null
    var restarts=0L
        private set
    var idleRetirements=0L
        private set

    /** Activation starts the idle-check cadence, but the first sample creates the finger. */
    fun activate() {
        reset()
        active=true
        if(!policy.freeFire) nextIdleCheckAt=clockMillis()+IDLE_CHECK_MILLIS
    }

    /** The engine owns releases when changing modes; cancel only this controller's timers. */
    fun reset() {
        active=false
        cameraX=anchorX.toDouble();cameraY=anchorY.toDouble()
        horizontalCompensation?.reset()
        lastActivityAt=null;nextAllowedStartAt=0L;pendingStartAt=null;nextIdleCheckAt=null
    }

    fun motion(rawDx:Float,rawDy:Float):Boolean {
        if(!rawDx.isFinite() || !rawDy.isFinite()) return false
        val dx=rawDx.toDouble()*geometry.sensitivityX*if(geometry.invertX) -1 else 1
        val dy=rawDy.toDouble()*geometry.sensitivityY*if(geometry.invertY) -1 else 1
        return displayMotion(dx,dy)
    }

    /** Synthetic camera pixels do not pass through physical gain or compensation. */
    fun syntheticMotion(displayDy:Float):Boolean=displayMotion(0.0,displayDy.toDouble())

    private fun displayMotion(dx:Double,dy:Double):Boolean {
        if(!active || composer.paused) return false
        if(!dx.isFinite() || !dy.isFinite()) return composer.stopForCameraCoordinateLimit()
        // Free Fire never retires/restarts on a timer. Its source samples need no
        // idle timestamp; TouchComposer timestamps the actual delivered frames.
        val now=if(policy.freeFire) 0L else clockMillis()
        if(abs(dx)<MOTION_EPSILON && abs(dy)<MOTION_EPSILON) {
            lastActivityAt=now
            return true
        }
        if(!composer.hasOwner(MappingEngine.CAMERA_OWNER)) {
            if(now<nextAllowedStartAt) {
                if(pendingStartAt==null) pendingStartAt=nextAllowedStartAt
                return true
            }
            if(!beginCamera(now)) return false
            pendingStartAt=null;lastActivityAt=now
            // Free Fire consumes the report after its DOWN is acknowledged. Dropping
            // that first displacement would make a cold +dx/-dx pair asymmetric.
            // Generic games retain their existing startup/edge-restart policy.
            if(!policy.freeFire) return true
        }
        if(policy.freeFire &&
            (horizontalCompensation!!.wouldClip(dx) || abs(cameraY+dy)>FREE_FIRE_COORDINATE_LIMIT) &&
            (cameraX!=anchorX.toDouble() || cameraY!=anchorY.toDouble())) {
            // Retire only camera at its last position, then begin at the safe anchor.
            // A MOVE back to the anchor would rotate the game; an acknowledged UP/DOWN
            // gives a new gesture without moving any held joystick/fire contact.
            if(!composer.end(MappingEngine.CAMERA_OWNER)) return false
            if(!beginCamera(now)) return false
            restarts++
        }
        val nextX=horizontalCompensation?.advance(dx) ?: cameraX+dx
        val nextY=cameraY+dy
        if(policy.freeFire) {
            cameraX=nextX.coerceIn(-FREE_FIRE_COORDINATE_LIMIT,FREE_FIRE_COORDINATE_LIMIT)
            cameraY=nextY.coerceIn(-FREE_FIRE_COORDINATE_LIMIT,FREE_FIRE_COORDINATE_LIMIT)
        } else {
            if(!nextX.isFinite() || !nextY.isFinite() || abs(nextX)>TouchCoordinatePolicy.LIMIT ||
                abs(nextY)>TouchCoordinatePolicy.LIMIT) return composer.stopForCameraCoordinateLimit()
            cameraX=nextX;cameraY=nextY
            if(!policy.bounds.contains(cameraX,cameraY)) {
                // The original ends at the proposed exterior point and discards its overflow.
                if(!composer.endAt(MappingEngine.CAMERA_OWNER,cameraX.toFloat(),cameraY.toFloat())) return false
                nextAllowedStartAt=now+RESTART_DELAY_MILLIS;pendingStartAt=null
                restarts++
                return true
            }
        }
        if(!composer.move(MappingEngine.CAMERA_OWNER,cameraX.toFloat(),cameraY.toFloat())) return false
        lastActivityAt=now
        return true
    }

    private fun beginCamera(now:Long):Boolean {
        var x=geometry.cameraX
        var y=geometry.cameraY
        if(!policy.freeFire) {
            x=x.coerceIn(policy.bounds.left,policy.bounds.right)
            y=y.coerceIn(policy.bounds.top,policy.bounds.bottom)
        }
        // Every newly admitted DOWN stays on the display, including an anchor near an edge.
        x=x.coerceIn(0f,geometry.width-1f);y=y.coerceIn(0f,geometry.height-1f)
        if(!composer.begin(MappingEngine.CAMERA_OWNER,x,y,preferredId=TouchCoordinatePolicy.CAMERA_ID)) return false
        cameraX=x.toDouble();cameraY=y.toDouble()
        horizontalCompensation?.reset()
        // Preserve the activation's idle-check phase without waking while no finger exists.
        advanceIdleCheckPast(now)
        return true
    }

    private fun advanceIdleCheckPast(now:Long) {
        val deadline=nextIdleCheckAt ?: return
        if(now>=deadline) nextIdleCheckAt=deadline+((now-deadline)/IDLE_CHECK_MILLIS+1)*IDLE_CHECK_MILLIS
    }

    /** Run due timers on the same worker as motion; no callback survives reset(). */
    fun dispatchTimers():Boolean {
        if(!active) return true
        if(composer.paused) return false
        if(policy.freeFire) return true
        if(pendingStartAt==null && !composer.hasOwner(MappingEngine.CAMERA_OWNER)) return true
        val now=clockMillis()
        pendingStartAt?.let { deadline ->
            if(now>=deadline) {
                if(!composer.hasOwner(MappingEngine.CAMERA_OWNER) && !beginCamera(now)) return false
                pendingStartAt=null;lastActivityAt=now
            }
        }
        nextIdleCheckAt?.let { deadline ->
            if(now>=deadline) {
                advanceIdleCheckPast(now)
                val last=lastActivityAt
                if(last!=null && now>=last && now-last>=IDLE_RELEASE_MILLIS &&
                    composer.hasOwner(MappingEngine.CAMERA_OWNER)) {
                    if(!composer.end(MappingEngine.CAMERA_OWNER)) return false
                    nextAllowedStartAt=now+RESTART_DELAY_MILLIS;pendingStartAt=null
                    idleRetirements++
                }
            }
        }
        return true
    }

    val nextWakeupDelayMillis:Long? get() {
        if(!active || composer.paused || policy.freeFire) return null
        val start=pendingStartAt
        val idle=if(composer.hasOwner(MappingEngine.CAMERA_OWNER)) nextIdleCheckAt else null
        val next=if(start==null) idle ?: return null else if(idle==null) start else minOf(start,idle)
        return (next-clockMillis()).coerceAtLeast(0L)
    }

    companion object {
        private const val MOTION_EPSILON=.0001
        private const val RESTART_DELAY_MILLIS=15L
        private const val IDLE_RELEASE_MILLIS=700L
        private const val IDLE_CHECK_MILLIS=350L
        private const val FREE_FIRE_COORDINATE_LIMIT=666_666.0
    }
}
