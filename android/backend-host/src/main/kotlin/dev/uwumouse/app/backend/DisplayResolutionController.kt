package dev.uwumouse.app.backend

/** Natural-orientation WMS sizes; no density, rotation or display-projection mutation. */
internal data class DisplayResolutionSnapshot(
    val displayId:Int,val uniqueId:String,val name:String,val rotation:Int,
    val width:Int,val height:Int,val physicalWidth:Int,val physicalHeight:Int,
) {
    val hasOverride:Boolean get()=width!=physicalWidth || height!=physicalHeight
    fun sameSize(width:Int,height:Int)=this.width==width && this.height==height
}

internal interface DisplayResolutionAccess {
    val supported:Boolean
    fun read(displayId:Int):DisplayResolutionSnapshot?
    fun setSize(displayId:Int,expectedUniqueId:String,width:Int,height:Int)
    fun clearSize(displayId:Int,expectedUniqueId:String)
}

/** The before-image is committed before calling WMS, not after its void reply. */
internal data class OwnedDisplayResolution(
    val displayId:Int,val uniqueId:String,
    val previousWidth:Int,val previousHeight:Int,val previousHadOverride:Boolean,
    val ownedWidth:Int,val ownedHeight:Int,
)

internal interface DisplayResolutionJournal {
    fun load():List<OwnedDisplayResolution>
    fun save(records:List<OwnedDisplayResolution>)
}

internal data class DisplayResolutionResult(
    val displayId:Int,val snapshot:DisplayResolutionSnapshot?=null,
    val available:Boolean=false,val managed:Boolean=false,val canRestore:Boolean=false,
    val message:String="",val operationSucceeded:Boolean=false,val changed:Boolean=false,
)

/**
 * Serialized cold control plane. Its caller excludes new input sessions before mutation.
 * A display ID is never substituted; restore additionally requires the exact unique ID
 * and the size last owned by this controller, so an external change is not reset blindly.
 */
