---
name: discord-integration-ops
description: |
  Operates the Discord integration: discord-sync (scripts/discord-sync.py)
  owns every guild-scoped slash command, role -> whitelist/op sync and audit
  logging, while the dcintegration Fabric mod only bridges chat and must never
  register commands. Covers the shared-bot-token failure mode, the registry
  ground-truth API check, the /mc admin surface, config/messages.json, and
  shipping a bot code change (the script is baked into the image, not
  bind-mounted, so a force-recreate alone changes nothing).

  Use when: slash commands vanish after an mc restart; a player needs
  whitelisting or op (the answer is /register plus a role, not RCON); adding
  or auditing a /mc command; editing player-facing text; or deploying a
  discord-sync change. Grep for "Slash commands synced" or "signal only works
  in main thread" first.
---

# Discord Integration Ops

Two processes log in with **one bot token**. Only one of them may ever register
commands. Every task below assumes you know which is which before you touch
anything.

| Client | Runs in | Owns |
| --- | --- | --- |
| **dcintegration** (Fabric mod) | `mc` container | Chat bridge only - game<->Discord chat, join/leave/death/advancement via webhook |
| **discord-sync** (`scripts/discord-sync.py`) | `discord-sync` container | **All slash commands** (guild-scoped), role->whitelist/op sync, audit log, admin command-log relay |

If the mod's `[commands] enabled` ever flips `true` in the **live**
`data/config/Discord-Integration.toml`, it bulk-overwrites the application's
command registry on the next mc boot and silently deletes `/mc`, `/register`,
`/unregister`. `config/dcintegration/config.toml` in this repo is a template
with placeholder values (`REPLACE_WITH_DISCORD_BOT_TOKEN`) - it tells you
nothing about what's running. Reading it and concluding commands are safe is
a mistake; only the live TOML on the server means anything.

## Step 1: the ground-truth check, before you read a single log line

Discord's own command registry beats every log line and every config file -
check it first, not last:

```
GET /applications/<app_id>/guilds/<guild_id>/commands   (bot token)
```

`<app_id>` is the bot application's ID from the Discord Developer Portal
(usually identical to the bot user's ID); `<guild_id>` is `DISCORD_GUILD_ID`.
Expected result: the **guild** list contains `register`, `unregister`, `mc`.
The **global** list is `[]`. Anything else - extra global commands, missing
guild commands - is the shared-token trap firing (see Trap 1) or the sync
loop having crashed at boot before `setup_hook` finished.

`./ops doctor` includes this check as part of its production triage.

## Step 2: how the registry is enforced (so you know where to look)

Two independent lines of defence, both load-bearing:

1. **deploy.sh, step 9** ("Enforce Discord integration config"): after
   patching the live `Discord-Integration.toml` (bot token, channel, brand
   name), it runs `scripts/ensure-discord-command-owner.py` against that
   file. The script edits *only* the `[commands] enabled` line to `false`
   and **fails the deploy** if it finds zero or more than one `enabled` key
   under `[commands]` - it refuses to guess against an unfamiliar or
   hand-edited schema rather than silently leaving commands enabled.
   `dev-up.sh` runs the same script locally, every `./dev up`.
2. **discord-sync's `setup_hook`** (`SyncBot.setup_hook` in
   `discord-sync.py`): syncs `/mc`, `/register`, `/unregister` to the guild,
   then explicitly clears and re-syncs the **global** command tree to `[]`
   on every boot - even if the mod's registry write already happened, this
   purges it back out. Log line: `Slash commands synced to guild <id>`,
   followed by `Global command registry purged (guild commands are
   canonical)`.

Neither defence is a substitute for the other - the config fix stops the mod
writing at all; the boot-time purge is what recovers if it did anyway.

## Player management: the correct order

1. Player runs `/register <minecraft_username>` in Discord (verified
   against the Mojang API; rejects unknown usernames and duplicate links).
2. An admin gives them the `Player` **Discord role**.
3. Within `SYNC_INTERVAL` (default 60s) the bot's role-sync loop notices the
   role change and whitelists them via RCON. The `Admin` role additionally
   ops. Removing the role reverses both on the next cycle.

