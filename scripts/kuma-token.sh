#!/usr/bin/env bash
# kuma-token.sh - Generate a Kuma session token for automated provisioning.
#
# Uptime Kuma's management API is socket.io only. With 2FA enabled, you need
# a session token (not the Prometheus API key from Settings > API Keys).
# This script logs in with your TOTP code and saves the resulting session
# token as KUMA_API_KEY in .env.
#
# Usage:
#   ./scripts/kuma-token.sh              # interactive (prompts for TOTP)
#   ./scripts/kuma-token.sh --remote     # runs on the production server
#
# Run this once after enabling 2FA, or whenever the token expires.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="${CONSUMER_DIR:-$(cd "$SCRIPT_DIR/.." && pwd)}"

if [[ "${1:-}" == "--remote" ]]; then
  source "$PROJECT_DIR/.env" 2>/dev/null || true
  source "$PROJECT_DIR/.env" 2>/dev/null || true
  HOST="${DROPLET_HOST:?Set DROPLET_HOST in .env}"
  KEY="${HOME}/.ssh/mc_deploy_key"

  read -rp "TOTP code: " TOTP_CODE
  SERVER_DIR="server"
  TOKEN=$(ssh -i "$KEY" "deploy@${HOST}" "
    docker run --rm --network ${SERVER_DIR}_default python:3.13-alpine sh -c '
      pip install -q uptime-kuma-api 2>/dev/null
      python3 -c \"
from uptime_kuma_api import UptimeKumaApi
api = UptimeKumaApi(\\\"http://uptime-kuma:3001\\\")
result = api.login(\\\"${KUMA_USERNAME:-admin}\\\", \\\"${KUMA_PASSWORD}\\\", \\\"${TOTP_CODE}\\\")
print(result[\\\"token\\\"])
api.disconnect()
\"'
  ")

  if [[ -z "$TOKEN" || "$TOKEN" == *"Error"* ]]; then
    echo "ERROR: Login failed. Check your TOTP code and try again."
    exit 1
  fi

  echo "Session token obtained."

  # Save locally
  sed -i '' "s|KUMA_API_KEY=.*|KUMA_API_KEY=${TOKEN}|" "$PROJECT_DIR/.env" 2>/dev/null \
    || sed -i "s|KUMA_API_KEY=.*|KUMA_API_KEY=${TOKEN}|" "$PROJECT_DIR/.env"
  echo "  Updated local .env"

  # Save on server
  ssh -i "$KEY" "deploy@${HOST}" "cd ~/${SERVER_DIR} && sed -i 's|KUMA_API_KEY=.*|KUMA_API_KEY=${TOKEN}|' .env"
  echo "  Updated server .env"

  echo ""
  echo "Done. Restart kuma-init to apply:"
  echo "  ssh -i ~/.ssh/mc_deploy_key deploy@${HOST} 'cd ~/${SERVER_DIR} && docker compose stop kuma-init && docker compose rm -f kuma-init && docker compose --profile cloud up -d kuma-init'"

else
  echo "Usage:"
  echo "  $0 --remote    Log in via the production server"
  echo ""
  echo "This generates a Kuma socket.io session token for automated provisioning."
  echo "The Prometheus API keys from Settings > API Keys don't work for this -"
  echo "Kuma's management API is socket.io only."
fi
