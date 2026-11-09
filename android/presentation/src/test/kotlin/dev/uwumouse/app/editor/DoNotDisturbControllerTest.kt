package dev.uwumouse.app.editor

import android.app.NotificationManager
import org.junit.Assert.*
import org.junit.Test

class DoNotDisturbControllerTest {
    private class FakeBackend : DoNotDisturbBackend {
        override var hasAccess = false
        var effectiveFilter = NotificationManager.INTERRUPTION_FILTER_ALL
        override val filter: Int get() = effectiveFilter
        var requestedFilter: Int? = null
        var fail = false
        var opened = false
        override fun setFilter(filter: Int) {
            if (fail) throw SecurityException()
            requestedFilter = filter
        }
        override fun openSettings() { opened = true }
        override fun observe(onChanged: () -> Unit) = AutoCloseable {}
    }

    @Test fun requiresPermissionAndOpensSettings() {
        val backend = FakeBackend()
        val controller = DoNotDisturbController(backend)
        assertFalse(controller.setEnabled(true))
        assertNull(backend.requestedFilter)
        assertTrue(controller.requestAccess())
        assertTrue(backend.opened)
    }

    @Test fun sendsRequestsAndReportsEffectiveSystemState() {
        val backend = FakeBackend().apply { hasAccess = true }
        val controller = DoNotDisturbController(backend)
        assertTrue(controller.setEnabled(true))
        assertEquals(NotificationManager.INTERRUPTION_FILTER_PRIORITY, backend.requestedFilter)
        backend.effectiveFilter = NotificationManager.INTERRUPTION_FILTER_PRIORITY
        assertTrue(controller.isEnabled)
        assertTrue(controller.setEnabled(false))
        assertEquals(NotificationManager.INTERRUPTION_FILTER_ALL, backend.requestedFilter)
        // Another Android rule may keep the effective filter active.
        assertTrue(controller.isEnabled)
        backend.effectiveFilter = NotificationManager.INTERRUPTION_FILTER_ALL
        assertFalse(controller.isEnabled)
        backend.effectiveFilter = NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        assertFalse(controller.isEnabled)
    }

    @Test fun reportsSystemFailures() {
        val backend = FakeBackend().apply { hasAccess = true; fail = true }
        assertFalse(DoNotDisturbController(backend).setEnabled(true))
    }
}
