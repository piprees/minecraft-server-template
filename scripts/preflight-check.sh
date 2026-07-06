#!/usr/bin/env bash
# preflight-check.sh - verify every prerequisite is present.
# Run this before any other script. It checks .env values, installed tools,
# and reachable services, reporting a clear pass/fail for each item.
#
# Target-aware: skips cloud-only checks for local deployments.
#
# Usage:
#   ./scripts/preflight-check.sh                    # auto-detect from CLOUD_PROVIDER
#   ./scripts/preflight-check.sh --target local
#   ./scripts/preflight-check.sh --target hetzner
#   ./scripts/preflight-check.sh --target digitalocean
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="${CONSUMER_DIR:-$(cd "$SCRIPT_DIR/.." && pwd)}"
ENV_FILE="$PROJECT_DIR/.env"

# --- colours -----------------------------------------------------------------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
BOLD='\033[1m'
RESET='\033[0m'

PASS=0
FAIL=0
WARN=0

pass() {
  echo -e "  ${GREEN}✓${RESET} $1"
  PASS=$((PASS + 1))
}
fail() {
  echo -e "  ${RED}✗${RESET} $1"
  FAIL=$((FAIL + 1))
}
warn() {
  echo -e "  ${YELLOW}!${RESET} $1"
  WARN=$((WARN + 1))
}

# NOTE: check functions always return 0 - failures are tallied in FAIL and
# decide the final exit code. Returning non-zero here would abort the whole
# run at the first failure under `set -e`, hiding every later check.
check_env() {
  local var_name="$1"
  local description="$2"
  local help_url="${3:-}"
  local value="${!var_name:-}"

  if [[ -z "$value" || "$value" == *"xxxx"* || "$value" == *"REPLACE"* || "$value" == "change-me"* ]]; then
    if [[ -n "$help_url" ]]; then
      fail "$description - set ${BOLD}${var_name}${RESET} in .env (see: $help_url)"
    else
      fail "$description - set ${BOLD}${var_name}${RESET} in .env"
    fi
  else
    pass "$description (${var_name} is set)"
  fi
  return 0
}

# For variables with platform defaults baked into docker-compose.yml -
# empty is fine, it just means "use the default".
check_env_default() {
  local var_name="$1"
  local description="$2"
  local default_value="$3"
  local value="${!var_name:-}"

  if [[ -z "$value" ]]; then
    pass "$description (platform default: ${default_value})"
  else
    pass "$description (${var_name}=${value})"
  fi
  return 0
}

check_tool() {
  local tool="$1"
  local description="$2"
  local install_hint="${3:-}"

  if command -v "$tool" &> /dev/null; then
    pass "$description ($tool found)"
  else
    fail "$description - install ${BOLD}${tool}${RESET}${install_hint:+ ($install_hint)}"
  fi
  return 0
}

# Cross-platform install hint for a tool
install_hint() {
  local tool="$1"
  case "$tool" in
    docker)
      echo "https://docs.docker.com/desktop/"
      ;;
    gh)
      echo "macOS: brew install gh | Debian/Ubuntu: apt install gh | https://cli.github.com/"
      ;;
    python3)
      echo "macOS: brew install python3 | Debian/Ubuntu: apt install python3 | https://www.python.org/downloads/"
      ;;
    jq)
      echo "macOS: brew install jq | Debian/Ubuntu: apt install jq | https://jqlang.org/"
      ;;
    curl)
      echo "macOS: brew install curl | Debian/Ubuntu: apt install curl"
      ;;
    hcloud)
      echo "macOS: brew install hcloud | Debian/Ubuntu: apt install hcloud-cli | https://github.com/hetznercloud/cli"
      ;;
    doctl)
      echo "macOS: brew install doctl | Debian/Ubuntu: snap install doctl | https://docs.digitalocean.com/reference/doctl/how-to/install/"
      ;;
    cloudflared)
      echo "macOS: brew install cloudflared | https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/"
      ;;
    restic)
      echo "macOS: brew install restic | Debian/Ubuntu: apt install restic | https://restic.net/"
      ;;
    op)
      echo "macOS: brew install 1password-cli | https://developer.1password.com/docs/cli/get-started/"
      ;;
    *)
      echo "https://command-not-found.com/$tool"
      ;;
  esac
}

# --- parse flags --------------------------------------------------------------
TARGET=""
for arg in "$@"; do
  case "$arg" in
    --target=*) TARGET="${arg#*=}" ;;
  esac
done
prev=""
for arg in "$@"; do
  if [[ "$prev" == "--target" ]]; then
    TARGET="$arg"
  fi
  prev="$arg"
done

# =============================================================================
echo -e "\n${BOLD}Minecraft Adventure Server - Preflight Check${RESET}"
echo "=============================================="

