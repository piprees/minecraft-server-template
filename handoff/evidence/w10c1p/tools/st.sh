#!/usr/bin/env bash
# st.sh <tag> [section]: fresh-world self-test (level-seed 20261022) -> runs/st-<tag>.{txt,log,rc}
W=/tmp/claude-0/w10c1p; P=/home/user/minecraft-server-template/mods/custom-dimensions-26
export JAVA_HOME=/home/user/.local/jdk/jdk-25.0.5+8 PATH=/home/user/.local/jdk/jdk-25.0.5+8/bin:$PATH
cd $P; rm -rf server/run/world server/run/renders server/run/customdim-survey
printf '%s\n' "server-port=25665" "online-mode=false" "enforce-secure-profile=false" "level-seed=20261022" "spawn-protection=0" \
  "view-distance=32" "simulation-distance=6" "pause-when-empty-seconds=0" "motd=customdimensions rig" > server/run/server.properties
ONLY=""; [ -n "${2:-}" ] && ONLY="-PselfTestOnly=$2"
timeout 3600 ./gradlew --offline --max-workers=3 :server:runServer -PselfTest=$W/runs/st-$1.txt $ONLY < /dev/null > $W/runs/st-$1.log 2>&1
echo "rc=$?" > $W/runs/st-$1.rc; rm -rf server/run/world
