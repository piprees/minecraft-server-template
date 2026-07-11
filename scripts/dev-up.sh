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
# Pin images to the stack version so local dev uses the same images as
# production. STACK_VERSION=v2 → IMAGE_TAG=2 → pulls :2 (latest v2.x.y).
if [[ -n "${STACK_VERSION:-}" && -z "${IMAGE_TAG:-}" ]]; then
  export IMAGE_TAG="${STACK_VERSION#v}"
fi
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

# --- Enforce c2me density-function-compiler OFF -------------------------------
# c2me's DFC caches compiled density functions across NoiseConfig creations,
# ignoring the seed — custom dimensions clone the main world without this.
# Mirrors deploy.sh step 8c. Idempotent.
C2ME_TOML="$CONSUMER_DIR/data/config/c2me.toml"
python3 - "$C2ME_TOML" << 'PYEOF'
import sys, os, re
p = sys.argv[1]
section = "[vanillaWorldGenOptimizations]"
key = "useDensityFunctionCompiler"
if os.path.exists(p):
    s = open(p).read()
    if key in s:
        s2 = re.sub(r'%s\s*=\s*\S+' % key, '%s = false' % key, s)
    elif section in s:
        s2 = s.replace(section, section + "\n\t%s = false" % key)
    else:
        s2 = s + "\n%s\n\t%s = false\n" % (section, key)
    if s2 != s:
        open(p, "w").write(s2)
        print("  c2me: useDensityFunctionCompiler forced off (per-dimension seeds)")
else:
    os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p, "w").write("%s\n\t%s = false\n" % (section, key))
PYEOF

# Distant Horizons: silence the per-boot G1/explicit-GC warning wall.
DH_TOML="$CONSUMER_DIR/data/config/DistantHorizons.toml"
if [[ -f "$DH_TOML" ]]; then
  DH_SEDS=(
    -e 's/logGarbageCollectorWarning = true/logGarbageCollectorWarning = false/'
    -e 's/showGarbageCollectorWarning = true/showGarbageCollectorWarning = false/'
    -e 's/logExplicitGcDisabledWarning = true/logExplicitGcDisabledWarning = false/'
    -e 's/showExplicitGcDisabledWarning = true/showExplicitGcDisabledWarning = false/'
  )
  if [[ "$(uname)" == "Darwin" ]]; then
    sed -i '' "${DH_SEDS[@]}" "$DH_TOML"
  else
    sed -i "${DH_SEDS[@]}" "$DH_TOML"
  fi
fi

# --- Install in-house mod JARs from the bundle --------------------------------
# Mirrors deploy.sh on production: stack/local-mods/*.jar -> data/mods/.
# Overwrite deliberately so a bundle update replaces stale copies. Without
# this step the local stack runs WITHOUT the in-house mods and local testing
# can't catch mod regressions before they hit production.
LOCAL_MODS="$STACK_DIR/local-mods"
if [[ -d "$LOCAL_MODS" ]] && ls "$LOCAL_MODS"/*.jar &> /dev/null 2>&1; then
  cp "$LOCAL_MODS"/*.jar "$CONSUMER_DIR/data/mods/"
  # Record what we installed: the stale-jar prune exempts these names even
  # when the bundle dir isn't visible (e.g. platform-dev checkouts).
  for j in "$LOCAL_MODS"/*.jar; do basename "$j"; done > "$CONSUMER_DIR/data/mods/.local-mods-manifest"
  echo "  Installed $(ls "$LOCAL_MODS"/*.jar | wc -l | tr -d ' ') in-house mod JAR(s) from the bundle"
fi

# --- Pre-seed mods from mirror/cache -----------------------------------------
# The mod mirror uses content-addressed filenames ({slug}-{versionId}.jar) but
# itzg expects Modrinth's original filenames. mirror-map.json maps between them.
# cache/server-mods/ uses original filenames (copied from data/mods/ after boot).
MOD_DIR="$CONSUMER_DIR/data/mods"
SEEDED=0

# Mirror directories (content-addressed, need mirror-map.json for renaming)
for mirror_dir in "$CONSUMER_DIR/modpack/dist/mods" "$STACK_DIR/../modpack/dist/mods"; do
  MAP_FILE="$mirror_dir/mirror-map.json"
  if [[ -f "$MAP_FILE" ]]; then
    while IFS= read -r line; do
      mirror_name=$(echo "$line" | cut -d'|' -f1)
      original_name=$(echo "$line" | cut -d'|' -f2)
      src="$mirror_dir/$mirror_name"
      dest="$MOD_DIR/$original_name"
      if [[ -f "$src" && ! -f "$dest" ]]; then
        cp "$src" "$dest"
        SEEDED=$((SEEDED + 1))
      fi
    done < <(python3 -c "
import json, sys
m = json.load(open(sys.argv[1]))
for k, v in m.items():
    print(f'{k}|{v}')
" "$MAP_FILE" 2>/dev/null)
    break
  fi
done

# Server-mods cache (original filenames, direct copy)
if [[ -d "$CONSUMER_DIR/cache/server-mods" ]] && ls "$CONSUMER_DIR/cache/server-mods/"*.jar &> /dev/null 2>&1; then
  for jar in "$CONSUMER_DIR/cache/server-mods/"*.jar; do
    dest="$MOD_DIR/$(basename "$jar")"
    if [[ ! -f "$dest" ]]; then
      cp "$jar" "$dest"
      SEEDED=$((SEEDED + 1))
    fi
  done
fi

if [[ $SEEDED -gt 0 ]]; then
  echo "  Pre-seeded $SEEDED mod JARs from local mirror/cache"
fi

# --- Start the local profile --------------------------------------------------
# Mod downloads use MODS_FILE (URLs resolved by the seed container, cached) —
# no Modrinth API at boot; itzg fetches only files missing from data/mods.
# Legacy hash-gate override cleanup:
rm -f "$CONSUMER_DIR/.modrinth-override.yml"
# --project-directory is critical: it makes ./data, ./overlay, ./modpack-dist,
# ./backups resolve relative to the consumer dir, not .stack/current/stack.
compose_up() {
  docker compose \
    -f "$STACK_DIR/docker-compose.yml" \
    -f "$STACK_DIR/docker-compose.local.yml" \
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

# --- Prune stale mod jars against the seed's manifest --------------------------
# The manifest was written by THIS boot's seed run, so removals from the
# overlay take effect immediately; in-house bundle jars are exempt.
MANIFEST=$(docker run --rm -v "${COMPOSE_PROJECT_NAME}_stack-mods":/m:ro alpine cat /m/mods-manifest.txt 2> /dev/null || echo "")
LOCAL_MANIFEST=$(cat "$CONSUMER_DIR/data/mods/.local-mods-manifest" 2> /dev/null || echo "")
if [[ -n "$MANIFEST" ]]; then
  PRUNED=0
  for jar in "$CONSUMER_DIR"/data/mods/*.jar; do
    [[ -f "$jar" ]] || continue
    base=$(basename "$jar")
    grep -qxF "$base" <<< "$MANIFEST" && continue
    grep -qxF "$base" <<< "$LOCAL_MANIFEST" && continue
    [[ -f "$STACK_DIR/local-mods/$base" ]] && continue
    rm -f "$jar"
    PRUNED=$((PRUNED + 1))
  done
  [[ $PRUNED -gt 0 ]] && echo "  Pruned $PRUNED stale mod jar(s)"
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
