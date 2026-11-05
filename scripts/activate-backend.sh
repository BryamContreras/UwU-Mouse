#!/system/bin/sh
set -eu
apk=$(pm path dev.mobimapper.mobimapper | head -n 1)
apk=${apk#package:}
case "$apk" in /data/app/*/base.apk) ;; *) echo "APK MobiMapper no encontrado"; exit 1 ;; esac
pm path dev.mobimapper.inputlab >/dev/null
# Fixed entry point; no command strings or remote listeners are exposed by the backend.
nohup env CLASSPATH="$apk" /system/bin/app_process /system/bin --nice-name=mobimapper_backend dev.mobimapper.mobimapper.backend.ShellMain </dev/null >/data/local/tmp/mobimapper-backend.log 2>&1 &
echo "$!" >/data/local/tmp/mobimapper-backend.pid
echo "Backend solicitado. Usa Cerrar backend ADB en la app antes de reinstalar o reactivar."
