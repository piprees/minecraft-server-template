#!/usr/bin/env bash
# rig-ready.sh - Assert the local test rig can be measured, and recover it when
# it cannot: mc healthy and answering RCON, the dev bridge up, and the client
# actually IN the world.
#
# Context: template-only, run before and after any measurement that touches the
# client. It exists because "the client is on Connection Lost" is invisible to
# every other instrument — the bridge still answers /health, the process is
# still alive, mc is still healthy, and an agent waiting for a screenshot waits
# forever. Any mc restart drops the client, so this is the normal state after
# a `./dev up`, not an exceptional one. Recovery kills the stuck JVM first
# because Prism swallows a --launch for an instance that is already running.
#
# Usage:
#   rig-ready.sh [--check] [--timeout N] [--non-interactive]
#
#   --check            report only; never launch or kill anything
#   --timeout N        seconds to wait for the world after a relaunch
#                      (default 180; a cold join loads chunks)
#   --non-interactive  no colour, machine-readable lines only
#
# Exit 0 when the rig is measurable. Exit non-zero with the reason NAMED on
# stderr — never silently, and never by hanging. Every wait is capped.
#
# Gotchas: the bridge answering /health proves the CLIENT PROCESS is up, not
# that a world is loaded — `worldLoaded` is the field that matters and it is
# false on the Connection Lost screen. RCON silence means autopause on
# production but the local profile disables autopause, so here it means down.
# Must run on macOS bash 3.2 - no mapfile, no ${var,,}, no grep -P.
set -euo pipefail

CONSUMER_DIR="${CONSUMER_DIR:-$HOME/Projects/elfydd}"
BRIDGE_PORT="${BRIDGE_PORT:-8766}"
BRIDGE="http://127.0.0.1:${BRIDGE_PORT}"
MC_CONTAINER="${MC_CONTAINER:-mc}"

CHECK_ONLY=0
TIMEOUT=180
PLAIN=0

while [ $# -gt 0 ]; do
  case "$1" in
    --check) CHECK_ONLY=1 ;;
    --timeout) shift; TIMEOUT="${1:-180}" ;;
    --non-interactive) PLAIN=1 ;;
    -h|--help) sed -n '2,26p' "$0"; exit 0 ;;
    *) printf 'rig-ready: unknown flag: %s\n' "$1" >&2; exit 2 ;;
  esac
  shift
done

ok()   { if [ "$PLAIN" -eq 1 ]; then printf 'OK    %s\n' "$1"; else printf '  \033[32mOK\033[0m    %s\n' "$1"; fi; }
bad()  { if [ "$PLAIN" -eq 1 ]; then printf 'FAIL  %s\n' "$1"; else printf '  \033[31mFAIL\033[0m  %s\n' "$1"; fi; }
note() { printf '        %s\n' "$1"; }
die()  { bad "$1"; printf 'rig-ready: NOT MEASURABLE — %s\n' "$1" >&2; exit 1; }

# --- mc -------------------------------------------------------------------
health="$(docker inspect "$MC_CONTAINER" --format '{{.State.Health.Status}}' 2>/dev/null || true)"
[ -n "$health" ] || die "the $MC_CONTAINER container does not exist"
[ "$health" = "healthy" ] || die "$MC_CONTAINER is $health, not healthy"
ok "$MC_CONTAINER healthy"

# RCON is the proof mc is actually serving, not merely running. The local
# profile disables autopause, so silence here is a real outage.
if ! docker exec -i "$MC_CONTAINER" rcon-cli "list" >/dev/null 2>&1; then
  die "$MC_CONTAINER does not answer RCON"
fi
ok "RCON answering"

# --- the bridge -----------------------------------------------------------
bridge_up() { curl -sf -m 5 "$BRIDGE/health" >/dev/null 2>&1; }
world_loaded() {
  curl -sf -m 5 "$BRIDGE/state" 2>/dev/null \
    | tr ',' '\n' | grep -q '"worldLoaded":[[:space:]]*true'
}

state_screen() {
  curl -sf -m 5 "$BRIDGE/state" 2>/dev/null \
    | tr ',' '\n' | grep '"screenTitle"' | head -1 | sed 's/.*"screenTitle":"\{0,1\}//; s/"$//'
}

relaunch() {
  # Prism swallows a --launch for an instance that is already running, so the
  # stuck JVM has to go first. Killed by main class, never by a stale pid.
  pids="$(pgrep -f 'org.prismlauncher.EntryPoint' 2>/dev/null || true)"
  if [ -n "$pids" ]; then
    note "stopping the stuck client"
    # shellcheck disable=SC2086  # deliberate word-splitting: pgrep gives a pid list
    kill -TERM $pids 2>/dev/null || true
    sleep 5
  fi
  note "launching the client"
  ( cd "$CONSUMER_DIR" && nohup ./dev launch --dev-bridge >/tmp/rig-ready-launch.log 2>&1 & ) || true
}

# Bounded poll. Never a loop whose exit condition cannot be guaranteed: the
# iteration cap is the guarantee.
wait_for_world() { # seconds
  waited=0
  step=5
  cap="$1"
  while [ "$waited" -lt "$cap" ]; do
    if bridge_up && world_loaded; then
      return 0
    fi
    sleep "$step"
    waited=$(( waited + step ))
  done
  return 1
}

if bridge_up && world_loaded; then
  ok "client in the world"
  printf 'rig-ready: MEASURABLE\n'
  exit 0
fi

# Not measurable. Say precisely why before deciding what to do about it.
if ! bridge_up; then
  reason="the dev bridge on port $BRIDGE_PORT does not answer"
else
  screen="$(state_screen)"
  reason="the client is not in a world${screen:+ (screen: $screen)}"
fi
bad "$reason"

if [ "$CHECK_ONLY" -eq 1 ]; then
  die "$reason (--check, so nothing was recovered)"
fi

note "recovering"
relaunch
if wait_for_world "$TIMEOUT"; then
  ok "client rejoined and is in the world"
  printf 'rig-ready: MEASURABLE (recovered)\n'
  exit 0
fi

die "the client did not reach a world within ${TIMEOUT}s after a relaunch — $reason"
