#!/usr/bin/env bash
# wave.sh: the server-lane run of /customdim fastrender all wave (package f3).
# One boot of the 26.3 dev server carrying the f3 commits; one command renders every dimension in passes, one at a
# time, into server/run/renders/<dimension>/wave-<seed>/ (wave.json, pass<N>-s<stride>-*.png, pass<N>-s<stride>-sheet.png).
# The driver waits for the "dimensions in waves" summary line (cap 3 h), then stops the server and copies the waves.
# Results -> /tmp/claude-0/f3/lane/<tag>/ (server.log, driver.log, waves/<dimension>/...).
# Usage: P=<mods/custom-dimensions-26 checkout carrying the f3 commits> wave.sh <tag> [threads]
set -u
TAG=${1:-w1}
THREADS=${2:-4}
P=${P:-/home/user/minecraft-server-template/mods/custom-dimensions-26}
export JAVA_HOME=/home/user/.local/jdk/jdk-25.0.5+8 PATH=/home/user/.local/jdk/jdk-25.0.5+8/bin:$PATH
OUT=/tmp/claude-0/f3/lane/$TAG; mkdir -p "$OUT"
LOG=$OUT/server.log; FIFO=$OUT/console.fifo
cd "$P" || exit 1
mkdir -p server/run; [ -f server/run/eula.txt ] || echo "eula=true" > server/run/eula.txt
rm -rf server/run/world server/run/renders
printf '%s\n' "server-port=25665" "online-mode=false" "level-seed=20261022" "spawn-protection=0" \
  "view-distance=10" "simulation-distance=6" "pause-when-empty-seconds=0" > server/run/server.properties
rm -f "$FIFO"; mkfifo "$FIFO"; exec 3<>"$FIFO"
timeout 14400 ./gradlew --offline --max-workers=3 :server:runServer <&3 > "$LOG" 2>&1 &
PID=$!; echo "$PID" > "$OUT/pid"
for _ in $(seq 1 300); do grep -q "Done (" "$LOG" && break; grep -q "BUILD FAILED\|Failed to start the minecraft server" "$LOG" && { kill $PID; exit 1; }; sleep 2; done
start=$(date +%s)
echo "customdim fastrender all wave $THREADS" >&3
for _ in $(seq 1 5400); do grep -qE "dimensions in waves in|Could not fast-render all" "$LOG" && break; sleep 2; done
echo "all wave $(( $(date +%s) - start )) s: $(grep -E "dimensions in waves in|Could not fast-render all" "$LOG" | tail -1 | cut -c1-300)" >> "$OUT/driver.log"
grep -E "pass [0-9]+ of [0-9]+ \(stride|Fast-rendered .* passes|Could not fast-render" "$LOG" | cut -c1-400 >> "$OUT/driver.log"
echo "stop" >&3
wait $PID
mkdir -p "$OUT/waves"
for d in server/run/renders/*/; do
  name=$(basename "$d")
  for w in "$d"wave-*; do [ -d "$w" ] && mkdir -p "$OUT/waves/$name" && cp -r "$w" "$OUT/waves/$name/"; done
done
touch "$OUT/done"
