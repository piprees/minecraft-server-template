#!/usr/bin/env bash
# maps-prove.sh: one bounded boot (under 10 minutes) for the explorer maps where players meet them. Seed 20261022,
# CI's server.properties, a copy of definitions/. In the overworld: the world border's size; locate of
# minecraft:mansion from 0,0 and from the middle of each quadrant; minecraft:shipwreck located from three points, and above each (y 200, forceloaded) a chest that takes
# minecraft:chests/shipwreck_map twice, as a shipwreck's map chest rolls it (treasure tag, radius 50, no reference).
# A minecraft:map with minecraft:map_id in a chest is a found buried treasure; none means the search failed there.
# Results -> /tmp/claude-0/k13/lane/<tag>/: server.log, driver.log, prove.log.
# Usage: P=<absolute mods/custom-dimensions-26 checkout> [MODS=<structure mod jar dir>] maps-prove.sh <tag>
set -u
TAG=${1:-p1}
P=${P:-/home/user/minecraft-server-template/.claude/worktrees/agent-af600b2f415e01031/mods/custom-dimensions-26}
case "$P" in /*) ;; *) echo "P must be an absolute path (T302)"; exit 2 ;; esac
export JAVA_HOME=/home/user/.local/jdk/jdk-25.0.5+8 PATH=/home/user/.local/jdk/jdk-25.0.5+8/bin:$PATH
OUT=/tmp/claude-0/k13/lane/$TAG
mkdir -p "$OUT"
LOG=$OUT/server.log
FIFO=$OUT/console.fifo
cd "$P" || exit 1
rm -rf "$OUT/defs"
cp -r definitions "$OUT/defs"
mkdir -p server/run
[ -f server/run/eula.txt ] || echo "eula=true" > server/run/eula.txt
rm -rf server/run/world server/run/renders
printf '%s\n' "server-port=25665" "online-mode=false" "level-seed=20261022" "spawn-protection=0" \
  "view-distance=10" "simulation-distance=6" "pause-when-empty-seconds=0" "max-tick-time=-1" > server/run/server.properties
rm -f "$FIFO"
mkfifo "$FIFO"
exec 3<> "$FIFO"
EXTRA=()
[ -n "${MODS:-}" ] && EXTRA=("-PstructureMods=$MODS")
timeout 1800 ./gradlew --offline --max-workers=3 :server:runServer "-Pdefinitions=$OUT/defs" "${EXTRA[@]}" <&3 > "$LOG" 2>&1 &
PID=$!
echo "$PID" > "$OUT/pid"
for _ in $(seq 1 300); do
  grep -q "Done (" "$LOG" && break
  grep -q "BUILD FAILED\|Failed to start the minecraft server" "$LOG" && { kill "$PID"; exit 1; }
  sleep 2
done
grep -q "Done (" "$LOG" || { echo "no boot in 600 s" >> "$OUT/driver.log"; kill "$PID"; exit 1; }
say() { echo "$1" >&3; echo "$(date +%H:%M:%S) > $1" >> "$OUT/driver.log"; }
# located <structure> <x> <z>: the x z of the nearest start from x, z in the overworld, or nothing.
located() {
  local before
  before=$(grep -c "The nearest $1 is at\|Could not find a structure of type \"$1\"" "$LOG")
  say "execute in minecraft:overworld positioned $2 0 $3 run locate structure $1"
  for _ in $(seq 1 60); do
    [ "$(grep -c "The nearest $1 is at\|Could not find a structure of type \"$1\"" "$LOG")" -gt "$before" ] && break
    sleep 2
  done
  grep "The nearest $1 is at" "$LOG" | tail -n +"$((before + 1))" | tail -1 | grep -oE '\[-?[0-9]+, ~, -?[0-9]+\]' \
    | tr -d '[],~' | awk '{print $1, $2}'
  return 0
}

say "execute in minecraft:overworld run worldborder get"
sleep 2
for from in "0 0" "6000 6000" "-6000 6000" "6000 -6000" "-6000 -6000"; do
  read -r fx fz <<< "$from"
  located minecraft:mansion "$fx" "$fz" > /dev/null
done
for from in "0 0" "1500 -1500" "-2000 1000"; do
  read -r fx fz <<< "$from"
  at=$(located minecraft:shipwreck "$fx" "$fz")
  [ -n "$at" ] || continue
  read -r sx sz <<< "$at"
  say "execute in minecraft:overworld run forceload add $sx $sz"
  sleep 15
  say "execute in minecraft:overworld run setblock $sx 200 $sz minecraft:chest"
  sleep 2
  say "execute in minecraft:overworld positioned $sx 200 $sz run loot insert $sx 200 $sz loot minecraft:chests/shipwreck_map"
  sleep 10
  say "execute in minecraft:overworld positioned $sx 200 $sz run loot insert $sx 200 $sz loot minecraft:chests/shipwreck_map"
  sleep 10
  say "execute in minecraft:overworld run data get block $sx 200 $sz Items"
  sleep 3
  say "execute in minecraft:overworld run setblock $sx 200 $sz minecraft:air"
  say "execute in minecraft:overworld run forceload remove $sx $sz"
done
grep -E "world border|The nearest|Could not find|Starts in overworld|Could not count|has the following block data" "$LOG" | cut -c1-4000 > "$OUT/prove.log"
say "stop"
wait "$PID"
touch "$OUT/done"
