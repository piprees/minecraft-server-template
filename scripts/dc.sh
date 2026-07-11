#!/usr/bin/env bash
# dc.sh — Safe docker compose wrapper for the production server.
#
# Blocks bare `docker compose up -d` when mc is running — that recreates
# mc without the countdown/kick/save dance from deploy.sh, dropping
# players mid-session (happened 2026-07-01, architecture trap 9).
#
# Install on the server: copy to ~/server/dc, add ~/server to PATH.
# Then `dc up -d` is safe; deploy.sh and infra-deploy.sh bypass this
# by calling docker compose directly.
#
# Usage:
#   dc up -d                    # blocked if mc is running
#   dc up -d --no-recreate      # allowed (won't touch mc)
#   dc up -d --force-recreate   # allowed (explicit intent)
#   dc logs mc --tail 50        # pass-through for everything else
set -euo pipefail

if [[ "$*" == *"up -d"* || "$*" == *"up -d "* ]]; then
  if ! [[ "$*" == *"--no-recreate"* || "$*" == *"--force-recreate"* ]]; then
    if docker ps --format '{{.Names}}' | grep -q '^mc$'; then
      echo "BLOCKED: bare 'up -d' would recreate mc without the countdown/kick/save dance."
      echo "Players would be dropped mid-session."
      echo ""
      echo "Options:"
      echo "  dc up -d --no-recreate      # update sidecars, leave mc alone"
      echo "  ./scripts/deploy.sh         # full deploy with countdown"
      echo "  dc up -d --force-recreate   # explicit override (you know what you're doing)"
      exit 1
    fi
  fi
fi

exec docker compose "$@"
