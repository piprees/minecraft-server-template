#!/usr/bin/env bash
# setup.sh - Interactive setup wizard for a Minecraft adventure server.
#
# Walks you through every step from a fresh clone to a running server:
# collecting credentials, generating config, testing locally, provisioning
# cloud infrastructure, and deploying. Each action is delegated to a
# purpose-built script; this wizard just orchestrates them.
#
# ── Workflow ─────────────────────────────────────────────────────────
#
#   1. Target         Pick where the server will run (local / hetzner / DO)
#   2. Prerequisites  Check for required CLI tools, offer to install
#   3. 1Password      Connect to 1Password CLI for secret storage/recovery
#   4. Branding       Server name, slug, MOTD
#   5. Core settings  Minecraft username, port, memory, allowlist, seed
#   6. Domain & keys  Cloud provider API token, Cloudflare API token + IDs
#   7. Discord        Bot token, channel ID, guild ID, webhook URL, role IDs
#   8. Backups        Cloudflare R2 bucket + S3 credentials, restic passphrase
#   9. Generate .env  Write .env from everything collected above
#  10. Preflight      Validate config (delegates to preflight-check.sh)
#  11. /etc/hosts     Offer to add local subdomain entries
#  12. Local test     Start the server locally (delegates to dev-up.sh)
#  13. Seed rolling   Optional multi-hour seed search (delegates to seed/)
#  14. Cloud deploy   provision.sh → harden.sh → prepare-droplet.sh → first boot
#  15. DNS & tunnel   Cloudflare tunnel + DNS records (cloudflare-setup.sh)
#  16. Networking     LAN / VPN / internet exposure guidance
#  17. Modpack        Build client modpack, cache assets for offline resilience
#  18. Summary        Quick-reference card, 1Password verification report
#
# ── What you'll need ─────────────────────────────────────────────────
#
# Gather these before you start (the wizard links to each dashboard):
#
#   Required for all targets:
#     • Your Minecraft Java Edition username (exact case)
#     • A Discord bot token, channel ID, guild ID, and webhook URL
#       https://discord.com/developers/applications
#
#   Required for cloud targets (hetzner / digitalocean):
#     • A domain managed by Cloudflare DNS
#     • Cloudflare API token — Custom Token with Account/Cloudflare Tunnel:Edit,
#       Account/Workers R2 Storage:Edit, Account/Workers Scripts:Edit,
#       Zone/DNS:Edit, Zone/Zone:Read (the wizard verifies it live and
#       auto-fills Account ID + Zone ID from it)
#       https://dash.cloudflare.com/profile/api-tokens
#     • Hetzner API token -or- DigitalOcean API token
#     • Cloudflare R2 S3 keypair (Access Key ID + Secret Access Key) for backups
#
#   Which token/key is which — and the traps: docs/credentials.md
#
# ── Environment model ───────────────────────────────────────────────
#
# All config lives in a single .env file (git-ignored).
# .env.example documents every variable with placeholders.
#
# After EVERY answered prompt, the value is immediately written to .env.
# A crash mid-wizard loses nothing.
#
# ── 1Password integration ───────────────────────────────────────────
#
# If the 1Password CLI (`op`) is installed and signed in, every secret
# collected during setup is stored in a vault item. This means:
#   • If you lose .env, regenerate it:  ./scripts/op-env.sh > .env
#   • After manual changes:             ./scripts/op-sync-env.sh
#   • Values are read-back verified after each store to catch failures
#
# ── Idempotency & multi-instance ────────────────────────────────────
#
# Safe to re-run. Already-set values are shown as defaults (press Enter
# to keep). Running from a DIFFERENT clone/folder produces an independent
# instance with its own COMPOSE_PROJECT_NAME, Docker networks, and volumes.
# Port clashes between instances are detected and alternative ports offered.
#
# ── Usage ────────────────────────────────────────────────────────────
#
#   ./scripts/setup.sh                        # full interactive wizard
#   ./scripts/setup.sh --target local          # preset the deployment target
#   ./scripts/setup.sh --target hetzner
#   ./scripts/setup.sh --target digitalocean
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib.sh"
cd "$PROJECT_DIR"

# Consumers invoke this wizard as `./ops setup`; platform developers run
# scripts/setup.sh directly. Use the right name in every message.
SELF_CMD="./scripts/setup.sh"
[[ -n "${CONSUMER_DIR:-}" ]] && SELF_CMD="./ops setup"

# --- state directory for persistent tool-decline tracking --------------------
STATE_DIR="$PROJECT_DIR/.setup-state"
mkdir -p "$STATE_DIR"

was_declined() { [[ -f "$STATE_DIR/declined-$1" ]]; }
mark_declined() { touch "$STATE_DIR/declined-$1"; }

# --- interactive UI helpers (extend lib.sh for the wizard) -------------------
DIM='\033[2m'
[[ ! -t 1 ]] && DIM=''

banner() {
  echo ""
  echo -e "${BOLD}${BLUE}═══════════════════════════════════════════════════${RESET}"
  echo -e "${BOLD}  $1${RESET}"
  echo -e "${BOLD}${BLUE}═══════════════════════════════════════════════════${RESET}"
  echo ""
}

step() {
  echo -e "\n${BOLD}${GREEN}▸ $1${RESET}"
}

info() {
  echo -e "  ${YELLOW}$1${RESET}"
}

setup_warn() {
  echo -e "  ${RED}⚠ $1${RESET}"
}

prompt_value() {
  local var_name="$1"
  local description="$2"
  local default="${3:-}"
  local current="${!var_name:-$default}"

  if [[ -n "$current" ]]; then
    echo -ne "  ${description} [${DIM}${current}${RESET}]: "
  else
    echo -ne "  ${description}: "
  fi
  read -r input
  input="${input:-$current}"
  # Tolerate pasted values that arrive pre-quoted ('...' or "...")
  input=$(strip_surrounding_quotes "$input")
  printf -v "$var_name" '%s' "$input"
}

prompt_secret() {
  local var_name="$1"
  local description="$2"
  local current="${!var_name:-}"

  if [[ -n "$current" ]]; then
    echo -ne "  ${description} [${DIM}already set, Enter to keep${RESET}]: "
  else
    echo -ne "  ${description}: "
  fi
  read -rs input
  echo ""
  if [[ -n "$input" ]]; then
    # Tolerate pasted values that arrive pre-quoted ('...' or "...")
    input=$(strip_surrounding_quotes "$input")
    printf -v "$var_name" '%s' "$input"
  elif [[ -z "$current" ]]; then
    printf -v "$var_name" '%s' ""
  fi
}

show_link() {
  echo -e "  ${BLUE}> ${1}${RESET}"
}

open_link() {
  show_link "$1"
  if command -v open &> /dev/null; then
    open "$1" 2> /dev/null || true
  elif command -v xdg-open &> /dev/null; then
    xdg-open "$1" 2> /dev/null || true
  fi
}

pause() {
  echo ""
  echo -ne "  ${DIM}Press Enter when ready...${RESET}"
  read -r
}

ask_yes_no() {
  local prompt="$1"
  local default="${2:-Y}"
  local hint
  if [[ "$default" =~ ^[Yy] ]]; then
    hint="Y/n"
  else
    hint="y/N"
  fi
  echo -ne "${prompt} [${hint}]: "
  read -r answer
  answer="${answer:-$default}"
  [[ "$answer" =~ ^[Yy] ]]
}

is_placeholder() {
  local val="$1"
  case "$val" in
    '') return 0 ;;
    *xxxxxxxxxxxx*) return 0 ;;
    example.com) return 0 ;;
    change-me-*) return 0 ;;
    YourMinecraftUsername) return 0 ;;
    000000000000000000) return 0 ;;
    'Your server MOTD here') return 0 ;;
    *) return 1 ;;
  esac
}

# --- write-through persistence helpers ----------------------------------------

update_dotenv() {
  local key="$1"
  local value="$2"
  local file="$PROJECT_DIR/.env"
  if [[ ! -f "$file" ]]; then
    echo "# Generated by setup.sh" > "$file"
    echo "" >> "$file"
  fi
  # Always-quoted writes via lib.sh (handles embedded quotes safely)
  set_env_var "$file" "$key" "$value"
}

# Write any value to .env
persist_secret() {
  local key="$1"
  local value="$2"
  update_dotenv "$key" "$value"
}

# --- error recovery ----------------------------------------------------------
save_progress() {
  : # progress already saved via persist_secret after each prompt
}

cleanup_on_error() {
  local rc=$?
  if [[ $rc -ne 0 ]]; then
    echo ""
    echo -e "${RED}═══════════════════════════════════════════════════${RESET}"
    echo -e "${RED}  Setup interrupted (exit code: ${rc})${RESET}"
    echo -e "${RED}═══════════════════════════════════════════════════${RESET}"
    echo ""
    echo "  Your progress has been saved. Re-run ${SELF_CMD} to continue."
    echo "  Configuration collected so far is preserved in .env."
    save_progress
  fi
}
trap cleanup_on_error EXIT
# Name the failing command - a set -e death with no message is undebuggable.
trap 'echo -e "\n  ${RED}Failed at line ${LINENO}: ${BASH_COMMAND}${RESET}" >&2' ERR

