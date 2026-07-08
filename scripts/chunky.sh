#!/usr/bin/env bash
# chunky.sh - Check Chunky pre-generation status across all dimensions.
#
# Shows progress for any running task, completion markers, and the
# idle-tasks .skip-pause state. Works locally or via SSH to production.
#
# Usage:
#   ./scripts/chunky.sh                # auto-detect local/production
#   ./scripts/chunky.sh --remote       # force production
#   ./scripts/chunky.sh --local        # force local
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib.sh"
load_env

TARGET="auto"
case "${1:-}" in
  --remote) TARGET="remote"; shift ;;
  --local) TARGET="local"; shift ;;
esac

if [[ "$TARGET" == "auto" ]]; then
  if docker ps --format '{{.Names}}' 2> /dev/null | grep -qx mc; then
    TARGET="local"
  else
    TARGET="remote"
  fi
fi

# The check runs as a single heredoc over SSH (or locally via eval).
# No declare -f, no function transport — just flat commands.
read -r -d '' CHECK_SCRIPT << 'CHECKEOF' || true
echo "Chunky pre-generation status"
echo "=============================="
echo ""

echo "Active tasks:"
progress=$(docker exec mc rcon-cli "chunky progress" 2>/dev/null || echo "")
if [ -z "$progress" ]; then
  echo "  (RCON silent - server is autopaused)"
elif echo "$progress" | grep -qi "No tasks running"; then
  echo "  None (if .skip-pause was just created, the JVM may still be waking)"
else
  echo "$progress" | sed 's/^/  /'
fi

echo ""
echo "Completion markers:"
for pair in \
  ".chunky-complete Overworld" \
  ".chunky-nether-complete Nether" \
  ".chunky-end-complete End" \
  ".chunky-paradise-lost-complete Paradise_Lost"; do
  file="${pair%% *}"
  label="${pair#* }"
  label=$(echo "$label" | tr '_' ' ')
  if [ -f "data/${file}" ]; then
    echo "  ${label}: done"
  else
    echo "  ${label}: pending"
  fi
done

echo ""
echo "Autopause bypass:"
if [ -f "data/.skip-pause" ]; then
  echo "  .skip-pause present (autopause disabled for pre-gen)"
else
  echo "  .skip-pause absent (autopause active)"
fi

echo ""
echo "Idle-tasks:"
docker logs idle-tasks --tail 3 2>&1 | sed 's/^/  /'
CHECKEOF

if [[ "$TARGET" == "local" ]]; then
  eval "$CHECK_SCRIPT"
else
  : "${DROPLET_HOST:?Set DROPLET_HOST in .env (or run with --local)}"
  DEPLOY_USER="${DEPLOY_USER:-deploy}"
  SSH_KEY="$HOME/.ssh/${BRAND_SLUG:+${BRAND_SLUG}_}mc_deploy_key"

  # shellcheck disable=SC2029
  ssh -i "$SSH_KEY" "${DEPLOY_USER}@${DROPLET_HOST}" "cd ~/server && ${CHECK_SCRIPT}"
fi
