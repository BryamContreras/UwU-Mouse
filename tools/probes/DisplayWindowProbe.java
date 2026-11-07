import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.graphics.Rect;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Shell-only metadata observer. No clicks, injection, node text, titles or service suppression. */
public final class DisplayWindowProbe {
    public static void main(String[] args) {
        try {
            if (Process.myUid() != 2000 || args.length != 0) throw new SecurityException();
            run();
            System.exit(0);
        } catch (Throwable failure) {
            System.err.println("DisplayWindowProbe failed: " + failure.getClass().getSimpleName());
            System.exit(1);
        }
    }

    private static void run() throws Exception {
        Looper.prepareMainLooper();
        Class<?> connectionType = Class.forName("android.app.IUiAutomationConnection");
        Object connection = Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance();
        UiAutomation automation = (UiAutomation) UiAutomation.class
                .getConstructor(Looper.class, connectionType).newInstance(Looper.myLooper(), connection);
        UiAutomation.class.getMethod("connect", int.class).invoke(automation,
                UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        try {
            AccessibilityServiceInfo info = automation.getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            automation.setServiceInfo(info);
            SystemClock.sleep(200);
            SparseArray<List<AccessibilityWindowInfo>> byDisplay = automation.getWindowsOnAllDisplays();
            JSONArray displays = new JSONArray();
            JSONArray windows = new JSONArray();
            Rect bounds = new Rect();
            for (int index = 0; index < byDisplay.size(); index++) {
                displays.put(byDisplay.keyAt(index));
                List<AccessibilityWindowInfo> visible = byDisplay.valueAt(index);
                if (visible == null) continue;
                for (AccessibilityWindowInfo window : visible) {
                    window.getBoundsInScreen(bounds);
                    AccessibilityNodeInfo root = window.getRoot();
                    CharSequence owner = root == null ? null : root.getPackageName();
                    JSONObject metadata = new JSONObject()
                            .put("id", window.getId())
                            .put("type", window.getType())
                            .put("displayId", window.getDisplayId())
                            .put("layer", window.getLayer())
                            .put("bounds", new JSONArray().put(bounds.left).put(bounds.top).put(bounds.right).put(bounds.bottom))
                            .put("focused", window.isFocused())
                            .put("active", window.isActive())
                            .put("packageName", owner == null ? JSONObject.NULL : owner.toString());
                    windows.put(metadata);
                }
            }
            System.out.println(new JSONObject().put("capturedUptimeMs", SystemClock.uptimeMillis())
                    .put("displayIds", displays).put("windows", windows));
        } finally { UiAutomation.class.getMethod("disconnect").invoke(automation); }
    }
}
