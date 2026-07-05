#!/usr/bin/env bash
# check-updates.sh - Check every mod (server list + client manifest) for
# updates on Modrinth, against the pinned version IDs in modrinth-mods.txt.
#
# Runs three ways:
#   - manually, anywhere
#   - daily at 06:00 UTC inside the mod-checker container (--html --discord),
#     which serves the page at mods.DOMAIN (paths fall back to /app/ there)
#   - from CI after deploys, when its inputs changed (deploy.yml)
#
# Bulk resolution goes through modrinth-api.py (one connection, rate-limit
# aware). Discord alerts are deduped by hashing the SET of mods with updates
# (.update-alert-hash in modpack/dist/) - re-alerts only when new mods join.
#
# Usage:
#   ./scripts/check-updates.sh              # terminal output
#   ./scripts/check-updates.sh --html       # also write modpack/dist/status.html
#   ./scripts/check-updates.sh --json       # JSON output (for scripting)
#   ./scripts/check-updates.sh --discord    # notify Discord if new updates (deduped)
#   ./scripts/check-updates.sh --fast       # skip "newest version" lookup (halves API calls)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="${CONSUMER_DIR:-$(cd "$SCRIPT_DIR/.." && pwd)}"

# Inside the mod-checker container, files are mounted under /app/.
# Fall back to /app/ paths if the project-relative paths don't exist.
if [[ -f "$PROJECT_DIR/config/modrinth-mods.txt" ]]; then
  cd "$PROJECT_DIR"
elif [[ -f /app/config/modrinth-mods.txt ]]; then
  PROJECT_DIR=/app
  cd /app
fi

# --- load .env ----------------------------------------------------------------
if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi

MC_VERSION="${MC_VERSION:-1.21.1}"
MODS_FILE="$PROJECT_DIR/config/modrinth-mods.txt"
MANIFEST="$PROJECT_DIR/modpack/adventure.mrpack.json"
STATUS_DIR="$PROJECT_DIR/modpack/dist"

OUTPUT_HTML=0
OUTPUT_JSON=0
NOTIFY_DISCORD=0
FAST_MODE=0
for arg in "$@"; do
  case "$arg" in
    --html) OUTPUT_HTML=1 ;;
    --json) OUTPUT_JSON=1 ;;
    --discord) NOTIFY_DISCORD=1 ;;
    --fast) FAST_MODE=1 ;;
  esac
done

if [[ ! -f "$MODS_FILE" ]]; then
  echo "Mod list not found at $MODS_FILE"
  exit 1
fi

if ! command -v python3 &> /dev/null; then
  echo "python3 required for API response parsing."
  exit 1
fi

# --- collect mod slugs and pinned version IDs from modrinth-mods.txt ----------
SLUGS=()
PINNED_IDS=()
while IFS= read -r line; do
  line="${line%%#*}"
  line="$(echo "$line" | xargs)"
  [[ -z "$line" ]] && continue
  [[ "$line" == datapack:* ]] && continue
  [[ "$line" == resourcepack:* ]] && continue
  entry="${line%\?}"
  slug="${entry%%:*}"
  pinned_id="${entry#*:}"
  [[ "$pinned_id" == "$slug" ]] && pinned_id=""
  SLUGS+=("$slug")
  PINNED_IDS+=("$pinned_id")
done < "$MODS_FILE"

