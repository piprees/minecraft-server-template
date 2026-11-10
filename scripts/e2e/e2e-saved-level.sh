#!/usr/bin/env bash
# e2e-saved-level.sh - A player who left in a lazy level comes back there (26.x).
#
# Purpose: proves the login path for a player saved in a lazy level that is
#          not open when they return, after a server restart or an idle unload:
#          the mod opens the saved level before the spawn task places the
#          player, who arrives in that level at the saved position, never at
#          the same coordinates in the overworld. Three phases, run in order:
#            leave   (inside a vanilla-join.sh --during) sends the player to a
#                    known spot in SLUG and records it in SAVED_FILE;
#            unload  (no client) unloads SLUG and waits until it reads lazy;
#                    a server restart in its place covers the restart case;
#            back    (inside a second vanilla-join.sh --during) asserts the
#                    player is in SLUG at the recorded spot and that the mod
#                    logged opening it for them;
#            gone    (in place of back, after SLUG's definition is removed
#                    and mc restarted) asserts the player is at the world
#                    spawn in the overworld, alive, and the mod logged why.
# Context: template-only, 26.x, a linked consumer holding scripts/e2e/rig-26/
#          (README § On 26.x) with JVM_DD_OPTS='customdimensions.e2e=true'.
#          Facts are e2e-state's and the server log's.
# Usage:   MC_VERSION_OVERRIDE=26.3 scripts/e2e/vanilla-join.sh --name Relog --hold 10 \
#            --out /tmp/relog-1 --during "PHASE=leave PLAYER=Relog CONSUMER_DIR=$PWD scripts/e2e/e2e-saved-level.sh"
#          (cd "$CONSUMER_DIR" && ./dev restart mc)      # the restart case, or:
#          PHASE=unload PLAYER=Relog CONSUMER_DIR=$PWD scripts/e2e/e2e-saved-level.sh   # the idle-unload case
#          MC_VERSION_OVERRIDE=26.3 scripts/e2e/vanilla-join.sh --name Relog --hold 10 \
#            --out /tmp/relog-2 --during "PHASE=back PLAYER=Relog CONSUMER_DIR=$PWD scripts/e2e/e2e-saved-level.sh"
#          SLUG (default e2e_two) is the lazy level; SAVED_FILE (default
#          $CONSUMER_DIR/.seed-rolling/saved-level.json) carries the spot from
#          leave to back.
# Gotchas: lib.sh wants PLAYER in every phase (unload names the one who left).
#          The back phase reads the log from the last leave or unload, so a
#          restart case needs a fresh leave. The player must be offline for
#          unload, and the level must have no
#          other player in it. The back phase reads e2e-state as soon as the
#          client has joined, before any command moves the player.
#          Exit is non-zero on any miss (lib.sh's EXIT trap).

# shellcheck disable=SC2034  # lib.sh reads it to name the run dir
SCRIPT_NAME="saved-level"
# shellcheck source=lib.sh
. "$(cd "$(dirname "$0")" && pwd)/lib.sh"
# shellcheck source=rig-frames-lib.sh
. "$(cd "$(dirname "$0")" && pwd)/rig-frames-lib.sh"
# shellcheck source=portal-matrix-lib.sh
. "$(cd "$(dirname "$0")" && pwd)/portal-matrix-lib.sh"

PHASE="${PHASE:-}"
SLUG="${SLUG:-e2e_two}"
SAVED_FILE="${SAVED_FILE:-$CONSUMER_DIR/.seed-rolling/saved-level.json}"
POLL_SECONDS="${POLL_SECONDS:-60}"
POLL_STEP="${POLL_STEP:-3}"
SPOT_X=6.5
SPOT_Y=-59
SPOT_Z=9.5

banner "e2e-saved-level — back in the lazy level the player left in ($PHASE)"
[ "$E2E_26" -eq 1 ] || { note "26.x only"; finish; exit 3; }
require_mc_healthy
DIM="$RIG_NS:$SLUG"

# RCON joins the reply's lines with nothing between them, so the slug is matched on its own.
slug_state() { rcon "customdim list" | grep -oE "$SLUG: [a-z]+" | sed 's/.*: //' | head -1; }

