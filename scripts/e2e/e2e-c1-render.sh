#!/usr/bin/env bash
# PASS = the client meshes each destination on the customdimensions-mesh thread, a handful of times across a static view, and survives the draw.
#
# Purpose: prove the C1 render draws the real destination from the CLIENT mod,
#          without hanging the render thread and without killing the JVM.
# Context: the workload fix has three parts and each has an assertion here —
#          the mesh is built OFF the render thread (thread= on the marker), an
#          unchanged resend does NOT rebuild it (the marker count stays low),
#          and the draw path is no longer gated (it draws at all).
# Usage:   ./e2e-c1-render.sh          (run item 5 first — it builds and lights
#                                       the frame this views through)
# Gotchas: RCON CANNOT discriminate. The server-side projection sends fake
#          block packets to one client; the server's own world is unchanged, so
#          an `execute if block` probe reports the real world either way. Do
#          not add a block assertion here thinking it proves anything.
#
#          Every marker below is a FORMAT STRING in the source. Grepping the
#          source for the rendered text finds nothing — the source reads
#          "companion-client:projection-mesh thread={} cells={} ...". Grep the
#          LOG for rendered text.
#
#          The mesh-count ceiling is the whole point of the test. The old code
#          built the mesh inside the draw call, so a ten-second static view
#          produced one build PER FRAME. Anything in the hundreds is the
#          regression, not a slow machine.

# shellcheck disable=SC2034  # lib.sh reads it to name the run dir
SCRIPT_NAME="c1-render"
# shellcheck source=lib.sh
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

TEMPLATE_DIR="${TEMPLATE_DIR:-/Users/pip/Projects/minecraft-server-template}"
CLIENT_JAR="$TEMPLATE_DIR/mods/custom-dimensions-client/build/libs/customdimensionsclient-0.0.0-local.jar"

IZ=297                                   # frame plane
VIEW_X=229.0; VIEW_Y=130; VIEW_Z=303     # 6 blocks south of the frame, facing it
VIEW_YAW=180; VIEW_PITCH=0               # yaw 180 = north = straight at the frame
VIEW_SECONDS=10                          # the static view the mesh count is measured over
MESH_BUILD_CEILING=8                     # one per portal per content change, with slack

MESH_MARKER="companion-client:projection-mesh"
BUILD_THREAD="customdimensions-mesh"

# --- assertions this script needs and lib.sh does not have ------------------
assert_file_lacks() { # label file pattern
  local got
  if [ ! -f "$2" ]; then _record fail "$1" "a readable file at $2" "no such file"; return; fi
  got="$(grep -c -- "$3" "$2" 2>/dev/null || true)"; [ -z "$got" ] && got=0
  if [ "$got" -eq 0 ]; then
    _record ok   "$1" "0 lines matching '$3'" "0"
  else
    _record fail "$1" "0 lines matching '$3'" "$got — $(grep -m1 -- "$3" "$2")"
  fi
}

assert_count_at_most() { # label file pattern n
  local got
  if [ ! -f "$2" ]; then _record fail "$1" "a readable file at $2" "no such file"; return; fi
  got="$(grep -c -- "$3" "$2" 2>/dev/null || true)"; [ -z "$got" ] && got=0
  if [ "$got" -le "$4" ]; then
    _record ok   "$1" "<= $4 lines matching '$3'" "$got"
  else
    _record fail "$1" "<= $4 lines matching '$3'" "$got"
  fi
}

assert_jar_contains() { # label jar entry
  if [ ! -f "$2" ]; then _record fail "$1" "a jar at $2" "no such file — build the client mod first"; return; fi
  if unzip -l "$2" 2>/dev/null | grep -q -- "$3"; then
    _record ok   "$1" "$3 inside the jar" "present"
  else
    _record fail "$1" "$3 inside the jar" "absent"
  fi
}

banner "C1 — is the portal showing the real destination, from the client mod?"

say "Gates"
require_backup_idle
require_mc_healthy
require_player_online
STARTED_AT="$(cd "$CONSUMER_DIR" && docker inspect mc --format '{{.State.StartedAt}}')"
CLIENT_PID_BEFORE="$(client_pid || echo none)"
note "client pid at start: $CLIENT_PID_BEFORE"

say "The artefact under test carries the fix"
assert_jar_contains "the off-thread mesh builder is in the jar" \
  "$CLIENT_JAR" "com/customdimensions/client/render/MeshBuilder.class"
assert_jar_contains "the jar is remapped (refmap present, not a dev jar)" \
  "$CLIENT_JAR" "customdimensionsclient-refmap.json"

