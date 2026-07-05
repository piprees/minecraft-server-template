# Commands reference

Every in-game command, RCON admin command, and Discord command for the Adventure Server.

> Operations and how-tos live in [README.md](README.md); agent constraints and architecture traps in [AGENTS.md](AGENTS.md). This file is the command reference - what exists, who can run it, and the exact syntax.

## Permission model

Permissions are managed by **LuckPerms** with two custom groups. OPs bypass all permission checks.

| Group    | Who gets it                                | What they get                                                |
| -------- | ------------------------------------------ | ------------------------------------------------------------ |
| `player` | Everyone (via `default` group inheritance) | Social and info commands only                                |
| `admin`  | OPs and manually assigned players          | Inherits `player` + teleportation, cheats, server management |

Assign a player to the admin group:

```
/lp user <player> parent add admin
```

## Player commands

Available to all players via the `player` LuckPerms group. No teleportation or cheats.

| Command        | Syntax                  | Description                                                              |
| -------------- | ----------------------- | ------------------------------------------------------------------------ |
| `/w`           | `/w <player> <message>` | Private whisper (`/msg` and `/tell` are aliases). Not relayed to Discord |
| `/me`          | `/me <action>`          | Action message ("\* Name hides from the creeper")                        |
| `/afk`         | `/afk`                  | Toggle AFK status. Auto-AFK after 15 minutes of inactivity               |
| `/gametime`    | `/gametime`             | Show the current in-game time and day count                              |
| `/nick`        | `/nick <nickname>`      | Set a display nickname (prefix `~` in red). Hover reveals real name      |
| `/nick reveal` | `/nick reveal <player>` | Reveal the real name behind a nickname                                   |
| `/rules`       | `/rules`                | Display the server rules                                                 |

### In-game interactions (no command needed)

