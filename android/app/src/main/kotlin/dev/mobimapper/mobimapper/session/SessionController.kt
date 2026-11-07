package dev.mobimapper.mobimapper.session

import android.content.Context
import android.os.*
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import dev.mobimapper.mobimapper.backend.BackendRegistry
import dev.mobimapper.mobimapper.backend.IBackendEvents
import dev.mobimapper.mobimapper.backend.IInjector
import dev.mobimapper.mobimapper.engine.*
import dev.mobimapper.mobimapper.backend.recovery.RecoveryNoticeGate
import dev.mobimapper.mobimapper.profile.*
import dev.mobimapper.mobimapper.game.GameCoordinator

object SessionController {
    const val TARGET = "dev.mobimapper.inputlab"
    @Volatile var targetPackage=TARGET; private set
    @Volatile var targetDisplayId=0; private set
    @Volatile var mode=SessionMode.STOPPED; private set
    @Volatile var activeProfileId:String?=null; private set
    @Volatile var profileName:String?=null; private set
    @Volatile private var runtimeProfile:PreparedProfile?=null
    @Volatile private var explicitlyStopped=false
    private var originalTargetPackage=TARGET
    private var originalTargetDisplayId=0
    @Volatile private var originalContinuousCamera=false
    @Volatile private var cameraContactAdmitted=false
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("MobiMapperEngine").apply { start() }
    private val worker = Handler(thread.looper)
    @Volatile private var backendSessionId=0L
    private var previousBackendSessionId=0L
    private var originalTargetUid=-1
    private val noticeGate=RecoveryNoticeGate()
    private val client=object:IBackendEvents.Stub() {
        override fun recovery(sessionId:Long,workerGeneration:Long,phase:Int,reason:String) {
            worker.post { handleRecovery(sessionId,workerGeneration,phase,reason) }
        }
    }
    private fun handleRecovery(sessionId:Long,workerGeneration:Long,phase:Int,reason:String) {
            if(!noticeGate.accept(sessionId,workerGeneration,phase)) return
            if(phase==1) { pause("Recuperando inyector: $reason"); mode=SessionMode.RECOVERING }
            if(phase==2) worker.post {
                if(sessionId==backendSessionId) {
                    val result=runCatching { (sessionBackend ?: BackendRegistry.injector)?.status() }.getOrNull()
                    if(result!=null && (result.getBoolean("cancelAdmitted") || result.getBoolean("terminalReconciled")) && result.getLong("sessionId")==sessionId) {
                        composer?.acknowledgeBackendReset(); releaseUnverified=false; contacts=0;cameraContactAdmitted=false
                        mode=if(explicitlyStopped) SessionMode.STOPPED else SessionMode.PAUSED
                        state=if(explicitlyStopped) "Detenida · secuencia recuperada" else "Pausada · secuencia recuperada"
                        error=if(explicitlyStopped) "Secuencia anterior cerrada; sesión detenida" else "Inicia una sesión nueva manualmente"
                    }
                }
                main.post { GameCoordinator.refreshControls() }
            }
            if(phase==3) { releaseUnverified=true; state="Bloqueada"; error=reason; mode=SessionMode.BLOCKED }
            main.post { GameCoordinator.refreshControls() }
    }
    private val metricsLock = Any()
    @Volatile var armed = false; private set
    @Volatile var active = false; private set
    @Volatile var targetConfirmed = false; private set
    @Volatile private var starting = false
    @Volatile private var stopping = false
    @Volatile private var probeStarted = false
    @Volatile var state = "Detenida"; private set
    @Volatile var error = ""; private set
    @Volatile var releaseUnverified = false; private set
    @Volatile var inputKeys = 0L; private set
    @Volatile var inputMouse = 0L; private set
    @Volatile var relativeSamples = 0L; private set
    @Volatile var wheelSteps = 0f; private set
    @Volatile var injected = 0L; private set
    @Volatile var contacts = 0; private set
    @Volatile var syntheticProbe = "Sin ejecutar"; private set
    private var width = 0
    private var height = 0
    private var composer: TouchComposer? = null
    private var engine: MappingEngine? = null
    private var macroRunner: MacroRunner? = null // Engine worker only.
    private var repeatRunner:RepeatRunner?=null
    private var cursorActionRunner:CursorActionRunner?=null
    private var cursorCommandTicket:Int?=null
    @Volatile private var cursorCommandPending=false
    @Volatile private var macroState:Map<String,Any?> = emptyMap()
    @Volatile private var actionState:Map<String,Any?> = emptyMap()
    private val precisionAimFilter=PrecisionAimFilter()
    @Volatile private var cameraPacer:CameraMotionPacer?=null
    private var cameraPacerWakeup:Runnable?=null
    // Engine-thread ownership: an ACK from a replacement process cannot release this stream.
    private var sessionBackend: IInjector? = null
    @Volatile private var camera = true
    private val pointerTransition=PointerTransitionGate()
    val pointerTransitionPending:Boolean get()=pointerTransition.pending || cursorCommandPending
    @Volatile private var startInCursor=false
    val cursorMode:Boolean get()=active && runtimeProfile!=null && !camera
    val profileSession:Boolean get()=runtimeProfile!=null
    private val queueWait = LongArray(2048)
    private val ipcTime = LongArray(2048)
    private val cameraDeliveryWait = LongArray(2048)
    private var queueCount = 0
    private var ipcCount = 0
    private var cameraDeliveryCount = 0
    @Volatile private var testRunning = false
    @Volatile private var generation = 0L
    @Volatile private var lastAttemptedFrame:TouchFrame?=null
    @Volatile private var lastMotorError:String?=null
    @Volatile private var firstRejectedInput:Map<String,Any?>?=null
    @Volatile private var metricsSessionId=0L
    @Volatile private var cameraTrial="sequential"
    @Volatile private var activePauseHistory:List<Map<String,Any?>> = emptyList()
    private val queue:InputQueue = InputQueue(worker, { type, code, x, y, enqueued, run ->
        if (active && targetConfirmed && run == generation) {
            synchronized(metricsLock) { queueWait[queueCount++ % 2048] = SystemClock.elapsedRealtimeNanos() - enqueued }
            if(type!=3 && cameraPacer?.flush()==false) {
                if(active) pause("No se pudo completar el movimiento pendiente")
                return@InputQueue
            }
            if(!active || run!=generation) return@InputQueue
            when(type) {
                1 -> {
                    val canStart=camera && !pointerTransition.pending && cursorActionRunner?.pending!=true
                    when {
                        cursorActionRunner?.input("key",code,x!=0f)==true -> publishActionState()
                        repeatRunner?.input("key",code,x!=0f,canStart)==true -> publishActionState()
                        macroRunner?.input("key",code,x!=0f,canStart)==true -> publishMacroState()
                        !pointerTransition.pending && cursorActionRunner?.pending!=true && (runtimeProfile==null || camera) -> engine?.key(code,x!=0f)
                    }
                }
                2 -> {
                    val canStart=camera && !pointerTransition.pending && cursorActionRunner?.pending!=true
                    val command=when {
                        cursorActionRunner?.input("mouse",code,x!=0f)==true -> { publishActionState();true }
                        repeatRunner?.input("mouse",code,x!=0f,canStart)==true -> { publishActionState();true }
                        macroRunner?.input("mouse",code,x!=0f,canStart)==true -> { publishMacroState();true }
                        else -> false
                    }
                    val recognized=if(command || !canStart) false else engine?.mouseButton(code,x!=0f)==true
                    if(code==MappingEngine.LEFT_BUTTON) precisionAimFilter.setFiring(x!=0f && recognized)
                }
                3 -> if (camera && !pointerTransition.pending && cursorActionRunner?.pending!=true && cameraShortcutEnabled()) {
                    if(cameraPacer?.offer(precisionMouseX(x),y,enqueued)==false && active) pause("No se pudo enviar el movimiento de cámara")
                }
                4 -> {
                    val requestedCursor=pointerTransition.requestedCursor(code) ?: return@InputQueue
                    precisionAimFilter.reset()
                    macroRunner?.cancel(releaseContact=true);publishMacroState()
                    repeatRunner?.cancel(releaseContact=true);publishActionState()
                    camera = !requestedCursor
                    if(runtimeProfile!=null) {
                        if(camera) engine?.leaveCursorMode() else engine?.enterCursorMode()
                        state=if(camera) "Juego activo" else "Cursor del juego activo"
                    } else {
                        if (!camera) engine?.mouseButton(MappingEngine.LEFT_BUTTON, false)
                        engine?.setCameraEnabled(camera)
                    }
                    if(cursorCommandTicket==code) {
                        publishEngineState()
                        if(active && run==generation) beginCursorCommand(run,code,requestedCursor)
                    } else publishMouse(run, runtimeProfile!=null || camera,code,requestedCursor)
                }
                5 -> if(!camera && runtimeProfile!=null && !pointerTransition.pending && cursorActionRunner?.pending!=true) engine?.cursorButton(code!=0,x,y)
                6 -> if(!camera && runtimeProfile!=null && !pointerTransition.pending && cursorActionRunner?.pending!=true) engine?.cursorMove(x,y)
                7 -> {
                    macroRunner?.input("mouse",code,false,canStart=false);publishMacroState()
                    repeatRunner?.input("mouse",code,false,canStart=false)
                    cursorActionRunner?.input("mouse",code,false);publishActionState()
                }
            }
            publishEngineState()
            // Only key transitions in free-cursor mode can change bubble availability here.
            // Steady mouse movement never schedules UI work or reads UI preferences.
            if((type==1 || type==5) && !camera && runtimeProfile!=null) main.post {
                if(run==generation) GameCoordinator.refreshControls()
            }
        }
    }, { pause("Cola saturada; transiciones detenidas") })

