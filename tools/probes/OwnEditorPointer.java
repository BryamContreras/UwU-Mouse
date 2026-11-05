import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/**
 * Shell UID 2000 QA only. No game input, no mapper backend injection, no profile writes.
 * app_process ... OwnEditorPointer rightclick x y
 * app_process ... OwnEditorPointer drag x1 y1 x2 y2 [durationMs]
 * app_process ... OwnEditorPointer overlayclick x y
 * app_process ... OwnEditorPointer overlaydrag x1 y1 x2 y2 [durationMs]
 * UiAutomation observes mapper accessibility overlays without suppressing services.
 * Mouse events use InputManager's fixed target-UID overload, never unscoped automation injection.
 */
public final class OwnEditorPointer {
    private static final String OWN = "dev.mobimapper.mobimapper";
    private static final String BUBBLE_TITLE = "MobiMapper \u00b7 Burbuja";
    private static final String MENU_TITLE = "MobiMapper \u00b7 Opciones";
    private static final int MAX_DIAGNOSTIC_BYTES = 1_048_576;
    private final UiAutomation automation;
    private final Object input;
    private final Method inject, setDisplay, setActionButton;
    private final int ownUid;
    private final boolean overlayClick;
    private final boolean overlayDrag;
    private final Point displaySize = new Point();
    private int windowId = -1;
    private Rect originalBounds;
    private long downTime;
    private float lastX, lastY;
    private boolean possiblyDown;
    private int attempted;

    private OwnEditorPointer(UiAutomation automation, Context context, boolean overlayClick, boolean overlayDrag) throws Exception {
        this.automation = automation;
        this.overlayClick = overlayClick;
        this.overlayDrag = overlayDrag;
        if (overlayDrag) {
            Display display = context.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
            if (display == null) throw new SecurityException("Display 0 unavailable");
            display.getRealSize(displaySize);
            if (displaySize.x <= 0 || displaySize.y <= 0)
                throw new SecurityException("Display 0 real dimensions unavailable");
        }
        ownUid = context.getPackageManager().getPackageUid(OWN, 0);
        if (ownUid < 10_000) throw new SecurityException("Installed mapper application UID required");
        input = context.getSystemService(Context.INPUT_SERVICE);
        inject = input.getClass().getMethod("injectInputEvent", InputEvent.class, int.class, int.class);
        setDisplay = InputEvent.class.getMethod("setDisplayId", int.class);
        setActionButton = MotionEvent.class.getMethod("setActionButton", int.class);
    }

    private static JSONObject diagnostics() throws Exception {
        java.lang.Process command = new ProcessBuilder("/system/bin/content", "call", "--uri",
                "content://" + OWN + ".backend", "--method", "diagnostics")
                .redirectErrorStream(true).start();
        FutureTask<String> output = new FutureTask<>(() -> {
            try (InputStream stream = command.getInputStream();
                 ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[4096];
                int count;
                while ((count = stream.read(chunk)) != -1) {
                    if (bytes.size() + count > MAX_DIAGNOSTIC_BYTES)
                        throw new SecurityException("Diagnostic output exceeds bound");
                    bytes.write(chunk, 0, count);
                }
                return new String(bytes.toByteArray(), StandardCharsets.UTF_8).trim();
            }
        });
        Thread reader = new Thread(output, "OwnEditorDiagnostics");
        reader.setDaemon(true);
        reader.start();
        try {
            if (!command.waitFor(5, TimeUnit.SECONDS)) throw new SecurityException("Diagnostic timeout");
            String wire = output.get(1, TimeUnit.SECONDS);
            String prefix = "Result: Bundle[{json=";
            if (command.exitValue() != 0 || !wire.startsWith(prefix) || !wire.endsWith("}]"))
                throw new SecurityException("Closed mapper diagnostics unavailable");
            return new JSONObject(wire.substring(prefix.length(), wire.length() - 2));
        } finally {
            command.destroy();
            output.cancel(true);
        }
    }

