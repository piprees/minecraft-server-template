#!/usr/bin/env bash
# Shared harness for the end-to-end retest scripts.
#
# Purpose: two instruments and one assertion form. Server facts come from
#          `customdim e2e-state` as a JSON artefact; the client is read and
#          driven through the companion mod's dev bridge on loopback.
# Context: sourced by e2e-item*.sh and e2e-c1-render.sh. Drives Pip's real
#          client — never a bot, and never the window manager.
# Usage:   source "$(dirname "$0")/lib.sh"; then state_refresh, assert_*, finish.
#          The bridge needs `./dev launch --dev-bridge` (port 8766, loopback).
#          BRIDGE_PORT=<n> overrides it; PLAYER=<name> overrides the player.
# EVERY ACTION RETURNS A MEASUREMENT. A walk that does not arrive says how far
#          it got and why it stopped; a read that has no value says so in words
#          no assertion can mistake for one. Nothing returns silence.
# Gotchas: no `set -e` here on purpose — an assertion must be able to fail
#          without aborting the run. A partial run is forced to FAIL by the
#          EXIT trap, so dying early can never look like a pass.
#          `frameStands` is three-valued: true, false, or null when the chunks
#          are cold and nobody could tell. Assert `= true`, never truthiness.
#          A walk's `arrived` and `stalled` are independent: not stalled is not
#          success. Assert `arrived`.
#          Never compute inside jq — `null + 1` is 1, so a missing field would
#          come back as a number. Read the value, then compare in the shell.
#          Assertions compare STRINGS, and the bridge rounds to 3dp and strips
#          trailing zeros, so full health is `20` and never `20.0`. Expect the
#          stripped form.

set -uo pipefail

CONSUMER_DIR="${CONSUMER_DIR:-$HOME/Projects/elfydd}"

# Whose client the run drives. WHITELIST first, then OPS, first name of either.
# The local profile disables the whitelist and usually leaves WHITELIST
# commented out, so OPS is the one that is actually set on a dev consumer.
# env_first_name reads a commented key as absent, which is what the leading
# ^[[:space:]]*KEY= anchor buys.
env_first_name() { # key
  sed -n "s/^[[:space:]]*$1=//p" "$CONSUMER_DIR/.env" 2>/dev/null |
    head -1 | tr -d "'\"" | cut -d, -f1 | tr -d '[:space:]'
}
if [ -z "${PLAYER:-}" ]; then
  PLAYER="$(env_first_name WHITELIST)"
  [ -z "$PLAYER" ] && PLAYER="$(env_first_name OPS)"
fi
if [ -z "$PLAYER" ]; then
  printf '\033[31mREFUSING TO RUN\033[0m — no player name.\n' >&2
  printf 'Set WHITELIST or OPS in %s/.env, or pass PLAYER=<name>.\n' "$CONSUMER_DIR" >&2
  exit 1
fi
RUN_ROOT="${RUN_ROOT:-/tmp/c0-e2e}"
RUN_ID="$(date +%Y%m%d-%H%M%S)"
RUN_DIR="$RUN_ROOT/${SCRIPT_NAME:-run}-$RUN_ID"
mkdir -p "$RUN_DIR"

PASSES=0
FAILURES=0
STEP=0
FINISHED=0

c_pass() { printf '  \033[32mPASS\033[0m  %s\n' "$1"; }
c_fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; }
say()    { printf '\n== %s\n' "$1"; }
note()   { printf '      %s\n' "$1"; }

# --- summary, and the guarantee that a partial run is a failure -------------
on_exit() {
  local rc=$?
  printf '\n----------------------------------------------------------------\n'
  printf 'run dir: %s\n' "$RUN_DIR"
  printf 'assertions: %d passed, %d failed\n' "$PASSES" "$FAILURES"
  if [ "$FINISHED" -ne 1 ]; then
    printf '\033[31mINCOMPLETE RUN\033[0m — the script exited before its last step.\n'
    printf 'Treat this as a FAILURE regardless of the counts above.\n'
    [ "$rc" -eq 0 ] && rc=1
  elif [ "$FAILURES" -gt 0 ]; then
    printf '\033[31mFAILED\033[0m\n'
    rc=1
  else
    printf '\033[32mPASSED\033[0m\n'
    rc=0
  fi
  exit "$rc"
}
trap on_exit EXIT

