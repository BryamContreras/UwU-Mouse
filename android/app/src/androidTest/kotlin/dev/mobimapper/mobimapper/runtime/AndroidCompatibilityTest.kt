package dev.mobimapper.mobimapper.runtime

import android.Manifest
import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Build
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.hardware.input.InputManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mobimapper.mobimapper.MainActivity
import dev.mobimapper.mobimapper.activation.ActivationNetworkPermission
import dev.mobimapper.mobimapper.backend.BackendRegistry
import dev.mobimapper.mobimapper.backend.IInjector
import dev.mobimapper.mobimapper.editor.OverlayOpacityBudget
import dev.mobimapper.mobimapper.editor.OverlayWindows
import dev.mobimapper.mobimapper.session.SessionController
import dev.mobimapper.mobimapper.session.SessionMode
import dev.mobimapper.mobimapper.session.SessionService
import dev.mobimapper.mobimapper.session.InputQueue
import dev.mobimapper.mobimapper.session.NativeMotionDispatchGate
import dev.mobimapper.mobimapper.session.RawMouseMotionBatch
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.FutureTask
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real framework/window checks. These tests never acquire evdev or inject into another app. */
@RunWith(AndroidJUnit4::class)
class AndroidCompatibilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val cursors = mutableListOf<ApplicationCursorOverlay>()
    private var originalOverlayMode: Int? = null

    @Before fun prepareOverlayPermission() {
        val expected = requireNotNull(InstrumentationRegistry.getArguments().getString("expectedApi")) {
            "Use tools/run_android_compatibility.py to supply the expected platform"
        }.toInt()
        assertEquals("The emulator must run the requested Android API", expected, Build.VERSION.SDK_INT)
        originalOverlayMode = context.getSystemService(AppOpsManager::class.java)
            .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, Process.myUid(), context.packageName)
        overlayMode("allow")
        assertTrue(Settings.canDrawOverlays(context))
    }

    @After fun cleanup() {
        cursors.forEach { it.hide() }
        context.stopService(Intent(context, SessionService::class.java))
        instrumentation.waitForIdleSync()
        originalOverlayMode?.let {
            overlayMode(when (it) {
                AppOpsManager.MODE_ALLOWED -> "allow"
                AppOpsManager.MODE_IGNORED -> "ignore"
                AppOpsManager.MODE_ERRORED -> "deny"
                AppOpsManager.MODE_FOREGROUND -> "foreground"
                else -> "default"
            })
        }
    }

    @Test fun mainActivityAndForegroundServiceStartWithoutArmingInput() {
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            await("Main activity never became a visible focused window") {
                onMain { activity.window.decorView.isAttachedToWindow && activity.hasWindowFocus() }
            }
            assertFalse(SessionController.active)
            assertEquals(0, SessionController.contacts)
            context.startForegroundService(Intent(context, SessionService::class.java))
            await("specialUse foreground service did not start") { sessionServiceForeground() }
            assertFalse("Starting the service must not arm capture", SessionController.active)
            assertEquals(0, SessionController.contacts)
            assertTrue(context.stopService(Intent(context, SessionService::class.java)))
            await("foreground service did not stop") { !sessionServiceForeground() }
        } finally {
            onMain { activity.finish() }
        }
    }

    @Test fun nativeMotionDispatchRunsWhileMainIsOccupiedAndRetainsButtonOrder() {
        val worker=HandlerThread("UwUMotionCompatibility").apply { start() }
        val main=Handler(Looper.getMainLooper())
        val gate=NativeMotionDispatchGate()
        val route=NativeMotionDispatchGate.Route(77L,7L,0,42)
        val mainOccupied=CountDownLatch(1)
        val releaseMain=CountDownLatch(1)
        val firstMotion=CountDownLatch(1)
        val consumed=CountDownLatch(3)
        val trace=CopyOnWriteArrayList<Pair<Int,Int>>()
        val overflow=AtomicBoolean(false)
        lateinit var queue:InputQueue
        queue=InputQueue(Handler(worker.looper),{ type,code,_,_,_,generation ->
            assertEquals(7L,generation)
            if(type==8) assertEquals(42,code)
            val value=if(type==8) requireNotNull(queue.consumingMotionBatch).xs.single().toInt() else code
            trace+=type to value
            firstMotion.countDown();consumed.countDown()
        },{ overflow.set(true) })
        fun batch(value:Float)=RawMouseMotionBatch(longArrayOf(SystemClock.elapsedRealtimeNanos()),
            floatArrayOf(value),floatArrayOf(1f))
        try {
            gate.publish(route)
            assertTrue(main.post { mainOccupied.countDown();releaseMain.await(10,TimeUnit.SECONDS) })
            assertTrue("Main fixture never started",mainOccupied.await(3,TimeUnit.SECONDS))
            val direct=requireNotNull(gate.directRoute(77,0))
            assertTrue(queue.offerMotionBatch(batch(101f),direct.generation,direct.pointerEpoch))
            assertTrue("Motion depended on the occupied main thread",firstMotion.await(3,TimeUnit.SECONDS))
            assertTrue(gate.defer())
            assertTrue(main.post {
                try { assertTrue(queue.offer(2,code=1,x=1f,generation=7L)) }
                finally { gate.complete(route.copy(buttons=1)) }
            })
            assertNull("Motion must wait for the earlier M1 DOWN",gate.directRoute(77,1))
            assertTrue(gate.defer())
            val following=batch(102f)
            assertTrue(main.post {
                try { assertTrue(queue.offerMotionBatch(following,7L,42)) }
                finally { gate.complete(route.copy(buttons=1)) }
            })
            assertEquals(listOf(8 to 101),trace.toList())
            releaseMain.countDown()
            assertTrue("Deferred input did not drain",consumed.await(3,TimeUnit.SECONDS))
            onMain { assertEquals(0,gate.pendingCallbacks) }
            assertEquals(listOf(8 to 101,2 to 1,8 to 102),trace.toList())
            assertFalse(overflow.get())
        } finally {
            releaseMain.countDown()
            worker.quitSafely()
            worker.join(3_000)
        }
    }

    @Test fun installedNativeLibrariesAndActivationTlsLoad() {
        assertTrue("This shared emulator APK must use x86_64", Build.SUPPORTED_ABIS.contains("x86_64"))
        for (name in listOf("libuwu_capture.so", "libuwu_adb.so", "libflutter.so", "libconscrypt_jni.so")) {
            val library = File(context.applicationInfo.nativeLibraryDir, name)
            assertTrue("Missing installed native library: $name", library.isFile)
            assertTrue("Unreadable installed native library: $name", library.canRead())
            assertFalse("The application must not be able to modify installed code: $name", library.canWrite())
        }
        // Loading checks the platform linker/DCL contract. No native inventory/start method is called.
        System.load(File(context.applicationInfo.nativeLibraryDir, "libuwu_capture.so").absolutePath)
        System.load(File(context.applicationInfo.nativeLibraryDir, "libuwu_adb.so").absolutePath)
        // Exercise the production key/certificate/provider path, including Bouncy Castle.
        val tls = dev.mobimapper.mobimapper.activation.adb.AdbKey(context, "uwu-compatibility").sslContext
        // Construct the actual native-backed TLS socket without opening a network connection.
        (tls.socketFactory.createSocket() as javax.net.ssl.SSLSocket).use { socket ->
            assertTrue(socket.supportedProtocols.contains("TLSv1.3"))
        }
        assertFalse(SessionController.active)
        assertEquals(0, SessionController.contacts)
    }

    @Test fun cursorAttachesMovesAndRemovesOnPrimaryDisplay() {
        val display = requireNotNull(context.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY))
        exerciseCursor(display)
    }

    @Test fun cursorUsesTheExplicitSecondaryDisplayWindowContext() {
        val images = ImageReader.newInstance(640, 480, PixelFormat.RGBA_8888, 2)
        images.setOnImageAvailableListener({ reader -> reader.acquireLatestImage()?.close() }, Handler(Looper.getMainLooper()))
        val manager = context.getSystemService(DisplayManager::class.java)
        val display = requireNotNull(manager.createVirtualDisplay("UwU compatibility display", 640, 480,
            context.resources.configuration.densityDpi, images.surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY))
        try {
            assertNotEquals(Display.DEFAULT_DISPLAY, display.display.displayId)
            exerciseCursor(display.display)
        } finally {
            display.release()
            onMain {
                images.setOnImageAvailableListener(null, null)
                images.close()
            }
        }
    }

    @Test fun deniedOverlayPermissionLeavesNoCursorWindow() {
        val display = requireNotNull(context.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY))
        val cursor = cursorFor(display)
        overlayMode("deny")
        try {
            assertFalse(Settings.canDrawOverlays(context))
            assertFalse(cursor.show(display.displayId, 80f, 80f, 20f))
            assertFalse(cursor.visible)
            assertTrue("Permission rejection must produce a reason", cursor.error.isNotBlank())
            cursor.move(90f, 90f, 20f)
            cursor.hide()
        } finally {
            overlayMode("allow")
        }
        assertTrue("A rejected permission must not poison a later grant", cursor.show(display.displayId, 80f, 80f, 20f))
        cursor.hide()
    }

    @Test fun focusedApplicationOverlayRetainsBackKeysAndTheirPhysicalSource() {
        val display = requireNotNull(context.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY))
        val overlay = context.createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        val windows = overlay.getSystemService(WindowManager::class.java)
        val delivered = mutableListOf<Triple<Int, Int, Boolean>>()
        val view = onMain {
            object : View(overlay) {
                init { isFocusable = true; isFocusableInTouchMode = true }
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    if (event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
                    delivered += Triple(event.action, event.source, event.isCanceled)
                    return true
                }
            }.also {
                val params = WindowManager.LayoutParams(320, 240, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM, PixelFormat.TRANSLUCENT)
                windows.addView(it, params)
                it.requestFocus()
            }
        }
        try {
            await("The owned Back test overlay never gained focus") {
                onMain { view.isAttachedToWindow && view.hasWindowFocus() }
            }
            val expected = mutableListOf<Triple<Int, Int, Boolean>>()
            for (source in listOf(InputDevice.SOURCE_MOUSE, InputDevice.SOURCE_KEYBOARD)) {
                val downTime = SystemClock.uptimeMillis()
                for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                    assertTrue("The test must retain focus before injecting its own Back key", onMain { view.hasWindowFocus() })
                    val event = KeyEvent(downTime, SystemClock.uptimeMillis(), action, KeyEvent.KEYCODE_BACK,
                        0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, source)
                    assertTrue("Framework rejected an event for the owned test overlay",
                        instrumentation.uiAutomation.injectInputEvent(event, true))
                    expected += Triple(action, source, false)
                }
            }
            await("Back key pairs did not reach the owned overlay") { onMain { delivered.size >= expected.size } }
            assertEquals("Scoped legacy Back must retain mouse/keyboard identity", expected, onMain { delivered.toList() })
            assertTrue("Handling Back must leave the owned overlay attached", onMain { view.isAttachedToWindow })
        } finally {
            onMain { windows.removeViewImmediate(view) }
        }
    }

    @Test fun activationNetworkPermissionReflectsPlatformGrant() {
        val expected = requireNotNull(InstrumentationRegistry.getArguments().getString("expectedLocalNetwork"))
        val required = Build.VERSION.SDK_INT >= 37 && context.applicationInfo.targetSdkVersion >= 37
        assertEquals(required, ActivationNetworkPermission.required(context))
        when (expected) {
            "denied" -> {
                assertTrue(required)
                assertFalse(ActivationNetworkPermission.granted(context))
                assertThrows(IllegalStateException::class.java) { ActivationNetworkPermission.requireGranted(context) }
            }
            "granted" -> {
                assertTrue(required)
                assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
                    context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK))
                assertTrue(ActivationNetworkPermission.granted(context))
                ActivationNetworkPermission.requireGranted(context)
            }
            "not-required" -> {
                assertFalse(required)
                assertTrue(ActivationNetworkPermission.granted(context))
                ActivationNetworkPermission.requireGranted(context)
            }
            else -> fail("Unknown expectedLocalNetwork value: $expected")
        }
    }

    @Test fun freshControllerObservesOriginalGuardianClosureWithoutFocusOrCallback() {
        val qemu = instrumentation.uiAutomation.executeShellCommand("getprop ro.kernel.qemu").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText().trim() }
        }
        assertEquals("The fake guardian fixture is restricted to an emulator", "1", qemu)
        val previous = BackendRegistry.injector
        assertNull("This suite must never attach a real injection backend", previous)
        assertEquals("Other compatibility fixtures must not begin a mapping session", 0L,
            SessionController.status(context)["backendSessionId"])
        val originalDisplay = 2_000_000_000
        assertNull(context.getSystemService(DisplayManager::class.java).getDisplay(originalDisplay))
        val closed = AtomicBoolean(false)
        val recoveries = AtomicInteger()
        val forbidden = CopyOnWriteArrayList<String>()
        val binder = Binder()
        // A detached status fixture exercises controller integration, not Binder authentication.
        // No real injection/capture method is ever delegated to another process or device.
        val guardian = Proxy.newProxyInstance(IInjector::class.java.classLoader, arrayOf(IInjector::class.java)) {
                proxy, method, arguments ->
            when (method.name) {
                "asBinder" -> binder
                "equals" -> proxy === arguments?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "CompatibilityGuardianFixture"
                "status" -> Bundle().apply {
                    putInt("protocol", 4); putInt("uid", 2000); putBoolean("targeted", true)
                    putLong("sessionId", 777L); putInt("targetUid", 10394)
                    putString("targetPackage", SessionController.TARGET); putInt("displayId", originalDisplay)
                    putInt("possibleContacts", if (closed.get()) 0 else 1); putInt("confirmedContacts", 0)
                    putBoolean("releaseUnverified", !closed.get()); putBoolean("keyReleaseUnverified", false)
                    putBoolean("cancelAdmitted", false); putBoolean("terminalReconciled", closed.get())
                    putString("recoveryState", if (closed.get()) "RECOVERED" else "BLOCKED")
                    putBoolean("sessionRecoverySupported", true); putBoolean("captureSupported", false)
                }
                "windowContext", "focusedApplication", "captureStatus" -> Bundle()
                "release" -> { assertEquals(777L, arguments?.get(0)); closed.get() }
                "recoverSession" -> { assertEquals(777L, arguments?.get(0)); recoveries.incrementAndGet(); false }
                "hideVisualCursor" -> null
                else -> {
                    forbidden += method.name
                    throw AssertionError("Unexpected native input operation: ${method.name}")
                }
            }
        } as IInjector
        try {
            onMain {
                BackendRegistry.injector = guardian
                NativeSessionHost.initialize(context)
                SessionController.backendAvailable()
            }
            await("Fresh controller did not retain the original guardian") {
                val status = SessionController.status(context)
                status["backendSessionId"] == 777L && status["guardianClosurePending"] == true &&
                    status["mode"] == "blocked"
            }
            assertEquals(SessionController.TARGET, SessionController.targetPackage)
            assertEquals(originalDisplay, SessionController.targetDisplayId)
            assertEquals(originalDisplay, SessionController.recoveryTargetDisplayId)
            assertFalse(SessionController.active)
            assertFalse(SessionController.armed)
            assertTrue(SessionController.releaseUnverified)
            assertEquals(1, SessionController.contacts)
            await("False-focus context refresh did not run the bounded closure attempts") {
                onMain { SessionController.targetChanged(false) }
                recoveries.get() == 3 && SessionController.mode == SessionMode.BLOCKED
            }
            onMain { SessionController.targetChanged(false) }
            assertEquals("Closure retries must remain bounded", 3, recoveries.get())
            // Simulate a surviving guardian finishing later. No IBackendEvents callback exists.
            closed.set(true)
            await("A removed original display starved terminal closure observation") {
                onMain { SessionController.targetChanged(false) }
                SessionController.mode == SessionMode.STOPPED && !SessionController.releaseUnverified &&
                    SessionController.contacts == 0 &&
                    SessionController.status(context)["guardianClosurePending"] == false
            }
            assertEquals(originalDisplay, SessionController.targetDisplayId)
            assertFalse(SessionController.active)
            assertFalse(SessionController.armed)
            assertTrue("Adoption/closure must never begin or inject input: $forbidden", forbidden.isEmpty())
        } finally {
            // Complete proof through the same fake owner before restoring the prior registry.
            closed.set(true)
            try {
                onMain { SessionController.stopSession() }
                await("Fake guardian cleanup did not finish") {
                    SessionController.mode == SessionMode.STOPPED && !SessionController.isTransitioning() &&
                        !SessionController.releaseUnverified && SessionController.contacts == 0
                }
            } finally {
                onMain { BackendRegistry.injector = previous }
            }
        }
        assertTrue("Cleanup must not begin or inject input: $forbidden", forbidden.isEmpty())
    }

    private fun exerciseCursor(display: Display) {
        val windowContext = windowContext(display)
        val window = windowContext.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val cursor = ApplicationCursorOverlay(windowContext).also { cursors += it }
        val x = window.exactCenterX()
        val y = window.exactCenterY()
        val admitted = cursor.show(display.displayId, x, y, 20f)
        assertTrue("Cursor window rejected: ${cursor.error}", admitted)
        instrumentation.waitForIdleSync()
        val view = cursorView(cursor)
        await("Cursor view never attached on display ${display.displayId}") { onMain { view.isAttachedToWindow } }
        onMain {
            assertTrue(view.isAttachedToWindow)
            assertEquals(display.displayId, view.display.displayId)
            val params = view.layoutParams as WindowManager.LayoutParams
            assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, params.type)
            assertNotEquals(0, params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            assertNotEquals(0, params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            val maximum = windowContext.getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch
            val passive = OverlayWindows.nonTouchAlpha(windowContext).toDouble()
            val combined = 1.0 - (1.0 - params.alpha) * (1.0 - passive) * (1.0 - passive)
            assertTrue("Overlay opacity exceeded the platform limit", params.alpha > 0f && combined <= maximum)
        }
        cursor.move(x + 10f, y + 10f, 24f)
        cursor.refreshWindowOpacity()
        instrumentation.waitForIdleSync()
        assertTrue(onMain { view.isAttachedToWindow })
        cursor.hide()
        instrumentation.waitForIdleSync()
        assertFalse(cursor.visible)
        assertFalse(onMain { view.isAttachedToWindow })
    }

    private fun cursorFor(display: Display) = ApplicationCursorOverlay(windowContext(display)).also { cursors += it }

    private fun windowContext(display: Display): Context {
        val base = context.createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        val maximum = base.getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch
        return OverlayWindows.externalContext(base, OverlayOpacityBudget.layerAlpha(maximum)).also {
            OverlayWindows.setCursorActive(it, true)
        }
    }

    // Inspect only our own field, never a hidden framework field or a production test hook.
    private fun cursorView(cursor: ApplicationCursorOverlay): View = onMain {
        ApplicationCursorOverlay::class.java.getDeclaredField("view").apply { isAccessible = true }.get(cursor) as View
    }

    @Suppress("DEPRECATION")
    private fun sessionServiceForeground() = context.getSystemService(ActivityManager::class.java)
        .getRunningServices(Int.MAX_VALUE).any { it.service.className == SessionService::class.java.name && it.foreground }

    private fun overlayMode(mode: String) {
        instrumentation.uiAutomation.executeShellCommand("appops set ${context.packageName} SYSTEM_ALERT_WINDOW $mode")
            .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() } }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (condition()) return
            SystemClock.sleep(25)
        }
        fail(message)
    }

    private fun <T> onMain(action: () -> T): T {
        val task = FutureTask(action)
        instrumentation.runOnMainSync(task)
        return task.get()
    }
}
