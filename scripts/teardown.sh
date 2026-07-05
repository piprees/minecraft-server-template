#!/usr/bin/env bash
# teardown.sh - Reverse of setup.sh. Removes resources with confirmation.
#
# Target-aware: only offers to delete resources relevant to your deployment
# target (local, hetzner, or digitalocean). Every destructive operation
# requires a double-confirm. Also cleans up /etc/hosts entries and the
# .setup-state directory created by setup.sh.
#
# Usage:
#   ./scripts/teardown.sh                    # auto-detect from CLOUD_PROVIDER
#   ./scripts/teardown.sh --target local
#   ./scripts/teardown.sh --target hetzner
#   ./scripts/teardown.sh --target digitalocean
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib.sh"
cd "$PROJECT_DIR"

# --- wizard UI helpers (match setup.sh style) ---------------------------------
DIM='\033[2m'
[[ ! -t 1 ]] && DIM=''

banner() {
  echo ""
  echo -e "${BOLD}${RED}═══════════════════════════════════════════════════${RESET}"
  echo -e "${BOLD}  $1${RESET}"
  echo -e "${BOLD}${RED}═══════════════════════════════════════════════════${RESET}"
  echo ""
}

step() {
  echo -e "\n${BOLD}${YELLOW}▸ $1${RESET}"
}

info() {
  echo -e "  ${YELLOW}$1${RESET}"
}

td_warn() {
  echo -e "  ${RED}⚠ $1${RESET}"
}

open_link() {
  local url="$1"
  echo -e "  ${BLUE}> ${url}${RESET}"
  if command -v open &> /dev/null; then
    open "$url" 2> /dev/null || true
  fi
}

confirm() {
  local message="$1"
  echo -ne "  ${RED}${message}${RESET} [y/N]: "
  read -r answer
  [[ "$answer" =~ ^[Yy] ]]
}

double_confirm() {
  local action="$1"
  local detail="${2:-}"
  if ! confirm "$action"; then
    echo "  Skipped."
    return 1
  fi
  if [[ -n "$detail" ]]; then
    td_warn "$detail"
  fi
  if ! confirm "Are you absolutely sure?"; then
    echo "  Skipped."
    return 1
  fi
  return 0
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

# --- load env for reference (three-file order) --------------------------------
if [[ -f "$PROJECT_DIR/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$PROJECT_DIR/.env"
  set +a
fi

# --- detect target from CLOUD_PROVIDER if not specified -----------------------
if [[ -z "$TARGET" ]]; then
  if [[ -n "${CLOUD_PROVIDER:-}" ]]; then
    TARGET="$CLOUD_PROVIDER"
  else
    echo "Cannot determine deployment target."
    echo ""
    echo "  1) Local"
    echo "  2) Hetzner"
    echo "  3) DigitalOcean"
    echo ""
    read -rp "  Which target to tear down? [1]: " choice
    case "${choice:-1}" in
      1) TARGET="local" ;;
      2) TARGET="hetzner" ;;
      3) TARGET="digitalocean" ;;
      *) TARGET="local" ;;
    esac
  fi
fi

IS_CLOUD=0
[[ "$TARGET" != "local" ]] && IS_CLOUD=1

# =============================================================================
banner "Teardown (${TARGET})"

td_warn "This script removes resources created by setup.sh and related scripts."
td_warn "Every destructive action requires double confirmation."
echo ""
echo -ne "  ${DIM}Press Enter to continue, or Ctrl+C to abort...${RESET}"
read -r

# =============================================================================
step "1. Stop Docker containers"
if docker compose ps --quiet 2> /dev/null | grep -q .; then
  echo "  Running containers detected."
  if double_confirm "Stop and remove containers + volumes?" "This destroys all Docker volumes (world data in volumes will be lost)."; then
    docker compose --profile local --profile cloud down -v 2> /dev/null || true
    echo -e "  ${GREEN}✓${RESET} Containers stopped and volumes removed"
  fi
else
  echo -e "  ${DIM}No running containers.${RESET}"
fi

