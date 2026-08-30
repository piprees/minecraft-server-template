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
  # Severity comes from .shellcheckrc (info) - do not pin it here, or the
  # SC2029 client-side-expansion check silently stops running.
  if ! shellcheck "$script"; then
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
for py in scripts/*.py $(find docker -name '*.py' | sort); do
  [[ -f "$py" ]] || continue
  if python3 -B -m py_compile "$py"; then
    echo "  ✓ $py syntax OK"
  else
    warn "$py has syntax errors"
    PYTHON_ERRORS=$((PYTHON_ERRORS + 1))
  fi
done

echo "  Running verification-checker tests..."
if python3 -B -m unittest discover -s scripts/tests -p 'test_*.py'; then
  echo "  ✓ Verification checkers pass"
else
  warn "Verification checker tests failed"
  PYTHON_ERRORS=$((PYTHON_ERRORS + 1))
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

echo "  Checking uNmINeD block tags are up to date..."
# The map renderer draws untagged modded blocks pink. Regenerate after any
# mod change or the new blocks render pink (TROUBLESHOOTING.md#t47 sibling).
BLOCKTAG_ERRORS=0
if python3 ./scripts/gen-unmined-blocktags.py --check > /dev/null 2>&1; then
  echo "  ✓ uNmINeD block tags cover every modded block"
else
  warn "uNmINeD block tags are stale (new mod blocks would render pink) — run ./scripts/gen-unmined-blocktags.py"
  BLOCKTAG_ERRORS=1
fi

echo "  Checking the open-water guard datapack is up to date..."
# Lithostitched conditions that keep land structures out of the sea. A stale
# pack ships structures standing in open water.
OPEN_WATER_ERRORS=0
if python3 ./scripts/gen-open-water-guard.py --check > /dev/null 2>&1; then
  echo "  ✓ Open-water guard covers every land structure"
else
  warn "Open-water guard is stale (land structures would generate at sea) — run ./scripts/gen-open-water-guard.py"
  OPEN_WATER_ERRORS=1
fi

echo "  Checking biome bands reach their world's climate..."
# A band outside the range a world's climate crosses is a biome that cannot
# generate. check-biome-bands.py tests overlap, starved natives and equal
# slices of the schema's axis, which are different questions; every one of
# these faults is silent in game.
# Plain, not --strict: a measured dead band fails, an indicative one reports.
BAND_REACH_ERRORS=0
if python3 ./scripts/check-band-reach.py > /dev/null 2>&1; then
  echo "  ✓ Every explicit biome band reaches its world"
else
  warn "A biome band sits outside its world's climate range — run ./scripts/check-band-reach.py"
  BAND_REACH_ERRORS=1
fi

echo "  Checking biome band partitions..."
# Overlaps, natives starved off their own axis, and partitions cut from the
# schema's -2..2 rather than the world's measured range. The third is the
# shape that produces the dead bands the check above measures, and it needs
# no measurement — which is what makes it the one a new dimension trips.
BIOME_BANDS_ERRORS=0
BIOME_BANDS_OUT=$(python3 ./scripts/check-biome-bands.py 2>&1) || BIOME_BANDS_ERRORS=1
if [[ $BIOME_BANDS_ERRORS -eq 0 ]]; then
  echo "  ✓ Biome bands partition their axis and are fitted to their world"
  # The tie arm does not gate yet, so surface its findings here or a passing
  # run hides them entirely.
  echo "$BIOME_BANDS_OUT" | grep "generates nowhere" || true
else
  echo "$BIOME_BANDS_OUT"
  warn "Biome band partition check failed — run ./scripts/check-biome-bands.py"
fi

echo "  Checking Modrinth resolve cache covers every pin..."
# defaults-seed bakes config/modrinth-resolve-cache.json in and resolves pins
# from it with zero Modrinth calls. A pin with no entry is resolved live at
# boot instead, so a Modrinth outage mid-deploy fails a required resolution
# and blocks the boot. Parsing comes from modrinth_pins.pins() so this can
# never disagree with gen-resolve-cache.py about what a pin is.
RESOLVE_CACHE_ERRORS=0
RESOLVE_CACHE_OUT=$(python3 - << 'PYEOF'
import json
import sys
from pathlib import Path

sys.path.insert(0, "scripts")
from modrinth_pins import pins

cache = json.loads(Path("config/modrinth-resolve-cache.json").read_text() or "{}")
missing = sorted(f"{slug}:{vid}" for slug, vid in pins().items()
                 if f"{slug}:{vid}" not in cache)
for key in missing:
    print(f"  {key} has no resolve-cache entry")
if missing:
    print("  regenerate: python3 scripts/gen-resolve-cache.py")
sys.exit(1 if missing else 0)
PYEOF
) || RESOLVE_CACHE_ERRORS=1
if [[ $RESOLVE_CACHE_ERRORS -eq 0 ]]; then
  echo "  ✓ Resolve cache covers every pinned mod and datapack"
else
  echo "$RESOLVE_CACHE_OUT"
  warn "Resolve cache is stale (seed would resolve live at boot)"
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
# The line above does NOT read docker-compose.local.yml — compose auto-loads
# docker-compose.override.yml and nothing else, so a syntax error in the
# local override passes both checks above undetected. dev-up.sh merges both
# files, so validate the same pair it runs.
if docker compose -f docker-compose.yml -f docker-compose.local.yml --profile local config --quiet; then
  echo "  ✓ Local profile + local override valid"
else
  warn "Local override (docker-compose.local.yml) invalid"
  COMPOSE_ERRORS=$((COMPOSE_ERRORS + 1))
fi

echo "  Checking YAML files..."
YAML_ERRORS=0
if command -v yamllint &> /dev/null; then
  if yamllint -c .yamllint.yml docker-compose.yml docker-compose.local.yml .github/workflows/*.yml config/cloudflared/config.yml; then
    echo "  ✓ YAML lint clean"
  else
    warn "YAML lint issues"
    YAML_ERRORS=$((YAML_ERRORS + 1))
  fi
else
  warn "yamllint is required; install it before running this check"
  YAML_ERRORS=1
fi

STATIC_ERRORS=$((SHELL_ERRORS + PYTHON_ERRORS + OWNERSHIP_ERRORS + RESOLVE_CACHE_ERRORS
  + BLOCKTAG_ERRORS + OPEN_WATER_ERRORS + BAND_REACH_ERRORS + BIOME_BANDS_ERRORS
  + COMPOSE_ERRORS + YAML_ERRORS))
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
