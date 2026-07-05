#!/usr/bin/env bash
# test-build.sh — integration test for the modpack-builder image.
#
# Needs network access (Modrinth API). Gated behind MODPACK_TEST=1.
set -euo pipefail

[[ "${MODPACK_TEST:-}" == "1" ]] || { echo "Skipping (set MODPACK_TEST=1 to run)"; exit 0; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
TEST_DIR="$(mktemp -d)"
IMAGE_TAG="modpack-builder-test:$$"

cleanup() {
  rm -rf "$TEST_DIR"
  docker rmi "$IMAGE_TAG" 2>/dev/null || true
}
trap cleanup EXIT

echo "==> Building modpack-builder image..."
docker build -t "$IMAGE_TAG" -f "$SCRIPT_DIR/Dockerfile" "$PROJECT_DIR"

# --- fixture overlay ---------------------------------------------------------
# Add a client mod (continuity is already optional in the default manifest, so
# pick something not already listed) and remove one that IS listed.
OVERLAY="$TEST_DIR/overlay"
mkdir -p "$OVERLAY/modpack"

ADDED_MOD="cit-resewn"
REMOVED_MOD="untitled-duck-mod"

cat > "$OVERLAY/modpack/manifest.json" << PATCH
{
  "name": "Test Server Modpack",
  "add": {
    "required": ["${ADDED_MOD}"]
  },
  "remove": ["${REMOVED_MOD}"]
}
PATCH

DIST="$TEST_DIR/dist"
mkdir -p "$DIST"

echo "==> Running modpack-builder container..."
docker run --rm \
  -v "$OVERLAY:/overlay:ro" \
  -v "$DIST:/work/dist" \
  -e GIT_SHA=test123 \
  -e BRAND_NAME="Test Server" \
  -e BRAND_SLUG=testserver \
  -e DOMAIN=test.example.com \
  -e LOCAL_DOMAIN=local.test \
  -e SERVER_PORT=25577 \
  "$IMAGE_TAG"

# --- assertions --------------------------------------------------------------
echo ""
echo "==> Running assertions..."
FAIL=0

assert() {
  local desc="$1"
  shift
  if "$@"; then
    echo "  ✓ $desc"
  else
    echo "  ✗ $desc" >&2
    FAIL=1
  fi
}

# Merged manifest has the added mod
MERGED_INDEX="$DIST/modrinth.index.json"
assert "index.json exists" test -f "$MERGED_INDEX"

# The added mod should appear in the files array (by filename pattern)
assert "added mod ($ADDED_MOD) present in index" \
  python3 -c "
import json, sys
idx = json.load(open('$MERGED_INDEX'))
paths = [f['path'] for f in idx.get('files', [])]
sys.exit(0 if any('$ADDED_MOD' in p.lower().replace('-', '') for p in paths) else 1)
"

# The removed mod should NOT appear
assert "removed mod ($REMOVED_MOD) absent from index" \
  python3 -c "
import json, sys
idx = json.load(open('$MERGED_INDEX'))
paths = [f['path'] for f in idx.get('files', [])]
sys.exit(1 if any('$REMOVED_MOD' in p.lower().replace('-', '') for p in paths) else 0)
"

# Both .mrpack variants produced
assert "prod .mrpack exists" \
  test -f "$DIST/testserver-1.21.1-latest.mrpack"

assert "local .mrpack exists" \
  test -f "$DIST/testserver-1.21.1-local-latest.mrpack"

# The local pack has two server entries (prod + local)
LOCAL_PACK="$DIST/testserver-1.21.1-local-latest.mrpack"
LOCAL_CHECK="$(mktemp -d)"
(cd "$LOCAL_CHECK" && unzip -qo "$LOCAL_PACK" overrides/servers.dat 2>/dev/null)
assert "local pack servers.dat has local entry" \
  python3 -c "
import sys
data = open('$LOCAL_CHECK/overrides/servers.dat', 'rb').read()
sys.exit(0 if b'Local Dev' in data else 1)
"
assert "local pack servers.dat has prod entry" \
  python3 -c "
import sys
data = open('$LOCAL_CHECK/overrides/servers.dat', 'rb').read()
sys.exit(0 if b'mc.test.example.com' in data else 1)
"
rm -rf "$LOCAL_CHECK"

# The prod pack should have the prod entry but NOT the local dev one
PROD_PACK="$DIST/testserver-1.21.1-latest.mrpack"
PROD_CHECK="$(mktemp -d)"
(cd "$PROD_CHECK" && unzip -qo "$PROD_PACK" overrides/servers.dat 2>/dev/null)
assert "prod pack servers.dat has prod entry" \
  python3 -c "
import sys
data = open('$PROD_CHECK/overrides/servers.dat', 'rb').read()
sys.exit(0 if b'mc.test.example.com' in data else 1)
"
assert "prod pack servers.dat has NO local dev entry" \
  python3 -c "
import sys
data = open('$PROD_CHECK/overrides/servers.dat', 'rb').read()
sys.exit(1 if b'Local Dev' in data else 0)
"
rm -rf "$PROD_CHECK"

echo ""
if [[ $FAIL -eq 0 ]]; then
  echo "==> All assertions passed"
else
  echo "==> Some assertions FAILED" >&2
  exit 1
fi