    private static void requireIdleEditor() throws Exception {
        JSONObject state = diagnostics();
        String mode = state.getString("mode");
        long age = SystemClock.uptimeMillis() - state.getLong("uptimeMs");
        if (!("editing".equals(mode) || "paused".equals(mode)) || state.getBoolean("active") ||
                !state.getBoolean("editorVisible") || state.getInt("contacts") != 0 ||
                state.getInt("backendPossibleContacts") != 0 || state.getBoolean("releaseUnverified") ||
                state.getBoolean("mouseCaptured") || state.getBoolean("contextBlockedBySystemWindow") ||
                age < 0 || age > 2000)
            throw new SecurityException("Inactive, empty, confirmed own editor required; no mouse event sent");
    }

    private static JSONObject requireIdleOverlay() throws Exception {
        JSONObject state = diagnostics();
        String mode = state.getString("mode");
        long age = SystemClock.uptimeMillis() - state.getLong("uptimeMs");
        if (!("paused".equals(mode) || "stopped".equals(mode)) || state.getBoolean("active") ||
                state.getBoolean("editorVisible") || state.getInt("contacts") != 0 ||
                state.getInt("backendPossibleContacts") != 0 || state.getBoolean("releaseUnverified") ||
                state.getBoolean("mouseCaptured") || state.getBoolean("contextBlockedBySystemWindow") ||
                !(state.getBoolean("bubbleVisible") || state.getBoolean("bubbleMenuVisible")) ||
                age < 0 || age > 2000)
            throw new SecurityException("Inactive, empty own bubble/menu required; no mouse event sent");
        return state;
    }

