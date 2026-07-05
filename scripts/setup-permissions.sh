#!/usr/bin/env bash
# setup-permissions.sh - Configure LuckPerms groups and permissions via RCON.
#
# Idempotent: LuckPerms silently accepts duplicate group/permission definitions.
# Called by deploy.sh after the server is healthy. Can also be run standalone.
#
# Two groups:
#   player - social and info commands only (no teleportation)
#   admin  - inherits player + teleportation, cheats, server management
#
# The 'default' LuckPerms group inherits 'player', so new players get safe
# commands automatically. OPs bypass all permission checks regardless.
#
# COMMANDS.md documents what each group can do - update it when changing
# permissions here.
set -euo pipefail

RCON_CMD="docker exec mc rcon-cli"

rcon() {
  $RCON_CMD "$1" > /dev/null 2>&1 || true
}

echo "==> Setting up LuckPerms groups and permissions..."

# --- create groups ------------------------------------------------------------
rcon "lp creategroup player"
rcon "lp creategroup admin"

# --- player group: social and info commands only ------------------------------
for perm in \
  essentialcommands.nick \
  essentialcommands.nick.reveal \
  essentialcommands.afk \
  essentialcommands.gametime \
  essentialcommands.rules \
; do
  rcon "lp group player permission set $perm true"
done

# Waystones - all players can use discovered waystones (in-world interaction, not a command)
rcon "lp group player permission set waystones.waystone true"

# --- admin group: inherits player + everything else ---------------------------
rcon "lp group admin parent add player"

for perm in \
  essentialcommands.near \
  essentialcommands.home \
  essentialcommands.home.set \
  essentialcommands.home.delete \
  essentialcommands.home.tp \
  essentialcommands.tpa \
  essentialcommands.tpa.here \
  essentialcommands.tpa.accept \
  essentialcommands.tpa.deny \
  essentialcommands.spawn \
  essentialcommands.back \
  essentialcommands.rtp \
  essentialcommands.warp.tp \
  essentialcommands.warp.set \
  essentialcommands.warp.delete \
  essentialcommands.fly \
  essentialcommands.heal \
  essentialcommands.feed \
  essentialcommands.repair \
  essentialcommands.enderchest \
  essentialcommands.workbench \
  essentialcommands.top \
  essentialcommands.invuln \
  essentialcommands.extinguish \
  essentialcommands.suicide \
  essentialcommands.wastebin \
  essentialcommands.anvil \
  essentialcommands.day \
  essentialcommands.night \
  essentialcommands.delete_all_player_data \
; do
  rcon "lp group admin permission set $perm true"
done

# --- default group inherits player --------------------------------------------
rcon "lp group default parent add player"

# --- assign admin group to OPS ------------------------------------------------
IFS=',' read -ra OPS_ARRAY <<< "${OPS:-}"
for op in "${OPS_ARRAY[@]}"; do
  op="$(echo "$op" | xargs)"
  [[ -n "$op" ]] && rcon "lp user $op parent add admin"
done

echo "  ✓ Groups: default > player (social/info only), admin (full access)"
echo "  ✓ OPs assigned to admin group"
