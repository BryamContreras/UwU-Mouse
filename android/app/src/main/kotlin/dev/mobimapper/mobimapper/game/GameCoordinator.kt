package dev.mobimapper.mobimapper.game

import dev.mobimapper.mobimapper.runtime.NativeSessionHost

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import dev.mobimapper.mobimapper.editor.ControllerBubble
import dev.mobimapper.mobimapper.editor.BubbleSettingsStore
import dev.mobimapper.mobimapper.editor.BubbleSettings
import dev.mobimapper.mobimapper.editor.NativeEditor
import dev.mobimapper.mobimapper.editor.MappedControlsOverlay
import dev.mobimapper.mobimapper.editor.EditorDraft
import dev.mobimapper.mobimapper.editor.OverlayWindows
import dev.mobimapper.mobimapper.profile.*
import dev.mobimapper.mobimapper.session.*
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
    private var controls:MappedControlsOverlay?=null
    private var presentationDisplayId=-1
    private var geometryInvalidated=false
    private val menuBounds=Rect()
    private var presentationRestore:Runnable?=null
    private var operation=0L
    private var currentId:String?=null
    private var waiting=false
    private var toolsRequested=false
    private var cameraShortcut=138
    private var startPending=false
    private var pendingCursorStart=false
    private var activeWindow:Rect?=null
    private var settledTarget:TargetSnapshot?=null
    @Volatile var lastTargetPreparation:Map<String,Any?>?=null; private set
    @Volatile var lastPresentationFailure:Map<String,Any?>?=null; private set
    private var profileRows:List<ProfileMenuItem> = emptyList()
    @Volatile private var profileKeys:Map<Int,String> = emptyMap()
    private var profileMenuCursor=false
    @Volatile var lastEditorClose:Map<String,Any?>?=null; private set
    private fun recordEditorClose(reason:String) {
        val event=mapOf("reason" to reason,"uptimeMs" to SystemClock.uptimeMillis(),
            "profileId" to currentId,"windowContext" to service?.contextWindowDiagnostics())
        lastEditorClose=event
        Log.i("MobiMapperEditor",JSONObject(event).toString())
    }
    private fun requireProfileChangeAllowed() {
        check(!waiting && !SessionController.isTransitioning() && !SessionController.active && !editorVisible) { "Espera a que termine la transición de sesión antes de cambiar el perfil" }
    }
    val editorVisible:Boolean get()=editor?.visible==true
    val bubbleVisible:Boolean get()=bubble?.visible==true
    val bubbleMenuVisible:Boolean get()=bubble?.menuVisible==true
    val controlsVisible:Boolean get()=controls?.visible==true
    val bubbleAppearance:Map<String,Any>? get()=bubble?.appearance()
    val cursorSettings:BubbleSettings get()=bubble?.settingsSnapshot ?: BubbleSettings()
    val ownOverlayVisible:Boolean get()=editorVisible || bubbleVisible || controlsVisible || service?.cursorVisible==true
    val sessionOpen:Boolean get()=toolsRequested
    fun cameraResumeKey():Int? = if(cameraShortcut>0 && toolsRequested && !editorVisible && SessionController.targetConfirmed &&
        !SessionController.releaseUnverified && SessionController.mode==SessionMode.PAUSED &&
        (startPending || (!waiting && !SessionController.isTransitioning())) && SessionController.contacts==0) cameraShortcut else null
    fun resumeFromCameraKey() {
        if(startPending) { pendingCursorStart=!pendingCursorStart; return }
        currentId?.let { runCatching { startProfile(it) }.onFailure { message(it.message ?: "No se pudo iniciar") } }
    }
    fun cursorHitsBubble(x:Float,y:Float):Boolean {
        if(!toolsRequested || !SessionController.cursorMode || SessionController.contacts!=0 || editorVisible || bubbleMenuVisible) return false
        refreshControls()
        return bubble?.containsCursorPoint(x,y)==true
    }
    fun cursorBubbleTouch(action:Int,x:Float,y:Float)=bubble?.dispatchCursorTouch(action,x,y)==true
    val menuPointerAvailable:Boolean get()=toolsRequested && bubbleMenuVisible && !editorVisible &&
        presentationDisplayId==SessionController.targetDisplayId
    fun menuPointerTarget(x:Float,y:Float):LocalPointerTarget =
        if(menuPointerAvailable) bubble?.localPointerTarget(x,y) ?: LocalPointerTarget.NONE else LocalPointerTarget.NONE
    fun menuPointerTouch(target:LocalPointerTarget,action:Int,x:Float,y:Float):Boolean = when(target) {
        LocalPointerTarget.MENU -> bubble?.dispatchMenuCursorTouch(action,x,y)==true
        LocalPointerTarget.BUBBLE -> bubble?.dispatchCursorTouch(action,x,y)==true
        LocalPointerTarget.NONE -> false
    }
    fun menuPointerScroll(x:Float,y:Float,vertical:Float)=bubble?.dispatchMenuCursorScroll(x,y,vertical)==true
    fun menuPointerHover(x:Float,y:Float)=bubble?.dispatchMenuCursorHover(x,y)==true
    fun clearMenuPointerHover() { bubble?.clearMenuCursorHover() }
    @Synchronized fun initialize(context:Context) {
        if(repository==null) { app=context.applicationContext; repository=ProfileStore(app) }
    }
    private fun store():ProfileStore=checkNotNull(repository) { "Abre MobiMapper primero" }
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
        capture.hideCursorForTransition();editor?.close();bubble?.close();controls?.hide()
        val context=capture.overlayContextFor(displayId)
        val settings=BubbleSettingsStore(capture)
        editor=NativeEditor(capture,context,capture);bubble=ControllerBubble(capture,context,settings);controls=MappedControlsOverlay(capture,context)
        presentationDisplayId=displayId;activeWindow=null;settledTarget=null
        capture.applyCursorSettings(settings.value)
    }
    fun detach(capture:NativeSessionHost) {
        if(service===capture) { operation++; waiting=false; toolsRequested=false; settledTarget=null;profileKeys=emptyMap();profileRows=emptyList(); editor?.close(); bubble?.close();controls?.hide(); editor=null; bubble=null;controls=null;presentationDisplayId=-1; service=null }
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
        val intent=Intent(app,dev.mobimapper.mobimapper.ProfileFileActivity::class.java)
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
        val backend=requireNotNull(dev.mobimapper.mobimapper.backend.BackendRegistry.injector) { "Activa el motor antes de restaurar perfiles" }
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
        val display=requireNotNull(app.getSystemService(DisplayManager::class.java).getDisplay(displayId)) { "Pantalla desconectada" }
        check(display.isValid) { "Pantalla desconectada" }
        val point=Point()
        @Suppress("DEPRECATION")
        display.getRealSize(point)
        return Triple(point.x,point.y,display.rotation)
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
        check(SessionController.mode!=SessionMode.RECOVERING && SessionController.mode!=SessionMode.BLOCKED && !SessionController.releaseUnverified) { "Espera al cierre confirmado de la secuencia anterior antes de editar" }
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
        check(SessionController.mode!=SessionMode.RECOVERING && SessionController.mode!=SessionMode.BLOCKED && !SessionController.releaseUnverified) { "Recupera la secuencia anterior antes de iniciar" }
        OverlayWindows.requirePermission(app,displayId)
        val p=matchingProfile(id,displayId)
        if(p==null || !p.calibration.confirmed) { openEditor(id,displayId);return }
        if(requireCurrentContext) check(displayId==SessionController.targetDisplayId && SessionController.targetConfirmed && service?.currentApplicationPackage==p.packageName) { "Vuelve al juego antes de cambiar el perfil" }
        cameraShortcut=if(p.camera.enabled) p.shortcuts.cameraToggle else 0
        require(p.calibration.confirmed) { "Coloca los controles y guarda en el editor primero" }
        check(service!=null) { "Abre UwU Mouse para preparar la captura nativa" }
        GameCatalog.requireLaunchable(app,p.packageName)
        val ticket=++operation; currentId=p.id; waiting=true; toolsRequested=true
        refreshProfileLibrary(p.packageName,displayId)
        pendingCursorStart=cursor;startPending=true
        editor?.close(); bubble?.closeMenu();controls?.hide()
        SessionController.pauseThen("Preparando perfil; sin reanudar entradas antiguas") { safe ->
            if(ticket!=operation) return@pauseThen
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
            awaitTarget(p,ticket,requireCurrentContext) {
                val (width,height,rotation)=geometry()
                val window=requireNotNull(service?.boundsForPackage(p.packageName)) { "No se pudo confirmar el área de la ventana" }
                val frame=GameArea(window.left.toFloat(),window.top.toFloat(),window.right.toFloat(),window.bottom.toFloat())
                if(p.calibration.screenWidth!=width || p.calibration.screenHeight!=height || p.calibration.rotation!=rotation ||
                    (p.calibration.windowFrame!=null && p.calibration.windowFrame!=frame)) {
                    startPending=false;message("Cambió la ventana; ajusta el mapeo y guarda");openEditor(p.id,displayId);return@awaitTarget
                }
                val prepared=ProfileCompiler.prepare(p,width,height,rotation,displayScope(displayId),frame)
                geometryInvalidated=false
                val area=p.calibration.area
                require(area.left>=window.left && area.top>=window.top && area.right<=window.right && area.bottom<=window.bottom) { "El área calibrada ya no cabe en la ventana; recalibra el perfil" }
                activeWindow=Rect(window)
                SessionController.armProfile(prepared,pendingCursorStart,displayId);startPending=false
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
            Log.i("MobiMapper","game_launch_skipped package=$packageName alreadyForeground=true")
            return
        }
        val intent=requireNotNull(app.packageManager.getLaunchIntentForPackage(packageName)) { "Aplicación no disponible" }
        // Resume an existing task in its prior state. SINGLE_TOP explicitly delivers a new
        // intent to the top Activity; that laboratory-specific choice is not a game default.
        // The target's own manifest launch mode still governs its Activity lifecycle.
        intent.flags=Intent.FLAG_ACTIVITY_NEW_TASK
        Log.i("MobiMapper","game_task_requested package=$packageName flags=${intent.flags}")
        val displayId=SessionController.targetDisplayId
        check(app.getSystemService(ActivityManager::class.java).isActivityStartAllowedOnDisplay(app,displayId,intent)) { "Android no permite abrir el juego en esta pantalla" }
        val options=ActivityOptions.makeBasic().apply { launchDisplayId=displayId }
        app.startActivity(intent,options.toBundle())
    }
    private fun awaitTarget(profile:GameProfile,ticket:Long,stayInContext:Boolean=false,ready:()->Unit) {
        val requestedAt=SystemClock.uptimeMillis()
        val deadline=requestedAt+20_000
        var stableSince=0L
        var lastGeometry:Triple<Int,Int,Int>?=null
        var lastWindow:Rect?=null
        val poll=object:Runnable {
            override fun run() {
                if(ticket!=operation) return
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
                        Log.i("MobiMapper","target_ready reused=$reused waitMs=${now-requestedAt} package=${profile.packageName}")
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
    fun contextChanged(authorized:Boolean,currentWindow:Rect?,editorPresentationAllowed:Boolean=false) {
        if(authorized && SessionController.active && SessionController.activeProfileId!=null) {
            if(currentWindow==null || activeWindow!=currentWindow) {
                // Window metadata can disappear or change transiently (popup, system bar,
                // accessibility root refresh). Pause safely without invalidating saved calibration.
                // A new explicit start still checks stable bounds and ProfileCompiler checks
                // screen dimensions/rotation. Only the editor commits a replacement calibration.
                SessionController.pause("Cambió la ventana del juego; verifica el área antes de reanudar")
            }
        }
        if(!authorized) {
            settledTarget=null;activeWindow=null
            presentationRestore?.let { main.removeCallbacks(it) };presentationRestore=null
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
                    SessionController.mode in setOf(SessionMode.PAUSED,SessionMode.PLAYING)) {
                    // Restore presentation only after a fresh confirmed context. No menu is reopened,
                    // no input is captured and no held action/session is resumed by this callback.
                    refreshControls()
                }
            }
            presentationRestore=restore;main.postDelayed(restore,80)
        }
    }
    fun refreshControls() {
        if(Looper.myLooper()!=Looper.getMainLooper()) { main.post { refreshControls() }; return }
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
            refreshed=true
        }
        return refreshed
    }
    private fun refreshControlsOnMain() {
        if(!toolsRequested || editorVisible || SessionController.mode==SessionMode.STOPPED || SessionController.targetPackage==SessionController.TARGET || !SessionController.targetConfirmed) { bubble?.hide();controls?.hide(); return }
        // Keep an existing bubble attached while preparing the same confirmed game. It remains
        // non-interactive until the release barrier and configuration transition finish.
        if(waiting && !bubbleVisible) { controls?.hide();return }
        val id=currentId ?: SessionController.activeProfileId ?: return
        val mode=SessionController.mode
        val recovering=mode==SessionMode.RECOVERING
        val blocked=mode==SessionMode.BLOCKED || SessionController.releaseUnverified
        val canResume=!waiting && !SessionController.active && !recovering && !blocked && !SessionController.isTransitioning() && SessionController.contacts==0
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
        val profile=store().get(id)
        val presentation=bubble?.settingsSnapshot
        presentation?.let { service?.applyCursorSettings(it) }
        if(!waiting && profile!=null && presentation?.showControls==true && !geometryInvalidated && !recovering && !blocked && profile.calibration.confirmed) {
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
            playing=SessionController.active,editEnabled=!waiting && !recovering && !blocked && !SessionController.isTransitioning(),playEnabled=canResume,
            interactionEnabled=!waiting && !SessionController.isTransitioning() && (!SessionController.active || ((SessionController.cursorMode || service?.mouseCaptured!=true) && SessionController.contacts==0)),cursorHint=cursorKey,
            pointerIntercepted=service?.mouseCaptured==true,onCursorReturn={
                if(toolsRequested && SessionController.targetConfirmed && canResume) runCatching { startProfile(id,true) }.onFailure { message(it.message ?: "No se pudo abrir el cursor") }
            },onVisualSettingsChanged={refreshControls()},profileItems=bubbleProfiles(),
            onCreateProfile={createBubbleProfile(it)},onActivateProfile={activateBubbleProfile(it)},
            onProfileKey={profileId,keyCode -> setBubbleProfileKey(profileId,keyCode)},onDeleteProfile={deleteBubbleProfile(it)},
            onExportProfile={openProfileFilePicker(it)},onImportProfile={openProfileFilePicker()})
    }
    fun openControlsMenu() {
        if(!toolsRequested || waiting || editorVisible || !SessionController.targetConfirmed) return
        if(SessionController.mode==SessionMode.RECOVERING || SessionController.mode==SessionMode.BLOCKED || SessionController.releaseUnverified) {
            refreshControls();guardPresentation("menu") { bubble?.openMenu() };return
        }
        // A touchable own window can receive a mapped coordinate and make targeted
        // injection fail. Pause before showing it, including camera-toggle work in flight.
        if(SessionController.contacts!=0 || (SessionController.active && !SessionController.cursorMode && service?.mouseCaptured==true)) return
        profileMenuCursor=SessionController.cursorMode
        SessionController.pauseThen("Menú de controles; mapeo pausado") { safe ->
            refreshControls()
            if(safe && toolsRequested && SessionController.targetConfirmed) guardPresentation("menu") { bubble?.openMenu() }
        }
    }
    private fun keyboardMode() {
        SessionController.pauseThen("Modo teclado y rat\u00f3n") { safe ->
            refreshControls()
            if(safe && toolsRequested && SessionController.targetConfirmed) guardPresentation("menu") { bubble?.openMenu() }
        }
    }
    fun stop() {
        if(editorVisible) recordEditorClose("session_stopped")
        operation++; waiting=false; startPending=false;toolsRequested=false;settledTarget=null;profileKeys=emptyMap();profileRows=emptyList(); editor?.close(); bubble?.hide();controls?.hide()
        SessionController.stopSession()
        app.stopService(Intent(app,SessionService::class.java))
    }
    fun clearTools() { operation++; waiting=false; startPending=false;toolsRequested=false;settledTarget=null;profileKeys=emptyMap();profileRows=emptyList(); editor?.close(); bubble?.hide();controls?.hide(); currentId=null }
    fun geometryChanged() {
        geometryInvalidated=true;settledTarget=null;controls?.hide()
        if(editorVisible) { recordEditorClose("geometry_changed"); editor?.close(); SessionController.editorClosed() }
        if(SessionController.active || SessionController.activeProfileId!=null)
            SessionController.pause("Cambió la pantalla; vuelve a iniciar para verificar el perfil")
    }
    fun displayUnavailable(displayId:Int) {
        if(displayId!=SessionController.targetDisplayId) return
        operation++;waiting=false;startPending=false;toolsRequested=false;settledTarget=null;activeWindow=null
        profileKeys=emptyMap();profileRows=emptyList()
        if(editorVisible) recordEditorClose("display_removed")
        editor?.close();bubble?.hide();controls?.hide();service?.hideCursorForTransition()
        SessionController.pause("Pantalla desconectada; abre el juego para iniciar otra sesión")
        // A display removal is conclusive context loss; do not wait for a later window event
        // or defer global mouse release while a key from the former display remains held.
        SessionController.targetChanged(false)
        service?.captureMouse(false)
        service?.discardDisplayContext(displayId)
        editor=null;bubble=null;controls=null;presentationDisplayId=-1
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
        val displayId=presentationDisplayId.takeIf { it>=0 } ?: SessionController.targetDisplayId
        val oldEditor=editor;val oldBubble=bubble;val oldControls=controls
        operation++;waiting=false;startPending=false;toolsRequested=false;settledTarget=null;activeWindow=null
        profileKeys=emptyMap();profileRows=emptyList()
        presentationRestore?.let { main.removeCallbacks(it) };presentationRestore=null
        // Clear owners before closing: a menu's close callback can synchronously refresh controls.
        editor=null;bubble=null;controls=null;presentationDisplayId=-1
        lastPresentationFailure=mapOf("phase" to phase,"displayId" to displayId,
            "type" to failure.javaClass.simpleName,"uptimeMs" to SystemClock.uptimeMillis())
        Log.e("MobiMapperWindow","presentation_unavailable phase=$phase display=$displayId",failure)
        SessionController.pause(if(failure is SecurityException)
            "Permite mostrar UwU Mouse sobre otras aplicaciones y vuelve a abrir el juego"
            else "No se pudo abrir la interfaz en esta pantalla; vuelve a abrir el juego")
        SessionController.targetChanged(false)
        disposeFailedPresentation { oldEditor?.close() }
        disposeFailedPresentation { oldBubble?.close() }
        disposeFailedPresentation { oldControls?.hide() }
        disposeFailedPresentation { service?.hideCursorForTransition() }
        service?.captureMouse(false)
        disposeFailedPresentation { service?.discardDisplayContext(displayId) }
        app.stopService(Intent(app,SessionService::class.java))
        // The paused controller exposes the error in Main. No Toast or automatic window retry.
    }
    private fun disposeFailedPresentation(close:()->Unit) {
        try { close() }
        catch(failure:WindowManager.BadTokenException) { Log.w("MobiMapperWindow","failed_window_cleanup",failure) }
        catch(failure:WindowManager.InvalidDisplayException) { Log.w("MobiMapperWindow","removed_display_cleanup",failure) }
        catch(failure:SecurityException) { Log.w("MobiMapperWindow","overlay_permission_cleanup",failure) }
        catch(failure:IllegalArgumentException) {
            // A rejected addView may leave an unattached View in an older presentation object.
            // Do not suppress other argument errors or normal-path lifecycle defects.
            if(failure.message?.endsWith("not attached to window manager")==true)
                Log.w("MobiMapperWindow","unattached_failed_window_cleanup",failure)
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
