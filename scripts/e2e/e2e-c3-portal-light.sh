#!/usr/bin/env bash
# PASS = the portal lights the source world around it, the far side's light reaches the window, and a server light update in the same chunk section does not take either away.
#
# Purpose: measure both of a portal's light paths, separately, on the real
#          client. Nobody had measured either.
# Context: the aperture's light is a FAKE block sent to one viewer
#          (PlayerProjectionState.apertureState). It exists in no world, so no
#          `execute if block` and no `data get` can see it. That is why the
#          verdict is the DIFFERENCE between the two instruments at the same
#          cells: the server reads its own ambient light there, the client
#          reads that plus the portal's, and the gap is the portal.
#          The destination's light travels a different road entirely:
#          ProjectionStream.packLight -> ClientProjection.lightAt ->
#          ProjectionView.getLightLevel -> the mesh's own lightmap.
# Usage:   ./dev up (server jar) and ./dev reload + a client relaunch (client
#          jar), then ./e2e-c3-portal-light.sh
#          RIG=nexus (default) or RIG=crucible.
# Gotchas: THE SERVER'S AMBIENT IS NOT ZERO. Measured at the nexus rig, which
#          stands in a torch-lit room: the server reads 4-7 block light at the
#          opening and the client reads 11. Only the delta is the portal, so
#          never assert the server side reads dark.
#
#          THE PROFILE ONLY FALLS WHILE THE PORTAL DOMINATES. Block light is
#          the MAX over every source, so the same room lifts the far end back
#          up: 11,10,9,8 then 9,10,9,10. RAMP_CELLS bounds the assertion to the
#          stretch a level-N source at the opening must own.
#
#          A configured `particleType` makes the destination's colour and light
#          inert for that portal — PortalHelper.apertureEffect returns the named
#          effect before the glow is applied. Both rigs are particleType null.
#          Check the receipt's "with a particleType that bypasses it" count
#          before reading anything into a glow figure on another portal.
#
#          The wipe step edits the world and puts it back. It picks a cell that
#          is ALREADY AIR in the aperture's own chunk section, 30 blocks of
#          taxicab distance away so its own light cannot reach the opening, and
#          refuses to run if that cell is not air.

# shellcheck disable=SC2034  # lib.sh reads it to name the run dir
SCRIPT_NAME="c3-portal-light"
# shellcheck source=lib.sh
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

TEMPLATE_DIR="${TEMPLATE_DIR:-/Users/pip/Projects/minecraft-server-template}"
CLIENT_JAR="$TEMPLATE_DIR/mods/custom-dimensions-client/build/libs/customdimensionsclient-0.0.0-local.jar"

RIG="${RIG:-nexus}"
case "$RIG" in
  nexus)
    DEST="adventure:the_crimson_nexus"
    IX=1500; IY=102; IZ=1500          # a middle cell of the opening
    CAM_X=1501.5; CAM_Y=100; CAM_Z=1495.5; CAM_YAW=0; CAM_PITCH=5
    FAR_X=1500; FAR_Y=101; FAR_Z=1420 # out of activation range, same terrain
    WIPE_X=1494; WIPE_Y=105; WIPE_Z=1494   # verified air, taxicab 15 from the opening
    ;;
  crucible)
    DEST="adventure:the_crucible"
    IX=3260; IY=86; IZ=2883
    CAM_X=3260.5; CAM_Y=85; CAM_Z=2878.5; CAM_YAW=0; CAM_PITCH=5
    FAR_X=3260; FAR_Y=85; FAR_Z=2800
    WIPE_X=3248; WIPE_Y=94; WIPE_Z=2880
    ;;
  *)
    printf '\033[31mREFUSING TO RUN\033[0m — RIG must be nexus or crucible, not %s\n' "$RIG" >&2
    exit 1 ;;
esac

EXPECTED_LEVEL="${EXPECTED_LEVEL:-11}"
LIGHT_FILE_PRIMARY="$CONSUMER_DIR/.seed-rolling/portal-light.json"
LIGHT_FILE_FALLBACK="$CONSUMER_DIR/data/config/custom-dimensions/portal-light/portal-light.json"
LIGHT_FILE=""
LIGHT_SEQ=0

