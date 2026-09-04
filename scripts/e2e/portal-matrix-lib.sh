#!/usr/bin/env bash
# Helpers for e2e-portal-matrix.sh, kept apart so they can be self-tested.
#
# Purpose: the assertions, readers and arithmetic the portal matrix needs and
#          lib.sh does not carry. Every one of them is exercised red and green
#          by portal-matrix-selftest.sh with no Minecraft, no docker and no
#          RCON, so a helper that only ever passes cannot ship.
# Context: sourced by e2e-portal-matrix.sh AFTER lib.sh, whose json_read,
#          _record, rcon and state_read these build on. Template-only.
# Usage:   . "$(dirname "$0")/lib.sh"; . "$(dirname "$0")/portal-matrix-lib.sh"
# Gotchas: no `set -e` here either — an assertion must be able to fail without
#          aborting the run, and lib.sh's EXIT trap is what makes a partial run
#          a failure. Every wait loop has a finite cap (safety rule 10).
#          Coordinates come out of jq as WORDS, read with `read -r`, never
#          `set --`: an unquoted expansion is the same bug in a nicer hat.

# --- reading the plan -------------------------------------------------------
# BAY_INDEX names the bay every bay_read/triple call is about, so a filter is
# written once and cannot silently read a different bay than the caller means.
plan_read() { json_read "$PLAN" "$1"; }
bay_read()  { json_read "$PLAN" ".bays[$BAY_INDEX] | $1"; }

# A coordinate array as space-separated words, for `read -r x y z <<EOF`.
triple() { json_read "$PLAN" ".bays[$BAY_INDEX] | $1 | map(tostring) | join(\" \")"; }

# --- zone selectors ---------------------------------------------------------
# A zone is found by a cell of its own interior, never by where it leads.
# lib.sh's source_zone_select is the source-side half of this; an arrival zone
# also needs the WORLD, because a return arrival standing in the overworld
# legitimately names the same target world as the source zone beside it.
arrival_zone_select() { # world x y z
  printf '.zones[] | select(.kind=="arrival" and .world=="%s" and any(.interior[]; . == [%s,%s,%s]))' \
    "$1" "$2" "$3" "$4"
}

arrival_zone_count() { # world
  state_read "[.zones[] | select(.kind==\"arrival\" and .world==\"$1\")] | length"
}

# e2e-state lists zones only for worlds that are loaded right now, so an idled
# destination reports no arrival zones for a reason that is not an unlink.
# Every arrival assertion asks this first.
world_loaded() { # world
  state_read "[.dimensions[] | select(.id==\"$1\")] | length"
}

# --- arithmetic the assertions turn on --------------------------------------
# ServerWorldMixin's arrival reuse box, mirrored by ArrivalResolver: 5 blocks
# horizontally, 16 vertically. Two arrivals inside it are the same arrival.
REUSE_RADIUS_H="${REUSE_RADIUS_H:-5}"
REUSE_RADIUS_V="${REUSE_RADIUS_V:-16}"

# Each argument checked on its own: concatenating them first lets an EMPTY
# coordinate hide inside its neighbours and compare as a number.
# `json_read` answers a missing fact with a sentence, and a sentence must never
# reach awk, where it is zero.
all_integers() {
  local arg
  for arg in "$@"; do
    case "$arg" in ''|-|*[!0-9-]*) return 1 ;; esac
  done
  return 0
}

within_reuse_box() { # x y z ax ay az
  all_integers "$@" || return 2
  awk -v x="$1" -v y="$2" -v z="$3" -v ax="$4" -v ay="$5" -v az="$6" \
      -v rh="$REUSE_RADIUS_H" -v rv="$REUSE_RADIUS_V" \
      'BEGIN { dx = x - ax; dy = y - ay; dz = z - az
               if (dx < 0) dx = -dx; if (dy < 0) dy = -dy; if (dz < 0) dz = -dz
               exit !(dx <= rh && dz <= rh && dy <= rv) }'
}

# Did a return trip land on the source frame's own column? The frame is at most
# a few blocks across, so this is a tolerance and not an equality.
near_column() { # x z centre-x centre-z tolerance
  all_integers "$1" "$2" "$3" "$4" || return 2
  awk -v x="$1" -v z="$2" -v cx="$3" -v cz="$4" -v tol="$5" \
      'BEGIN { dx = x - cx; dz = z - cz
               if (dx < 0) dx = -dx; if (dz < 0) dz = -dz
               exit !(dx <= tol && dz <= tol) }'
}

# --- assertions -------------------------------------------------------------
# An item predicate against a live inventory slot. Same three-outcome shape as
# lib.sh's assert_block: passed, failed, or an answer that is neither — which
# is a predicate this game did not understand, and must never read as a fail.
assert_items() { # label predicate want(present|absent)
  local out want_txt
  out="$(rcon "execute if items entity $PLAYER weapon.mainhand $2" | tr -d '\r' | tr '\n' ' ')"
  [ "$3" = "present" ] && want_txt="Test passed" || want_txt="Test failed"
  case "$out" in
    *"Test passed"*|*"Test failed"*)
      case "$out" in
        *"$want_txt"*) _record ok   "$1" "$want_txt ($2)" "$out" ;;
        *)             _record fail "$1" "$want_txt ($2)" "$out" ;;
      esac ;;
    *)
      _record fail "$1" "$want_txt ($2)" \
        "the server did not answer with a verdict: $out" ;;
  esac
}

