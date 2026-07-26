# Skill brief: `discord-integration-ops`

> **This is a brief, not the skill.** Build `SKILL.md` from it; verify every path against the repo before writing.

## Why this skill

Two separate Discord clients share one bot token, and the failure mode when that goes wrong is *silent deletion of every slash command*. `AGENTS.md` trap 1 is the first trap in the list for a reason:

> dcintegration (in mc) and discord-sync both log in as the same bot. If the mod's `[commands] enabled` flips true in the live `data/config/Discord-Integration.toml`, it bulk-overwrites the command registry on every mc boot and silently deletes `/mc` + `/register`.

And there is a second, purely epistemic trap: **the repo's `config/dcintegration/config.toml` is an intent document, not the live schema.** An agent that reads it and concludes commands are disabled has learned nothing about the running server.

This is also the player-management path. `README.md § Manage players` makes clear the normal route is Discord `/register` + role, not RCON — but every agent reaches for `whitelist add` first.

## Scope

**In:** the two-client architecture, the slash-command registry and its ground truth, `/mc` and `/register`, role→whitelist sync, the chat bridge, player-facing message templates, webhooks, the pinned welcome message, and Discord-side troubleshooting.

**Out:** general server triage → brief 03. Bot token provisioning and intents → brief 12 (`docs/setup-guide.md § Discord bot setup`).

## Source material

| File | What to mine |
| --- | --- |
| `AGENTS.md` trap 1 | The shared-token trap, deploy.sh's enforcement, discord-sync's global purge, the intent-doc warning |
| `AGENTS.md:49` | Fixed decision: discord-sync owns all slash commands (guild-scoped); the mod is chat-bridge only |
| `README.md § Discord integration` | The two-client table, the troubleshooting table, **the registry ground-truth API call** |
| `README.md § Manage players` | The three-step self-serve path and the manual RCON fallbacks |
| `scripts/discord-sync.py` (1535 lines) — module docstring lines 1-38 | The authoritative summary: what it does, the three architecture constraints |
| `scripts/discord-sync.py` — `ThreadSafeRcon`, `COMMAND_LOG_ALLOWLIST`, `ADMIN_ACTION_RE`, the `/mc` group | The command set, the audit log, the log watcher |
| `config/messages.json` | Every player/Discord-facing string. Keys: `deploy.*`, `restart.*`, `modpack.updated`, `updates.available`, `setup.webhook_test`, `discord.welcome_pin` |
| `scripts/discord-notify.sh` (header) | `--key` template lookup and `{variable}` substitution; the three-level resolution order (overlay → bundle → platform checkout) |
| `scripts/discord-pin-sync.sh` (header) | `--init`/`--check`/`--push`; why the pin must be bot-authored; `allowed_mentions: none` |
| `scripts/discord-cleanup.sh` (header) | Bulk vs individual deletion, the 14-day boundary, `--dry-run` |
| `scripts/ensure-discord-command-owner.py` | What it enforces and when it runs |
| `COMMANDS.md` | The full `/mc` set and the LuckPerms permission model |
| `scripts/setup-permissions.sh` (header) | The two groups; `default` inherits `player`; **"COMMANDS.md documents what each group can do — update it when changing permissions here"** |
| `docker-compose.yml` — `discord-sync` service | Bind-mounted read-only script → **force-recreate, not restart** |
| `.github/workflows/discord-pin-sync.yml` | The scheduled pin sync |

## Required structure

```
discord-integration-ops/
├── SKILL.md
└── references/
    ├── command-registry.md   # the two clients, ownership, ground truth, recovery when commands vanish
    └── messages-and-webhooks.md  # messages.json keys, discord-notify templating, pin sync, cleanup
```

### SKILL.md must contain

1. **The two-client table first**, because every subsequent decision depends on knowing which one owns the behaviour:
   | Client | Runs in | Owns |
   | --- | --- | --- |
   | dcintegration (Fabric mod) | `mc` container | Chat bridge only — game↔Discord chat, join/leave/death/advancement via webhook |
   | discord-sync (`scripts/discord-sync.py`) | `discord-sync` container | **All slash commands**, role→whitelist sync, audit log, command relay |
2. **The ground-truth check**, verbatim, as the first debugging move — not log-reading:
   ```
   GET /applications/<app_id>/guilds/<guild_id>/commands   (bot token)
   ```
   Guild should list `register`, `unregister`, `mc`. Global should be `[]`. Anything else is the trap firing.
3. **Player management, correct path first.** `/register <minecraft_username>` (verified against Mojang) → admin grants `@Player` → within 60 s the bot whitelists via RCON. `@Admin` additionally ops. Role removal reverses both on the same cycle. RCON is the *fallback*, immediate but not the norm.
4. **The `/mc` command surface** — predefined admin commands only, `@Admin` required, audit-logged, **no free-text RCON passthrough**. That last property is a deliberate security boundary; say so.
5. **Editing player-facing strings**: they all live in `config/messages.json`, never hard-coded (`AGENTS.md:206`). Show `discord-notify.sh --key` templating with a real key and variable substitution.
6. **Deploying a change to the bot**: `discord-sync.py` is bind-mounted read-only, so a change needs a **force-recreate**, not a restart. `deploy.sh` and the CI infra step both do this.
7. **A troubleshooting table** from `README.md § Discord integration`, extended with the registry ground-truth row.

### Traps to capture

1. **Shared bot token.** The mod's `[commands] enabled` must stay `false` in the **live** `data/config/Discord-Integration.toml`. `deploy.sh` enforces this on every full deploy; discord-sync purges the global registry at boot as a second line of defence.
2. **The repo's `config/dcintegration/config.toml` is an intent doc, not the live schema.** Reading it tells you nothing about the running server. Check the live TOML in `data/config/`.
3. **`mcrcon` arms SIGALRM**, which raises `signal only works in main thread` under `asyncio.to_thread`. All new RCON code in the bot must go through `ThreadSafeRcon`.
4. **RCON returning `None` usually means autopaused, not down.** Handle it gracefully — `/mc status` is the reference implementation.
5. **Discord webhook calls are all `|| true`** (fire and forget) by design — a Discord outage never blocks a deploy or boot. Do not "fix" this by adding error handling that blocks.
6. **A bot can only edit its own messages**, which is why the welcome pin must be bot-authored: `--init` posts and pins it, you save the id as `DISCORD_WELCOME_MESSAGE_ID`, then unpin the old human-authored one.
7. **Changing LuckPerms permissions means updating `COMMANDS.md`** in the same commit — `setup-permissions.sh` says so and nothing enforces it.
8. **`discord-cleanup.sh` deletes messages.** Always `--dry-run` first.

### Validation section

```bash
docker logs discord-sync --tail 50 | grep -i "slash commands synced"
docker restart discord-sync          # re-syncs guild commands
./ops rcon "whitelist list"
./ops doctor                          # includes the registry check
./ops discord-pin-sync --check        # exits 1 on drift
```

Add the client-side step that agents forget: after a re-sync, the *user* must Ctrl+R the Discord client for commands to appear.

## Done when

- An agent asked "the slash commands are gone" checks the registry via the API before reading a single log line, and knows the fix is the live TOML, not a reinstall.
- An agent asked to add a player recommends the Discord route and offers RCON as the immediate fallback.
