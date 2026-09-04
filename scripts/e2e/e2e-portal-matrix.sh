#!/usr/bin/env bash
# e2e-portal-matrix.sh - the whole portal lifecycle, once per configured
# dimension, in one carved room.
#
# Purpose: ingest the dimension configs, carve a bay per mod-managed portal,
#          build its frame (and a deliberate near neighbour) unlit, then per
#          dimension: ignite, cross, come back, break it, re-light it, and
#          light the neighbour - asserting at every step from the SERVER's own
#          zone records. The client is used for the render step and nothing
#          else, so a client death costs the screenshots and no linking result.
#
# Context: local dev stack only (~/Projects/elfydd, `./dev link`ed to a
#          platform checkout). Template-only: not in build-stack-bundle.sh's
#          MANIFEST, not reachable from `ops` or `dev`, by design.
#          The geometry and every expectation come from portal-matrix-plan.py -
#          nothing here is a hardcoded coordinate.
#
# Usage:   ./dev up                       # the jar under test must be SERVED
#          ./dev launch --dev-bridge      # only needed for --render
#          ./scripts/e2e/e2e-portal-matrix.sh [options]
#
#   --only SLUG[,SLUG]   restrict to these dimensions
#   --limit N            first N bays
#   --from SLUG          resume at this dimension
#   --horizontal MODE    forced (default) | all | none - which dimensions get
#                        a Y-axis bay. `all` is step 9's full second matrix.
#   --render N|all|none  how many bays get screenshots (default 4)
#   --build-only         carve and build every frame, run no lifecycle
#   --keep               leave the frames lit and the room standing
#   --restore            fill each bay back to stone when it is done
#   --plan FILE          use an existing plan instead of generating one
#   --dry-run            print the plan's counts and the first bay, touch nothing
#   --non-interactive    never prompt (the default when stdin is not a tty)
#
# Environment:
#   IGNITER_EXPECTATION  auto (default) | untouched | damaged | consumed.
#                        THE one switch for the igniter contract - see below.
#   CONSUMER_DIR, RUN_ROOT, BRIDGE_PORT, PLAYER   as in lib.sh.
#
# THE IGNITER SWITCH lives in `IGNITER_EXPECTATION` and is resolved, once, by
#   portal-matrix-plan.py's `resolve_igniter`. `auto` asserts the RULED
#   contract: a damageable igniter takes one point of damage, anything else is
#   left alone. `consumed` asserts today's build, which spends one of whatever
#   was used. Nothing in this file knows what an igniter is - it reads
#   `igniterPredicate` and `igniterVerdict` off the plan.
#
# Gotchas: 79 bays is hours, and dimensions are created ONE AT A TIME on
#          purpose (TROUBLESHOOTING.md#k6) - use --limit/--only/--from.
#          A dimension that idles out (5 minutes) stops listing its zones, so
#          every arrival assertion first proves the destination is still
#          loaded rather than reading an unload as an unlink.
#          The ignition click is made from the LANE, never from inside the
#          opening: a player standing in the frame crosses the instant it
#          lights, and that crossing is not the one being measured.
#          An anchor dimension does NOT break symmetrically
#          (PortalHelper.breakLinkedArrival returns 0 for one), so its bay
#          asserts the arrival SURVIVES. That is the config's behaviour, not a
#          weakened assertion.

# shellcheck disable=SC2034  # lib.sh reads it to name the run dir
SCRIPT_NAME="portal-matrix"
# shellcheck source=lib.sh
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
# shellcheck source=portal-matrix-lib.sh
. "$(cd "$(dirname "$0")" && pwd)/portal-matrix-lib.sh"

HERE="$(cd "$(dirname "$0")" && pwd)"
PLANNER="$HERE/portal-matrix-plan.py"

ONLY=""
LIMIT=0
FROM=""
HORIZONTAL="forced"
RENDER_BUDGET=4
BUILD_ONLY=0
KEEP=0
RESTORE=0
PLAN=""
DRY_RUN=0
NON_INTERACTIVE=0
[ -t 0 ] || NON_INTERACTIVE=1

while [ $# -gt 0 ]; do
  case "$1" in
    --only) ONLY="$2"; shift 2 ;;
    --limit) LIMIT="$2"; shift 2 ;;
    --from) FROM="$2"; shift 2 ;;
    --horizontal) HORIZONTAL="$2"; shift 2 ;;
    --render) RENDER_BUDGET="$2"; shift 2 ;;
    --build-only) BUILD_ONLY=1; shift ;;
    --keep) KEEP=1; shift ;;
    --restore) RESTORE=1; shift ;;
    --plan) PLAN="$2"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    --non-interactive) NON_INTERACTIVE=1; shift ;;
    -h|--help) sed -n '2,60p' "$0"; exit 0 ;;
    *) printf 'unknown option: %s\n' "$1" >&2; exit 2 ;;
  esac
done
case "$RENDER_BUDGET" in
  all) RENDER_BUDGET=999999 ;;
  none) RENDER_BUDGET=0 ;;
esac

