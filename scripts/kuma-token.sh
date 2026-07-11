#!/usr/bin/env bash
# kuma-token.sh - Get a Kuma session token (KUMA_API_KEY) for automation.
#
# Uptime Kuma's MANAGEMENT API is socket.io only (verified against the 2.x
# source: express serves only the dashboard, /metrics and status pages;
# everything else lives in socket.io handlers). With 2FA enabled, headless
# automation needs a session JWT - and the python wrapper (uptime-kuma-api
# 1.2.1, latest) cannot complete a password+TOTP login against Kuma 2.x,
# so tokens are minted through the real web UI instead.
#
# Modes:
#   ./ops kuma-token --browser   RECOMMENDED. Opens Chromium via Playwright;
#                                log in (password + TOTP) and the script lifts
#                                the session JWT from localStorage automatically.
#                                First run downloads Chromium (~150MB).
#   ./ops kuma-token --paste     Paste a JWT you copied yourself:
#                                dashboard -> DevTools -> Application ->
#                                Local Storage -> the JWT-looking value.
#   ./ops kuma-token --remote    LEGACY socket login. Works only against
#                                Kuma 1.x; on 2.x it times out. Kept for
#                                consumers still pinned to 1.x.
#
# Whatever the mode, the token is written to the local AND server .env
# (append-if-missing), and you MUST push it to the GitHub environment
# afterwards or the next full CI deploy wipes it from the server:
#   ./ops github-env-sync
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="${CONSUMER_DIR:-$(cd "$SCRIPT_DIR/.." && pwd)}"

# shellcheck disable=SC1091
source "$PROJECT_DIR/.env" 2> /dev/null || true
SERVER_DIR="server"
KEY="${HOME}/.ssh/${BRAND_SLUG:+${BRAND_SLUG}_}mc_deploy_key"
[[ -f "$KEY" ]] || KEY="${HOME}/.ssh/mc_deploy_key"

looks_like_jwt() {
  [[ "$1" =~ ^ey[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$ ]]
}

save_token() {
  local token="$1"

  # Local .env - APPEND when the line doesn't exist: a consumer .env
  # without a KUMA_API_KEY line made the old sed a silent no-op, so the
  # token never reached GitHub and CI deploys kept wiping it (2026-07-11).
  if grep -q '^KUMA_API_KEY=' "$PROJECT_DIR/.env"; then
    sed -i '' "s|^KUMA_API_KEY=.*|KUMA_API_KEY=${token}|" "$PROJECT_DIR/.env" 2> /dev/null \
      || sed -i "s|^KUMA_API_KEY=.*|KUMA_API_KEY=${token}|" "$PROJECT_DIR/.env"
  else
    printf 'KUMA_API_KEY=%s\n' "$token" >> "$PROJECT_DIR/.env"
  fi
  echo "  Updated local .env"

  if [[ -n "${DROPLET_HOST:-}" ]]; then
    ssh -i "$KEY" "deploy@${DROPLET_HOST}" "cd ~/${SERVER_DIR} && if grep -q '^KUMA_API_KEY=' .env; then sed -i 's|^KUMA_API_KEY=.*|KUMA_API_KEY=${token}|' .env; else printf 'KUMA_API_KEY=%s\n' '${token}' >> .env; fi"
    echo "  Updated server .env"
  else
    echo "  DROPLET_HOST not set - server .env NOT updated"
  fi

  echo ""
  echo "IMPORTANT: push the token to the GitHub environment or the next full"
  echo "CI deploy will wipe it from the server again:"
  echo "  ./ops github-env-sync"
}

case "${1:-}" in

  --browser)
    : "${DOMAIN:?Set DOMAIN in .env}"
    URL="https://status.${DOMAIN}/dashboard"
    echo "Opening ${URL} - log in with your password + TOTP."
    echo "The session token is picked up automatically after login."

    TMPJS="$(mktemp -t kuma-token-XXXXXX).cjs"
    cat > "$TMPJS" << 'JS'
