#!/usr/bin/env bash
# =============================================================================
# warm-image.sh — Build a pre-warmed Docker image for seed rolling
# =============================================================================
# Boots a seed-roll container from the base itzg image, waits for Fabric +
# mods to fully initialize, then commits the result as seedroll:warm. Future
# worker boots from this image skip ~30-40s of mod loading.
#
# Run once after modpack changes (./dev up, new mod versions, etc.). The
# warm image is stored locally — it's never pushed.
#
# Usage:
#   ./warm-image.sh              # build from .seedtest/base (must exist)
#   ./warm-image.sh --force      # rebuild even if seedroll:warm exists
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="${CONSUMER_DIR:-$(cd "$SCRIPT_DIR/../.." && pwd)}"
SEEDTEST="$PROJECT_ROOT/.seedtest"
WORK_BASE="$SEEDTEST/base"

ROLL_MEMORY="${ROLL_MEMORY:-10G}"
BASE_IMAGE="${ROLL_IMAGE:-itzg/minecraft-server:2026.7.0-java21}"
WARM_IMAGE="seedroll:warm"
CONTAINER_NAME="seedroll-warmup"

FORCE=0
[[ "${1:-}" == "--force" ]] && FORCE=1

if [[ "$FORCE" == 0 ]] && docker image inspect "$WARM_IMAGE" > /dev/null 2>&1; then
  echo "Warm image '$WARM_IMAGE' already exists (use --force to rebuild)"
  exit 0
fi

if [[ ! -f "$WORK_BASE/.ready" ]]; then
  echo "Error: $WORK_BASE not prepared — run roll-all.sh once first (or ./dev seed-roll)" >&2
  echo "  (it creates the base dir with stripped mods + configs)" >&2
  exit 1
fi

echo "Building warm image from $BASE_IMAGE..."
echo "  Using worker dir: $WORK_BASE"

# Prepare a temporary worker dir for the warmup boot
WARM_DIR="$SEEDTEST/warmup"
rm -rf "$WARM_DIR"
mkdir -p "$WARM_DIR/mods"
for jar in "$WORK_BASE/mods/"*.jar; do
  ln "$jar" "$WARM_DIR/mods/" 2>/dev/null || cp "$jar" "$WARM_DIR/mods/"
done
for item in .fabric libraries versions .install-fabric.env eula.txt; do
  [[ -e "$WORK_BASE/$item" ]] && cp -a "$WORK_BASE/$item" "$WARM_DIR/"
done
cp "$WORK_BASE"/fabric-server-mc.*.jar "$WARM_DIR/" 2>/dev/null || true
for dir in config defaultconfigs moonlight-global-datapacks villagerpacks; do
  [[ -d "$WORK_BASE/$dir" ]] && cp -a "$WORK_BASE/$dir" "$WARM_DIR/"
done

# Write minimal configs for boot
mkdir -p "$WARM_DIR/config"
printf '{"namespace":"adventure","dimensions":[],"portals":[],"worlds":[]}\n' \
  > "$WARM_DIR/config/multiverse_config.json"
printf '[vanillaWorldGenOptimizations]\n\tuseDensityFunctionCompiler = false\n' \
  > "$WARM_DIR/config/c2me.toml"
rm -rf "$WARM_DIR/config/bluemap" "$WARM_DIR/config/DistantHorizons"

# Pin Fabric versions if available
FABRIC_ENV=()
if [[ -f "$WARM_DIR/.install-fabric.env" ]]; then
  loader=$(sed -n 's/.*loader\.\([0-9.]*\).*/\1/p' "$WARM_DIR/.install-fabric.env" 2>/dev/null | head -1)
  launcher=$(sed -n 's/.*launcher\.\([0-9.]*\).*/\1/p' "$WARM_DIR/.install-fabric.env" 2>/dev/null | head -1)
  [[ -n "$loader" ]] && FABRIC_ENV+=("-e" "FABRIC_LOADER_VERSION=$loader")
  [[ -n "$launcher" ]] && FABRIC_ENV+=("-e" "FABRIC_LAUNCHER_VERSION=$launcher")
fi

docker rm -f "$CONTAINER_NAME" 2>/dev/null || true

mem_gb=${ROLL_MEMORY%G}
java_mem="$((mem_gb - 1))G"

