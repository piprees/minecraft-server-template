---
title: Messages and Webhooks
description: config/messages.json key catalogue, discord-notify.sh templating and resolution order, the welcome-pin sync workflow, and the bulk-cleanup script
tags: [messages.json, discord-notify, discord-pin-sync, discord-cleanup, webhook, templating]
---

# Messages and Webhooks

## `config/messages.json` - every player/Discord-facing string

Never hard-code a player-facing message in a script or workflow file - add
or edit a key here instead. Variables use `{variable}` syntax, substituted
by whichever caller renders the template.

| Key | Used by | Variables |
| --- | --- | --- |
| `deploy.starting` | `deploy.yml` / deploy notifications | `{deploy_mode}`, `{commit_sha}`, `{commit_msg}` |
| `deploy.successful` | same | same |
| `deploy.failed` | same | same, plus `{admin_ping}`, `{actions_url}` |
| `modpack.updated` | CI after a full deploy with mod content changes | `{pack_name}`, `{player_ping}` |
| `restart.now` | `deploy.sh` (quick restart, no countdown) | - |
| `restart.warning_60s` / `_30s` / `_10s` / `_5s` | `deploy.sh` countdown | - |
| `restart.countdown_3` / `_2` / `_1` | `deploy.sh` countdown | - |
| `restart.kick` | `deploy.sh` graceful kick | - |
| `restart.kick_forced` | `deploy.sh` forced kick (30s timeout exceeded) | - |
| `restart.welcome_back` | `deploy.sh` end of deploy | - |
| `updates.available` | `check-updates.sh` (mod-checker) | `{admin_ping}`, `{count}`, `{mod_list}` |
| `setup.webhook_test` | setup wizard, verifying `DISCORD_WEBHOOK_URL` | - |
| `discord.welcome_pin` | `discord-pin-sync.sh` | `{brand_name}`, `{admin_role}`, `{player_role}`, `{domain}` |

All Minecraft-side strings (`restart.*`) use `§`-prefixed formatting codes,
not Discord markdown. All Discord-side strings use Discord markdown
(`**bold**`, etc.) - don't mix the two conventions when adding a key.

## `discord-notify.sh` - sending a message

```bash
./scripts/discord-notify.sh "Plain text message"
./scripts/discord-notify.sh --key deploy.starting deploy_mode=full commit_sha=abc123 commit_msg="fix: thing"
```

`--key <name>` looks up the template in `messages.json` and substitutes
every `key=value` argument into `{key}` placeholders (Python path uses
`str.replace`; the `jq` fallback path does the same via bash substring
replacement - keep behaviour identical if you touch either).

**Messages file resolution order** (first match wins - this is why the
same script works from a platform checkout, the stack bundle, or a
consumer repo):

1. `$PROJECT_DIR/overlay/config/messages.json` - a consumer's own override
2. `$SCRIPT_DIR/../config/messages.json` - the stack bundle's copy
   (this script normally lives at `.stack/current/stack/scripts/`)
3. `$PROJECT_DIR/config/messages.json` - a platform checkout's own config
   (consumers have no top-level `config/` dir, so this only resolves in
   platform-repo development)

Requires `DISCORD_WEBHOOK_URL` in the environment - if unset, the script
prints a warning to stderr and exits `0` (never blocks a deploy or boot on
a missing webhook; see Trap 4 in `SKILL.md`).

**Role pings**: if the resolved message contains `<@&ROLE_ID>`, the script
extracts every role ID and builds an `allowed_mentions.roles` array so
Discord actually pings those roles (Discord silently no-ops role mentions
without an explicit allow-list, even if the raw ID is in the text).

## `discord-pin-sync.sh` - the bot-authored welcome pin

Discord only lets a bot edit **its own** messages, so the managed welcome
pin in the general/welcome channel must be posted by the bot, never a human.

```bash
./scripts/discord-pin-sync.sh --init     # first time only: post + pin + print the message id
./scripts/discord-pin-sync.sh --check    # diff live pin vs messages.json; exit 1 on drift
./scripts/discord-pin-sync.sh --push     # update the bot-authored pin from messages.json
```

Workflow:

1. `--init` posts `discord.welcome_pin` (with `{brand_name}`, `{domain}`,
   `{admin_role}`, `{player_role}` substituted from `.env`) to
   `DISCORD_WELCOME_CHANNEL_ID`, pins it, and prints the message ID.
2. Save that ID as `DISCORD_WELCOME_MESSAGE_ID` in `.env` (and push it to
   the GitHub `production` environment via `github-env-sync.sh` so the
   scheduled workflow can use it).
3. Unpin/delete whatever human-authored welcome message existed before -
   the bot-authored one is now the source of truth.
4. From then on, edit `discord.welcome_pin` in `config/messages.json` and
   run `--push` (or let the scheduled workflow do it).

**Every call sends `allowed_mentions: {"parse": []}`** - role mentions in
the welcome pin render as `@Admin`/`@Player` text but never actually ping
anyone, deliberately (it's a static reference message, not an announcement).

**`--push`/`--check` refuse to touch a non-bot-authored message** - if the
live message's `author.bot` is false, they exit with an explicit error
telling you to run `--init` instead, rather than silently failing to
update it or (worse) failing to detect drift.

Message length is capped at Discord's 2000-char limit - the script checks
this itself and exits with a clear error if `discord.welcome_pin` grows
past it, rather than letting the Discord API reject the request.

**Scheduled sync**: `.github/workflows/discord-pin-sync.yml` runs on every
push to `main` that touches `config/messages.json`, plus manual dispatch.
Guarded by `vars.DROPLET_HOST != ''` (skips on repos with no production
environment configured) and runs in the `production` GitHub environment so
it has access to `DISCORD_BOT_TOKEN` and the role/channel/message ID vars.

## `discord-cleanup.sh` - bulk-deleting bot/webhook messages

```bash
./scripts/discord-cleanup.sh --dry-run          # uses DISCORD_CHANNEL_ID from .env
./scripts/discord-cleanup.sh <channel_id>       # target a specific channel
./scripts/discord-cleanup.sh                    # actually deletes
```

**Always `--dry-run` first** - there is no undo.

What it targets: messages authored by the bot's own user ID, **and**
messages sent by any webhook registered in the channel (dcintegration
relays chat via a webhook, not as the bot user, so webhook messages need
separate handling to catch chat-bridge spam).

Deletion strategy: messages under 14 days old are bulk-deleted (Discord's
bulk-delete endpoint, up to 100 per call, the only way to delete messages
that age without one API call each); anything older is deleted one at a
time with rate-limit backoff (`429` -> sleep and retry). The 14-day cutoff
is a hard Discord API limitation, not a script choice - trying to
bulk-delete an older message returns an API error.
