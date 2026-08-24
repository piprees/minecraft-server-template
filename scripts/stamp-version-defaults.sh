#!/usr/bin/env bash
#
# stamp-version-defaults.sh - Write the released major into the shipped
# .env.example defaults.
#
# Context: STACK_VERSION in examples/consumer/.env.example is the pin a new
# consumer inherits by copying the scaffold, and both .env.example files are in
# build-stack-bundle.sh's MANIFEST. Nothing but a release knows the current
# major, so a literal written by hand is stale from the next release onwards.
#
# Usage:
#   scripts/stamp-version-defaults.sh vX.Y.Z
#
# Context: release.yml runs this in the bundle job BEFORE build-stack-bundle.sh,
# so the tarball carries a correct pin even when the commit-back to main loses a
# race and silently no-ops. The same files then ride along in that commit.
#
# Gotchas: stamps the major only (v5), never the exact version - a consumer
# pinned to a major floats on its patches, which is the intent. The "Pin
# exactly" comment beside it carries the full version, so the example stays a
# real released tag. Idempotent; prints nothing but a summary when already
# current. Exits non-zero if a target file has no STACK_VERSION line, because a
# silent no-match is how this rots in the first place.
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

if [[ "$changed" -eq 0 ]]; then
  echo "version defaults already current at $MAJOR"
fi
