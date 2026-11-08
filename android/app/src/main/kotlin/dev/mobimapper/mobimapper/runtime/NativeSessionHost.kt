package dev.mobimapper.mobimapper.runtime

import android.content.ContextWrapper
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
import android.view.Choreographer
import android.graphics.Rect
import android.os.Bundle
import android.os.Process
import java.util.concurrent.atomic.AtomicInteger
import dev.mobimapper.mobimapper.session.*
import dev.mobimapper.mobimapper.backend.BackendRegistry
import dev.mobimapper.mobimapper.backend.IPhysicalInputCallback
import dev.mobimapper.mobimapper.editor.EditorPresentationHost
import dev.mobimapper.mobimapper.editor.EditorWindowBounds
import dev.mobimapper.mobimapper.game.GameCoordinator
import dev.mobimapper.mobimapper.editor.GameCursorOverlay
import dev.mobimapper.mobimapper.editor.BubbleSettings
import dev.mobimapper.mobimapper.editor.OverlayOpacityBudget
import dev.mobimapper.mobimapper.editor.OverlayWindows
import dev.mobimapper.mobimapper.engine.TouchFrame

/** Context uses only window package/type/display/layer/bounds, without view text traversal. */
class NativeSessionHost private constructor(context: Context) : ContextWrapper(context.applicationContext),
    InputManager.InputDeviceListener, EditorPresentationHost {
    private object WindowType { const val TYPE_APPLICATION=1; const val TYPE_SYSTEM=3; const val TYPE_ACCESSIBILITY_OVERLAY=4 }
    @Volatile private var nativeCaptureId=0L
    @Volatile private var startingNativeCapture=false
    private val startupCaptureLosses=java.util.concurrent.ConcurrentHashMap<Long,String>()
    @Volatile private var captureAttemptEpoch=0L
    @Volatile private var captureFailure=""
    val nativeCaptureError:String get()=CaptureFailureMessages.describe(captureFailure)
    private val pendingNativeFrames=AtomicInteger()
    private val pendingNativeSamples=AtomicInteger()
    private var rateCapabilityBinder:android.os.IBinder?=null
    private var nativeRateSupported=false
    private var appliedMouseRateHz=0
    @Volatile private var receivedMotionBatches=0L
    @Volatile private var receivedBatchedSamples=0L
    @Volatile private var ignoredUnknownKeys=0L
    @Volatile private var lastUnknownScanCode=-1
    private val keyMap=LinuxKeyMap.load()
    private val nativeKeyDownTimes=HashMap<Long,Long>()
    private val nativePressedCodes=HashMap<Long,Int>()
    private var nativeMetaState=0
    private var explicitContextUntil=0L
    fun observeContextFor(durationMs:Long=5000) {
        check(Looper.myLooper()==Looper.getMainLooper())
        explicitContextUntil=SystemClock.uptimeMillis()+durationMs.coerceIn(1,30_000)
        refreshContext()
    }
    private val nativeCallback=object:IPhysicalInputCallback.Stub() {
        override fun onMotionBatch(captureId:Long,eventTimesNanos:LongArray,deviceIds:IntArray,
            devicePaths:Array<String>,relXs:FloatArray,relYs:FloatArray,wheel:Float,horizontalWheel:Float,buttonState:Int) {
            if(captureId!=nativeCaptureId && !startingNativeCapture) return
            val size=eventTimesNanos.size
            val batch=try {
                require(deviceIds.size==size && devicePaths.size==size && wheel.isFinite() && horizontalWheel.isFinite())
                require(buttonState and 31.inv()==0)
                RawMouseMotionBatch(eventTimesNanos,relXs,relYs)
            } catch(_:IllegalArgumentException) { onCaptureLost(captureId,"invalid_native_motion_batch");return }
            if(pendingNativeSamples.addAndGet(size)>8192) {
                pendingNativeSamples.addAndGet(-size);onCaptureLost(captureId,"native_sample_queue_overflow");return
            }
            if(pendingNativeFrames.incrementAndGet()>512) {
                pendingNativeFrames.decrementAndGet();pendingNativeSamples.addAndGet(-size)
                onCaptureLost(captureId,"native_callback_queue_overflow");return
            }
            if(InputLatencyProbe.enabled) InputLatencyProbe.recordStage("native_capture_binder_receipt",eventTimesNanos.last())
            if(!main.post {
                try {
                    if(captureId==nativeCaptureId) {
                        receivedMotionBatches++;receivedBatchedSamples+=size
                        receiveMotionBatch(batch,deviceIds,devicePaths,wheel,buttonState)
                    }
                } finally { pendingNativeFrames.decrementAndGet();pendingNativeSamples.addAndGet(-size) }
            }) {
                pendingNativeFrames.decrementAndGet();pendingNativeSamples.addAndGet(-size)
                onCaptureLost(captureId,"main_thread_unavailable")
            }
        }
        override fun onFrame(captureId:Long,kind:Int,eventTimeNanos:Long,deviceId:Int,devicePath:String,
            relX:Float,relY:Float,wheel:Float,horizontalWheel:Float,buttonState:Int,keyScanCode:Int,
            keyDown:Boolean,keyRepeat:Boolean) {
            if(captureId!=nativeCaptureId && !startingNativeCapture) return
            if(InputLatencyProbe.enabled) InputLatencyProbe.recordStage("native_capture_binder_receipt",eventTimeNanos)
            if(pendingNativeFrames.incrementAndGet()>512) {
                pendingNativeFrames.decrementAndGet()
                onCaptureLost(captureId,"native_callback_queue_overflow");return
            }
            if(!main.post {
                try {
                    if(captureId==nativeCaptureId) receivePhysical(kind,eventTimeNanos,deviceId,devicePath,
                        relX,relY,wheel,buttonState,keyScanCode,keyDown,keyRepeat)
                } finally { pendingNativeFrames.decrementAndGet() }
            }) { pendingNativeFrames.decrementAndGet();onCaptureLost(captureId,"main_thread_unavailable") }
        }
        override fun onCaptureLost(captureId:Long,reason:String) {
            if(startingNativeCapture) {
                if(startupCaptureLosses.size<16) startupCaptureLosses[captureId]=reason
                return
            }
            if(captureId!=nativeCaptureId || captureId==0L) return
            val attempt=captureAttemptEpoch
            // Invalidate queued events before posting UI cleanup. The privileged lease has
            // already returned the devices even when the application main thread is stalled.
            nativeCaptureId=0L
            captureFailure=reason
            main.post {
                if(attempt!=captureAttemptEpoch || nativeCaptureId!=0L) return@post
                SessionController.pause("Captura nativa detenida: ${CaptureFailureMessages.describe(reason)}");forceMouseOff("native_capture_lost")
            }
        }
    }
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
            setCursorOpacityActive(cursorInstanceDisplay,false)
            cursorInstance=GameCursorOverlay(this,overlayContextFor(displayId)).apply { setSizeDp(cursorSizeDp) }
            cursorInstanceDisplay=displayId
        }
        return requireNotNull(cursorInstance)
    }
    val cursorVisible:Boolean get()=cursorInstance?.visible==true
    fun overlayContextFor(displayId:Int):Context {
        check(Looper.myLooper()==Looper.getMainLooper())
        val display=getSystemService(DisplayManager::class.java).getDisplay(displayId)
            ?: throw WindowManager.InvalidDisplayException("Pantalla desconectada")
        if(!display.isValid) throw WindowManager.InvalidDisplayException("Pantalla desconectada")
        // Recheck the grant even when a previously admitted context is cached.
        OverlayWindows.requirePermission(this,displayId)
        return overlayContexts.getOrPut(displayId) {
            // Every screen now uses an explicit application overlay window context.
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
    private var cursorSensitivity=1f
    private var cursorSizeDp=BubbleSettings.DEFAULT_CURSOR_SIZE_DP
    /** Cold UI callback only: no preferences, profile geometry or IO in physical cursor input. */
    fun applyCursorSettings(settings:BubbleSettings) {
        check(Looper.myLooper()==Looper.getMainLooper())
        cursorSensitivity=settings.cursorSensitivity
        if(cursorSizeDp!=settings.cursorSizeDp) {
            cursorSizeDp=settings.cursorSizeDp
            cursorInstance?.setSizeDp(cursorSizeDp)
        }
    }
    private fun setCursorOpacityActive(displayId:Int,active:Boolean):Boolean {
        val context=overlayContexts[displayId] ?: return !active
        if(!OverlayWindows.setCursorActive(context,active)) return true
        return GameCoordinator.refreshPassiveWindowOpacity(displayId)
    }
    private var cursorPresentationActive=false
    private var cursorPresentationForMenu=false
    private var cursorPositionKnown=false
    private val menuGesture=LocalPointerGestureGate()
    private var menuAwaitingRelease=false
    private var menuMovePending=false
    private var menuMoveX=0f
    private var menuMoveY=0f
    private var menuHoverPending=false
    private val menuMoveCallback=Choreographer.FrameCallback {
        if(menuMovePending && wantsMenuPointer() && appliedMouseCapture) flushMenuPointerMove()
        else menuMovePending=false
        if(menuHoverPending) {
            menuHoverPending=false
            if(wantsMenuPointer() && appliedMouseCapture && physicalButtons==0)
                GameCoordinator.menuPointerHover(cursorX,cursorY)
        }
    }
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
        val admittedUptime:Long,
    )
    private var nextEditorPresentationToken=0L
    private var editorPresentationToken=0L
    @Volatile private var editingPresentation:EditingPresentation?=null
    @Volatile private var editorAnchorResetReason="not_started"
    @Volatile private var windowContext=CachedWindowContext()
    @Volatile private var windowMetadataError="not_ready"
    private var windowOwners:Map<Int,String> = emptyMap()
    private val cursorTarget:PointerBounds? get()=windowContext.targetBounds
    private val cursorBlocked:List<PointerBounds> get()=windowContext.blockedBounds
    private var cursorSystemGesture=false
    private var cursorSystemStartY=0f
    private var cursorOpenNotifications=false
    private var captureEpoch=-1L
    @Volatile private var physicalButtons=0
    val physicalMouseButtons:Int get()=physicalButtons
    @Volatile private var physicalButtonsKnown=false
    private fun keysReleased()=consumedKeys.isEmpty() && nativeKeys.isEmpty() && profileSwitches.isEmpty
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var appliedMouseCapture = false
    val mouseCaptured:Boolean get()=appliedMouseCapture
    /** Worker-side quiescence check; no device, window or Binder query on this path. */
    fun inputDispatchIdle():Boolean = nativeCaptureId!=0L && !startingNativeCapture && appliedMouseCapture &&
        physicalButtonsKnown && physicalButtons==0 && pendingNativeFrames.get()==0 && pendingNativeSamples.get()==0
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
        if (instance === this && (physicalSourcesMatch() || keysReleased())) applyRequestedSources()
    }
    private val sourceTailTimeout = Runnable {
        sourceTailPending = false
        if (!physicalSourcesMatch() && (!keysReleased() || (physicalButtonsKnown && physicalButtons!=0))) {
            tailTimeouts++
            // Never reset the keyboard stream while leaving the corresponding joystick active.
            SessionController.pause("Cambio de captura sin soltar teclas o botones en 2 segundos; reanuda manualmente")
            forceMouseOff("tail_timeout")
        } else scheduleSourceChange()
    }
    private fun attach() {
        instance = this
        dev.mobimapper.mobimapper.backend.BackendRegistry.requestAttach(this)
        val inputManager=getSystemService(InputManager::class.java)
        inputManager.registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
        inputInventory.clear()
        for(id in inputManager.inputDeviceIds) readInputDevice(id).snapshot?.let { inputInventory[id]=it }
        inputInventoryCount=inputInventory.size
        GameCoordinator.attach(this)
        refreshContext()
        main.post(heartbeat)
    }
    private val heartbeat=object:Runnable {
        override fun run() {
            if(instance!==this@NativeSessionHost) return
            val needsContext=GameCoordinator.sessionOpen || SessionController.active || GameCoordinator.editorVisible ||
                SessionController.isTransitioning() || SessionController.needsRecoveryContext || SystemClock.uptimeMillis()<explicitContextUntil ||
                dev.mobimapper.mobimapper.MainActivity.latest.get()?.hasWindowFocus()==true
            if(needsContext) refreshContext()
            if(captureFailure.isEmpty() && ((nativeCaptureId!=0L)!=wantsKeyboardCapture() || effectiveMouseCapture()!=appliedMouseCapture || desiredMouseRateHz()!=appliedMouseRateHz)) scheduleSourceChange()
            val current=nativeCaptureId
            if(current!=0L && runCatching { BackendRegistry.injector?.renewCapture(current)==true }.getOrDefault(false).not()) {
                nativeCallback.onCaptureLost(current,"native_lease_not_renewed")
            }
            main.postDelayed(this,if(needsContext)200 else 1000)
        }
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
        if (effectiveMouseCapture() == appliedMouseCapture && (nativeCaptureId!=0L)==wantsKeyboardCapture() && desiredMouseRateHz()==appliedMouseRateHz) {
            main.removeCallbacks(sourceTailTimeout); sourceTailPending = false
            return
        }
        if(physicalSourcesMatch()) {
            // Changing only the transport cadence does not release devices or
            // reset held keys/buttons, so it needs no input-release barrier.
            main.removeCallbacks(sourceTailTimeout);sourceTailPending=false
            main.post(applySources)
            return
        }
        if (!keysReleased() || (effectiveMouseCapture()!=appliedMouseCapture && physicalButtonsKnown && physicalButtons!=0)) {
            if (!sourceTailPending) {
                sourceTailPending = true; deferredSourceChanges++
                main.postDelayed(sourceTailTimeout, 2_000)
            }
            return
        }
        main.removeCallbacks(sourceTailTimeout); sourceTailPending = false
        // Wait until the final owned key UP is processed before changing exclusive devices.
        main.post(applySources)
    }
    private fun applyRequestedSources() {
        val backend=BackendRegistry.injector
        if(backend!=null && backend.asBinder()!==rateCapabilityBinder) {
            nativeRateSupported=runCatching { backend.status().getBoolean("nativeCaptureRateSupported",false) }.getOrDefault(false)
            rateCapabilityBinder=backend.asBinder()
        }
        val mouseRequested=effectiveMouseCapture()
        val rate=desiredMouseRateHz()
        if (mouseRequested == appliedMouseCapture && (nativeCaptureId!=0L)==wantsKeyboardCapture()) {
            if(rate!=appliedMouseRateHz && nativeCaptureId!=0L) {
                if(runCatching { backend?.setCaptureMouseRate(nativeCaptureId,rate)==true }.getOrDefault(false)) appliedMouseRateHz=rate
                else { captureFailure="native_rate_configuration_failed";SessionController.pause(nativeCaptureError);forceMouseOff("native_rate_failed") }
            }
            return
        }
        // A physical DOWN can arrive after the source change was posted. Keep
        // the current reader until its paired UP instead of reacquiring held input.
        if (!keysReleased() || (mouseRequested!=appliedMouseCapture && physicalButtonsKnown && physicalButtons!=0)) {
            scheduleSourceChange()
            return
        }
        captureAttemptEpoch++
        mouseButtons.reset()
        val previous=nativeCaptureId
        nativeCaptureId=0L
        if(previous!=0L) runCatching { BackendRegistry.injector?.stopCapture(previous) }
        if(wantsKeyboardCapture()) {
            try {
                startupCaptureLosses.clear()
                startingNativeCapture=true
                val activeBackend=requireNotNull(backend)
                val result=if(nativeRateSupported) activeBackend.startCaptureWithRate(nativeCallback,
                    SessionController.targetDisplayId,SessionController.targetPackage,mouseRequested,true,rate)
                else activeBackend.startCapture(nativeCallback,
                    SessionController.targetDisplayId,SessionController.targetPackage,mouseRequested,true)
                check(result.getBoolean("running")) { result.getString("reason") ?: "Captura nativa no disponible" }
                keyMap.configureDevices(result.getStringArray("availablePaths") ?: emptyArray(),
                    result.getStringArray("availableNames") ?: emptyArray(),result.getIntArray("availableVendorIds") ?: intArrayOf(),
                    result.getIntArray("availableProductIds") ?: intArrayOf())
                nativeCaptureId=result.getLong("captureId")
                check(nativeCaptureId>0)
                val startupLoss=startupCaptureLosses[nativeCaptureId]
                check(startupLoss==null) { startupLoss ?: "Captura perdida durante el inicio" }
                captureFailure=""
            } catch(failure:Exception) {
                captureFailure=failure.message ?: "No se pudo tomar el dispositivo de entrada"
                requestedMouseCapture=false
                SessionController.pause(nativeCaptureError)
            } finally { startingNativeCapture=false;startupCaptureLosses.clear() }
        }
        appliedMouseCapture = mouseRequested && nativeCaptureId!=0L
        appliedMouseRateHz=if(appliedMouseCapture) rate else 0
        if(!appliedMouseCapture) SessionController.nativeMouseReleased()
        // The reader rejects held inputs before and after taking exclusive ownership.
        physicalButtonsKnown=nativeCaptureId!=0L
        if(physicalButtonsKnown) { physicalButtons=0;mouseButtons.rebase(0);menuAwaitingRelease=false }
        updateCursorPresentation()
        GameCoordinator.refreshControls()
        if(!appliedMouseCapture) main.postDelayed({
            if(instance===this && !appliedMouseCapture) GameCoordinator.refreshControls()
        },32)
        if (SystemClock.uptimeMillis() <= controlTraceUntil) {
            Log.i("MobiMapperKeys", "control_source_update mouse=$appliedMouseCapture pending=${consumedKeys.size} deferred=$deferredSourceChanges timeouts=$tailTimeouts")
        }
    }
    private fun wantsKeyboardCapture() = SessionController.targetConfirmed && !SessionController.needsRecoveryContext && !GameCoordinator.editorVisible &&
        (SessionController.active || GameCoordinator.sessionOpen)
    private fun wantsMenuPointer() = GameCoordinator.menuPointerAvailable && SessionController.targetConfirmed &&
        !SessionController.active && !SessionController.isTransitioning() && !SessionController.needsRecoveryContext &&
        !SessionController.releaseUnverified && SessionController.contacts==0
    private fun effectiveMouseCapture()=requestedMouseCapture || wantsMenuPointer()
    private fun physicalSourcesMatch()=effectiveMouseCapture()==appliedMouseCapture && (nativeCaptureId!=0L)==wantsKeyboardCapture()
    private fun desiredMouseRateHz()=if(nativeRateSupported && effectiveMouseCapture() && SessionController.active && !wantsMenuPointer())
        SessionController.mouseCaptureRateHz else 0
    /** Menu open/close is a control-plane transition, never an input event forwarded to the game. */
    fun refreshMenuPointerSources() {
        updateCursorPresentation()
        if(captureFailure.isEmpty()) scheduleSourceChange()
    }

    private fun receiveMotionBatch(batch:RawMouseMotionBatch,deviceIds:IntArray,devicePaths:Array<String>,wheel:Float,buttons:Int) {
        if(!SessionController.targetConfirmed) { forceMouseOff("native_context_lost");return }
        if(!appliedMouseCapture) return
        // Motion-only batches cannot own a button edge. Keep transitions on the
        // ordinary frame path, in the same Binder/queue order as this batch.
        if(physicalButtonsKnown && buttons!=physicalButtons) {
            captureFailure="native_batch_button_mismatch";SessionController.pause(nativeCaptureError);forceMouseOff("batch_buttons");return
        }
        if(SessionController.active && !SessionController.cursorMode && !wantsMenuPointer() && !menuAwaitingRelease) {
            rawMotionCallbacks+=batch.size
            lastRawMotionSource=InputDevice.SOURCE_MOUSE;lastRawMotionAction=MotionEvent.ACTION_HOVER_MOVE
            lastRawMotionDeviceId=deviceIds.last();lastRawMotionDeviceSources=InputDevice.SOURCE_MOUSE
            lastRawMotionDeviceKnown=true;lastRawMotionExternal=true;lastRawMotionVirtual=false;lastRawMotionFilter="native_exclusive_batch"
            lastInputDisplayId=SessionController.targetDisplayId
            physicalButtons=buttons;physicalButtonsKnown=true
            SessionController.mouseBatch(batch)
            if(wheel!=0f) SessionController.wheel(wheel)
        } else if(batch.size>1 && buttons==0 && !menuAwaitingRelease && !cursorGameDown && !cursorUiOwned &&
            menuGesture.owner==LocalPointerTarget.NONE && (wantsMenuPointer() || SessionController.cursorMode)) {
            if(!cursorPresentationActive) updateCursorPresentation()
            if(!cursorPresentationActive) return
            // Preserve per-sample clipping, but publish one idle-hover event. No
            // widget or game owns a press on this path; drags keep every sample.
            batch.moveCursor(cursorPosition,batch.size-1,cursorSensitivity)
            cursorX=cursorPosition.x;cursorY=cursorPosition.y
            cursorSamples+=batch.size-1;rawMotionCallbacks+=batch.size-1
            val last=batch.size-1
            receivePhysical(0,batch.timesNanos[last],deviceIds[last],devicePaths[last],batch.xs[last],batch.ys[last],
                wheel,buttons,-1,false,false)
        } else {
            for(index in batch.timesNanos.indices) receivePhysical(0,batch.timesNanos[index],deviceIds[index],devicePaths[index],
                batch.xs[index],batch.ys[index],if(index==batch.size-1) wheel else 0f,buttons,-1,false,false)
        }
    }
    private fun receivePhysical(kind:Int,timeNanos:Long,deviceId:Int,devicePath:String,dx:Float,dy:Float,
                                wheel:Float,buttons:Int,scanCode:Int,down:Boolean,repeat:Boolean) {
        if(!SessionController.targetConfirmed) { forceMouseOff("native_context_lost");return }
        if(kind==1) {
            val code=keyMap.keyCode(scanCode,deviceId)
            if(code==KeyEvent.KEYCODE_UNKNOWN) {
                // Optional vendor/media keys must not stop mapped movement. Do not
                // manufacture an Android key code for an unsupported scan code.
                if(down && !repeat) { ignoredUnknownKeys++;lastUnknownScanCode=scanCode }
                return
            }
            val identity=(deviceId.toLong() shl 32) or (scanCode.toLong() and 0xffffffffL)
            val millis=timeNanos/1_000_000L
            val downTime=if(down) nativeKeyDownTimes.getOrPut(identity) { millis } else nativeKeyDownTimes.remove(identity) ?: millis
            if(down) nativePressedCodes[identity]=code else nativePressedCodes.remove(identity)
            nativeMetaState=nativePressedCodes.values.fold(0) { state,pressed -> state or when(pressed) {
                KeyEvent.KEYCODE_SHIFT_LEFT -> KeyEvent.META_SHIFT_LEFT_ON
                KeyEvent.KEYCODE_SHIFT_RIGHT -> KeyEvent.META_SHIFT_RIGHT_ON
                KeyEvent.KEYCODE_CTRL_LEFT -> KeyEvent.META_CTRL_LEFT_ON
                KeyEvent.KEYCODE_CTRL_RIGHT -> KeyEvent.META_CTRL_RIGHT_ON
                KeyEvent.KEYCODE_ALT_LEFT -> KeyEvent.META_ALT_LEFT_ON
                KeyEvent.KEYCODE_ALT_RIGHT -> KeyEvent.META_ALT_RIGHT_ON
                KeyEvent.KEYCODE_META_LEFT -> KeyEvent.META_META_LEFT_ON
                KeyEvent.KEYCODE_META_RIGHT -> KeyEvent.META_META_RIGHT_ON
                else -> 0
            } }
            val event=KeyEvent(downTime,millis,if(down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,code,
                if(repeat) 1 else 0,KeyEvent.normalizeMetaState(nativeMetaState),deviceId,scanCode,0,InputDevice.SOURCE_KEYBOARD)
            if(!onKeyEvent(event,fromNative=true)) {
                val current=nativeCaptureId
                if(current!=0L && runCatching { BackendRegistry.injector?.forwardKey(current,event.action,code,scanCode,
                    millis,event.metaState)==true }.getOrDefault(false).not()) {
                    captureFailure="No se pudo entregar la tecla sin asignar"
                    SessionController.pause(captureFailure);forceMouseOff("key_passthrough_rejected")
                }
            }
            return
        }
        if(!appliedMouseCapture) return
        rawMotionCallbacks++;lastRawMotionSource=InputDevice.SOURCE_MOUSE;lastRawMotionAction=MotionEvent.ACTION_HOVER_MOVE
        lastRawMotionDeviceId=deviceId;lastRawMotionDeviceSources=InputDevice.SOURCE_MOUSE
        lastRawMotionDeviceKnown=true;lastRawMotionExternal=true;lastRawMotionVirtual=false;lastRawMotionFilter="native_exclusive"
        lastInputDisplayId=SessionController.targetDisplayId
        val menuPointer=wantsMenuPointer()
        val token=if(InputLatencyProbe.enabled) InputLatencyProbe.recordReceipt(if(menuPointer) "native_menu_motion" else "native_motion",timeNanos,System.nanoTime(),
            InputDevice.SOURCE_MOUSE,MotionEvent.ACTION_HOVER_MOVE,deviceId,code=buttons,active=SessionController.active,targetConfirmed=true,
            cursorMode=SessionController.cursorMode || menuPointer) else 0L
        val released=if(physicalButtonsKnown) physicalButtons and buttons.inv() else 0
        val physicalChanged=if(physicalButtonsKnown) physicalButtons xor buttons else 0
        physicalButtons=buttons;physicalButtonsKnown=true
        if(sourceTailPending && buttons==0 && keysReleased()) scheduleSourceChange()
        if(menuPointer) {
            handleMenuPointer(timeNanos,dx,dy,wheel,buttons)
            return
        }
        if(menuAwaitingRelease) {
            mouseButtons.rebase(buttons)
            if(buttons==0) menuAwaitingRelease=false
            return
        }
        if(!SessionController.active) return
        var suspendHandled=0
        for(button in buttonMasks) if(physicalChanged and button!=0 &&
            SessionController.cameraSuspendButton(button,buttons and button!=0)) suspendHandled=suspendHandled or button
        var releasedMapped=0
        for(button in buttonMasks) if(released and button!=0 && SessionController.mappedButtonReleased(button)) releasedMapped=releasedMapped or button
        if(SessionController.pointerTransitionPending) return
        if(SessionController.cursorMode) {
            if(!cursorPresentationActive) updateCursorPresentation()
            if(!cursorPresentationActive) return
            cursorPosition.moveInput(dx,dy,cursorSensitivity);cursorX=cursorPosition.x;cursorY=cursorPosition.y
            val properties=MotionEvent.PointerProperties().apply { id=0;toolType=MotionEvent.TOOL_TYPE_MOUSE }
            val coordinates=MotionEvent.PointerCoords().apply { x=cursorX;y=cursorY;pressure=if(buttons!=0)1f else 0f }
            val event=MotionEvent.obtain(timeNanos/1_000_000L,timeNanos/1_000_000L,MotionEvent.ACTION_HOVER_MOVE,
                1,arrayOf(properties),arrayOf(coordinates),nativeMetaState,buttons,1f,1f,-1,0,InputDevice.SOURCE_MOUSE,0)
            try { handleCursorMotion(event,token) } finally { event.recycle() }
            return
        }
        SessionController.mouse(dx,dy,timeNanos)
        val changed=mouseButtons.transitions(buttons)
        for(button in buttonMasks) if(changed and button!=0 && releasedMapped and button==0 &&
            suspendHandled and button==0 && SessionController.consumesButton(button))
            SessionController.button(button,buttons and button!=0)
        if(wheel!=0f) SessionController.wheel(wheel)
    }

    override fun begin(packageName:String,displayId:Int)=beginEditorPresentation(packageName,displayId)
    override fun end(reason:String)=endEditorPresentation(reason)
    override fun windows(displayId:Int):List<EditorWindowBounds> = windowContext.windows.filter {
        it.type==WindowType.TYPE_APPLICATION && it.frame.displayId==displayId && it.packageName!=null
    }.map {
        val frame=it.frame
        val bounds=Rect(frame.left,frame.top,frame.right,frame.bottom)
        EditorWindowBounds(requireNotNull(it.packageName),displayId,bounds,Rect(bounds),it.focused,frame.layer)
    }
    private fun forceMouseOff(reason: String) {
        captureAttemptEpoch++
        requestedMouseCapture = false
        main.removeCallbacks(applySources); main.removeCallbacks(sourceTailTimeout); sourceTailPending = false
        nativeKeys.clear()
        consumedKeys.clear();profileSwitches.clear();nativeKeyDownTimes.clear();nativePressedCodes.clear();nativeMetaState=0
        val previous=nativeCaptureId
        nativeCaptureId=0L
        appliedMouseRateHz=0
        if(previous!=0L) runCatching { BackendRegistry.injector?.stopCapture(previous) }
        clearCursorInteraction()
        if (appliedMouseCapture) {
            val pending = consumedKeys.size
            // A forced filter reset can bypass UP callbacks. Do not retain stale owners afterwards.
            consumedKeys.clear()
            profileSwitches.clear()
            appliedMouseCapture=false
            physicalButtonsKnown=false
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
            it.id==selected.id && it.type==WindowType.TYPE_APPLICATION &&
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
            actualWindow.frame),actualWindow.id,SessionController.activeProfileId,snapshot.capturedUptime,SystemClock.uptimeMillis())
        val admittedToken=editorPresentationToken
        main.postDelayed({
            if(instance===this && editingPresentation?.anchor?.token==admittedToken && GameCoordinator.editorVisible)
                refreshContext()
        },SessionPolicy.EDITOR_FOCUS_HANDOFF_MS+1L)
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
            "metadataError" to windowMetadataError,
            "editorPresentationAllowed" to snapshot.editorPresentationAllowed,
            "editorAnchorResetReason" to editorAnchorResetReason,
            "editorPresentationAnchor" to snapshot.editorAnchor?.let {
                mapOf("token" to it.anchor.token,"packageName" to it.anchor.packageName,
                    "displayId" to it.anchor.frame.displayId,"windowId" to it.windowId,"profileId" to it.profileId,
                    "bounds" to bounds(it.anchor.frame),"capturedUptime" to it.capturedUptime,"admittedUptime" to it.admittedUptime)
            },
            "serviceMotionSources" to if(appliedMouseCapture) InputDevice.SOURCE_MOUSE or InputDevice.SOURCE_MOUSE_RELATIVE else 0,
            "captureProvider" to "native-evdev", "captureId" to nativeCaptureId,"captureError" to nativeCaptureError,
            "captureErrorCode" to captureFailure,
            "motionTransport" to mapOf("nativeRateSupported" to nativeRateSupported,"appliedMouseRateHz" to appliedMouseRateHz,
                "batches" to receivedMotionBatches,"samples" to receivedBatchedSamples,"pendingCallbacks" to pendingNativeFrames.get(),
                "pendingSamples" to pendingNativeSamples.get()),
            "unknownKeys" to mapOf("ignored" to ignoredUnknownKeys,"lastScanCode" to lastUnknownScanCode),
            "keyLayouts" to keyMap.diagnostics(),
            "displayRouting" to mapOf("count" to displayRoutingCount,"lastInputDisplayId" to lastInputDisplayId,
                "meanMs" to if(displayRoutingCount==0L) 0.0 else displayRoutingTotalNanos/1e6/displayRoutingCount,
                "maxMs" to displayRoutingMaxNanos/1e6),
            "rawMotion" to mapOf("callbacks" to rawMotionCallbacks,"lastSource" to lastRawMotionSource,
                "lastAction" to lastRawMotionAction,"lastDeviceId" to lastRawMotionDeviceId,
                "deviceSources" to lastRawMotionDeviceSources,
                "deviceKnown" to lastRawMotionDeviceKnown,"external" to lastRawMotionExternal,
                "virtual" to lastRawMotionVirtual,"filterReason" to lastRawMotionFilter),
            "menuPointer" to mapOf("requested" to wantsMenuPointer(),"active" to (cursorPresentationActive && cursorPresentationForMenu),
                "owner" to menuGesture.owner.name,"awaitingRelease" to menuAwaitingRelease),
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
    /** Window metadata is refreshed on window events, never by each mouse sample. */
    fun refreshContext() {
        val target=SessionController.targetPackage
        val displayId=SessionController.targetDisplayId
        multipleDisplays=getSystemService(DisplayManager::class.java).displays.count { it.isValid }>1
        val nativeContext=runCatching { BackendRegistry.injector?.windowContext() }.getOrNull()
        val contextValid=nativeContext?.getBoolean("valid")==true
        windowMetadataError=nativeContext?.getString("error") ?: "backend_unavailable"
        val currentRoot=if(contextValid) nativeContext?.getString("focusedPackage") else null
        val focusQueryStarted=SystemClock.uptimeMillis()
        val focused=runCatching { dev.mobimapper.mobimapper.backend.BackendRegistry.injector?.focusedApplication() }.getOrNull()
        val trustedFocus=contextValid && focused?.getBoolean("valid")==true
        val focusedDisplay=if(trustedFocus) focused?.getInt("displayId",-1) ?: -1 else if(!multipleDisplays && displayId==0) 0 else -1
        // Resolve each root once. Retain known ownership for a continuously visible window ID,
        // but never retain bounds/a window after it disappears from this snapshot.
        @Suppress("DEPRECATION")
        val rawWindows=if(!contextValid) emptyList() else nativeContext?.getParcelableArrayList<Bundle>("windows").orEmpty()
            .filter { it.getInt("displayId",-1)==displayId && it.getBoolean("visible") }.map { window ->
                val owner=window.getString("packageName")
                WindowMetadata(window.getInt("id"),window.getInt("type"),owner,
                    ContextWindowFrame(displayId,window.getInt("layer"),window.getInt("left"),window.getInt("top"),
                        window.getInt("right"),window.getInt("bottom")),window.getBoolean("focused"),
                    window.getBoolean("focused"),owner!=null)
            }
        windowOwners=WindowSelectionPolicy.resolveOwners(rawWindows.map { WindowOwner(it.id,it.packageName) },windowOwners)
        val contextWindows=rawWindows.map { it.copy(packageName=windowOwners[it.id]) }
        val appWindows=contextWindows.filter { it.type==WindowType.TYPE_APPLICATION }
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
        val systemWindows=contextWindows.filter { it.type==WindowType.TYPE_SYSTEM }
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
        val brokerFocusAgeMs=maxOf(SystemClock.uptimeMillis()-focusQueryStarted,
            SystemClock.uptimeMillis()-(nativeContext?.getLong("capturedUptimeMs",0L) ?: 0L))
        val presentation=editingPresentation
        val focusedOwnWindow=contextWindows.firstOrNull {
            it.focused && it.rootResolved && it.packageName==packageName &&
                (it.type==WindowType.TYPE_SYSTEM || it.type==WindowType.TYPE_ACCESSIBILITY_OVERLAY)
        }?.let { ContextWindowCandidate(it.type,it.packageName,it.frame,it.focused,it.rootResolved) }
        val presentationIdentity=GameCoordinator.editorVisible && presentation!=null &&
            presentation.profileId==SessionController.activeProfileId && SessionController.mode==SessionMode.EDITING &&
            SessionPolicy.mayEdit(SessionController.contacts,SessionController.releaseUnverified,SessionController.active) && !SessionController.armed &&
            presentation.anchor.packageName==target && presentation.anchor.frame.displayId==displayId &&
            presentation.anchor.token==editorPresentationToken && editorPresentationToken>0L
        val brokerMatches=trustedFocus && foreground==target && focusedDisplay==displayId && brokerFocusAgeMs in 0L..500L
        val foreignRoot=currentRoot!=null && currentRoot!=target && currentRoot!=packageName
        // An anchor can guard presentation only. It is never substituted for selected,
        // targetBounds, targetFrame, targetConfirmed or any engine/injection metadata.
        val presentationCovered=presentation?.let {
            SessionPolicy.coveringWindow(it.anchor.frame,candidates,target,packageName)!=null
        } ?: false
        val normalEditorPresentationAllowed=displayReady && !locked && !foreignRoot && focusedForeign==null &&
            !presentationCovered && presentationIdentity && SessionPolicy.mayRetainEditorPresentation(
                SessionController.mode,SessionController.active,SessionController.contacts,SessionController.releaseUnverified,
                target,displayId,packageName,editorPresentationToken,presentation?.anchor,
                focused?.getString("packageName"),focusedDisplay,trustedFocus,brokerFocusAgeMs,focusedOwnWindow)
        // Only presentation gets a brief handoff when WMS publishes focus before visibility.
        // The independent task query stays separate from contextValid/trustedFocus here.
        val handoffEditorPresentationAllowed=presentationIdentity && SessionPolicy.mayRetainEditorFocusHandoff(
            mode=SessionController.mode,active=SessionController.active,armed=SessionController.armed,
            contacts=SessionController.contacts,releaseUnverified=SessionController.releaseUnverified,
            captureStopped=nativeCaptureId==0L && !startingNativeCapture && !appliedMouseCapture,
            targetPackage=target,targetDisplayId=displayId,editorToken=editorPresentationToken,
            anchor=presentation?.anchor,admittedUptimeMs=presentation?.admittedUptime ?: -1L,nowUptimeMs=capturedUptime,
            metadataValid=contextValid,metadataError=windowMetadataError,
            metadataAgeMs=capturedUptime-(nativeContext?.getLong("capturedUptimeMs",0L) ?: 0L),
            brokerPackage=focused?.getString("packageName"),brokerDisplayId=focused?.getInt("displayId",-1) ?: -1,
            brokerValid=focused?.getBoolean("valid")==true,brokerQueryAgeMs=capturedUptime-focusQueryStarted,
            displayReady=displayReady,locked=locked,
            foreignContext=foreignRoot || focusedForeign!=null || presentationCovered || blocked)
        val editorPresentationAllowed=normalEditorPresentationAllowed || handoffEditorPresentationAllowed
        // Normal retention requires the real APP or resolved own editor window. The bounded
        // admission handoff above is the only exception for temporarily missing visibility.
        if(presentation!=null && !handoffEditorPresentationAllowed && !(presentationIdentity && displayReady && !locked && !foreignRoot &&
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
    fun onKeyEvent(event: KeyEvent, fromNative:Boolean=false): Boolean {
        val latencyReceivedNanos = if (InputLatencyProbe.enabled) System.nanoTime() else 0L
        if (latencyReceivedNanos != 0L) InputLatencyProbe.recordReceipt(
            if(fromNative) "native_key" else "key", event.eventTime * 1_000_000L, latencyReceivedNanos,
            event.source, event.action, event.deviceId, code = event.keyCode,
            active = SessionController.active, targetConfirmed = SessionController.targetConfirmed,
            cursorMode = SessionController.cursorMode)
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
        val physical=fromNative || event.device?.let { it.isExternal && !it.isVirtual }==true
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
            if(physical && SessionController.cameraShortcutEnabled() && event.keyCode==GameCoordinator.cameraResumeKey()) {
                if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) {
                    consumedKeys.add(identity);GameCoordinator.resumeFromCameraKey();return true
                }
            }
            if(GameCoordinator.sessionOpen && SessionController.targetConfirmed && !GameCoordinator.editorVisible &&
                physical && event.action==KeyEvent.ACTION_DOWN) nativeKeys.add(identity)
            traceControl(event, handled = false, owned = false);return false
        }
        if (!SessionController.targetConfirmed) { SessionController.pause("Destino no confirmado"); forceMouseOff("focus_lost"); return false }
        if(!physical) return false
        val bound = SessionController.consumesKey(event.keyCode)
        if (bound && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            consumedKeys.add(identity)
            traceControl(event, handled = true, owned = true)
            SessionController.key(event.keyCode, true)
        }
        if(!bound && SessionController.cursorMode && event.action==KeyEvent.ACTION_DOWN) nativeKeys.add(identity)
        return bound
    }
    private fun updateCursorPresentation() {
        val menuPointer=wantsMenuPointer()
        if(appliedMouseCapture && (menuPointer || SessionController.cursorMode) && SessionController.targetConfirmed) {
            if(cursorPresentationActive && cursorPresentationForMenu!=menuPointer) clearCursorInteraction()
            if(!cursorPresentationActive) {
                val target=cursorTarget ?: return
                val oldX=cursorX;val oldY=cursorY
                val area=if(menuPointer) PointerBounds(0f,0f,SessionController.cursorDisplayWidth.toFloat(),SessionController.cursorDisplayHeight.toFloat())
                    else SessionController.cursorEntryBounds(target)
                if(!cursorPosition.begin(area,
                        SessionController.cursorDisplayWidth,SessionController.cursorDisplayHeight)) return
                if(menuPointer && cursorPositionKnown) cursorPosition.move(oldX-cursorPosition.x,oldY-cursorPosition.y)
                cursorX=cursorPosition.x;cursorY=cursorPosition.y
                cursorPresentationActive=true;cursorPresentationForMenu=menuPointer;cursorPositionKnown=true
            }
            // Position is initialized before addView: neither (0,0) nor a previous session can flash.
            try {
                val overlay=cursorOverlay
                // Lower the other passive windows before showing the higher-alpha cursor.
                if(!setCursorOpacityActive(SessionController.targetDisplayId,true)) {
                    SessionController.pause("No se pudo preparar la presentación del cursor")
                    forceMouseOff("cursor_opacity_budget");return
                }
                overlay.refreshWindowOpacity()
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
        menuMovePending=false
        menuHoverPending=false
        GameCoordinator.clearMenuPointerHover()
        Choreographer.getInstance().removeFrameCallback(menuMoveCallback)
        val menuOwner=menuGesture.cancel()
        if(menuOwner!=LocalPointerTarget.NONE) GameCoordinator.menuPointerTouch(menuOwner,MotionEvent.ACTION_CANCEL,cursorX,cursorY)
        if(cursorPresentationForMenu && physicalButtonsKnown && physicalButtons!=0) menuAwaitingRelease=true
        if(cursorUiOwned) GameCoordinator.cursorBubbleTouch(MotionEvent.ACTION_CANCEL,cursorX,cursorY)
        cursorUiOwned=false;cursorGameDown=false;cursorBlockedClick=false
        cursorSystemGesture=false;cursorOpenNotifications=false
        cursorPresentationActive=false
        cursorPresentationForMenu=false
        cursorInstance?.hide()
        // The higher-alpha cursor is gone before restoring the other windows' normal opacity.
        setCursorOpacityActive(cursorInstanceDisplay,false)
    }
    private fun flushMenuPointerMove() {
        if(!menuMovePending) return
        menuMovePending=false
        Choreographer.getInstance().removeFrameCallback(menuMoveCallback)
        val owner=menuGesture.owner
        if(!wantsMenuPointer() || cursorBlocked.any { it.contains(menuMoveX,menuMoveY) }) {
            menuGesture.cancel()
            if(owner!=LocalPointerTarget.NONE) GameCoordinator.menuPointerTouch(owner,MotionEvent.ACTION_CANCEL,menuMoveX,menuMoveY)
            return
        }
        if(owner!=LocalPointerTarget.NONE) GameCoordinator.menuPointerTouch(owner,MotionEvent.ACTION_MOVE,menuMoveX,menuMoveY)
    }
    private fun handleMenuPointer(timeNanos:Long,dx:Float,dy:Float,wheel:Float,buttons:Int) {
        if(!cursorPresentationActive || !cursorPresentationForMenu) updateCursorPresentation()
        if(!cursorPresentationActive || !cursorPresentationForMenu) return
        cursorPosition.moveInput(dx,dy,cursorSensitivity);cursorX=cursorPosition.x;cursorY=cursorPosition.y;cursorSamples++
        cursorOverlay.moveTo(cursorX,cursorY)
        val changed=mouseButtons.transitions(buttons)
        if(buttons!=0) { menuHoverPending=false;GameCoordinator.clearMenuPointerHover() }
        if(cursorBlocked.any { it.contains(cursorX,cursorY) }) {
            menuHoverPending=false;GameCoordinator.clearMenuPointerHover()
            menuMovePending=false
            Choreographer.getInstance().removeFrameCallback(menuMoveCallback)
            val owner=menuGesture.cancel()
            if(owner!=LocalPointerTarget.NONE) GameCoordinator.menuPointerTouch(owner,MotionEvent.ACTION_CANCEL,cursorX,cursorY)
            return
        }
        if(changed and MotionEvent.BUTTON_PRIMARY!=0) {
            menuHoverPending=false;GameCoordinator.clearMenuPointerHover()
            flushMenuPointerMove()
            if(buttons and MotionEvent.BUTTON_PRIMARY!=0) {
                val owner=menuGesture.press(GameCoordinator.menuPointerTarget(cursorX,cursorY))
                if(owner!=LocalPointerTarget.NONE) GameCoordinator.menuPointerTouch(owner,MotionEvent.ACTION_DOWN,cursorX,cursorY)
            } else {
                // The UP may close this menu and synchronously start another mode.
                val owner=menuGesture.release()
                if(owner!=LocalPointerTarget.NONE) GameCoordinator.menuPointerTouch(owner,MotionEvent.ACTION_UP,cursorX,cursorY)
            }
        } else if(menuGesture.owner!=LocalPointerTarget.NONE && (dx!=0f || dy!=0f)) {
            menuMoveX=cursorX;menuMoveY=cursorY
            if(!menuMovePending) {
                menuMovePending=true
                Choreographer.getInstance().postFrameCallback(menuMoveCallback)
            }
        }
        if(wantsMenuPointer() && buttons==0) {
            if(!menuHoverPending && !menuMovePending) Choreographer.getInstance().postFrameCallback(menuMoveCallback)
            menuHoverPending=true
        }
        if(wantsMenuPointer() && wheel!=0f) GameCoordinator.menuPointerScroll(cursorX,cursorY,wheel)
    }
    /** Main-thread presentation barrier for a mapped cursor toggle's one-shot touch. */
    fun hideCursorForTransition() { clearCursorInteraction() }
    private fun handleCursorMotion(event:MotionEvent, latencyReceiptToken:Long) {
        cursorSamples++
        cursorOverlay.moveTo(cursorX,cursorY,latencyReceiptToken)
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
        if(primaryChanged && SessionController.isCameraSuspendButton(MotionEvent.BUTTON_PRIMARY)) return
        if(cursorSystemGesture) {
            if(cursorY-cursorSystemStartY>=32*resources.displayMetrics.density) cursorOpenNotifications=true
            if(primaryChanged && !down) {
                val open=cursorOpenNotifications
                cursorSystemGesture=false;cursorOpenNotifications=false
                SessionController.pauseThen("Cursor devuelto al sistema; reanuda manualmente") { safe ->
                if(safe && open) runCatching { BackendRegistry.injector?.showNotifications() }
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
    fun close() {
        endEditorPresentation("service_destroyed")
        GameCoordinator.detach(this)
        SessionController.pause("Captura nativa desconectada")
        forceMouseOff("service_destroyed")
        instance = null
        consumedKeys.clear()
        nativeKeys.clear()
        profileSwitches.clear()
        inputInventory.clear();inputInventoryCount=0
        cursorInstance=null;overlayContexts.clear()
        main.removeCallbacks(applySources); main.removeCallbacks(sourceTailTimeout)
        main.removeCallbacks(heartbeat)
        getSystemService(InputManager::class.java).unregisterInputDeviceListener(this)
    }
    companion object {
        @Volatile var instance: NativeSessionHost? = null
        fun initialize(context:Context) {
            if(Looper.myLooper()!=Looper.getMainLooper()) {
                Handler(Looper.getMainLooper()).post { initialize(context.applicationContext) };return
            }
            if(instance==null) NativeSessionHost(context).attach()
        }
    }
}
