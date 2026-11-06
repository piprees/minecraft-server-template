#!/usr/bin/env bash
# e2e-item4-idle-unload.sh - Idle unload, both halves, on the rig's destination.
#
# Purpose: prove <ns>:e2e_two stays open while a player is in it for longer
#          than settings.json's idleUnloadMinutes, and unloads after the last
#          player leaves (LevelManager's `unloading <slug>: no players for N
#          min` line, then `customdim list` reading it lazy again).
# Context: template-only (not in MANIFEST); 26.x. Needs no client: the player
#          is a Carpet bot, and residency is a server fact. Run
#          build-e2e-portal.sh first (it opens the pair and enables Carpet's
#          fake players). On 26.x only a player IN the level keeps it open; the
#          arrival tickets that hold a destination while a companion watches its
#          portal are e2e-rig-companion.sh's subject, not this one.
# Usage:   e2e-item4-idle-unload.sh [stay-seconds]   (default 330, over the 5 min idle)
#          RIG_NS (default adventure), OUT (default /tmp/c0-e2e/item4-<time>)
# Gotchas: both halves are needed: a build that never unloads passes the first,
#          one that unloads too eagerly passes the second. Each wait is bounded;
#          the log is read only from this run's start. Exit 1 on any FAIL.
set -uo pipefail

NS="${RIG_NS:-adventure}"
STAY="${1:-330}"
OUT="${OUT:-/tmp/c0-e2e/item4-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$OUT"
: > "$OUT/assertions.tsv"
SINCE="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
FAILED=0

rc() { timeout 20 docker exec -i mc rcon-cli "$1" 2>&1 | tr -d '\r'; }
check() { # label ok|fail detail
  printf '%s\t%s\t%s\n' "$2" "$1" "$3" >> "$OUT/assertions.tsv"
  printf '  %-4s %s (%s)\n' "$(echo "$2" | tr a-z A-Z)" "$1" "$3"
  [[ "$2" == ok ]] || FAILED=1
}
state() { rc "customdim list" | grep -oE "e2e_two: [a-z]+" | head -1 | sed 's/.*: //'; }
unload_line() { docker logs mc --since "$SINCE" 2>&1 | grep -E "unloading e2e_two: no players" | tail -1; }

echo "== open e2e_two with the bot inside it"
rc "carpet commandPlayer true" > /dev/null
rc "player IdleBot spawn at 0 200 0 facing 0 0 in minecraft:overworld" > /dev/null
sleep 4
rc "customdim activate e2e_two" > /dev/null
for i in $(seq 1 24); do [[ "$(state)" == active ]] && break; sleep 5; done
rc "execute in $NS:e2e_two run tp IdleBot 4.5 -60 8.5" | head -c 100; echo
[[ "$(state)" == active ]] && check "e2e_two is open" ok "customdim list: active" ||
  check "e2e_two is open" fail "customdim list: $(state)"

echo "== stay: ${STAY}s with the bot in it"
sleep "$STAY"
s="$(state)"
[[ "$s" == active && -z "$(unload_line)" ]] && check "stays open while occupied" ok "active after ${STAY}s, no unload line" ||
  check "stays open while occupied" fail "state $s; $(unload_line)"

echo "== walk away: the bot to the overworld"
rc "execute in minecraft:overworld run tp IdleBot 0 200 0" | head -c 100; echo
line="" s=""
for i in $(seq 1 $(((STAY + 120) / 10))); do
  line="$(unload_line)"
  s="$(state)"
  [[ -n "$line" && "$s" == lazy ]] && break
  sleep 10
done
[[ -n "$line" ]] && check "unloads after the last player leaves" ok "$(printf '%s' "$line" | sed -E 's/^.*INFO\]: //')" ||
  check "unloads after the last player leaves" fail "no unload line in $((STAY + 120))s"
[[ "$s" == lazy ]] && check "reads lazy again" ok "customdim list: lazy" || check "reads lazy again" fail "customdim list: $s"

rc "player IdleBot kill" > /dev/null
echo "== $(grep -c '^ok' "$OUT/assertions.tsv") passed, $(grep -c '^fail' "$OUT/assertions.tsv") failed ($OUT/assertions.tsv)"
exit "$FAILED"
