#!/usr/bin/env bash
# admin-parity-check.sh - Check the admin and Discord RCON surface on a running server.
#
# Context: template-only; smoke-test-26.yml runs it against the 26.3 smoke
# stack, and docs/design/rebuild-26/parity.md is the list it checks. Every
# RCON command discord-sync.py sends, deploy.sh's game-rule block (through
# mc_game_rule, so the version's ids), setup-permissions.sh's LuckPerms
# groups, and the presence of spark, Ledger, Essential Commands and OPaC.
# A command passes when the server parses it and answers as expected; with
# no player online, a player-targeted command passes on "No player was
# found" (it parsed, and only the target is missing). --player NAME, a player
# who is online, makes those commands run for real.
#
# Usage:
#   scripts/admin-parity-check.sh [--player NAME] [--out FILE]
#
# Gotchas:
#   - It changes the server: game rules go to deploy.sh's live values, the
#     probe name is whitelisted, opped, banned and then reversed, LuckPerms
#     groups are created, the weather clears. Run it on a throwaway server.
#   - kick, kill and "kick @a" never target --player: they would end the
#     session the join gate is measuring. They are checked against a name
#     that is not online.
#   - spark and LuckPerms reply asynchronously, after rcon-cli has returned,
#     so RCON gets nothing back; their checks read the server log instead.
#   - `execute as <name> run ...` answers nothing whether or not the player
#     is online, so the Essential Commands checks prove the parse; Essential
#     Commands' own replies go to the player, not to RCON.
#   - Every call is capped at 30 s (K6).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib.sh
source "$SCRIPT_DIR/lib.sh"

PLAYER=""
OUT=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --player)
      PLAYER="$2"
      shift 2
      ;;
    --out)
      OUT="$2"
      shift 2
      ;;
    *)
      sed -n '/^# Usage:/,/^# Gotchas:/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//' >&2
      exit 2
      ;;
  esac
done
OUT="${OUT:-admin-parity-$(mc_version).txt}"
TARGET="${PLAYER:-ParityProbe}"
ABSENT="ParityNobody"
FAILED=0
: > "$OUT"

rc() {
  timeout 30 docker exec mc rcon-cli "$1" 2>&1 | tr -d '\r' | sed 's/\x1b\[[0-9;]*m//g' | tr '\n' ' ' |
    sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//'
}

# expect <label> <command> <regex> - the reply must match the regex, and must
# never be a parse error.
expect() {
  local label="$1" cmd="$2" want="$3" reply
  reply="$(rc "$cmd" || true)"
  if printf '%s' "$reply" | grep -qE 'Unknown or incomplete command|Incorrect argument|Expected |Invalid |<--\[HERE\]|Unknown command'; then
    printf 'FAIL %-34s %s => %s\n' "$label" "$cmd" "$reply" >> "$OUT"
    FAILED=1
  elif printf '%s\n' "$reply" | grep -qE "$want"; then
    printf 'PASS %-34s %s => %s\n' "$label" "$cmd" "${reply:-<no reply>}" >> "$OUT"
  else
    printf 'FAIL %-34s %s => %s (wanted /%s/)\n' "$label" "$cmd" "${reply:-<no reply>}" "$want" >> "$OUT"
    FAILED=1
  fi
}

# logged <label> <command> <regex> - for an asynchronous reply: the server log
# must show the regex within 60 s of the command.
logged() {
  local label="$1" cmd="$2" want="$3" since i
  since="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  rc "$cmd" > /dev/null || true
  for i in $(seq 1 30); do
    if docker logs mc --since "$since" 2>&1 | grep -qE "$want"; then
      printf 'PASS %-34s %s => log /%s/\n' "$label" "$cmd" "$want" >> "$OUT"
      return 0
    fi
    sleep 2
  done
  printf 'FAIL %-34s %s => nothing in the log matching /%s/ after %ss\n' "$label" "$cmd" "$want" "$((i * 2))" >> "$OUT"
  FAILED=1
}

# A command that names a player who is not online answers with one of these.
ok_target="No player was found|No entity was found"
[[ -n "$PLAYER" ]] && ok_target="^$"
echo "# Admin parity: Minecraft $(mc_version), target ${PLAYER:-<none online>}" >> "$OUT"

echo "## discord-sync: role sync, /register, /unregister" >> "$OUT"
expect "role sync: whitelist add" "whitelist add $TARGET" "Added .* to the whitelist|already whitelisted"
expect "role sync: op" "op $TARGET" "Made .* a server operator|Nothing changed"
expect "role sync: deop" "deop $TARGET" "no longer a server operator|Nothing changed"
[[ -z "$PLAYER" ]] && expect "role sync: whitelist remove" "whitelist remove $TARGET" "Removed .* from the whitelist|not whitelisted"

