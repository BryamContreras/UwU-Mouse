import android.os.Looper;
import java.lang.reflect.Method;

/** Shell-only capability probe. With no argument it never changes pointer presentation. */
public final class PointerCapabilities {
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        android.content.Context context = (android.content.Context) activityThread
                .getMethod("getSystemContext").invoke(thread);
        Object manager = context.getSystemService(android.content.Context.INPUT_SERVICE);
        for (Method method : manager.getClass().getMethods()) {
            if (method.getName().toLowerCase().contains("pointer")) System.out.println(method);
        }
        if (args.length == 1 && (args[0].equals("hide") || args[0].equals("restore"))) {
            manager.getClass().getMethod("setPointerIconType", int.class)
                    .invoke(manager, args[0].equals("hide") ? 0 : 1000);
            System.out.println("pointer request completed: " + args[0]);
        }
        System.exit(0);
    }
}