# =============================================================================
# Cloud server deletion (target-specific)
# =============================================================================
if [[ "$TARGET" == "hetzner" ]]; then
  step "2. Delete Hetzner Cloud server"
  if command -v hcloud &> /dev/null; then
    SERVERS=$(hcloud server list -o columns=id,name,ipv4,status 2> /dev/null || true)
    if [[ -n "$SERVERS" ]] && echo "$SERVERS" | grep -q '[0-9]'; then
      echo "  Found servers:"
      echo "$SERVERS" | while IFS= read -r line; do echo "    $line"; done
      echo ""
      echo -ne "  Enter server name to delete (or Enter to skip): "
      read -r server_name
      if [[ -n "$server_name" ]]; then
        if double_confirm "DELETE Hetzner server '${server_name}'?" "This destroys the server and all its data permanently."; then
          hcloud server delete "$server_name"
          echo -e "  ${GREEN}✓${RESET} Server deleted"
        fi
      else
        echo "  Skipped."
      fi
    else
      echo -e "  ${DIM}No servers found.${RESET}"
    fi
  else
    info "hcloud CLI not available - delete the server manually if one was provisioned."
    open_link "https://console.hetzner.cloud"
  fi

elif [[ "$TARGET" == "digitalocean" ]]; then
  step "2. Delete DigitalOcean droplet"
  if command -v doctl &> /dev/null && doctl account get &> /dev/null 2>&1; then
    DROPLETS=$(doctl compute droplet list --format ID,Name,PublicIPv4 --no-header 2> /dev/null || true)
    if [[ -n "$DROPLETS" ]]; then
      echo "  Found droplets:"
      echo "$DROPLETS" | while IFS= read -r line; do echo "    $line"; done
      echo ""
      echo -ne "  Enter droplet ID to delete (or Enter to skip): "
      read -r droplet_id
      if [[ -n "$droplet_id" ]]; then
        if double_confirm "DELETE droplet ${droplet_id}?" "This destroys the server and all its data permanently."; then
          doctl compute droplet delete "$droplet_id" --force
          echo -e "  ${GREEN}✓${RESET} Droplet deleted"
        fi
      else
        echo "  Skipped."
      fi
    else
      echo -e "  ${DIM}No droplets found.${RESET}"
    fi
  else
    info "doctl not available - delete the droplet manually if one was provisioned."
    open_link "https://cloud.digitalocean.com/droplets"
  fi
else
  echo ""
  echo -e "  ${DIM}Local target - no cloud server to delete.${RESET}"
fi

# =============================================================================
if [[ $IS_CLOUD -eq 1 ]]; then
  step "3. Delete R2 backup bucket"
  if [[ -n "${CLOUDFLARE_API_TOKEN:-}" && -n "${R2_ACCOUNT_ID:-}" && -n "${R2_BUCKET:-}" ]]; then
    echo "  Bucket: ${R2_BUCKET}"

    if double_confirm "DELETE R2 bucket '${R2_BUCKET}' and ALL backup data?" "This is irreversible. All restic backups will be permanently lost."; then
      if command -v restic &> /dev/null && [[ -n "${RESTIC_PASSWORD:-}" && -n "${R2_ACCESS_KEY_ID:-}" ]]; then
        echo "  Clearing restic snapshots..."
        RESTIC_REPOSITORY="s3:https://${R2_ACCOUNT_ID}.r2.cloudflarestorage.com/${R2_BUCKET}" \
          AWS_ACCESS_KEY_ID="$R2_ACCESS_KEY_ID" \
          AWS_SECRET_ACCESS_KEY="${R2_SECRET_ACCESS_KEY:-}" \
          RESTIC_PASSWORD="$RESTIC_PASSWORD" \
          restic forget --keep-last 0 --prune 2> /dev/null || true
      fi

      echo "  Deleting bucket..."
      HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X DELETE \
        "https://api.cloudflare.com/client/v4/accounts/${R2_ACCOUNT_ID}/r2/buckets/${R2_BUCKET}" \
        -H "Authorization: Bearer ${CLOUDFLARE_API_TOKEN}" 2> /dev/null || echo "000")

      if [[ "$HTTP_CODE" == "200" ]]; then
        echo -e "  ${GREEN}✓${RESET} Bucket deleted"
      else
        td_warn "Bucket deletion returned HTTP ${HTTP_CODE}."
        info "The bucket may not be empty. Delete manually:"
        open_link "https://dash.cloudflare.com/?to=/:account/r2/default/buckets/${R2_BUCKET}"
      fi
    fi
  else
    info "Missing credentials - delete R2 bucket manually if one was created."
    open_link "https://dash.cloudflare.com/?to=/:account/r2/overview"
  fi

  # ===========================================================================
  step "4. Delete Cloudflare tunnel and DNS records"
  info "Tunnels and DNS records are created by cloudflare-setup.sh, not setup.sh."
  info "If you ran cloudflare-setup.sh, delete these manually:"
  info "  1. Tunnel: Dashboard > Zero Trust > Networks > Tunnels > delete '${CLOUDFLARE_TUNNEL_NAME:-mc-${BRAND_SLUG:-adventure}}'"
  info "  2. DNS records: Dashboard > ${DOMAIN:-your domain} > DNS > delete mc, map, pack, status, mods"
  open_link "https://one.dash.cloudflare.com/?to=/:account/networks/tunnels"
  echo ""
  echo -ne "  ${DIM}Press Enter when done (or skip)...${RESET}"
  read -r
