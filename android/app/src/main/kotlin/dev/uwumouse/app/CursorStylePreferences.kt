package dev.uwumouse.app

import android.content.Context

/** Global cursor appearance; independent of game profiles and floating controls. */
object CursorStylePreferences {
    private val styles = setOf("classic", "dark", "cyan", "pink")
    fun get(context: Context): String = context.getSharedPreferences("cursor_style", Context.MODE_PRIVATE)
        .getString("style", "classic").let { if (it in styles) it!! else "classic" }
    fun set(context: Context, style: String) {
        require(style in styles) { "Estilo de cursor no válido" }
        check(context.getSharedPreferences("cursor_style", Context.MODE_PRIVATE).edit().putString("style", style).commit()) {
            "No se pudo guardar el estilo del cursor"
        }
    }
}
