import android.content.ComponentName;
import android.content.Context;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.lang.reflect.Method;

/** Isolated shell capability experiment, only the already selected Free Fire package. */
public final class ContinuousCameraProbe {
    private static final String TARGET = "com.dts.freefireth";
    private final Object tasks, manager;
    private final Method focus, inject, setDisplay;
    private final int targetUid;
    private final MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[1];
    private final MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[1];
    private final long downTime = SystemClock.uptimeMillis();

    private ContinuousCameraProbe() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        Context context = (Context) at.getMethod("getSystemContext").invoke(thread);
        targetUid = context.getPackageManager().getPackageUid(TARGET, 0);
        tasks = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
        focus = Class.forName("android.app.IActivityTaskManager").getMethod("getFocusedRootTaskInfo");
        manager = context.getSystemService(Context.INPUT_SERVICE);
        inject = manager.getClass().getMethod("injectInputEvent", InputEvent.class, int.class, int.class);
        setDisplay = InputEvent.class.getMethod("setDisplayId", int.class);
        properties[0] = new MotionEvent.PointerProperties();
        properties[0].id = 9; properties[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
        coords[0] = new MotionEvent.PointerCoords();
        coords[0].pressure = 1; coords[0].size = 1;
    }

    private boolean send(int action, float x, float y) throws Exception {
        if (action != MotionEvent.ACTION_CANCEL) {
            Object info = focus.invoke(tasks);
            ComponentName top = (ComponentName) info.getClass().getField("topActivity").get(info);
            if (top == null || !TARGET.equals(top.getPackageName()) || info.getClass().getField("displayId").getInt(info) != 0)
                throw new SecurityException("Free Fire must remain focused on display 0");
        }
        coords[0].x = x; coords[0].y = y;
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1,
                properties, coords, 0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN,
                action == MotionEvent.ACTION_CANCEL ? 32 : 0);
        try { setDisplay.invoke(event, 0); return (Boolean) inject.invoke(manager, event, 1, targetUid); }
        finally { event.recycle(); }
    }

    public static void main(String[] args) throws Exception {
        if (Process.myUid() != 2000 || args.length != 4) throw new SecurityException();
        int width = Integer.parseInt(args[0]), height = Integer.parseInt(args[1]);
        float x = Float.parseFloat(args[2]), y = Float.parseFloat(args[3]);
        if (width < 100 || width > 8192 || height < 100 || height > 8192 ||
                !Float.isFinite(x) || !Float.isFinite(y) || x < width * .5f || x >= width || y < 0 || y >= height)
            throw new IllegalArgumentException("An inside right-hand camera anchor is required");
        Looper.prepareMainLooper();
        ContinuousCameraProbe probe = new ContinuousCameraProbe();
        int moves = 0;
        try {
            if (!probe.send(MotionEvent.ACTION_DOWN, x, y)) throw new IllegalStateException("DOWN rejected");
            System.out.println("BEGIN onePointer=9 down=" + probe.downTime + " anchor=" + x + "," + y + " targetUid=" + probe.targetUid);
            // About 7 seconds. One uninterrupted contact, no centre restart or other action.
            for (int i = 0; i < 270; i++) {
                SystemClock.sleep(20);
                x += i < 90 ? 32 : -32;
                if (!probe.send(MotionEvent.ACTION_MOVE, x, y)) throw new IllegalStateException("MOVE rejected at " + x);
                moves++;
                if (i == 89 || i == 269) System.out.println("PHASE moves=" + moves + " x=" + x + " y=" + y);
            }
        } finally {
            boolean cancelled = probe.send(MotionEvent.ACTION_CANCEL, x, y);
            System.out.println("END moves=" + moves + " down=" + probe.downTime + " last=" + x + "," + y + " CANCEL=" + cancelled);
            if (!cancelled) throw new IllegalStateException("Cancellation unverified");
        }
        System.exit(0);
    }
}
