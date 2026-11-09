package dev.uwumouse.app.activation

import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.uwumouse.app.activation.adb.AdbClient
import dev.uwumouse.app.activation.adb.AdbKey
import dev.uwumouse.app.activation.adb.AdbPairingClient
import dev.uwumouse.app.backend.BackendRegistry
import dev.uwumouse.app.game.GameCoordinator
import dev.uwumouse.app.runtime.NativeSessionHost
import dev.uwumouse.app.session.SessionController
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Phone-only ADB authorization. Only a fixed bootstrap is exposed; no general shell API. */
object LocalActivation {
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor { r -> Thread(r,"UwULocalActivation").apply { isDaemon=true } }
    private lateinit var app:Context
    private var key:AdbKey?=null
    private var generation=0L
    private var phase="idle"
    private var message="Activa el motor desde este teléfono"
    private var failureType=""
    private var bootstrapOutput=""
    private var pairPort:Int?=null
    private var connectPort:Int?=null
    // Concurrent cancellation may abort socket IO. Native pairing state is disposed only by its worker.
    private var current:(()->Unit)?=null
    private var discovering=false
    private var discoveryEpoch=0L
    private val listeners=mutableListOf<NsdManager.DiscoveryListener>()
    private val infoCallbacks=mutableMapOf<String,NsdManager.ServiceInfoCallback>()
    private val lost=mutableMapOf<String,Pair<String,Int>>()
    private var deadline:Runnable?=null
    val busy:Boolean @Synchronized get()=phase in setOf("pairing","connecting","starting")
    @Synchronized fun initialize(context:Context) { if(!::app.isInitialized) app=context.applicationContext }
    @Synchronized fun status():Map<String,Any?> {
        if(phase=="ready" && BackendRegistry.injector?.asBinder()?.isBinderAlive!=true) {
            phase="idle";message="Motor apagado · reactiva para jugar"
        }
        return mapOf("phase" to phase,"message" to message,"busy" to busy,
            "localNetworkPermissionRequired" to ActivationNetworkPermission.required(app),
            "localNetworkPermissionGranted" to ActivationNetworkPermission.granted(app),
            "paired" to app.getSharedPreferences("uwu_local_activation",Context.MODE_PRIVATE).getBoolean("paired",false),
            "pairingPort" to pairPort,"connectPort" to connectPort,"host" to "127.0.0.1","failureType" to failureType,
            "bootstrapOutput" to bootstrapOutput)
    }
    private fun idle() {
        ActivationPolicy.requireIdle(SessionController.mode,SessionController.active,
            SessionController.isTransitioning(),SessionController.contacts,SessionController.releaseUnverified,
            GameCoordinator.sessionOpen,GameCoordinator.editorVisible,NativeSessionHost.instance?.mouseCaptured==true)
        BackendRegistry.injector?.takeIf { it.asBinder().isBinderAlive }?.status()?.let { state ->
            check(state.getInt("possibleContacts",-1)==0 && state.getInt("confirmedContacts",-1)==0 &&
                !state.getBoolean("releaseUnverified",true) && state.getString("recoveryState") in setOf("READY","PAUSED","RECOVERED")) {
                "Espera a que el guardián termine de liberar la secuencia anterior"
            }
        }
    }
    fun startDiscovery() {
        check(Looper.myLooper()==Looper.getMainLooper())
        ActivationNetworkPermission.requireGranted(app)
        idle()
        val epoch=synchronized(this) {
            if(busy || discovering) return
            phase="discovering";message="Abre Depuración inalámbrica → Emparejar con código"
            discovering=true
            pairPort=null;connectPort=null;lost.clear();++discoveryEpoch
        }
        try { app.startForegroundService(Intent(app,ActivationService::class.java)) }
        catch(failure:RuntimeException) {
            synchronized(this) {
                if(discoveryEpoch==epoch) {
                    discovering=false;++discoveryEpoch;phase="error"
                    message="Vuelve a UwU Mouse para iniciar la activación"
                }
            }
            notifyChanged()
            throw IllegalStateException("Vuelve a UwU Mouse para iniciar la activación",failure)
        }
        val nsd=app.getSystemService(NsdManager::class.java)
        for(type in listOf("_adb-tls-pairing._tcp.","_adb-tls-connect._tcp.")) {
            val listener=object:NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType:String) = Unit
                override fun onDiscoveryStopped(serviceType:String) = Unit
                override fun onStartDiscoveryFailed(serviceType:String,errorCode:Int) {
                    main.post { if(discoveryCurrent(epoch)) updateHint("No se detectó el puerto; puedes escribirlo manualmente") }
                }
                override fun onStopDiscoveryFailed(serviceType:String,errorCode:Int) = Unit
                override fun onServiceFound(info:NsdServiceInfo) {
                    main.post {
                        if(!discoveryCurrent(epoch)) return@post
                        val serviceKey="$type/${info.serviceName}"
                        if(infoCallbacks.containsKey(serviceKey)) return@post
                        val callback=object:NsdManager.ServiceInfoCallback {
                            override fun onServiceInfoCallbackRegistrationFailed(errorCode:Int) {
                                if(!discoveryCurrent(epoch) || infoCallbacks[serviceKey]!==this) return
                                infoCallbacks.remove(serviceKey);removeEndpoint(serviceKey,epoch)
                                updateHint("No se detectó el puerto; puedes escribirlo manualmente")
                            }
                            override fun onServiceInfoCallbackUnregistered() {
                                if(!discoveryCurrent(epoch) || infoCallbacks[serviceKey]!==this) return
                                infoCallbacks.remove(serviceKey);removeEndpoint(serviceKey,epoch)
                            }
                            override fun onServiceUpdated(serviceInfo:NsdServiceInfo) {
                                if(!serviceInfo.hostAddresses.any(::local) || serviceInfo.port !in 1..65535) return
                                synchronized(this@LocalActivation) {
                                    if(!discoveryCurrent(epoch) || infoCallbacks[serviceKey]!==this) return
                                    lost[serviceKey]=type to serviceInfo.port
                                    if(type.contains("pairing")) pairPort=serviceInfo.port else connectPort=serviceInfo.port
                                }
                                notifyChanged()
                            }
                            override fun onServiceLost() {
                                if(infoCallbacks[serviceKey]===this) removeEndpoint(serviceKey,epoch)
                            }
                        }
                        infoCallbacks[serviceKey]=callback
                        runCatching { nsd.registerServiceInfoCallback(info,app.mainExecutor,callback) }.onFailure {
                            if(infoCallbacks[serviceKey]===callback) infoCallbacks.remove(serviceKey)
                            removeEndpoint(serviceKey,epoch)
                            if(discoveryCurrent(epoch)) updateHint("Escribe el puerto que muestra Android")
                        }
                    }
                }
                override fun onServiceLost(info:NsdServiceInfo) {
                    main.post {
                        if(!discoveryCurrent(epoch)) return@post
                        val serviceKey="$type/${info.serviceName}"
                        infoCallbacks.remove(serviceKey)?.let { callback -> runCatching { nsd.unregisterServiceInfoCallback(callback) } }
                        removeEndpoint(serviceKey,epoch)
                    }
                }
            }
            listeners.add(listener)
            runCatching { nsd.discoverServices(type,NsdManager.PROTOCOL_DNS_SD,listener) }
                .onFailure { updateHint("Escribe el puerto que muestra Android") }
        }
        notifyChanged()
    }
    @Synchronized private fun discoveryCurrent(epoch:Long)=discovering && discoveryEpoch==epoch
    private fun removeEndpoint(serviceKey:String,epoch:Long) {
        synchronized(this) {
            if(!discoveryCurrent(epoch)) return
            lost.remove(serviceKey)
            pairPort=lost.values.lastOrNull { it.first.contains("pairing") }?.second
            connectPort=lost.values.lastOrNull { it.first.contains("connect") }?.second
        }
        notifyChanged()
    }
    private fun local(address:InetAddress):Boolean = address.isLoopbackAddress || runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence().any { iface -> iface.inetAddresses.asSequence().any { it==address } }
    }.getOrDefault(false)
    private fun localHost(host:String?):String {
        val value=host?.trim().takeUnless { it.isNullOrEmpty() } ?: "127.0.0.1"
        require(value.matches(Regex("[0-9a-fA-F:.%]+")) && local(InetAddress.getByName(value))) { "Solo se admite este teléfono" }
        return value
    }
    fun pair(host:String?,port:Int?,code:String) {
        val validatedCode=ActivationPolicy.pairingCode(code)
        val endpoint=localHost(host)
        val endpointPort=ActivationPolicy.port(port ?: synchronized(this) { pairPort } ?: error("Abre Emparejar con código para detectar su puerto"))
        begin("pairing","Emparejando con Android…") { ticket ->
            val identity=identity()
            AdbPairingClient(endpoint,endpointPort,validatedCode,identity).use { client ->
                own(ticket) { client.abort() }
                check(client.start()) { "Android rechazó el código; abre uno nuevo e inténtalo otra vez" }
            }
            valid(ticket)
            check(app.getSharedPreferences("uwu_local_activation",Context.MODE_PRIVATE).edit().putBoolean("paired",true).commit())
            connectAndStart(ticket,null,null)
        }
    }
    fun connect(host:String?,port:Int?) {
        val endpoint=localHost(host)
        port?.let(ActivationPolicy::port)
        begin("connecting","Buscando el motor…") { ticket -> connectAndStart(ticket,endpoint,port) }
    }
    private fun begin(next:String,text:String,run:(Long)->Unit) {
        check(Looper.myLooper()==Looper.getMainLooper())
        ActivationNetworkPermission.requireGranted(app)
        idle()
        val ticket=synchronized(this) {
            check(!busy) { "Espera a que termine la activación" }
            phase=next;message=text;failureType="";bootstrapOutput="";++generation
        }
        deadline=Runnable { fail(ticket,"La activación tardó demasiado. Comprueba Depuración inalámbrica y vuelve a intentar") }.also { main.postDelayed(it,60000) }
        notifyChanged()
        worker.execute {
            try { run(ticket) }
            catch(_:InterruptedException) { /* Cancellation has already published its state. */ }
            catch(failure:Throwable) {
                synchronized(this) {
                    if(ticket==generation) {
                        failureType=failure.javaClass.simpleName
                        // Class/stage/location only: never log a pairing code, certificate, key or raw exception message.
                        val location=failure.stackTrace.firstOrNull { it.className.startsWith("dev.uwumouse.") }
                        android.util.Log.w("UwUActivation","$phase: $failureType at ${location?.fileName}:${location?.lineNumber}")
                    }
                }
                fail(ticket,if(next=="pairing") "No se pudo emparejar o activar. Abre un código nuevo y comprueba los puertos" else "No se pudo activar. Comprueba Depuración inalámbrica; si olvidaste la autorización, empareja de nuevo")
            }
            finally { synchronized(this) { if(ticket==generation) current=null } }
        }
    }
    private fun identity():AdbKey = key ?: AdbKey(app).also { key=it }
    @Synchronized private fun valid(ticket:Long) { if(ticket!=generation) throw InterruptedException() }
    @Synchronized private fun own(ticket:Long,abort:()->Unit) { valid(ticket);current=abort }
    private fun onMain(ticket:Long,run:()->Unit) {
        val task=FutureTask { valid(ticket);run() }
        check(main.post(task));try { task.get(5,TimeUnit.SECONDS) } catch(failure:Exception) { task.cancel(false);throw failure }
    }
    private fun verified():Boolean = runCatching {
        val state=BackendRegistry.injector?.status() ?: return false
        state.getInt("uid")==2000 && state.getInt("protocol")==4 && state.getBoolean("injectPermission") &&
            state.getInt("possibleContacts",-1)==0 && state.getInt("confirmedContacts",-1)==0 &&
            !state.getBoolean("releaseUnverified",true) && state.getString("recoveryState") in setOf("READY","PAUSED","RECOVERED")
    }.getOrDefault(false)
    private fun connectAndStart(ticket:Long,host:String?,port:Int?) {
        valid(ticket)
        BackendRegistry.requestAttach(app)
        repeat(6) { if(verified()) { ready(ticket);return };Thread.sleep(200);valid(ticket) }
        synchronized(this) { valid(ticket);phase="connecting";message="Conectando con Android…" };notifyChanged()
        val until=SystemClock.uptimeMillis()+10000
        var selectedPort=port ?: synchronized(this) { connectPort }
        while(selectedPort==null && SystemClock.uptimeMillis()<until) { Thread.sleep(200);valid(ticket);selectedPort=synchronized(this) { connectPort } }
        selectedPort=selectedPort ?: app.getSharedPreferences("uwu_local_activation",Context.MODE_PRIVATE).getInt("connectPort",0).takeIf { it in 1..65535 }
        val endpointPort=ActivationPolicy.port(selectedPort ?: error("No se detectó el puerto de conexión"))
        AdbClient(host ?: "127.0.0.1",endpointPort,identity()).use { client ->
            own(ticket) { client.abort() };client.connect();valid(ticket)
            onMain(ticket) { idle() }
            synchronized(this) { valid(ticket);phase="starting";message="Iniciando el motor local…" };notifyChanged()
            val output=StringBuilder()
            valid(ticket)
            try {
                client.shellCommand(ActivationPolicy.bootstrapFor(app.packageName)) { bytes ->
                    check(output.length+bytes.size<=16384) { "Respuesta de activación demasiado larga" }
                    output.append(bytes.toString(Charsets.UTF_8))
                }
            } finally {
                // Only the fixed bootstrap's output: it never reads or prints credentials or user input.
                synchronized(this) { if(ticket==generation) bootstrapOutput=output.toString().take(1024) }
            }
            check(output.contains("UWU_START_REQUESTED") || output.contains("UWU_ALREADY_RUNNING")) { "Android no inició el motor" }
        }
        valid(ticket)
        app.getSharedPreferences("uwu_local_activation",Context.MODE_PRIVATE).edit().putInt("connectPort",endpointPort).apply()
        repeat(40) { valid(ticket);BackendRegistry.requestAttach(app);if(verified()) { ready(ticket);return };Thread.sleep(250) }
        error("No se pudo confirmar UID 2000 y el permiso del motor")
    }
    private fun ready(ticket:Long) {
        synchronized(this) { valid(ticket);phase="ready";message="Motor activo · vuelve a UwU Mouse y toca tu juego";current=null }
        main.post {
            if(synchronized(this) { ticket==generation }) {
                deadline?.let(main::removeCallbacks);stopDiscovery();app.stopService(Intent(app,ActivationService::class.java))
            }
        }
    }
    private fun fail(ticket:Long,text:String) {
        val failed=synchronized(this) { if(ticket!=generation) return;generation++;phase="error";message=text;(generation to current).also { current=null } }
        runCatching { failed.second?.invoke() };main.post {
            if(synchronized(this) { generation==failed.first }) deadline?.let(main::removeCallbacks)
            notifyChanged()
        }
    }
    fun cancel() {
        val connection=synchronized(this) { generation++;if(phase!="ready") {phase="idle";message="Activación cancelada"};current.also {current=null} }
        // Abort only socket IO; use{}.close() on the owning worker releases native state.
        if(connection!=null) Thread({ runCatching { connection.invoke() } },"UwUCancelActivation").start()
        deadline?.let(main::removeCallbacks);stopDiscovery();app.stopService(Intent(app,ActivationService::class.java))
    }
    fun serviceStopped() { if(synchronized(this) { discovering || busy }) cancel() }
    private fun stopDiscovery() {
        synchronized(this) { discovering=false;++discoveryEpoch;pairPort=null;connectPort=null;lost.clear() }
        val nsd=app.getSystemService(NsdManager::class.java)
        val callbacks=infoCallbacks.values.toList();infoCallbacks.clear()
        callbacks.forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } }
        listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } };listeners.clear()
    }
    private fun updateHint(text:String) { synchronized(this) { if(!busy) message=text };notifyChanged() }
    private fun notifyChanged() { main.post { ActivationService.instance?.refresh() } }
}
