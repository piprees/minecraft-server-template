#!/usr/bin/env bash
# github-env-sync.sh - Wire GitHub Actions up for auto-deploy.
#
# Creates the GitHub 'production' environment (idempotent) and pushes every
# secret and variable the workflows need, sourced from .env. Without this,
# deploy.yml is silently SKIPPED (its job gate is `if: vars.DROPLET_HOST != ''`)
# and health.yml / discord-pin-sync.yml can't authenticate.
#
# This is the ONLY place GitHub gets wired: setup.sh and prepare-droplet.sh
# both delegate here. Keep REQUIRED_SECRETS / OPTIONAL_SECRETS / *_VARS in
# sync with `secrets.*` / `vars.*` usage in .github/workflows/*.yml.
#
# DEPLOY_SSH_KEY is read from the private key file (DEPLOY_KEY_PATH,
# default ~/.ssh/mc_deploy_key), not from .env.
#
# Requires: gh (authenticated), a GitHub remote on this repo, .env.
#
# Usage:
#   ./scripts/github-env-sync.sh                  # create env + push everything
#   ./scripts/github-env-sync.sh --check          # read-only report, no writes
#   ./scripts/github-env-sync.sh --allow-missing  # exit 0 even if required values are absent
#   ./scripts/github-env-sync.sh --env-name staging
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="${CONSUMER_DIR:-$(cd "$SCRIPT_DIR/.." && pwd)}"
cd "$PROJECT_DIR"

# --- keep these lists in sync with .github/workflows/*.yml -------------------
REQUIRED_SECRETS=(
  RCON_PASSWORD
  R2_ACCOUNT_ID R2_BUCKET R2_ACCESS_KEY_ID R2_SECRET_ACCESS_KEY
  RESTIC_PASSWORD
  DISCORD_BOT_TOKEN DISCORD_CHANNEL_ID DISCORD_GUILD_ID DISCORD_WEBHOOK_URL
  KUMA_PASSWORD
  CLOUDFLARE_API_TOKEN CLOUDFLARE_ACCOUNT_ID CLOUDFLARE_ZONE_ID
)
# Generated later in setup (kuma-token.sh / cloudflare-setup.sh) - synced when present
OPTIONAL_SECRETS=(
  KUMA_API_KEY DISCORD_CHAT_CHANNEL_ID
  CLOUDFLARE_TUNNEL_ID
)
REQUIRED_VARS=(
  DROPLET_HOST
  DEPLOY_USER
)
OPTIONAL_VARS=(
  SERVER_DIR
  BRAND_NAME
  BRAND_SLUG
  MOTD
  DOMAIN
  MC_VERSION
  SEED
  SERVER_PORT
  MEMORY
  VIEW_DISTANCE
  SIMULATION_DISTANCE
  SPAWN_X
  SPAWN_Y
  SPAWN_Z
  PREGEN_BORDER_RADIUS
  BACKUP_INTERVAL
  CLOUD_PROVIDER
  IMAGE_REGISTRY
  DISCORD_INVITE_URL
  BRAND_ICON_URL
  DISCORD_ADMIN_ROLE_ID
  DISCORD_PLAYER_ROLE_ID
  DISCORD_WELCOME_CHANNEL_ID
  DISCORD_WELCOME_MESSAGE_ID
  STACK_VERSION
)

# --- flags ---------------------------------------------------------------------
CHECK_ONLY=0
ALLOW_MISSING=0
ENV_NAME="production"
prev=""
for arg in "$@"; do
  case "$arg" in
    --check) CHECK_ONLY=1 ;;
    --allow-missing) ALLOW_MISSING=1 ;;
    --env-name=*) ENV_NAME="${arg#*=}" ;;
  esac
  if [[ "$prev" == "--env-name" ]]; then
    ENV_NAME="$arg"
  fi
  prev="$arg"
done

# --- load config (--check only reads GitHub, so .env is optional there) ----------
if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
elif [[ $CHECK_ONLY -eq 0 ]]; then
  echo "✗ .env not found - run ./scripts/setup.sh first."
  exit 1
fi

