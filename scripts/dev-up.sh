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
#   CONSUMER_DIR=/path/to/consumer dev-up.sh refresh-config
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

# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib.sh"

# --- Parse command ------------------------------------------------------------
ACTION="${1:-up}"

case "$ACTION" in
  down | stop)
    # Load env for COMPOSE_PROJECT_NAME
    if [[ -f "$CONSUMER_DIR/.env" ]]; then
      set -a
      source "$CONSUMER_DIR/.env"
      set +a
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
      set -a
      source "$CONSUMER_DIR/.env"
      set +a
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
  refresh-config)
    BUNDLE_CONFIG="$STACK_DIR/config"
    local_data_cfg="$CONSUMER_DIR/data/config"
    timestamp="$(date '+%Y%m%d-%H%M%S')"
    if [[ -d "$local_data_cfg" ]]; then
      cp -R "$local_data_cfg" "${local_data_cfg}.bak.${timestamp}"
      echo "Backed up data/config to data/config.bak.${timestamp}"
    fi
    mkdir -p "$local_data_cfg"

    if [[ ! -d "$BUNDLE_CONFIG" ]]; then
      echo "Platform config directory not found: $BUNDLE_CONFIG" >&2
      exit 1
    fi

    echo "Refreshing platform defaults into data/config/..."
    cd "$BUNDLE_CONFIG"
    platform_config_files | while IFS= read -r f; do
      dest="$local_data_cfg/${f#./}"
      mkdir -p "$(dirname "$dest")"
      cp "$f" "$dest"
      printf '  platform: %s\n' "${f#./}"
    done
    cd "$CONSUMER_DIR"

    # The staged dimension overlay is DERIVED from overlay/config/custom-dimensions,
    # never authored in data/, so clear it unconditionally before rebuilding.
    #
    # Clearing it only when a source directory exists means REMOVING a
    # consumer overlay never takes effect: the staged copy survives and
    # keeps replacing every platform dimension file, silently, for good.
    # The symptom is a boot warning about config you have already fixed.
    rm -rf "$local_data_cfg/custom-dimensions/overlay"

    if [[ -d "$CONSUMER_DIR/overlay/config" ]]; then
      echo "Reapplying consumer overlay..."
      cd "$CONSUMER_DIR/overlay/config"
      # custom-dimensions overlays are routed to the mod's overlay dir —
      # they must never clobber the platform dimension files (the mod
      # resolves replace/"overrides"/empty-{} itself).
      find . -type f -not -path './custom-dimensions/*' | while IFS= read -r f; do
        dest="$local_data_cfg/${f#./}"
        mkdir -p "$(dirname "$dest")"
        cp "$f" "$dest"
        printf '  overlay: %s\n' "${f#./}"
      done
      cd "$CONSUMER_DIR"
      if [[ -d "$CONSUMER_DIR/overlay/config/custom-dimensions" ]]; then
        mkdir -p "$local_data_cfg/custom-dimensions/overlay"
        cp -R "$CONSUMER_DIR/overlay/config/custom-dimensions/." \
          "$local_data_cfg/custom-dimensions/overlay/"
        echo "  overlay: custom-dimensions/ -> custom-dimensions/overlay/"
      fi
    fi

    C2ME_TOML="$local_data_cfg/c2me.toml"
    python3 "$SCRIPT_DIR/force-toml-key.py" "$C2ME_TOML" \
      "[vanillaWorldGenOptimizations]" "useDensityFunctionCompiler" "false"
    echo "Config refresh complete. Review changes against data/config.bak.${timestamp}."
    exit 0
    ;;
  up | start)
    ;;
  *)
    echo "Usage: dev-up.sh [up|down|logs|refresh-config]"
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
echo "  Web:      http://${LOCAL_DOMAIN}:${WEB_PORT}"
echo "  Seeds:    http://seeds.${LOCAL_DOMAIN}:${WEB_PORT} (local only)"
echo "  Memory:   ${MEMORY:-5G}"
echo ""
echo "  Add to /etc/hosts if not already present:"
echo ""
echo "    127.0.0.1  ${LOCAL_DOMAIN} mc.${LOCAL_DOMAIN} map.${LOCAL_DOMAIN} status.${LOCAL_DOMAIN} pack.${LOCAL_DOMAIN} mods.${LOCAL_DOMAIN} seeds.${LOCAL_DOMAIN}"
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
  platform_config_files | while IFS= read -r f; do
    dest="$local_data_cfg/${f#./}"
    if [[ ! -f "$dest" ]]; then
      mkdir -p "$(dirname "$dest")"
      cp "$f" "$dest"
    fi
  done
  cd "$CONSUMER_DIR"
