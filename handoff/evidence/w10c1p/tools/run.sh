#!/usr/bin/env bash
# run.sh <tag> <definitions dir> <job>...: fresh world on the 26.3 dev server; each job is "survey:<slug>" or
# "render:<slug>[:R:S]" (seed from the definition), one at a time, each waited for (cap 15 min); then stop.
# Survey dossiers -> /tmp/claude-0/w10c1p/runs/<tag>/dossiers/<slug>/, renders -> runs/<tag>/renders/.
set -u
TAG=$1; DEFS=$2; shift 2
P=/home/user/minecraft-server-template/mods/custom-dimensions-26
export JAVA_HOME=/home/user/.local/jdk/jdk-25.0.5+8 PATH=/home/user/.local/jdk/jdk-25.0.5+8/bin:$PATH
OUT=/tmp/claude-0/w10c1p/runs/$TAG; mkdir -p $OUT/dossiers
LOG=$OUT/server.log; FIFO=$OUT/console.fifo
cd $P
rm -rf server/run/world server/run/renders server/run/customdim-survey
printf '%s\n' "server-port=25665" "online-mode=false" "enforce-secure-profile=false" "level-seed=${LEVEL_SEED:-4705305050088502262}" "spawn-protection=0" \
  "view-distance=${VIEW:-32}" "simulation-distance=6" "pause-when-empty-seconds=0" "motd=customdimensions rig" > server/run/server.properties
rm -f "$FIFO"; mkfifo "$FIFO"; exec 3<>"$FIFO"
timeout ${TMO:-7200} ./gradlew --offline --max-workers=3 :server:runServer -Pdefinitions=$DEFS <&3 > "$LOG" 2>&1 &
PID=$!; echo "$PID" > $OUT/pid
for i in $(seq 1 300); do grep -q "Done (" "$LOG" && break; grep -q "BUILD FAILED\|Exception in server tick loop\|Failed to initialize server\|Failed to start the minecraft server" "$LOG" && { echo "server failed" >> $OUT/driver.log; kill $PID; exit 1; }; sleep 2; done
sleep 2
for j in "$@"; do
  kind=${j%%:*}; rest=${j#*:}; slug=${rest%%:*}
  start=$(date +%s)
  if [ "$kind" = cmd ]; then echo "${rest//\~/ }" >&3; sleep 3; echo "$j: $(tail -2 "$LOG" | tr '\n' ' ' | cut -c1-300)" >> $OUT/driver.log; continue; fi
  if [ "$kind" = survey ] || [ "$kind" = full ]; then
    pat="Surveyed $slug:|Could not survey $slug"; before=$(grep -cE "$pat" "$LOG")
    if [ "$kind" = full ]; then echo "customdim survey $slug" >&3; else echo "customdim survey $slug 1 1" >&3; fi
    for i in $(seq 1 180); do [ "$(grep -cE "$pat" "$LOG")" -gt "$before" ] && break; sleep 5; done
    D=$OUT/dossiers; [ "$kind" = full ] && D=$OUT/full; mkdir -p $D; [ -f server/run/customdim-survey/$slug/dossier.json ] && { rm -rf $D/$slug; cp -r server/run/customdim-survey/$slug $D/$slug; }
  else
    seed=$(python3 -c "import json;s=json.load(open('$DEFS/$slug.json')).get('seed');print(s if isinstance(s,int) else 4705305050088502262)")
    rs=${rest#$slug}; rs=${rs#:}; R=${rs%%:*}; S=${rs#*:}; S=${S%%:*}; [ -z "$rs" ] && { R=512; S=2; }
    case "$rs" in *:*:*) seed=${rs##*:};; esac
    pat="Rendered |Could not render|Unknown or incomplete|No dimension"; before=$(grep -cE "$pat" "$LOG")
    echo "customdim render $slug $seed $R $S" >&3
    for i in $(seq 1 150); do [ "$(grep -cE "$pat" "$LOG")" -gt "$before" ] && break; sleep 2; done
  fi
  echo "$j $(( $(date +%s) - start )) s: $(grep -E "Surveyed $slug:|Could not survey $slug|Rendered |Could not render" "$LOG" | tail -1 | cut -c1-200)" >> $OUT/driver.log
done
echo "stop" >&3
wait $PID
echo "server rc=$?" >> $OUT/driver.log
rm -rf $OUT/renders; cp -r server/run/renders $OUT/renders 2>/dev/null
touch $OUT/done
