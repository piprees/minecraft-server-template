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
# require_provider_cli.
#
# Mod config sync is handled inline by deploy.sh step 8 (before mc starts).
# Adding a mod with config means: add its files under config/<modname>/ and
# a COPY line in docker/defaults-seed/Dockerfile; platform config changes
# reach consumers via the next release (full deploy via tag comparison).
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

# --- Script banner -----------------------------------------------------------
# Prints the deploy banner (if available) with the brand colour, followed
# by the script name and a brief description. Call at the top of any
# user-facing script: show_banner "deploy" "Full production deploy"
show_banner() {
  # Skip if the ops/dev dispatcher already printed it (BANNER_SHOWN is
  # exported by the dispatchers before exec-ing the target script).
  [[ "${BANNER_SHOWN:-}" == "1" ]] && return
  local cmd="${1:-}" detail="${2:-}"
  local banner_file="${CONSUMER_DIR:-$PROJECT_DIR}/overlay/config/deploy-banner.txt"
  [[ -f "$banner_file" ]] || banner_file="$PROJECT_DIR/config/deploy-banner.txt"
  if [[ -f "$banner_file" ]]; then
    echo -e "${BOLD}"
    cat "$banner_file"
    echo -e "${RESET}"
  fi
  if [[ -n "$cmd" ]]; then
    local brand="${BRAND_NAME:-}"
    local line="${brand:+$brand — }${cmd}${detail:+ | $detail}"
    echo -e "  ${line}"
    echo ""
  fi
  export BANNER_SHOWN=1
}
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

# --- .env writing ---------------------------------------------------------------
# THE rule for .env files: every value is written single-quoted, with any
# embedded single quote mapped to a typographic ’. That form parses
# identically under bash `source` and docker compose's env-file reader.
# An unquoted MOTD containing spaces once executed itself as a command on
# a production server - all writers go through these helpers.
# Users paste values already wrapped in quotes ('token' or "My Server") -
# strip one matching surrounding pair so they don't get double-wrapped.
strip_surrounding_quotes() {
  local v="${1-}"
  if [[ ${#v} -ge 2 ]]; then
    local first="${v:0:1}" last="${v: $((${#v} - 1)):1}"
    if [[ ("$first" == "'" && "$last" == "'") || ("$first" == '"' && "$last" == '"') ]]; then
      v="${v:1:$((${#v} - 2))}"
    fi
  fi
  printf '%s' "$v"
}

env_quote() {
  local v
  v=$(strip_surrounding_quotes "${1-}")
  printf "'%s'" "${v//\'/’}"
}

# set_env_var FILE KEY VALUE - update in place or append, always quoted.
set_env_var() {
  local file="$1" key="$2" value="$3"
  local quoted
  quoted=$(env_quote "$value")
  if grep -q "^${key}=" "$file" 2> /dev/null; then
    # Escape sed replacement metacharacters in the value
    local safe="${quoted//\\/\\\\}"
    safe="${safe//&/\\&}"
    safe="${safe//|/\\|}"
    sed_i "s|^${key}=.*|${key}=${safe}|" "$file"
  else
    printf '%s=%s\n' "$key" "$quoted" >> "$file"
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