# Also collect client-only mods from the manifest
if [[ -f "$MANIFEST" ]]; then
  CLIENT_SLUGS=$(python3 -c "
import json
with open('$MANIFEST') as f:
    data = json.load(f)
cm = data.get('_clientMods', {})
seen = set()
for m in cm.get('required', []) + cm.get('optional', []):
    if m not in seen:
        seen.add(m)
        print(m)
" 2> /dev/null || true)

  while IFS= read -r slug; do
    [[ -z "$slug" ]] && continue
    # Only add if not already in server list
    found=0
    for s in "${SLUGS[@]}"; do
      [[ "$s" == "$slug" ]] && found=1 && break
    done
    [[ $found -eq 0 ]] && SLUGS+=("$slug")
  done <<< "$CLIENT_SLUGS"
fi

echo "Checking ${#SLUGS[@]} mods against Modrinth API..."
echo "Target Minecraft version: $MC_VERSION"
echo ""

# --- query all mods via single Python process (connection reuse) --------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

API_INPUT=""
for i in "${!SLUGS[@]}"; do
  API_INPUT+="${SLUGS[$i]}	${PINNED_IDS[$i]:-}"$'\n'
done

API_EXTRA_ARGS=()
if [[ $FAST_MODE -eq 1 ]]; then
  API_EXTRA_ARGS+=("--fast")
fi
API_RESULTS=$(printf '%s' "$API_INPUT" | python3 "$SCRIPT_DIR/modrinth-api.py" check "$MC_VERSION" "${API_EXTRA_ARGS[@]}")

# --- collect results and display ----------------------------------------------
RESULTS=()
UPDATES_AVAILABLE=0
UPGRADE_PATH=0

for i in "${!SLUGS[@]}"; do
  result=$(echo "$API_RESULTS" | sed -n "$((i + 1))p")
  RESULTS+=("$result")

  status=$(echo "$result" | python3 -c "import sys,json; print(json.load(sys.stdin)['status'])" 2> /dev/null || echo "?")
  pinned_ver=$(echo "$result" | python3 -c "import sys,json; print(json.load(sys.stdin)['pinned_ver'])" 2> /dev/null || echo "?")
  latest_compat=$(echo "$result" | python3 -c "import sys,json; print(json.load(sys.stdin)['latest_compat_ver'])" 2> /dev/null || echo "?")
  newest_mc=$(echo "$result" | python3 -c "import sys,json; print(json.load(sys.stdin)['newest_mc'])" 2> /dev/null || echo "?")

  case "$status" in
    up-to-date)
      printf "  ✓ %-35s %s\n" "${SLUGS[$i]}" "$pinned_ver"
      ;;
    update-available)
      printf "  ^ %-35s %s > %s\n" "${SLUGS[$i]}" "$pinned_ver" "$latest_compat"
      UPDATES_AVAILABLE=$((UPDATES_AVAILABLE + 1))
      ;;
    no-compat-build)
      printf "  ⚠ %-35s no %s Fabric build (newest: %s)\n" "${SLUGS[$i]}" "$MC_VERSION" "$newest_mc"
      UPGRADE_PATH=$((UPGRADE_PATH + 1))
      ;;
    not-found)
      printf "  ✗ %-35s not found on Modrinth\n" "${SLUGS[$i]}"
      ;;
    api-error | *)
      printf "  ? %-35s API error\n" "${SLUGS[$i]}"
      ;;
  esac
done

# --- Summary ------------------------------------------------------------------
echo ""
echo "=================================================================="
echo "  ${#SLUGS[@]} mods checked against Minecraft $MC_VERSION"
echo "  $UPDATES_AVAILABLE patch updates available (same MC version)"
echo "  $UPGRADE_PATH mods have newer builds on a different MC version"
echo "=================================================================="

# --- JSON output --------------------------------------------------------------
if [[ $OUTPUT_JSON -eq 1 ]]; then
  echo ""
  echo "["
  for i in "${!RESULTS[@]}"; do
    [[ $i -gt 0 ]] && echo ","
    echo "  ${RESULTS[$i]}"
  done
  echo "]"
fi