# --- .env file ---------------------------------------------------------------
echo -e "\n${BOLD}1. Environment file${RESET}"

if [[ -f "$ENV_FILE" ]]; then
  pass ".env file exists"
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
else
  fail ".env file not found - copy .env.example to .env and fill it in"
  echo -e "\n${RED}Cannot continue without .env. Exiting.${RESET}"
  exit 1
fi

# .env holds secrets and must NEVER be committed. A plain `git add .` respects
# .gitignore, but `git add -f` (or an overeager tool) can still track it - and
# once tracked, the ignore rule is powerless. Catch both failure modes.
if command -v git &> /dev/null && git -C "$PROJECT_DIR" rev-parse --is-inside-work-tree &> /dev/null; then
  if git -C "$PROJECT_DIR" ls-files --error-unmatch .env &> /dev/null; then
    fail ".env is TRACKED BY GIT - your secrets are in the repo. Fix now: git rm --cached .env && git commit; then ROTATE every secret already pushed"
  elif git -C "$PROJECT_DIR" check-ignore -q .env; then
    pass ".env is git-ignored and untracked"
  else
    fail ".env is NOT ignored - add a '.env' line to .gitignore before committing anything"
  fi
fi

# --- detect target if not specified ------------------------------------------
if [[ -z "$TARGET" ]]; then
  TARGET="${CLOUD_PROVIDER:-local}"
fi

IS_CLOUD=0
[[ "$TARGET" != "local" ]] && IS_CLOUD=1

echo -e "\n${BOLD}Target: ${TARGET}${RESET}"

# --- Minecraft settings ------------------------------------------------------
echo -e "\n${BOLD}2. Minecraft server settings${RESET}"

check_env_default MC_VERSION "Minecraft version" "1.21.1"
check_env_default MEMORY "JVM memory allocation" "5G"
check_env_default SERVER_PORT "Game port" "25577"

if [[ -z "${OPS:-}" && -z "${DISCORD_ADMIN_ROLE_ID:-}" ]]; then
  warn "No OPS and no DISCORD_ADMIN_ROLE_ID - nobody will have admin. Set one of them."
else
  pass "Admin access configured (OPS and/or Discord admin role)"
fi
if [[ -z "${WHITELIST:-}" && -z "${DISCORD_PLAYER_ROLE_ID:-}" ]]; then
  warn "No WHITELIST and no DISCORD_PLAYER_ROLE_ID - nobody can join. Set one of them."
else
  pass "Player access configured (WHITELIST and/or Discord player role)"
fi

if [[ -z "${RCON_PASSWORD:-}" ]]; then
  warn "RCON_PASSWORD is empty - will be auto-generated on first run"
fi

if [[ -z "${SEED:-}" ]]; then
  warn "SEED is empty - roll seeds first, or leave blank for random"
fi

# --- Cloud provider -----------------------------------------------------------
if [[ "$TARGET" == "hetzner" ]]; then
  echo -e "\n${BOLD}3. Hetzner Cloud${RESET}"
  check_env HCLOUD_TOKEN "Hetzner API token" "https://console.hetzner.cloud"
  check_tool hcloud "Hetzner CLI" "$(install_hint hcloud)"
elif [[ "$TARGET" == "digitalocean" ]]; then
  echo -e "\n${BOLD}3. DigitalOcean${RESET}"
  check_env DO_API_TOKEN "DO API token" "https://cloud.digitalocean.com/account/api/tokens"
  check_tool doctl "DigitalOcean CLI" "$(install_hint doctl)"

  if command -v doctl &> /dev/null && [[ -n "${DO_API_TOKEN:-}" ]]; then
    if doctl account get &> /dev/null 2>&1; then
      pass "doctl authenticated successfully"
    else
      warn "doctl installed but not authenticated - run: doctl auth init"
    fi
  fi
else
  echo -e "\n${BOLD}3. Cloud provider${RESET}"
  pass "Local target - no cloud provider needed"
fi