fi

# =============================================================================
step "5. Credentials (NOT touched)"
# Teardown destroys INSTANCE resources only. Account-level credentials -
# the deploy SSH key, API tokens, and the 1Password item - are shared
# across server repos and survive teardown by design. Deleting them here
# is how credentials kept "mysteriously dying" between test cycles.
info "Deploy key (~/.ssh/mc_deploy_key), API tokens, and the 1Password item are preserved."
info "Revoke them manually ONLY if you are abandoning the account entirely (see docs/credentials.md)."

# =============================================================================
# Provider CLI auth cleanup
# =============================================================================
if [[ "$TARGET" == "digitalocean" ]]; then
  step "6. Remove doctl auth context"
  if command -v doctl &> /dev/null; then
    if doctl auth list 2> /dev/null | grep -q minecraft; then
      if confirm "Remove doctl 'minecraft' auth context?"; then
        doctl auth remove --context minecraft 2> /dev/null || true
        echo -e "  ${GREEN}✓${RESET} doctl context removed"
      else
        echo "  Skipped."
      fi
    else
      echo -e "  ${DIM}No 'minecraft' doctl context found.${RESET}"
    fi
  fi
elif [[ "$TARGET" == "hetzner" ]]; then
  step "6. Remove hcloud context"
  if command -v hcloud &> /dev/null; then
    CONTEXTS=$(hcloud context list -o columns=name 2> /dev/null || true)
    if [[ -n "$CONTEXTS" ]] && echo "$CONTEXTS" | grep -q '[a-z]'; then
      echo "  Active hcloud contexts:"
      echo "$CONTEXTS" | while IFS= read -r line; do echo "    $line"; done
      echo ""
      if confirm "Remove active hcloud context?"; then
        ACTIVE=$(hcloud context active 2> /dev/null || true)
        if [[ -n "$ACTIVE" ]]; then
          hcloud context delete "$ACTIVE" 2> /dev/null || true
          echo -e "  ${GREEN}✓${RESET} hcloud context '${ACTIVE}' removed"
        fi
      else
        echo "  Skipped."
      fi
    else
      echo -e "  ${DIM}No hcloud contexts found.${RESET}"
    fi
  fi
fi

# =============================================================================
step "8. Clean local data directories"
info "Local config files (.env) are NOT touched by teardown."
info "They contain your credentials and settings - delete manually if needed."
echo ""
echo "  These directories contain world data, mods, and cached assets:"
echo "    data/   - world data, server state, downloaded mods"
echo "    cache/  - Docker image tarballs, mod JARs, offline bundles"
for dir in data cache; do
  if [[ -d "$PROJECT_DIR/$dir" ]]; then
    SIZE=$(du -sh "$PROJECT_DIR/$dir" 2> /dev/null | cut -f1 || echo "unknown")
    echo ""
    echo "  $dir/ - ${SIZE}"
    if double_confirm "Delete $dir/?" "All files in $dir/ will be permanently removed."; then
      rm -rf "${PROJECT_DIR:?}/$dir"
      echo -e "  ${GREEN}✓${RESET} $dir/ removed"
    fi
  fi
