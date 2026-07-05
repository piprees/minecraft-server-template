#!/usr/bin/env bash
# backup-now.sh - Trigger an immediate backup via the mc-backup sidecar.
#
# Instead of running restic directly (which creates snapshots with different
# paths/tags/excludes than the sidecar), this restarts the mc-backup container
# which triggers an immediate backup cycle using the same config as scheduled
# backups. One backup system, one set of excludes, one retention policy.
#
# Usage:
#   ./scripts/backup-now.sh              # verbose
#   ./scripts/backup-now.sh --quiet      # minimal output
set -euo pipefail

QUIET=0
[[ "${1:-}" == "--quiet" ]] && QUIET=1

log() { [[ $QUIET -eq 0 ]] && echo "$@" || true; }

if ! docker ps --format '{{.Names}}' | grep -q '^mc-backup$'; then
  echo "mc-backup container is not running. Start with: docker compose --profile cloud up -d"
  exit 1
fi

log "Triggering backup via mc-backup sidecar (restart)..."
docker restart mc-backup
log "mc-backup restarted - it will run a backup after INITIAL_DELAY (2m)."
log "Watch progress: docker logs -f mc-backup"