# --- Cloudflare ---------------------------------------------------------------
if [[ $IS_CLOUD -eq 1 ]]; then
  echo -e "\n${BOLD}4. Cloudflare${RESET}"
  check_env DOMAIN "Domain name"
  check_env CLOUDFLARE_API_TOKEN "Cloudflare API token" "https://dash.cloudflare.com/profile/api-tokens"
  check_env CLOUDFLARE_ACCOUNT_ID "Cloudflare Account ID"
  check_env CLOUDFLARE_ZONE_ID "Cloudflare Zone ID"
  check_tool cloudflared "Cloudflare Tunnel CLI" "$(install_hint cloudflared)"

  # Verify the token LIVE - the single most common failure is a value that
  # isn't the custom API Token (Global API Key, R2 "Token value", expired
  # token). Catch it here, not halfway through cloudflare-setup.sh.
  if [[ -n "${CLOUDFLARE_API_TOKEN:-}" && "${CLOUDFLARE_API_TOKEN}" != *"xxxx"* ]]; then
    TOKEN_STATUS=$(curl -s --connect-timeout 10 \
      "https://api.cloudflare.com/client/v4/user/tokens/verify" \
      -H "Authorization: Bearer ${CLOUDFLARE_API_TOKEN}" \
      | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['result']['status'] if d.get('success') else 'invalid')" 2>/dev/null || echo "unreachable")
    case "$TOKEN_STATUS" in
      active)
        pass "Cloudflare API token verified (active)"
        # Resolve/verify the zone while we hold a working token
        if [[ -n "${DOMAIN:-}" && "$DOMAIN" != "example.com" ]]; then
          FOUND_ZONE=$(curl -s --connect-timeout 10 \
            "https://api.cloudflare.com/client/v4/zones?name=${DOMAIN}" \
            -H "Authorization: Bearer ${CLOUDFLARE_API_TOKEN}" \
            | python3 -c "import sys,json; r=json.load(sys.stdin).get('result') or []; print(r[0]['id'] if r else '')" 2>/dev/null || true)
          if [[ -z "$FOUND_ZONE" ]]; then
            fail "Zone '${DOMAIN}' not visible to this token - add the zone to the token's Zone Resources (or the domain isn't on this Cloudflare account)"
          elif [[ -z "${CLOUDFLARE_ZONE_ID:-}" || "${CLOUDFLARE_ZONE_ID}" == *"xxxx"* ]]; then
            warn "CLOUDFLARE_ZONE_ID is empty - set it to ${FOUND_ZONE} (zone for ${DOMAIN})"
          elif [[ "$FOUND_ZONE" != "$CLOUDFLARE_ZONE_ID" ]]; then
            fail "CLOUDFLARE_ZONE_ID doesn't match the zone for ${DOMAIN} (expected ${FOUND_ZONE})"
          else
            pass "Zone ID matches ${DOMAIN}"
          fi
        fi
        ;;
      unreachable)
        warn "Could not reach the Cloudflare API to verify the token (offline?)"
        ;;
      *)
        fail "CLOUDFLARE_API_TOKEN is NOT a valid API token (verify returned: ${TOKEN_STATUS}). It must be a custom API Token - not the Global API Key, not the R2 page's 'Token value'. See docs/credentials.md"
        ;;
    esac
  fi

  # R2 keypair format - catches 'pasted the wrong value from the R2 page'
  if [[ -n "${R2_ACCESS_KEY_ID:-}" && ! "${R2_ACCESS_KEY_ID}" =~ ^[0-9a-fx]{32}$ ]]; then
    fail "R2_ACCESS_KEY_ID doesn't look like an Access Key ID (expect 32 hex chars from the R2 token page)"
  fi
  if [[ -n "${R2_SECRET_ACCESS_KEY:-}" && ! "${R2_SECRET_ACCESS_KEY}" =~ ^[0-9a-fx]{64}$ ]]; then
    fail "R2_SECRET_ACCESS_KEY doesn't look like a Secret Access Key (expect 64 hex chars from the R2 token page)"
  fi
else
  echo -e "\n${BOLD}4. Domain${RESET}"
  check_env DOMAIN "Domain name (used for local subdomain access)"
fi

# --- Discord ------------------------------------------------------------------
echo -e "\n${BOLD}5. Discord${RESET}"

check_env DISCORD_BOT_TOKEN "Discord bot token" "https://discord.com/developers/applications"
check_env DISCORD_CHANNEL_ID "Discord channel ID"
check_env DISCORD_WEBHOOK_URL "Discord webhook URL"

# --- Offsite backups (Cloudflare R2) ------------------------------------------
if [[ $IS_CLOUD -eq 1 ]]; then
  echo -e "\n${BOLD}6. Offsite backups (Cloudflare R2)${RESET}"
  check_env R2_ACCOUNT_ID "Cloudflare Account ID" "https://dash.cloudflare.com"
  check_env R2_BUCKET "R2 bucket name" "https://dash.cloudflare.com/?to=/:account/r2"
  check_env R2_ACCESS_KEY_ID "R2 access key ID" "https://dash.cloudflare.com/?to=/:account/r2/api-tokens"
  check_env R2_SECRET_ACCESS_KEY "R2 secret access key"
  check_env RESTIC_PASSWORD "Restic encryption passphrase"
else
  echo -e "\n${BOLD}6. Local backups${RESET}"
  pass "Local target - backups use MinIO (auto-configured)"
fi

