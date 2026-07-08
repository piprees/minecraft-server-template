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
DEPLOY_KEY="${DEPLOY_KEY_PATH:-$HOME/.ssh/${BRAND_SLUG:+${BRAND_SLUG}_}mc_deploy_key}"
BRAND_SLUG="${BRAND_SLUG:-adventure}"
STACK_VERSION="${STACK_VERSION:-latest}"
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
  # A degit'd consumer has no git repo at all - CI/CD needs one. Offer to
  # create it (private) and push, so the whole flow works from a bare folder.
  echo "No GitHub repo configured yet - the deploy workflow needs one."
  if command -v gh > /dev/null 2>&1 && gh auth status > /dev/null 2>&1 && [[ "${NON_INTERACTIVE:-0}" != "1" ]]; then
    GH_USER=$(gh api user -q .login 2> /dev/null || true)
    DEFAULT_SLUG="${GH_USER}/${BRAND_SLUG}"
    read -rp "  Create private repo ${DEFAULT_SLUG} and push this folder? [Y/n]: " answer
    if [[ "${answer:-Y}" =~ ^[Yy] ]]; then
      git rev-parse --is-inside-work-tree > /dev/null 2>&1 || git init -b main
      git add -A
      git rev-parse HEAD > /dev/null 2>&1 || git commit -m "feat: initial server config"
      gh repo create "$DEFAULT_SLUG" --private --source . --remote origin --push
      REPO_SLUG="$DEFAULT_SLUG"
    fi
  fi
  if [[ -z "$REPO_SLUG" ]]; then
    echo "  Create one, then re-run:"
    echo "    git init -b main && git add -A && git commit -m 'feat: initial server config'"
    echo "    gh repo create <you>/${BRAND_SLUG} --private --source . --remote origin --push"
    exit 1
  fi
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
# 4b. Pre-seed server mods from the local machine (optional)
# =============================================================================
# The local test already downloaded every mod JAR. Shipping them to the
# server skips the ~150-download Modrinth burst on first deploy - the main
# source of 429 rate limits and slow, restart-prone first boots.
echo ""
echo "=== 4b. Pre-seed server mods ==="

HAS_SERVER_MODS=$($SSH_CMD "ls ~/${SERVER_DIR}/data/mods/*.jar > /dev/null 2>&1 && echo yes || echo no")
if [[ "$HAS_SERVER_MODS" == "yes" ]]; then
  step_skip "Server already has mods"
elif ls "$PROJECT_DIR/data/mods/"*.jar > /dev/null 2>&1; then
  MOD_COUNT=$(find "$PROJECT_DIR/data/mods" -name '*.jar' | wc -l | xargs)
  UPLOAD_MODS="Y"
  if [[ "${NON_INTERACTIVE:-0}" != "1" ]]; then
    read -rp "  Upload ${MOD_COUNT} locally-synced mod JARs to the server (skips Modrinth on first deploy)? [Y/n]: " answer
    UPLOAD_MODS="${answer:-Y}"
  fi
  if [[ "$UPLOAD_MODS" =~ ^[Yy] ]]; then
    $SSH_CMD "mkdir -p ~/${SERVER_DIR}/data/mods"
    rsync -az --ignore-existing -e "ssh -o StrictHostKeyChecking=no" \
      "$PROJECT_DIR/data/mods/" "${DEPLOY_USER}@${DROPLET_HOST}:~/${SERVER_DIR}/data/mods/"
    # Pre-seed the mod hash EXACTLY as deploy.sh computes it, so the first
    # deploy sees the mod list as unchanged and skips the Modrinth sync.
    SERVER_STACK_VER=$($SSH_CMD "readlink ~/${SERVER_DIR}/.stack/current 2> /dev/null" || echo "unknown")
    MOD_INPUTS="${SERVER_STACK_VER}"
    [[ -f "$PROJECT_DIR/overlay/mods-extra.txt" ]] && MOD_INPUTS+=$(cat "$PROJECT_DIR/overlay/mods-extra.txt")
    [[ -f "$PROJECT_DIR/overlay/mods-remove.txt" ]] && MOD_INPUTS+=$(cat "$PROJECT_DIR/overlay/mods-remove.txt")
    MOD_HASH=$(echo "$MOD_INPUTS" | shasum -a 256 | cut -d' ' -f1)
    $SSH_CMD "echo '${MOD_HASH}' > ~/${SERVER_DIR}/data/.modrinth-hash"
    step_ok "${MOD_COUNT} mod JARs uploaded, Modrinth sync pre-seeded"
  else
    echo "  Skipped - first deploy will download mods from Modrinth."
  fi
else
  echo "  No local mods found (run ./dev up first to enable this shortcut)."
fi

# =============================================================================
# 5. Create production .env
# =============================================================================
echo ""
echo "=== 5. Production .env ==="

# Build .env from local .env with ONLINE_MODE=TRUE for production
sed -e "s/^ONLINE_MODE=.*/ONLINE_MODE='TRUE'/" \
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