    private void requireOwnOverlayWindow(float x, float y) throws Exception {
        JSONObject state = requireIdleOverlay();
        if (!Float.isFinite(x) || !Float.isFinite(y)) throw new SecurityException("Finite own overlay coordinates required");
        AccessibilityWindowInfo selected = null;
        Rect selectedBounds = null;
        boolean selectedRootResolved = false;
        for (AccessibilityWindowInfo window : automation.getWindows()) {
            if (window.getDisplayId() != 0 || window.getType() != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
            String title = window.getTitle() == null ? "" : window.getTitle().toString();
            if (!((BUBBLE_TITLE.equals(title) && state.getBoolean("bubbleVisible")) ||
                    (MENU_TITLE.equals(title) && state.getBoolean("bubbleMenuVisible")))) continue;
            Rect bounds = new Rect();
            window.getBoundsInScreen(bounds);
            if (bounds.isEmpty() || x < bounds.left || x >= bounds.right || y < bounds.top || y >= bounds.bottom) continue;
            AccessibilityNodeInfo root = window.getRoot();
            boolean rootResolved = root != null;
            if (root != null) {
                boolean own;
                try { own = OWN.contentEquals(root.getPackageName()); }
                finally { root.recycle(); }
                if (!own) continue; // Resolved foreign/unknown ownership cannot use the title fallback.
            }
            // The bubble/menu deliberately never focuses. A null root is accepted only after the
            // exact current title/visibility/type/display/bounds checks; injection still forces ownUid.
            if (selected == null || window.getLayer() > selected.getLayer()) {
                selected = window; selectedBounds = bounds; selectedRootResolved = rootResolved;
            } else if (window.getLayer() == selected.getLayer()) {
                throw new SecurityException("Ambiguous own overlay at pointer coordinates");
            }
        }
        if (selected == null) throw new SecurityException("Exact own bubble/menu title and bounds required");
        if (windowId == -1) {
            windowId = selected.getId(); originalBounds = new Rect(selectedBounds);
            System.out.println("OWN_OVERLAY uid=" + ownUid + " window=" + windowId + " display=0 title=" +
                    selected.getTitle() + " rootResolved=" + selectedRootResolved + " bounds=" + selectedBounds);
        } else if (selected.getId() != windowId || !originalBounds.equals(selectedBounds)) {
            throw new SecurityException("Own overlay window changed during click");
        }
    }

    /** Bubble drag only: pointer capture owns MOVE/UP even after the bubble bounds move. */
    private void requireOwnBubbleDragWindow(float x, float y) throws Exception {
        JSONObject state = requireIdleOverlay();
        if (!"paused".equals(state.getString("mode")) || !state.getBoolean("bubbleVisible"))
            throw new SecurityException("Paused own bubble required for drag");
        requireDisplayPoint(x, y);
        AccessibilityWindowInfo selected = null;
        Rect selectedBounds = null;
        boolean selectedRootResolved = false;
        for (AccessibilityWindowInfo window : automation.getWindows()) {
            if (window.getDisplayId() != 0 ||
                    window.getType() != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
                    !BUBBLE_TITLE.contentEquals(window.getTitle() == null ? "" : window.getTitle())) continue;
            Rect bounds = new Rect();
            window.getBoundsInScreen(bounds);
            if (bounds.isEmpty()) continue;
            AccessibilityNodeInfo root = window.getRoot();
            boolean rootResolved = root != null;
            if (root != null) {
                boolean own;
                try { own = OWN.contentEquals(root.getPackageName()); }
                finally { root.recycle(); }
                if (!own) throw new SecurityException("Bubble ownership changed during drag");
            }
            // Half-docked frames may be clipped or negative. Initial DOWN still has to be
            // inside the currently visible bubble; subsequent coordinates belong to its gesture.
            if (windowId == -1 && !contains(bounds, x, y)) continue;
            if (selected != null) throw new SecurityException("Ambiguous exact own bubble window");
            selected = window; selectedBounds = bounds; selectedRootResolved = rootResolved;
        }
        if (selected == null) throw new SecurityException("Exact current own bubble window required");
        if (windowId == -1) {
            windowId = selected.getId(); originalBounds = new Rect(selectedBounds);
            System.out.println("OWN_BUBBLE_DRAG uid=" + ownUid + " window=" + windowId +
                    " display=0 title=" + BUBBLE_TITLE + " rootResolved=" + selectedRootResolved +
                    " bounds=" + selectedBounds + " realSize=" + displaySize.x + "x" + displaySize.y);
        } else if (selected.getId() != windowId) {
            throw new SecurityException("Bubble window changed during drag");
        }
        if (!possiblyDown && !contains(selectedBounds, x, y))
            throw new SecurityException("Initial bubble DOWN must be inside current bounds");
    }

    private static boolean contains(Rect bounds, float x, float y) {
        return x >= bounds.left && x < bounds.right && y >= bounds.top && y < bounds.bottom;
    }

    private void requireDisplayPoint(float x, float y) {
        if (!Float.isFinite(x) || !Float.isFinite(y) ||
                x < 0 || x >= displaySize.x || y < 0 || y >= displaySize.y)
            throw new SecurityException("Pointer point inside real display 0 required");
    }

    private void requireOwnWindow(float x, float y) throws Exception {
        if (overlayDrag) { requireOwnBubbleDragWindow(x, y); return; }
        if (overlayClick) { requireOwnOverlayWindow(x, y); return; }
        requireIdleEditor();
        AccessibilityWindowInfo selected = null;
        Rect bounds = new Rect();
        for (AccessibilityWindowInfo window : automation.getWindows()) {
            if (window.getDisplayId() != 0 || !window.isFocused() ||
                    window.getType() != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null) continue;
            boolean own;
            try { own = OWN.contentEquals(root.getPackageName()); }
            finally { root.recycle(); }
            if (!own) continue;
            if (selected != null) throw new SecurityException("Ambiguous focused own overlay");
            selected = window;
            window.getBoundsInScreen(bounds);
        }
        if (selected == null || bounds.isEmpty() || !Float.isFinite(x) || !Float.isFinite(y) ||
                x < bounds.left || x >= bounds.right || y < bounds.top || y >= bounds.bottom)
            throw new SecurityException("Resolved focused own editor bounds on display 0 required");
        if (windowId == -1) {
            windowId = selected.getId();
            originalBounds = new Rect(bounds);
            System.out.println("OWN_EDITOR uid=" + ownUid + " window=" + windowId + " display=0 bounds=" + bounds);
        } else if (selected.getId() != windowId || !originalBounds.equals(bounds)) {
            throw new SecurityException("Editor window changed during gesture");
        }
    }

    private boolean injectMouse(int action, int buttons, int actionButton, float x, float y) throws Exception {
        MotionEvent.PointerProperties property = new MotionEvent.PointerProperties();
        property.id = 0;
        property.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords coordinate = new MotionEvent.PointerCoords();
        coordinate.x = x; coordinate.y = y;
        coordinate.pressure = buttons == 0 ? 0 : 1;
        coordinate.size = 1;
        MotionEvent event = MotionEvent.obtain(downTime, Math.max(downTime, SystemClock.uptimeMillis()),
                action, 1, new MotionEvent.PointerProperties[]{property},
                new MotionEvent.PointerCoords[]{coordinate}, 0, buttons, 1, 1, 0, 0,
                InputDevice.SOURCE_MOUSE, 0);
        try {
            setDisplay.invoke(event, 0);
            if (actionButton != 0) setActionButton.invoke(event, actionButton);
            return (Boolean) inject.invoke(input, event, 2, ownUid); // WAIT_FOR_FINISHED, fixed mapper UID.
        } finally { event.recycle(); }
    }

    private void send(int action, int buttons, int actionButton, float x, float y) throws Exception {
        requireOwnWindow(x, y);
        if (action == MotionEvent.ACTION_DOWN) {
            if (possiblyDown) throw new IllegalStateException("Previous own mouse gesture not released");
            downTime = SystemClock.uptimeMillis();
            possiblyDown = true; // A failed/lost reply may follow delivery; preserve cleanup ownership.
        }
        lastX = x; lastY = y;
        boolean admitted = injectMouse(action, buttons, actionButton, x, y);
        System.out.println("OWN_MOUSE sequence=" + (++attempted) + " action=" + action + " buttons=" + buttons +
                " actionButton=" + actionButton + " x=" + x + " y=" + y + " admitted=" + admitted);
        if (!admitted) throw new IllegalStateException("Own mouse event not admitted");
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) possiblyDown = false;
    }

