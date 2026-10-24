# Admin and Discord parity on 26.3

Every admin and Discord feature the stack runs on 1.21.1, how it is checked on 26.3, and the result (P6). `scripts/admin-parity-check.sh` runs the RCON half headless against the 26.3 smoke stack; `smoke-test-26.yml` runs it with no player online and again while the vanilla client is connected (`--player`). `scripts/smoke-26.sh check` is the boot half. Results below are from the local 26.3 stack (MC 26.3, Fabric Loader 0.20.1, the 73-mod list, custom-dimensions-26).

| Feature | Source | How it is checked on 26.3 | Result |
| --- | --- | --- | --- |
| Role sync: `whitelist add/remove`, `op`, `deop` | `discord-sync.py` role sync, `/register`, `/unregister` | admin-parity-check.sh, replies matched | Pass |
| `/mc status`: `list`, `chunky progress`, `data get entity <p> Pos` | discord-sync.py | admin-parity-check.sh | Pass |
| `/mc status`: TPS, MSPT and memory from `spark health` | discord-sync.py | admin-parity-check.sh reads the log: spark 26.3 answers with an upload to spark.lucko.me, after RCON has returned | RCON gets nothing; discord-sync falls back to vanilla `tick query` (TPS, MSPT). Memory shows `?` on 26.3 |
| `/mc say`, `whisper`, `weather`, `tempban` (`kick`, `ban`), `pardon`, `kill`, `give`, `effect`, `gamemode`, `xp` | discord-sync.py | admin-parity-check.sh | Pass |
| `/mc heal`, `feed`, `fly`, `invuln`, `extinguish`, `enchant` (Essential Commands) | discord-sync.py | admin-parity-check.sh; with no player online Essential Commands answers "A player is required" or "No player was found" | Parse: pass. Against a connected player: blocked by the join gate (below) |
| `/mc claims`: `lp user <p> permission set xaero.pac_max_claims <n>` | discord-sync.py | admin-parity-check.sh: parses, LuckPerms replies asynchronously (no RCON reply, as on 1.21.1) | Pass (accepted) |
| `/mc border`: `worldborder get/center/set` | discord-sync.py | admin-parity-check.sh | Pass |
| `/mc border`: `chunky border add` | discord-sync.py | RCON | Fails: chunkyborder has no 26.x build (register: replaced by per-dimension borders our compiler sets); cutover-removals.md |
| `/mc restart`: `say` countdown, `kick @a`, `save-all flush` | discord-sync.py | admin-parity-check.sh (`kick` against an absent name) | Pass |
| Audit relay of in-game admin actions | discord-sync.py `ADMIN_ACTION_RE`, game rule `log_admin_commands` | 26.x logs system messages as `System chat: [Name: action]`; the regex takes the prefix (test_discord_sync_status.py); admin-parity-check.sh checks `log_admin_commands` is true | Pass |
| Audit log of `/mc` commands to the Discord channel | discord-sync.py `audit()` | Discord side only | Needs the owner: with a bot token, run `/mc say hello` and see the audit line in #minecraft |
| Slash command registration, guild-scoped | discord-sync.py | Discord side only | Needs the owner: on a 26.3 stack with `DISCORD_BOT_TOKEN`, check the discord-sync log for "Slash commands synced" |
| Chat bridge | dcintegration, `config/dcintegration/` | Modrinth: no Fabric 26.3 build (newest 26.2); the 26.3 list carries it as waiting (RB-09) | Waiting for the mod. Boot gate reports it SKIP. When a build ships: check its config keys against the jar and a boot with the token unset |
| deploy.sh game-rule enforcement and quiet boot | `restore_game_rules`, step 4, step 12 | `mc_game_rule` maps each 1.21.1 name to its 26.3 id (`scripts/game-rules.tsv`, from the 26.3 registry report); admin-parity-check.sh sets every rule with the live value | Pass (13 rules; `doFireTick true/false` becomes `fire_spread_radius_around_player 128/0`) |
| New-world game-rule defaults | `config/boring_default_game_rules/` | boring-default-game-rules is not on the 26.3 list (register: replaced) | deploy.sh's enforcement sets the same rules on every deploy (cutover-removals.md) |
| LuckPerms groups `player`, `admin`, `default > player` | `scripts/setup-permissions.sh` | admin-parity-check.sh runs it; every `lp` command parses | Pass (accepted; LuckPerms replies asynchronously) |
| Ledger | ledger 1.3.25 | boot gate (loaded); `ledger status` parses | Pass |
| spark | spark | boot gate (loaded); `spark health` answers in the log | Pass (see `/mc status`) |
| Essential Commands | essential-commands | boot gate (loaded); `ec` root parses | Pass |
| Open Parties and Claims | open-parties-and-claims | boot gate (loaded); its claim limit through LuckPerms | Pass |
| Autopause | itzg `ENABLE_AUTOPAUSE` in docker-compose.yml | the same image family and compose block on the java25 tag | Needs the owner: leave a 26.3 server empty for `AUTOPAUSE_TIMEOUT_EST` and see "Pausing Java process" in `docker logs mc` |
| Vanilla client join | RB-02 | `scripts/e2e/vanilla-join.sh` | Fails on the current list: Fabric's registry sync refuses the client, naming `bookshelf`, `brigadier` and `farmersdelight` |
