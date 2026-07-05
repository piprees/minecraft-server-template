#!/usr/bin/env bash
set -euo pipefail

IMAGE_NAME="defaults-seed-test"
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
FIXTURES="$REPO_ROOT/docker/defaults-seed/fixtures/overlay-basic"

cleanup() {
  rm -rf "${TMPDIR:-}"
}
trap cleanup EXIT

echo "==> Building defaults-seed image..."
docker build -t "$IMAGE_NAME" -f "$REPO_ROOT/docker/defaults-seed/Dockerfile" "$REPO_ROOT"

TMPDIR="$(mktemp -d)"
OUT_CONFIG="$TMPDIR/out-config"
OUT_MODS="$TMPDIR/out-mods"
mkdir -p "$OUT_CONFIG" "$OUT_MODS"

pass=0
fail=0

assert_eq() {
  local label="$1" actual="$2" expected="$3"
  if [[ "$actual" == "$expected" ]]; then
    echo "  PASS: $label"
    pass=$((pass + 1))
  else
    echo "  FAIL: $label"
    echo "    expected: $expected"
    echo "    actual:   $actual"
    fail=$((fail + 1))
  fi
}

assert_contains() {
  local label="$1" file="$2" pattern="$3"
  if grep -qF "$pattern" "$file"; then
    echo "  PASS: $label"
    pass=$((pass + 1))
  else
    echo "  FAIL: $label — '$pattern' not found in $file"
    fail=$((fail + 1))
  fi
}

assert_not_contains() {
  local label="$1" file="$2" pattern="$3"
  if ! grep -qF "$pattern" "$file"; then
    echo "  PASS: $label"
    pass=$((pass + 1))
  else
    echo "  FAIL: $label — '$pattern' unexpectedly found in $file"
    fail=$((fail + 1))
  fi
}

echo "==> Test 1: overlay merge with add/remove"
rm -rf "${OUT_CONFIG:?}"/* "${OUT_MODS:?}"/*

docker run --rm \
  -v "$FIXTURES:/overlay:ro" \
  -v "$OUT_CONFIG:/out/config" \
  -v "$OUT_MODS:/out/mods" \
  "$IMAGE_NAME"

assert_eq "overlay config file exists" "$(cat "$OUT_CONFIG/test-override.txt" 2>/dev/null)" "overlay wins"
assert_not_contains "removed slug absent" "$OUT_MODS/modrinth-mods.txt" "krypton"
assert_contains "extra mod present" "$OUT_MODS/modrinth-mods.txt" "extra-mod:version1"

echo "==> Test 2: idempotency (byte-identical output)"
hash1=$(sha256sum "$OUT_MODS/modrinth-mods.txt" | cut -d' ' -f1)
rm -rf "${OUT_CONFIG:?}"/* "${OUT_MODS:?}"/*

docker run --rm \
  -v "$FIXTURES:/overlay:ro" \
  -v "$OUT_CONFIG:/out/config" \
  -v "$OUT_MODS:/out/mods" \
  "$IMAGE_NAME"

hash2=$(sha256sum "$OUT_MODS/modrinth-mods.txt" | cut -d' ' -f1)
assert_eq "deterministic output" "$hash1" "$hash2"

echo "==> Test 3: empty overlay reproduces pure defaults"
EMPTY_OVERLAY="$TMPDIR/empty-overlay"
mkdir -p "$EMPTY_OVERLAY"
rm -rf "${OUT_CONFIG:?}"/* "${OUT_MODS:?}"/*

docker run --rm \
  -v "$EMPTY_OVERLAY:/overlay:ro" \
  -v "$OUT_CONFIG:/out/config" \
  -v "$OUT_MODS:/out/mods" \
  "$IMAGE_NAME"

if [[ -f "$OUT_CONFIG/test-override.txt" ]]; then
  echo "  FAIL: test-override.txt should not exist in pure defaults"
  fail=$((fail + 1))
else
  echo "  PASS: no test-override in pure defaults (file absent)"
  pass=$((pass + 1))
fi

assert_eq "pure defaults has 0 removed 0 added" \
  "$(docker run --rm \
    -v "$EMPTY_OVERLAY:/overlay:ro" \
    -v "$OUT_CONFIG:/out/config" \
    -v "$OUT_MODS:/out/mods" \
    "$IMAGE_NAME" 2>&1 | grep -oE '[0-9]+ removed, [0-9]+ added')" \
  "0 removed, 0 added"

echo ""
echo "Results: $pass passed, $fail failed"

if [[ "$fail" -gt 0 ]]; then
  exit 1
fi

echo "All tests passed."
