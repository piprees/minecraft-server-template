#!/usr/bin/env bash
# lane-f4b.sh - One server-lane boot rerunning the LOD self-test section on the
#   build whose comparison reads a chunk's dug blocks from the noise column.
#
# Purpose: runs handoff/evidence/f4/lane-f4.sh (boot with Distant Horizons,
#   -PselfTestOnly=lod, DH databases deleted first) and then lists, per
#   dimension, the columns whose LOD and real heights differ by more than one
#   block and the columns whose surface was read through dug blocks.
#
# Usage: handoff/evidence/f4b/lane-f4b.sh [mod dir] [out dir]
#   mod dir  mods/custom-dimensions-26 of the checkout to boot (default: this
#            checkout's); it must hold the commit adding lod/ChunkGround.java
#   out dir  default /tmp/claude-0/f4b/lane
#
# Pass bars: lane-f4.sh's, unchanged (no FAIL line; "lod vs chunks <slug>"
#   heights within 1 >= 90 %, block under the surface >= 60 %, biome >= 90 %;
#   shortcut, bind and pregen lines PASS; 0 DH errors). Also expected: every
#   "lod vs chunks" line names its count of columns read through dug blocks,
#   and each TSV has the chunkHeight and dug columns.
#
# Gotchas: server lane only (one server at a time; 5 GB memory and 2 GB disk
#   free first). Foreground, one-hour timeout inside lane-f4.sh; read the
#   snapshot, never stream the log.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
mod="${1:-$here/../../../mods/custom-dimensions-26}"
out="${2:-/tmp/claude-0/f4b/lane}"
mod="$(cd "$mod" && pwd)"
[[ -f "$mod/server/src/main/java/com/customdimensions/server/lod/ChunkGround.java" ]] || {
  echo "ERROR: $mod has no lod/ChunkGround.java: merge the F4b commits first" >&2
  exit 1
}
bash "$here/../f4/lane-f4.sh" "$mod" "$out"

echo "--- columns off by more than one block, and columns read through dug blocks"
for tsv in "$out"/lod-check-*.tsv; do
  [[ -f "$tsv" ]] || continue
  echo "$(basename "$tsv"):"
  awk -F'\t' 'NR == 1 { print "  " $0; next }
    $3 != "none" && (($3 - $4) > 1 || ($4 - $3) > 1 || $10 != "") { print "  " $0 }' "$tsv"
done