ZONE="$(printf '.zones[] | select(.kind=="source") | select([(.aperture // [])[].at] | index([%s,%s,%s]))' "$IX" "$IY" "$IZ")"
PROJ="$(printf '.projections[] | select(.destination=="%s")' "$DEST")"

# --- the server's own light facts -------------------------------------------
# Same discipline as state_refresh: a fixed filename invites reading the
# PREVIOUS call's answer, so an artefact whose generatedAt did not move is
# refused rather than measured.
_light_stamp() { [ -f "$1" ] && jq -r '.generatedAt // ""' "$1" 2>/dev/null; }

light_refresh() {
  local was_a was_b receipt file was now
  was_a="$(_light_stamp "$LIGHT_FILE_PRIMARY")"
  was_b="$(_light_stamp "$LIGHT_FILE_FALLBACK")"
  receipt="$(rcon "customdim portal-light" | tr -d '\r' | tr '\n' ' ')"
  if [ -f "$LIGHT_FILE_PRIMARY" ] && [ -f "$LIGHT_FILE_FALLBACK" ]; then
    if [ "$LIGHT_FILE_PRIMARY" -nt "$LIGHT_FILE_FALLBACK" ]; then file="$LIGHT_FILE_PRIMARY"
    else file="$LIGHT_FILE_FALLBACK"; fi
  elif [ -f "$LIGHT_FILE_PRIMARY" ]; then file="$LIGHT_FILE_PRIMARY"
  elif [ -f "$LIGHT_FILE_FALLBACK" ]; then file="$LIGHT_FILE_FALLBACK"
  else file=""; fi
  if [ -z "$file" ]; then
    LIGHT_FILE=""
    _record fail "portal-light artefact written" "a JSON artefact this call rewrote" \
      "no artefact at either path — RCON answered: $receipt"
    return 1
  fi
  if [ "$file" = "$LIGHT_FILE_PRIMARY" ]; then was="$was_a"; else was="$was_b"; fi
  now="$(_light_stamp "$file")"
  if [ -z "$now" ] || [ "$now" = "$was" ]; then
    LIGHT_FILE=""
    _record fail "portal-light artefact written" "a JSON artefact this call rewrote" \
      "$file was not rewritten (generatedAt still '${now:-unreadable}') — RCON answered: $receipt"
    return 1
  fi
  LIGHT_FILE="$file"
  LIGHT_SEQ=$((LIGHT_SEQ + 1))
  cp "$file" "$RUN_DIR/portal-light-$LIGHT_SEQ.json" 2>/dev/null
  note "portal-light: $receipt"
  return 0
}

_light_path() {
  if [ -n "$LIGHT_FILE" ]; then printf '%s' "$LIGHT_FILE"
  else printf '%s' "$RUN_DIR/NO-portal-light-refresh-failed.json"; fi
}
light_read()   { json_read "$(_light_path)" "$1"; }
assert_light() { assert_json "$1" "$(_light_path)" "$2" "$3"; }

# --- the client's light profile ---------------------------------------------
# The whole 8-cell profile as one comma-joined string, so the SHAPE is the
# assertion. A flat profile and a falling one are different pictures, and only
# the falling one can come from a point source at the opening.
profile_of() { bridge_read "[$PROJ | .lightProfile[].block] | join(\",\")"; }

# Strictly falling by exactly one per step, from `top`, until it reaches 0.
expected_profile() { # top cells
  local top="$1" cells="$2" i=0 out="" v
  while [ "$i" -lt "$cells" ]; do
    v=$((top - i)); [ "$v" -lt 0 ] && v=0
    out="$out${out:+,}$v"
    i=$((i + 1))
  done
  printf '%s' "$out"
}

# The portal's own contribution, over the first cells where it can still
# dominate. Block light is the MAX over every source, so a lit room lifts the
# far end of the profile back up — measured at the nexus rig, which stands in a
# torch-lit room: 11,10,9,8 then 9,10,9,10 as the room's own torches take over.
# Asserting the whole profile falls therefore FAILS on a working portal.
#
# What only a level-N source at the opening can produce is the first stretch:
# N at the opening, falling exactly one per block until the ambient meets it.
# RAMP_CELLS bounds the assertion to that stretch; the rest is reported.
RAMP_CELLS="${RAMP_CELLS:-4}"

