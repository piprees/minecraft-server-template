---
title: Command Registry
description: The two-client ownership model, the registry ground-truth check and recovery, the full /mc command table, and the LuckPerms group model behind it
tags: [discord-sync, dcintegration, slash-commands, mc-group, luckperms, registry, rcon]
---

# Command Registry

## Ownership, restated precisely

- **discord-sync** (`scripts/discord-sync.py`, `discord-sync` container) is
  the only process allowed to register application commands. Its
  `SyncBot.setup_hook` copies the global command tree it built in-process
  (`/mc` group + `/register` + `/unregister`) onto the guild
  (`DISCORD_GUILD_ID`), syncs it, then clears and re-syncs the **global**
  tree to empty. This runs on every bot start.
- **dcintegration** (Fabric mod, `mc` container) shares the same
  `DISCORD_BOT_TOKEN` for the chat bridge only. Its `[commands]` feature, if
  ever enabled, registers commands through the same application - which
  bulk-overwrites whatever discord-sync last set, at the scope the mod
  chooses. `deploy.sh` and `dev-up.sh` both force `[commands] enabled =
  false` in the live `data/config/Discord-Integration.toml` via
  `scripts/ensure-discord-command-owner.py`.

## Ground-truth check

Discord's actual registry state is the only thing that tells you the truth.
Query it directly with the bot token:

```
GET https://discord.com/api/v10/applications/<app_id>/guilds/<guild_id>/commands
GET https://discord.com/api/v10/applications/<app_id>/commands
```

- `<app_id>`: the bot application's ID (Discord Developer Portal -> your
  application -> General Information; normally the same value as the bot
  user's ID).
- `<guild_id>`: `DISCORD_GUILD_ID` from `.env`.

**Expected state:** the guild-scoped list contains exactly `register`,
`unregister`, `mc`. The global list is `[]`.

**If the global list is non-empty:** the mod's command feature fired at
least once. Fix `data/config/Discord-Integration.toml` (or wait for the
next deploy, which re-enforces it), then restart discord-sync so
`setup_hook` purges the global tree again - it clears global commands on
every boot regardless of how they got there.

**If the guild list is missing commands:** discord-sync failed during
`setup_hook` (bad token, bad guild ID, network failure to Discord) or
hasn't started. Check `docker logs discord-sync` for exceptions before the
"Slash commands synced to guild" line.

## `ensure-discord-command-owner.py` - what it actually enforces

Called by `deploy.sh` (step 9, only when `Discord-Integration.toml` looks
intact - i.e. has a `[messages` section) and by `dev-up.sh` on every
`./dev up`. It:

- Scans for the `[commands]` section and its `enabled` key.
- Rewrites `enabled = true` to `enabled = false` (leaves any trailing
  comment on the line intact).
- **Fails (exit 1)** if it finds zero or more than one `enabled` key in
  `[commands]` - it will not guess at an unfamiliar schema shape, so a mod
  update that restructures this section breaks the deploy loudly instead of
  silently leaving commands enabled.
- Writes atomically (temp file + rename) if a change was needed.

If `Discord-Integration.toml` is truncated (`deploy.sh` checks for a
missing `[messages` section first), `deploy.sh` deletes it instead so the
mod regenerates a fresh one on next boot - `ensure-discord-command-owner.py`
never runs against a known-broken file.

## Player <-> Minecraft linking

- `/register <minecraft_username>`: validates the username against
  `^[a-zA-Z0-9_]{3,16}$`, resolves it through the Mojang API
  (`api.mojang.com/users/profiles/minecraft/<name>`), rejects unknown names
  (404) and names already linked to a different Discord user. Persists to
  `/data/discord-players.json` (Discord ID -> MC username) and mirrors the
  UUID into dcintegration's `/data/DiscordIntegration-Data/LinkedPlayers.json`
  so the mod's own player-linking features see it too.
- `/unregister`: removes both records and immediately runs `whitelist
  remove` + `deop` via RCON - it doesn't wait for the sync loop.
