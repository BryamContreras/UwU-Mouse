package dev.uwumouse.app.runtime

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
import dev.uwumouse.app.MainActivity
import dev.uwumouse.app.activation.ActivationNetworkPermission
import dev.uwumouse.app.backend.BackendRegistry
import dev.uwumouse.app.backend.IInjector
import dev.uwumouse.app.backend.recovery.DispatcherTouchStateParser
import dev.uwumouse.app.editor.OverlayOpacityBudget
import dev.uwumouse.app.editor.OverlayWindows
import dev.uwumouse.app.game.stopSessionThenCleanUp
import dev.uwumouse.app.session.SessionController
import dev.uwumouse.app.session.SessionMode
import dev.uwumouse.app.session.SessionService
import dev.uwumouse.app.session.InputQueue
import dev.uwumouse.app.session.NativeMotionDispatchGate
import dev.uwumouse.app.session.RawMouseMotionBatch
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

    @Test fun lifecycleParsersHandleRetiredDisplaysHoverAndRepeatedWindowSummaries() {
        // Sanitized shape observed on an Android 17 physical device. Exercise Android's
        // regex implementation on every supported API, independently of the host JDK.
        val cursor = """
  TouchStatesByDisplay:
    LogicalDisplayId 8:
      TouchedWindows: <none>
  CursorStatesByDisplay:
    LogicalDisplayId 8:
      TouchedWindows: <none>
    LogicalDisplayId 0:
      TouchedWindows:
        0: name='PointerEventDispatcherOverlay0', targetFlags=0x0, forwardingWindowToken=0x0, mDeviceStates=
          DeviceId(20):[touchingPointers=[], downTimeInTarget=<not set>, hoveringPointers=[Pointer(id=0, MOUSE) at (0, 1027.91)], pilferingPointerIds=<none>]
  Display: 0
""".trim('\n', '\r')
        assertTrue(DispatcherTouchStateParser.isDefinitelyUntouched(cursor))
        assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched(
            cursor.replace("touchingPointers=[]", "touchingPointers=[Pointer(id=0, MOUSE) at (0, 1027.91)]")))
        assertFalse(DispatcherTouchStateParser.isDefinitelyUntouched(
            cursor.replace("downTimeInTarget=<not set>", "downTimeInTarget=100")))
        val primary = """
  Window #0 Window{123abc u0 dev.example.game}:
    mDisplayId=0
    mOwnerUid=10123 package=dev.example.game
    mAttrs={ty=BASE_APPLICATION}
    mHasSurface=true
    Frames: frame=[0,0][1080,1920]
    isOnScreen=true
    isVisible=true
""".trim('\n', '\r')
        val windows = "WINDOW MANAGER WINDOWS\n$primary\n" +
            "  Hiding System Alert Windows:\n" +
            primary.replace("Window #0 Window", "#0 Window") +
            "\n  mGlobalConfiguration={}\n  mTopFocusedDisplayId=0\n"
        val displays = "WINDOW MANAGER DISPLAY CONTENTS\n  Display: mDisplayId=0\n" +
            "  mCurrentFocus=Window{123abc u0 dev.example.game}\n  mFocusedApp=ActivityRecord{abc}\n"
        // window-context is a private backend-host dependency; inspect its packaged
        // production parser without adding a production dependency to the app.
        val parser = Class.forName("dev.uwumouse.windowcontext.WindowDumpParser")
        val parsed = parser.getMethod("parse", String::class.java, String::class.java)
            .invoke(parser.getField("INSTANCE").get(null), windows, displays)
        val snapshot = parsed.javaClass
        assertEquals(true, snapshot.getMethod("getValid").invoke(parsed))
        assertEquals(1, (snapshot.getMethod("getWindows").invoke(parsed) as List<*>).size)
        assertEquals("dev.example.game", snapshot.getMethod("getFocusedPackage").invoke(parsed))
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
        val tls = dev.uwumouse.app.activation.adb.AdbKey(context, "uwu-compatibility").sslContext
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

    @Test fun customResolutionChangesOnlyTheOwnedVirtualDisplayAndRestoresItsBounds() {
        fun shell(command:String)=instrumentation.uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText().trim() }
        }
        assertEquals("Resolution changes in this fixture are restricted to an emulator", "1",
            shell("getprop ro.kernel.qemu"))
        assertNull("This fixture must not attach a real input backend",BackendRegistry.injector)
        assertFalse(SessionController.active)
        assertEquals(0,SessionController.contacts)
        val manager=context.getSystemService(DisplayManager::class.java)
        val primaryBefore=onMain { TargetDisplayGeometry.read(context,Display.DEFAULT_DISPLAY) }
        val images=ImageReader.newInstance(640,480,PixelFormat.RGBA_8888,2)
        val peerImages=ImageReader.newInstance(320,240,PixelFormat.RGBA_8888,2)
        var target:android.hardware.display.VirtualDisplay?=null
        var peer:android.hardware.display.VirtualDisplay?=null
        var ownedDisplayId:Int?=null
        var failure:Throwable?=null
        try {
            val handler=Handler(Looper.getMainLooper())
            images.setOnImageAvailableListener({ reader -> reader.acquireLatestImage()?.close() },handler)
            peerImages.setOnImageAvailableListener({ reader -> reader.acquireLatestImage()?.close() },handler)
            val flags=DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            val screen=requireNotNull(manager.createVirtualDisplay("UwU resolution fixture",640,480,
                context.resources.configuration.densityDpi,images.surface,flags))
            target=screen
            val targetId=screen.display.displayId
            assertTrue("A new owned virtual display must never be the primary",targetId>Display.DEFAULT_DISPLAY)
            ownedDisplayId=targetId
            assertEquals("WMS shell must address an owned public virtual display",0,screen.display.flags and Display.FLAG_PRIVATE)
            val other=requireNotNull(manager.createVirtualDisplay("UwU resolution isolation fixture",320,240,
                context.resources.configuration.densityDpi,peerImages.surface,flags))
            peer=other
            val peerId=other.display.displayId
            assertTrue(peerId>Display.DEFAULT_DISPLAY)
            assertNotEquals(targetId,peerId)
            val targetBefore=onMain { TargetDisplayGeometry.read(context,targetId) }
            val peerBefore=onMain { TargetDisplayGeometry.read(context,peerId) }
            assertEquals(640,targetBefore.width);assertEquals(480,targetBefore.height)
            assertEquals(320,peerBefore.width);assertEquals(240,peerBefore.height)
            val original=shell("wm size -d $targetId")
            assertTrue("Unexpected initial virtual display size: $original",original.contains("Physical size: 640x480"))
            assertFalse("A newly created fixture must not inherit an override: $original",original.contains("Override size:"))

            // Exercise the framework's real privileged per-display operation. An empty
            // command response is not proof: both WMS and window metrics must change.
            val applied=shell("wm size 800x600 -d $targetId")
            await("The owned display did not become 800x600; wm output: $applied") {
                onMain { TargetDisplayGeometry.read(context,targetId).let { it.width==800 && it.height==600 } }
            }
            val changed=shell("wm size -d $targetId")
            assertTrue("WMS did not retain the target override: $changed",changed.contains("Override size: 800x600"))
            assertEquals("An explicit target override changed the primary display",primaryBefore,
                onMain { TargetDisplayGeometry.read(context,Display.DEFAULT_DISPLAY) })
            assertEquals("An explicit target override changed another virtual display",peerBefore,
                onMain { TargetDisplayGeometry.read(context,peerId) })

            val restored=shell("wm size reset -d $targetId")
            await("The owned display did not restore its original window metrics; wm output: $restored") {
                onMain { TargetDisplayGeometry.read(context,targetId)==targetBefore }
            }
            val reset=shell("wm size -d $targetId")
            assertTrue("The original base size changed: $reset",reset.contains("Physical size: 640x480"))
            assertFalse("Reset left a logical override behind: $reset",reset.contains("Override size:"))
            assertEquals(primaryBefore,onMain { TargetDisplayGeometry.read(context,Display.DEFAULT_DISPLAY) })
            assertEquals(peerBefore,onMain { TargetDisplayGeometry.read(context,peerId) })
            assertFalse(SessionController.active)
            assertEquals(0,SessionController.contacts)
        } catch(problem:Throwable) {
            failure=problem
            throw problem
        } finally {
            var cleanupFailure:Throwable?=null
            fun clean(action:()->Unit) {
                try { action() } catch(problem:Throwable) {
                    val previous=cleanupFailure
                    if(previous==null) cleanupFailure=problem else previous.addSuppressed(problem)
                }
            }
            // Clear only our recorded non-primary ID, before releasing its display.
            // An earlier assertion must not leave any override or ImageReader alive.
            clean {
                ownedDisplayId?.let { id ->
                    check(id>Display.DEFAULT_DISPLAY)
                    if(manager.getDisplay(id)!=null) {
                        val output=shell("wm size reset -d $id")
                        await("Fixture cleanup did not clear its own override: $output") {
                            onMain { TargetDisplayGeometry.read(context,id).let { it.width==640 && it.height==480 } }
                        }
                    }
                }
            }
            clean { target?.release() }
            clean { peer?.release() }
            clean { onMain { images.setOnImageAvailableListener(null,null);images.close() } }
            clean { onMain { peerImages.setOnImageAvailableListener(null,null);peerImages.close() } }
            clean { TargetDisplayGeometry.clear() }
            cleanupFailure?.let { problem ->
                val original=failure
                if(original==null) throw problem else original.addSuppressed(problem)
            }
        }
    }

    @Test fun primaryControlClosesOwnedExternalOverlayBeforeAndAfterDisplayRemoval() {
        val manager=context.getSystemService(DisplayManager::class.java)
        val primary=requireNotNull(manager.getDisplay(Display.DEFAULT_DISPLAY))
        val images=ImageReader.newInstance(640,480,PixelFormat.RGBA_8888,2)
        images.setOnImageAvailableListener({ reader -> reader.acquireLatestImage()?.close() },Handler(Looper.getMainLooper()))
        var virtual:android.hardware.display.VirtualDisplay?=null
        var activity:android.app.Activity?=null
        var primaryCursor:ApplicationCursorOverlay?=null
        var externalCursor:ApplicationCursorOverlay?=null
        var displayReleased=false
        try {
            val screen=requireNotNull(manager.createVirtualDisplay("UwU external closure display",640,480,
                context.resources.configuration.densityDpi,images.surface,DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY))
            virtual=screen
            val externalId=screen.display.displayId
            assertNotEquals(Display.DEFAULT_DISPLAY,externalId)
            val controls=instrumentation.startActivitySync(Intent(context,MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            activity=controls
            await("Primary controls did not gain focus") { onMain { controls.hasWindowFocus() } }
            assertEquals(Display.DEFAULT_DISPLAY,controls.display?.displayId)
            val externalGeometry=TargetDisplayGeometry.read(context,externalId)
            assertEquals(640,externalGeometry.width)
            assertEquals(480,externalGeometry.height)
            val primaryBounds=onMain { controls.windowManager.maximumWindowMetrics.bounds }
            val primaryGeometry=TargetDisplayGeometry.read(context,Display.DEFAULT_DISPLAY)
            assertEquals(primaryBounds.width(),primaryGeometry.width)
            assertEquals(primaryBounds.height(),primaryGeometry.height)
            assertEquals(externalGeometry,TargetDisplayGeometry.read(context,externalId))
            val phone=cursorFor(primary).also { primaryCursor=it }
            val external=cursorFor(screen.display).also { externalCursor=it }
            assertTrue("Primary cursor rejected: ${phone.error}",phone.show(primary.displayId,80f,80f,20f))
            assertTrue("External cursor rejected: ${external.error}",external.show(externalId,80f,80f,20f))
            val phoneView=cursorView(phone)
            val firstExternalView=cursorView(external)
            await("Both owned displays must have attached windows") {
                onMain { phoneView.isAttachedToWindow && firstExternalView.isAttachedToWindow }
            }
            assertEquals(externalId,onMain { firstExternalView.display.displayId })
            var requestedStops=0
            val failures=mutableListOf<Exception>()
            fun closeExternal()=onMain {
                // This is the production close ordering used by GameCoordinator. It
                // exercises real own-window disposal without arming any input backend.
                var nativeStopRequested=false
                stopSessionThenCleanUp({ nativeStopRequested=true;requestedStops++ },listOf({
                    assertTrue("External disposal preceded the native stop request",nativeStopRequested)
                    external.hide()
                }),{ failures+=it })
            }
            closeExternal()
            assertEquals(1,requestedStops)
            assertTrue("External window cleanup failed: $failures",failures.isEmpty())
            assertFalse(external.visible)
            assertFalse(onMain { firstExternalView.isAttachedToWindow })
            assertTrue("Closing another display detached the primary cursor",onMain { phoneView.isAttachedToWindow })
            assertTrue("Closing another display changed primary focus",onMain { controls.hasWindowFocus() })

            assertTrue("External cursor could not be reopened: ${external.error}",external.show(externalId,100f,100f,20f))
            val detachedDisplayView=cursorView(external)
            await("Reopened external window never attached") { onMain { detachedDisplayView.isAttachedToWindow } }
            screen.release();displayReleased=true
            await("The owned virtual display did not disappear") { manager.getDisplay(externalId)==null }
            assertThrows(IllegalArgumentException::class.java) { TargetDisplayGeometry.read(context,externalId) }
            closeExternal()
            assertEquals(2,requestedStops)
            assertTrue("A removed display prevented cleanup: $failures",failures.isEmpty())
            assertFalse(external.visible)
            await("The removed display retained its owned window") { onMain { !detachedDisplayView.isAttachedToWindow } }
            assertTrue(phone.visible)
            assertTrue(onMain { phoneView.isAttachedToWindow && phoneView.display.displayId==Display.DEFAULT_DISPLAY })
            assertTrue("Removing the secondary display changed primary focus",onMain { controls.hasWindowFocus() })
            assertFalse("Window lifecycle checks must never arm input",SessionController.active)
            assertFalse(SessionController.armed)
            assertEquals(0,SessionController.contacts)
        } finally {
            try { externalCursor?.hide() }
            finally {
                try { primaryCursor?.hide() }
                finally {
                    if(!displayReleased) virtual?.release()
                    onMain {
                        images.setOnImageAvailableListener(null,null)
                        images.close() // Also releases the ImageReader-owned Surface.
                        activity?.finish()
                    }
                }
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
