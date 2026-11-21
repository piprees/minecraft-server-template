#!/usr/bin/env bash
# e2e-vanilla-portal.sh - The server-side portal, as a client without the companion meets it (26.x).
#
# Purpose: for each orientation (an X and a Z frame into adventure:e2e_two, a
#          flat frame into adventure:e2e_three) prove what an unmodded client
#          is given: the opening's cells are sent as the vanilla block for that
#          plane (Nether portal on the axis, End portal when flat), the
#          destination's particles reach the player, the destination's name
#          arrives as a title on approach, the screenshot's centre is painted
#          in the portal's colour, and the player crosses, arrives and stays.
#          Throughout, the player is not a companion and is sent no companion
#          payload. Two passes, one join each: SERVER_PACK=decline (default)
#          for a client that declined the server resource pack, which must
#          see the vanilla portal; SERVER_PACK=accept for one that loaded it,
#          which must be sent the pack's tripwire state and see the pane in
#          the cell's tint, with no vanilla portal in the centre.
# Context: template-only, 26.x, a linked consumer holding scripts/e2e/rig-26/
#          (README § On 26.x). Runs inside vanilla-join.sh --during with an
#          UNMODDED client (no --mrpack), so the X display and the game
#          directory come from it; its --server-pack must match SERVER_PACK. Facts are e2e-state's (the server needs
#          JVM_DD_OPTS='customdimensions.e2e=true'), the server log's, and the
#          screenshot's.
# Usage:   MC_VERSION_OVERRIDE=26.3 scripts/e2e/vanilla-join.sh --name VanillaGate \
#            --out /tmp/vanilla-portal --hold 20 \
#            --during "PLAYER=VanillaGate CONSUMER_DIR=$PWD scripts/e2e/e2e-vanilla-portal.sh"
#          MC_VERSION_OVERRIDE=26.3 scripts/e2e/vanilla-join.sh --name TintGate \
#            --out /tmp/vanilla-portal-pack --hold 20 --server-pack accept \
#            --during "SERVER_PACK=accept PLAYER=TintGate CONSUMER_DIR=$PWD scripts/e2e/e2e-vanilla-portal.sh"
#          RUN_ROOT (default /tmp/c0-e2e) receives results.md, assertions.tsv,
#          the e2e-state sequence and the screenshots. PYTHON names a Python
#          with Pillow (default python3). COLOUR_FLOOR (default 0.03) is the
#          share of the centre crop that must be portal-coloured;
#          VANILLA_CEILING (default 0.01) the most of it the vanilla portal may
#          paint in the accept pass.
# Gotchas: a client without the pack gets vanilla's own textures, so its
#          colour census asserts the vanilla portal and the particle record
#          the colour. The accept pass needs the stack to offer the pack
#          (dev-up.sh stages it: e2e-state's server.tintPack non-null) and
#          pack.<LOCAL_DOMAIN> to resolve on the client's machine. The rig
#          destinations' colours snap to light_blue and yellow, hues apart from
#          the vanilla portal, the sky and their frames. The title is sent once per
#          approach, so the player is first sent far away to reset it.
#          Exit is non-zero on any miss (lib.sh's EXIT trap).

# shellcheck disable=SC2034  # lib.sh reads it to name the run dir
SCRIPT_NAME="vanilla-portal"
# shellcheck source=lib.sh
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
# shellcheck source=rig-frames-lib.sh
. "$(cd "$(dirname "$0")" && pwd)/rig-frames-lib.sh"
# shellcheck source=portal-matrix-lib.sh
. "$(cd "$(dirname "$0")" && pwd)/portal-matrix-lib.sh"

HERE="$(cd "$(dirname "$0")" && pwd)"
PYTHON="${PYTHON:-python3}"
COLOUR_FLOOR="${COLOUR_FLOOR:-0.03}"
VANILLA_CEILING="${VANILLA_CEILING:-0.01}"
SERVER_PACK="${SERVER_PACK:-decline}"
DISPLAY_FOR_SHOTS="${VANILLA_JOIN_DISPLAY:-${DISPLAY:-}}"
RESULTS="$RUN_DIR/results.md"
POLL_SECONDS="${POLL_SECONDS:-60}"
POLL_STEP="${POLL_STEP:-3}"

banner "e2e-vanilla-portal — the server-side portal on an unmodded client"
[ "$E2E_26" -eq 1 ] || { note "26.x only"; finish; exit 3; }
require_mc_healthy
require_player_online
STARTED_AT="$(cd "$CONSUMER_DIR" && docker inspect mc --format '{{.State.StartedAt}}')"
SINCE="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

case "$SERVER_PACK" in
  accept | decline) ;;
  *) note "SERVER_PACK is accept or decline, not $SERVER_PACK"; finish; exit 2 ;;