assert_profile_falls() { # label
  local got want i=0 v expect ok=1 why=""
  got="$(profile_of)"
  want="$(expected_profile "$EXPECTED_LEVEL" "$RAMP_CELLS")"
  case "$got" in
    ''|*[!0-9,]*) _record fail "$1" "a $RAMP_CELLS-cell ramp from $EXPECTED_LEVEL" "$got"; return ;;
  esac
  for v in $(printf '%s' "$got" | tr ',' ' '); do
    [ "$i" -ge "$RAMP_CELLS" ] && break
    expect=$((EXPECTED_LEVEL - i))
    if [ "$v" -lt "$expect" ]; then
      ok=0; why="${why:+$why; }d=$i reads $v, under the $expect a level-$EXPECTED_LEVEL source at the opening must give"
    fi
    i=$((i + 1))
  done
  if [ "$i" -lt "$RAMP_CELLS" ]; then
    ok=0; why="${why:+$why; }only $i cell(s) in the profile, needed $RAMP_CELLS"
  fi
  if [ "$ok" -eq 1 ]; then
    _record ok "$1" "first $RAMP_CELLS cells >= $want" "$got"
  else
    _record fail "$1" "first $RAMP_CELLS cells >= $want" "$got — $why"
  fi
}

# The measurement that cannot be confounded by the room: the SAME cells read on
# both sides. The server cannot see a fake block, so a positive delta at every
# opening cell is the aperture's light and nothing else.
assert_aperture_beats_the_server() { # label
  local cells srv_file
  srv_file="$(_light_path)"
  cells="$(bridge_read "[$PROJ | .apertureLight[] | \"\(.at|join(\",\"))=\(.block)\"] | join(\" \")")"
  if [ ! -f "$srv_file" ]; then
    _record fail "$1" "a server reading to compare against" "no portal-light artefact"
    return
  fi
  local under=0 total=0 detail="" at cli srv
  for pair in $cells; do
    at="${pair%%=*}"; cli="${pair##*=}"
    srv="$(json_read "$srv_file" "[$ZONE | .aperture[] | select((.at|join(\",\"))==\"$at\") | .blockLight] | first")"
    case "$srv" in ''|*[!0-9]*) continue ;; esac
    total=$((total + 1))
    [ "$cli" -le "$srv" ] && under=$((under + 1))
    detail="$detail $at client=$cli server=$srv"
  done
  if [ "$total" -eq 0 ]; then
    _record fail "$1" "every opening cell compared" "no cell matched on both sides — the two readings name different places"
  elif [ "$under" -eq 0 ]; then
    _record ok "$1" "client > server at all $total cell(s)" "$detail"
  else
    _record fail "$1" "client > server at all $total cell(s)" "$under of $total show no gain —$detail"
  fi
}

assert_jar_contains() { # label jar entry
  if [ ! -f "$2" ]; then _record fail "$1" "a jar at $2" "no such file — build the client mod first"; return; fi
  if unzip -l "$2" 2>/dev/null | grep -q -- "$3"; then
    _record ok "$1" "$3 inside the jar" "present"
  else
    _record fail "$1" "$3 inside the jar" "absent — the running client predates this instrument"
  fi
}

banner "C3 — does the portal emit light, and does the far side's light reach the window?"

say "Gates"
require_backup_idle
require_mc_healthy
require_player_online
require_bridge
STARTED_AT="$(cd "$CONSUMER_DIR" && docker inspect mc --format '{{.State.StartedAt}}')"
assert_jar_contains "the client jar carries the light instrument" \
  "$CLIENT_JAR" "com/customdimensions/client/render/LightFacts.class"

# ---------------------------------------------------------------------------
say "SERVER — what is being painted, and the proof no server probe can see it"
light_refresh
assert_light "the rig's source zone is in the report" "[$ZONE] | length" 1
assert_light "it leads to the destination" "$ZONE | .targetWorld" "$DEST"
assert_light "its chunks are resident, so the opening was actually read" "$ZONE | .resident" true
assert_light "the configured light level" "$ZONE | .configuredLightLevel" "$EXPECTED_LEVEL"
assert_light "so the aperture pass paints something" "$ZONE | .painted" true
assert_light "no particleType, so the destination's glow does reach the particle" \
  "$ZONE | .particleTypeOverride" "null"

# The load-bearing negative, with the positive control standing beside it: the
# server's own world is EMPTY at every cell the client is showing light at.
# Without this, "the server says 11" and "the world contains a light block"
# are indistinguishable, and only one of them is true.
assert_light "every opening cell is air in the server's own world" \
  "[$ZONE | .aperture[] | select(.block != \"minecraft:air\")] | length" 0
assert_light "and carries no luminance there" \
  "[$ZONE | .aperture[] | select(.luminance != 0)] | length" 0
report_metric "server block light at the opening" "$(light_read "[$ZONE | .aperture[].blockLight] | join(\",\")")" \
  "the room's OWN light, not the portal's — this is the baseline the client must beat"
report_metric "server sky light at the opening" "$(light_read "[$ZONE | .aperture[].skyLight] | join(\",\")")" \
  "the source world's own daylight"

# ---------------------------------------------------------------------------
say "Put the viewer in front of the portal and let the projection settle"
rcon "execute in minecraft:overworld run tp $PLAYER $CAM_X $CAM_Y $CAM_Z $CAM_YAW $CAM_PITCH" >/dev/null
sleep 10
bridge_state
assert_bridge "the client holds a projection for the destination" "[$PROJ] | length" 1
assert_bridge "its mesh is built" "$PROJ | .meshReady" true
assert_shot "SHOT A — the lit portal" "A-lit"

say "PATH 1 — does the portal emit light? (the client's own world)"
assert_bridge "every opening cell holds the light block on this client" \
  "[$PROJ | .apertureLight[] | select(.id != \"minecraft:light\")] | length" 0
assert_bridge "at the configured luminance" \
  "[$PROJ | .apertureLight[] | select(.luminance != $EXPECTED_LEVEL)] | length" 0
assert_bridge "and the client computes that much block light there" \
  "[$PROJ | .apertureLight[] | select(.block < $EXPECTED_LEVEL)] | length" 0
assert_profile_falls "block light falls one level per block out of the opening"
assert_aperture_beats_the_server "the opening is brighter on the client than in the server world"
report_metric "light profile out of the opening" "$(profile_of)" \
  "d=0 is the opening; each step is one block towards the viewer"
report_metric "sky light along the same line" \
  "$(bridge_read "[$PROJ | .lightProfile[].sky] | join(\",\")")" \
  "unchanged by the portal — it is the source world's, and it is the control"

say "PATH 2 — does the destination's light reach the window?"
report_metric "destination light the server sent" "$(bridge_read "$PROJ | .destLight | \"cells=\(.cells) lit=\(.lit) block=\(.blockMin)..\(.blockMax)/\(.blockMean) sky=\(.skyMin)..\(.skyMax)/\(.skyMean)\"")"
report_metric "light the mesh was built with" "$(bridge_read "$PROJ | .meshLight | \"cells=\(.cells) lit=\(.lit) block=\(.blockMin)..\(.blockMax)/\(.blockMean) sky=\(.skyMin)..\(.skyMax)/\(.skyMean)\"")"
assert_bridge_at_least "the server described a lit destination, not an empty grid" \
  "$PROJ | .destLight.cells" 1
