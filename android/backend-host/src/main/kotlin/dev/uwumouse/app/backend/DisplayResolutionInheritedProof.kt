package dev.uwumouse.app.backend

import java.util.concurrent.TimeUnit

/**
 * An empty journal in a new guardian says nothing about an old injector process.
 * Establish that every untracked producer has exited before querying the dispatcher.
 * A whitelisted current worker is safe only when the caller has closed its own journal
 * and holds the configuration barrier; its PID must be owned by that WorkerConnection.
 */
internal class DisplayResolutionInheritedProof(
    private val producerPids:()->Set<Int>?,
    private val guardianPids:()->Set<Int>?,
    private val dispatcherUntouched:()->Boolean,
    private val currentGuardianPid:Int,
) {
    fun verified(allowedCurrentPid:Int?):Boolean {
        if(currentGuardianPid<=0 || (allowedCurrentPid!=null && allowedCurrentPid<=0)) return false
        fun producersClosed():Boolean {
            val guardians=guardianPids() ?: return false
            if(guardians.size!=1 || currentGuardianPid !in guardians) return false
            val pids=producerPids() ?: return false
            return if(allowedCurrentPid==null) pids.isEmpty()
                else pids.size==1 && allowedCurrentPid in pids
        }
        if(!producersClosed()) return false
        if(!dispatcherUntouched()) return false
        // An unknown producer appearing during the diagnostic cannot be ignored.
        return producersClosed()
    }
}

/** One bounded exact-name process query; neither stdout nor stderr is exposed to the app. */
internal object DisplayResolutionProducerPids {
    fun read(processName:String):Set<Int>? {
        if(!processName.matches(Regex("[A-Za-z0-9_]{1,128}"))) return null
        val process=try { ProcessBuilder("/system/bin/pidof",processName).redirectErrorStream(true).start() }
            catch(_:Exception) { return null }
        return try {
            if(!process.waitFor(500,TimeUnit.MILLISECONDS)) return null
            val bytes=process.inputStream.use { it.readNBytes(MAX_BYTES+1) }
            if(bytes.size>MAX_BYTES || bytes.any { it.toInt() !in 9..13 && it.toInt() !in 32..126 }) return null
            val output=bytes.toString(Charsets.US_ASCII).trim()
            when(process.exitValue()) {
                1 -> if(output.isEmpty()) emptySet() else null
                0 -> {
                    if(!output.matches(Regex("[0-9]+(?:\\s+[0-9]+)*"))) return null
                    val pids=output.split(Regex("\\s+")).map { it.toIntOrNull()?.takeIf { value -> value>0 } ?: return null }
                    pids.toSet().takeIf { it.size==pids.size }
                }
                else -> null
            }
        } catch(_:InterruptedException) {
            Thread.currentThread().interrupt();null
        } catch(_:Exception) { null }
        finally {
            runCatching { process.inputStream.close() }
            runCatching { process.outputStream.close() }
            runCatching { process.errorStream.close() }
            if(process.isAlive) {
                process.destroyForcibly()
                runCatching { process.waitFor(200,TimeUnit.MILLISECONDS) }
            }
        }
    }
    private const val MAX_BYTES=4096
}