esac
note "server resource pack: $SERVER_PACK"

say "The server pack"
state_refresh
if [ "$SERVER_PACK" = accept ]; then
  assert_state "the server offers the pack its mod carries (tints armed)" ".server.tintPack != null" true
  assert_state "the player loaded it" "$PLAYER_SELECT | .serverPack" true
else
  assert_state "the player did not load a server pack" "$PLAYER_SELECT | .serverPack" false
fi
report_metric "tint pack" "$(state_read '.server.tintPack')"

say "The player is not a companion"
assert_state "the server never admitted a companion hello" "$PLAYER_SELECT | .companion" false
assert_state "no companion payload was ever sent to it" "$PLAYER_SELECT | .companionPayloads" null

say "Open the rig levels"
for slug in e2e_one e2e_two e2e_three; do
  if rig_open "$slug"; then
    _record ok "$slug is open" open "open"
  else
    _record fail "$slug is open" open "not open after the cap"
  fi
done

screenshot_root() { # name -> $RUN_DIR/<name>.png
  if [ -z "$DISPLAY_FOR_SHOTS" ]; then
    note "no X display to photograph (run inside vanilla-join.sh --during)"
    return 1
  fi
  import -display "$DISPLAY_FOR_SHOTS" -window root "$RUN_DIR/$1.png" 2>"$RUN_DIR/$1.import.err"
}

# axis x0 z0 frame destination vanilla-block colour-mode
check_orientation() {
  local axis="$1" x0="$2" z0="$3" frame="$4" dest="$5" sees="$6" mode="$7" label share tick0 cells
  label="$axis frame into $dest"
  say "$label"
  rig_frame "$axis" "$x0" "$z0" "$frame"
  # shellcheck disable=SC2086
  wait_for_chunk $FRAME_CELL "$RIG_WORLD" 30 >/dev/null
  rig_light minecraft:amethyst_shard > "$RUN_DIR/use-$axis.txt"
  sleep 2
  state_refresh
  assert_state "$label: one source zone over the opening" "[$(rig_zone_select)] | length" 1
  assert_state "$label: it leads to $dest" "$(rig_zone_select) | .targetWorld" "$dest"
  assert_state "$label: every cell is lit" "$(rig_zone_select) | .lit" true
  cells="$(state_read "[$(rig_zone_select) | .cells[] | .clientSees] | unique | join(\" \")")"
  case "$cells" in
    *"$sees"*) _record ok "$label: a vanilla client is sent $sees for every cell" "$sees" "$cells" ;;
    *) _record fail "$label: a vanilla client is sent $sees for every cell" "$sees" "$cells" ;;
  esac
  assert_state "$label: the cells agree on one client block" \
    "[$(rig_zone_select) | .cells[] | .clientSees] | unique | length" 1
  tint="$(state_read "[$(rig_zone_select) | .cells[] | .tint] | unique | join(\" \")")"
  report_metric "$label: tint" "$tint"
  if [ "$SERVER_PACK" = accept ]; then
    assert_state "$label: a pack client is sent one tinted tripwire state for every cell" \
      "[$(rig_zone_select) | .cells[] | .packSees] | map(select(. != null and startswith(\"Block{minecraft:tripwire}\"))) | unique | length" 1
    assert_state "$label: every cell has a tinted state" \
      "[$(rig_zone_select) | .cells[] | select(.packSees == null)] | length" 0
  fi

  # Away and back: the title is once per approach.
  rig_tp "$FRAME_AWAY" "$FRAME_LOOK"
  sleep 2
  state_refresh
  tick0="$(state_read "$PLAYER_SELECT | .title.tick // -1")"
  rig_tp "$FRAME_STAND" "$FRAME_LOOK"
  sleep 3
  state_refresh
  assert_state "$label: the approach title names $dest" "$PLAYER_SELECT | .title.destination" "$dest"
  assert_state "$label: the title was sent for this opening" \
    "($PLAYER_SELECT | .title.portal) as \$p | [$(rig_zone_select) | .interior[] | select(. == \$p)] | length" 1
  assert_json_at_least "$label: the title is a new one (tick past $tick0)" "$(_state_path)" \
    "$PLAYER_SELECT | .title.tick" "$((tick0 + 1))"
  report_metric "$label: title text" "$(state_read "$PLAYER_SELECT | .title.text")"
  assert_state_at_least "$label: particle bursts at the opening" "$(rig_zone_select) | .particles.bursts" 1
  assert_state "$label: the player was in range of the last burst" \
    "$(rig_zone_select) | .particles.recipients | index(\$player) != null" true
  report_metric "$label: particle and colour" \
    "$(state_read "$(rig_zone_select) | .particles.particle + \" \" + .particles.color")"

  if screenshot_root "vanilla-$axis"; then
    share="$("$PYTHON" "$HERE/portal-colour.py" "$RUN_DIR/vanilla-$axis.png" "$mode" 2>"$RUN_DIR/colour-$axis.err")"
    printf '%s\n' "$share" > "$RUN_DIR/colour-$axis.json"
    if [ "$SERVER_PACK" = accept ]; then
      assert_json_at_most_float "$label: no vanilla portal in the centre ($mode)" \
        "$RUN_DIR/colour-$axis.json" '.share' "$VANILLA_CEILING"
      share="$("$PYTHON" "$HERE/portal-colour.py" "$RUN_DIR/vanilla-$axis.png" "hue:${tint#\#}" 2>>"$RUN_DIR/colour-$axis.err")"
      printf '%s\n' "$share" > "$RUN_DIR/tint-$axis.json"
      assert_json_at_least_float "$label: the centre is painted in the tint ($tint)" \
        "$RUN_DIR/tint-$axis.json" '.share' "$COLOUR_FLOOR"
    else
      assert_json_at_least_float "$label: the centre is painted as the vanilla portal ($mode)" \
        "$RUN_DIR/colour-$axis.json" '.share' "$COLOUR_FLOOR"
    fi
  else
    _record fail "$label: a screenshot of the approach" "$RUN_DIR/vanilla-$axis.png" \
      "$(cat "$RUN_DIR/vanilla-$axis.import.err" 2>/dev/null || echo 'no display')"
  fi

  rig_tp "$FRAME_ENTER" "$FRAME_LOOK"
  if wait_for_dimension "$dest" "$POLL_SECONDS"; then
    _record ok "$label: the player crossed" "$dest" "$DIM_NOTE"
    sleep 10
    state_refresh
    assert_player_dimension "$label: still in $dest ten seconds on" "$dest"
    assert_state "$label: the crossing is journalled into $dest" "$PLAYER_SELECT | .lastTraversal.to" "$dest"
    assert_state "$label: no pre-loaded transfer was told to a vanilla client" \
      "$PLAYER_SELECT | .lastTraversal.preloadedSent" false
  else
    _record fail "$label: the player crossed" "$dest" "$DIM_NOTE"
  fi
  rcon "execute in $RIG_WORLD run tp $PLAYER 4.5 -60 -8.5 0 0" >/dev/null
  wait_for_dimension "$RIG_WORLD" 30 >/dev/null || note "did not get back to $RIG_WORLD: $DIM_NOTE"
}

