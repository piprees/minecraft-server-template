#!/usr/bin/env bash
# Exercises the web-map functions of the 26.3 idle-tasks.sh against a scratch /data.
set -euo pipefail
src=/home/user/minecraft-server-template/.claude/worktrees/agent-a8eafc2c6921aa92d/cutover/files/scripts/idle-tasks.sh
d=/tmp/claude-0/m12/idle
rm -rf "$d"
mkdir -p "$d/map-web"
awk '/^# --- The web map/,/^# --- Main loop/' "$src" | sed "s|/data/|$d/|g" > /tmp/claude-0/m12/idle-fns.sh
SKIP_PAUSE_FILE=$d/.skip-pause
SKIP_PAUSE_OWN=$d/.skip-pause-idle
SKIP_PAUSE_OTHER=$d/.skip-pause-deploying
SKIP_PAUSE_MAP=$d/.skip-pause-map
pregen_active=false
rcon() { echo "RCON: $*" >&2; }
active() { if [[ -f "$SKIP_PAUSE_MAP" ]]; then echo "active=true"; else echo "active=false"; fi; }
# shellcheck disable=SC1091
source /tmp/claude-0/m12/idle-fns.sh
for v in 30m 6h 1d 45 0 junk; do MAP_INTERVAL=$v; echo "$v -> $(map_interval_seconds)"; done
MAP_INTERVAL=6h
map_export_due && echo "due when never run"
pregen_active=true
map_export_due || echo "not due while pre-gen runs"
pregen_active=false
start_map_export
ls -A "$d" | tr '\n' ' '; echo
map_export_due || echo "not due while active"
check_map_export; active
sleep 1
echo "34 dimensions" > "$d/map-web/.export-finished"
check_map_export; active
ls -A "$d" | tr '\n' ' '; echo
map_export_due || echo "not due within the interval"
MAP_INTERVAL=0; map_export_due || echo "off at 0"
MAP_INTERVAL=6h
sleep 1
echo "an export started by ./ops map render:"
touch "$d/.map-export-started" "$SKIP_PAUSE_MAP" "$SKIP_PAUSE_FILE"
check_map_export; active
sleep 1
echo "7 dimensions" > "$d/map-web/.export-finished"
check_map_export; active
ls -A "$d" | tr '\n' ' '; echo
