#!/usr/bin/env bash
# pack-build.sh - Build the client modpack via the modpack-builder image.
#
# Runs anywhere with Docker and a .env: the server (invoked by
# deploy-reusable.yml after a full deploy) or a consumer machine.
# The build itself lives in the modpack-builder image (build-modpack.sh
# baked in); this wrapper just wires up mounts and environment.
#
# Output lands in <project>/modpack-dist/ (served by pack-web).
#
# Usage:
#   .stack/current/stack/scripts/pack-build.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib.sh"

# When running from a bundle (.stack/current/stack/scripts), the project
# root is above .stack; otherwise lib.sh's PROJECT_DIR is already right.
if [[ "$SCRIPT_DIR" == *"/.stack/"* ]]; then
  PROJECT_DIR="${SCRIPT_DIR%%/.stack/*}"
fi
cd "$PROJECT_DIR"
load_env

mkdir -p "$PROJECT_DIR/modpack-dist" "$PROJECT_DIR/overlay"

# Version string: consumer sha from the deploy state file when present
# (servers have no git checkout), else local git, else "local".
GIT_SHA="local"
if [[ -f "$PROJECT_DIR/.deployed" ]]; then
  GIT_SHA=$(sed -n 's/^consumer_sha=//p' "$PROJECT_DIR/.deployed" | head -c 7)
elif command -v git > /dev/null 2>&1; then
  GIT_SHA=$(git -C "$PROJECT_DIR" rev-parse --short HEAD 2> /dev/null || echo "local")
fi

log "Building modpack (${GIT_SHA}) via modpack-builder image..."
docker run --rm \
  -v "$PROJECT_DIR/overlay:/overlay:ro" \
  -v "$PROJECT_DIR/modpack-dist:/work/dist" \
  -e "DOMAIN=${DOMAIN:-localhost}" \
  -e "BRAND_NAME=${BRAND_NAME:-My Server}" \
  -e "BRAND_SLUG=${BRAND_SLUG:-myserver}" \
  -e "MC_VERSION=${MC_VERSION:-1.21.1}" \
  -e "SERVER_PORT=${SERVER_PORT:-25577}" \
  -e "GIT_SHA=${GIT_SHA}" \
  -e "DISCORD_INVITE_URL=${DISCORD_INVITE_URL:-}" \
  "${IMAGE_REGISTRY:-ghcr.io/piprees/minecraft-server-template}/modpack-builder:${IMAGE_TAG:-latest}"

log "Modpack built to $PROJECT_DIR/modpack-dist/"
