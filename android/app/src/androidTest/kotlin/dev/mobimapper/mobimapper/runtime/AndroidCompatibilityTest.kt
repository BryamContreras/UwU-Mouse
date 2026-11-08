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
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.hardware.input.InputManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mobimapper.mobimapper.MainActivity
import dev.mobimapper.mobimapper.activation.ActivationNetworkPermission
import dev.mobimapper.mobimapper.editor.OverlayOpacityBudget
import dev.mobimapper.mobimapper.editor.OverlayWindows
import dev.mobimapper.mobimapper.session.SessionController
import dev.mobimapper.mobimapper.session.SessionService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.FutureTask

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

    @Test fun installedNativeLibrariesAreReadableAndCaptureBinaryLoads() {
        assertTrue("This shared emulator APK must use x86_64", Build.SUPPORTED_ABIS.contains("x86_64"))
        for (name in listOf("libuwu_capture.so", "libuwu_adb.so", "libflutter.so")) {
            val library = File(context.applicationInfo.nativeLibraryDir, name)
            assertTrue("Missing installed native library: $name", library.isFile)
            assertTrue("Unreadable installed native library: $name", library.canRead())
            assertFalse("The application must not be able to modify installed code: $name", library.canWrite())
        }
        // Loading checks the platform linker/DCL contract. No native inventory/start method is called.
        System.load(File(context.applicationInfo.nativeLibraryDir, "libuwu_capture.so").absolutePath)
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