# --- run a delegated script with error recovery ------------------------------
run_script() {
  local description="$1"
  shift
  step "$description"
  if "$@"; then
    echo -e "  ${GREEN}✓${RESET} $description"
    return 0
  else
    local rc=$?
    echo ""
    setup_warn "$description failed (exit code: $rc)"
    if ask_yes_no "  Retry?"; then
      if "$@"; then
        echo -e "  ${GREEN}✓${RESET} $description (succeeded on retry)"
        return 0
      fi
    fi
    echo ""
    info "Skipped. You can fix this later and re-run ${SELF_CMD}."
    info "Or run the script directly: $*"
    return 0
  fi
}

# --- COMPOSE_PROJECT_NAME from directory name --------------------------------
sanitise_project_name() {
  local dir_name
  dir_name="$(basename "$PROJECT_DIR")"
  echo "$dir_name" | tr '[:upper:]' '[:lower:]' | sed 's/[^a-z0-9_-]/-/g' | sed 's/^-//' | sed 's/-$//' | head -c 63
}

# --- port clash detection ----------------------------------------------------
check_port() {
  local port="$1"
  local proto="${2:-tcp}"
  if command -v lsof &> /dev/null; then
    lsof -i "${proto}:${port}" -sTCP:LISTEN &> /dev/null 2>&1 && return 0
  fi
  if command -v nc &> /dev/null; then
    nc -z 127.0.0.1 "$port" &> /dev/null 2>&1 && return 0
  fi
  return 1
}

offer_alt_port() {
  local var_name="$1"
  local current="${!var_name}"
  local label="$2"

  if check_port "$current"; then
    setup_warn "Port ${current} (${label}) is already in use."
    local alt=$((current + 1))
    while check_port "$alt"; do
      alt=$((alt + 1))
    done
    prompt_value "$var_name" "Alternative port for ${label}" "$alt"
  fi
}

# =============================================================================
# 1Password helpers (store secrets for recovery and cross-device use)
# =============================================================================
HAS_OP=0
OP_VERIFY_FAILURES=0

setup_1password() {
  if ! command -v op &> /dev/null || ! op account list &> /dev/null 2>&1; then
    return
  fi
  HAS_OP=1

  # One 1Password item PER SERVER (brand) so two server repos can never
  # clobber each other's credentials. Needs the slug up front.
  if [[ -z "${BRAND_SLUG:-}" && -z "${OP_ITEM_NAME:-}" ]]; then
    echo ""
    info "Secrets are stored per-server in 1Password, named by your server slug."
    prompt_value BRAND_SLUG "Server slug (lowercase, no spaces)" "$(sanitise_project_name)"
    persist_secret BRAND_SLUG "$BRAND_SLUG"
  fi
  VAULT_NAME="${OP_VAULT:-Dev}"
  # Hyphen, not parentheses: op:// secret references reject ( and ), which
  # silently broke every read-back. Keep item names URI-safe.
  ITEM_NAME="${OP_ITEM_NAME:-Minecraft Server${BRAND_SLUG:+ - ${BRAND_SLUG}}}"

  echo -e "  ${GREEN}✓${RESET} 1Password CLI detected - secrets will be stored in your vault."
  echo -e "    Vault: ${BOLD}${VAULT_NAME}${RESET} / Item: ${BOLD}${ITEM_NAME}${RESET}"

  if ! op item get "$ITEM_NAME" --vault "$VAULT_NAME" &> /dev/null 2>&1; then
    echo "  Creating 1Password item '${ITEM_NAME}'..."
    op item create --category=server --vault "$VAULT_NAME" \
      --title "$ITEM_NAME" \
      "local.RCON_PASSWORD=$(head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)" \
      "local.KUMA_PASSWORD=$(head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)" \
      "local.ONLINE_MODE=FALSE" \
      "prod.RCON_PASSWORD=$(head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)" \
      "prod.KUMA_PASSWORD=$(head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)" \
      "prod.ONLINE_MODE=TRUE" \
      2> /dev/null || true
    echo -e "  ${GREEN}✓${RESET} Created with auto-generated RCON + Kuma passwords for local + prod."
  else
    echo -e "  ${GREEN}✓${RESET} Item already exists."
  fi
}

op_load() {
  [[ $HAS_OP -eq 0 ]] && return 0
  local var_name="$1" field="${2:-$1}" section="${3:-}"
  [[ -n "${!var_name:-}" ]] && return 0
  local spec="$field"
  [[ -n "$section" ]] && spec="${section}.${field}"
  local val
  # op item get, not op read: secret-reference URIs reject several
  # characters (parentheses broke per-brand item names entirely).
  val=$(op item get "$ITEM_NAME" --vault "$VAULT_NAME" --fields "label=${spec}" --reveal 2> /dev/null || true)
  # A missing field is fine (not every credential exists in every vault).
  # Do NOT end on a bare `[[ ]] &&` - a false test would return 1 and,
  # under set -e, kill the whole wizard with no error message.
  if [[ -n "$val" ]]; then
    printf -v "$var_name" '%s' "$val"
  fi
  return 0
}

op_store() {
  local field="$1"
  local value="$2"
  local section="${3:-}"

  [[ $HAS_OP -eq 0 || -z "$value" ]] && return 0

  local edit_key="$field"
  if [[ -n "$section" ]]; then
    edit_key="${section}.${field}"
  fi

  # 1Password is a backup convenience - a store/verify failure is counted
  # and reported in the final summary, but must NEVER abort the wizard
  # (a non-zero return here would, under set -e).
  if ! op item edit "$ITEM_NAME" --vault "$VAULT_NAME" \
    "${edit_key}=${value}" > /dev/null 2>&1; then
    echo -e "    ${RED}✗${RESET} 1Password: failed to store ${field}"
    OP_VERIFY_FAILURES=$((OP_VERIFY_FAILURES + 1))
    return 0
  fi

  local readback
  readback=$(op item get "$ITEM_NAME" --vault "$VAULT_NAME" --fields "label=${edit_key}" --reveal 2> /dev/null || true)
  if [[ "$readback" != "$value" ]]; then
    # The CLI can race the desktop app's sync right after a write - one
    # short retry absorbs it.
    sleep 1
    readback=$(op item get "$ITEM_NAME" --vault "$VAULT_NAME" --fields "label=${edit_key}" --reveal 2> /dev/null || true)
  fi
  if [[ "$readback" == "$value" ]]; then
    echo -e "    ${GREEN}✓${RESET} 1Password: ${field} (verified)"
  else
    echo -e "    ${RED}✗${RESET} 1Password: ${field} (read-back mismatch - continuing; re-sync later with ./ops op-sync-env)"
    OP_VERIFY_FAILURES=$((OP_VERIFY_FAILURES + 1))
  fi
  return 0
}

# --- cross-platform install hint ---------------------------------------------
install_hint() {
  local tool="$1"
  case "$tool" in
    docker) echo "https://docs.docker.com/desktop/" ;;
    gh) echo "macOS: brew install gh | Debian/Ubuntu: apt install gh | Windows: winget install GitHub.cli | https://cli.github.com/" ;;
    python3) echo "macOS: brew install python3 | Debian/Ubuntu: apt install python3 | Windows: winget install Python.Python.3 | https://www.python.org/downloads/" ;;
    jq) echo "macOS: brew install jq | Debian/Ubuntu: apt install jq | https://jqlang.org/" ;;
    curl) echo "macOS: brew install curl | Debian/Ubuntu: apt install curl" ;;
    hcloud) echo "macOS: brew install hcloud | Debian/Ubuntu: apt install hcloud-cli | https://github.com/hetznercloud/cli" ;;
    doctl) echo "macOS: brew install doctl | Debian/Ubuntu: snap install doctl | https://docs.digitalocean.com/reference/doctl/how-to/install/" ;;
    cloudflared) echo "macOS: brew install cloudflared | https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/" ;;
    restic) echo "macOS: brew install restic | Debian/Ubuntu: apt install restic | https://restic.net/" ;;
    op) echo "macOS: brew install 1password-cli | https://developer.1password.com/docs/cli/get-started/" ;;
    *) echo "https://command-not-found.com/$tool" ;;
  esac
}

# =============================================================================
#  Phase 1: Welcome & Target Selection
# =============================================================================
banner "Minecraft Adventure Server - Setup Wizard"

echo "This wizard walks you through every step to get the server running."
echo "It delegates to existing scripts so each action has a single source of truth."
echo ""

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

# --- load existing config as defaults ----------------------------------------
IS_RERUN=0
if [[ -f "$PROJECT_DIR/.env" ]]; then
  IS_RERUN=1
  set -a
  # shellcheck disable=SC1091
  source "$PROJECT_DIR/.env"
  set +a
fi

# --- detect or ask for target -------------------------------------------------
if [[ -z "$TARGET" && -n "${CLOUD_PROVIDER:-}" && "$CLOUD_PROVIDER" != "local" ]]; then
  TARGET="$CLOUD_PROVIDER"
fi

if [[ -z "$TARGET" ]]; then
  step "Where will this server run?"
  echo ""
  echo "  1) Local machine only (testing, seed rolling, no cloud)"
  echo "  2) Hetzner Cloud (~EUR8/mo, recommended for production)"
  echo "  3) DigitalOcean (~\$48/mo)"
  echo ""
  read -rp "  Choice [1]: " choice
  case "${choice:-1}" in
    1) TARGET="local" ;;
    2) TARGET="hetzner" ;;
    3) TARGET="digitalocean" ;;
    *) TARGET="local" ;;
  esac