echo "## discord-sync: /mc status" >> "$OUT"
expect "status: list" "list" "players online"
expect "status: chunky progress" "chunky progress" "Chunky"
expect "status: tick query (spark fallback)" "tick query" "Average time per tick"
logged "status: spark health" "spark health" "Health Report|spark.lucko.me|TPS from last"
expect "status: player position" "data get entity $TARGET Pos" "has the following entity data|$ok_target"

echo "## discord-sync: /mc admin commands" >> "$OUT"
expect "/mc say" "say [Parity] check" "^$|Parity"
expect "/mc whisper" "msg $TARGET [Parity] check" "whisper|$ok_target|^$"
expect "/mc weather" "weather clear" "Set the weather to clear"
expect "/mc tempban: kick" "kick $ABSENT Temporarily banned for 1 minutes" "No player was found"
expect "/mc tempban: ban" "ban $ABSENT Temporarily banned for 1 minutes" "Banned "
expect "/mc pardon" "pardon $ABSENT" "Unbanned "
expect "/mc heal (Essential Commands)" "execute as $TARGET run heal" "^$"
expect "/mc feed (Essential Commands)" "execute as $TARGET run feed" "^$"
expect "/mc fly (Essential Commands)" "execute as $TARGET run fly @s true" "^$"
expect "/mc invuln (Essential Commands)" "execute as $TARGET run invuln @s true" "^$"
expect "/mc extinguish (Essential Commands)" "execute as $TARGET run extinguish" "^$"
expect "/mc kill" "kill $ABSENT" "No entity was found"
expect "/mc give" "give $TARGET minecraft:bread 1" "Gave 1|$ok_target"
expect "/mc effect" "effect give $TARGET minecraft:speed 5 0" "Applied effect|$ok_target"
expect "/mc enchant (Essential Commands)" "execute as $TARGET run ec enchant minecraft:unbreaking 1" "^$|[Ee]nchant"
expect "/mc gamemode" "gamemode survival $TARGET" "^$|Set .* game mode|$ok_target"
expect "/mc xp" "experience add $TARGET 1 levels" "Gave 1 experience levels|$ok_target"
expect "/mc claims (LuckPerms, OPaC)" "lp user $TARGET permission set xaero.pac_max_claims 5" "^$|[Ss]et"
expect "/mc border: get" "worldborder get" "The world border is currently"
expect "/mc border: center" "worldborder center 0 0" "Set the center of the world border|Nothing changed"
expect "/mc restart: save" "save-all flush" "Saved the game"
expect "/mc restart: kick @a (parse only)" "kick $ABSENT Server restarting - back shortly" "No player was found"

echo "## deploy.sh: game-rule enforcement ($(mc_version) ids)" >> "$OUT"
while read -r rule value; do
  # shellcheck disable=SC2046 # mc_game_rule prints "<id> <value>"
  set -- $(mc_game_rule "$rule" "$value")
  expect "gamerule $rule" "gamerule $1 $2" "is now set to|is already set to|is currently set to"
done < <(grep -oE 'rcon "gamerule \$\(mc_game_rule [A-Za-z]+ [a-z0-9]+\)"' "$SCRIPT_DIR/deploy.sh" |
  sed -E 's/.*mc_game_rule ([A-Za-z]+) ([a-z0-9]+).*/\1 \2/' | awk '!seen[$1]++')
# shellcheck disable=SC2046 # mc_game_rule prints "<id> <value>"
set -- $(mc_game_rule logAdminCommands true)
expect "audit relay needs $1" "gamerule $1" "is currently set to true"

echo "## LuckPerms, Ledger, Essential Commands" >> "$OUT"
"$SCRIPT_DIR/setup-permissions.sh" > /dev/null 2>&1 || true
expect "LuckPerms: setup-permissions parsed" "lp creategroup admin" "^$|already exists|[Cc]reated"
expect "Ledger: command registered" "ledger status" "^$|Ledger|[Ss]tatus"
expect "Essential Commands: ec root" "ec enchant minecraft:unbreaking 1" "can only be run as a player|player is required|[Ee]nchant|^$"

PASSES="$(grep -c '^PASS' "$OUT" || true)"
FAILS="$(grep -c '^FAIL' "$OUT" || true)"
echo "## $PASSES passed, $FAILS failed" >> "$OUT"
cat "$OUT"
exit "$FAILED"
