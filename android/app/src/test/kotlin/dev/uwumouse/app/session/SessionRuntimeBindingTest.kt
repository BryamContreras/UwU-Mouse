package dev.uwumouse.app.session

import dev.uwumouse.app.engine.TouchFrame
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class SessionRuntimeBindingTest {
    private fun ports() = SessionRuntimePorts(
        SessionRuntimePorts.UNBOUND.environment,
        SessionRuntimePorts.UNBOUND.events,
    )

    @Test fun uncomposedProcessCannotAdmitInputOrStartCapture() {
        val binding = SessionRuntimeBinding()
        val environment = binding.ports.environment
        assertFalse(environment.available)
        assertFalse(environment.inputDispatchIdle())
        assertNull(environment.injector)
        assertNull(environment.currentApplicationPackage)
        assertFalse(environment.allowsInjection(TouchFrame(0, 1, 1,
            intArrayOf(0), floatArrayOf(1f), floatArrayOf(1f)), false))
        environment.captureMouse(true, 1)
        assertFalse(environment.diagnostics().available)
    }

    @Test fun componentReentryCanReuseOnlyTheSameCompositionIdentity() {
        val binding = SessionRuntimeBinding()
        val first = ports()
        val sameValuesDifferentOwner = ports()
        assertEquals(first, sameValuesDifferentOwner)
        binding.bind(first)
        binding.bind(first)
        assertThrows(IllegalStateException::class.java) { binding.bind(sameValuesDifferentOwner) }
        assertSame(first, binding.ports)
        assertThrows(IllegalArgumentException::class.java) { binding.bind(SessionRuntimePorts.UNBOUND) }
        assertSame(first, binding.ports)
    }

    @Test fun concurrentEntrypointsCannotInstallTwoDifferentOwners() {
        val binding = SessionRuntimeBinding()
        val candidates = listOf(ports(), ports())
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val accepted = AtomicInteger()
        val rejected = AtomicInteger()
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val threads = candidates.map { candidate -> Thread {
            try {
                ready.countDown()
                check(start.await(2, TimeUnit.SECONDS))
                try { binding.bind(candidate); accepted.incrementAndGet() }
                catch (_: IllegalStateException) { rejected.incrementAndGet() }
            } catch (failure: Throwable) { failures.add(failure) }
        }.apply { start() } }
        try {
            assertTrue(ready.await(2, TimeUnit.SECONDS))
        } finally { start.countDown() }
        threads.forEach { it.join(2_000) }
        assertTrue(threads.none { it.isAlive })
        assertTrue(failures.isEmpty())
        assertEquals(1, accepted.get())
        assertEquals(1, rejected.get())
        assertTrue(candidates.any { it === binding.ports })
    }
}