fi

IS_CLOUD=0
[[ "$TARGET" != "local" ]] && IS_CLOUD=1

persist_secret CLOUD_PROVIDER "$TARGET"
echo ""
echo -e "  ${GREEN}✓${RESET} Target: ${BOLD}${TARGET}${RESET}"

# --- derive COMPOSE_PROJECT_NAME from directory (multi-instance support) ------
if [[ -z "${COMPOSE_PROJECT_NAME:-}" ]] || is_placeholder "${COMPOSE_PROJECT_NAME:-}"; then
  COMPOSE_PROJECT_NAME="$(sanitise_project_name)"
fi
persist_secret COMPOSE_PROJECT_NAME "$COMPOSE_PROJECT_NAME"

# --- detect container name clash (another instance already owns "mc") ---------
if [[ -z "${CONTAINER_PREFIX:-}" ]] && command -v docker &> /dev/null && docker info &> /dev/null 2>&1; then
  existing_mc="$(docker ps -a --format '{{.Names}}' 2> /dev/null | grep -x 'mc' || true)"
  if [[ -n "$existing_mc" ]]; then
    existing_project="$(docker inspect mc --format '{{index .Config.Labels "com.docker.compose.project"}}' 2> /dev/null || true)"
    if [[ -n "$existing_project" && "$existing_project" != "$COMPOSE_PROJECT_NAME" ]]; then
      setup_warn "A container named 'mc' already exists (project: ${existing_project})."
      info "Setting CONTAINER_PREFIX to avoid name collisions."
      CONTAINER_PREFIX="${COMPOSE_PROJECT_NAME}-"
      persist_secret CONTAINER_PREFIX "$CONTAINER_PREFIX"
    fi
  fi
fi

# =============================================================================
#  Phase 2: Prerequisites
# =============================================================================
banner "Prerequisites"

step "Checking required tools"

check_tool_with_consent() {
  local cmd="$1"
  local required="${2:-optional}"
  local pkg_name="${3:-$cmd}"

  if command -v "$cmd" &> /dev/null; then
    echo -e "  ${GREEN}✓${RESET} $cmd"
    return 0
  fi

  if was_declined "$cmd"; then
    echo -e "  ${YELLOW}○${RESET} $cmd - skipped (previously declined)"
    return 1
  fi

  if [[ "$required" == "required" ]]; then
    echo -e "  ${RED}✗${RESET} $cmd - REQUIRED"
  else
    echo -e "  ${YELLOW}○${RESET} $cmd - not found"
  fi
  echo -e "    $(install_hint "$cmd")"

  if [[ "$(uname)" == "Darwin" ]] && command -v brew &> /dev/null && [[ "$cmd" != "docker" ]]; then
    if ask_yes_no "    Install ${cmd} with brew?" "Y"; then
      if brew install "$pkg_name" 2> /dev/null; then
        echo -e "  ${GREEN}✓${RESET} $cmd installed"
        return 0
      else
        setup_warn "Failed to install $cmd"
      fi
    else
      mark_declined "$cmd"
    fi
  elif [[ "$(uname)" == "Linux" ]] && command -v apt &> /dev/null && [[ "$cmd" != "docker" ]]; then
    if ask_yes_no "    Install ${cmd} with apt?" "Y"; then
      if sudo apt install -y "$pkg_name" 2> /dev/null; then
        echo -e "  ${GREEN}✓${RESET} $cmd installed"
        return 0
      else
        setup_warn "Failed to install $cmd"
      fi
    else
      mark_declined "$cmd"
    fi
  fi

  return 1
}

check_tool_with_consent docker required
check_tool_with_consent curl required
check_tool_with_consent jq required
check_tool_with_consent gh optional
check_tool_with_consent python3 optional
check_tool_with_consent op optional 1password-cli

case "$TARGET" in
  hetzner)
    check_tool_with_consent hcloud optional
    ;;
  digitalocean)
    check_tool_with_consent doctl optional
    ;;
esac

if [[ $IS_CLOUD -eq 1 ]]; then
  check_tool_with_consent restic optional
  check_tool_with_consent cloudflared optional
fi

# Docker requires special handling (can't brew install it)
if ! command -v docker &> /dev/null; then
  echo ""
  setup_warn "Docker is required but not installed."
  echo -e "  macOS:   Install Docker Desktop from https://docs.docker.com/desktop/"
  echo -e "  Linux:   https://docs.docker.com/engine/install/"
  echo -e "  Windows: Install Docker Desktop, use WSL2 backend"
  pause
fi

echo ""
echo -e "  ${GREEN}✓${RESET} Prerequisites checked"

# =============================================================================
#  Phase 3: 1Password Integration
# =============================================================================
setup_1password

if [[ $HAS_OP -eq 1 ]]; then
  op_load HCLOUD_TOKEN HETZNER_API_TOKEN
  op_load DO_API_TOKEN
  op_load CLOUDFLARE_API_TOKEN
  op_load CLOUDFLARE_ACCOUNT_ID
  op_load CLOUDFLARE_ZONE_ID
  op_load CLOUDFLARE_TUNNEL_ID
  op_load DISCORD_BOT_TOKEN
  op_load DISCORD_CHANNEL_ID
  op_load DISCORD_GUILD_ID
  op_load DISCORD_WEBHOOK_URL
  op_load R2_ACCOUNT_ID
  op_load R2_BUCKET
  op_load R2_ACCESS_KEY_ID
  op_load R2_SECRET_ACCESS_KEY
  op_load RESTIC_PASSWORD
  op_load RCON_PASSWORD RCON_PASSWORD local
  op_load KUMA_PASSWORD KUMA_PASSWORD local
  op_load KUMA_API_KEY KUMA_UPTIME_CHECKS_API_KEY
  op_load DOMAIN
else
  setup_warn "1Password CLI not found. Secrets will be saved to .env only."
  echo -e "  $(install_hint op)"
fi

# Auto-generate passwords if still empty
if [[ -z "${RCON_PASSWORD:-}" ]]; then
  RCON_PASSWORD="$(head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)"
  persist_secret RCON_PASSWORD "$RCON_PASSWORD"
  op_store "RCON_PASSWORD" "$RCON_PASSWORD" "local"
fi
if [[ -z "${KUMA_PASSWORD:-}" ]]; then
  KUMA_PASSWORD="$(head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)"
  persist_secret KUMA_PASSWORD "$KUMA_PASSWORD"
  op_store "KUMA_PASSWORD" "$KUMA_PASSWORD" "local"
fi

