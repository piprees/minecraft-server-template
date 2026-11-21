#!/usr/bin/env bash
# measure.sh: the server-lane measurement of /customdim fastrender (package f1) against /customdim render.
# One boot of the 26.3 dev server with the f1 commits; three dimensions: the_aviary (sky), the_underdark (caves,
# roofed), overworld. Each gets: the render on its widest grid (r4096 s8, 1024x1024), fastrender on the same grid
# (stride 8 over a 4096 border; overworld: stride 16 over its 8192 border, also 1024x1024), and fastrender border
# to border at stride 4 on 2 and 4 threads (the pass bar's run). The render takes the seed fastrender reported. Results -> /tmp/claude-0/f1/measure/<tag>/.
# Usage: P=<mods/custom-dimensions-26 checkout carrying the f1 commits> measure.sh <tag>
set -u
TAG=${1:-m1}
P=${P:-/home/user/minecraft-server-template/mods/custom-dimensions-26}
export JAVA_HOME=/home/user/.local/jdk/jdk-25.0.5+8 PATH=/home/user/.local/jdk/jdk-25.0.5+8/bin:$PATH
OUT=/tmp/claude-0/f1/measure/$TAG; mkdir -p "$OUT"
LOG=$OUT/server.log; FIFO=$OUT/console.fifo
cd "$P" || exit 1
mkdir -p server/run; [ -f server/run/eula.txt ] || echo "eula=true" > server/run/eula.txt
rm -rf server/run/world server/run/renders
printf '%s\n' "server-port=25665" "online-mode=false" "level-seed=4705305050088502262" "spawn-protection=0" \
  "view-distance=10" "simulation-distance=6" "pause-when-empty-seconds=0" > server/run/server.properties
rm -f "$FIFO"; mkfifo "$FIFO"; exec 3<>"$FIFO"
timeout 5400 ./gradlew --offline --max-workers=3 :server:runServer <&3 > "$LOG" 2>&1 &
PID=$!; echo "$PID" > "$OUT/pid"
for _ in $(seq 1 300); do grep -q "Done (" "$LOG" && break; grep -q "BUILD FAILED\|Failed to start the minecraft server" "$LOG" && { kill $PID; exit 1; }; sleep 2; done
run() { # run <pattern> <command>: one command, waited for (cap 20 min)
  local pat=$1; shift; local before; before=$(grep -cE "$pat" "$LOG"); local start; start=$(date +%s)
  echo "$*" >&3
  for _ in $(seq 1 600); do [ "$(grep -cE "$pat" "$LOG")" -gt "$before" ] && break; sleep 2; done
  echo "$* $(( $(date +%s) - start )) s: $(grep -E "$pat" "$LOG" | tail -1 | cut -c1-400)" >> "$OUT/driver.log"
}
R="Rendered |Could not render"; F="Fast-rendered |Could not fast-render|Stride .* is outside"
for d in the_aviary the_underdark overworld; do
  if [ $d = overworld ]; then run "$F" customdim fastrender $d 16 2; else run "$F" customdim fastrender $d 8 2; fi
  cp server/run/renders/$d/fast-*.json "$OUT/$d-same-grid.json" 2>/dev/null
  seed=$(grep -oE "Fast-rendering $d with seed -?[0-9]+" "$LOG" | tail -1 | awk '{print $NF}')
  run "$R" customdim render $d $seed 4096 8
  run "$F" customdim fastrender $d 4 2
  cp server/run/renders/$d/fast-*.json "$OUT/$d-s4-t2.json" 2>/dev/null
  run "$F" customdim fastrender $d 4 4
  cp server/run/renders/$d/fast-*.json "$OUT/$d-s4-t4.json" 2>/dev/null
done
echo "stop" >&3
wait $PID
cp -r server/run/renders "$OUT/renders" 2>/dev/null
touch "$OUT/done"
