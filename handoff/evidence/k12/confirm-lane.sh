#!/usr/bin/env bash
# confirm-lane.sh: the server-lane confirmation of package k12 (End cities, elytra in End dungeon chests, forced y).
# One boot of the 26.3 dev server on a fresh world, seed 20261022, server.properties as CI writes it (max-tick-time=-1,
# T288), reading a copy of definitions/ plus one throwaway void world, k12_forced_ground: an End city forced at 8,8
# with "y": 96 and one at 168,168 with "y": 40. In order:
#   1. the boot's structure control lines for the_end, the overworld and k12_forced_ground (groups, sites, timings);
#   2. `customdim fastrender the_end wave 4` (its structures panel and `fast.structures.footprints`: End city starts)
#      and `customdim survey the_end 1 1` (the census: End city SITES, terrain checks not applied);
#   3. three End cities located from three points, each forceloaded, and minecraft:chests/end_city_treasure rolled 100
#      times by command inside each (`loot spawn`, origin in the city); the elytra and all items counted round it;
#      controls: 100 rolls in the_end's void ring at 8,640 and 100 in the overworld at spawn, both expecting 0 elytra;
#      with MODS set, one mes:phantom_citadel located and rolled the same way (an End dungeon other than a city);
#   4. k12_forced_ground activated, both forced chunks forceloaded: `locate` finds the y-96 city at 8,8, a marker
#      summoned on its top reports a height far above the floor, and the log carries the refusal of the y-40 one.
# Each wait is bounded. Results -> /tmp/claude-0/k12/lane/<tag>/: server.log, driver.log, structure-control.log,
# waves/the_end/, survey line, loot.tsv (place, rolls, elytra, items), forced.log.
# What to read: structure-control.log (the_end `groups {dungeons=...}` and its ms); the wave json's footprints for
# minecraft:end_city (about 57 inside the 4096 border, EndCitiesTest); loot.tsv (about 8 elytra per 100 rolls in each
# city, 0 in both controls); forced.log (locate at [8, ~, 8], marker y > 96, "did not start on ground at y 40").
# Usage: P=<mods/custom-dimensions-26 checkout carrying the k12 commits> [MODS=<structure mod jar dir>] confirm-lane.sh <tag>
set -u
TAG=${1:-c1}
P=${P:-/home/user/minecraft-server-template/mods/custom-dimensions-26}
export JAVA_HOME=/home/user/.local/jdk/jdk-25.0.5+8 PATH=/home/user/.local/jdk/jdk-25.0.5+8/bin:$PATH
OUT=/tmp/claude-0/k12/lane/$TAG
mkdir -p "$OUT/waves"
LOG=$OUT/server.log
FIFO=$OUT/console.fifo
cd "$P" || exit 1

rm -rf "$OUT/defs"
cp -r definitions "$OUT/defs"
python3 -I - "$OUT/defs" << 'EOF'
import json, sys
d = json.load(open(sys.argv[1] + "/the_red_monument.json"))
for key in ("portals", "exitPortal", "exits", "roll"):
    d.pop(key, None)
d["description"] = "A throwaway void world: an End city forced at y 96 and one at y 40."
d["spawn"] = [8, 100, 8]
d["structures"] = {"density": "none", "mode": "none", "force": [
    {"structure": "minecraft:end_city", "x": 8, "z": 8, "y": 96},
    {"structure": "minecraft:end_city", "x": 168, "z": 168, "y": 40}]}
json.dump(d, open(sys.argv[1] + "/k12_forced_ground.json", "w"), indent=2)
EOF

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

