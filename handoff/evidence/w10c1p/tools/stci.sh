#!/usr/bin/env bash
# stci.sh <tag> [section]: fresh-world self-test under CI's server.properties (mod26.yml) -> runs/st-<tag>.{txt,log,rc}
W=/tmp/claude-0/w10c1p; P=/home/user/minecraft-server-template/mods/custom-dimensions-26
export JAVA_HOME=/home/user/.local/jdk/jdk-25.0.5+8 PATH=/home/user/.local/jdk/jdk-25.0.5+8/bin:$PATH
cd $P; rm -rf server/run/world server/run/renders server/run/customdim-survey
printf 'online-mode=false\nmax-tick-time=-1\nview-distance=4\nsimulation-distance=4\nlevel-seed=20261022\n' > server/run/server.properties
ONLY=""; [ -n "${2:-}" ] && ONLY="-PselfTestOnly=$2"
start=$(date +%s)
timeout 3600 ./gradlew --offline --max-workers=3 :server:runServer -PselfTest=$W/runs/st-$1.txt $ONLY < /dev/null > $W/runs/st-$1.log 2>&1
echo "rc=$? seconds=$(( $(date +%s) - start ))" > $W/runs/st-$1.rc; rm -rf server/run/world