const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch({ headless: false });
  const page = await browser.newPage();
  await page.goto(process.env.KUMA_DASH_URL);
  // Poll localStorage for a JWT-shaped value under ANY key - robust to
  // Kuma renaming its storage key between versions.
  let token = '';
  for (let i = 0; i < 300 && !token; i++) {
    token = await page.evaluate(() => {
      for (let j = 0; j < localStorage.length; j++) {
        const v = localStorage.getItem(localStorage.key(j)) || '';
        if (/^ey[\w-]+\.[\w-]+\.[\w-]+$/.test(v)) return v;
      }
      return '';
    }).catch(() => '');
    if (!token) await page.waitForTimeout(1000);
  }
  await browser.close();
  if (!token) { console.error('Timed out waiting for login (5 min).'); process.exit(1); }
  console.log(token);
})();
JS
    # Playwright needs its browser once; install is idempotent and cached.
    npx -y playwright install chromium > /dev/null 2>&1 || true
    TOKEN=$(KUMA_DASH_URL="$URL" npx -y -p playwright node "$TMPJS")
    rm -f "$TMPJS"

    if ! looks_like_jwt "$TOKEN"; then
      echo "ERROR: did not capture a session token." >&2
      exit 1
    fi
    echo "Session token captured."
    save_token "$TOKEN"
    ;;

  --paste)
    echo "In your browser: log in at https://status.${DOMAIN:-<your-domain>}/dashboard,"
    echo "then DevTools -> Application -> Local Storage -> copy the JWT value"
    echo "(a long string starting 'ey...' with two dots)."
    read -rp "Paste token: " TOKEN
    TOKEN="$(echo "$TOKEN" | tr -d '[:space:]')"
    if ! looks_like_jwt "$TOKEN"; then
      echo "ERROR: that doesn't look like a session JWT (expected ey...x.y.z)." >&2
      exit 1
    fi
    save_token "$TOKEN"
    ;;

  --remote)
    echo "WARNING: --remote drives the python socket login, which only works"
    echo "against Uptime Kuma 1.x. On Kuma 2.x it times out - use --browser."
    : "${DROPLET_HOST:?Set DROPLET_HOST in .env}"
    read -rp "TOTP code: " TOTP_CODE
    # Use the kuma-init image (already on the server, has uptime-kuma-api);
    # resolve the compose network from the running mc container instead of
    # hardcoding a project name.
    TOKEN=$(ssh -i "$KEY" "deploy@${DROPLET_HOST}" "
      NET=\$(docker inspect mc --format '{{range \$k, \$v := .NetworkSettings.Networks}}{{\$k}}{{end}}' | head -1)
      IMG=\$(docker inspect kuma-init --format '{{.Config.Image}}' 2>/dev/null || echo ghcr.io/piprees/minecraft-server-template/kuma-init:latest)
      docker run --rm --network \"\$NET\" --entrypoint python3 \"\$IMG\" -c \"
from uptime_kuma_api import UptimeKumaApi
api = UptimeKumaApi(\\\"http://uptime-kuma:3001\\\", timeout=60)
result = api.login(\\\"${KUMA_USERNAME:-admin}\\\", \\\"${KUMA_PASSWORD}\\\", \\\"${TOTP_CODE}\\\")
print(result[\\\"token\\\"])
api.disconnect()
\"
    ")
    if ! looks_like_jwt "$TOKEN"; then
      echo "ERROR: Login failed (Kuma 2.x? use --browser). " >&2
      exit 1
    fi
    echo "Session token obtained."
    save_token "$TOKEN"
    ;;

  *)
    echo "Usage:"
    echo "  $0 --browser    Log in via a real browser window (works on Kuma 1.x and 2.x)"
    echo "  $0 --paste      Paste a session JWT you copied from the dashboard"
    echo "  $0 --remote     Legacy socket login (Kuma 1.x only)"
    echo ""
    echo "Kuma's management API is socket.io only and 2FA-gated; the session"
    echo "JWT this produces is the only headless credential. After any mode:"
    echo "  ./ops github-env-sync   # or the next CI deploy wipes the token"
    ;;
esac
