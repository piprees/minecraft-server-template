#!/usr/bin/env bash
# discord-cleanup.sh - Delete all messages from our bot/apps in a Discord channel.
#
# Fetches messages authored by the bot, bulk-deletes those under 14 days old,
# and individually deletes older ones (respecting rate limits).
#
# Usage:
#   ./scripts/discord-cleanup.sh                    # uses DISCORD_CHANNEL_ID from .env
#   ./scripts/discord-cleanup.sh <channel_id>       # specify a channel
#   ./scripts/discord-cleanup.sh --dry-run           # show what would be deleted
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="${CONSUMER_DIR:-$(cd "$SCRIPT_DIR/.." && pwd)}"

if [[ -f "$PROJECT_DIR/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$PROJECT_DIR/.env"
  set +a
fi

CHANNEL_ID="${1:-${DISCORD_CHANNEL_ID:-}}"
DRY_RUN=0
[[ "${1:-}" == "--dry-run" ]] && DRY_RUN=1 && CHANNEL_ID="${DISCORD_CHANNEL_ID:-}"

: "${DISCORD_BOT_TOKEN:?Set DISCORD_BOT_TOKEN in .env}"
: "${CHANNEL_ID:?Provide a channel ID as argument or set DISCORD_CHANNEL_ID in .env}"

API="https://discord.com/api/v10"
AUTH="Bot ${DISCORD_BOT_TOKEN}"

# Get our bot's user ID
BOT_USER_ID=$(curl -s -H "Authorization: $AUTH" "$API/users/@me" | python3 -c "import json,sys; print(json.load(sys.stdin)['id'])")
echo "Bot user ID: $BOT_USER_ID"
echo "Channel: $CHANNEL_ID"

# Also get webhook IDs owned by us (dcintegration uses webhooks)
WEBHOOK_IDS=()
while IFS= read -r wid; do
  [[ -n "$wid" ]] && WEBHOOK_IDS+=("$wid")
done < <(curl -s -H "Authorization: $AUTH" "$API/channels/$CHANNEL_ID/webhooks" | \
  python3 -c "import json,sys
hooks = json.load(sys.stdin)
if isinstance(hooks, list):
    for h in hooks:
        print(h['id'])
" 2>/dev/null || true)

echo "Webhook IDs: ${WEBHOOK_IDS[*]:-none}"
echo ""

# Fetch all messages and filter to bot/webhook authored ones
BEFORE=""
TOTAL=0
DELETED=0
BULK_IDS=()
OLD_IDS=()
FOURTEEN_DAYS_AGO=$(python3 -c "import time; print(int((time.time() - 14*86400) * 1000 - 1420070400000) << 22)")

while true; do
  URL="$API/channels/$CHANNEL_ID/messages?limit=100"
  [[ -n "$BEFORE" ]] && URL="${URL}&before=$BEFORE"

  MESSAGES=$(curl -s -H "Authorization: $AUTH" "$URL")
  COUNT=$(echo "$MESSAGES" | python3 -c "import json,sys; msgs=json.load(sys.stdin); print(len(msgs) if isinstance(msgs, list) else 0)")

  if [[ "$COUNT" -eq 0 ]]; then
    break
  fi

  # Filter to our bot's messages and webhook messages
  BOT_IDS_STR="${WEBHOOK_IDS[*]:-}"
  FILTERED=$(echo "$MESSAGES" | python3 -c "
import json, sys
msgs = json.load(sys.stdin)
bot_id = '$BOT_USER_ID'
webhook_ids = set('$BOT_IDS_STR'.split())
fourteen_days = int('$FOURTEEN_DAYS_AGO')
for m in msgs:
    is_ours = m['author']['id'] == bot_id or m['author']['id'] in webhook_ids or m.get('webhook_id','') in webhook_ids
    if is_ours:
        age = 'recent' if int(m['id']) > fourteen_days else 'old'
        content = m.get('content', '')[:60].replace(chr(10), ' ')
        print(m['id'] + '|' + age + '|' + content)
")

  while IFS='|' read -r mid age content; do
    [[ -z "$mid" ]] && continue
    TOTAL=$((TOTAL + 1))
    if [[ "$age" == "recent" ]]; then
      BULK_IDS+=("$mid")
    else
      OLD_IDS+=("$mid")
    fi
    if [[ $DRY_RUN -eq 1 ]]; then
      echo "  [$age] $mid: $content"
    fi
  done <<< "$FILTERED"

  BEFORE=$(echo "$MESSAGES" | python3 -c "import json,sys; msgs=json.load(sys.stdin); print(msgs[-1]['id'] if isinstance(msgs, list) and msgs else '')")
  [[ -z "$BEFORE" ]] && break
done

echo "Found $TOTAL bot/webhook messages (${#BULK_IDS[@]} recent, ${#OLD_IDS[@]} old)"

if [[ $DRY_RUN -eq 1 ]]; then
  echo "(dry run - nothing deleted)"
  exit 0
fi

if [[ $TOTAL -eq 0 ]]; then
  echo "Nothing to delete."
  exit 0
fi

# Bulk delete recent messages (under 14 days, up to 100 at a time)
if [[ ${#BULK_IDS[@]} -gt 0 ]]; then
  echo "Bulk-deleting ${#BULK_IDS[@]} recent messages..."
  for ((i=0; i<${#BULK_IDS[@]}; i+=100)); do
    BATCH=("${BULK_IDS[@]:i:100}")
    if [[ ${#BATCH[@]} -eq 1 ]]; then
      curl -s -X DELETE -H "Authorization: $AUTH" "$API/channels/$CHANNEL_ID/messages/${BATCH[0]}" > /dev/null
    else
      JSON_ARRAY=$(printf '%s\n' "${BATCH[@]}" | python3 -c "import json,sys; print(json.dumps([l.strip() for l in sys.stdin if l.strip()]))")
      HTTP=$(curl -s -o /dev/null -w "%{http_code}" -X POST \
        -H "Authorization: $AUTH" -H "Content-Type: application/json" \
        -d "{\"messages\": $JSON_ARRAY}" \
        "$API/channels/$CHANNEL_ID/messages/bulk-delete")
      if [[ "$HTTP" == "429" ]]; then
        echo "  Rate limited, waiting 5s..."
        sleep 5
        curl -s -X POST -H "Authorization: $AUTH" -H "Content-Type: application/json" \
          -d "{\"messages\": $JSON_ARRAY}" \
          "$API/channels/$CHANNEL_ID/messages/bulk-delete" > /dev/null
      fi
    fi
    DELETED=$((DELETED + ${#BATCH[@]}))
    echo "  Deleted $DELETED / $TOTAL"
  done
fi

# Individually delete old messages (over 14 days)
if [[ ${#OLD_IDS[@]} -gt 0 ]]; then
  echo "Deleting ${#OLD_IDS[@]} old messages (one at a time, rate-limited)..."
  for mid in "${OLD_IDS[@]}"; do
    HTTP=$(curl -s -o /dev/null -w "%{http_code}" -X DELETE \
      -H "Authorization: $AUTH" \
      "$API/channels/$CHANNEL_ID/messages/$mid")
    DELETED=$((DELETED + 1))
    if [[ "$HTTP" == "429" ]]; then
      echo "  Rate limited at $DELETED / $TOTAL, waiting 2s..."
      sleep 2
      curl -s -X DELETE -H "Authorization: $AUTH" "$API/channels/$CHANNEL_ID/messages/$mid" > /dev/null
    fi
    if (( DELETED % 10 == 0 )); then
      echo "  Deleted $DELETED / $TOTAL"
      sleep 1
    fi
  done
fi

echo ""
echo "Done. Deleted $DELETED messages."
