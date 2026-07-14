#!/usr/bin/env bash
# server-power.sh - Power on, shut down, or reboot the cloud server.
#
# Usage (via ops):
#   ./ops shutdown              # graceful OS shutdown, then power off
#   ./ops startup               # power on a stopped server
#   ./ops reboot                # graceful reboot
#
# Detects the cloud provider from .env (Hetzner or DigitalOcean) and
# uses its API to manage the server's power state. Local-only setups
# (no cloud token) are told to use ./ops stop/start instead.
#
# Hetzner: uses hcloud CLI (server name = mc-${BRAND_SLUG})
# DigitalOcean: uses doctl CLI (droplet name = mc-${BRAND_SLUG})
#
# Shutdown is graceful (OS-level shutdown, then power off) — not a
# hard power cut. Docker containers stop cleanly, the world saves.
# Startup waits for the server to become SSH-reachable before returning.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib.sh"
load_env

ACTION="${1:-}"
show_banner "${ACTION:-server-power}" "cloud VPS power management"

usage() {
  echo "Usage: server-power.sh <shutdown|startup|reboot>"
  echo ""
  echo "  shutdown   Graceful shutdown + power off (saves costs)"
  echo "  startup    Power on a stopped server"
  echo "  reboot     Graceful reboot"
  echo ""
  echo "This controls the cloud VPS itself, not Docker containers."
  echo "For individual services, use: ./ops stop|start|restart <service>"
}

if [[ -z "$ACTION" || "$ACTION" == "help" || "$ACTION" == "--help" ]]; then
  usage
  exit 0
fi

PROVIDER=$(detect_provider)

if [[ "$PROVIDER" == "local" ]]; then
  echo "No cloud provider detected (no HCLOUD_TOKEN or DO_API_TOKEN in .env)."
  echo "For local Docker stack control, use:"
  echo "  ./ops stop mc          # stop the game server"
  echo "  ./ops start mc         # start the game server"
  echo "  ./dev down             # stop the whole local stack"
  echo "  ./dev up               # start the whole local stack"
  exit 1
fi

require_provider_cli "$PROVIDER"

SERVER_NAME="${HCLOUD_SERVER_NAME:-mc-${BRAND_SLUG:-adventure}}"

# --- SSH + RCON helpers (runs from Mac, targets the server) -------------------
: "${DEPLOY_USER:=deploy}"
SSH_KEY="$HOME/.ssh/${BRAND_SLUG:+${BRAND_SLUG}_}mc_deploy_key"

ssh_cmd() {
  ssh -o ConnectTimeout=5 -o StrictHostKeyChecking=no \
    -i "$SSH_KEY" "${DEPLOY_USER}@${DROPLET_HOST:-}" "$@" 2>/dev/null
}

remote_rcon() {
  ssh_cmd "docker exec mc rcon-cli '$1'" 2>/dev/null || true
}

remote_player_count() {
  local result
  result=$(ssh_cmd "docker exec mc rcon-cli 'list'" 2>/dev/null || echo "")
  echo "$result" | grep -oE 'There are [0-9]+' | grep -oE '[0-9]+' || echo "-1"
}

# In-game countdown before shutdown/reboot. Skips if no players online
# or the server isn't reachable (paused/off). Messages go to Discord
# via the chat bridge.
countdown_and_save() {
  local reason="$1"
  if [[ -z "${DROPLET_HOST:-}" ]]; then
    return
  fi
  local count
  count=$(remote_player_count)
  if [[ "$count" == "-1" ]]; then
    echo "  Server not responding to RCON (paused or already down), skipping countdown."
    return
  fi
  if [[ "$count" == "0" ]]; then
    echo "  No players online — skipping countdown."
    remote_rcon "say Server $reason in 10 seconds..."
    sleep 10
  else
    echo "  $count player(s) online — sending countdown..."
    remote_rcon "say Server $reason in 60 seconds..."
    sleep 30
    remote_rcon "say Server $reason in 30 seconds..."
    sleep 20
    remote_rcon "say Server $reason in 10 seconds..."
    sleep 5
    remote_rcon "say Server $reason in 5..."
    sleep 2
    remote_rcon "say 3..."
    sleep 1
    remote_rcon "say 2..."
    sleep 1
    remote_rcon "say 1..."
    sleep 1
  fi
  echo "  Saving world..."
  remote_rcon "save-all flush"
  sleep 3
}

