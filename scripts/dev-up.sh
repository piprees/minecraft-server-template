#!/usr/bin/env bash
# dev-up.sh — Local dev stack management. Called by the consumer's `dev` script.
#
# Handles env loading (.env), hosts-entry printout, and compose
# invocation with --project-directory so relative volumes (overlay/, data/,
# modpack-dist/) resolve to the CONSUMER dir, not .stack/current.
#
# Usage (called by consumer's dev script, not directly):
#   CONSUMER_DIR=/path/to/consumer dev-up.sh up
#   CONSUMER_DIR=/path/to/consumer dev-up.sh down
#   CONSUMER_DIR=/path/to/consumer dev-up.sh logs
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# --- Resolve consumer directory -----------------------------------------------
# CONSUMER_DIR can be passed as env var. Otherwise, walk up from the bundle
# location: .stack/current -> .stack/vX.Y.Z (symlink target) -> consumer dir.
if [[ -z "${CONSUMER_DIR:-}" ]]; then
  # SCRIPT_DIR is .stack/current/stack/scripts or .stack/vX.Y.Z/stack/scripts
  # Go up to .stack/, then up to consumer dir
  STACK_PARENT="$(cd "$SCRIPT_DIR/../.." && pwd)"
  if [[ "$(basename "$STACK_PARENT")" == "current" ]]; then
    # Resolve the symlink: .stack/current -> .stack/vX.Y.Z
    STACK_PARENT="$(cd -P "$STACK_PARENT" && pwd)"
  fi
  # Now at .stack/vX.Y.Z, go up twice to consumer dir
  CONSUMER_DIR="$(cd "$STACK_PARENT/../.." && pwd)"
fi

# The stack dir contains docker-compose.yml etc.
STACK_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# --- Parse command ------------------------------------------------------------
ACTION="${1:-up}"

case "$ACTION" in
  down | stop)
    # Load env for COMPOSE_PROJECT_NAME
    if [[ -f "$CONSUMER_DIR/.env" ]]; then
      set -a; source "$CONSUMER_DIR/.env"; set +a
    fi
    BRAND_SLUG="${BRAND_SLUG:-myserver}"
    COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-$BRAND_SLUG}"
    export COMPOSE_PROJECT_NAME

    echo "Stopping local stack (project: ${COMPOSE_PROJECT_NAME})..."
    docker compose \
      -f "$STACK_DIR/docker-compose.yml" \
      -f "$STACK_DIR/docker-compose.local.yml" \
      --project-directory "$CONSUMER_DIR" \
      -p "$COMPOSE_PROJECT_NAME" \
      --profile local down
    exit 0
    ;;
  logs)
    # Load env for COMPOSE_PROJECT_NAME
    if [[ -f "$CONSUMER_DIR/.env" ]]; then
      set -a; source "$CONSUMER_DIR/.env"; set +a
    fi
    BRAND_SLUG="${BRAND_SLUG:-myserver}"
    COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-$BRAND_SLUG}"
    export COMPOSE_PROJECT_NAME

    docker compose \
      -f "$STACK_DIR/docker-compose.yml" \
      -f "$STACK_DIR/docker-compose.local.yml" \
      --project-directory "$CONSUMER_DIR" \
      -p "$COMPOSE_PROJECT_NAME" \
      --profile local logs -f mc
    exit 0
    ;;
  up | start)
    ;;
  *)
    echo "Usage: dev-up.sh [up|down|logs]"
    exit 1
    ;;
esac