done

# =============================================================================
step "9. Remove /etc/hosts entries"
HOSTS_MARKER_BEGIN="# BEGIN minecraft-${BRAND_SLUG:-adventure}"
HOSTS_MARKER_END="# END minecraft-${BRAND_SLUG:-adventure}"
if grep -q "$HOSTS_MARKER_BEGIN" /etc/hosts 2>/dev/null; then
  echo "  Found entries between ${HOSTS_MARKER_BEGIN}...${HOSTS_MARKER_END}"
  if double_confirm "Remove /etc/hosts entries? (requires sudo)"; then
    if [[ "$(uname)" == "Darwin" ]]; then
      sudo sed -i '' "/${HOSTS_MARKER_BEGIN}/,/${HOSTS_MARKER_END}/d" /etc/hosts
    else
      sudo sed -i "/${HOSTS_MARKER_BEGIN}/,/${HOSTS_MARKER_END}/d" /etc/hosts
    fi
    echo -e "  ${GREEN}✓${RESET} /etc/hosts entries removed"
  fi
else
  echo -e "  ${DIM}No managed /etc/hosts entries found.${RESET}"
fi

# =============================================================================
step "10. Clean up setup state"
if [[ -d "$PROJECT_DIR/.setup-state" ]]; then
  echo "  Found .setup-state/ directory (tool decline tracking)."
  if confirm "Remove .setup-state/?"; then
    rm -rf "$PROJECT_DIR/.setup-state"
    echo -e "  ${GREEN}✓${RESET} .setup-state/ removed"
  fi
else
  echo -e "  ${DIM}No .setup-state/ directory.${RESET}"
fi

# =============================================================================
banner "Credentials Kept (revoke ONLY if abandoning the project)"

echo "Teardown preserves account-level credentials so the next spin-up just works."
echo "If you are walking away from this project for good, revoke these by hand:"
echo ""

if [[ "$TARGET" == "hetzner" ]]; then
  echo -e "  ${YELLOW}□${RESET} Hetzner Cloud API token"
  echo -e "    ${BLUE}> https://console.hetzner.cloud${RESET}"
  echo ""
elif [[ "$TARGET" == "digitalocean" ]]; then
  echo -e "  ${YELLOW}□${RESET} DigitalOcean API token"
  echo -e "    ${BLUE}> https://cloud.digitalocean.com/account/api/tokens${RESET}"
  echo ""
fi

if [[ $IS_CLOUD -eq 1 ]]; then
  echo -e "  ${YELLOW}□${RESET} Cloudflare API token"
  echo -e "    ${BLUE}> https://dash.cloudflare.com/profile/api-tokens${RESET}"
  echo ""
  echo -e "  ${YELLOW}□${RESET} Cloudflare R2 API token (S3 credentials)"
  echo -e "    ${BLUE}> https://dash.cloudflare.com/?to=/:account/r2/api-tokens${RESET}"
  echo ""
fi

echo -e "  ${YELLOW}□${RESET} Discord bot / application"
echo -e "    ${BLUE}> https://discord.com/developers/applications${RESET}"
echo ""
echo -e "  ${YELLOW}□${RESET} Discord webhook"
echo -e "    Channel > Edit > Integrations > Webhooks > delete"
echo ""

if [[ $IS_CLOUD -eq 1 ]]; then
  echo -e "  ${YELLOW}□${RESET} GitHub deploy key & environment secrets"
  echo -e "    Repo > Settings > Deploy keys / Environments > production"
  echo ""
  echo -e "  ${YELLOW}□${RESET} GitHub repository (if you want to delete it entirely)"
  echo -e "    Repo > Settings > Danger Zone > Delete this repository"
  echo ""
fi

if [[ "$TARGET" == "local" ]]; then
  echo -e "  ${YELLOW}□${RESET} /etc/hosts entries (if not removed in step 9 above)"
  echo ""
fi

echo -e "${BOLD}Teardown complete.${RESET} Credentials were preserved - the next ./ops provision reuses them as-is."
