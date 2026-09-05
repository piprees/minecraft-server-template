#!/usr/bin/env bash
# rig-quiet.sh - Put the rig into a state where a measurement means something.
#
# Purpose: every confound that has silently changed a reading here, removed and
#          then ASSERTED. A gate that only warns is a gate that gets ignored.
# Context: template-only. Called at the top of a measurement run; safe to run
#          twice. It changes world state (time, weather, gamerules), so it is
#          for the local rig and refuses to touch anything else.
# Usage:   rig-quiet.sh [--dim DIM ...]   (repeatable; defaults to the rig pair)
# Gotchas: portal particles are per-dimension config, not a gamerule — the
#          overworld ships `minecraft:cloud` and it lands IN the opening, which
#          is the box most measurements crop. This reports it and cannot fix it
#          without a config change plus `./dev refresh-config`.
#          Hiding the HUD is NOT possible from the bridge yet: DevBridge.binding
#          exposes vanilla `client.options.*Key` only, and hide-GUI lives in
#          Keyboard.onKey. `options.hudHidden` needs a bridge command.
set -euo pipefail

CONSUMER_DIR="${CONSUMER_DIR:-$HOME/Projects/elfydd}"
DIMS=""
while [ $# -gt 0 ]; do
  case "$1" in
    --dim) DIMS="$DIMS $2"; shift 2;;
    *) echo "unknown argument: $1" >&2; exit 2;;
  esac
done
[ -n "$DIMS" ] || DIMS="minecraft:overworld adventure:the_amplified_reaches"

say() { printf '[rig-quiet] %s\n' "$1"; }
rcon() { docker exec -i mc rcon-cli "$1" 2>&1 | tr -d '\r'; }

FAIL=0

# --- the sun must not move, and it must not rain ---------------------------
rcon "gamerule doDaylightCycle false" >/dev/null
rcon "time set 6000" >/dev/null
rcon "gamerule doWeatherCycle false" >/dev/null
rcon "weather clear" >/dev/null
for d in $DIMS; do
  t="$(rcon "execute in $d run time query daytime" | sed -n 's/.*is \([0-9]*\).*/\1/p')"
  say "$d daytime=$t"
  if [ "${t:-x}" != "6000" ]; then
    say "  REFUSED: $d reads $t, not 6000 — a moving sun changes every luma reading"
    FAIL=1
  fi
done

# --- the render queue must be idle -----------------------------------------
PENDING="$(curl -sf -m 8 http://127.0.0.1:8765/pipeline-status 2>/dev/null \
  | sed -n 's/.*"render_pending": *\([0-9]*\).*/\1/p')"
if [ -n "${PENDING:-}" ] && [ "$PENDING" -gt 0 ] 2>/dev/null; then
  say "render_pending=$PENDING — pausing HIGHRES"
  curl -sf -m 10 -X POST http://127.0.0.1:8765/render/high/pause >/dev/null 2>&1 || true
fi
CPU="$(docker stats mc --no-stream --format '{{.CPUPerc}}' 2>/dev/null || echo unknown)"
say "mc CPU=$CPU  render_pending=${PENDING:-0}"

# --- nothing of ours may be standing in frame ------------------------------
BOTS="$(rcon 'list' | tail -1)"
say "players: $BOTS"
case "$BOTS" in
  *bot*|*Bot*) say "  WARNING: a bot is online and may be in frame";;
esac

# --- portal particles land IN the opening ----------------------------------
# Per-dimension config, so this can only report. `minecraft:cloud` on the
# overworld portal puts smoke exactly where the crop is.
for d in $DIMS; do
  slug="${d#*:}"
  f="$CONSUMER_DIR/data/config/custom-dimensions/dimensions/$slug.json"
  [ -f "$f" ] || continue
  p="$(python3 -c "
import json,sys
try: print(json.load(open('$f')).get('portal',{}).get('particleType'))
except Exception: print('unreadable')" 2>/dev/null)"
  if [ "$p" = "None" ] || [ -z "$p" ]; then
    say "$d portal particles: OFF"
  else
    say "$d portal particles: $p  <- lands IN the opening; measurements will swing"
    say "  fix: overlay/config/custom-dimensions/dimensions/$slug.json"
    say '        {"overrides": {"portal": {"particleType": null}}}'
    say "  then ./dev refresh-config && ./dev up"
    FAIL=1
  fi
done

if [ "$FAIL" -ne 0 ]; then
  say "NOT QUIET — fix the refusals above before quoting a number."
  exit 1
fi
say "quiet. Time frozen at 6000, weather clear, queue idle, no portal particles."
