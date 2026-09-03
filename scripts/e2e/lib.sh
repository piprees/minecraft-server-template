#!/usr/bin/env bash
# Shared harness for the C0 end-to-end retest scripts.
#
# Purpose: one RCON helper, one assertion form, one refusal gate, one summary.
# Context: sourced by e2e-item*.sh. Drives Pip's real client — never a bot.
# Usage:   source "$(dirname "$0")/lib.sh"; then assert_*, shot, finish.
# WALKING: a walk STOPS DEAD on a one-block rise. Vanilla step height is 0.6,
#          so a player auto-steps a slab and not a block. Any test that walks a
#          distance must FLATTEN ITS LANE (build-test-frame.sh does) — a short
#          walk that "worked once" proves the ground was level there, not that
#          walking works. Use walk_forward(), never a bare `dev input hold w`.
# Gotchas: no `set -e` here on purpose — an assertion must be able to fail
#          without aborting the run. A partial run is forced to FAIL by the
#          EXIT trap, so dying early can never look like a pass.

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

assert_contains() { # label command expected-substring
  local out; out="$(rcon "$2" | tr -d '\r' | tr '\n' ' ')"
  case "$out" in
    *"$3"*) _record ok   "$1" "contains '$3'" "$out" ;;
    *)      _record fail "$1" "contains '$3'" "$out" ;;
  esac
}

assert_not_contains() { # label command unexpected-substring
  local out; out="$(rcon "$2" | tr -d '\r' | tr '\n' ' ')"
  case "$out" in
    *"$3"*) _record fail "$1" "does NOT contain '$3'" "$out" ;;
    *)      _record ok   "$1" "does NOT contain '$3'" "$out" ;;
  esac
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

report_metric() { # label value note
  printf '  \033[36mMETRIC\033[0m %s = %s\n' "$1" "$2"
  [ -n "${3:-}" ] && printf '        %s\n' "$3"
  printf 'metric\t%s\t%s\n' "$1" "$2" >> "$RUN_DIR/assertions.tsv"
}

# A screenshot IS the evidence here, so a silently-failed capture must fail the
# run rather than leaving an empty file nobody looks at.
assert_shot() { # label name
  local out="$RUN_DIR/$2.png" size
  (cd "$CONSUMER_DIR" && ./dev screenshot --out "$out" >/dev/null 2>&1 </dev/null) || true
  if [ ! -f "$out" ]; then
    _record fail "$1" "a PNG at $out" "no file written"; return
  fi
  size="$(wc -c < "$out" | tr -d ' ')"
  if [ "$size" -lt 1024 ]; then
    _record fail "$1" "a PNG larger than 1KB" "$size bytes — capture almost certainly failed"
  else
    _record ok "$1" "a PNG larger than 1KB" "$out ($size bytes)"
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

require_player_online() {
  local out; out="$(rcon 'list' | tr -d '\r' | tr '\n' ' ')"
  case "$out" in
    *"$PLAYER"*) note "player online: $out" ;;
    *) printf '\033[31mREFUSING TO RUN\033[0m — %s is not online.\n' "$PLAYER" >&2
       printf 'list answered: %s\n' "$out" >&2
       printf 'Rejoin the client first (see RETEST.md "When the client is disconnected").\n' >&2
       exit 1 ;;
  esac
}

# --- the client -------------------------------------------------------------
client_pid() {
  local slug pid
  slug="$(grep -E '^BRAND_SLUG=' "$CONSUMER_DIR/.env" 2>/dev/null | head -1 | cut -d= -f2- | tr -d "'\"" )"
  for pid in $(pgrep -f 'org.prismlauncher.EntryPoint' 2>/dev/null); do
    if [ -z "$slug" ]; then echo "$pid"; return 0; fi
    if ps -p "$pid" -o command= 2>/dev/null | grep -q -- "$slug"; then echo "$pid"; return 0; fi
  done
  return 1
}

# Screen point at the centre of the client window. The pointer only picks the
# window — it never aims. Aiming is the tp.
window_centre() {
  local pid bounds px py sw sh
  pid="$(client_pid)" || { echo ""; return 1; }
  bounds="$(osascript -e "tell application \"System Events\" to tell (first process whose unix id is $pid) to get {position, size} of window 1" 2>/dev/null)"
  [ -z "$bounds" ] && { echo ""; return 1; }
  px="$(echo "$bounds" | cut -d, -f1 | tr -d ' ')"
  py="$(echo "$bounds" | cut -d, -f2 | tr -d ' ')"
  sw="$(echo "$bounds" | cut -d, -f3 | tr -d ' ')"
  sh="$(echo "$bounds" | cut -d, -f4 | tr -d ' ')"
  echo "$((px + sw / 2)) $((py + sh / 2))"
}

