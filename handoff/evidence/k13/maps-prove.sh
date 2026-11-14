#!/usr/bin/env bash
# maps-prove.sh: one bounded boot (under 10 minutes) for the explorer maps where players meet them. Seed 20261022,
# CI's server.properties, a copy of definitions/. In the overworld: the world border's size; locate of
# minecraft:mansion from 0,0 and from the middle of each quadrant, and whether each mansion found stands in a
# biome of #minecraft:has_structure/woodland_mansion at its chunk centre; minecraft:shipwreck located from three points, and above each (y 200, forceloaded) a chest that takes
# minecraft:chests/shipwreck_map twice, as a shipwreck's map chest rolls it (treasure tag, radius 50, no reference).
# A minecraft:map with minecraft:map_id in a chest is a found buried treasure; none means the search failed there.
# With ELYTRA set: the End city nearest 0,1200 located and minecraft:chests/end_city_treasure rolled 100 times at a
# point inside one of its pieces (confirm-lane.sh step 4), into loot.tsv.
# Results -> /tmp/claude-0/k13/lane/<tag>/: server.log, driver.log, prove.log, loot.tsv.
# Usage: P=<absolute mods/custom-dimensions-26 checkout> [MODS=<structure mod jar dir>] [ONLY=mansion] [ELYTRA=1] maps-prove.sh <tag>
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
  at=$(located minecraft:mansion "$fx" "$fz")
  [ -n "$at" ] && MANSIONS="${MANSIONS:-}$at;"
done
# Each distinct mansion found: whether its chunk's centre, at the surface, is a biome mansions take.
printf '%s' "${MANSIONS:-}" | tr ';' '\n' | sort -u | while read -r mx mz; do
  [ -n "$mx" ] || continue
  say "execute in minecraft:overworld run forceload add $mx $mz"
  sleep 10
  say "execute in minecraft:overworld positioned $((mx + 8)) 0 $((mz + 8)) positioned over world_surface if biome ~ ~ ~ #minecraft:has_structure/woodland_mansion"
  sleep 2
  say "execute in minecraft:overworld run forceload remove $mx $mz"
done
[ "${ONLY:-}" = mansion ] || for from in "0 0" "1500 -1500" "-2000 1000"; do
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
if [ -n "${ELYTRA:-}" ]; then
await() {
  local pattern=$1 tries=$2 label=$3 before=$4 start=$5
  for _ in $(seq 1 "$tries"); do
    [ "$(grep -c "$pattern" "$LOG")" -gt "$before" ] && break
    sleep 2
  done
  echo "$label: $(( $(date +%s) - start )) s: $(grep "$pattern" "$LOG" | tail -1 | cut -c1-300)" >> "$OUT/driver.log"
}
# rolls <label> <dimension> <x> <z> <table> <structure>: 100 rolls of the table at a point inside a piece of the
# structure within 40 blocks of x, z (an 11x11 grid, step 8, two blocks under the surface, kept where a
# location_check predicate holds), or a row saying none was found.
rolls() {
  local label=$1 dim=$2 x=$3 z=$4 table=$5 structure=$6 before inside dx dz
  say "execute in $dim run forceload add $((x - 48)) $((z - 48)) $((x + 48)) $((z + 48))"
  sleep 40
  say "execute in $dim run kill @e[tag=k13in]"
  for dx in -40 -32 -24 -16 -8 0 8 16 24 32 40; do
    for dz in -40 -32 -24 -16 -8 0 8 16 24 32 40; do
      say "execute in $dim positioned $((x + dx)) 0 $((z + dz)) positioned over world_surface positioned ~ ~-2 ~ if predicate {type:\"minecraft:location_check\",predicate:{structures:\"$structure\"}} run summon marker ~ ~ ~ {Tags:[\"k13in\"]}"
    done
  done
  say "execute in $dim store result score #inside k13 if entity @e[tag=k13in]"
  before=$(grep -c "#inside has [0-9]* \[k13\]" "$LOG")
  say "scoreboard players get #inside k13"
  await "#inside has [0-9]* \[k13\]" 15 "inside $label" "$before" "$(date +%s)"
  inside=$(grep -oE '#inside has [0-9]+' "$LOG" | tail -1 | awk '{print $3}')
  if [ "${inside:-0}" -eq 0 ]; then
    printf '%s\t0\tno point inside a piece within 40 blocks\t\n' "$label" >> "$OUT/loot.tsv"
  else
    before=$(grep -c "has the following entity data" "$LOG")
    say "execute in $dim positioned $x 0 $z run data get entity @e[tag=k13in,limit=1,sort=nearest] Pos"
    await "has the following entity data" 15 "point $label" "$before" "$(date +%s)"
    label="$label in-piece $(grep "has the following entity data" "$LOG" | tail -1 | sed 's/.*entity data: //')"
    say "execute in $dim run kill @e[type=item,x=$x,y=0,z=$z,distance=..512]"
    for _ in $(seq 1 100); do
      say "execute in $dim positioned $x 0 $z at @e[tag=k13in,limit=1,sort=nearest] run loot spawn ~ ~ ~ loot $table"
    done
    sleep 5
    say "execute in $dim positioned $x 0 $z store result score #elytra k13 if entity @e[type=item,distance=..512,nbt={Item:{id:\"minecraft:elytra\"}}]"
    say "execute in $dim positioned $x 0 $z store result score #items k13 if entity @e[type=item,distance=..512]"
    before=$(grep -c "#items has [0-9]* \[k13\]" "$LOG")
    say "scoreboard players get #elytra k13"
    say "scoreboard players get #items k13"
    await "#items has [0-9]* \[k13\]" 15 "score $label" "$before" "$(date +%s)"
    printf '%s\t100\t%s\t%s\n' "$label" "$(grep -oE '#elytra has [0-9]+' "$LOG" | tail -1 | awk '{print $3}')" \
      "$(grep -oE '#items has [0-9]+' "$LOG" | tail -1 | awk '{print $3}')" >> "$OUT/loot.tsv"
    say "execute in $dim run kill @e[type=item,x=$x,y=0,z=$z,distance=..512]"
  fi
  say "execute in $dim run kill @e[tag=k13in]"
  say "execute in $dim run forceload remove $((x - 48)) $((z - 48)) $((x + 48)) $((z + 48))"
}
  say "scoreboard objectives add k13 dummy"
  printf 'place\trolls\telytra\titems\n' > "$OUT/loot.tsv"
  before=$(grep -c "The nearest minecraft:end_city is at" "$LOG")
  say "execute in minecraft:the_end positioned 0 0 1200 run locate structure minecraft:end_city"
  for _ in $(seq 1 30); do
    [ "$(grep -c "The nearest minecraft:end_city is at" "$LOG")" -gt "$before" ] && break
    sleep 2
  done
  at=$(grep "The nearest minecraft:end_city is at" "$LOG" | tail -n +"$((before + 1))" | tail -1 | grep -oE '\[-?[0-9]+, ~, -?[0-9]+\]' \
    | tr -d '[],~' | awk '{print $1, $2}')
  if [ -n "$at" ]; then
    read -r cx cz <<< "$at"
    rolls "end_city@$cx,$cz" minecraft:the_end "$cx" "$cz" minecraft:chests/end_city_treasure minecraft:end_city
  fi
fi
grep -E "world border|The nearest|Could not find|Test passed|Test failed|Starts in overworld|Could not count|has the following block data" "$LOG" | cut -c1-4000 > "$OUT/prove.log"
say "stop"
wait "$PID"
touch "$OUT/done"