# --- Load consumer environment ------------------------------------------------
if [[ -f "$CONSUMER_DIR/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$CONSUMER_DIR/.env"
  set +a
fi

# --- Resolve variables --------------------------------------------------------
BRAND_SLUG="${BRAND_SLUG:-myserver}"
LOCAL_DOMAIN="${LOCAL_DOMAIN:-${BRAND_SLUG}.local}"
SERVER_PORT="${SERVER_PORT:-25577}"
GAME_PORT="${GAME_PORT:-$SERVER_PORT}"
WEB_PORT="${WEB_PORT:-8080}"
VOICE_PORT="${VOICE_PORT:-24454}"
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-$BRAND_SLUG}"
export COMPOSE_PROJECT_NAME

# --- Check Docker is running --------------------------------------------------
if ! docker info > /dev/null 2>&1; then
  echo "Docker is not running. Start Docker Desktop and try again."
  exit 1
fi

# --- Create directories if needed ---------------------------------------------
# data/mods included: Docker creates missing bind-mount paths as root, and
# mod-checker's read-only ./data/mods mount would leave it unwritable for mc.
mkdir -p "$CONSUMER_DIR/data/mods" \
         "$CONSUMER_DIR/data/config" \
         "$CONSUMER_DIR/modpack-dist" \
         "$CONSUMER_DIR/overlay" \
         "$CONSUMER_DIR/backups"

# --- Auto-generate RCON password if blank -------------------------------------
if [[ -z "${RCON_PASSWORD:-}" ]]; then
  RCON_PW="$(head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)"
  printf "RCON_PASSWORD='%s'\n" "$RCON_PW" >> "$CONSUMER_DIR/.env"
  echo "Auto-generated RCON_PASSWORD and appended to .env."
  export RCON_PASSWORD="$RCON_PW"
fi

# --- Auto-generate Kuma admin password if blank --------------------------------
# kuma-init creates the admin account on first run (KUMA_USERNAME/KUMA_PASSWORD).
if [[ -z "${KUMA_PASSWORD:-}" ]]; then
  KUMA_PW="$(head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)"
  printf "KUMA_PASSWORD='%s'\n" "$KUMA_PW" >> "$CONSUMER_DIR/.env"
  echo "Auto-generated KUMA_PASSWORD and appended to .env."
  export KUMA_PASSWORD="$KUMA_PW"
fi

# --- Print /etc/hosts lines --------------------------------------------------
echo ""
echo "Starting Minecraft server (local profile, project: ${COMPOSE_PROJECT_NAME})..."
echo "  Version:  ${MC_VERSION:-1.21.1}"
echo "  Game:     mc.${LOCAL_DOMAIN}:${GAME_PORT}"
echo "  Voice:    mc.${LOCAL_DOMAIN}:${VOICE_PORT} (UDP)"
echo "  Web:      http://map.${LOCAL_DOMAIN}:${WEB_PORT}"
echo "  Memory:   ${MEMORY:-5G}"
echo ""
echo "  Add to /etc/hosts if not already present:"
echo ""
echo "    127.0.0.1  mc.${LOCAL_DOMAIN} map.${LOCAL_DOMAIN} status.${LOCAL_DOMAIN} pack.${LOCAL_DOMAIN} mods.${LOCAL_DOMAIN}"
echo ""

# --- Seed default mod configs into data/config/ ------------------------------
# Copy platform default configs from the bundle into data/config/ without
# overwriting existing files (mods or the player may have customised them).
BUNDLE_CONFIG="$STACK_DIR/config"
if [[ -d "$BUNDLE_CONFIG" ]]; then
  echo "  Seeding default mod configs into data/config/..."
  local_data_cfg="$CONSUMER_DIR/data/config"
  mkdir -p "$local_data_cfg"
  cd "$BUNDLE_CONFIG"
  find . -type f \
    -not -path './nginx/*' \
    -not -path './uptime-kuma/*' \
    -not -path './cloudflare/*' \
    -not -path './cloudflared/*' \
    -not -name 'modrinth-mods.txt' \
    -not -name 'modrinth-mods.pinned.txt' \
    -not -name 'messages.json' \
    -not -name '1password.env' \
    | while IFS= read -r f; do
    dest="$local_data_cfg/${f#./}"
    if [[ ! -f "$dest" ]]; then
      mkdir -p "$(dirname "$dest")"
      cp "$f" "$dest"
    fi
  done
  cd "$CONSUMER_DIR"
fi

# --- Pre-seed mods from mirror/cache -----------------------------------------
# If modpack/dist/mods/ (the mod mirror) or cache/server-mods/ exists, copy
# any JARs into data/mods/ that aren't already there. itzg skips downloading
# mods it already has, so this reduces Modrinth API calls and avoids rate
# limits on first boot. The mirror has client mods (overlaps ~70% with server
# mods); the server-mods cache has the full set after a previous boot.
MOD_DIR="$CONSUMER_DIR/data/mods"
SEEDED=0
for src_dir in "$CONSUMER_DIR/cache/server-mods" "$CONSUMER_DIR/modpack/dist/mods" \
               "$STACK_DIR/../modpack/dist/mods"; do
  if [[ -d "$src_dir" ]] && ls "$src_dir/"*.jar &> /dev/null 2>&1; then
    for jar in "$src_dir/"*.jar; do
      dest="$MOD_DIR/$(basename "$jar")"
      if [[ ! -f "$dest" ]]; then
        cp "$jar" "$dest"
        SEEDED=$((SEEDED + 1))
      fi
    done
  fi
done
if [[ $SEEDED -gt 0 ]]; then
  echo "  Pre-seeded $SEEDED mod JARs from local mirror/cache"
fi

# --- Modrinth hash-gating ----------------------------------------------------
# Only re-sync mods when the merged mod list changes (or on first boot).
MODRINTH_OVERRIDE="$CONSUMER_DIR/.modrinth-override.yml"
MOD_HASH_FILE="$CONSUMER_DIR/data/.modrinth-hash"

# The seed container produces the merged mod list in the stack-mods volume.
# We hash the consumer's overlay files to detect changes.
HASH_INPUT=""
if [[ -f "$CONSUMER_DIR/overlay/mods-extra.txt" ]]; then
  HASH_INPUT+=$(cat "$CONSUMER_DIR/overlay/mods-extra.txt")
fi
if [[ -f "$CONSUMER_DIR/overlay/mods-remove.txt" ]]; then
  HASH_INPUT+=$(cat "$CONSUMER_DIR/overlay/mods-remove.txt")
fi

if command -v sha256sum > /dev/null 2>&1; then
  CURRENT_MOD_HASH=$(echo "$HASH_INPUT" | sha256sum | cut -d' ' -f1)
else
  CURRENT_MOD_HASH=$(echo "$HASH_INPUT" | shasum -a 256 | cut -d' ' -f1)
fi
PREVIOUS_MOD_HASH=$(cat "$MOD_HASH_FILE" 2>/dev/null || echo "none")

EXTRA_COMPOSE=""
if [[ "$CURRENT_MOD_HASH" != "$PREVIOUS_MOD_HASH" ]]; then
  echo "  Mod overlay changed - Modrinth sync enabled for this boot"
  cat > "$MODRINTH_OVERRIDE" << 'MODEOF'
services:
  mc:
    environment:
      MODRINTH_PROJECTS: "@/extras/modrinth-mods.txt"
MODEOF
  EXTRA_COMPOSE="-f $MODRINTH_OVERRIDE"
else
  rm -f "$MODRINTH_OVERRIDE"
fi

# --- Start the local profile --------------------------------------------------
# --project-directory is critical: it makes ./data, ./overlay, ./modpack-dist,
# ./backups resolve relative to the consumer dir, not .stack/current/stack.
compose_up() {
  # shellcheck disable=SC2086
  docker compose \
    -f "$STACK_DIR/docker-compose.yml" \
    -f "$STACK_DIR/docker-compose.local.yml" \
    $EXTRA_COMPOSE \
    --project-directory "$CONSUMER_DIR" \
    -p "$COMPOSE_PROJECT_NAME" \
    --profile local up -d
}

MC_NAME="${CONTAINER_PREFIX:-}mc"
if ! compose_up; then
  # A mod-sync boot downloads ~150 JARs and mc can restart once mid-sync
  # (Modrinth rate limits). That aborts compose's dependency wait even
  # though mc recovers on its own - so wait for it, then start the rest.
  MC_STATE=$(docker inspect -f '{{.State.Status}}' "$MC_NAME" 2> /dev/null || echo "missing")
  if [[ "$MC_STATE" == "running" || "$MC_STATE" == "restarting" ]]; then
    echo ""
    echo "  mc is still booting (first boot downloads ~150 mods - can take 10+ minutes)."
    echo "  Waiting for it to become healthy before starting the remaining services..."
    HEALTHY=0
    for _ in $(seq 1 90); do
      HEALTH=$(docker inspect -f '{{.State.Health.Status}}' "$MC_NAME" 2> /dev/null || echo "none")
      if [[ "$HEALTH" == "healthy" ]]; then
        HEALTHY=1
        break
      fi
      sleep 10
    done
    if [[ $HEALTHY -eq 1 ]]; then
      echo "  mc is healthy - starting the remaining services..."
      compose_up
    else
      echo "  mc did not become healthy within 15 minutes."
      echo "  Check the logs: ./dev logs"
      exit 1
    fi
  else
    echo "  mc failed to start (state: ${MC_STATE}). Check the logs: ./dev logs"
    exit 1
  fi
fi

# Save mod hash and clean up override
if [[ -f "$MODRINTH_OVERRIDE" ]]; then
  echo "$CURRENT_MOD_HASH" > "$MOD_HASH_FILE"
  rm -f "$MODRINTH_OVERRIDE"
fi

# --- Auto-accept BlueMap download ---------------------------------------------
BLUEMAP_CONF="$CONSUMER_DIR/data/config/bluemap/core.conf"
if [[ -f "$BLUEMAP_CONF" ]] && grep -q 'accept-download: false' "$BLUEMAP_CONF"; then
  if [[ "$(uname)" == "Darwin" ]]; then
    sed -i '' 's/accept-download: false/accept-download: true/' "$BLUEMAP_CONF"
  else
    sed -i 's/accept-download: false/accept-download: true/' "$BLUEMAP_CONF"
  fi
  echo "  BlueMap: auto-accepted resource download."
fi

echo ""
echo "Server starting. First boot downloads Fabric + mods - give it a few minutes."
echo ""
echo "  Watch logs:    ./dev logs"
echo "  RCON console:  ./dev rcon"
echo "  Stop:          ./dev down"
echo ""
echo "  Game server:   mc.${LOCAL_DOMAIN}:${GAME_PORT}"
echo "  Web services:  http://map.${LOCAL_DOMAIN}:${WEB_PORT}"
