package dev.mobimapper.mobimapper.engine

import kotlin.math.abs

/** The main camera policy; auxiliary Free Look uses different defaults in GG. */
data class GgCameraPolicy(val freeFire:Boolean,val bounds:AimBounds,val referenceWidth:Float) {
    init { require(referenceWidth.isFinite() && referenceWidth>0f) }
}

/**
 * GG-derived camera motion. Original samples are integrated before delivery coalesces;
 * Double coordinates retain fractional motion and every gesture uses the same anchor.
 * Free Fire uses GG's standard curve normalized to the calibrated game area.
 */
class GgCameraController(
    private val composer:TouchComposer,
    private val geometry:MappingGeometry,
    private val policy:GgCameraPolicy,
    private val clockMillis:()->Long,
) {
    init { require(composer.continuousCamera) }

    private var active=false
    // Keep fractional motion even when Android's final Float coordinate cannot represent it yet.
    private var cameraX=geometry.cameraX.toDouble()
    private var cameraY=geometry.cameraY.toDouble()
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
        cameraX=geometry.cameraX.toDouble();cameraY=geometry.cameraY.toDouble()
        lastActivityAt=null;nextAllowedStartAt=0L;pendingStartAt=null;nextIdleCheckAt=null
    }

    fun motion(rawDx:Float,rawDy:Float):Boolean {
        if(!rawDx.isFinite() || !rawDy.isFinite()) return false
        val horizontal=if(policy.freeFire) {
            val distance=abs(geometry.cameraX.toDouble()-cameraX)/policy.referenceWidth
            // Preserve source operation order: 5.6*r*r, rather than (5.6*r)^2.
            rawDx.toDouble()*((.98/((5.6*distance)*distance+1.0))+.02)*2.0
        } else rawDx.toDouble()
        val dx=horizontal*geometry.sensitivityX*if(geometry.invertX) -1 else 1
        val dy=rawDy.toDouble()*geometry.sensitivityY*if(geometry.invertY) -1 else 1
        return displayMotion(dx,dy)
    }

    /** Synthetic camera pixels do not pass through physical gain or compensation. */
    fun syntheticMotion(displayDy:Float):Boolean=displayMotion(0.0,displayDy.toDouble())

    private fun displayMotion(dx:Double,dy:Double):Boolean {
        if(!active || composer.paused) return false
        if(!dx.isFinite() || !dy.isFinite()) return composer.stopForCameraCoordinateLimit()
        val now=clockMillis()
        if(abs(dx)<MOTION_EPSILON && abs(dy)<MOTION_EPSILON) {
            lastActivityAt=now
            return true
        }
        if(!composer.hasOwner(MappingEngine.CAMERA_OWNER)) {
            if(now>=nextAllowedStartAt) {
                if(!beginCamera()) return false
                pendingStartAt=null;lastActivityAt=now
            } else if(pendingStartAt==null) pendingStartAt=nextAllowedStartAt
            // GG starts/restarts the contact with this sample; it does not replay its delta.
            return true
        }
        val nextX=cameraX+dx
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

    private fun beginCamera():Boolean {
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
        return true
    }

    /** Run due timers on the same worker as motion; no callback survives reset(). */
    fun dispatchTimers():Boolean {
        if(!active) return true
        if(composer.paused) return false
        val now=clockMillis()
        pendingStartAt?.let { deadline ->
            if(now>=deadline) {
                if(!composer.hasOwner(MappingEngine.CAMERA_OWNER) && !beginCamera()) return false
                pendingStartAt=null;lastActivityAt=now
            }
        }
        nextIdleCheckAt?.let { deadline ->
            if(now>=deadline) {
                nextIdleCheckAt=deadline+((now-deadline)/IDLE_CHECK_MILLIS+1)*IDLE_CHECK_MILLIS
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
        if(!active || composer.paused) return null
        val next=listOfNotNull(pendingStartAt,nextIdleCheckAt).minOrNull() ?: return null
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
