#!/usr/bin/env bash
# lib.sh - Shared utilities for all scripts.
#
# Source this at the top of every script:
#   SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
#   source "$SCRIPT_DIR/lib.sh"
#
# Provides: PROJECT_DIR, colour codes, log/warn/die, load_env (sources .env),
# sed_i + sha256 (macOS/Linux portable), backup (file.bak.TIMESTAMP),
# rcon + get_player_count (via docker exec mc), detect_provider /
# require_provider_cli, and sync_mod_configs.
#
# sync_mod_configs() is the single source of truth for copying committed
# mod configs from config/ into data/config/ (called by deploy.sh on every
# full deploy and initial-setup.sh on first boot). Adding a mod with config
# means: add its copy logic HERE, and add its config dir to MC_PATTERNS in
# .github/workflows/deploy.yml so changes trigger a full deploy.
#
# Everything must run on macOS bash 3.2 - no declare -A, no ${var,,}.

# --- Project root -------------------------------------------------------------
# Precedence: CONSUMER_DIR (ops/dev wrappers) > pre-set PROJECT_DIR (container
# entrypoints export it) > derived from lib.sh location.
PROJECT_DIR="${CONSUMER_DIR:-${PROJECT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}}"

# --- Colours (safe for piped output) -----------------------------------------
# shellcheck disable=SC2034
if [[ -t 1 ]]; then
  RED='\033[0;31m'
  GREEN='\033[0;32m'
  YELLOW='\033[0;33m'
  BLUE='\033[0;34m'
  BOLD='\033[1m'
  RESET='\033[0m'
else
  RED='' GREEN='' YELLOW='' BLUE='' BOLD='' RESET=''
fi

# --- Logging helpers ----------------------------------------------------------
log() { echo -e "${GREEN}==>${RESET} $*"; }
warn() { echo -e "${YELLOW}WARNING:${RESET} $*" >&2; }
die() {
  echo -e "${RED}ERROR:${RESET} $*" >&2
  exit 1
}

# --- Environment loading -----------------------------------------------------
# All config lives in .env (git-ignored). .env.example documents every variable.
load_env() {
  if [[ -f "$PROJECT_DIR/.env" ]]; then
    set -a
    # shellcheck disable=SC1091
    source "$PROJECT_DIR/.env"
    set +a
  fi
}

# --- Portable sed -i (macOS BSD vs GNU) ---------------------------------------
sed_i() {
  if [[ "$(uname)" == "Darwin" ]]; then
    sed -i '' "$@"
  else
    sed -i "$@"
  fi
}

# --- Portable SHA-256 (macOS has shasum, Linux has sha256sum) -----------------
sha256() {
  if command -v sha256sum &> /dev/null; then
    sha256sum "$@"
  else
    shasum -a 256 "$@"
  fi
}

# --- Backup before overwrite --------------------------------------------------
backup() {
  local stamp
  stamp="$(date +%Y%m%d-%H%M%S)"
  [[ -f "$1" ]] && cp -p "$1" "$1.bak.$stamp" && echo "  backed up $1"
}

# --- RCON helpers (require Docker) --------------------------------------------
# CONTAINER_PREFIX is empty by default (single-instance); set to
# "${COMPOSE_PROJECT_NAME}-" for multi-instance to avoid name collisions.
rcon() {
  docker exec "${CONTAINER_PREFIX:-}mc" rcon-cli "$@" 2> /dev/null || true
}

get_player_count() {
  local result
  result=$(docker exec "${CONTAINER_PREFIX:-}mc" rcon-cli "list" 2> /dev/null || echo "")
  if [[ -z "$result" ]]; then
    echo "-1"
    return
  fi
  echo "$result" | grep -oE 'There are [0-9]+' | grep -oE '[0-9]+' || echo "-1"
}

# --- Provider detection -------------------------------------------------------
# Returns: "digitalocean", "hetzner", or "local"
detect_provider() {
  if [[ -n "${HCLOUD_TOKEN:-${HETZNER_API_TOKEN:-}}" ]]; then
    echo "hetzner"
  elif [[ -n "${DO_API_TOKEN:-}" ]]; then
    echo "digitalocean"
  else
    echo "local"
  fi
}

