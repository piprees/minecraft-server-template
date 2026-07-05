#!/usr/bin/env bash
# initial-setup.sh - First boot of a prepared server, then hands off to deploy.sh.
#
# Run ON the server after prepare-droplet.sh, from the server directory.
# The stack bundle must already be installed (.stack/current exists).
# Idempotent. Does the first-boot-only work deploy.sh assumes exists:
# RCON password generation, data/backups dirs, restic repo init (R2),
# BlueMap defaults, image pull. Then delegates to deploy.sh --non-interactive.
#
# Usage:
#   cd ~/server && .stack/current/stack/scripts/initial-setup.sh
#   cd ~/server && .stack/current/stack/scripts/initial-setup.sh --offline
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib.sh"

# Compute paths the same way deploy.sh does
STACK_DIR="$PROJECT_DIR"
if [[ "$SCRIPT_DIR" == *"/.stack/"* ]]; then
  SERVER_DIR="${SCRIPT_DIR%%/.stack/*}"
else
  SERVER_DIR="${SERVER_DIR:-$PROJECT_DIR}"
fi
cd "$SERVER_DIR"

COMPOSE_FILE="$STACK_DIR/docker-compose.yml"

# --- flags --------------------------------------------------------------------
NON_INTERACTIVE=0
OFFLINE=0
for arg in "$@"; do
  case "$arg" in
    --non-interactive) NON_INTERACTIVE=1 ;;
    --offline) OFFLINE=1 ;;
  esac
done
[[ "${NON_INTERACTIVE_ENV:-}" == "1" ]] && NON_INTERACTIVE=1

# --- load .env ----------------------------------------------------------------
if [[ ! -f .env ]]; then
  echo "No .env found in $SERVER_DIR."
  if [[ $NON_INTERACTIVE -eq 1 ]]; then
    echo "In non-interactive mode, .env must already exist. Aborting."
    exit 1
  fi
  echo "Run prepare-droplet.sh first, or copy .env manually."
  exit 1
fi

set -a
# shellcheck disable=SC1091
source .env
set +a

: "${MC_VERSION:?Set MC_VERSION in .env}"
: "${SERVER_PORT:?Set SERVER_PORT in .env}"
: "${RCON_PASSWORD:=}"

echo "==> Deploying server"
echo "    Version: $MC_VERSION"
echo "    Port:    $SERVER_PORT"
echo "    Profile: cloud"

# =============================================================================
# First-boot-only setup (idempotent - safe to re-run)
# =============================================================================

# --- auto-generate RCON password if blank -------------------------------------
if [[ -z "${RCON_PASSWORD:-}" ]]; then
  RCON_PW="$(head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)"
  echo "RCON_PASSWORD=$RCON_PW" >> .env
  export RCON_PASSWORD="$RCON_PW"
  echo "  Auto-generated RCON_PASSWORD."
fi

# --- create required directories ----------------------------------------------
mkdir -p data data/kuma backups modpack-dist

# --- offline mode: load cached images + pre-seed mods -------------------------
if [[ $OFFLINE -eq 1 ]]; then
  echo ""
  echo "==> Offline mode: using cached assets"
  CACHE_DIR="$SERVER_DIR/cache"

  if [[ -d "$CACHE_DIR/images" ]] && ls "$CACHE_DIR/images/"*.tar &> /dev/null 2>&1; then
    echo "  Loading cached Docker images..."
    for tarball in "$CACHE_DIR/images/"*.tar; do
      docker load -i "$tarball" 2> /dev/null && echo "    + $(basename "$tarball")" || true
    done
  fi

  if [[ -d "$CACHE_DIR/server-mods" ]] && ls "$CACHE_DIR/server-mods/"*.jar &> /dev/null 2>&1; then
    mkdir -p data/mods
    cp -n "$CACHE_DIR/server-mods/"*.jar data/mods/ 2> /dev/null || true
    echo "  + Pre-seeded $(find data/mods -name '*.jar' 2>/dev/null | wc -l | xargs) cached mod JARs"
  fi

  # Pre-seed the mod hash so deploy.sh skips Modrinth sync
  MOD_HASH_FILE="$SERVER_DIR/data/.modrinth-hash"
  STACK_VER=$(readlink "$SERVER_DIR/.stack/current" 2> /dev/null || echo "unknown")
  MOD_INPUTS="${STACK_VER}"
  [[ -f "$SERVER_DIR/overlay/mods-extra.txt" ]] && MOD_INPUTS+=$(cat "$SERVER_DIR/overlay/mods-extra.txt")
  [[ -f "$SERVER_DIR/overlay/mods-remove.txt" ]] && MOD_INPUTS+=$(cat "$SERVER_DIR/overlay/mods-remove.txt")
  echo "$MOD_INPUTS" | sha256sum | cut -d' ' -f1 > "$MOD_HASH_FILE"
  echo "  Mod hash saved - deploy.sh will skip Modrinth sync"
  echo ""
