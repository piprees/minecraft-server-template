#!/usr/bin/env bash
# map-lane.sh: the server-lane run of /customdim map export all (package m12).
# One boot of the 26.3 dev server carrying the m12 commits, on a fresh world with seed 20261022. Before the export it
# saves a few real chunks so the explored layer has data: 9 x 9 chunks round 0,0 in the overworld and in the_aviary
# (forceload, then save-all flush). Then `customdim map export all` (every dimension: generated layers, explored layer,
# manifest, shell) and, once it reports, a second `customdim map export all`, which should skip every generated layer
# (fingerprints) and every explored region (mtimes). Waits up to 4 h for each, then stops the server.
# Results -> /tmp/claude-0/m12/lane/<tag>/: server.log, driver.log (both export times, every per-dimension line),
# sizes.tsv (per dimension: generated tiles MB, explored tiles MB, tiles), and site/ (manifest, shell, every
# dimension's layers.json, explored.json, markers.json, thumb.png and zoom 0 and 1 tiles; the full tree stays in
# server/run/map-web).
# Usage: P=<mods/custom-dimensions-26 checkout carrying the m12 commits> map-lane.sh <tag>
set -u
TAG=${1:-m1}
P=${P:-/home/user/minecraft-server-template/mods/custom-dimensions-26}
export JAVA_HOME=/home/user/.local/jdk/jdk-25.0.5+8 PATH=/home/user/.local/jdk/jdk-25.0.5+8/bin:$PATH
OUT=/tmp/claude-0/m12/lane/$TAG
mkdir -p "$OUT"
LOG=$OUT/server.log
FIFO=$OUT/console.fifo
cd "$P" || exit 1
mkdir -p server/run
[ -f server/run/eula.txt ] || echo "eula=true" > server/run/eula.txt
rm -rf server/run/world server/run/map-web
printf '%s\n' "server-port=25665" "online-mode=false" "level-seed=20261022" "spawn-protection=0" \
  "view-distance=10" "simulation-distance=6" "pause-when-empty-seconds=0" "max-tick-time=-1" > server/run/server.properties
rm -f "$FIFO"
mkfifo "$FIFO"
exec 3<> "$FIFO"
timeout 36000 ./gradlew --offline --max-workers=3 :server:runServer <&3 > "$LOG" 2>&1 &
PID=$!
echo "$PID" > "$OUT/pid"
for _ in $(seq 1 300); do
  grep -q "Done (" "$LOG" && break
  grep -q "BUILD FAILED\|Failed to start the minecraft server" "$LOG" && { kill "$PID"; exit 1; }
  sleep 2
done

say() { echo "$1" >&3; echo "$(date +%H:%M:%S) > $1" >> "$OUT/driver.log"; }
say "forceload add -72 -72 71 71"
say "customdim activate the_aviary"
sleep 20
say "execute in adventure:the_aviary run forceload add -72 -72 71 71"
sleep 60
say "save-all flush"
sleep 15

export_all() {
  local label=$1 before start
  before=$(grep -c "Exported the web map for" "$LOG")
  start=$(date +%s)
  say "customdim map export all"
  for _ in $(seq 1 7200); do
    [ "$(grep -c "Exported the web map for" "$LOG")" -gt "$before" ] && break
    sleep 2
  done
  echo "$label: $(( $(date +%s) - start )) s: $(grep "Exported the web map for" "$LOG" | tail -1 | cut -c1-300)" >> "$OUT/driver.log"
}
export_all "first export"
grep -E "Web map [a-z_]+: |web map export of" "$LOG" | cut -c1-300 > "$OUT/first-export.log"
export_all "second export"
grep -E "Web map [a-z_]+: |web map export of" "$LOG" | cut -c1-300 | tail -n +"$(( $(wc -l < "$OUT/first-export.log") + 1 ))" > "$OUT/second-export.log"
say "stop"
wait "$PID"

ROOT=server/run/map-web
printf 'dimension\tgenerated_MB\texplored_MB\tgenerated_tiles\texplored_tiles\n' > "$OUT/sizes.tsv"
for d in "$ROOT"/maps/*/; do
  name=$(basename "$d")
  g=$(du -sm "$d/tiles" 2> /dev/null | cut -f1)
  e=$(du -sm "$d/explored" 2> /dev/null | cut -f1)
  gt=$(find "$d/tiles" -name '*.png' 2> /dev/null | wc -l)
  et=$(find "$d/explored" -name '*.png' 2> /dev/null | wc -l)
  printf '%s\t%s\t%s\t%s\t%s\n' "$name" "${g:-0}" "${e:-0}" "$gt" "$et" >> "$OUT/sizes.tsv"
done
mkdir -p "$OUT/site"
cp "$ROOT"/manifest.json "$ROOT"/index.html "$ROOT"/app.js "$ROOT"/app.css "$OUT/site/" 2> /dev/null
for d in "$ROOT"/maps/*/; do
  name=$(basename "$d")
  mkdir -p "$OUT/site/maps/$name"
  cp "$d"/*.json "$d"/thumb.png "$OUT/site/maps/$name/" 2> /dev/null
  for layer in "$d"/tiles/*/ "$d"/explored/; do
    [ -d "$layer" ] || continue
    rel=${layer#"$d"}
    for z in 0 1; do
      [ -d "$layer$z" ] && mkdir -p "$OUT/site/maps/$name/$rel" && cp -r "$layer$z" "$OUT/site/maps/$name/$rel"
    done
  done
done
du -sh "$ROOT" >> "$OUT/driver.log"
touch "$OUT/done"
