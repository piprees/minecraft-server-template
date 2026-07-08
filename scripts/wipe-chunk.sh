#!/usr/bin/env bash
# wipe-chunk.sh - Delete a region file so its chunks regenerate from the seed.
#
# Minecraft stores chunks in 32x32 region files. This script identifies the
# region file containing the given block or chunk coordinates, moves it aside
# (backup), and the server regenerates those chunks on next load.
#
# The server must be stopped or the chunks must be unloaded — writing to
# region files while the server holds them causes corruption. The script
# checks for a running mc container and refuses unless --force is passed.
#
# Usage:
#   ./scripts/wipe-chunk.sh --block -1808 -2832        # block coordinates (F3 screen)
#   ./scripts/wipe-chunk.sh --chunk -113 -177           # chunk coordinates (Chunky output)
#   ./scripts/wipe-chunk.sh --region -4 -6              # region file directly
#   ./scripts/wipe-chunk.sh --block -1808 -2832 --nether  # non-overworld dimension
#   ./scripts/wipe-chunk.sh --block -1808 -2832 --force   # skip running-server check
#   ./scripts/wipe-chunk.sh --block -1808 -2832 --dry-run # show what would be deleted
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib.sh"
load_env

COORD_TYPE=""
X=""
Z=""
DIMENSION="overworld"
FORCE=false
DRY_RUN=false
REMOTE=true

# --- parse args ---------------------------------------------------------------
while [[ $# -gt 0 ]]; do
  case "$1" in
    --block)  COORD_TYPE="block";  X="$2"; Z="$3"; shift 3 ;;
    --chunk)  COORD_TYPE="chunk";  X="$2"; Z="$3"; shift 3 ;;
    --region) COORD_TYPE="region"; X="$2"; Z="$3"; shift 3 ;;
    --nether)       DIMENSION="nether"; shift ;;
    --end)          DIMENSION="end"; shift ;;
    --force)        FORCE=true; shift ;;
    --dry-run)      DRY_RUN=true; shift ;;
    --local)        REMOTE=false; shift ;;
    *)
      echo "Unknown argument: $1" >&2
      echo "Usage: $0 --block|-chunk|--region X Z [--nether|--end] [--force] [--dry-run]" >&2
      exit 1
      ;;
  esac
done

if [[ -z "$COORD_TYPE" || -z "$X" || -z "$Z" ]]; then
  echo "Usage: $0 --block|--chunk|--region X Z [--nether|--end] [--force] [--dry-run]"
  echo ""
  echo "Examples:"
  echo "  $0 --block -1808 -2832          # F3 coordinates"
  echo "  $0 --chunk -113 -177            # Chunky output"
  echo "  $0 --region -4 -6              # region file directly"
  echo "  $0 --block -1808 -2832 --nether # Nether dimension"
  exit 1
fi

# --- convert to region coordinates -------------------------------------------
floor_div() {
  local n="$1" d="$2"
  if [[ $n -ge 0 ]]; then
    echo $((n / d))
  else
    echo $(( (n - d + 1) / d ))
  fi
}

case "$COORD_TYPE" in
  block)
    CHUNK_X=$(floor_div "$X" 16)
    CHUNK_Z=$(floor_div "$Z" 16)
    REGION_X=$(floor_div "$CHUNK_X" 32)
    REGION_Z=$(floor_div "$CHUNK_Z" 32)
    ;;
  chunk)
    CHUNK_X="$X"
    CHUNK_Z="$Z"
    REGION_X=$(floor_div "$X" 32)
    REGION_Z=$(floor_div "$Z" 32)
    ;;
  region)
    REGION_X="$X"
    REGION_Z="$Z"
    CHUNK_X=$((X * 32))
    CHUNK_Z=$((Z * 32))
    ;;
esac

# --- resolve world directory --------------------------------------------------
case "$DIMENSION" in
  overworld) WORLD_DIR="data/world/region" ;;
  nether)    WORLD_DIR="data/world/DIM-1/region" ;;
  end)       WORLD_DIR="data/world/DIM1/region" ;;
esac

REGION_FILE="r.${REGION_X}.${REGION_Z}.mca"
STAMP="$(date +%Y%m%d-%H%M%S)"
BACKUP_FILE="${REGION_FILE}.bak.${STAMP}"

echo "Wipe chunk region"
echo "=============================="
echo "  Input:      ${COORD_TYPE} ${X}, ${Z}"
echo "  Dimension:  ${DIMENSION}"
echo "  Chunk:      ${CHUNK_X}, ${CHUNK_Z}"
echo "  Region:     ${REGION_X}, ${REGION_Z}"
echo "  File:       ${WORLD_DIR}/${REGION_FILE}"
echo "  Covers:     chunks (${REGION_X}*32)..(${REGION_X}*32+31), (${REGION_Z}*32)..(${REGION_Z}*32+31)"
echo "              = blocks ($((REGION_X*512)))..$((REGION_X*512+511)), ($((REGION_Z*512)))..$((REGION_Z*512+511))"
echo ""

# --- build the remote command -------------------------------------------------
do_wipe() {
  local base_dir="$1"
  local region_path="${base_dir}/${WORLD_DIR}/${REGION_FILE}"
  local backup_path="${base_dir}/${WORLD_DIR}/${BACKUP_FILE}"

  if [[ "$FORCE" != true ]]; then
    if docker ps --format '{{.Names}}' 2> /dev/null | grep -qx mc; then
      echo "ERROR: mc container is running. Stop it first or use --force." >&2
      echo "  The server holds region files open — writing while running risks corruption." >&2
      exit 1
    fi
  fi

  if [[ ! -f "$region_path" ]]; then
    echo "Region file not found: $region_path"
    echo "Nothing to wipe (chunks will generate fresh on next load)."
    exit 0
  fi

  if [[ "$DRY_RUN" == true ]]; then
    echo "DRY RUN: would move $region_path -> $backup_path"
    exit 0
  fi

  mv "$region_path" "$backup_path"
  echo "Moved: ${REGION_FILE} -> ${BACKUP_FILE}"
  echo "Chunks in this region will regenerate from the seed on next load."
  echo ""
  echo "To undo: mv '${backup_path}' '${region_path}'"
}

if [[ "$REMOTE" == false ]]; then
  do_wipe "."
else
  : "${DROPLET_HOST:?Set DROPLET_HOST in .env}"
  DEPLOY_USER="${DEPLOY_USER:-deploy}"
  SSH_KEY="$HOME/.ssh/${BRAND_SLUG:+${BRAND_SLUG}_}mc_deploy_key"

  # shellcheck disable=SC2029
  ssh -i "$SSH_KEY" "${DEPLOY_USER}@${DROPLET_HOST}" \
    "cd ~/server && $(declare -f do_wipe floor_div) && \
     FORCE=$FORCE DRY_RUN=$DRY_RUN \
     do_wipe ."
fi
