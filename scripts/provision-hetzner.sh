#!/usr/bin/env bash
# provision-hetzner.sh - Create a Hetzner Cloud server. That's it.
#
# Creates the server + firewall + uploads SSH keys. Does NOT harden or
# configure — those are separate scripts (harden.sh, prepare-droplet.sh)
# called by setup.sh in sequence. This separation means each script is
# the single source of truth for its job.
#
# Idempotent: detects an existing server and reports its IP.
#
# Usage:
#   ./scripts/provision-hetzner.sh
#   HCLOUD_SERVER_TYPE=cx33 ./scripts/provision-hetzner.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"
load_env

# Accept either HCLOUD_TOKEN (hcloud CLI convention) or HETZNER_API_TOKEN (1Password)
HCLOUD_TOKEN="${HCLOUD_TOKEN:-${HETZNER_API_TOKEN:-}}"
: "${HCLOUD_TOKEN:?Set HCLOUD_TOKEN or HETZNER_API_TOKEN in .env}"
: "${HCLOUD_LOCATION:=fsn1}"
: "${HCLOUD_SERVER_TYPE:=cx33}"

SERVER_NAME="${HCLOUD_SERVER_NAME:-mc-${BRAND_SLUG:-adventure}}"
SERVER_IMAGE="ubuntu-24.04"
FW_NAME="${SERVER_NAME}-fw"

export HCLOUD_TOKEN

require_provider_cli hetzner

# =============================================================================
# 1. Check if server already exists
# =============================================================================
EXISTING_IP=$(hcloud server ip "$SERVER_NAME" 2> /dev/null || true)
if [[ -n "$EXISTING_IP" ]]; then
  log "Server '$SERVER_NAME' already exists at $EXISTING_IP"
  SERVER_IP="$EXISTING_IP"
else
  # ===========================================================================
  # 2. Ensure SSH keys exist in Hetzner
  # ===========================================================================
  SSH_KEY_NAMES=$(hcloud ssh-key list -o noheader -o columns=name 2> /dev/null || true)
  if [[ -z "$SSH_KEY_NAMES" ]]; then
    log "Uploading SSH keys to Hetzner..."
    for pubkey in ~/.ssh/id_ed25519.pub ~/.ssh/id_rsa.pub ~/.ssh/mc_deploy_key.pub; do
      if [[ -f "$pubkey" ]]; then
        keyname="$(basename "$pubkey" .pub)"
        hcloud ssh-key create --name "$keyname" --public-key-from-file "$pubkey" 2> /dev/null || true
        echo "  Uploaded $keyname"
      fi
    done
    SSH_KEY_NAMES=$(hcloud ssh-key list -o noheader -o columns=name 2> /dev/null || true)
    [[ -z "$SSH_KEY_NAMES" ]] && die "No SSH keys available. Upload one manually first."
  fi

  # ===========================================================================
  # 3. Create firewall
  # ===========================================================================
  if ! hcloud firewall describe "$FW_NAME" &> /dev/null; then
    log "Creating firewall '$FW_NAME'..."
    FW_RULES_FILE="$(mktemp)"
    cat > "$FW_RULES_FILE" << FWEOF