fi

# v4 dimension overlay for the custom-dimensions mod (mirrors deploy.sh):
# overlay/config/custom-dimensions/ -> data/config/custom-dimensions/overlay/
# — the mod resolves replace/"overrides"/empty-{} against the platform
# dimensions/ itself, so overlay files never clobber the defaults.
if [[ -d "$CONSUMER_DIR/overlay/config/custom-dimensions" ]]; then
  rm -rf "$CONSUMER_DIR/data/config/custom-dimensions/overlay"
  mkdir -p "$CONSUMER_DIR/data/config/custom-dimensions/overlay"
  cp -R "$CONSUMER_DIR/overlay/config/custom-dimensions/." \
    "$CONSUMER_DIR/data/config/custom-dimensions/overlay/"
  echo "  Dimension overlay staged at data/config/custom-dimensions/overlay/"
fi

# --- Sync platform datapacks into the world -----------------------------------
# Mirrors deploy.sh: bundle config/datapacks/<pack>/ -> data/world/datapacks/,
# then overlay packs of the same name replace the platform copy. Without this
# the local stack never loads platform datapacks (structures tuning,
# adventure-mob-sweep) and local testing diverges from production.
dp_dir="$CONSUMER_DIR/data/world/datapacks"
if [[ -d "$STACK_DIR/config/datapacks" ]]; then
  mkdir -p "$dp_dir"
  for pack in "$STACK_DIR/config/datapacks"/*/; do
    [[ -d "$pack" ]] || continue
    pack_name="$(basename "$pack")"
    rm -rf "${dp_dir:?}/${pack_name}"
    cp -r "$pack" "$dp_dir/$pack_name"
  done
  echo "  Platform datapacks synced to world/datapacks/"
fi
if [[ -d "$CONSUMER_DIR/overlay/config/datapacks" ]]; then
  mkdir -p "$dp_dir"
  for pack in "$CONSUMER_DIR/overlay/config/datapacks"/*/; do
    [[ -d "$pack" ]] || continue
    pack_name="$(basename "$pack")"
    rm -rf "${dp_dir:?}/${pack_name}"
    cp -r "$pack" "$dp_dir/$pack_name"
  done
  echo "  Overlay datapacks synced to world/datapacks/"
fi

# Strip datapack overrides owned by mods removed via overlay/mods-remove.txt
# — a structure_set referencing an absent mod's structures fails registry
# load and blocks the boot. AFTER both syncs so overlay-swapped presets are
# covered; only packs carrying an ownership.json are touched. Mirrors
# deploy.sh.
if [[ -d "$dp_dir" ]]; then
  python3 "$SCRIPT_DIR/filter-datapacks.py" "$dp_dir" \
    "$STACK_DIR/config/modrinth-mods.txt" "$CONSUMER_DIR/overlay" || true
fi

# --- Enforce c2me density-function-compiler OFF -------------------------------
# c2me's DFC caches compiled density functions across NoiseConfig creations,
# ignoring the seed — custom dimensions clone the main world without this.
# Mirrors deploy.sh step 8c. Idempotent.
C2ME_TOML="$CONSUMER_DIR/data/config/c2me.toml"
python3 "$SCRIPT_DIR/force-toml-key.py" "$C2ME_TOML" \
  "[vanillaWorldGenOptimizations]" "useDensityFunctionCompiler" "false"

# Distant Horizons: silence the per-boot G1/explicit-GC warning wall, and keep
# distant generation off. Nothing local has a player far enough out to need
# LODs, and the generation machinery is ~50 threads and a batch chunk
# generator per level, all writing SQLite onto the Docker Desktop file share.
DH_TOML="$CONSUMER_DIR/data/config/DistantHorizons.toml"
if [[ -f "$DH_TOML" ]]; then
  dh_silence_warnings "$DH_TOML"
  # Local-only: no distant, server-side or real-time generation in dev.
  sed_i \
    -e 's/enableDistantGeneration = true/enableDistantGeneration = false/' \
    -e 's/enableServerGeneration = true/enableServerGeneration = false/' \
    -e 's/enableRealTimeUpdates = true/enableRealTimeUpdates = false/' \
    "$DH_TOML"