finish() { FINISHED=1; }

# --- RCON -------------------------------------------------------------------
# One command per docker exec: rcon-cli concatenates multiple arguments into a
# single command and answers "Incorrect argument for command".
rcon() {
  # </dev/null matters: `docker exec -i` reads stdin, and every caller below
  # runs inside a `while read` loop fed by a heredoc. Without this it eats the
  # remaining lines and the loop stops early with no error.
  (cd "$CONSUMER_DIR" && timeout 25 docker exec -i mc rcon-cli "$1" 2>&1 </dev/null)
}

dev() { (cd "$CONSUMER_DIR" && ./dev "$@" </dev/null); }

# --- assertions -------------------------------------------------------------
# Every assertion is numbered, prints expected and actual, and counts.
_record() { # ok label expected actual
  STEP=$((STEP + 1))
  if [ "$1" = "ok" ]; then
    PASSES=$((PASSES + 1)); c_pass "$STEP. $2"
  else
    FAILURES=$((FAILURES + 1)); c_fail "$STEP. $2"
  fi
  printf '        expected: %s\n' "$3"
  printf '        actual:   %s\n' "$4"
  printf '%s\t%s\t%s\t%s\t%s\n' "$STEP" "$1" "$2" "$3" "$4" >> "$RUN_DIR/assertions.tsv"
}

report_metric() { # label value note
  printf '  \033[36mMETRIC\033[0m %s = %s\n' "$1" "$2"
  [ -n "${3:-}" ] && printf '        %s\n' "$3"
  printf 'metric\t%s\t%s\n' "$1" "$2" >> "$RUN_DIR/assertions.tsv"
}

# --- reading JSON -----------------------------------------------------------
# The one reader both instruments go through. It prints either the value or a
# sentence saying why there isn't one, and returns 1 for the sentence — so an
# assertion's "actual" is always populated and a missing field can never be
# read as a measurement. Three outcomes stay apart: a filter that does not
# compile (a harness bug), a filter that selects nothing (the fact is absent),
# and a value. Several matching rows come back comma-joined rather than
# silently first.
json_read() { # file jq-filter
  local out rc errs="$RUN_DIR/.jq-stderr"
  if [ ! -f "$1" ]; then
    printf 'no such file: %s' "$1"; return 1
  fi
  out="$(jq -r --arg player "$PLAYER" "$2" "$1" 2>"$errs")"
  rc=$?
  if [ "$rc" -ne 0 ]; then
    printf 'jq refused the filter (rc=%s): %s' "$rc" "$(tr '\n' ' ' < "$errs")"
    return 1
  fi
  if [ -z "$out" ]; then
    printf 'no value: nothing in %s matched %s' "$(basename "$1")" "$2"
    return 1
  fi
  printf '%s' "$out" | tr '\n' ','
}

assert_json() { # label file jq-filter expected
  local got
  got="$(json_read "$2" "$3")"
  if [ "$got" = "$4" ]; then
    _record ok "$1" "$4" "$got"
  else
    _record fail "$1" "$4" "$got"
  fi
}

# Numeric, and it refuses anything that is not a number rather than letting
# `true` or a sentence compare as zero.
assert_json_at_least() { # label file jq-filter minimum
  local got
  got="$(json_read "$2" "$3")"
  case "$got" in
    ''|*[!0-9.-]*) _record fail "$1" ">= $4" "$got"; return ;;
  esac
  if awk -v a="$got" -v b="$4" 'BEGIN { exit !(a + 0 >= b + 0) }'; then
    _record ok "$1" ">= $4" "$got"
  else
    _record fail "$1" ">= $4" "$got"
  fi
}

assert_file_contains() { # label file expected-substring
  if [ ! -f "$2" ]; then _record fail "$1" "contains '$3'" "no such file: $2"; return; fi
  if grep -q -- "$3" "$2"; then
    _record ok "$1" "contains '$3'" "found in $2"
  else
    _record fail "$1" "contains '$3'" "not found in $2"
  fi
}

assert_count_at_least() { # label file pattern n
  local got; got="$(grep -c -- "$3" "$2" 2>/dev/null || true)"
  [ -z "$got" ] && got=0
  if [ "$got" -ge "$4" ]; then
    _record ok "$1" ">= $4 lines matching '$3'" "$got"
  else
    _record fail "$1" ">= $4 lines matching '$3'" "$got"
  fi
}

