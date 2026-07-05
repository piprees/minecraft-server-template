#!/usr/bin/env bash
# reset-seed.sh - Reset the world with a new seed for a launch event.
#
# Runs FROM your Mac, SSHing to the droplet for all remote operations.
# Backs up everything before destroying world data, then restarts
# the server with the new seed.
#
# Usage:
#   ./scripts/reset-seed.sh                   # interactive (prompts for seed)
#   ./scripts/reset-seed.sh <seed>            # pre-fill seed (still confirms)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="${CONSUMER_DIR:-$(cd "$SCRIPT_DIR/.." && pwd)}"
cd "$PROJECT_DIR"

STAMP="$(date +%Y%m%d-%H%M%S)"

# Portable in-place sed (BSD on macOS requires an extension argument after -i)
sed_i() {
  if [[ "$(uname)" == "Darwin" ]]; then
    sed -i '' "$@"
  else
    sed -i "$@"
  fi
}

# --- load .env ----------------------------------------------------------------
if [[ ! -f .env ]]; then
  echo "No .env found in $PROJECT_DIR. Run setup.sh first."
  exit 1
fi

set -a
# shellcheck disable=SC1091
source .env
set +a

: "${DROPLET_HOST:?Set DROPLET_HOST in .env}"
: "${DEPLOY_USER:?Set DEPLOY_USER in .env (usually 'deploy')}"

CURRENT_SEED="${SEED:-unknown}"
REMOTE="${DEPLOY_USER}@${DROPLET_HOST}"
REPO_NAME="$(basename "$(git rev-parse --show-toplevel 2>/dev/null || echo server)")"
# shellcheck disable=SC2088
REMOTE_DIR="~/${REPO_NAME}"

# --- parse args ---------------------------------------------------------------
NEW_SEED="${1:-}"

# =============================================================================
# 1. Explain what will happen and collect confirmation
# =============================================================================
echo ""
echo "=================================================================="
echo " WORLD RESET - New Seed Deployment"
echo "=================================================================="
echo ""
echo " Droplet:       ${DROPLET_HOST}"
echo " Current seed:  ${CURRENT_SEED}"
echo ""
echo " This script will:"
echo "   1. Back up the world (restic + local tar.gz on the droplet)"
echo "   2. Stop all containers on the droplet"
echo "   3. Delete world data (Overworld, Nether, End)"
echo "   4. Delete BlueMap render data"
echo "   5. Delete Chunky completion marker"
echo "   6. Delete Distant Horizons LOD cache (if present)"
echo "   7. Update the seed in .env (local + droplet)"
echo "   8. Restart the server with the new seed"
echo ""
echo " This is IRREVERSIBLE without restoring from the backup."
echo "=================================================================="
echo ""

# --- prompt for the new seed --------------------------------------------------
if [[ -z "$NEW_SEED" ]]; then
  read -rp "Enter the new seed: " NEW_SEED
fi

if [[ -z "$NEW_SEED" ]]; then
  echo "No seed provided. Aborting."
  exit 1
fi

echo ""
echo "New seed: ${NEW_SEED}"
echo ""

# --- confirm by re-typing the seed -------------------------------------------
read -rp "Type the new seed again to confirm: " CONFIRM_SEED
if [[ "$CONFIRM_SEED" != "$NEW_SEED" ]]; then
  echo "Seeds don't match. Aborting."
  exit 1
fi

# --- confirm destructive action -----------------------------------------------
echo ""
echo "WARNING: This will permanently delete the current world."
echo "         The backup will be the only way to recover it."
echo ""
read -rp "Type RESET to confirm you understand: " CONFIRM_RESET
if [[ "$CONFIRM_RESET" != "RESET" ]]; then
  echo "Confirmation not received. Aborting."
  exit 1
fi

echo ""
echo "==> Starting world reset..."

# =============================================================================
# 2. Backup - restic snapshot via backup-now.sh
# =============================================================================
echo ""
echo "==> Running restic backup on the droplet..."
ssh "$REMOTE" "cd ${REMOTE_DIR} && ./scripts/backup-now.sh" || {
  echo "WARNING: Restic backup failed. Continuing with tar backup."
}

# =============================================================================
# 3. Backup - tar.gz snapshot of data/ on the droplet
# =============================================================================
BACKUP_NAME="pre-reset-${CURRENT_SEED}-${STAMP}.tar.gz"
BACKUP_PATH="backups/${BACKUP_NAME}"

echo ""
echo "==> Creating tar.gz backup on the droplet: ${BACKUP_PATH}"
ssh "$REMOTE" "cd ${REMOTE_DIR} && mkdir -p backups && tar czf ${BACKUP_PATH} data/"
echo "  Backup saved to ${REMOTE_DIR}/${BACKUP_PATH}"