fi

# Ledger keeps its SQLite in WAL mode, and WAL needs a -shm file held as a
# shared mmap. data/ is a virtiofs share here, which does not keep that mapping
# coherent: the page goes out from under the mapping and the JVM dies with
# SIGBUS (BUS_ADRERR) in the middle of a commit, taking mc with it. Point the
# database at the ledger-db volume, which is ext4 inside the VM. SQLite derives
# the -wal/-shm names from the RESOLVED path, so they follow it off the share.
# Production keeps the real file: its data/ is ext4 already.
# Vanilla refuses to load a world containing a symlink whose target is not in
# allowed_symlinks.txt, and dies with "Found forbidden symlinks". A bare line
# is a prefix match against the target, and the file lives in the server's top
# directory — /data, not the world.
LEDGER_ALLOW="$CONSUMER_DIR/data/allowed_symlinks.txt"
if [[ ! -f "$LEDGER_ALLOW" ]] || ! grep -qx '/ledger-db' "$LEDGER_ALLOW"; then
  echo '/ledger-db' >> "$LEDGER_ALLOW"
fi

# A fresh named volume is root-owned and mc runs as uid 1000, which Ledger
# reports as SQLITE_CANTOPEN and a tick-loop crash. Chown through the mc image
# so this pulls nothing.
# The tag comes from the compose file rather than a second copy here: a
# duplicated pin silently stops matching when the compose one moves, and the
# only symptom is the SQLITE_CANTOPEN crash this exists to prevent.
MC_IMAGE="$(sed -n 's|.*image: .*/\(itzg/minecraft-server:[^ ]*\).*|\1|p' \
  "$STACK_DIR/docker-compose.yml" | head -1)"
MC_IMAGE="${MIRROR_REGISTRY:-ghcr.io/piprees/mirrors}/${MC_IMAGE:-itzg/minecraft-server:latest}"

# $1 = volume suffix, $2 = mount path, $3 = what breaks if the chown fails.
chown_sqlite_volume() {
  local project="${COMPOSE_PROJECT_NAME:-${BRAND_SLUG:-myserver}}"
  local vol="${project}_$1"
  # Created here rather than by compose so the chown lands before mc starts.
  # Without compose's own labels every `./dev up` warns about the volume.
  docker volume create \
    --label com.docker.compose.project="$project" \
    --label com.docker.compose.volume="$1" \
    "$vol" > /dev/null 2>&1 || true
  if ! docker run --rm --entrypoint chown -v "$vol:$2" \
    "$MC_IMAGE" 1000:1000 "$2" > /dev/null 2>&1; then
    echo "  WARNING: could not chown $vol via $MC_IMAGE" >&2
    echo "  WARNING: $3 until this succeeds" >&2
  fi
}

chown_sqlite_volume ledger-db /ledger-db \
  "Ledger will crash-loop with SQLITE_CANTOPEN"

LEDGER_DB="$CONSUMER_DIR/data/world/ledger.sqlite"
if [[ -d "$CONSUMER_DIR/data/world" && ! -L "$LEDGER_DB" ]]; then
  # An existing database is local audit history for a world that is about to be
  # re-rolled anyway; keeping a crashed copy is worth less than a clean start.
  [[ -f "$LEDGER_DB" ]] && backup "$LEDGER_DB" && rm -f "$LEDGER_DB"
  rm -f "$LEDGER_DB-wal" "$LEDGER_DB-shm"
  ln -sfn /ledger-db/ledger.sqlite "$LEDGER_DB"
  echo "  ledger: database moved off the file share (SIGBUS in WAL shared memory)"
fi

