#!/usr/bin/env bash
# e2e-rig-companion.sh - The rig's three-way check and the companion's crossing, on 26.x, read from logs.
#
# Purpose: proves the rig is an instrument and the companion chain works on the
#          real client. With the player at the canonical pose (5.5 -60 1.0,
#          yaw 0, in <ns>:e2e_one, facing the rig portal built by
#          build-e2e-portal.sh):
#            1. portal_links.json names e2e_two
#            2. the server starts a feed for that portal to e2e_two
#               (`companion-feed:start player=<P> portal=... -> <ns>:e2e_two`)
#            3. the client's feed fills (`companion-feed: frames=F received
#               chunks=N`, N at least MIN_CHUNKS, default 20)
#          then the player steps into the opening:
#            4. the server sends the pre-loaded transfer
#               (`companion-send:preloaded-transfer player=<P>`)
#            5. the player is in <ns>:e2e_two, and still there ten seconds later
#            6. the client logged `companion-feed: preloaded <ns>:e2e_two`
# Context: template-only (not in MANIFEST). The 26.x companion has no dev
#          bridge and the 26.x server no `customdim e2e-state`, so every fact
#          is a log line or a file, and the dimension is the player's own
#          `Dimension` NBT. Run it as vanilla-join.sh's --during command with
#          the companion pack (companion-pack.sh), so the client is real:
#            scripts/e2e/vanilla-join.sh --mrpack companion.mrpack --name Rig \
#              --during 'CLIENT_LOG=<out>/game/logs/latest.log scripts/e2e/e2e-rig-companion.sh'
# Usage:   CLIENT_LOG=<client latest.log> [PLAYER=Rig] [RIG_NS=adventure]
#          [MIN_CHUNKS=20] [OUT=dir] e2e-rig-companion.sh
# Gotchas: each wait is bounded (FEED_WAIT, default 240 s, for software GL);
#          the server log is read only from this run's start, so an earlier
#          run's lines never pass this one. Exit 1 on any FAIL; assertions go to
#          $OUT/assertions.tsv.
set -uo pipefail

PLAYER="${PLAYER:-Rig}"
NS="${RIG_NS:-adventure}"
MIN_CHUNKS="${MIN_CHUNKS:-20}"
FEED_WAIT="${FEED_WAIT:-240}"
OUT="${OUT:-$(dirname "${CLIENT_LOG:-/tmp/x}")/rig-companion}"
CLIENT_LOG="${CLIENT_LOG:?CLIENT_LOG=<the client latest.log>}"
mkdir -p "$OUT"
: > "$OUT/assertions.tsv"
SINCE="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
FAILED=0

rc() { timeout 20 docker exec -i mc rcon-cli "$1" 2>&1 | tr -d '\r'; }
slog() { docker logs mc --since "$SINCE" > "$OUT/server.log" 2>&1; cat "$OUT/server.log"; }
check() { # label ok|fail detail
  printf '%s\t%s\t%s\n' "$2" "$1" "$3" >> "$OUT/assertions.tsv"
  printf '  %-4s %s (%s)\n' "$(echo "$2" | tr a-z A-Z)" "$1" "$3"
  [[ "$2" == ok ]] || FAILED=1
}
dimension() { rc "data get entity $PLAYER Dimension" | sed -nE 's/.*: "([^"]+)".*/\1/p'; }
received() { grep -oE 'companion-feed: frames=[0-9]+ received chunks=[0-9]+' "$CLIENT_LOG" 2>/dev/null | tail -1 | sed -E 's/.*chunks=//'; }

echo "== pose $PLAYER at the rig (5.5 -60 1.0 yaw 0 in $NS:e2e_one)"
rc "gamemode creative $PLAYER" > /dev/null
rc "execute in $NS:e2e_one run tp $PLAYER 5.5 -60 1.0 0 0" | head -c 120; echo

links="$(docker exec mc sh -c 'cat /data/world/data/customdimensions/portal_links.json 2>/dev/null' | grep -c e2e_two)"
[[ "${links:-0}" -gt 0 ]] && check "1 the link exists" ok "portal_links.json mentions e2e_two" ||
  check "1 the link exists" fail "no e2e_two in portal_links.json: run build-e2e-portal.sh"

start="" n=""
for i in $(seq 1 $((FEED_WAIT / 5))); do
  start="$(slog | grep -E "companion-feed:start player=$PLAYER .*-> $NS:e2e_two" | tail -1)"
  n="$(received)"
  [[ -n "$start" && "${n:-0}" -ge "$MIN_CHUNKS" ]] && break
  sleep 5
done
[[ -n "$start" ]] && check "2 a feed to e2e_two" ok "$(printf '%s' "$start" | sed -E 's/^.*companion-feed/companion-feed/' | cut -c1-120)" ||
  check "2 a feed to e2e_two" fail "no companion-feed:start to $NS:e2e_two in ${FEED_WAIT}s"
[[ "${n:-0}" -ge "$MIN_CHUNKS" ]] && check "3 the client feed fills" ok "received chunks=$n" ||
  check "3 the client feed fills" fail "received chunks=${n:-none}, want >= $MIN_CHUNKS"

echo "== step into the opening"
rc "execute in $NS:e2e_one run tp $PLAYER 4.5 -59 4.5 0 0" | head -c 120; echo
dim=""
for i in $(seq 1 24); do
  dim="$(dimension)"
  [[ "$dim" == "$NS:e2e_two" ]] && break
  sleep 5
done
slog | grep -qE "companion-send:preloaded-transfer player=$PLAYER" &&
  check "4 the pre-loaded transfer is sent" ok "companion-send:preloaded-transfer" ||
  check "4 the pre-loaded transfer is sent" fail "no companion-send:preloaded-transfer for $PLAYER"
sleep 10
after="$(dimension)"
[[ "$dim" == "$NS:e2e_two" && "$after" == "$NS:e2e_two" ]] && check "5 arrives and stays" ok "$after ten seconds on" ||
  check "5 arrives and stays" fail "dimension ${dim:-none}, then ${after:-none}"
grep -qE "companion-feed: preloaded $NS:e2e_two" "$CLIENT_LOG" &&
  check "6 the client took the pre-loaded transfer" ok "companion-feed: preloaded $NS:e2e_two" ||
  check "6 the client took the pre-loaded transfer" fail "no 'companion-feed: preloaded' line in the client log"

rc "execute in $NS:e2e_one run tp $PLAYER 5.5 -60 1.0 0 0" > /dev/null
echo "== $(grep -c '^ok' "$OUT/assertions.tsv") passed, $(grep -c '^fail' "$OUT/assertions.tsv") failed ($OUT/assertions.tsv)"
exit "$FAILED"
