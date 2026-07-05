#!/usr/bin/env bash
# prepare-droplet.sh - Set up a hardened droplet for its first deploy.
#
# Run this AFTER harden.sh and BEFORE the first deploy. It bridges the
# gap by configuring everything the deploy workflow needs:
#
#   1. Registers the deploy key with GitHub (so CI can SSH to the droplet)
#   2. Copies the private key to the droplet
#   3. Creates the server directory skeleton (no git clone)
#   4. Uploads stack-pull.sh and installs the initial stack bundle
#   5. Creates a production .env
#   6. Sets GitHub Actions environment variables (DROPLET_HOST, DEPLOY_USER)
#
# Idempotent: safe to run multiple times. Skips steps already completed.
#
# Requires: gh (GitHub CLI), ssh access to the deploy user
#
# Usage:
#   ./scripts/prepare-droplet.sh
#   DROPLET_HOST=1.2.3.4 ./scripts/prepare-droplet.sh   # override IP
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="${CONSUMER_DIR:-$(cd "$SCRIPT_DIR/.." && pwd)}"
cd "$PROJECT_DIR"

# --- load config --------------------------------------------------------------
if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi

DEPLOY_USER="${DEPLOY_USER:-deploy}"
DEPLOY_KEY="${DEPLOY_KEY_PATH:-$HOME/.ssh/mc_deploy_key}"
BRAND_SLUG="${BRAND_SLUG:-adventure}"
STACK_VERSION="${STACK_VERSION:-v1}"
SERVER_DIR="server"

# --- resolve droplet IP -------------------------------------------------------
if [[ -z "${DROPLET_HOST:-}" ]]; then
  if command -v doctl &> /dev/null; then
    DROPLET_HOST=$(doctl compute droplet list \
      --format Name,PublicIPv4 --no-header \
      | grep -E "^mc-${BRAND_SLUG}\s" | awk '{print $2}' || true)
  fi
  if [[ -z "${DROPLET_HOST:-}" ]] && command -v hcloud &> /dev/null; then
    DROPLET_HOST=$(hcloud server list -o columns=name,ipv4 -o noheader \
      | grep -E "^mc-${BRAND_SLUG}\s" | awk '{print $2}' || true)
  fi
fi

if [[ -z "${DROPLET_HOST:-}" ]]; then
  echo "Could not determine droplet IP."
  echo "Set DROPLET_HOST in .env or pass it: DROPLET_HOST=1.2.3.4 $0"
  exit 1
fi

echo "==> Preparing droplet at ${DROPLET_HOST} for first deploy"
echo "    Deploy user: ${DEPLOY_USER}"
echo "    Server dir:  ~/${SERVER_DIR}"
echo ""

# --- resolve GitHub repo ------------------------------------------------------
REPO_SLUG=$(gh repo view --json nameWithOwner -q .nameWithOwner 2> /dev/null || true)
if [[ -z "$REPO_SLUG" ]]; then
  REMOTE_URL=$(git remote get-url origin 2> /dev/null || true)
  REPO_SLUG=$(echo "$REMOTE_URL" | sed -E 's#.*github\.com[:/](.+)(\.git)?$#\1#' | sed 's/\.git$//')
fi

if [[ -z "$REPO_SLUG" ]]; then
  echo "Could not determine GitHub repo. Set a git remote first."
  exit 1
fi
echo "    GitHub repo: ${REPO_SLUG}"
echo ""

SSH_CMD="ssh -o StrictHostKeyChecking=no ${DEPLOY_USER}@${DROPLET_HOST}"

# --- helper -------------------------------------------------------------------
step_ok() { echo "  + $1"; }
step_skip() { echo "  + $1 (already done)"; }

# =============================================================================
# 1. Register deploy key with GitHub (for CI SSH to the droplet)
# =============================================================================
echo "=== 1. GitHub deploy key ==="

if [[ ! -f "${DEPLOY_KEY}.pub" ]]; then
  echo "  Deploy key not found at ${DEPLOY_KEY}.pub"
  echo "  Generate one: ssh-keygen -t ed25519 -f ${DEPLOY_KEY} -C github-actions-deploy -N ''"
  exit 1
fi

EXISTING_KEYS=$(gh repo deploy-key list --repo "$REPO_SLUG" 2> /dev/null || true)
if echo "$EXISTING_KEYS" | grep -q "droplet-deploy"; then
  step_skip "Deploy key registered with GitHub"
else
  gh repo deploy-key add "${DEPLOY_KEY}.pub" --repo "$REPO_SLUG" --title "droplet-deploy" 2> /dev/null \
    && step_ok "Deploy key added to GitHub repo" \
    || step_skip "Deploy key already registered with GitHub"
fi

# =============================================================================
# 2. Copy deploy private key to droplet (for CI SSH access)
# =============================================================================
echo ""
echo "=== 2. Deploy key on droplet ==="

