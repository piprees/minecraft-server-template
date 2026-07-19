#!/usr/bin/env bash
# =============================================================================
# warm-image.sh — Build a pre-warmed Docker image for seed rolling
# =============================================================================
# Uses a Dockerfile to COPY all mods, Fabric, libraries, and configs into
# the image. No network calls, no volume mounts during build. Workers boot
# from this image with selective mounts for per-seed state only.
# =============================================================================
set -euo pipefail

PROJECT_ROOT="${CONSUMER_DIR:-$(pwd)}"
SEEDTEST="$PROJECT_ROOT/.seedtest"
WORK_BASE="$SEEDTEST/base"

BASE_IMAGE="${ROLL_IMAGE:-itzg/minecraft-server:2026.7.0-java21}"
WARM_IMAGE="seedroll:warm"

FORCE=0
[[ "${1:-}" == "--force" ]] && FORCE=1

if [[ "$FORCE" == 0 ]] && docker image inspect "$WARM_IMAGE" > /dev/null 2>&1; then
  echo "Warm image '$WARM_IMAGE' already exists (use --force to rebuild)"
  exit 0
fi

if [[ ! -f "$WORK_BASE/.ready" ]]; then
  echo "Error: $WORK_BASE not prepared — run ./dev seed-roll once first" >&2
  exit 1
fi

echo "Building warm image from $BASE_IMAGE..."

# Stage build context
BUILD_DIR="$SEEDTEST/warm-build"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/data"

# Copy all static content
cp -a "$WORK_BASE/mods" "$BUILD_DIR/data/mods"
cp -a "$WORK_BASE/.fabric" "$BUILD_DIR/data/.fabric"
cp -a "$WORK_BASE/libraries" "$BUILD_DIR/data/libraries"
[[ -d "$WORK_BASE/versions" ]] && cp -a "$WORK_BASE/versions" "$BUILD_DIR/data/versions"
cp "$WORK_BASE"/fabric-server-mc.*.jar "$BUILD_DIR/data/" 2>/dev/null || true
cp "$WORK_BASE/eula.txt" "$BUILD_DIR/data/" 2>/dev/null || true
[[ -f "$WORK_BASE/.install-fabric.env" ]] && cp "$WORK_BASE/.install-fabric.env" "$BUILD_DIR/data/"

for dir in config defaultconfigs moonlight-global-datapacks villagerpacks world-datapacks-template; do
  [[ -d "$WORK_BASE/$dir" ]] && cp -a "$WORK_BASE/$dir" "$BUILD_DIR/data/$dir"
done

# v4 config for the warm image (just namespace, no dimensions)
mkdir -p "$BUILD_DIR/data/config/custom-dimensions/dimensions"
printf '{"namespace":"adventure"}\n' > "$BUILD_DIR/data/config/custom-dimensions/settings.json"
rm -f "$BUILD_DIR/data/config/multiverse_config.json"
printf '[vanillaWorldGenOptimizations]\n\tuseDensityFunctionCompiler = false\n' \
  > "$BUILD_DIR/data/config/c2me.toml"
rm -rf "$BUILD_DIR/data/config/bluemap" "$BUILD_DIR/data/config/DistantHorizons"
mkdir -p "$BUILD_DIR/data/config/bluemap/maps"
printf 'accept-download: true\ndata: "bluemap"\nmetrics: false\n' \
  > "$BUILD_DIR/data/config/bluemap/core.conf"
printf 'enabled: false\n' > "$BUILD_DIR/data/config/bluemap/webserver.conf"

# Write the Dockerfile — COPY bakes everything into the image layer.
# No RUN steps, no network calls, no entrypoint execution during build.
cat > "$BUILD_DIR/Dockerfile" << 'EOF'
ARG BASE_IMAGE
FROM ${BASE_IMAGE}
COPY data/ /data/
RUN chown -R 1000:1000 /data
EOF

echo "  Building image (local COPY, no network)..."
docker build -q \
  --build-arg "BASE_IMAGE=$BASE_IMAGE" \
  -t "$WARM_IMAGE" \
  "$BUILD_DIR"

rm -rf "$BUILD_DIR"

SIZE=$(docker image inspect "$WARM_IMAGE" --format '{{.Size}}' | awk '{printf "%.0fMB", $1/1048576}')
echo "  Done: $WARM_IMAGE ($SIZE)"