| Feature           | How                                                                | Mod                     |
| ----------------- | ------------------------------------------------------------------ | ----------------------- |
| Claim land        | Open the world map (Xaero's), right-click chunks to claim          | Open Parties and Claims |
| Configure claims  | In-game UI via map overlay                                         | Open Parties and Claims |
| Use waystones     | Right-click a discovered waystone to teleport                      | Waystones               |
| Sit on blocks     | Right-click stairs, slabs, or carpets                              | Sit!                    |
| Carry blocks      | Shift+right-click chests, animals, etc.                            | Carry On                |
| Chop whole trees  | Break one log block (sneaking disables)                            | FallingTree             |
| Locate biomes     | Craft and use a Nature's Compass                                   | Nature's Compass        |
| Locate structures | Craft and use an Explorer's Compass                                | Explorer's Compass      |
| Voice chat        | Press `V` to configure, proximity-based                            | Simple Voice Chat       |
| View recipes      | Press `E` to open inventory, search with EMI                       | EMI                     |
| Bounty quests     | Interact with a bounty board in a village                          | Bountiful               |
| Fill your minimap | `/maplink download_tiles 2048 2048 0 0` then open the map with `M` | MapLink                 |

## Admin commands

Available to players in the `admin` LuckPerms group. Includes everything from `player` plus:

### Teleportation

| Command        | Syntax                | Description                                                       |
| -------------- | --------------------- | ----------------------------------------------------------------- |
| `/home set`    | `/home set [<name>]`  | Set a home location (limit: 1/2/5 depending on permission tier)   |
| `/home tp`     | `/home tp [<name>]`   | Teleport to a saved home                                          |
| `/home delete` | `/home delete <name>` | Delete a saved home                                               |
| `/tpa`         | `/tpa <player>`       | Request to teleport to another player                             |
| `/tpa here`    | `/tpa here <player>`  | Request another player to teleport to you                         |
| `/tpa accept`  | `/tpa accept`         | Accept an incoming teleport request                               |
| `/tpa deny`    | `/tpa deny`           | Deny an incoming teleport request                                 |
| `/spawn`       | `/spawn`              | Teleport to the world spawn                                       |
| `/back`        | `/back`               | Return to your previous location (not on death)                   |
| `/rtp`         | `/rtp`                | Random teleport within 1000 blocks (30s cooldown, overworld only) |
| `/near`        | `/near [<range>]`     | Show nearby players (default 200 blocks, max 200)                 |
| `/top`         | `/top`                | Teleport to the highest block above you                           |

### Warps

| Command        | Syntax                | Description                        |
| -------------- | --------------------- | ---------------------------------- |
| `/warp tp`     | `/warp tp <name>`     | Teleport to a named warp           |
| `/warp set`    | `/warp set <name>`    | Create a new warp at your location |
| `/warp delete` | `/warp delete <name>` | Remove a warp                      |
| `/warp list`   | `/warp list`          | List all available warps           |

### Cheats and utilities

| Command       | Syntax                             | Description                                 |
| ------------- | ---------------------------------- | ------------------------------------------- |
| `/fly`        | `/fly [<player>] <true\|false>`    | Toggle creative flight                      |
| `/fly speed`  | `/fly speed <1-5\|reset>`          | Set flight speed (max 5)                    |
| `/heal`       | `/heal [<player>]`                 | Fully restore health                        |
| `/feed`       | `/feed [<player>]`                 | Fully restore hunger                        |
| `/repair`     | `/repair [<player>]`               | Repair the held item                        |
| `/invuln`     | `/invuln [<player>] <true\|false>` | Toggle invulnerability                      |
| `/extinguish` | `/extinguish [<player>]`           | Put out fire on a player                    |
| `/enderchest` | `/enderchest`                      | Open your ender chest anywhere              |
| `/workbench`  | `/workbench`                       | Open a crafting table anywhere              |
| `/wastebin`   | `/wastebin`                        | Open a disposal inventory (items destroyed) |
| `/suicide`    | `/suicide`                         | Kill yourself (respawn at bed)              |
| `/day`        | `/day`                             | Set time to day                             |
| `/night`      | `/night`                           | Set time to night                           |

### Enchanting (admin)

| Command       | Syntax                              | Description                              |
| ------------- | ----------------------------------- | ---------------------------------------- |
| `/ec enchant` | `/ec enchant <enchantment> <level>` | Apply an enchantment above normal limits |
| `/ec list`    | `/ec list`                          | List all available enchantments          |

### Player data

| Command                   | Syntax                    | Description                                     |
| ------------------------- | ------------------------- | ----------------------------------------------- |
| `/delete_all_player_data` | `/delete_all_player_data` | Wipe all of your own player data (irreversible) |

## OP-only commands

These require full operator status (`/op <player>`) and bypass all permission checks. OPs are also automatically in the `admin` LuckPerms group.

### Vanilla server commands

| Command | Syntax | Description |
| --- | --- | --- |
| `/op` | `/op <player>` | Grant operator status |
| `/deop` | `/deop <player>` | Revoke operator status |
| `/gamemode` | `/gamemode <survival\|creative\|adventure\|spectator> [<player>]` | Change game mode |
| `/teleport` | `/tp <player> <x> <y> <z>` | Teleport a player to coordinates |
| `/give` | `/give <player> <item> [<count>]` | Give items |
| `/kill` | `/kill [<player>]` | Kill a player or entity |
| `/kick` | `/kick <player> [<reason>]` | Disconnect a player |
| `/ban` | `/ban <player> [<reason>]` | Ban a player |
| `/pardon` | `/pardon <player>` | Unban a player |
| `/difficulty` | `/difficulty <peaceful\|easy\|normal\|hard>` | Change difficulty |
| `/gamerule` | `/gamerule <rule> [<value>]` | Get or set a game rule |
| `/time` | `/time set <value>` | Set world time (`0`=dawn, `6000`=noon, `13000`=dusk, `18000`=midnight) |
| `/weather` | `/weather <clear\|rain\|thunder> [<duration>]` | Change weather |
| `/whitelist` | `/whitelist <add\|remove\|list\|on\|off> [<player>]` | Manage the allowlist |
| `/setworldspawn` | `/setworldspawn [<x> <y> <z>]` | Set the world spawn point |
| `/worldborder` | `/worldborder set <diameter>` | Set the vanilla world border |
| `/summon` | `/summon <entity> [<x> <y> <z>]` | Spawn an entity |
| `/effect` | `/effect give <player> <effect> [<duration>] [<amplifier>]` | Apply a status effect |
| `/enchant` | `/enchant <player> <enchantment> [<level>]` | Enchant held item (within vanilla limits) |
| `/locate` | `/locate structure <structure>` | Find the nearest structure |
| `/locate biome` | `/locate biome <biome>` | Find the nearest biome |
| `/experience` | `/xp add <player> <amount> [points\|levels]` | Add experience |
| `/clear` | `/clear [<player>] [<item>]` | Clear inventory |
| `/fill` | `/fill <from> <to> <block>` | Fill a region with blocks |
| `/setblock` | `/setblock <x> <y> <z> <block>` | Place a block |
| `/data` | `/data get entity <player>` | Read or modify NBT data |
| `/scoreboard` | `/scoreboard objectives <add\|remove\|list>` | Manage scoreboards |
| `/schedule` | `/schedule function <name> <time>` | Schedule a function to run |
| `/save-all` | `/save-all [flush]` | Save the world (`flush` forces write to disk) |
| `/save-off` | `/save-off` | Disable auto-saving (for consistent backups) |
| `/save-on` | `/save-on` | Re-enable auto-saving |
| `/stop` | `/stop` | Shut down the server |
| `/reload` | `/reload` | Reload datapacks |
| `/seed` | `/seed` | Show the world seed |
| `/tick` | `/tick rate <tps>` | Change the tick rate (debug) |
| `/say` | `/say <message>` | Broadcast a server message |
| `/tellraw` | `/tellraw <player> <json>` | Send a formatted message |
| `/title` | `/title <player> title <json>` | Display a title on screen |
| `/spectate` | `/spectate [<player>]` | Spectate another player |
| `/spreadplayers` | `/spreadplayers <x> <z> <spread> <range> false` | Scatter players randomly |
| `/spawnpoint` | `/spawnpoint <player> [<x> <y> <z>]` | Set a player's individual spawn |

### LuckPerms (permissions management)

| Command     | Syntax                                         | Description                            |
| ----------- | ---------------------------------------------- | -------------------------------------- |
| `/lp user`  | `/lp user <player> parent add <group>`         | Add a player to a group                |
| `/lp user`  | `/lp user <player> parent remove <group>`      | Remove a player from a group           |
| `/lp user`  | `/lp user <player> permission set <perm> true` | Grant a specific permission            |
| `/lp user`  | `/lp user <player> info`                       | Show a player's groups and permissions |
| `/lp group` | `/lp group <group> listmembers`                | List all members of a group            |
| `/lp group` | `/lp group <group> permission set <perm> true` | Grant a permission to a group          |

Common permission nodes:

```
essentialcommands.*               # all Essential Commands
xaero.pac_max_claims <N>          # override max claim chunks for a player
minecraft.command.gamemode.*      # all gamemode access
```

### Spark (performance profiling)

| Command              | Syntax                  | Description                           |
| -------------------- | ----------------------- | ------------------------------------- |
| `/spark health`      | `/spark health`         | Show TPS, MSPT, CPU, and memory stats |
| `/spark profiler`    | `/spark profiler start` | Start CPU profiling                   |
| `/spark profiler`    | `/spark profiler stop`  | Stop profiling and get a report link  |
| `/spark gc`          | `/spark gc`             | Request garbage collection            |
| `/spark heapsummary` | `/spark heapsummary`    | Show heap memory breakdown            |

### BlueMap (web map)

| Command             | Syntax                     | Description                        |
| ------------------- | -------------------------- | ---------------------------------- |
| `/bluemap update`   | `/bluemap update`          | Trigger a full map render          |
| `/bluemap update`   | `/bluemap update <radius>` | Render chunks within radius of you |
| `/bluemap purge`    | `/bluemap purge <map>`     | Delete all render data for a map   |
| `/bluemap freeze`   | `/bluemap freeze <map>`    | Pause rendering for a map          |
| `/bluemap unfreeze` | `/bluemap unfreeze <map>`  | Resume rendering for a map         |
| `/bluemap maps`     | `/bluemap maps`            | List all configured maps           |
| `/bluemap reload`   | `/bluemap reload`          | Reload BlueMap configuration       |
| `/bluemap version`  | `/bluemap version`         | Show BlueMap version               |
| `/bluemap stop`     | `/bluemap stop`            | Stop BlueMap rendering             |
| `/bluemap start`    | `/bluemap start`           | Start BlueMap rendering            |

### Chunky (chunk pre-generation)

| Command                 | Syntax                           | Description                                 |
| ----------------------- | -------------------------------- | ------------------------------------------- |
| `/chunky world`         | `/chunky world <dimension>`      | Select the target dimension                 |
| `/chunky center`        | `/chunky center <x> <z>`         | Set the centre point                        |
| `/chunky radius`        | `/chunky radius <blocks>`        | Set the generation radius                   |
| `/chunky shape`         | `/chunky shape <square\|circle>` | Set the generation shape                    |
| `/chunky start`         | `/chunky start`                  | Begin pre-generation                        |
| `/chunky pause`         | `/chunky pause`                  | Pause generation                            |
| `/chunky continue`      | `/chunky continue`               | Resume paused generation                    |
| `/chunky cancel`        | `/chunky cancel`                 | Cancel and discard progress                 |
| `/chunky confirm`       | `/chunky confirm`                | Confirm a cancel operation                  |
| `/chunky progress`      | `/chunky progress`               | Show generation progress                    |
| `/chunky border add`    | `/chunky border add`             | Add a ChunkyBorder at the current selection |
| `/chunky border list`   | `/chunky border list`            | List all ChunkyBorder borders               |
| `/chunky border remove` | `/chunky border remove`          | Remove a ChunkyBorder border                |

### Open Parties and Claims (land protection)

OPAC is primarily GUI-driven via the Xaero's World Map overlay. These admin commands exist:

| Command | Syntax | Description |
| --- | --- | --- |
| Grant extra claims | `/lp user <player> permission set xaero.pac_max_claims <N>` | Override max claim count for a player |
| Bonus claims | Set via OPAC in-game UI (OP config menu) | Grant bonus chunks or forceloads per-player |

Claim settings are configured in `config/openpartiesandclaims/openpartiesandclaims-server.toml`. Current limits:

- **16 chunks** per player (4×4 area)
- **0 forceloaded** chunks (forceloading disabled - breaks autopause)
- **3 chunk** max claim distance (must claim near existing claims)
- Claims expire after **90 days** of inactivity
- Claims sync to Xaero's maps so all players can see boundaries

### Ledger (grief investigation)

| Command            | Syntax             | Description                                       |
| ------------------ | ------------------ | ------------------------------------------------- |
| `/ledger`          | `/ledger`          | Open the Ledger search GUI                        |
| `/ledger search`   | `/ledger search`   | Search block changes by player, area, or time     |
| `/ledger inspect`  | `/ledger inspect`  | Toggle inspect mode (click blocks to see history) |
| `/ledger rollback` | `/ledger rollback` | Rollback changes matching search criteria         |
| `/ledger restore`  | `/ledger restore`  | Restore rolled-back changes                       |

### Styled Chat (chat formatting)

Styled Chat handles chat formatting server-side. Configuration is in `data/config/styled-chat/` - no in-game commands for admins.

## Dimension teleportation

Teleport between dimensions using vanilla `/execute`:

```
# Teleport yourself to the Nether
/execute in minecraft:the_nether run tp @s <x> <y> <z>

# Teleport yourself to the End
/execute in minecraft:the_end run tp @s <x> <y> <z>

# Teleport yourself to Paradise Lost (Aether) - Y=200, floating islands
/execute in paradise_lost:paradise_lost run tp @s 0 200 0

# Teleport another player to a dimension
/execute in minecraft:the_nether run tp <player> <x> <y> <z>

# Quick jumps to each dimension's spawn area
/execute in minecraft:overworld run tp @s 0 64 0
/execute in minecraft:the_nether run tp @s 0 64 0
/execute in minecraft:the_end run tp @s 100 50 0
/execute in paradise_lost:paradise_lost run tp @s 0 200 0
```

## Useful admin recipes

### Player management

```bash
# Whitelist (RCON)
docker exec -i mc rcon-cli "whitelist add PlayerName"
docker exec -i mc rcon-cli "whitelist remove PlayerName"
docker exec -i mc rcon-cli "whitelist list"

# Operators
docker exec -i mc rcon-cli "op PlayerName"
docker exec -i mc rcon-cli "deop PlayerName"

# LuckPerms groups
docker exec -i mc rcon-cli "lp user PlayerName parent add admin"
docker exec -i mc rcon-cli "lp user PlayerName parent remove admin"

# Grant extra claim chunks
docker exec -i mc rcon-cli "lp user PlayerName permission set xaero.pac_max_claims 32"

# Give creative mode
docker exec -i mc rcon-cli "gamemode creative PlayerName"
docker exec -i mc rcon-cli "gamemode survival PlayerName"
```

### World management

```bash
# Set difficulty
docker exec -i mc rcon-cli "difficulty hard"

# Change time
docker exec -i mc rcon-cli "time set 0"           # dawn
docker exec -i mc rcon-cli "time set 6000"        # noon
docker exec -i mc rcon-cli "time set 18000"       # midnight

# Weather
docker exec -i mc rcon-cli "weather clear"
docker exec -i mc rcon-cli "weather thunder 600"   # 30 seconds of thunder

# World border
docker exec -i mc rcon-cli "worldborder center 0 0"
docker exec -i mc rcon-cli "worldborder set 16384"  # 8192 radius

# Set spawn
docker exec -i mc rcon-cli "setworldspawn 0 64 0"

# Game rules
docker exec -i mc rcon-cli "gamerule doInsomnia false"
docker exec -i mc rcon-cli "gamerule playersSleepingPercentage 50"
docker exec -i mc rcon-cli "gamerule keepInventory false"
```

### Performance diagnostics

```bash
# Quick health check
docker exec -i mc rcon-cli "spark health"

# Who's online
docker exec -i mc rcon-cli "list"

# Profile for 60 seconds
docker exec -i mc rcon-cli "spark profiler start"
# ... wait ...
docker exec -i mc rcon-cli "spark profiler stop"

# Memory
docker exec -i mc rcon-cli "spark heapsummary"

# Force GC
docker exec -i mc rcon-cli "spark gc"
```

### Map and rendering

```bash
# Trigger BlueMap render
docker exec -i mc rcon-cli "bluemap update"

# Purge and rebuild a map
docker exec -i mc rcon-cli "bluemap purge world"
docker exec -i mc rcon-cli "bluemap update"

# Pre-generate chunks (Chunky)
docker exec -i mc rcon-cli "chunky world minecraft:overworld"
docker exec -i mc rcon-cli "chunky center 0 0"
docker exec -i mc rcon-cli "chunky radius 5000"
docker exec -i mc rcon-cli "chunky start"

# Check progress
docker exec -i mc rcon-cli "chunky progress"
```

### Backup and recovery

```bash
# Trigger immediate backup
./scripts/backup-now.sh

# Manual save
docker exec -i mc rcon-cli "save-all flush"

# List recent backups (on server)
export RESTIC_REPOSITORY="s3:https://${R2_ACCOUNT_ID}.r2.cloudflarestorage.com/${R2_BUCKET}"
export AWS_ACCESS_KEY_ID="$R2_ACCESS_KEY_ID"
export AWS_SECRET_ACCESS_KEY="$R2_SECRET_ACCESS_KEY"
export RESTIC_PASSWORD
restic snapshots --last 5
```

### Server operations (SSH)

```bash
# SSH into the server
ssh -i ~/.ssh/mc_deploy_key deploy@DROPLET_HOST

# RCON interactive console
ssh -i ~/.ssh/mc_deploy_key deploy@DROPLET_HOST \
  'cd ~/server && docker exec -it mc rcon-cli'

# Single RCON command
ssh -i ~/.ssh/mc_deploy_key deploy@DROPLET_HOST \
  'cd ~/server && docker exec -i mc rcon-cli "list"'

# Tail server logs
ssh -i ~/.ssh/mc_deploy_key deploy@DROPLET_HOST \
  'cd ~/server && docker compose --profile cloud logs -f mc'

# Manual deploy
ssh -i ~/.ssh/mc_deploy_key deploy@DROPLET_HOST \
  'cd ~/server && ./scripts/deploy.sh --pull --non-interactive'

# Restart without full deploy
ssh -i ~/.ssh/mc_deploy_key deploy@DROPLET_HOST \
  'cd ~/server && docker compose --profile cloud restart mc'
```

## Discord commands

All slash commands are **guild-scoped and owned by the discord-sync bot** (`scripts/discord-sync.py`). The dcintegration mod shares the same bot token but only handles the chat bridge (game↔Discord chat, join/leave/death/advancement posts) - its command feature stays disabled or it wipes these commands on every server boot. If commands vanish from the Discord client, see [README → Discord integration](README.md#discord-integration).

### Registration (any Discord user)

| Command                          | Description                                                                 |
| -------------------------------- | --------------------------------------------------------------------------- |
| `/register <minecraft_username>` | Link your Discord account to a Minecraft username (verified against Mojang) |
| `/unregister`                    | Remove your Minecraft username link and whitelist entry                     |

Both commands post a notification to `#minecraft` so admins can see who registered.

Role-based sync: Discord roles (`Player`, `Admin`) automatically sync to the Minecraft whitelist and ops list via RCON every 60 seconds.

### `/mc` admin commands (requires @Admin Discord role)

All `/mc` commands are audit-logged to `#minecraft`. Commands that target a player validate the username and check they're online (where relevant).

#### Info

| Command | Description |
| --- | --- |
| `/mc status` | Server health (TPS, MSPT, memory), online players with coordinates, chunk pre-generation progress per dimension, and full roster with Discord names and roles |

#### Social

| Command                              | Description                                         |
| ------------------------------------ | --------------------------------------------------- |
| `/mc say <message>`                  | Broadcast a message to in-game chat (max 200 chars) |
| `/mc whisper <player> <message>`     | Send a private message to a player in-game          |
| `/mc weather <clear\|rain\|thunder>` | Change the weather                                  |

#### Moderation

| Command | Description |
| --- | --- |
| `/mc tempban <player> [minutes]` | Temporarily ban a player (default 10 min, max 1440). Auto-pardons when the timer expires |
| `/mc pardon <player>` | Unban a player and cancel any pending tempban timer |

#### Maintenance

| Command | Description | Limits |
| --- | --- | --- |
| `/mc restart` | Full server restart (30s countdown, kick, save, restart game + web services) | 10 min cooldown, button confirmation |
| `/mc map-refresh` | Trigger a BlueMap re-render | 5 min cooldown |

#### Player tools

| Command | Description | Limits |
| --- | --- | --- |
| `/mc heal <player>` | Fully restore health | Player must be online |
| `/mc feed <player>` | Fully restore hunger | Player must be online |
| `/mc fly <player> <on\|off>` | Toggle creative flight | Player must be online |
| `/mc invuln <player> <on\|off>` | Toggle invulnerability | Player must be online |
| `/mc extinguish <player>` | Put out fire on a player | Player must be online |
| `/mc kill <player>` | Kill a player | Button confirmation |
| `/mc give <player> <item> [count]` | Give an item (e.g. `diamond_sword`, `minecraft:golden_apple`) | Max 64 per call |
| `/mc effect <player> <effect> <seconds> [amplifier]` | Apply a status effect (e.g. `speed`, `minecraft:strength`) | Max 3600s, amplifier 0-5 |
| `/mc enchant <player> <enchantment> [level]` | Enchant the item the player is holding (above vanilla limits) | Max level 255 |
| `/mc gamemode <player> <mode> [minutes]` | Change game mode (auto-reverts to survival if minutes given) | Max 60 min auto-revert |
| `/mc xp <player> <amount>` | Give experience levels | Max 100 levels |
| `/mc claims <player> <count>` | Set a player's maximum claim chunks | Max 128 |

#### Safety model

- **Allowlist only** - no free-text RCON passthrough, every command is predefined
- **Input validation** - player names checked against `^[a-zA-Z0-9_]{3,16}$`, item/effect/enchantment IDs validated against Minecraft identifier format
- **Cooldowns** - restart (10 min), map-refresh (5 min)
- **Confirmation buttons** - restart, kill, tempban
- **Audit trail** - every `/mc` command, `/register`, and `/unregister` posts to `#minecraft`

## Scripts reference

See the [Scripts table in README.md](README.md#scripts) for the full list with context. Day-to-day operational scripts:

| Script | Usage |
| --- | --- |
| `./ops rcon "list"` | Any RCON command from your Mac (auto-detects local vs production) |
| `./ops doctor` | Full production health triage in one command |
| `./ops deploy --pull` | Full deploy on the server: countdown, kick, restart, config sync |
| `./ops backup` | Trigger an immediate backup (on the server) |
| `./dev up` / `./dev down` / `./dev logs` | Start/stop/logs for the local dev server |
| `./ops restart <name>` | Force-recreate a production sidecar (refuses mc) |
| `./ops stats --once` | Production system + container snapshot |
| `./ops logs` | Interactive log tailing (streams — humans only, agents use `docker logs --tail`) |
