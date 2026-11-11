package dev.uwumouse.app.game

import dev.uwumouse.app.runtime.NativeSessionHost
import dev.uwumouse.app.runtime.DisplayResolutionController
import dev.uwumouse.app.runtime.OverlayDisplayResolutionSettings

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import dev.uwumouse.app.editor.ControllerBubble
import dev.uwumouse.app.editor.DisplayResolutionSettingsPort
import dev.uwumouse.app.editor.BubbleSettingsStore
import dev.uwumouse.app.editor.BubbleSettings
import dev.uwumouse.app.editor.NativeEditor
import dev.uwumouse.app.editor.MappedControlsOverlay
import dev.uwumouse.app.editor.PerformanceMonitorBubble
import dev.uwumouse.app.editor.EditorDraft
import dev.uwumouse.app.editor.OverlayWindows
import dev.uwumouse.app.profile.*
import dev.uwumouse.app.session.*
import org.json.JSONArray
import org.json.JSONObject

/** Control plane only. Profiles are compiled once before arm; this object never sees input samples. */
object GameCoordinator {
    private val main=Handler(Looper.getMainLooper())
    private lateinit var app:Context
    private var repository:ProfileStore?=null
    private var service:NativeSessionHost?=null
    private var editor:NativeEditor?=null
    private var bubble:ControllerBubble?=null
    private var resolutionSettings:DisplayResolutionSettingsPort?=null
    private var resolutionToolsDisplayId=-1
    private var controls:MappedControlsOverlay?=null
    private var monitor:PerformanceMonitorBubble?=null
    private var monitorContext:Context?=null
    private var statusVisual:TrustedStatusVisualPort?=null
    private val monitorSampler=PerformanceMonitorSampler { fps -> monitor?.updateFps(fps) }
    private var presentationDisplayId=-1
    private var geometryInvalidated=false
    private val menuBounds=Rect()
    private var presentationRestore:Runnable?=null
    private var operation=0L
    private var currentId:String?=null
    private var waiting=false
    private var toolsRequested=false
    private var stopServiceWhenClosed=false
    private var closingPresentation=false
    private var cameraShortcut=138
    private var startPending=false
    private var pendingCursorStart=false
    private var activeWindow:Rect?=null
    private var runtimeDisplayProfile:GameProfile?=null
    private var runtimeProfileSource:GameProfile?=null
    private var settledTarget:TargetSnapshot?=null
    private val contextReturn=ContextReturnGate()
    private var contextReturnBackend:android.os.IBinder?=null
    private var automaticReturnRequest=false
    private val taskClosure=GameTaskClosureGate()
    private val taskClosureOwner=GameTaskClosureOwner()
    @Volatile var lastTargetPreparation:Map<String,Any?>?=null; private set
    @Volatile var lastPresentationFailure:Map<String,Any?>?=null; private set
    private var profileRows:List<ProfileMenuItem> = emptyList()
    @Volatile private var profileKeys:Map<Int,String> = emptyMap()
    private var profileMenuCursor=false
    private var cursorUiTarget=LocalPointerTarget.NONE
    @Volatile var lastEditorClose:Map<String,Any?>?=null; private set
    private fun recordEditorClose(reason:String) {
        val event=mapOf("reason" to reason,"uptimeMs" to SystemClock.uptimeMillis(),
            "profileId" to currentId,"windowContext" to service?.contextWindowDiagnostics())
        lastEditorClose=event
        Log.i("UwUMouseEditor",JSONObject(event).toString())
    }
    private fun requireProfileChangeAllowed() {
        check(!waiting && !DisplayResolutionController.changing && !SessionController.isTransitioning() && !SessionController.active && !editorVisible) { "Espera a que termine la transición de sesión antes de cambiar el perfil" }
        clearContextReturn("profile_changed")
        runtimeDisplayProfile=null;runtimeProfileSource=null
    }
    val editorVisible:Boolean get()=editor?.visible==true
    val bubbleVisible:Boolean get()=bubble?.visible==true
    val bubbleMenuVisible:Boolean get()=bubble?.menuVisible==true
    val resolutionPanelVisible:Boolean get()=bubble?.systemPanelVisible==true
    val menuTextInputActive:Boolean get()=bubble?.menuTextInputActive==true
    val controlsVisible:Boolean get()=controls?.visible==true
    val monitorVisible:Boolean get()=monitor?.visible==true
    val monitorFps:Int? get()=monitor?.fps
    val monitorBattery:Int? get()=monitor?.battery
    val bubbleAppearance:Map<String,Any>? get()=bubble?.appearance()
    val cursorSettings:BubbleSettings get()=bubble?.settingsSnapshot ?: BubbleSettings()
    val ownOverlayVisible:Boolean get()=editorVisible || bubbleVisible || controlsVisible || monitor?.visible==true || service?.cursorVisible==true
    val sessionOpen:Boolean get()=toolsRequested
    internal val needsTargetLifecycle:Boolean get()=taskClosureOwner.identity?.let {
        it.packageName==SessionController.targetPackage && it.displayId==SessionController.targetDisplayId &&
            SessionController.mode!=SessionMode.STOPPED
    }==true
    fun cameraResumeKey():Int? = if(cameraShortcut>0 && toolsRequested && !editorVisible && !menuTextInputActive && SessionController.targetConfirmed &&
        !SessionController.releaseUnverified && SessionController.mode==SessionMode.PAUSED &&
        (startPending || (!waiting && !SessionController.isTransitioning())) && SessionController.contacts==0) cameraShortcut else null
    fun resumeFromCameraKey() {
        if(startPending) { pendingCursorStart=!pendingCursorStart; return }
        currentId?.let { runCatching { startProfile(it) }.onFailure { message(it.message ?: "No se pudo iniciar") } }
    }
    private fun clearContextReturn(reason:String) {
        contextReturn.clear(reason);contextReturnBackend=null
    }
    /** Native acquisition can wait for physical release, but never beyond its finite deadline. */
    fun cancelCaptureReturn(reason:String) { clearContextReturn(reason) }
    private fun returnIdentity():ContextReturnGate.Identity? = currentId?.takeIf {
        toolsRequested && it==SessionController.activeProfileId
    }?.let { ContextReturnGate.Identity(operation,it,SessionController.targetPackage,SessionController.targetDisplayId,
        SessionController.manualPauseEpoch) }
    private fun rememberActiveInterruption(requireDeparture:Boolean=false) {
        if(!SessionController.active || editorVisible || bubbleMenuVisible || resolutionPanelVisible ||
            menuTextInputActive || DisplayResolutionController.changing) return
        val identity=returnIdentity() ?: return
        val backend=dev.uwumouse.app.backend.BackendRegistry.injector?.asBinder() ?: return
        if(!backend.isBinderAlive) return
        if(contextReturn.identity!=identity) contextReturnBackend=backend
        contextReturn.interrupted(identity,requireDeparture,activeWindow?.let {
            ContextReturnGate.Frame(it.left,it.top,it.right,it.bottom)
        })
    }
    /** A system cursor escape must actually leave/change the game before it can recapture. */
    fun rememberSystemHandoff() { rememberActiveInterruption(requireDeparture=true) }
    /** Called before targetChanged can pause and discard the fact that this session was playing. */
    fun beforeContextChange(authorized:Boolean,currentWindow:Rect?,displayReady:Boolean,locked:Boolean) {
        if(!displayReady || locked) { clearContextReturn(if(locked) "locked" else "display_unavailable");return }
        if(contextReturn.identity!=null && dev.uwumouse.app.backend.BackendRegistry.injector?.asBinder()!==contextReturnBackend)
            clearContextReturn("backend_changed")
        if(!authorized || currentWindow==null || currentWindow!=activeWindow) rememberActiveInterruption()
    }
    private fun tryContextReturn(authorized:Boolean,currentWindow:Rect?) {
        if(contextReturn.identity==null) return
        val backend=dev.uwumouse.app.backend.BackendRegistry.injector?.asBinder()
        if(backend==null || backend!==contextReturnBackend || !backend.isBinderAlive || !toolsRequested) {
            clearContextReturn("owner_unavailable");return
        }
        val frame=currentWindow?.let { ContextReturnGate.Frame(it.left,it.top,it.right,it.bottom) }
        val admitted=authorized && !editorVisible && !bubbleMenuVisible && !resolutionPanelVisible &&
            !menuTextInputActive && !DisplayResolutionController.changing
        val shouldStart=contextReturn.observe(returnIdentity(),frame,admitted,
            SessionController.contacts==0 && !SessionController.releaseUnverified,
            waiting || startPending || SessionController.isTransitioning(),SessionController.active,
            SessionController.mode==SessionMode.PAUSED,SystemClock.uptimeMillis(),
            service?.nativeMouseCaptureReady==true)
        if(!shouldStart) return
        val id=contextReturn.identity?.profileId ?: return
        automaticReturnRequest=true
        try {
            // A fresh configuration/generation starts with a neutral cursor. No prior
            // key, button, loop, camera contact or queued movement is restored.
            startProfile(id,cursor=true,requireCurrentContext=true)
        } catch(failure:Exception) {
            Log.w("UwUMouse","context_return_failed type=${failure.javaClass.simpleName}")
        } finally { automaticReturnRequest=false }
    }
    fun cursorHitsBubble(x:Float,y:Float):Boolean {
        if(!toolsRequested || !SessionController.cursorMode || SessionController.contacts!=0 || editorVisible || bubbleMenuVisible) return false
        refreshControls()
        cursorUiTarget=when {
            bubble?.containsCursorPoint(x,y)==true -> LocalPointerTarget.BUBBLE
            monitor?.containsCursorPoint(x,y)==true -> LocalPointerTarget.MONITOR
            else -> LocalPointerTarget.NONE
        }
        return cursorUiTarget!=LocalPointerTarget.NONE
    }
    fun cursorBubbleTouch(action:Int,x:Float,y:Float):Boolean {
        val owner=cursorUiTarget
        if(action==android.view.MotionEvent.ACTION_UP || action==android.view.MotionEvent.ACTION_CANCEL) cursorUiTarget=LocalPointerTarget.NONE
        return when(owner) {
            LocalPointerTarget.BUBBLE -> bubble?.dispatchCursorTouch(action,x,y)==true
            LocalPointerTarget.MONITOR -> monitor?.dispatchCursorTouch(action,x,y)==true
            else -> false
        }
    }
    val menuPointerAvailable:Boolean get()=toolsRequested && bubbleMenuVisible && !editorVisible &&
        presentationDisplayId==SessionController.targetDisplayId
    fun menuPointerTarget(x:Float,y:Float):LocalPointerTarget {
        if(!menuPointerAvailable) return LocalPointerTarget.NONE
        val target=bubble?.localPointerTarget(x,y) ?: LocalPointerTarget.NONE
        return if(target!=LocalPointerTarget.NONE) target
            else if(monitor?.containsCursorPoint(x,y)==true) LocalPointerTarget.MONITOR else LocalPointerTarget.NONE
    }
    fun menuPointerTouch(target:LocalPointerTarget,action:Int,x:Float,y:Float):Boolean = when(target) {
        LocalPointerTarget.MENU -> bubble?.dispatchMenuCursorTouch(action,x,y)==true
        LocalPointerTarget.BUBBLE -> bubble?.dispatchCursorTouch(action,x,y)==true
        LocalPointerTarget.MONITOR -> monitor?.dispatchCursorTouch(action,x,y)==true
        LocalPointerTarget.NONE -> false
    }
    fun menuPointerScroll(x:Float,y:Float,vertical:Float)=bubble?.dispatchMenuCursorScroll(x,y,vertical)==true
    fun menuPointerHover(x:Float,y:Float)=bubble?.dispatchMenuCursorHover(x,y)==true
    fun clearMenuPointerHover() { bubble?.clearMenuCursorHover() }
    @Synchronized fun initialize(context:Context) {
        if(repository==null) { app=context.applicationContext; repository=ProfileStore(app) }
    }
    private fun store():ProfileStore=checkNotNull(repository) { "Abre UwU Mouse primero" }
    /** Read the repository only on explicit control-plane changes, never while processing a key. */
    private fun displayScope(displayId:Int)=if(displayId==0) "phone" else "external"
    private fun refreshProfileLibrary(packageName:String?=currentId?.let { store().get(it)?.packageName },displayId:Int=SessionController.targetDisplayId) {
        val game=packageName ?: store().selected()?.packageName
        val profiles=store().list().filter { it.packageName==game && it.calibration.displayScope==displayScope(displayId) }
        profileRows=profiles.map { ProfileMenuItem(it.id,it.name,it.switchKey,it.id==currentId) }
        profileKeys=profiles.filter { it.switchKey>0 }.associate { it.switchKey to it.id }
    }
    fun bubbleProfiles():List<ProfileMenuItem> = profileRows
    fun captureBubbleKey(code:Int):Boolean = bubble?.captureProfileKey(code)==true
    private fun profileSwitchAllowed()=toolsRequested && !waiting && !editorVisible && !bubbleMenuVisible &&
        SessionController.targetConfirmed && !SessionController.releaseUnverified && !SessionController.isTransitioning() &&
        (SessionController.mode==SessionMode.PAUSED || SessionController.mode==SessionMode.PLAYING)
    fun profileSwitchBindings():Map<Int,String> = if(profileSwitchAllowed()) profileKeys else emptyMap()
    /** Defer control-plane work until Android has acknowledged the owned DOWN. */
    fun requestProfileSwitch(id:String) {
        val ticket=operation
        main.post {
            if(id==currentId && SessionController.active) return@post
            if(ticket==operation && profileSwitchAllowed() && id in profileKeys.values)
                runCatching { activateBubbleProfile(id) }.onFailure { message(it.message ?: "No se pudo cambiar el perfil") }
        }
    }
    private fun requireBubbleProfileChange() {
        requireProfileChangeAllowed()
        check(toolsRequested && SessionController.targetConfirmed && !SessionController.releaseUnverified &&
            SessionController.mode==SessionMode.PAUSED) { "Abre Perfiles con el juego pausado" }
    }
    fun createBubbleProfile(name:String) {
        requireBubbleProfileChange()
        val previous=requireNotNull(currentId?.let { store().get(it) }) { "Selecciona un perfil primero" }
        val game=store().list().filter { it.packageName==previous.packageName }
        val finalName=name.trim().takeIf { it.isNotBlank() && game.none { row -> row.name==it } }
            ?: generateSequence(1) { it+1 }.map { "Perfil $it" }.first { candidate -> game.none { it.name==candidate } }
        val copy=previous.copy(id=java.util.UUID.randomUUID().toString(),name=finalName,switchKey=0)
        store().save(copy)
        currentId=copy.id
        cameraShortcut=if(copy.camera.enabled) copy.shortcuts.cameraToggle else 0
        SessionController.selectEditingTarget(copy,SessionController.targetDisplayId);SessionController.editorClosed()
        service?.refreshContext();refreshProfileLibrary(copy.packageName);refreshControls()
    }
    fun setBubbleProfileKey(id:String,keyCode:Int) {
        requireBubbleProfileChange()
        val previous=requireNotNull(store().get(id)) { "Perfil no encontrado" }
        check(profileRows.any { it.id==id }) { "El perfil no pertenece a este juego" }
        store().update(previous.copy(switchKey=keyCode))
        refreshProfileLibrary(previous.packageName);refreshControls()
    }
    fun deleteBubbleProfile(id:String) {
        requireBubbleProfileChange()
        check(SessionController.contacts==0) { "Espera a que terminen los contactos" }
        val previous=requireNotNull(store().get(id)) { "Perfil no encontrado" }
        check(profileRows.any { it.id==id }) { "El perfil no pertenece a este juego" }
        val replacement=if(currentId==id) profileRows.firstOrNull { it.id!=id }?.let { store().get(it.id) } else null
        // The removal and fallback selection share the existing atomic document commit.
        store().delete(id,replacement?.id)
        if(currentId==id) {
            currentId=replacement?.id
            if(replacement==null) { stop();return }
            cameraShortcut=if(replacement.camera.enabled) replacement.shortcuts.cameraToggle else 0
            SessionController.selectEditingTarget(replacement,SessionController.targetDisplayId);SessionController.editorClosed()
        }
        refreshProfileLibrary(previous.packageName);refreshControls()
    }
    fun activateBubbleProfile(id:String) {
        check(toolsRequested && !waiting && !editorVisible && SessionController.targetConfirmed &&
            !SessionController.releaseUnverified && SessionController.mode in setOf(SessionMode.PAUSED,SessionMode.PLAYING)) { "Vuelve al juego antes de cambiar el perfil" }
        check(profileRows.any { it.id==id }) { "El perfil no pertenece a este juego" }
        val cursor=if(SessionController.active) SessionController.cursorMode else profileMenuCursor
        startProfile(id,cursor,requireCurrentContext=true)
    }
    fun attach(capture:NativeSessionHost) {
        initialize(capture);service=capture
        // Initializing the native host alone creates no presentation windows.
        // Explicit editing/playing configures the selected display after its release barrier.
    }
    private fun configurePresentation(displayId:Int) {
        if(presentationDisplayId==displayId && editor!=null) return
        val capture=requireNotNull(service)
        hideMonitor();capture.hideCursorForTransition();editor?.close();bubble?.close();controls?.hide();statusVisual?.close()
        val context=capture.overlayContextFor(displayId)
        val settings=BubbleSettingsStore(capture)
        val visuals=TrustedStatusVisualPort {
            bubble?.invalidateTrustedVisual();monitor?.invalidateTrustedVisual();refreshControls()
        };statusVisual=visuals
        editor=NativeEditor(capture,context,capture);bubble=ControllerBubble(capture,context,settings,visuals);controls=MappedControlsOverlay(capture,context)
        resolutionSettings=OverlayDisplayResolutionSettings(displayId,{
            capture.admitResolutionSettingsPresentation(displayId)
            geometry(displayId).third
        }) {
            check(service===capture && toolsRequested && presentationDisplayId==displayId &&
                SessionController.targetDisplayId==displayId &&
                app.getSystemService(DisplayManager::class.java).getDisplay(displayId)?.isValid==true) {
                "La pantalla del juego ya no está disponible; vuelve a abrir la sesión"
            }
        }
        monitor=PerformanceMonitorBubble(capture,context,visuals) { x,y -> bubble?.saveMonitorPosition(x,y) };monitorContext=context
        presentationDisplayId=displayId;activeWindow=null;settledTarget=null
        capture.applyCursorSettings(settings.value)
    }
    fun detach(capture:NativeSessionHost) {
        if(service===capture) { clearContextReturn("detached");runtimeDisplayProfile=null;runtimeProfileSource=null }
        if(service===capture) { operation++; waiting=false; toolsRequested=false;resolutionToolsDisplayId=-1; settledTarget=null;profileKeys=emptyMap();profileRows=emptyList(); hideMonitor();editor?.close(); bubble?.close();controls?.hide();statusVisual?.close(); editor=null; bubble=null;resolutionSettings=null;controls=null;monitor=null;monitorContext=null;statusVisual=null;presentationDisplayId=-1; service=null }
    }
    fun profiles()=store().list().map { wire(ProfileCodec.toJson(it)) }
    fun selected():Any?=store().selected()?.let { wire(ProfileCodec.toJson(it)) }
    fun exportProfileFile(id:String):Pair<String,String> {
        val profile=requireNotNull(store().get(id)) { "Perfil no encontrado" }
        val safeName=profile.name.replace(Regex("[^\\p{L}\\p{N} ._-]"),"_").take(80).ifBlank { "UwU Mouse" }
        return "$safeName.uwumouse.json" to ProfileCodec.encodeDocument(ProfileDocument(listOf(profile),profile.id))
    }
    /** User-selected JSON uses the same transactional importer, without requiring activation. */
    fun importUserProfileFile(document:ProfileDocument):Int {
        requireProfileChangeAllowed()
        check(SessionPolicy.mayInstallConfiguration(SessionController.mode,SessionController.contacts,SessionController.releaseUnverified)) { "Espera a que se liberen los controles" }
        val result=store().importDocument(document,true)
        clearTools();currentId=result.selectedProfileId;refreshProfileLibrary()
        return result.importedProfiles.size
    }
    fun openProfileFilePicker(exportId:String?=null) {
        bubble?.closeMenu()
        SessionController.pause("Archivo de perfil")
        val displayId=presentationDisplayId.takeIf { it>=0 } ?: 0
        val intent=Intent(app,dev.uwumouse.app.ProfileFileActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("exportId",exportId)
        app.startActivity(intent,ActivityOptions.makeBasic().apply { launchDisplayId=displayId }.toBundle())
    }
    fun selectedForDisplay(displayId:Int):Any? {
        val selected=store().selected() ?: return null
        val matching=if(selected.calibration.displayScope==displayScope(displayId)) selected
            else store().list().firstOrNull { it.packageName==selected.packageName && it.calibration.displayScope==displayScope(displayId) } ?: selected
        return wire(ProfileCodec.toJson(matching))
    }
    /** Authorized shell recovery only, on the main control plane and while fully stopped. */
    fun importProfileDocument(document:ProfileDocument,selectImported:Boolean=false):Map<String,Any?> {
        check(Looper.myLooper()==Looper.getMainLooper()) { "La restauración necesita el hilo principal" }
        check(SessionController.mode==SessionMode.STOPPED && !SessionController.active && !SessionController.isTransitioning() &&
            SessionController.contacts==0 && !SessionController.releaseUnverified && !waiting && !startPending &&
            !toolsRequested && !editorVisible && !bubbleVisible && NativeSessionHost.instance?.mouseCaptured!=true) {
            "Detén la sesión y cierra el editor antes de restaurar perfiles"
        }
        val backend=requireNotNull(dev.uwumouse.app.backend.BackendRegistry.injector) { "Activa el motor antes de restaurar perfiles" }
        check(backend.asBinder().isBinderAlive) { "Motor desconectado" }
        val state=backend.status()
        check(state.getInt("possibleContacts",-1)==0 && state.getInt("confirmedContacts",-1)==0 &&
            !state.getBoolean("releaseUnverified",true) && state.getString("recoveryState") in setOf("READY","PAUSED","RECOVERED")) {
            "La secuencia anterior todavía no tiene liberación confirmada"
        }
        val result=store().importDocument(document,selectImported)
        currentId=result.selectedProfileId
        refreshProfileLibrary()
        return mapOf("schemaVersion" to GameProfile.SCHEMA_VERSION,"profileCount" to result.profileCount,
            "selectedProfileId" to result.selectedProfileId,
            "imported" to result.importedProfiles.map { mapOf("id" to it.id,"name" to it.name) })
    }
    /** Compatibility diagnostic: the only selectable camera policy is Smooth. */
    fun setSelectedCameraMode(mode:String):Any? {
        require(mode==CameraSpec.SMOOTH) { "La cámara utiliza Región suave" }
        requireProfileChangeAllowed()
        check(SessionPolicy.mayInstallConfiguration(SessionController.mode,SessionController.contacts,SessionController.releaseUnverified)) {
            "Detén el mapeo y espera la liberación antes de cambiar el modo de cámara"
        }
        val previous=requireNotNull(store().selected()) { "Selecciona un perfil primero" }
        val changed=previous.copy(camera=previous.camera.copy(mode=mode))
        store().save(changed)
        refreshProfileLibrary(changed.packageName)
        return wire(ProfileCodec.toJson(changed))
    }
    fun create(packageName:String,name:String,displayId:Int=0):Any? {
        requireProfileChangeAllowed()
        GameCatalog.requireLaunchable(app,packageName)
        val (width,height,rotation)=geometry(displayId)
        val profile=GameProfile.create(packageName,name,width,height,rotation,displayScope(displayId))
        store().save(profile)
        currentId=profile.id;refreshProfileLibrary(profile.packageName,displayId)
        return wire(ProfileCodec.toJson(profile))
    }
    fun select(id:String):Any? {
        requireProfileChangeAllowed()
        val p=requireNotNull(store().get(id)) { "Perfil no encontrado" }; store().select(id)
        currentId=id;refreshProfileLibrary(p.packageName)
        return wire(ProfileCodec.toJson(p))
    }
    fun rename(id:String,name:String):Any? {
        requireProfileChangeAllowed()
        val p=requireNotNull(store().get(id)).copy(name=name.trim()); store().save(p)
        currentId=id;refreshProfileLibrary(p.packageName)
        return wire(ProfileCodec.toJson(p))
    }
    fun delete(id:String) {
        requireProfileChangeAllowed()
        store().delete(id)
        if(currentId==id) currentId=null
        refreshProfileLibrary()
    }
    private fun geometry(displayId:Int=SessionController.targetDisplayId):Triple<Int,Int,Int> {
        val geometry=dev.uwumouse.app.runtime.TargetDisplayGeometry.read(app,displayId)
        return Triple(geometry.width,geometry.height,geometry.rotation)
    }
    private fun matchingProfile(id:String,displayId:Int):GameProfile? {
        val requested=requireNotNull(store().get(id)) { "Perfil no encontrado" }
        val scope=displayScope(displayId)
        if(requested.calibration.displayScope==scope) return requested
        return store().selected()?.takeIf { it.packageName==requested.packageName && it.calibration.displayScope==scope }
            ?: store().list().firstOrNull { it.packageName==requested.packageName && it.calibration.displayScope==scope }
    }
    /** Called only after the release barrier. The phone's calibrated mapping is never resized. */
    private fun editingProfile(id:String,displayId:Int):GameProfile {
        matchingProfile(id,displayId)?.let { return it }
        val original=requireNotNull(store().get(id))
        val (width,height,rotation)=geometry(displayId)
        val base=original.name.take(65)+(if(displayId==0) " · teléfono" else " · pantalla externa")
        val names=store().list().filter { it.packageName==original.packageName }.map { it.name }.toSet()
        val name=if(base !in names) base else generateSequence(2){it+1}.map { "$base $it" }.first { it !in names }
        val copy=original.copy(id=java.util.UUID.randomUUID().toString(),name=name,switchKey=0,
            calibration=Calibration(width,height,rotation,GameArea(0f,0f,width.toFloat(),height.toFloat()),false,displayScope(displayId)))
        val bounded=EditorDraft(copy,width,height,rotation).value
        store().save(bounded)
        message(if(displayId==0) "Coloca los controles para el teléfono" else "Coloca los controles para la pantalla externa y guarda")
        return bounded
    }
    fun openEditor(id:String,displayId:Int=SessionController.targetDisplayId) {
        clearContextReturn("editor");runtimeDisplayProfile=null;runtimeProfileSource=null
        check(!DisplayResolutionController.changing) { "Espera a que termine el cambio de resolución" }
        check(SessionController.mode!=SessionMode.RECOVERING && SessionController.mode!=SessionMode.BLOCKED && !SessionController.releaseUnverified) { "Espera al cierre confirmado de la secuencia anterior antes de editar" }
        stopServiceWhenClosed=false
        OverlayWindows.requirePermission(app,displayId)
        val requested=requireNotNull(store().get(id)) { "Perfil no encontrado" }
        geometry(displayId)
        startPending=false
        controls?.hide()
        check(service!=null) { "Abre UwU Mouse para preparar la captura nativa" }
        GameCatalog.requireLaunchable(app,requested.packageName)
        val ticket=++operation; waiting=true; toolsRequested=true; currentId=id
        refreshProfileLibrary(requested.packageName,displayId)
        SessionController.pauseThen("Preparando editor; terminando contactos") { safe ->
            if(ticket!=operation) return@pauseThen
            if(!safe) { waiting=false; fail("No se puede editar: liberación pendiente"); return@pauseThen }
            try {
            val p=editingProfile(id,displayId);currentId=p.id
            cameraShortcut=if(p.camera.enabled) p.shortcuts.cameraToggle else 0
            SessionController.selectEditingTarget(p,displayId);configurePresentation(displayId)
            refreshProfileLibrary(p.packageName,displayId)
            store().select(p.id)
            launch(p.packageName)
            awaitTarget(p,ticket) {
                bubble?.hide()
                SessionController.selectEditingTarget(p,displayId)
                editor?.show(p,{ changed ->
                    check(SessionPolicy.mayInstallConfiguration(SessionController.mode,SessionController.contacts,SessionController.releaseUnverified))
                    store().save(changed)
                    refreshProfileLibrary(changed.packageName)
                    recordEditorClose("saved")
                    cameraShortcut=if(changed.camera.enabled) changed.shortcuts.cameraToggle else 0
                    geometryInvalidated=false
                    SessionController.editorClosed()
                    // Save ends in PAUSED. Only the explicit Play button can create a new session.
                    main.post { refreshControls() }
                    message("Perfil guardado. Pulsa Jugar para iniciar")
                },{
                    recordEditorClose("cancelled")
                    SessionController.editorClosed(); refreshControls()
                })
            }
            } catch(failure:Exception) { waiting=false;fail(failure,"No se pudo abrir el editor en esta pantalla","editor") }
        }
    }
    fun editCurrent() { (SessionController.activeProfileId ?: currentId ?: store().selected()?.id)?.let { openEditor(it) } }
    fun startProfile(id:String,cursor:Boolean=true,requireCurrentContext:Boolean=false,displayId:Int=SessionController.targetDisplayId) {
        val returnEpoch=if(automaticReturnRequest) contextReturn.identity?.manualPauseEpoch else null
        if(automaticReturnRequest && (returnEpoch==null || returnEpoch!=SessionController.manualPauseEpoch)) {
            clearContextReturn("manual_pause");return
        }
        if(!automaticReturnRequest) { clearContextReturn("manual_start");runtimeDisplayProfile=null;runtimeProfileSource=null }
        check(!DisplayResolutionController.changing) { "Espera a que termine el cambio de resolución" }
        check(SessionController.mode!=SessionMode.RECOVERING && SessionController.mode!=SessionMode.BLOCKED && !SessionController.releaseUnverified) { "Recupera la secuencia anterior antes de iniciar" }
        stopServiceWhenClosed=false
        OverlayWindows.requirePermission(app,displayId)
        val p=matchingProfile(id,displayId)
        if(p==null || !p.calibration.confirmed) { openEditor(id,displayId);return }
        if(requireCurrentContext) check(displayId==SessionController.targetDisplayId && SessionController.targetConfirmed && service?.currentApplicationPackage==p.packageName) { "Vuelve al juego antes de cambiar el perfil" }
        cameraShortcut=if(p.camera.enabled) p.shortcuts.cameraToggle else 0
        require(p.calibration.confirmed) { "Coloca los controles y guarda en el editor primero" }
        check(service!=null) { "Abre UwU Mouse para preparar la captura nativa" }
        GameCatalog.requireLaunchable(app,p.packageName)
        val ticket=++operation; currentId=p.id; waiting=true; toolsRequested=true
        if(automaticReturnRequest) contextReturn.rebase(ContextReturnGate.Identity(ticket,p.id,p.packageName,displayId,requireNotNull(returnEpoch)))
        refreshProfileLibrary(p.packageName,displayId)
        pendingCursorStart=cursor;startPending=true
        editor?.close(); bubble?.closeMenu();controls?.hide()
        SessionController.pauseThen("Preparando perfil; sin reanudar entradas antiguas") { safe ->
            if(ticket!=operation) return@pauseThen
            if(returnEpoch!=null && returnEpoch!=SessionController.manualPauseEpoch) {
                waiting=false;startPending=false;clearContextReturn("manual_pause");refreshControls();return@pauseThen
            }
            if(!safe) { waiting=false; fail("Recupera la secuencia anterior antes de iniciar"); return@pauseThen }
            try {
            if(requireCurrentContext) {
                service?.refreshContext()
                if(!SessionController.targetConfirmed || service?.currentApplicationPackage!=p.packageName) {
                    waiting=false;fail("Cambio de perfil cancelado al salir del juego");return@pauseThen
                }
            }
            // Select only the context. The compiled snapshot is installed after target geometry settles.
            SessionController.selectEditingTarget(p,displayId); SessionController.editorClosed();configurePresentation(displayId)
            store().select(p.id)
            if(requireCurrentContext) service?.refreshContext() else launch(p.packageName)
            awaitTarget(p,ticket,requireCurrentContext,returnEpoch) {
                val (width,height,rotation)=geometry()
                val window=requireNotNull(service?.boundsForPackage(p.packageName)) { "No se pudo confirmar el área de la ventana" }
                val frame=GameArea(window.left.toFloat(),window.top.toFloat(),window.right.toFloat(),window.bottom.toFloat())
                val runtimeProfile=RuntimeProfileGeometry.adapt(p,width,height,rotation,displayScope(displayId),frame)
                val prepared=ProfileCompiler.prepare(runtimeProfile,width,height,rotation,displayScope(displayId),frame)
                geometryInvalidated=false
                val area=runtimeProfile.calibration.area
                require(area.left>=window.left && area.top>=window.top && area.right<=window.right && area.bottom<=window.bottom) { "El área calibrada ya no cabe en la ventana; recalibra el perfil" }
                activeWindow=Rect(window)
                runtimeDisplayProfile=runtimeProfile;runtimeProfileSource=p
                if(returnEpoch==null) SessionController.armProfile(prepared,pendingCursorStart,displayId)
                else if(!SessionController.tryArmProfileAfterInterruption(prepared,pendingCursorStart,displayId,returnEpoch))
                    clearContextReturn("manual_pause")
                startPending=false
                refreshControls()
            }
            } catch(failure:Exception) { waiting=false;fail(failure,"No se pudo preparar esta pantalla","profile") }
        }
    }
    private fun launch(packageName:String) {
        app.startForegroundService(Intent(app,SessionService::class.java))
        service?.refreshContext()
        // F7 and the paused control already sit above the game. Sending another launcher
        // intent here can invoke the game's onNewIntent and change its current scene.
        if(service?.isPackageForegroundForLaunch(packageName)==true) {
            Log.i("UwUMouse","game_launch_skipped package=$packageName alreadyForeground=true")
            return
        }
        val intent=requireNotNull(app.packageManager.getLaunchIntentForPackage(packageName)) { "Aplicación no disponible" }
        // Resume an existing task in its prior state. SINGLE_TOP explicitly delivers a new
        // intent to the top Activity; that laboratory-specific choice is not a game default.
        // The target's own manifest launch mode still governs its Activity lifecycle.
        intent.flags=Intent.FLAG_ACTIVITY_NEW_TASK
        Log.i("UwUMouse","game_task_requested package=$packageName flags=${intent.flags}")
        val displayId=SessionController.targetDisplayId
        check(app.getSystemService(ActivityManager::class.java).isActivityStartAllowedOnDisplay(app,displayId,intent)) { "Android no permite abrir el juego en esta pantalla" }
        val options=ActivityOptions.makeBasic().apply { launchDisplayId=displayId }
        app.startActivity(intent,options.toBundle())
    }
    private fun awaitTarget(profile:GameProfile,ticket:Long,stayInContext:Boolean=false,
        expectedManualPauseEpoch:Long?=null,ready:()->Unit) {
        val requestedAt=SystemClock.uptimeMillis()
        val deadline=requestedAt+20_000
        var stableSince=0L
        var lastGeometry:Triple<Int,Int,Int>?=null
        var lastWindow:Rect?=null
        val poll=object:Runnable {
            override fun run() {
                if(ticket!=operation) return
                if(expectedManualPauseEpoch!=null && expectedManualPauseEpoch!=SessionController.manualPauseEpoch) {
                    waiting=false;startPending=false;clearContextReturn("manual_pause");refreshControls();return
                }
                val capture=service
                capture?.refreshContext()
                if(stayInContext && (capture?.currentApplicationPackage!=profile.packageName || !SessionController.targetConfirmed)) {
                    waiting=false;fail("Cambio de perfil cancelado al salir del juego");return
                }
                val current=try { geometry() } catch(_:Exception) { waiting=false;displayUnavailable(SessionController.targetDisplayId);return }
                val window=capture?.boundsForPackage(profile.packageName)
                val now=SystemClock.uptimeMillis()
                val snapshot=window?.let { TargetSnapshot(profile.packageName,current.first,current.second,current.third,
                    it.left,it.top,it.right,it.bottom,SessionController.targetDisplayId) }
                if(capture?.currentApplicationPackage==profile.packageName && SessionController.targetConfirmed) {
                    if(lastGeometry!=current || lastWindow!=window) { stableSince=now; lastGeometry=current; lastWindow=window?.let { Rect(it) } }
                    val reused=TargetReadinessPolicy.mayReuse(settledTarget,snapshot,profile.calibration,true,geometryInvalidated)
                    if(snapshot!=null && (reused || now-stableSince>=800)) {
                        waiting=false
                        settledTarget=snapshot
                        lastTargetPreparation=mapOf("packageName" to profile.packageName,"displayId" to SessionController.targetDisplayId,"reusedStableWindow" to reused,
                            "waitMs" to now-requestedAt,"requestedUptimeMs" to requestedAt,"readyUptimeMs" to now)
                        Log.i("UwUMouse","target_ready reused=$reused waitMs=${now-requestedAt} package=${profile.packageName}")
                        try { ready() } catch(e:Exception) { fail(e,"No se pudo aplicar el perfil","target_ready") }
                        return
                    }
                } else { stableSince=0; lastGeometry=null }
                if(now>=deadline) { waiting=false; fail("No se confirmó el juego; vuelve a abrirlo y reintenta"); return }
                main.postDelayed(this,100)
            }
        }
        main.post(poll)
    }
    fun contextChanged(authorized:Boolean,currentWindow:Rect?,editorPresentationAllowed:Boolean=false,
        resolutionPresentationAllowed:Boolean=false) {
        if(authorized && SessionController.active && SessionController.activeProfileId!=null) {
            if(currentWindow==null || activeWindow!=currentWindow) {
                rememberActiveInterruption()
                // Window metadata can disappear or change transiently (popup, system bar,
                // accessibility root refresh). Pause safely without invalidating saved calibration.
                // A fresh return start checks stable bounds and compiles a RAM-only
                // projection. Only the editor commits a replacement calibration.
                SessionController.pause("Ventana del juego en transición")
            }
        }
        if(!authorized) {
            settledTarget=null;activeWindow=null
            presentationRestore?.let { main.removeCallbacks(it) };presentationRestore=null
            if(resolutionPresentationAllowed && resolutionPanelVisible) {
                // The settings overlay/IME may take focus. This retains its form only;
                // target confirmation and all engine/capture gates remain independent.
                hideMonitor();controls?.hide()
                return
            }
            if(editorVisible && editorPresentationAllowed && SessionController.mode==SessionMode.EDITING &&
                !SessionController.active && SessionController.contacts==0 && !SessionController.releaseUnverified) {
                // A verified own full-screen editor can hide the application's accessibility
                // window. Keep its draft only; target confirmation/capture remain disabled.
                // Saving must rediscover the actual APP before any new session can arm.
                bubble?.hide();controls?.hide()
                return
            }
            if(!waiting) {
                if(editorVisible) { recordEditorClose("target_context_lost"); editor?.close(); SessionController.editorClosed() }
                bubble?.hide();controls?.hide()
            }
        } else if(toolsRequested && !waiting && !editorVisible && !bubbleVisible && presentationRestore==null) {
            val ticket=operation;val capture=service
            val restore=Runnable {
                presentationRestore=null
                if(ticket==operation && capture!=null && capture===service && toolsRequested && !waiting && !editorVisible &&
                    SessionController.targetConfirmed && !SessionController.releaseUnverified &&
                    (SessionController.mode in setOf(SessionMode.PAUSED,SessionMode.PLAYING) ||
                        (SessionController.mode==SessionMode.STOPPED && resolutionToolsDisplayId==presentationDisplayId &&
                            presentationDisplayId==SessionController.targetDisplayId))) {
                    // Restore presentation only after a fresh confirmed context. No menu is reopened,
                    // no input is captured and no held action/session is resumed by this callback.
                    refreshControls()
                }
            }
            presentationRestore=restore;main.postDelayed(restore,80)
        }
        tryContextReturn(authorized,currentWindow)
    }
    /** Existing context observations drive closure; losing focus by itself only pauses mapping. */
    internal fun observeTargetLifecycle(observation:GameTaskClosureGate.Observation,now:Long) {
        val requested=if(toolsRequested && SessionController.targetPackage!=SessionController.TARGET)
            GameTaskClosureGate.Identity(operation,SessionController.targetPackage,SessionController.targetDisplayId) else null
        val identity=taskClosureOwner.reconcile(requested,SessionController.targetPackage,SessionController.targetDisplayId,
            SessionController.mode==SessionMode.STOPPED)
        // Closing an absent game remains possible after its display is removed or locked.
        // Readiness, focus and lock checks still independently gate all input admission.
        val mayClose=!waiting && !startPending && !editorVisible &&
            !SessionController.isTransitioning() && SessionController.mode!=SessionMode.STOPPED
        if(taskClosure.observe(identity,observation,now,mayClose)) {
            Log.i("UwUMouse","game_task_closed package=${SessionController.targetPackage} display=${SessionController.targetDisplayId}")
            stop()
        }
    }
    /** Explicit diagnostics only; none of these snapshots run on the physical input path. */
    fun lifecycleDiagnostics():Map<String,Any?> = mapOf(
        "operation" to operation,"toolsRequested" to toolsRequested,"waiting" to waiting,
        "startPending" to startPending,"editorVisible" to editorVisible,
        "transitioning" to SessionController.isTransitioning(),"mode" to SessionController.mode.wire,
        "needsTargetLifecycle" to needsTargetLifecycle,"gate" to taskClosure.diagnostics(),
        "contextReturn" to contextReturn.diagnostics(),"runtimeGeometryAdapted" to
            (runtimeDisplayProfile!=null && runtimeDisplayProfile!==runtimeProfileSource))
    fun refreshControls() {
        if(Looper.myLooper()!=Looper.getMainLooper()) { main.post { refreshControls() }; return }
        if(SessionClosurePolicy.mayStopForegroundService(stopServiceWhenClosed,SessionController.mode,
                SessionController.active,SessionController.isTransitioning(),SessionController.contacts,SessionController.releaseUnverified)) {
            stopServiceWhenClosed=false
            app.stopService(Intent(app,SessionService::class.java))
        }
        // stopSession can synchronously publish changes. Its native release request must
        // finish before any overlay disposal, including a reentrant controls refresh.
        if(closingPresentation) return
        guardPresentation("controls") { refreshControlsOnMain() }
        service?.refreshMenuPointerSources()
    }
    /** Must finish lowering passive windows before the native host shows a stronger cursor. */
    fun refreshPassiveWindowOpacity(displayId:Int):Boolean {
        check(Looper.myLooper()==Looper.getMainLooper())
        if(presentationDisplayId!=displayId) return true
        var refreshed=false
        guardPresentation("opacity") {
            bubble?.refreshWindowOpacity()
            controls?.refreshWindowOpacity()
            monitor?.refreshWindowOpacity()
            refreshed=true
        }
        return refreshed
    }
    private fun refreshControlsOnMain() {
        val retainResolution=resolutionPanelVisible && service?.resolutionSettingsPresentationAllowed==true
        val retainStoppedTools=resolutionToolsDisplayId==presentationDisplayId &&
            presentationDisplayId==SessionController.targetDisplayId && SessionController.targetConfirmed
        if(!toolsRequested || editorVisible || (SessionController.mode==SessionMode.STOPPED && !retainResolution && !retainStoppedTools) || SessionController.targetPackage==SessionController.TARGET || (!SessionController.targetConfirmed && !retainResolution)) { hideMonitor();bubble?.hide();controls?.hide(); return }
        // Keep an existing bubble attached while preparing the same confirmed game. It remains
        // non-interactive until the release barrier and configuration transition finish.
        if(waiting && !bubbleVisible) { controls?.hide();return }
        val id=currentId ?: SessionController.activeProfileId ?: return
        val mode=SessionController.mode
        val recovering=mode==SessionMode.RECOVERING
        val blocked=mode==SessionMode.BLOCKED || SessionController.releaseUnverified
        val canResume=mode==SessionMode.PAUSED && !waiting && !DisplayResolutionController.changing && !menuTextInputActive && SessionController.targetConfirmed && !SessionController.active && !recovering && !blocked && !SessionController.isTransitioning() && SessionController.contacts==0
        val label=when {
            recovering -> "Recuperando inyector"
            blocked -> "Bloqueada"
            waiting -> "Preparando"
            SessionController.cursorMode -> "Cursor del juego"
            SessionController.active -> "Jugando"
            SessionController.isTransitioning() -> "Terminando contactos"
            mode==SessionMode.STOPPED -> "Detenida"
            else -> "Pausada"
        }
        val savedProfile=store().get(id)
        val profile=runtimeDisplayProfile?.takeIf { it.id==id && runtimeProfileSource===savedProfile } ?: savedProfile
        val profileFrame=profile?.calibration?.let { it.windowFrame ?: GameArea(0f,0f,it.screenWidth.toFloat(),it.screenHeight.toFloat()) }
        val currentFrame=service?.boundsForPackage(SessionController.targetPackage)
        val controlsFitCurrentWindow=profileFrame!=null && currentFrame!=null &&
            profileFrame.left==currentFrame.left.toFloat() && profileFrame.top==currentFrame.top.toFloat() &&
            profileFrame.right==currentFrame.right.toFloat() && profileFrame.bottom==currentFrame.bottom.toFloat()
        val presentation=bubble?.settingsSnapshot
        presentation?.let { service?.applyCursorSettings(it) }
        if(presentation?.monitorEnabled==true && SessionController.mode!=SessionMode.STOPPED && SessionController.targetConfirmed && !waiting && !recovering && !blocked) showMonitor(SessionController.targetPackage,SessionController.targetDisplayId)
        else hideMonitor()
        if(!waiting && !DisplayResolutionController.changing && SessionController.targetConfirmed && mode!=SessionMode.STOPPED && profile!=null && presentation?.showControls==true && !geometryInvalidated && controlsFitCurrentWindow && !recovering && !blocked && profile.calibration.confirmed) {
            controls?.show(profile,presentation.controlsOpacity)
            if(bubble?.copyMenuBounds(menuBounds)==true) controls?.avoid(menuBounds) else controls?.avoid(null)
        } else controls?.hide()
        val cursorKey=if(profile?.camera?.enabled==true)
            android.view.KeyEvent.keyCodeToString(profile.shortcuts.cameraToggle).removePrefix("KEYCODE_")
        else profile?.actions?.firstOrNull { it.behavior==ActionSpec.CURSOR_TOGGLE }?.let {
            if(it.triggerKind=="key") android.view.KeyEvent.keyCodeToString(it.code).removePrefix("KEYCODE_")
            else "M${when(it.code){1->1;2->2;4->3;8->4;else->5}}"
        } ?: ""
        bubble?.show(label,profile?.name ?: "Perfil local",{ openControlsMenu() },
            { runCatching { openEditor(id) }.onFailure { message(it.message ?: "Editor no disponible") } },
            { runCatching { startProfile(id) }.onFailure { message(it.message ?: "No se pudo iniciar") } },{ keyboardMode() },{ stop() },
            playing=SessionController.active,editEnabled=!waiting && !DisplayResolutionController.changing && SessionController.targetConfirmed && !recovering && !blocked && !SessionController.isTransitioning(),playEnabled=canResume,
            interactionEnabled=!waiting && !SessionController.isTransitioning() && (!SessionController.active || ((SessionController.cursorMode || service?.mouseCaptured!=true) && SessionController.contacts==0)),cursorHint=cursorKey,
            pointerIntercepted=service?.mouseCaptured==true,onCursorReturn={
                if(toolsRequested && SessionController.targetConfirmed && canResume) runCatching { startProfile(id,true) }.onFailure { message(it.message ?: "No se pudo abrir el cursor") }
            },onVisualSettingsChanged={refreshControls()},profileItems=bubbleProfiles(),
            onCreateProfile={createBubbleProfile(it)},onActivateProfile={activateBubbleProfile(it)},
            onProfileKey={profileId,keyCode -> setBubbleProfileKey(profileId,keyCode)},onDeleteProfile={deleteBubbleProfile(it)},
            onExportProfile={openProfileFilePicker(it)},onImportProfile={openProfileFilePicker()},
            displayResolution=resolutionSettings,onMenuInputFocusChanged={ service?.refreshMenuPointerSources() })
    }
    /** Authorized own-UI diagnostics use exactly the same inline System panel. */
    fun openDisplayResolutionSettings(displayId:Int) {
        check(toolsRequested && presentationDisplayId==displayId && SessionController.targetDisplayId==displayId &&
            bubbleVisible && !waiting && !DisplayResolutionController.changing) { "Abre primero la burbuja del juego en esta pantalla" }
        openControlsMenu("Sistema")
    }
    /** Cancel pending target admissions without removing the user's System/Restore entry. */
    fun beginDisplayResolutionChange() {
        check(Looper.myLooper()==Looper.getMainLooper())
        clearContextReturn("display_resolution");runtimeDisplayProfile=null;runtimeProfileSource=null
        // Keep access to System/Restore after closing the form. This never rearms mapping.
        resolutionToolsDisplayId=presentationDisplayId.takeIf { toolsRequested && resolutionPanelVisible } ?: -1
        operation++;waiting=false;startPending=false;settledTarget=null
        presentationRestore?.let { main.removeCallbacks(it) };presentationRestore=null
        controls?.hide()
        if(editorVisible) {
            recordEditorClose("display_resolution")
            editor?.close()
            SessionController.editorClosed()
        }
    }
    private fun showMonitor(packageName:String,displayId:Int) {
        val current=monitor ?: return
        val context=monitorContext ?: return
        val appearance=bubble?.settingsSnapshot ?: return
        current.updateSettings(appearance.monitorOpacity,appearance.monitorX,appearance.monitorY)
        if(!current.visible) {
            // Rebalance all existing passive windows before admitting another overlapping one.
            OverlayWindows.setMonitorActive(context,true)
            bubble?.refreshWindowOpacity();controls?.refreshWindowOpacity();service?.refreshCursorWindowOpacity()
            current.show(movable=bubbleMenuVisible)
        }
        current.setMovable(bubbleMenuVisible)
        monitorSampler.start(packageName,displayId)
    }
    private fun hideMonitor() {
        monitorSampler.stop()
        val current=monitor ?: return
        if(!current.visible) return
        current.hide()
        monitorContext?.let { OverlayWindows.setMonitorActive(it,false) }
        bubble?.refreshWindowOpacity();controls?.refreshWindowOpacity();service?.refreshCursorWindowOpacity()
    }
    fun openControlsMenu(initialPage:String?=null) {
        if(!toolsRequested || waiting || editorVisible || !SessionController.targetConfirmed) return
        clearContextReturn("menu_opened")
        if(SessionController.mode==SessionMode.RECOVERING || SessionController.mode==SessionMode.BLOCKED || SessionController.releaseUnverified) {
            refreshControls();guardPresentation("menu") { bubble?.openMenu(initialPage) };return
        }
        // A touchable own window can receive a mapped coordinate and make targeted
        // injection fail. Pause before showing it, including camera-toggle work in flight.
        if(SessionController.contacts!=0 || (SessionController.active && !SessionController.cursorMode && service?.mouseCaptured==true)) return
        profileMenuCursor=SessionController.cursorMode
        SessionController.pauseThen("Menú de controles; mapeo pausado") { safe ->
            refreshControls()
            if(safe && toolsRequested && SessionController.targetConfirmed) guardPresentation("menu") { bubble?.openMenu(initialPage) }
        }
    }
    private fun keyboardMode() {
        clearContextReturn("manual_keyboard_mode")
        SessionController.pauseThen("Modo teclado y rat\u00f3n") { safe ->
            refreshControls()
            if(safe && toolsRequested && SessionController.targetConfirmed) guardPresentation("menu") { bubble?.openMenu() }
        }
    }
    fun pauseByUser() {
        clearContextReturn("manual_pause")
        SessionController.pauseByUser()
    }
    fun stop() {
        check(Looper.myLooper()==Looper.getMainLooper())
        clearContextReturn("stopped");runtimeDisplayProfile=null;runtimeProfileSource=null
        taskClosureOwner.clear()
        val wasEditing=editorVisible
        stopServiceWhenClosed=true
        operation++; waiting=false; startPending=false;toolsRequested=false;resolutionToolsDisplayId=-1;settledTarget=null;profileKeys=emptyMap();profileRows=emptyList()
        presentationRestore?.let { main.removeCallbacks(it) };presentationRestore=null
        closingPresentation=true
        try {
            stopSessionThenCleanUp({ SessionController.stopSession() },listOf<()->Unit>(
                { if(wasEditing) recordEditorClose("session_stopped") },
                { hideMonitor() },{ editor?.close() },{ bubble?.hide() },{ controls?.hide() }),
                { failure -> Log.w("UwUMouseWindow","session_stop_visual_cleanup",failure) })
        } finally { closingPresentation=false }
    }
    /** Explicit closure action only: restores the immutable original context, never mapping. */
    fun focusPendingClosure():Boolean {
        check(Looper.myLooper()==Looper.getMainLooper())
        clearContextReturn("explicit_closure")
        val target=SessionController.recoveryTargetPackage ?: return false
        val displayId=SessionController.recoveryTargetDisplayId
        check(displayId==SessionController.targetDisplayId && target==SessionController.targetPackage) {
            "El contexto de recuperación no coincide con la sesión anterior"
        }
        stopServiceWhenClosed=true
        app.startForegroundService(Intent(app,SessionService::class.java))
        SessionController.retryPendingClosure()
        val display=app.getSystemService(DisplayManager::class.java).getDisplay(displayId)
        if(display?.isValid==true) {
            GameCatalog.requireLaunchable(app,target)
            launch(target)
            message("Cerrando la sesión anterior; después pulsa Jugar para iniciar")
        } else {
            // A removed display must never redirect CANCEL or a game task to another one.
            message("Verificando el cierre de la sesión de la pantalla desconectada")
        }
        return true
    }
    fun clearTools() { clearContextReturn("tools_closed");runtimeDisplayProfile=null;runtimeProfileSource=null;taskClosureOwner.clear();operation++; waiting=false; startPending=false;toolsRequested=false;resolutionToolsDisplayId=-1;settledTarget=null;profileKeys=emptyMap();profileRows=emptyList(); hideMonitor();editor?.close(); bubble?.hide();controls?.hide(); currentId=null }
    fun geometryChanged() {
        rememberActiveInterruption();contextReturn.invalidateStability()
        geometryInvalidated=true;settledTarget=null;controls?.hide()
        if(editorVisible) { recordEditorClose("geometry_changed"); editor?.close(); SessionController.editorClosed() }
        if(SessionController.active || SessionController.activeProfileId!=null)
            SessionController.pause("Pantalla en transición; recuperando contexto")
    }
    fun displayUnavailable(displayId:Int) {
        if(displayId!=SessionController.targetDisplayId) return
        clearContextReturn("display_unavailable");runtimeDisplayProfile=null;runtimeProfileSource=null
        operation++;waiting=false;startPending=false;toolsRequested=false;resolutionToolsDisplayId=-1;settledTarget=null;activeWindow=null
        profileKeys=emptyMap();profileRows=emptyList()
        if(editorVisible) recordEditorClose("display_removed")
        hideMonitor();editor?.close();bubble?.hide();controls?.hide();service?.hideCursorForTransition()
        SessionController.pause("Pantalla desconectada; abre el juego para iniciar otra sesión")
        // A display removal is conclusive context loss; do not wait for a later window event
        // or defer global mouse release while a key from the former display remains held.
        SessionController.targetChanged(false)
        service?.captureMouse(false)
        service?.discardDisplayContext(displayId)
        statusVisual?.close();statusVisual=null
        editor=null;bubble=null;resolutionSettings=null;controls=null;monitor=null;monitorContext=null;presentationDisplayId=-1
    }
    /** Only Android window-admission failures close presentation; other defects still propagate. */
    private fun guardPresentation(phase:String,action:()->Unit) {
        try { action() }
        catch(failure:WindowManager.BadTokenException) { presentationUnavailable(failure,phase) }
        catch(failure:WindowManager.InvalidDisplayException) { presentationUnavailable(failure,phase) }
        catch(failure:SecurityException) { presentationUnavailable(failure,phase) }
    }
    private fun fail(failure:Exception,fallback:String,phase:String) {
        when(failure) {
            is WindowManager.BadTokenException -> presentationUnavailable(failure,phase)
            is WindowManager.InvalidDisplayException -> presentationUnavailable(failure,phase)
            is SecurityException -> presentationUnavailable(failure,phase)
            else -> fail(failure.message ?: fallback)
        }
    }
    /** A failed own window must not trigger another addView from pause/presentation callbacks. */
    fun presentationUnavailable(failure:RuntimeException,phase:String="overlay") {
        require(failure is WindowManager.BadTokenException || failure is WindowManager.InvalidDisplayException || failure is SecurityException)
        if(Looper.myLooper()!=Looper.getMainLooper()) { main.post { presentationUnavailable(failure,phase) };return }
        clearContextReturn("presentation_unavailable");runtimeDisplayProfile=null;runtimeProfileSource=null
        val displayId=presentationDisplayId.takeIf { it>=0 } ?: SessionController.targetDisplayId
        val oldEditor=editor;val oldBubble=bubble;val oldControls=controls;val oldMonitor=monitor;val oldVisual=statusVisual
        monitorSampler.stop()
        operation++;waiting=false;startPending=false;toolsRequested=false;resolutionToolsDisplayId=-1;settledTarget=null;activeWindow=null
        profileKeys=emptyMap();profileRows=emptyList()
        presentationRestore?.let { main.removeCallbacks(it) };presentationRestore=null
        // Clear owners before closing: a menu's close callback can synchronously refresh controls.
        editor=null;bubble=null;resolutionSettings=null;controls=null;monitor=null;monitorContext=null;statusVisual=null;presentationDisplayId=-1
        lastPresentationFailure=mapOf("phase" to phase,"displayId" to displayId,
            "type" to failure.javaClass.simpleName,"uptimeMs" to SystemClock.uptimeMillis())
        Log.e("UwUMouseWindow","presentation_unavailable phase=$phase display=$displayId",failure)
        SessionController.pause(if(failure is SecurityException)
            "Permite mostrar UwU Mouse sobre otras aplicaciones y vuelve a abrir el juego"
            else "No se pudo abrir la interfaz en esta pantalla; vuelve a abrir el juego")
        SessionController.targetChanged(false)
        disposeFailedPresentation { oldEditor?.close() }
        disposeFailedPresentation { oldBubble?.close() }
        disposeFailedPresentation { oldControls?.hide() }
        disposeFailedPresentation { oldMonitor?.hide() }
        disposeFailedPresentation { oldVisual?.close() }
        disposeFailedPresentation { service?.hideCursorForTransition() }
        service?.captureMouse(false)
        disposeFailedPresentation { service?.discardDisplayContext(displayId) }
        app.stopService(Intent(app,SessionService::class.java))
        // The paused controller exposes the error in Main. No Toast or automatic window retry.
    }
    private fun disposeFailedPresentation(close:()->Unit) {
        try { close() }
        catch(failure:WindowManager.BadTokenException) { Log.w("UwUMouseWindow","failed_window_cleanup",failure) }
        catch(failure:WindowManager.InvalidDisplayException) { Log.w("UwUMouseWindow","removed_display_cleanup",failure) }
        catch(failure:SecurityException) { Log.w("UwUMouseWindow","overlay_permission_cleanup",failure) }
        catch(failure:IllegalArgumentException) {
            // A rejected addView may leave an unattached View in an older presentation object.
            // Do not suppress other argument errors or normal-path lifecycle defects.
            if(failure.message?.endsWith("not attached to window manager")==true)
                Log.w("UwUMouseWindow","unattached_failed_window_cleanup",failure)
            else throw failure
        }
    }
    private fun fail(reason:String) { startPending=false;SessionController.pause(reason); message(reason) }
    private fun message(text:String) { Toast.makeText(app,text,Toast.LENGTH_LONG).show() }
    private fun wire(value:Any?):Any?=when(value) {
        null,JSONObject.NULL -> null
        is JSONObject -> value.keys().asSequence().associateWith { wire(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { wire(value.get(it)) }
        else -> value
    }
}
