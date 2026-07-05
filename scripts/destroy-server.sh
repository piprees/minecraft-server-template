#!/usr/bin/env bash
# destroy-server.sh - Delete the Hetzner server and its firewall.
#
# Useful during development/testing when you need a clean slate. Does NOT
# touch DNS records, Cloudflare tunnels, R2 buckets, or local config.
#
# Usage:
#   ./scripts/destroy-server.sh           # interactive (double-confirm)
#   ./scripts/destroy-server.sh --force   # skip confirmation (CI/scripting)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="${CONSUMER_DIR:-$(cd "$SCRIPT_DIR/.." && pwd)}"
cd "$PROJECT_DIR"

if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi

HCLOUD_TOKEN="${HCLOUD_TOKEN:-${HETZNER_API_TOKEN:-}}"
: "${HCLOUD_TOKEN:?Set HCLOUD_TOKEN in .env}"
export HCLOUD_TOKEN

BRAND_SLUG="${BRAND_SLUG:-adventure}"
SERVER_NAME="${HCLOUD_SERVER_NAME:-mc-${BRAND_SLUG}}"
FW_NAME="${SERVER_NAME}-fw"

if ! command -v hcloud &>/dev/null; then
  echo "hcloud CLI not found."
  exit 1
fi

SERVER_IP=$(hcloud server ip "$SERVER_NAME" 2>/dev/null || true)
if [[ -z "$SERVER_IP" ]]; then
  echo "No server named '$SERVER_NAME' found. Nothing to delete."
  exit 0
fi

echo ""
echo "  Server:   $SERVER_NAME"
echo "  IP:       $SERVER_IP"
echo "  Firewall: $FW_NAME"
echo ""

if [[ "${1:-}" != "--force" ]]; then
  echo "  This will PERMANENTLY DELETE the server and all its data."
  echo "  Backups in R2 are NOT affected."
  echo ""
  read -rp "  Type the server name to confirm [$SERVER_NAME]: " CONFIRM
  if [[ "$CONFIRM" != "$SERVER_NAME" ]]; then
    echo "  Aborted."
    exit 1
  fi
  read -rp "  Are you sure? [y/N]: " CONFIRM2
  if [[ ! "$CONFIRM2" =~ ^[Yy]$ ]]; then
    echo "  Aborted."
    exit 1
  fi
fi

echo ""
echo "  Deleting server '$SERVER_NAME'..."
hcloud server delete "$SERVER_NAME"
echo "  Server deleted."

# Clean up stale host key
ssh-keygen -R "$SERVER_IP" 2>/dev/null || true

echo ""
echo "  Done. Re-provision with: ./scripts/setup.sh"