# --- fast-forward: skip credential collection if everything is set -----------
SKIP_CREDENTIALS=0
if [[ $IS_RERUN -eq 1 ]]; then
  REQUIRED_VARS=(OPS WHITELIST SERVER_PORT MEMORY RCON_PASSWORD DOMAIN
    DISCORD_BOT_TOKEN DISCORD_CHANNEL_ID DISCORD_WEBHOOK_URL)
  if [[ $IS_CLOUD -eq 1 ]]; then
    REQUIRED_VARS+=(CLOUDFLARE_API_TOKEN CLOUDFLARE_ACCOUNT_ID CLOUDFLARE_ZONE_ID
      R2_ACCESS_KEY_ID R2_SECRET_ACCESS_KEY RESTIC_PASSWORD)
    case "$TARGET" in
      hetzner) REQUIRED_VARS+=(HCLOUD_TOKEN) ;;
      digitalocean) REQUIRED_VARS+=(DO_API_TOKEN) ;;
    esac
  fi

  MISSING_REQUIRED=()
  for var in "${REQUIRED_VARS[@]}"; do
    if is_placeholder "${!var:-}"; then
      MISSING_REQUIRED+=("$var")
    fi
  done

  if [[ ${#MISSING_REQUIRED[@]} -eq 0 ]]; then
    echo ""
    echo -e "${GREEN}✓ All credentials and settings found in .env / 1Password.${RESET}"
    if ask_yes_no "  Skip to testing and deployment?"; then
      SKIP_CREDENTIALS=1
      echo -e "  ${GREEN}✓${RESET} Skipping to testing and deployment"
    fi
  else
    echo ""
    echo -e "${YELLOW}Missing or placeholder values: ${MISSING_REQUIRED[*]}${RESET}"
    info "Running full credential collection."
  fi
fi

if [[ $SKIP_CREDENTIALS -eq 0 ]]; then

  # ===========================================================================
  #  Phase 4: Branding & Identity
  # ===========================================================================
  banner "Server Branding"

  step "Server name"
  info "The display name for your server (shown in Discord, web pages, etc.)."
  prompt_value BRAND_NAME "Server name" "${BRAND_NAME:-Adventure Server}"
  persist_secret BRAND_NAME "$BRAND_NAME"

  step "Server slug"
  info "Lowercase slug used in filenames, Docker namespaces, tunnel names."
  info "No spaces, no special characters."
  slug_default="${BRAND_SLUG:-adventure}"
  prompt_value BRAND_SLUG "Server slug" "$slug_default"
  persist_secret BRAND_SLUG "$BRAND_SLUG"

  # Rename the modpack manifest if it's still the default placeholder
  OLD_MANIFEST="$PROJECT_DIR/modpack/adventure.mrpack.json"
  NEW_MANIFEST="$PROJECT_DIR/modpack/${BRAND_SLUG}.mrpack.json"
  if [[ -f "$OLD_MANIFEST" && "$BRAND_SLUG" != "adventure" && ! -f "$NEW_MANIFEST" ]]; then
    mv "$OLD_MANIFEST" "$NEW_MANIFEST"
    # Update all references across the repo
    for f in docker-compose.yml \
             scripts/build-modpack.sh scripts/check-updates.sh scripts/cache-assets.sh \
             .github/workflows/deploy.yml AGENTS.md README.md CONTRIBUTING.md \
             docs/customisation.md modpack/README.md \
             .github/ISSUE_TEMPLATE/mod-request.yml; do
      if [[ -f "$PROJECT_DIR/$f" ]]; then
        sed_i "s/adventure\.mrpack\.json/${BRAND_SLUG}.mrpack.json/g" "$PROJECT_DIR/$f"
      fi
    done
    echo -e "  ${GREEN}✓${RESET} Renamed modpack manifest to ${BRAND_SLUG}.mrpack.json"
  fi

  step "Message of the day (MOTD)"
  info "Shown in the Minecraft server browser. Supports section signs for colour."
  prompt_value MOTD "MOTD" "${MOTD:-}"
  persist_secret MOTD "$MOTD"

  # ===========================================================================
  #  Phase 5: Core Settings
  # ===========================================================================
  banner "Server Settings"

  step "Admin details"
  info "Your exact Minecraft Java username (case-sensitive)."
  info "This account gets operator privileges on the server."
  prompt_value OPS "Minecraft username" ""
  persist_secret OPS "$OPS"

  step "Initial allowlist"
  info "Comma-separated Minecraft usernames who can join."
  info "You can add more later with: docker exec -i ${CONTAINER_PREFIX:-}mc rcon-cli whitelist add NAME"
  prompt_value WHITELIST "Allowlist" "$OPS"
  persist_secret WHITELIST "$WHITELIST"

  step "World seed"
  info "Enter a seed if you have one, or leave blank to get a random one."
  info "If you want to find a really good seed, skip this and use the"
  info "seed roller later (it tests hundreds of seeds against the real modpack)."
  prompt_value SEED "Seed (blank = random, or roll later)" "${SEED:-}"
  if [[ -n "$SEED" ]]; then
    persist_secret SEED "$SEED"
  fi

  step "Spawn coordinates"
  info "Where players spawn when they first join. Leave as 0/64/0 to use"
  info "the world's natural spawn point, or set specific coords if you've"
  info "scouted a seed and know where you want players to land."
  prompt_value SPAWN_X "Spawn X" "${SPAWN_X:-0}"
  prompt_value SPAWN_Y "Spawn Y" "${SPAWN_Y:-64}"
  prompt_value SPAWN_Z "Spawn Z" "${SPAWN_Z:-0}"
  persist_secret SPAWN_X "$SPAWN_X"
  persist_secret SPAWN_Y "$SPAWN_Y"
  persist_secret SPAWN_Z "$SPAWN_Z"

  step "Ports"
  info "Non-default ports reduce automated scanning noise."
  prompt_value GAME_PORT "Game port" "${GAME_PORT:-25577}"
  SERVER_PORT="$GAME_PORT"
  prompt_value VOICE_PORT "Voice chat port (UDP)" "${VOICE_PORT:-24454}"
  prompt_value WEB_PORT "Web services port" "${WEB_PORT:-8080}"
  prompt_value KUMA_PORT "Monitoring port" "${KUMA_PORT:-3001}"

  # Check for port clashes
  offer_alt_port GAME_PORT "game"
  SERVER_PORT="$GAME_PORT"
  offer_alt_port VOICE_PORT "voice"
  offer_alt_port WEB_PORT "web"
  offer_alt_port KUMA_PORT "monitoring"

  persist_secret GAME_PORT "$GAME_PORT"
  persist_secret SERVER_PORT "$SERVER_PORT"
  persist_secret VOICE_PORT "$VOICE_PORT"
  persist_secret WEB_PORT "$WEB_PORT"
  persist_secret KUMA_PORT "$KUMA_PORT"

  step "JVM memory"
  if [[ $IS_CLOUD -eq 1 ]]; then
    info "5-6G for an 8GB cloud server, 4G for local Mac testing."
  else
    info "4G is good for local testing. 6G for a dedicated machine."
  fi
  prompt_value MEMORY "Memory" "5G"
  persist_secret MEMORY "$MEMORY"

  echo -e "\n  ${GREEN}✓${RESET} Core settings saved"

  # ===========================================================================
  #  Phase 6: Domain & Provider Credentials
  # ===========================================================================
  banner "Domain"

  step "Your domain"
  if [[ $IS_CLOUD -eq 1 ]]; then
    info "Must be managed by Cloudflare DNS."
    info "Subdomains: mc.DOMAIN (game), map.DOMAIN (map), pack.DOMAIN (modpack)"
  else
    info "Used for local /etc/hosts entries so services use real subdomain names."
    info "Subdomains: map.DOMAIN, status.DOMAIN, pack.DOMAIN, mods.DOMAIN"
  fi
  prompt_value DOMAIN "Domain name" "${DOMAIN:-example.com}"
  persist_secret DOMAIN "$DOMAIN"
  op_store "DOMAIN" "$DOMAIN"

  step "Cloudflare tunnel name"
  info "Name for the Cloudflare tunnel (derived from your server slug)."
  tunnel_default="${CLOUDFLARE_TUNNEL_NAME:-mc-${BRAND_SLUG}}"
  prompt_value CLOUDFLARE_TUNNEL_NAME "Tunnel name" "$tunnel_default"
  persist_secret CLOUDFLARE_TUNNEL_NAME "$CLOUDFLARE_TUNNEL_NAME"

  if [[ $IS_CLOUD -eq 1 ]]; then
    # --- Deploy SSH key (per-brand: avoids clobbering other servers' keys) ----
    step "Deploy SSH key"
    DEPLOY_KEY_PATH="${DEPLOY_KEY_PATH:-$HOME/.ssh/${BRAND_SLUG}_mc_deploy_key}"
    if [[ ! -f "$DEPLOY_KEY_PATH" ]]; then
      ssh-keygen -t ed25519 -f "$DEPLOY_KEY_PATH" -C "${BRAND_SLUG}-deploy" -N '' -q
      echo -e "  ${GREEN}\u2713${RESET} Generated ${DEPLOY_KEY_PATH}"
    else
      echo -e "  ${GREEN}\u2713${RESET} Using existing ${DEPLOY_KEY_PATH}"
    fi
    persist_secret DEPLOY_KEY_PATH "$DEPLOY_KEY_PATH"
    export DEPLOY_KEY_PATH

    # --- Cloud provider token -------------------------------------------------
    banner "Cloud Provider: ${TARGET}"

    case "$TARGET" in
      hetzner)
        step "Hetzner Cloud API token"
        info "Hetzner Console > your project > Security > API Tokens > Generate."
        info "Needs Read & Write permissions. 64 alphanumeric chars, shown once."
        if [[ -z "${HCLOUD_TOKEN:-}" ]]; then
          open_link "https://console.hetzner.cloud"
          pause
        else
          show_link "https://console.hetzner.cloud"
        fi
        prompt_secret HCLOUD_TOKEN "Paste your Hetzner API token"
        persist_secret HCLOUD_TOKEN "$HCLOUD_TOKEN"
        op_store "HETZNER_API_TOKEN" "${HCLOUD_TOKEN:-}"
        ;;

      digitalocean)
        step "DigitalOcean API token"
        info "Needs Read + Write scope. You only see the token once."
        if [[ -z "${DO_API_TOKEN:-}" ]]; then
          open_link "https://cloud.digitalocean.com/account/api/tokens"
          pause
        else
          show_link "https://cloud.digitalocean.com/account/api/tokens"
        fi
        prompt_secret DO_API_TOKEN "Paste your DO API token"
        persist_secret DO_API_TOKEN "$DO_API_TOKEN"
        op_store "DO_API_TOKEN" "${DO_API_TOKEN:-}"
        ;;
    esac

    # --- Cloudflare -----------------------------------------------------------
    banner "Cloudflare"

    step "Cloudflare API token"
    info "Create Token > Create Custom Token, with these five permissions:"
    info "  Account > Cloudflare Tunnel    > Edit"
    info "  Account > Workers R2 Storage   > Edit"
    info "  Account > Workers Scripts      > Edit"
    info "  Zone    > DNS                  > Edit"
    info "  Zone    > Zone                 > Read"
    info "Zone Resources: include ${DOMAIN}. Copy the token it shows once."
    info "This is a custom API Token - not the Global API Key, and not the"
    info "'Token value' from the R2 page (that one comes later, for backups)."
    if [[ -z "${CLOUDFLARE_API_TOKEN:-}" ]]; then
      open_link "https://dash.cloudflare.com/profile/api-tokens"
      pause
    else
      show_link "https://dash.cloudflare.com/profile/api-tokens"
    fi

    # Verify the token live; a bad paste fails here in seconds, not later
    # in cloudflare-setup.sh. One retry, then continue (preflight re-checks).
    CF_TOKEN_OK=0
    for _cf_attempt in 1 2; do
      prompt_secret CLOUDFLARE_API_TOKEN "Paste your Cloudflare API token"
      if [[ -z "${CLOUDFLARE_API_TOKEN:-}" ]]; then break; fi
      CF_VERIFY=$(curl -s --connect-timeout 10 \
        "https://api.cloudflare.com/client/v4/user/tokens/verify" \
        -H "Authorization: Bearer ${CLOUDFLARE_API_TOKEN}" \
        | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['result']['status'] if d.get('success') else 'invalid')" 2>/dev/null || echo "unreachable")
      if [[ "$CF_VERIFY" == "active" ]]; then
        echo -e "  ${GREEN}✓${RESET} Token verified (active)"
        CF_TOKEN_OK=1
        break
      elif [[ "$CF_VERIFY" == "unreachable" ]]; then
        setup_warn "Couldn't reach the Cloudflare API to verify - continuing."
        break
      else
        setup_warn "That value is not a valid API token (see docs/credentials.md)."
        [[ "$_cf_attempt" == "1" ]] && info "Try again:"
      fi
    done
    persist_secret CLOUDFLARE_API_TOKEN "${CLOUDFLARE_API_TOKEN:-}"
    op_store "CLOUDFLARE_API_TOKEN" "${CLOUDFLARE_API_TOKEN:-}"

    step "Cloudflare Account ID & Zone ID"
    # With a working token these are one API call away - no copy-pasting.
    if [[ $CF_TOKEN_OK -eq 1 ]]; then
      ZONE_JSON=$(curl -s --connect-timeout 10 \
        "https://api.cloudflare.com/client/v4/zones?name=${DOMAIN}" \
        -H "Authorization: Bearer ${CLOUDFLARE_API_TOKEN}" 2>/dev/null || true)
      AUTO_ZONE=$(echo "$ZONE_JSON" | python3 -c "import sys,json; r=json.load(sys.stdin).get('result') or []; print(r[0]['id'] if r else '')" 2>/dev/null || true)
      AUTO_ACCOUNT=$(echo "$ZONE_JSON" | python3 -c "import sys,json; r=json.load(sys.stdin).get('result') or []; print(r[0]['account']['id'] if r else '')" 2>/dev/null || true)
      if [[ -n "$AUTO_ZONE" && -n "$AUTO_ACCOUNT" ]]; then
        CLOUDFLARE_ZONE_ID="$AUTO_ZONE"
        CLOUDFLARE_ACCOUNT_ID="$AUTO_ACCOUNT"
        echo -e "  ${GREEN}✓${RESET} Resolved from the token: zone ${AUTO_ZONE} (${DOMAIN}), account ${AUTO_ACCOUNT}"
      else
        setup_warn "Zone '${DOMAIN}' isn't visible to this token."
        info "Either the domain isn't on this Cloudflare account, or the token's"
        info "Zone Resources don't include it. Enter the IDs manually:"
      fi
    else
      info "Dashboard > your domain > Overview > right sidebar."
    fi
    if [[ -z "${CLOUDFLARE_ACCOUNT_ID:-}" || -z "${CLOUDFLARE_ZONE_ID:-}" ]]; then
      show_link "https://dash.cloudflare.com"
      prompt_value CLOUDFLARE_ACCOUNT_ID "Account ID" "${CLOUDFLARE_ACCOUNT_ID:-}"
      prompt_value CLOUDFLARE_ZONE_ID "Zone ID" "${CLOUDFLARE_ZONE_ID:-}"
    fi
    persist_secret CLOUDFLARE_ACCOUNT_ID "$CLOUDFLARE_ACCOUNT_ID"
    persist_secret CLOUDFLARE_ZONE_ID "$CLOUDFLARE_ZONE_ID"
    op_store "CLOUDFLARE_ACCOUNT_ID" "$CLOUDFLARE_ACCOUNT_ID"
    op_store "CLOUDFLARE_ZONE_ID" "$CLOUDFLARE_ZONE_ID"

  fi # end IS_CLOUD (provider + Cloudflare)

  # ===========================================================================
  #  Phase 7: Discord
  # ===========================================================================
  banner "Discord"

  step "Create a Discord bot"
  echo ""
  echo "  Quick setup (see docs/setup-guide.md for the full version):"
  echo ""
  echo "    1. Go to https://discord.com/developers/applications"
  echo "    2. Click 'New Application', name it after your server"
  echo "    3. Go to Bot tab:"
  echo "       - Click 'Reset Token' and copy it (you'll paste it below)"
  echo "       - Under 'Privileged Gateway Intents', enable:"
  echo "         * MESSAGE CONTENT INTENT"
  echo "         * SERVER MEMBERS INTENT"
  echo "    4. Go to OAuth2 > URL Generator:"
  echo "       - Scopes: bot, applications.commands"
  echo "       - Bot permissions: Manage Roles, Send Messages,"
  echo "         Read Message History, Use Slash Commands"
  echo "       - Copy the generated URL and open it to invite the bot"
  echo "    5. Enable Developer Mode in Discord (User Settings > Advanced)"
  echo "       to copy IDs by right-clicking channels, roles, and servers."
  echo ""
  if [[ -z "${DISCORD_BOT_TOKEN:-}" ]]; then
    open_link "https://discord.com/developers/applications"
    pause
  else
    show_link "https://discord.com/developers/applications"
  fi

  prompt_secret DISCORD_BOT_TOKEN "Bot token"
  persist_secret DISCORD_BOT_TOKEN "$DISCORD_BOT_TOKEN"
  op_store "DISCORD_BOT_TOKEN" "$DISCORD_BOT_TOKEN"

  step "Discord channel ID"
  info "Developer Mode on > right-click the Minecraft channel > Copy Channel ID."
  prompt_value DISCORD_CHANNEL_ID "Channel ID" ""
  persist_secret DISCORD_CHANNEL_ID "$DISCORD_CHANNEL_ID"
  op_store "DISCORD_CHANNEL_ID" "$DISCORD_CHANNEL_ID"

  step "Discord server (guild) ID"
  info "Developer Mode on > right-click the server name > Copy Server ID."
  prompt_value DISCORD_GUILD_ID "Guild ID" ""
  persist_secret DISCORD_GUILD_ID "$DISCORD_GUILD_ID"
  op_store "DISCORD_GUILD_ID" "$DISCORD_GUILD_ID"

  step "Discord webhook (for deploy notifications)"
  info "Channel > Edit > Integrations > Webhooks > New Webhook > Copy URL."
  prompt_value DISCORD_WEBHOOK_URL "Webhook URL" ""
  persist_secret DISCORD_WEBHOOK_URL "$DISCORD_WEBHOOK_URL"
  op_store "DISCORD_WEBHOOK_URL" "$DISCORD_WEBHOOK_URL"

  step "Discord role IDs"
  info "These IDs link Discord roles to in-game permissions."
  info "Right-click each role > Copy Role ID (Developer Mode must be on)."
  echo ""

  prompt_value DISCORD_ADMIN_ROLE_ID "Admin role ID" ""
  persist_secret DISCORD_ADMIN_ROLE_ID "$DISCORD_ADMIN_ROLE_ID"

  prompt_value DISCORD_PLAYER_ROLE_ID "Player role ID" ""
  persist_secret DISCORD_PLAYER_ROLE_ID "$DISCORD_PLAYER_ROLE_ID"

  prompt_value DISCORD_BOT_ROLE_ID "Bot role ID" ""
  persist_secret DISCORD_BOT_ROLE_ID "$DISCORD_BOT_ROLE_ID"

  step "Discord welcome channel"
  info "The channel where the bot posts the pinned welcome message."
  info "Leave empty to skip welcome pin management."
  prompt_value DISCORD_WELCOME_CHANNEL_ID "Welcome channel ID" ""
  persist_secret DISCORD_WELCOME_CHANNEL_ID "$DISCORD_WELCOME_CHANNEL_ID"

  if [[ -n "${DISCORD_WELCOME_CHANNEL_ID:-}" ]]; then
    prompt_value DISCORD_WELCOME_MESSAGE_ID "Welcome message ID (empty until --init)" ""
    persist_secret DISCORD_WELCOME_MESSAGE_ID "$DISCORD_WELCOME_MESSAGE_ID"
  fi

  step "Discord invite URL"
  info "Shown on the modpack download page so players can join your Discord."
  info "Leave empty to hide the Discord link from the download page."
  prompt_value DISCORD_INVITE_URL "Discord invite URL (optional)" "${DISCORD_INVITE_URL:-}"
  if [[ -n "$DISCORD_INVITE_URL" ]]; then
    persist_secret DISCORD_INVITE_URL "$DISCORD_INVITE_URL"
  fi

  step "Testing Discord webhook"
  if [[ -n "${DISCORD_WEBHOOK_URL:-}" ]]; then
    DISCORD_WEBHOOK_URL="$DISCORD_WEBHOOK_URL" \
      "$SCRIPT_DIR/discord-notify.sh" --key setup.webhook_test \
      && echo -e "  ${GREEN}✓${RESET} Message sent - check your Discord channel" \
      || setup_warn "Webhook test failed. Check the URL and try again."
  else
    info "No webhook URL set - skipping test."
  fi

  # ===========================================================================
  #  Phase 8: Offsite Backups (Cloudflare R2)
  # ===========================================================================
  if [[ $IS_CLOUD -eq 1 ]]; then
    banner "Offsite Backups (Cloudflare R2)"

    R2_ACCOUNT_ID="${CLOUDFLARE_ACCOUNT_ID}"

    step "R2 backup bucket name"
    prompt_value R2_BUCKET "Bucket name" "${R2_BUCKET:-mc-backups}"
    info "The bucket will be created when you run cloudflare-setup.sh."
    persist_secret R2_ACCOUNT_ID "$R2_ACCOUNT_ID"
    persist_secret R2_BUCKET "$R2_BUCKET"
    op_store "R2_ACCOUNT_ID" "$R2_ACCOUNT_ID"
    op_store "R2_BUCKET" "$R2_BUCKET"

    step "R2 S3 keypair (for restic backups)"
    info "Cloudflare doesn't allow creating these via API - dashboard only."
    info "R2 > Manage API Tokens > Create Account API token."
    info "Permission: Object Read & Write, scoped to '${R2_BUCKET}'."
    info "The result page shows three values. You need the bottom two:"
    info "  Access Key ID      (32 hex chars)"
    info "  Secret Access Key  (64 hex chars, shown only once)"
    info "The 'Token value' at the top isn't used anywhere in this setup."
    if [[ -z "${R2_ACCESS_KEY_ID:-}" ]]; then
      open_link "https://dash.cloudflare.com/?to=/:account/r2/api-tokens"
      pause
    else
      show_link "https://dash.cloudflare.com/?to=/:account/r2/api-tokens"
    fi
    prompt_secret R2_ACCESS_KEY_ID "Access Key ID"
    if [[ -n "${R2_ACCESS_KEY_ID:-}" && ! "$R2_ACCESS_KEY_ID" =~ ^[0-9a-f]{32}$ ]]; then
      setup_warn "That doesn't look like an Access Key ID (expected 32 hex chars). Re-check which value you copied."
      prompt_secret R2_ACCESS_KEY_ID "Access Key ID"
    fi
    prompt_secret R2_SECRET_ACCESS_KEY "Secret Access Key"
    if [[ -n "${R2_SECRET_ACCESS_KEY:-}" && ! "$R2_SECRET_ACCESS_KEY" =~ ^[0-9a-f]{64}$ ]]; then
      setup_warn "That doesn't look like a Secret Access Key (expected 64 hex chars). Re-check which value you copied."
      prompt_secret R2_SECRET_ACCESS_KEY "Secret Access Key"
    fi
    persist_secret R2_ACCESS_KEY_ID "$R2_ACCESS_KEY_ID"
    persist_secret R2_SECRET_ACCESS_KEY "$R2_SECRET_ACCESS_KEY"
    op_store "R2_ACCESS_KEY_ID" "$R2_ACCESS_KEY_ID"
    op_store "R2_SECRET_ACCESS_KEY" "$R2_SECRET_ACCESS_KEY"

    step "Restic encryption passphrase"
    if [[ -z "${RESTIC_PASSWORD:-}" ]]; then
      RESTIC_PASSWORD="$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 32)"
      echo -e "  Auto-generated: ${DIM}${RESTIC_PASSWORD}${RESET}"
    else
      echo -e "  ${GREEN}✓${RESET} Keeping existing passphrase"
    fi
    setup_warn "If you lose this, ALL backups become unrecoverable."
    info "It's been saved to 1Password (if connected)."
    persist_secret RESTIC_PASSWORD "$RESTIC_PASSWORD"
    op_store "RESTIC_PASSWORD" "$RESTIC_PASSWORD"
  fi

  # ===========================================================================
  #  Phase 9: Configuration
  # ===========================================================================
  banner "Configuration"
  echo -e "  ${GREEN}✓${RESET} All values saved to .env"

fi # end SKIP_CREDENTIALS

# =============================================================================
#  Phase 10: Preflight Check
# =============================================================================
banner "Preflight Check"

step "Running preflight-check.sh..."
echo ""
if ! "$SCRIPT_DIR/preflight-check.sh" --target "$TARGET"; then
  echo ""
  setup_warn "Some preflight checks failed."
  info "This is normal if you haven't set up cloud services yet."
  if ! ask_yes_no "  Continue anyway?" "Y"; then
    echo ""
    info "Fix the issues above, then re-run: ${SELF_CMD}"
    exit 0
  fi
fi

# =============================================================================
#  Phase 11: /etc/hosts (local dev domain)
# =============================================================================
banner "Local DNS (/etc/hosts)"

step "Local dev domain"
info "A separate domain for local testing, so /etc/hosts entries"
info "don't shadow your real production DNS."
LOCAL_DOMAIN_DEFAULT="${LOCAL_DOMAIN:-${BRAND_SLUG:-adventure}.local}"
prompt_value LOCAL_DOMAIN "Local dev domain" "$LOCAL_DOMAIN_DEFAULT"
persist_secret LOCAL_DOMAIN "$LOCAL_DOMAIN"

HOSTS_MARKER_BEGIN="# BEGIN minecraft-${BRAND_SLUG:-adventure}"
HOSTS_MARKER_END="# END minecraft-${BRAND_SLUG:-adventure}"

if [[ "$(uname)" == "Darwin" ]] || [[ "$(uname)" == "Linux" ]]; then
  step "Add local /etc/hosts entries?"
  info "Maps local subdomains to 127.0.0.1 so you can access web services"
  info "in your browser while keeping your real domain pointing at prod."
  echo ""
  echo "  Entries to add:"
  echo "    127.0.0.1  mc.${LOCAL_DOMAIN} map.${LOCAL_DOMAIN} pack.${LOCAL_DOMAIN} status.${LOCAL_DOMAIN} mods.${LOCAL_DOMAIN}"
  echo ""

  ALREADY_PRESENT=0
  if grep -q "$HOSTS_MARKER_BEGIN" /etc/hosts 2> /dev/null; then
    ALREADY_PRESENT=1
    echo -e "  ${GREEN}✓${RESET} Entries already present (inside ${HOSTS_MARKER_BEGIN}...${HOSTS_MARKER_END})"
  fi

  if [[ $ALREADY_PRESENT -eq 0 ]]; then
    if ask_yes_no "  Add entries to /etc/hosts? (requires sudo)"; then
      HOSTS_BLOCK="${HOSTS_MARKER_BEGIN}
127.0.0.1  mc.${LOCAL_DOMAIN} map.${LOCAL_DOMAIN} pack.${LOCAL_DOMAIN} status.${LOCAL_DOMAIN} mods.${LOCAL_DOMAIN}
${HOSTS_MARKER_END}"
      echo "$HOSTS_BLOCK" | sudo tee -a /etc/hosts > /dev/null
      echo -e "  ${GREEN}✓${RESET} Added. Remove later with: sudo sed -i '' '/${HOSTS_MARKER_BEGIN}/,/${HOSTS_MARKER_END}/d' /etc/hosts"
    else
      info "Skipped. Add manually or re-run ${SELF_CMD}."
    fi
  fi

  info "To undo: sudo sed -i '' '/${HOSTS_MARKER_BEGIN}/,/${HOSTS_MARKER_END}/d' /etc/hosts"
else
  step "/etc/hosts (Windows)"
  echo ""
  echo "  Add these lines to C:\\Windows\\System32\\drivers\\etc\\hosts"
  echo "  (open Notepad as Administrator):"
  echo ""
  echo "    127.0.0.1  mc.${LOCAL_DOMAIN} map.${LOCAL_DOMAIN} pack.${LOCAL_DOMAIN} status.${LOCAL_DOMAIN} mods.${LOCAL_DOMAIN}"
  echo ""
  pause
fi

# =============================================================================
#  Phase 12: Local Server Test
# =============================================================================
banner "Local Server Test"

echo "Test the local server?"
echo "This will pull Docker images and start the Minecraft server."
echo "First boot downloads Fabric + ~50 mods - takes a few minutes."
echo ""

LOCAL_DEFAULT="Y"
if docker ps --format '{{.Names}}' 2>/dev/null | grep -q "^${CONTAINER_PREFIX:-}mc$"; then
  LOCAL_DEFAULT="N"
  echo -e "  ${GREEN}✓${RESET} Local server is already running."
fi
if ask_yes_no "Start local server?" "$LOCAL_DEFAULT"; then
  run_script "Starting local server" "$SCRIPT_DIR/dev-up.sh"
  echo ""
  echo "  Watch logs:    docker compose --profile local logs -f mc"
  echo "  RCON console:  docker exec -i ${CONTAINER_PREFIX:-}mc rcon-cli"
  echo "  Stop:          ./scripts/dev-up.sh --down"
  echo "  Game server:   mc.${LOCAL_DOMAIN:-localhost}:${GAME_PORT:-25577}"
  pause
fi

# =============================================================================
#  Phase 13: Seed Rolling (optional)
# =============================================================================
banner "Seed Rolling (optional)"

if [[ -n "${SEED:-}" ]]; then
  echo "You already have a seed set: ${SEED}"
  echo "Skip this unless you want to find a better one."
else
  echo "No seed set yet. The server will pick a random one on first boot."
  echo "The seed roller finds good ones by testing structure placement and"
  echo "spawn biomes against the real modpack. Worth doing if you care about"
  echo "your world's starting area."
fi
echo ""
setup_warn "Seed rolling takes HOURS (each seed = full server boot + worldgen)."
info "It's resumable though, so you can stop and come back to it."
echo ""

if ask_yes_no "Start seed rolling?" "N"; then
  echo ""
  # Optional feature: a seed-rolling failure must not kill the wizard.
  run_script "Rolling seeds" "$SCRIPT_DIR/seed/roll-seeds.sh"
  run_script "Building seed report" "$SCRIPT_DIR/seed/report-top.sh"

  echo ""
  step "Choose your seed"
  echo "  Review seed-report-top25.md, then set your chosen seed:"
  prompt_value CHOSEN_SEED "Winning seed" ""
  if [[ -n "$CHOSEN_SEED" ]]; then
    persist_secret SEED "$CHOSEN_SEED"
    echo -e "  ${GREEN}✓${RESET} Seed saved to .env"
  fi
fi

# =============================================================================
#  Phase 14: Cloud Deployment
# =============================================================================
if [[ $IS_CLOUD -eq 1 ]]; then
  banner "Cloud Deployment"

  echo "Ready to provision and deploy to ${TARGET}?"
  echo "This walks through: provision > harden > prepare > deploy."
  echo "Each step is handled by its own script and can be re-run safely."
  echo ""

  if ask_yes_no "Start cloud deployment?" "N"; then

    # --- 14a. Provision -------------------------------------------------------
    echo ""
    step "Step 1/4: Provision the server (./scripts/provision.sh)"
    info "This creates the cloud server (or detects an existing one)."
    echo ""

    if ask_yes_no "  Run provision.sh?"; then
      if "$SCRIPT_DIR/provision.sh" --provider "$TARGET"; then
        echo -e "  ${GREEN}✓${RESET} Server provisioned"
      else
        setup_warn "Provisioning failed."
        info "Fix the issue above and run: ./scripts/provision.sh --provider $TARGET"
        info "Then re-run ${SELF_CMD} to continue from here."
        if ! ask_yes_no "  Continue to next step anyway?" "N"; then
          exit 0
        fi
      fi
    else
      info "Skipped. Run later with: ./scripts/provision.sh --provider $TARGET"
    fi

    # --- Resolve server IP (try .env, then provider CLI) ----------------------
    SERVER_IP="${DROPLET_HOST:-}"
    if [[ -z "$SERVER_IP" ]]; then
      case "$TARGET" in
        hetzner)
          SERVER_IP=$(hcloud server ip "mc-${BRAND_SLUG:-adventure}" 2>/dev/null || true)
          ;;
        digitalocean)
          SERVER_IP=$(doctl compute droplet list --format Name,PublicIPv4 --no-header 2>/dev/null \
            | grep -E "^mc-${BRAND_SLUG:-adventure}\s" | awk '{print $2}' || true)
          ;;
      esac
    fi
    if [[ -z "$SERVER_IP" ]]; then
      prompt_value SERVER_IP "Server IP address" ""
    fi
    if [[ -n "$SERVER_IP" ]]; then
      DROPLET_HOST="$SERVER_IP"
      persist_secret DROPLET_HOST "$SERVER_IP"
      echo -e "  ${GREEN}✓${RESET} Server IP: ${SERVER_IP}"
    fi

    # --- 14b. Harden the server --------------------------------------------------
    if [[ -n "$SERVER_IP" ]]; then
      echo ""
      step "Step 2/4: Harden the server (./scripts/harden.sh)"
      info "Secures SSH, sets up firewall, installs Docker, configures fail2ban."

      DEPLOY_KEY="${DEPLOY_KEY_PATH:-$HOME/.ssh/mc_deploy_key}"
      DEPLOY_SSH_OK=0
      ROOT_SSH_OK=0
      ssh -o ConnectTimeout=5 -o BatchMode=yes -i "$DEPLOY_KEY" "${DEPLOY_USER:-deploy}@${SERVER_IP}" 'command -v docker' 2>/dev/null && DEPLOY_SSH_OK=1
      ssh -o ConnectTimeout=5 -o BatchMode=yes "root@${SERVER_IP}" 'true' 2>/dev/null && ROOT_SSH_OK=1

      if [[ $DEPLOY_SSH_OK -eq 1 ]]; then
        echo -e "  ${GREEN}✓${RESET} Already hardened (deploy user works, Docker installed)"
      elif [[ $ROOT_SSH_OK -eq 1 ]]; then
        run_script "Hardening server" "$SCRIPT_DIR/harden.sh" --remote "root@${SERVER_IP}"
      else
        setup_warn "Can't reach the server via SSH (tried root and deploy)."
        info "Check your SSH keys and the server IP, then re-run ${SELF_CMD}."
      fi

      # --- 14c. Prepare the server ------------------------------------------------
      echo ""
      step "Step 3/4: Prepare the server (./scripts/prepare-droplet.sh)"
      info "Deploy key, repo clone, .env, GitHub Actions variables."
      export DROPLET_HOST="$SERVER_IP"
      run_script "Preparing server" "$SCRIPT_DIR/prepare-droplet.sh"
    else
      setup_warn "No server IP available. Provision the server first."
    fi

    # --- 14d. First Deploy ----------------------------------------------------
    echo ""
    step "Step 4/4: First deploy (./scripts/initial-setup.sh)"
    info "Starts the Minecraft server on the cloud instance."
    echo ""

    DEPLOY_USER="${DEPLOY_USER:-deploy}"
    DEPLOY_KEY="${DEPLOY_KEY_PATH:-$HOME/.ssh/${BRAND_SLUG:+${BRAND_SLUG}_}mc_deploy_key}"
    # Bundle model: the server dir is always ~/server (prepare-droplet.sh
    # creates it), never the consumer folder's name.
    if [[ -n "$SERVER_IP" ]]; then
      run_script "First deploy on ${SERVER_IP}" \
        ssh -o ConnectTimeout=10 -o ServerAliveInterval=30 -i "$DEPLOY_KEY" \
        "${DEPLOY_USER}@${SERVER_IP}" "cd ~/server && .stack/current/stack/scripts/initial-setup.sh"
      info "After this, pushes to main auto-deploy via GitHub Actions."
    fi

  else
    echo ""
    echo "  Cloud deployment steps (run these when ready):"
    echo ""
    echo "  1. Provision: ./ops provision"
    echo "  2. Harden:    ./ops harden"
    echo "  3. Prepare:   ./ops prepare"
    echo "  4. Deploy:    ssh deploy@SERVER_IP 'cd ~/server && .stack/current/stack/scripts/initial-setup.sh'"
  fi

  # ===========================================================================
  #  Phase 15: DNS & Tunnel Setup
  # ===========================================================================
  banner "DNS & Tunnel Setup"

  echo "Set up Cloudflare tunnel and DNS records?"
  echo "This creates DNS records for mc.${DOMAIN}, map.${DOMAIN},"
  echo "pack.${DOMAIN}, and status.${DOMAIN}."
  echo ""
  info "Also creates the R2 backup bucket if configured."
  echo ""

  CF_DEFAULT="Y"
  if [[ -n "${CLOUDFLARE_TUNNEL_ID:-}" && -f "$PROJECT_DIR/config/cloudflared/config.yml" ]]; then
    CF_DEFAULT="N"
    echo -e "  ${GREEN}✓${RESET} Tunnel and DNS already configured (${CLOUDFLARE_TUNNEL_NAME:-})"
  fi
  if ask_yes_no "Run cloudflare-setup.sh?" "$CF_DEFAULT"; then
    if "$SCRIPT_DIR/cloudflare-setup.sh"; then
      echo -e "  ${GREEN}✓${RESET} Cloudflare configured"
    else
      setup_warn "Cloudflare setup failed."
      info "Fix the issue above and run: ./scripts/cloudflare-setup.sh"
    fi
  else
    info "Skipped. Run later with: ./scripts/cloudflare-setup.sh"
  fi

  # ===========================================================================
  #  Phase 15b: GitHub Actions auto-deploy wiring
  # ===========================================================================
  banner "GitHub Actions (auto-deploy)"

  step "Sync the GitHub 'production' environment"
  info "Pushes every secret and variable deploy.yml needs. Without this,"
  info "pushes to main are silently SKIPPED (the workflow gates on DROPLET_HOST)."
  echo ""

  if command -v gh &> /dev/null && gh auth status &> /dev/null; then
    if ask_yes_no "  Sync GitHub environment now?" "Y"; then
      if "$SCRIPT_DIR/github-env-sync.sh"; then
        echo -e "  ${GREEN}✓${RESET} GitHub environment synced - pushes to main will deploy"
      else
        setup_warn "Sync incomplete - some values are missing."
        info "Fill them in (.env), then re-run: ./scripts/github-env-sync.sh"
      fi
    else
      info "Skipped. Run later with: ./scripts/github-env-sync.sh"
    fi
  else
    setup_warn "gh CLI not available or not authenticated - GitHub auto-deploy NOT wired."
    info "Run 'gh auth login', then: ./scripts/github-env-sync.sh"
  fi

