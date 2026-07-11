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
  # Use the kuma-init image (already on the server, has uptime-kuma-api) —
  # pulling python:alpine + pip install used to burn the 30s TOTP window.
  # The compose network is named after COMPOSE_PROJECT_NAME, not "server",
  # so resolve it from the running mc container instead of hardcoding.
  TOKEN=$(ssh -i "$KEY" "deploy@${HOST}" "
    NET=\$(docker inspect mc --format '{{range \$k, \$v := .NetworkSettings.Networks}}{{\$k}}{{end}}' | head -1)
    IMG=\$(docker inspect kuma-init --format '{{.Config.Image}}' 2>/dev/null || echo ghcr.io/piprees/minecraft-server-template/kuma-init:latest)
    docker run --rm --network \"\$NET\" --entrypoint python3 \"\$IMG\" -c \"
from uptime_kuma_api import UptimeKumaApi
api = UptimeKumaApi(\\\"http://uptime-kuma:3001\\\")
result = api.login(\\\"${KUMA_USERNAME:-admin}\\\", \\\"${KUMA_PASSWORD}\\\", \\\"${TOTP_CODE}\\\")
print(result[\\\"token\\\"])
api.disconnect()
\"
  ")

  if [[ -z "$TOKEN" || "$TOKEN" == *"Error"* ]]; then
    echo "ERROR: Login failed. Check your TOTP code and try again."
    exit 1
  fi

  echo "Session token obtained."

  # Save locally — APPEND when the line doesn't exist: a consumer .env
  # without a KUMA_API_KEY line made the old sed a silent no-op, so the
  # token never reached GitHub and CI deploys kept wiping it (2026-07-11).
  if grep -q '^KUMA_API_KEY=' "$PROJECT_DIR/.env"; then
    sed -i '' "s|^KUMA_API_KEY=.*|KUMA_API_KEY=${TOKEN}|" "$PROJECT_DIR/.env" 2>/dev/null \
      || sed -i "s|^KUMA_API_KEY=.*|KUMA_API_KEY=${TOKEN}|" "$PROJECT_DIR/.env"
  else
    printf 'KUMA_API_KEY=%s\n' "$TOKEN" >> "$PROJECT_DIR/.env"
  fi
  echo "  Updated local .env"

  # Save on server (same append-if-missing guard)
  ssh -i "$KEY" "deploy@${HOST}" "cd ~/${SERVER_DIR} && if grep -q '^KUMA_API_KEY=' .env; then sed -i 's|^KUMA_API_KEY=.*|KUMA_API_KEY=${TOKEN}|' .env; else printf 'KUMA_API_KEY=%s\n' '${TOKEN}' >> .env; fi"
  echo "  Updated server .env"

  echo ""
  echo "IMPORTANT: push the token to the GitHub environment or the next full"
  echo "CI deploy will wipe it from the server again:"
  echo "  ./ops github-env-sync"

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