# A block probe answers "Test passed" / "Test failed". An UNLOADED position
# answers "That position is not loaded" — reported as its own failure rather
# than being silently read as "Test failed" (TROUBLESHOOTING.md#t50).
# ASSERT_DIM names the world every probe runs in. Unset means the overworld,
# which is what an RCON command defaults to — so a probe in another dimension
# silently reads the OVERWORLD at those coordinates and answers about a place
# nobody is testing. Set it before asserting anywhere else, and unset it after.
assert_block() { # label x y z block want(present|absent)
  local out want_txt prefix=""
  [ -n "${ASSERT_DIM:-}" ] && prefix="execute in $ASSERT_DIM run "
  out="$(rcon "${prefix}execute if block $2 $3 $4 $5" | tr -d '\r' | tr '\n' ' ')"
  [ "$6" = "present" ] && want_txt="Test passed" || want_txt="Test failed"
  case "$out" in
    *"not loaded"*)
      _record fail "$1" "$want_txt at $2 $3 $4" "POSITION NOT LOADED — probe is meaningless here" ;;
    *"Test passed"*|*"Test failed"*)
      case "$out" in
        *"$want_txt"*) _record ok   "$1" "$want_txt ($5 at $2 $3 $4)" "$out" ;;
        *)             _record fail "$1" "$want_txt ($5 at $2 $3 $4)" "$out" ;;
      esac ;;
    *)
      _record fail "$1" "$want_txt at $2 $3 $4" "unrecognised RCON answer: $out" ;;
  esac
}

# --- the server's own facts -------------------------------------------------
# `customdim e2e-state <player>` writes one JSON artefact and answers with a
# receipt. The harness reads the FILE and never parses the reply: a reply is a
# sentence, and the run this replaced read "No entity was found" as a
# coordinate.
#
# The mod writes to the .seed-rolling bind mount when it exists and to its own
# config directory when it does not, so both are candidates and the newer one
# wins. Passing the player name is what makes the answer unambiguous: a name
# nobody holds comes back as one record with "online": false and null facts.
STATE_PRIMARY="$CONSUMER_DIR/.seed-rolling/e2e-state.json"
STATE_FALLBACK="$CONSUMER_DIR/data/config/custom-dimensions/e2e-state/e2e-state.json"
STATE_FILE=""
STATE_REASON=""
STATE_SEQ=0

_state_stamp() { # file
  [ -f "$1" ] || return 0
  jq -r '.generatedAt // ""' "$1" 2>/dev/null
}

_state_newest() {
  if [ -f "$STATE_PRIMARY" ] && [ -f "$STATE_FALLBACK" ]; then
    if [ "$STATE_PRIMARY" -nt "$STATE_FALLBACK" ]; then
      printf '%s' "$STATE_PRIMARY"
    else
      printf '%s' "$STATE_FALLBACK"
    fi
  elif [ -f "$STATE_PRIMARY" ]; then
    printf '%s' "$STATE_PRIMARY"
  elif [ -f "$STATE_FALLBACK" ]; then
    printf '%s' "$STATE_FALLBACK"
  fi
}

# One RCON round trip. An artefact that did not change its generatedAt is the
# PREVIOUS call's answer, which is the failure mode a fixed filename invites,
# so it is refused rather than measured.
state_refresh() {
  local was_primary was_fallback receipt file was now
  was_primary="$(_state_stamp "$STATE_PRIMARY")"
  was_fallback="$(_state_stamp "$STATE_FALLBACK")"
  receipt="$(rcon "customdim e2e-state $PLAYER" | tr -d '\r' | tr '\n' ' ')"
  file="$(_state_newest)"
  if [ -z "$file" ]; then
    STATE_FILE=""
    STATE_REASON="no artefact at $STATE_PRIMARY or $STATE_FALLBACK — RCON answered: $receipt"
    _record fail "e2e-state artefact written" "a JSON artefact this call rewrote" "$STATE_REASON"
    return 1
  fi
  if [ "$file" = "$STATE_PRIMARY" ]; then was="$was_primary"; else was="$was_fallback"; fi
  now="$(_state_stamp "$file")"
  if [ -z "$now" ] || [ "$now" = "$was" ]; then
    STATE_FILE=""
    STATE_REASON="$file was not rewritten (generatedAt still '${now:-unreadable}') — RCON answered: $receipt"
    _record fail "e2e-state artefact written" "a JSON artefact this call rewrote" "$STATE_REASON"
    return 1
  fi
  STATE_FILE="$file"
  STATE_REASON=""
  # Every refresh is kept: the run's evidence is the sequence, not the last one.
  STATE_SEQ=$((STATE_SEQ + 1))
  cp "$file" "$RUN_DIR/e2e-state-$STATE_SEQ.json" 2>/dev/null
  note "e2e-state: $file (tick $(json_read "$file" '.server.tick'), generated $now)"
  return 0
}

