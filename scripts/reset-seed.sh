#!/usr/bin/env bash
# reset-seed.sh - Reset the world with a new seed for a launch event.
#
# Runs FROM your Mac, SSHing to the droplet for all remote operations.
# Backs up everything before destroying world data, then restarts
# the server with the new seed.
#
# Deletes: everything under data/world/ in one sweep — that single directory
# holds the overworld, DIM-1 (nether), DIM1 (end), dimensions/<ns>/<slug>/
# (every custom dimension, including paradise_lost), playerdata, stats,
# advancements, modplayerdata, essentialcommands homes, ec_player_profiles,
# openpartiesandclaims claims (data/world/data/openpartiesandclaims), the
# per-world DistantHorizons.sqlite (+ -shm/-wal), poi, ledger.sqlite, and
# level.dat itself — so there is never a lingering WorldGenSettings.dimensions
# registry entry outliving the world it describes (the D3 boot-wedge trap
# doesn't apply here: level.dat and the world dirs it references die together).
# Separately: uNmINeD map renders (maps/, index.html, manifest.json), Chunky
# markers + task state + .skip-pause, dynamic-data-pack-cache (a real
# top-level dir, unlike the rest of this list), the per-dimension "already
# set up" markers deploy.sh uses to skip re-running `dimension load` on every
# deploy (data/.dimension-setup/ — stale markers here would make deploy.sh
# think every dimension on the NEW world was already loaded on the OLD one,
# and silently skip loading all of them), and the two mod state files that
# describe the world being destroyed — portal_links.json (every portal's
# links, countdowns and aura state) and custom-dimensions-fingerprints.json
# (each dimension's creation-time worldgen baseline). Keeping either would
# leave portals resolving into a world at coordinates that meant something
# else, and drift reported against a world that no longer exists. The mod
# writes both fresh on the next boot. Deletion runs under sudo (sidecars
# write as root, so DEPLOY_USER cannot remove data/unmined-web) and is
# verified afterwards rather than assumed. Also wipes the old world's bot
# and webhook messages from the Discord channels (DISCORD_CHANNEL_ID +
# DISCORD_CHAT_CHANNEL_ID when distinct) via discord-cleanup.sh — fire and
# forget, a Discord outage never blocks a reset.
#
# Deliberately NOT touched: whitelist.json, ops.json, banned-*.json (the
# whitelist is a door lock, not world state); discord-players.json and
# DiscordIntegration-Data/LinkedPlayers.json (Discord-account <-> MC-player
# links survive same as the whitelist — players don't re-link just because
# the world did); .seed-rolling/ (a sibling of data/, not under it — its
# candidate-seed banks, lint, render-check and column-ladder artefacts are
# keyed by a hash of the DIMENSION CONFIG, not the live world, so they stay
# valid across a reset and are irrelevant to the world just destroyed).
#
# The seed argument updates SEED in .env locally and on the server, which
# seeds level.dat ONLY. Terrain comes from each base world's dimension config
# (TROUBLESHOOTING.md#t31), so a new seed here changes nothing on its own:
# put the seed in the config, deploy it, and reset AFTER it is on the server.
# Same for spawn — the overworld entry's "spawn" replaces SPAWN_X/Y/Z.
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
echo "   3. Delete data/world/ (every dimension's terrain, player data, DH cache,"
echo "      POI, ledger, and level.dat, in one sweep)"
echo "   4. Delete uNmINeD map renders (data/unmined-web: maps/, index.html, manifest.json)"
echo "   5. Delete all Chunky markers, task state, and .skip-pause"
echo "   6. Delete dynamic-data-pack-cache and the per-dimension deploy-setup markers"
echo "   6b. Delete portal links and dimension fingerprints for the old world"
echo "   7. Wipe old-world bot/webhook messages from the Discord channels"
if [[ "$WIPE_BACKUPS" == true ]]; then
echo "   8. WIPE all restic snapshots in R2"
fi
echo "   9. Update the seed in .env (local + droplet)"
echo "  10. Restart and re-apply game rules, permissions, world borders"
echo ""
echo " NOTE: step 9 only updates .env's SEED value. Terrain comes from each"
echo " dimension's own config (TROUBLESHOOTING.md#t31) — for this seed to"
echo " reach the overworld, put it in"
echo " config/custom-dimensions/dimensions/overworld.json (seed: \"env\")"
echo " and deploy that BEFORE running this reset, or the world regenerates"
echo " with the OLD terrain under a new seed value that changed nothing."
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
  --exclude='data/world/data/DistantHorizons.sqlite*' \
  --exclude='data/world/poi' \
  --exclude='data/world/ledger.sqlite' \
  --exclude='data/dynamic-data-pack-cache' \
  --exclude='data/kuma' \
  data/" || TAR_STATUS=$?
