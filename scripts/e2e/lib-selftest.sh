#!/usr/bin/env bash
# Self-test for lib.sh's readers, assertions and bridge client.
#
# Purpose: prove each helper FAILS for the reason it was written to catch, not
#          only that it passes on good input. A green assertion nobody has seen
#          go red is a tautology.
# Context: no Minecraft, no docker, no RCON. Server facts come from fixture
#          artefacts written here; the client bridge is a Python stub on
#          loopback that answers the shapes the real one answers, 503s
#          included. Template-only, run by hand.
# Usage:   ./lib-selftest.sh          (SELFTEST_PORT=<n> to move the stub)
# Gotchas: the counters this reports are the SELF-TEST's own. Each case runs a
#          real assertion, so lib.sh's counters fill with the assertions under
#          test and are replaced at the end — a case that expects a FAIL is a
#          self-test PASS.

# shellcheck disable=SC2034  # lib.sh reads it to name the run dir
SCRIPT_NAME="selftest"
SELFTEST_ROOT="${TMPDIR:-/tmp}/e2e-lib-selftest-$$"
mkdir -p "$SELFTEST_ROOT/consumer"

PLAYER="selftest"
CONSUMER_DIR="$SELFTEST_ROOT/consumer"
RUN_ROOT="$SELFTEST_ROOT/runs"
BRIDGE_PORT="${SELFTEST_PORT:-8791}"
BRIDGE_RETRY_SLEEP=0
export PLAYER CONSUMER_DIR RUN_ROOT BRIDGE_PORT BRIDGE_RETRY_SLEEP

# shellcheck source=lib.sh
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"

SELF_OK=0
SELF_BAD=0
STUB_PID=""

# lib.sh owns the EXIT trap. Replacing it would lose the incomplete-run
# guarantee, so this kills the stub and hands the original status straight on.
selftest_exit() {
  local rc=$?
  [ -n "$STUB_PID" ] && { kill "$STUB_PID" 2>/dev/null; wait "$STUB_PID" 2>/dev/null; }
  ( exit "$rc" )
  on_exit
}
trap selftest_exit EXIT

self_pass() { SELF_OK=$((SELF_OK + 1)); printf '  \033[32mSELF-PASS\033[0m %s\n' "$1"; }
self_fail() { SELF_BAD=$((SELF_BAD + 1)); printf '  \033[31mSELF-FAIL\033[0m %s\n' "$1"; }

# Runs one assertion and judges lib.sh's own verdict for it: the recorded
# ok/fail, and a substring the recorded "actual" must carry. The substring is
# what stops a helper passing this by failing for an unrelated reason.
expect() { # description want(ok|fail) actual-substring assertion...
  local want_verdict="$2" want_actual="$3" line got_verdict got_actual
  local description="$1"
  shift 3
  "$@"
  line="$(tail -1 "$RUN_DIR/assertions.tsv")"
  got_verdict="$(printf '%s' "$line" | cut -f2)"
  got_actual="$(printf '%s' "$line" | cut -f5)"
  if [ "$got_verdict" != "$want_verdict" ]; then
    self_fail "$description — wanted a $want_verdict, lib.sh recorded $got_verdict"
    return
  fi
  case "$got_actual" in
    *"$want_actual"*) self_pass "$description" ;;
    *) self_fail "$description — actual was '$got_actual', wanted it to mention '$want_actual'" ;;
  esac
}

# Judges a helper that returns a status and prints a value rather than
# recording an assertion.
expect_read() { # description want(0|1) output-substring command...
  local description="$1" want_rc="$2" want_out="$3" out rc
  shift 3
  out="$("$@")"
  rc=$?
  if [ "$rc" != "$want_rc" ]; then
    self_fail "$description — wanted rc=$want_rc, got rc=$rc (output: $out)"
    return
  fi
  case "$out" in
    *"$want_out"*) self_pass "$description" ;;
    *) self_fail "$description — output was '$out', wanted it to mention '$want_out'" ;;
  esac
}

banner "lib.sh self-test — every helper, red and green"

# ---------------------------------------------------------------------------
say "Fixtures"
FX="$RUN_DIR/fixtures"
mkdir -p "$FX"