fi

# --- initialise restic repository (idempotent) --------------------------------
if command -v restic &> /dev/null \
    && [[ -n "${R2_ACCOUNT_ID:-}" && -n "${R2_BUCKET:-}" \
        && -n "${R2_ACCESS_KEY_ID:-}" && -n "${RESTIC_PASSWORD:-}" ]]; then
  echo ""
  echo "==> Initialising restic backup repository..."
  RESTIC_REPOSITORY="s3:https://${R2_ACCOUNT_ID}.r2.cloudflarestorage.com/${R2_BUCKET}"
  AWS_ACCESS_KEY_ID="$R2_ACCESS_KEY_ID" \
    AWS_SECRET_ACCESS_KEY="${R2_SECRET_ACCESS_KEY:-}" \
    RESTIC_PASSWORD="$RESTIC_PASSWORD" \
    RESTIC_REPOSITORY="$RESTIC_REPOSITORY" \
    restic init 2> /dev/null \
    && echo "  Restic repository initialised" \
    || echo "  Restic repository already initialised (or will init on first backup)"
fi

# --- auto-accept BlueMap download ---------------------------------------------
BLUEMAP_CONF="$SERVER_DIR/data/config/bluemap/core.conf"
if [[ -f "$BLUEMAP_CONF" ]] && grep -q 'accept-download: false' "$BLUEMAP_CONF"; then
  sed -i 's/accept-download: false/accept-download: true/' "$BLUEMAP_CONF"
  echo "  BlueMap: auto-accepted resource download."
fi

BLUEMAP_WEBAPP="$SERVER_DIR/data/config/bluemap/webapp.conf"
if [[ -f "$BLUEMAP_WEBAPP" ]]; then
  sed -i 's/enable-free-flight: true/enable-free-flight: false/' "$BLUEMAP_WEBAPP"
  sed -i 's/default-to-flat-view: false/default-to-flat-view: true/' "$BLUEMAP_WEBAPP"
  if [[ -n "${SPAWN_X:-}" && -n "${SPAWN_Z:-}" ]]; then
    sed -i "s/start-pos: {.*}/start-pos: { x: ${SPAWN_X}, z: ${SPAWN_Z} }/" "$BLUEMAP_WEBAPP"
  fi
  echo "  BlueMap: webapp defaults set (flat view, free-flight off, centred on spawn)."
fi

# --- pull images --------------------------------------------------------------
echo ""
echo "==> Pulling Docker images..."
docker compose --project-directory "$SERVER_DIR" -f "$COMPOSE_FILE" --profile cloud pull

# =============================================================================
# Delegate to deploy.sh for the actual startup
# =============================================================================
echo ""
echo "==> Starting server via deploy.sh..."
export NON_INTERACTIVE
"$SCRIPT_DIR/deploy.sh" --non-interactive

# =============================================================================
echo ""
echo "=================================================================="
echo " Deployment complete."
echo ""
echo " Game server:  mc.${DOMAIN:-example.com}:${SERVER_PORT}"
echo " Web map:      https://map.${DOMAIN:-example.com} (after tunnel setup)"
echo " Modpack:      https://pack.${DOMAIN:-example.com} (after tunnel setup)"
echo " Monitoring:   https://status.${DOMAIN:-example.com} (after tunnel setup)"
echo ""
echo " Admin:"
echo "   docker exec -i mc rcon-cli"
echo "   docker compose --project-directory $SERVER_DIR -f $COMPOSE_FILE --profile cloud logs -f mc"
echo ""
echo " Next steps:"
echo "   1. Run cloudflare-setup.sh to create the tunnel + DNS"
echo "   2. Uptime Kuma monitors auto-configured by kuma-init container"
echo "   3. Test a backup: backup-now.sh"
echo "=================================================================="
