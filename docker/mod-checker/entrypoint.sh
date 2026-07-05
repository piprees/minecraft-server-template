#!/usr/bin/env bash
set -euo pipefail

echo "Running initial mod update check..."
bash /app/scripts/check-updates.sh --html --discord

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
