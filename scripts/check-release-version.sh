#!/usr/bin/env bash
set -euo pipefail
# check-release-version.sh - refuse a release version too small for its changes.
#
# Context: release.yml's first job. Consumers pin a major (STACK_VERSION=v5)
# and take every release inside it, so a breaking commit since the last
# release - `type!:` or `type(scope)!:` in the subject, or a
# `BREAKING CHANGE:` / `BREAKING-CHANGE:` footer - must ship as a new major.
# The merged next-major worldgen PR (mod-updates/next-major) is such a commit.
#
# Usage:
#   scripts/check-release-version.sh vX.Y.Z [REV]
#   REV defaults to HEAD. Needs the tags and the history since the last
#   release (actions/checkout with fetch-depth: 0).
#
# Gotchas:
#   - "Last release" is the highest vX.Y.Z tag reachable from REV, compared
#     by version, not by date.
#   - Exits 1 with the offending commits listed; exits 0 with no previous tag.

VERSION="${1:?usage: check-release-version.sh vX.Y.Z [REV]}"
REV="${2:-HEAD}"

if ! [[ "$VERSION" =~ ^v([0-9]+)\.[0-9]+\.[0-9]+$ ]]; then
  echo "::error::Version must be vX.Y.Z (got: $VERSION)"
  exit 1
fi
NEW_MAJOR="${BASH_REMATCH[1]}"

PREVIOUS=$(git tag --merged "$REV" --list 'v*' | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | sort -V | tail -1 || true)
if [[ -z "$PREVIOUS" ]]; then
  echo "No earlier release tag; nothing to compare."
  exit 0
fi
PREVIOUS_MAJOR="${PREVIOUS#v}"
PREVIOUS_MAJOR="${PREVIOUS_MAJOR%%.*}"

BREAKING=$(git log --format='%x1e%h %s%n%b' "$PREVIOUS..$REV" | awk '
  BEGIN { RS = "\036" }
  NF {
    split($0, lines, "\n"); head = lines[1]
    subject = head; sub(/^[0-9a-f]+ /, "", subject)
    if (subject ~ /^[a-z]+(\([^)]*\))?!: / || $0 ~ /\nBREAKING[ -]CHANGE: /) print head
  }')

if [[ -z "$BREAKING" ]]; then
  echo "No breaking changes since $PREVIOUS; $VERSION is fine."
  exit 0
fi
if (( NEW_MAJOR > PREVIOUS_MAJOR )); then
  echo "Breaking changes since $PREVIOUS ship in new major $VERSION:"
  echo "$BREAKING"
  exit 0
fi
echo "::error::$VERSION keeps major v$PREVIOUS_MAJOR, but these commits since $PREVIOUS are breaking; release v$((PREVIOUS_MAJOR + 1)).0.0 instead:"
echo "$BREAKING"
exit 1