# Before a refresh, and after one that failed, this names a file that cannot
# exist. A stale artefact from the previous call must never answer for this one.
_state_path() {
  if [ -n "$STATE_FILE" ]; then
    printf '%s' "$STATE_FILE"
  else
    printf '%s' "$RUN_DIR/NO-e2e-state-refresh-failed-or-was-never-called.json"
  fi
}

state_read()   { json_read "$(_state_path)" "$1"; }
assert_state() { assert_json "$1" "$(_state_path)" "$2" "$3"; }
assert_state_at_least() { assert_json_at_least "$1" "$(_state_path)" "$2" "$3"; }

# The player's own record, selected by name, so the filter is written once.
PLAYER_SELECT='.players[] | select(.name==$player)'

player_dimension() { state_read "$PLAYER_SELECT | .dimension"; }
player_block_z()   { state_read "$PLAYER_SELECT | .blockPos[2]"; }

assert_player_dimension() { # label expected-dimension
  assert_state "$1" "$PLAYER_SELECT | .dimension" "$2"
}

# A source zone, picked out by a cell of its own interior — never by where it
# leads. Two reasons: an ARRIVAL zone standing in the same world legitimately
# carries the same `targetWorld` (the crucible's return arrival sits in the
# overworld and names the crucible), and a second frame to the same place
# somewhere else is a build, not a defect. The mod's own zone identity is
# (target, axis, interior), so the interior is what makes the question exact.
source_zone_select() { # x y z
  printf '.zones[] | select(.kind=="source" and any(.interior[]; . == [%s,%s,%s]))' "$1" "$2" "$3"
}

assert_source_zone() { # label x y z expected-target-world
  assert_state "$1" "$(source_zone_select "$2" "$3" "$4") | .targetWorld" "$5"
}

# Two source zones over one interior is the re-ignition duplicate
# PortalHelper.addZoneIfAbsent exists to prevent: doubled portal particles, a
# doubled immersive projection, doubled chunk tickets, and it survives a restart.
assert_source_zone_unique() { # label x y z
  assert_state "$1" "[$(source_zone_select "$2" "$3" "$4")] | length" 1
}

# Three-valued on purpose: null means the chunks are cold and nobody could tell,
# which is not the same as down, so this asserts `= true` rather than truthiness.
assert_source_zone_frame_stands() { # label x y z
  assert_state "$1" "$(source_zone_select "$2" "$3" "$4") | .frameStands" true
}

# --- the client, through the companion mod's dev bridge ---------------------
# Loopback HTTP, opened by `./dev launch --dev-bridge` and deleted by a plain
# launch. Nothing here touches the window manager: no focus is stolen, no
# synthetic keystroke is sent, and a screenshot is the GAME's framebuffer
# rather than a screen region with whatever is stacked on top.
BRIDGE_PORT="${BRIDGE_PORT:-8766}"
BRIDGE_BASE="http://127.0.0.1:$BRIDGE_PORT"
BRIDGE_TIMEOUT="${BRIDGE_TIMEOUT:-30}"
BRIDGE_ATTEMPTS="${BRIDGE_ATTEMPTS:-6}"
BRIDGE_RETRY_SLEEP="${BRIDGE_RETRY_SLEEP:-5}"
BRIDGE_REASON=""
BRIDGE_LAST=""
BRIDGE_SEQ=0

