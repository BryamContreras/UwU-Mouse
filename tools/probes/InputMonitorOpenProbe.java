import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.os.Looper;
import android.os.Process;
import android.view.Display;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Ephemeral legacy monitor permission probe. No receiver, event reads, pilfer or injection. */
public final class InputMonitorOpenProbe {
    private static final String TARGET = "dev.uwumouse.inputlab";
    private static final String ACTIVITY = "dev.uwumouse.inputlab.InputLabActivity";
    private static final String PERMISSION = "android.permission.MONITOR_INPUT";

    private InputMonitorOpenProbe() { }

    public static void main(String[] args) {
        try {
            if (Process.myUid() != 2000 || args.length != 1) {
                throw new SecurityException("Shell UID 2000 and exactly one display ID required");
            }
            int displayId = Integer.parseInt(args[0]);
            if (displayId < 0) throw new IllegalArgumentException("Display ID must be nonnegative");
            if (Looper.myLooper() == null) Looper.prepareMainLooper();
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("systemMain").invoke(null);
            Context context = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
            System.exit(probe(context, displayId));
        } catch (Throwable failure) {
            Throwable cause = unwrap(failure);
            // Guard/setup failure is never reported as a monitor permission denial.
            System.err.println("{\"stage\":\"guard_or_setup\",\"monitorAttempted\":false,\"opened\":false,"
                    + "\"denied\":false,\"causeType\":" + quote(cause.getClass().getName())
                    + ",\"cause\":" + quote(reason(cause)) + "}");
            System.exit(1);
        }
    }

    private static int probe(Context context, int displayId) throws Exception {
        Object manager = context.getSystemService(Context.INPUT_SERVICE);
        if (manager == null) throw new IllegalStateException("Input service unavailable");
        Method monitor = manager.getClass().getMethod("monitorInput", String.class, int.class);
        // Resolve cleanup before opening anything. No channel contents or tokens are inspected.
        Method dispose = Class.forName("android.view.InputChannel").getMethod("dispose");
        boolean permissionGranted = context.checkPermission(PERMISSION, Process.myPid(), Process.myUid())
                == PackageManager.PERMISSION_GRANTED;
        requireFocusedLab(context, displayId);

        Object channel = null;
        Throwable failure = null;
        Throwable cleanupFailure = null;
        boolean disposed = false;
        try {
            // Exactly one attempt, exact two-argument legacy API. Never retries another API.
            channel = monitor.invoke(manager, "UwUMouse-readonly-probe", displayId);
        } catch (Throwable rejected) {
            failure = unwrap(rejected);
        } finally {
            if (channel != null) {
                try {
                    dispose.invoke(channel);
                    disposed = true;
                } catch (Throwable rejected) {
                    cleanupFailure = unwrap(rejected);
                }
            }
        }
        boolean opened = channel != null;
        boolean denied = failure instanceof SecurityException;
        String causeType = failure == null ? "" : failure.getClass().getName();
        String cause = failure == null ? (opened ? "" : "null_channel") : reason(failure);
        System.out.println("{\"stage\":\"monitor\",\"uid\":" + Process.myUid() + ",\"pid\":" + Process.myPid()
                + ",\"displayId\":" + displayId + ",\"monitorPermissionGranted\":" + permissionGranted
                + ",\"monitorAttempted\":true,\"opened\":" + opened + ",\"denied\":" + denied
                + ",\"disposed\":" + disposed + ",\"causeType\":" + quote(causeType)
                + ",\"cause\":" + quote(cause) + ",\"disposeCauseType\":"
                + quote(cleanupFailure == null ? "" : cleanupFailure.getClass().getName()) + "}");
        return opened && disposed && failure == null && cleanupFailure == null ? 0 : (denied ? 2 : 1);
    }

    private static void requireFocusedLab(Context context, int displayId) throws Exception {
        DisplayManager displays = context.getSystemService(DisplayManager.class);
        Display display = displays == null ? null : displays.getDisplay(displayId);
        if (display == null || !display.isValid() || display.getState() != Display.STATE_ON) {
            throw new SecurityException("Requested display must be available and on");
        }
        Object tasks = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
        Object info = Class.forName("android.app.IActivityTaskManager").getMethod("getFocusedRootTaskInfo").invoke(tasks);
        if (info == null) throw new SecurityException("Focused InputLab task required");
        ComponentName top = (ComponentName) info.getClass().getField("topActivity").get(info);
        int focusedDisplay = info.getClass().getField("displayId").getInt(info);
        if (top == null || !TARGET.equals(top.getPackageName()) || !ACTIVITY.equals(top.getClassName())
                || focusedDisplay != displayId) {
            throw new SecurityException("Exact focused InputLab activity on requested display required");
        }
    }

    private static Throwable unwrap(Throwable failure) {
        while (failure instanceof InvocationTargetException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure;
    }

    private static String reason(Throwable failure) {
        // Only a fixed reason is emitted; arbitrary exception messages may contain Binder tokens.
        String message = failure.getMessage();
        if (failure instanceof SecurityException) {
            return message != null && message.contains("MONITOR_INPUT")
                    ? "MONITOR_INPUT_permission_denied" : "security_denied";
        }
        return "operation_failed";
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t") + "\"";
    }
}
