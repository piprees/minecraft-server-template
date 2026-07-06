#!/usr/bin/env bash
# cloudflare-setup.sh - One-stop Cloudflare provisioning. Idempotent.
#
# Creates/updates, in order:
#   1. A record   mc.DOMAIN -> server IP (DNS-only/grey cloud - the game port
#                 is NEVER tunnelled; the free tier is HTTP-only)
#   2. SRV record _minecraft._tcp.mc.DOMAIN -> port SERVER_PORT (friends can
#                 connect with the bare hostname, no port)
#   3. Tunnel     CLOUDFLARE_TUNNEL_NAME + credentials into config/cloudflared/
#   4. R2 bucket  for restic backups (if R2_BUCKET set)
#   5. Tunnel config.yml ingress: map/status/mods -> nav-proxy, pack -> pack-web
#   6. CNAMEs     map/pack/status/mods.DOMAIN -> tunnel (proxied/orange cloud)
#   7. Maintenance Worker via wrangler (branded error page when tunnel is down)
#
# Requires: cloudflared CLI; CLOUDFLARE_API_TOKEN/ACCOUNT_ID/ZONE_ID + DOMAIN
# in .env. Restart cloudflared afterwards to pick up config changes.
#
# Usage:
#   ./scripts/cloudflare-setup.sh
#   ./scripts/cloudflare-setup.sh --non-interactive
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib.sh"   # PROJECT_DIR (CONSUMER_DIR-aware), sed_i, log/warn/die
cd "$PROJECT_DIR"

# --- load .env ----------------------------------------------------------------
if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi

: "${DOMAIN:?Set DOMAIN in .env}"
: "${CLOUDFLARE_API_TOKEN:?Set CLOUDFLARE_API_TOKEN in .env}"
: "${CLOUDFLARE_ACCOUNT_ID:?Set CLOUDFLARE_ACCOUNT_ID in .env}"
: "${CLOUDFLARE_ZONE_ID:?Set CLOUDFLARE_ZONE_ID in .env}"
: "${SERVER_PORT:=25577}"

TUNNEL_NAME="${CLOUDFLARE_TUNNEL_NAME:-mc-${BRAND_SLUG:-adventure}}"
export NON_INTERACTIVE=0
[[ "${1:-}" == "--non-interactive" ]] && export NON_INTERACTIVE=1

# Cloudflare free tier rate-limits at ~4 req/s. Pause between API calls.
cf_sleep() { sleep 1.5; }

# Make a Cloudflare API call with retry on rate limit (429)
cf_api() {
  local method="$1" path="$2" data="${3:-}"
  local attempt max_attempts=3
  for attempt in $(seq 1 $max_attempts); do
    local response
    if [[ -n "$data" ]]; then
      response=$(curl -s -w "\n%{http_code}" -X "$method" \
        "https://api.cloudflare.com/client/v4${path}" \
        -H "Authorization: Bearer ${CLOUDFLARE_API_TOKEN}" \
        -H "Content-Type: application/json" \
        --data "$data" 2>/dev/null)
    else
      response=$(curl -s -w "\n%{http_code}" -X "$method" \
        "https://api.cloudflare.com/client/v4${path}" \
        -H "Authorization: Bearer ${CLOUDFLARE_API_TOKEN}" \
        -H "Content-Type: application/json" 2>/dev/null)
    fi
    local http_code body
    http_code=$(echo "$response" | tail -1)
    body=$(echo "$response" | sed '$d')
    if [[ "$http_code" == "429" ]]; then
      echo "    Rate limited, waiting 5s (attempt $attempt/$max_attempts)..." >&2
      sleep 5
      continue
    fi
    echo "$body"
    cf_sleep
    return 0
  done
  echo '{"success":false,"errors":[{"message":"rate limited after retries"}]}'
}

# --- check cloudflared --------------------------------------------------------
if ! command -v cloudflared &> /dev/null; then
  echo "cloudflared not found. Install with: brew install cloudflared"
  exit 1
fi

# --- authenticate cloudflared with the API token ------------------------------
# cloudflared uses CLOUDFLARE_API_TOKEN env var for tunnel management.
export CLOUDFLARE_API_TOKEN

# =============================================================================
# 1. Get the droplet's public IP
# =============================================================================
echo "=== 1. Detecting droplet public IP ==="