# A 503 with retryable:true is the render thread being busy — normal while a
# world loads — so it is retried a bounded number of times. Anything else is
# the answer. A body that is not JSON never becomes the file an assertion
# reads, so a proxy error page cannot be parsed as a measurement.
bridge_request() { # outfile method path [json-body] [max-seconds]
  local out="$1" method="$2" path="$3" body="${4:-}" seconds="${5:-$BRIDGE_TIMEOUT}"
  local tmp="$out.part" code rc attempt=0
  BRIDGE_REASON=""
  rm -f "$out" "$tmp"
  while [ "$attempt" -lt "$BRIDGE_ATTEMPTS" ]; do
    attempt=$((attempt + 1))
    if [ "$method" = "GET" ]; then
      code="$(curl -sS -o "$tmp" -w '%{http_code}' --max-time "$seconds" \
        "$BRIDGE_BASE$path" 2>"$tmp.err")"
    else
      code="$(curl -sS -o "$tmp" -w '%{http_code}' --max-time "$seconds" \
        -X POST -H 'Content-Type: application/json' --data-binary "$body" \
        "$BRIDGE_BASE$path" 2>"$tmp.err")"
    fi
    rc=$?
    if [ "$rc" -ne 0 ]; then
      BRIDGE_REASON="curl failed (rc=$rc) on $method $path: $(tr '\n' ' ' < "$tmp.err")"
      rm -f "$tmp" "$tmp.err"
      return 1
    fi
    if ! jq -e . "$tmp" >/dev/null 2>&1; then
      BRIDGE_REASON="$method $path answered $code with a body that is not JSON: $(head -c 200 "$tmp")"
      rm -f "$tmp" "$tmp.err"
      return 1
    fi
    if [ "$code" = "200" ]; then
      mv "$tmp" "$out"
      rm -f "$tmp.err"
      BRIDGE_LAST="$out"
      return 0
    fi
    if [ "$code" = "503" ] && jq -e '.retryable == true' "$tmp" >/dev/null 2>&1; then
      note "bridge: $path is busy (attempt $attempt of $BRIDGE_ATTEMPTS) — $(jq -r '.error' "$tmp")"
      sleep "$BRIDGE_RETRY_SLEEP"
      continue
    fi
    BRIDGE_REASON="$method $path answered $code: $(jq -r '.error // "no error field"' "$tmp")"
    rm -f "$tmp" "$tmp.err"
    return 1
  done
  BRIDGE_REASON="$method $path stayed busy for all $BRIDGE_ATTEMPTS attempts"
  rm -f "$tmp" "$tmp.err"
  return 1
}

# Every POST body is built by jq, so a run directory with a quote in its name
# cannot produce a body the bridge silently misreads.
bridge_input() { # name json-body [max-seconds]
  BRIDGE_SEQ=$((BRIDGE_SEQ + 1))
  local out="$RUN_DIR/input-$BRIDGE_SEQ-$1.json"
  bridge_request "$out" POST /input "$2" "${3:-$BRIDGE_TIMEOUT}"
}

bridge_look() { # yaw pitch
  bridge_input look "$(jq -n --argjson yaw "$1" --argjson pitch "$2" \
    '{look: {yaw: $yaw, pitch: $pitch}}')"
}

WALK_TIMEOUT_MS="${WALK_TIMEOUT_MS:-30000}"
# shellcheck disable=SC2034  # read by the scripts that source this
WALK_NOTE=""

# The walk's own before/after screenshots land in the run directory rather than
# the client's temp dir, so the evidence for a step is beside the numbers.
bridge_walk() { # blocks [timeout-ms]
  local blocks="$1" timeout_ms="${2:-$WALK_TIMEOUT_MS}"
  bridge_input walk "$(jq -n --argjson blocks "$blocks" --argjson ms "$timeout_ms" \
    --arg shots "$RUN_DIR" '{walk: {blocks: $blocks, timeoutMs: $ms, shots: $shots}}')" \
    "$(( timeout_ms / 1000 + 30 ))"
}

_walk_detail() { # response-file
  printf 'travelled %s in %s ticks, arrived=%s stalled=%s, %s, %s' \
    "$(json_read "$1" '.travelled')" "$(json_read "$1" '.ticks')" \
    "$(json_read "$1" '.arrived')" "$(json_read "$1" '.stalled')" \
    "$(json_read "$1" '.reason')" \
    "$(json_read "$1" 'if .stalledAt == null then "not stalled" else "stalled at " + (.stalledAt | map(tostring) | join(" ")) end')"
}