echo "  Starting container (waiting for server ready)..."
docker run -d --name "$CONTAINER_NAME" \
  "${FABRIC_ENV[@]}" \
  --memory "$ROLL_MEMORY" \
  -p "127.0.0.1:0:25575" \
  -e "EULA=TRUE" -e "TYPE=FABRIC" -e "VERSION=1.21.1" \
  -e "SEED=1" -e "MEMORY=$java_mem" \
  -e "USE_AIKAR_FLAGS=false" \
  -e "JVM_XX_OPTS=-XX:+UseZGC -XX:+ZGenerational -XX:+AlwaysPreTouch" \
  -e "ENABLE_RCON=TRUE" -e "RCON_PASSWORD=seedroll" \
  -e "ONLINE_MODE=FALSE" -e "ENABLE_AUTOPAUSE=FALSE" \
  -e "OVERRIDE_SERVER_PROPERTIES=true" \
  -e "MAX_TICK_TIME=-1" \
  -e "SEED_ROLL_MODE=true" \
  -e "VIEW_DISTANCE=6" -e "SIMULATION_DISTANCE=4" \
  -e "LEVEL_TYPE=minecraft:flat" \
  -e "GENERATE_STRUCTURES=false" \
  -v "$WARM_DIR:/data" \
  "$BASE_IMAGE"

# Wait for RCON (server fully booted = all mods loaded)
PORT=$(docker port "$CONTAINER_NAME" 25575 | tail -1 | cut -d: -f2)
TIMEOUT=180
START=$(date +%s)

while true; do
  ELAPSED=$(( $(date +%s) - START ))
  if [[ $ELAPSED -gt $TIMEOUT ]]; then
    echo "  ERROR: Server didn't become ready in ${TIMEOUT}s" >&2
    docker logs --tail 20 "$CONTAINER_NAME" >&2
    docker rm -f "$CONTAINER_NAME" 2>/dev/null || true
    rm -rf "$WARM_DIR"
    exit 1
  fi
  if ! docker inspect -f '{{.State.Running}}' "$CONTAINER_NAME" 2>/dev/null | grep -q true; then
    echo "  ERROR: Container died during warmup" >&2
    docker logs --tail 20 "$CONTAINER_NAME" >&2
    docker rm -f "$CONTAINER_NAME" 2>/dev/null || true
    rm -rf "$WARM_DIR"
    exit 1
  fi
  # Try RCON handshake
  if python3 -c "
import socket, struct, sys
try:
    s = socket.create_connection(('127.0.0.1', $PORT), timeout=3)
    pwd = b'seedroll'
    payload = struct.pack('<ii', 1, 3) + pwd + b'\x00\x00'
    s.sendall(struct.pack('<i', len(payload)) + payload)
    s.settimeout(3)
    raw = s.recv(4)
    sys.exit(0 if len(raw) == 4 else 1)
except: sys.exit(1)
" 2>/dev/null; then
    break
  fi
  # Progress
  LINE=$(docker logs --tail 1 "$CONTAINER_NAME" 2>/dev/null | head -1 | cut -c1-100)
  printf "\r  [%3ds] %s" "$ELAPSED" "${LINE:-(waiting)}"
  sleep 3
done

printf "\n  Server ready in %ds — committing image...\n" "$ELAPSED"

# Stop the server cleanly before commit (reduces image size, clean state)
docker stop -t 10 "$CONTAINER_NAME" > /dev/null 2>&1 || true

# Remove the mounted data (it's in the volume, not the container layer).
# The committed image keeps the installed Fabric + mod classes in memory.
docker commit \
  --change 'ENV WARMUP_COMPLETE=true' \
  --message "Pre-warmed seed-roll image (mods loaded, Fabric installed)" \
  "$CONTAINER_NAME" "$WARM_IMAGE"

docker rm -f "$CONTAINER_NAME" 2>/dev/null || true
rm -rf "$WARM_DIR"

SIZE=$(docker image inspect "$WARM_IMAGE" --format '{{.Size}}' | awk '{printf "%.0fMB", $1/1048576}')
echo "  Done: $WARM_IMAGE ($SIZE)"
echo ""
echo "Set ROLL_IMAGE=$WARM_IMAGE before rolling, or it will be used automatically"
echo "if the image exists when roll-all.sh starts."