    /** Engine-thread only; timed camera delivery has the same failure/release path as queued edges. */
    private fun publishEngineState() {
        contacts=composer?.activeCount ?: 0
        lastMotorError=composer?.lastError
        if(active && (composer?.paused==true || engine?.lastOperationSucceeded==false)) pause("Fallo del motor o de inyección")
    }
    /** Published at macro boundaries only; never allocates on ordinary relative mouse samples. */
    private fun publishMacroState() {
        macroState=macroRunner?.let { mapOf("activeId" to it.activeId,"stepIndex" to it.stepIndex,
            "pending" to it.pending,"completed" to it.completed,"rejected" to it.rejected,"error" to it.lastError) } ?: emptyMap()
    }
    private fun publishActionState() {
        cursorCommandPending=active && cursorActionRunner?.pending==true
        actionState=mapOf("repeatActive" to (repeatRunner?.activeCount ?: 0),"repeatPulses" to repeatRunner?.pulses,
            "repeatPending" to (repeatRunner?.pending ?: false),
            "repeatRejected" to repeatRunner?.rejected,"repeatError" to repeatRunner?.lastError,
            "cursorOwner" to cursorActionRunner?.currentOwner,"cursorQueued" to cursorActionRunner?.queued,
            "cursorCompleted" to cursorActionRunner?.completed,"cursorRejected" to cursorActionRunner?.rejected,
            "cursorError" to cursorActionRunner?.lastError)
    }
    private fun scheduleAction(run:Long,delay:Long,action:()->Unit):()->Unit {
        val task=Runnable { if(run==generation && active && targetConfirmed) action() }
        if(!worker.postDelayed(task,delay)) pause("El hilo de controles no está disponible")
        return { worker.removeCallbacks(task) }
    }
    /** Presentation is hidden on main before a map command injects its one-shot tap. */
    private fun beginCursorCommand(run:Long,ticket:Int,cursor:Boolean) {
        main.post {
            if(run==generation && active && targetConfirmed && pointerTransition.current(ticket)) {
                CaptureService.instance?.hideCursorForTransition()
                worker.post {
                    if(run==generation && active && targetConfirmed && pointerTransition.current(ticket)) {
                        val started=cursorActionRunner?.tap { success ->
                            if(run==generation && active && targetConfirmed && pointerTransition.current(ticket)) {
                                if(success) {
                                    cursorCommandTicket=null
                                    publishMouse(run,true,ticket,cursor)
                                } else pause("No se pudo completar la acción de cursor")
                            }
                        }==true
                        if(!started && active) pause("Acción de cursor sin transición preparada")
                    }
                }
            }
        }
    }
    private fun precisionMouseX(dx:Float):Float=precisionAimFilter.filterX(dx,queue.consumingSampleTimeNanos)
    private fun discardPacedMotion() {
        precisionAimFilter.reset()
        cameraPacerWakeup?.let { worker.removeCallbacks(it) }
        cameraPacerWakeup=null;cameraPacer?.discard();cameraPacer=null
    }

