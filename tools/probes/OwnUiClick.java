import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Shell-only test helper: inspect/click only MobiMapper's own UI without suppressing its service. */
public final class OwnUiClick {
    private static final String OWN = "dev.mobimapper.mobimapper";
    private static boolean knownOverlayTitle(CharSequence title) {
        return title != null && ("MobiMapper \u00b7 Editor".contentEquals(title) ||
                "MobiMapper \u00b7 Burbuja".contentEquals(title) || "MobiMapper \u00b7 Opciones".contentEquals(title));
    }
    private static boolean knownOwnOverlayRole(AccessibilityWindowInfo window, AccessibilityNodeInfo root) {
        if (root == null || root.getWindowId() != window.getId() || !OWN.contentEquals(root.getPackageName())) return false;
        if (knownOverlayTitle(window.getTitle())) return true;
        if (window.getTitle() != null) return false;
        CharSequence role = root.getContentDescription();
        return role != null && ("UwU Mouse \u00b7 editor".contentEquals(role) ||
                "UwU Mouse \u00b7 opciones".contentEquals(role) || "UwU Mouse \u00b7 abrir opciones".contentEquals(role));
    }
    private static boolean ownNode(AccessibilityNodeInfo node, Set<Integer> ownWindows) {
        return node != null && node.getPackageName() != null && OWN.contentEquals(node.getPackageName()) &&
                ownWindows.contains(node.getWindowId());
    }
    private static boolean labelMatches(CharSequence label, String wanted) {
        if (label == null) return false;
        String text = label.toString().trim();
        // Flutter's ExpansionTile can merge its expanded-state hint before/after the title.
        if ("Laboratorio y diagnóstico".equals(wanted)) return text.contains(wanted);
        return text.equals(wanted) || text.startsWith(wanted + "\n");
    }
    public static void main(String[] args) {
        try { run(args); System.exit(0); }
        catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
    private static void run(String[] args) throws Exception {
        int selectedDisplay = 0;
        if (args.length > 0 && "--display".equals(args[0])) {
            if (args.length < 3) throw new IllegalArgumentException("Display and own UI alias required");
            selectedDisplay = Integer.parseInt(args[1]);
            if (selectedDisplay < 0) throw new IllegalArgumentException("Invalid display");
            args = Arrays.copyOfRange(args, 2, args.length);
        }
        boolean longPress = args.length > 0 && "game_options".equals(args[0]);
        if (args.length > 0) args[0] = switch (args[0]) {
            case "game_options" -> "Jugar Free Fire";
            case "map_freefire" -> "Mapear Free Fire";
            case "edit" -> "Editar sobre el juego";
            case "save" -> "Guardar";
            case "cancel" -> "Cancelar";
            case "lab" -> "Iniciar en laboratorio";
            case "probe" -> "Probar inyección sintética";
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
            case "delete_profile4" -> "Eliminar perfil Perfil 4";
            case "confirm_delete_profile" -> "Eliminar";
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
            case "connection_settings" -> "Ajustes de conexión";
            case "enable_accessibility" -> "Activar accesibilidad";
            case "activate_motor" -> "Activar motor";
            case "allow_overlays" -> "Permitir ventanas";
            case "shutdown_backend" -> "Cerrar backend ADB";
            case "activation_done" -> "Listo";
            case "check_service" -> "Comprobar servicio";
            case "activate_phone" -> "Activar en este teléfono";
            case "wireless_settings" -> "Abrir Depuración inalámbrica";
            case "reactivate" -> "Reactivar sin código";
            case "keys" -> "Teclas de sesión";
            case "controls" -> "Editar controles";
            case "play" -> "Jugar";
            case "enter" -> "Entrar al juego";
            case "end" -> "Detener y cerrar";
            default -> args[0];
        };
        if (args.length < 1 || !Arrays.asList("Editar sobre el juego", "Colocar controles",
                "Guardar", "Cancelar", "Iniciar en laboratorio", "Laboratorio y diagnóstico", "Probar inyección sintética",
                "Iniciar sesión", "Detener", "Actualizar", "MobiMapper · abrir opciones", "Mapear", "Cerrar",
                "Ajustes", "Comprobar servicio", "Jugar", "Entrar al juego", "Detener y cerrar", "Editar controles", "Teclas de sesión",
                "Teclas", "WASD", "Cámara", "Acción", "Área", "Opciones del juego", "Cerrar menú", "Cerrar panel", "Cerrar sesión", "Free Fire",
                "Cambiar modo: Control activado", "Cambiar modo: Teclado activado", "Disparo", "Ocultar", "Detener y liberar entradas",
                "Macro", "Agregar punto", "Ubicar", "Asignar", "Quitar tecla de suspensión",
                "Espera punto 1, milisegundos", "Aceptar espera", "Repetir", "Mapa/Mochila",
                "Intervalo de repetición, milisegundos", "Mostrar controles en pantalla", "Jugar Free Fire", "Mapear Free Fire",
                "UwU Mouse · abrir opciones", "Perfiles", "Crear perfil", "Activar perfil Free Fire · principal",
                "Activar perfil Perfil 2", "Tecla de perfil Perfil 2", "Activar en este teléfono",
                "Abrir Depuración inalámbrica", "Reactivar sin código", "Ajustes de conexión",
                "Activar accesibilidad", "Activar motor", "Permitir ventanas", "Cerrar backend ADB", "Listo",
                "Eliminar perfil Perfil 4", "Eliminar").contains(args[0])) {
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
                ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
                Set<Integer> ownWindows = new HashSet<>();
                List<AccessibilityWindowInfo> windows = automation.getWindowsOnAllDisplays().get(selectedDisplay);
                if (windows != null) for (AccessibilityWindowInfo window : windows) {
                    boolean ownExternalOverlay = selectedDisplay != 0 &&
                            window.getType() == AccessibilityWindowInfo.TYPE_SYSTEM;
                    if (window.getDisplayId() != selectedDisplay ||
                            (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION &&
                             window.getType() != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                             !ownExternalOverlay)) continue;
                    AccessibilityNodeInfo root = window.getRoot();
                    if (root != null && root.getPackageName() != null && OWN.contentEquals(root.getPackageName()) &&
                            root.getWindowId() == window.getId() && (!ownExternalOverlay || knownOwnOverlayRole(window, root))) {
                        ownWindows.add(window.getId());
                        pending.add(root);
                    }
                }
                if (pending.isEmpty()) throw new IllegalStateException("Own MobiMapper window on selected display required; no action sent");
                AccessibilityNodeInfo scroll = null;
                while (!pending.isEmpty()) {
                    AccessibilityNodeInfo node = pending.removeFirst();
                    if (!ownNode(node, ownWindows)) continue;
                    if (node.isVisibleToUser() && (labelMatches(node.getText(), args[0]) ||
                            labelMatches(node.getContentDescription(), args[0]))) {
                        for (int parent = 0; ownNode(node, ownWindows) && parent < 5; parent++, node = node.getParent()) {
                            if (node.isEnabled() && (longPress ? node.isLongClickable() : node.isClickable())) {
                                System.out.println("own_ui_click accepted=" + node.performAction(longPress ? AccessibilityNodeInfo.ACTION_LONG_CLICK : AccessibilityNodeInfo.ACTION_CLICK) + " display=" + selectedDisplay);
                                return;
                            }
                        }
                        throw new IllegalStateException("Own control disabled");
                    }
                    if (node.isVisibleToUser() && node.isScrollable()) scroll = node;
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