cat > "$FX/state.json" <<'JSON'
{"stackVersion": "0.0.0-local", "kind": "e2e-state",
 "generatedAt": "2026-09-03T18:41:02.113471Z",
 "server": {"tick": 17167, "tps": 20, "modVersion": "0.0.0-local"},
 "players": [
  {"name": "selftest", "online": true, "uuid": "0a1b", "dimension": "minecraft:overworld",
   "pos": [230.59, 129.0, 311.45], "blockPos": [230, 129, 311], "yaw": 146.7, "pitch": 6.6,
   "onGround": true, "mainHandItem": "minecraft:diamond", "portalCooldown": 0}],
 "dimensions": [
  {"id": "adventure:the_crucible", "managed": true, "loadedAtTick": {"absent": "no per-world load tick"}},
  {"id": "minecraft:overworld", "managed": false, "loadedAtTick": {"absent": "no per-world load tick"}}],
 "zoneScope": "every world loaded right now",
 "zones": [
  {"kind": "source", "world": "minecraft:overworld", "targetWorld": "adventure:the_crucible",
   "axis": "X", "resident": true, "frameStands": true, "interior": [[228, 130, 297], [228, 131, 297]]},
  {"kind": "arrival", "world": "minecraft:overworld", "targetWorld": "adventure:the_crucible",
   "axis": "X", "resident": true, "frameStands": true, "interior": [[14, 132, 19]]}]}
JSON

cat > "$FX/state-cold.json" <<'JSON'
{"stackVersion": "0.0.0-local", "kind": "e2e-state",
 "generatedAt": "2026-09-03T18:44:19.900001Z",
 "server": {"tick": 20000, "tps": 20, "modVersion": "0.0.0-local"},
 "players": [
  {"name": "selftest", "online": false, "uuid": null, "dimension": null, "pos": null,
   "blockPos": null, "yaw": null, "pitch": null, "onGround": false, "mainHandItem": null,
   "portalCooldown": null}],
 "dimensions": [
  {"id": "minecraft:overworld", "managed": false, "loadedAtTick": {"absent": "no per-world load tick"}}],
 "zoneScope": "every world loaded right now",
 "zones": [
  {"kind": "source", "world": "minecraft:overworld", "targetWorld": "adventure:the_crucible",
   "axis": "X", "resident": false, "frameStands": null, "interior": [[228, 130, 297]]}]}
JSON
note "fixtures in $FX"

# ---------------------------------------------------------------------------
say "json_read — a value, an absence, and a broken filter stay three things"
expect_read "a present value reads back" 0 "minecraft:overworld" \
  json_read "$FX/state.json" '.players[] | select(.name==$player) | .dimension'
expect_read "an offline player's position is null, never a sentence" 0 "null" \
  json_read "$FX/state-cold.json" '.players[] | select(.name==$player) | .pos[2]'
expect_read "a filter matching nothing says so" 1 "no value" \
  json_read "$FX/state.json" '.players[] | select(.name=="nobody") | .dimension'
expect_read "a filter that does not compile is not an absent field" 1 "jq refused the filter" \
  json_read "$FX/state.json" '.players[] | select(.name==$player'
expect_read "a missing file names itself" 1 "no such file" \
  json_read "$FX/absent.json" '.anything'
expect_read "two matching rows are both shown, never silently the first" 0 "true,true" \
  json_read "$FX/state.json" '.zones[] | .frameStands'

# ---------------------------------------------------------------------------
say "assert_json — the shapes an assertion has to tell apart"
expect "a matching value passes" ok "minecraft:overworld" \
  assert_json "dimension" "$FX/state.json" '.players[] | select(.name==$player) | .dimension' minecraft:overworld
expect "a wrong value fails and prints what was there" fail "minecraft:overworld" \
  assert_json "dimension" "$FX/state.json" '.players[] | select(.name==$player) | .dimension' adventure:the_crucible
expect "an absent value fails without pretending to be one" fail "no value" \
  assert_json "dimension" "$FX/state.json" '.players[] | select(.name=="nobody") | .dimension' minecraft:overworld

# ---------------------------------------------------------------------------
say "frameStands is three-valued — null is not false and neither is true"
expect "a standing frame passes" ok "true" \
  assert_json "frame" "$FX/state.json" \
  '.zones[] | select(.kind=="source" and .targetWorld=="adventure:the_crucible") | .frameStands' true
