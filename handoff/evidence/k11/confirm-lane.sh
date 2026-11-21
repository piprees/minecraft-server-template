#!/usr/bin/env bash
# confirm-lane.sh: the server-lane confirmation of package k11 (wave sheets, End cities, web map increments).
# One boot of the 26.3 dev server on a fresh world, seed 20261022, server.properties as CI writes it (max-tick-time=-1,
# T288). In order:
#   1. waves of the_slatemouth, the_icebound_rift, the_red_monument (void), the_end and overworld
#      (`customdim fastrender <slug> wave 4`), each copied to waves/<slug>/;
#   2. the generated layers of the three void worlds (`customdim map export <slug> generated`): their thumbnails;
#   3. 9 x 9 chunks forceloaded round 0,0 in the_aviary and saved, then `customdim map export the_aviary` twice, with a
#      save between them so the server rewrites the regions; explored.json kept after each.
# Each wait is bounded. Results -> /tmp/claude-0/k11/lane/<tag>/: server.log, driver.log, waves/, site/ (manifest, each
# exported dimension's json and thumb.png), explored-1.json, explored-2.json, sizes.tsv (from the manifest's bytes),
# structure-control.log (the boot's structure control lines for the five).
# What to read: waves/<slug>/wave-*/pass2-*-sheet.png for each (void maps and structures panels show the planned places;
# the_end's structures panel shows end cities; overworld has no tier panels); pass2-*.json `planned` and
# `fast.structures.footprints` (the_end > 0); site/maps/<void>/thumb.png (a plain tile with the name and places);
# explored-2.json `chunksRead` well under explored-1.json's with `chunksKept` the rest.
# Usage: P=<mods/custom-dimensions-26 checkout carrying the k11 commits> confirm-lane.sh <tag>
set -u
TAG=${1:-c1}
P=${P:-/home/user/minecraft-server-template/mods/custom-dimensions-26}
export JAVA_HOME=/home/user/.local/jdk/jdk-25.0.5+8 PATH=/home/user/.local/jdk/jdk-25.0.5+8/bin:$PATH
OUT=/tmp/claude-0/k11/lane/$TAG
mkdir -p "$OUT/waves" "$OUT/site/maps"
LOG=$OUT/server.log
FIFO=$OUT/console.fifo
cd "$P" || exit 1
mkdir -p server/run
[ -f server/run/eula.txt ] || echo "eula=true" > server/run/eula.txt
rm -rf server/run/world server/run/map-web server/run/renders
printf '%s\n' "server-port=25665" "online-mode=false" "level-seed=20261022" "spawn-protection=0" \
  "view-distance=10" "simulation-distance=6" "pause-when-empty-seconds=0" "max-tick-time=-1" > server/run/server.properties
rm -f "$FIFO"
mkfifo "$FIFO"
exec 3<> "$FIFO"
timeout 14400 ./gradlew --offline --max-workers=3 :server:runServer <&3 > "$LOG" 2>&1 &
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

for d in the_slatemouth the_icebound_rift the_red_monument the_end overworld; do
  before=$(grep -c "Fast-rendered $d seed" "$LOG")
  start=$(date +%s)
  say "customdim fastrender $d wave 4"
  await "Fast-rendered $d seed" 900 "wave $d" "$before" "$start"
  cp -r "server/run/renders/$d" "$OUT/waves/" 2> /dev/null
done

for d in the_slatemouth the_icebound_rift the_red_monument; do
  before=$(grep -c "Exported the web map for" "$LOG")
  start=$(date +%s)
  say "customdim map export $d generated"
  await "Exported the web map for" 300 "export $d generated" "$before" "$start"
done

say "customdim activate the_aviary"
sleep 20
say "execute in adventure:the_aviary run forceload add -72 -72 71 71"
sleep 60
say "save-all flush"
sleep 15
AV=server/run/map-web/maps/the_aviary
for n in 1 2; do
  before=$(grep -c "Exported the web map for" "$LOG")
  start=$(date +%s)
  say "customdim map export the_aviary"
  await "Exported the web map for" 1800 "aviary export $n" "$before" "$start"
  cp "$AV/explored.json" "$OUT/explored-$n.json" 2> /dev/null
  ls -l --time-style=+%H:%M:%S "server/run/world/dimensions/adventure/the_aviary/region/" >> "$OUT/driver.log" 2>&1
  if [ "$n" = 1 ]; then
    sleep 330
    say "save-all flush"
    sleep 15
  fi
done
grep -E "structure control applied to [a-z:_]*(the_slatemouth|the_icebound_rift|the_red_monument|the_end|overworld):" "$LOG" \
  | cut -c1-400 > "$OUT/structure-control.log"
say "stop"
wait "$PID"

ROOT=server/run/map-web
cp "$ROOT"/manifest.json "$OUT/site/" 2> /dev/null
for d in "$ROOT"/maps/*/; do
  name=$(basename "$d")
  mkdir -p "$OUT/site/maps/$name"
  cp "$d"/*.json "$d"/thumb.png "$OUT/site/maps/$name/" 2> /dev/null
done
python3 - "$ROOT/manifest.json" > "$OUT/sizes.tsv" << 'EOF'
import json, sys
m = json.load(open(sys.argv[1]))
print("dimension\tgenerated_bytes\texplored_bytes\texplored")
for d in m["dimensions"]:
    b = d.get("bytes", {})
    if "layers" in d or b.get("explored"):
        print(f'{d["slug"]}\t{b.get("generated", 0)}\t{b.get("explored", 0)}\t{d.get("explored")}')
EOF
du -sh "$ROOT" >> "$OUT/driver.log"
touch "$OUT/done"
