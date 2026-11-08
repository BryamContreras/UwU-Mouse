package dev.mobimapper.windowcontext

import android.os.Bundle

/** Cached window metadata. Implementations must never perform IPC or IO in snapshot(). */
interface WindowContextProvider : AutoCloseable {
    fun snapshot(): Bundle
    override fun close()
}
