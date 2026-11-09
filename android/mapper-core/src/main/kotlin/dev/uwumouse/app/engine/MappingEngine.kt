package dev.uwumouse.app.engine

import kotlin.math.hypot
import kotlin.math.abs

data class MappingGeometry(
    val width: Float,
    val height: Float,
    val joystickX: Float = width * .20f,
    val joystickY: Float = height * .72f,
    val joystickRadius: Float = width * .11f,
    val cameraX: Float = width * .69f,
    val cameraY: Float = height * .43f,
    val cameraRadiusX: Float = width * .17f,
    val cameraRadiusY: Float = height * .16f,
    val actionX: Float = width * .87f,
    val actionY: Float = height * .80f,
    val fireX: Float = width * .87f,
    val fireY: Float = height * .65f,
    val sensitivityX: Float = 1f,
    val sensitivityY: Float = 1f,
    val invertX: Boolean = false,
    val invertY: Boolean = false,
) {
    init {
        val values = floatArrayOf(width, height, joystickX, joystickY, joystickRadius, cameraX,
            cameraY, cameraRadiusX, cameraRadiusY, actionX, actionY, fireX, fireY,
            sensitivityX, sensitivityY)
        require(values.all { it.isFinite() })
        require(width > 1f && height > 1f && joystickRadius > 0f)
        require(cameraRadiusX > 0f && cameraRadiusY > 0f)
        require(sensitivityX > 0f && sensitivityY > 0f)
        require(joystickX - joystickRadius >= 0f && joystickX + joystickRadius < width)
        require(joystickY - joystickRadius >= 0f && joystickY + joystickRadius < height)
        require(cameraX - cameraRadiusX >= 0f && cameraX + cameraRadiusX < width)
        require(cameraY - cameraRadiusY >= 0f && cameraY + cameraRadiusY < height)
        require(actionX >= 0 && actionX < width && actionY >= 0 && actionY < height)
        require(fireX >= 0 && fireX < width && fireY >= 0 && fireY < height)
    }
}

/** Prepared once per session. No profile parsing or storage lookup occurs in the input path. */
data class PreparedTouch(val owner:String,val x:Float,val y:Float,val key:Int?=null,val button:Int?=null,val mouseAim:Boolean=false)

data class AimBounds(val left:Float,val top:Float,val right:Float,val bottom:Float) {
    init { require(listOf(left,top,right,bottom).all { it.isFinite() } && left>=0 && top>=0 && right>left && bottom>top) }
    fun contains(x:Double,y:Double)=x>=left && y>=top && x<=right && y<=bottom
}

