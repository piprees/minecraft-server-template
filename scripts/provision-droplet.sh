#!/usr/bin/env bash
# provision-droplet.sh - Create a DigitalOcean droplet. Idempotent.
#
# Requires: doctl, DO_API_TOKEN in .env
#
# Usage:
#   ./scripts/provision-droplet.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="${CONSUMER_DIR:-$(cd "$SCRIPT_DIR/.." && pwd)}"

# --- load config --------------------------------------------------------------
if [[ -f "$PROJECT_DIR/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$PROJECT_DIR/.env"
  set +a
fi

: "${DO_API_TOKEN:?Set DO_API_TOKEN in .env}"
: "${DO_REGION:=lon1}"
: "${DO_SIZE:=s-4vcpu-8gb}"

DROPLET_NAME="${DO_SERVER_NAME:-mc-${BRAND_SLUG:-adventure}}"
DROPLET_TAG="minecraft"
DROPLET_IMAGE="ubuntu-24-04-x64"

export DIGITALOCEAN_ACCESS_TOKEN="$DO_API_TOKEN"

# --- check doctl is available -------------------------------------------------
if ! command -v doctl &> /dev/null; then
  echo "doctl not found. Install with: brew install doctl"
  exit 1
fi

# --- check if droplet already exists ------------------------------------------
echo "Checking for existing droplet named '$DROPLET_NAME'..."

EXISTING_IP=$(doctl compute droplet list \
  --format Name,PublicIPv4 \
  --no-header \
  | grep -E "^${DROPLET_NAME}\s" \
  | awk '{print $2}' \
  || true)

if [[ -n "$EXISTING_IP" ]]; then
  echo "Droplet '$DROPLET_NAME' already exists at $EXISTING_IP"
  echo ""
  echo "  SSH:  ssh ${DEPLOY_USER:-deploy}@${EXISTING_IP}"
  echo "  IP:   $EXISTING_IP"
  echo ""
  echo "Skipping creation. To rebuild, destroy the droplet first via the DO dashboard."
  exit 0
fi

# --- find SSH keys to install -------------------------------------------------
echo "Finding SSH keys registered with DigitalOcean..."

SSH_KEY_IDS=$(doctl compute ssh-key list --format ID --no-header | tr '\n' ',' | sed 's/,$//')

if [[ -z "$SSH_KEY_IDS" ]]; then
  echo "No SSH keys found in your DO account."
  echo "Upload your public key first:"
  echo "  doctl compute ssh-key create my-key --public-key \"\$(cat ~/.ssh/id_ed25519.pub)\""
  exit 1
fi

echo "  Using SSH key IDs: $SSH_KEY_IDS"

# --- create the droplet -------------------------------------------------------
echo ""
echo "Creating droplet..."
echo "  Name:    $DROPLET_NAME"
echo "  Region:  $DO_REGION"
echo "  Size:    $DO_SIZE"
echo "  Image:   $DROPLET_IMAGE"
echo ""

doctl compute droplet create "$DROPLET_NAME" \
  --region "$DO_REGION" \
  --size "$DO_SIZE" \
  --image "$DROPLET_IMAGE" \
  --ssh-keys "$SSH_KEY_IDS" \
  --tag-names "$DROPLET_TAG" \
  --enable-monitoring \
  --wait

# --- fetch the IP (droplet is now ready) --------------------------------------
echo "Fetching droplet IP..."

DROPLET_IP=$(doctl compute droplet list \
  --format Name,PublicIPv4 \
  --no-header \
  | grep -E "^${DROPLET_NAME}\s" \
  | awk '{print $2}')

echo ""
echo "=================================================================="
echo " Droplet created successfully."
echo ""
echo "  IP:       $DROPLET_IP"
echo "  SSH:      ssh root@${DROPLET_IP}"
echo ""
echo " Next steps:"
echo "  1. Run the hardening script:"
echo "     scp scripts/harden.sh root@${DROPLET_IP}:/root/"
echo "     ssh root@${DROPLET_IP} 'chmod +x /root/harden.sh && /root/harden.sh'"
echo ""
echo "  2. Or use the automated route:"
echo "     ./scripts/harden.sh --remote root@${DROPLET_IP}"
echo ""
echo "  3. Update .env with:"
echo "     DROPLET_HOST=${DROPLET_IP}"
echo ""
echo "  4. Set the DNS A record:"
echo "     mc.${DOMAIN:-example.com} > ${DROPLET_IP}"
echo "=================================================================="