fi # end IS_CLOUD

# =============================================================================
#  Phase 16: Networking / Sharing
# =============================================================================
if [[ $IS_CLOUD -eq 1 ]]; then
  echo ""
  echo -e "  ${GREEN}✓${RESET} Players connect at: mc.${DOMAIN:-example.com}:${GAME_PORT:-25577}"
  echo "    (SRV record means the port isn't needed — just mc.${DOMAIN:-example.com})"
else
  banner "Networking (how friends connect)"

  echo "How will players reach this server?"
  echo ""
  echo "  1) Local / LAN only (default - nothing to configure)"
  echo "  2) Virtual LAN (Tailscale, ZeroTier, Hamachi - easiest for remote friends)"
  echo "  3) Internet (port forwarding from home, DDNS)"
  echo ""
  read -rp "  Choice [1]: " net_choice

  case "${net_choice:-1}" in
    2)
      step "Virtual LAN setup"
      echo ""
      echo "  The server listens on port ${GAME_PORT:-25577}/tcp (game)"
      echo "  and ${VOICE_PORT:-24454}/udp (voice chat)."
      echo ""
      echo "  1. Install your VPN of choice on the server machine:"
      echo "     - Tailscale:  https://tailscale.com/download"
      echo "     - ZeroTier:   https://www.zerotier.com/download"
      echo "     - Hamachi:    https://vpn.net"
      echo "  2. Create or join a network, and have friends do the same."
      echo "  3. Friends connect in Minecraft using the VPN IP:"
      echo ""
      echo "     <your-vpn-ip>:${GAME_PORT:-25577}"
      echo ""
      echo "  No port forwarding or firewall changes needed."
      pause
      ;;
    3)
      step "Internet exposure from home"
      echo ""
      echo "  Ports to forward on your router:"
      echo "    ${GAME_PORT:-25577}/tcp  - Minecraft game"
      echo "    ${VOICE_PORT:-24454}/udp - Simple Voice Chat"
      echo ""
      echo "  Firewall commands (Linux):"
      echo "    sudo ufw allow ${GAME_PORT:-25577}/tcp comment 'Minecraft'"
      echo "    sudo ufw allow ${VOICE_PORT:-24454}/udp comment 'Voice Chat'"
      echo ""
      echo "  Dynamic DNS (if your IP changes):"
      echo "    ./scripts/ddns-update.sh --install-cron"
      echo ""
      echo "  Friends connect at: your-domain-or-ip:${GAME_PORT:-25577}"
      echo ""
      setup_warn "IMPORTANT: Undo checklist when you're done hosting:"
      echo "    1. Remove router port forwards"
      echo "    2. sudo ufw delete allow ${GAME_PORT:-25577}/tcp"
      echo "    3. sudo ufw delete allow ${VOICE_PORT:-24454}/udp"
      echo "    4. Remove DDNS cron: crontab -e (delete the ddns-update line)"
      echo "    5. Consider TCPShield (https://tcpshield.com) to hide your home IP"
      pause
      ;;
    *)
      echo -e "  ${GREEN}✓${RESET} Local/LAN only - no additional configuration needed."
      echo "  Friends on the same network connect at: <your-ip>:${GAME_PORT:-25577}"
      ;;
  esac