data class MappingBindings(
    val upKey: Int = MappingEngine.W,
    val downKey: Int = MappingEngine.S,
    val leftKey: Int = MappingEngine.A,
    val rightKey: Int = MappingEngine.D,
    val actionKey: Int? = MappingEngine.SPACE,
    val actionButton: Int? = MappingEngine.LEFT_BUTTON,
    val touches: List<PreparedTouch> = emptyList(),
    val distinctCameraContacts: Boolean = false,
    val fireDragBounds: AimBounds? = null,
    val overlapCameraHandoff: Boolean = false,
    // Ordering is independent of whether camera replacement fingers overlap.
    val cameraLast: Boolean = overlapCameraHandoff,
    val continuousCamera: Boolean = false,
    val macroKeys: Set<Int> = emptySet(),
    val macroButtons: Set<Int> = emptySet(),
    val commandKeys: Set<Int> = emptySet(),
    val commandButtons: Set<Int> = emptySet(),
    val joystickEnabled: Boolean = true,
    val cameraMappingEnabled: Boolean = true,
    val interlacedCameraHandoff: Boolean = false,
    // Legacy RAM diagnostic; GG camera owns its package-specific retirement policy.
    val retireBoundedCameraOnIdle:Boolean=false,
    val heldFireCameraBounds:AimBounds?=null,
    val ggCameraPolicy:GgCameraPolicy?=null,
) {
    init {
        val configuredDirections = listOf(upKey, downKey, leftKey, rightKey)
        val directions = if(joystickEnabled) configuredDirections else emptyList()
        require(configuredDirections.all { it > 0 } && (!joystickEnabled || directions.distinct().size == 4))
        require(actionKey == null || (actionKey > 0 && actionKey !in directions))
        require(actionButton == null || actionButton in listOf(1, 2, 4, 8, 16))
        require(touches.size <= 24 && touches.map { it.owner }.distinct().size == touches.size)
        require(touches.all { (it.key!=null) != (it.button!=null) && it.x.isFinite() && it.y.isFinite() })
        val keys=touches.mapNotNull { it.key }; val buttons=touches.mapNotNull { it.button }
        require(keys.all { it>0 && it !in directions })
        require(buttons.all { it in listOf(1,2,4,8,16) } && buttons.distinct().size==buttons.size)
        require(macroKeys.all { it in 1..340 && it !in directions && it != actionKey && it !in keys })
        require(macroButtons.all { it in listOf(1,2,4,8,16) && it != actionButton && it !in buttons })
        require(commandKeys.all { it in 1..340 && it !in directions && it != actionKey && it !in keys && it !in macroKeys })
        require(commandButtons.all { it in listOf(1,2,4,8,16) && it != actionButton && it !in buttons && it !in macroButtons })
        require(touches.size + macroKeys.size + macroButtons.size + commandKeys.size + commandButtons.size <= 24)
        require(touches.filter { it.mouseAim }.all { it.button==1 && fireDragBounds?.contains(it.x.toDouble(),it.y.toDouble())==true })
        require(!continuousCamera || touches.none { it.mouseAim }) { "Continuous camera requires independent fixed actions" }
        require(!continuousCamera || cameraMappingEnabled)
        require(ggCameraPolicy==null || continuousCamera) { "GG camera requires exterior camera coordinates" }
    }
    fun handlesKey(code: Int): Boolean = (joystickEnabled && (code == upKey || code == downKey || code == leftKey || code == rightKey)) || code == actionKey || touches.any { it.key==code } || code in macroKeys || code in commandKeys
    fun handlesButton(code: Int): Boolean = code == actionButton || touches.any { it.button==code } || code in macroButtons || code in commandButtons
}

/**
 * Initial laboratory bindings. Input callbacks are serialized by the Android session owner.
 * Booleans from key/button methods mean 'binding recognized/consume', not successful injection.
 * Check composer.paused/lastOperationSucceeded before continuing a session after backend errors.
 * Relative deltas are display pixel units, not a claim of unaccelerated raw hardware input.
 */
