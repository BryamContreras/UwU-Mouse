package dev.uwumouse.app.runtime

/** Technical provider codes remain in diagnostics; controls show an actionable message. */
internal object CaptureFailureMessages {
    fun describe(reason: String): String = when {
        reason.isBlank() -> ""
        "No external input devices match this request" in reason ->
            "Conecta el mouse o teclado USB/Bluetooth y vuelve a iniciar el mapeo."
        "No external relative mouse is available" in reason ->
            "Conecta un mouse USB o Bluetooth y vuelve a iniciar el mapeo."
        "Selected mouse is not an external relative mouse" in reason ->
            "El mouse seleccionado ya no está disponible. Conéctalo y vuelve a iniciar."
        "exclusive_input_grab_denied_errno_16" in reason ->
            "El mouse o teclado está ocupado. Detén los otros mapeadores y vuelve a iniciar."
        "release_all_external_keys_and_buttons_before_capture" in reason ->
            "Suelta todas las teclas y botones del mouse antes de iniciar."
        "device_disconnected" in reason || "input_disconnected" in reason ->
            "Se desconectó el dispositivo de entrada. Conéctalo y vuelve a iniciar."
        "lease_expired" in reason || "lease_not_renewed" in reason ->
            "La captura se detuvo. Vuelve a iniciar el mapeo."
        "capture_target_context_lost" in reason ->
            "Vuelve al juego para iniciar el mapeo."
        else -> reason
    }
}
