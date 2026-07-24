#!/usr/bin/env bash
# test-scripts.sh - Validate server scripts against an Ubuntu Docker container.
#
# Spins up an Ubuntu 24.04 container, simulates a fresh VPS, and runs the
# hardening + deployment scripts to verify they work. Cloudflare tunnel
# parts are skipped (no real tunnel in test).
#
# This gives us a formal verification path without needing a real cloud
# server. The container gets Docker-in-Docker for compose testing.
#
# Usage:
#   ./scripts/test-scripts.sh              # full test suite
#   ./scripts/test-scripts.sh --quick      # syntax + lint only (no container)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

TEST_CONTAINER="${BRAND_SLUG:-adventure}-test-vps"
TEST_IMAGE="ubuntu:24.04"
QUICK=0

for arg in "$@"; do
  [[ "$arg" == "--quick" ]] && QUICK=1
done

cleanup() {
  if docker ps -a --format '{{.Names}}' | grep -q "^${TEST_CONTAINER}$"; then
    log "Cleaning up test container..."
    docker rm -f "$TEST_CONTAINER" > /dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

# =============================================================================
# Phase 1: Static analysis (always runs)
# =============================================================================
log "Phase 1: Static analysis"

echo "  Checking shell scripts..."
SHELL_ERRORS=0
SHELL_TOTAL=0
while IFS= read -r -d '' script; do
  SHELL_TOTAL=$((SHELL_TOTAL + 1))
  if ! shellcheck --severity=warning "$script"; then
    SHELL_ERRORS=$((SHELL_ERRORS + 1))
  fi
done < <(find scripts docker examples/consumer -type f \( -name '*.sh' -o -name dev -o -name ops \) -print0)
SHELL_PASS=$((SHELL_TOTAL - SHELL_ERRORS))
if [[ $SHELL_ERRORS -eq 0 ]]; then
  echo "  ✓ All $SHELL_TOTAL shell scripts pass ShellCheck"
else
  warn "$SHELL_PASS/$SHELL_TOTAL pass; $SHELL_ERRORS have ShellCheck warnings"
fi

echo "  Checking Python scripts..."
PYTHON_ERRORS=0
for py in scripts/*.py scripts/seed/*.py; do
  [[ -f "$py" ]] || continue
  if python3 -B -m py_compile "$py"; then
    echo "  ✓ $py syntax OK"
  else
    warn "$py has syntax errors"
    PYTHON_ERRORS=$((PYTHON_ERRORS + 1))
  fi
done

echo "  Running seed-roll regression tests..."
if python3 -B -m unittest discover -s scripts/seed -p 'test_*.py'; then
  echo "  ✓ Seed-roll regression tests pass"
else
  warn "Seed-roll regression tests failed"
  PYTHON_ERRORS=$((PYTHON_ERRORS + 1))
fi

echo "  Checking seed-roll bundle dependencies..."
BUNDLE_ERRORS=0
SEED_RUNTIME_FILES=$(grep -hoE '\$SCRIPT_DIR/[[:alnum:]_.-]+\.(py|sh)' scripts/seed/roll-*.sh \
  | sed 's#\$SCRIPT_DIR/#scripts/seed/#' | sort -u)
for bundle_file in $SEED_RUNTIME_FILES; do
  [[ "$bundle_file" == *.py ]] || continue
  while IFS= read -r module; do
    local_module="scripts/seed/$module.py"
    [[ -f "$local_module" ]] && SEED_RUNTIME_FILES="$SEED_RUNTIME_FILES $local_module"
  done < <(sed -nE 's/^from ([[:alnum:]_]+) import .*/\1/p; s/^import ([[:alnum:]_]+).*/\1/p' \
    "$bundle_file")
done
for bundle_file in $(printf '%s\n' $SEED_RUNTIME_FILES | sort -u); do
  if ! grep -Fxq "  $bundle_file" scripts/build-stack-bundle.sh; then
    warn "Seed-roll dependency missing from bundle manifest: $bundle_file"
    BUNDLE_ERRORS=$((BUNDLE_ERRORS + 1))
  fi
done
if [[ $BUNDLE_ERRORS -eq 0 ]]; then
  echo "  ✓ All seed-roll dependencies are bundled"
fi

echo "  Checking datapack ownership manifests..."
# A datapack file referencing a removable mod's content fails dynamic-registry
# load when that mod is removed (boot break). filter-datapacks.py strips owned
# files at sync time, but only for packs carrying an ownership.json — so any
# platform pack touching mod namespaces without one is an unguarded boot risk.
# Namespaces exempt: minecraft (vanilla always present) and adventure (ours).
OWNERSHIP_ERRORS=0
OWNERSHIP_OUT=$(python3 - << 'PYEOF'
import json, re, sys
from pathlib import Path

EXEMPT = {"minecraft", "adventure"}
ID_NS = re.compile(r'"#?([a-z_0-9.-]+):[a-z_0-9/.-]+"')
problems = 0
packs = set()
for root in (Path("config/datapacks"), Path("config/datapack-presets")):
    if root.is_dir():
        packs |= {m.parent for m in root.rglob("pack.mcmeta")}
for pack in sorted(packs):
    ownership_file = pack / "ownership.json"
    owned = set(json.loads(ownership_file.read_text())) if ownership_file.is_file() else None
    for f in sorted(pack.rglob("*.json")):
        rel = str(f.relative_to(pack))
        if rel in ("pack.mcmeta", "ownership.json"):
            continue
        # Tag files never boot-break: missing entries fail at tag-load
        # (logged, tag degraded), not at dynamic-registry load.
        if "/tags/" in f.as_posix():
            continue
        parts = f.relative_to(pack).parts
        namespaces = set()
        if parts and parts[0] == "data" and len(parts) > 1:
            namespaces.add(parts[1])
        namespaces |= set(ID_NS.findall(f.read_text()))
        mod_ns = namespaces - EXEMPT
        if not mod_ns:
            continue
        if owned is None:
            print(f"  {pack}: {rel} references mod namespace(s) "
                  f"{sorted(mod_ns)} but the pack has no ownership.json")
            problems += 1
        elif rel not in owned:
            print(f"  {pack}: {rel} references mod namespace(s) "
                  f"{sorted(mod_ns)} but is missing from ownership.json")
            problems += 1
sys.exit(1 if problems else 0)
PYEOF
) || OWNERSHIP_ERRORS=1
if [[ $OWNERSHIP_ERRORS -eq 0 ]]; then
  echo "  ✓ Datapack ownership manifests cover all mod-namespace references"
else
  echo "$OWNERSHIP_OUT"
  warn "Datapack ownership lint failed (unguarded mod references — boot risk on mod removal)"
fi

echo "  Validating docker-compose.yml..."
COMPOSE_ERRORS=0
if docker compose --profile cloud config --quiet; then
  echo "  ✓ Cloud profile valid"
else
  warn "Cloud profile invalid"
  COMPOSE_ERRORS=$((COMPOSE_ERRORS + 1))
fi
if docker compose --profile local config --quiet; then
  echo "  ✓ Local profile valid"
else
  warn "Local profile invalid"
  COMPOSE_ERRORS=$((COMPOSE_ERRORS + 1))
fi

echo "  Checking YAML files..."
YAML_ERRORS=0
if command -v yamllint &> /dev/null; then
  if yamllint -c .yamllint.yml docker-compose.yml .github/workflows/*.yml config/cloudflared/config.yml; then
    echo "  ✓ YAML lint clean"
  else
    warn "YAML lint issues"
    YAML_ERRORS=$((YAML_ERRORS + 1))
  fi
else
  warn "yamllint is required; install it before running this check"
  YAML_ERRORS=1
fi

STATIC_ERRORS=$((SHELL_ERRORS + PYTHON_ERRORS + BUNDLE_ERRORS + OWNERSHIP_ERRORS + COMPOSE_ERRORS + YAML_ERRORS))
if [[ $STATIC_ERRORS -gt 0 ]]; then
  echo "::error::$STATIC_ERRORS static-analysis check(s) failed"
  exit 1
fi

if [[ $QUICK -eq 1 ]]; then
  log "Quick mode - skipping container tests"
  exit 0
fi

# =============================================================================
# Phase 2: Container-based script testing
# =============================================================================
log "Phase 2: Container-based testing (Ubuntu 24.04)"

echo "  Starting test container..."
docker run -d \
  --name "$TEST_CONTAINER" \
  --privileged \
  -v "$PROJECT_DIR:/workspace:ro" \
  "$TEST_IMAGE" \
  sleep 3600 > /dev/null

# Wait for container to be ready
sleep 2

run_in_test() {
  docker exec "$TEST_CONTAINER" bash -c "$@"
}

echo "  Installing prerequisites..."
run_in_test "apt-get update -qq >/dev/null 2>&1 && apt-get install -y -qq sudo curl git psmisc >/dev/null 2>&1"

# --- Test harden.sh (the most critical script) --------------------------------
echo ""
log "Testing harden.sh..."

# Copy the script (it modifies system files, so we run it in the container)
docker cp scripts/harden.sh "${TEST_CONTAINER}:/root/harden.sh"

echo "  Running harden.sh --non-interactive (some systemd steps will fail in Docker - expected)..."
run_in_test "DEPLOY_USER=deploy SERVER_PORT=25577 VOICE_PORT=24454 bash /root/harden.sh --non-interactive" 2>&1 | tail -10 || true
echo "  (Exit code non-zero expected - systemctl/ufw not available in Docker)"

# Verify hardening results
echo "  Verifying hardening..."
ERRORS=0

# Check deploy user exists
if run_in_test "id deploy" > /dev/null 2>&1; then
  echo "  ✓ deploy user created"
else
  warn "deploy user not created"
  ERRORS=$((ERRORS + 1))
fi

# Check SSH config
if run_in_test "grep -q 'PermitRootLogin no' /etc/ssh/sshd_config"; then
  echo "  ✓ Root login disabled"
else
  warn "Root login still enabled"
  ERRORS=$((ERRORS + 1))
fi

if run_in_test "grep -q 'PasswordAuthentication no' /etc/ssh/sshd_config"; then
  echo "  ✓ Password auth disabled"
else
  warn "Password auth still enabled"
  ERRORS=$((ERRORS + 1))
fi

# Check swap
if run_in_test "test -f /swapfile"; then
  echo "  ✓ Swap file created"
else
  warn "Swap file not created"
  ERRORS=$((ERRORS + 1))
fi

# Check Docker daemon.json
if run_in_test "test -f /etc/docker/daemon.json && grep -q iptables /etc/docker/daemon.json"; then
  echo "  ✓ Docker daemon.json configured (iptables=false)"
else
  warn "Docker daemon.json not configured"
  ERRORS=$((ERRORS + 1))
fi

# Check fail2ban config
if run_in_test "test -f /etc/fail2ban/jail.local"; then
  echo "  ✓ fail2ban configured"
else
  warn "fail2ban not configured"
  ERRORS=$((ERRORS + 1))
fi

echo ""
if [[ $ERRORS -eq 0 ]]; then
  log "All hardening checks passed"
else
  warn "$ERRORS hardening checks failed"
fi

# --- Test lib.sh loading -----------------------------------------------------
echo ""
log "Testing lib.sh..."
docker cp scripts/lib.sh "${TEST_CONTAINER}:/root/lib.sh"
if run_in_test "SCRIPT_DIR=/root source /root/lib.sh && echo \"\$PROJECT_DIR\" && detect_provider" 2>&1 | tail -2; then
  echo "  ✓ lib.sh loads and runs"
else
  warn "lib.sh failed"
fi

# =============================================================================
# Summary
# =============================================================================
echo ""
echo "=================================================================="
log "Test suite complete"
echo "  Shell analysis:     $([[ $SHELL_ERRORS -eq 0 ]] && echo "PASS" || echo "WARN ($SHELL_ERRORS issues)")"
echo "  Python syntax:      PASS"
echo "  Compose validation: PASS"
echo "  Hardening tests:    $([[ ${ERRORS:-0} -eq 0 ]] && echo "PASS" || echo "WARN ($ERRORS issues)")"
echo "=================================================================="
