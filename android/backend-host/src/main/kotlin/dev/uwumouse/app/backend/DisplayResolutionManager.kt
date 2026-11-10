package dev.uwumouse.app.backend

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.view.Display
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/** Shell-only display operations. No arbitrary command, density, rotation or projection API. */
internal class DisplayResolutionManager(context:Context,identity:BackendIdentity,private val ownerLost:()->Unit) {
    private val controller=DisplayResolutionController(WindowManagerResolutionAccess(context),
        PrivateDisplayResolutionJournal(File("/data/local/tmp/${identity.guardianProcess}-display-resolution.bin")))
    private data class Owner(val binder:IBinder,val death:IBinder.DeathRecipient)
    private val owners=HashMap<String,Owner>()
    private val pending=controller.ownedRecords().mapTo(LinkedHashSet()) { it.uniqueId }
    @Volatile var hasPendingRestoration=pending.isNotEmpty()
        private set
    @Volatile private var closed=false
    val supported:Boolean get()=controller.supported

    @Synchronized fun status(displayId:Int):Bundle = controller.status(displayId).bundle()

    @Synchronized fun apply(displayId:Int,uniqueId:String,width:Int,height:Int,owner:IBinder):Bundle {
        if(closed) return failed(displayId,"El motor está cerrado")
        if(!owner.isBinderAlive) return failed(displayId,"La aplicación ya no está disponible")
        val old=owners[uniqueId]
        val next:Owner=if(old!=null && old.binder==owner) old else {
            val death=IBinder.DeathRecipient { ownerDied(uniqueId,owner) }
            try { owner.linkToDeath(death,0);Owner(owner,death) }
            catch(_:Exception) { return failed(displayId,"No se pudo verificar la vida de la aplicación") }
        }
        if(!owner.isBinderAlive) {
            if(next!==old) runCatching { next.binder.unlinkToDeath(next.death,0) }
            return failed(displayId,"La aplicación se cerró antes de aplicar la resolución")
        }
        val result=controller.apply(displayId,uniqueId,width,height)
        if(controller.ownedRecords().any { it.uniqueId==uniqueId }) {
            if(old==null || result.operationSucceeded || result.changed) {
                if(next!==old) old?.let { runCatching { it.binder.unlinkToDeath(it.death,0) } }
                owners[uniqueId]=next
            } else if(next!==old) runCatching { next.binder.unlinkToDeath(next.death,0) }
            if(owner.isBinderAlive) pending.remove(uniqueId) else pending.add(uniqueId)
        } else {
            if(next!==old) runCatching { next.binder.unlinkToDeath(next.death,0) }
            forgetOwner(uniqueId)
        }
        hasPendingRestoration=pending.isNotEmpty()
        return result.bundle()
    }

    @Synchronized fun restore(displayId:Int,uniqueId:String):Bundle {
        val result=controller.restore(displayId,uniqueId)
        if(controller.ownedRecords().none { it.uniqueId==uniqueId }) forgetOwner(uniqueId)
        return result.bundle()
    }

    /** Caller holds the supervisor's configuration barrier and has proved input idle. */
    @Synchronized fun restorePending() {
        for(record in controller.ownedRecords().filter { it.uniqueId in pending }) {
            controller.restore(record.displayId,record.uniqueId)
            if(controller.ownedRecords().none { it.uniqueId==record.uniqueId }) forgetOwner(record.uniqueId)
        }
    }

    @Synchronized fun restoreAll() {
        for(record in controller.ownedRecords()) {
            controller.restore(record.displayId,record.uniqueId)
            if(controller.ownedRecords().none { it.uniqueId==record.uniqueId }) forgetOwner(record.uniqueId)
        }
    }

    @Synchronized fun close() {
        closed=true
        owners.values.forEach { runCatching { it.binder.unlinkToDeath(it.death,0) } }
        owners.clear()
    }

    private fun ownerDied(uniqueId:String,binder:IBinder) {
        val notify=synchronized(this) {
            if(closed || owners[uniqueId]?.binder!=binder) false
            else { pending.add(uniqueId);hasPendingRestoration=true;true }
        }
        if(notify) ownerLost()
    }
    private fun forgetOwner(uniqueId:String) {
        owners.remove(uniqueId)?.let { runCatching { it.binder.unlinkToDeath(it.death,0) } }
        pending.remove(uniqueId);hasPendingRestoration=pending.isNotEmpty()
    }
    private fun failed(displayId:Int,message:String)=controller.status(displayId).copy(message=message).bundle()
    private fun DisplayResolutionResult.bundle()=Bundle().apply {
        putBoolean("available",available)
        putInt("displayId",displayId)
        putString("displayUniqueId",snapshot?.uniqueId ?: "")
        putString("displayName",snapshot?.name ?: "")
        putInt("rotation",snapshot?.rotation ?: 0)
        putInt("width",snapshot?.width ?: 0);putInt("height",snapshot?.height ?: 0)
        putInt("physicalWidth",snapshot?.physicalWidth ?: 0);putInt("physicalHeight",snapshot?.physicalHeight ?: 0)
        putBoolean("hasOverride",snapshot?.hasOverride ?: false)
        putBoolean("managed",managed);putBoolean("canRestore",canRestore)
        putBoolean("operationSucceeded",operationSucceeded);putBoolean("changed",changed)
        putString("message",message)
    }
}

