package dev.mobimapper.mobimapper.session

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.graphics.Rect
import dev.mobimapper.mobimapper.game.GameCoordinator
import dev.mobimapper.mobimapper.editor.GameCursorOverlay
import dev.mobimapper.mobimapper.editor.OverlayOpacityBudget
import dev.mobimapper.mobimapper.editor.OverlayWindows
import dev.mobimapper.mobimapper.engine.TouchFrame

/** Context uses only window package/type/display/layer/bounds, without view text traversal. */
class CaptureService : AccessibilityService(), InputManager.InputDeviceListener {
    private val mouseButtons = MouseButtonGate()
    private val consumedKeys = HashSet<Long>()
    private val nativeKeys=HashSet<Long>()
    private val profileSwitches=ProfileSwitchGate()
    private val inputInventory=HashMap<Int,InputDeviceSnapshot>()
    private val inventoryKeyCodes=IntArray(KeyEvent.getMaxKeyCode()+1) { it }
    @Volatile private var inputInventoryCount=0
    @Volatile private var deviceChangeCount=0L
    @Volatile private var ignoredDeviceChanges=0L
    @Volatile private var materialDeviceChanges=0L
    @Volatile private var addedDeviceCount=0L
    @Volatile private var removedDeviceCount=0L
    @Volatile private var lastDeviceChange:Map<String,Any?>?=null
    @Volatile private var lastDeviceRemoval:Map<String,Any?>?=null
    private val overlayContexts=HashMap<Int,Context>()
    @Volatile private var lastOverlayContext:Map<String,Any?>?=null
    private var cursorInstance:GameCursorOverlay?=null
    private var cursorInstanceDisplay=-1
    private val cursorOverlay:GameCursorOverlay get() {
        val displayId=SessionController.targetDisplayId
        if(cursorInstance==null || cursorInstanceDisplay!=displayId) {
            cursorInstance?.hide()
            cursorInstance=GameCursorOverlay(this,overlayContextFor(displayId));cursorInstanceDisplay=displayId
        }
        return requireNotNull(cursorInstance)
    }
    val cursorVisible:Boolean get()=cursorInstance?.visible==true
    fun overlayContextFor(displayId:Int):Context {
        check(Looper.myLooper()==Looper.getMainLooper())
        // Preserve the original phone service context. A Service is not necessarily visual,
        // so createWindowContext(type, options) must never be called on this instance.
        if(displayId==0) return this
        val display=getSystemService(DisplayManager::class.java).getDisplay(displayId)
            ?: throw WindowManager.InvalidDisplayException("Pantalla desconectada")
        if(!display.isValid) throw WindowManager.InvalidDisplayException("Pantalla desconectada")
        // Recheck the grant even when a previously admitted context is cached.
        OverlayWindows.requirePermission(this,displayId)
        return overlayContexts.getOrPut(displayId) {
            // Standard external windows use the application Context, not AccessibilityContext's
            // default accessibility token. Display 0 retains its original service WM above.
            val windowContext=applicationContext.createWindowContext(display,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null)
            val maximumOpacity=try { getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch }
                catch(_:RuntimeException) { Float.NaN }
            val alpha=OverlayOpacityBudget.layerAlpha(maximumOpacity)
            lastOverlayContext=mapOf("displayId" to displayId,"route" to "application_overlay_window_context",
                "resourcesContextClass" to windowContext.javaClass.name,
                "maximumObscuringOpacity" to maximumOpacity.takeIf { it.isFinite() },"nonTouchAlpha" to alpha)
            OverlayWindows.externalContext(windowContext,alpha)
        }
    }
    fun discardDisplayContext(displayId:Int) {
        if(editingPresentation?.anchor?.frame?.displayId==displayId) endEditorPresentation("display_unavailable")
        if(cursorInstanceDisplay==displayId) {
            clearCursorInteraction();cursorInstance=null;cursorInstanceDisplay=-1
        }
        overlayContexts.remove(displayId)
    }
    @Volatile private var multipleDisplays=false
    @Volatile private var displayRoutingCount=0L
    @Volatile private var displayRoutingTotalNanos=0L
    @Volatile private var displayRoutingMaxNanos=0L
    @Volatile private var lastInputDisplayId=-1
    @Volatile private var rawMotionCallbacks=0L
    @Volatile private var lastRawMotionSource=0
    @Volatile private var lastRawMotionAction=-1
    @Volatile private var lastRawMotionDeviceId=-1
    @Volatile private var lastRawMotionDeviceSources=0
    @Volatile private var lastRawMotionDeviceKnown=false
    @Volatile private var lastRawMotionExternal=false
    @Volatile private var lastRawMotionVirtual=false
    @Volatile private var lastRawMotionFilter="none"
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
        val displayId:Int=0,val focusedDisplayId:Int=-1,val trustedFocus:Boolean=false,
        val targetPackage:String="",val selected:ApplicationWindowCandidate?=null,
        val rootPackage:String?=null,val foregroundPackage:String?=null,
        val targetBounds:PointerBounds?=null,val blockedBounds:List<PointerBounds> = emptyList(),
        val windows:List<WindowMetadata> = emptyList(),val blockedWindows:List<WindowMetadata> = emptyList(),
        val capturedUptime:Long=0L,
        val brokerFocusAgeMs:Long=Long.MAX_VALUE,
        val editorPresentationAllowed:Boolean=false,
        val editorAnchor:EditingPresentation?=null,
    )
    private data class EditingPresentation(
        val anchor:EditorPresentationAnchor,val windowId:Int,val profileId:String?,val capturedUptime:Long,
    )
    private var nextEditorPresentationToken=0L
    private var editorPresentationToken=0L
    @Volatile private var editingPresentation:EditingPresentation?=null
    @Volatile private var editorAnchorResetReason="not_started"
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
        val inputManager=getSystemService(InputManager::class.java)
        inputManager.registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
        inputInventory.clear()
        for(id in inputManager.inputDeviceIds) readInputDevice(id).snapshot?.let { inputInventory[id]=it }
        inputInventoryCount=inputInventory.size
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
        if (!enabled) {
            val targetId=SessionController.targetDisplayId
            val targetInteractive=if(targetId==0) getSystemService(PowerManager::class.java).isInteractive
                else getSystemService(DisplayManager::class.java).getDisplay(targetId)?.state==android.view.Display.STATE_ON
            if(!SessionController.targetConfirmed || !targetInteractive ||
                getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                forceMouseOff("context_lost")
                return
            }
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
    /** Anchor only this editor admission to a real, currently authorized APP window. */
    fun beginEditorPresentation(packageName:String,displayId:Int):Boolean {
        check(Looper.myLooper()==Looper.getMainLooper())
        endEditorPresentation("replaced")
        refreshContext()
        val snapshot=windowContext
        val selected=snapshot.selected
        val now=SystemClock.uptimeMillis()
        val realApplication=selected!=null && snapshot.windows.any {
            it.id==selected.id && it.type==AccessibilityWindowInfo.TYPE_APPLICATION &&
                it.rootResolved && it.packageName==packageName
        }
        if(SessionController.mode!=SessionMode.EDITING || !SessionPolicy.mayEdit(SessionController.contacts,
                SessionController.releaseUnverified,SessionController.active) || SessionController.armed ||
            SessionController.targetPackage!=packageName || SessionController.targetDisplayId!=displayId ||
            !SessionController.targetConfirmed || snapshot.targetPackage!=packageName || snapshot.displayId!=displayId ||
            !snapshot.trustedFocus || snapshot.focusedDisplayId!=displayId || snapshot.foregroundPackage!=packageName ||
            snapshot.brokerFocusAgeMs !in 0L..500L || now-snapshot.capturedUptime !in 0L..500L || !realApplication) {
            editorAnchorResetReason="admission_context_unconfirmed"
            return false
        }
        // Editor mouse/keyboard operations must never overlap source capture, even while a
        // previous key's deferred source-change callback is still pending.
        forceMouseOff("editor_presentation")
        editorPresentationToken=++nextEditorPresentationToken
        val actualWindow=requireNotNull(selected)
        editingPresentation=EditingPresentation(EditorPresentationAnchor(editorPresentationToken,packageName,
            actualWindow.frame),actualWindow.id,SessionController.activeProfileId,snapshot.capturedUptime)
        windowContext=snapshot.copy(editorAnchor=editingPresentation,editorPresentationAllowed=false)
        editorAnchorResetReason=""
        return true
    }
    fun endEditorPresentation(reason:String="editor_closed") {
        check(Looper.myLooper()==Looper.getMainLooper())
        editingPresentation=null;editorPresentationToken=0L;editorAnchorResetReason=reason
        val snapshot=windowContext
        if(snapshot.editorAnchor!=null || snapshot.editorPresentationAllowed)
            windowContext=snapshot.copy(editorAnchor=null,editorPresentationAllowed=false)
    }
    fun boundsForPackage(packageName:String):Rect? {
        val snapshot=windowContext
        if(snapshot.targetPackage!=packageName || snapshot.displayId!=SessionController.targetDisplayId) return null
        val bounds=snapshot.selected?.frame ?: return null
        return Rect(bounds.left,bounds.top,bounds.right,bounds.bottom)
    }
    /** Launch deduplication only; an occluding window can still forbid mapping in this context. */
    fun isPackageForegroundForLaunch(packageName:String):Boolean {
        val snapshot=windowContext
        return snapshot.displayId==SessionController.targetDisplayId && snapshot.targetPackage==packageName && snapshot.selected?.packageName==packageName &&
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
            "displayId" to snapshot.displayId,"focusedDisplayId" to snapshot.focusedDisplayId,"trustedFocus" to snapshot.trustedFocus,
            "brokerFocusAgeMs" to snapshot.brokerFocusAgeMs,
            "editorPresentationAllowed" to snapshot.editorPresentationAllowed,
            "editorAnchorResetReason" to editorAnchorResetReason,
            "editorPresentationAnchor" to snapshot.editorAnchor?.let {
                mapOf("token" to it.anchor.token,"packageName" to it.anchor.packageName,
                    "displayId" to it.anchor.frame.displayId,"windowId" to it.windowId,"profileId" to it.profileId,
                    "bounds" to bounds(it.anchor.frame),"capturedUptime" to it.capturedUptime)
            },
            "serviceMotionSources" to runCatching { serviceInfo.motionEventSources }.getOrNull(),
            "displayRouting" to mapOf("count" to displayRoutingCount,"lastInputDisplayId" to lastInputDisplayId,
                "meanMs" to if(displayRoutingCount==0L) 0.0 else displayRoutingTotalNanos/1e6/displayRoutingCount,
                "maxMs" to displayRoutingMaxNanos/1e6),
            "rawMotion" to mapOf("callbacks" to rawMotionCallbacks,"lastSource" to lastRawMotionSource,
                "lastAction" to lastRawMotionAction,"lastDeviceId" to lastRawMotionDeviceId,
                "deviceSources" to lastRawMotionDeviceSources,
                "deviceKnown" to lastRawMotionDeviceKnown,"external" to lastRawMotionExternal,
                "virtual" to lastRawMotionVirtual,"filterReason" to lastRawMotionFilter),
            "overlayContext" to lastOverlayContext,
            "deviceInventory" to mapOf("knownCount" to inputInventoryCount,"changedCount" to deviceChangeCount,
                "ignoredCount" to ignoredDeviceChanges,"materialCount" to materialDeviceChanges,
                "addedCount" to addedDeviceCount,"removedCount" to removedDeviceCount,
                "lastChange" to lastDeviceChange,"lastRemoval" to lastDeviceRemoval),
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
        val displayId=SessionController.targetDisplayId
        multipleDisplays=getSystemService(DisplayManager::class.java).displays.count { it.isValid }>1
        val currentRoot=runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
        val focusQueryStarted=SystemClock.uptimeMillis()
        val focused=runCatching { dev.mobimapper.mobimapper.backend.BackendRegistry.injector?.focusedApplication() }.getOrNull()
        val trustedFocus=focused?.getBoolean("valid")==true
        val focusedDisplay=if(trustedFocus) focused?.getInt("displayId",-1) ?: -1 else if(!multipleDisplays && displayId==0) 0 else -1
        // Resolve each root once. Retain known ownership for a continuously visible window ID,
        // but never retain bounds/a window after it disappears from this snapshot.
        val rawWindows=(windowsOnAllDisplays[displayId] ?: emptyList()).filter { it.displayId==displayId }.mapNotNull { window -> runCatching {
            val owner=runCatching { window.root?.packageName?.toString() }.getOrNull()
            WindowMetadata(window.id,window.type,owner,frame(window),window.isFocused,window.isActive,owner!=null)
        }.getOrNull() }
        windowOwners=WindowSelectionPolicy.resolveOwners(rawWindows.map { WindowOwner(it.id,it.packageName) },windowOwners)
        val contextWindows=rawWindows.map { it.copy(packageName=windowOwners[it.id]) }
        val appWindows=contextWindows.filter { it.type==AccessibilityWindowInfo.TYPE_APPLICATION }
        val focusedApp=appWindows.firstOrNull { it.focused }?.packageName ?: appWindows.firstOrNull { it.active }?.packageName
        val ownEditor=focusedDisplay==displayId && ((GameCoordinator.ownOverlayVisible && currentRoot==packageName) ||
            (currentRoot==null && contextWindows.any {
                WindowSelectionPolicy.ownsFocusedEditorOverlay(GameCoordinator.editorVisible,it.type,
                    it.packageName,packageName,it.frame,it.focused,displayId)
            }))
        val foreground=if(trustedFocus) {
            if(focusedDisplay==displayId) focused?.getString("packageName") else null
        } else if(focusedDisplay!=displayId) null
            else if(ownEditor) focusedApp else if(currentRoot==target && focusedApp!=null && focusedApp!=target)
                null else currentRoot ?: focusedApp
        val previous=windowContext.takeIf { it.targetPackage==target && it.displayId==displayId }?.selected?.id
        val targetWindow=WindowSelectionPolicy.selectTarget(appWindows.map {
            ApplicationWindowCandidate(it.id,it.packageName,it.frame,it.focused,it.active)
        },target,previous,displayId)
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
        val focusedForeign=contextWindows.firstOrNull { it.focused && it.packageName!=target && it.packageName!=packageName }
        val coveringWindow=focusedForeign?.let { ContextWindowCandidate(it.type,it.packageName,it.frame) }
            ?: targetFrame?.let { SessionPolicy.coveringWindow(it,candidates,target,packageName) }
        val blocked=coveringWindow!=null
        val coveringPackage=coveringWindow?.packageName
        val capturedUptime=SystemClock.uptimeMillis()
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
        val displayReady=getSystemService(DisplayManager::class.java).getDisplay(displayId)?.state==android.view.Display.STATE_ON
        val locked=getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val confirmed=displayReady && !locked && SessionPolicy.authorizedContext(target,foreground,ownEditor,targetWindow!=null,blocked)
        val brokerFocusAgeMs=SystemClock.uptimeMillis()-focusQueryStarted
        val presentation=editingPresentation
        val focusedOwnWindow=contextWindows.firstOrNull {
            it.focused && it.rootResolved && it.packageName==packageName &&
                (it.type==AccessibilityWindowInfo.TYPE_SYSTEM || it.type==AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY)
        }?.let { ContextWindowCandidate(it.type,it.packageName,it.frame,it.focused,it.rootResolved) }
        val presentationIdentity=GameCoordinator.editorVisible && presentation!=null &&
            presentation.profileId==SessionController.activeProfileId && SessionController.mode==SessionMode.EDITING &&
            SessionPolicy.mayEdit(SessionController.contacts,SessionController.releaseUnverified,SessionController.active) && !SessionController.armed &&
            presentation.anchor.packageName==target && presentation.anchor.frame.displayId==displayId
        val brokerMatches=trustedFocus && foreground==target && focusedDisplay==displayId && brokerFocusAgeMs in 0L..500L
        val foreignRoot=currentRoot!=null && currentRoot!=target && currentRoot!=packageName
        // An anchor can guard presentation only. It is never substituted for selected,
        // targetBounds, targetFrame, targetConfirmed or any engine/injection metadata.
        val presentationCovered=presentation?.let {
            SessionPolicy.coveringWindow(it.anchor.frame,candidates,target,packageName)!=null
        } ?: false
        val editorPresentationAllowed=displayReady && !locked && !foreignRoot && focusedForeign==null &&
            !presentationCovered && presentationIdentity && SessionPolicy.mayRetainEditorPresentation(
                SessionController.mode,SessionController.active,SessionController.contacts,SessionController.releaseUnverified,
                target,displayId,packageName,editorPresentationToken,presentation?.anchor,
                focused?.getString("packageName"),focusedDisplay,trustedFocus,brokerFocusAgeMs,focusedOwnWindow)
        // The APP may still be visible during the initial editor focus handoff. Preserve the
        // anchor only while that actual APP remains authorized; a foreign/unknown focus ends it.
        if(presentation!=null && !(presentationIdentity && displayReady && !locked && !foreignRoot &&
                focusedForeign==null && !presentationCovered && brokerMatches &&
                (editorPresentationAllowed || (confirmed && targetWindow!=null))))
            endEditorPresentation("context_or_identity_lost")
        windowContext=CachedWindowContext(displayId,focusedDisplay,trustedFocus,target,targetWindow,currentRoot,foreground,targetBounds,blockedBounds,
            contextWindows,blockedWindows,capturedUptime,brokerFocusAgeMs,editorPresentationAllowed,editingPresentation)
        currentApplicationPackage=if(confirmed) target else if(blocked || foreground==target) null else foreground
        if(blocked && (SessionController.active || SessionController.armed)) SessionController.pause(contextBlockReason)
        SessionController.targetChanged(confirmed)
        if(!confirmed) forceMouseOff(if(blocked) "system_window_cover" else "focus_lost")
        GameCoordinator.contextChanged(confirmed,targetFrame?.let { Rect(it.left,it.top,it.right,it.bottom) },editorPresentationAllowed)
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
        rawMotionCallbacks++;lastRawMotionSource=event.source;lastRawMotionAction=event.actionMasked
        lastRawMotionDeviceId=event.deviceId;lastRawMotionDeviceSources=0;lastRawMotionDeviceKnown=false
        lastRawMotionExternal=false;lastRawMotionVirtual=false
        if (!SessionController.active) { lastRawMotionFilter="inactive";return }
        if (!SessionController.targetConfirmed) {
            lastRawMotionFilter="context"
            SessionController.pause("Destino no confirmado"); forceMouseOff("focus_lost"); return
        }
        val device = event.device
        if(device==null) { lastRawMotionFilter="null_device";return }
        lastRawMotionDeviceKnown=true;lastRawMotionExternal=device.isExternal;lastRawMotionVirtual=device.isVirtual
        lastRawMotionDeviceSources=device.sources
        val rejected=PhysicalMouseInputPolicy.rejectionReason(event.source,lastRawMotionDeviceSources,
            lastRawMotionExternal,lastRawMotionVirtual)
        if(rejected!=null) { lastRawMotionFilter=rejected;return }
        lastRawMotionFilter="accepted"
        // Accessibility captures mouse sources globally. Resolve the event's actual display in
        // the already authorized shell process; never infer it from device, coordinates or focus.
        if(multipleDisplays || SessionController.targetDisplayId!=0) {
            val started=SystemClock.elapsedRealtimeNanos()
            val eventDisplay=runCatching {
                dev.mobimapper.mobimapper.backend.BackendRegistry.injector?.motionDisplayId(event)
            }.getOrNull()
            val elapsed=SystemClock.elapsedRealtimeNanos()-started
            displayRoutingCount++;displayRoutingTotalNanos+=elapsed
            displayRoutingMaxNanos=maxOf(displayRoutingMaxNanos,elapsed);lastInputDisplayId=eventDisplay ?: -1
            if(eventDisplay!=SessionController.targetDisplayId) {
                SessionController.pause(if(eventDisplay==null) "No se pudo confirmar la pantalla del ratón" else "Ratón en otra pantalla; reanuda manualmente")
                forceMouseOff("input_display_changed");return
            }
        }
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
            try {
                val overlay=cursorOverlay
                overlay.moveTo(cursorX,cursorY);overlay.show()
            } catch (failure:WindowManager.BadTokenException) {
                GameCoordinator.presentationUnavailable(failure,"cursor")
                forceMouseOff("cursor_bad_token")
            } catch (failure:WindowManager.InvalidDisplayException) {
                // The display can disappear in WindowManager before its listener reaches us.
                GameCoordinator.presentationUnavailable(failure,"cursor")
                forceMouseOff("cursor_display_removed")
            } catch (_:IllegalArgumentException) {
                // A context can reject a display removed during cursor publication.
                GameCoordinator.displayUnavailable(SessionController.targetDisplayId)
            }
        } else clearCursorInteraction()
    }
    private fun clearCursorInteraction() {
        if(cursorUiOwned) GameCoordinator.cursorBubbleTouch(MotionEvent.ACTION_CANCEL,cursorX,cursorY)
        cursorUiOwned=false;cursorGameDown=false;cursorBlockedClick=false
        cursorSystemGesture=false;cursorOpenNotifications=false
        cursorPresentationActive=false
        cursorInstance?.hide()
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
            if(SessionController.targetDisplayId==0 && cursorY<=8*resources.displayMetrics.density) {
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
        if(snapshot.displayId!=SessionController.targetDisplayId) return false
        return CursorPointPolicy.allowsFrame(frame.action,frame.xs,frame.ys,snapshot.targetBounds,snapshot.blockedBounds,frame.ids,virtualCameraAdmitted)
    }
    private data class InputDeviceRead(val snapshot:InputDeviceSnapshot?,val error:String?=null)
    /** Cold callback work only. Absolute X/Y ranges may follow DeX viewports; mapping uses relative axes. */
    private fun readInputDevice(deviceId:Int):InputDeviceRead = try {
        val device=getSystemService(InputManager::class.java).getInputDevice(deviceId)
        if(device==null) InputDeviceRead(null,"not_found") else {
            val supported=if(device.isExternal && !device.isVirtual && device.supportsSource(InputDevice.SOURCE_KEYBOARD)) {
                val present=device.hasKeys(*inventoryKeyCodes)
                check(present.size==inventoryKeyCodes.size)
                inventoryKeyCodes.filterIndexed { index,_ -> present[index] }.toSet()
            } else emptySet()
            val ranges=device.motionRanges.filter {
                it.axis==MotionEvent.AXIS_RELATIVE_X || it.axis==MotionEvent.AXIS_RELATIVE_Y || it.axis==MotionEvent.AXIS_VSCROLL
            }.map { InputDeviceMotionRange(it.axis,it.source,it.min,it.max,it.flat,it.fuzz,it.resolution) }.toSet()
            InputDeviceRead(InputDeviceSnapshot(device.descriptor,device.vendorId,device.productId,device.sources,
                device.keyboardType,device.isExternal,device.isVirtual,device.isEnabled,ranges,supported,device.name))
        }
    } catch(failure:Exception) { InputDeviceRead(null,failure.javaClass.simpleName) }
    private fun inputDeviceDiagnostic(value:InputDeviceSnapshot?):Map<String,Any?>?=value?.let {
        mapOf("name" to it.name,"descriptor" to it.descriptor,"vendorId" to it.vendorId,"productId" to it.productId,
            "sources" to it.sources,"keyboardType" to it.keyboardType,"external" to it.external,
            "virtual" to it.virtual,"enabled" to it.enabled,"supportedKeys" to it.supportedKeys.sorted(),
            "motionRanges" to it.motionRanges.sortedWith(compareBy<InputDeviceMotionRange> { range -> range.axis }.thenBy { range -> range.source }).map { range ->
                mapOf("axis" to range.axis,"source" to range.source,"min" to range.min,"max" to range.max,
                    "flat" to range.flat,"fuzz" to range.fuzz,"resolution" to range.resolution)
            })
    }
    private fun forgetDevicePresses(deviceId:Int) {
        consumedKeys.removeAll { (it shr 32).toInt()==deviceId }
        nativeKeys.removeAll { (it shr 32).toInt()==deviceId }
        profileSwitches.removeDevice(deviceId)
    }
    override fun onInputDeviceAdded(deviceId: Int) {
        val current=readInputDevice(deviceId).snapshot
        if(current!=null) inputInventory[deviceId]=current else inputInventory.remove(deviceId)
        inputInventoryCount=inputInventory.size;addedDeviceCount++
        // Reconnecting a peripheral never arms a session or recreates held actions.
    }
    override fun onInputDeviceChanged(deviceId: Int) {
        val before=inputInventory[deviceId]
        val read=readInputDevice(deviceId);val after=read.snapshot
        if(after!=null) inputInventory[deviceId]=after else inputInventory.remove(deviceId)
        inputInventoryCount=inputInventory.size;deviceChangeCount++
        val decision=InputDeviceChangePolicy.evaluate(before,after)
        if(decision.pause) materialDeviceChanges++ else ignoredDeviceChanges++
        val pausing=decision.pause && (SessionController.active || SessionController.armed)
        lastDeviceChange=mapOf("uptimeMs" to SystemClock.uptimeMillis(),"deviceId" to deviceId,
            "reason" to decision.reason,"changedFields" to decision.changedFields,"pauseRequired" to decision.pause,
            "pausedActiveSession" to pausing,"snapshotError" to read.error,
            "before" to inputDeviceDiagnostic(before),"after" to inputDeviceDiagnostic(after))
        Log.i("MobiMapperDevice","changed id=$deviceId reason=${decision.reason} fields=${decision.changedFields.joinToString()} pause=$pausing")
        if(decision.pause) {
            forgetDevicePresses(deviceId)
            if(pausing) SessionController.pause("Cambió un periférico; reanuda manualmente")
            forceMouseOff("device_changed")
        }
    }
    override fun onInputDeviceRemoved(deviceId: Int) {
        val previous=inputInventory.remove(deviceId)
        inputInventoryCount=inputInventory.size;removedDeviceCount++
        lastDeviceRemoval=mapOf("uptimeMs" to SystemClock.uptimeMillis(),"deviceId" to deviceId,
            "reason" to "device_removed","previous" to inputDeviceDiagnostic(previous))
        forgetDevicePresses(deviceId)
        if(SessionController.active || SessionController.armed) SessionController.pause("Periférico desconectado")
        forceMouseOff("device_removed")
    }
    override fun onInterrupt() { endEditorPresentation("service_interrupted");SessionController.pause("Accesibilidad interrumpida"); forceMouseOff("service_interrupted") }
    override fun onDestroy() {
        endEditorPresentation("service_destroyed")
        GameCoordinator.detach(this)
        SessionController.pause("Servicio de accesibilidad desconectado")
        forceMouseOff("service_destroyed")
        instance = null
        consumedKeys.clear()
        nativeKeys.clear()
        profileSwitches.clear()
        inputInventory.clear();inputInventoryCount=0
        cursorInstance=null;overlayContexts.clear()
        main.removeCallbacks(applySources); main.removeCallbacks(sourceTailTimeout)
        getSystemService(InputManager::class.java).unregisterInputDeviceListener(this)
        super.onDestroy()
    }
    companion object {
        @Volatile var instance: CaptureService? = null
    }
}
