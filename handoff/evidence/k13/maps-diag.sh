#!/usr/bin/env bash
# maps-diag.sh: one bounded boot that tells which exploration-map searches answer. Seed 20261022, CI's
# server.properties, a copy of definitions/. A chest at 0 200 0 in the overworld (forceloaded, in the air, so no item
# entities and no surface lookups): `locate` of minecraft:buried_treasure by id and by #minecraft:on_treasure_maps,
# minecraft:chests/shipwreck_map inserted twice, then one blank map per slot given an inline exploration_map modifier:
#   0 #on_treasure_maps r50 no reference (the shipwreck's)   1 the same, r100
#   2 minecraft:buried_treasure r50 no reference              3 the same, r100
#   4 #on_treasure_maps r50 with reference                    5 #on_ocean_monument_maps r100 with reference (a trade)
#   6 #on_buried_trial_chambers_maps r100 with reference      7 #on_woodland_mansion_maps r100 with reference
# The chest's Items are read after the inserts and after each slot. A slot holding a map with minecraft:map_id
# answered; a blank minecraft:map did not (the modifier returns the stack unchanged when the search finds nothing).
# Results -> /tmp/claude-0/k13/lane/<tag>/: server.log, driver.log, maps.log.
# Usage: P=<absolute mods/custom-dimensions-26 checkout> [MODS=<structure mod jar dir>] maps-diag.sh <tag>
set -u
TAG=${1:-m1}
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
timeout 3600 ./gradlew --offline --max-workers=3 :server:runServer "-Pdefinitions=$OUT/defs" "${EXTRA[@]}" <&3 > "$LOG" 2>&1 &
PID=$!
echo "$PID" > "$OUT/pid"
for _ in $(seq 1 300); do
  grep -q "Done (" "$LOG" && break
  grep -q "BUILD FAILED\|Failed to start the minecraft server" "$LOG" && { kill "$PID"; exit 1; }
  sleep 2
done
grep -q "Done (" "$LOG" || { echo "no boot in 600 s" >> "$OUT/driver.log"; kill "$PID"; exit 1; }
say() { echo "$1" >&3; echo "$(date +%H:%M:%S) > $1" >> "$OUT/driver.log"; }

AT="execute in minecraft:overworld positioned 0 200 0"
say "execute in minecraft:overworld run forceload add 0 0"
sleep 20
say "$AT run locate structure minecraft:buried_treasure"
sleep 15
say "$AT run locate structure #minecraft:on_treasure_maps"
sleep 15
say "execute in minecraft:overworld run setblock 0 200 0 minecraft:chest"
sleep 2
start=$(wc -l < "$LOG")
say "$AT run loot insert 0 200 0 loot minecraft:chests/shipwreck_map"
sleep 15
say "$AT run loot insert 0 200 0 loot minecraft:chests/shipwreck_map"
sleep 15
say "$AT run data get block 0 200 0 Items"
sleep 3
say "$AT run data merge block 0 200 0 {Items:[]}"
n=0
for modifier in \
  'destination:"#minecraft:on_treasure_maps",decoration:"minecraft:red_x",zoom:1b,skip_existing_chunks:false' \
  'destination:"#minecraft:on_treasure_maps",decoration:"minecraft:red_x",zoom:1b,skip_existing_chunks:false,search_radius:100' \
  'destination:"minecraft:buried_treasure",decoration:"minecraft:red_x",zoom:1b,skip_existing_chunks:false' \
  'destination:"minecraft:buried_treasure",decoration:"minecraft:red_x",zoom:1b,skip_existing_chunks:false,search_radius:100' \
  'destination:"#minecraft:on_treasure_maps",decoration:"minecraft:red_x",zoom:1b' \
  'destination:"#minecraft:on_ocean_monument_maps",decoration:"minecraft:monument",search_radius:100' \
  'destination:"#minecraft:on_buried_trial_chambers_maps",decoration:"minecraft:trial_chambers",search_radius:100' \
  'destination:"#minecraft:on_woodland_mansion_maps",decoration:"minecraft:mansion",search_radius:100'; do
  say "$AT run item replace block 0 200 0 container.$n with minecraft:map"
  say "$AT run item modify block 0 200 0 container.$n {type:\"minecraft:exploration_map\",$modifier}"
  sleep 20
  say "$AT run data get block 0 200 0 Items[{Slot:${n}b}]"
  sleep 3
  n=$((n + 1))
done
{
  echo "locate lines, then the chest after the inserts, then each slot after its modifier:"
  grep -E "The nearest|Could not find" "$LOG" | cut -c1-300
  tail -n +"$((start + 1))" "$LOG" | grep -E "has the following|Could not|Failed|Unknown|rror|Inserted|Modified|Applied" | cut -c1-1500
} > "$OUT/maps.log"
say "stop"
wait "$PID"
touch "$OUT/done"