case "$PROVIDER" in
  hetzner)
    export HCLOUD_TOKEN="${HCLOUD_TOKEN:-${HETZNER_API_TOKEN:-}}"
    : "${HCLOUD_TOKEN:?Set HCLOUD_TOKEN or HETZNER_API_TOKEN in .env}"

    STATUS=$(hcloud server describe "$SERVER_NAME" -o format='{{.Status}}' 2>/dev/null || echo "unknown")

    case "$ACTION" in
      shutdown)
        if [[ "$STATUS" == "off" ]]; then
          echo "Server '$SERVER_NAME' is already off."
          exit 0
        fi
        countdown_and_save "shutting down"
        echo "Shutting down '$SERVER_NAME' (status: $STATUS)..."
        hcloud server shutdown "$SERVER_NAME"
        echo "Waiting for power off..."
        for i in $(seq 1 60); do
          S=$(hcloud server describe "$SERVER_NAME" -o format='{{.Status}}' 2>/dev/null)
          if [[ "$S" == "off" ]]; then
            echo "Server powered off (took ${i}0s)."
            exit 0
          fi
          sleep 10
        done
        warn "Server didn't reach 'off' state within 10 minutes."
        echo "Current status: $(hcloud server describe "$SERVER_NAME" -o format='{{.Status}}')"
        exit 1
        ;;
      startup)
        if [[ "$STATUS" == "running" ]]; then
          echo "Server '$SERVER_NAME' is already running."
          exit 0
        fi
        echo "Starting '$SERVER_NAME' (status: $STATUS)..."
        hcloud server poweron "$SERVER_NAME"
        echo "Waiting for server to become reachable..."
        SERVER_IP=$(hcloud server ip "$SERVER_NAME")
        DROPLET_HOST="$SERVER_IP"
        for i in $(seq 1 60); do
          if ssh_cmd 'echo ok' 2>/dev/null; then
            echo "Server is up and SSH-reachable (took ${i}0s)."
            echo "Docker containers will auto-start (restart policy: unless-stopped)."
            exit 0
          fi
          sleep 10
        done
        warn "Server powered on but SSH isn't reachable after 10 minutes."
        echo "IP: $SERVER_IP — check the Hetzner console."
        exit 1
        ;;
      reboot)
        if [[ "$STATUS" == "off" ]]; then
          echo "Server '$SERVER_NAME' is off. Use './ops startup' to power on."
          exit 1
        fi
        countdown_and_save "rebooting"
        echo "Rebooting '$SERVER_NAME' (status: $STATUS)..."
        hcloud server reboot "$SERVER_NAME"
        echo "Waiting for server to come back..."
        sleep 15
        SERVER_IP=$(hcloud server ip "$SERVER_NAME")
        for i in $(seq 1 60); do
          if ssh_cmd 'echo ok' 2>/dev/null; then
            echo "Server rebooted and SSH-reachable (took $((i * 10 + 15))s)."
            exit 0
          fi
          DROPLET_HOST="$SERVER_IP"
          sleep 10
        done
        warn "Server rebooted but SSH isn't reachable after 10 minutes."
        exit 1
        ;;
      *)
        echo "Unknown action: $ACTION"
        usage
        exit 1
        ;;
    esac
    ;;

  digitalocean)
    : "${DO_API_TOKEN:?Set DO_API_TOKEN in .env}"
    export DIGITALOCEAN_ACCESS_TOKEN="$DO_API_TOKEN"

    DROPLET_ID=$(doctl compute droplet list --format ID,Name --no-header \
      | grep "$SERVER_NAME" | awk '{print $1}')
    if [[ -z "$DROPLET_ID" ]]; then
      die "Droplet '$SERVER_NAME' not found. Check BRAND_SLUG and your DO account."
    fi

    STATUS=$(doctl compute droplet get "$DROPLET_ID" --format Status --no-header)

    case "$ACTION" in
      shutdown)
        if [[ "$STATUS" == "off" ]]; then
          echo "Droplet '$SERVER_NAME' is already off."
          exit 0
        fi
        countdown_and_save "shutting down"
        echo "Shutting down '$SERVER_NAME' (status: $STATUS)..."
        doctl compute droplet-action shutdown "$DROPLET_ID" --wait
        echo "Server powered off."
        ;;
      startup)
        if [[ "$STATUS" == "active" ]]; then
          echo "Droplet '$SERVER_NAME' is already running."
          exit 0
        fi
        echo "Starting '$SERVER_NAME' (status: $STATUS)..."
        doctl compute droplet-action power-on "$DROPLET_ID" --wait
        echo "Waiting for SSH..."
        SERVER_IP=$(doctl compute droplet get "$DROPLET_ID" --format PublicIPv4 --no-header)
        DROPLET_HOST="$SERVER_IP"
        for i in $(seq 1 60); do
          if ssh_cmd 'echo ok' 2>/dev/null; then
            echo "Server is up and SSH-reachable (took ${i}0s)."
            echo "Docker containers will auto-start (restart policy: unless-stopped)."
            exit 0
          fi
          sleep 10
        done
        warn "Server powered on but SSH isn't reachable after 10 minutes."
        exit 1
        ;;
      reboot)
        if [[ "$STATUS" == "off" ]]; then
          echo "Droplet '$SERVER_NAME' is off. Use './ops startup' to power on."
          exit 1
        fi
        countdown_and_save "rebooting"
        echo "Rebooting '$SERVER_NAME' (status: $STATUS)..."
        doctl compute droplet-action reboot "$DROPLET_ID" --wait
        echo "Waiting for SSH..."
        sleep 15
        SERVER_IP=$(doctl compute droplet get "$DROPLET_ID" --format PublicIPv4 --no-header)
        DROPLET_HOST="$SERVER_IP"
        for i in $(seq 1 60); do
          if ssh_cmd 'echo ok' 2>/dev/null; then
            echo "Server rebooted and SSH-reachable (took $((i * 10 + 15))s)."
            exit 0
          fi
          sleep 10
        done
        warn "Server rebooted but SSH isn't reachable after 10 minutes."
        exit 1
        ;;
      *)
        echo "Unknown action: $ACTION"
        usage
        exit 1
        ;;
    esac
    ;;
esac