case "$PHASE" in
  leave)
    require_player_online
    say "Send the player into $DIM"
    rig_open "$SLUG" || _record fail "$SLUG opens" open "not open after the cap"
    rcon "execute in $DIM run setblock 6 -60 9 minecraft:white_concrete" >/dev/null
    rcon "execute in $DIM run tp $PLAYER $SPOT_X $SPOT_Y $SPOT_Z 90 0" >/dev/null
    wait_for_dimension "$DIM" 30 >/dev/null || note "did not reach $DIM: $DIM_NOTE"
    sleep 2
    state_refresh
    assert_player_dimension "the player is in $DIM" "$DIM"
    pos="$(state_read "$PLAYER_SELECT | .pos | map(. * 10 | round / 10) | join(\",\")")"
    report_metric "saved position" "$pos"
    printf '{"player":"%s","dimension":"%s","pos":[%s],"since":"%s"}\n' "$PLAYER" "$DIM" "$pos" \
      "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$SAVED_FILE"
    note "recorded in $SAVED_FILE; let the client leave, then unload $SLUG or restart mc"
    ;;
  unload)
    say "Unload $DIM with nobody in it"
    rcon "customdim unload $SLUG" >/dev/null
    waited=0
    while [ "$waited" -lt 60 ]; do
      [ "$(slug_state)" = lazy ] && break
      sleep 3
      waited=$((waited + 3))
    done
    st="$(slug_state)"
    if [ "$st" = lazy ]; then _record ok "$SLUG reads lazy" lazy "$st"; else _record fail "$SLUG reads lazy" lazy "${st:-unknown}"; fi
    if [ -f "$SAVED_FILE" ]; then
      jq --arg t "$(date -u +%Y-%m-%dT%H:%M:%SZ)" '.since = $t' "$SAVED_FILE" > "$SAVED_FILE.tmp" && mv "$SAVED_FILE.tmp" "$SAVED_FILE"
    fi
    ;;
  back)
    require_player_online
    [ -f "$SAVED_FILE" ] || { note "no $SAVED_FILE: run PHASE=leave first"; finish; exit 2; }
    say "The player is back in $DIM"
    state_refresh
    assert_player_dimension "the player rejoined in $DIM" "$(jq -r .dimension "$SAVED_FILE")"
    want="$(jq -c .pos "$SAVED_FILE")"
    assert_state "the player stands at the saved position (within a block)" \
      "($PLAYER_SELECT | .pos) as \$p | $want as \$w | [range(3)] | all(((\$p[.] - \$w[.]) | fabs) < 1)" true
    report_metric "position on rejoin" "$(state_read "$PLAYER_SELECT | .pos | map(. * 10 | round / 10) | join(\",\")")"
    lines="$RUN_DIR/login-lines.log"
    since="$(jq -r '.since // "10m"' "$SAVED_FILE")"
    docker logs mc --since "$since" 2>&1 | grep -E "login: " > "$lines" || true
    if grep -q "login: opening $SLUG for $PLAYER" "$lines"; then
      _record ok "the mod opened $SLUG for $PLAYER before placing them" opened "$(grep "login: opening $SLUG for $PLAYER" "$lines" | tail -1 | cut -c1-120)"
    else
      _record fail "the mod opened $SLUG for $PLAYER before placing them" opened "no 'login: opening $SLUG for $PLAYER' line since $since"
    fi
    if grep -q "login: $PLAYER was saved in" "$lines"; then
      _record fail "the player was not sent to the world spawn" none "$(grep "login: $PLAYER was saved in" "$lines" | tail -1 | cut -c1-120)"
    else
      _record ok "the player was not sent to the world spawn" none none
    fi
    ;;
  gone)
    require_player_online
    [ -f "$SAVED_FILE" ] || { note "no $SAVED_FILE: run PHASE=leave first"; finish; exit 2; }
    say "The player's level is gone: they are at the world spawn, alive"
    state_refresh
    assert_player_dimension "the player rejoined in the overworld" "minecraft:overworld"
    want="$(jq -c .pos "$SAVED_FILE")"
    assert_state "the player is not at the saved coordinates" \
      "($PLAYER_SELECT | .pos) as \$p | $want as \$w | [range(3)] | any(((\$p[.] - \$w[.]) | fabs) > 4)" true
    health="$(rcon "data get entity $PLAYER Health" | grep -oE '[0-9.]+f$' | tr -d f)"
    if awk -v h="${health:-0}" 'BEGIN { exit !(h + 0 > 0) }'; then
      _record ok "the player is alive" "> 0" "$health"
    else
      _record fail "the player is alive" "> 0" "${health:-unread}"
    fi
    report_metric "position on rejoin" "$(state_read "$PLAYER_SELECT | .pos | map(. * 10 | round / 10) | join(\",\")")"
    since="$(jq -r '.since // "10m"' "$SAVED_FILE")"
    lines="$RUN_DIR/login-lines.log"
    docker logs mc --since "$since" 2>&1 | grep -E "login: " > "$lines" || true
    assert_file_contains "the mod placed them at the world spawn" "$lines" "login: $PLAYER was saved in $DIM, which is not open"
    ;;
  *) note "PHASE is leave, unload, back or gone, not '$PHASE'"; finish; exit 2 ;;
esac

finish