# Distant Horizons opens one WAL-mode SQLite per level at
# <level>/data/DistantHorizons.sqlite, so its -shm mmap takes the same virtiofs
# SIGBUS as Ledger's — on the Server thread, as a level is created ([P5]).
# Point every database at the dh-db volume, ext4 inside the VM. Names are
# flattened into the volume root because the host cannot create directories
# inside a Docker volume. Only the database moves: raids.dat, maps and the
# other per-level NBT stay on the share, where backups and `./dev clean` see
# them.
DH_ALLOW="$CONSUMER_DIR/data/allowed_symlinks.txt"
if [[ ! -f "$DH_ALLOW" ]] || ! grep -qx '/dh-db' "$DH_ALLOW"; then
  echo '/dh-db' >> "$DH_ALLOW"
fi
chown_sqlite_volume dh-db /dh-db \
  "Distant Horizons will fail every level with SQLITE_CANTOPEN"

DH_LINKED=0
# $1 = level directory under data/, $2 = flat database name in the volume.
dh_link_level() {
  local db="$CONSUMER_DIR/data/$1/data/DistantHorizons.sqlite"
  if [[ -L "$db" ]]; then return 0; fi
  mkdir -p "$CONSUMER_DIR/data/$1/data"
  if [[ -f "$db" ]]; then
    backup "$db"
    rm -f "$db"
  fi
  rm -f "$db-wal" "$db-shm"
  ln -sfn "/dh-db/$2.sqlite" "$db"
  DH_LINKED=$((DH_LINKED + 1))
}

