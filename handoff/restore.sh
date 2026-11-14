#!/usr/bin/env bash
# restore.sh - put the paused session's briefs, tools and text evidence back
# where the briefs and HANDOFF.md say they are.
#
# Context: the 26.3 rebuild is orchestrated from briefs that name absolute
# paths under /tmp/claude-0 (a session scratchpad and one evidence folder per
# work package). A fresh container has neither. This copies handoff/ there.
#
# Usage: ./handoff/restore.sh            (from the repository root; safe to re-run)
#
# Gotchas: images (shots, renders) are not in git; the review pages hold them
# (HANDOFF.md lists the artifact links). Existing files are never overwritten.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
scratch=/tmp/claude-0/-home-user/5eb91d81-f3fb-5a91-9f20-35496482f7d0/scratchpad
mkdir -p "$scratch" /tmp/claude-0
cp -Rn "$here/scratchpad/." "$scratch/"
for dir in "$here"/evidence/*/; do
  name="$(basename "$dir")"
  mkdir -p "/tmp/claude-0/$name"
  cp -Rn "$dir." "/tmp/claude-0/$name/"
done
chmod +x "$scratch"/*.sh 2>/dev/null || true
echo "restored: $scratch and /tmp/claude-0/{$(ls "$here/evidence" | tr '\n' ',' | sed 's/,$//')}"