`/unregister` removes the link and immediately de-whitelists/de-ops via
RCON (no need to wait for the sync loop).

Manual RCON (`whitelist add <name>`, `op <name>`) works and is immediate -
but it's the **fallback**, not the normal path, and it doesn't touch the
Discord<->Minecraft link the bot needs for `/mc status`'s roster and for
role removal to de-whitelist correctly later.

**Role sync matches by Discord role NAME, not ID.** `PLAYER_ROLE = "Player"`
and `ADMIN_ROLE = "Admin"` are literal, case-sensitive role names checked
against `member.roles` - `DISCORD_ADMIN_ROLE_ID`/`DISCORD_PLAYER_ROLE_ID` in
`.env` are used only for message-template pings (`discord-pin-sync.sh`,
`config/messages.json`), never for the sync itself. Renaming the `Admin` or
`Player` role in the Discord server breaks whitelist/op sync with **no
error anywhere** - the loop just stops matching that role.

## The /mc command surface

Requires the `Admin` Discord role (`is_admin()` check); every invocation
that isn't a validation failure is audit-logged to the channel
`DISCORD_CHANNEL_ID` points to (conventionally `#minecraft`). There is
**no free-text RCON passthrough** anywhere in the group - every command is a
predefined function with its own input validation. That's a deliberate
security boundary: don't add a generic "run RCON" command to close a gap,
even under pressure.

The full, current set (verified against `scripts/discord-sync.py`, not
transcribed from docs): `status`, `say`, `whisper`, `weather`, `tempban`,
`pardon`, `restart`, `map-refresh`, `heal`, `feed`, `fly`, `invuln`,
`extinguish`, `kill`, `give`, `effect`, `enchant`, `gamemode`, `xp`,
`claims`, `border`. Full syntax and limits for each: `COMMANDS.md` § `/mc`
admin commands and `references/command-registry.md`.

**`COMMANDS.md` is missing `/mc border`** (set the player world border +
matching Chunky pre-gen borders). It exists in the running code and is
audit-logged like everything else; treat this as a doc-sync gap, not a
sign the command doesn't exist. If you touch permission-affecting commands,
update `COMMANDS.md` in the same commit -
`scripts/setup-permissions.sh` says so explicitly and nothing else enforces it.

Validation patterns worth knowing before you add a new command: player
names against `^[a-zA-Z0-9_]{3,16}$`; item/effect/enchantment IDs coerced
to `namespace:path` (bare `sharpness` becomes `minecraft:sharpness`); RCON
responses containing `No entity was found` are translated to "player not
online" rather than shown raw.

## Editing player-facing strings

Every player/Discord-facing message template lives in
`config/messages.json` - never hard-code one in a script. See
`references/messages-and-webhooks.md` for the full key catalogue and the
`discord-notify.sh --key` templating syntax.

## Deploying a change to discord-sync.py

The bot's docstring says the script is "bind-mounted read-only" - **that
comment is stale.** `docker/discord-sync/Dockerfile` does `COPY
scripts/discord-sync.py /app/` at image build time; `docker-compose.yml`'s
`discord-sync` service has no script bind mount (only `./data:/data` and
the Docker socket for `/mc restart`). A plain `docker restart discord-sync`
re-runs the **same image** - it will never pick up a code change.