# --- GitHub deploy key --------------------------------------------------------
if [[ $IS_CLOUD -eq 1 ]]; then
  echo -e "\n${BOLD}7. GitHub deploy key${RESET}"
  DEPLOY_KEY_PUB="${DEPLOY_KEY_PUB:-$HOME/.ssh/${BRAND_SLUG:+${BRAND_SLUG}_}mc_deploy_key.pub}"
  DEPLOY_KEY_PUB_EXPANDED="${DEPLOY_KEY_PUB/#\~/$HOME}"
  if [[ -f "$DEPLOY_KEY_PUB_EXPANDED" ]]; then
    pass "Deploy public key exists at $DEPLOY_KEY_PUB"
  else
    fail "Deploy public key not found at $DEPLOY_KEY_PUB - generate with: ssh-keygen -t ed25519 -f ~/.ssh/mc_deploy_key -C \"github-actions-deploy\" -N \"\""
  fi

  # Auto-deploy only works if the GitHub 'production' environment is wired up.
  # deploy.yml gates on vars.DROPLET_HOST - if it's absent, deploys are
  # silently skipped, not failed, so surface it here.
  if command -v gh &> /dev/null && gh auth status &> /dev/null; then
    REPO_SLUG=$(gh repo view --json nameWithOwner -q .nameWithOwner 2> /dev/null || true)
    if [[ -n "$REPO_SLUG" ]]; then
      if [[ -n "$(gh variable get DROPLET_HOST --repo "$REPO_SLUG" --env production 2> /dev/null || true)" ]]; then
        pass "GitHub 'production' environment wired (verify fully: ./scripts/github-env-sync.sh --check)"
      else
        warn "GitHub 'production' environment not wired - pushes to main will NOT deploy. Run: ./scripts/github-env-sync.sh"
      fi
    fi
  else
    warn "gh not available/authenticated - can't verify GitHub auto-deploy wiring"
  fi
else
  echo -e "\n${BOLD}7. Deploy key${RESET}"
  pass "Local target - no deploy key needed"
fi

# --- Docker -------------------------------------------------------------------
echo -e "\n${BOLD}8. Docker${RESET}"

check_tool docker "Docker" "$(install_hint docker)"

if command -v docker &> /dev/null; then
  if docker compose version &> /dev/null 2>&1; then
    pass "Docker Compose v2 available"
  elif command -v docker-compose &> /dev/null; then
    pass "Docker Compose v1 available (v2 preferred)"
  else
    fail "Docker Compose not found"
  fi

  if docker info &> /dev/null 2>&1; then
    pass "Docker daemon is running"
  else
    fail "Docker daemon is not running - start Docker Desktop"
  fi
fi

# --- Essential CLI tools ------------------------------------------------------
echo -e "\n${BOLD}9. CLI tools${RESET}"

check_tool gh "GitHub CLI" "$(install_hint gh)" || true
check_tool python3 "Python 3" "$(install_hint python3)" || true
check_tool jq "jq" "$(install_hint jq)" || true
check_tool curl "curl" "$(install_hint curl)" || true

# --- SSH (1Password CLI, optional) --------------------------------------------
echo -e "\n${BOLD}10. SSH & 1Password (optional)${RESET}"

if command -v op &> /dev/null; then
  pass "1Password CLI available"
else
  warn "1Password CLI not found - not required, but handy for secrets ($(install_hint op))"
fi

# --- Local-specific checks ----------------------------------------------------
if [[ "$TARGET" == "local" ]]; then
  echo -e "\n${BOLD}11. Local environment${RESET}"

  HOSTS_DOMAIN="${LOCAL_DOMAIN:-${BRAND_SLUG:-myserver}.local}"
  if grep -q "map\.${HOSTS_DOMAIN}" /etc/hosts 2> /dev/null; then
    pass "/etc/hosts has entries for ${HOSTS_DOMAIN} subdomains"
  else
    warn "/etc/hosts missing entries for local subdomains. Add:"
    echo -e "      127.0.0.1  mc.${HOSTS_DOMAIN} map.${HOSTS_DOMAIN} status.${HOSTS_DOMAIN} pack.${HOSTS_DOMAIN} mods.${HOSTS_DOMAIN}"
  fi
fi

# =============================================================================
echo -e "\n${BOLD}=============================================="
echo -e "Results: ${GREEN}${PASS} passed${RESET}, ${RED}${FAIL} failed${RESET}, ${YELLOW}${WARN} warnings${RESET}"
echo "=============================================="

if [[ $FAIL -gt 0 ]]; then
  echo -e "\n${RED}${BOLD}Preflight FAILED.${RESET} Fix the items above before proceeding."
  echo "Refer to README.md for detailed setup instructions."
  exit 1
else
  echo -e "\n${GREEN}${BOLD}Preflight PASSED.${RESET} You're ready to proceed."
  if [[ $WARN -gt 0 ]]; then
    echo -e "${YELLOW}Review the warnings above - they may need attention later.${RESET}"
  fi
  exit 0
fi
