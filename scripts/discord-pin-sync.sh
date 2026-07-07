#!/usr/bin/env bash
# discord-pin-sync.sh - Keep the Discord #general welcome pin in sync with
# the "discord.welcome_pin" key in config/messages.json.
#
# Discord only lets a bot edit ITS OWN messages, so the managed pin must be
# bot-authored. One-time setup: run --init (posts the message to the welcome
# channel, pins it, prints the message id), save the id as
# DISCORD_WELCOME_MESSAGE_ID in .env, then unpin/delete the old
# human-authored pin. After that, edit config/messages.json and run --push.
#
# Template variables in the message: {brand_name}, {admin_role},
# {player_role}, {domain} - resolved from env at publish time.
# Role mentions are never pinged (allowed_mentions: none).
#
# Usage:
#   ./scripts/discord-pin-sync.sh --check   # diff message vs live pin; exit 1 on drift
#   ./scripts/discord-pin-sync.sh --push    # update the bot-authored pin from messages.json
#   ./scripts/discord-pin-sync.sh --init    # first-time: post + pin + print the id
#
# Needs: DISCORD_BOT_TOKEN (.env), DISCORD_WELCOME_CHANNEL_ID and (after
# --init) DISCORD_WELCOME_MESSAGE_ID (.env). The bot needs
# Send Messages + Read Message History in the channel, and Manage Messages
# to pin during --init.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "$SCRIPT_DIR/lib.sh"
load_env

MODE="${1:-}"
case "$MODE" in --check | --push | --init) ;; *)
  echo "Usage: $0 --check | --push | --init    (see header)"
  exit 1
  ;;
esac

: "${DISCORD_BOT_TOKEN:?Set DISCORD_BOT_TOKEN in .env}"
: "${DISCORD_WELCOME_CHANNEL_ID:?Set DISCORD_WELCOME_CHANNEL_ID in .env}"
MESSAGES_FILE="$PROJECT_DIR/config/messages.json"
[[ -f "$MESSAGES_FILE" ]] || die "Messages file not found: $MESSAGES_FILE"

MODE="$MODE" MESSAGES_FILE="$MESSAGES_FILE" python3 << 'PYEOF'
import json, os, sys, urllib.request

mode = os.environ["MODE"]
token = os.environ["DISCORD_BOT_TOKEN"]
channel = os.environ["DISCORD_WELCOME_CHANNEL_ID"]
message_id = os.environ.get("DISCORD_WELCOME_MESSAGE_ID", "")
api = "https://discord.com/api/v10"

with open(os.environ["MESSAGES_FILE"]) as f:
    messages = json.load(f)
body = messages.get("discord.welcome_pin", "")
if not body:
    sys.exit("No 'discord.welcome_pin' key in config/messages.json.")

# Substitute template variables
brand_name = os.environ.get("BRAND_NAME", "Adventure Server")
domain = os.environ.get("DOMAIN", "example.com")
admin_role = os.environ.get("DISCORD_ADMIN_ROLE_ID", "")
player_role = os.environ.get("DISCORD_PLAYER_ROLE_ID", "")

body = body.replace("{brand_name}", brand_name)
body = body.replace("{domain}", domain)
if admin_role:
    body = body.replace("{admin_role}", f"<@&{admin_role}>")
if player_role:
    body = body.replace("{player_role}", f"<@&{player_role}>")

if len(body) > 2000:
    sys.exit(f"Message body is {len(body)} chars - Discord's limit is 2000. Trim the message.")

def call(method, path, payload=None):
    req = urllib.request.Request(
        api + path,
        data=json.dumps(payload).encode() if payload is not None else None,
        headers={"Authorization": f"Bot {token}", "Content-Type": "application/json",
                 "User-Agent": f"{os.environ.get('BRAND_SLUG', 'adventure')}/pin-sync"},
        method=method,
    )
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            data = resp.read()
            return json.loads(data) if data else {}
    except urllib.error.HTTPError as e:
        sys.exit(f"Discord API {e.code} on {method} {path}: {e.read().decode()[:200]}")

no_pings = {"parse": []}

if mode == "--init":
    msg = call("POST", f"/channels/{channel}/messages",
               {"content": body, "allowed_mentions": no_pings})
    call("PUT", f"/channels/{channel}/pins/{msg['id']}")
    print(f"Posted and pinned. Now set in .env:")
    print(f"  DISCORD_WELCOME_MESSAGE_ID={msg['id']}")
    print("Then unpin/delete the old human-authored pin.")
    sys.exit(0)

if not message_id:
    sys.exit("DISCORD_WELCOME_MESSAGE_ID not set - run --init first (see header).")

live = call("GET", f"/channels/{channel}/messages/{message_id}")
if not live["author"].get("bot", False):
    sys.exit("That message isn't bot-authored - the bot can only edit its own "
             "messages. Run --init to create a bot-owned pin.")

if live["content"].strip() == body:
    print("Pin matches config/messages.json - nothing to do.")
    sys.exit(0)

if mode == "--check":
    print("DRIFT: the live pin differs from config/messages.json.")
    print("Update with: ./scripts/discord-pin-sync.sh --push")
    sys.exit(1)

call("PATCH", f"/channels/{channel}/messages/{message_id}",
     {"content": body, "allowed_mentions": no_pings})
print("Pin updated from config/messages.json.")
PYEOF
