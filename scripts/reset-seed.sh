#!/usr/bin/env bash
# reset-seed.sh - Reset the world with a new seed for a launch event.
#
# Runs FROM your Mac, SSHing to the droplet for all remote operations.
# Backs up everything before destroying world data, then restarts
# the server with the new seed.
#
# Deletes: all world data (overworld, nether, end, dimensions/),
# player data (playerdata, stats, advancements), uNmINeD map renders,
# Chunky markers + task state + .skip-pause, Distant Horizons cache,
# POI, ledger, dynamic-data-pack-cache. Deletion runs under sudo (sidecars
# write as root, so DEPLOY_USER cannot remove data/unmined-web) and is
# verified afterwards rather than assumed.
#
# Optionally wipes restic backups in R2 (--wipe-backups flag).
#
# After restart, re-runs deploy.sh's post-boot configuration:
# world borders, game rules, permissions, spawn coordinates.
#
# Gotcha: the tar.gz backup runs COLD, after the containers stop. A hot tar of
# a live data/ exits 1 ("file changed as we read it") the moment mc flushes a
# region file, which under set -e aborts the whole reset. The restic snapshot
# has to run hot (the sidecar needs mc for save-off), so it is waited on before
# the stack goes down, and skipped entirely when --wipe-backups would purge it
# minutes later anyway.
#
# Usage:
#   ./scripts/reset-seed.sh                   # interactive (prompts for seed)
#   ./scripts/reset-seed.sh <seed>            # pre-fill seed (still confirms)
#   ./scripts/reset-seed.sh --same-seed       # reset world, keep current seed
#   ./scripts/reset-seed.sh --force           # skip all confirmation prompts
#   ./scripts/reset-seed.sh --wipe-backups    # also purge restic snapshots
#   ./scripts/reset-seed.sh --force --same-seed --wipe-backups
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
SSH_KEY="$HOME/.ssh/${BRAND_SLUG:+${BRAND_SLUG}_}mc_deploy_key"
# shellcheck disable=SC2088
REMOTE_DIR="~/server"
# shellcheck disable=SC2088
STACK_SCRIPTS="${REMOTE_DIR}/.stack/current/stack/scripts"

# --- parse args ---------------------------------------------------------------
WIPE_BACKUPS=false
SAME_SEED=false
FORCE=false
NEW_SEED=""
for arg in "$@"; do
  case "$arg" in
    --wipe-backups) WIPE_BACKUPS=true ;;
    --same-seed) SAME_SEED=true ;;
    --force) FORCE=true ;;
    *) NEW_SEED="$arg" ;;
  esac
done

if [[ "$SAME_SEED" == true ]]; then
  NEW_SEED="$CURRENT_SEED"
fi

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
if [[ "$WIPE_BACKUPS" == true ]]; then
echo "   1. Stop all containers on the droplet (restic skipped: snapshots are purged below)"
else
echo "   1. Back up the world to restic, then stop all containers on the droplet"
fi
echo "   2. Back up data/ to a cold tar.gz on the droplet"
echo "   3. Delete world data (Overworld, Nether, End, dimensions/)"
echo "   4. Delete player data (playerdata, stats, advancements)"
echo "   5. Delete uNmINeD map renders (data/unmined-web)"
echo "   6. Delete all Chunky markers, task state, and .skip-pause"
echo "   7. Delete Distant Horizons LOD cache"
echo "   8. Delete regenerable state (POI, ledger, dynamic-data-pack-cache)"
if [[ "$WIPE_BACKUPS" == true ]]; then
echo "   9. WIPE all restic snapshots in R2"
fi
echo "  10. Update the seed in .env (local + droplet)"
echo "  11. Restart and re-apply game rules, permissions, world borders"
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

if [[ "$FORCE" != true ]]; then
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
else
  echo "  --force: skipping confirmation prompts"
fi

echo ""
echo "==> Starting world reset..."

# =============================================================================
# 2. Backup - restic snapshot via backup-now.sh (hot; needs mc up for save-off)
# =============================================================================
if [[ "$WIPE_BACKUPS" == true ]]; then
  echo ""
  echo "==> Skipping restic backup (--wipe-backups purges snapshots below)."
