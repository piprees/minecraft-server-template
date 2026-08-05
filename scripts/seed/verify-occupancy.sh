#!/usr/bin/env bash
# =============================================================================
# verify-occupancy.sh — prove generated occupants match the census assignment
# =============================================================================
#
# The occupancy contract (mods/AGENTS.md): a noise site's assigned structure
# is exact and mirrored; the site is occupied by that structure iff the
# structure's own generation accepts the position, and by nothing else, ever.
# This drives a Carpet fake player to censused sites so their chunks really
# generate, then reads each site's LIVE StructureStarts with
# `customdim occupant` (never /locate — a locate miss walks placements for
# minutes and wedges the main thread; see TROUBLESHOOTING.md#t17):
#
#   PASS        occupant reports the assigned id at the site chunk
#   REJECTED    occupant reports empty AND the rejections artefact records
#               this exact (group, structure, chunk) — the contract's
#               recorded-empty case
#   UNVERIFIED  occupant reports empty and no record exists. On a chunk
#               generated before this run that is NOT a violation: the
#               rejections artefact is append-on-generation-event, so a
#               census-directory clear erases history. Re-prove by
#               regenerating the chunk (move the region file aside while mc
#               is stopped, tp the bot back) — the rejection is re-recorded.
#   FAIL        occupant reports a DIFFERENT structure — a real violation.
#
# Usage:
#   ./scripts/seed/verify-occupancy.sh <slug> [--sites N] [--container mc]
#
# Template-only: NOT in the bundle MANIFEST. Needs a running local server on
# the current jar and Carpet (a platform default).
#
# Gotchas:
#   - Each tp needs settle time for worldgen; ~12s per site is deliberate.
#   - Sites are picked round-robin per group, >=8 chunks apart, so two sites
#     never share generated terrain.
#   - occupant reads a LOADED chunk; the bot standing there keeps it loaded.
# =============================================================================
set -euo pipefail

SLUG="${1:-}"
[[ -n "$SLUG" && "$SLUG" != -* ]] || {
  echo "Usage: $0 <slug> [--sites N] [--container mc]" >&2
  exit 1
}
shift
SITES=12
MC_CONTAINER="mc"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --sites) SITES="$2"; shift 2 ;;
    --container) MC_CONTAINER="$2"; shift 2 ;;
    *) echo "Unknown option: $1" >&2; exit 1 ;;
  esac
done

BOT="Precis"
# No -i: an interactive exec inside the while-read site loop would consume
# the loop's stdin and silently truncate the run to one site.
rcon() { docker exec "$MC_CONTAINER" timeout 30 rcon-cli "$@" 2>&1; }

NS="$(docker exec "$MC_CONTAINER" sh -c \
  'cat /data/config/custom-dimensions/settings.json 2>/dev/null' \
  | python3 -c 'import json,sys
try:
    print(json.load(sys.stdin).get("namespace", "adventure"))
except Exception:
    print("adventure")' 2> /dev/null || echo adventure)"
DIM="$NS:$SLUG"

echo "== occupancy check: $DIM ($SITES sites) =="
rcon "customdim load $SLUG" > /dev/null
sleep 5
OUT="$(rcon "customdim structure-census $DIM")"
case "$OUT" in
  *"structure-census $DIM:"*) ;;
  *) echo "ERROR: census failed: $OUT" >&2; exit 1 ;;
esac

CENSUS="/tmp/occupancy-census-$SLUG.json"
docker cp "$MC_CONTAINER:/data/config/custom-dimensions/census/${NS}__${SLUG}.json" "$CENSUS"

# Deterministic site pick: round-robin per group (sorted), >=8 chunks apart.
PICKS="$(python3 - "$CENSUS" "$SITES" <<'PYEOF'
import json
import sys

census = json.load(open(sys.argv[1]))
want = int(sys.argv[2])
groups = {g: [p for p in b.get("positions", []) if len(p) >= 3 and p[2]]
          for g, b in sorted((census.get("groups") or {}).items())}
groups = {g: ps for g, ps in groups.items() if ps}
chosen = []


def far_enough(cx, cz):
    return all(max(abs(cx - a), abs(cz - b)) >= 8 for a, b, *_ in chosen)