say() { echo "$1" >&3; echo "$(date +%H:%M:%S) > $1" >> "$OUT/driver.log"; }
# await <pattern> <tries of 2 s> <label> <count before> <start>: waits until the log holds one more matching line.
await() {
  local pattern=$1 tries=$2 label=$3 before=$4 start=$5
  for _ in $(seq 1 "$tries"); do
    [ "$(grep -c "$pattern" "$LOG")" -gt "$before" ] && break
    sleep 2
  done
  echo "$label: $(( $(date +%s) - start )) s: $(grep "$pattern" "$LOG" | tail -1 | cut -c1-300)" >> "$OUT/driver.log"
}
# located <structure> <dimension> <x> <z>: the x z of the nearest start from x, z, or nothing.
located() {
  local before
  before=$(grep -c "The nearest $1 is at" "$LOG")
  say "execute in $2 positioned $3 0 $4 run locate structure $1"
  await "The nearest $1 is at" 60 "locate $1 in $2 from $3,$4" "$before" "$(date +%s)"
  [ "$(grep -c "The nearest $1 is at" "$LOG")" -gt "$before" ] || return 0
  grep "The nearest $1 is at" "$LOG" | tail -1 | grep -oE '\[-?[0-9]+, ~, -?[0-9]+\]' | tr -d '[],~' | awk '{print $1, $2}'
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
  say "execute in $dim positioned $x 0 $z store result score #elytra k12 if entity @e[type=item,distance=..512,nbt={Item:{id:\"minecraft:elytra\"}}]"
  say "execute in $dim positioned $x 0 $z store result score #items k12 if entity @e[type=item,distance=..512]"
  before=$(grep -c "has [0-9]* \[k12\]" "$LOG")
  say "scoreboard players get #elytra k12"
  say "scoreboard players get #items k12"
  await "has [0-9]* \[k12\]" 15 "score $label" "$((before + 1))" "$(date +%s)"
  printf '%s\t100\t%s\t%s\n' "$label" "$(grep -oE '#elytra has [0-9]+' "$LOG" | tail -1 | awk '{print $3}')" \
    "$(grep -oE '#items has [0-9]+' "$LOG" | tail -1 | awk '{print $3}')" >> "$OUT/loot.tsv"
  say "execute in $dim run kill @e[type=item,x=$x,y=0,z=$z,distance=..512]"
  say "execute in $dim run forceload remove $x $z"
}

grep -E "structure control applied to [a-z:_]*(the_end|overworld|k12_forced_ground):" "$LOG" | cut -c1-500 > "$OUT/structure-control.log"

before=$(grep -c "Fast-rendered the_end seed" "$LOG")
start=$(date +%s)
say "customdim fastrender the_end wave 4"
await "Fast-rendered the_end seed" 900 "wave the_end" "$before" "$start"
cp -r server/run/renders/the_end "$OUT/waves/" 2> /dev/null

before=$(grep -c "surveyed the_end" "$LOG")
start=$(date +%s)
say "customdim survey the_end 1 1"
await "surveyed the_end" 900 "survey the_end" "$before" "$start"
grep "surveyed the_end" "$LOG" | tail -1 > "$OUT/survey.log"

say "scoreboard objectives add k12 dummy"
printf 'place\trolls\telytra\titems\n' > "$OUT/loot.tsv"
n=0
for from in "0 1200" "1600 -1600" "-2000 400"; do
  n=$((n + 1))
  read -r fx fz <<< "$from"
  at=$(located minecraft:end_city minecraft:the_end "$fx" "$fz")
  [ -n "$at" ] || { echo "end city $n: none located from $from" >> "$OUT/driver.log"; continue; }
  read -r cx cz <<< "$at"
  rolls "end_city_$n@$cx,$cz" minecraft:the_end "$cx" "$cz" minecraft:chests/end_city_treasure
done
rolls "the_end_void@8,640" minecraft:the_end 8 640 minecraft:chests/end_city_treasure
rolls "overworld_spawn@0,0" minecraft:overworld 0 0 minecraft:chests/end_city_treasure
if [ -n "${MODS:-}" ]; then
  at=$(located mes:phantom_citadel minecraft:the_end 0 1200)
  if [ -n "$at" ]; then
    read -r cx cz <<< "$at"
    rolls "phantom_citadel@$cx,$cz" minecraft:the_end "$cx" "$cz" minecraft:chests/end_city_treasure
  fi
fi

say "customdim activate k12_forced_ground"
sleep 20
say "execute in adventure:k12_forced_ground run forceload add 0 0 15 15"
say "execute in adventure:k12_forced_ground run forceload add 160 160 175 175"
sleep 60
at=$(located minecraft:end_city adventure:k12_forced_ground 8 8)
echo "locate in k12_forced_ground: [$at]" > "$OUT/forced.log"
say "execute in adventure:k12_forced_ground positioned 8 0 8 positioned over world_surface run summon marker ~ ~ ~ {Tags:[\"k12y\"]}"
before=$(grep -c "has the following entity data" "$LOG")
say "execute in adventure:k12_forced_ground run data get entity @e[tag=k12y,limit=1] Pos"
await "has the following entity data" 15 "marker" "$before" "$(date +%s)"
grep "has the following entity data" "$LOG" | tail -1 | cut -c1-300 >> "$OUT/forced.log"
grep "k12_forced_ground: forced minecraft:end_city" "$LOG" | cut -c1-300 >> "$OUT/forced.log"

say "stop"
wait "$PID"
touch "$OUT/done"