else
  echo ""
  echo "==> Running restic backup on the droplet..."
  if ssh -i "$SSH_KEY" "$REMOTE" "cd ${REMOTE_DIR} && bash ${STACK_SCRIPTS}/backup-now.sh"; then
    # backup-now.sh only restarts the sidecar; the snapshot lands after
    # INITIAL_DELAY. Wait for it before stopping the stack, or `down` kills the
    # snapshot mid-flight. Bounded: 60 polls x 10s = 10 minutes, then continue.
    echo "  Waiting for the snapshot to land (up to 10 minutes)..."
    RESTIC_DONE=false
    for _ in $(seq 1 60); do
      if ssh -i "$SSH_KEY" "$REMOTE" "docker logs mc-backup --since 30m 2>&1 | grep -q 'snapshot .* saved'"; then
        RESTIC_DONE=true
        break
      fi
      sleep 10
    done
    if [[ "$RESTIC_DONE" == true ]]; then
      echo "  Restic snapshot saved."
    else
      echo "WARNING: No 'snapshot saved' line within 10 minutes. Continuing with the tar backup."
    fi
  else
    echo "WARNING: Restic backup failed. Continuing with the tar backup."
  fi
fi

# =============================================================================
# 3. Stop all containers on the droplet
# =============================================================================
echo ""
echo "==> Stopping all containers on the droplet..."
COMPOSE_FILE="${REMOTE_DIR}/.stack/current/stack/docker-compose.yml"
ssh -i "$SSH_KEY" "$REMOTE" "cd ${REMOTE_DIR} && docker compose --project-directory ${REMOTE_DIR} -f ${COMPOSE_FILE} --profile cloud down"
echo "  Containers stopped."

# =============================================================================
# 4. Backup - cold tar.gz snapshot of data/ on the droplet
# =============================================================================
BACKUP_NAME="pre-reset-${CURRENT_SEED}-${STAMP}.tar.gz"
BACKUP_PATH="backups/${BACKUP_NAME}"

echo ""
echo "==> Creating tar.gz backup on the droplet: ${BACKUP_PATH}"
# tar exit 1 is the "some files differ" warning class; only >=2 is fatal.
# Nothing should be writing now that the stack is down, but a stray writer must
# not abort the reset after the containers have already stopped.
TAR_STATUS=0
ssh -i "$SSH_KEY" "$REMOTE" "cd ${REMOTE_DIR} && mkdir -p backups && tar czf ${BACKUP_PATH} \
  --exclude='data/unmined-web' \
  --exclude='data/mods' \
  --exclude='data/libraries' \
  --exclude='data/versions' \
  --exclude='data/logs' \
  --exclude='data/crash-reports' \
  --exclude='data/DistantHorizons' \
  --exclude='data/DistantHorizons.sqlite' \
  --exclude='data/poi' \
  --exclude='data/ledger.sqlite' \
  --exclude='data/dynamic-data-pack-cache' \
  --exclude='data/kuma' \
  data/" || TAR_STATUS=$?
if [[ "$TAR_STATUS" -ge 2 ]]; then
  echo "ERROR: tar failed (exit ${TAR_STATUS}). Refusing to delete the world without a backup."
  echo "       The stack is stopped. Bring it back with:"
  echo "       ssh -i $SSH_KEY ${REMOTE} 'cd ${REMOTE_DIR} && docker compose --project-directory ${REMOTE_DIR} -f ${COMPOSE_FILE} --profile cloud up -d'"
  exit 1
fi
[[ "$TAR_STATUS" -eq 1 ]] && echo "  NOTE: tar reported changed files (exit 1); archive written."
echo "  Backup saved to ${REMOTE_DIR}/${BACKUP_PATH}"

# =============================================================================
# 5. Delete world + player + regenerable data
# =============================================================================
echo ""
echo "==> Deleting world and player data on the droplet..."

# sudo: sidecars write as root inside their containers, so data/unmined-web is
# root-owned and DEPLOY_USER cannot remove it. Statements are separated by ';'
# not '&&' — chaining meant one Permission denied skipped every later deletion
# and, under set -e, aborted the reset with the world already gone and the
# stack still down.
ssh -i "$SSH_KEY" "$REMOTE" "cd ${REMOTE_DIR}; \
  sudo rm -rf data/world/ data/world_the_nether/ data/world_the_end/ data/dimensions/; \
  sudo rm -rf data/playerdata/ data/stats/ data/advancements/; \
  sudo rm -rf data/unmined-web/maps/ data/unmined-web/index.html; \
  sudo rm -f  data/.chunky-complete data/.chunky-nether-complete data/.chunky-end-complete data/.chunky-paradise-lost-complete; \
  sudo rm -f  data/.skip-pause; \
  sudo rm -rf data/config/chunky/tasks/; \
  sudo rm -rf data/DistantHorizons/ data/DistantHorizons.sqlite; \
  sudo rm -rf data/poi/ data/ledger.sqlite data/dynamic-data-pack-cache/"