[
  {"direction":"in","protocol":"tcp","port":"22","source_ips":["0.0.0.0/0","::/0"],"description":"SSH"},
  {"direction":"in","protocol":"tcp","port":"${SERVER_PORT:-25577}","source_ips":["0.0.0.0/0","::/0"],"description":"Minecraft game"},
  {"direction":"in","protocol":"udp","port":"${VOICE_PORT:-24454}","source_ips":["0.0.0.0/0","::/0"],"description":"Voice chat"}
]
FWEOF
    hcloud firewall create --name "$FW_NAME" --rules-file "$FW_RULES_FILE"
    rm -f "$FW_RULES_FILE"
  else
    log "Firewall '$FW_NAME' already exists"
  fi

  # ===========================================================================
  # 4. Create the server
  # ===========================================================================
  SSH_KEY_FLAGS=()
  while IFS= read -r key; do
    [[ -n "$key" ]] && SSH_KEY_FLAGS+=(--ssh-key "$key")
  done <<< "$SSH_KEY_NAMES"

  log "Creating server..."
  echo "  Name:     $SERVER_NAME"
  echo "  Type:     $HCLOUD_SERVER_TYPE"
  echo "  Location: $HCLOUD_LOCATION"
  echo "  Image:    $SERVER_IMAGE"

  CREATED=0
  CURRENT_TYPE="$HCLOUD_SERVER_TYPE"
  CURRENT_LOCATION="$HCLOUD_LOCATION"

  while [[ $CREATED -eq 0 ]]; do
    if hcloud server create \
      --name "$SERVER_NAME" \
      --type "$CURRENT_TYPE" \
      --image "$SERVER_IMAGE" \
      --location "$CURRENT_LOCATION" \
      "${SSH_KEY_FLAGS[@]}" \
      --firewall "$FW_NAME" \
      --label env=production 2>&1; then
      CREATED=1
    else
      echo ""
      warn "$CURRENT_TYPE isn't available in $CURRENT_LOCATION right now."
      echo "  ARM servers sell out frequently in popular regions."
      echo ""
      echo "  You wanted: $CURRENT_TYPE"
      case "$CURRENT_TYPE" in
        cax11) echo "             2 ARM cores, 4 GB RAM, 40 GB disk (~€3.79/mo)" ;;
        cax21) echo "             4 ARM cores, 8 GB RAM, 80 GB disk (~€7.49/mo)" ;;
        cax31) echo "             8 ARM cores, 16 GB RAM, 160 GB disk (~€14.49/mo)" ;;
        cax41) echo "             16 ARM cores, 32 GB RAM, 320 GB disk (~€27.49/mo)" ;;
      esac
      echo ""
      echo "  Options:"
      echo "    1) Try again (availability changes frequently)"
      echo "    2) Pick a different server type"
      echo "    3) Try a different location"
      echo "    4) Abort and try later"
      echo ""
      read -rp "  Choice [1]: " fallback_choice
      case "${fallback_choice:-1}" in
        1)
          log "Retrying $CURRENT_TYPE in $CURRENT_LOCATION..."
          ;;
        2)
          echo ""
          echo "  Available server types (live from Hetzner API):"
          echo ""
          # Query real availability and pricing from the API
          PICK_LIST=$(hcloud server-type list --sort name -o json 2>/dev/null | python3 -c "
import json, sys
types = json.load(sys.stdin)
letters = 'abcdefghijklmnopqrstuvwxyz'
idx = 0
for t in sorted(types, key=lambda x: float(next((p['price_monthly']['gross'] for p in x.get('prices',[]) if p['location']=='fsn1'), '9999'))):
    if not any(t['name'].startswith(p) for p in ('cax', 'cx', 'ccx')):
        continue
    if t['cores'] > 16 or t.get('deprecation'):
        continue
    price = next((float(p['price_monthly']['gross']) for p in t.get('prices',[]) if p['location']=='fsn1'), 0)
    if price == 0:
        continue
    arch = 'ARM' if t['architecture'] == 'arm' else 'x86'
    shared = 'dedicated' if t['cpu_type'] == 'dedicated' else 'shared'
    marker = '  <-- original' if t['name'] == '$HCLOUD_SERVER_TYPE' else ''
    print(f\"    {letters[idx]}) {t['name']:8s} {t['cores']}c {t['memory']:.0f}GB {t['disk']}GB {arch} {shared} EUR{price:.2f}/mo{marker}\")
    idx += 1
" 2>/dev/null || echo "    (couldn't fetch types - pick manually)")
          echo "$PICK_LIST"
          echo ""
          # Build a mapping of letters to type names
          PICK_MAP=$(hcloud server-type list --sort name -o json 2>/dev/null | python3 -c "
import json, sys
types = json.load(sys.stdin)
letters = 'abcdefghijklmnopqrstuvwxyz'
idx = 0
for t in sorted(types, key=lambda x: float(next((p['price_monthly']['gross'] for p in x.get('prices',[]) if p['location']=='fsn1'), '9999'))):
    if not any(t['name'].startswith(p) for p in ('cax', 'cx', 'ccx')):
        continue
    if t['cores'] > 16 or t.get('deprecation'):
        continue
    price = next((float(p['price_monthly']['gross']) for p in t.get('prices',[]) if p['location']=='fsn1'), 0)
    if price == 0:
        continue
    print(f'{letters[idx]}={t[\"name\"]}')
    idx += 1
" 2>/dev/null)
          # Find the default (cx33 if available, otherwise first in list)
          DEFAULT_PICK=$(echo "$PICK_MAP" | grep '=cx33$' | cut -d= -f1)
          [[ -z "$DEFAULT_PICK" ]] && DEFAULT_PICK=$(echo "$PICK_MAP" | head -1 | cut -d= -f1)
          read -rp "  Pick a server type [${DEFAULT_PICK}]: " type_choice
          type_choice="${type_choice:-$DEFAULT_PICK}"
          PICKED=$(echo "$PICK_MAP" | grep "^${type_choice}=" | cut -d= -f2)
          if [[ -n "$PICKED" ]]; then
            CURRENT_TYPE="$PICKED"
          elif [[ -n "$type_choice" ]]; then
            CURRENT_TYPE="$type_choice"
          fi
          log "Switching to $CURRENT_TYPE..."
          ;;
        3)
          echo ""
          echo "    a) fsn1 - Falkenstein, Germany"
          echo "    b) nbg1 - Nuremberg, Germany"
          echo "    c) hel1 - Helsinki, Finland"
          echo ""
          read -rp "  Pick a location [a]: " loc_choice
          case "${loc_choice:-a}" in
            a|fsn1) CURRENT_LOCATION="fsn1" ;;
            b|nbg1) CURRENT_LOCATION="nbg1" ;;
            c|hel1) CURRENT_LOCATION="hel1" ;;
            *) CURRENT_LOCATION="fsn1" ;;
          esac
          log "Trying $CURRENT_TYPE in $CURRENT_LOCATION..."
          ;;
        4)
          die "Aborted. Re-run when ready."
          ;;
        *)
          log "Retrying $CURRENT_TYPE in $CURRENT_LOCATION..."
          ;;
      esac
    fi
  done

  SERVER_IP=$(hcloud server ip "$SERVER_NAME")
  log "Server created at $SERVER_IP"

  # Clear any stale host key from a previous server at this IP
  ssh-keygen -R "$SERVER_IP" 2>/dev/null || true

  # Wait for SSH to become available
  echo "  Waiting for SSH..."
  for _ in $(seq 1 30); do
    if ssh -o ConnectTimeout=5 -o StrictHostKeyChecking=accept-new root@"$SERVER_IP" 'true' 2> /dev/null; then
      break
    fi
    sleep 2
  done