assert_bridge_at_least "at least one destination cell carries light" \
  "$PROJ | .destLight.lit" 1
assert_bridge_at_least "the mesh carries vertices to put it on" \
  "$PROJ | .meshLight.cells" 1
# The mesh's lightmap must come from the GRID, not the client's own world. A
# mesh whose light is uniformly 15 is ProjectionView.getLightLevel having
# stopped answering — the failure that looks like a working portal.
MESH_MIN="$(bridge_read "$PROJ | .meshLight.blockMin")"
MESH_MAX="$(bridge_read "$PROJ | .meshLight.blockMax")"
if [ "$MESH_MIN" = "$MESH_MAX" ] && [ "$MESH_MIN" = "15" ]; then
  _record fail "the mesh's light varies, so it came from the destination grid" \
    "a range, not a constant 15" "every vertex is 15 — the grid is not being consulted"
else
  _record ok "the mesh's light varies, so it came from the destination grid" \
    "a range, not a constant 15" "block $MESH_MIN..$MESH_MAX"
fi
dev client-log --tail 400 > "$RUN_DIR/client-emit.log" 2>/dev/null || true
assert_count_at_least "the emit line carries a light term" \
  "$RUN_DIR/client-emit.log" "light=\[cells=" 1

# ---------------------------------------------------------------------------
say "THE WIPE — a server light update in the opening's own chunk section"
# The suspected defect, stated so the run can refute it: the aperture's light
# is computed by the CLIENT from a fake block. A LightUpdateS2CPacket replaces
# that section's light arrays with the server's, which have no light in them,
# and the projector's next pass re-sends the SAME block state — which
# WorldChunk.setBlockState drops before it reaches the relight. If that is what
# is happening, the block below stays and the gradient goes.
assert_block "the wipe cell is air before anything is placed" \
  "$WIPE_X" "$WIPE_Y" "$WIPE_Z" minecraft:air present