dev() { (cd "$CONSUMER_DIR" && ./dev "$@" </dev/null); }

shot() { # name
  local out="$RUN_DIR/$1.png"
  (cd "$CONSUMER_DIR" && ./dev screenshot --out "$out" >/dev/null 2>&1) || true
  if [ -f "$out" ]; then note "screenshot: $out"; else note "screenshot FAILED: $1"; fi
}

# Aim over RCON, then trigger with a click at the window centre.
#
# THIS DOES NOT WORK ON THIS MACHINE AND THE CALLER MUST VERIFY THE OUTCOME.
# Measured: a torch in hand, aimed at a clear face, right-clicked, places
# nothing; the same through a keyboard key with `key.use` rebound to it places
# nothing either. What DOES reach the game is the discrete key callback — esc
# opens the menu, e opens the inventory, chat sends. What does not is anything
# Minecraft POLLS while held: movement, attack, use. One limit, both symptoms.
#
# So never treat a call to this as an action performed. Probe for the result and
# report a refusal, the way the socket loop in e2e-item2-stronghold.sh does.
aim_and_use() { # x y z yaw pitch label
  local centre
  rcon "tp $PLAYER $1 $2 $3 $4 $5" >/dev/null
  sleep 1
  centre="$(window_centre)"
  if [ -z "$centre" ]; then
    _record fail "${6:-use}" "client window bounds readable" "no client window found — is the game running?"
    return 1
  fi
  dev input focus >/dev/null 2>&1
  sleep 1
  # shellcheck disable=SC2086
  dev input rightclick $centre >/dev/null 2>&1
  sleep 1
}

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

# Stand on the block and use its top face — the shape of every ritual click.
stand_and_use() { # bx by bz [face]
  rcon "tp $PLAYER $(awk "BEGIN{print $1 + 0.5}") $(($2 + 1)) $(awk "BEGIN{print $3 + 0.5}")" >/dev/null
  sleep 1
  use_block "$1" "$2" "$3" "${4:-up}"
}

# Walk the player forward in bounded steps, checking between each.
#
# Two findings are baked in, both from a run that failed for neither reason
# anyone guessed:
#
#  1. A synthetic key-down longer than a few seconds is dropped, so a single
#     `hold w 12` walks about 10 blocks rather than 43 and the player never
#     arrives. Steps of 4s, re-checked between.
#  2. A walk stops dead on a one-block rise. Flatten the lane.
#
# The `key space` between steps is BELT AND BRACES ONLY and does not clear a
# one-block rise by itself: you have to be moving when you jump, and
# `./dev input hold` takes a single key, so w and space cannot be held
# together. Flattening the lane is the real defence; this only helps shake the
# player loose when they are already wedged.
walk_forward() { # target-z max-steps [stop-when-dimension-contains]
  local target="$1" max="$2" stop_dim="${3:-}" used=0 dim z start_z
  dev input focus >/dev/null 2>&1
  sleep 1
  start_z="$(rcon "data get entity $PLAYER Pos" | sed 's/.*, //; s/d\]//' | cut -d. -f1)"
  while [ "$used" -lt "$max" ]; do
    dev input hold w 4 >/dev/null 2>&1
    dev input key space >/dev/null 2>&1
    sleep 1
    # A held key does not reach the game on every machine — macOS reconciles
    # synthetic key state against the real keyboard, so `hold` reports success
    # and the player never moves. Two steps of exactly no movement is that,
    # not a rise, so approach by teleport instead. The player still ENTERS the
    # portal on their own feet, which is what the crossing needs.
    if [ "$used" -ge 2 ]; then
      z="$(rcon "data get entity $PLAYER Pos" | sed 's/.*, //; s/d\]//' | cut -d. -f1)"
      if [ "$z" = "$start_z" ]; then
        note "walk: held keys move nobody on this machine — approaching by teleport"
        approach_by_tp "$target" "$stop_dim"
        return $?
      fi
    fi
    if [ -n "$stop_dim" ]; then
      dim="$(rcon "execute as $PLAYER run data get entity @s Dimension" | tr -d '\r')"
      case "$dim" in *"$stop_dim"*) used=$((used + 1)); note "walk: crossed after $used step(s)"; return 0 ;; esac
    fi
    z="$(rcon "data get entity $PLAYER Pos" | sed 's/.*, //; s/d\]//' | cut -d. -f1)"
    used=$((used + 1))
    if [ -n "$z" ] && [ "$z" -le "$target" ] 2>/dev/null; then
      note "walk: reached z=$z (target <= $target) after $used step(s)"; return 0
    fi
    note "walk: step $used, z=${z:-unknown}"
  done
  note "walk: used all $max steps without reaching z<=$target — lane may not be flat"
  return 1
}

