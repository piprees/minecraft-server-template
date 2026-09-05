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

# --- every portal effect that alters a frame -------------------------------
# Checked in the DEPLOYED config the server reads, not the overlay source: the
# two differ until `./dev refresh-config` runs, and a whole session was measured
# against configs nobody had deployed.
# A consumer-added dimension lands under overlay/dimensions/, a platform one
# under dimensions/. Look in both or an e2e dim reads as "no config".
for d in $DIMS; do
  slug="${d#*:}"
  f=""
  for cand in \
    "$CONSUMER_DIR/data/config/custom-dimensions/overlay/dimensions/$slug.json" \
    "$CONSUMER_DIR/data/config/custom-dimensions/dimensions/$slug.json"; do
    [ -f "$cand" ] && { f="$cand"; break; }
  done
  if [ -z "$f" ]; then
    say "$d: NO DEPLOYED CONFIG FOUND — it cannot have been refreshed"
    FAIL=1
    continue
  fi
  read -r particles light aura <<EOF
$(python3 -c "
import json
d = json.load(open('$f'))
p = (d.get('overrides') or d).get('portal') or {}
if isinstance(p, list):
    p = p[0] if p else {}
a = p.get('aura') or {}
print(p.get('particleType'), p.get('lightLevel'), a.get('enabled'))" 2>/dev/null)
EOF
  say "$d ($(basename "$(dirname "$(dirname "$f")")")): particles=$particles lightLevel=$light aura.enabled=$aura"
  [ "$particles" = "None" ] || { say "  REFUSED: particles land IN the opening, which is the box every crop uses"; FAIL=1; }
  [ "$light" = "0" ] || { say "  REFUSED: lightLevel $light is a light source beside the thing being measured"; FAIL=1; }
  [ "$aura" = "False" ] || { say "  REFUSED: the aura REWRITES terrain around the portal between frames"; FAIL=1; }
done

if [ "$FAIL" -ne 0 ]; then
  say "NOT QUIET — fix the refusals above before quoting a number."
  exit 1
fi
say "quiet. Time frozen at 6000, weather clear, queue idle, no particles, no portal light, no aura."