assert_at_most() { # label file pattern n
  local got
  if [ ! -f "$2" ]; then _record fail "$1" "a readable file at $2" "no such file"; return; fi
  got="$(grep -c -- "$3" "$2" 2>/dev/null || true)"; [ -z "$got" ] && got=0
  if [ "$got" -le "$4" ]; then
    _record ok   "$1" "<= $4 lines matching '$3'" "$got"
  else
    _record fail "$1" "<= $4 lines matching '$3'" "$got — $(grep -m1 -- "$3" "$2")"
  fi
}

# --- waiting ----------------------------------------------------------------
POLL_SECONDS="${POLL_SECONDS:-90}"
# A zero step is an unbounded loop wearing a cap, which is the one thing a
# wait here may never be (safety rule 10).
POLL_STEP="${POLL_STEP:-3}"
case "$POLL_STEP" in ''|*[!0-9]*|0) POLL_STEP=1 ;; esac

# Poll the SERVER's own view until the player is in a world, or give up. The
# client is not involved: a tp into the opening is what crosses, and the
# dimension after it is the oracle. DIM_NOTE carries the reason out, so a
# failure says what world the player was actually in.
DIM_NOTE=""
DIM_SECONDS=0
# shellcheck disable=SC2034  # DIM_NOTE and DIM_SECONDS are read by the caller
wait_for_dimension() { # target-dimension [max-seconds]
  local target="$1" max="${2:-$POLL_SECONDS}" waited=0 dim="unread"
  DIM_NOTE=""
  DIM_SECONDS=0
  while [ "$waited" -le "$max" ]; do
    if ! state_refresh; then
      DIM_NOTE="the server would not write e2e-state after ${waited}s: $STATE_REASON"
      DIM_SECONDS="$waited"
      return 1
    fi
    dim="$(player_dimension)"
    if [ "$dim" = "$target" ]; then
      DIM_SECONDS="$waited"
      DIM_NOTE="reached $target after ${waited}s"
      return 0
    fi
    sleep "$POLL_STEP"
    waited=$((waited + POLL_STEP))
  done
  DIM_SECONDS="$waited"
  DIM_NOTE="still in $dim after ${max}s, wanted $target"
  return 1
}

# Poll until a jq filter over e2e-state reads an expected value, or give up.
# The value it settled on comes back in SETTLED, so a failure reports what was
# there rather than "not yet".
SETTLED=""
wait_for_state() { # jq-filter expected [max-seconds]
  local want="$2" max="${3:-$POLL_SECONDS}" waited=0
  SETTLED=""
  while [ "$waited" -le "$max" ]; do
    if ! state_refresh; then SETTLED="$STATE_REASON"; return 1; fi
    SETTLED="$(state_read "$1")"
    [ "$SETTLED" = "$want" ] && return 0
    sleep "$POLL_STEP"
    waited=$((waited + POLL_STEP))
  done
  return 1
}

# --- the server log, snapshotted between two marks ---------------------------
# Never streamed, never a filter over a follow (safety rules 10-11).
LOG_MARK=0
log_mark() {
  LOG_MARK="$(cd "$CONSUMER_DIR" && docker logs mc 2>&1 | wc -l | tr -d ' ')"
}
log_since() { # outfile
  (cd "$CONSUMER_DIR" && docker logs mc 2>&1 | tail -n "+$((LOG_MARK + 1))") > "$1" 2>&1 || true
}

# How many records portal_links.json holds. A count is the assertion; the
# individual records are the mod's business.
links_records() {
  local f="$CONSUMER_DIR/data/config/portal_links.json"
  [ -f "$f" ] || { printf '0'; return; }
  jq -r 'length' "$f" 2>/dev/null || printf '0'
}

# --- building ---------------------------------------------------------------
# setblock every coordinate in a file of "x y z" lines, then verify each one.
# One assertion for the whole set with BOTH counts printed, so "0 wrong" can
# never come from "0 examined" (TROUBLESHOOTING.md#t63).
place_cells() { # label cellfile block
  local placed=0 wrong=0 total=0 out first=""
  if [ ! -f "$2" ]; then
    _record fail "$1" "a cell list at $2" "no such file"
    return
  fi
  while read -r cx cy cz; do
    [ -z "$cx" ] && continue
    total=$((total + 1))
    rcon "execute in minecraft:overworld run setblock $cx $cy $cz $3" >/dev/null
  done < "$2"
  while read -r cx cy cz; do
    [ -z "$cx" ] && continue
    out="$(rcon "execute in minecraft:overworld run execute if block $cx $cy $cz $3" | tr -d '\r\n')"
    case "$out" in
      *"Test passed"*) placed=$((placed + 1)) ;;
      *) wrong=$((wrong + 1)); [ -z "$first" ] && first="$cx $cy $cz -> $out" ;;
    esac
  done < "$2"
  if [ "$total" -eq 0 ]; then
    _record fail "$1" "at least one cell to place" "the cell list was empty"
  elif [ "$wrong" -eq 0 ]; then
    _record ok "$1" "$total of $total cells are $3" "$placed placed and verified"
  else
    _record fail "$1" "$total of $total cells are $3" \
      "$wrong wrong out of $total, first: $first"
  fi
}
