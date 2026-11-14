#!/usr/bin/env bash
# confirm-lane.sh: the server-lane confirmation of package k13 (locate over the plan's placements, underground starts
# need ground, fog over the biomes by default). One boot of the 26.3 dev server on a fresh world, seed 20261022,
# server.properties as CI writes it (max-tick-time=-1, T288), reading a copy of definitions/. In order:
#   1. the boot's structure control lines for the_end, the overworld and the_snowbound_isle;
#   2. locate: minecraft:end_city in the_end from three points and minecraft:trial_chambers in the overworld from three
#      (each answer, its time, and whether it answered at all: before k13 the End answered "Could not find" at once);
#   3. an explorer map: minecraft:chests/shipwreck_map spawned at the overworld spawn, the item read back (a
#      minecraft:filled_map with a target decoration means ExplorationMapFunction's search found the buried treasure);
#   4. K12's elytra rolls: minecraft:chests/end_city_treasure 100 times inside each End city step 2 located;
#   5. the End's city count: `customdim fastrender the_end wave 4`, End city and phantom citadel ids counted in its
#      json (with MODS set, Moog's End Structures shares the dungeons group, T297);
#   6. the sky world the_snowbound_isle: 12x12 chunks round its spawn forceloaded, the log's "no ground under it"
#      refusals for it, and each located mineshaft and trial chambers with the surface height over its chunk centre;
#   7. fog: the compiled pack's timeline adventure:atmosphere/the_abyssal_shrine (its definition sets fogColor and
#      skyColor and no overBiomes) and its dimension type's first timeline.
# Each wait is bounded. Results -> /tmp/claude-0/k13/lane/<tag>/: server.log, driver.log, structure-control.log,
# locate.tsv (dimension, structure, from, answer, seconds), map.log, loot.tsv, waves/the_end/, end-count.log,
# sky.log, fog.log.
# What to read: locate.tsv (every row answers a position; End cities near the sites K12 counted); map.log
# (minecraft:filled_map and a map_decorations target); loot.tsv (about 8 elytra per 100 rolls in each city);
# end-count.log (minecraft:end_city footprints, about 57 vanilla-only, about 30 with MODS); sky.log (refusal lines
# present; every located start's surface y over its chunk is an island top, far above the start's own floor, never
# the floor of the level); fog.log (the timeline exists and is first).
# Usage: P=<mods/custom-dimensions-26 checkout carrying the k13 commits> [MODS=<structure mod jar dir>] confirm-lane.sh <tag>
set -u
TAG=${1:-c1}
P=${P:-/home/user/minecraft-server-template/.claude/worktrees/agent-af600b2f415e01031/mods/custom-dimensions-26}
case "$P" in /*) ;; *) echo "P must be an absolute path (T302)"; exit 2 ;; esac
export JAVA_HOME=/home/user/.local/jdk/jdk-25.0.5+8 PATH=/home/user/.local/jdk/jdk-25.0.5+8/bin:$PATH
OUT=/tmp/claude-0/k13/lane/$TAG
mkdir -p "$OUT/waves"
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
timeout 7200 ./gradlew --offline --max-workers=3 :server:runServer "-Pdefinitions=$OUT/defs" "${EXTRA[@]}" <&3 > "$LOG" 2>&1 &
PID=$!
echo "$PID" > "$OUT/pid"
for _ in $(seq 1 300); do
  grep -q "Done (" "$LOG" && break
  grep -q "BUILD FAILED\|Failed to start the minecraft server" "$LOG" && { kill "$PID"; exit 1; }
  sleep 2
done
grep -q "Done (" "$LOG" || { echo "no boot in 600 s" >> "$OUT/driver.log"; kill "$PID"; exit 1; }

say() { echo "$1" >&3; echo "$(date +%H:%M:%S) > $1" >> "$OUT/driver.log"; }
# await <pattern> <tries of 2 s> <label> <count before> <start>: waits until the log holds more matching lines than before.
await() {
  local pattern=$1 tries=$2 label=$3 before=$4 start=$5
  for _ in $(seq 1 "$tries"); do
    [ "$(grep -c "$pattern" "$LOG")" -gt "$before" ] && break
    sleep 2
  done
  echo "$label: $(( $(date +%s) - start )) s: $(grep "$pattern" "$LOG" | tail -1 | cut -c1-300)" >> "$OUT/driver.log"
}
# located <structure> <dimension> <x> <z>: the x z of the nearest start from x, z (a row in locate.tsv either way).
located() {
  local before start answer secs
  before=$(grep -c "The nearest $1 is at\|Could not find a structure of type \"$1\"" "$LOG")
  start=$(date +%s)
  say "execute in $2 positioned $3 0 $4 run locate structure $1"
  await "The nearest $1 is at\|Could not find a structure of type \"$1\"" 150 "locate $1 in $2 from $3,$4" "$before" "$start"
  secs=$(( $(date +%s) - start ))
  answer=$(grep "The nearest $1 is at\|Could not find a structure of type \"$1\"" "$LOG" | tail -n +"$((before + 1))" | tail -1 \
    | grep -oE '\[-?[0-9]+, ~, -?[0-9]+\]' | tr -d '[],~' | awk '{print $1, $2}')
  printf '%s\t%s\t%s,%s\t%s\t%s\n' "$2" "$1" "$3" "$4" "${answer:-none}" "$secs" >> "$OUT/locate.tsv"
  [ -n "$answer" ] && echo "$answer"
  return 0
}
# rolls <label> <dimension> <x> <z> <table>: 100 rolls of the table with the origin two blocks under the surface at x, z.
rolls() {
  local label=$1 dim=$2 x=$3 z=$4 table=$5 before
  say "execute in $dim run forceload add $x $z"
  sleep 30
  say "execute in $dim run kill @e[type=item,x=$x,y=0,z=$z,distance=..512]"
  for _ in $(seq 1 100); do
    say "execute in $dim positioned $x 0 $z positioned over world_surface positioned ~ ~-2 ~ run loot spawn ~ ~3 ~ loot $table"
  done
  sleep 5
  say "execute in $dim positioned $x 0 $z store result score #elytra k13 if entity @e[type=item,distance=..512,nbt={Item:{id:\"minecraft:elytra\"}}]"
  say "execute in $dim positioned $x 0 $z store result score #items k13 if entity @e[type=item,distance=..512]"
  before=$(grep -c "has [0-9]* \[k13\]" "$LOG")
  say "scoreboard players get #elytra k13"
  say "scoreboard players get #items k13"
  await "has [0-9]* \[k13\]" 15 "score $label" "$((before + 1))" "$(date +%s)"
  printf '%s\t100\t%s\t%s\n' "$label" "$(grep -oE '#elytra has [0-9]+' "$LOG" | tail -1 | awk '{print $3}')" \
    "$(grep -oE '#items has [0-9]+' "$LOG" | tail -1 | awk '{print $3}')" >> "$OUT/loot.tsv"
  say "execute in $dim run kill @e[type=item,x=$x,y=0,z=$z,distance=..512]"
  say "execute in $dim run forceload remove $x $z"
}
# surface <dimension> <x> <z> <label>: the world-surface height at x, z, from a marker, appended to sky.log.
surface() {
  local before
  say "execute in $1 run forceload add $2 $3"
  sleep 20
  say "execute in $1 positioned $2 0 $3 positioned over world_surface run summon marker ~ ~ ~ {Tags:[\"k13s\"]}"
  before=$(grep -c "has the following entity data" "$LOG")
  say "execute in $1 positioned $2 0 $3 run data get entity @e[tag=k13s,limit=1,sort=nearest] Pos"
  await "has the following entity data" 15 "surface $4" "$before" "$(date +%s)"
  echo "$4 surface over $2,$3: $(grep "has the following entity data" "$LOG" | tail -1 | grep -oE '\[[^]]*\]')" >> "$OUT/sky.log"
  say "execute in $1 run kill @e[tag=k13s]"
  say "execute in $1 run forceload remove $2 $3"
}

grep -E "structure control applied to [a-z:_]*(the_end|overworld):" "$LOG" | cut -c1-600 > "$OUT/structure-control.log"
say "scoreboard objectives add k13 dummy"

# 2. locate
printf 'dimension\tstructure\tfrom\tanswer\tseconds\n' > "$OUT/locate.tsv"
CITIES=()
for from in "0 1200" "1600 -1600" "-2000 400"; do
  read -r fx fz <<< "$from"
  at=$(located minecraft:end_city minecraft:the_end "$fx" "$fz")
  [ -n "$at" ] && CITIES+=("$at")
done
for from in "0 0" "1500 1500" "-1500 -800"; do
  read -r fx fz <<< "$from"
  located minecraft:trial_chambers minecraft:overworld "$fx" "$fz" > /dev/null
done
[ -n "${MODS:-}" ] && located mes:phantom_citadel minecraft:the_end 0 1200 > /dev/null

# 3. an explorer map
say "execute in minecraft:overworld run forceload add 0 0"
sleep 20
say "execute in minecraft:overworld run kill @e[type=item,x=0,y=0,z=0,distance=..512]"
say "execute in minecraft:overworld positioned 0 0 0 positioned over world_surface run loot spawn ~ ~2 ~ loot minecraft:chests/shipwreck_map"
sleep 30
before=$(grep -c "has the following entity data" "$LOG")
say "execute in minecraft:overworld positioned 0 0 0 run data get entity @e[type=item,distance=..512,nbt={Item:{id:\"minecraft:filled_map\"}},limit=1] Item"
await "has the following entity data" 15 "explorer map" "$before" "$(date +%s)"
{
  echo "filled maps among the spawned items (empty when the search found nothing and the map stayed blank):"
  grep "has the following entity data" "$LOG" | tail -n +"$((before + 1))" | cut -c1-600
} > "$OUT/map.log"
say "execute in minecraft:overworld run forceload remove 0 0"

# 4. elytra rolls inside each located End city
printf 'place\trolls\telytra\titems\n' > "$OUT/loot.tsv"
n=0
for at in "${CITIES[@]}"; do
  n=$((n + 1))
  read -r cx cz <<< "$at"
  rolls "end_city_$n@$cx,$cz" minecraft:the_end "$cx" "$cz" minecraft:chests/end_city_treasure
done

# 5. the End's city count
before=$(grep -c "Fast-rendered the_end seed" "$LOG")
say "customdim fastrender the_end wave 4"
await "Fast-rendered the_end seed" 900 "wave the_end" "$before" "$(date +%s)"
cp -r server/run/renders/the_end "$OUT/waves/" 2> /dev/null
python3 -I - "$OUT/waves" > "$OUT/end-count.log" 2>&1 << 'EOF'
import json, pathlib, sys
counts = {}
def walk(node, under):
    if isinstance(node, dict):
        for k, v in node.items():
            walk(v, under or "footprint" in k.lower())
    elif isinstance(node, list):
        for v in node:
            walk(v, under)
    elif isinstance(node, str) and under and node in ("minecraft:end_city", "mes:phantom_citadel"):
        counts[node] = counts.get(node, 0) + 1
for f in sorted(pathlib.Path(sys.argv[1]).rglob("*.json")):
    try:
        walk(json.loads(f.read_text()), False)
    except ValueError:
        pass
print("footprints by structure:", counts or "none found (read the wave json by hand)")
EOF

# 6. the sky world
say "customdim activate the_snowbound_isle"
sleep 30
grep -E "structure control applied to adventure:the_snowbound_isle:" "$LOG" | cut -c1-600 >> "$OUT/structure-control.log"
say "execute in adventure:the_snowbound_isle run forceload add -96 -96 95 95"
sleep 240
say "execute in adventure:the_snowbound_isle run forceload remove -96 -96 95 95"
{
  echo "refusals logged for the_snowbound_isle (first per structure):"
  grep "adventure:the_snowbound_isle: underground .* did not start: no ground under it" "$LOG" | cut -c1-300
} > "$OUT/sky.log"
for s in minecraft:mineshaft minecraft:trial_chambers; do
  for from in "0 0" "1200 -600"; do
    read -r fx fz <<< "$from"
    at=$(located "$s" adventure:the_snowbound_isle "$fx" "$fz")
    [ -n "$at" ] || continue
    read -r sx sz <<< "$at"
    surface adventure:the_snowbound_isle "$((sx + 8))" "$((sz + 8))" "$s@$sx,$sz"
  done
done

# 7. fog over the biomes by default
PACK=server/run/world/datapacks/customdimensions
{
  echo "timeline: $(cat "$PACK/data/adventure/timeline/atmosphere/the_abyssal_shrine.json" 2> /dev/null || echo missing)"
  echo "dimension type timelines: $(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1])).get("timelines"))' \
    "$PACK/data/adventure/dimension_type/the_abyssal_shrine.json" 2>&1)"
} > "$OUT/fog.log"

say "stop"
wait "$PID"
touch "$OUT/done"
