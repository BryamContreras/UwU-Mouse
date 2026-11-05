package dev.mobimapper.mobimapper.game

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import dev.mobimapper.mobimapper.editor.ControllerBubble
import dev.mobimapper.mobimapper.editor.NativeEditor
import dev.mobimapper.mobimapper.editor.MappedControlsOverlay
import dev.mobimapper.mobimapper.profile.*
import dev.mobimapper.mobimapper.session.*
import org.json.JSONArray
import org.json.JSONObject

/** Control plane only. Profiles are compiled once before arm; this object never sees input samples. */
object GameCoordinator {
    private val main=Handler(Looper.getMainLooper())
    private lateinit var app:Context
    private var repository:ProfileStore?=null
    private var service:CaptureService?=null
    private var editor:NativeEditor?=null
    private var bubble:ControllerBubble?=null
    private var controls:MappedControlsOverlay?=null
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
    @Synchronized fun initialize(context:Context) {
        if(repository==null) { app=context.applicationContext; repository=ProfileStore(app) }
    }
    private fun store():ProfileStore=checkNotNull(repository) { "Abre MobiMapper primero" }
    /** Read the repository only on explicit control-plane changes, never while processing a key. */
    private fun refreshProfileLibrary(packageName:String?=currentId?.let { store().get(it)?.packageName }) {
        val game=packageName ?: store().selected()?.packageName
        val profiles=store().list().filter { it.packageName==game }
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
        SessionController.selectEditingTarget(copy);SessionController.editorClosed()
        service?.refreshContext();refreshProfileLibrary(copy.packageName);refreshControls()
    }
    fun setBubbleProfileKey(id:String,keyCode:Int) {
        requireBubbleProfileChange()
        val previous=requireNotNull(store().get(id)) { "Perfil no encontrado" }
        check(profileRows.any { it.id==id }) { "El perfil no pertenece a este juego" }
        store().update(previous.copy(switchKey=keyCode))
        refreshProfileLibrary(previous.packageName);refreshControls()
    }
    fun activateBubbleProfile(id:String) {
        check(toolsRequested && !waiting && !editorVisible && SessionController.targetConfirmed &&
            !SessionController.releaseUnverified && SessionController.mode in setOf(SessionMode.PAUSED,SessionMode.PLAYING)) { "Vuelve al juego antes de cambiar el perfil" }
        check(profileRows.any { it.id==id }) { "El perfil no pertenece a este juego" }
        val cursor=if(SessionController.active) SessionController.cursorMode else profileMenuCursor
        startProfile(id,cursor,requireCurrentContext=true)
    }
    fun attach(capture:CaptureService) {
        initialize(capture); service=capture; editor=NativeEditor(capture); bubble=ControllerBubble(capture);controls=MappedControlsOverlay(capture)
    }
    fun detach(capture:CaptureService) {
        if(service===capture) { operation++; waiting=false; toolsRequested=false; profileKeys=emptyMap();profileRows=emptyList(); editor?.close(); bubble?.close();controls?.hide(); editor=null; bubble=null;controls=null; service=null }
    }
    fun profiles()=store().list().map { wire(ProfileCodec.toJson(it)) }
    fun selected():Any?=store().selected()?.let { wire(ProfileCodec.toJson(it)) }
    /** Explicit control-plane change only; the next arm compiles the persisted mode. */
    fun setSelectedCameraMode(mode:String):Any? {
        require(CameraSpec.isSupportedMode(mode)) { "Modo de cámara no válido" }
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
    fun create(packageName:String,name:String):Any? {
        requireProfileChangeAllowed()
        GameCatalog.requireLaunchable(app,packageName)
        val (width,height,rotation)=geometry()
        val profile=GameProfile.create(packageName,name,width,height,rotation)
        store().save(profile)
        currentId=profile.id;refreshProfileLibrary(profile.packageName)
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
    private fun geometry():Triple<Int,Int,Int> {
        val display=app.getSystemService(DisplayManager::class.java).getDisplay(0)
        val point=Point()
        @Suppress("DEPRECATION")
        display.getRealSize(point)
        return Triple(point.x,point.y,display.rotation)
    }
    fun openEditor(id:String) {
        check(SessionController.mode!=SessionMode.RECOVERING && SessionController.mode!=SessionMode.BLOCKED && !SessionController.releaseUnverified) { "Espera al cierre confirmado de la secuencia anterior antes de editar" }
        val p=requireNotNull(store().get(id)) { "Perfil no encontrado" }
        cameraShortcut=if(p.camera.enabled) p.shortcuts.cameraToggle else 0;startPending=false
        controls?.hide()
        check(service!=null) { "Activa la accesibilidad de MobiMapper" }
        GameCatalog.requireLaunchable(app,p.packageName)
        val ticket=++operation; waiting=true; toolsRequested=true; currentId=id
        refreshProfileLibrary(p.packageName)
        SessionController.pauseThen("Preparando editor; terminando contactos") { safe ->
            if(ticket!=operation) return@pauseThen
            if(!safe) { waiting=false; fail("No se puede editar: liberación pendiente"); return@pauseThen }
            SessionController.selectEditingTarget(p)
            store().select(id)
            launch(p.packageName)
            awaitTarget(p,ticket) {
                bubble?.hide()
                SessionController.selectEditingTarget(p)
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
        }
    }
    fun editCurrent() { (SessionController.activeProfileId ?: currentId ?: store().selected()?.id)?.let { openEditor(it) } }
    fun startProfile(id:String,cursor:Boolean=false,requireCurrentContext:Boolean=false) {
        check(SessionController.mode!=SessionMode.RECOVERING && SessionController.mode!=SessionMode.BLOCKED && !SessionController.releaseUnverified) { "Recupera la secuencia anterior antes de iniciar" }
        val p=requireNotNull(store().get(id)) { "Perfil no encontrado" }
        if(requireCurrentContext) check(SessionController.targetConfirmed && service?.currentApplicationPackage==p.packageName) { "Vuelve al juego antes de cambiar el perfil" }
        cameraShortcut=if(p.camera.enabled) p.shortcuts.cameraToggle else 0
        require(p.calibration.confirmed) { "Coloca los controles y guarda en el editor primero" }
        check(service!=null) { "Activa la accesibilidad de MobiMapper" }
        GameCatalog.requireLaunchable(app,p.packageName)
        val ticket=++operation; currentId=id; waiting=true; toolsRequested=true
        refreshProfileLibrary(p.packageName)
        pendingCursorStart=cursor;startPending=true
        editor?.close(); bubble?.hide();controls?.hide()
        SessionController.pauseThen("Preparando perfil; sin reanudar entradas antiguas") { safe ->
            if(ticket!=operation) return@pauseThen
            if(!safe) { waiting=false; fail("Recupera la secuencia anterior antes de iniciar"); return@pauseThen }
            if(requireCurrentContext) {
                service?.refreshContext()
                if(!SessionController.targetConfirmed || service?.currentApplicationPackage!=p.packageName) {
                    waiting=false;fail("Cambio de perfil cancelado al salir del juego");return@pauseThen
                }
            }
            // Select only the context. The compiled snapshot is installed after target geometry settles.
            SessionController.selectEditingTarget(p); SessionController.editorClosed()
            store().select(id)
            if(requireCurrentContext) service?.refreshContext() else launch(p.packageName)
            awaitTarget(p,ticket,requireCurrentContext) {
                val (width,height,rotation)=geometry()
                val prepared=ProfileCompiler.prepare(p,width,height,rotation)
                geometryInvalidated=false
                val window=requireNotNull(service?.boundsForPackage(p.packageName)) { "No se pudo confirmar el área de la ventana" }
                val area=p.calibration.area
                require(area.left>=window.left && area.top>=window.top && area.right<=window.right && area.bottom<=window.bottom) { "El área calibrada ya no cabe en la ventana; recalibra el perfil" }
                activeWindow=Rect(window)
                SessionController.armProfile(prepared,pendingCursorStart);startPending=false
                bubble?.hide()
            }
        }
    }
    private fun launch(packageName:String) {
        app.startForegroundService(Intent(app,SessionService::class.java))
        service?.refreshContext()
        // F7 and the paused control already sit above the game. Sending another launcher
        // intent here can invoke the game's onNewIntent and change its current scene.
        if(service?.currentApplicationPackage==packageName || service?.isPackageForegroundForLaunch(packageName)==true) {
            Log.i("MobiMapper","game_launch_skipped package=$packageName alreadyForeground=true")
            return
        }
        val intent=requireNotNull(app.packageManager.getLaunchIntentForPackage(packageName)) { "Aplicación no disponible" }
        // Resume an existing task in its prior state. SINGLE_TOP explicitly delivers a new
        // intent to the top Activity; that laboratory-specific choice is not a game default.
        // The target's own manifest launch mode still governs its Activity lifecycle.
        intent.flags=Intent.FLAG_ACTIVITY_NEW_TASK
        Log.i("MobiMapper","game_task_requested package=$packageName flags=${intent.flags}")
        app.startActivity(intent)
    }
    private fun awaitTarget(profile:GameProfile,ticket:Long,stayInContext:Boolean=false,ready:()->Unit) {
        val deadline=SystemClock.uptimeMillis()+20_000
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
                val current=geometry()
                val window=capture?.boundsForPackage(profile.packageName)
                val now=SystemClock.uptimeMillis()
                if(capture?.currentApplicationPackage==profile.packageName && SessionController.targetConfirmed) {
                    if(lastGeometry!=current || lastWindow!=window) { stableSince=now; lastGeometry=current; lastWindow=window?.let { Rect(it) } }
                    if(now-stableSince>=800) {
                        waiting=false
                        try { ready() } catch(e:Exception) { fail(e.message ?: "No se pudo aplicar el perfil") }
                        return
                    }
                } else { stableSince=0; lastGeometry=null }
                if(now>=deadline) { waiting=false; fail("No se confirmó el juego; vuelve a abrirlo y reintenta"); return }
                main.postDelayed(this,100)
            }
        }
        main.post(poll)
    }
    fun contextChanged(authorized:Boolean,currentWindow:Rect?) {
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
            presentationRestore?.let { main.removeCallbacks(it) };presentationRestore=null
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
        if(!toolsRequested || waiting || editorVisible || SessionController.mode==SessionMode.STOPPED || SessionController.targetPackage==SessionController.TARGET || !SessionController.targetConfirmed) { bubble?.hide();controls?.hide(); return }
        val id=currentId ?: SessionController.activeProfileId ?: return
        val mode=SessionController.mode
        val recovering=mode==SessionMode.RECOVERING
        val blocked=mode==SessionMode.BLOCKED || SessionController.releaseUnverified
        val canResume=!SessionController.active && !recovering && !blocked && !SessionController.isTransitioning() && SessionController.contacts==0
        val label=when {
            recovering -> "Recuperando inyector"
            blocked -> "Bloqueada"
            SessionController.cursorMode -> "Cursor del juego"
            SessionController.active -> "Jugando"
            SessionController.isTransitioning() -> "Terminando contactos"
            mode==SessionMode.STOPPED -> "Detenida"
            else -> "Pausada"
        }
        val profile=store().get(id)
        val presentation=bubble?.settingsSnapshot
        if(profile!=null && presentation?.showControls==true && !geometryInvalidated && !recovering && !blocked && profile.calibration.confirmed) {
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
            playing=SessionController.active,editEnabled=!recovering && !blocked && !SessionController.isTransitioning(),playEnabled=canResume,
            interactionEnabled=!SessionController.active || ((SessionController.cursorMode || service?.mouseCaptured!=true) && SessionController.contacts==0),cursorHint=cursorKey,
            pointerIntercepted=service?.mouseCaptured==true,onCursorReturn={
                if(toolsRequested && SessionController.targetConfirmed && canResume) runCatching { startProfile(id,true) }.onFailure { message(it.message ?: "No se pudo abrir el cursor") }
            },onVisualSettingsChanged={refreshControls()},profileItems=bubbleProfiles(),
            onCreateProfile={createBubbleProfile(it)},onActivateProfile={activateBubbleProfile(it)},
            onProfileKey={profileId,keyCode -> setBubbleProfileKey(profileId,keyCode)})
    }
    private fun openControlsMenu() {
        if(!toolsRequested || waiting || editorVisible || !SessionController.targetConfirmed) return
        if(SessionController.mode==SessionMode.RECOVERING || SessionController.mode==SessionMode.BLOCKED || SessionController.releaseUnverified) {
            refreshControls(); bubble?.openMenu(); return
        }
        // A touchable own window can receive a mapped coordinate and make targeted
        // injection fail. Pause before showing it, including camera-toggle work in flight.
        if(SessionController.contacts!=0 || (SessionController.active && !SessionController.cursorMode && service?.mouseCaptured==true)) return
        profileMenuCursor=SessionController.cursorMode
        SessionController.pauseThen("Menú de controles; mapeo pausado") { safe ->
            refreshControls()
            if(safe && toolsRequested && SessionController.targetConfirmed) bubble?.openMenu()
        }
    }
    private fun keyboardMode() {
        SessionController.pauseThen("Modo teclado y rat\u00f3n") { safe ->
            refreshControls()
            if(safe && toolsRequested && SessionController.targetConfirmed) bubble?.openMenu()
        }
    }
    fun stop() {
        if(editorVisible) recordEditorClose("session_stopped")
        operation++; waiting=false; startPending=false;toolsRequested=false;profileKeys=emptyMap();profileRows=emptyList(); editor?.close(); bubble?.hide();controls?.hide()
        SessionController.stopSession()
        app.stopService(Intent(app,SessionService::class.java))
    }
    fun clearTools() { operation++; waiting=false; startPending=false;toolsRequested=false;profileKeys=emptyMap();profileRows=emptyList(); editor?.close(); bubble?.hide();controls?.hide(); currentId=null }
    fun geometryChanged() {
        geometryInvalidated=true;controls?.hide()
        if(editorVisible) { recordEditorClose("geometry_changed"); editor?.close(); SessionController.editorClosed() }
        if(SessionController.active || SessionController.activeProfileId!=null) SessionController.pause("Geometría cambiada; recalibra el perfil")
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
