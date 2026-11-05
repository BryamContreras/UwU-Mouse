import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayDeque;
import java.util.Arrays;

/** Shell-only test helper: inspect/click only MobiMapper's own UI without suppressing its service. */
public final class OwnUiClick {
    private static boolean labelMatches(CharSequence label, String wanted) {
        if (label == null) return false;
        String text = label.toString().trim();
        return text.equals(wanted) || text.startsWith(wanted + "\n");
    }
    public static void main(String[] args) {
        try { run(args); System.exit(0); }
        catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
    private static void run(String[] args) throws Exception {
        boolean longPress = args.length > 0 && "game_options".equals(args[0]);
        if (args.length > 0) args[0] = switch (args[0]) {
            case "game_options" -> "Jugar Free Fire";
            case "edit" -> "Editar sobre el juego";
            case "save" -> "Guardar";
            case "cancel" -> "Cancelar";
            case "lab" -> "Iniciar en laboratorio";
            case "lab_tools" -> "Laboratorio y diagnóstico";
            case "start" -> "Iniciar sesión";
            case "stop" -> "Detener";
            case "release" -> "Detener y liberar entradas";
            case "refresh" -> "Actualizar";
            case "bubble" -> "UwU Mouse · abrir opciones";
            case "profiles" -> "Perfiles";
            case "new_profile" -> "Crear perfil";
            case "profile_original" -> "Activar perfil Free Fire · principal";
            case "profile2" -> "Activar perfil Perfil 2";
            case "profile_key2" -> "Tecla de perfil Perfil 2";
            case "map" -> "Mapear";
            case "close_menu" -> "Cerrar menú";
            case "close_panel" -> "Cerrar panel";
            case "close_session" -> "Cerrar sesión";
            case "mode_control" -> "Cambiar modo: Control activado";
            case "mode_keyboard" -> "Cambiar modo: Teclado activado";
            case "fire" -> "Disparo";
            case "macro" -> "Macro";
            case "macro_point" -> "Agregar punto";
            case "wait1" -> "Espera punto 1, milisegundos";
            case "wait_accept" -> "Aceptar espera";
            case "repeat" -> "Repetir";
            case "map_bag" -> "Mapa/Mochila";
            case "repeat_interval" -> "Intervalo de repetición, milisegundos";
            case "show_controls" -> "Mostrar controles en pantalla";
            case "hide" -> "Ocultar";
            case "options" -> "Opciones del juego";
            case "freefire" -> "Free Fire";
            case "settings" -> "Ajustes";
            case "keys" -> "Teclas de sesión";
            case "controls" -> "Editar controles";
            case "play" -> "Jugar";
            case "enter" -> "Entrar al juego";
            case "end" -> "Detener y cerrar";
            default -> args[0];
        };
        if (args.length < 1 || !Arrays.asList("Editar sobre el juego", "Colocar controles",
                "Guardar", "Cancelar", "Iniciar en laboratorio", "Laboratorio y diagnóstico",
                "Iniciar sesión", "Detener", "Actualizar", "MobiMapper · abrir opciones", "Mapear", "Cerrar",
                "Ajustes", "Jugar", "Entrar al juego", "Detener y cerrar", "Editar controles", "Teclas de sesión",
                "Teclas", "WASD", "Cámara", "Acción", "Área", "Opciones del juego", "Cerrar menú", "Cerrar panel", "Cerrar sesión", "Free Fire",
                "Cambiar modo: Control activado", "Cambiar modo: Teclado activado", "Disparo", "Ocultar", "Detener y liberar entradas",
                "Macro", "Agregar punto", "Ubicar", "Asignar", "Quitar tecla de suspensión",
                "Espera punto 1, milisegundos", "Aceptar espera", "Repetir", "Mapa/Mochila",
                "Intervalo de repetición, milisegundos", "Mostrar controles en pantalla", "Jugar Free Fire",
                "UwU Mouse · abrir opciones", "Perfiles", "Crear perfil", "Activar perfil Free Fire · principal",
                "Activar perfil Perfil 2", "Tecla de perfil Perfil 2").contains(args[0])) {
            throw new IllegalArgumentException("Unsupported own UI action");
        }
        Looper.prepareMainLooper();
        Class<?> connectionType = Class.forName("android.app.IUiAutomationConnection");
        Object connection = Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance();
        UiAutomation automation = (UiAutomation) UiAutomation.class
                .getConstructor(Looper.class, connectionType).newInstance(Looper.myLooper(), connection);
        UiAutomation.class.getMethod("connect", int.class).invoke(automation, UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        try {
            AccessibilityServiceInfo info = automation.getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            automation.setServiceInfo(info);
            SystemClock.sleep(200);
            for (int attempt = 0; attempt < 5; attempt++) {
                AccessibilityNodeInfo root = automation.getRootInActiveWindow();
                ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
                if (root != null && "dev.mobimapper.mobimapper".contentEquals(root.getPackageName())) pending.add(root);
                else for (AccessibilityWindowInfo window : automation.getWindows()) {
                    if (window.getDisplayId() != 0 || window.getType() != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
                    AccessibilityNodeInfo own = window.getRoot();
                    if (own != null && "dev.mobimapper.mobimapper".contentEquals(own.getPackageName())) pending.add(own);
                }
                if (pending.isEmpty()) throw new IllegalStateException("Own MobiMapper window required; no action sent");
                AccessibilityNodeInfo scroll = null;
                while (!pending.isEmpty()) {
                    AccessibilityNodeInfo node = pending.removeFirst();
                    if (node.isVisibleToUser() && (labelMatches(node.getText(), args[0]) ||
                            labelMatches(node.getContentDescription(), args[0]))) {
                        for (int parent = 0; node != null && parent < 5; parent++, node = node.getParent()) {
                            if (node.isEnabled() && (longPress ? node.isLongClickable() : node.isClickable())) {
                                System.out.println("own_ui_click accepted=" + node.performAction(longPress ? AccessibilityNodeInfo.ACTION_LONG_CLICK : AccessibilityNodeInfo.ACTION_CLICK));
                                return;
                            }
                        }
                        throw new IllegalStateException("Own control disabled");
                    }
                    if (node.isScrollable()) scroll = node;
                    for (int i = 0; i < node.getChildCount(); i++) {
                        AccessibilityNodeInfo child = node.getChild(i);
                        if (child != null) pending.addLast(child);
                    }
                }
                if (scroll == null || args.length < 2) break;
                int action = "backward".equals(args[1]) ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
                if (!scroll.performAction(action)) break;
                SystemClock.sleep(250);
            }
            throw new IllegalStateException("Own control not visible; no click sent");
        } finally { UiAutomation.class.getMethod("disconnect").invoke(automation); }
    }
}