say "Precondition — the crucible frame is lit and registered"
assert_file_contains "a crucible zone is registered" \
  "$CONSUMER_DIR/data/config/portal_links.json" "the_crucible"
assert_block "frame ring is intact (west middle)" 227 131 "$IZ" minecraft:copper_block present
assert_block "source interior is empty (source portals have no blocks)" \
  228 131 "$IZ" minecraft:air present

say "Baseline both logs before the viewer approaches"
SRV_BEFORE="$RUN_DIR/mc-before.log"
CLI_BEFORE="$RUN_DIR/client-before.log"
(cd "$CONSUMER_DIR" && docker logs mc --tail 4000 > "$SRV_BEFORE" 2>&1) || true
dev client-log --tail 4000 > "$CLI_BEFORE" 2>/dev/null || true
SRV_BEFORE_LINES="$(wc -l < "$SRV_BEFORE" | tr -d ' ')"
CLI_BEFORE_LINES="$(wc -l < "$CLI_BEFORE" | tr -d ' ')"
note "server baseline $SRV_BEFORE_LINES lines, client baseline $CLI_BEFORE_LINES lines"

say "Put the viewer in front of the portal and hold still for ${VIEW_SECONDS}s"
# The aim is the tp. The pointer only picks the window; the crosshair is fixed
# at screen centre and the game reads look direction, not cursor position.
rcon "execute in minecraft:overworld run tp $PLAYER $VIEW_X $VIEW_Y $VIEW_Z $VIEW_YAW $VIEW_PITCH" >/dev/null
dev input focus >/dev/null 2>&1
sleep "$VIEW_SECONDS"
assert_shot "SHOT A — the portal as the player sees it" "A-through-the-portal"

say "Collect the new log lines"
SRV_NEW="$RUN_DIR/mc-new.log"
CLI_NEW="$RUN_DIR/client-new.log"
(cd "$CONSUMER_DIR" && docker logs mc --tail 8000 > "$RUN_DIR/mc-after.log" 2>&1) || true
dev client-log --tail 8000 > "$RUN_DIR/client-after.log" 2>/dev/null || true
tail -n "+$((SRV_BEFORE_LINES + 1))" "$RUN_DIR/mc-after.log" > "$SRV_NEW" 2>/dev/null || cp "$RUN_DIR/mc-after.log" "$SRV_NEW"
tail -n "+$((CLI_BEFORE_LINES + 1))" "$RUN_DIR/client-after.log" > "$CLI_NEW" 2>/dev/null || cp "$RUN_DIR/client-after.log" "$CLI_NEW"
grep -h "companion" "$CLI_NEW" > "$RUN_DIR/client-render-lines.txt" 2>/dev/null || true
note "client companion lines this view: $RUN_DIR/client-render-lines.txt"

# ---------------------------------------------------------------------------
say "Is the CLIENT mod talking?"
# Zero client markers plus a rendered-looking screenshot means the server is
# still doing it and the picture proves nothing.
assert_count_at_least "client companion initialised" \
  "$RUN_DIR/client-after.log" "companion-client:initialised" 1
assert_count_at_least "client sent its hello" \
  "$RUN_DIR/client-after.log" "companion-client:hello-sent" 1
assert_count_at_least "server accepted the handshake" \
  "$RUN_DIR/mc-after.log" "companion-accept:handshake" 1
assert_count_at_least "client received and decoded a projection" \
  "$RUN_DIR/client-after.log" "companion-client:projection " 1

# ---------------------------------------------------------------------------
say "Did the client BUILD the destination, and where?"
# Q3/Q4 from the old draft, answered: the marker is companion-client:projection-mesh
# and it fires once per completed build — one per portal per content change,
# never per frame. That is what makes a count assertable.
assert_count_at_least "client meshed the destination at least once" \
  "$RUN_DIR/client-after.log" "$MESH_MARKER" 1
assert_count_at_least "the mesh was built on the builder thread" \
  "$RUN_DIR/client-after.log" "$MESH_MARKER thread=$BUILD_THREAD" 1
grep -h "$MESH_MARKER" "$RUN_DIR/client-after.log" > "$RUN_DIR/mesh-builds.txt" 2>/dev/null || true
note "every mesh build this session: $RUN_DIR/mesh-builds.txt"

say "NEGATIVE CONTROLS — the three ways this regresses"
# 1. The build back on the render thread is the hang. No mesh line may name it.
assert_file_lacks "no mesh was built on the render thread" \
  "$RUN_DIR/client-after.log" "$MESH_MARKER thread=Render thread"
