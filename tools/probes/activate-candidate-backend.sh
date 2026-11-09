#!/system/bin/sh
# Development-only migration: preserve the controller's RAM while loading a corrected guardian.
# This is an explicit ADB activation, not autonomous recovery or a normal session requirement.
set -eu
candidate=/data/local/tmp/uwumouse-candidate.apk
test -r "$candidate"
if pidof uwumouse_backend >/dev/null || pidof uwumouse_injector >/dev/null; then
  echo 'Existing own backend process; candidate activation refused'
  exit 1
fi
nohup env CLASSPATH="$candidate" /system/bin/app_process /system/bin --nice-name=uwumouse_backend dev.uwumouse.app.backend.ShellMain </dev/null >/data/local/tmp/uwumouse-candidate-backend.log 2>&1 &
echo "$!" >/data/local/tmp/uwumouse-backend.pid
echo 'Candidate guardian explicitly requested; inspect diagnostics before updating the installed app'
