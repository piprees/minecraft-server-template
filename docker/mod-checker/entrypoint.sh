#!/usr/bin/env bash
set -euo pipefail

export STATE_DIR="/app/state"
mkdir -p "$STATE_DIR"

# Boot check refreshes the status page only — NO --discord. The container
# is force-recreated on every deploy, so a boot-time ping means an @Admin
# ping per deploy; update notifications belong to the scheduled daily run
# below (per-version deduped) and the weekly CI re-pin PR.
echo "Running initial mod update check (page refresh only)..."
bash /app/scripts/check-updates.sh --html

echo "Next check in 24h. Running daily at 06:00 UTC."
while true; do
  now=$(date +%s)
  target=$(date -d "tomorrow 06:00" +%s 2>/dev/null \
    || date -v+1d -v6H -v0M -v0S +%s 2>/dev/null \
    || echo $((now + 86400)))
  delay=$((target - now))
  [ "$delay" -lt 60 ] && delay=86400
  sleep "$delay"
  echo "[$(date '+%Y-%m-%d %H:%M:%S')] Running daily mod update check..."
  bash /app/scripts/check-updates.sh --html --discord
done
