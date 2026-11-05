package dev.mobimapper.mobimapper.session

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.graphics.Rect
import dev.mobimapper.mobimapper.game.GameCoordinator
import dev.mobimapper.mobimapper.editor.GameCursorOverlay
import dev.mobimapper.mobimapper.engine.TouchFrame

/** Context uses only window package/type/display/layer/bounds, without view text traversal. */
class CaptureService : AccessibilityService(), InputManager.InputDeviceListener {
    private val mouseButtons = MouseButtonGate()
    private val consumedKeys = HashSet<Long>()
    private val nativeKeys=HashSet<Long>()
    private val profileSwitches=ProfileSwitchGate()
    private val cursorDelegate=lazy { GameCursorOverlay(this) }
    private val cursorOverlay by cursorDelegate
    val cursorVisible:Boolean get()=cursorDelegate.isInitialized() && cursorOverlay.visible
    @Volatile var cursorX=0f;private set
    @Volatile var cursorY=0f;private set
    @Volatile var cursorSamples=0L;private set
    private val cursorPosition=CursorPositionPolicy()
    private var cursorPresentationActive=false
    private var cursorUiOwned=false
    private var cursorGameDown=false
    private var cursorBlockedClick=false
    private data class WindowMetadata(
        val id:Int,val type:Int,val packageName:String?,val frame:ContextWindowFrame,
        val focused:Boolean,val active:Boolean,val rootResolved:Boolean,
    )
    private data class CachedWindowContext(
        val targetPackage:String="",val selected:ApplicationWindowCandidate?=null,
        val rootPackage:String?=null,val foregroundPackage:String?=null,
        val targetBounds:PointerBounds?=null,val blockedBounds:List<PointerBounds> = emptyList(),
        val windows:List<WindowMetadata> = emptyList(),val blockedWindows:List<WindowMetadata> = emptyList(),
        val capturedUptime:Long=0L,
    )
    @Volatile private var windowContext=CachedWindowContext()
    private var windowOwners:Map<Int,String> = emptyMap()
    private val cursorTarget:PointerBounds? get()=windowContext.targetBounds
    private val cursorBlocked:List<PointerBounds> get()=windowContext.blockedBounds
    private var cursorSystemGesture=false
    private var cursorSystemStartY=0f
    private var cursorOpenNotifications=false
    private var captureEpoch=-1L
    private var physicalButtons=0
    val physicalMouseButtons:Int get()=physicalButtons
    private var physicalButtonsKnown=false
    private fun keysReleased()=consumedKeys.isEmpty() && nativeKeys.isEmpty() && profileSwitches.isEmpty
    private val main = Handler(Looper.getMainLooper())
    private var appliedMouseCapture = false
    val mouseCaptured:Boolean get()=appliedMouseCapture
    private var requestedMouseCapture = false
    private var sourceTailPending = false
    private var deferredSourceChanges = 0L
    private var tailTimeouts = 0L
    private var controlTraceUntil = 0L
    @Volatile var currentApplicationPackage:String?=null; private set
    @Volatile var contextBlockedBySystemWindow=false; private set
    @Volatile var contextBlockReason=""; private set
    @Volatile var contextSystemWindowCount=0; private set
    @Volatile var contextCoveringSystemPackage:String?=null; private set
    @Volatile var contextCheckedUptime=0L; private set
    private val buttonMasks=intArrayOf(MotionEvent.BUTTON_PRIMARY,MotionEvent.BUTTON_SECONDARY,MotionEvent.BUTTON_TERTIARY,MotionEvent.BUTTON_BACK,MotionEvent.BUTTON_FORWARD)
    private val applySources = Runnable {
        if (instance === this && keysReleased()) applyRequestedSources()
    }
    private val sourceTailTimeout = Runnable {
        sourceTailPending = false
        if (requestedMouseCapture != appliedMouseCapture && !keysReleased()) {
            tailTimeouts++
            // Never reset the keyboard stream while leaving the corresponding joystick active.
            SessionController.pause("Cambio de captura sin soltar teclas en 2 segundos; reanuda manualmente")
            forceMouseOff("tail_timeout")
        } else scheduleSourceChange()
    }
    override fun onServiceConnected() {
        instance = this
        dev.mobimapper.mobimapper.backend.BackendRegistry.requestAttach(this)
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            setMotionEventSources(0)
        }
        getSystemService(InputManager::class.java).registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
        GameCoordinator.attach(this)
        refreshContext()
    }
    fun captureMouse(enabled: Boolean,epoch:Long=-1) {
        // A new session can reuse still-applied sources while a previous key UP is deferred.
        // Reset ownership even in that case; held buttons never seed the new engine.
        if (enabled) {
            if(captureEpoch!=epoch) clearCursorInteraction()
            if(appliedMouseCapture && captureEpoch==epoch && physicalButtonsKnown) mouseButtons.rebase(physicalButtons)
            else mouseButtons.reset()
            captureEpoch=epoch
        }
        requestedMouseCapture = enabled
        updateCursorPresentation()
        if (!enabled && (!SessionController.targetConfirmed ||
                !getSystemService(PowerManager::class.java).isInteractive ||
                getSystemService(KeyguardManager::class.java).isKeyguardLocked)) {
            forceMouseOff("context_lost")
            return
        }
        scheduleSourceChange()
    }
    private fun scheduleSourceChange() {
        main.removeCallbacks(applySources)
        if (requestedMouseCapture == appliedMouseCapture) {
            main.removeCallbacks(sourceTailTimeout); sourceTailPending = false
            return
        }
        if (!keysReleased()) {
            if (!sourceTailPending) {
                sourceTailPending = true; deferredSourceChanges++
                main.postDelayed(sourceTailTimeout, 2_000)
            }
            return
        }
        main.removeCallbacks(sourceTailTimeout); sourceTailPending = false
        // AccessibilityService returns setOnKeyEventResult only after onKeyEvent returns.
        // Posting ensures the last owned UP is acknowledged before setServiceInfo resets streams.
        main.post(applySources)
    }
    private fun applyRequestedSources() {
        if (requestedMouseCapture == appliedMouseCapture) return
        mouseButtons.reset()
        serviceInfo = serviceInfo.apply {
            setMotionEventSources(if (requestedMouseCapture)
                InputDevice.SOURCE_MOUSE or InputDevice.SOURCE_MOUSE_RELATIVE else 0)
        }
        appliedMouseCapture = requestedMouseCapture
        if(!appliedMouseCapture) SessionController.nativeMouseReleased()
        physicalButtonsKnown=false
        updateCursorPresentation()
        GameCoordinator.refreshControls()
        if(!appliedMouseCapture) main.postDelayed({
            if(instance===this && !appliedMouseCapture) GameCoordinator.refreshControls()
        },32)
        if (SystemClock.uptimeMillis() <= controlTraceUntil) {
            Log.i("MobiMapperKeys", "control_source_update mouse=$appliedMouseCapture pending=${consumedKeys.size} deferred=$deferredSourceChanges timeouts=$tailTimeouts")
        }
    }
    private fun forceMouseOff(reason: String) {
        requestedMouseCapture = false
        main.removeCallbacks(applySources); main.removeCallbacks(sourceTailTimeout); sourceTailPending = false
        nativeKeys.clear()
        clearCursorInteraction()
        if (appliedMouseCapture) {
            val pending = consumedKeys.size
            // A forced filter reset can bypass UP callbacks. Do not retain stale owners afterwards.
            consumedKeys.clear()
            profileSwitches.clear()
            applyRequestedSources()
            if (pending > 0) Log.w("MobiMapperKeys", "control_tail_unverified reason=$reason pending=$pending timeouts=$tailTimeouts")
        }
    }
    private fun traceControl(event: KeyEvent, handled: Boolean, owned: Boolean) {
        if (event.keyCode != KeyEvent.KEYCODE_F8 && event.keyCode != KeyEvent.KEYCODE_F9) return
        val now = SystemClock.uptimeMillis()
        if (SessionController.active && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) controlTraceUntil = now + 2_000
        if ((SessionController.active || owned || now <= controlTraceUntil) && event.repeatCount == 0) {
            val name = if (event.keyCode == KeyEvent.KEYCODE_F8) "F8" else "F9"
            Log.i("MobiMapperKeys", "control=$name action=${event.action} code=${event.keyCode} scan=${event.scanCode} device=${event.deviceId} handled=$handled owned=$owned active=${SessionController.active} mouse=$appliedMouseCapture requested=$requestedMouseCapture tail=${consumedKeys.size}")
        }
    }
    fun boundsForPackage(packageName:String):Rect? {
        val snapshot=windowContext
        if(snapshot.targetPackage!=packageName) return null
        val bounds=snapshot.selected?.frame ?: return null
        return Rect(bounds.left,bounds.top,bounds.right,bounds.bottom)
    }
    /** Launch deduplication only; an occluding window can still forbid mapping in this context. */
    fun isPackageForegroundForLaunch(packageName:String):Boolean {
        val snapshot=windowContext
        return snapshot.targetPackage==packageName && snapshot.selected?.packageName==packageName &&
            snapshot.foregroundPackage==packageName
    }
    /** Cold diagnostics of the same immutable snapshot used by input; no root/window IPC here. */
    fun contextWindowDiagnostics():Map<String,Any?> {
        val snapshot=windowContext
        fun bounds(frame:ContextWindowFrame)=listOf(frame.left,frame.top,frame.right,frame.bottom)
        fun metadata(window:WindowMetadata)=mapOf("windowId" to window.id,"packageName" to window.packageName,
            "type" to window.type,"layer" to window.frame.layer,"bounds" to bounds(window.frame),
            "focused" to window.focused,"active" to window.active,"rootResolved" to window.rootResolved)
        return mapOf("targetPackage" to snapshot.targetPackage,"selectedWindowId" to snapshot.selected?.id,
            "selectedBounds" to snapshot.selected?.frame?.let(::bounds),"targetWindowPresent" to (snapshot.selected!=null),
            "foregroundPackage" to snapshot.foregroundPackage,"rootPackage" to snapshot.rootPackage,
            "windowCount" to snapshot.windows.size,"capturedUptime" to snapshot.capturedUptime,
            "blockedBounds" to snapshot.blockedWindows.map { bounds(it.frame) },
            "blockedWindows" to snapshot.blockedWindows.map(::metadata),"windows" to snapshot.windows.map(::metadata))
    }
    private fun frame(window:AccessibilityWindowInfo):ContextWindowFrame {
        val bounds=Rect().also { window.getBoundsInScreen(it) }
        return ContextWindowFrame(window.displayId,window.layer,bounds.left,bounds.top,bounds.right,bounds.bottom)
    }
    /** Window metadata is refreshed on window events, never by each mouse sample. */
    fun refreshContext() {
        val target=SessionController.targetPackage
        val currentRoot=runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
        // Resolve each root once. Retain known ownership for a continuously visible window ID,
        // but never retain bounds/a window after it disappears from this snapshot.
        val rawWindows=windows.filter { it.displayId==0 }.mapNotNull { window -> runCatching {
            val owner=runCatching { window.root?.packageName?.toString() }.getOrNull()
            WindowMetadata(window.id,window.type,owner,frame(window),window.isFocused,window.isActive,owner!=null)
        }.getOrNull() }
        windowOwners=WindowSelectionPolicy.resolveOwners(rawWindows.map { WindowOwner(it.id,it.packageName) },windowOwners)
        val contextWindows=rawWindows.map { it.copy(packageName=windowOwners[it.id]) }
        val appWindows=contextWindows.filter { it.type==AccessibilityWindowInfo.TYPE_APPLICATION }
        val focusedApp=appWindows.firstOrNull { it.focused }?.packageName ?: appWindows.firstOrNull { it.active }?.packageName
        val ownEditor=(GameCoordinator.ownOverlayVisible && currentRoot==packageName) ||
            (currentRoot==null && contextWindows.any {
                WindowSelectionPolicy.ownsFocusedEditorOverlay(GameCoordinator.editorVisible,it.type,
                    it.packageName,packageName,it.frame,it.focused)
            })
        val foreground=if(ownEditor) focusedApp else if(currentRoot==target && focusedApp!=null && focusedApp!=target)
            null else currentRoot ?: focusedApp
        val previous=windowContext.takeIf { it.targetPackage==target }?.selected?.id
        val targetWindow=WindowSelectionPolicy.selectTarget(appWindows.map {
            ApplicationWindowCandidate(it.id,it.packageName,it.frame,it.focused,it.active)
        },target,previous)
        val targetFrame=targetWindow?.frame
        val targetBounds=targetFrame?.let { PointerBounds(it.left.toFloat(),it.top.toFloat(),it.right.toFloat(),it.bottom.toFloat()) }
        val candidates=contextWindows.map { ContextWindowCandidate(it.type,it.packageName,it.frame) }
        val blockedWindows=if(targetFrame==null) emptyList() else contextWindows.filterIndexed { index,_ ->
            SessionPolicy.potentiallyBlockingWindow(targetFrame,candidates[index],target,packageName)
        }
        val blockedBounds=blockedWindows.map { candidate -> candidate.frame.let {
            PointerBounds(it.left.toFloat(),it.top.toFloat(),it.right.toFloat(),it.bottom.toFloat())
        } }
        val systemWindows=contextWindows.filter { it.type==AccessibilityWindowInfo.TYPE_SYSTEM }
        val coveringWindow=targetFrame?.let { SessionPolicy.coveringWindow(it,candidates,target,packageName) }
        val blocked=coveringWindow!=null
        val coveringPackage=coveringWindow?.packageName
        val capturedUptime=SystemClock.uptimeMillis()
        windowContext=CachedWindowContext(target,targetWindow,currentRoot,foreground,targetBounds,blockedBounds,
            contextWindows,blockedWindows,capturedUptime)
        if(blocked!=contextBlockedBySystemWindow || coveringPackage!=contextCoveringSystemPackage) {
            Log.i("MobiMapperContext","system_cover blocked=$blocked package=$coveringPackage type=${coveringWindow?.type} layer=${coveringWindow?.frame?.layer} target=$target uptime=$capturedUptime")
        }
        contextBlockedBySystemWindow=blocked
        contextBlockReason=if(blocked) "Una ventana superpuesta cubre el juego; ciérrala e inicia otra sesión" else ""
        contextSystemWindowCount=systemWindows.size
        contextCoveringSystemPackage=coveringPackage
        contextCheckedUptime=capturedUptime
        // This cached property also gates awaitTarget/armProfile. Focus alone must not arm a
        // session while an external window, even of unknown type, covers the application.
        val confirmed=SessionPolicy.authorizedContext(target,foreground,ownEditor,targetWindow!=null,blocked)
        currentApplicationPackage=if(confirmed) target else if(blocked || foreground==target) null else foreground
        if(blocked && (SessionController.active || SessionController.armed)) SessionController.pause(contextBlockReason)
        SessionController.targetChanged(confirmed)
        if(!confirmed) forceMouseOff(if(blocked) "system_window_cover" else "focus_lost")
        GameCoordinator.contextChanged(confirmed,targetFrame?.let { Rect(it.left,it.top,it.right,it.bottom) })
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            refreshContext()
        }
    }
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val identity = (event.deviceId.toLong() shl 32) or (event.keyCode.toLong() and 0xffffffffL)
        if(identity in nativeKeys) {
            if(event.action==KeyEvent.ACTION_UP) { nativeKeys.remove(identity);scheduleSourceChange() }
            return false // Preserve a DOWN that the game received before a mode switch.
        }
        if(profileSwitches.owns(identity)) {
            if(event.action==KeyEvent.ACTION_UP) { profileSwitches.release(identity);scheduleSourceChange() }
            return true // Never send the trigger's repeat/UP into the new mapping or another app.
        }
        if (identity in consumedKeys) {
            if (event.action == KeyEvent.ACTION_UP) {
                consumedKeys.remove(identity)
                if (SessionController.active && SessionController.targetConfirmed) SessionController.key(event.keyCode, false)
                scheduleSourceChange()
            }
            traceControl(event, handled = true, owned = true)
            return true // Preserve down/up pairing even when the session paused before release.
        }
        val physical=event.device?.let { it.isExternal && !it.isVirtual }==true
        if(physical && event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) {
            if(GameCoordinator.captureBubbleKey(event.keyCode)) {
                profileSwitches.claim(identity);return true
            }
            val bindings=GameCoordinator.profileSwitchBindings()
            val target=profileSwitches.press(identity,event.keyCode,true,bindings.isNotEmpty(),bindings)
            if(target!=null) { GameCoordinator.requestProfileSwitch(target);return true }
        }
        if (!SessionController.active) {
            val device=event.device
            if(device?.isExternal==true && !device.isVirtual && SessionController.cameraShortcutEnabled() && event.keyCode==GameCoordinator.cameraResumeKey()) {
                if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) {
                    consumedKeys.add(identity);GameCoordinator.resumeFromCameraKey();return true
                }
            }
            if(GameCoordinator.sessionOpen && SessionController.targetConfirmed && !GameCoordinator.editorVisible &&
                device?.isExternal==true && !device.isVirtual && event.action==KeyEvent.ACTION_DOWN) nativeKeys.add(identity)
            traceControl(event, handled = false, owned = false);return false
        }
        if (!SessionController.targetConfirmed) { SessionController.pause("Destino no confirmado"); forceMouseOff("focus_lost"); return false }
        val device = event.device ?: return false
        if (!device.isExternal || device.isVirtual) return false
        val bound = SessionController.consumesKey(event.keyCode)
        if (bound && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            consumedKeys.add(identity)
            traceControl(event, handled = true, owned = true)
            SessionController.key(event.keyCode, true)
        }
        if(!bound && SessionController.cursorMode && event.action==KeyEvent.ACTION_DOWN) nativeKeys.add(identity)
        return bound
    }
    override fun onMotionEvent(event: MotionEvent) {
        if (!SessionController.active) return
        if (!SessionController.targetConfirmed) { SessionController.pause("Destino no confirmado"); forceMouseOff("focus_lost"); return }
        val device = event.device ?: return
        if (!device.isExternal || device.isVirtual || event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) return
        val released=if(physicalButtonsKnown) physicalButtons and event.buttonState.inv() else 0
        physicalButtons=event.buttonState;physicalButtonsKnown=true
        // A mode handoff suppresses held buttons for game actions, but their real UP must still
        // release repeats/trigger latches, including while the handoff ACK or cursor owns input.
        var releasedMapped=0
        if(released!=0) for(button in buttonMasks) if(released and button!=0 && SessionController.mappedButtonReleased(button)) {
            releasedMapped=releasedMapped or button
        }
        if(SessionController.pointerTransitionPending) return // Baseline is rebased when the handoff is acknowledged.
        if(SessionController.cursorMode) {
            if(!cursorPresentationActive) updateCursorPresentation()
            if(!cursorPresentationActive) return
            for(sample in 0 until event.historySize) cursorPosition.moveSample(
                event.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_X,sample),
                event.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_Y,sample),event.getHistoricalEventTimeNanos(sample))
            cursorPosition.moveSample(event.getAxisValue(MotionEvent.AXIS_RELATIVE_X),
                event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y),event.eventTimeNanos)
            cursorX=cursorPosition.x;cursorY=cursorPosition.y
            handleCursorMotion(event);return
        }
        // Extract primitives during callback: MotionEvent is owned/recycled by Android.
        for (sample in 0 until event.historySize) {
            SessionController.mouse(event.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_X, sample), event.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_Y, sample),event.getHistoricalEventTimeNanos(sample))
        }
        SessionController.mouse(event.getAxisValue(MotionEvent.AXIS_RELATIVE_X), event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y),event.eventTimeNanos)
        val buttons = event.buttonState
        val changed = mouseButtons.transitions(buttons)
        for(button in buttonMasks) if (changed and button != 0 && releasedMapped and button==0 && SessionController.consumesButton(button)) {
            SessionController.button(button, buttons and button != 0)
        }
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) SessionController.wheel(event.getAxisValue(MotionEvent.AXIS_VSCROLL))
    }
    private fun updateCursorPresentation() {
        if(appliedMouseCapture && SessionController.cursorMode && SessionController.targetConfirmed) {
            if(!cursorPresentationActive) {
                val target=cursorTarget ?: return
                if(!cursorPosition.begin(SessionController.cursorEntryBounds(target),
                        SessionController.cursorDisplayWidth,SessionController.cursorDisplayHeight)) return
                cursorX=cursorPosition.x;cursorY=cursorPosition.y
                cursorPresentationActive=true
            }
            // Position is initialized before addView: neither (0,0) nor a previous session can flash.
            cursorOverlay.moveTo(cursorX,cursorY);cursorOverlay.show()
        } else clearCursorInteraction()
    }
    private fun clearCursorInteraction() {
        if(cursorUiOwned) GameCoordinator.cursorBubbleTouch(MotionEvent.ACTION_CANCEL,cursorX,cursorY)
        cursorUiOwned=false;cursorGameDown=false;cursorBlockedClick=false
        cursorSystemGesture=false;cursorOpenNotifications=false
        cursorPresentationActive=false
        if(cursorDelegate.isInitialized()) cursorOverlay.hide()
    }
    /** Main-thread presentation barrier for a mapped cursor toggle's one-shot touch. */
    fun hideCursorForTransition() { clearCursorInteraction() }
    private fun handleCursorMotion(event:MotionEvent) {
        cursorSamples++
        cursorOverlay.moveTo(cursorX,cursorY)
        if(event.actionMasked==MotionEvent.ACTION_CANCEL) {
            cursorSystemGesture=false;cursorOpenNotifications=false
            SessionController.pause("Gesto del cursor cancelado");return
        }
        val changed=mouseButtons.transitions(event.buttonState)
        val primaryChanged=changed and MotionEvent.BUTTON_PRIMARY != 0
        val down=event.buttonState and MotionEvent.BUTTON_PRIMARY != 0
        for(button in buttonMasks) if(changed and button!=0 && event.buttonState and button!=0 && SessionController.isCursorCommandButton(button)) {
            SessionController.button(button,true)
        }
        // A cursor-mode Map button owns this press; it cannot also become a cursor click/drag.
        if(primaryChanged && SessionController.isCursorCommandButton(MotionEvent.BUTTON_PRIMARY)) return
        if(cursorSystemGesture) {
            if(cursorY-cursorSystemStartY>=32*resources.displayMetrics.density) cursorOpenNotifications=true
            if(primaryChanged && !down) {
                val open=cursorOpenNotifications
                cursorSystemGesture=false;cursorOpenNotifications=false
                SessionController.pauseThen("Cursor devuelto al sistema; reanuda manualmente") { safe ->
                    if(safe && open) performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                }
            }
            return
        }
        if(cursorUiOwned) {
            if(primaryChanged && !down) {
                cursorUiOwned=false
                GameCoordinator.cursorBubbleTouch(MotionEvent.ACTION_UP,cursorX,cursorY)
            } else GameCoordinator.cursorBubbleTouch(MotionEvent.ACTION_MOVE,cursorX,cursorY)
            return
        }
        if(primaryChanged && down) {
            // A deliberate top-edge drag opens Android through accessibility after release,
            // never as a touch targeted at the game's UID.
            if(cursorY<=8*resources.displayMetrics.density) {
                cursorSystemGesture=true;cursorSystemStartY=cursorY;cursorOpenNotifications=false
                return
            }
            if(GameCoordinator.cursorHitsBubble(cursorX,cursorY)) {
                cursorUiOwned=true;GameCoordinator.cursorBubbleTouch(MotionEvent.ACTION_DOWN,cursorX,cursorY)
                return
            }
            if(!CursorPointPolicy.allows(cursorX,cursorY,cursorTarget,cursorBlocked)) {
                cursorBlockedClick=true;return
            }
            cursorGameDown=true
            SessionController.cursorButton(true,cursorX,cursorY)
        } else if(cursorGameDown) {
            SessionController.cursorMove(cursorX,cursorY)
            if(primaryChanged && !down) {
                cursorGameDown=false;SessionController.cursorButton(false,cursorX,cursorY)
            }
        } else if(primaryChanged && !down && cursorBlockedClick) {
            cursorBlockedClick=false
            SessionController.pause("Cursor fuera del juego; entrada devuelta a Android")
        }
        if(event.actionMasked==MotionEvent.ACTION_CANCEL) SessionController.pause("Gesto del cursor cancelado")
    }
    fun allowsInjection(frame:TouchFrame,virtualCameraAdmitted:Boolean=false):Boolean {
        val snapshot=windowContext
        return CursorPointPolicy.allowsFrame(frame.action,frame.xs,frame.ys,snapshot.targetBounds,snapshot.blockedBounds,frame.ids,virtualCameraAdmitted)
    }
    override fun onInputDeviceAdded(deviceId: Int) = Unit
    override fun onInputDeviceChanged(deviceId: Int) {
        if (SessionController.active) SessionController.pause("Cambió un dispositivo; reanuda manualmente")
    }
    override fun onInputDeviceRemoved(deviceId: Int) {
        consumedKeys.removeAll { (it shr 32).toInt() == deviceId }
        nativeKeys.removeAll { (it shr 32).toInt()==deviceId }
        profileSwitches.removeDevice(deviceId)
        if (SessionController.active) SessionController.pause("Periférico desconectado")
        scheduleSourceChange()
    }
    override fun onInterrupt() { SessionController.pause("Accesibilidad interrumpida"); forceMouseOff("service_interrupted") }
    override fun onDestroy() {
        GameCoordinator.detach(this)
        SessionController.pause("Servicio de accesibilidad desconectado")
        forceMouseOff("service_destroyed")
        instance = null
        consumedKeys.clear()
        nativeKeys.clear()
        profileSwitches.clear()
        main.removeCallbacks(applySources); main.removeCallbacks(sourceTailTimeout)
        getSystemService(InputManager::class.java).unregisterInputDeviceListener(this)
        super.onDestroy()
    }
    companion object {
        @Volatile var instance: CaptureService? = null
    }
}