    private void clickRight(float x, float y) throws Exception {
        downTime = SystemClock.uptimeMillis();
        send(MotionEvent.ACTION_HOVER_MOVE, 0, 0, x, y);
        send(MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_SECONDARY, 0, x, y);
        send(MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.BUTTON_SECONDARY, MotionEvent.BUTTON_SECONDARY, x, y);
        send(MotionEvent.ACTION_BUTTON_RELEASE, 0, MotionEvent.BUTTON_SECONDARY, x, y);
        send(MotionEvent.ACTION_UP, 0, 0, x, y);
        send(MotionEvent.ACTION_HOVER_MOVE, 0, 0, x, y);
    }

    private void clickOverlay(float x, float y) throws Exception {
        downTime = SystemClock.uptimeMillis();
        send(MotionEvent.ACTION_HOVER_MOVE, 0, 0, x, y);
        send(MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_PRIMARY, 0, x, y);
        send(MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_PRIMARY, x, y);
        send(MotionEvent.ACTION_BUTTON_RELEASE, 0, MotionEvent.BUTTON_PRIMARY, x, y);
        send(MotionEvent.ACTION_UP, 0, 0, x, y);
        // UP may open/resize/close the menu. Do not inject a hover into the resulting new geometry.
    }

    private void drag(float x1, float y1, float x2, float y2, int durationMs) throws Exception {
        requireOwnWindow(x1, y1);
        requireOwnWindow(x2, y2);
        downTime = SystemClock.uptimeMillis();
        send(MotionEvent.ACTION_HOVER_MOVE, 0, 0, x1, y1);
        send(MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_PRIMARY, 0, x1, y1);
        send(MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_PRIMARY, x1, y1);
        for (int step = 1; step <= 10; step++) {
            SystemClock.sleep(durationMs / 10);
            float fraction = step / 10f;
            send(MotionEvent.ACTION_MOVE, MotionEvent.BUTTON_PRIMARY, 0,
                    x1 + (x2 - x1) * fraction, y1 + (y2 - y1) * fraction);
        }
        send(MotionEvent.ACTION_BUTTON_RELEASE, 0, MotionEvent.BUTTON_PRIMARY, x2, y2);
        send(MotionEvent.ACTION_UP, 0, 0, x2, y2);
        send(MotionEvent.ACTION_HOVER_MOVE, 0, 0, x2, y2);
    }