IGNITER_EXPECTATION="${IGNITER_EXPECTATION:-auto}"
RENDER_DONE=0
RENDER_OK=1

mkdir -p "$RUN_DIR/dim"
SUMMARY="$RUN_DIR/summary.tsv"
printf 'bay\tdimension\taxis\tignite\ttravel\treturn\tbreak\trelight\tproximity\trender\tpass\tfail\n' \
  > "$SUMMARY"

# ---------------------------------------------------------------------------
# Gates
# ---------------------------------------------------------------------------

banner "portal matrix — the whole lifecycle, once per configured dimension"

if [ "$DRY_RUN" -eq 0 ]; then
  require_backup_idle
  require_mc_healthy
  require_player_online
fi

# ---------------------------------------------------------------------------
# The plan
# ---------------------------------------------------------------------------
say "Plan"
if [ -z "$PLAN" ]; then
  PLAN="$RUN_DIR/plan.json"
  if ! python3 "$PLANNER" \
      --horizontal "$HORIZONTAL" \
      --igniter-expectation "$IGNITER_EXPECTATION" \
      --only "$ONLY" --limit "$LIMIT" --from "$FROM" > "$PLAN" 2> "$RUN_DIR/plan.err"; then
    printf '\033[31mREFUSING TO RUN\033[0m — the planner failed:\n' >&2
    cat "$RUN_DIR/plan.err" >&2
    exit 1
  fi
fi
note "plan: $PLAN"
note "configs read from $(plan_read '.source.dimensionsDir') (namespace $(plan_read '.source.namespace'))"

TOTAL="$(plan_read '.counts.bays')"
case "$TOTAL" in ''|*[!0-9]*) printf 'the plan carries no bay count: %s\n' "$TOTAL" >&2; exit 1 ;; esac

# The denominator, printed next to the result. A run over zero bays reads
# exactly like a clean sweep otherwise.
report_metric "bays to run" "$TOTAL" \
  "$(plan_read '.counts.horizontalBays') horizontal, $(plan_read '.counts.anchorBays') anchor"
report_metric "dimensions left out" "$(plan_read '.counts.skipped')" \
  "each with its reason in $PLAN under .skipped"
report_metric "twins too far apart to link" "$(plan_read '.counts.unlinkableTwins')" \
  "their proximity step is skipped with that reason, never counted as a pass"
report_metric "igniter contract asserted" "$(plan_read '.options.igniterExpectation')" \
  "resolved per dimension by portal-matrix-plan.py's resolve_igniter"

DRIFT="$(plan_read '[.source.portalDrift[]] | length')"
if [ "$DRIFT" != "0" ]; then
  note "PORTAL CONFIG DRIFT: the server's rendered config differs from the platform's"
  note "for: $(plan_read '.source.portalDrift | join(", ")')"
  note "data/config/ is seeded skip-if-exists — ./dev refresh-config (TROUBLESHOOTING.md#t78)"
fi
report_metric "dimensions whose rendered portal differs from the platform config" "$DRIFT" \
  "this run measures the config the SERVER has"

jq -r '.skipped[] | "  skipped \(.slug) \(.axis // "") — \(.reason)"' "$PLAN" \
  > "$RUN_DIR/skipped.txt" 2>/dev/null || true

if [ "$DRY_RUN" -eq 1 ]; then
  say "Dry run — the first bay"
  jq '.bays[0]' "$PLAN"
  head -40 "$RUN_DIR/skipped.txt"
  finish
  exit 0
fi

if [ "$TOTAL" -gt 12 ] && [ "$NON_INTERACTIVE" -eq 0 ]; then
  printf '\n%s bays will each create a dimension and drive a full portal lifecycle.\n' "$TOTAL"
  printf 'That is hours, and dimensions are created one at a time on purpose.\n'
  printf 'Continue? [y/N] '
  read -r reply
  case "$reply" in y|Y|yes) ;; *) printf 'stopped.\n'; exit 1 ;; esac
fi

STARTED_AT="$(cd "$CONSUMER_DIR" && docker inspect mc --format '{{.State.StartedAt}}')"
note "mc StartedAt: $STARTED_AT"

# A lit aura can place lava and start fires (three dimensions do). This changes
# nothing about ignition, linking or breaking — it stops the harness's own
# player dying inside a bay it lit.
rcon "effect give $PLAYER minecraft:fire_resistance 999999 0 true" >/dev/null

# ---------------------------------------------------------------------------
# Build phase — carve every bay and stand every frame up, unlit
# ---------------------------------------------------------------------------
say "Carve the room and build every frame, unlit"
BAY_INDEX=0
while [ "$BAY_INDEX" -lt "$TOTAL" ]; do
  ID="$(bay_read '.id')"
  read -r CFX CFZ <<EOF
$(triple '.room.chunkFrom')
EOF
  read -r CTX CTZ <<EOF
$(triple '.room.chunkTo')
EOF
  read -r BX1 BY1 BZ1 <<EOF
$(triple '.room.box[0:3]')
EOF
  read -r BX2 BY2 BZ2 <<EOF