idx = {g: 0 for g in groups}
while len(chosen) < want:
    progressed = False
    for g, ps in groups.items():
        if len(chosen) >= want:
            break
        while idx[g] < len(ps):
            cx, cz, sid = ps[idx[g]][0], ps[idx[g]][1], ps[idx[g]][2]
            idx[g] += 1
            if far_enough(cx, cz):
                chosen.append((cx, cz, sid, g))
                progressed = True
                break
    if not progressed:
        break
for cx, cz, sid, g in chosen:
    print(g, cx, cz, sid)
PYEOF
)"

GROUPS_COVERED="$(echo "$PICKS" | awk '{print $1}' | sort -u | wc -l | tr -d ' ')"
TOTAL="$(echo "$PICKS" | grep -c . || true)"
echo "picked $TOTAL sites across $GROUPS_COVERED group(s)"
if [[ "$TOTAL" -lt 10 || "$GROUPS_COVERED" -lt 2 ]]; then
  echo "WARN: acceptance needs >=10 sites across >=2 groups — this dimension gives $TOTAL/$GROUPS_COVERED"
fi

rcon "carpet commandPlayer true" > /dev/null || true
if ! rcon "list" | grep -qi "$BOT"; then
  rcon "player $BOT spawn" > /dev/null
  sleep 4
fi

PASS=0
REJECTED=0
UNVERIFIED=0
FAIL=0
FAILED_SITES=""
UNVERIFIED_SITES=""

rejected_at() {
  docker exec "$MC_CONTAINER" sh -c \
    "cat /data/config/custom-dimensions/census/rejections__${NS}__${SLUG}.json 2>/dev/null" \
    | python3 -c "
import json
import sys
try:
    doc = json.load(sys.stdin)
except Exception:
    doc = {}
entries = doc.get('rejections', doc if isinstance(doc, list) else [])
hit = any(r.get('chunkX') == $1 and r.get('chunkZ') == $2
          and r.get('structure') == '$3'
          for r in entries if isinstance(r, dict))
print('yes' if hit else 'no')" 2> /dev/null || echo no
}

# The occupant summary line is "occupant <dim> [cx, cz]: <ids|empty> -> <path>".
# Anchor on the "]: " that closes the chunk coordinates — the dimension id
# itself contains a colon, so a "first colon" match reads every answer empty.
occupant_at() {
  rcon "customdim occupant $DIM $1 $2" \
    | sed -n 's/.*\]: \(.*\) -> .*/\1/p' | head -1
}

while read -r group cx cz sid; do
  [[ -n "$group" ]] || continue
  bx=$((cx * 16 + 8))
  bz=$((cz * 16 + 8))
  rcon "execute in $DIM run tp $BOT $bx 200 $bz" > /dev/null
  sleep 12
  OCC="$(occupant_at "$cx" "$cz")"
  case " $OCC " in
    *" $sid "* | *"$sid,"* | *", $sid"*)
      PASS=$((PASS + 1))
      echo "  PASS       $group ($cx,$cz) $sid occupies the site"
      ;;
    " empty " | "  ")
      if [[ "$(rejected_at "$cx" "$cz" "$sid")" == "yes" ]]; then
        REJECTED=$((REJECTED + 1))
        echo "  REJECTED   $group ($cx,$cz) $sid — recorded in rejections artefact"
      else
        UNVERIFIED=$((UNVERIFIED + 1))
        UNVERIFIED_SITES="$UNVERIFIED_SITES $group:($cx,$cz):$sid"
        echo "  UNVERIFIED $group ($cx,$cz) $sid — empty, no record (pre-existing chunk? regenerate to re-prove)"
      fi
      ;;
    *)
      FAIL=$((FAIL + 1))
      FAILED_SITES="$FAILED_SITES $group:($cx,$cz):$sid"
      echo "  FAIL       $group ($cx,$cz) expected $sid, occupant said: ${OCC:-<no answer>}"
      ;;
  esac
done <<EOF
$PICKS
EOF

rcon "player $BOT kill" > /dev/null || true

echo ""
echo "== $PASS occupied-as-assigned, $REJECTED recorded rejections, $UNVERIFIED unverified, $FAIL violations =="
[[ -n "$UNVERIFIED_SITES" ]] && echo "unverified:$UNVERIFIED_SITES"
[[ -n "$FAILED_SITES" ]] && echo "violations:$FAILED_SITES"
[[ "$FAIL" -eq 0 && "$UNVERIFIED" -eq 0 && "$TOTAL" -ge 1 ]]
