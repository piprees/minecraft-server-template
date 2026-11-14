#!/usr/bin/env bash
# lane-f4.sh - One server-lane boot proving the Distant Horizons LOD generator.
#
# Purpose: boots the 26.3 dev server with Distant Horizons (-Pdh) and the
#   self-test's LOD section only (-PselfTestOnly=lod). In overworld, the_nether
#   (roofed) and the_aviary (sky tiers) the section checks the generator is
#   bound, compares the coarse shortcut with whole columns, runs
#   `/dh pregen start <id> 0 0 32` and counts the requests the generator
#   served, then compares DH's stored LOD columns with real chunks on a 6 x 6
#   grid. No timing comparison with DH's own generator.
#
# Usage: handoff/evidence/f4/lane-f4.sh [mod dir] [out dir]
#   mod dir  mods/custom-dimensions-26 of the checkout to boot (default: this
#            checkout's)
#   out dir  where the report, log snapshot and TSVs go (default
#            /tmp/claude-0/f4/lane)
#
# Pass bars (all must hold):
#   - the report has no FAIL line and at least 12 "lod" lines (4 per dimension);
#   - "lod generator bound to" PASS for all three dimensions;
#   - "lod pregen <slug>": served > 0 full-detail requests, 0 failed;
#   - "lod vs chunks <slug>": 0 columns without LOD data, heights within 1 >= 90 %,
#     block under the surface >= 60 %, biome >= 90 % (the line says PASS);
#   - "lod shortcut" lines PASS (heights within 1 >= 95 %, runs kept >= 90 %);
#   - the log has "LOD generator bound to" for each level DH loaded,
#     "come from customdimensions" for each dimension pre-generated, and none
#     of "Error generating data", "World generator disabled",
#     "World generator return type incorrect", "has a gap", "is upside down".
#
# Gotchas: server lane only (one server at a time; 5 GB memory and 2 GB disk
#   free first). It deletes Distant Horizons' LOD databases under
#   server/run/world (throwaway dev data) so the pre-generation asks the
#   generator for every section; it touches nothing else in the world.
#   Runs in the foreground under a one-hour timeout; read the snapshot, never
#   stream the log.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
mod="${1:-$here/../../../mods/custom-dimensions-26}"
out="${2:-/tmp/claude-0/f4/lane}"
mod="$(cd "$mod" && pwd)"
mkdir -p "$out"
export JAVA_HOME=/home/user/.local/jdk/jdk-25.0.5+8
export PATH="$JAVA_HOME/bin:$PATH"

free -g | sed -n 2p
df -h / | tail -1
[[ -f "$mod/server/run/eula.txt" ]] || {
  echo "ERROR: $mod/server/run has no eula.txt: boot the checkout that holds the lane's run directory" >&2
  exit 1
}
grep -q '^level-seed=20261022$' "$mod/server/run/server.properties" ||
  echo "WARN: level-seed is not the self-test's 20261022; the LOD section does not depend on it"

echo "Distant Horizons databases removed:"
find "$mod/server/run/world" -name 'DistantHorizons*.sqlite*' -print -delete 2> /dev/null || true

report="$out/st-lod.txt"
rm -f "$report"
echo "booting; log in $out/server.log"
set +e
(cd "$mod" && timeout 3600 ./gradlew --offline --max-workers=3 :server:runServer -Pdh \
  -PselfTest="$report" -PselfTestOnly=lod > "$out/server.log" 2>&1)
status=$?
set -e
echo "runServer exit $status"

cp "$mod"/server/run/lod-check-*.tsv "$out"/ 2> /dev/null || true
echo "--- report"
grep -E '^(PASS|FAIL|SKIP) lod' "$report" || echo "no lod lines"
echo "--- counts"
echo "PASS $(grep -c '^PASS' "$report" || true) FAIL $(grep -c '^FAIL' "$report" || true) SKIP $(grep -c '^SKIP' "$report" || true)"
echo "--- log"
grep -cE 'LOD generator bound to' "$out/server.log" || true
grep -E 'come from customdimensions|LOD generation for every' "$out/server.log" | cut -c1-240 || true
echo "DH errors: $(grep -cE 'Error generating data|World generator disabled|World generator return type incorrect|has a gap|is upside down' "$out/server.log" || true)"
ls -l "$out"/lod-check-*.tsv 2> /dev/null || echo "no lod-check TSVs"