check_orientation X 20 24 minecraft:magenta_concrete "$RIG_NS:e2e_two" "minecraft:nether_portal}[axis=x]" nether
check_orientation Z 32 24 minecraft:magenta_concrete "$RIG_NS:e2e_two" "minecraft:nether_portal}[axis=z]" nether
check_orientation Y 44 24 minecraft:red_concrete "$RIG_NS:e2e_three" "minecraft:end_portal}" end

say "Never a companion payload"
state_refresh
assert_state "still not a companion" "$PLAYER_SELECT | .companion" false
assert_state "still no companion payload sent" "$PLAYER_SELECT | .companionPayloads" null
SRV="$RUN_DIR/mc-since.log"
(cd "$CONSUMER_DIR" && docker logs mc --since "$SINCE" > "$SRV" 2>&1) || true
if grep -aE "companion-(accept|send|feed:start)[^ ]* player=$PLAYER( |$)" "$SRV" > "$RUN_DIR/companion-lines.log"; then
  _record fail "the server log has no companion line for $PLAYER" "none" "$(head -1 "$RUN_DIR/companion-lines.log")"
else
  _record ok "the server log has no companion line for $PLAYER" "none" "none in $(wc -l < "$SRV" | tr -d ' ') lines"
fi

say "The measurement was not invalidated mid-run"
NOW_STARTED="$(cd "$CONSUMER_DIR" && docker inspect mc --format '{{.State.StartedAt}}')"
if [ "$NOW_STARTED" = "$STARTED_AT" ]; then
  _record ok "mc did not restart during the run" "$STARTED_AT" "$NOW_STARTED"
else
  _record fail "mc did not restart during the run" "$STARTED_AT" "$NOW_STARTED"
fi

{
  printf '# e2e-vanilla-portal (server pack: %s)\n\nplayer %s, run %s\n\n| # | result | assertion | expected | actual |\n| --- | --- | --- | --- | --- |\n' \
    "$SERVER_PACK" "$PLAYER" "$RUN_DIR"
  awk -F '\t' '$1 != "metric" { printf "| %s | %s | %s | %s | %s |\n", $1, $2, $3, $4, $5 }' "$RUN_DIR/assertions.tsv"
  printf '\nScreenshots: %s/vanilla-*.png\n' "$RUN_DIR"
} > "$RESULTS"
note "results: $RESULTS"
finish