# The DH/poi/ledger excludes above are paths INSIDE data/world/ (confirmed
# against a live server, 2026-08-15) — data/DistantHorizons.sqlite and
# data/poi at the top level of data/ don't exist, so a tar --exclude naming
# them silently excludes nothing and the archive used to carry a multi-GB
# DistantHorizons.sqlite it never needed to.
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
# data/world/ alone accounts for the overworld, DIM-1/DIM1 (nether/end),
# dimensions/<ns>/<slug>/ (every custom dimension incl. paradise_lost),
# playerdata/stats/advancements, modplayerdata, essentialcommands,
# ec_player_profiles, and data/world/data/ (openpartiesandclaims claims,
# DistantHorizons.sqlite, poi, ledger.sqlite, level.dat) — confirmed against
# a live server's directory layout, 2026-08-15. Everything else here is a
# genuinely separate top-level path.
ssh -i "$SSH_KEY" "$REMOTE" "cd ${REMOTE_DIR}; \
  sudo rm -rf data/world/; \
  sudo rm -rf data/unmined-web/maps/ data/unmined-web/index.html data/unmined-web/manifest.json; \
  sudo rm -f  data/.chunky-complete data/.chunky-nether-complete data/.chunky-end-complete data/.chunky-paradise-lost-complete; \
  sudo rm -f  data/.skip-pause; \
  sudo rm -rf data/config/chunky/tasks/; \
  sudo rm -f  data/config/portal_links.json data/config/custom-dimensions-fingerprints.json; \
  sudo rm -rf data/dynamic-data-pack-cache/; \
  sudo rm -rf data/.dimension-setup/"

# Verify rather than trust: a partial delete leaves the old world in place and
# the new seed would silently generate nothing.
LEFTOVERS="$(ssh -i "$SSH_KEY" "$REMOTE" "cd ${REMOTE_DIR} && ls -d data/world data/unmined-web/maps data/unmined-web/manifest.json data/config/chunky/tasks data/dynamic-data-pack-cache data/.dimension-setup 2>/dev/null" || true)"
if [[ -n "$LEFTOVERS" ]]; then
  echo "ERROR: these paths survived deletion:"
  echo "$LEFTOVERS" | sed 's/^/         /'
  echo "       Remove them by hand, then re-run deploy.sh to bring the stack up:"
  echo "       ssh -i $SSH_KEY ${REMOTE} 'cd ${REMOTE_DIR}/.stack/current/stack && bash scripts/deploy.sh --pull --non-interactive'"
  exit 1
fi

echo "  Deleted: data/world/ (every dimension's terrain, player data, DH cache, POI, ledger, level.dat)"
echo "  Deleted: uNmINeD map renders and manifest (regenerated on the next render pass)"
echo "  Deleted: Chunky markers, task state, .skip-pause"
echo "  Deleted: dynamic-data-pack-cache"
echo "  Deleted: per-dimension deploy-setup markers (deploy.sh will re-run 'dimension load' for all of them)"
echo "  Deleted: portal links and dimension fingerprints (rewritten on next boot)"

# =============================================================================
# 5b. Wipe Discord channels — the old world's chat and notifications
# =============================================================================
echo ""
echo "==> Wiping old-world bot/webhook messages from Discord..."
WIPE_CHANNELS="${DISCORD_CHANNEL_ID:-}"
if [[ -n "${DISCORD_CHAT_CHANNEL_ID:-}" && "${DISCORD_CHAT_CHANNEL_ID}" != "${DISCORD_CHANNEL_ID:-}" ]]; then
  WIPE_CHANNELS="${WIPE_CHANNELS} ${DISCORD_CHAT_CHANNEL_ID}"
fi
if [[ -z "${WIPE_CHANNELS// /}" || -z "${DISCORD_BOT_TOKEN:-}" ]]; then
  echo "  Skipped: DISCORD_CHANNEL_ID / DISCORD_BOT_TOKEN not configured."
else
  # Fire and forget — a Discord outage or rate limit never blocks a reset.
  for channel in $WIPE_CHANNELS; do
    bash "$SCRIPT_DIR/discord-cleanup.sh" "$channel" || \
      echo "  WARNING: cleanup of channel ${channel} failed — continuing."
  done
fi

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
