#!/usr/bin/env bash
# check-modrinth-compat.sh - Check mods for version/loader compatibility.
#
# Usage:
#   ./scripts/check-modrinth-compat.sh
#   ./scripts/check-modrinth-compat.sh --version 1.21.1 --loader fabric
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="${CONSUMER_DIR:-$(cd "$SCRIPT_DIR/.." && pwd)}"
MOD_FILE="$PROJECT_DIR/config/modrinth-mods.txt"
SERVER_ENV="$PROJECT_DIR/.env.example"
API="https://api.modrinth.com/v2"

# --- defaults from server.env ------------------------------------------------
DEFAULT_VERSION=""
if [[ -f "$SERVER_ENV" ]]; then
  DEFAULT_VERSION=$(grep -E '^MC_VERSION=' "$SERVER_ENV" | head -1 | cut -d= -f2 | tr -d "'" | tr -d '"')
fi
DEFAULT_VERSION="${DEFAULT_VERSION:-1.21.1}"

TARGET_VERSION="$DEFAULT_VERSION"
LOADER="fabric"

# --- arg parsing --------------------------------------------------------------
while [[ $# -gt 0 ]]; do
  case "$1" in
    --version | -v)
      TARGET_VERSION="$2"
      shift 2
      ;;
    --loader | -l)
      LOADER="$2"
      shift 2
      ;;
    --help | -h)
      echo "Usage: $0 [--version MC_VERSION] [--loader LOADER]"
      echo ""
      echo "  --version, -v   Minecraft version to check (default: MC_VERSION from server.env, currently ${DEFAULT_VERSION})"
      echo "  --loader,  -l   Mod loader (default: fabric)"
      exit 0
      ;;
    *)
      echo "Unknown option: $1. Use --help for usage."
      exit 1
      ;;
  esac
done

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
DIM='\033[2m'
BOLD='\033[1m'
RESET='\033[0m'

ok=0
nearby=0
notfound=0
noversion=0

echo -e "${BOLD}Checking mods against Modrinth: ${TARGET_VERSION} / ${LOADER}${RESET}"
echo ""

while IFS= read -r line; do
  # Skip comments and blank lines
  [[ "$line" =~ ^[[:space:]]*# ]] && continue
  [[ -z "${line// /}" ]] && continue

  # Skip datapack entries (not regular mods)
  if [[ "$line" == datapack:* ]]; then
    slug="${line#datapack:}"
    slug="${slug%\?}"
    echo -e "  ${DIM}-${RESET} ${BOLD}$slug${RESET} - ${DIM}datapack (not checked via mod API)${RESET}"
    continue
  fi

  # Strip optional marker and version type suffix for the slug lookup
  slug="${line%\?}"  # remove trailing ?
  slug="${slug%%:*}" # remove :alpha/:beta/:version pin
  optional=""
  [[ "$line" == *'?' ]] && optional=" ${DIM}(optional)${RESET}"

  # 1. Check if the project exists at all
  project_resp=$(curl -s -w "\n%{http_code}" "$API/project/$slug" 2> /dev/null)
  http_code=$(echo "$project_resp" | tail -1)
  project_body=$(echo "$project_resp" | sed '$d')

  if [[ "$http_code" == "404" ]]; then
    echo -e "  ${RED}✗${RESET} ${BOLD}$slug${RESET} - not found on Modrinth${optional}"
    notfound=$((notfound + 1))
    continue
  fi

  project_title=$(echo "$project_body" | jq -r '.title // empty')

  # 2. Check for exact target version + loader match
  exact_resp=$(curl -s "$API/project/$slug/version?loaders=%5B%22${LOADER}%22%5D&game_versions=%5B%22${TARGET_VERSION}%22%5D" 2> /dev/null)
  exact_count=$(echo "$exact_resp" | jq 'length')

  if [[ "$exact_count" -gt 0 ]]; then
    version_number=$(echo "$exact_resp" | jq -r '.[0].version_number')
    version_type=$(echo "$exact_resp" | jq -r '.[0].version_type')
    echo -e "  ${GREEN}✓${RESET} ${BOLD}$slug${RESET} - ${project_title} ${DIM}v${version_number} (${version_type})${RESET}${optional}"
    ok=$((ok + 1))
    continue
  fi

  # 3. No exact match - check what versions exist for this loader
  loader_resp=$(curl -s "$API/project/$slug/version?loaders=%5B%22${LOADER}%22%5D" 2> /dev/null)
  loader_count=$(echo "$loader_resp" | jq 'length')

  if [[ "$loader_count" -gt 0 ]]; then
    nearby_versions=$(echo "$loader_resp" | jq -r '.[0:5] | .[].game_versions[]' | sort -Vu | tr '\n' ', ' | sed 's/,$//')
    latest_number=$(echo "$loader_resp" | jq -r '.[0].version_number')
    latest_type=$(echo "$loader_resp" | jq -r '.[0].version_type')
    echo -e "  ${YELLOW}~${RESET} ${BOLD}$slug${RESET} - ${project_title}: no ${TARGET_VERSION} build. Latest ${LOADER}: ${DIM}v${latest_number} (${latest_type}) for [${nearby_versions}]${RESET}${optional}"
    nearby=$((nearby + 1))
    continue
  fi

  # 4. Check without loader filter (maybe it's Forge-only)
  any_resp=$(curl -s "$API/project/$slug/version" 2> /dev/null)
  any_count=$(echo "$any_resp" | jq 'length')

  if [[ "$any_count" -gt 0 ]]; then
    loaders=$(echo "$any_resp" | jq -r '.[0:5] | .[].loaders[]' | sort -u | tr '\n' ', ' | sed 's/,$//')
    echo -e "  ${RED}✗${RESET} ${BOLD}$slug${RESET} - ${project_title}: no ${LOADER} builds at all. Available loaders: ${DIM}[${loaders}]${RESET}${optional}"
  else
    echo -e "  ${RED}✗${RESET} ${BOLD}$slug${RESET} - ${project_title}: no versions published${optional}"
  fi
  noversion=$((noversion + 1))

  # Rate-limit courtesy (Modrinth asks for 300req/min max)
  sleep 0.2

done < "$MOD_FILE"

echo ""
echo -e "${BOLD}Summary:${RESET}"
echo -e "  ${GREEN}✓${RESET} ${ok} exact match (${TARGET_VERSION}/${LOADER})"
echo -e "  ${YELLOW}~${RESET} ${nearby} nearby version (may work, needs pinning or testing)"
echo -e "  ${RED}✗${RESET} ${notfound} not found on Modrinth"
echo -e "  ${RED}✗${RESET} ${noversion} no compatible loader/version"
