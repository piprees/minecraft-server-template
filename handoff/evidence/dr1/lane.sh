#!/usr/bin/env bash
# DR1 lane. Usage: lane.sh measure|build|boot|selftest
#   measure   Gradle runServer -PmeasureStructures with the list's structure mods (fills structure-sizes.json and bridge sizes)
#   build     Gradle build (every test)
#   boot      production-like Fabric server, the full 26.3 list + our jar, fresh world: driver checks, then the vanilla join
#   notrek    the same server without Trek: the End and overworld starts only (the before-Trek village count)
#   today     the 26.3 list and bridges before DR1 (7ef42926) with today's jar: the End and overworld starts only
#   selftest  the same server, fresh world, the full self-test; stops itself
set -u
W=/home/user/minecraft-server-template/.claude/worktrees/agent-a8404968ac635c23d
P=$W/mods/custom-dimensions-26
OUT=/tmp/claude-0/dr1/lane
FULL=/tmp/claude-0/dr1/full
SRV=/tmp/claude-0/dr1/srv
export JAVA_HOME=/home/user/.local/jdk/jdk-25.0.5+8 PATH=/home/user/.local/jdk/jdk-25.0.5+8/bin:$PATH
mkdir -p "$OUT"
vanilla_copy() {
  local V=/home/user/minecraft-server-template/mods/custom-dimensions-26/compiler/build/vanilla/26.3
  if [ ! -f "$P/compiler/build/vanilla/26.3/reports/biome_parameters/minecraft/overworld.json" ]; then
    mkdir -p "$P/compiler/build/vanilla/26.3"
    cp -r "$V/." "$P/compiler/build/vanilla/26.3/"
  fi
}