HAS_KEY=$($SSH_CMD 'test -f ~/.ssh/github_deploy_key && echo yes || echo no')
if [[ "$HAS_KEY" == "yes" ]]; then
  step_skip "Private key on droplet"
else
  scp -o StrictHostKeyChecking=no "${DEPLOY_KEY}" "${DEPLOY_USER}@${DROPLET_HOST}:~/.ssh/github_deploy_key"
  $SSH_CMD 'chmod 600 ~/.ssh/github_deploy_key'
  step_ok "Private key copied to droplet"
fi

# =============================================================================
# 3. Create server directory skeleton (no git clone)
# =============================================================================
echo ""
echo "=== 3. Server directory ==="

# shellcheck disable=SC2088
HAS_DIR=$($SSH_CMD "test -d ~/${SERVER_DIR}/data && echo yes || echo no")
if [[ "$HAS_DIR" == "yes" ]]; then
  step_skip "Server directory exists at ~/${SERVER_DIR}"
else
  $SSH_CMD "mkdir -p ~/${SERVER_DIR}/{overlay,cloudflared,modpack-dist,data,.stack}"
  step_ok "Server directory skeleton created at ~/${SERVER_DIR}"
fi

# =============================================================================
# 4. Install stack-pull.sh and initial stack bundle
# =============================================================================
echo ""
echo "=== 4. Stack bundle ==="

# Upload stack-pull.sh (the one script consumers vendor)
STACK_PULL_SRC=""
if [[ -f "$PROJECT_DIR/stack-pull.sh" ]]; then
  STACK_PULL_SRC="$PROJECT_DIR/stack-pull.sh"
elif [[ -f "$PROJECT_DIR/scripts/stack-pull.sh" ]]; then
  STACK_PULL_SRC="$PROJECT_DIR/scripts/stack-pull.sh"
fi

if [[ -n "$STACK_PULL_SRC" ]]; then
  scp -o StrictHostKeyChecking=no "$STACK_PULL_SRC" \
    "${DEPLOY_USER}@${DROPLET_HOST}:~/${SERVER_DIR}/stack-pull.sh"
  $SSH_CMD "chmod +x ~/${SERVER_DIR}/stack-pull.sh"
  step_ok "stack-pull.sh uploaded"
else
  echo "  WARNING: stack-pull.sh not found locally, skipping upload"
fi

# Pull the initial stack bundle
HAS_STACK=$($SSH_CMD "test -L ~/${SERVER_DIR}/.stack/current && echo yes || echo no")
if [[ "$HAS_STACK" == "yes" ]]; then
  step_skip "Stack bundle already installed"
else
  $SSH_CMD "cd ~/${SERVER_DIR} && STACK_VERSION=${STACK_VERSION} ./stack-pull.sh" \
    && step_ok "Stack bundle ${STACK_VERSION} installed" \
    || echo "  WARNING: stack-pull.sh failed (may need GitHub API access or manual install)"
fi

# =============================================================================
# 5. Create production .env
# =============================================================================
echo ""
echo "=== 5. Production .env ==="

# Build .env from local .env with ONLINE_MODE=TRUE for production
sed -e "s/^ONLINE_MODE=.*/ONLINE_MODE=TRUE/" \
  -e '/^DEPLOY_KEY_PUB=/d' \
  .env > /tmp/mc-prod-env.$$
scp -o StrictHostKeyChecking=no "/tmp/mc-prod-env.$$" "${DEPLOY_USER}@${DROPLET_HOST}:~/${SERVER_DIR}/.env"
rm -f "/tmp/mc-prod-env.$$"
step_ok "Production .env deployed (ONLINE_MODE=TRUE)"

# =============================================================================
# 6. GitHub environment: secrets + variables (delegates to github-env-sync.sh)
# =============================================================================
echo ""
echo "=== 6. GitHub environment (secrets + variables) ==="

if DROPLET_HOST="$DROPLET_HOST" DEPLOY_USER="$DEPLOY_USER" \
  "$SCRIPT_DIR/github-env-sync.sh"; then
  step_ok "GitHub 'production' environment synced"
else
  echo "  WARNING: GitHub environment sync incomplete. Deploys are SKIPPED until this passes."
  echo "    Fix the missing values in .env, then re-run: ./scripts/github-env-sync.sh"
fi

# =============================================================================
# Done
# =============================================================================
echo ""
echo "=================================================================="
echo " Droplet prepared for deployment."
echo ""
echo " First deploy (run on the droplet):"
echo "   ssh ${DEPLOY_USER}@${DROPLET_HOST}"
echo "   cd ~/${SERVER_DIR} && .stack/current/stack/scripts/initial-setup.sh"
echo ""
echo " Or trigger via GitHub Actions by pushing to main."
echo "=================================================================="
