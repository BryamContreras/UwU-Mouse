import android.content.ComponentName;
import android.content.Context;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.lang.reflect.Method;

/** Shell-only, InputLab-only capability experiment. Not a product injection/recovery path. */
public final class OffscreenTouchProbe {
    private static final String TARGET = "dev.uwumouse.inputlab";
    private final Object tasks, manager;
    private final Method focus, inject, display;
    private final int uid;
    private final MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[1];
    private final MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[1];
    private final long downTime = SystemClock.uptimeMillis();

    private OffscreenTouchProbe() throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context context = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        tasks = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
        focus = Class.forName("android.app.IActivityTaskManager").getMethod("getFocusedRootTaskInfo");
        uid = context.getPackageManager().getPackageUid(TARGET, 0);
        manager = context.getSystemService(Context.INPUT_SERVICE);
        inject = manager.getClass().getMethod("injectInputEvent", InputEvent.class, int.class, int.class);
        display = InputEvent.class.getMethod("setDisplayId", int.class);
        properties[0] = new MotionEvent.PointerProperties();
        properties[0].id = 0; properties[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
        coordinates[0] = new MotionEvent.PointerCoords();
        coordinates[0].pressure = 1; coordinates[0].size = 1;
    }

    private void requireFocusedLab() throws Exception {
        Object info = focus.invoke(tasks);
        ComponentName top = (ComponentName) info.getClass().getField("topActivity").get(info);
        if (top == null || !TARGET.equals(top.getPackageName()) || info.getClass().getField("displayId").getInt(info) != 0)
            throw new SecurityException("Visible InputLab on display 0 required");
    }

    private boolean send(int action, float x, float y) throws Exception {
        // Cancellation always targets the original Lab UID, including after a focus loss.
        if (action != MotionEvent.ACTION_CANCEL) requireFocusedLab();
        coordinates[0].x = x; coordinates[0].y = y;
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1,
                properties, coordinates, 0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN,
                action == MotionEvent.ACTION_CANCEL ? 32 : 0);
        try { display.invoke(event, 0); return (Boolean) inject.invoke(manager, event, 1, uid); }
        finally { event.recycle(); }
    }

    public static void main(String[] args) throws Exception {
        if (Process.myUid() != 2000 || args.length != 2) throw new SecurityException();
        int width = Integer.parseInt(args[0]), height = Integer.parseInt(args[1]);
        if (width < 100 || width > 8192 || height < 100 || height > 8192) throw new IllegalArgumentException();
        Looper.prepareMainLooper();
        OffscreenTouchProbe probe = new OffscreenTouchProbe();
        float x = width * .65f, y = height * .5f;
        boolean cancelled = false;
        try {
            if (!probe.send(MotionEvent.ACTION_DOWN, x, y)) throw new IllegalStateException("DOWN not admitted");
            float[][] points = {{width + 200, y}, {-200, y}, {x, height + 200},
                    {x, -200}, {width * 10, height * 10}, {x, y}};
            for (float[] point : points) {
                SystemClock.sleep(120);
                x = point[0]; y = point[1];
                boolean admitted = probe.send(MotionEvent.ACTION_MOVE, x, y);
                System.out.println("MOVE x=" + x + " y=" + y + " admitted=" + admitted);
                if (!admitted) throw new IllegalStateException("MOVE not admitted");
            }
        } finally {
            cancelled = probe.send(MotionEvent.ACTION_CANCEL, x, y);
            System.out.println("CANCEL admitted=" + cancelled + " target=" + TARGET + " uid=" + probe.uid);
        }
        if (!cancelled) throw new IllegalStateException("Cancellation unverified");
        System.exit(0);
    }
}
