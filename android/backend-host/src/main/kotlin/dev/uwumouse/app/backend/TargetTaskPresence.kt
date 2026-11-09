package dev.uwumouse.app.backend

import android.app.ActivityManager
import android.os.SystemClock

internal data class TaskPresenceSnapshot(val packageName:String,val capturedUptimeMs:Long,
    val valid:Boolean,val present:Boolean)
internal data class TaskIdentity(val packages:Set<String>,val running:Boolean=true)

/** Enumeration is global: minimizing or moving a DeX task to another display keeps it present. */
internal object TargetTaskPresence {
    private val tasksMethod=runCatching {
        Class.forName("android.app.IActivityTaskManager").getMethod("getTasks",
            Int::class.javaPrimitiveType,Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,Int::class.javaPrimitiveType)
    }.getOrNull()
    private var reader:TaskPresenceReader?=null
    private var service:Any?=null

    @Synchronized fun snapshot(taskManager:Any,packageName:String):TaskPresenceSnapshot {
        if(service!==taskManager) {
            service=taskManager
            reader=TaskPresenceReader(SystemClock::uptimeMillis) {
                val method=tasksMethod ?: return@TaskPresenceReader null
                val result=method.invoke(taskManager,TaskPresenceReader.MAX_TASKS,false,false,-1) as? List<*>
                    ?: return@TaskPresenceReader null
                result.map { entry ->
                    val task=entry as? ActivityManager.RunningTaskInfo
                        ?: error("Unexpected task metadata")
                    TaskIdentity(setOfNotNull(task.baseActivity?.packageName,task.topActivity?.packageName,
                        task.baseIntent.component?.packageName,task.baseIntent.`package`),
                        task.isRunning || task.numActivities>0)
                }
            }
        }
        return requireNotNull(reader).snapshot(packageName)
    }
}

/** Reuses at most one second of metadata; incomplete/failed enumeration never proves absence. */
internal class TaskPresenceReader(
    private val clock:()->Long,
    private val query:()->List<TaskIdentity>?,
) {
    private var cached:TaskPresenceSnapshot?=null

    fun snapshot(packageName:String):TaskPresenceSnapshot {
        val now=clock()
        cached?.takeIf { it.packageName==packageName && now-it.capturedUptimeMs in 0 until CACHE_MILLIS }
            ?.let { return it }
        val tasks=runCatching(query).getOrNull()
        // A retained relaunch intent alone does not keep a game session alive after
        // Android explicitly reports no activities and no running task.
        val present=tasks?.any { it.running && packageName in it.packages }==true
        val complete=tasks?.none { it.running && it.packages.isEmpty() }==true
        return TaskPresenceSnapshot(packageName,now,
            tasks!=null && (present || (complete && tasks.size<MAX_TASKS)),present).also { cached=it }
    }

    companion object {
        const val MAX_TASKS=256
        private const val CACHE_MILLIS=1_000L
    }
}