# =============================================================================
# 4. Stop all containers on the droplet
# =============================================================================
echo ""
echo "==> Stopping all containers on the droplet..."
ssh "$REMOTE" "cd ${REMOTE_DIR} && docker compose --profile cloud down"
echo "  Containers stopped."

# =============================================================================
# 5. Delete world data
# =============================================================================
echo ""
echo "==> Deleting world data on the droplet..."

ssh "$REMOTE" "cd ${REMOTE_DIR} && rm -rf data/world/ data/world_the_nether/ data/world_the_end/"
echo "  Deleted: data/world/, data/world_the_nether/, data/world_the_end/"

# --- BlueMap render data (keep config) ----------------------------------------
ssh "$REMOTE" "cd ${REMOTE_DIR} && rm -rf data/bluemap/web/maps/"
echo "  Deleted: data/bluemap/web/maps/ (config preserved)"

# --- Chunky completion marker -------------------------------------------------
ssh "$REMOTE" "cd ${REMOTE_DIR} && rm -f data/.chunky-complete"
echo "  Deleted: data/.chunky-complete"

# --- Distant Horizons LOD cache -----------------------------------------------
if ssh "$REMOTE" "test -d ${REMOTE_DIR}/data/DistantHorizons"; then
  ssh "$REMOTE" "cd ${REMOTE_DIR} && rm -rf data/DistantHorizons/"
  echo "  Deleted: data/DistantHorizons/"
else
  echo "  Skipped: data/DistantHorizons/ (not present)"
fi

# =============================================================================
# 6. Update seed everywhere
# =============================================================================
echo ""
echo "==> Updating seed: ${CURRENT_SEED} -> ${NEW_SEED}"

# --- .env (local) -----------------------------------------------------------------
cp -p .env ".env.bak.${STAMP}"
sed_i "s/^SEED=.*/SEED=${NEW_SEED}/" .env
echo "  Updated .env (backed up to .env.bak.${STAMP})"

# --- .env on the droplet ------------------------------------------------------
ssh "$REMOTE" "cd ${REMOTE_DIR} && cp -p .env .env.bak.${STAMP} && sed -i 's/^SEED=.*/SEED=${NEW_SEED}/' .env"
echo "  Updated .env on droplet (backed up to .env.bak.${STAMP})"

# =============================================================================
# 7. Restart the cloud stack
# =============================================================================
echo ""
echo "==> Starting cloud stack on the droplet..."
ssh "$REMOTE" "cd ${REMOTE_DIR} && docker compose --profile cloud up -d"

# --- wait for healthcheck -----------------------------------------------------
echo ""
echo "==> Waiting for the server to become ready..."
MAX_WAIT=600
ELAPSED=0
INTERVAL=10

while [[ $ELAPSED -lt $MAX_WAIT ]]; do
  if ssh "$REMOTE" "docker exec mc rcon-cli list" &> /dev/null; then
    echo "  Server is ready! (took ${ELAPSED}s)"
    break
  fi
  sleep $INTERVAL
  ELAPSED=$((ELAPSED + INTERVAL))
  echo "    ...waiting (${ELAPSED}s / ${MAX_WAIT}s)"
done

if [[ $ELAPSED -ge $MAX_WAIT ]]; then
  echo ""
  echo "WARNING: Server did not become ready within ${MAX_WAIT}s."
  echo "Check logs: ssh ${REMOTE} 'cd ${REMOTE_DIR} && docker compose --profile cloud logs -f mc'"
  echo "This may be normal on first boot with a new world."
fi

# =============================================================================
# 8. Summary and undo instructions
# =============================================================================
echo ""
echo "=================================================================="
echo " World reset complete."
echo ""
echo " New seed:    ${NEW_SEED}"
echo " Old seed:    ${CURRENT_SEED}"
echo " Backup:      ${REMOTE_DIR}/${BACKUP_PATH}"
echo "=================================================================="
echo ""
echo " To undo (restore world data from backup):"
echo ""
echo "   # 1. Stop the server"
echo "   ssh ${REMOTE} 'cd ${REMOTE_DIR} && docker compose --profile cloud down'"
echo ""
echo "   # 2. Restore the backup"
echo "   ssh ${REMOTE} 'cd ${REMOTE_DIR} && tar xzf ${BACKUP_PATH}'"
echo ""
echo "   # 3. Revert the seed in .env"
echo "   #    Change SEED=${NEW_SEED} back to SEED=${CURRENT_SEED}"
echo ""
echo "   # 4. Revert the seed in .env (local and droplet)"
echo "   #    Or restore from .env.bak.${STAMP}"
echo ""
echo "   # 5. Restart"
echo "   ssh ${REMOTE} 'cd ${REMOTE_DIR} && docker compose --profile cloud up -d'"
echo ""
echo " Don't forget to commit and push .env if deploying via CI."
echo "=================================================================="
