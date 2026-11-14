#!/usr/bin/env bash
# lane.sh — K10's boot for the server lane (T217: once after a new density-function shape). Two boots, one script:
#   1. the terrain self-test on K10's worktree build, over K10's scratch definitions (definitions/ with the four gaps'
#      fields on seven users) plus every self-test fixture; its new line "terrain roofed biomes (selftest_roofed_biomes)"
#      checks the coast cue, the below-floor band and the fog over the biomes in a running game. The server stops itself.
#   2. a plain boot over the same scratch definitions that surveys one user per gap: the_buried_age (sea roles),
#      the_blackstone_keep (vista under the roof), the_obsidian_sanctum (atmosphere over biomes), the_boneyard (below the floor).
# Then lane-read.py judges each against its bar and prints PASS/FAIL lines.
# Needs: no other server running, free -g >= 5 GB available. Never edit this file while it runs (T181).
# Everything lands in /tmp/claude-0/k10/lane/. Expect ~25 min for the self-test section and ~20 for the four surveys.
set -u
W=/home/user/minecraft-server-template/mods/custom-dimensions-26
MAIN=/home/user/minecraft-server-template/mods/custom-dimensions-26
OUT=/tmp/claude-0/w10c2/k10lane/out; mkdir -p "$OUT"
export JAVA_HOME=/home/user/.local/jdk/jdk-25.0.5+8 PATH=/home/user/.local/jdk/jdk-25.0.5+8/bin:$PATH
avail=$(free -g | awk '/^Mem:/ {print $7}'); [ "$avail" -ge 5 ] || { echo "only ${avail} GB available" | tee -a "$OUT/driver.log"; exit 1; }
# Definitions: the scratch users (rebuilt from the worktree's definitions/), and those plus the self-test fixtures.
DEFS=$OUT/defs; COMB=$OUT/defs-selftest
python3 /tmp/claude-0/w10c2/k10lane/make_users.py "$DEFS" >> "$OUT/driver.log"
rm -rf "$COMB"; cp -r "$DEFS" "$COMB"; cp "$W"/server/src/selftest/definitions/*.json "$COMB"/; cp "$W"/server/src/selftest/gallery/*_default.json "$COMB"/
# Server run directory: the main checkout's skeleton, no world.
cd "$W" || exit 1; mkdir -p server/run
for f in eula.txt ops.json config defaultconfigs mods; do [ -e "$MAIN/server/run/$f" ] && [ ! -e "server/run/$f" ] && cp -r "$MAIN/server/run/$f" server/run/; done
cp server/run/server.properties "server/run/server.properties.bak.$(date +%Y%m%d%H%M%S)" 2>/dev/null
props() { printf '%s\n' "server-port=25666" "online-mode=false" "enforce-secure-profile=false" "level-seed=20261022" "spawn-protection=0" \
  "view-distance=10" "simulation-distance=6" "pause-when-empty-seconds=0" "motd=customdimensions k10" > server/run/server.properties; }

# ---- boot 1: the terrain self-test --------------------------------------------------------------------------
rm -rf server/run/world; props
REPORT=$OUT/selftest.txt; rm -f "$REPORT"
timeout 5400 ./gradlew --offline --max-workers=1 :server:runServer -Pdefinitions="$COMB" -PselfTest="$REPORT" -PselfTestOnly=terrain \
  > "$OUT/selftest-server.log" 2>&1
echo "self-test boot rc=$? $(grep -c '^PASS' "$REPORT" 2>/dev/null) PASS $(grep -c '^FAIL' "$REPORT" 2>/dev/null) FAIL" >> "$OUT/driver.log"
rm -rf server/run/world

# ---- boot 2: one survey per gap -----------------------------------------------------------------------------
props
LOG=$OUT/server.log; FIFO=$OUT/console.fifo; rm -f "$FIFO"; mkfifo "$FIFO"; exec 3<>"$FIFO"
timeout 5400 ./gradlew --offline --max-workers=1 :server:runServer -Pdefinitions="$DEFS" <&3 > "$LOG" 2>&1 &
PID=$!; echo "$PID" > "$OUT/pid"
for _ in $(seq 1 300); do grep -q "Done (" "$LOG" && break
  grep -q "BUILD FAILED\|Exception in server tick loop\|Failed to start the minecraft server" "$LOG" && { echo "server failed" >> "$OUT/driver.log"; kill "$PID"; exit 1; }; sleep 2; done
sleep 2
survey() {
  local pat="Surveyed $1:|Could not survey $1" before t; before=$(grep -cE "$pat" "$LOG"); t=$(date +%s)
  echo "customdim survey $1" >&3
  for _ in $(seq 1 360); do [ "$(grep -cE "$pat" "$LOG")" -gt "$before" ] && break; sleep 5; done
  rm -rf "$OUT/full/$1"; mkdir -p "$OUT/full"; cp -r "server/run/customdim-survey/$1" "$OUT/full/$1" 2>/dev/null
  echo "survey $1 $(( $(date +%s) - t )) s: $(grep -E "$pat" "$LOG" | tail -1 | cut -c1-200)" >> "$OUT/driver.log"
}
for slug in the_buried_age the_blackstone_keep the_obsidian_sanctum the_boneyard; do survey "$slug"; done
echo "stop" >&3; wait "$PID"; echo "server rc=$?" >> "$OUT/driver.log"
rm -rf server/run/world
touch "$OUT/done"
python3 /tmp/claude-0/k10/lane-read.py "$OUT" | tee "$OUT/verdicts.txt"