expect "a COLD frame fails, and says null rather than false" fail "null" \
  assert_json "frame" "$FX/state-cold.json" \
  '.zones[] | select(.kind=="source" and .targetWorld=="adventure:the_crucible") | .frameStands' true

# ---------------------------------------------------------------------------
say "assert_json_at_least — and the refusal to compare a non-number"
expect "a count over the floor passes" ok "1" \
  assert_json_at_least "managed dimensions" "$FX/state.json" '[.dimensions[] | select(.managed)] | length' 1
expect "a count under the floor fails" fail "0" \
  assert_json_at_least "managed dimensions" "$FX/state-cold.json" '[.dimensions[] | select(.managed)] | length' 1
expect "a boolean is refused rather than compared as zero" fail "true" \
  assert_json_at_least "not a number" "$FX/state.json" \
  '.zones[] | select(.kind=="source") | .frameStands' 1

# ---------------------------------------------------------------------------
say "the state helpers refuse to answer from a stale artefact"
STATE_FILE=""
expect "an assertion before any refresh fails, naming the reason" fail "NO-e2e-state-refresh-failed" \
  assert_state "dimension before a refresh" "$PLAYER_SELECT | .dimension" minecraft:overworld
STATE_FILE="$FX/state.json"
expect "and passes once an artefact is in hand" ok "minecraft:overworld" \
  assert_player_dimension "dimension after a refresh" minecraft:overworld

# ---------------------------------------------------------------------------
say "A zone is found by its own interior, never by where it leads"
# The return arrival stands in the overworld and legitimately names the
# crucible, so counting by targetWorld counts two things and calls the second a
# duplicate. This pair is the regression: the broad reading, then the narrow one.
expect "counting by targetWorld alone finds the arrival too — 2, not 1" ok "2" \
  assert_json "zones to the crucible" "$FX/state.json" \
  '[.zones[] | select(.targetWorld=="adventure:the_crucible")] | length' 2
expect "keyed on the frame's own interior it is exactly one source zone" ok "1" \
  assert_source_zone_unique "one source zone over the frame" 228 130 297
expect "and it leads where the frame says" ok "adventure:the_crucible" \
  assert_source_zone "the frame's zone" 228 130 297 adventure:the_crucible
expect "an arrival zone's interior is not a source zone" fail "no value" \
  assert_source_zone "the arrival's cell" 14 132 19 adventure:the_crucible
expect "a cell no zone holds counts zero, never another zone's one" fail "0" \
  assert_source_zone_unique "a cell with no frame" 999 64 999
expect "a standing frame passes" ok "true" \
  assert_source_zone_frame_stands "crucible frame" 228 130 297
STATE_FILE="$FX/state-cold.json"
expect "and a COLD one fails with null, not false" fail "null" \
  assert_source_zone_frame_stands "crucible frame" 228 130 297
# shellcheck disable=SC2034  # read by lib.sh's state helpers
STATE_FILE="$FX/state.json"

