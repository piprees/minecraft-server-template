#!/usr/bin/env bash
# confirm.sh — K9's confirmation boot (server lane only): full surveys and r4096/8 renders (the player border, where the places are laid) of the_sunken_temple and
# the_aviary, plus a variant copy of the_aviary (the_aviary_k9, same definition and seed) whose patches must equal the
# aviary's; then compare.sh prints the dossiers' placeness.places beside the stand-in on the renders.
# Runs from K9's worktree build (no main-checkout edits). Needs free -g >= 5 GB available and no other server running.
# Everything lands in /tmp/claude-0/k9/c1/. Expect about 45-60 minutes (full surveys of 4096-block areas, two 1024-pixel renders).
set -u
W=/home/user/minecraft-server-template/.claude/worktrees/agent-ae9bc5762f32be03c/mods/custom-dimensions-26
MAIN=/home/user/minecraft-server-template/mods/custom-dimensions-26
OUT=/tmp/claude-0/k9/c1; mkdir -p "$OUT"
export JAVA_HOME=/home/user/.local/jdk/jdk-25.0.5+8 PATH=/home/user/.local/jdk/jdk-25.0.5+8/bin:$PATH
avail=$(free -g | awk '/^Mem:/ {print $7}'); [ "$avail" -ge 5 ] || { echo "only ${avail} GB available" | tee -a "$OUT/driver.log"; exit 1; }
# Definitions: the worktree's, plus the variant copy.
DEFS=$OUT/defs; rm -rf "$DEFS"; cp -r "$W/definitions" "$DEFS"; cp "$DEFS/the_aviary.json" "$DEFS/the_aviary_k9.json"
# Server run directory: the main checkout's skeleton, read-only copy, no world.
cd "$W" || exit 1; mkdir -p server/run
for f in eula.txt ops.json config defaultconfigs mods; do [ -e "$MAIN/server/run/$f" ] && [ ! -e "server/run/$f" ] && cp -r "$MAIN/server/run/$f" server/run/; done
rm -rf server/run/world server/run/renders server/run/customdim-survey
printf '%s\n' "server-port=25665" "online-mode=false" "enforce-secure-profile=false" "level-seed=20261022" "spawn-protection=0" \
  "view-distance=10" "simulation-distance=6" "pause-when-empty-seconds=0" "motd=customdimensions k9" > server/run/server.properties
LOG=$OUT/server.log; FIFO=$OUT/console.fifo; rm -f "$FIFO"; mkfifo "$FIFO"; exec 3<>"$FIFO"
timeout 7200 ./gradlew --offline --max-workers=1 :server:runServer -Pdefinitions="$DEFS" <&3 > "$LOG" 2>&1 &
PID=$!; echo "$PID" > "$OUT/pid"
for _ in $(seq 1 300); do grep -q "Done (" "$LOG" && break
  grep -q "BUILD FAILED\|Exception in server tick loop\|Failed to start the minecraft server" "$LOG" && { echo "server failed" >> "$OUT/driver.log"; kill "$PID"; exit 1; }; sleep 2; done
sleep 2
seed() { python3 -c "import json;s=json.load(open('$DEFS/$1.json')).get('seed');print(s if isinstance(s,int) else 0)"; }
survey() {
  local pat="Surveyed $1:|Could not survey $1" before; before=$(grep -cE "$pat" "$LOG"); local t; t=$(date +%s)
  echo "customdim survey $1" >&3
  for _ in $(seq 1 360); do [ "$(grep -cE "$pat" "$LOG")" -gt "$before" ] && break; sleep 5; done
  rm -rf "$OUT/full/$1"; mkdir -p "$OUT/full"; cp -r "server/run/customdim-survey/$1" "$OUT/full/$1" 2>/dev/null
  echo "survey $1 $(( $(date +%s) - t )) s: $(grep -E "$pat" "$LOG" | tail -1 | cut -c1-200)" >> "$OUT/driver.log"
}
render() {
  local pat="Rendered |Could not render|No dimension" before; before=$(grep -cE "$pat" "$LOG"); local t; t=$(date +%s)
  echo "customdim render $1 $(seed "$1") 4096 8" >&3
  for _ in $(seq 1 300); do [ "$(grep -cE "$pat" "$LOG")" -gt "$before" ] && break; sleep 2; done
  echo "render $1 $(( $(date +%s) - t )) s: $(grep -E "$pat" "$LOG" | tail -1 | cut -c1-200)" >> "$OUT/driver.log"
}
survey the_sunken_temple; render the_sunken_temple
survey the_aviary; render the_aviary
survey the_aviary_k9
echo "stop" >&3; wait "$PID"; echo "server rc=$?" >> "$OUT/driver.log"
rm -rf "$OUT/renders"; cp -r server/run/renders "$OUT/renders" 2>/dev/null
rm -rf server/run/world
touch "$OUT/done"
bash /tmp/claude-0/k9/compare.sh | tee "$OUT/compare.txt"
