#!/usr/bin/env bash
# DR1 boot driver: starts the prepared production-like server in /tmp/claude-0/dr1/srv, runs the checks, joins with
# the vanilla client, stops. Every wait is bounded. Results in /tmp/claude-0/dr1/lane/boot/.
set -u
SRV=/tmp/claude-0/dr1/srv
OUT=/tmp/claude-0/dr1/lane/${DR1_TAG:-boot}
W=/home/user/minecraft-server-template/.claude/worktrees/agent-a8404968ac635c23d
rm -rf "$OUT"; mkdir -p "$OUT"
LOG=$OUT/server.log
FIFO=$OUT/console.fifo
rm -f "$FIFO"; mkfifo "$FIFO"; exec 3<> "$FIFO"
cd "$SRV" || exit 1
timeout 10800 java -Xmx6G -jar fabric-server-launch.jar --nogui <&3 > "$LOG" 2>&1 &
PID=$!; echo "$PID" > "$OUT/pid"
for _ in $(seq 1 600); do
  grep -q "Done (" "$LOG" && break
  kill -0 "$PID" 2> /dev/null || break
  sleep 2
done
grep -q "Done (" "$LOG" || { echo "no boot" >> "$OUT/driver.log"; kill "$PID" 2> /dev/null; exit 1; }
say() { echo "$1" >&3; echo "$(date +%H:%M:%S) > $1" >> "$OUT/driver.log"; }
# await <pattern> <tries of 2 s> <label>: waits for one more matching line than when called.
await() {
  local pattern=$1 tries=$2 label=$3 before
  before=${4:-$(grep -c "$pattern" "$LOG")}
  for _ in $(seq 1 "$tries"); do
    [ "$(grep -c "$pattern" "$LOG")" -gt "$before" ] && break
    sleep 2
  done
  echo "$label: $(grep "$pattern" "$LOG" | tail -1 | cut -c1-3000)" >> "$OUT/results.txt"
}
ask() { local pattern=$2 before; before=$(grep -c "$pattern" "$LOG"); say "$1"; await "$pattern" "${3:-30}" "${4:-$1}" "$before"; }
xz() { grep "$1" "$LOG" | tail -1 | grep -oE '\[-?[0-9]+, ~, -?[0-9]+\]' | tr -d '[],~' | awk '{print $1, $2}'; }

grep -iE "firewall|switched off|refuses to start|allowed mods" "$LOG" | cut -c1-2000 > "$OUT/firewall.log"
grep -E "structure control applied to [a-z:_]*(the_end|overworld):" "$LOG" | cut -c1-1500 > "$OUT/structure-control.log"
say "scoreboard objectives add dr1 dummy"
say "datapack list enabled"

# starts: the End and the overworld, every confirmed start by structure
if [ -z "${DR1_SKIP_STARTS:-}" ]; then
ask "customdim starts the_end" "Starts in the_end within\|Could not count structure starts in the_end" 900
ask "customdim starts overworld" "Starts in overworld within\|Could not count structure starts in overworld" 1800
fi
if [ -n "${DR1_STARTS_ONLY:-}" ] && [ -n "${DR1_EXTRA:-}" ]; then
  loc() { ask "execute in $1 positioned $3 0 $4 run locate structure $2" "The nearest $2\|Could not find a structure of type \"$2\"\|Could not find a structure of type $2" 150 "locate $2 in $1 from $3,$4"; }
  loc minecraft:overworld minecraft:village_plains 0 0
  VIL=$(xz "The nearest minecraft:village_plains")
  loc minecraft:overworld "#minecraft:eye_of_ender_located" 0 0
  SH=$(xz "The nearest #minecraft:eye_of_ender_located")
  loc minecraft:overworld minecraft:ancient_city 0 0
  EC=""
  SKIP_TO_CHECKS=1
