#!/usr/bin/env bash
# provision.sh - Create a cloud server using the configured provider.
#
# Routes to the provider-specific provisioning script based on CLOUD_PROVIDER
# in server.env. Defaults to prompting the user to choose.
#
# Usage:
#   ./scripts/provision.sh                       # uses CLOUD_PROVIDER from .env
#   CLOUD_PROVIDER=hetzner ./scripts/provision.sh # explicit provider
#   ./scripts/provision.sh --provider hetzner     # flag override
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"
load_env

# --- Parse flags --------------------------------------------------------------
PROVIDER="${CLOUD_PROVIDER:-}"
for arg in "$@"; do
  case "$arg" in
    --provider=*) PROVIDER="${arg#*=}" ;;
    --provider) ;; # next arg handled below
  esac
done
# Handle --provider VALUE (two-arg form)
while [[ $# -gt 0 ]]; do
  case "$1" in
    --provider)
      PROVIDER="${2:-}"
      shift 2
      ;;
    *) shift ;;
  esac
done

# --- Prompt if no provider set ------------------------------------------------
if [[ -z "$PROVIDER" ]]; then
  echo ""
  echo "Where do you want to deploy?"
  echo ""
  echo "  1) Local Linux machine (default - no cloud account needed)"
  echo "  2) Hetzner Cloud (~€8/mo, recommended for production)"
  echo "  3) DigitalOcean (~\$48/mo)"
  echo ""
  read -rp "Choice [1]: " choice
  case "${choice:-1}" in
    1) PROVIDER="local" ;;
    2) PROVIDER="hetzner" ;;
    3) PROVIDER="digitalocean" ;;
    *) PROVIDER="local" ;;
  esac
  echo ""
fi

# --- Route to provider script -------------------------------------------------
case "$PROVIDER" in
  hetzner)
    log "Provisioning on Hetzner Cloud..."
    require_provider_cli hetzner
    exec "$SCRIPT_DIR/provision-hetzner.sh" "$@"
    ;;
  digitalocean | do)
    log "Provisioning on DigitalOcean..."
    require_provider_cli digitalocean
    exec "$SCRIPT_DIR/provision-droplet.sh" "$@"
    ;;
  local)
    log "Local deployment - no cloud provisioning needed."
    echo ""
    echo "  For a local Linux machine:"
    echo "  1. Ensure Ubuntu 24.04 is running with Docker installed"
    echo "  2. Run: ./scripts/harden.sh --remote root@YOUR_IP"
    echo "  3. Run: ./scripts/prepare-droplet.sh"
    echo "  4. SSH in and run: ./scripts/deploy.sh"
    echo ""
    echo "  For testing in Docker:"
    echo "  ./scripts/test-scripts.sh"
    ;;
  *)
    die "Unknown provider: $PROVIDER (expected: hetzner, digitalocean, local)"
    ;;
esac