# Verify rather than trust: a partial delete leaves the old world in place and
# the new seed would silently generate nothing.
LEFTOVERS="$(ssh -i "$SSH_KEY" "$REMOTE" "cd ${REMOTE_DIR} && ls -d data/world data/world_the_nether data/world_the_end data/dimensions data/unmined-web/maps data/config/chunky/tasks data/dynamic-data-pack-cache 2>/dev/null" || true)"
if [[ -n "$LEFTOVERS" ]]; then
  echo "ERROR: these paths survived deletion:"
  echo "$LEFTOVERS" | sed 's/^/         /'
  echo "       Remove them by hand, then re-run deploy.sh to bring the stack up:"
  echo "       ssh -i $SSH_KEY ${REMOTE} 'cd ${REMOTE_DIR}/.stack/current/stack && bash scripts/deploy.sh --pull --non-interactive'"
  exit 1
fi

echo "  Deleted: world data (all dimensions)"
echo "  Deleted: player data (playerdata, stats, advancements)"
echo "  Deleted: uNmINeD map renders (regenerated on the next render pass)"
echo "  Deleted: Chunky markers, task state, .skip-pause"
echo "  Deleted: Distant Horizons, POI, ledger, dynamic-data-pack-cache"

# =============================================================================
# 6. Update seed everywhere
# =============================================================================
if [[ "$SAME_SEED" == true ]]; then
  echo ""
  echo "==> Keeping current seed: ${CURRENT_SEED}"
else
  echo ""
  echo "==> Updating seed: ${CURRENT_SEED} -> ${NEW_SEED}"

  # --- .env (local) ---------------------------------------------------------------
  # Single-quoted, per the .env writing convention (env_quote in lib.sh).
  cp -p .env ".env.bak.${STAMP}"
  sed_i "s/^SEED=.*/SEED='${NEW_SEED}'/" .env
  echo "  Updated .env (backed up to .env.bak.${STAMP})"

  # --- .env on the droplet --------------------------------------------------------
  ssh -i "$SSH_KEY" "$REMOTE" "cd ${REMOTE_DIR} && cp -p .env .env.bak.${STAMP} && sed -i \"s/^SEED=.*/SEED='${NEW_SEED}'/\" .env"
  echo "  Updated .env on droplet (backed up to .env.bak.${STAMP})"
fi

# =============================================================================
# 7. Wipe restic backups (optional)
# =============================================================================
if [[ "$WIPE_BACKUPS" == true ]]; then
  echo ""
  echo "==> Wiping restic snapshots in R2..."
  # shellcheck disable=SC2087
  ssh -i "$SSH_KEY" "$REMOTE" bash <<'WIPE_EOF'
cd ~/server && set -a && source .env && set +a
export RESTIC_REPOSITORY="s3:https://${R2_ACCOUNT_ID}.r2.cloudflarestorage.com/${R2_BUCKET}"
export AWS_ACCESS_KEY_ID="$R2_ACCESS_KEY_ID"
export AWS_SECRET_ACCESS_KEY="$R2_SECRET_ACCESS_KEY"
export RESTIC_PASSWORD
SNAP_IDS=$(restic snapshots --json 2>/dev/null | python3 -c 'import json,sys; [print(s["short_id"]) for s in json.load(sys.stdin)]' 2>/dev/null)
if [ -n "$SNAP_IDS" ]; then
  restic forget $SNAP_IDS --prune 2>&1 | tail -3
else
  echo "No snapshots to remove"
fi
WIPE_EOF
  echo "  Restic backups wiped"
fi

# =============================================================================
# 8. Restart via deploy.sh (handles compose, config sync, permissions, borders)
# =============================================================================
echo ""
echo "==> Running deploy.sh on the droplet (full server setup)..."
ssh -i "$SSH_KEY" "$REMOTE" "cd ${REMOTE_DIR}/.stack/current/stack && bash scripts/deploy.sh --pull --non-interactive" \
  || echo "WARNING: deploy.sh exited non-zero. Check server logs."

# =============================================================================
# 9. Summary and undo instructions
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
echo "   ssh -i $SSH_KEY ${REMOTE} 'cd ${REMOTE_DIR}/.stack/current/stack && docker compose --project-directory ${REMOTE_DIR} --profile cloud down'"
echo ""
echo "   # 2. Restore the backup"
echo "   ssh -i $SSH_KEY ${REMOTE} 'cd ${REMOTE_DIR} && tar xzf ${BACKUP_PATH}'"
echo ""
echo "   # 3. Revert the seed in .env (local and droplet)"
echo "   #    Or restore from .env.bak.${STAMP}"
echo ""
echo "   # 4. Restart via deploy.sh"
echo "   ssh -i $SSH_KEY ${REMOTE} 'cd ${REMOTE_DIR}/.stack/current/stack && bash scripts/deploy.sh --pull --non-interactive'"
echo ""
echo " Don't forget to commit and push .env if deploying via CI."
echo "=================================================================="
