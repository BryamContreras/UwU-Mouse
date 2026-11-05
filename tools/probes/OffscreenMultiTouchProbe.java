import android.content.ComponentName;
import android.content.Context;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.lang.reflect.Method;
import java.util.Arrays;

/** Shell-only, fixed-InputLab capability probe. Never used by the product mapper/guardian. */
public final class OffscreenMultiTouchProbe {
    private static final String TARGET = "dev.mobimapper.inputlab";
    private static final long STEP_MILLIS = 120;
    private final Object tasks, manager;
    private final Method focus, inject, display;
    private final int uid;
    // Before an ambiguous call this includes its complete possible set, even an attempted UP.
    private Frame possible;
    private long lastDownTime;
    private int sequence;

    private OffscreenMultiTouchProbe() throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context context = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        tasks = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
        focus = Class.forName("android.app.IActivityTaskManager").getMethod("getFocusedRootTaskInfo");
        uid = context.getPackageManager().getPackageUid(TARGET, 0);
        if (uid < 10000) throw new SecurityException("Expected the installed InputLab app UID");
        manager = context.getSystemService(Context.INPUT_SERVICE);
        inject = manager.getClass().getMethod("injectInputEvent", InputEvent.class, int.class, int.class);
        display = InputEvent.class.getMethod("setDisplayId", int.class);
    }

    private void requireFocusedLab() throws Exception {
        Object info = focus.invoke(tasks);
        ComponentName top = (ComponentName) info.getClass().getField("topActivity").get(info);
        if (top == null || !TARGET.equals(top.getPackageName()) || info.getClass().getField("displayId").getInt(info) != 0)
            throw new SecurityException("Visible InputLab on display 0 required");
    }

    private boolean injectFrame(Frame frame, int mode) throws Exception {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[frame.ids.length];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[frame.ids.length];
        for (int i = 0; i < frame.ids.length; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = frame.ids[i];
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coordinates[i] = new MotionEvent.PointerCoords();
            coordinates[i].x = frame.xs[i]; coordinates[i].y = frame.ys[i];
            coordinates[i].pressure = 1; coordinates[i].size = 1;
        }
        MotionEvent event = MotionEvent.obtain(frame.downTime, SystemClock.uptimeMillis(), frame.action,
                frame.ids.length, properties, coordinates, 0, 0, 1, 1, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, (frame.action & 255) == MotionEvent.ACTION_CANCEL ? 32 : 0);
        try {
            display.invoke(event, 0);
            return (Boolean) inject.invoke(manager, event, mode, uid);
        } finally { event.recycle(); }
    }

    private void send(String label, int action, int[] ids, float[] xs, float[] ys) throws Exception {
        requireFocusedLab();
        if ((action & 255) == MotionEvent.ACTION_DOWN) {
            if (possible != null) throw new IllegalStateException("Previous stream is not closed");
            lastDownTime = SystemClock.uptimeMillis();
        }
        Frame frame = new Frame(action, lastDownTime, ids, xs, ys);
        possible = frame;
        int number = ++sequence;
        System.out.println("ATTEMPT sequence=" + number + " label=" + label + " " + frame.describe()
                + " target=" + TARGET + " uid=" + uid + " display=0");
        boolean admitted = injectFrame(frame, (action & 255) == MotionEvent.ACTION_CANCEL ? 2 : 1);
        System.out.println("ACK sequence=" + number + " label=" + label + " admitted=" + admitted
                + " downTime=" + lastDownTime + " uptime=" + SystemClock.uptimeMillis());
        if (!admitted) throw new IllegalStateException(label + " not admitted; possible stream retained");
        possible = frame.afterAcknowledgement();
    }

    private void pause() { SystemClock.sleep(STEP_MILLIS); }

    private void run(int width, int height) throws Exception {
        final float joyX = width * .20f, joyY = height * .70f;
        final float fireX = width * .85f, fireY = height * .80f;
        final float cameraX = width * .65f, cameraY = height * .40f;
        send("joy-down", MotionEvent.ACTION_DOWN, new int[] {0}, new float[] {joyX}, new float[] {joyY});
        pause();
        send("fire-down", MotionEvent.ACTION_POINTER_DOWN | (1 << 8), new int[] {0, 1},
                new float[] {joyX, fireX}, new float[] {joyY, fireY});
        pause();
        send("camera-down", MotionEvent.ACTION_POINTER_DOWN | (2 << 8), new int[] {0, 1, 9},
                new float[] {joyX, fireX, cameraX}, new float[] {joyY, fireY, cameraY});
        float[][] outside = {
                {width + 200f, cameraY}, {-200f, cameraY},
                {cameraX, -200f}, {cameraX, height + 200f},
                {width * 2f + 200f, height * 2f + 200f}
        };
        for (int i = 0; i < outside.length; i++) {
            pause();
            send("outside-move-" + (i + 1), MotionEvent.ACTION_MOVE, new int[] {0, 1, 9},
                    new float[] {joyX, fireX, outside[i][0]}, new float[] {joyY, fireY, outside[i][1]});
        }
        // Lift camera at its last off-display point. Never MOVE it back to the display.
        float[] last = outside[outside.length - 1];
        pause();
        send("camera-up-outside", MotionEvent.ACTION_POINTER_UP | (2 << 8), new int[] {0, 1, 9},
                new float[] {joyX, fireX, last[0]}, new float[] {joyY, fireY, last[1]});
        pause();
        send("fire-up", MotionEvent.ACTION_POINTER_UP | (1 << 8), new int[] {0, 1},
                new float[] {joyX, fireX}, new float[] {joyY, fireY});
        pause();
        send("joy-up", MotionEvent.ACTION_UP, new int[] {0}, new float[] {joyX}, new float[] {joyY});

        pause();
        send("single-camera-down", MotionEvent.ACTION_DOWN, new int[] {9}, new float[] {cameraX}, new float[] {cameraY});
        pause();
        send("single-camera-move-outside", MotionEvent.ACTION_MOVE, new int[] {9}, new float[] {-200f}, new float[] {-200f});
        pause();
        send("single-camera-cancel-outside", MotionEvent.ACTION_CANCEL, new int[] {9}, new float[] {-200f}, new float[] {-200f});
    }

    private boolean cleanup() {
        Frame candidate = possible;
        if (candidate == null) {
            System.out.println("CLEANUP pending=false lastDownTime=" + lastDownTime);
            return true;
        }
        Frame cancel = new Frame(MotionEvent.ACTION_CANCEL, candidate.downTime, candidate.ids, candidate.xs, candidate.ys);
        possible = cancel;
        // Cleanup still targets only Lab's original UID/display. Do not withhold CANCEL on focus loss.
        System.out.println("CLEANUP pending=true attemptedAction=" + candidate.action + " " + cancel.describe()
                + " target=" + TARGET + " uid=" + uid + " display=0");
        try {
            boolean admitted = injectFrame(cancel, 2);
            if (admitted) possible = null;
            System.out.println("CLEANUP_CANCEL admitted=" + admitted + " lastDownTime=" + lastDownTime);
            return admitted;
        } catch (Exception error) {
            System.out.println("CLEANUP_CANCEL admitted=false exception=" + error.getClass().getName()
                    + " lastDownTime=" + lastDownTime);
            return false;
        }
    }

    private static final class Frame {
        final int action;
        final long downTime;
        final int[] ids;
        final float[] xs, ys;

        Frame(int action, long downTime, int[] ids, float[] xs, float[] ys) {
            if (ids.length < 1 || ids.length > 3 || ids.length != xs.length || ids.length != ys.length)
                throw new IllegalArgumentException("Invalid pointer arrays");
            int bits = 0;
            for (int i = 0; i < ids.length; i++) {
                if (ids[i] < 0 || ids[i] > 9 || (bits & (1 << ids[i])) != 0 || !Float.isFinite(xs[i]) || !Float.isFinite(ys[i]))
                    throw new IllegalArgumentException("Invalid pointer");
                bits |= 1 << ids[i];
            }
            int masked = action & 255, index = action >>> 8;
            if (index >= ids.length || (masked != MotionEvent.ACTION_DOWN && masked != MotionEvent.ACTION_UP
                    && masked != MotionEvent.ACTION_MOVE && masked != MotionEvent.ACTION_CANCEL
                    && masked != MotionEvent.ACTION_POINTER_DOWN && masked != MotionEvent.ACTION_POINTER_UP))
                throw new IllegalArgumentException("Invalid action");
            this.action = action; this.downTime = downTime;
            this.ids = ids.clone(); this.xs = xs.clone(); this.ys = ys.clone();
        }

        Frame afterAcknowledgement() {
            int masked = action & 255;
            if (masked == MotionEvent.ACTION_UP || masked == MotionEvent.ACTION_CANCEL) return null;
            if (masked != MotionEvent.ACTION_POINTER_UP) return new Frame(MotionEvent.ACTION_MOVE, downTime, ids, xs, ys);
            int removed = action >>> 8;
            int[] nextIds = new int[ids.length - 1];
            float[] nextXs = new float[nextIds.length], nextYs = new float[nextIds.length];
            for (int i = 0, j = 0; i < ids.length; i++) if (i != removed) {
                nextIds[j] = ids[i]; nextXs[j] = xs[i]; nextYs[j++] = ys[i];
            }
            return new Frame(MotionEvent.ACTION_MOVE, downTime, nextIds, nextXs, nextYs);
        }

        String describe() {
            return "action=" + action + " downTime=" + downTime + " ids=" + Arrays.toString(ids)
                    + " xs=" + Arrays.toString(xs) + " ys=" + Arrays.toString(ys);
        }
    }

    public static void main(String[] args) throws Exception {
        if (Process.myUid() != 2000 || args.length != 2) throw new SecurityException("ADB shell and width/height required");
        int width = Integer.parseInt(args[0]), height = Integer.parseInt(args[1]);
        if (width < 100 || width > 8192 || height < 100 || height > 8192) throw new IllegalArgumentException("Invalid display dimensions");
        Looper.prepareMainLooper();
        OffscreenMultiTouchProbe probe = new OffscreenMultiTouchProbe();
        Throwable failure = null;
        boolean completed = false;
        boolean released;
        try { probe.run(width, height); completed = true; }
        catch (Throwable error) { failure = error; }
        finally { released = probe.cleanup(); }
        System.out.println("RESULT completed=" + completed + " releaseAdmitted=" + released
                + " frames=" + probe.sequence + " lastDownTime=" + probe.lastDownTime
                + " receiverVerified=false target=" + TARGET);
        if (failure != null) failure.printStackTrace(System.err);
        System.exit(completed && released ? 0 : 1);
    }
}