DROPLET_IP="${DROPLET_HOST:-}"
if [[ -z "$DROPLET_IP" ]]; then
  # Try to detect from the machine we're running on
  DROPLET_IP=$(curl -s -4 https://ifconfig.me 2> /dev/null || true)
fi

if [[ -z "$DROPLET_IP" ]]; then
  echo "Could not detect public IP. Set DROPLET_HOST in .env."
  exit 1
fi
echo "  Droplet IP: $DROPLET_IP"

# =============================================================================
# 2. Create DNS A record for the game port (NOT proxied - grey cloud)
# =============================================================================
echo ""
echo "=== 2. DNS A record: mc.${DOMAIN} > ${DROPLET_IP} ==="

# Check if it already exists
EXISTING_A=$(cf_api GET "/zones/${CLOUDFLARE_ZONE_ID}/dns_records?type=A&name=mc.${DOMAIN}" \
  | python3 -c "import sys,json; r=json.load(sys.stdin)['result']; print(r[0]['id'] if r else '')" 2>/dev/null || true)

A_DATA="{\"type\":\"A\",\"name\":\"mc\",\"content\":\"${DROPLET_IP}\",\"ttl\":1,\"proxied\":false}"
if [[ -n "$EXISTING_A" ]]; then
  echo "  Updating existing A record..."
  cf_api PUT "/zones/${CLOUDFLARE_ZONE_ID}/dns_records/${EXISTING_A}" "$A_DATA" \
    | python3 -c "import sys,json; d=json.load(sys.stdin); print('  OK' if d.get('success') else '  FAILED: '+str(d.get('errors','')))"
else
  echo "  Creating A record..."
  cf_api POST "/zones/${CLOUDFLARE_ZONE_ID}/dns_records" "$A_DATA" \
    | python3 -c "import sys,json; d=json.load(sys.stdin); print('  OK' if d.get('success') else '  FAILED: '+str(d.get('errors','')))"
fi

# =============================================================================
# 3. Optional SRV record (hides the non-default port)
# =============================================================================
echo ""
echo "=== 3. SRV record: _minecraft._tcp.mc.${DOMAIN} > port ${SERVER_PORT} ==="

EXISTING_SRV=$(cf_api GET "/zones/${CLOUDFLARE_ZONE_ID}/dns_records?type=SRV&name=_minecraft._tcp.mc.${DOMAIN}" \
  | python3 -c "import sys,json; r=json.load(sys.stdin)['result']; print(r[0]['id'] if r else '')" 2>/dev/null || true)

SRV_DATA="{\"type\":\"SRV\",\"name\":\"_minecraft._tcp.mc\",\"data\":{\"service\":\"_minecraft\",\"proto\":\"_tcp\",\"name\":\"mc.${DOMAIN}\",\"priority\":0,\"weight\":5,\"port\":${SERVER_PORT},\"target\":\"mc.${DOMAIN}\"},\"ttl\":1}"

if [[ -n "$EXISTING_SRV" ]]; then
  echo "  Updating existing SRV record..."
  cf_api PUT "/zones/${CLOUDFLARE_ZONE_ID}/dns_records/${EXISTING_SRV}" "$SRV_DATA" \
    | python3 -c "import sys,json; d=json.load(sys.stdin); print('  OK' if d.get('success') else '  FAILED: '+str(d.get('errors','')))"
else
  echo "  Creating SRV record..."
  cf_api POST "/zones/${CLOUDFLARE_ZONE_ID}/dns_records" "$SRV_DATA" \
    | python3 -c "import sys,json; d=json.load(sys.stdin); print('  OK' if d.get('success') else '  FAILED: '+str(d.get('errors','')))"
fi

# =============================================================================
# 4. Create the Cloudflare Tunnel
# =============================================================================
echo ""
echo "=== 4. Cloudflare Tunnel: ${TUNNEL_NAME} ==="

# Check if tunnel already exists (via API, not cloudflared CLI)
TUNNEL_ID=$(cf_api GET "/accounts/${CLOUDFLARE_ACCOUNT_ID}/cfd_tunnel?name=${TUNNEL_NAME}&is_deleted=false" \
  | python3 -c "import sys,json; r=json.load(sys.stdin).get('result',[]); print(r[0]['id'] if r else '')" 2>/dev/null || true)

if [[ -n "$TUNNEL_ID" ]]; then
  echo "  Tunnel '$TUNNEL_NAME' already exists: $TUNNEL_ID"
else
  echo "  Creating tunnel '$TUNNEL_NAME' via API..."
  TUNNEL_SECRET=$(head -c 32 /dev/urandom | base64)
  CREATE_RESULT=$(cf_api POST "/accounts/${CLOUDFLARE_ACCOUNT_ID}/cfd_tunnel" \
    "{\"name\":\"${TUNNEL_NAME}\",\"tunnel_secret\":\"${TUNNEL_SECRET}\"}")
  TUNNEL_ID=$(echo "$CREATE_RESULT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['result']['id'] if d.get('success') else '')" 2>/dev/null || true)

  if [[ -z "$TUNNEL_ID" ]]; then
    echo "  ERROR: Failed to create tunnel."
    echo "$CREATE_RESULT" | python3 -c "import sys,json; print(json.load(sys.stdin).get('errors',''))" 2>/dev/null
    exit 1
  fi
  echo "  Created tunnel: $TUNNEL_ID"

  # Write credentials file (cloudflared needs this to authenticate the tunnel connection)
  mkdir -p "$HOME/.cloudflared"
  cat > "$HOME/.cloudflared/${TUNNEL_ID}.json" << CREDEOF
{"AccountTag":"${CLOUDFLARE_ACCOUNT_ID}","TunnelSecret":"${TUNNEL_SECRET}","TunnelID":"${TUNNEL_ID}"}
CREDEOF
  echo "  Credentials written to ~/.cloudflared/${TUNNEL_ID}.json"
fi

# =============================================================================
# 5. Copy tunnel credentials to config/cloudflared/
# =============================================================================
echo ""
echo "=== 5. Tunnel credentials ==="

CRED_DIR="$PROJECT_DIR/config/cloudflared"
mkdir -p "$CRED_DIR"

# cloudflared stores creds in ~/.cloudflared/
CRED_SRC="$HOME/.cloudflared/${TUNNEL_ID}.json"
CRED_DST="$CRED_DIR/${TUNNEL_ID}.json"

if [[ -f "$CRED_SRC" ]]; then
  # -f: the credentials file is written 0400, a plain cp can't overwrite it
  cp -f "$CRED_SRC" "$CRED_DST"
  echo "  Credentials copied to $CRED_DST"
elif [[ -f "$CRED_DST" ]]; then
  echo "  Credentials already in place."
else
  echo "  WARNING: Credentials file not found at $CRED_SRC"
  echo "  You may need to run: cloudflared tunnel login"
fi

# =============================================================================
# 5b. Create R2 backup bucket (if credentials are configured)
# =============================================================================
echo ""
echo "=== 5b. R2 backup bucket ==="

R2_ACCOUNT_ID="${R2_ACCOUNT_ID:-${CLOUDFLARE_ACCOUNT_ID}}"
R2_BUCKET="${R2_BUCKET:-}"

if [[ -n "$R2_ACCOUNT_ID" && -n "$R2_BUCKET" ]]; then
  # Check if bucket already exists
  EXISTING_BUCKETS=$(cf_api GET "/accounts/${R2_ACCOUNT_ID}/r2/buckets" \
    | python3 -c "import sys,json; print(' '.join(b['name'] for b in json.load(sys.stdin).get('result',{}).get('buckets',[])))" 2>/dev/null || true)

  if echo "$EXISTING_BUCKETS" | grep -qw "${R2_BUCKET}"; then
    echo "  ✓ Bucket '${R2_BUCKET}' already exists"
  else
    echo "  Creating R2 bucket '${R2_BUCKET}'..."
    # Valid location hints: wnam, enam, weur, eeur, apac, oc, auto
    R2_BODY=$(cf_api POST "/accounts/${R2_ACCOUNT_ID}/r2/buckets" "{\"name\":\"${R2_BUCKET}\",\"locationHint\":\"${R2_LOCATION_HINT:-weur}\"}")
    R2_SUCCESS=$(echo "$R2_BODY" | python3 -c "import sys,json; print(json.load(sys.stdin).get('success',False))" 2>/dev/null || echo "False")
    if [[ "$R2_SUCCESS" == "True" ]]; then
      echo "  ✓ Bucket '${R2_BUCKET}' created (${R2_LOCATION_HINT:-weur})"
    else
      echo "  WARNING: Bucket creation failed."
      echo "$R2_BODY" | python3 -c "import sys,json; print('  '+str(json.load(sys.stdin).get('errors','')))" 2>/dev/null
      echo "  Create it manually: Dashboard > R2 > Create Bucket"
    fi
  fi
else
  echo "  Skipped (R2_BUCKET not set in .env)"
fi

# =============================================================================
# 6. Write tunnel config.yml with real values
# =============================================================================
echo ""
echo "=== 6. Writing tunnel config ==="

cat > "$CRED_DIR/config.yml" << EOF
# Cloudflare Tunnel configuration - generated by cloudflare-setup.sh
# Tunnels HTTP services only. The game port uses a plain DNS A record.
tunnel: ${TUNNEL_ID}
credentials-file: /etc/cloudflared/${TUNNEL_ID}.json

ingress:
  # BlueMap web map (via nav-proxy for nav bar injection)
  - hostname: map.${DOMAIN}
    service: http://nav-proxy:80

  # Modpack download server
  - hostname: pack.${DOMAIN}
    service: http://pack-web:80

  # Uptime Kuma monitoring / status page (via nav-proxy for nav bar injection)
  - hostname: status.${DOMAIN}
    service: http://nav-proxy:80

  # Mod JAR mirror - straight to pack-web (no nav-bar injection for binaries)
  - hostname: mods.${DOMAIN}
    path: ^/mods/.*
    service: http://pack-web:80

  # Mod status page (via nav-proxy for nav bar injection)
  - hostname: mods.${DOMAIN}
    service: http://nav-proxy:80

  # Root domain redirect (nginx handles the 301 to status.DOMAIN)
  - hostname: ${DOMAIN}
    service: http://pack-web:80

  # Catch-all: reject unmatched hostnames
  - service: http_status:404
EOF

echo "  Written: $CRED_DIR/config.yml"

# Persist the tunnel ID so github-env-sync.sh and op-sync-env.sh can see it.
if grep -q '^CLOUDFLARE_TUNNEL_ID=' "$PROJECT_DIR/.env" 2> /dev/null; then
  sed_i "s/^CLOUDFLARE_TUNNEL_ID=.*/CLOUDFLARE_TUNNEL_ID='${TUNNEL_ID}'/" "$PROJECT_DIR/.env"
else
  printf '\nCLOUDFLARE_TUNNEL_ID=%s\n' "'${TUNNEL_ID}'" >> "$PROJECT_DIR/.env"
fi
echo "  CLOUDFLARE_TUNNEL_ID written to .env"

# =============================================================================
# 7. Create CNAME records for tunnelled services
# =============================================================================
echo ""
echo "=== 7. DNS CNAME records for tunnelled services ==="

for SUBDOMAIN in map pack status mods; do
  echo "  Setting ${SUBDOMAIN}.${DOMAIN} > ${TUNNEL_ID}.cfargotunnel.com"

  EXISTING_CNAME=$(cf_api GET "/zones/${CLOUDFLARE_ZONE_ID}/dns_records?type=CNAME&name=${SUBDOMAIN}.${DOMAIN}" \
    | python3 -c "import sys,json; r=json.load(sys.stdin)['result']; print(r[0]['id'] if r else '')" 2>/dev/null || true)

  CNAME_DATA="{\"type\":\"CNAME\",\"name\":\"${SUBDOMAIN}\",\"content\":\"${TUNNEL_ID}.cfargotunnel.com\",\"ttl\":1,\"proxied\":true}"

  if [[ -n "$EXISTING_CNAME" ]]; then
    cf_api PUT "/zones/${CLOUDFLARE_ZONE_ID}/dns_records/${EXISTING_CNAME}" "$CNAME_DATA" \
      | python3 -c "import sys,json; d=json.load(sys.stdin); print('    OK' if d.get('success') else '    FAILED: '+str(d.get('errors','')))"
  else
    cf_api POST "/zones/${CLOUDFLARE_ZONE_ID}/dns_records" "$CNAME_DATA" \
      | python3 -c "import sys,json; d=json.load(sys.stdin); print('    OK' if d.get('success') else '    FAILED: '+str(d.get('errors','')))"
  fi
done

# =============================================================================
# 8. Deploy maintenance Worker (catches tunnel errors with a branded page)
# =============================================================================
echo ""
echo "=== 8. Maintenance Worker ==="

WORKER_DIR="$PROJECT_DIR/config/cloudflare"

if [[ ! -f "$WORKER_DIR/wrangler.jsonc" ]]; then
  echo "  WARNING: wrangler.jsonc not found in $WORKER_DIR - skipping"
elif ! command -v wrangler &> /dev/null; then
  echo "  WARNING: wrangler CLI not found - skipping Worker deployment"
  echo "  Install with: brew install wrangler"
else
  echo "  Deploying via wrangler (waiting 5s for rate limit cooldown)..."
  sleep 5
  # Substitute real values into wrangler config before deploying
  WORKER_NAME="${BRAND_SLUG:-adventure}-maintenance"
  sed_i "s/\"adventure-maintenance\"/\"${WORKER_NAME}\"/" "$WORKER_DIR/wrangler.jsonc" 2>/dev/null || true
  if grep -q 'example\.com' "$WORKER_DIR/wrangler.jsonc" 2>/dev/null; then
    sed_i "s/example\.com/${DOMAIN}/g" "$WORKER_DIR/wrangler.jsonc"
  fi
  echo "  Worker: ${WORKER_NAME}, domain: ${DOMAIN}"
  (cd "$WORKER_DIR" && wrangler deploy 2>&1) | sed 's/^/  /'
fi

# =============================================================================
echo ""
echo "=================================================================="
echo " Cloudflare setup complete."
echo ""
echo " DNS records:"
echo "   mc.${DOMAIN}        > ${DROPLET_IP} (A, DNS-only, game port)"
echo "   map.${DOMAIN}   > tunnel (CNAME, proxied, BlueMap)"
echo "   pack.${DOMAIN}  > tunnel (CNAME, proxied, modpack)"
echo "   status.${DOMAIN} > tunnel (CNAME, proxied, monitoring)"
echo "   mods.${DOMAIN}   > tunnel (CNAME, proxied, mod status)"
echo ""
echo " SRV record:"
echo "   _minecraft._tcp.mc.${DOMAIN} > mc.${DOMAIN}:${SERVER_PORT}"
echo "   (Friends can connect with just 'mc.${DOMAIN}' - no port needed)"
echo ""
echo " Tunnel: ${TUNNEL_NAME} (${TUNNEL_ID})"
echo ""
echo "=================================================================="

# Copy tunnel config + credentials to the server and restart cloudflared.
# Server layout is the bundle model: project dir ~/server, compose mounts
# ${CLOUDFLARED_DIR:-./cloudflared} - i.e. ~/server/cloudflared/.
if [[ -n "${DROPLET_HOST:-}" ]]; then
  DEPLOY_KEY="${DEPLOY_KEY_PATH:-$HOME/.ssh/${BRAND_SLUG:+${BRAND_SLUG}_}mc_deploy_key}"
  DEPLOY_USER="${DEPLOY_USER:-deploy}"
  echo ""
  echo "  Syncing tunnel config to server and restarting cloudflared..."
  ssh -o ConnectTimeout=5 -i "$DEPLOY_KEY" "${DEPLOY_USER}@${DROPLET_HOST}" 'mkdir -p ~/server/cloudflared' 2>/dev/null || true
  scp -i "$DEPLOY_KEY" "$CRED_DIR/config.yml" "${DEPLOY_USER}@${DROPLET_HOST}:~/server/cloudflared/config.yml" 2>/dev/null || true
  scp -i "$DEPLOY_KEY" "$CRED_DIR/${TUNNEL_ID}.json" "${DEPLOY_USER}@${DROPLET_HOST}:~/server/cloudflared/${TUNNEL_ID}.json" 2>/dev/null || true
  # The cloudflared container runs as nonroot uid 65532 - files scp'd as the
  # deploy user with tight modes are unreadable inside the container.
  ssh -o ConnectTimeout=5 -i "$DEPLOY_KEY" "${DEPLOY_USER}@${DROPLET_HOST}" \
    "sudo chown 65532:65532 ~/server/cloudflared/config.yml ~/server/cloudflared/${TUNNEL_ID}.json && sudo chmod 400 ~/server/cloudflared/${TUNNEL_ID}.json && sudo chmod 444 ~/server/cloudflared/config.yml" 2>/dev/null || true
  ssh -o ConnectTimeout=5 -i "$DEPLOY_KEY" "${DEPLOY_USER}@${DROPLET_HOST}" \
    "cd ~/server && docker compose --project-directory . -f .stack/current/stack/docker-compose.yml --profile cloud up -d --force-recreate --no-deps cloudflared 2>/dev/null" \
    && echo "  cloudflared restarted with new config." \
    || echo "  Could not restart cloudflared (server may not have the stack running yet - the first deploy starts it)."
fi
