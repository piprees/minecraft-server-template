#!/usr/bin/env bash
# ddns-update.sh - Update Cloudflare DNS A record with the current public IP.
#
# For LOCAL HOSTING ONLY. Cloud servers (Hetzner, DigitalOcean) have static
# IPs and don't need this.
#
# Checks the current public IP against the Cloudflare A record for DOMAIN.
# Updates it if changed. Run as a cron job or systemd timer.
#
# Usage:
#   ./scripts/ddns-update.sh                 # one-shot update
#   ./scripts/ddns-update.sh --install-cron  # install a cron job (every 5 min)
#
# Required env vars (from .env):
#   CLOUDFLARE_API_TOKEN  - API token with DNS:Edit permission for the zone
#   DOMAIN                - e.g. example.com
#
# Optional:
#   DDNS_HOSTNAME         - subdomain to update (default: mc.DOMAIN)
#   DDNS_LOG              - log file path (default: /var/log/ddns-update.log)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="${CONSUMER_DIR:-$(cd "$SCRIPT_DIR/.." && pwd)}"

# --- load env -----------------------------------------------------------------
if [[ -f "$PROJECT_DIR/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$PROJECT_DIR/.env"
  set +a
fi
if [[ -f "$PROJECT_DIR/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$PROJECT_DIR/.env"
  set +a
fi

: "${CLOUDFLARE_API_TOKEN:?Set CLOUDFLARE_API_TOKEN in .env}"
: "${DOMAIN:?Set DOMAIN in .env}"

HOSTNAME="${DDNS_HOSTNAME:-mc.${DOMAIN}}"
LOG="${DDNS_LOG:-/var/log/ddns-update.log}"
CF_API="https://api.cloudflare.com/client/v4"

log() {
  echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" | tee -a "$LOG"
}

# --- install cron mode --------------------------------------------------------
if [[ "${1:-}" == "--install-cron" ]]; then
  CRON_CMD="*/5 * * * * cd $PROJECT_DIR && ./scripts/ddns-update.sh >> $LOG 2>&1"
  if crontab -l 2>/dev/null | grep -qF 'ddns-update.sh'; then
    echo "Cron job already exists:"
    crontab -l | grep 'ddns-update'
  else
    (crontab -l 2>/dev/null; echo "$CRON_CMD") | crontab -
    echo "Installed cron job (every 5 minutes):"
    echo "  $CRON_CMD"
  fi
  exit 0
fi

# --- get current public IP ----------------------------------------------------
CURRENT_IP=$(curl -s --max-time 10 https://ifconfig.me || curl -s --max-time 10 https://api.ipify.org || true)
if [[ -z "$CURRENT_IP" || ! "$CURRENT_IP" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  log "ERROR: Could not determine public IP"
  exit 1
fi

# --- get zone ID --------------------------------------------------------------
ZONE_ID=$(curl -s -X GET "$CF_API/zones?name=$DOMAIN" \
  -H "Authorization: Bearer $CLOUDFLARE_API_TOKEN" \
  -H "Content-Type: application/json" \
  | python3 -c "import sys,json; r=json.load(sys.stdin); print(r['result'][0]['id'] if r['result'] else '')" 2>/dev/null)

if [[ -z "$ZONE_ID" ]]; then
  log "ERROR: Could not find Cloudflare zone for $DOMAIN"
  exit 1
fi

# --- get existing A record ----------------------------------------------------
RECORD_DATA=$(curl -s -X GET "$CF_API/zones/$ZONE_ID/dns_records?type=A&name=$HOSTNAME" \
  -H "Authorization: Bearer $CLOUDFLARE_API_TOKEN" \
  -H "Content-Type: application/json")

RECORD_ID=$(echo "$RECORD_DATA" | python3 -c "import sys,json; r=json.load(sys.stdin); print(r['result'][0]['id'] if r['result'] else '')" 2>/dev/null)
RECORD_IP=$(echo "$RECORD_DATA" | python3 -c "import sys,json; r=json.load(sys.stdin); print(r['result'][0]['content'] if r['result'] else '')" 2>/dev/null)

if [[ -z "$RECORD_ID" ]]; then
  log "ERROR: No A record found for $HOSTNAME - create it manually first"
  exit 1
fi

# --- compare and update if needed ---------------------------------------------
if [[ "$CURRENT_IP" == "$RECORD_IP" ]]; then
  # IP unchanged - no output unless debugging (keeps cron logs clean)
  exit 0
fi

log "IP changed: $RECORD_IP > $CURRENT_IP"

RESULT=$(curl -s -X PATCH "$CF_API/zones/$ZONE_ID/dns_records/$RECORD_ID" \
  -H "Authorization: Bearer $CLOUDFLARE_API_TOKEN" \
  -H "Content-Type: application/json" \
  --data "{\"content\":\"$CURRENT_IP\"}")

SUCCESS=$(echo "$RESULT" | python3 -c "import sys,json; print(json.load(sys.stdin).get('success', False))" 2>/dev/null)

if [[ "$SUCCESS" == "True" ]]; then
  log "Updated $HOSTNAME > $CURRENT_IP"
else
  log "ERROR: Failed to update DNS record"
  log "$RESULT"
  exit 1
fi