DEPLOY_USER="${DEPLOY_USER:-deploy}"
DEPLOY_KEY="${DEPLOY_KEY_PATH:-$HOME/.ssh/${BRAND_SLUG:+${BRAND_SLUG}_}mc_deploy_key}"

# --- gh preconditions ------------------------------------------------------------
if ! command -v gh &> /dev/null; then
  echo "✗ gh (GitHub CLI) is required. Install: brew install gh"
  exit 1
fi
if ! gh auth status &> /dev/null; then
  echo "✗ gh is not authenticated. Run: gh auth login"
  exit 1
fi

# --- resolve GitHub repo (same logic as prepare-droplet.sh) -----------------------
REPO_SLUG=$(gh repo view --json nameWithOwner -q .nameWithOwner 2> /dev/null || true)
if [[ -z "$REPO_SLUG" ]]; then
  REMOTE_URL=$(git remote get-url origin 2> /dev/null || true)
  REPO_SLUG=$(echo "$REMOTE_URL" | sed -E 's#.*github\.com[:/](.+)(\.git)?$#\1#' | sed 's/\.git$//')
fi
if [[ -z "$REPO_SLUG" ]]; then
  echo "✗ Could not determine GitHub repo. Set a git remote first."
  exit 1
fi

echo "==> GitHub environment sync"
echo "    Repo:        ${REPO_SLUG}"
echo "    Environment: ${ENV_NAME}"
[[ $CHECK_ONLY -eq 1 ]] && echo "    Mode:        check (read-only)"
echo ""

MISSING_REQUIRED=()