# `stalled` false is NOT success: a walk can miss its distance by running out
# of time or by walking in a circle. The verdict is `arrived`.
assert_walk() { # label blocks [timeout-ms]
  local blocks="$2"
  if ! bridge_walk "$blocks" "${3:-$WALK_TIMEOUT_MS}"; then
    _record fail "$1" "a completed walk of $blocks blocks" "$BRIDGE_REASON"
    return
  fi
  if [ "$(json_read "$BRIDGE_LAST" '.arrived')" = "true" ]; then
    _record ok "$1" "arrived after $blocks blocks" "$(_walk_detail "$BRIDGE_LAST")"
  else
    _record fail "$1" "arrived after $blocks blocks" "$(_walk_detail "$BRIDGE_LAST")"
  fi
}

# Walk toward a portal in bounded steps until the SERVER says the player is in
# the target world. Arrival by distance is not the oracle here: crossing moves
# the player far enough that the tracker calls any walk arrived, so the
# dimension is read from e2e-state after every step. A stall ends it — repeating
# a walk that went nowhere only hides why. WALK_NOTE carries the reason out.
# shellcheck disable=SC2034  # WALK_NOTE is read by the caller, not here
walk_to_dimension() { # target-dimension blocks-per-step max-steps
  local target="$1" blocks="$2" max="$3" step=0 dim="unread" stalled reason
  WALK_NOTE=""
  while [ "$step" -lt "$max" ]; do
    step=$((step + 1))
    if ! bridge_walk "$blocks"; then
      WALK_NOTE="the bridge refused step $step: $BRIDGE_REASON"
      return 1
    fi
    stalled="$(json_read "$BRIDGE_LAST" '.stalled')"
    reason="$(json_read "$BRIDGE_LAST" '.reason')"
    note "walk step $step: $(_walk_detail "$BRIDGE_LAST")"
    if ! state_refresh; then
      WALK_NOTE="the server state could not be read after step $step: $STATE_REASON"
      return 1
    fi
    dim="$(player_dimension)"
    note "walk step $step: server says $dim at block z $(player_block_z)"
    case "$dim" in
      *"$target"*) note "walk: crossed into $dim after $step step(s)"; return 0 ;;
    esac
    if [ "$stalled" = "true" ]; then
      WALK_NOTE="stalled after $step step(s), still in $dim — $reason, at $(json_read "$BRIDGE_LAST" '.stalledAt | map(tostring) | join(" ")')"
      return 1
    fi
  done
  WALK_NOTE="$max step(s) of $blocks blocks and still in $dim — last walk: $reason"
  return 1
}

# --- screenshots ------------------------------------------------------------
# The bridge writes the game's own framebuffer and reports the path and byte
# count, so the capture is assertable instead of guessed at from a file size.
# A PNG smaller than this is a failed readback, not a dark room.
SHOT_MIN_BYTES="${SHOT_MIN_BYTES:-1024}"
SHOT_PNG=""

_capture_shot() { # name
  SHOT_PNG="$RUN_DIR/$1.png"
  bridge_request "$RUN_DIR/$1.shot.json" POST /screenshot \
    "$(jq -n --arg path "$SHOT_PNG" '{path: $path}')"
}

shot() { # name
  if _capture_shot "$1"; then
    note "screenshot: $SHOT_PNG ($(json_read "$BRIDGE_LAST" '.bytes') bytes, $(json_read "$BRIDGE_LAST" '"\(.width)x\(.height)"'))"
  else
    note "screenshot FAILED: $1 — $BRIDGE_REASON"
  fi
}