# 2. Rebuilding per frame is the workload defect. A ten-second static view on
#    the old code produced one build per frame; a fixed client produces one per
#    portal per content change, and the server only resends on a change.
assert_count_at_most "the mesh was not rebuilt per frame during the static view" \
  "$CLI_NEW" "$MESH_MARKER" "$MESH_BUILD_CEILING"
# 3. The 3-second diagnostic was removed with the gate. Its return means an
#    older jar is installed than the one asserted above.
assert_file_lacks "the render-tick diagnostic is gone" \
  "$RUN_DIR/client-after.log" "companion-client:render-tick"

say "Did the client survive the draw?"
CLIENT_PID_AFTER="$(client_pid || echo none)"
if [ "$CLIENT_PID_AFTER" = "$CLIENT_PID_BEFORE" ] && [ "$CLIENT_PID_AFTER" != "none" ]; then
  _record ok "the client did not crash or restart" "$CLIENT_PID_BEFORE" "$CLIENT_PID_AFTER"
else
  _record fail "the client did not crash or restart" "$CLIENT_PID_BEFORE" "$CLIENT_PID_AFTER"
fi
assert_file_lacks "no fatal JVM error in the client log" \
  "$RUN_DIR/client-after.log" "SIGSEGV"
assert_file_lacks "no mesh build threw" \
  "$RUN_DIR/client-after.log" "$MESH_MARKER failed"

# ---------------------------------------------------------------------------
say "Is the SERVER still painting fake blocks?"
# Reported, not asserted: the server-side projection is a separate subsystem,
# and a threshold nobody can justify is worse than a number printed plainly.
PROJ_N="$(grep -c "projection activated" "$SRV_NEW" 2>/dev/null || true)"; [ -z "$PROJ_N" ] && PROJ_N=0
MASK_N="$(grep -c "sightline mask" "$SRV_NEW" 2>/dev/null || true)"; [ -z "$MASK_N" ] && MASK_N=0
report_metric "server: 'projection activated' lines during the view" "$PROJ_N" \
  "server-painted preview activations for this approach"
report_metric "server: 'sightline mask' lines during the view" "$MASK_N" \
  "each line is the server deciding which fake blocks a viewer may see"
grep -h "sightline mask\|projection activated" "$SRV_NEW" > "$RUN_DIR/server-render-lines.txt" 2>/dev/null || true
note "server render lines: $RUN_DIR/server-render-lines.txt"

# ---------------------------------------------------------------------------
say "Is it the RIGHT dimension? — the honest visual pair"
# Shot A is what the player saw THROUGH the frame. Shot B is what is actually
# there, taken from inside the destination on the same heading. If the render
# is real, a human comparing them should see the same place.
note "crossing to photograph the destination on the same heading"
rcon "tp $PLAYER 229.0 130 297.5 $VIEW_YAW $VIEW_PITCH" >/dev/null
sleep 6
DEST="$(rcon "execute as $PLAYER run data get entity @s Dimension" | tr -d '\r\n')"
case "$DEST" in
  *"the_crucible"*)
    _record ok "the viewer crossed into the destination" "adventure:the_crucible" "$DEST"
    sleep 2
    assert_shot "SHOT B — the destination, same heading" "B-inside-the-destination"
    ;;
  *)
    _record fail "the viewer crossed into the destination" "adventure:the_crucible" "$DEST"
    note "SHOT B skipped — without the crossing there is nothing to compare SHOT A against"
    ;;
esac

say "Return the viewer"
rcon "execute in minecraft:overworld run tp $PLAYER $VIEW_X $VIEW_Y $VIEW_Z $VIEW_YAW $VIEW_PITCH" >/dev/null
sleep 2
assert_shot "SHOT C — back at the portal, for a second look" "C-back-at-the-portal"

say "The measurement was not invalidated mid-run"
NOW_STARTED="$(cd "$CONSUMER_DIR" && docker inspect mc --format '{{.State.StartedAt}}')"
if [ "$NOW_STARTED" = "$STARTED_AT" ]; then
  _record ok "mc did not restart during the run" "$STARTED_AT" "$NOW_STARTED"
else
  _record fail "mc did not restart during the run" "$STARTED_AT" "$NOW_STARTED"
fi

printf '\n\033[36mLOOK AT THESE\033[0m — the visual test is a human comparison:\n'
printf '  A (through the frame):  %s\n' "$RUN_DIR/A-through-the-portal.png"
printf '  B (inside the dest):    %s\n' "$RUN_DIR/B-inside-the-destination.png"
printf '  C (back at the frame):  %s\n' "$RUN_DIR/C-back-at-the-portal.png"
printf 'A and B should show the same place. If A shows flat surfaces, distant\n'
printf 'bedrock, or terrain B does not have, the render is not the destination.\n'

finish
