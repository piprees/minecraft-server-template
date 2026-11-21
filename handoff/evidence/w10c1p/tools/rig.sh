#!/usr/bin/env bash
# rig.sh <tag> <list.json> [render distance]: the rig's biome shots of a list on the repo's definitions, render distance 32 -> runs/rig-<tag>.
W=/tmp/claude-0/w10c1p
cd /home/user/minecraft-server-template/mods/custom-dimensions-client-26
export JAVA_HOME=/home/user/.local/jdk/jdk-25.0.5+8 PATH=/home/user/.local/jdk/jdk-25.0.5+8/bin:$PATH
start=$(date +%s); mkdir -p $W/runs/rig-$1
GL_SHIM=1 timeout 6000 scripts/rig.sh --out $W/runs/rig-$1 --definitions /home/user/minecraft-server-template/mods/custom-dimensions-26/definitions --render-distance ${3:-32} biomes $2 > $W/runs/rig-$1.log 2>&1
echo "$1 rc=$? seconds=$(( $(date +%s) - start ))" >> $W/runs/rig.done
