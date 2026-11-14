#!/usr/bin/env bash
# mkshas.sh - Record the repo file each stored replacement was written against.
#
# Purpose: writes files.sha256, one "<sha256> <repo path>" line per file under
#   files/ ("new" when the repo has none), which apply.sh's put() checks so a
#   repo file that changed since its replacement was written is reported.
#
# Usage:
#   cutover/mkshas.sh <repo> <repo-path>...   # re-record the named entries only
#   cutover/mkshas.sh <repo> --all            # re-record every entry
#
# Gotchas:
#   - Re-record an entry only after carrying the repo file's changes into its
#     replacement: re-recording is what silences the drift check.
#   - Stored names map back to repo paths: "dot-" becomes a leading dot and
#     AGENTS.next.md becomes AGENTS.md (apply.sh's stored()).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="${1:?usage: mkshas.sh <repo> <repo-path>... | --all}"
shift
[[ $# -gt 0 ]] || {
  echo "ERROR: name the entries to re-record, or --all" >&2
  exit 2
}
table="$here/files.sha256"

repo_path() { printf '%s\n' "$1" | sed -E 's#(^|/)dot-#\1.#g; s#(^|/)AGENTS\.next\.md$#\1AGENTS.md#'; }
line_for() {
  if [[ -f "$repo/$1" ]]; then printf '%s %s\n' "$(sha256sum "$repo/$1" | cut -d' ' -f1)" "$1"; else printf 'new %s\n' "$1"; fi
}

tmp="$(mktemp)"
# The table keeps its order and comments: a named entry is rewritten in place,
# a stored file the table lacks is appended.
while IFS= read -r line; do
  rel="${line#* }"
  case "$line" in
    "" | "#"*) echo "$line" ;;
    *) if [[ "$1" == --all ]] || printf '%s\n' "$@" | grep -qxF "$rel"; then line_for "$rel"; else echo "$line"; fi ;;
  esac
done < "$table" > "$tmp"
(cd "$here/files" && find . -type f | sed 's|^\./||' | LC_ALL=C sort) | while IFS= read -r stored; do
  rel="$(repo_path "$stored")"
  awk -v p="$rel" '$2 == p { found = 1 } END { exit !found }' "$table" || line_for "$rel"
done >> "$tmp"
mv "$tmp" "$table"
