#!/usr/bin/env bash
# restart-service.sh - Backward-compatible wrapper for service.sh restart.
# Use service.sh directly for start/stop/status.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec bash "$SCRIPT_DIR/service.sh" restart "$@"