class MappingEngine(
    private val composer: TouchComposer,
    private var geometry: MappingGeometry,
    private var bindings: MappingBindings = MappingBindings(),
    private val clockMillis:()->Long = { System.nanoTime()/1_000_000L },
) {
    init {
        require(bindings.continuousCamera==composer.continuousCamera) { "Camera capability requires a matching composer session" }
        require(heldFireBoundsFit(bindings,geometry)) { "Held-fire camera bounds must remain inside the display" }
    }
    private val physicalKeys = HashSet<Int>(5)
    private val physicalButtons = HashSet<Int>(5)
    private var fixedFireOwner=bindings.touches.firstOrNull { it.button==LEFT_BUTTON && !it.mouseAim }?.owner
    @Volatile private var fireAim:PreparedTouch?=null
    val aimOwner:String get()=fireAim?.owner ?: CAMERA_OWNER
    @Volatile var fireHoldStarts=0L;private set
    @Volatile var fireHoldEnds=0L;private set
    @Volatile var fireBoundaryClamps=0L;private set
    @Volatile var heldFireCameraClamps=0L;private set
    private var lastHorizontal = 0
    private var lastVertical = 0
    private var cameraX = geometry.cameraX.toDouble()
    private var cameraY = geometry.cameraY.toDouble()
    private var cameraBounds=cameraBounds(geometry)
    private var lastCameraId: Int? = null
    private var continuousTravel=0.0
    private var ggCamera=createGgCamera()
    private var ggRestartsSeen=0L
    private var ggIdleRetirementsSeen=0L
    var cameraEnabled = false
        private set
    @Volatile var cameraRestarts = 0L
        private set
    @Volatile var idleCameraRetirements = 0L
        private set
    var lastOperationSucceeded = true
        private set
    var cursorEnabled=false
        private set

    /** Explicit handoff closes the stream; held controls never seed the next mode. */
    fun enterCursorMode():Boolean {
        if(!releaseForModeChange()) return false
        cursorEnabled=true
        return true
    }
    fun leaveCursorMode():Boolean {
        if(!releaseForModeChange()) return false
        return setCameraEnabled(true)
    }
    private fun releaseForModeChange():Boolean {
        ggCamera?.reset()
        physicalKeys.clear();physicalButtons.clear();fireAim=null;lastHorizontal=0;lastVertical=0;cameraEnabled=false;cursorEnabled=false
        cameraX=geometry.cameraX.toDouble();cameraY=geometry.cameraY.toDouble()
        continuousTravel=0.0
        if(composer.paused || composer.releaseUnverified) { lastOperationSucceeded=false;return false }
        // CANCEL terminates the guardian session. Mode changes stay in that session,
        // ending each current owner with POINTER_UP/UP before another DOWN is allowed.
        for(contact in composer.snapshot()) if(!composer.end(contact.owner)) { lastOperationSucceeded=false;return false }
        lastOperationSucceeded=true
        return true
    }
    fun cursorButton(down:Boolean,x:Float,y:Float):Boolean {
        if(!cursorEnabled || composer.paused || (down && !cursorPointValid(x,y))) return false
        lastOperationSucceeded=if(down) composer.begin(CURSOR_OWNER,x,y) else composer.end(CURSOR_OWNER)
        return lastOperationSucceeded
    }
    fun cursorMove(x:Float,y:Float):Boolean {
        if(!cursorEnabled || composer.paused || !cursorPointValid(x,y)) return false
        lastOperationSucceeded=if(composer.hasOwner(CURSOR_OWNER)) composer.move(CURSOR_OWNER,x,y) else true
        return lastOperationSucceeded
    }
    private fun cursorPointValid(x:Float,y:Float)=x.isFinite() && y.isFinite() && x>=0 && y>=0 && x<geometry.width && y<geometry.height

    fun key(keyCode: Int, down: Boolean): Boolean {
        if(cursorEnabled) return false
        if (!bindings.handlesKey(keyCode)) return false
        if (composer.paused) return true
        val changed = if (down) physicalKeys.add(keyCode) else physicalKeys.remove(keyCode)
        if (!changed) return true
        if (down && bindings.joystickEnabled) when (keyCode) {
            bindings.leftKey, bindings.rightKey -> lastHorizontal = keyCode
            bindings.upKey, bindings.downKey -> lastVertical = keyCode
        }
        val touches=bindings.touches.filter { it.key==keyCode }
        lastOperationSucceeded = if(touches.isNotEmpty()) {
            touches.all { touch -> if(down) beginControl(touch.owner,touch.x,touch.y) else composer.end(touch.owner) }
        } else if (keyCode == bindings.actionKey) {
            if (down) beginControl(ACTION_OWNER, geometry.actionX, geometry.actionY)
            else composer.end(ACTION_OWNER)
        } else if(bindings.joystickEnabled) updateJoystick() else true
        return true
    }

    fun mouseButton(button: Int, down: Boolean): Boolean {
        if(cursorEnabled) return false
        if (!bindings.handlesButton(button)) return false
        if (composer.paused) return true
        val changed=if(down) physicalButtons.add(button) else physicalButtons.remove(button)
        if(!changed) return true
        val touch=bindings.touches.firstOrNull { it.button==button }
        if(button==1 && touch!=null) { if(down) fireHoldStarts++ else fireHoldEnds++ }
        lastOperationSucceeded = if(touch?.mouseAim==true) {
            if(down) {
                if(!composer.end(CAMERA_OWNER)) return failed()
                fireAim=touch;cameraX=touch.x.toDouble();cameraY=touch.y.toDouble()
                beginControl(touch.owner,touch.x,touch.y)
            } else {
                val ended=composer.end(touch.owner)
                fireAim=null;cameraX=geometry.cameraX.toDouble();cameraY=geometry.cameraY.toDouble()
                ended
            }
        } else if(touch!=null) {
            if(down) beginControl(touch.owner,touch.x,touch.y) else composer.end(touch.owner)
        } else if (down) beginControl(FIRE_OWNER, geometry.fireX, geometry.fireY) else composer.end(FIRE_OWNER)
        return true
    }

    fun setCameraEnabled(enabled: Boolean): Boolean {
        val requested=enabled && bindings.cameraMappingEnabled
        if (requested == cameraEnabled) return true
        if (composer.paused && requested) return false
        cameraEnabled = requested
        if(requested) ggCamera?.activate() else ggCamera?.reset()
        cameraX = geometry.cameraX.toDouble()
        cameraY = geometry.cameraY.toDouble()
        continuousTravel=0.0
        lastOperationSucceeded = if (!requested) {
            val ended=composer.end(CAMERA_OWNER) && (fireAim?.let { composer.end(it.owner) } ?: true)
            fireAim=null;physicalButtons.clear();ended
        } else true
        return lastOperationSucceeded
    }

    /** The session owner supplies the idle deadline; held physical buttons never retire aim. */
    val canRetireIdleCamera:Boolean get() {
        if(ggCamera!=null) return false
        if(!cameraEnabled || cursorEnabled || composer.paused || composer.releaseUnverified ||
            physicalButtons.isNotEmpty() || fireAim!=null || !composer.hasOwner(CAMERA_OWNER)) return false
        if(bindings.retireBoundedCameraOnIdle) return abs(cameraX-geometry.cameraX)>=geometry.cameraRadiusX.toDouble()*.70 ||
                abs(cameraY-geometry.cameraY)>=geometry.cameraRadiusY.toDouble()*.70
        if(bindings.continuousCamera) return cameraX<0.0 || cameraY<0.0 || cameraX>=geometry.width ||
            cameraY>=geometry.height || continuousTravel>=2.0*maxOf(geometry.width,geometry.height)
        return false
    }

    /** Release only an eligible idle camera finger, leaving movement and other actions held. */
    fun retireIdleCamera():Boolean {
        if(!canRetireIdleCamera) return true
        if(!composer.end(CAMERA_OWNER)) return failed()
        cameraX=geometry.cameraX.toDouble();cameraY=geometry.cameraY.toDouble()
        lastCameraId=null
        continuousTravel=0.0
        idleCameraRetirements++
        lastOperationSucceeded=true
        return true
    }

    /**
     * Integrates fractional relative movement without a frame-rate multiplier. Continuous mode
     * starts one camera finger at its anchor and retains that finger beyond the display bounds.
     * Bounded mode segments at its configured region. The finite continuation limit pauses the
     * whole stream rather than recycling camera or silently discarding displacement.
     */
    fun relativeMouse(dx: Float, dy: Float): Boolean {
        if (!bindings.cameraMappingEnabled || !cameraEnabled || composer.paused || !dx.isFinite() || !dy.isFinite()) return false
        ggCamera?.let { return acceptGgCameraResult(it.motion(dx,dy)) }
        val displayX=dx.toDouble()*geometry.sensitivityX*if(geometry.invertX) -1 else 1
        val displayY=dy.toDouble()*geometry.sensitivityY*if(geometry.invertY) -1 else 1
        return cameraMotionInDisplay(displayX,displayY)
    }

    /** Synthetic display pixels bypass physical sensitivity/inversion and never modify M1. */
    fun syntheticCameraMotion(deltaYInDisplayPixels:Float):Boolean {
        if(!bindings.cameraMappingEnabled || !cameraEnabled || cursorEnabled || composer.paused ||
            fireAim!=null || !deltaYInDisplayPixels.isFinite()) return false
        ggCamera?.let { return acceptGgCameraResult(it.syntheticMotion(deltaYInDisplayPixels)) }
        return cameraMotionInDisplay(0.0,deltaYInDisplayPixels.toDouble())
    }

    /** The Android owner runs these deadlines on the same worker as physical input. */
    val ggCameraWakeupDelayMillis:Long? get() =
        if(cameraEnabled && !cursorEnabled && !composer.paused) ggCamera?.nextWakeupDelayMillis else null

    fun dispatchGgCameraTimers():Boolean {
        if(!cameraEnabled || cursorEnabled || composer.paused) return true
        return ggCamera?.let { acceptGgCameraResult(it.dispatchTimers()) } ?: true
    }

    private fun createGgCamera()=bindings.ggCameraPolicy?.let {
        GgCameraController(composer,geometry,it,clockMillis)
    }

    private fun acceptGgCameraResult(accepted:Boolean):Boolean {
        ggCamera?.let {
            cameraRestarts+=it.restarts-ggRestartsSeen
            idleCameraRetirements+=it.idleRetirements-ggIdleRetirementsSeen
            ggRestartsSeen=it.restarts;ggIdleRetirementsSeen=it.idleRetirements
        }
        lastOperationSucceeded=accepted
        return accepted
    }

    private fun cameraMotionInDisplay(dx:Double,dy:Double):Boolean {
        var remainingX=dx
        var remainingY=dy
        if (!remainingX.isFinite() || !remainingY.isFinite()) return false
        if (remainingX == 0.0 && remainingY == 0.0) return true
        if(bindings.continuousCamera) {
            val nextX=cameraX+remainingX;val nextY=cameraY+remainingY
            if(!nextX.isFinite() || !nextY.isFinite() || abs(nextX)>TouchCoordinatePolicy.LIMIT || abs(nextY)>TouchCoordinatePolicy.LIMIT) {
                cursorEnabled=false;cameraEnabled=false
                physicalKeys.clear();physicalButtons.clear();fireAim=null;lastHorizontal=0;lastVertical=0
                composer.stopForCameraCoordinateLimit()
                return failed()
            }
            if(!composer.hasOwner(CAMERA_OWNER) && !beginAim(CAMERA_OWNER)) return failed()
            cameraX=nextX;cameraY=nextY
            lastOperationSucceeded=composer.move(CAMERA_OWNER,cameraX.toFloat(),cameraY.toFloat())
            if(lastOperationSucceeded) continuousTravel+=abs(remainingX)+abs(remainingY)
            return lastOperationSucceeded
        }
        val restartsAtEntry=cameraRestarts
        val fire=fireAim
        val owner=fire?.owner ?: CAMERA_OWNER
        val anchorX=fire?.x?.toDouble() ?: geometry.cameraX.toDouble()
        val anchorY=fire?.y?.toDouble() ?: geometry.cameraY.toDouble()
        val bounds=if(fire!=null) requireNotNull(bindings.fireDragBounds) else cameraBounds
        if (!composer.hasOwner(owner) && !beginAim(owner)) return failed()
        if(fire!=null) {
            // A held physical trigger must never become UP/DOWN at a screen boundary.
            // At the limit retain the same finger until release; a new press starts at its HUD anchor.
            if(!bounds.contains(cameraX+remainingX,cameraY+remainingY)) fireBoundaryClamps++
            cameraX=(cameraX+remainingX).coerceIn(bounds.left.toDouble(),bounds.right.toDouble())
            cameraY=(cameraY+remainingY).coerceIn(bounds.top.toDouble(),bounds.bottom.toDouble())
            lastOperationSucceeded=composer.move(owner,cameraX.toFloat(),cameraY.toFloat())
            return lastOperationSucceeded
        }
        val heldBounds=bindings.heldFireCameraBounds
        if(heldBounds!=null && LEFT_BUTTON in physicalButtons && fixedFireOwner?.let { composer.hasOwner(it) }==true) {
            // During a physical fire hold, retain the camera finger the game already follows.
            // The real game bounds remain strict; a reversal responds from the clamped point.
            if(!heldBounds.contains(cameraX+remainingX,cameraY+remainingY)) heldFireCameraClamps++
            cameraX=(cameraX+remainingX).coerceIn(heldBounds.left.toDouble(),heldBounds.right.toDouble())
            cameraY=(cameraY+remainingY).coerceIn(heldBounds.top.toDouble(),heldBounds.bottom.toDouble())
            lastOperationSucceeded=composer.move(owner,cameraX.toFloat(),cameraY.toFloat())
            return lastOperationSucceeded
        }
        // A held-fire sample may have left the normal camera region. Restore its anchor
        // only on a later movement after physical UP, then integrate that new delta once.
        if(!bounds.contains(cameraX,cameraY) && !restartAim(owner,anchorX,anchorY)) return failed()
        // When a complete sample fits at the anchor, restart before consuming it. Sending
        // MOVE to the old edge immediately before UP is redundant in this case: retain the
        // entire delta on the new finger, saving one synchronous IPC at the boundary.
        if((bindings.distinctCameraContacts || bindings.cameraLast) && !bounds.contains(cameraX+remainingX,cameraY+remainingY) &&
            bounds.contains(anchorX+remainingX,anchorY+remainingY)) {
            if((bindings.interlacedCameraHandoff && composer.canInterlaceCamera) ||
                (!bindings.overlapCameraHandoff && composer.canResetCamera(owner))) {
                val targetX=anchorX+remainingX;val targetY=anchorY+remainingY
                val accepted=if(bindings.interlacedCameraHandoff && composer.canInterlaceCamera)
                    composer.interlaceCameraAndMove(owner,anchorX.toFloat(),anchorY.toFloat(),
                        targetX.toFloat(),targetY.toFloat(),if(lastCameraId==8)9 else 8)
                    else composer.resetCameraAndMove(owner,anchorX.toFloat(),anchorY.toFloat(),
                        targetX.toFloat(),targetY.toFloat(),if(lastCameraId==8)9 else 8)
                if(!accepted) return failed()
                cameraX=targetX;cameraY=targetY
                lastCameraId=composer.snapshot().last().id
                cameraRestarts++
                lastOperationSucceeded=true
                return true
            }
            if(!restartAim(owner,anchorX,anchorY)) return failed()
        }
        var segments = 0
        while (remainingX != 0.0 || remainingY != 0.0) {
            if (++segments > MAX_CAMERA_SEGMENTS) { pause(); return failed() }
            var fraction = 1.0
            if (remainingX > 0.0) fraction = minOf(fraction,
                (bounds.right - cameraX) / remainingX)
            if (remainingX < 0.0) fraction = minOf(fraction,
                (bounds.left - cameraX) / remainingX)
            if (remainingY > 0.0) fraction = minOf(fraction,
                (bounds.bottom - cameraY) / remainingY)
            if (remainingY < 0.0) fraction = minOf(fraction,
                (bounds.top - cameraY) / remainingY)
            fraction = fraction.coerceIn(0.0, 1.0)
            if (fraction > 0.0) {
                cameraX += remainingX * fraction
                cameraY += remainingY * fraction
                if (!composer.move(owner, cameraX.toFloat(), cameraY.toFloat())) return failed()
            }
            if (fraction >= 1.0) break
            remainingX *= 1.0 - fraction
            remainingY *= 1.0 - fraction
            if (!restartAim(owner,anchorX,anchorY)) return failed()
        }
        // A new finger's first sample must not turn into the entire remaining transport
        // batch. Keep later samples coalesced, but publish this initial displacement now.
        if(cameraRestarts!=restartsAtEntry && !composer.flushMoveBatch()) return failed()
        lastOperationSucceeded = true
        return true
    }

    private fun restartAim(owner:String,anchorX:Double,anchorY:Double):Boolean {
        if(bindings.overlapCameraHandoff && owner==CAMERA_OWNER) {
            if(!composer.handoff(owner,anchorX.toFloat(),anchorY.toFloat(),if(lastCameraId==8)9 else 8)) return false
            cameraX=anchorX;cameraY=anchorY
            lastCameraId=composer.snapshot().first { it.owner==owner }.id
            cameraRestarts++
            return true
        }
        if(!composer.end(owner)) return false
        cameraX=anchorX;cameraY=anchorY
        if(!beginAim(owner)) return false
        cameraRestarts++
        return true
    }

    private fun beginAim(owner:String): Boolean {
        if(bindings.continuousCamera && owner==CAMERA_OWNER) {
            if(!composer.begin(owner,cameraX.toFloat(),cameraY.toFloat(),preferredId=TouchCoordinatePolicy.CAMERA_ID)) return false
            lastCameraId=TouchCoordinatePolicy.CAMERA_ID
            continuousTravel=0.0
            return true
        }
        val dedicated=bindings.distinctCameraContacts && owner==CAMERA_OWNER
        val orderedCamera=bindings.cameraLast && owner==CAMERA_OWNER
        // Prefer high camera IDs; ordinary actions still allocate low IDs. This avoids
        // reusing the just-released shooting finger for camera at typical contact counts.
        // An ordered, sequential same-ID trial starts at the same ID as the distinct trial,
        // then explicitly reuses it even if a lower action ID has since become available.
        val preferredId=when {
            dedicated -> if(lastCameraId==8) 9 else 8
            orderedCamera -> lastCameraId ?: 8
            else -> null
        }
        if (!composer.begin(owner, cameraX.toFloat(), cameraY.toFloat(),
                if (dedicated) lastCameraId else null, preferredId)) return false
        if(dedicated || orderedCamera) lastCameraId = composer.snapshot().first { it.owner == CAMERA_OWNER }.id
        return true
    }

    private fun beginControl(owner:String,x:Float,y:Float)=composer.begin(owner,x,y,
        beforeOwner=if(bindings.cameraLast) CAMERA_OWNER else null)

    fun pause(): Boolean {
        ggCamera?.reset()
        cursorEnabled=false
        physicalKeys.clear()
        physicalButtons.clear();fireAim=null
        lastHorizontal = 0
        lastVertical = 0
        cameraEnabled = false
        cameraX = geometry.cameraX.toDouble()
        cameraY = geometry.cameraY.toDouble()
        lastOperationSucceeded = composer.cancelAll()
        return lastOperationSucceeded
    }

    fun resume(): Boolean {
        cursorEnabled=false
        physicalKeys.clear() // Reconnection never resurrects a held fire or direction.
        physicalButtons.clear();fireAim=null
        lastHorizontal = 0
        lastVertical = 0
        lastOperationSucceeded = composer.resume()
        return lastOperationSucceeded
    }

    /** Coordinate changes are permitted only at a confirmed empty transition. */
    fun updateGeometry(newGeometry: MappingGeometry): Boolean {
        if (composer.activeCount != 0 || composer.releaseUnverified || !heldFireBoundsFit(bindings,newGeometry)) return false
        geometry = newGeometry
        ggCamera=createGgCamera();ggRestartsSeen=0L;ggIdleRetirementsSeen=0L
        if(cameraEnabled) ggCamera?.activate()
        cameraBounds=cameraBounds(newGeometry)
        cameraX = geometry.cameraX.toDouble()
        cameraY = geometry.cameraY.toDouble()
        return true
    }

    /** Called only after the old stream is empty; held state is never transferred to a new profile. */
    fun updateConfiguration(newGeometry: MappingGeometry, newBindings: MappingBindings): Boolean {
        if (composer.activeCount != 0 || composer.releaseUnverified ||
            newBindings.continuousCamera!=composer.continuousCamera || !heldFireBoundsFit(newBindings,newGeometry)) return false
        cursorEnabled=false
        geometry = newGeometry
        cameraBounds=cameraBounds(newGeometry)
        bindings = newBindings
        ggCamera=createGgCamera();ggRestartsSeen=0L;ggIdleRetirementsSeen=0L
        fixedFireOwner=newBindings.touches.firstOrNull { it.button==LEFT_BUTTON && !it.mouseAim }?.owner
        physicalButtons.clear();fireAim=null
        physicalKeys.clear()
        lastHorizontal = 0; lastVertical = 0
        cameraEnabled = false
        cameraX = geometry.cameraX.toDouble(); cameraY = geometry.cameraY.toDouble()
        lastOperationSucceeded = true
        return true
    }

    private fun updateJoystick(): Boolean {
        val x = direction(bindings.leftKey, bindings.rightKey, lastHorizontal)
        val y = direction(bindings.upKey, bindings.downKey, lastVertical)
        if (x == 0 && y == 0) return composer.end(JOYSTICK_OWNER)
        val norm = maxOf(1f, hypot(x.toFloat(), y.toFloat()))
        val targetX = geometry.joystickX + x / norm * geometry.joystickRadius
        val targetY = geometry.joystickY + y / norm * geometry.joystickRadius
        if (!composer.hasOwner(JOYSTICK_OWNER) &&
            !beginControl(JOYSTICK_OWNER, geometry.joystickX, geometry.joystickY)) return false
        return composer.move(JOYSTICK_OWNER, targetX, targetY)
    }

    private fun direction(negative: Int, positive: Int, last: Int): Int {
        val n = negative in physicalKeys
        val p = positive in physicalKeys
        return when {
            n && p -> if (last == negative) -1 else 1
            n -> -1
            p -> 1
            else -> 0
        }
    }

    private fun failed(): Boolean { lastOperationSucceeded = false; return false }

    private fun cameraBounds(g:MappingGeometry)=AimBounds(g.cameraX-g.cameraRadiusX,g.cameraY-g.cameraRadiusY,
        g.cameraX+g.cameraRadiusX,g.cameraY+g.cameraRadiusY)

    private fun heldFireBoundsFit(b:MappingBindings,g:MappingGeometry)=b.heldFireCameraBounds?.let {
        it.right<g.width && it.bottom<g.height
    } ?: true

    companion object {
        const val W = 51
        const val A = 29
        const val S = 47
        const val D = 32
        const val SPACE = 62
        const val LEFT_BUTTON = 1
        const val JOYSTICK_OWNER = "joystick"
        const val ACTION_OWNER = "action"
        const val FIRE_OWNER = "fire"
        const val CAMERA_OWNER = "camera"
        const val CURSOR_OWNER = "cursor"
        private const val MAX_CAMERA_SEGMENTS = 64
    }
}