`discord-sync.py` is platform-repo code, so shipping a change is a
**platform release**, not a consumer overlay tweak: push to `main` rebuilds
the `discord-sync` image (tagged `latest` and the release semver by
`publish.yml`), then cut a release (`gh workflow run release.yml -f
version=vX.Y.Z`). A consumer's next deploy resolves its `STACK_VERSION` pin
to that tag - which differs from what the server is running, so tier
detection forces a **full** deploy (the resolved-tag comparison in
`deploy-reusable.yml`, not an `overlay/` file diff). `deploy.sh` then pulls
the new `IMAGE_TAG` at step 6 and force-recreates `discord-sync` alongside
the other sidecars at step 11. **Infra-tier deploys never pull a new
image** - `infra-deploy.sh` force-recreates `discord-sync` too, but only to
pick up a freshly regenerated `.env` (channel IDs, brand name); it fires on
plain `overlay/` changes where the stack version hasn't moved, so there's
no new image to pull. A plain `docker restart discord-sync` never helps
either way - it reuses whatever image is already local.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Slash commands missing from the Discord client | Step 1's registry check, then `docker logs discord-sync --tail 50 \| grep -i "slash commands synced"`; if the log looks fine but the client still doesn't show them, the user needs to Ctrl+R the Discord client - it caches the command list |
| Commands present but failing / timing out | `docker logs discord-sync` for RCON errors - `RCON failed` in the log or a `None` result usually means mc is autopaused, not down (see Trap 4) |
| No chat relay (dcintegration side) | `docker logs mc \| grep -i discord`; check `botToken` in the **live** `data/config/Discord-Integration.toml`, not the repo template |
| Role sync not whitelisting a player | Confirm the Discord role is spelled exactly `Player`/`Admin` (Trap in Player management above), then check the sync loop hasn't errored (`Sync cycle failed` in the log) |
| Registry state (ground truth) | Step 1's API call - guild lists `register`, `unregister`, `mc`; global is `[]` |

## Traps

1. **Shared bot token, one purpose each.** `dcintegration`'s `[commands]
   enabled` must stay `false` in the **live** `data/config/Discord-Integration.toml`.
   `ensure-discord-command-owner.py` enforces this on every `deploy.sh`/`dev-up.sh`
   run and fails loudly if the schema is ambiguous; discord-sync's boot-time
   global-registry purge is the second line of defence. The repo's
   `config/dcintegration/config.toml` is a template, never the live schema.
2. **`mcrcon` arms SIGALRM**, which raises `signal only works in main
   thread` under `asyncio.to_thread`. All RCON in discord-sync goes through
   `ThreadSafeRcon` (socket timeouts instead) - use it for any new RCON code
   in the bot, never the stock `MCRcon`.
3. **RCON returning `None` usually means autopaused, not down.** `/mc
   status` is the reference implementation: it treats a `None` from `spark
   health` as "Offline/Paused" rather than erroring.
4. **Discord webhook and Mojang API calls are fire-and-forget by design.**
   `discord-notify.sh`'s webhook POST and the deploy/backup notifications
   don't block on failure - don't "fix" this by adding error handling that
   blocks a deploy or boot on a Discord outage.
5. **A bot can only edit its own messages.** The welcome pin
   (`discord.welcome_pin` in `messages.json`) must be bot-authored:
   `discord-pin-sync.sh --init` posts and pins it, you save the returned ID
   as `DISCORD_WELCOME_MESSAGE_ID`, then unpin/delete any old human-authored
   pin. `--push`/`--check` against a human-authored message id fail with an
   explicit error rather than silently doing nothing.
6. **Changing LuckPerms permissions means updating `COMMANDS.md` in the same
   commit** - `setup-permissions.sh`'s header says so; nothing automated
   enforces it (see the `/mc border` gap above, which is exactly this
   failure already having happened once).
7. **`discord-cleanup.sh` deletes messages, in bulk.** Always run
   `--dry-run` first. It targets the bot's own messages and any webhook IDs
   registered in the channel (dcintegration posts via webhook, not as the
   bot user) - both need clearing to fully reset a channel.
8. **Role sync is name-matched, not ID-matched** (see Player management
   above) - a Discord-side role rename breaks it invisibly.
9. **discord-sync.py is image-baked, not bind-mounted** (see Deploying
   above) - its own docstring is out of date on this point; don't trust it.

## Validation

```bash
docker logs discord-sync --tail 50 | grep -i "slash commands synced"
docker restart discord-sync              # re-syncs guild commands from the CURRENT image
./ops rcon "whitelist list"
./ops doctor                             # includes the registry ground-truth check
./ops discord-pin-sync --check           # exits 1 on drift from messages.json
```

After any re-sync, tell the human to **Ctrl+R the Discord client** - the
command list is cached client-side and a healthy sync won't appear until
they do.

## References

- `references/command-registry.md` - full `/mc` command table with syntax
  and limits, the registry recovery procedure, LuckPerms group model
- `references/messages-and-webhooks.md` - `config/messages.json` key
  catalogue, `discord-notify.sh` templating, pin sync, cleanup script
