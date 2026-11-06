package dev.mobimapper.mobimapper.activation

import dev.mobimapper.mobimapper.session.SessionMode

/** Control-plane validation. This code is never called by the mapping engine. */
object ActivationPolicy {
    fun pairingCode(value:String):String = value.trim().also {
        require(it.matches(Regex("[0-9]{6}"))) { "Escribe los seis dígitos del código de Android" }
    }
    fun port(value:Int):Int = value.also { require(it in 1..65535) { "Puerto no válido" } }
    fun requireIdle(mode:SessionMode,active:Boolean,transition:Boolean,contacts:Int,releaseUnverified:Boolean,
                    sessionOpen:Boolean,editor:Boolean,captured:Boolean) {
        // A dead backend pauses an already closed session. Its empty, released state
        // may be activated again; an open paused game still belongs to that session.
        val closedMode=mode==SessionMode.STOPPED || mode==SessionMode.PAUSED
        check(closedMode && !active && !transition && contacts==0 && !releaseUnverified && !sessionOpen && !editor && !captured) {
            "Cierra la sesión y espera la liberación de los controles antes de activar"
        }
    }
    /** Fixed local bootstrap: no user-controlled command, package, path or target. */
    val bootstrap = """
        if [ "${'$'}(id -u)" != 2000 ]; then echo UWU_WRONG_UID; exit 1; fi
        exec 0>/data/local/tmp/uwu-mouse-start.lock
        if ! flock -n 0; then echo UWU_START_BUSY; exit 1; fi
        if pidof mobimapper_backend >/dev/null 2>&1; then echo UWU_ALREADY_RUNNING; exit 0; fi
        apk=${'$'}(pm path dev.mobimapper.mobimapper | head -n 1)
        apk=${'$'}{apk#package:}
        case "${'$'}apk" in /data/app/*/base.apk) ;; *) echo UWU_APK_NOT_FOUND; exit 1 ;; esac
        nohup env CLASSPATH="${'$'}apk" /system/bin/app_process /system/bin --nice-name=mobimapper_backend dev.mobimapper.mobimapper.backend.ShellMain </dev/null >/data/local/tmp/mobimapper-backend.log 2>&1 &
        echo "${'$'}!" >/data/local/tmp/mobimapper-backend.pid
        echo UWU_START_REQUESTED
    """.trimIndent()
}
