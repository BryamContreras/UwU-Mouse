import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.os.Process;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** Shell-only, read-only permission and API-signature inventory. Never acquires input channels. */
public final class DisplayInputCapabilities {
    private static final String[] PERMISSIONS = {
        "android.permission.MONITOR_INPUT",
        "android.permission.MANAGE_INPUT_CONSUMERS",
        "android.permission.INPUT_FILTER",
        "android.permission.INJECT_EVENTS",
        "android.permission.MANAGE_ACCESSIBILITY",
        "android.permission.INTERNAL_SYSTEM_WINDOW"
    };
    private static final String[] CLASSES = {
        "android.hardware.input.InputManager",
        "android.hardware.input.IInputManager",
        "android.view.IWindowManager",
        "android.view.InputMonitor",
        "android.view.InputEventReceiver"
    };

    private DisplayInputCapabilities() { }

    public static void main(String[] args) {
        try {
            int uid = Process.myUid();
            int pid = Process.myPid();
            System.out.println("{\"probe\":\"DisplayInputCapabilities\",\"uid\":" + uid
                    + ",\"pid\":" + pid + ",\"readOnly\":true}");
            if (uid != 2000 || args.length != 0) {
                throw new SecurityException("Shell UID 2000 and no arguments required");
            }

            // Bootstrap only: no input service is obtained and no listed method is invoked.
            if (Looper.myLooper() == null) Looper.prepareMainLooper();
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("systemMain").invoke(null);
            Context context = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
            for (String permission : PERMISSIONS) {
                int result = context.checkPermission(permission, pid, uid);
                System.out.println("{\"permission\":" + quote(permission)
                        + ",\"granted\":" + (result == PackageManager.PERMISSION_GRANTED)
                        + ",\"result\":" + result + "}");
            }

            int failures = 0;
            for (String className : CLASSES) {
                if (!listSignatures(className)) failures++;
            }
            System.out.println("{\"complete\":" + (failures == 0)
                    + ",\"classesInspected\":" + CLASSES.length + ",\"unavailableClasses\":" + failures + "}");
            System.exit(failures == 0 ? 0 : 2);
        } catch (Throwable failure) {
            Throwable cause = failure instanceof InvocationTargetException && failure.getCause() != null
                    ? failure.getCause() : failure;
            // Print a type, never exception messages, window text, Binder tokens or object values.
            System.err.println("{\"complete\":false,\"errorType\":" + quote(cause.getClass().getName()) + "}");
            System.exit(1);
        }
    }

    private static boolean listSignatures(String className) {
        try {
            Class<?> type = Class.forName(className, false, DisplayInputCapabilities.class.getClassLoader());
            Set<String> signatures = new TreeSet<>();
            for (Method method : type.getDeclaredMethods()) {
                String name = method.getName().toLowerCase(Locale.ROOT);
                if (name.contains("monitor") || name.contains("capture") || name.contains("pointer")
                        || name.contains("focusedwindow") || name.contains("inputconsumer")) {
                    signatures.add(method.toGenericString());
                }
            }
            System.out.println("{\"class\":" + quote(className)
                    + ",\"available\":true,\"matchingMethods\":" + signatures.size() + "}");
            for (String signature : signatures) {
                System.out.println("{\"class\":" + quote(className) + ",\"signature\":" + quote(signature) + "}");
            }
            return true;
        } catch (ReflectiveOperationException | LinkageError | SecurityException failure) {
            System.out.println("{\"class\":" + quote(className) + ",\"available\":false,\"errorType\":"
                    + quote(failure.getClass().getName()) + "}");
            return false;
        }
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t") + "\"";
    }
}