$(triple '.room.box[3:6]')
EOF
  FRAME="$(bay_read '.frameBlock')"
  CEIL="$(bay_read '.room.ceilingY')"

  note "bay $((BAY_INDEX + 1))/$TOTAL $ID — carving $BX1,$BY1,$BZ1 .. $BX2,$BY2,$BZ2"
  rcon "execute in minecraft:overworld run forceload add $CFX $CFZ $CTX $CTZ" >/dev/null
  if ! wait_for_chunk "$BX1" "$BY1" "$BZ1" minecraft:overworld 90; then
    _record fail "bay $ID: its chunks generated" "a loaded column at $BX1 $BY1 $BZ1" \
      "still not loaded after 90s — nothing was built here"
    rcon "execute in minecraft:overworld run forceload remove $CFX $CFZ $CTX $CTZ" >/dev/null
    BAY_INDEX=$((BAY_INDEX + 1))
    continue
  fi
  # `hollow` is the carve: a one-block stone shell, everything inside it air.
  # It is also what keeps a cave, an aquifer or a lava lake out of the bay.
  rcon "execute in minecraft:overworld run fill $BX1 $BY1 $BZ1 $BX2 $BY2 $BZ2 minecraft:stone hollow" >/dev/null
  rcon "execute in minecraft:overworld run fill $((BX1 + 1)) $CEIL $((BZ1 + 1)) $((BX2 - 1)) $CEIL $((BZ2 - 1)) minecraft:glowstone" >/dev/null

  jq -r ".bays[$BAY_INDEX].primary.ring[], .bays[$BAY_INDEX].twin.ring[] | \"\(.[0]) \(.[1]) \(.[2])\"" \
    "$PLAN" > "$RUN_DIR/dim/$ID.ring.txt"
  jq -r ".bays[$BAY_INDEX].primary.interior[], .bays[$BAY_INDEX].twin.interior[] | \"\(.[0]) \(.[1]) \(.[2])\"" \
    "$PLAN" > "$RUN_DIR/dim/$ID.interior.txt"
  place_cells "bay $ID: both frame rings stand in $FRAME" "$RUN_DIR/dim/$ID.ring.txt" "$FRAME"
  place_cells "bay $ID: both openings are clear" "$RUN_DIR/dim/$ID.interior.txt" minecraft:air
  rcon "execute in minecraft:overworld run forceload remove $CFX $CFZ $CTX $CTZ" >/dev/null
  BAY_INDEX=$((BAY_INDEX + 1))
done

if [ "$BUILD_ONLY" -eq 1 ]; then
  say "--build-only: every frame is standing and unlit"
  finish
  exit 0
fi

# ---------------------------------------------------------------------------
# The lifecycle, one dimension at a time
# ---------------------------------------------------------------------------

# Each step's verdict for the summary table, so a bay reports which half of it
# failed rather than a single number.
S_IGNITE=""; S_TRAVEL=""; S_RETURN=""; S_BREAK=""; S_RELIGHT=""; S_PROX=""; S_RENDER=""
verdict() { # varname ok|fail|skip
  eval "$1=\"\$2\""
}

# One row per bay, and the bay's own slice of assertions.tsv appended to its
# log. Called on every exit from run_bay, so a bay that failed early still
# appears in the table rather than vanishing from the denominator.
bay_summary() { # passes-before failures-before rows-before
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$ID" "$DIMENSION" "$AXIS" "$S_IGNITE" "$S_TRAVEL" "$S_RETURN" "$S_BREAK" \
    "$S_RELIGHT" "$S_PROX" "$S_RENDER" \
    "$((PASSES - $1))" "$((FAILURES - $2))" >> "$SUMMARY"
  tail -n "+$(($3 + 1))" "$RUN_DIR/assertions.tsv" >> "$BAY_LOG"
}

# Exactly one igniter in the main hand, which is what makes "absent afterwards"
# mean consumed. NEVER `clear` — this drives a real player on a shared server
# and that empties their whole inventory; `item replace` touches one slot.
give_igniter() {
  rcon "item replace entity $PLAYER weapon.mainhand with $IGNITER 1" >/dev/null
  sleep 1
}