# A screenshot IS the evidence, so a silently-failed capture must fail the run.
# Three separate ways it can be wrong, each with its own message: the bridge
# refused, the client could not write, or the file the harness can see is not
# the file the client reported writing.
assert_shot() { # label name
  local reported bytes on_disk
  if ! _capture_shot "$2"; then
    _record fail "$1" "a PNG at $SHOT_PNG" "$BRIDGE_REASON"
    return
  fi
  reported="$(json_read "$BRIDGE_LAST" '.path')"
  if [ "$reported" = "null" ]; then
    _record fail "$1" "a PNG at $SHOT_PNG" "the client could not write it: $(json_read "$BRIDGE_LAST" '.error')"
    return
  fi
  bytes="$(json_read "$BRIDGE_LAST" '.bytes')"
  if [ ! -f "$SHOT_PNG" ]; then
    _record fail "$1" "a PNG at $SHOT_PNG" "the client reported $reported ($bytes bytes) and the harness cannot see it"
    return
  fi
  on_disk="$(wc -c < "$SHOT_PNG" | tr -d ' ')"
  if [ "$on_disk" != "$bytes" ]; then
    _record fail "$1" "the file the client wrote" "client reported $bytes bytes, the file on disk is $on_disk"
    return
  fi
  if [ "$on_disk" -lt "$SHOT_MIN_BYTES" ]; then
    _record fail "$1" ">= $SHOT_MIN_BYTES bytes of framebuffer" "$on_disk bytes — the readback failed"
    return
  fi
  _record ok "$1" ">= $SHOT_MIN_BYTES bytes of framebuffer" \
    "$SHOT_PNG ($on_disk bytes, $(json_read "$BRIDGE_LAST" '"\(.width)x\(.height)"'))"
}

bridge_state() {
  if bridge_request "$RUN_DIR/bridge-state.json" GET /state; then
    return 0
  fi
  _record fail "the client answered /state" "the client's own view of the world" "$BRIDGE_REASON"
  return 1
}

bridge_read()   { json_read "$RUN_DIR/bridge-state.json" "$1"; }
assert_bridge() { assert_json "$1" "$RUN_DIR/bridge-state.json" "$2" "$3"; }
assert_bridge_at_least() { assert_json_at_least "$1" "$RUN_DIR/bridge-state.json" "$2" "$3"; }

# The player's own facts, or the reason there are none.
#
# With no player in the world the whole record is {"absent": "..."} and EVERY
# path under it reads null — a value, not an absence, and the same null an
# empty hand gives. That state is world load, which is exactly when a harness
# polls, so the absence is checked before the field is read and reported in
# words instead.
bridge_player_read() { # jq-filter under .player, e.g. .status.onGround
  local why
  if why="$(json_read "$RUN_DIR/bridge-state.json" '.player.absent // empty')"; then
    printf 'the client has no player: %s' "$why"
    return 1
  fi
  json_read "$RUN_DIR/bridge-state.json" ".player$1"
}

assert_bridge_player() { # label jq-filter-under-.player expected
  local got
  got="$(bridge_player_read "$2")"
  if [ "$got" = "$3" ]; then
    _record ok "$1" "$3" "$got"
  else
    _record fail "$1" "$3" "$got"
  fi
}

# --- refusal gates ----------------------------------------------------------
require_backup_idle() {
  local out last running
  running="$(cd "$CONSUMER_DIR" && docker inspect mc-backup-local --format '{{.State.Running}}' 2>/dev/null)"
  if [ "$running" != "true" ]; then
    note "backup container is not running (state: ${running:-absent}) — it cannot touch RCON"
    return 0
  fi
  out="$(cd "$CONSUMER_DIR" && docker logs mc-backup-local --tail 20 2>&1)"
  last="$(printf '%s' "$out" | tail -1)"
  case "$last" in
    *"sleeping"*|*"waiting initial delay"*) note "backup idle: $last" ;;
    *) printf '\033[31mREFUSING TO RUN\033[0m — mc-backup-local is not idle.\n' >&2
       printf 'It drives the same RCON socket and replaces probe answers with "Saved the game".\n' >&2
       printf 'last log line: %s\n' "$last" >&2
       exit 1 ;;
  esac
}

require_mc_healthy() {
  local h; h="$(cd "$CONSUMER_DIR" && docker inspect mc --format '{{.State.Health.Status}}' 2>/dev/null)"
  if [ "$h" != "healthy" ]; then
    printf '\033[31mREFUSING TO RUN\033[0m — mc health is "%s".\n' "$h" >&2; exit 1
  fi
  note "mc healthy, started $(cd "$CONSUMER_DIR" && docker inspect mc --format '{{.State.StartedAt}}')"
}

