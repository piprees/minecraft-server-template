---
title: deploy.sh sequence
description: The 17 numbered sections of scripts/deploy.sh, what each guarantees, and what breaks if it's skipped
tags: [deploy.sh, countdown, whitelist, seed, c2me, sync-mods, gamerules, dimensions]
---

# deploy.sh sequence

`scripts/deploy.sh` runs **on the server**, from the stack bundle (`.stack/current/stack/scripts/deploy.sh`). CI invokes it for every full-tier deploy; `./ops update` invokes it directly over SSH. It is idempotent-ish (safe to re-run) but not cheap — a full run against ~150 mods and 80+ custom dimensions takes 3–15 minutes. The numbering below matches the `# N. ...` section comments in the script itself. Find them with:

```bash
grep -nE '^# [0-9]+[a-z]?\. ' scripts/deploy.sh
```

Note that only 1 and 6–17 carry a numbered comment; 2–5 are the unlabelled shutdown block between `# 1. In-game countdown warnings` and `# 6. Pull Docker images`. If the grep above returns a different set than this file describes, trust the script.

## 1–5: shutting the world down safely

1. **In-game countdown** — 60s/30s/10s/5s/3/2/1 warnings via `say`, skipped if no players are online (falls back to the same path as `--quick`).
2. **Block new connections** — stops `discord-sync` (so role-sync can't re-whitelist mid-restart) and clears the whitelist via `clear_whitelist()`, which verifies emptiness by re-reading `whitelist list` afterwards rather than trusting the command succeeded.
3. **Kick all players** — `kick @a`, waits up to 30s for `get_player_count` to hit zero, force-kicks anyone still connected.
4. **Quiet-boot gamerules + save** — sets `doMobSpawning`/`randomTickSpeed`/`doDaylightCycle`/`doWeatherCycle`/`doFireTick` off/0 **before** stopping mc, because gamerules persist in `level.dat` — the _next_ boot starts quiet, which makes dimension activation dramatically cheaper (spawn + game-event work during chunk load is the expensive part). Then `save-off` → `save-all flush` → `save-on`.
5. **Stop mc cleanly** — `docker compose stop mc`.

If mc wasn't responding to RCON at all (`server_alive` false), steps 1–5 are skipped entirely — there's nothing to warn or save.

## 6–10b: rebuilding state while mc is stopped

6. **Pull Docker images** — tag derived from `STACK_VERSION` (`v2.13.0` → `2.13.0`) so images match the bundle exactly.
7. **Re-run the seed container** — `--force-recreate --no-deps seed`. This is what actually lays platform defaults + consumer overlay into the shared `stack-config`/`stack-mods` volumes; without recreating it, a `compose up` would just reuse the already-exited seed container and nothing new lands.
8. **Seed mod configs into `data/config/`** — copies the bundle's `config/` tree (minus nginx/datapacks/kuma/cloudflare/extractors — those aren't mod configs) into `data/config/`, then overlays `overlay/config/` on top (with `overlay/config/custom-dimensions/` routed separately to `data/config/custom-dimensions/overlay/`, since the mod resolves that overlay itself). **Must run before mc starts** — several mods auto-generate config on first boot, and if that happens before this copy, the auto-generated default wins over the bundle's version.
   - Also syncs platform + overlay datapacks into `data/world/datapacks/`, then strips datapack overrides belonging to mods the consumer removed (an orphaned `structure_set` reference fails registry load and blocks the boot). 8b. **Copy in-house mod jars** (`local-mods/*.jar` → `data/mods/`) — must happen here, mc still stopped, **before** step 11 starts the stack. Doing this later (after the health wait) meant a broken jar could never be replaced by the very deploy shipping it, and even a healthy deploy would only pick up new jars one restart late. Also runs `patch-mod-data.py` here to repair known-bad data inside third-party jars (currently: Epic Dungeons' CamelCase loot ids). 8c. **Enforce single-key config invariants** — re-writes `c2me.toml`'s `[vanillaWorldGenOptimizations] useDensityFunctionCompiler = false` (c2me strips this key from its own config on every boot, so it must be re-applied every deploy or every custom dimension generates as a clone of the main world's noise) and silences DistantHorizons' GC-warning nag in `DistantHorizons.toml` (re-enabled later, in section 14).
9. **Enforce Discord integration config** — rewrites `botToken`, routes chat to `DISCORD_CHAT_CHANNEL_ID` (falling back to the admin channel), and — critically — calls `ensure-discord-command-owner.py` to guarantee `[commands] enabled = false` in `Discord-Integration.toml`. If that flips true, the mod bulk-overwrites the guild slash-command registry on every mc boot and silently deletes `/mc` + `/register` (the shared-bot-token trap).
10. **Prune stale mod jars** — reads `mods-manifest.txt` from the `stack-mods` volume (written by the seed's resolver) and deletes any jar in `data/mods/` that isn't in that manifest, the local-mods manifest, or `local-mods/`. Fails loudly (`exit 1`) if the manifest itself is missing — refusing to prune blind rather than deleting jars it can't account for. 10b. **Sync missing managed mods/datapacks** — `sync-mods.sh` fetches only files the manifest expects but `data/` lacks. `MODS_FILE`/`DATAPACKS_FILE` are empty by default specifically so itzg's entrypoint makes **zero** network requests at boot; this step is the only place a mod-list change actually touches the network, and only when the list changed. A failed download here aborts the deploy (booting without a worldgen mod would corrupt chunks).

## 11–15: bringing it back up

11. **Start the stack** — `compose up -d --remove-orphans`. A mod-sync boot can restart mc mid-download (Modrinth rate limits), which can abort compose's own dependency wait even though mc recovers under its restart policy — the script doesn't die on that, because the RCON wait in section 12 is the real gate. Autopause suspension and backup pausing are re-applied here (the container was just recreated, so the pre-restart marker files are gone with the old filesystem). Then force-recreates sidecars: `unmined-render nav-proxy pack-web cloudflared mod-checker discord-sync idle-tasks` (kuma-init is deliberately NOT in this list — see the skill's traps section).
12. **Wait for RCON healthcheck** — up to 600s (many-dimension boots regularly exceed 5 minutes), polling every 10s, re-asserting autopause suspension each poll in case mc crash-restarted mid-boot. On timeout: `exit 1` with the whitelist still empty — it recovers from `.env` on the next boot, not automatically here.
13. **Start anything compose's dependency wait gave up on** — a second `up -d --remove-orphans`, tolerant of failure.
14. **World borders + game rules + dimensions + permissions + spawn** — world borders are mod-owned (`custom-dimensions`' `WorldBorderManager`, not RCON) since v4 Phase 3, so this section just activates the Nether/End/Paradise Lost dimensions for Chunky (forceload one chunk, save, remove) and runs one-time setup for any custom dimension not yet marked done in `data/.dimension-setup/` (gated so it only fires once per dimension, ever). Restores real game rules (spawning/ticking/cycles back on — this is also where quiet-boot mode from section 4 ends), re-enables DH distant generation, reloads ServerCore + datapacks, runs `setup-permissions.sh`, and sets world spawn from `.env` **only if** the dimension config has no config-driven spawn (config wins over env if both are present).
15. **Restore the whitelist** — from the `WHITELIST` env var captured before it was cleared, then `whitelist on`, then restarts `discord-sync`.

## 16–17: wrap-up

16. **Welcome-back message** via RCON `say`.
17. **Docker prune** (`--filter until=24h`) and deletion of `debug/chunk-*.txt` reports older than 3 days (Chunky pregen accumulates hundreds of these when Epic Dungeons' loot-id bug fires feature-placement errors).

## What's genuinely load-bearing vs merely tidy

Skipping any of 8, 8b, 8c, or 10b produces a server that boots but is subtly wrong (stale config, wrong worldgen, missing mods) rather than one that fails loudly. Skipping 12 (the health wait) or 15 (whitelist restore) produces an outage or a locked-out playerbase, which is at least visible. Section 17 is genuinely just cleanup — safe to skip if you're reasoning about correctness rather than disk usage.