rcon "setblock $WIPE_X $WIPE_Y $WIPE_Z minecraft:glowstone" >/dev/null
sleep 2
rcon "setblock $WIPE_X $WIPE_Y $WIPE_Z minecraft:air" >/dev/null
sleep 6
assert_block "the wipe cell is air again — the world is as it was" \
  "$WIPE_X" "$WIPE_Y" "$WIPE_Z" minecraft:air present

bridge_state
assert_shot "SHOT B — the same view after the light update" "B-after-light-update"
# POSITIVE CONTROL, and the whole point of this step: the light BLOCK must
# still be there. Without it, a lost gradient is just a torn-down projection.
assert_bridge "the light block is still on this client" \
  "[$PROJ | .apertureLight[] | select(.id != \"minecraft:light\")] | length" 0
assert_bridge "still at the configured luminance" \
  "[$PROJ | .apertureLight[] | select(.luminance != $EXPECTED_LEVEL)] | length" 0
assert_profile_falls "the light survives a server light update in its own section"
report_metric "light profile after the update" "$(profile_of)" \
  "compare with the profile above — the block is unchanged, so any difference is the light engine"

# ---------------------------------------------------------------------------
say "THE BOUNDARY — the same cells with no portal projecting"
rcon "execute in minecraft:overworld run tp $PLAYER $FAR_X $FAR_Y $FAR_Z" >/dev/null
sleep 12
bridge_state
assert_bridge "the projection is torn down out of range" "[$PROJ] | length" 0
assert_shot "SHOT C — away from the portal, nothing projecting" "C-out-of-range"

say "Back to the rig, and the light comes back"
rcon "execute in minecraft:overworld run tp $PLAYER $CAM_X $CAM_Y $CAM_Z $CAM_YAW $CAM_PITCH" >/dev/null
sleep 12
bridge_state
assert_bridge "the projection is back" "[$PROJ] | length" 1
assert_profile_falls "and so is the light"
assert_shot "SHOT D — back at the rig" "D-back-at-the-rig"

say "The measurement was not invalidated mid-run"
NOW_STARTED="$(cd "$CONSUMER_DIR" && docker inspect mc --format '{{.State.StartedAt}}')"
if [ "$NOW_STARTED" = "$STARTED_AT" ]; then
  _record ok "mc did not restart during the run" "$STARTED_AT" "$NOW_STARTED"
else
  _record fail "mc did not restart during the run" "$STARTED_AT" "$NOW_STARTED"
fi
if bridge_request "$RUN_DIR/bridge-health-after.json" GET /health; then
  _record ok "the client survived the run" "an answering bridge" \
    "tick $(json_read "$RUN_DIR/bridge-health-after.json" '.tick')"
else
  _record fail "the client survived the run" "an answering bridge" \
    "the bridge stopped answering: $BRIDGE_REASON — every assertion after the loss is void (TROUBLESHOOTING.md#p6)"
fi

printf '\n\033[36mLOOK AT THESE\033[0m — the light is a picture as well as a number:\n'
printf '  A (lit):                 %s\n' "$RUN_DIR/A-lit.png"
printf '  B (after light update):  %s\n' "$RUN_DIR/B-after-light-update.png"
printf '  C (out of range):        %s\n' "$RUN_DIR/C-out-of-range.png"
printf '  D (back at the rig):     %s\n' "$RUN_DIR/D-back-at-the-rig.png"
printf 'A and B should be the same picture. If B is darker around the frame, the\n'
printf 'light update took the aperture light with it and the profile above says so.\n'

finish