fi

# =============================================================================
#  Phase 17: Modpack & Asset Caching
# =============================================================================
banner "Modpack & Assets"

MODPACK_DEFAULT="Y"
if [[ -f "$PROJECT_DIR/modpack-dist/index.html" ]]; then
  MODPACK_DEFAULT="N"
  echo -e "  ${GREEN}✓${RESET} Modpack already built."
fi
if ask_yes_no "Build the client modpack?" "$MODPACK_DEFAULT"; then
  # build-modpack.sh is baked into the modpack-builder image;
  # pack-build.sh is the host-side wrapper that runs it.
  run_script "Building modpack" "$SCRIPT_DIR/pack-build.sh"
fi

echo ""
CACHE_DEFAULT="Y"
if [[ -d "$PROJECT_DIR/cache/images" ]] && ls "$PROJECT_DIR/cache/images/"*.tar &>/dev/null 2>&1; then
  CACHE_DEFAULT="N"
  echo -e "  ${GREEN}✓${RESET} Cache already exists."
fi
if ask_yes_no "Cache Docker images + mod JARs for offline resilience?" "$CACHE_DEFAULT"; then
  run_script "Caching assets" "$SCRIPT_DIR/cache-assets.sh"
fi

# =============================================================================
#  Final 1Password Sync
# =============================================================================
if [[ $HAS_OP -eq 1 ]]; then
  banner "1Password Sync"
  step "Storing all secrets to 1Password"
  info "Every value is written then read back to verify integrity."
  echo ""

  OP_VERIFY_FAILURES=0
  op_store "HETZNER_API_TOKEN" "${HCLOUD_TOKEN:-}"
  op_store "DO_API_TOKEN" "${DO_API_TOKEN:-}"
  op_store "CLOUDFLARE_API_TOKEN" "${CLOUDFLARE_API_TOKEN:-}"
  op_store "CLOUDFLARE_ACCOUNT_ID" "${CLOUDFLARE_ACCOUNT_ID:-}"
  op_store "CLOUDFLARE_ZONE_ID" "${CLOUDFLARE_ZONE_ID:-}"
  op_store "CLOUDFLARE_TUNNEL_ID" "${CLOUDFLARE_TUNNEL_ID:-}"
  op_store "DISCORD_BOT_TOKEN" "${DISCORD_BOT_TOKEN:-}"
  op_store "DISCORD_CHANNEL_ID" "${DISCORD_CHANNEL_ID:-}"
  op_store "DISCORD_GUILD_ID" "${DISCORD_GUILD_ID:-}"
  op_store "DISCORD_WEBHOOK_URL" "${DISCORD_WEBHOOK_URL:-}"
  op_store "R2_ACCOUNT_ID" "${R2_ACCOUNT_ID:-}"
  op_store "R2_BUCKET" "${R2_BUCKET:-}"
  op_store "R2_ACCESS_KEY_ID" "${R2_ACCESS_KEY_ID:-}"
  op_store "R2_SECRET_ACCESS_KEY" "${R2_SECRET_ACCESS_KEY:-}"
  op_store "RESTIC_PASSWORD" "${RESTIC_PASSWORD:-}"
  op_store "DOMAIN" "${DOMAIN:-}"
  op_store "RCON_PASSWORD" "${RCON_PASSWORD:-}" "local"
  op_store "KUMA_PASSWORD" "${KUMA_PASSWORD:-}" "local"
  op_store "KUMA_UPTIME_CHECKS_API_KEY" "${KUMA_API_KEY:-}"