/** Reflection metadata is resolved once in shell, and each request verifies its exact display. */
private class WindowManagerResolutionAccess(private val context:Context):DisplayResolutionAccess {
    private data class Api(val service:Any,val initial:java.lang.reflect.Method,val base:java.lang.reflect.Method,
        val set:java.lang.reflect.Method,val clear:java.lang.reflect.Method,val unique:java.lang.reflect.Method)
    private val api=runCatching {
        check(Process.myUid()==2000 && context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")==
            android.content.pm.PackageManager.PERMISSION_GRANTED)
        val type=Class.forName("android.view.IWindowManager")
        val service=requireNotNull(Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null))
        Api(service,type.getMethod("getInitialDisplaySize",Int::class.javaPrimitiveType,Point::class.java),
            type.getMethod("getBaseDisplaySize",Int::class.javaPrimitiveType,Point::class.java),
            type.getMethod("setForcedDisplaySize",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType),
            type.getMethod("clearForcedDisplaySize",Int::class.javaPrimitiveType),Display::class.java.getMethod("getUniqueId"))
    }.getOrNull()
    override val supported:Boolean get()=api!=null

    private fun display(displayId:Int,expected:String?=null):Display {
        require(displayId>=0)
        val current=requireNotNull(context.getSystemService(DisplayManager::class.java).getDisplay(displayId))
        check(current.isValid)
        val unique=requireNotNull(api).unique.invoke(current) as? String
        check(unique!=null && DisplayResolutionController.validIdentity(unique) && (expected==null || unique==expected))
        return current
    }
    override fun read(displayId:Int):DisplayResolutionSnapshot? {
        val methods=api ?: return null
        return runCatching {
            val target=display(displayId)
            val unique=methods.unique.invoke(target) as String
            val original=Point();val current=Point()
            methods.initial.invoke(methods.service,displayId,original)
            methods.base.invoke(methods.service,displayId,current)
            val verified=display(displayId,unique)
            DisplayResolutionSnapshot(displayId,unique,verified.name.take(128),verified.rotation,
                current.x,current.y,original.x,original.y)
        }.getOrNull()
    }
    override fun setSize(displayId:Int,expectedUniqueId:String,width:Int,height:Int) {
        display(displayId,expectedUniqueId)
        val methods=requireNotNull(api)
        methods.set.invoke(methods.service,displayId,width,height)
    }
    override fun clearSize(displayId:Int,expectedUniqueId:String) {
        display(displayId,expectedUniqueId)
        val methods=requireNotNull(api)
        methods.clear.invoke(methods.service,displayId)
    }
}

/** Process-crash recovery journal, atomically replaced as a shell-owned 0600 regular file. */
private class PrivateDisplayResolutionJournal(private val file:File):DisplayResolutionJournal {
    override fun load():List<OwnedDisplayResolution> {
        val descriptor=try { Os.open(file.path,OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,0) }
        catch(error:ErrnoException) { if(error.errno==OsConstants.ENOENT) return emptyList() else throw error }
        return FileInputStream(descriptor).use { stream ->
            validate(Os.fstat(descriptor))
            val input=DataInputStream(stream)
            require(input.readInt()==MAGIC && input.readInt()==1)
            val count=input.readInt();require(count in 0..16)
            val result=List(count) { OwnedDisplayResolution(input.readInt(),input.readUTF(),input.readInt(),
                input.readInt(),input.readBoolean(),input.readInt(),input.readInt()) }
            require(input.read()==-1)
            result
        }
    }
    override fun save(records:List<OwnedDisplayResolution>) {
        require(records.size<=16)
        try { validate(Os.lstat(file.path)) }
        catch(error:ErrnoException) { if(error.errno!=OsConstants.ENOENT) throw error }
        val temporary=File(file.parentFile,".${file.name}.${UUID.randomUUID()}.tmp")
        try {
            val descriptor=Os.open(temporary.path,OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or
                OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,0x180)
            FileOutputStream(descriptor).use { stream ->
                Os.fchmod(descriptor,0x180)
                val output=DataOutputStream(stream)
                output.writeInt(MAGIC);output.writeInt(1);output.writeInt(records.size)
                for(record in records) {
                    output.writeInt(record.displayId);output.writeUTF(record.uniqueId)
                    output.writeInt(record.previousWidth);output.writeInt(record.previousHeight)
                    output.writeBoolean(record.previousHadOverride);output.writeInt(record.ownedWidth);output.writeInt(record.ownedHeight)
                }
                output.flush();stream.fd.sync()
            }
            Os.rename(temporary.path,file.path)
        } finally { runCatching { Os.remove(temporary.path) } }
    }
    private fun validate(stat:android.system.StructStat) {
        require(OsConstants.S_ISREG(stat.st_mode) && stat.st_uid==Process.myUid() && stat.st_nlink==1L &&
            stat.st_mode and 0x1ff==0x180 && stat.st_size in 0..32768L) { "Invalid private display-resolution journal" }
    }
    companion object { private const val MAGIC=0x55575231 }
}