elif [ -n "${DR1_STARTS_ONLY:-}" ]; then
  say "stop"
  for _ in $(seq 1 60); do kill -0 "$PID" 2> /dev/null || break; sleep 2; done
  kill "$PID" 2> /dev/null
  touch "$OUT/done"
  exit 0
fi

# locate: the mansion want, outposts, ancient city, mineshaft, a village, the strongholds eyes find
if [ -z "${SKIP_TO_CHECKS:-}" ]; then
loc() { ask "execute in $1 positioned $3 0 $4 run locate structure $2" "The nearest $2\|Could not find a structure of type \"$2\"\|Could not find a structure of type $2" 150 "locate $2 in $1 from $3,$4"; }
loc minecraft:overworld minecraft:mansion -7344 -672
loc minecraft:overworld minecraft:mansion 0 0
loc minecraft:overworld minecraft:pillager_outpost 0 0
loc minecraft:overworld minecraft:ancient_city 0 0
loc minecraft:overworld minecraft:mineshaft 0 0
loc minecraft:overworld "#minecraft:village" 0 0
VIL=$(xz "The nearest #minecraft:village")
loc minecraft:overworld "#minecraft:eye_of_ender_located" 0 0
SH=$(xz "The nearest #minecraft:eye_of_ender_located")
loc minecraft:overworld minecraft:stronghold_big 0 0
loc minecraft:the_end minecraft:end_city 0 1200
EC=$(xz "The nearest minecraft:end_city")
fi

# village: no waystone in a fresh village (every waystone block id, 3x3 boxes of 32 round the start, 32 high)
if [ -n "$VIL" ]; then
  read -r vx vz <<< "$VIL"
  say "execute in minecraft:overworld run forceload add $((vx - 48)) $((vz - 48)) $((vx + 48)) $((vz + 48))"
  sleep 60
  say "execute in minecraft:overworld positioned $vx 0 $vz positioned over world_surface run summon marker ~ ~ ~ {Tags:[\"dr1v\"]}"
  sleep 2
  ask "execute in minecraft:overworld run data get entity @e[tag=dr1v,limit=1] Pos" "has the following entity data" 15 "village surface"
  vy=$(grep "has the following entity data" "$LOG" | tail -1 | grep -oE '\[-?[0-9.]+d, -?[0-9.]+d' | sed 's/.*, //;s/\..*//;s/d//')
  vy=${vy:-70}
  before=$(grep -c "Successfully filled\|No blocks were filled\|too many blocks" "$LOG")
  n=0
  for b in waystone deepslate_bricks_waystone end_stone_bricks_waystone mud_bricks_waystone nether_bricks_waystone \
    polished_blackstone_bricks_waystone prismarine_waystone red_nether_bricks_waystone red_sandstone_waystone \
    resin_bricks_waystone sandstone_waystone tuff_bricks_waystone; do
    for dx in -48 -16 16; do for dz in -48 -16 16; do
      say "execute in minecraft:overworld run fill $((vx + dx)) $((vy - 12)) $((vz + dz)) $((vx + dx + 31)) $((vy + 19)) $((vz + dz + 31)) minecraft:air replace sswaystones:$b"
      n=$((n + 1))
    done; done
  done
  sleep 10
  { echo "village at $VIL surface y $vy: $n fills"; tail -n +1 "$LOG" | grep "Successfully filled\|No blocks were filled\|too many blocks\|Unknown block" | tail -n +"$((before + 1))" | sort | uniq -c; } > "$OUT/village-waystones.log"
  say "execute in minecraft:overworld run forceload remove $((vx - 48)) $((vz - 48)) $((vx + 48)) $((vz + 48))"
fi