# The approach a held key cannot make: 4 blocks at a time along -Z, keeping the
# player's own X and Y, until they are standing in the frame. Every step is a
# real position the server accepts, so contact with the portal happens the same
# way it does on foot — what this cannot prove is that the lane is walkable.
approach_by_tp() { # target-z [stop-when-dimension-contains]
  local target="$1" stop_dim="${2:-}" pos x y z steps=0 last_z=""
  while [ "$steps" -lt 30 ]; do
    pos="$(rcon "data get entity $PLAYER Pos" | sed 's/.*\[//; s/\]//; s/d//g')"
    x="$(echo "$pos" | cut -d, -f1)"
    y="$(echo "$pos" | cut -d, -f2 | tr -d ' ')"
    z="$(echo "$pos" | cut -d, -f3 | tr -d ' ')"
    case "$z" in '' | *[!0-9.-]*) note "approach: no position to read"; return 1 ;; esac
    # With a dimension to reach, the target is not the destination — the portal
    # is a few blocks past it, and stopping short measures a crossing that
    # never happened. Keep going, one block at a time once close, until the
    # dimension changes.
    if [ -z "$stop_dim" ] && [ "${z%.*}" -le "$target" ] 2>/dev/null; then
      note "approach: reached z=${z%.*} (target <= $target) after $steps step(s)"
      return 0
    fi
    if [ -n "$stop_dim" ] && [ "${z%.*}" -le "$((target - 8))" ] 2>/dev/null; then
      note "approach: passed z=${z%.*} without crossing — the portal is not on this line"
      return 1
    fi
    # Ground the teleport cannot pass is ground a walk could not have passed
    # either: the server ejects an entity dropped inside blocks and the step
    # undoes itself. When APPROACH_CELL names the portal's own interior, take
    # it — the crossing is what is being measured, not the terrain in front of
    # it. Say so, because a result that used it has not walked anywhere.
    if [ "${z%.*}" = "$last_z" ] && [ -n "${APPROACH_CELL:-}" ]; then
      note "approach: blocked at z=${z%.*} — entering the frame directly ($APPROACH_CELL)"
      note "approach: THE LANE IS UNPROVEN — this measures the crossing, not the walk"
      rcon "tp $PLAYER $APPROACH_CELL" >/dev/null
      sleep 3
      if [ -n "$stop_dim" ]; then
        dim="$(rcon "execute as $PLAYER run data get entity @s Dimension" | tr -d '\r')"
        case "$dim" in *"$stop_dim"*) note "approach: crossed on contact"; return 0 ;; esac
        note "approach: stood in the frame and did not cross"
        return 1
      fi
      return 0
    fi
    last_z="${z%.*}"
    if [ "${z%.*}" -le "$((target + 4))" ] 2>/dev/null; then
      rcon "tp $PLAYER $x $y $(awk "BEGIN{print $z - 1}")" >/dev/null
    else
      rcon "tp $PLAYER $x $y $(awk "BEGIN{print $z - 4}")" >/dev/null
    fi
    steps=$((steps + 1))
    sleep 2
    if [ -n "$stop_dim" ]; then
      dim="$(rcon "execute as $PLAYER run data get entity @s Dimension" | tr -d '\r')"
      case "$dim" in *"$stop_dim"*) note "approach: crossed after $steps step(s)"; return 0 ;; esac
    fi
  done
  note "approach: 30 steps without arriving"
  return 1
}

# A step only a human can judge. Never silently skipped.
pause_for_human() { # instruction
  printf '\n\033[33mHUMAN STEP\033[0m — do this, then press Enter:\n  %s\n' "$1"
  if [ -t 0 ]; then
    read -r _
  else
    _record fail "human step" "a person at the keyboard" "no TTY on stdin — cannot prompt, so this step did NOT happen"
  fi
}

banner() {
  printf '================================================================\n'
  printf '%s\n' "$1"
  printf 'run dir: %s\n' "$RUN_DIR"
  printf '================================================================\n'
}
