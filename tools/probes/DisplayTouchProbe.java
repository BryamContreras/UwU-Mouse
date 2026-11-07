import android.content.ComponentName;
import android.content.Context;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.Display;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.lang.reflect.Method;

/** Shell-only, InputLab-only display-routing preflight. Never starts the product engine. */
public final class DisplayTouchProbe {
    private static final String TARGET = "dev.mobimapper.inputlab";
    private static final String ACTIVITY = "dev.mobimapper.inputlab.InputLabActivity";
    private final Object tasks, manager;
    private final Method focus, inject, setDisplay;
    private final int targetUid, displayId;
    private final MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[1];
    private final MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[1];
    private final long downTime = SystemClock.uptimeMillis();

    private DisplayTouchProbe(int displayId, int width, int height) throws Exception {
        this.displayId = displayId;
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context context = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        Display targetDisplay = context.getSystemService(DisplayManager.class).getDisplay(displayId);
        if (targetDisplay == null || !targetDisplay.isValid()) throw new SecurityException("Display unavailable");
        Point actual = new Point();
        targetDisplay.getRealSize(actual);
        if (actual.x != width || actual.y != height) throw new SecurityException("Display geometry changed");
        targetUid = context.getPackageManager().getPackageUid(TARGET, 0);
        if (targetUid < 10000) throw new SecurityException("Invalid InputLab UID");
        tasks = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
        focus = Class.forName("android.app.IActivityTaskManager").getMethod("getFocusedRootTaskInfo");
        manager = context.getSystemService(Context.INPUT_SERVICE);
        inject = manager.getClass().getMethod("injectInputEvent", InputEvent.class, int.class, int.class);
        setDisplay = InputEvent.class.getMethod("setDisplayId", int.class);
        properties[0] = new MotionEvent.PointerProperties();
        properties[0].id = 0;
        properties[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
        coordinates[0] = new MotionEvent.PointerCoords();
        coordinates[0].pressure = 1f;
        coordinates[0].size = 1f;
    }

    private void requireFocusedLab() throws Exception {
        Object info = focus.invoke(tasks);
        if (info == null) throw new SecurityException("No focused root task");
        ComponentName top = (ComponentName) info.getClass().getField("topActivity").get(info);
        int focusedDisplay = info.getClass().getField("displayId").getInt(info);
        if (top == null || !TARGET.equals(top.getPackageName()) || !ACTIVITY.equals(top.getClassName()) ||
                focusedDisplay != displayId) throw new SecurityException("Focused InputLab on original display required");
    }

    private boolean send(int action, float x, float y) throws Exception {
        if (action != MotionEvent.ACTION_CANCEL) requireFocusedLab();
        coordinates[0].x = x;
        coordinates[0].y = y;
        long eventTime = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, 1,
                properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN,
                action == MotionEvent.ACTION_CANCEL ? 32 : 0);
        boolean admitted;
        try {
            // Both normal traffic and unconditional cleanup retain the original display and UID.
            setDisplay.invoke(event, displayId);
            admitted = (Boolean) inject.invoke(manager, event, action == MotionEvent.ACTION_CANCEL ? 2 : 1, targetUid);
        } finally { event.recycle(); }
        System.out.println("{\"action\":" + action + ",\"displayId\":" + displayId +
                ",\"targetUid\":" + targetUid + ",\"downTime\":" + downTime +
                ",\"eventTime\":" + eventTime + ",\"admitted\":" + admitted + "}");
        return admitted;
    }

    public static void main(String[] args) throws Exception {
        if (Process.myUid() != 2000 || args.length != 3) throw new SecurityException("ADB shell and three geometry arguments required");
        int displayId = Integer.parseInt(args[0]);
        int width = Integer.parseInt(args[1]), height = Integer.parseInt(args[2]);
        if (displayId < 0 || width < 320 || width > 8192 || height < 100 || height > 8192)
            throw new IllegalArgumentException("Invalid display geometry");
        Looper.prepareMainLooper();
        DisplayTouchProbe probe = new DisplayTouchProbe(displayId, width, height);
        float x = width * .5f, y = height * .5f;
        boolean cancelled;
        try {
            if (!probe.send(MotionEvent.ACTION_DOWN, x, y)) throw new IllegalStateException("DOWN not admitted");
            SystemClock.sleep(120);
            x += 100f;
            if (!probe.send(MotionEvent.ACTION_MOVE, x, y)) throw new IllegalStateException("MOVE not admitted");
            SystemClock.sleep(120);
        } finally { cancelled = probe.send(MotionEvent.ACTION_CANCEL, x, y); }
        if (!cancelled) throw new IllegalStateException("Cancellation unverified");
        System.out.println("{\"complete\":true,\"target\":\"" + TARGET + "\",\"displayId\":" + displayId + "}");
        System.exit(0);
    }
}