# stronghold: the portal room's eyes (Biome Strongholds' markers on end_portal_frame blocks), 12 eyes, the portal
if [ -n "$SH" ]; then
  read -r sx sz <<< "$SH"
  say "execute in minecraft:overworld run forceload add $((sx - 112)) $((sz - 112)) $((sx + 112)) $((sz + 112))"
  sleep 240
  ask "execute in minecraft:overworld store result score #eyes dr1 if entity @e[tag=bistro.ender_eye]" "Gave\|\[dr1\]\|matched\|Test passed\|Test failed" 5 "eyes count (store)"
  ask "scoreboard players get #eyes dr1" "#eyes has" 10 "bistro.ender_eye markers loaded"
  ask "execute in minecraft:overworld run data get entity @e[tag=bistro.ender_eye,limit=1] Pos" "has the following entity data\|No entity was found" 10 "first eye marker"
  ask "execute in minecraft:overworld store result score #frames dr1 if entity @e[tag=bistro.ender_eye,predicate=bistro:end_frame_filled]" "zzz-none" 2 "filled before"
  ask "scoreboard players get #frames dr1" "#frames has" 10 "frames with an eye before"
  say "execute in minecraft:overworld as @e[tag=bistro.ender_eye] at @s run fill ~ ~ ~ ~ ~ ~ minecraft:end_portal_frame[eye=true] replace minecraft:end_portal_frame"
  sleep 5
  ask "execute in minecraft:overworld run data get entity @e[tag=bistro.end_portal,limit=1] Pos" "has the following entity data\|No entity was found" 10 "portal controller"
  C=$(grep "has the following entity data" "$LOG" | tail -1 | grep -oE '\[-?[0-9.]+d, -?[0-9.]+d, -?[0-9.]+d\]' | tr -d '[]d,')
  if [ -n "$C" ]; then
    read -r cx cy cz <<< "$C"
    ask "player dr1bot spawn at ${cx%.*} ${cy%.*} ${cz%.*} facing 0 0 in minecraft:overworld" "dr1bot joined the game" 15 "a player beside the portal (Biome Strongholds counts down only with one within 8 blocks)"
  fi
  sleep 20
  ask "execute in minecraft:overworld store result score #after dr1 if entity @e[tag=bistro.ender_eye]" "zzz-none" 2 "eyes after (store)"
  ask "scoreboard players get #after dr1" "#after has" 10 "eye markers left after 12 eyes (the portal function kills them)"
  E=$(grep "has the following entity data" "$LOG" | tail -1 | grep -oE '\[-?[0-9.]+d, -?[0-9.]+d, -?[0-9.]+d\]' | tr -d '[]d,' )
  if [ -n "$E" ]; then
    read -r ex ey ez <<< "$E"; ex=${ex%.*}; ey=${ey%.*}; ez=${ez%.*}
    ask "execute in minecraft:overworld run fill $((ex - 12)) $((ey - 4)) $((ez - 12)) $((ex + 12)) $((ey + 4)) $((ez + 12)) minecraft:structure_void replace minecraft:end_portal" "Successfully filled\|No blocks were filled" 10 "end_portal blocks round the first eye"
  fi
  [ -n "$C" ] && say "player dr1bot kill"
  say "execute in minecraft:overworld run forceload remove $((sx - 112)) $((sz - 112)) $((sx + 112)) $((sz + 112))"
fi

if [ -n "${SKIP_TO_CHECKS:-}" ]; then
  say "stop"
  for _ in $(seq 1 60); do kill -0 "$PID" 2> /dev/null || break; sleep 2; done
  kill "$PID" 2> /dev/null
  touch "$OUT/done"
  exit 0
fi

