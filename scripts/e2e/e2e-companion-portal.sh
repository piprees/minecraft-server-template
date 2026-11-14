#!/usr/bin/env bash
# e2e-companion-portal.sh - The companion's preview, measured through the frame (26.x).
#
# Purpose: with the companion client, prove the far side is drawn and is the
#          far side: through frames into three destinations (adventure:e2e_two
#          sky-lit, e2e_three under the Nether's fog, e2e_four a void) the
#          bridge's /portals reports the opening drawn and /portal-census casts
#          rays through the aperture that land on the destination's own blocks
#          (and on none in the void); a husk and an arrow standing at the far
#          side are in the server's feed and the client's, and are drawn; a
#          moving arrow is drawn moving; the husk covers the opening's pixels
#          against a white wall (entity-pixels.py on a with/without pair); a
#          block changed on the far side shows in the next census; the frame
#          rate stays above FPS_FLOOR; and walking in crosses on the pre-loaded
#          transfer and stays.
# Context: template-only, 26.x, a linked consumer holding scripts/e2e/rig-26/
#          (README § On 26.x). Runs inside vanilla-join.sh --during with the
#          companion pack (companion-pack.sh) and the dev bridge
#          (--jvm-arg -Dcustomdimensions.devBridge=8766); the server needs
#          JVM_DD_OPTS='customdimensions.e2e=true'.
# Usage:   MC_VERSION_OVERRIDE=26.3 scripts/e2e/vanilla-join.sh --name Rig \
#            --mrpack /tmp/companion.mrpack --jvm-arg -Dcustomdimensions.devBridge=8766 \
#            --out /tmp/companion-portal --hold 20 \
#            --during "PLAYER=Rig CONSUMER_DIR=$PWD scripts/e2e/e2e-companion-portal.sh"
#          FPS_FLOOR (default 10) is the lowest frame rate accepted at the
#          portal; software GL under Xvfb is slow, so it is a floor, not a target.
# Gotchas: `drawing` is the renderer's own record of an emit in the last
#          second, so a portal that is fed but culled reads false. The census
#          reads the FED chunks, not pixels: a ray that enters a chunk the
#          client does not hold is `unfed`, never a miss. `entitiesDrawn`
#          counts fed entities with a model quad kept by the opening's clip in
#          a frame of the last second; `entitiesDrawnReason` says why it is 0.
#          entity-pixels.py needs Pillow (PYTHON=<python with Pillow>); the
#          pair shares one camera, so nothing else may move between shots.

# shellcheck disable=SC2034  # lib.sh reads it to name the run dir
SCRIPT_NAME="companion-portal"
# shellcheck source=lib.sh
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
# shellcheck source=rig-frames-lib.sh
. "$(cd "$(dirname "$0")" && pwd)/rig-frames-lib.sh"
# shellcheck source=portal-matrix-lib.sh
. "$(cd "$(dirname "$0")" && pwd)/portal-matrix-lib.sh"

FPS_FLOOR="${FPS_FLOOR:-10}"
ENTITY_PIXEL_FLOOR="${ENTITY_PIXEL_FLOOR:-0.01}"
PYTHON="${PYTHON:-python3}"
# e2e_one's portal colour: the world the walk-in leaves.
ORIGIN_HUE="${ORIGIN_HUE:-7FCC19}"
ARRIVAL_CEILING="${ARRIVAL_CEILING:-0.005}"
HERE="$(cd "$(dirname "$0")" && pwd)"
RESULTS="$RUN_DIR/results.md"
POLL_SECONDS="${POLL_SECONDS:-60}"
POLL_STEP="${POLL_STEP:-3}"
TWO="$RIG_NS:e2e_two"
THREE="$RIG_NS:e2e_three"
FOUR="$RIG_NS:e2e_four"

banner "e2e-companion-portal — the preview through the frame"
[ "$E2E_26" -eq 1 ] || { note "26.x only"; finish; exit 3; }
require_mc_healthy
require_bridge
require_player_online
STARTED_AT="$(cd "$CONSUMER_DIR" && docker inspect mc --format '{{.State.StartedAt}}')"
SINCE="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

say "The player is an admitted companion that draws locally"
assert_state "the server admitted the companion hello" "$PLAYER_SELECT | .companion" true
assert_state "it declared rendersLocally" "$PLAYER_SELECT | .view.rendersLocally" true
bridge_state
assert_bridge "the client draws the far side (no shader pack refusing it)" '.companion.drawing' true