# ---------------------------------------------------------------------------
say "Start the stub bridge"
STUB="$RUN_DIR/stub-bridge.py"
cat > "$STUB" <<'PYTHON'
"""A stand-in for the companion mod's dev bridge, for lib.sh's self-test.

Answers the shapes the real bridge answers. Behaviour is chosen by the path,
or for /screenshot by a marker in the requested filename, so every branch of
the client can be driven without a Minecraft client.
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

BUSY = {"ok": False, "error": "timed out after 5000ms waiting for the render thread",
        "timeout": True, "retryable": True, "path": "/state", "timeoutMs": 5000}
DIAMOND = {"id": "minecraft:diamond", "count": 64, "damage": None, "maxDamage": None}
PICKAXE = {"id": "minecraft:diamond_pickaxe", "count": 1, "damage": 12, "maxDamage": 1561}
# No number carries a .0: the real writer rounds to 3dp and strips trailing
# zeros, so a stub that emits 130.0 would let a string comparison pass here and
# fail against the game.
STATE = {"ok": True, "tick": 942,
         "player": {"dimension": "minecraft:overworld", "pos": [229, 130, 337],
                    "blockPos": [229, 130, 337],
                    "rotation": {"yaw": 180, "pitch": 0, "headYaw": 180,
                                 "bodyYaw": 180, "facing": "north"},
                    "vitals": {"health": 20, "maxHealth": 20, "food": 18, "saturation": 4.2,
                               "air": 300, "maxAir": 300, "xpLevel": 7, "xpProgress": 0.35},
                    "held": {"mainHand": DIAMOND, "offHand": None, "selectedSlot": 0,
                             "hotbar": [DIAMOND, None, None, None, PICKAXE,
                                        None, None, None, None]},
                    "status": {"pose": "STANDING", "onGround": True, "fallDistance": 0,
                               "falling": False, "drowning": False, "sneaking": False,
                               "sprinting": False, "swimming": False, "crawling": False,
                               "gliding": False, "sleeping": False, "riding": False,
                               "onFire": False, "inLava": False, "inWater": False,
                               "submerged": False, "climbing": False, "blocking": False,
                               "spectator": False}},
         "client": {"currentScreen": None, "screenTitle": None, "arrivalScreen": False,
                    "fps": 60, "loadedChunks": 313, "worldLoaded": True, "paused": False},
         "projections": [{"destination": "adventure:the_crucible", "aperture": [228, 130, 297],
                          "meshReady": True, "quads": 20929,
                          "layers": [{"layer": "solid", "quads": 20929}]}]}
# World load: the whole player record is an absence, and every path under it
# reads null — the same null an empty hand gives.
ABSENT = {"ok": True, "tick": 12,
          "player": {"absent": "no player in the world"},
          "client": {"currentScreen": "class_424", "screenTitle": "Loading terrain...",
                     "arrivalScreen": False, "fps": 60, "loadedChunks": 0,
                     "worldLoaded": False, "paused": False},
          "projections": []}
# Crawling under a slab reports pose SWIMMING with swimming false: 1.21.1 has
# no CRAWLING pose, so the pose cannot tell the two apart and the flags can.
CRAWLING = json.loads(json.dumps(STATE))
CRAWLING["player"]["status"].update({"pose": "SWIMMING", "crawling": True, "swimming": False})
SEEN = {}


class Stub(BaseHTTPRequestHandler):

    def log_message(self, *args):
        pass

    def reply(self, status, body):
        raw = (body if isinstance(body, str) else json.dumps(body)).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        if self.path == "/health":
            self.reply(200, {"ok": True, "mod": "customdimensionsclient",
                             "mc": "1.21.1", "tick": 942})
        elif self.path == "/state":
            self.reply(200, STATE)
        elif self.path == "/busy-once":
            SEEN["busy-once"] = SEEN.get("busy-once", 0) + 1
            self.reply(200, STATE) if SEEN["busy-once"] > 1 else self.reply(503, BUSY)
        elif self.path == "/absent":
            self.reply(200, ABSENT)
        elif self.path == "/crawling":
            self.reply(200, CRAWLING)
        elif self.path == "/always-busy":
            self.reply(503, BUSY)
        elif self.path == "/not-json":
            self.reply(200, "<html>a proxy said no</html>")
        else:
            self.reply(404, {"ok": False, "error": "no such endpoint: " + self.path})

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"] or 0)) or "{}")
        if self.path == "/screenshot":
            self.reply(200, self.shot(body["path"]))
        elif self.path == "/input":
            self.reply(200, self.walk(body["walk"]) if "walk" in body
                       else {"ok": True, "action": next(iter(body)), "detail": {},
                             "before": STATE, "after": STATE, "shots": {}})
        else:
            self.reply(404, {"ok": False, "error": "no such endpoint: " + self.path})

    @staticmethod
    def shot(path):
        """A marker in the filename picks which way a capture goes wrong."""
        if "unwritable" in path:
            return {"path": None, "error": "java.io.IOException: read-only file system"}
        size = 10 if "tiny" in path else 4096
        with open(path, "wb") as out:
            out.write(b"\x89PNG\r\n\x1a\n" + b"s" * (size - 8))
        return {"path": path, "bytes": size + (1 if "lie" in path else 0),
                "width": 1708, "height": 960}

    @staticmethod
    def walk(request):
        """Three blocks stalls, ten arrives — the two verdicts, told apart."""
        blocks = request.get("blocks", 1)
        stalled = blocks == 3
        return {"ok": True, "action": "walk", "requested": blocks,
                "travelled": 1.5 if stalled else blocks,
                "ticks": 64, "arrived": not stalled, "stalled": stalled,
                "reason": ("position unchanged for 20 ticks while forward was held"
                           if stalled else "arrived"),
                "stalledAt": [207.3, 130, 320.3] if stalled else None,
                "before": STATE, "after": STATE, "shots": {}}


HTTPServer(("127.0.0.1", int(sys.argv[1])), Stub).serve_forever()
PYTHON
python3 "$STUB" "$BRIDGE_PORT" &
STUB_PID=$!
STUB_READY=0
STUB_WAITED=0
while [ "$STUB_WAITED" -lt 25 ]; do
  if curl -s -o /dev/null --max-time 2 "$BRIDGE_BASE/health"; then STUB_READY=1; break; fi
  sleep 0.2
  STUB_WAITED=$((STUB_WAITED + 1))
done
if [ "$STUB_READY" -ne 1 ]; then
  printf '\033[31mREFUSING TO RUN\033[0m — the stub bridge never came up on %s.\n' "$BRIDGE_BASE" >&2
  exit 1
fi
note "stub bridge up on $BRIDGE_BASE (pid $STUB_PID)"

# ---------------------------------------------------------------------------
say "bridge_request — 503 is retried, everything else is the answer"
if bridge_request "$RUN_DIR/busy-once.json" GET /busy-once; then
  self_pass "a retryable 503 is retried and the second answer is taken"
else
  self_fail "a retryable 503 should have been retried: $BRIDGE_REASON"
fi
BRIDGE_ATTEMPTS=2
if bridge_request "$RUN_DIR/never.json" GET /always-busy; then
  self_fail "a permanently busy endpoint should not have succeeded"
else
  case "$BRIDGE_REASON" in
    *"stayed busy for all 2 attempts"*) self_pass "a bounded retry gives up and says how many times it tried" ;;
    *) self_fail "gave up with the wrong reason: $BRIDGE_REASON" ;;
  esac
fi
# shellcheck disable=SC2034  # read by lib.sh's bridge client
BRIDGE_ATTEMPTS=6
if bridge_request "$RUN_DIR/nonjson.json" GET /not-json; then
  self_fail "a body that is not JSON should never become a readable answer"
else
  case "$BRIDGE_REASON" in
    *"not JSON"*) self_pass "a non-JSON body is refused rather than parsed" ;;
    *) self_fail "wrong reason for a non-JSON body: $BRIDGE_REASON" ;;
  esac
fi
if [ -f "$RUN_DIR/nonjson.json" ]; then
  self_fail "a failed request left a file an assertion could read"
else
  self_pass "a failed request leaves no file behind for an assertion to read"
fi
if bridge_request "$RUN_DIR/gone.json" GET /no-such-thing; then
  self_fail "a 404 should not have succeeded"
else
  case "$BRIDGE_REASON" in
    *"404"*) self_pass "a 404 is reported with its status and message" ;;
    *) self_fail "wrong reason for a 404: $BRIDGE_REASON" ;;
  esac
fi

# ---------------------------------------------------------------------------
say "The client's own state"
expect "an assertion before any bridge_state finds nothing to read" fail "no such file" \
  assert_bridge "world loaded" '.client.worldLoaded' true
bridge_state
expect "the client reports its world loaded" ok "true" \
  assert_bridge "world loaded" '.client.worldLoaded' true
expect "the arrival screen is read by instanceof, not by class name" ok "false" \
  assert_bridge "arrival screen" '.client.arrivalScreen' false
expect "a projection's quad count is a number worth a floor" ok "20929" \
  assert_bridge_at_least "quads" '.projections[] | select(.destination=="adventure:the_crucible") | .quads' 1

say "The player's facts, and the absence that reads like one"
expect_read "a nested field reads back through the player reader" 0 "true" \
  bridge_player_read '.status.onGround'
expect_read "an empty off hand is null, and null is the answer" 0 "null" \
  bridge_player_read '.held.offHand'
expect_read "the hotbar is always nine entries" 0 "9" \
  bridge_player_read '.held.hotbar | length'
expect_read "an empty hotbar slot is a null element, not a missing one" 0 "null" \
  bridge_player_read '.held.hotbar[3]'
expect_read "a worn item carries its damage" 0 "12" \
  bridge_player_read '.held.hotbar[4].damage'
expect_read "and one that cannot wear carries null, which is a fact" 0 "null" \
  bridge_player_read '.held.mainHand.damage'
expect "the held item reads back by id, never by mainHandItem" ok "minecraft:diamond" \
  assert_bridge_player "held" '.held.mainHand.id' minecraft:diamond
expect "health is 20, never 20.0 — trailing zeros are stripped" ok "20" \
  assert_bridge_player "health" '.vitals.health' 20

# Serve the world-load body into the file the assertions read.
bridge_request "$RUN_DIR/bridge-state.json" GET /absent
expect_read "an absent player is said to be absent, not read as null" 1 "the client has no player" \
  bridge_player_read '.status.onGround'
expect "and an assertion on one fails with that reason, not with 'null'" fail "no player in the world" \
  assert_bridge_player "dimension" '.dimension' minecraft:overworld
expect "a bare assert_bridge on the same field gets only null — which is why the reader exists" fail "null" \
  assert_bridge "dimension" '.player.dimension' minecraft:overworld

# Crawling under a slab: the pose says SWIMMING and the flags say otherwise.
bridge_request "$RUN_DIR/bridge-state.json" GET /crawling
expect "crawling reports pose SWIMMING — assert the flag, never the pose" ok "true" \
  assert_bridge_player "crawling" '.status.crawling' true
expect "and the swimming flag is false while that pose says otherwise" ok "false" \
  assert_bridge_player "swimming" '.status.swimming' false
bridge_state

# ---------------------------------------------------------------------------
say "assert_shot — four ways a capture goes wrong, four messages"
expect "a real capture passes and reports its size" ok "4096 bytes" \
  assert_shot "a shot" "good-shot"
expect "a client that cannot write says so" fail "the client could not write it" \
  assert_shot "a shot" "unwritable-shot"
expect "a byte count that disagrees with the file fails" fail "the file on disk is 4096" \
  assert_shot "a shot" "lie-shot"
expect "a PNG too small to be a framebuffer fails" fail "the readback failed" \
  assert_shot "a shot" "tiny-shot"

# ---------------------------------------------------------------------------
say "assert_walk — arrived is the verdict, not stalled"
expect "a walk that arrives passes" ok "arrived=true" \
  assert_walk "walk ten" 10
expect "a walk that stalls fails, and says where and why" fail "stalled at 207.3 130 320.3" \
  assert_walk "walk three" 3

# ---------------------------------------------------------------------------
say "walk_to_dimension — the crossing is the oracle, and a stall ends it"
# The server half is stubbed: these cases are about the loop's decisions, and
# a real e2e-state artefact needs a server.
FAKE_DIM="minecraft:overworld"
state_refresh() { return 0; }
player_dimension() { printf '%s' "$FAKE_DIM"; }
player_block_z() { printf '337'; }

if walk_to_dimension adventure:the_crucible 3 4; then
  self_fail "a stalled walk should not have reported a crossing"
else
  case "$WALK_NOTE" in
    *"stalled after 1 step"*) self_pass "a stall stops the walk at once and names the step" ;;
    *) self_fail "wrong note for a stalled walk: $WALK_NOTE" ;;
  esac
fi

if walk_to_dimension adventure:the_crucible 10 3; then
  self_fail "a walk that never crosses should not have reported a crossing"
else
  case "$WALK_NOTE" in
    *"3 step(s) of 10 blocks and still in minecraft:overworld"*)
      self_pass "a walk that arrives every step without crossing is still a failure" ;;
    *) self_fail "wrong note for a non-crossing walk: $WALK_NOTE" ;;
  esac
fi

FAKE_DIM="adventure:the_crucible"
if walk_to_dimension adventure:the_crucible 10 3; then
  self_pass "a crossing is read from the server, not from the walk's own verdict"
else
  self_fail "a crossing was not detected: $WALK_NOTE"
fi

# ---------------------------------------------------------------------------
# The counters lib.sh accumulated belong to the assertions under test. The
# run's verdict is this file's own.
# shellcheck disable=SC2034  # both are read by lib.sh's EXIT trap
PASSES=$SELF_OK
# shellcheck disable=SC2034
FAILURES=$SELF_BAD
printf '\nself-test cases: %d passed, %d failed\n' "$SELF_OK" "$SELF_BAD"
finish
