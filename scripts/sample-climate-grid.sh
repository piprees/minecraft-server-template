#!/usr/bin/env bash
# sample-climate-grid.sh — one dimension's six climate axes on an N x N grid.
#
# Purpose:  Measure the climate a world actually crosses, so a biome band can be
#           written against it instead of against the schema's -2..2. TSV to
#           stdout: x, z, temp, humid, cont, eros, depth, weird.
#
# Usage:    scripts/sample-climate-grid.sh <slug> <border> [n]   # n defaults to 41
#           scripts/sample-climate-grid.sh the_claymarsh 1024 > claymarsh.tsv
#
# Context:  borders.player is a RADIUS and the vanilla world border is SQUARE,
#           so [-B,+B]^2 is exactly the playable area. `customdim sample-noise`
#           reads the noise router and needs no loaded chunks — only the
#           ServerWorld — so `customdim load <slug>` first and a grid costs
#           nothing in generation.
#
#           n = 41 matches FactsEngine.GRID, which is what the seed roller and
#           `customdim facts` measure with. A coarser grid overstates absence:
#           measured over 372 bands in 26 dimensions, an 11x11 grid calls 68
#           bands empty where 41x41 at the same geometry calls 18 — 3.8x. See
#           TROUBLESHOOTING.md#k7.
#
# Gotchas:  - The router depends on `type`, `noiseSettings` and `seed`, NOT on
#             the biome list, so a grid taken from a server running an older
#             biome list is still valid for a newer one. Check those three
#             fields match before relying on that.
#           - `docker exec -i` here is ONE invocation fed a command list. The
#             forbidden form is `-i` inside a loop, where it eats the loop's own
#             stdin and the loop runs once. One invocation is also ~60x faster
#             than one exec per point: 1681 samples in about 5 seconds.
#           - The response pattern is anchored at both ends. RCON concatenates
#             feedback with no separator (T17), so an unanchored pattern would
#             swallow a second reply and emit a clean-looking row carrying the
#             wrong values; anchored, a fused line is dropped instead.
#           - Endpoints are computed per index so the last column is exactly
#             +border; integer stepping drifts short on a border not divisible
#             by n-1, such as 683.
#           - Check a result for PLAUSIBILITY before trusting it. Reading a TSV
#             while a background writer is still filling it produced "all 12
#             bands empty" once; nothing errored, and 12 of 12 is simply not a
#             believable answer. Row count is the cheap check: n*n exactly.
#           - depth is reported at y=0 and is linear in y at -1/128 per block.
#             `customdim facts` reads block y 64, so subtract 0.5 to compare.
#
# Template-only (platform development); not in the bundle MANIFEST.
set -euo pipefail

if [ "$#" -lt 2 ]; then
  echo "usage: $0 <slug> <border> [n]" >&2
  exit 2
fi
slug="$1"
border="$2"
n="${3:-41}"

coord() {
  awk -v b="$2" -v i="$1" -v n="$3" \
    'BEGIN{printf "%d", int(-b + (2*b*i)/(n-1) + (i*2>=n-1?0.5:-0.5))}'
}

cmds=$(mktemp)
trap 'rm -f "$cmds"' EXIT

i=0
while [ "$i" -lt "$n" ]; do
  x=$(coord "$i" "$border" "$n")
  j=0
  while [ "$j" -lt "$n" ]; do
    z=$(coord "$j" "$border" "$n")
    printf 'customdim sample-noise adventure:%s %s %s\n' "$slug" "$x" "$z" >> "$cmds"
    j=$(( j + 1 ))
  done
  i=$(( i + 1 ))
done

printf 'x\tz\ttemp\thumid\tcont\teros\tdepth\tweird\n'
docker exec -i mc rcon-cli < "$cmds" 2>&1 \
  | sed -E 's/^> //' \
  | sed -nE 's/^noise ([-0-9]+) ([-0-9]+) temp=([-0-9.]+) humid=([-0-9.]+) cont=([-0-9.]+) eros=([-0-9.]+) depth=([-0-9.]+) weird=([-0-9.]+)[[:space:]]*$/\1\t\2\t\3\t\4\t\5\t\6\t\7\t\8/p'
