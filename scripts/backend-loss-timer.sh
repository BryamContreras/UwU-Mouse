#!/system/bin/sh
set -eu
backend_pid=${1-}
tap_x=${2-}
tap_y=${3-}
case "$backend_pid:$tap_x:$tap_y" in *[!0-9:]*|::*|*::*) echo "numeric arguments required"; exit 2 ;; esac
process_line=$(ps -p "$backend_pid" -o UID,NAME | tail -n 1)
set -- $process_line
[ "${1-}" = "2000" ] && [ "${2-}" = "mobimapper_backend" ] || exit 3
# Start the visible mapper probe. This tap is UI automation, not mapped physical input.
input tap "$tap_x" "$tap_y" &
tap_process=$!
sleep 1.4
# Recheck identity immediately before this test-only fault injection.
process_line=$(ps -p "$backend_pid" -o UID,NAME | tail -n 1)
set -- $process_line
[ "${1-}" = "2000" ] && [ "${2-}" = "mobimapper_backend" ] || exit 4
kill "$backend_pid"
wait "$tap_process"
echo "fault injected into owned backend pid=$backend_pid"
