#!/usr/bin/env bash
# game-log.sh - Log snapshots from production (never streams).
#
# Two sources, one command:
#   ./ops logs [service] [--tail N] [--grep PATTERN]   # docker logs snapshot
#   ./ops logs --latest  [--tail N] [--grep PATTERN]   # RAW game log file
#
# The console (docker logs / live-logs) is filtered by log4j2-adventure.xml
# to keep eyes on signal; the file appender is UNFILTERED, so --latest
# (data/logs/latest.log) shows every error the console filters hide.
# latest.log lifecycle: reset on every boot (OnStartupTriggeringPolicy),
# rolled at 10MB/daily into gzips beside it, deleted after 3 days - it is
# never unbounded.
#
# Examples:
#   ./ops logs                                # mc console, last 200 lines
#   ./ops logs nav-proxy --tail 50            # any service
#   ./ops logs mc --tail 300 --grep ERROR     # filtered console snapshot
#   ./ops logs --latest --grep 'CME|Exception' # unfiltered raw game log
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib.sh"
load_env

: "${DROPLET_HOST:?Set DROPLET_HOST in .env}"
DEPLOY_USER="${DEPLOY_USER:-deploy}"
SSH_KEY="${DOCTOR_SSH_KEY:-$HOME/.ssh/${BRAND_SLUG:+${BRAND_SLUG}_}mc_deploy_key}"

SERVICE=""
LATEST=0
TAIL=200
PATTERN=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --latest) LATEST=1 ;;
    --tail)
      TAIL="${2:-}"
      shift
      ;;
    --grep)
      PATTERN="${2:-}"
      shift
      ;;
    -*)
      echo "Usage: ./ops logs [service|--latest] [--tail N] [--grep PATTERN]" >&2
      exit 1
      ;;
    *) SERVICE="$1" ;;
  esac
  shift
done

if ! [[ "$TAIL" =~ ^[0-9]+$ ]]; then
  echo "--tail expects a number (got: $TAIL)" >&2
  exit 1
fi

if [[ $LATEST -eq 1 ]]; then
  # Raw unfiltered game log file. Filter server-side (it can be large).
  SRC="cat ~/server/data/logs/latest.log"
else
  SERVICE="${SERVICE:-mc}"
  SRC="docker logs ${SERVICE} --tail $((TAIL * 4)) 2>&1"
fi

if [[ -n "$PATTERN" ]]; then
  PATTERN_Q=$(printf '%q' "$PATTERN")
  REMOTE="$SRC | grep -E -- $PATTERN_Q | tail -n $TAIL"
else
  REMOTE="$SRC | tail -n $TAIL"
fi

ssh -i "$SSH_KEY" -o ConnectTimeout=10 -o BatchMode=yes \
  "${DEPLOY_USER}@${DROPLET_HOST}" "$REMOTE" \
  || { echo "(no matches, or source unreadable)" >&2; exit 1; }
