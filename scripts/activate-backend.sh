#!/system/bin/sh
set -eu
exec 0>/data/local/tmp/uwu-mouse-start.lock
flock -n 0 || { echo "Otra activación está en curso"; exit 1; }
if pidof mobimapper_backend >/dev/null 2>&1; then echo "El backend ya está ejecutándose; vuelve a comprobar su conexión"; exit 0; fi
apk=$(pm path dev.mobimapper.mobimapper | head -n 1)
apk=${apk#package:}
case "$apk" in /data/app/*/base.apk) ;; *) echo "APK MobiMapper no encontrado"; exit 1 ;; esac
# Fixed entry point; no command strings or remote listeners are exposed by the backend.
nohup env CLASSPATH="$apk" /system/bin/app_process /system/bin --nice-name=mobimapper_backend dev.mobimapper.mobimapper.backend.ShellMain </dev/null >/data/local/tmp/mobimapper-backend.log 2>&1 &
echo "$!" >/data/local/tmp/mobimapper-backend.pid
echo "Backend solicitado. Usa Cerrar backend ADB en la app antes de reinstalar o reactivar."
