#!/usr/bin/env bash
# discord-notify.sh - Send a message to the Discord webhook.
#
# Usage:
#   ./scripts/discord-notify.sh "Your message here"
#   ./scripts/discord-notify.sh --key deploy.starting
#   ./scripts/discord-notify.sh --key modpack.updated pack_name="adventure-1.21.1-v200735a"
#
# With --key, looks up the message template from config/messages.json and
# substitutes {variable} placeholders with key=value arguments.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="${CONSUMER_DIR:-$(cd "$SCRIPT_DIR/.." && pwd)}"
# Resolution order: consumer overlay override, the stack bundle copy
# (this script lives in .stack/current/stack/scripts/), then a platform
# checkout's config/ - consumers have no config/ dir of their own.
MESSAGES_FILE="$PROJECT_DIR/overlay/config/messages.json"
[[ -f "$MESSAGES_FILE" ]] || MESSAGES_FILE="$SCRIPT_DIR/../config/messages.json"
[[ -f "$MESSAGES_FILE" ]] || MESSAGES_FILE="$PROJECT_DIR/config/messages.json"

WEBHOOK_URL="${DISCORD_WEBHOOK_URL:-}"

if [[ -z "$WEBHOOK_URL" ]]; then
  echo "DISCORD_WEBHOOK_URL not set - skipping Discord notification" >&2
  exit 0
fi

# --- resolve message ----------------------------------------------------------
MESSAGE=""

if [[ "${1:-}" == "--key" ]]; then
  KEY="${2:-}"
  if [[ -z "$KEY" ]]; then
    echo "Usage: discord-notify.sh --key <message.key> [var=value ...]" >&2
    exit 1
  fi
  shift 2

  if [[ ! -f "$MESSAGES_FILE" ]]; then
    echo "Messages file not found: $MESSAGES_FILE" >&2
    exit 1
  fi

  if command -v python3 &> /dev/null; then
    MESSAGE=$(python3 -c "
import json, sys
with open('$MESSAGES_FILE') as f:
    msgs = json.load(f)
msg = msgs.get('$KEY', '')
if not msg:
    print('Unknown message key: $KEY', file=sys.stderr)
    sys.exit(1)
# Substitute {variable} placeholders from remaining args
for arg in sys.argv[1:]:
    if '=' in arg:
        k, v = arg.split('=', 1)
        msg = msg.replace('{' + k + '}', v)
print(msg)
" "$@")
  elif command -v jq &> /dev/null; then
    MESSAGE=$(jq -r --arg key "$KEY" '.[$key] // empty' "$MESSAGES_FILE")
    if [[ -z "$MESSAGE" ]]; then
      echo "Unknown message key: $KEY" >&2
      exit 1
    fi
    for arg in "$@"; do
      if [[ "$arg" == *=* ]]; then
        var="${arg%%=*}"
        val="${arg#*=}"
        MESSAGE="${MESSAGE//\{$var\}/$val}"
      fi
    done
  else
    echo "python3 or jq required for --key mode" >&2
    exit 1
  fi
else
  MESSAGE="${1:-}"
fi

if [[ -z "$MESSAGE" ]]; then
  echo "Usage: discord-notify.sh <message>" >&2
  echo "       discord-notify.sh --key <message.key> [var=value ...]" >&2
  exit 1
fi

# --- build allowed_mentions for role pings ------------------------------------
ROLE_IDS=()
if [[ "$MESSAGE" =~ \<@\& ]]; then
  while [[ "$MESSAGE" =~ \<@\&([0-9]+)\> ]]; do
    ROLE_IDS+=("${BASH_REMATCH[1]}")
    MESSAGE="${MESSAGE//${BASH_REMATCH[0]}/%%ROLE_DONE_${BASH_REMATCH[1]}%%}"
  done
  for rid in "${ROLE_IDS[@]}"; do
    MESSAGE="${MESSAGE//%%ROLE_DONE_${rid}%%/<@&${rid}>}"
  done
fi

# --- send ---------------------------------------------------------------------
if command -v jq &> /dev/null; then
  if [[ ${#ROLE_IDS[@]} -gt 0 ]]; then
    ROLES_JSON=$(printf '%s\n' "${ROLE_IDS[@]}" | jq -R . | jq -s .)
    PAYLOAD=$(jq -n --arg msg "$MESSAGE" --argjson roles "$ROLES_JSON" \
      '{content: $msg, allowed_mentions: {roles: $roles}}')
  else
    PAYLOAD=$(jq -n --arg msg "$MESSAGE" '{content: $msg}')
  fi
else
  MESSAGE="${MESSAGE//\\/\\\\}"
  MESSAGE="${MESSAGE//\"/\\\"}"
  if [[ ${#ROLE_IDS[@]} -gt 0 ]]; then
    ROLES_CSV=$(printf '"%s",' "${ROLE_IDS[@]}")
    ROLES_CSV="[${ROLES_CSV%,}]"
    PAYLOAD="{\"content\": \"${MESSAGE}\", \"allowed_mentions\": {\"roles\": ${ROLES_CSV}}}"
  else
    PAYLOAD="{\"content\": \"${MESSAGE}\"}"
  fi
fi

HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
  -H "Content-Type: application/json" \
  -d "$PAYLOAD" \
  "$WEBHOOK_URL" 2> /dev/null || echo "000")

if [[ "$HTTP_CODE" == "204" || "$HTTP_CODE" == "200" ]]; then
  exit 0
else
  echo "Discord webhook returned HTTP ${HTTP_CODE}" >&2
  exit 1
fi