run_bay() {
  local n=$((BAY_INDEX + 1))
  local pass_before=$PASSES fail_before=$FAILURES
  local rows_before
  rows_before="$(wc -l < "$RUN_DIR/assertions.tsv" | tr -d ' ')"
  S_IGNITE="-"; S_TRAVEL="-"; S_RETURN="-"; S_BREAK="-"
  S_RELIGHT="-"; S_PROX="-"; S_RENDER="-"

  ID="$(bay_read '.id')"
  DIMENSION="$(bay_read '.dimension')"
  AXIS="$(bay_read '.axis')"
  FRAME="$(bay_read '.frameBlock')"
  IGNITER="$(bay_read '.igniter')"
  EXPECTATION="$(bay_read '.igniterExpectation')"
  PREDICATE="$(bay_read '.igniterPredicate')"
  VERDICT="$(bay_read '.igniterVerdict')"
  ASSERTABLE="$(bay_read '.igniterAssertable')"
  ANCHOR="$(bay_read '.anchor')"
  SINGLE_USE="$(bay_read '.singleUse')"
  SYMMETRIC="$(bay_read '.breaksSymmetrically')"
  RELIGHT_EXPECT="$(bay_read '.relightExpectation')"
  LINKABLE="$(bay_read '.twin.linkable')"
  BAY_LOG="$RUN_DIR/dim/$ID.log"

  read -r CFX CFZ <<EOF
$(triple '.room.chunkFrom')
EOF
  read -r CTX CTZ <<EOF
$(triple '.room.chunkTo')
EOF
  read -r IX IY IZ <<EOF
$(triple '.primary.interior[0]')
EOF
  read -r TX TY TZ <<EOF
$(triple '.twin.interior[0]')
EOF
  read -r BKX BKY BKZ <<EOF
$(triple '.primary.breakCell')
EOF
  UX="$(bay_read '.primary.use[0]')"; UY="$(bay_read '.primary.use[1]')"
  UZ="$(bay_read '.primary.use[2]')"; UF="$(bay_read '.primary.use[3]')"
  TUX="$(bay_read '.twin.use[0]')"; TUY="$(bay_read '.twin.use[1]')"
  TUZ="$(bay_read '.twin.use[2]')"; TUF="$(bay_read '.twin.use[3]')"
  STAND="$(bay_read '.primary.stand | map(tostring) | join(" ")')"
  TSTAND="$(bay_read '.twin.stand | map(tostring) | join(" ")')"
  ENTER="$(bay_read '.primary.enter | map(tostring) | join(" ")')"
  TENTER="$(bay_read '.twin.enter | map(tostring) | join(" ")')"
  LOOK="$(bay_read '.room.look | map(tostring) | join(" ")')"
  VIEW="$(bay_read '.room.view | map(tostring) | join(" ")')"
  SRC_CX="$(bay_read '.primary.centreColumn[0]')"
  SRC_CZ="$(bay_read '.primary.centreColumn[1]')"

  say "[$n/$TOTAL] $ID — $DIMENSION  axis=$AXIS  scale=$(bay_read '.scale')  shape=$(bay_read '.shape')"
  {
    printf 'bay %s\n' "$ID"
    printf 'dimension %s  axis %s  shape %s  orientation %s  scale %s\n' \
      "$DIMENSION" "$AXIS" "$(bay_read '.shape')" "$(bay_read '.orientation')" "$(bay_read '.scale')"
    printf 'frame %s  igniter %s  expectation %s (%s -> %s)\n' \
      "$FRAME" "$IGNITER" "$EXPECTATION" "$PREDICATE" "$VERDICT"
    printf 'anchor %s  singleUse %s  breaksSymmetrically %s  relightExpectation %s\n' \
      "$ANCHOR" "$SINGLE_USE" "$SYMMETRIC" "$RELIGHT_EXPECT"
    printf 'primary interior %s %s %s   twin interior %s %s %s   twin linkable %s\n' \
      "$IX" "$IY" "$IZ" "$TX" "$TY" "$TZ" "$LINKABLE"
  } > "$BAY_LOG"

  rcon "execute in minecraft:overworld run forceload add $CFX $CFZ $CTX $CTZ" >/dev/null
  rcon "execute in minecraft:overworld run tp $PLAYER $STAND $LOOK" >/dev/null
  sleep 2
  if ! wait_for_chunk "$IX" "$IY" "$IZ" minecraft:overworld 60; then
    _record fail "$ID: the bay is loaded before anything is measured" \
      "a loaded column at $IX $IY $IZ" "still cold after 60s"
    S_IGNITE="cold"
    bay_summary "$pass_before" "$fail_before" "$rows_before"
    return
  fi

  # -------------------------------------------------------------- 1. IGNITE
  say "  1. ignite"
  log_mark
  LINKS_BEFORE="$(links_records)"
  state_refresh
  assert_state "$ID: no source zone over this frame before the click" \
    "[$(source_zone_select "$IX" "$IY" "$IZ")] | length" 0
  assert_block "$ID: the opening is clear before the click" "$IX" "$IY" "$IZ" minecraft:air present

  give_igniter
  sleep 1
  # The negative control for the igniter instrument: it has to be seen reading
  # the item present before an "absent" afterwards means anything.
  assert_items "$ID: NEGATIVE CONTROL — the igniter is in hand before the click" \
    "$IGNITER" present

  use_block "$UX" "$UY" "$UZ" "$UF" >/dev/null
  sleep 3
  log_since "$RUN_DIR/dim/$ID.ignite.log"
  grep -a "portal ignition refused" "$RUN_DIR/dim/$ID.ignite.log" \
    > "$RUN_DIR/dim/$ID.refusals.log" 2>/dev/null || true
  REFUSALS="$(wc -l < "$RUN_DIR/dim/$ID.refusals.log" | tr -d ' ')"
  REASONS="$(sed -n 's/.*refused[^A-Z]*\([A-Z_]\{4,\}\).*/\1/p' \
    "$RUN_DIR/dim/$ID.refusals.log" | sort -u | tr '\n' ',' )"
  report_metric "$ID: ignition refusals logged" "$REFUSALS" "reasons: ${REASONS:-none}"

  state_refresh
  ZONES_HERE="$(state_read "[$(source_zone_select "$IX" "$IY" "$IZ")] | length")"
  if [ "$ZONES_HERE" = "1" ]; then
    assert_source_zone "$ID: the frame's own zone leads to $DIMENSION" \
      "$IX" "$IY" "$IZ" "$DIMENSION"
    assert_source_zone_unique "$ID: exactly one source zone over this frame" "$IX" "$IY" "$IZ"
    assert_source_zone_frame_stands "$ID: the frame stands" "$IX" "$IY" "$IZ"
    verdict S_IGNITE ok
  else
    # The refusal reason IS the failure, not "it did not light".
    _record fail "$ID: the frame lit" "one source zone over $IX $IY $IZ" \
      "$ZONES_HERE zones; refusals this click: ${REASONS:-none logged (is CUSTOMDIM_LOG_LEVEL=debug set?)}"
    verdict S_IGNITE fail
  fi

  if [ "$ASSERTABLE" = "true" ]; then
    assert_items "$ID: the igniter after ignition — expectation '$EXPECTATION'" \
      "$PREDICATE" "$VERDICT"
  else
    report_metric "$ID: igniter assertion skipped" "$EXPECTATION" "$(bay_read '.igniterNote')"
  fi
  report_metric "$ID: portal_links.json records after ignition" "$(links_records)" \
    "was $LINKS_BEFORE before the click"

  if [ "$S_IGNITE" != "ok" ]; then
    note "  the frame never lit — the rest of this bay has nothing to measure"
    bay_summary "$pass_before" "$fail_before" "$rows_before"
    return
  fi

  # -------------------------------------------------------------- 2. TRAVEL
  say "  2. travel in"
  ARR_BEFORE="$(arrival_zone_count "$DIMENSION")"
  rcon "execute in minecraft:overworld run tp $PLAYER $ENTER $LOOK" >/dev/null
  if wait_for_dimension "$DIMENSION" "$POLL_SECONDS"; then
    _record ok "$ID: the player crossed into $DIMENSION" "$DIMENSION" "$DIM_NOTE"
    verdict S_TRAVEL ok
  else
    _record fail "$ID: the player crossed into $DIMENSION" "$DIMENSION" "$DIM_NOTE"
    verdict S_TRAVEL fail
  fi
  assert_player_dimension "$ID: the server has the player in $DIMENSION" "$DIMENSION"
  AX="$(state_read "$PLAYER_SELECT | .blockPos[0]")"
  AY="$(state_read "$PLAYER_SELECT | .blockPos[1]")"
  AZ="$(state_read "$PLAYER_SELECT | .blockPos[2]")"
  report_metric "$ID: the arrival the player landed in" "$AX $AY $AZ" \
    "carved or reused — an arrival coordinate is not stable across a break"
  assert_state "$ID: an arrival zone stands where the player landed" \
    "[$(arrival_zone_select "$DIMENSION" "$AX" "$AY" "$AZ")] | length" 1
  ARR_AFTER="$(arrival_zone_count "$DIMENSION")"
  report_metric "$ID: arrival zones in $DIMENSION" "$ARR_AFTER" "was $ARR_BEFORE before the crossing"
  printf 'arrival after first crossing: %s %s %s (zones %s -> %s)\n' \
    "$AX" "$AY" "$AZ" "$ARR_BEFORE" "$ARR_AFTER" >> "$BAY_LOG"

  # -------------------------------------------------------------- 3. RETURN
  say "  3. come back"
  # The player is standing IN the arrival, so the return fires by itself once
  # the portal cooldown expires. If it does not, put them back in the cell
  # once and say which path was needed.
  RETURN_PATH="unprompted"
  if ! wait_for_dimension minecraft:overworld "$POLL_SECONDS"; then
    RETURN_PATH="re-entered the arrival cell"
    rcon "execute in $DIMENSION run tp $PLAYER $AX.5 $AY $AZ.5" >/dev/null
    wait_for_dimension minecraft:overworld "$POLL_SECONDS" || true
  fi
  if [ "$(player_dimension)" = "minecraft:overworld" ]; then
    _record ok "$ID: the return trip landed back in the overworld" \
      "minecraft:overworld" "$DIM_NOTE ($RETURN_PATH)"
    verdict S_RETURN ok
  else
    _record fail "$ID: the return trip landed back in the overworld" \
      "minecraft:overworld" "$DIM_NOTE ($RETURN_PATH)"
    verdict S_RETURN fail
  fi
  RX="$(state_read "$PLAYER_SELECT | .blockPos[0]")"
  RZ="$(state_read "$PLAYER_SELECT | .blockPos[2]")"
  # The source frame is what the return has to find, and the frame is four
  # blocks wide at most, so the column plus the aura pad is the tolerance.
  if near_column "$RX" "$RZ" "$SRC_CX" "$SRC_CZ" 4; then
    _record ok "$ID: the return landed at the source frame" \
      "within 4 blocks of column $SRC_CX,$SRC_CZ" "$RX,$RZ"
  else
    _record fail "$ID: the return landed at the source frame" \
      "within 4 blocks of column $SRC_CX,$SRC_CZ" "$RX,$RZ"
  fi

  # --------------------------------------------------------------- 4. BREAK
  say "  4. break the overworld side"
  log_mark
  rcon "execute in minecraft:overworld run tp $PLAYER $STAND $LOOK" >/dev/null
  sleep 2
  DEST_LOADED_BEFORE="$(world_loaded "$DIMENSION")"
  rcon "execute in minecraft:overworld run setblock $BKX $BKY $BKZ minecraft:air" >/dev/null
  if wait_for_state "[$(source_zone_select "$IX" "$IY" "$IZ")] | length" 0 60; then
    _record ok "$ID: breaking one frame block closed the source zone" "0 source zones" "$SETTLED"
  else
    _record fail "$ID: breaking one frame block closed the source zone" "0 source zones" "$SETTLED"
  fi
  log_since "$RUN_DIR/dim/$ID.break.log"
  DEST_LOADED="$(world_loaded "$DIMENSION")"
  if [ "$DEST_LOADED" != "1" ]; then
    # e2e-state lists zones only for loaded worlds, so an unloaded destination
    # makes "no arrival zone" unreadable rather than true.
    _record fail "$ID: $DIMENSION was still loaded when the arrival was checked" \
      "1 loaded world with that id" \
      "$DEST_LOADED — its zones are not listed, so the unlink could not be measured"
    verdict S_BREAK fail
  elif [ "$SYMMETRIC" = "true" ]; then
    assert_state "$ID: the arrival unlinked with the source" \
      "[$(arrival_zone_select "$DIMENSION" "$AX" "$AY" "$AZ")] | length" 0
    assert_file_contains "$ID: the server said it closed the far end" \
      "$RUN_DIR/dim/$ID.break.log" "Source portal broken in"
    verdict S_BREAK ok
  else
    # PortalHelper.breakLinkedArrival returns 0 for an anchor definition: one
    # arrival is shared by every source into that dimension.
    assert_state "$ID: ANCHOR — the shared arrival survives the source break" \
      "[$(arrival_zone_select "$DIMENSION" "$AX" "$AY" "$AZ")] | length" 1
    assert_at_most "$ID: ANCHOR — nothing claimed to close the far end" \
      "$RUN_DIR/dim/$ID.break.log" "Source portal broken in" 0
    verdict S_BREAK ok
  fi
  report_metric "$ID: portal_links.json records after the break" "$(links_records)" \
    "was $LINKS_BEFORE before this bay started"
  printf 'after break: dest loaded %s (was %s), links %s\n' \
    "$DEST_LOADED" "$DEST_LOADED_BEFORE" "$(links_records)" >> "$BAY_LOG"

  # ---------------------------------------------------- 5. REBUILD + RELIGHT
  say "  5. rebuild and re-light the same frame"
  rcon "execute in minecraft:overworld run setblock $BKX $BKY $BKZ $FRAME" >/dev/null
  sleep 1
  assert_block "$ID: the broken frame block is back" "$BKX" "$BKY" "$BKZ" "$FRAME" present
  give_igniter
  sleep 1
  use_block "$UX" "$UY" "$UZ" "$UF" >/dev/null
  sleep 3
  state_refresh
  if [ "$(state_read "[$(source_zone_select "$IX" "$IY" "$IZ")] | length")" = "1" ]; then
    _record ok "$ID: the rebuilt frame lit again" "one source zone" "1"
  else
    _record fail "$ID: the rebuilt frame lit again" "one source zone" \
      "$(state_read "[$(source_zone_select "$IX" "$IY" "$IZ")] | length")"
  fi
  rcon "execute in minecraft:overworld run tp $PLAYER $ENTER $LOOK" >/dev/null
  if wait_for_dimension "$DIMENSION" "$POLL_SECONDS"; then
    BX2A="$(state_read "$PLAYER_SELECT | .blockPos[0]")"
    BY2A="$(state_read "$PLAYER_SELECT | .blockPos[1]")"
    BZ2A="$(state_read "$PLAYER_SELECT | .blockPos[2]")"
    # findRegisteredPortalNear's own box: (5, 16) in (x/z, y).
    if within_reuse_box "$BX2A" "$BY2A" "$BZ2A" "$AX" "$AY" "$AZ"; then
      RELIGHT_SAW="linked-to-old"
    else
      RELIGHT_SAW="new-arrival"
    fi
    report_metric "$ID: what the re-lit frame did" "$RELIGHT_SAW" \
      "first arrival $AX $AY $AZ, second $BX2A $BY2A $BZ2A"
    if [ "$RELIGHT_SAW" = "$RELIGHT_EXPECT" ]; then
      _record ok "$ID: re-light — the code says '$RELIGHT_EXPECT'" \
        "$RELIGHT_EXPECT" "$RELIGHT_SAW ($AX $AY $AZ -> $BX2A $BY2A $BZ2A)"
    else
      _record fail "$ID: re-light — the code says '$RELIGHT_EXPECT'" \
        "$RELIGHT_EXPECT" "$RELIGHT_SAW ($AX $AY $AZ -> $BX2A $BY2A $BZ2A)"
    fi
    verdict S_RELIGHT ok
    AX="$BX2A"; AY="$BY2A"; AZ="$BZ2A"
    printf 'relight: expected %s, saw %s; arrival now %s %s %s\n' \
      "$RELIGHT_EXPECT" "$RELIGHT_SAW" "$AX" "$AY" "$AZ" >> "$BAY_LOG"
    wait_for_dimension minecraft:overworld "$POLL_SECONDS" || true
  else
    _record fail "$ID: the re-lit frame crossed again" "$DIMENSION" "$DIM_NOTE"
    verdict S_RELIGHT fail
  fi

  # -------------------------------------------------------------- 7. RENDER
  if [ "$RENDER_DONE" -lt "$RENDER_BUDGET" ] && [ "$RENDER_OK" -eq 1 ]; then
    render_bay
  else
    verdict S_RENDER skip
  fi

  # ----------------------------------------------------------- 8. PROXIMITY
  say "  8. a second frame right beside the first"
  if [ "$LINKABLE" != "true" ]; then
    report_metric "$ID: proximity step skipped" "twin not linkable" "$(bay_read '.twin.linkNote')"
    verdict S_PROX skip
  else
    rcon "execute in minecraft:overworld run tp $PLAYER $TSTAND $LOOK" >/dev/null
    sleep 2
    ARR_BEFORE_TWIN="$(arrival_zone_count "$DIMENSION")"
    give_igniter
    sleep 1
    use_block "$TUX" "$TUY" "$TUZ" "$TUF" >/dev/null
    sleep 3
    state_refresh
    if [ "$(state_read "[$(source_zone_select "$TX" "$TY" "$TZ")] | length")" = "1" ]; then
      assert_source_zone "$ID: the neighbour's own zone leads to $DIMENSION" \
        "$TX" "$TY" "$TZ" "$DIMENSION"
      rcon "execute in minecraft:overworld run tp $PLAYER $TENTER $LOOK" >/dev/null
      if wait_for_dimension "$DIMENSION" "$POLL_SECONDS"; then
        PX="$(state_read "$PLAYER_SELECT | .blockPos[0]")"
        PY="$(state_read "$PLAYER_SELECT | .blockPos[1]")"
        PZ="$(state_read "$PLAYER_SELECT | .blockPos[2]")"
        if within_reuse_box "$PX" "$PY" "$PZ" "$AX" "$AY" "$AZ"; then
          _record ok "$ID: the neighbour reused the existing arrival" \
            "within (5,16) of $AX $AY $AZ" "$PX $PY $PZ"
        else
          _record fail "$ID: the neighbour reused the existing arrival" \
            "within (5,16) of $AX $AY $AZ" "$PX $PY $PZ — it built its own"
        fi
        assert_state "$ID: no second arrival was built for the neighbour" \
          "[.zones[] | select(.kind==\"arrival\" and .world==\"$DIMENSION\")] | length" \
          "$ARR_BEFORE_TWIN"
        verdict S_PROX ok
        wait_for_dimension minecraft:overworld "$POLL_SECONDS" || true
      else
        _record fail "$ID: the neighbour crossed into $DIMENSION" "$DIMENSION" "$DIM_NOTE"
        verdict S_PROX fail
      fi
    else
      log_since "$RUN_DIR/dim/$ID.twin.log"
      _record fail "$ID: the neighbouring frame lit" "one source zone over $TX $TY $TZ" \
        "$(sed -n 's/.*refused[^A-Z]*\([A-Z_]\{4,\}\).*/\1/p' "$RUN_DIR/dim/$ID.twin.log" \
           | sort -u | tr '\n' ',')"
      verdict S_PROX fail
    fi
  fi

  # --------------------------------------------------------------- teardown
  if [ "$KEEP" -eq 0 ]; then
    rcon "execute in minecraft:overworld run setblock $BKX $BKY $BKZ minecraft:air" >/dev/null
    rcon "execute in minecraft:overworld run setblock $((TX - 1)) $TY $TZ minecraft:air" >/dev/null
    sleep 2
  fi
  if [ "$RESTORE" -eq 1 ]; then
    read -r RB1 RB2 RB3 <<EOF
$(triple '.room.box[0:3]')
EOF
    read -r RB4 RB5 RB6 <<EOF
$(triple '.room.box[3:6]')
EOF
    rcon "execute in minecraft:overworld run fill $RB1 $RB2 $RB3 $RB4 $RB5 $RB6 minecraft:stone" >/dev/null
  fi
  rcon "execute in minecraft:overworld run forceload remove $CFX $CFZ $CTX $CTZ" >/dev/null

  bay_summary "$pass_before" "$fail_before" "$rows_before"
  note "  bay log: $BAY_LOG"
}

