#!/usr/bin/env bash
# op-sync-env.sh - Push local .env secrets into 1Password (Dev vault,
# "Minecraft Server" item) for backup/recovery. The inverse of op-env.sh.
#
# Field names deliberately match the op:// references in config/1password.env
# so op-env.sh can rebuild .env without any manual mapping. Run after adding
# or rotating a secret locally; creates the vault item if missing.
#
# Usage:
#   ./scripts/op-sync-env.sh             # requires `op` CLI, signed in
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="${CONSUMER_DIR:-$(cd "$SCRIPT_DIR/.." && pwd)}"

# One 1Password item PER SERVER (brand) - a shared item name across repos
# means one repo's op-sync clobbers another's freshly rotated credentials.
# Override with OP_VAULT / OP_ITEM_NAME in .env.
if [[ -f "$PROJECT_DIR/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$PROJECT_DIR/.env"
  set +a
fi
VAULT_NAME="${OP_VAULT:-Dev}"
ITEM_NAME="${OP_ITEM_NAME:-Minecraft Server${BRAND_SLUG:+ - ${BRAND_SLUG}}}"

RED='\033[0;31m'
GREEN='\033[0;32m'
BOLD='\033[1m'
RESET='\033[0m'

if ! command -v op &> /dev/null || ! op account list &> /dev/null 2>&1; then
  echo -e "${RED}1Password CLI not available or not signed in.${RESET}"
  exit 1
fi

if [[ ! -f "$PROJECT_DIR/.env" ]]; then
  echo -e "${RED}.env not found at $PROJECT_DIR/.env${RESET}"
  exit 1
fi

set -a
# shellcheck disable=SC1091
# shellcheck disable=SC1091
# shellcheck disable=SC1091
source "$PROJECT_DIR/.env"
set +a

# Create item if missing
if ! op item get "$ITEM_NAME" --vault "$VAULT_NAME" &> /dev/null 2>&1; then
  echo "Creating 1Password item '${ITEM_NAME}' in vault '${VAULT_NAME}'..."
  op item create --category=server --vault "$VAULT_NAME" \
    --title "$ITEM_NAME" \
    "local.RCON_PASSWORD=$(head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)" \
    "local.ONLINE_MODE=FALSE" \
    "prod.RCON_PASSWORD=$(head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)" \
    "prod.ONLINE_MODE=TRUE"
  echo -e "${GREEN}✓${RESET} Created with auto-generated RCON passwords."
fi

sync_field() {
  local field="$1"
  local value="$2"
  if [[ -z "$value" ]]; then
    echo "  SKIP  $field (empty)"
    return
  fi
  if op item edit "$ITEM_NAME" --vault "$VAULT_NAME" "${field}=${value}" > /dev/null 2>&1; then
    echo -e "  ${GREEN}✓${RESET}  $field"
  else
    echo -e "  ${RED}✗${RESET}  $field (op item edit failed)"
  fi
}

echo ""
echo -e "${BOLD}Syncing .env secrets > 1Password (${VAULT_NAME}/${ITEM_NAME})${RESET}"
echo ""

# Field names must match config/1password.env op:// references so that
# op-env.sh can restore .env from 1Password without manual mapping.

# --- Cloud provider tokens ---
sync_field "HETZNER_API_TOKEN" "${HCLOUD_TOKEN:-${HETZNER_API_TOKEN:-}}"
sync_field "DO_API_TOKEN" "${DO_API_TOKEN:-}"

# --- Cloudflare ---
sync_field "CLOUDFLARE_API_TOKEN" "${CLOUDFLARE_API_TOKEN:-}"
sync_field "CLOUDFLARE_ACCOUNT_ID" "${CLOUDFLARE_ACCOUNT_ID:-}"
sync_field "CLOUDFLARE_ZONE_ID" "${CLOUDFLARE_ZONE_ID:-}"
sync_field "CLOUDFLARE_TUNNEL_ID" "${CLOUDFLARE_TUNNEL_ID:-}"

# --- Discord ---
sync_field "DISCORD_BOT_TOKEN" "${DISCORD_BOT_TOKEN:-}"
sync_field "DISCORD_CHANNEL_ID" "${DISCORD_CHANNEL_ID:-}"
sync_field "DISCORD_CHAT_CHANNEL_ID" "${DISCORD_CHAT_CHANNEL_ID:-}"
sync_field "DISCORD_GUILD_ID" "${DISCORD_GUILD_ID:-}"
sync_field "DISCORD_WEBHOOK_URL" "${DISCORD_WEBHOOK_URL:-}"
sync_field "DISCORD_ADMIN_ROLE_ID" "${DISCORD_ADMIN_ROLE_ID:-}"
sync_field "DISCORD_PLAYER_ROLE_ID" "${DISCORD_PLAYER_ROLE_ID:-}"
sync_field "DISCORD_BOT_ROLE_ID" "${DISCORD_BOT_ROLE_ID:-}"
sync_field "DISCORD_WELCOME_CHANNEL_ID" "${DISCORD_WELCOME_CHANNEL_ID:-}"
sync_field "DISCORD_WELCOME_MESSAGE_ID" "${DISCORD_WELCOME_MESSAGE_ID:-}"
sync_field "DISCORD_INVITE_URL" "${DISCORD_INVITE_URL:-}"

# --- R2 / Backups ---
sync_field "R2_ACCOUNT_ID" "${R2_ACCOUNT_ID:-}"
sync_field "R2_BUCKET" "${R2_BUCKET:-}"
sync_field "R2_ACCESS_KEY_ID" "${R2_ACCESS_KEY_ID:-}"
sync_field "R2_SECRET_ACCESS_KEY" "${R2_SECRET_ACCESS_KEY:-}"
sync_field "RESTIC_PASSWORD" "${RESTIC_PASSWORD:-}"

# --- Per-environment secrets (stored in "local" section) ---
sync_field "local.RCON_PASSWORD" "${RCON_PASSWORD:-}"
sync_field "local.KUMA_PASSWORD" "${KUMA_PASSWORD:-}"

# --- Kuma (field name matches 1password.env op:// reference) ---
sync_field "KUMA_UPTIME_CHECKS_API_KEY" "${KUMA_API_KEY:-}"

# --- Non-secret config (useful for full recovery from 1Password alone) ---
sync_field "DOMAIN" "${DOMAIN:-}"
sync_field "BRAND_NAME" "${BRAND_NAME:-}"
sync_field "BRAND_SLUG" "${BRAND_SLUG:-}"
sync_field "SEED" "${SEED:-}"
sync_field "SPAWN_X" "${SPAWN_X:-}"
sync_field "SPAWN_Y" "${SPAWN_Y:-}"
sync_field "SPAWN_Z" "${SPAWN_Z:-}"
sync_field "KUMA_USERNAME" "${KUMA_USERNAME:-}"

echo ""
echo -e "${GREEN}Done.${RESET} Verify with: op item get \"${ITEM_NAME}\" --vault \"${VAULT_NAME}\""