# --- Mod config sync (single source of truth for deploy + initial-setup) ------
# Copies committed mod configs from config/ into data/config/.
# Add new mod config dirs here when adding mods with configs.
sync_mod_configs() {
  local data_cfg="$PROJECT_DIR/data/config"
  mkdir -p "$data_cfg"

  # JSON/JSON5 flat-file configs (copied directly into data/config/)
  for mod_dir in firespreadtweaks healingcampfire youritemsaresafe doubledoors \
    nutritiousmilk collective expanded_bow_enchanting fallingtree lootr \
    groundclear nametagtweaks; do
    if [[ -d "$PROJECT_DIR/config/${mod_dir}" ]]; then
      cp "$PROJECT_DIR/config/${mod_dir}"/*.{json,json5} "$data_cfg/" 2> /dev/null || true
    fi
  done

  # TOML configs (copied into data/config/ as flat files)
  if [[ -f "$PROJECT_DIR/config/betterdays/betterdays-common.toml" ]]; then
    cp "$PROJECT_DIR/config/betterdays/betterdays-common.toml" "$data_cfg/"
  fi
  if [[ -f "$PROJECT_DIR/config/openpartiesandclaims/openpartiesandclaims-server.toml" ]]; then
    cp "$PROJECT_DIR/config/openpartiesandclaims/openpartiesandclaims-server.toml" "$data_cfg/"
  fi

  # Subdirectory configs (need their own directories under data/config/)
  if [[ -d "$PROJECT_DIR/config/boring_default_game_rules" ]]; then
    mkdir -p "$data_cfg/boring_default_game_rules"
    cp "$PROJECT_DIR/config/boring_default_game_rules"/* "$data_cfg/boring_default_game_rules/" 2> /dev/null || true
  fi
  if [[ -f "$PROJECT_DIR/config/voicechat/voicechat-server.properties" ]]; then
    mkdir -p "$data_cfg/voicechat"
    cp "$PROJECT_DIR/config/voicechat/voicechat-server.properties" "$data_cfg/voicechat/"
  fi
  if [[ -f "$PROJECT_DIR/config/essentialcommands/EssentialCommands.properties" ]]; then
    cp "$PROJECT_DIR/config/essentialcommands/EssentialCommands.properties" "$data_cfg/"
    mkdir -p "$data_cfg/essentialcommands"
    cp "$PROJECT_DIR/config/essentialcommands/rules.txt" "$data_cfg/essentialcommands/" 2> /dev/null || true
  fi
  if [[ -d "$PROJECT_DIR/config/starterkit" ]]; then
    mkdir -p "$data_cfg/starterkit/kits" "$data_cfg/starterkit/descriptions"
    cp -r "$PROJECT_DIR/config/starterkit"/* "$data_cfg/starterkit/" 2> /dev/null || true
  fi
  if [[ -f "$PROJECT_DIR/config/starterkit.json5" ]]; then
    cp "$PROJECT_DIR/config/starterkit.json5" "$data_cfg/"
  fi
  if [[ -d "$PROJECT_DIR/config/servercore" ]]; then
    mkdir -p "$data_cfg/servercore"
    cp "$PROJECT_DIR/config/servercore"/*.yml "$data_cfg/servercore/" 2> /dev/null || true
  fi

  # BlueMap core config (render-thread-count, accept-download, etc.)
  if [[ -f "$PROJECT_DIR/config/bluemap/core.conf" ]]; then
    mkdir -p "$data_cfg/bluemap"
    cp "$PROJECT_DIR/config/bluemap/core.conf" "$data_cfg/bluemap/"
  fi

  # BlueMap map configs (always overwrite - repo is source of truth)
  if [[ -d "$PROJECT_DIR/config/bluemap/maps" ]]; then
    local bm_maps="$data_cfg/bluemap/maps"
    mkdir -p "$bm_maps"
    cp "$PROJECT_DIR/config/bluemap/maps"/*.conf "$bm_maps/" 2> /dev/null || true
  fi

  # Custom datapacks (copied to world/datapacks/)
  if [[ -d "$PROJECT_DIR/config/datapacks" ]]; then
    local dp_dir="$PROJECT_DIR/data/world/datapacks"
    mkdir -p "$dp_dir"
    for pack in "$PROJECT_DIR/config/datapacks"/*/; do
      local pack_name
      pack_name="$(basename "$pack")"
      rm -rf "${dp_dir:?}/${pack_name}"
      cp -r "$pack" "$dp_dir/$pack_name"
    done
    echo "  ✓ Custom datapacks synced to world/datapacks/"
  fi

  echo "  ✓ All mod configs synced to data/config/"
}

# Provider-specific server creation tool
require_provider_cli() {
  local provider="${1:-$(detect_provider)}"
  case "$provider" in
    digitalocean)
      if ! command -v doctl &> /dev/null; then
        echo "doctl not found." >&2
        echo "  macOS:        brew install doctl" >&2
        echo "  Debian/Ubuntu: snap install doctl" >&2
        echo "  Other:        https://docs.digitalocean.com/reference/doctl/how-to/install/" >&2
        exit 1
      fi
      ;;
    hetzner)
      if ! command -v hcloud &> /dev/null; then
        echo "hcloud not found." >&2
        echo "  macOS:        brew install hcloud" >&2
        echo "  Debian/Ubuntu: apt install hcloud-cli" >&2
        echo "  Other:        https://github.com/hetznercloud/cli" >&2
        exit 1
      fi
      ;;
    local)
      ;;
  esac
}