    private void dragOverlay(float x1, float y1, float x2, float y2, int durationMs) throws Exception {
        requireDisplayPoint(x1, y1);
        requireDisplayPoint(x2, y2); // Deliberately not checked against the original bubble frame.
        requireOwnWindow(x1, y1);
        downTime = SystemClock.uptimeMillis();
        send(MotionEvent.ACTION_HOVER_MOVE, 0, 0, x1, y1);
        send(MotionEvent.ACTION_DOWN, MotionEvent.BUTTON_PRIMARY, 0, x1, y1);
        send(MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_PRIMARY, x1, y1);
        for (int step = 1; step <= 10; step++) {
            SystemClock.sleep(durationMs / 10);
            float fraction = step / 10f;
            send(MotionEvent.ACTION_MOVE, MotionEvent.BUTTON_PRIMARY, 0,
                    x1 + (x2 - x1) * fraction, y1 + (y2 - y1) * fraction);
        }
        send(MotionEvent.ACTION_BUTTON_RELEASE, 0, MotionEvent.BUTTON_PRIMARY, x2, y2);
        send(MotionEvent.ACTION_UP, 0, 0, x2, y2);
        // UP may snap the bubble to an edge. No hover into the new geometry.
    }

    private void cleanup() {
        if (!possiblyDown) return;
        // A terminal event remains constrained to the original mapper UID/display, even if focus
        // changed or the overlay disappeared. Never send a replacement DOWN or an unscoped CANCEL.
        try {
            boolean admitted = injectMouse(MotionEvent.ACTION_CANCEL, 0, 0, lastX, lastY);
            System.out.println("OWN_MOUSE_CLEANUP cancelAdmitted=" + admitted);
            if (admitted) possiblyDown = false;
        } catch (Exception failure) {
            System.err.println("OWN_MOUSE_CLEANUP unverified=" + failure.getClass().getSimpleName());
        }
    }

    public static void main(String[] args) {
        try { run(args); System.exit(0); }
        catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }

    private static void run(String[] args) throws Exception {
        if (android.os.Process.myUid() != 2000) throw new SecurityException("ADB shell UID 2000 required");
        boolean right = args.length == 3 && "rightclick".equals(args[0]);
        boolean left = args.length == 3 && "click".equals(args[0]);
        boolean drag = (args.length == 5 || args.length == 6) && "drag".equals(args[0]);
        boolean overlay = args.length == 3 && "overlayclick".equals(args[0]);
        boolean overlayDrag = (args.length == 5 || args.length == 6) && "overlaydrag".equals(args[0]);
        if (!right && !left && !drag && !overlay && !overlayDrag)
            throw new IllegalArgumentException("click/rightclick x y | drag x1 y1 x2 y2 [durationMs] | overlayclick x y | overlaydrag x1 y1 x2 y2 [durationMs]");
        float x1 = Float.parseFloat(args[1]), y1 = Float.parseFloat(args[2]);
        float x2 = (drag || overlayDrag) ? Float.parseFloat(args[3]) : x1,
                y2 = (drag || overlayDrag) ? Float.parseFloat(args[4]) : y1;
        int duration = args.length == 6 ? Integer.parseInt(args[5]) : 300;
        if (duration < 150 || duration > 2000) throw new IllegalArgumentException("Drag nominal duration must be 150..2000 ms");
        if (overlay || overlayDrag) {
            JSONObject state = requireIdleOverlay();
            if (overlayDrag && (!"paused".equals(state.getString("mode")) || !state.getBoolean("bubbleVisible")))
                throw new SecurityException("Paused own bubble required for drag; no mouse event sent");
        } else requireIdleEditor();
        Looper.prepareMainLooper();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context context = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        Class<?> connectionType = Class.forName("android.app.IUiAutomationConnection");
        Object connection = Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance();
        UiAutomation automation = (UiAutomation) UiAutomation.class.getConstructor(Looper.class, connectionType)
                .newInstance(Looper.myLooper(), connection);
        UiAutomation.class.getMethod("connect", int.class).invoke(automation,
                UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        OwnEditorPointer pointer = null;
        try {
            AccessibilityServiceInfo info = automation.getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            automation.setServiceInfo(info);
            SystemClock.sleep(150);
            pointer = new OwnEditorPointer(automation, context, overlay, overlayDrag);
            if (overlayDrag) pointer.dragOverlay(x1, y1, x2, y2, duration);
            else if (overlay) pointer.clickOverlay(x1, y1);
            else if (left) pointer.clickOverlay(x1,y1);
            else if (right) pointer.clickRight(x1, y1);
            else pointer.drag(x1, y1, x2, y2, duration);
            System.out.println("OWN_EDITOR_POINTER completed=true synthetic=true gameInput=false");
        } finally {
            if (pointer != null) pointer.cleanup();
            UiAutomation.class.getMethod("disconnect").invoke(automation);
        }
    }
}
