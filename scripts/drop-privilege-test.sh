#!/system/bin/sh
set -eu
worker=${1-}
guardian=${2-}
case "$worker:$guardian" in *[!0-9:]*|:|:*|*:) exit 2 ;; esac
for pid in "$worker" "$guardian"; do
  line=$(ps -p "$pid" -o UID,NAME | tail -n 1)
  set -- $line
  [ "${1-}" = 2000 ] || exit 3
  case "${2-}" in uwumouse_backend|uwumouse_injector) ;; *) exit 4 ;; esac
done
# Freeze injector first so guardian EOF cleanup cannot race this complete-authority-loss test.
kill -STOP "$worker"
kill -KILL "$guardian"
kill -KILL "$worker"
echo "removed both authorized processes; controller and InputLab preserved"