# --- HTML status page ---------------------------------------------------------
if [[ $OUTPUT_HTML -eq 1 ]]; then
  mkdir -p "$STATUS_DIR"
  STATUS_FILE="$STATUS_DIR/status.html"
  rm -f "$STATUS_FILE" 2> /dev/null || true

  # "2nd July 2026 @ 23:35" - ordinal suffix computed here because neither
  # BSD nor busybox date can; %e (unlike %-d) works on both.
  CHECK_DAY=$(TZ='Europe/London' date '+%e' | xargs)
  case "$CHECK_DAY" in
    1 | 21 | 31) DAY_SUFFIX="st" ;;
    2 | 22) DAY_SUFFIX="nd" ;;
    3 | 23) DAY_SUFFIX="rd" ;;
    *) DAY_SUFFIX="th" ;;
  esac
  TIMESTAMP="${CHECK_DAY}${DAY_SUFFIX} $(TZ='Europe/London' date '+%B %Y @ %H:%M')"

  # Footer version - the same string as the pack page footer. Prefer the pack
  # build actually being served (dist/packwiz/pack.toml, written by
  # build-modpack.sh); git isn't available inside the mod-checker container.
  PACK_TOML="$STATUS_DIR/packwiz/pack.toml"
  PACK_VERSION=""
  if [[ -f "$PACK_TOML" ]]; then
    PACK_VERSION=$(sed -n 's/^version = "\([^"]*\)"$/\1/p' "$PACK_TOML" | head -1)
  fi
  if [[ -z "$PACK_VERSION" ]]; then
    PACK_VERSION=$(git rev-parse --short HEAD 2> /dev/null || echo unknown)
  fi
  PACK_NAME="${BRAND_SLUG:-adventure}-${MC_VERSION}-v${PACK_VERSION}"

  cat > "$STATUS_FILE" << 'HTMLHEAD'
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Mod Status</title>
    <meta name="description" content="Mod versions and update status for a private modded Minecraft adventure server.">
    <meta name="robots" content="noindex, nofollow">
    <meta name="theme-color" content="#0c1319">
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { font-family: system-ui, -apple-system, sans-serif; background: #0c1319; color: #c5cdd8; min-height: 100vh; display: flex; flex-direction: column; line-height: 1.6; padding-top: 44px; }
        a { color: #5a9a70; text-decoration: none; transition: color .15s, text-decoration-color .15s; }
        a:hover, a:focus { color: #70b088; text-decoration: underline; text-underline-offset: 2px; }
        main { max-width: 65ch; margin: 0 auto; padding: 3rem 1.5rem 1.5rem; width: 100%; }
        h1 { font-family: system-ui, -apple-system, sans-serif; font-size: clamp(1.5rem, 1rem + 1.5vw, 2rem); font-weight: 700; margin-bottom: 0.25rem; color: #e8ecf1; letter-spacing: 0.04em; text-wrap: balance; }
        .subtitle { color: #7a8999; margin-bottom: .25rem; font-size: 0.875rem; }
        .summary-text { color: #7a8999; font-size: 0.875rem; }
        .header-row { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 1em; margin-bottom: 1.25em; }
        .header-left { flex: 1; min-width: 200px; }
        .header-right { flex-shrink: 0; }
        .summary-text strong { font-weight: 600; }
        .summary-text .s-update { color: #d4950a; }
        .summary-text .s-nocompat { color: #c96a6a; }
        .table-wrap { overflow-x: auto; max-width: 100%; -webkit-overflow-scrolling: touch; }
        table { width: 100%; border-collapse: collapse; font-size: 0.875rem; }
        th { text-align: left; padding: 0.5rem 0.75rem; background: #141d27; color: #7a8999; font-weight: 600; font-size: 0.875rem; }
        tbody tr:nth-child(even) { background: rgba(122, 137, 153, .04); }
        label[for="mod-filter"] { display: block; font-size: 0.8rem; color: #7a8999; margin-bottom: 0.3rem; }
        .filter-box { width: 100%; max-width: 24rem; margin-bottom: 1rem; padding: .55rem .8rem; background: #141d27; border: 1px solid #2a3a4c; border-radius: 8px; color: #c5cdd8; font-size: 0.875rem; outline: none; }
        .filter-box:focus { border-color: #5a9a70; }
        .filter-box::placeholder { color: #7a8999; }
        td { padding: 0.5rem 0.75rem; border-bottom: 1px solid #1c2835; }
        tr:hover { background: rgba(122, 137, 153, .06); }
        .dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; vertical-align: 1px; margin-right: 0.4rem; flex-shrink: 0; }
        .dot.up-to-date { background: #5a9a70; }
        .dot.update-available { background: #d4950a; }
        .dot.no-compat-build { background: #c96a6a; }
        .dot.not-found { background: #7a8999; }
        .dot.api-error { background: #7a8999; }
        .footer { text-align: center; padding: 1.5rem; font-size: 0.8rem; color: #7a8999; margin-top: auto; }
    </style>
</head>
HTMLHEAD

  # Count statuses
  count_ok=0
  count_update=0
  count_nocompat=0
  count_other=0
  for r in "${RESULTS[@]}"; do
    s=$(echo "$r" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('status','?'))" 2> /dev/null || echo "?")
    case "$s" in
      up-to-date) count_ok=$((count_ok + 1)) ;;
      update-available) count_update=$((count_update + 1)) ;;
      no-compat-build) count_nocompat=$((count_nocompat + 1)) ;;
      *) count_other=$((count_other + 1)) ;;
    esac
  done

  cat >> "$STATUS_FILE" << HTMLBODY
<body>
    <main>
    <h1>Mods</h1>
    <div class="header-row">
        <div class="header-left">
            <p class="subtitle">Checked $TIMESTAMP</p>
            <p class="summary-text"><strong class="s-update">$count_update</strong> outdated, <strong class="s-nocompat">$count_nocompat</strong> incompatible</p>
        </div>
        <div class="header-right">
            <label for="mod-filter">Filter by name or status</label>
            <input class="filter-box" type="search" id="mod-filter" placeholder="e.g. sodium, update available..." autocomplete="off">
        </div>
    </div>

    <div class="table-wrap">
    <table>
        <thead>
            <tr>
                <th>Mod</th>
                <th>Pinned ($MC_VERSION)</th>
                <th>Latest ($MC_VERSION)</th>
            </tr>
        </thead>
        <tbody>
HTMLBODY

  # Sort results: updates first, then no-compat, then up-to-date
  SORTED_RESULTS=()
  for status_filter in update-available no-compat-build not-found api-error up-to-date; do
    for r in "${RESULTS[@]}"; do
      s=$(echo "$r" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status',''))" 2> /dev/null || echo "")
      [[ "$s" == "$status_filter" ]] && SORTED_RESULTS+=("$r")
    done
  done

  for r in "${SORTED_RESULTS[@]}"; do
    row_tmpfile="$(mktemp)"
    echo "$r" > "$row_tmpfile"
    MC_VER="$MC_VERSION" python3 -c "
import json, sys, os

with open('$row_tmpfile') as f:
    d = json.load(f)

mc_ver = os.environ.get('MC_VER', '1.21.1')
dash = '-'

badge_class = d['status']
badge_text = d['status'].replace('-', ' ').title()
if d['status'] == 'no-compat-build':
    badge_text = f'No {mc_ver} Build'
elif d['status'] == 'up-to-date':
    badge_text = 'Up to Date'
elif d['status'] == 'update-available':
    badge_text = 'Update Available'

slug = d['slug']
url = d.get('url', '')
pinned = d.get('pinned_ver') or dash
pinned_id = d.get('pinned_id') or ''
latest_compat = d.get('latest_compat_ver') or dash
latest_id = d.get('latest_compat_id') or ''
newest = d.get('newest_ver') or dash
newest_id = d.get('newest_id') or ''
newest_mc = d.get('newest_mc') or ''

def ver_link(ver, vid):
    if ver == dash:
        return ver
    if vid:
        return f'<a href=\"https://modrinth.com/mod/{slug}/version/{vid}\">{ver}</a>'
    if url:
        return f'<a href=\"{url}/versions\">{ver}</a>'
    return ver

pinned_html = ver_link(pinned, pinned_id)
latest_html = ver_link(latest_compat, latest_id)
newest_mc_html = newest_mc if newest_mc else dash

print(f'''            <tr data-status=\"{badge_class}\">
                <td><span class=\"dot {badge_class}\" role=\"img\" aria-label=\"{badge_text}\" title=\"{badge_text}\"></span><strong><a href=\"{url}\" style=\"color:#e8ecf1\">{slug}</a></strong></td>
                <td>{pinned_html}</td>
                <td>{latest_html}</td>
            </tr>''')
" 2> /dev/null >> "$STATUS_FILE" || true
    rm -f "$row_tmpfile"
  done

  cat >> "$STATUS_FILE" << HTMLTABLEEND
        </tbody>
    </table>
    </div>
    </main>
    <footer class="footer">
        ${PACK_NAME}
    </footer>
HTMLTABLEEND

  cat >> "$STATUS_FILE" << 'HTMLFOOT'
    <script>
    (function () {
        var box = document.getElementById('mod-filter');
        var rows = Array.prototype.slice.call(document.querySelectorAll('tbody tr'));
        box.addEventListener('input', function () {
            var q = box.value.toLowerCase();
            rows.forEach(function (r) {
                var haystack = (r.textContent + ' ' + (r.getAttribute('data-status') || '').replace(/-/g, ' ')).toLowerCase();
                r.style.display = haystack.indexOf(q) === -1 ? 'none' : '';
            });
        });
    })();
    </script>
</body>
</html>
HTMLFOOT

  echo ""
  echo "Status page written to: $STATUS_FILE"
  echo "View at: https://mods.${DOMAIN:-example.com}"
fi

# --- Discord notification (only when new updates appear) ----------------------
if [[ $NOTIFY_DISCORD -eq 1 && $UPDATES_AVAILABLE -gt 0 ]]; then
  ALERT_HASH_FILE="$STATUS_DIR/.update-alert-hash"
  mkdir -p "$STATUS_DIR"

  # Build sorted list of mods with updates
  UPDATE_SLUGS=""
  UPDATE_LINES=""
  for r in "${RESULTS[@]}"; do
    info=$(echo "$r" | python3 -c "
import sys, json
d = json.load(sys.stdin)
if d.get('status') == 'update-available':
    print(f\"{d['slug']}|{d.get('pinned_ver','')} → {d.get('latest_compat_ver','')}\")
" 2> /dev/null || true)
    if [[ -n "$info" ]]; then
      slug="${info%%|*}"
      versions="${info#*|}"
      UPDATE_SLUGS="${UPDATE_SLUGS}${slug}\n"
      UPDATE_LINES="${UPDATE_LINES}• \`${slug}\` ${versions}\n"
    fi
  done

  # Hash the set of mods with updates (not versions - so re-alerts if new mods join)
  CURRENT_HASH=$(printf '%b' "$UPDATE_SLUGS" | sort | sha256sum | cut -d' ' -f1)
  PREVIOUS_HASH=$(cat "$ALERT_HASH_FILE" 2> /dev/null || echo "none")

  if [[ "$CURRENT_HASH" != "$PREVIOUS_HASH" ]]; then
    echo ""
    echo "New updates detected - notifying Discord..."

    WEBHOOK_URL="${DISCORD_WEBHOOK_URL:-}"
    ADMIN_ROLE_ID="${DISCORD_ADMIN_ROLE_ID:-}"
    MESSAGES_FILE="$PROJECT_DIR/config/messages.json"

    if [[ -n "$WEBHOOK_URL" && -f "$MESSAGES_FILE" ]]; then
      if [[ -n "$ADMIN_ROLE_ID" ]]; then
        ADMIN_PING="<@&${ADMIN_ROLE_ID}> "
      else
        ADMIN_PING=""
      fi

      MOD_LIST=$(printf '%b' "$UPDATE_LINES")
      MSG=$(python3 -c "
import json
msg = json.load(open('$MESSAGES_FILE'))['updates.available']
msg = msg.replace('{admin_ping}', '''$ADMIN_PING''')
msg = msg.replace('{count}', '$UPDATES_AVAILABLE')
msg = msg.replace('{mod_list}', '''$MOD_LIST''')
print(msg)
" 2> /dev/null || echo "")

      if [[ -n "$MSG" ]]; then
        if [[ -n "$ADMIN_ROLE_ID" ]]; then
          PAYLOAD=$(echo "$MSG" | jq -Rs --arg rid "$ADMIN_ROLE_ID" \
            '{content: ., allowed_mentions: {roles: [$rid]}}')
        else
          PAYLOAD=$(echo "$MSG" | jq -Rs '{content: .}')
        fi
        curl -s -H "Content-Type: application/json" \
          -d "$PAYLOAD" "$WEBHOOK_URL" || true
      fi
    else
      echo "Skipping Discord notification: DISCORD_WEBHOOK_URL not set or messages.json missing"
    fi

    # Save the hash so we don't re-alert for the same set
    echo "$CURRENT_HASH" > "$ALERT_HASH_FILE"
  else
    echo ""
    echo "Updates unchanged since last alert - skipping Discord notification"
  fi
elif [[ $NOTIFY_DISCORD -eq 1 && $UPDATES_AVAILABLE -eq 0 ]]; then
  # Clear the hash when everything is up to date, so next update triggers an alert
  rm -f "$STATUS_DIR/.update-alert-hash" 2> /dev/null || true
fi