# The only step that needs the client. A dead bridge fails this and nothing
# else: RENDER_OK stays down for the rest of the run and every later bay
# records a skip rather than a cascade of client failures (P6 - the client
# dies of a HotSpot SIGBUS with no fix).
render_bay() {
  say "  7. render — through the frame and from the far side"
  # rig-ready.sh, not a /health probe: the bridge answers /health from the
  # Connection Lost screen, and any mc restart drops the client. It recovers
  # one when it can and NAMES the reason when it cannot.
  if ! "$HERE/rig-ready.sh" --non-interactive > "$RUN_DIR/dim/$ID.rig.log" 2>&1; then
    _record fail "$ID: the client was there for the render step" \
      "a client in the world, per rig-ready.sh" \
      "$(tail -2 "$RUN_DIR/dim/$ID.rig.log" | tr '\n' ' ')"
    RENDER_OK=0
    verdict S_RENDER fail
    return
  fi
  CLI_BEFORE="$RUN_DIR/dim/$ID.client-before.log"
  dev client-log --tail 3000 > "$CLI_BEFORE" 2>/dev/null || true
  CLI_LINES="$(wc -l < "$CLI_BEFORE" | tr -d ' ')"

  # Three viewpoints on the source side, then one from inside the destination.
  # The tp sets the aim: the crosshair is fixed at screen centre and the game
  # reads look direction, so a position plus a yaw IS the shot.
  read -r VX VY VZ <<EOF
$VIEW
EOF
  read -r VYAW VPITCH <<EOF
$LOOK
EOF
  for offset in 0 -3 3; do
    rcon "execute in minecraft:overworld run tp $PLAYER $(awk "BEGIN{print $VX + $offset}") $VY $VZ $VYAW $VPITCH" >/dev/null
    sleep 3
    assert_shot "$ID: the frame from the lane, offset $offset" "$ID-lane$offset"
  done
  rcon "execute in minecraft:overworld run tp $PLAYER $(awk "BEGIN{print $VX}") $VY $(awk "BEGIN{print $VZ - 3}") $VYAW $VPITCH" >/dev/null
  sleep 4
  assert_shot "$ID: close to the opening" "$ID-close"

  CLI_AFTER="$RUN_DIR/dim/$ID.client-after.log"
  dev client-log --tail 8000 > "$CLI_AFTER" 2>/dev/null || true
  tail -n "+$((CLI_LINES + 1))" "$CLI_AFTER" > "$RUN_DIR/dim/$ID.client-new.log" 2>/dev/null \
    || cp "$CLI_AFTER" "$RUN_DIR/dim/$ID.client-new.log"
  grep -a "companion-client:emit" "$RUN_DIR/dim/$ID.client-new.log" \
    > "$RUN_DIR/dim/$ID.emit.log" 2>/dev/null || true
  EMITS="$(wc -l < "$RUN_DIR/dim/$ID.emit.log" | tr -d ' ')"
  EMIT_LAST="$(tail -1 "$RUN_DIR/dim/$ID.emit.log" 2>/dev/null | sed 's/.*companion-client:emit/emit/')"
  report_metric "$ID: emit lines during the render step" "$EMITS" "${EMIT_LAST:-none}"
  printf 'emit lines: %s\n%s\n' "$EMITS" "$EMIT_LAST" >> "$BAY_LOG"

  # Cross and photograph the same place, so the pair can be compared by eye.
  rcon "execute in minecraft:overworld run tp $PLAYER $ENTER $LOOK" >/dev/null
  if wait_for_dimension "$DIMENSION" "$POLL_SECONDS"; then
    sleep 3
    assert_shot "$ID: the destination itself, for comparison" "$ID-destination"
    wait_for_dimension minecraft:overworld "$POLL_SECONDS" || true
    verdict S_RENDER ok
  else
    _record fail "$ID: crossed to photograph the destination" "$DIMENSION" "$DIM_NOTE"
    verdict S_RENDER fail
  fi
  RENDER_DONE=$((RENDER_DONE + 1))
}

BAY_INDEX=0
while [ "$BAY_INDEX" -lt "$TOTAL" ]; do
  run_bay
  BAY_INDEX=$((BAY_INDEX + 1))
done

# ---------------------------------------------------------------------------
say "The measurement was not invalidated mid-run"
NOW_STARTED="$(cd "$CONSUMER_DIR" && docker inspect mc --format '{{.State.StartedAt}}')"
if [ "$NOW_STARTED" = "$STARTED_AT" ]; then
  _record ok "mc did not restart during the run" "$STARTED_AT" "$NOW_STARTED"
else
  _record fail "mc did not restart during the run" "$STARTED_AT" "$NOW_STARTED"
fi

say "Per-dimension result"
column -t -s "$(printf '\t')" < "$SUMMARY" 2>/dev/null || cat "$SUMMARY"
printf '\nper-dimension logs: %s/dim/\nmachine-readable:   %s and %s\n' \
  "$RUN_DIR" "$RUN_DIR/assertions.tsv" "$SUMMARY"

finish