    private fun publishMouse(run: Long, enabled: Boolean,modeTicket:Int?=null,appliedCursor:Boolean=false) {
        main.post { synchronized(this) {
            if (run == generation) {
                CaptureService.instance?.captureMouse(enabled && active && targetConfirmed && (runtimeProfile!=null || camera),run)
                modeTicket?.let { ticket ->
                    pointerTransition.acknowledge(ticket,appliedCursor)?.let { next ->
                        if(active && targetConfirmed) queue.offer(4,code=next,generation=run)
                    }
                    worker.post {
                        if(run==generation && active && targetConfirmed) {
                            cursorActionRunner?.retry();publishActionState()
                        }
                    }
                }
                GameCoordinator.refreshControls()
            }
        } }
    }
    @Synchronized fun arm(width: Int, height: Int,displayId:Int=0) {
        useLaboratory(displayId)
        armInternal(width,height)
        targetChanged(capturedTargetConfirmed())
    }
    @Synchronized fun useLaboratory(displayId:Int=0) {
        check(SessionPolicy.mayInstallConfiguration(mode,contacts,releaseUnverified) && !active && !armed && !testRunning && !starting && !stopping) { "Espera a que termine el gesto anterior" }
        require(displayId>=0) { "Pantalla de destino no válida" }
        runtimeProfile=null; startInCursor=false;activeProfileId=null; profileName=null; targetPackage=TARGET;targetDisplayId=displayId
        lastAttemptedFrame=null;lastMotorError=null;firstRejectedInput=null
        if(Looper.myLooper()==Looper.getMainLooper()) CaptureService.instance?.refreshContext()
        targetChanged(capturedTargetConfirmed())
    }
    /** A same-package cache from another display must never authorize a new session. */
    private fun capturedTargetConfirmed():Boolean {
        val capture=CaptureService.instance ?: return false
        return capture.currentApplicationPackage==targetPackage && capture.boundsForPackage(targetPackage)!=null
    }
    @Synchronized fun selectEditingTarget(profile:GameProfile,displayId:Int=0) {
        check(SessionPolicy.mayEdit(contacts,releaseUnverified,active) && !stopping)
        require(displayId>=0) { "Pantalla de destino no válida" }
        runtimeProfile=null; activeProfileId=profile.id; profileName=profile.name; targetPackage=profile.packageName;targetDisplayId=displayId
        explicitlyStopped=false; mode=SessionMode.EDITING; state="Edición"; error="Mapeo pausado; guardar no reanuda acciones mantenidas"
    }
    @Synchronized fun armProfile(prepared:PreparedProfile,cursor:Boolean=false,displayId:Int=targetDisplayId) {
        check(SessionPolicy.mayInstallConfiguration(mode,contacts,releaseUnverified) && !active && !stopping) { "Termina o recupera la sesión anterior" }
        require(displayId>=0) { "Pantalla de destino no válida" }
        // Closed diagnostic choice is RAM-only and applies only at this empty transition.
        // The saved profile and the classic laboratory configuration are unchanged.
        runtimeProfile=if(prepared.profile.packageName==TARGET || prepared.bindings.continuousCamera) prepared else prepared.copy(bindings=prepared.bindings.copy(
            overlapCameraHandoff=cameraTrial=="overlap",distinctCameraContacts=cameraTrial!="same-id",cameraLast=true))
        startInCursor=cursor; activeProfileId=prepared.profile.id; profileName=prepared.profile.name; targetPackage=prepared.profile.packageName;targetDisplayId=displayId
        armInternal(prepared.profile.calibration.screenWidth,prepared.profile.calibration.screenHeight)
        targetChanged(capturedTargetConfirmed())
    }
    private fun armInternal(width:Int,height:Int) {
        pointerTransition.invalidate()
        explicitlyStopped=false
        check(BackendRegistry.injector?.asBinder()?.isBinderAlive == true) { "Activa el backend ADB" }
        check(CaptureService.instance != null) { "Activa Accesibilidad de MobiMapper en Ajustes" }
        check(!releaseUnverified) { "Liberación no verificada; revisar laboratorio antes de reactivar" }
        check(!active && !armed && !starting && !stopping && !testRunning) { "Detén la sesión anterior y espera su liberación" }
        this.width = width; this.height = height
        val run = ++generation
        targetConfirmed = false
        armed = true; mode=SessionMode.PAUSED; state = "Esperando destino"; error = ""
        main.postDelayed({ if (run == generation && armed) pause("No se confirmó el destino en 10 segundos") }, 10_000)
    }
    @Synchronized fun configureCameraTrial(strategy:String) {
        require(strategy in setOf("sequential","same-id","overlap"))
        require(runtimeProfile?.bindings?.continuousCamera!=true) { "El ensayo de relevo no se aplica a una cámara continua" }
        check(SessionPolicy.mayInstallConfiguration(mode,contacts,releaseUnverified) && !active && !armed && !starting && !stopping && !testRunning) {
            "Detén la sesión y confirma cero contactos antes de cambiar el ensayo"
        }
        cameraTrial=strategy
    }
    @Synchronized fun targetChanged(isTarget: Boolean) {
        targetConfirmed = isTarget
        if (!isTarget) {
            if (active || starting || (testRunning && probeStarted)) pause("Aplicación destino sin foco; inicia otra vez manualmente")
            return
        }
        if (armed && !active && !testRunning && !starting && !stopping) {
            val run = generation
            starting = true
            worker.post {
                if (run != generation || !targetConfirmed) return@post
                if (prepare(run)) {
                    val accepted = synchronized(this) {
                        if (run == generation && targetConfirmed && armed && !stopping) {
                            pointerTransition.begin(!camera)
                            active = true; armed = false; starting = false; mode=SessionMode.PLAYING; state = if(targetPackage==TARGET) "Laboratorio activo" else "Juego activo"; true
                        } else false
                    }
                    if (accepted) publishMouse(run, true) else releaseOnWorker()
                } else synchronized(this) {
                    if (run == generation) { armed = false; starting = false }
                }
            }
        }
    }
    private fun prepare(run: Long): Boolean {
        if (run != generation) return false
        return try {
            discardPacedMotion()
            val backend = requireNotNull(BackendRegistry.injector)
            check((composer?.activeCount ?: 0) == 0 && composer?.releaseUnverified != true && !releaseUnverified) {
                "La sesión anterior conserva contactos o una liberación no verificada"
            }
            val continuousCamera=runtimeProfile?.bindings?.continuousCamera==true
            val packageName=targetPackage
            val displayId=targetDisplayId
            if(displayId!=0) check(backend.status().getBoolean("multiDisplaySupported")) { "Reactiva el motor para usar esta pantalla" }
            val sid=if(displayId!=0) backend.beginTargetOnDisplay(client,width,height,packageName,displayId,continuousCamera)
                else if(continuousCamera) backend.beginContinuousTarget(client,width,height,packageName)
                else if(packageName==TARGET) backend.begin(client,width,height) else backend.beginTarget(client,width,height,packageName)
            check(sid>0) { "Backend no listo o liberación no verificada" }
            // Own the newly opened empty session before another status RPC can fail.
            previousBackendSessionId=backendSessionId
            backendSessionId=sid;sessionBackend=backend;check(noticeGate.begin(sid))
            val backendState=backend.status()
            check(backendState.getInt("displayId",-1)==displayId) { "El motor no confirmó la pantalla de destino" }
            originalTargetUid=backendState.getInt("targetUid",-1)
            originalTargetPackage=packageName
            originalTargetDisplayId=displayId
            originalContinuousCamera=continuousCamera;cameraContactAdmitted=false
            lastAttemptedFrame=null;lastMotorError=null;firstRejectedInput=null
            synchronized(metricsLock) { queueCount=0;ipcCount=0;cameraDeliveryCount=0;metricsSessionId=sid }
            // A callback already admitted before pause may have offered after its clear.
            // Configuration starts empty; no old-session motion or held edge is replayed.
            queue.clear()
            queue.resetMetrics()
            val mergeGeometry=runtimeProfile?.geometry
            if(mergeGeometry!=null) queue.configureCameraMerge(
                mergeGeometry.cameraRadiusX.toDouble()/mergeGeometry.sensitivityX,
                mergeGeometry.cameraRadiusY.toDouble()/mergeGeometry.sensitivityY,
                preserveSamples=runtimeProfile?.profile?.camera?.precisionAim==true)
            else queue.disableCameraMerge()
            if (run != generation) { releaseOnWorker(); return false }
            composer = TouchComposer(SystemClock::uptimeMillis, { frame ->
                if (run != generation || stopping || !(active || (testRunning && probeStarted))) false else {
                lastAttemptedFrame=frame
                val before = SystemClock.elapsedRealtimeNanos()
                var failureOrigin="backendRejected"
                val accepted = try {
                    if(CaptureService.instance?.allowsInjection(frame,continuousCamera && cameraContactAdmitted)!=true) {
                        failureOrigin="contextGate"
                        pause("Una ventana del sistema intercepta los controles; reanuda manualmente")
                        false
                    } else backend.inject(sid,frame.action, frame.downTime, frame.eventTime, frame.ids, frame.xs, frame.ys)
                } catch(e:Exception) {
                    failureOrigin="backendException:${e.javaClass.simpleName}"
                    false
                }
                finally { synchronized(metricsLock) { ipcTime[ipcCount++ % 2048] = SystemClock.elapsedRealtimeNanos() - before } }
                if(!accepted && frame.action and 255 != TouchComposer.CANCEL && firstRejectedInput==null) {
                    firstRejectedInput=mapOf("sessionId" to sid,"origin" to failureOrigin,
                        "action" to frame.action,"ids" to frame.ids.toList(),"xs" to frame.xs.toList(),"ys" to frame.ys.toList(),
                        "downTime" to frame.downTime,"eventTime" to frame.eventTime,
                        "returnedUptimeMs" to SystemClock.uptimeMillis(),
                        "callMs" to (SystemClock.elapsedRealtimeNanos()-before)/1e6,
                        "windowContextAfterReturn" to CaptureService.instance?.contextWindowDiagnostics())
                }
                if (accepted) {
                    injected++
                    when(frame.action and 255) {
                        TouchComposer.DOWN -> cameraContactAdmitted=frame.ids.contains(TouchCoordinatePolicy.CAMERA_ID)
                        TouchComposer.POINTER_DOWN -> if(frame.ids[frame.action ushr 8]==TouchCoordinatePolicy.CAMERA_ID) cameraContactAdmitted=true
                        TouchComposer.POINTER_UP -> if(frame.ids[frame.action ushr 8]==TouchCoordinatePolicy.CAMERA_ID) cameraContactAdmitted=false
                        TouchComposer.UP,TouchComposer.CANCEL -> cameraContactAdmitted=false
                    }
                }
                accepted
                }
            },continuousCamera=continuousCamera)
            val prepared=runtimeProfile
            precisionAimFilter.configure(prepared?.let { it.profile.camera.precisionAim &&
                it.bindings.handlesButton(MappingEngine.LEFT_BUTTON) }==true)
            engine = if(prepared!=null) MappingEngine(composer!!,prepared.geometry,prepared.bindings).apply { if(startInCursor) enterCursorMode() else setCameraEnabled(true) }
            else MappingEngine(composer!!, MappingGeometry(width.toFloat(), height.toFloat(),
                joystickX=width*.23f, joystickY=height*.70f, joystickRadius=minOf(width*.12f,height*.24f),
                actionX=width*.82f, actionY=height*.82f)).apply { setCameraEnabled(true) }
            val actionComposer=composer!!
            val beginAction:(String,Float,Float)->Boolean={ owner,x,y ->
                val limit=if(actionComposer.continuousCamera && !actionComposer.hasOwner(MappingEngine.CAMERA_OWNER)) 9 else 10
                !actionComposer.paused && actionComposer.activeCount<limit && actionComposer.begin(owner,x,y)
            }
            repeatRunner?.cancel();cursorActionRunner?.cancel();cursorCommandTicket=null;cursorCommandPending=false
            repeatRunner=prepared?.repeats?.takeIf { it.isNotEmpty() }?.let { repeats ->
                RepeatRunner(repeats,SystemClock::uptimeMillis,{ delay,action -> scheduleAction(run,delay,action) },
                    beginAction,{ actionComposer.end(it) },{ publishActionState();publishEngineState() })
            }
            cursorActionRunner=prepared?.cursorActions?.takeIf { it.isNotEmpty() }?.let { commands ->
                CursorActionRunner(commands,reserveTransition={
                    val ticket=pointerTransition.requestCommand()
                    if(ticket==null) false else {
                        cursorCommandTicket=ticket
                        queue.offer(4,code=ticket,generation=run)
                    }
                },schedule={ delay,action -> scheduleAction(run,delay,action) },begin=beginAction,
                    end={ actionComposer.end(it) },changed={ publishActionState();publishEngineState() })
            }
            publishActionState()
            macroRunner?.cancel()
            macroRunner=prepared?.macros?.takeIf { it.isNotEmpty() }?.let { macros ->
                val sessionComposer=composer!!
                MacroRunner(macros, schedule={ delay, action ->
                    val task=Runnable { if(run==generation && active && targetConfirmed) action() }
                    worker.postDelayed(task,delay)
                    val cancel:()->Unit = { worker.removeCallbacks(task) }
                    cancel
                },begin={ owner,x,y ->
                    // Refuse this macro alone when the normal contact slots are occupied.
                    // Continuous camera reserves ID9 even while that finger is currently up.
                    val limit=if(sessionComposer.continuousCamera && !sessionComposer.hasOwner(MappingEngine.CAMERA_OWNER)) 9 else 10
                    !sessionComposer.paused && sessionComposer.activeCount<limit && sessionComposer.begin(owner,x,y)
                },end={ sessionComposer.end(it) },changed={ publishMacroState();publishEngineState() })
            }
            publishMacroState()
            lateinit var sessionPacer:CameraMotionPacer
            val wakeup=Runnable {
                if(run==generation && active && camera && targetConfirmed) {
                    if(!sessionPacer.drainDue() && active) pause("No se pudo enviar el movimiento de cámara programado")
                }
                else sessionPacer.discard()
            }
            sessionPacer=CameraMotionPacer(SystemClock::elapsedRealtimeNanos,{ dx,dy,oldestEnqueued ->
                if(run!=generation || !active || !camera || !targetConfirmed) false else {
                    synchronized(metricsLock) { cameraDeliveryWait[cameraDeliveryCount++ % 2048]=SystemClock.elapsedRealtimeNanos()-oldestEnqueued }
                    engine?.relativeMouse(dx,dy)
                    publishEngineState()
                    active && run==generation && composer?.paused!=true && engine?.lastOperationSucceeded!=false
                }
            },{ delayNanos ->
                val delayMillis=if(delayNanos<=0L) 0L else (delayNanos+999_999L)/1_000_000L
                if(!worker.postDelayed(wakeup,delayMillis)) pause("El hilo de cámara no está disponible")
            })
            sessionPacer.configure(prepared?.profile?.camera?.updateRateHz ?: 0)
            cameraPacer=sessionPacer;cameraPacerWakeup=wakeup
            camera = prepared==null || !startInCursor; true
        } catch(e: Exception) {
            releaseOnWorker()
            synchronized(this) { if (run == generation) { error = e.message ?: "Backend no disponible"; state = "Pausada" } }
            false
        }
    }
    fun key(code: Int, down: Boolean) {
        if(!active || !targetConfirmed) return
        inputKeys++
        val shortcuts=runtimeProfile?.profile?.shortcuts
        if(shortcuts!=null) {
            if(shortcuts.stop>0 && code==shortcuts.stop) { if(down) pause("Parada por atajo configurado"); return }
            if(cameraShortcutEnabled() && code==shortcuts.cameraToggle) { if(down) requestPointerMode(); return }
            val suspendKey=runtimeProfile?.profile?.camera?.suspendKey ?: 0
            if(cameraShortcutEnabled() && suspendKey>0 && code==suspendKey) {
                pointerTransition.hold(down)?.let { queue.offer(4,code=it,generation=generation) }
                return
            }
            if(shortcuts.edit>0 && code==shortcuts.edit) { if(down) main.post { GameCoordinator.editCurrent() }; return }
            if(code in (runtimeProfile?.bindings?.commandKeys ?: emptySet())) {
                queue.offer(1,code,if(down)1f else 0f,generation=generation);return
            }
            // A consumed DOWN may be followed by its UP after switching to the cursor.
            // Let the runner clear its latch without starting anything in that mode.
            if(!down && code in (runtimeProfile?.bindings?.macroKeys ?: emptySet())) {
                queue.offer(1,code,0f,generation=generation);return
            }
            if(cursorMode || pointerTransitionPending) return
            if(runtimeProfile?.bindings?.handlesKey(code)==true) queue.offer(1,code,if(down)1f else 0f,generation=generation)
            return
        }
        when (SessionKeys.actionFor(code)) {
            SessionKeyAction.STOP -> { if (down) pause("Parada por atajo configurado"); return }
            SessionKeyAction.CAMERA_TOGGLE -> { if (down) requestPointerMode(); return }
            SessionKeyAction.PASS_THROUGH -> return
            SessionKeyAction.MAPPING -> Unit
        }
        if(!pointerTransitionPending) queue.offer(1, code, if (down) 1f else 0f, generation=generation)
    }
    private fun requestPointerMode() { pointerTransition.toggleBase()?.let { queue.offer(4,code=it,generation=generation) } }
    fun cameraShortcutEnabled():Boolean=runtimeProfile?.bindings?.cameraMappingEnabled!=false
    fun isCursorCommandButton(code:Int):Boolean=runtimeProfile?.cursorActions?.any { it.triggerKind=="mouse" && it.code==code }==true
    /** Cold presentation setup, before the cursor window becomes visible. */
    fun cursorEntryBounds(target:PointerBounds):PointerBounds {
        val area=runtimeProfile?.profile?.calibration?.area
        return CursorPositionPolicy.entryArea(area?.let { PointerBounds(it.left,it.top,it.right,it.bottom) },target)
    }
    val cursorDisplayWidth:Int get()=width
    val cursorDisplayHeight:Int get()=height
    fun nativeMouseReleased() {
        val run=generation
        main.postDelayed({ worker.post {
            if(run==generation && !active && !starting) runCatching { BackendRegistry.injector?.release(backendSessionId) }
        } },48)
    }
    fun button(code: Int, down: Boolean) { inputMouse++; queue.offer(2, code, if(down) 1f else 0f, generation=generation) }
    /** Physical UP bypasses the action gate to release repeats and clear command/macro latches. */
    fun mappedButtonReleased(code:Int):Boolean {
        val bindings=runtimeProfile?.bindings ?: return false
        if(!active || !targetConfirmed || (code !in bindings.macroButtons && code !in bindings.commandButtons)) return false
        queue.offer(7,code=code,generation=generation);return true
    }
    fun macroButtonReleased(code:Int):Boolean=mappedButtonReleased(code)
    fun mouse(dx: Float, dy: Float, sampleTimeNanos:Long=Long.MIN_VALUE) {
        if(!active || !targetConfirmed || pointerTransitionPending) return
        inputMouse++;if(dx!=0f || dy!=0f) { relativeSamples++;queue.offer(3,x=dx,y=dy,generation=generation,sampleTimeNanos=sampleTimeNanos) }
    }
    fun cursorMove(x:Float,y:Float) {
        if(!active || !targetConfirmed || pointerTransitionPending) return
        inputMouse++;queue.offer(6,x=x,y=y,generation=generation)
    }
    fun cursorButton(down:Boolean,x:Float,y:Float) {
        if(!active || !targetConfirmed || pointerTransitionPending) return
        inputMouse++;queue.offer(5,code=if(down)1 else 0,x=x,y=y,generation=generation)
    }
    fun wheel(vertical: Float) { wheelSteps += vertical } // observation only, no fake binding
    fun backendDied() { pause("Backend perdido; la liberación requiere comprobarse en receptor") }
    fun consumesKey(code:Int):Boolean {
        if(!active || !targetConfirmed) return false
        val p=runtimeProfile ?: return SessionKeys.consumes(code)
        val suspendKey=p.profile.camera.suspendKey
        val cameraShortcut=cameraShortcutEnabled() && (code==p.profile.shortcuts.cameraToggle || (suspendKey>0 && code==suspendKey))
        if(cameraShortcut) return true
        if(cursorMode && !pointerTransitionPending) return p.cursorActions.any { it.triggerKind=="key" && it.code==code } ||
            (p.profile.shortcuts.stop>0 && code==p.profile.shortcuts.stop)
        return p.bindings.handlesKey(code) || (p.profile.shortcuts.stop>0 && code==p.profile.shortcuts.stop) || (p.profile.shortcuts.edit>0 && code==p.profile.shortcuts.edit)
    }
    fun consumesButton(code:Int)=runtimeProfile?.bindings?.handlesButton(code) ?: (code==MappingEngine.LEFT_BUTTON)
    fun isTransitioning()=armed || starting || stopping || testRunning
    fun pauseThen(reason:String,onReady:(Boolean)->Unit) {
        pause(reason)
        val run=generation
        worker.post { val safe=SessionPolicy.mayEdit(contacts,releaseUnverified,active)
            main.post { if(run==generation) onReady(safe) }
        }
    }
    fun editorClosed() { if(!active) { mode=if(releaseUnverified) SessionMode.BLOCKED else SessionMode.PAUSED; state=if(releaseUnverified) "Bloqueada" else "Pausada" } }
    fun stopSession() { explicitlyStopped=true; pause("Detenida por usuario") }
    @Synchronized fun sessionServiceDestroyed() {
        // A prior pause owns its release and diagnostic reason. Service cleanup must
        // not replace either, including an inactive blocked/recovering sequence.
        if(stopping || (!active && !armed && !starting && !testRunning)) return
        pause("Servicio de sesión detenido")
    }
    @Synchronized fun pause(reason: String = "Detenida por usuario") {
        if(active || probeStarted) {
            val frame=lastAttemptedFrame
            val trace=mapOf<String,Any?>("uptimeMs" to SystemClock.uptimeMillis(),"reason" to reason,
                "sessionId" to backendSessionId,"packageName" to targetPackage,"contactsBeforePause" to contacts,
                "motorError" to lastMotorError,"attemptedAction" to frame?.action,"attemptedIds" to frame?.ids?.toList(),
                "firstRejectedInput" to firstRejectedInput,
                "cameraRate" to cameraPacer?.diagnostics(),
                "attemptedXs" to frame?.xs?.toList(),"attemptedYs" to frame?.ys?.toList(),
                "windowContext" to CaptureService.instance?.contextWindowDiagnostics(),
                "pointerMode" to if(cursorMode) "cursor" else "camera","keys" to inputKeys,"relativeSamples" to relativeSamples)
            activePauseHistory=(activePauseHistory+trace).takeLast(8)
        }
        active = false; armed = false; generation++; testRunning = false;pointerTransition.invalidate();cursorCommandPending=false
        starting = false; stopping = true; probeStarted = false
        val run = generation
        mode=if(explicitlyStopped) SessionMode.STOPPED else SessionMode.PAUSED; state = if(explicitlyStopped) "Detenida" else "Pausada"; error = reason
        queue.clear()
        publishMouse(run, false)
        main.post { GameCoordinator.refreshControls() }
        worker.post {
            releaseOnWorker()
            synchronized(this) { if (run == generation) { stopping = false; if(releaseUnverified && mode!=SessionMode.RECOVERING) { mode=SessionMode.BLOCKED; state="Bloqueada" } } }
            main.post { GameCoordinator.refreshControls() }
            Log.i("MobiMapper", "paused releaseAcknowledged=${!releaseUnverified} pending=$contacts reason=$reason")
        }
    }
    private fun releaseOnWorker(): Boolean {
        repeatRunner?.cancel();cursorActionRunner?.cancel();cursorCommandTicket=null;publishActionState()
        macroRunner?.cancel();publishMacroState()
        discardPacedMotion()
        val c = composer
        val backend = sessionBackend
        val priorUnverified = releaseUnverified
        val pending = (c?.activeCount ?: 0) > 0 || c?.releaseUnverified == true || priorUnverified
        val localReleased = engine?.pause() ?: c?.cancelAll() ?: !pending
        val backendReleased = runCatching { backend?.release(backendSessionId) ?: false }.getOrDefault(false)
        // Only the Binder captured with this composer can acknowledge its possibly delivered stream.
        // A new registered backend may know nothing about contacts left by a dead process.
        if (backendReleased) { c?.acknowledgeBackendReset(); sessionBackend = null;cameraContactAdmitted=false }
        releaseUnverified = if (backendReleased) false else
            (c?.releaseUnverified == true || (c?.activeCount ?: 0) > 0 || (priorUnverified && !localReleased))
        contacts = c?.activeCount ?: 0
        return !releaseUnverified
    }
    private fun probeTargetFocused(): Boolean = if (CaptureService.instance != null) targetConfirmed else
        runCatching { BackendRegistry.injector?.status()?.getBoolean("targetFocused") == true }.getOrDefault(false)
    /** Deterministic injection only. Never counts as a physical keyboard/mouse test. */
    @Synchronized fun probe(width: Int, height: Int,displayId:Int=0) {
        useLaboratory(displayId)
        check(!active && !armed && !starting && !stopping && !testRunning) { "Detén primero la sesión y espera su liberación" }
        check(!releaseUnverified) { "Liberación pendiente de verificar" }
        this.width = width; this.height = height
        val run = ++generation
        testRunning = true; probeStarted = false; syntheticProbe = "Esperando receptor"; state = "Diagnóstico sintético"; error = ""
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        var focusConfirmedAt = 0L
        val awaitTarget = object : Runnable {
        override fun run() {
            if (run != generation) return
            if (!probeTargetFocused()) {
                focusConfirmedAt = 0L
                if (SystemClock.elapsedRealtime() >= deadline) { syntheticProbe = "Destino no confirmado"; pause("No se confirmó foco del receptor") }
                else worker.postDelayed(this, 150)
                return
            }
            // The focused Activity can still be transformed by its launch animation. This delay is
            // diagnostic startup only; no delay is introduced in the physical input engine.
            val now = SystemClock.elapsedRealtime()
            if (focusConfirmedAt == 0L) {
                focusConfirmedAt = now
                syntheticProbe = "Esperando estabilización de ventana"
                worker.postDelayed(this, 600)
                return
            }
            if (now - focusConfirmedAt < 600L) { worker.postDelayed(this, 600L - (now - focusConfirmedAt)); return }
            if (!prepare(run)) { if (run == generation) { syntheticProbe = "Falló: $error"; testRunning = false }; return }
            if (run != generation || !probeTargetFocused()) { releaseOnWorker(); if (run == generation) pause("Destino perdido al preparar"); return }
            probeStarted = true
            val c = composer!!
            fun step(delay: Long, operation: () -> Boolean) { worker.postDelayed({
                if (run != generation) return@postDelayed
                if (!probeTargetFocused()) { syntheticProbe = "Interrumpido: destino sin foco"; pause("Destino perdido durante diagnóstico"); return@postDelayed }
                if (!operation()) { syntheticProbe = "Falló: ${c.lastError}"; pause("Fallo diagnóstico") }
                contacts = c.activeCount
            }, delay) }
            step(0) { c.begin("probe-joystick", width*.23f, height*.70f) }
            step(150) { c.begin("probe-camera", width*.66f, height*.48f) }
            step(300) { c.begin("probe-action", width*.82f, height*.82f) }
            step(450) { c.move("probe-joystick", width*.30f, height*.66f) }
            step(600) { c.end("probe-camera") }
            step(750) { c.move("probe-joystick", width*.19f, height*.67f) }
            step(900) { c.end("probe-action") }
            step(1050) { c.end("probe-joystick") }
            step(1200) {
                val released = releaseOnWorker()
                if (!released) return@step false
                syntheticProbe = "8 transiciones aceptadas; verificar informe del receptor"
                state = "Diagnóstico terminado"; testRunning = false; probeStarted = false
                Log.i("MobiMapper", "probe complete pending=${c.activeCount} injected=$injected")
                true
            }
        } }
        worker.post(awaitTarget)
    }
    fun shutdownBackend() { pause(); worker.post { runCatching { BackendRegistry.injector?.shutdown() }; BackendRegistry.injector = null } }
    /** Closed laboratory route; exercises cursor delivery with an independent touch receiver. */
    @Synchronized fun testCursorLab(context:Context) {
        check(CaptureService.instance?.currentApplicationPackage==TARGET) { "Abre InputLab primero" }
        useLaboratory()
        val display=context.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(0)
        val size=android.graphics.Point()
        @Suppress("DEPRECATION")
        display.getRealSize(size)
        val p=GameProfile.create(TARGET,"Cursor InputLab",size.x,size.y,display.rotation).let {
            it.copy(calibration=it.calibration.copy(confirmed=true),camera=it.camera.copy(mode=CameraSpec.BOUNDED,updateRateHz=0),shortcuts=SessionShortcuts(138,139,0))
        }
        armProfile(ProfileCompiler.prepare(p,size.x,size.y,display.rotation),cursor=true)
        activeProfileId=null // Ephemeral laboratory configuration, not a selected game profile.
    }
    /** Closed synthetic repeat/Map regression; same receiver, shared compositor, RAM-only profile. */
    fun testActionLab(context:Context) {
        if(Looper.myLooper()!=Looper.getMainLooper()) {
            main.post { runCatching { testActionLab(context) }.onFailure { pause(it.message ?: "Falló la prueba de acciones") } }
            return
        }
        check(CaptureService.instance?.currentApplicationPackage==TARGET) { "Abre InputLab primero" }
        useLaboratory()
        val display=context.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(0)
        val size=android.graphics.Point()
        @Suppress("DEPRECATION")
        display.getRealSize(size)
        val profile=GameProfile.create(TARGET,"Acciones InputLab",size.x,size.y,display.rotation).let {
            it.copy(calibration=it.calibration.copy(confirmed=true),
                camera=it.camera.copy(enabled=true,mode=CameraSpec.CONTINUOUS,suspendKey=0,updateRateHz=0,precisionAim=false),
                action=ActionSpec(.87f,.80f,code=62),shortcuts=SessionShortcuts(138,139,0),
                additionalActions=listOf(
                    ActionSpec(.50f,.50f,code=30,behavior=ActionSpec.REPEAT,repeatIntervalMs=100),
                    ActionSpec(.75f,.35f,code=41,behavior=ActionSpec.CURSOR_TOGGLE)))
        }
        armProfile(ProfileCompiler.prepare(profile,size.x,size.y,display.rotation));activeProfileId=null
        val run=generation;val deadline=SystemClock.uptimeMillis()+5000
        syntheticProbe="Preparando repetición y dos acciones Map; entrada sintética"
        val await=object:Runnable { override fun run() {
            if(run!=generation) return
            if(!active) {
                if(SystemClock.uptimeMillis()<deadline) worker.postDelayed(this,20)
                else pause("No inició la prueba de acciones")
                return
            }
            fun step(delay:Long,action:()->Unit) {
                worker.postDelayed({ if(run==generation && active && targetConfirmed) action() },delay)
            }
            step(0) { key(51,true);mouse(40f,20f) }
            step(100) { key(30,true) }
            step(500) { mouse(20f,-10f) }
            step(650) { key(30,false) };step(700) { key(51,false) }
            step(850) { key(41,true) };step(900) { key(41,false) }
            step(1100) { key(41,true) };step(1150) { key(41,false) }
            step(1300) { mouse(20f,10f) }
            step(1400) { key(30,true) }
            step(1480) { key(138,true) };step(1490) { key(138,false) }
            step(1510) { key(30,false) }
            step(1600) { key(138,true) };step(1610) { key(138,false) }
            step(1700) { key(30,true) };step(1810) { key(30,false) }
            step(1900) {
                syntheticProbe="Acciones sintéticas terminadas: verificar dos Map, pulsos y cancelación en el mismo receptor"
                pause("Fin de diagnóstico de acciones")
            }
        } }
        worker.post(await)
    }
    /** Shell-only RAM profile: real shared composer, independent receiver, no saved game edits. */
    fun testMacroLab(context:Context,automated:Boolean) {
        if(Looper.myLooper()!=Looper.getMainLooper()) {
            main.post { runCatching { testMacroLab(context,automated) }.onFailure { pause(it.message ?: "Falló la prueba de macro") } };return
        }
        check(CaptureService.instance?.currentApplicationPackage==TARGET) { "Abre InputLab primero" }
        useLaboratory()
        val display=context.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(0)
        val size=android.graphics.Point()
        @Suppress("DEPRECATION")
        display.getRealSize(size)
        val p=GameProfile.create(TARGET,"Macro InputLab",size.x,size.y,display.rotation).let {
            it.copy(calibration=it.calibration.copy(confirmed=true),camera=it.camera.copy(suspendKey=113),
                shortcuts=SessionShortcuts(138,139,0),macros=listOf(MacroSpec("lab-macro","Secuencia",.42f,.45f,code=30,
                    steps=listOf(MacroStep(.42f,.45f,150),MacroStep(.55f,.55f,150),MacroStep(.65f,.60f,300)))))
        }
        armProfile(ProfileCompiler.prepare(p,size.x,size.y,display.rotation));activeProfileId=null
        if(!automated) return
        val run=generation;val deadline=SystemClock.uptimeMillis()+5000
        val await=object:Runnable { override fun run() {
            if(run!=generation) return
            if(!active) {
                if(SystemClock.uptimeMillis()<deadline) worker.postDelayed(this,20) else pause("No inició la prueba de macro")
                return
            }
            fun step(delay:Long,action:()->Unit) { worker.postDelayed({ if(run==generation && active && targetConfirmed) action() },delay) }
            step(0) { key(51,true);mouse(40f,20f) }
            step(100) { key(30,true) };step(150) { key(30,false) }
            step(450) { mouse(20f,-10f) };step(800) { key(51,false) }
            step(950) { key(30,true) };step(970) { key(30,false) }
            step(990) { key(113,true) };step(1120) { key(113,false) }
            step(1200) { mouse(20f,10f) }
            step(1300) { key(30,true) };step(1320) { key(30,false) }
            step(1400) { syntheticProbe="Macro completa, cancelación por Suspender y pausa solicitadas; verificar receptor";pause("Fin de diagnóstico de macro") }
        } }
        worker.post(await)
    }
    /** Replay the user's bindings against our own receiver; never edits or recalibrates the saved game. */
    @Synchronized fun testMappedLab(context:Context,automated:Boolean=false,holdContinuous:Boolean=false,precision:Boolean=false) {
        if(Looper.myLooper()!=Looper.getMainLooper()) {
            main.post { runCatching { testMappedLab(context,automated,holdContinuous,precision) }.onFailure { pause(it.message ?: "No inició el perfil de prueba") } }
            return
        }
        val capture=requireNotNull(CaptureService.instance)
        check(capture.currentApplicationPackage==TARGET) { "Abre InputLab primero" }
        val saved=requireNotNull(ProfileStore(context).selected()) { "Selecciona un perfil primero" }
        require(!holdContinuous || saved.camera.mode=="continuous") { "Selecciona cámara continua para probar su recuperación" }
        useLaboratory()
        capture.refreshContext()
        val bounds=requireNotNull(capture.boundsForPackage(TARGET)) { "Receptor sin ventana verificada" }
        val display=context.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(0)
        val size=android.graphics.Point()
        @Suppress("DEPRECATION")
        display.getRealSize(size)
        val mirrored=saved.copy(packageName=TARGET,name="Perfil en InputLab",
            camera=if(precision) saved.camera.copy(precisionAim=true) else saved.camera,
            calibration=saved.calibration.copy(screenWidth=size.x,screenHeight=size.y,rotation=display.rotation,
                area=GameArea(bounds.left.toFloat(),bounds.top.toFloat(),bounds.right.toFloat(),bounds.bottom.toFloat()),
                confirmed=true,displayScope="phone",windowFrame=null),
            shortcuts=SessionShortcuts(138,139,0))
        val precisionMarkerKey=mirrored.actions.firstOrNull { it.triggerKind=="key" }?.code
        require(!precision || precisionMarkerKey!=null) { "El perfil de prueba necesita una acción de teclado" }
        armProfile(ProfileCompiler.prepare(mirrored,size.x,size.y,display.rotation))
        activeProfileId=null
        if(!automated && !holdContinuous) return
        val run=generation;val deadline=SystemClock.uptimeMillis()+5000
        syntheticProbe="Preparando secuencia del perfil"
        val await=object:Runnable {
            override fun run() {
                if(run!=generation) return
                if(!active) {
                    if(SystemClock.uptimeMillis()<deadline) worker.postDelayed(this,20)
                    else pause("No inició el perfil de prueba")
                    return
                }
                fun step(delay:Long,action:()->Unit) { worker.postDelayed({ if(run==generation && active) action() },delay) }
                if(holdContinuous) {
                    step(0) { button(1,true) }
                    step(40) { key(mirrored.joystick.upKey,true) }
                    step(80) { mouse(size.x*2f/mirrored.camera.sensitivityX,-size.y*2f/mirrored.camera.sensitivityY);syntheticProbe="Cámara continua exterior mantenida" }
                    step(15000) { pause("Fin de prueba continua mantenida") }
                    return
                }
                step(0) { mouse(20f,10f) }
                step(40) { button(1,true) }
                if(precision) repeat(4) { index ->
                    step(50L+index*8L) {
                        mouse(if(index%2==0) 1f else -1f,.25f,SystemClock.elapsedRealtimeNanos())
                        // Non-MOVE marker frames retain exact admitted coordinates even if Android
                        // resamples/coalesces MOVE callbacks. This is laboratory-only input.
                        key(requireNotNull(precisionMarkerKey),true);key(precisionMarkerKey,false)
                    }
                }
                step(80) { mouse(350f,-420f) }
                step(150) { key(mirrored.joystick.upKey,true) }
                step(200) { mouse(400f,300f) }
                step(260) { key(mirrored.joystick.rightKey,true) }
                step(320) { mouse(-600f,-380f) }
                step(420) { key(mirrored.joystick.upKey,false) }
                step(480) { mouse(450f,360f) }
                step(560) { key(mirrored.joystick.rightKey,false) }
                step(620) { button(1,false) }
                step(750) { syntheticProbe="Secuencia de perfil completada";key(139,true) }
            }
        }
        worker.post(await)
    }
    fun backendAvailable() {
        worker.post {
            val frame=composer?.recoveryFrame()
            if(releaseUnverified && frame!=null) {
                val backend=BackendRegistry.injector ?: return@post
                state="Recuperando gesto anterior"; mode=SessionMode.RECOVERING
                main.post { GameCoordinator.refreshControls() }
                try {
                    if(originalTargetDisplayId!=0) check(backend.status().getBoolean("multiDisplaySupported")) { "El motor no puede recuperar la pantalla original" }
                    val sid=if(originalTargetDisplayId!=0) backend.recoverTargetOrphanOnDisplay(client,width,height,frame.downTime,frame.ids,frame.xs,frame.ys,
                            originalTargetUid,originalTargetPackage,originalTargetDisplayId,originalContinuousCamera)
                        else if(originalContinuousCamera) backend.recoverContinuousTargetOrphan(client,width,height,frame.downTime,frame.ids,frame.xs,frame.ys,originalTargetUid,originalTargetPackage)
                        else if(originalTargetPackage==TARGET) backend.recoverOrphan(client,width,height,frame.downTime,frame.ids,frame.xs,frame.ys,originalTargetUid)
                        else backend.recoverTargetOrphan(client,width,height,frame.downTime,frame.ids,frame.xs,frame.ys,originalTargetUid,originalTargetPackage)
                    if(sid>0) {
                        val recoveredStatus=backend.status()
                        check(recoveredStatus.getInt("displayId",-1)==originalTargetDisplayId) { "La recuperación no confirmó la pantalla original" }
                        backendSessionId=sid; noticeGate.begin(sid); sessionBackend=backend; composer?.acknowledgeBackendReset()
                        releaseUnverified=false; contacts=0;cameraContactAdmitted=false
                        mode=if(explicitlyStopped) SessionMode.STOPPED else SessionMode.PAUSED
                        state=if(explicitlyStopped) "Detenida · secuencia recuperada" else "Pausada · secuencia recuperada"
                        error=if(explicitlyStopped) "Secuencia anterior cerrada; sesión detenida" else if(recoveredStatus.getBoolean("cancelAdmitted")) "Inicia manualmente; cancelación admitida por Android" else "Inicia una sesión nueva manualmente"
                    } else { state="Bloqueada"; mode=SessionMode.BLOCKED; error="Cancelación anterior no admitida" }
                } catch(e:Exception) { state="Bloqueada"; mode=SessionMode.BLOCKED; error=e.message ?: "No se pudo recuperar" }
                main.post { GameCoordinator.refreshControls() }
            }
        }
    }
    fun testStaleWork():Map<String,Boolean> {
        val frame=requireNotNull(composer?.recoveryFrame())
        val backend=requireNotNull(sessionBackend)
        check(previousBackendSessionId>0 && previousBackendSessionId!=backendSessionId)
        val staleInject=backend.inject(previousBackendSessionId,2,frame.downTime,frame.eventTime,frame.ids,frame.xs,frame.ys)
        val staleRelease=backend.release(previousBackendSessionId)
        client.recovery(previousBackendSessionId,Long.MAX_VALUE,2,"Prueba de callback antiguo")
        return mapOf("staleInjectRejected" to !staleInject,"staleReleaseRejected" to !staleRelease)
    }
    /** Closed, shell-only laboratory harness. InputLab remains a passive recipient. */
    @Synchronized fun testGesture(context:Context,count:Int,holdMillis:Int,terminalUp:Boolean=false) {
        useLaboratory()
        check(targetConfirmed && !active && !armed && !testRunning && !stopping && !releaseUnverified)
        require(count==1 || count==3); require(holdMillis in 1000..30000)
        val bounds=context.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
        width=bounds.width(); height=bounds.height()
        val run=++generation; testRunning=true; probeStarted=false; state="Diagnóstico sintético"; error=""
        worker.post {
            if(!prepare(run)) { testRunning=false; return@post }
            probeStarted=true
            val c=composer!!
            fun step(delay:Long,op:()->Boolean) { worker.postDelayed({
                if(run==generation && probeTargetFocused()) {
                    if(!op()) pause("Fallo durante prueba nativa")
                    contacts=c.activeCount
                }
            },delay) }
            step(0) { c.begin("test-joy",width*.23f,height*.70f) }
            if(count==3) {
                step(150) { c.begin("test-camera",width*.66f,height*.48f) }
                step(300) { c.begin("test-action",width*.82f,height*.82f) }
                step(600) { c.end("test-action") }
                step(750) { c.begin("test-action",width*.82f,height*.82f) }
            }
            if(terminalUp) step(900) { c.end("test-joy") }
            step(holdMillis.toLong()) { pause("Fin de prueba nativa"); true }
        }
    }
    fun status(context: Context): Map<String, Any?> {
        val backend = runCatching { BackendRegistry.injector?.status() }.getOrNull()
        val devices = InputDevice.getDeviceIds().asSequence().mapNotNull { InputDevice.getDevice(it) }.filter { it.isExternal && !it.isVirtual }.toList()
        fun percentiles(values: LongArray, count: Int): List<Double> {
            if (count == 0) return emptyList()
            val sorted = synchronized(metricsLock) { values.copyOf(minOf(count, 2048)) }.sorted()
            return listOf(.50, .95, .99).map { sorted[((sorted.size - 1)*it).toInt()] / 1e6 }
        }
        val queueMetrics = queue.metrics()
        val queueDiagnostic = queue.diagnostics()
        return mapOf("protocol" to 1, "state" to state, "active" to active,
            "mode" to mode.wire,"activeProfileId" to activeProfileId,"profileName" to profileName,
            "activePackage" to targetPackage,"targetDisplayId" to targetDisplayId,"originalTargetDisplayId" to originalTargetDisplayId,
            "editorVisible" to GameCoordinator.editorVisible,
            "editorLastClose" to GameCoordinator.lastEditorClose,"contextError" to error,
            "bubbleVisible" to GameCoordinator.bubbleVisible,"bubbleMenuVisible" to GameCoordinator.bubbleMenuVisible,
            "controlsVisible" to GameCoordinator.controlsVisible,
            "bubbleAppearance" to GameCoordinator.bubbleAppearance,
            "mouseCaptured" to CaptureService.instance?.mouseCaptured,
            "pointerMode" to if(!active) "none" else if(camera) "camera" else if(runtimeProfile==null) "native" else "cursor",
            "cursorVisible" to CaptureService.instance?.cursorVisible,
            "cursorX" to CaptureService.instance?.cursorX,"cursorY" to CaptureService.instance?.cursorY,
            "cursorSamples" to CaptureService.instance?.cursorSamples,
            "contextBlockedBySystemWindow" to (CaptureService.instance?.contextBlockedBySystemWindow ?: false),
            "contextBlockReason" to CaptureService.instance?.contextBlockReason,
            "contextSystemWindowCount" to CaptureService.instance?.contextSystemWindowCount,
            "contextCoveringSystemPackage" to CaptureService.instance?.contextCoveringSystemPackage,
            "contextCheckedUptime" to CaptureService.instance?.contextCheckedUptime,
            "windowContext" to CaptureService.instance?.contextWindowDiagnostics(),
            "targetPreparation" to GameCoordinator.lastTargetPreparation,
            "error" to error, "accessibility" to (CaptureService.instance != null), "backend" to (backend != null),
            "backendUid" to backend?.getInt("uid"),"backendDisplayId" to backend?.getInt("displayId"),
            "multiDisplaySupported" to backend?.getBoolean("multiDisplaySupported"),"injectPermission" to backend?.getBoolean("injectPermission"),
            "backendError" to backend?.getString("error"), "targetUid" to backend?.getInt("targetUid"),
            "targetFocused" to backend?.getBoolean("targetFocused"), "focusError" to backend?.getString("focusError"),
            "supervisorPid" to backend?.getInt("supervisorPid"), "workerPid" to backend?.getInt("workerPid"),
            "workerGeneration" to backend?.getLong("workerGeneration"), "backendSessionId" to backendSessionId,
            "recoveryState" to backend?.getString("recoveryState"), "recoveryAttempts" to backend?.getInt("recoveryAttempts"),
            "recoveredCount" to backend?.getInt("recoveredCount"), "cancelAdmitted" to backend?.getBoolean("cancelAdmitted"),
            "liveWorkerCancelAdmitted" to backend?.getBoolean("liveWorkerCancelAdmitted"),
            "terminalReconciled" to backend?.getBoolean("terminalReconciled"),
            "rejectedMoveReconciled" to backend?.getBoolean("rejectedMoveReconciled"),
            "detectedUptime" to backend?.getLong("detectedUptime"), "recoveredUptime" to backend?.getLong("recoveredUptime"),
            "backendPossibleContacts" to backend?.getInt("possibleContacts"), "backendConfirmedContacts" to backend?.getInt("confirmedContacts"),
            "backendDownTime" to backend?.getLong("downTime"), "backendLastAction" to backend?.getInt("lastAction"), "backendLastEventTime" to backend?.getLong("lastEventTime"),
            "backendIds" to backend?.getIntArray("ids")?.toList(), "uptimeMs" to SystemClock.uptimeMillis(),
            "backendXs" to backend?.getFloatArray("xs")?.toList(), "backendYs" to backend?.getFloatArray("ys")?.toList(),
            "staleCallbacksDiscarded" to noticeGate.discarded,
            "uiCreated" to dev.mobimapper.mobimapper.MainActivity.createdCount,
            "uiDestroyed" to dev.mobimapper.mobimapper.MainActivity.destroyedCount,
            "uiInstance" to dev.mobimapper.mobimapper.MainActivity.lastInstance,
            "backendProtocol" to backend?.getInt("protocol"), "controllerBinderAlive" to backend?.getBoolean("controllerBinderAlive"), "workerAlive" to backend?.getBoolean("workerAlive"),
            "backendCoordinatePolicy" to backend?.getString("coordinatePolicy"),"continuousCameraSupported" to backend?.getBoolean("continuousCameraSupported"),
            "systemPointerHidden" to backend?.getBoolean("systemPointerHidden"), "systemPointerError" to backend?.getString("systemPointerError"),
            "recoveryIds" to backend?.getIntArray("recoveryIds")?.toList(), "recoveryDownTime" to backend?.getLong("recoveryDownTime"), "recoveryEventTime" to backend?.getLong("recoveryEventTime"),
            "releaseUnverified" to (releaseUnverified || backend?.getBoolean("releaseUnverified") == true), "contacts" to contacts, "keys" to inputKeys,
            "mouse" to inputMouse, "relativeSamples" to relativeSamples, "wheelSteps" to wheelSteps.toDouble(),
            "injected" to injected, "probe" to syntheticProbe, "maxQueueDepth" to queueMetrics.first,
            "cameraRestarts" to engine?.cameraRestarts, "aimOwner" to engine?.aimOwner,
            "cameraTrial" to if(runtimeProfile?.bindings?.continuousCamera==true) "continuous" else cameraTrial,"cameraOverlap" to runtimeProfile?.bindings?.overlapCameraHandoff,
            "cameraDistinctIds" to runtimeProfile?.bindings?.distinctCameraContacts,
            "cameraMode" to runtimeProfile?.profile?.camera?.mode,"continuousCameraSession" to originalContinuousCamera,
            "cameraRate" to cameraPacer?.diagnostics(),"cameraDeliveryWaitMs" to percentiles(cameraDeliveryWait,cameraDeliveryCount),
            "precisionAim" to precisionAimFilter.snapshot(),
            "actionRuntime" to actionState,
            "joystickMappingEnabled" to (runtimeProfile?.bindings?.joystickEnabled ?: true),
            "cameraMappingEnabled" to cameraShortcutEnabled(),
            "cameraRequestedRateHz" to runtimeProfile?.profile?.camera?.updateRateHz,
            "cameraContactAdmitted" to cameraContactAdmitted,
            "pointerTransitionPending" to pointerTransitionPending,
            "cursorCommandPending" to cursorCommandPending,
            "macros" to macroState,
            "pointerBaseMode" to if(pointerTransition.baseCursor) "cursor" else "camera",
            "cameraSuspendedByHold" to pointerTransition.temporaryCursorHeld,
            "activePauseHistory" to activePauseHistory,
            "firstRejectedInput" to firstRejectedInput,
            "metricsSessionId" to metricsSessionId,"queueDiagnostics" to mapOf(
                "currentDepth" to queueDiagnostic.currentDepth,"maxDepth" to queueDiagnostic.maxDepth,
                "mergedMoves" to queueDiagnostic.mergedMoves,"relativeMergedMoves" to queueDiagnostic.relativeMergedMoves,
                "cursorMergedMoves" to queueDiagnostic.cursorMergedMoves),
            "backendTimingSessionId" to backend?.getLong("timingSessionId"),
            "backendFocusMs" to backend?.getDoubleArray("focusMs")?.toList(),
            "backendPipeMs" to backend?.getDoubleArray("pipeMs")?.toList(),
            "backendFocusCount" to backend?.getLong("focusTimingCount"),"backendPipeCount" to backend?.getLong("pipeTimingCount"),
            "fireHoldStarts" to engine?.fireHoldStarts, "fireHoldEnds" to engine?.fireHoldEnds, "fireBoundaryClamps" to engine?.fireBoundaryClamps,
            "physicalMouseButtons" to CaptureService.instance?.physicalMouseButtons,
            "mergedMoves" to queueMetrics.second, "queueMs" to percentiles(queueWait,queueCount), "ipcMs" to percentiles(ipcTime,ipcCount),
            "devices" to devices.map { mapOf("name" to it.name, "descriptor" to it.descriptor, "vendorId" to it.vendorId, "productId" to it.productId, "sources" to it.sources) })
    }
}