# Asks about this player by name, so a substring of somebody else's name cannot
# answer for them and an absent player is "online": false rather than silence.
require_player_online() {
  local online
  if ! state_refresh; then
    printf '\033[31mREFUSING TO RUN\033[0m — the server would not write e2e-state.\n' >&2
    printf '%s\n' "$STATE_REASON" >&2
    exit 1
  fi
  online="$(state_read "$PLAYER_SELECT | .online")"
  if [ "$online" != "true" ]; then
    printf '\033[31mREFUSING TO RUN\033[0m — %s is not online (online: %s).\n' "$PLAYER" "$online" >&2
    printf 'Rejoin the client first: ./dev launch --dev-bridge\n' >&2
    exit 1
  fi
  note "player online: $PLAYER in $(player_dimension)"
}

# The bridge is off unless the client was launched with --dev-bridge, and a
# client sitting at the title screen answers /health but has no world to read.
# Both are refusals rather than a run that fails on its first assertion.
require_bridge() {
  local loaded
  if ! bridge_request "$RUN_DIR/bridge-health.json" GET /health; then
    printf '\033[31mREFUSING TO RUN\033[0m — no dev bridge on %s.\n' "$BRIDGE_BASE" >&2
    printf '%s\n' "$BRIDGE_REASON" >&2
    printf 'Relaunch the client with it open: ./dev launch --dev-bridge\n' >&2
    exit 1
  fi
  note "dev bridge: $(json_read "$RUN_DIR/bridge-health.json" '"\(.mod) on Minecraft \(.mc), client tick \(.tick)"')"
  # Its own file, not the one assertions read: until a script calls
  # bridge_state, assert_bridge must find nothing rather than the gate's answer.
  if ! bridge_request "$RUN_DIR/bridge-gate-state.json" GET /state; then
    printf '\033[31mREFUSING TO RUN\033[0m — the bridge would not answer /state.\n' >&2
    printf '%s\n' "$BRIDGE_REASON" >&2
    exit 1
  fi
  loaded="$(json_read "$RUN_DIR/bridge-gate-state.json" '.client.worldLoaded')"
  if [ "$loaded" != "true" ]; then
    printf '\033[31mREFUSING TO RUN\033[0m — the client has no world loaded (worldLoaded: %s).\n' "$loaded" >&2
    printf 'Join the server in the client before running this.\n' >&2
    exit 1
  fi
  note "client in world: $(json_read "$RUN_DIR/bridge-gate-state.json" '.player.dimension') at block $(json_read "$RUN_DIR/bridge-gate-state.json" '.player.blockPos | map(tostring) | join(" ")')"
}

# --- building and probing ---------------------------------------------------
# The use that actually happens. Runs the interaction manager's own
# interactBlock as the player, so vanilla's item path and every mixin on it
# fire exactly as a right-click would. Takes the BLOCK, not a look direction.
#
# There is no player on an RCON connection, so it runs through `execute as`.
# The player must be within reach: the command refuses rather than reaching
# through a wall, so move them first.
use_block() { # bx by bz [face]
  rcon "execute as $PLAYER run customdim use $1 $2 $3 ${4:-up}"
}

# Stand on the block and use its top face — the shape of every ritual click.
stand_and_use() { # bx by bz [face]
  rcon "tp $PLAYER $(awk "BEGIN{print $1 + 0.5}") $(($2 + 1)) $(awk "BEGIN{print $3 + 0.5}")" >/dev/null
  sleep 1
  use_block "$1" "$2" "$3" "${4:-up}"
}

# Wait until a position answers something other than "not loaded", or give up.
#
# A fixed sleep after a teleport is a guess, and it is wrong exactly when the
# player came from far away — every setblock then answers "position not loaded"
# and silently does nothing, which reads as a failed build rather than a race.
wait_for_chunk() { # x y z [dimension] [max-seconds]
  local dim="${4:-minecraft:overworld}" max="${5:-40}" waited=0 out
  while [ "$waited" -lt "$max" ]; do
    out="$(rcon "execute in $dim run execute if block $1 $2 $3 minecraft:air")"
    case "$out" in *"not loaded"*) : ;; *) note "chunk at $1 $2 $3 ready after ${waited}s"; return 0 ;; esac
    sleep 2
    waited=$((waited + 2))
  done
  note "chunk at $1 $2 $3 STILL not loaded after ${max}s"
  return 1
}

banner() {
  printf '================================================================\n'
  printf '%s\n' "$1"
  printf 'run dir: %s\n' "$RUN_DIR"
  printf 'player:  %s\n' "$PLAYER"
  printf 'bridge:  %s\n' "$BRIDGE_BASE"
  printf '================================================================\n'
}
