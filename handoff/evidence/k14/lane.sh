#!/usr/bin/env bash
# lane.sh: the server-lane check of package k14 (curated structures with no starts). One boot of the 26.3 dev server on
# a fresh world, seed 20261022, the pack's structure mods, server.properties as CI writes it (max-tick-time=-1, T288),
# reading a copy of definitions/ in which the_nether's bastion site filter is taken out, so every bastion start is
# measured. In order:
#   1. the boot's structure control lines and the chooser's WARNs (not placed, biome bypassed, list ids, forced and
#      shunned) for the four slugs and the_nether;
#   2. `customdim starts <slug>` for the_sunken_temple, the_abyssal_shrine, the_violet_spire, the_forged_depths and
#      the_nether: every confirmed start inside the border, by structure;
#   3. `customdim starts the_nether 1024 minecraft:bastion_remnant`: each bastion's chunk, box floor and the surface
#      a site filter reads there (the floor under the roof; sea level over the open lava sea);
#   4. `customdim survey <slug> 1 1` for the same five: the post-survey WARN of each asked-for structure without a start.
# Each wait is bounded. Results -> /tmp/claude-0/k14/lane/<tag>/: server.log, driver.log, control.log, warns.log,
# starts.log, bastions.log.
# Usage: P=<mods/custom-dimensions-26 checkout carrying the k14 commits> [MODS=<structure mod jar dir>] [STRIP=0 keeps the
# bastion filter] [SLUGS="<slug> ..."] lane.sh <tag>
set -u
TAG=${1:-l1}
P=${P:-/home/user/minecraft-server-template/.claude/worktrees/agent-a2a8f36a8aee3d66f/mods/custom-dimensions-26}
MODS=${MODS:-/tmp/claude-0/pack-structure-mods}
case "$P" in /*) ;; *) echo "P must be an absolute path (T302)"; exit 2 ;; esac
export JAVA_HOME=/home/user/.local/jdk/jdk-25.0.5+8 PATH=/home/user/.local/jdk/jdk-25.0.5+8/bin:$PATH
OUT=/tmp/claude-0/k14/lane/$TAG
mkdir -p "$OUT"
LOG=$OUT/server.log
FIFO=$OUT/console.fifo
cd "$P" || exit 1

rm -rf "$OUT/defs"
cp -r definitions "$OUT/defs"
[ "${STRIP:-1}" = 1 ] && python3 -I -c 'import json,sys
p=sys.argv[1]; d=json.load(open(p)); d["structures"].get("sites", {}).pop("minecraft:bastion_remnant", None)
json.dump(d, open(p, "w"), indent=2)' "$OUT/defs/the_nether.json"

mkdir -p server/run
[ -f server/run/eula.txt ] || echo "eula=true" > server/run/eula.txt
rm -rf server/run/world server/run/renders server/run/customdim-survey
printf '%s\n' "server-port=25665" "online-mode=false" "level-seed=20261022" "spawn-protection=0" \
  "view-distance=10" "simulation-distance=6" "pause-when-empty-seconds=0" "max-tick-time=-1" > server/run/server.properties
rm -f "$FIFO"
mkfifo "$FIFO"
exec 3<> "$FIFO"
timeout 5400 ./gradlew --offline --max-workers=3 :server:runServer "-Pdefinitions=$OUT/defs" "-PstructureMods=$MODS" <&3 > "$LOG" 2>&1 &
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
  echo "$label: $(( $(date +%s) - start )) s: $(grep "$pattern" "$LOG" | tail -1 | cut -c1-200)" >> "$OUT/driver.log"
}
SLUGS=${SLUGS:-"the_sunken_temple the_abyssal_shrine the_violet_spire the_forged_depths the_nether"}

for s in $SLUGS; do
  before=$(grep -c "Starts in $s within\|Could not count structure starts in $s" "$LOG")
  say "customdim starts $s"
  await "Starts in $s within\|Could not count structure starts in $s" 300 "starts $s" "$before" "$(date +%s)"
done
grep -E "Starts in [a-z_]+ within|Could not count structure starts" "$LOG" | sed 's/.*System chat: //' > "$OUT/starts.log"

before=$(grep -c "Starts of minecraft:bastion_remnant in the_nether" "$LOG")
say "customdim starts the_nether 1024 minecraft:bastion_remnant"
await "Starts of minecraft:bastion_remnant in the_nether\|Could not count structure starts in the_nether" 300 "bastions" "$before" "$(date +%s)"
grep "Starts of minecraft:bastion_remnant in the_nether" "$LOG" | sed 's/.*System chat: //' | tail -1 > "$OUT/bastions.log"

for s in $SLUGS; do
  before=$(grep -c "Surveyed $s:\|survey $s failed\|Could not survey $s" "$LOG")
  say "customdim survey $s 1 1"
  await "Surveyed $s:\|survey $s failed\|Could not survey $s" 300 "survey $s" "$before" "$(date +%s)"
done

grep -E "structure control applied to (adventure|minecraft):(the_sunken_temple|the_abyssal_shrine|the_violet_spire|the_forged_depths|the_nether):" \
  "$LOG" | sed 's/dropped \[[^]]*\]/dropped [..]/' | cut -c1-600 > "$OUT/control.log"
grep -E "is not placed|biome check bypassed|structures.list|also shunned|has no start within|did not start|were not counted" "$LOG" \
  | cut -c1-700 | sort -u > "$OUT/warns.log"

say "stop"
wait "$PID"
touch "$OUT/done"
