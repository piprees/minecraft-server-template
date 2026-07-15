#!/usr/bin/env bash
# build-stack-bundle.sh — Assemble the stack bundle tarball for a release.
#
# Usage:
#   ./scripts/build-stack-bundle.sh v1.2.3
#   VERSION=v1.2.3 ./scripts/build-stack-bundle.sh
#
# Output: dist/stack-v1.2.3.tar.gz + dist/stack-v1.2.3.tar.gz.sha256
#
# Template-maintenance script — not shipped in the bundle itself.
set -euo pipefail

VERSION="${1:-${VERSION:-}}"
VERSION="${VERSION#v}"

if [[ -z "$VERSION" ]]; then
  echo "Usage: $0 <version>" >&2
  echo "  e.g. $0 v1.2.3" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="${CONSUMER_DIR:-$(cd "$SCRIPT_DIR/.." && pwd)}"
DIST_DIR="$PROJECT_DIR/dist"
STAGING_DIR="$DIST_DIR/.bundle-staging"
BUNDLE_NAME="stack-v${VERSION}"

MANIFEST=(
  docker-compose.yml
  docker-compose.local.yml
  .env.example
  scripts/lib.sh
  scripts/deploy.sh
  scripts/initial-setup.sh
  scripts/infra-deploy.sh
  scripts/setup-permissions.sh
  scripts/setup.sh
  scripts/prepare-droplet.sh
  scripts/preflight-check.sh
  scripts/github-env-sync.sh
  scripts/provision.sh
  scripts/provision-hetzner.sh
  scripts/provision-droplet.sh
  scripts/harden.sh
  scripts/cloudflare-setup.sh
  scripts/teardown.sh
  scripts/op-env.sh
  scripts/op-sync-env.sh
  scripts/kuma-token.sh
  scripts/rcon.sh
  scripts/ssh.sh
  scripts/chunky.sh
  scripts/wipe-chunk.sh
  scripts/doctor.sh
  scripts/game-log.sh
  scripts/patch-mod-data.py
  scripts/live-logs.sh
  scripts/live-stats.sh
  scripts/restart-service.sh
  scripts/service.sh
  scripts/map-render.sh
  scripts/backup-now.sh
  scripts/reset-seed.sh
  scripts/seed/roll-seeds.sh
  scripts/seed/score-seed.sh
  scripts/seed/report-top.sh
  scripts/seed/score-report.py
  scripts/seed/profiles/classic.profile
  scripts/seed/profiles/overworld-natural.profile
  scripts/seed/profiles/dim-hard-overworld.profile
  scripts/seed/profiles/dim-hard-nether.profile
  scripts/seed/profiles/dim-pastoral.profile
  scripts/seed/profiles/dim-end-chaos.profile
  scripts/seed/profiles/dim-nether-rolling.profile
  scripts/server-power.sh
  scripts/discord-notify.sh
  scripts/discord-cleanup.sh
  scripts/discord-pin-sync.sh
  scripts/ddns-update.sh
  scripts/cache-assets.sh
  scripts/dev-up.sh
  scripts/pack-build.sh
  scripts/remote-update.sh
  scripts/stack-pull.sh
  scripts/dc.sh
  examples/consumer/dev
  examples/consumer/ops
  # Everything `dev update` syncs into a consumer repo must ship in the
  # bundle — its copy loops are guarded by [[ -f "$src" ]] and silently
  # skip missing sources (elfydd ran a v1-era deploy.yml for months
  # because these weren't here).
  examples/consumer/.env.example
  examples/consumer/.gitignore
  examples/consumer/AGENTS.md
  examples/consumer/commands.json
  examples/consumer/README.md
  examples/consumer/.github/workflows/deploy.yml
  examples/consumer/.github/workflows/update.yml
)

echo "Building stack bundle v${VERSION}..."

errors=0
for file in "${MANIFEST[@]}"; do
  if [[ ! -f "$PROJECT_DIR/$file" ]]; then
    echo "ERROR: missing manifest file: $file" >&2
    errors=$((errors + 1))
  fi
done
if [[ $errors -gt 0 ]]; then
  echo "Aborting: $errors missing file(s)" >&2
  exit 1
fi

rm -rf "$STAGING_DIR"
mkdir -p "$STAGING_DIR/stack/scripts/seed" "$STAGING_DIR/stack/examples/consumer"

echo "$VERSION" > "$STAGING_DIR/stack/VERSION"

for file in "${MANIFEST[@]}"; do
  dest="$STAGING_DIR/stack/$file"
  mkdir -p "$(dirname "$dest")"
  cp "$PROJECT_DIR/$file" "$dest"
done

# Bundle the entire config/ directory (mod configs, messages, nginx, etc.)
# Excludes 1password.env (secrets) and pinned mod lists (platform-internal).
rsync -a \
  --exclude='1password.env' \
  --exclude='modrinth-mods.pinned.txt' \
  "$PROJECT_DIR/config/" "$STAGING_DIR/stack/config/"

# Include built in-house mod JARs (produced by CI before this script runs)
LOCAL_MODS="$DIST_DIR/local-mods"
if [[ -d "$LOCAL_MODS" ]] && ls "$LOCAL_MODS"/*.jar &> /dev/null 2>&1; then
  mkdir -p "$STAGING_DIR/stack/local-mods"
  cp "$LOCAL_MODS"/*.jar "$STAGING_DIR/stack/local-mods/"
  echo "  Included $(ls "$LOCAL_MODS"/*.jar | wc -l | tr -d ' ') in-house mod JAR(s)"
fi

mkdir -p "$DIST_DIR"

TAR_CMD="tar"
if command -v gtar > /dev/null 2>&1; then
  TAR_CMD="gtar"
fi

if $TAR_CMD --sort=name --help > /dev/null 2>&1; then
  $TAR_CMD \
    --sort=name \
    --owner=0 \
    --group=0 \
    --mtime="2024-01-01 00:00:00" \
    -czf "$DIST_DIR/${BUNDLE_NAME}.tar.gz" \
    -C "$STAGING_DIR" \
    stack
else
  echo "WARNING: GNU tar not available — bundle will not be reproducible (install gnu-tar for CI parity)" >&2
  $TAR_CMD \
    -czf "$DIST_DIR/${BUNDLE_NAME}.tar.gz" \
    -C "$STAGING_DIR" \
    stack
fi

(cd "$DIST_DIR" && shasum -a 256 "${BUNDLE_NAME}.tar.gz" > "${BUNDLE_NAME}.tar.gz.sha256")

rm -rf "$STAGING_DIR"

echo "Bundle: dist/${BUNDLE_NAME}.tar.gz"
echo "Checksum: dist/${BUNDLE_NAME}.tar.gz.sha256"
echo "Done."