fi

# =============================================================================
#  Phase 18: Summary
# =============================================================================
banner "Setup Complete"

# --- 1Password verification summary ---
if [[ $HAS_OP -eq 1 ]]; then
  echo ""
  if [[ $OP_VERIFY_FAILURES -gt 0 ]]; then
    setup_warn "${OP_VERIFY_FAILURES} value(s) failed 1Password verification."
    info "Re-run setup or use: ./scripts/op-sync-env.sh to push .env > 1Password"
  else
    echo -e "  ${GREEN}✓${RESET} All secrets stored and verified in 1Password"
    info "To restore .env from 1Password: ./scripts/op-env.sh > .env"
  fi
  echo ""
fi

echo "What you've got:"
echo -e "  ${GREEN}✓${RESET} Credentials in 1Password (or .env)"
echo -e "  ${GREEN}✓${RESET} .env with all config and secrets"
echo -e "  ${GREEN}✓${RESET} Instance: ${COMPOSE_PROJECT_NAME} (Docker project name)"
echo ""

if [[ $IS_CLOUD -eq 1 ]]; then
  DEPLOY_USER="${DEPLOY_USER:-deploy}"
  echo "Quick reference:"
  echo ""
  echo "  Local server:     ./scripts/dev-up.sh"
  echo "  Provision:        ./scripts/provision.sh"
  echo "  Harden:           ./scripts/harden.sh --remote root@SERVER_IP"
  echo "  Prepare:          DROPLET_HOST=SERVER_IP ./scripts/prepare-droplet.sh"
  echo "  First deploy:     ssh ${DEPLOY_USER}@SERVER_IP 'cd ~/server && .stack/current/stack/scripts/initial-setup.sh'"
  echo "  Cloudflare:       ./scripts/cloudflare-setup.sh"
  echo "  GitHub wiring:    ./scripts/github-env-sync.sh   (verify: --check)"
  echo "  Build modpack:    ./dev pack"
  echo "  Teardown:         ./scripts/teardown.sh"
  echo ""
  echo "  After first deploy, pushes to main auto-deploy via GitHub Actions."
else
  echo "Quick reference:"
  echo ""
  echo "  Start server:     ./scripts/dev-up.sh"
  echo "  Stop server:      ./scripts/dev-up.sh --down"
  echo "  Watch logs:       ./scripts/dev-up.sh --logs"
  echo "  Roll seeds:       ./scripts/seed/roll-seeds.sh"
  echo "  Build modpack:    ./dev pack"
  echo "  Teardown:         ./scripts/teardown.sh --target local"
  echo ""
  LOCAL_HOST="${LOCAL_DOMAIN:-localhost}"
  echo "  Game server:      mc.${LOCAL_HOST}:${GAME_PORT:-25577}"
  echo "  Web services:     http://map.${LOCAL_HOST}:${WEB_PORT:-8080}"
  if [[ "$LOCAL_HOST" == "localhost" ]]; then
    echo ""
    echo "  Want subdomain access? Run setup.sh again to configure LOCAL_DOMAIN"
    echo "  and add /etc/hosts entries."
  fi
fi

echo ""
echo "Re-run this wizard any time to update settings: ${SELF_CMD}"
echo ""
echo -e "${BOLD}Done. Have fun.${RESET}"

# Clear the error trap on clean exit
trap - EXIT