# =============================================================================
# Check mode: compare what GitHub has against what the workflows need
# =============================================================================
if [[ $CHECK_ONLY -eq 1 ]]; then
  if ! gh api "repos/${REPO_SLUG}/environments/${ENV_NAME}" --silent 2> /dev/null; then
    echo "✗ Environment '${ENV_NAME}' does not exist on GitHub."
    echo "  Deploys will be SKIPPED until you run: ./scripts/github-env-sync.sh"
    exit 1
  fi
  echo "✓ Environment '${ENV_NAME}' exists"

  GH_SECRETS=$(gh secret list --repo "$REPO_SLUG" --env "$ENV_NAME" --json name -q '.[].name' 2> /dev/null || true)
  GH_VARS=$(gh variable list --repo "$REPO_SLUG" --env "$ENV_NAME" --json name -q '.[].name' 2> /dev/null || true)
  # Repo-scoped vars: required vars must also exist here (the deploy job's
  # gate can't see environment-scoped variables — see push_var below).
  GH_REPO_VARS=$(gh variable list --repo "$REPO_SLUG" --json name -q '.[].name' 2> /dev/null || true)

  for key in DEPLOY_SSH_KEY "${REQUIRED_SECRETS[@]}"; do
    if grep -qx "$key" <<< "$GH_SECRETS"; then
      echo "  ✓ secret ${key}"
    else
      echo "  ✗ secret ${key} MISSING"
      MISSING_REQUIRED+=("$key")
    fi
  done
  for key in "${OPTIONAL_SECRETS[@]}"; do
    grep -qx "$key" <<< "$GH_SECRETS" && echo "  ✓ secret ${key}" || echo "  ! secret ${key} not set (optional)"
  done
  for key in "${REQUIRED_VARS[@]}"; do
    if grep -qx "$key" <<< "$GH_VARS" && grep -qx "$key" <<< "$GH_REPO_VARS"; then
      echo "  ✓ var    ${key}"
    elif grep -qx "$key" <<< "$GH_VARS"; then
      echo "  ✗ var    ${key} env-scoped only - deploy gate can't see it (re-run sync)"
      MISSING_REQUIRED+=("$key")
    else
      echo "  ✗ var    ${key} MISSING"
      MISSING_REQUIRED+=("$key")
    fi
  done
  for key in "${OPTIONAL_VARS[@]}"; do
    grep -qx "$key" <<< "$GH_VARS" && echo "  ✓ var    ${key}" || echo "  ! var    ${key} not set (optional)"
  done

  echo ""
  if [[ ${#MISSING_REQUIRED[@]} -gt 0 ]]; then
    echo "✗ Missing required: ${MISSING_REQUIRED[*]}"
    echo "  Fix with: ./scripts/github-env-sync.sh"
    exit 1
  fi
  echo "✓ GitHub environment fully configured."
  exit 0
fi

# =============================================================================
# Sync mode
# =============================================================================

# --- 1. create the environment (idempotent PUT) -----------------------------------
if gh api "repos/${REPO_SLUG}/environments/${ENV_NAME}" --silent 2> /dev/null; then
  echo "  ✓ Environment '${ENV_NAME}' exists"
else
  gh api -X PUT "repos/${REPO_SLUG}/environments/${ENV_NAME}" --silent
  echo "  ✓ Environment '${ENV_NAME}' created"
fi

# --- 2. secrets --------------------------------------------------------------------
push_secret() {
  local key="$1" value="$2" required="$3"
  if [[ -z "$value" ]]; then
    if [[ "$required" == "required" ]]; then
      echo "  ✗ secret ${key} - empty in .env"
      MISSING_REQUIRED+=("$key")
    else
      echo "  ! secret ${key} - empty, skipped (optional)"
    fi
    return 0
  fi
  if gh secret set "$key" --repo "$REPO_SLUG" --env "$ENV_NAME" --body "$value" 2> /dev/null; then
    echo "  ✓ secret ${key}"
  else
    echo "  ✗ secret ${key} - push failed"
    MISSING_REQUIRED+=("$key")
  fi
}

# DEPLOY_SSH_KEY comes from the key file, not .env
if [[ -f "$DEPLOY_KEY" ]]; then
  push_secret DEPLOY_SSH_KEY "$(cat "$DEPLOY_KEY")" required
else
  echo "  ✗ secret DEPLOY_SSH_KEY - key file not found at ${DEPLOY_KEY}"
  echo "    Generate: ssh-keygen -t ed25519 -f ${DEPLOY_KEY} -C github-actions-deploy -N ''"
  MISSING_REQUIRED+=(DEPLOY_SSH_KEY)
fi

for key in "${REQUIRED_SECRETS[@]}"; do
  push_secret "$key" "${!key:-}" required
done
for key in "${OPTIONAL_SECRETS[@]}"; do
  push_secret "$key" "${!key:-}" optional
done

# --- 3. variables ------------------------------------------------------------------
push_var() {
  local key="$1" value="$2" required="$3"
  if [[ -z "$value" ]]; then
    if [[ "$required" == "required" ]]; then
      echo "  ✗ var    ${key} - empty in .env"
      MISSING_REQUIRED+=("$key")
    else
      echo "  ! var    ${key} - empty, skipped (optional)"
    fi
    return 0
  fi
  if gh variable set "$key" --repo "$REPO_SLUG" --env "$ENV_NAME" --body "$value" 2> /dev/null; then
    echo "  ✓ var    ${key} = ${value}"
  else
    echo "  ✗ var    ${key} - push failed"
    MISSING_REQUIRED+=("$key")
  fi
  # Required vars also go to REPO scope: the deploy job's gate
  # (`if: vars.DROPLET_HOST != ''`) is evaluated BEFORE the environment
  # attaches, so environment-scoped variables are invisible to it and the
  # job silently skips. Env-scoped values still override repo-scoped ones
  # in steps, so multi-environment setups keep working.
  if [[ "$required" == "required" ]]; then
    gh variable set "$key" --repo "$REPO_SLUG" --body "$value" 2> /dev/null \
      || echo "  ✗ var    ${key} - repo-scope push failed"
  fi
}

for key in "${REQUIRED_VARS[@]}"; do
  push_var "$key" "${!key:-}" required
done
for key in "${OPTIONAL_VARS[@]}"; do
  push_var "$key" "${!key:-}" optional
done

# --- summary ------------------------------------------------------------------------
echo ""
if [[ ${#MISSING_REQUIRED[@]} -gt 0 ]]; then
  echo "⚠ Synced with gaps. Missing required: ${MISSING_REQUIRED[*]}"
  echo "  Set them in .env (or generate the deploy key), then re-run:"
  echo "  ./scripts/github-env-sync.sh"
  [[ $ALLOW_MISSING -eq 1 ]] && exit 0
  exit 1
fi
echo "✓ GitHub '${ENV_NAME}' environment fully synced. Pushes to main will deploy."