# K12's elytra rule inside an End city piece: 100 rolls of end_city_treasure at a point inside a piece
if [ -n "$EC" ]; then
  read -r x z <<< "$EC"
  say "execute in minecraft:the_end run forceload add $((x - 48)) $((z - 48)) $((x + 48)) $((z + 48))"
  sleep 40
  for dx in -40 -32 -24 -16 -8 0 8 16 24 32 40; do for dz in -40 -32 -24 -16 -8 0 8 16 24 32 40; do
    say "execute in minecraft:the_end positioned $((x + dx)) 0 $((z + dz)) positioned over world_surface positioned ~ ~-2 ~ if predicate {type:\"minecraft:location_check\",predicate:{structures:\"minecraft:end_city\"}} run summon marker ~ ~ ~ {Tags:[\"dr1in\"]}"
  done; done
  ask "execute in minecraft:the_end store result score #inside dr1 if entity @e[tag=dr1in]" "zzz-none" 2 "inside (store)"
  ask "scoreboard players get #inside dr1" "#inside has" 10 "points inside an End city piece"
  ask "execute in minecraft:the_end positioned $x 0 $z run data get entity @e[tag=dr1in,limit=1,sort=nearest] Pos" "has the following entity data\|No entity was found" 10 "in-piece point"
  say "execute in minecraft:the_end run kill @e[type=item,x=$x,y=0,z=$z,distance=..512]"
  for _ in $(seq 1 100); do
    say "execute in minecraft:the_end positioned $x 0 $z at @e[tag=dr1in,limit=1,sort=nearest] run loot spawn ~ ~ ~ loot minecraft:chests/end_city_treasure"
  done
  sleep 5
  say "execute in minecraft:the_end positioned $x 0 $z store result score #elytra dr1 if entity @e[type=item,distance=..512,nbt={Item:{id:\"minecraft:elytra\"}}]"
  say "execute in minecraft:the_end positioned $x 0 $z store result score #items dr1 if entity @e[type=item,distance=..512]"
  ask "scoreboard players get #elytra dr1" "#elytra has" 10 "elytra in 100 rolls"
  ask "scoreboard players get #items dr1" "#items has" 10 "items in 100 rolls"
  say "execute in minecraft:the_end run kill @e[type=item,x=$x,y=0,z=$z,distance=..512]"
  say "execute in minecraft:the_end run forceload remove $((x - 48)) $((z - 48)) $((x + 48)) $((z + 48))"
fi

# waystones: no recipe, an admin gives and places one
ask "player dr1bot spawn at 0 200 0 facing 0 0 in minecraft:overworld" "dr1bot joined the game\|dr1bot" 15 "fake player"
ask "recipe give dr1bot sswaystones:waystone" "recipe\|Recipe\|Unknown\|element" 10 "recipe give (no recipe expected)"
ask "give dr1bot sswaystones:waystone" "Gave 1" 10 "give waystone"
say "execute in minecraft:overworld run forceload add 0 0"
sleep 10
ask "execute in minecraft:overworld positioned 4 0 4 positioned over world_surface run setblock ~ ~ ~ sswaystones:waystone" "Changed the block\|Could not set" 10 "admin places a waystone"
ask "help sswaystones" "sswaystones" 10 "sswaystones command tree"
ask "player dr1bot kill" "dr1bot" 10 "fake player gone"

# the vanilla client: joins, sees the frozen-frame answer with no words, receives a waystone
cat > "$OUT/during.sh" << EOF
c() { echo "\$1" > "$FIFO"; sleep 2; }
c "tellraw dr1join \"dr1-control-line\""
c "execute as dr1join at @s run function bistro:advancement/interact_portal_barrier"
c "give dr1join sswaystones:waystone"
c "recipe give dr1join sswaystones:waystone"
sleep 5
cp "\$VANILLA_JOIN_GAME/logs/latest.log" "$OUT/client-latest.log" 2> /dev/null
EOF
PATH=/tmp/claude-0/dr1/bin:$PATH GL_SHIM=1 "$W/scripts/e2e/vanilla-join.sh" --version 26.3 --server 127.0.0.1:25667 \
  --name dr1join --hold 60 --out "$OUT/join" --during "bash $OUT/during.sh" > "$OUT/join-client.log" 2>&1
echo "join exit $?" >> "$OUT/join-client.log"

say "stop"
for _ in $(seq 1 60); do kill -0 "$PID" 2> /dev/null || break; sleep 2; done
kill "$PID" 2> /dev/null
touch "$OUT/done"
