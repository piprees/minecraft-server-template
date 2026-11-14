#!/usr/bin/env bash
# selftest-structures.sh: the start-up self-test's structures section on a fresh world, seed 20261022, CI's
# server.properties; the server stops itself when the section is done.
# Results -> /tmp/claude-0/k13/lane/<tag>/: server.log, st.txt (the report: one PASS/FAIL line per check).
# Usage: P=<absolute mods/custom-dimensions-26 checkout> selftest-structures.sh <tag>
set -u
TAG=${1:-s1}
P=${P:-/home/user/minecraft-server-template/.claude/worktrees/agent-af600b2f415e01031/mods/custom-dimensions-26}
case "$P" in /*) ;; *) echo "P must be an absolute path (T302)"; exit 2 ;; esac
export JAVA_HOME=/home/user/.local/jdk/jdk-25.0.5+8 PATH=/home/user/.local/jdk/jdk-25.0.5+8/bin:$PATH
OUT=/tmp/claude-0/k13/lane/$TAG
mkdir -p "$OUT"
cd "$P" || exit 1
mkdir -p server/run
[ -f server/run/eula.txt ] || echo "eula=true" > server/run/eula.txt
rm -rf server/run/world server/run/renders
printf '%s\n' "server-port=25665" "online-mode=false" "level-seed=20261022" "spawn-protection=0" \
  "view-distance=4" "simulation-distance=4" "pause-when-empty-seconds=0" "max-tick-time=-1" > server/run/server.properties
timeout 2400 ./gradlew --offline --max-workers=3 :server:runServer "-PselfTest=$OUT/st.txt" -PselfTestOnly=structures \
  < /dev/null > "$OUT/server.log" 2>&1
echo "rc=$?" > "$OUT/rc"
