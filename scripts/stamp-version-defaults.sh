#!/usr/bin/env bash
#
# stamp-version-defaults.sh - Write the released version into the scaffold's
# version defaults: examples/consumer/.stack-version (the exact pin), the
# deploy-reusable.yml@vN major in examples/consumer/.github/workflows/deploy.yml,
# and the STACK_VERSION major in both .env.example files.
#
# Context: a new consumer inherits all of these by copying the scaffold, and
# they sit in build-stack-bundle.sh's MANIFEST. Nothing but a release knows the
# current version, so a literal written by hand is stale from the next release
# onwards - and a deploy.yml whose @vN lags .stack-version's major is refused
# by deploy-reusable.yml's major check.
#
# Usage:
#   scripts/stamp-version-defaults.sh vX.Y.Z
#
# Context: release.yml runs this in the bundle job BEFORE build-stack-bundle.sh,
# so the tarball carries a correct pin even when the commit-back to main loses a
# race and silently no-ops. The same files then ride along in that commit.
#
# Gotchas: .env.example gets the major only (v5) - it is the deprecated local
# fallback pin, and its "Pin exactly" comment carries the full version so the
# example stays a real released tag. .stack-version gets the exact version.
# Idempotent; prints nothing but a summary when already current. Exits
# non-zero if a target has no line to stamp, because a silent no-match is how
# this rots in the first place.
set -euo pipefail

VERSION="${1:-}"
if [[ ! "$VERSION" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "usage: $(basename "$0") vX.Y.Z" >&2
  exit 2
fi

MAJOR="${VERSION%%.*}"
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

TARGETS=(
  ".env.example"
  "examples/consumer/.env.example"
)

changed=0
for rel in "${TARGETS[@]}"; do
  file="$PROJECT_DIR/$rel"
  [[ -f "$file" ]] || { echo "missing target: $rel" >&2; exit 1; }

  if ! grep -qE '^STACK_VERSION=' "$file"; then
    echo "no STACK_VERSION line in $rel — refusing to stamp silently" >&2
    exit 1
  fi

  before="$(cat "$file")"

  # sed without -i: BSD and GNU disagree on whether it takes a backup suffix.
  # Four rewrites: the pin itself, the "Pin exactly (STACK_VERSION=vX.Y.Z)"
  # example beside it, and the two halves of the sentence explaining them, so
  # the comment never cites a version that predates the pin.
  tmp="$file.stamp.$$"
  sed -e "s/^STACK_VERSION=.*/STACK_VERSION=$MAJOR/" \
      -e "s/STACK_VERSION=v[0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*/STACK_VERSION=$VERSION/g" \
      -e "s/A major pin like v[0-9][0-9]*/A major pin like $MAJOR/" \
      -e "s/latest v[0-9][0-9]*\.x\.y/latest $MAJOR.x.y/" \
      "$file" > "$tmp"
  mv "$tmp" "$file"

  if [[ "$before" != "$(cat "$file")" ]]; then
    echo "stamped $rel -> STACK_VERSION=$MAJOR (example pin $VERSION)"
    changed=$((changed + 1))
  fi
done

pin_file="$PROJECT_DIR/examples/consumer/.stack-version"
if [[ "$(cat "$pin_file" 2>/dev/null || true)" != "$VERSION" ]]; then
  printf '%s\n' "$VERSION" > "$pin_file"
  echo "stamped examples/consumer/.stack-version -> $VERSION"
  changed=$((changed + 1))
fi

deploy_rel="examples/consumer/.github/workflows/deploy.yml"
deploy_file="$PROJECT_DIR/$deploy_rel"
if ! grep -qE 'deploy-reusable\.yml@v[0-9]+$' "$deploy_file" 2>/dev/null; then
  echo "no deploy-reusable.yml@vN line in $deploy_rel — refusing to stamp silently" >&2
  exit 1
fi
before="$(cat "$deploy_file")"
tmp="$deploy_file.stamp.$$"
sed -e "s/deploy-reusable\.yml@v[0-9][0-9]*\$/deploy-reusable.yml@$MAJOR/" "$deploy_file" > "$tmp"
mv "$tmp" "$deploy_file"
if [[ "$before" != "$(cat "$deploy_file")" ]]; then
  echo "stamped $deploy_rel -> deploy-reusable.yml@$MAJOR"
  changed=$((changed + 1))
fi

if [[ "$changed" -eq 0 ]]; then
  echo "version defaults already current at $MAJOR"
fi