# prep <defs-dir>: a fresh server dir: mods, config (base, then the 26.3 overlay over it, as deploy.sh), definitions,
# bridges, datapacks (filtered by the 26.3 list), server.properties.
prep() {
  local defs=$1
  rm -rf "$SRV/world" "$SRV/config" "$SRV/mods" "$SRV/logs" "$SRV/crash-reports"
  mkdir -p "$SRV/mods" "$SRV/config" "$SRV/world/datapacks"
  cp "$FULL"/mods/*.jar "$SRV/mods/"
  python3 "$W/scripts/patch-mod-data.py" "$SRV/mods" > "$OUT/patch-mod-data.log" 2>&1
  cp "$P"/server/build/libs/customdimensions-0.1.0.jar "$SRV/mods/"
  (
    cd "$W" || exit 1
    export MC_VERSION_OVERRIDE=26.3
    # shellcheck source=/dev/null
    source scripts/lib.sh
    cd config || exit 1
    version_config_files 26.3 | while IFS= read -r f; do
      dest="$SRV/config/${f#./versions/26.3/}"; mkdir -p "$(dirname "$dest")"; cp "$f" "$dest"
    done
    platform_config_files | while IFS= read -r f; do
      dest="$SRV/config/${f#./}"; [ -f "$dest" ] && continue; mkdir -p "$(dirname "$dest")"; cp "$f" "$dest"
    done
  )
  rm -rf "$SRV/config/custom-dimensions/dimensions" "$SRV/config/custom-dimensions/bridges"
  mkdir -p "$SRV/config/custom-dimensions/dimensions" "$SRV/config/custom-dimensions/bridges"
  cp -r "$defs"/. "$SRV/config/custom-dimensions/dimensions/"
  cp "$P"/bridges/*.json "$SRV/config/custom-dimensions/bridges/"
  for pack in "$W"/config/datapacks/*/; do cp -r "$pack" "$SRV/world/datapacks/$(basename "$pack")"; done
  cp "$FULL"/datapacks/* "$SRV/world/datapacks/"
  python3 "$W/scripts/filter-datapacks.py" "$SRV/world/datapacks" "$W/config/modrinth-mods-26.3.txt" "$OUT/no-overlay" > "$OUT/filter.log" 2>&1
  echo "eula=true" > "$SRV/eula.txt"
  printf '%s\n' "server-port=25667" "online-mode=false" "level-seed=20261022" "spawn-protection=0" "view-distance=10" \
    "simulation-distance=6" "pause-when-empty-seconds=0" "max-tick-time=-1" "white-list=false" "enforce-whitelist=false" \
    "level-name=world" > "$SRV/server.properties"
}

case "${1:-}" in
  measure)
    vanilla_copy
    SM=/tmp/claude-0/dr1/smods; rm -rf "$SM"; mkdir -p "$SM"; cp "$FULL"/mods/*.jar "$SM/"
    cd "$P" || exit 1
    mkdir -p server/run; [ -f server/run/eula.txt ] || echo "eula=true" > server/run/eula.txt
    rm -rf server/run/world
    printf '%s\n' "server-port=25667" "online-mode=false" "level-seed=20261022" "max-tick-time=-1" "white-list=false" > server/run/server.properties
    timeout 3600 ./gradlew --offline --max-workers=1 :server:runServer "-PstructureMods=$SM" \
      -PmeasureStructures=src/main/resources/structure-sizes.json > "$OUT/measure.log" 2>&1
    echo "measure exit $?" >> "$OUT/measure.log"
    rm -rf server/run/world
    ;;
  build)
    vanilla_copy
    cd "$P" || exit 1
    timeout 3600 ./gradlew --max-workers=1 --offline build > "$OUT/build.log" 2>&1
    echo "build exit $?" >> "$OUT/build.log"
    ;;
  selftest)
    rm -rf "$OUT/stdefs"; mkdir -p "$OUT/stdefs"
    cp -r "$P"/definitions/. "$OUT/stdefs/"; cp -r "$P"/server/src/selftest/definitions/. "$OUT/stdefs/"
    cp "$P"/server/src/selftest/gallery/*_default.json "$OUT/stdefs/"
    prep "$OUT/stdefs"
    cd "$SRV" || exit 1
    timeout 5400 java -Xmx6G -Dcustomdimensions.selftest="$OUT/selftest.txt" \
      "-Dcustomdimensions.vanilla.reference=$P/compiler/build/vanilla/26.3" \
      -jar fabric-server-launch.jar --nogui < /dev/null > "$OUT/selftest-server.log" 2>&1 &
    J=$!
    for _ in $(seq 1 1700); do
      kill -0 "$J" 2> /dev/null || break
      grep -q "Failed to start the minecraft server\|Crash report saved" "$OUT/selftest-server.log" && { sleep 5; kill "$J" 2> /dev/null; break; }
      sleep 3
    done
    kill "$J" 2> /dev/null
    echo "selftest end" >> "$OUT/selftest-server.log"
    ;;
  boot)
    prep "$P/definitions"
    bash /tmp/claude-0/dr1/driver.sh
    ;;
  notrek)
    prep "$P/definitions"
    rm -f "$SRV"/mods/trek-*.jar
    DR1_TAG=notrek DR1_STARTS_ONLY=1 DR1_EXTRA=1 bash /tmp/claude-0/dr1/driver.sh
    ;;
  stronghold)
    prep "$P/definitions"
    DR1_TAG=stronghold DR1_STARTS_ONLY=1 DR1_EXTRA=1 DR1_SKIP_STARTS=1 bash /tmp/claude-0/dr1/driver.sh
    ;;
  today)
    prep "$P/definitions"
    rm -f "$SRV"/mods/*.jar
    cp /tmp/claude-0/dr1/today/mods/*.jar "$SRV/mods/"
    python3 "$W/scripts/patch-mod-data.py" "$SRV/mods" > "$OUT/patch-mod-data-today.log" 2>&1
    cp "$P"/server/build/libs/customdimensions-0.1.0.jar "$SRV/mods/"
    git -C "$W" show 7ef42926:mods/custom-dimensions-26/bridges > /dev/null
    rm -f "$SRV"/config/custom-dimensions/bridges/*.json
    for b in $(git -C "$W" ls-tree --name-only 7ef42926 mods/custom-dimensions-26/bridges/); do
      git -C "$W" show "7ef42926:$b" > "$SRV/config/custom-dimensions/bridges/$(basename "$b")"
    done
    rm -rf "$SRV/world/datapacks/admin-waystones" "$SRV/world/datapacks/wordless-strongholds"
    DR1_TAG=today DR1_STARTS_ONLY=1 bash /tmp/claude-0/dr1/driver.sh
    ;;
  *) echo "usage: lane.sh measure|build|boot|notrek|stronghold|today|selftest"; exit 2 ;;
esac