if [[ -d "$CONSUMER_DIR/data/world" ]]; then
  dh_link_level world overworld
  dh_link_level world/DIM-1 the_nether
  dh_link_level world/DIM1 the_end

  # Every level already on disk, whichever mod owns it.
  for dh_dir in "$CONSUMER_DIR"/data/world/dimensions/*/*; do
    if [[ ! -d "$dh_dir" ]]; then continue; fi
    dh_slug="$(basename "$dh_dir")"
    dh_ns="$(basename "$(dirname "$dh_dir")")"
    dh_link_level "world/dimensions/$dh_ns/$dh_slug" "${dh_ns}__${dh_slug}"
  done

  # Every dimension the config declares, so one created mid-session finds its
  # symlink already there. The reserved four sit at vanilla or mod-owned paths
  # covered above.
  DH_NS="$(python3 -c \
    'import json,sys;print(json.load(open(sys.argv[1])).get("namespace","adventure"))' \
    "$CONSUMER_DIR/data/config/custom-dimensions/settings.json" 2> /dev/null \
    || echo adventure)"
  for dh_cfg in "$CONSUMER_DIR"/data/config/custom-dimensions/dimensions/*.json; do
    if [[ ! -f "$dh_cfg" ]]; then continue; fi
    dh_slug="$(basename "$dh_cfg" .json)"
    case "$dh_slug" in
      overworld | the_nether | the_end | paradise_lost) continue ;;
    esac
    dh_link_level "world/dimensions/$DH_NS/$dh_slug" "${DH_NS}__${dh_slug}"
  done

  # DH probes the file before opening it and reports a dangling symlink as
  # "Unable to read database file ... check the permissions", so every target
  # has to exist. A zero-byte file is a valid empty SQLite database. Only a
  # container can write into the volume, and it resolves each link itself
  # rather than being handed 160+ names on the command line.
  if ! docker run --rm \
    -v "${COMPOSE_PROJECT_NAME:-${BRAND_SLUG:-myserver}}_dh-db:/dh-db" \
    -v "$CONSUMER_DIR/data:/data" --entrypoint sh "$MC_IMAGE" -c '
      find /data/world -name DistantHorizons.sqlite -type l | while read -r l; do
        t=$(readlink "$l"); [ -e "$t" ] || : > "$t"
      done
      chown -R 1000:1000 /dh-db' > /dev/null 2>&1; then
    echo "  WARNING: could not seed the dh-db volume via $MC_IMAGE" >&2
    echo "  WARNING: Distant Horizons will fail every level load until it can" >&2
  fi

  if [[ "$DH_LINKED" -gt 0 ]]; then
    echo "  distant-horizons: $DH_LINKED level databases moved off the file share"
  fi
fi

# --- Enforce Discord integration config (mirrors deploy.sh step 9) ------------
# The dcintegration mod reads its bot token from Discord-Integration.toml, NOT
# from env vars. dev-up.sh seeds the config with skip-if-exists, so the first
# boot creates the TOML with botToken="INSERT BOT TOKEN HERE" and subsequent
# boots never touch it. Without this injection, INSTANCE stays null and the
# mod's mixin on player leave crashes the server with an NPE.
DI_TOML="$CONSUMER_DIR/data/config/Discord-Integration.toml"
if [[ -n "${DISCORD_BOT_TOKEN:-}" && -f "$DI_TOML" ]]; then
  sed_i \
    -e "s|botToken = \".*\"|botToken = \"${DISCORD_BOT_TOKEN}\"|" \
    -e "s|botChannel = .*|botChannel = ${DISCORD_CHAT_CHANNEL_ID:-${DISCORD_CHANNEL_ID:-0}}|" \
    -e 's|enable = false|enable = true|' \
    -e "s|serverName = \".*\"|serverName = \"${BRAND_NAME:-Server}\"|" \
    -e 's|serverStarting = true|serverStarting = false|' \
    "$DI_TOML" 2> /dev/null || true
  # discord-sync owns slash commands — disable them in the mod.
  if [[ -f "$SCRIPT_DIR/ensure-discord-command-owner.py" ]]; then
    python3 "$SCRIPT_DIR/ensure-discord-command-owner.py" "$DI_TOML"
  fi
  echo "  Discord integration configured (bot token injected)"
elif [[ -z "${DISCORD_BOT_TOKEN:-}" && -f "$DI_TOML" ]]; then
  echo "  Warning: DISCORD_BOT_TOKEN not set — dcintegration will fail to connect"
fi

# --- Install in-house mod JARs ------------------------------------------------
# Mirrors deploy.sh on production: <stack>/local-mods/*.jar -> data/mods/.
# Under `./dev link` those entries are symlinks into a platform checkout's
# build/libs, and cp follows them — so this installs the current local build.
# Overwrite deliberately, so a bundle update or a rebuild replaces stale
# copies. Without this step the local stack runs WITHOUT the in-house mods.
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
" "$MAP_FILE" 2> /dev/null)
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

# --- Tear down existing containers --------------------------------------------
# Ensures entrypoint overrides and env changes from docker-compose.local.yml
# take effect. A plain `up -d` reuses existing containers with stale config;
# `down` removes them so `up` creates fresh ones every time.
echo "  Stopping any existing containers..."
docker compose \
  -f "$STACK_DIR/docker-compose.yml" \
  -f "$STACK_DIR/docker-compose.local.yml" \
  --project-directory "$CONSUMER_DIR" \
  -p "$COMPOSE_PROJECT_NAME" \
  --profile local down 2>/dev/null || true

# --- Start the local profile --------------------------------------------------
# MODS_FILE is empty by default (offline boots — itzg neither downloads nor
# HEAD-checks anything). Run the seed alone first, then fetch any missing
# managed jars host-side from its resolved URL lists BEFORE mc starts.
# Zero network requests when nothing is missing.
# --project-directory is critical: it makes ./data, ./overlay, ./modpack-dist,
# ./backups resolve relative to the consumer dir, not .stack/current/stack.
compose_up() {
  docker compose \
    -f "$STACK_DIR/docker-compose.yml" \
    -f "$STACK_DIR/docker-compose.local.yml" \
    --project-directory "$CONSUMER_DIR" \
    -p "$COMPOSE_PROJECT_NAME" \
    --profile local up -d "$@"
}

compose_up seed
SEED_EXIT=$(docker wait "${CONTAINER_PREFIX:-}seed")
if [[ "$SEED_EXIT" != "0" ]]; then
  echo "  Seed container failed (exit $SEED_EXIT) - refusing to boot on stale mod lists."
  echo "  Check the logs: docker logs ${CONTAINER_PREFIX:-}seed"
  exit 1
fi
"$SCRIPT_DIR/sync-mods.sh" "$CONSUMER_DIR/data" "${COMPOSE_PROJECT_NAME}_stack-mods"

# Repair known-bad data inside third-party mod jars, exactly as deploy.sh does
# on production (idempotent; a patched jar keeps its filename so the
# skip-existing download and the manifest prune both leave it alone).
# MUST run after sync-mods.sh (the jars have to exist) and before mc starts.
#
# Local and production must repair the same jars: without this, Epic
# Dungeons' CamelCase loot ids abort feature placement and spawn lootless
# chests, and carpet's piston mixin crashes the tick loop next to
# Supplementaries.
if [[ -f "$SCRIPT_DIR/patch-mod-data.py" ]]; then
  python3 "$SCRIPT_DIR/patch-mod-data.py" "$CONSUMER_DIR/data/mods" || true
fi

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

# --- Grant op via RCON in offline mode ----------------------------------------
# In offline mode the itzg image can't resolve usernames to UUIDs via Mojang,
# so the OPS env var silently fails. Grant op via RCON post-boot instead —
# the server generates offline UUIDs that work without API calls.
if [[ "${ONLINE_MODE:-TRUE}" == "FALSE" && -n "${OPS:-}" ]]; then
  echo "  Granting op to local players via RCON (offline mode)..."
  IFS=',' read -ra OP_LIST <<< "$OPS"
  for attempt in 1 2 3; do
    ALL_OK=true
    for player in "${OP_LIST[@]}"; do
      player="$(echo "$player" | xargs)"
      [[ -z "$player" ]] && continue
      if docker exec "$MC_NAME" rcon-cli "op $player" 2>/dev/null | grep -qi "nothing changed\|opped"; then
        echo "    op: $player"
      else
        ALL_OK=false
      fi
    done
    $ALL_OK && break
    [[ $attempt -lt 3 ]] && sleep 5
  done
fi

# --- Auto-rebuild modpack if stale --------------------------------------------
# The modpack must stay in sync with the stack version and mod overlay so the
# client has the correct mods, configs, and resource packs. Rebuild when:
#   - modpack-dist/ has no .mrpack file (never built)
#   - the stack version changed since the last build
#   - overlay/mods-extra.txt or overlay/mods-remove.txt changed
# The build runs the modpack-builder image (same as ./dev pack).
PACK_DIR="$CONSUMER_DIR/modpack-dist"
PACK_MARKER="$PACK_DIR/.build-inputs"
STACK_VER="$(cat "$STACK_DIR/VERSION" 2>/dev/null || echo unknown)"
OVERLAY_HASH="$(cat "$CONSUMER_DIR/overlay/mods-extra.txt" "$CONSUMER_DIR/overlay/mods-remove.txt" 2>/dev/null | cksum | awk '{print $1}')"
CURRENT_INPUTS="${STACK_VER}:${OVERLAY_HASH}"
PREV_INPUTS="$(cat "$PACK_MARKER" 2>/dev/null || echo none)"
HAVE_MRPACK=false
ls "$PACK_DIR"/*.mrpack &>/dev/null 2>&1 && HAVE_MRPACK=true

if [[ "$HAVE_MRPACK" == "false" || "$CURRENT_INPUTS" != "$PREV_INPUTS" ]]; then
  echo ""
  if [[ "$HAVE_MRPACK" == "false" ]]; then
    echo "  Modpack not built yet — building now..."
  else
    echo "  Modpack inputs changed — rebuilding..."
  fi
  mkdir -p "$PACK_DIR"
  if docker run --rm \
    -v "$CONSUMER_DIR/overlay:/overlay:ro" \
    -v "$PACK_DIR:/work/dist" \
    -e "DOMAIN=${LOCAL_DOMAIN:-${DOMAIN:-localhost}}" \
    -e "BRAND_NAME=${BRAND_NAME:-My Server}" \
    -e "BRAND_SLUG=${BRAND_SLUG:-myserver}" \
    -e "MC_VERSION=${MC_VERSION:-1.21.1}" \
    -e "SERVER_PORT=${SERVER_PORT:-25577}" \
    -e "LOCAL_DOMAIN=${LOCAL_DOMAIN:-localhost}" \
    -e "GIT_SHA=${GIT_SHA:-local}" \
    -e "DISCORD_INVITE_URL=${DISCORD_INVITE_URL:-}" \
    "${IMAGE_REGISTRY:-ghcr.io/piprees/minecraft-server-template}/modpack-builder:${IMAGE_TAG:-latest}" 2>&1; then
    echo "$CURRENT_INPUTS" > "$PACK_MARKER"
    echo "  Modpack built to modpack-dist/"
  else
    echo "  Warning: modpack build failed — client pack may be stale"
  fi
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