say "Open the rig levels"
for slug in e2e_one e2e_two e2e_three e2e_four; do
  if rig_open "$slug"; then _record ok "$slug is open" open open; else _record fail "$slug is open" open "not open"; fi
done

# A portal fed to the client for this destination, drawn, with chunks held:
# polled to a cap, never waited on blindly.
wait_drawn() { # destination name [max-seconds]
  local dest="$1" name="$2" max="${3:-45}" waited=0 drawing held
  while [ "$waited" -le "$max" ]; do
    if bridge_portals "$name"; then
      drawing="$(json_read "$BRIDGE_LAST" "[.portals[] | select(.destination==\"$dest\") | .drawing] | any")"
      held="$(json_read "$BRIDGE_LAST" "[.portals[] | select(.destination==\"$dest\") | .chunksHeld] | max // 0")"
      if [ "$drawing" = "true" ] && [ "${held:-0}" -gt 0 ] 2>/dev/null; then
        note "$dest drawn with $held chunks held after ${waited}s"
        return 0
      fi
    fi
    sleep 3
    waited=$((waited + 3))
  done
  note "$dest not drawn after ${max}s (drawing ${drawing:-unread}, chunks ${held:-unread})"
  return 1
}

portal_read() { # destination jq-under-the-portal
  json_read "$BRIDGE_LAST" "[.portals[] | select(.destination==\"$1\") | $2] | first"
}

# axis x0 z0 frame destination label
view_destination() {
  local axis="$1" x0="$2" z0="$3" frame="$4" dest="$5" name="$6"
  say "$name: a frame into $dest"
  rig_frame "$axis" "$x0" "$z0" "$frame"
  # shellcheck disable=SC2086
  wait_for_chunk $FRAME_CELL "$RIG_WORLD" 30 >/dev/null
  rig_light minecraft:amethyst_shard > "$RUN_DIR/use-$name.txt"
  sleep 2
  state_refresh
  assert_state "$name: the frame lit into $dest" "$(rig_zone_select) | .targetWorld" "$dest"
  rcon "item replace entity $PLAYER weapon.mainhand with minecraft:air" >/dev/null
  rig_tp "$FRAME_STAND" "$FRAME_LOOK"
  if wait_drawn "$dest" "$name"; then
    _record ok "$name: the far side is drawn in the opening" "drawing, chunks held" \
      "$(portal_read "$dest" '.lastFaces') faces, $(portal_read "$dest" '.chunksHeld') chunks"
  else
    _record fail "$name: the far side is drawn in the opening" "drawing, chunks held" \
      "drawing $(portal_read "$dest" '.drawing'), chunks $(portal_read "$dest" '.chunksHeld')"
  fi
  state_refresh
  assert_state "$name: the server feeds the player $dest" "$PLAYER_SELECT | .feed.destination" "$dest"
  report_metric "$name: sky and fog the far side is drawn with" \
    "$(portal_read "$dest" '.skyColor') $(portal_read "$dest" '.fogColor')"
  if bridge_census "$name"; then
    assert_json "$name: the census looked into $dest" "$BRIDGE_LAST" '.destination' "$dest"
    assert_json "$name: no ray entered an unfed chunk" "$BRIDGE_LAST" '.unfed' 0
    report_metric "$name: census rays hit/open of total" \
      "$(json_read "$BRIDGE_LAST" '"\(.hits)/\(.open) of \(.rays)"')" \
      "$(json_read "$BRIDGE_LAST" '.blocks | to_entries | map("\(.key)=\(.value)") | join(" ")')"
  else
    _record fail "$name: the aperture census ran" "/portal-census" "$BRIDGE_REASON"
  fi
  assert_shot "$name: the opening from the stand" "$name"
}

# --- the far side of e2e_two carries a husk and an arrow, summoned first so
# the feed's first snapshot holds them. The husk does not burn at e2e_two's
# fixed noon; NoAI keeps both where the frame looks.
say "A mob and an arrow at e2e_two's far side"
rcon "execute in $TWO run forceload add 14 40 30 60" >/dev/null
rcon "execute in $TWO run summon minecraft:husk 21 -60 52 {NoAI:1b,PersistenceRequired:1b,Silent:1b,Tags:[\"e2e\"]}" >/dev/null
rcon "execute in $TWO run summon minecraft:arrow 21.5 -58.5 51.5 {NoGravity:1b,pickup:0b,Tags:[\"e2e\"]}" >/dev/null

view_destination X 20 48 minecraft:magenta_concrete "$TWO" sky-lit
assert_json_at_least "sky-lit: rays land on e2e_two's magenta floor" "$RUN_DIR/census-sky-lit.json" \
  '.blocks["minecraft:magenta_concrete"] // 0' 1

say "sky-lit: the husk and the arrow"
sleep 3
state_refresh
assert_state_at_least "the server's feed holds the husk" \
  "[$PLAYER_SELECT | .feed.entities[] | select(.type==\"minecraft:husk\")] | length" 1
assert_state_at_least "the server's feed holds the arrow" \
  "[$PLAYER_SELECT | .feed.entities[] | select(.type==\"minecraft:arrow\")] | length" 1
if bridge_portals entities; then
  assert_json_at_least "the client holds the husk for e2e_two" "$BRIDGE_LAST" \
    "[.portals[] | select(.destination==\"$TWO\") | .entities[] | select(.type==\"minecraft:husk\")] | length" 1
  assert_json_at_least "the client holds the arrow for e2e_two" "$BRIDGE_LAST" \
    "[.portals[] | select(.destination==\"$TWO\") | .entities[] | select(.type==\"minecraft:arrow\")] | length" 1
  DRAWN="$(portal_read "$TWO" '.entitiesDrawn')"
  if [ "$DRAWN" != "null" ] && [ "${DRAWN:-0}" -ge 2 ] 2>/dev/null; then
    _record ok "the husk and the arrow are drawn in the opening" ">= 2" \
      "$DRAWN: $(portal_read "$TWO" '.entitiesDrawnTypes | to_entries | map("\(.key)=\(.value)") | join(" ")')"
  else
    _record fail "the husk and the arrow are drawn in the opening" ">= 2" \
      "$DRAWN — $(portal_read "$TWO" '.entitiesDrawnReason')"
  fi
  assert_json_at_least "the drawn entities include the husk" "$BRIDGE_LAST" \
    "[.portals[] | select(.destination==\"$TWO\") | .entitiesDrawnTypes[\"minecraft:husk\"] // 0] | max // 0" 1
  assert_json_at_least "the drawn entities include the arrow" "$BRIDGE_LAST" \
    "[.portals[] | select(.destination==\"$TWO\") | .entitiesDrawnTypes[\"minecraft:arrow\"] // 0] | max // 0" 1
fi

# The drawn x of the fed arrow past z 53 (the moving one), from one /portals reading.
moving_arrow_x() { # portals-name
  json_read "$RUN_DIR/portals-$1.json" \
    "[.portals[] | select(.destination==\"$TWO\") | .entities[] | select(.type==\"minecraft:arrow\" and .pos[2] > 53) | .drawnPos[0]] | first"
}

say "sky-lit: an arrow crossing the far side is drawn moving"
rcon "execute in $TWO run summon minecraft:arrow 19.5 -58.5 53.5 {NoGravity:1b,pickup:0b,Motion:[0.08d,0.0d,0.0d],Tags:[\"e2e\",\"e2e_moving\"]}" >/dev/null
sleep 2
if bridge_portals moving-a; then
  sleep 1
  if bridge_portals moving-b; then
    XA="$(moving_arrow_x moving-a)"
    XB="$(moving_arrow_x moving-b)"
    if awk -v a="$XA" -v b="$XB" 'BEGIN { exit !(a ~ /^-?[0-9.]+$/ && b ~ /^-?[0-9.]+$/ && b - a > 0.3) }'; then
      _record ok "the moving arrow's drawn position moves along its flight" "> 0.3 blocks in 1 s" "$XA -> $XB"
    else
      _record fail "the moving arrow's drawn position moves along its flight" "> 0.3 blocks in 1 s" \
        "${XA:-unread} -> ${XB:-unread}"
    fi
  fi
fi
rcon "execute in $TWO run kill @e[tag=e2e_moving]" >/dev/null

say "sky-lit: the husk's pixels in the opening, against a white wall"
rcon "execute in $TWO run fill 15 -60 55 27 -54 55 minecraft:white_concrete" >/dev/null
sleep 5
assert_shot "sky-lit: the husk in front of the white wall" entity-with
rcon "execute in $TWO run tp @e[type=minecraft:husk,tag=e2e] 29 -60 52" >/dev/null
sleep 3
assert_shot "sky-lit: the white wall without the husk" entity-without
if [ -f "$RUN_DIR/entity-with.png" ] && [ -f "$RUN_DIR/entity-without.png" ] \
  && "$PYTHON" "$HERE/entity-pixels.py" "$RUN_DIR/entity-with.png" "$RUN_DIR/entity-without.png" \
    > "$RUN_DIR/entity-pixels.json" 2> "$RUN_DIR/entity-pixels.err"; then
  share="$(json_read "$RUN_DIR/entity-pixels.json" '.share')"
  if awk -v g="$share" -v m="$ENTITY_PIXEL_FLOOR" 'BEGIN { exit !(g ~ /^[0-9.]+$/ && g + 0 >= m + 0) }'; then
    _record ok "the husk covers the white wall in the opening" ">= $ENTITY_PIXEL_FLOOR of the centre" "$share"
  else
    _record fail "the husk covers the white wall in the opening" ">= $ENTITY_PIXEL_FLOOR of the centre" "$share"
  fi
  report_metric "entity pixels: wall, changed, entity" \
    "$(json_read "$RUN_DIR/entity-pixels.json" '"\(.wallPixels) \(.changed) \(.entityPixels) of \(.pixels)"')"
else
  _record fail "the husk covers the white wall in the opening" "an entity-pixels.py reading" \
    "$(cat "$RUN_DIR/entity-pixels.err" 2>/dev/null || echo 'no screenshot pair')"
fi
rcon "execute in $TWO run fill 15 -60 55 27 -54 55 minecraft:air" >/dev/null
rcon "execute in $TWO run tp @e[type=minecraft:husk,tag=e2e] 21 -60 52" >/dev/null

say "sky-lit: a far-side change shows in the next census"
rcon "execute in $TWO run fill 20 -60 51 21 -58 51 minecraft:gold_block" >/dev/null
sleep 5
if bridge_census live; then
  assert_json_at_least "the gold wall placed on the far side is in the census" "$RUN_DIR/census-live.json" \
    '.blocks["minecraft:gold_block"] // 0' 1
fi
rcon "execute in $TWO run fill 20 -60 51 21 -58 51 minecraft:air" >/dev/null

say "sky-lit: the frame rate at the portal"
FPS_MIN=""
i=0
while [ "$i" -lt 5 ]; do
  i=$((i + 1))
  if bridge_state; then
    f="$(bridge_read '.client.fps')"
    if [ -z "$FPS_MIN" ] || [ "${f:-0}" -lt "$FPS_MIN" ] 2>/dev/null; then FPS_MIN="$f"; fi
  fi
  sleep 1
done
if [ -n "$FPS_MIN" ] && [ "$FPS_MIN" -ge "$FPS_FLOOR" ] 2>/dev/null; then
  _record ok "the frame rate stays above the floor while the far side is drawn" ">= $FPS_FLOOR fps" "min $FPS_MIN over 5 s"
else
  _record fail "the frame rate stays above the floor while the far side is drawn" ">= $FPS_FLOOR fps" "min ${FPS_MIN:-unread} over 5 s"
fi

view_destination Y 44 48 minecraft:red_concrete "$THREE" nether-fogged
assert_json_at_least "nether-fogged: rays land on e2e_three's netherrack" "$RUN_DIR/census-nether-fogged.json" \
  '.blocks["minecraft:netherrack"] // 0' 1
FOG_TWO="$(json_read "$RUN_DIR/portals-sky-lit.json" "[.portals[] | select(.destination==\"$TWO\") | .fogColor] | first")"
FOG_THREE="$(json_read "$RUN_DIR/portals-nether-fogged.json" "[.portals[] | select(.destination==\"$THREE\") | .fogColor] | first")"
if [ -n "$FOG_THREE" ] && [ "$FOG_THREE" != "null" ] && [ "$FOG_THREE" != "$FOG_TWO" ]; then
  _record ok "the Nether-fogged far side carries its own fog colour" "not e2e_two's $FOG_TWO" "$FOG_THREE"
else
  _record fail "the Nether-fogged far side carries its own fog colour" "not e2e_two's $FOG_TWO" "${FOG_THREE:-unread}"
fi

view_destination X 60 48 minecraft:black_concrete "$FOUR" void
assert_json "void: no ray lands on anything but the world's floor" "$RUN_DIR/census-void.json" \
  '[.blocks | keys[] | select(. != "minecraft:obsidian")] | length' 0
assert_json_at_least "void: rays run through open air" "$RUN_DIR/census-void.json" '.open' 1

say "The crossing into e2e_two on the pre-loaded transfer"
rcon "execute in $RIG_WORLD run tp $PLAYER 21.0 -60 41.5 0 0" >/dev/null
sleep 3
CLI_BEFORE="$RUN_DIR/client-before.log"
client_log_snapshot "$CLI_BEFORE" 20000 || true
BEFORE_LINES="$(wc -l < "$CLI_BEFORE" | tr -d ' ')"
bridge_look 0 0 >/dev/null || true
if walk_to_dimension "$TWO" 3 8; then
  _record ok "the player walked in and crossed" "$TWO" "crossed"
else
  _record fail "the player walked in and crossed" "$TWO" "$WALK_NOTE"
fi
# The arrival is shot six times over three seconds: nothing of the world left
# behind (e2e_one's colour, its dust and its floor) may cover the view.
worst=0
for i in 0 1 2 3 4 5; do
  if _capture_shot "arrival-$i" && "$PYTHON" "$HERE/portal-colour.py" "$RUN_DIR/arrival-$i.png" "hue:$ORIGIN_HUE" --crop 1.0 \
      > "$RUN_DIR/arrival-$i.json" 2>>"$RUN_DIR/arrival.err"; then
    worst="$(jq -n --argjson a "$worst" --argjson b "$(jq .share "$RUN_DIR/arrival-$i.json")" '[$a, $b] | max')"
  fi
  sleep 0.5
done
printf '{"share": %s}\n' "$worst" > "$RUN_DIR/arrival-worst.json"
assert_json_at_most_float "the arrival frames carry none of the origin world's colour" \
  "$RUN_DIR/arrival-worst.json" '.share' "$ARRIVAL_CEILING"
sleep 7
state_refresh
assert_player_dimension "still in e2e_two ten seconds on" "$TWO"
assert_state "the crossing is journalled with the pre-loaded transfer told" \
  "$PLAYER_SELECT | .lastTraversal.preloadedSent" true
SRV="$RUN_DIR/mc-since.log"
(cd "$CONSUMER_DIR" && docker logs mc --since "$SINCE" > "$SRV" 2>&1) || true
assert_file_contains "the server sent the pre-loaded transfer" "$SRV" \
  "companion-send:preloaded-transfer player=$PLAYER dimension=$TWO"
CLI_AFTER="$RUN_DIR/client-after.log"
client_log_snapshot "$CLI_AFTER" 20000 || true
tail -n "+$((BEFORE_LINES + 1))" "$CLI_AFTER" > "$RUN_DIR/client-new.log" 2>/dev/null || cp "$CLI_AFTER" "$RUN_DIR/client-new.log"
assert_count_at_least "the client was told the crossing is pre-loaded" "$RUN_DIR/client-new.log" \
  "companion-feed: preloaded $TWO" 1
assert_shot "arrived in e2e_two" "arrived"

say "The measurement was not invalidated mid-run"
rcon "execute in $TWO run kill @e[tag=e2e]" >/dev/null
NOW_STARTED="$(cd "$CONSUMER_DIR" && docker inspect mc --format '{{.State.StartedAt}}')"
if [ "$NOW_STARTED" = "$STARTED_AT" ]; then
  _record ok "mc did not restart during the run" "$STARTED_AT" "$NOW_STARTED"
else
  _record fail "mc did not restart during the run" "$STARTED_AT" "$NOW_STARTED"
fi

{
  printf '# e2e-companion-portal\n\nplayer %s, run %s, fps floor %s\n\n| # | result | assertion | expected | actual |\n| --- | --- | --- | --- | --- |\n' \
    "$PLAYER" "$RUN_DIR" "$FPS_FLOOR"
  awk -F '\t' '$1 != "metric" { printf "| %s | %s | %s | %s | %s |\n", $1, $2, $3, $4, $5 }' "$RUN_DIR/assertions.tsv"
  printf '\nScreenshots: %s/*.png; census and portal reports: census-*.json, portals-*.json\n' "$RUN_DIR"
} > "$RESULTS"
note "results: $RESULTS"
finish