internal class DisplayResolutionController(
    private val access:DisplayResolutionAccess,
    private val journal:DisplayResolutionJournal,
) {
    private val records=LinkedHashMap<String,OwnedDisplayResolution>()
    private var journalFailure=false
    init {
        try {
            val saved=journal.load()
            require(saved.size<=MAX_RECORDS && saved.map { it.uniqueId }.distinct().size==saved.size)
            saved.forEach { require(validRecord(it));records[it.uniqueId]=it }
        } catch(_:Exception) { records.clear();journalFailure=true }
    }
    val supported:Boolean get()=access.supported && !journalFailure
    fun ownedRecords():List<OwnedDisplayResolution> = records.values.toList()

    fun status(displayId:Int):DisplayResolutionResult {
        if(!supported) return unavailable(displayId,if(journalFailure)
            "No se pudo verificar el registro de la resolución anterior" else "Este Android no permite consultar y cambiar la resolución")
        val current=read(displayId) ?: return unavailable(displayId,"Pantalla desconectada o dimensiones no verificables")
        val owned=records[current.uniqueId]
        val matches=owned?.let { current.sameSize(it.ownedWidth,it.ownedHeight) }==true
        return DisplayResolutionResult(displayId,current,true,matches,matches,
            if(owned!=null && !matches) "La resolución cambió fuera de UwU Mouse; no se restaurará automáticamente" else "")
    }

    fun apply(displayId:Int,expectedUniqueId:String,width:Int,height:Int):DisplayResolutionResult {
        val before=status(displayId)
        if(!before.available) return before
        val current=requireNotNull(before.snapshot)
        if(!validIdentity(expectedUniqueId) || current.uniqueId!=expectedUniqueId)
            return before.copy(message="Cambió la identidad de la pantalla; vuelve a seleccionarla")
        if(width !in MIN_SIZE..MAX_SIZE || height !in MIN_SIZE..MAX_SIZE)
            return before.copy(message="La resolución debe estar entre $MIN_SIZE y $MAX_SIZE píxeles por lado")
        if(current.sameSize(width,height)) return before.copy(operationSucceeded=true)
        val existing=records[current.uniqueId]?.takeIf { current.sameSize(it.ownedWidth,it.ownedHeight) }
        if(existing==null && current.uniqueId !in records && records.size>=MAX_RECORDS)
            return before.copy(message="Restaura una resolución anterior antes de configurar otra pantalla")
        val proposed=OwnedDisplayResolution(displayId,current.uniqueId,
            existing?.previousWidth ?: current.width,existing?.previousHeight ?: current.height,
            existing?.previousHadOverride ?: current.hasOverride,width,height)
        val oldRecord=records[current.uniqueId]
        if(!commit(current.uniqueId,proposed)) return before.copy(message="No se pudo guardar la resolución anterior; no se aplicó el cambio")
        val sent=runCatching { access.setSize(displayId,expectedUniqueId,width,height) }.isSuccess
        val after=read(displayId)
        if(after!=null && after.uniqueId==expectedUniqueId && after.sameSize(width,height) && sent)
            return status(displayId).copy(operationSucceeded=true,changed=true)

        // WMS may clamp silently, return without changing anything, or throw after a change.
        // Observe and journal that actual size before attempting a compare-and-restore.
        if(after!=null && after.uniqueId==expectedUniqueId) {
            if(after.sameSize(current.width,current.height)) {
                commit(current.uniqueId,oldRecord)
            } else {
                val observed=proposed.copy(ownedWidth=after.width,ownedHeight=after.height)
                if(commit(current.uniqueId,observed)) {
                    val stillOwned=read(displayId)?.let { it.uniqueId==expectedUniqueId &&
                        it.sameSize(observed.ownedWidth,observed.ownedHeight) }==true
                    if(stillOwned) {
                        runCatching {
                            if(current.hasOverride) access.setSize(displayId,expectedUniqueId,current.width,current.height)
                            else access.clearSize(displayId,expectedUniqueId)
                        }
                        val rollback=read(displayId)
                        if(rollback!=null && rollback.uniqueId==expectedUniqueId &&
                            rollback.sameSize(current.width,current.height)) commit(current.uniqueId,oldRecord)
                    }
                }
            }
        }
        val result=status(displayId)
        return result.copy(operationSucceeded=false,changed=result.snapshot?.let {
            it.uniqueId==current.uniqueId && !it.sameSize(current.width,current.height)
        } ?: false,message="Android no confirmó la resolución solicitada; se intentó recuperar la configuración anterior")
    }

    fun restore(displayId:Int,expectedUniqueId:String):DisplayResolutionResult {
        val before=status(displayId)
        if(!before.available) return before
        val current=requireNotNull(before.snapshot)
        if(!validIdentity(expectedUniqueId) || current.uniqueId!=expectedUniqueId)
            return before.copy(message="Cambió la identidad de la pantalla; no se modificó otra pantalla")
        val owned=records[expectedUniqueId]
            ?: return before.copy(message="UwU Mouse no tiene una resolución anterior guardada para esta pantalla")
        if(!current.sameSize(owned.ownedWidth,owned.ownedHeight)) {
            // Another actor owns the current value. Drop our obsolete lease, preserving it.
            if(!commit(expectedUniqueId,null)) return before.copy(message="No se pudo retirar el registro anterior")
            return status(displayId).copy(message="La resolución fue cambiada fuera de UwU Mouse; se conservó ese cambio")
        }
        // A previous restoration may have reached its destination while journal cleanup
        // failed. Retry only that cleanup, not another WMS mutation.
        if(isRestored(current,owned)) return completeRestoration(displayId,owned,current,changed=false)
        runCatching {
            if(owned.previousHadOverride) access.setSize(displayId,expectedUniqueId,owned.previousWidth,owned.previousHeight)
            else access.clearSize(displayId,expectedUniqueId)
        }
        val after=read(displayId)
        // A Binder reply can fail after WMS already restored the exact before-image.
        if(after!=null && after.uniqueId==expectedUniqueId && isRestored(after,owned))
            return completeRestoration(displayId,owned,after,changed=true)

        var durable=true
        if(after!=null && after.uniqueId==expectedUniqueId && !after.sameSize(current.width,current.height)) {
            // An unsuccessful restore can itself clamp or partially mutate the size.
            // Recheck our last observation before attempting one bounded rollback.
            val stillOurs=read(displayId)?.let { it.uniqueId==expectedUniqueId &&
                it.sameSize(after.width,after.height) }==true
            if(stillOurs) {
                runCatching {
                    if(current.hasOverride) access.setSize(displayId,expectedUniqueId,current.width,current.height)
                    else access.clearSize(displayId,expectedUniqueId)
                }
                val rollback=read(displayId)
                if(rollback!=null && rollback.uniqueId==expectedUniqueId) {
                    if(isRestored(rollback,owned))
                        return completeRestoration(displayId,owned,rollback,changed=true)
                    if(!rollback.sameSize(current.width,current.height)) {
                        // Preserve the original before-image while owning only the size
                        // actually left by our failed rollback, never the requested one.
                        val observed=owned.copy(ownedWidth=rollback.width,ownedHeight=rollback.height)
                        durable=commit(expectedUniqueId,observed)
                        if(!durable) records[expectedUniqueId]=observed
                    }
                }
            }
        }
        val result=status(displayId)
        return result.copy(changed=result.snapshot?.let { it.uniqueId==expectedUniqueId &&
            !it.sameSize(current.width,current.height) } ?: false,
            message=if(durable) "Android no confirmó la restauración; se conserva la configuración anterior para reintentar"
                else "No se pudo guardar el estado de recuperación; reintenta Restaurar antes de cerrar el motor")
    }

    private fun isRestored(current:DisplayResolutionSnapshot,owned:OwnedDisplayResolution):Boolean =
        if(owned.previousHadOverride) current.sameSize(owned.previousWidth,owned.previousHeight) else !current.hasOverride

    private fun completeRestoration(displayId:Int,owned:OwnedDisplayResolution,current:DisplayResolutionSnapshot,
                                    changed:Boolean):DisplayResolutionResult {
        if(!commit(owned.uniqueId,null)) {
            // Keep an in-process retry usable even when removing the durable record fails.
            // Its next restore recognizes the confirmed baseline and retries cleanup only.
            records[owned.uniqueId]=owned.copy(ownedWidth=current.width,ownedHeight=current.height)
            return status(displayId).copy(changed=changed,
                message="La resolución se restauró, pero no se pudo actualizar su registro; reintenta Restaurar")
        }
        return status(displayId).copy(operationSucceeded=true,changed=changed)
    }

    private fun read(displayId:Int):DisplayResolutionSnapshot? = if(displayId<0) null else runCatching { access.read(displayId) }
        .getOrNull()?.takeIf { it.displayId==displayId && validIdentity(it.uniqueId) && it.rotation in 0..3 &&
            it.width in 1..MAX_RECORDED_SIZE && it.height in 1..MAX_RECORDED_SIZE &&
            it.physicalWidth in 1..MAX_RECORDED_SIZE && it.physicalHeight in 1..MAX_RECORDED_SIZE }

    private fun commit(uniqueId:String,value:OwnedDisplayResolution?):Boolean {
        val next=LinkedHashMap(records)
        if(value==null) next.remove(uniqueId) else next[uniqueId]=value
        return try { journal.save(next.values.toList());records.clear();records.putAll(next);true }
        catch(_:Exception) { false }
    }

    private fun unavailable(id:Int,message:String)=DisplayResolutionResult(id,message=message)
    companion object {
        const val MIN_SIZE=500
        const val MAX_SIZE=8192
        private const val MAX_RECORDED_SIZE=32768
        private const val MAX_RECORDS=16
        fun validIdentity(value:String)=value.length in 1..512 && value.none { it=='\u0000' || it=='\n' || it=='\r' }
        private fun validRecord(value:OwnedDisplayResolution)=value.displayId>=0 && validIdentity(value.uniqueId) &&
            value.previousWidth in 1..MAX_RECORDED_SIZE && value.previousHeight in 1..MAX_RECORDED_SIZE &&
            value.ownedWidth in 1..MAX_RECORDED_SIZE && value.ownedHeight in 1..MAX_RECORDED_SIZE
    }
}

/** An empty but still armed journal must not be resized before its explicit release. */
internal object DisplayResolutionAdmission {
    fun mayChange(streamClosed:Boolean,possibleContacts:Int,confirmedContacts:Int,releaseUnverified:Boolean,
        forwardedKeys:Int,keyReleaseUnverified:Boolean,captureActive:Boolean,recovering:Boolean):Boolean =
        streamClosed && possibleContacts==0 && confirmedContacts==0 && !releaseUnverified &&
            forwardedKeys==0 && !keyReleaseUnverified && !captureActive && !recovering
}