fi

echo ""
log "Server IP: $SERVER_IP"

# Persist the IP so harden.sh / prepare-droplet.sh / github-env-sync.sh can
# find the server without the user copying it around.
if grep -q '^DROPLET_HOST=' "$PROJECT_DIR/.env" 2> /dev/null; then
  sed_i "s/^DROPLET_HOST=.*/DROPLET_HOST=$SERVER_IP/" "$PROJECT_DIR/.env"
else
  printf '\nDROPLET_HOST=%s\n' "$SERVER_IP" >> "$PROJECT_DIR/.env"
fi
log "DROPLET_HOST=$SERVER_IP written to .env"

# =============================================================================
# Done — server created, SSH available, nothing else.
# Hardening (harden.sh) and preparation (prepare-droplet.sh) are separate
# scripts called by setup.sh in sequence. This script only creates the server.
# =============================================================================
echo ""
echo "=================================================================="
echo " Server provisioned."
echo ""
echo "  Name:   $SERVER_NAME"
echo "  IP:     $SERVER_IP"
echo "  Type:   $CURRENT_TYPE (${CURRENT_LOCATION})"
echo ""
echo " Next: harden.sh -> prepare-droplet.sh -> initial-setup.sh"
echo " (setup.sh runs these automatically)"
echo "=================================================================="