- **Role sync loop** (`SyncBot._sync_loop`, every `SYNC_INTERVAL` seconds,
  default 60): for each linked player, diffs current Discord roles against
  the last-seen state and only sends RCON commands on a change - `Player`
  role present -> `whitelist add`; `Admin` role present -> also `op`;
  neither role -> `whitelist remove` + `deop`. Matching is by **role name**
  (`"Player"`, `"Admin"` - case-sensitive literals in the code), not by the
  `DISCORD_ADMIN_ROLE_ID`/`DISCORD_PLAYER_ROLE_ID` env vars, which are used
  only for `<@&id>` mention templating elsewhere (pin sync, message
  templates).

## Full `/mc` command table (verified against `scripts/discord-sync.py`)

All require the `Admin` Discord role. All except pure read/status calls are
audit-logged to the channel `DISCORD_CHANNEL_ID` points to.

| Command | Syntax | Notes |
| --- | --- | --- |
| `status` | `/mc status` | TPS/MSPT/memory (via `spark health`), uptime, last backup time, online players + coordinates, Chunky pre-gen progress per dimension, uNmINeD render status, full registration roster |
| `say` | `/mc say <message>` | Broadcast to in-game chat, max 200 chars after sanitisation (strips non-printable chars, leading `/`, defangs `@everyone`/`@here`) |
| `whisper` | `/mc whisper <player> <message>` | Private in-game message; reports if the player isn't online |
| `weather` | `/mc weather <clear\|rain\|thunder>` | Dropdown choice, not free text |
| `tempban` | `/mc tempban <player> [minutes=10]` | 1-1440 min, confirmation button, auto-pardons on expiry via a persisted timer (`/data/discord-pending-actions.json`) that survives bot restarts |
| `pardon` | `/mc pardon <player>` | Cancels any pending auto-pardon timer too |
| `restart` | `/mc restart` | 10 min cooldown, confirmation button, 30s in-game countdown, kicks, saves, `docker restart mc` then the web sidecars, polls RCON for up to 5 min |
| `map-refresh` | `/mc map-refresh` | 5 min cooldown; `docker restart unmined-render` (a restart triggers an immediate render pass) |
| `heal` / `feed` / `extinguish` | `/mc <cmd> <player>` | Player must be online |
| `fly` / `invuln` | `/mc <cmd> <player> <on\|off>` | Dropdown toggle, player must be online |
| `kill` | `/mc kill <player>` | Confirmation button |
| `give` | `/mc give <player> <item> [count=1]` | Item ID normalised to `namespace:path` (bare `sharpness`-style input becomes `minecraft:sharpness`), count capped 1-64 |
| `effect` | `/mc effect <player> <effect> <seconds> [amplifier=0]` | Seconds capped 1-3600, amplifier 0-5 |
| `enchant` | `/mc enchant <player> <enchantment> [level=1]` | Level capped 1-255; applies to the currently held item via `execute as <player> run ec enchant ...` |
| `gamemode` | `/mc gamemode <player> <mode> [minutes]` | Mode is a dropdown; optional auto-revert to survival after 1-60 min, persisted like tempban |
| `xp` | `/mc xp <player> <amount>` | Levels, capped 1-100 |
| `claims` | `/mc claims <player> <count>` | Sets `xaero.pac_max_claims` via LuckPerms, capped 1-128; does not require the player online |
| `border` | `/mc border [radius]` | Sets vanilla world border + matching Chunky pre-gen borders (overworld at `radius`, nether at `radius/8`); omit `radius` to read the current border. **Not documented in `COMMANDS.md`** - a real gap, not evidence the command is missing |

Player-targeted commands other than `claims`/`say`/`weather`/`restart`/
`map-refresh` check the RCON response for `No entity was found` and report
"not online" instead of a raw RCON error.

## LuckPerms group model (for context, enforced by `setup-permissions.sh`)

| Group | Who | Gets |
| --- | --- | --- |
| `player` | Everyone (`default` inherits it) | Social/info commands only (`essentialcommands.nick`, `.afk`, `.gametime`, `.rules`, `waystones.waystone`) |
| `admin` | OPs + manually assigned | Inherits `player` + teleport, warps, cheats, server management |

`setup-permissions.sh` is idempotent (LuckPerms silently accepts duplicate
grants) and is called by `deploy.sh` after the server passes its health
check. Its header states the sync obligation explicitly: **update
`COMMANDS.md` in the same commit as any permission change here** - nothing
automated checks this, and the `/mc border` gap above is what happens when
it's skipped.
