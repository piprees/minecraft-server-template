# Troubleshooting

> **Single source of truth for problems.** Every known trap, platform quirk, and open issue lives here. Nothing in this repo should describe a problem in its own words — link here instead.

**Every entry has a permanent ID.** Cite it from anywhere: `TROUBLESHOOTING.md#t14`, `../TROUBLESHOOTING.md#d3`, or just "see T14" in a script comment. IDs are stable — an entry keeps its ID even if the surrounding list is reordered, retitled, or thinned out. When an entry stops being true, delete the entry and its ID; never renumber the rest.

| Prefix | Range | What it covers |
| --- | --- | --- |
| **T** | [T1–T18](#architecture-traps) | Architecture traps — each has caused a real production incident |
| **P** | [P1–P4](#macos-local-dev) | macOS local-dev quirks (BSD tooling, toolchain) |
| **D** | [D1–D8](#dimension-lifecycle) | Custom-dimension lifecycle on a live world |
| **K** | [K1–K2](#known-issues) | Open issues — unfixed, on the watch list |

Related contracts: [`AGENTS.md`](AGENTS.md) (how to behave), [`COMMANDS.md`](COMMANDS.md) (command reference), [`mods/AGENTS.md`](mods/AGENTS.md) (in-house mod development, including portal-subsystem specifics).

---

## Start here

```bash
./ops doctor          # one SSH round-trip, full checklist, PASS/WARN/FAIL per item, exit 1 on any FAIL
```

Run this before anything else. It covers deploy drift, disk, every expected container, `ONLINE_MODE`/`ENFORCE_WHITELIST`, RCON + TPS, backup age, the Discord command registry, the modpack mirror, kuma-init, fail2ban and recent mc errors.

**Do not do these while diagnosing:**

| Never | Why |
| --- | --- |
| `docker logs -f`, `live-logs.sh`, `gh run watch` | Stream forever; trap a non-interactive session with no way out |
| Unbounded wait loops over SSH | A crashing container never becomes healthy. One `sleep N` outside a loop is fine |
| `docker restart mc` on production | Skips the countdown, kick, save-flush and whitelist dance. Use `deploy.sh` or Discord `/mc restart` |
| Retry a failing Kuma login | See [T7](#t7) — the password is never the problem, and retrying risks fail2ban |
| Poll a CI run repeatedly | Check once, hand over the Actions URL, stop |
| Anything that touches the game port on an interval | Defeats autopause |

**RCON silence usually means autopause, not an outage.** The JVM freezes after 10 minutes empty; `docker ps` still shows healthy. Silence + healthy container = paused.

---

## Symptom index

| Symptom | Go to |
| --- | --- |
| Slash commands (`/mc`, `/register`) vanished after an mc restart | [T1](#t1) |
| Config or mod changes didn't take effect after a deploy | [T2](#t2) |
| `signal only works in main thread` in discord-sync logs | [T3](#t3) |
| mc crash-loops with Modrinth `429 Too Many Requests` | [T4](#t4) |
| Players locked out — "You are not whitelisted" despite the role | [T5](#t5) |
| Kuma monitors deleted in the UI reappear | [T6](#t6) |
| `authIncorrectCreds` from kuma-init | [T7](#t7) — **stop, do not retry** |
| Chunky re-generates chunks already done | [T8](#t8) |
| Players dropped mid-session with no countdown | [T9](#t9) |
| Players' keybinds or voice settings reset after a pack update | [T10](#t10) |
| CI green but the server isn't running the new code | [T11](#t11) |
| A local test passed but the change was never live | [T12](#t12) |
| Map missing a dimension, or a quiet render log | [T13](#t13), [D8](#d8) |
| Snapshot count / R2 usage growing without bound | [T14](#t14) |
| A released dimension-config change deploys green and changes nothing | [T15](#t15) |
| Launchers download HTML instead of mod JARs | [T16](#t16) |
| RCON output truncated, concatenated, or empty; `/locate` never returns | [T17](#t17) |
| `Unknown dimension 'adventure:<slug>'` on a healthy server | [T18](#t18) |
| Mod build fails with a misleading Gradle task error | [P4](#p4) |
| A worldgen config change had no effect | [D2](#d2) |
| Boot hangs after deleting a dimension's world directory | [D3](#d3), [K1](#k1) |
| Custom dimensions all generate identical terrain | [D6](#d6) |
| `Error upgrading chunk`, RCON i/o-timeout, container healthy | [K1](#k1) |
| `TheChunkSystem` ConcurrentModificationException | [K2](#k2) |
| Can't connect / server won't start / backups failing / lag | [Common symptoms](#common-symptoms) |

---

## Architecture traps

Each of these has caused a real incident.

<a id="t1"></a>
### T1 — Shared Discord bot token

- **Symptom:** Discord slash commands (`/mc`, `/register`) disappear after mc restart.
- **Cause:** dcintegration (in mc) and discord-sync both log in as the same bot. If the mod's `[commands] enabled` flips true in the live `data/config/Discord-Integration.toml`, it bulk-overwrites the command registry on every mc boot and silently deletes `/mc` + `/register`. deploy.sh enforces `enabled = false`; discord-sync purges the global registry at boot as a second line of defence. The repo's `config/dcintegration/config.toml` is an intent doc, **not** the live schema.

<a id="t2"></a>
### T2 — Seed container must re-run on deploy

- **Symptom:** Config or mod changes don't take effect after deploy; mc boots with stale files.
- **Cause:** The `defaults-seed` container lays platform defaults + consumer overlay into shared volumes at boot. On a full deploy the seed container must be recreated so updated defaults/overlay take effect before mc starts. Nginx configs are bind-mounted, so nav-proxy and pack-web additionally need force-recreate to pick up config changes.

<a id="t3"></a>
### T3 — mcrcon and threads

- **Symptom:** `signal only works in main thread` exception in discord-sync logs.
- **Cause:** `mcrcon` arms SIGALRM, which raises under `asyncio.to_thread`. discord-sync.py's `ThreadSafeRcon` replaces it with socket timeouts — use that for any new RCON code in the bot.

<a id="t4"></a>
### T4 — Mod sync is seed-resolved, never API-at-boot

- **Symptom:** mc crash-loops with Modrinth `429 Too Many Requests` on every restart.
- **Cause:** `MODRINTH_PROJECTS` is gone: it made itzg re-resolve ~160 versions through api.modrinth.com on every sync boot and 429-crash-looped mc whenever the mod list changed. The seed container's `resolve-mods.py` resolves pins to direct URLs (cached forever in the stack-mods volume — version IDs are immutable) and `scripts/sync-mods.sh` downloads only files missing from `data/mods/` host-side, between the seed and mc's start (`MODS_FILE`/`DATAPACKS_FILE` are empty by default so mc itself makes zero network requests at boot). Stale jars are pruned by deploy.sh/dev-up.sh against `mods-manifest.txt` — in-house `local-mods/` jars are exempt. Hand-added jars in `data/mods/` will be pruned; ship them via `overlay/mods-extra.txt` or `local-mods/` instead. A failed required resolution fails the seed and blocks the boot loudly.

<a id="t5"></a>
### T5 — Whitelist as a door lock

- **Symptom:** Players can't join after a failed deploy; "You are not whitelisted" despite having the role.
- **Cause:** deploy.sh must clear the whitelist to block joins during restart and restore it after. If a deploy dies mid-way, players may be locked out — the itzg image restores from env on next boot, or restore manually via RCON.
- **Boundary:** `service.sh` refuses raw `mc` lifecycle operations (`die "Refusing raw MC lifecycle operation..."`). Use deploy.sh or Discord `/mc restart`.

<a id="t6"></a>
### T6 — Kuma is config-driven

- **Symptom:** Monitors deleted in the Kuma UI reappear after deploy.
- **Cause:** `config/uptime-kuma/kuma-config.json` is authoritative; kuma-init re-syncs every deploy and resurrects monitors deleted only via the UI. `KUMA_API_KEY` is a socket.io **session token** (`kuma-token.sh`), not the Prometheus API key.

<a id="t7"></a>
### T7 — Kuma auth is human-gated; do NOT debug it by retrying logins

- **Symptom:** `authIncorrectCreds` in kuma-init/kuma-provision logs.
- **Cause:** the admin account has 2FA, so password-only logins **always** fail `authIncorrectCreds` regardless of the password. The password isn't wrong — it's missing a TOTP. A session token is the only headless path, and minting one needs an interactive TOTP code (`./ops reauth-kuma`).
- **Stop rule:** repeated attempts risk Kuma's rate limit and the host's fail2ban. A locked account means rebuilding Kuma. On `authIncorrectCreds`: **stop calling**.
- **Usual root cause:** the token was wiped by a CI full deploy regenerating the server `.env` from GitHub secrets while the `KUMA_API_KEY` secret was empty (audited 2026-07-11: the consumer `.env` had no `KUMA_API_KEY` line, so `github-env-sync.sh` never pushed one, and a hand-installed token was overwritten — this left a deploy maintenance window open for hours).
- **Recovery (human-only):** `./ops reauth-kuma` → add the token to the consumer `.env` → `./ops github-env-sync` so CI deploys stop wiping it. The token must live in the GitHub `production` secret, not just `.env`.
- **A stuck maintenance window can be cleared without credentials:**
  ```bash
  docker exec uptime-kuma sqlite3 /app/data/kuma.db "DELETE FROM maintenance WHERE title='Deploy in progress';"
  docker restart uptime-kuma
  ```

<a id="t8"></a>
### T8 — Chunky markers

- **Symptom:** Chunky re-generates chunks that were already done.
- **Cause:** Pre-generation completion is tracked by `data/.chunky-*-complete` marker files. Delete them to force re-generation (e.g. after a border change).

<a id="t9"></a>
### T9 — Infra deploys must never recreate mc; `--no-recreate` is load-bearing

- **Symptom:** Players dropped mid-session with no countdown or warning after a sidecar-only change.
- **Cause:** Full deploys create mc WITH the temporary Modrinth override, then delete the override file — so a plain `docker compose up -d` afterwards sees mc as config-drifted and recreates it with **no countdown, players dropped mid-session** (2026-07-01). The infra step's first `up -d` carries `--no-recreate`; sidecars are updated by the explicit `--force-recreate --no-deps` list. Only deploy.sh, after the countdown, may recreate mc. `scripts/dc.sh` blocks a bare `up -d` on the server for the same reason.

<a id="t10"></a>
### T10 — Raw pack overrides clobber player settings

- **Symptom:** Players' keybinds, voice chat settings, or video options reset after a pack update.
- **Cause:** Prism re-applies everything in `modpack/overrides/` root on every pack update — a raw `options.txt`/`config/*` there wiped players' keybinds and voice chat settings repeatedly (fixed 2026-07-02). Only `servers.dat` ships raw. All client defaults go under `modpack/overrides/configureddefaults/` (merge/copy-if-missing) and are sourced from a reference Prism instance via `scripts/client-defaults.sh --diff`/`--sync`. New defaults are curated by hand — never bulk-copy the instance config dir (e.g. `NCR-Encryption.json` contains a secret).

<a id="t11"></a>
### T11 — The `.deployed` state file can lie

- **Symptom:** CI deploys go green but the server isn't running the new code.
- **Cause:** the consumer-diff half of tier detection uses `consumer_sha` in `~/server/.deployed`. If that file records a sha whose deploy never completed, every following push downgrades to pull tier and CI goes green while the server runs nothing — the historical "fix" was nuking the server, which only worked because it deleted the stale file. Guards (both in `deploy-reusable.yml`): state is written only on success, a state file with no mc container forces a full deploy, and the stack comparison never trusts the state file at all — it reads `readlink .stack/current` on the server.
- **Recovery:** if a server has `.deployed` but no containers, delete the file or dispatch the workflow manually (manual dispatch always deploys full).

<a id="t12"></a>
### T12 — Hand-patched shared volumes are reverted by every seed run

- **Symptom:** a local test of unreleased seed-image content (mod list, nginx templates) passes, but the change was never actually live — the "pass" came through the old config.
- **Cause:** `stack-config`/`stack-mods` volume contents come from the **defaults-seed image** (rebuilt only by CI on push), and the one-shot `seed` re-runs on any `compose up` without `--no-deps`, silently restoring image defaults over hand-patches.
- **Correct local test:** patch the volume, recreate ONLY the consuming service with `--force-recreate --no-deps` (or a plain `docker restart mc`), then verify the **rendered** state, never the patch:
  ```bash
  docker exec nav-proxy grep <expected> /etc/nginx/conf.d/default.conf
  ```
  (2026-07-13: a nav-proxy upstream change "passed" while proxying to the old upstream.)

<a id="t13"></a>
### T13 — The map is a static render with no live link to mc

- **Symptom:** agents look for map problems in mc, or read a quiet render log as a failure.
- **Cause:** `unmined-render` (uNmINeD CLI) renders every base world and every on-disk custom dimension to webp tiles + a self-contained OpenLayers viewer under `data/unmined-web/maps/<name>/`, plus a generated `index.html`. nav-proxy serves that bind mount directly at `map.DOMAIN` (per-dimension URLs are `map.DOMAIN/<slug>`) — **no upstream, so the map stays up while mc restarts or autopauses.** If `map.DOMAIN` is down, the problem is nav-proxy or the tunnel, never the game server. There is no RCON interface. Diagnose with `./ops map status` or `docker logs unmined-render`; force a pass with `./ops map render` (a restart renders immediately, then sleeps).
- **Renders are incremental, so a near-silent pass is the normal case.** Unchanged dimensions are skipped by mtime marker and only changed regions re-render. Don't treat "no new tiles" as broken without checking whether anything changed.
- **Dimension discovery is automatic** — see [D8](#d8).
- `UNMINED_INTERVAL` (compose default `6h`) sets the gap between passes; `0` disables the loop. `PREGEN_BORDER_RADIUS` (8192) bounds the base worlds, nether at /8.

<a id="t14"></a>
### T14 — Restic retention groups by hostname

- **Symptom:** snapshot count and R2 usage grow without bound (23 snapshots / ~50 GiB by 2026-07-24) despite the retention policy.
- **Cause:** `restic forget` groups snapshots by `(host, paths)`; the mc-backup container's hostname defaulted to its container id, and every full deploy recreates the sidecar — so each deploy era became its own retention group whose last snapshots were stranded forever.
- **Fix (in place):** `hostname: "${BRAND_SLUG:-adventure}-mc-backup"` pinned in `docker-compose.yml` — load-bearing, and brand-scoped so multiple servers sharing one bucket keep separate retention groups.
- **One-off cleanup across dead hosts:**
  ```bash
  docker exec mc-backup restic forget --group-by paths --keep-last N --prune   # plain --keep-last keeps N PER DEAD HOST
  docker exec mc-backup restic stats --mode raw-data                           # real usage, not snapshot count
  ```

<a id="t15"></a>
### T15 — A removed consumer dimension overlay used to keep applying forever

- **Symptom:** a released dimension-config change deploys green and changes nothing. The boot log warns about config you have already fixed. `data/config/custom-dimensions/dimensions/*.json` looks correct on the server, and the mod still uses something else.
- **Cause:** the staged overlay at `data/config/custom-dimensions/overlay/` is DERIVED from `overlay/config/custom-dimensions/`, but both `deploy.sh` and `dev-up.sh` only cleared it *inside* the "does the source directory exist" guard. Delete the consumer overlay and the staged copy survives — silently full-replacing every platform dimension file, for good. Found 2026-07-26 with 82 stale dimensions on the local consumer and 81 on production.
- **Fixed in v3.10.1** (both scripts now clear it unconditionally before rebuilding). **A server that predates v3.10.1 needs one manual clear**, because the stale copy is what stops the fix mattering:
  ```bash
  rm -rf ~/server/data/config/custom-dimensions/overlay        # then redeploy
  ls ~/server/data/config/custom-dimensions/overlay/dimensions | wc -l   # expect 0, or the consumer's own count
  ```
- **General rule:** anything staged into `data/` from a source directory is derived state. Clear it unconditionally and rebuild; never make the clear conditional on the source still existing, or removal becomes unrepresentable.

<a id="t16"></a>
### T16 — The mod mirror and packwiz index are build output

- **Symptom:** Launchers download HTML instead of mod JARs; packwiz auto-update serves stale mods.
- **Cause:** `modpack/dist/mods/` and `modpack/dist/packwiz/` are generated and pruned by `build-modpack.sh` — never hand-edit them. Mod downloads route via `mods.DOMAIN/mods/` (a tunnel path-rule straight to pack-web, bypassing nav-proxy) with Modrinth's CDN as the `.mrpack` fallback; launchers hash-verify either source. The packwiz index drives auto-updates on every launch.
- **Invariants:** `/mods/` in `pack-web.conf` must return a clean 404 (not the site-wide 301-to-homepage) or launchers download HTML instead of falling back; `.toml` files must never become edge-cached (they're the update signal, and `.toml` isn't in Cloudflare's default cache list — keep it that way); don't publish this pack on Modrinth (their upload validation rejects non-whitelisted mirror URLs).

<a id="t17"></a>
### T17 — RCON cannot carry an answer, and a green check may be reading nothing

- **Symptom:** a diagnostic command "works" but its output is one run-on
  string with lines concatenated and the end missing; or a check passes
  against a server that is paused, mid-restart, or returning empty responses
  under load; or `/locate` never returns and the terminal appears wedged.
- **Cause:** RCON concatenates feedback lines with no separator and truncates
  the response at a few KB, so ~280 audit rows come back unreadable and
  half-missing. It also carries no error type — a parse failure, a timeout
  and an empty result are indistinguishable, so an empty response under load
  reads exactly like success. Separately, slow commands are slow because of
  the COMMAND, not the channel: locating a vanilla village in the stock
  overworld times out at 120 s, and Chunky pre-generation does not change it
  (measured 2026-07-27).
- **Fix:** do not parse RCON output. Diagnostic commands write versioned JSON
  under `data/config/custom-dimensions/` and answer with a summary plus a
  path; checkers in `scripts/` assert over those files with no server
  running. `./dev verify` runs all of them. Contract and the full table:
  `mods/AGENTS.md` § Diagnostic artefacts. Rationale and rejected
  alternatives: `docs/spikes/SPIKE-REPLACE-RCON.md`.
- **Corollary:** run `scripts/check-dimension-drift.py` before trusting any
  worldgen assertion — see [D2](#d2). A world created under an older config
  makes every other check measure history.

<a id="t18"></a>
### T18 — A custom dimension is created on first entry, so it is absent until then

- **Symptom:** `execute in adventure:<slug> run seed` answers
  `Unknown dimension`, and the dimension is missing from every command that
  takes a dimension argument, on a healthy server with the config plainly
  present.
- **Cause:** `CreateWorldsMixin` filters vanilla's `createWorlds` loop down to
  the base worlds, so the ~80 custom dimensions are built by
  `DimensionManager.getOrCreateDimension` when a player first enters one.
  Distant Horizons and c2me build per-level state from
  `ServerWorldEvents.LOAD`, and paying that for every dimension at every boot
  is what this avoids.
- **Fix:** load it and poll — creation is queued to `END_SERVER_TICK`, so it
  lands a tick or two later, not immediately.
  ```bash
  docker exec -i mc rcon-cli "customdim load the_overgrowth"   # bare slug
  docker exec -i mc rcon-cli "execute in adventure:the_overgrowth run seed"
  ```
  The four base worlds are exempt: vanilla asks for them by key from paths
  with no lazy-creation hook, so they exist from the first tick.

---

## macOS local dev

<a id="p1"></a>
### P1 — `od -An -td8` produces single-byte values on BSD

The `8` is read as field width, not byte size. The unsigned variant `-tu8` works. For signed 64-bit random integers:

```bash
python3 -c "import os,struct; print(struct.unpack('<q', os.urandom(8))[0])"
```

<a id="p2"></a>
### P2 — `LEVEL_TYPE=flat` on the overworld breaks structure placement in ALL custom dimensions

The mod's `createDimensionOptions` uses `overworldOpts` as a template. When the overworld is flat, `overworldOpts.chunkGenerator()` is a `FlatChunkGenerator` — the `multi_biome` case checks `instanceof NoiseChunkGenerator`, fails, and falls through to the default branch which copies the flat generator. Confirmed via A/B test: same clone dim, same seed, flat overworld → all locates return "Could not find"; normal overworld → real distances.

<a id="p3"></a>
### P3 — `grep -P` does not exist on macOS

BSD grep has no PCRE. Use `grep -oE` (extended regex) or `sed`. BSD `grep -E` also doesn't support `\s` — use `[[:space:]]`. This has caused multiple CI and runtime failures, and recurred in the seed-rolling scripts after being documented.

<a id="p4"></a>
### P4 — `mise exec` is required for mod builds

`mods/mise.toml` pins `java = "temurin-21"`, but a global Java (e.g. 25) takes precedence. Gradle fails with a misleading task-creation error, not a clear wrong-Java message. Use `mise exec -- ./gradlew build`.

---

## Dimension lifecycle

Operating on dimensions that already exist on a live world. For authoring the JSON see `.claude/skills/custom-dimension-authoring/SKILL.md`.

<a id="d1"></a>
### D1 — Per-dimension seeds only apply at world creation time

Changing a seed in config has no effect on an existing dimension — the seed baked into level data at creation persists. Local dev: wipe `data/world` to test seed changes.

<a id="d2"></a>
### D2 — ALL worldgen config is creation-time-only, and survives everything short of a full world wipe

`type`, `noiseSettings`, `biomes`, `seed`. The dimension's generator is serialised into `level.dat`'s `WorldGenSettings` at creation; `registerDimensions` skips keys already in the registry and vanilla re-persists the stored generator on every save. Deleting the dimension's region directory only regenerates old-style chunks, and `customdim destroy` unloads the world but does NOT scrub the level.dat entry (verified empirically 2026-07-22). Applying a worldgen change requires wiping `data/world`.

The mod fingerprints creation-time worldgen config (`config/custom-dimensions-fingerprints.json`) and WARNs at boot when a dimension's config has drifted from what its world was created with. It never deletes or regenerates on its own — a WARN is information, not a failure.

<a id="d3"></a>
### D3 — Fully removing a dimension requires a level.dat scrub

World-dir deletion alone is a boot wedge. A lingering `Data.WorldGenSettings.dimensions` entry re-creates the dim every boot; with the world dir gone it REGENERATES spawn chunks each time, which can hit [K1](#k1) on the boot itself.

Proven procedure (2026-07-24, local) — **order matters, every file must be gone BEFORE `docker start`**:

1. Stop mc.
2. Back up `data/world/level.dat`.
3. `pip install nbtlib` in a scratch venv.
4. Delete the dimension keys from `Data.WorldGenSettings.dimensions`.
5. Delete the world dirs, config files, `custom-dimensions-fingerprints.json` entries, and `portal_links.json` records referencing the dims.
6. Start mc.

<a id="d4"></a>
### D4 — `dev-up.sh` skip-if-exists blocks config upgrades

Config files are seeded with `if [[ ! -f "$dest" ]]`, so a consumer upgrading across a schema change keeps the old file without the new fields. Delete the target under `data/config/` before `./dev up`, or copy the new config over by hand.

<a id="d5"></a>
### D5 — Template and consumer configs must stay in sync

The platform copy under `config/` is the source of truth; the consumer copy under `data/config/` must be an exact copy. After any config edit, **always copy, never diff-and-decide**.

<a id="d6"></a>
### D6 — c2me DFC: verify via log grep, not config inspection

`useDensityFunctionCompiler` must stay `false` or every custom dimension silently clones the main world. The key IS read by c2me before c2me strips it from the config file on boot, so its absence from `c2me.toml` afterwards is expected and proves nothing. The log line confirms it was applied:

```
Removing config entry .vanillaWorldGenOptimizations.useDensityFunctionCompiler because it is not used
```

`deploy.sh` (step 8c) and `dev-up.sh` re-patch it every time. A bare `docker restart mc` therefore boots **unpatched** — re-patch before every restart cycle in a local loop.

<a id="d7"></a>
### D7 — Seed-roll worker dirs need cleaning

- **`collective` must not be stripped from seed rolls.** 9+ mods depend on it (healingcampfire, nametagtweaks, nutritiousmilk, …). Without it, Fabric fails with a `FormattedException` listing every missing dep.
- **DistantHorizons configs leak into seedtest dirs.** Copying `data/config/` wholesale brings DH per-level state that causes map-loading warnings at boot. Delete `config/DistantHorizons` from worker dirs after copying.

<a id="d8"></a>
### D8 — A new dimension appears on the map only once it has generated chunks

`unmined-render` discovers dimensions by scanning `data/world/dimensions/<ns>/<slug>/region/*.mca` — there is no map config to write and no reload to call. A dimension that exists in config but has never been visited or pre-generated has no region files and renders nothing. Visit it, or let Chunky pre-gen reach it, then force a pass with `./ops map render`.

---

## Known issues

Open, unfixed, on the watch list.

<a id="k1"></a>
### K1 — Epic Dungeons loot ids crash feature placement → c2me wedges the main thread

*(2026-07-23 local; second trigger found 2026-07-24)*

`epic:chests/DungeonZombie` (uppercase = invalid identifier path) throws `Non [a-z0-9/._-] character in path` during chunk feature placement when an Epic Dungeons dungeon generates. Under c2me the chunk upgrade fails once (`Error upgrading chunk [x, z] to "minecraft:features"`) and a main-thread sync load waiting on that chunk (RCON `forceload add`, `execute if block` on ungenerated chunks) then hangs FOREVER — RCON goes i/o-timeout while `docker ps` stays healthy.

**Second trigger:** deleting a runtime dimension's world directory without scrubbing its level.dat entry — the next boot re-creates the dim and regenerates its spawn chunks, and if a dungeon lands there the BOOT ITSELF wedges (hit twice 2026-07-24). See [D3](#d3).

**Diagnosis caveat:** spark's `Timed out waiting for world statistics` alone is NOT proof — it also fires through legitimately heavy boots (mass dimension creation can run 10+ min). Confirm with `Error upgrading chunk`/`DungeonZombie` counts and whether the log has stopped advancing.

**Recovery:** `docker stop -t 90 mc && docker start mc` (local only; production restarts go through deploy.sh), plus the [D3](#d3) scrub when the trigger is a regenerating deleted dim, or it wedges again every boot.

Upstream candidate — the loot table id is the mod's data bug.

<a id="k2"></a>
### K2 — c2me `TheChunkSystem` ConcurrentModificationException

`Error executing task on Chunk source main thread executor for <dim>` … `TheChunkSystem.lambda$onItemUpgrade$0`.

c2me 0.4.0-alpha's chunk-system rewrite races vanilla's entity manager during heavy multi-dimension chunk activity (boot world creation, forceloads). Non-fatal — the executor catches it — but bursts correlate with degraded TPS during boots. **Do NOT filter it from logs** (real error, upstream candidate). If it starts crashing servers, the module can be bypassed only by removing c2me; revisit on c2me updates.

---

## Common symptoms

Everyday problems with no incident behind them.

**Server won't start:**

| Symptom | Fix |
| --- | --- |
| Mod incompatibility | A mod has no 1.21.1 build. Check `./scripts/check-updates.sh`, mark it `?` or remove it |
| Missing dependency | Check the crash log for the mod ID, add the library to the mod list |
| Out of memory | Raise `MEMORY` in `.env`; look for `OutOfMemoryError` in logs |
| Port conflict | `lsof -i :25577` |
| Mod download fails | Verify the slug + pinned ID on Modrinth; use `./dev up --offline` if Modrinth is down |
| Modrinth `429` in the seed container | Only possible on a cold resolve cache (first ever boot) — the resolver paces requests and honours `Retry-After`. See [T4](#t4) |

**Can't connect:**

| Check | Command |
| --- | --- |
| Server running? | `docker ps` — mc should be `(healthy)` |
| RCON responds? | `docker exec -i mc rcon-cli "list"` (no response can just mean autopaused) |
| Whitelisted? | `docker exec -i mc rcon-cli "whitelist list"` — or did they `/register` + get the role? |
| Firewall open? | `sudo ufw status` — `25577/tcp` allowed |
| DNS resolves? | `dig mc.example.com` → server IP |
| Correct port? | `mc.example.com:25577` (the SRV record usually makes the bare hostname work) |

**Crash-loop triage.** Boot failures die before the game log exists, so `data/logs/latest.log` looks fine while the container loops — and `latest.log` is reset on every boot. Check the container itself:

```bash
docker inspect mc --format 'RestartCount={{.RestartCount}} Health={{if .State.Health}}{{.State.Health.Status}}{{end}}'
docker logs mc --tail 80      # init + mixin errors live here
ls data/crash-reports/        # tick-loop crashes (local)
```

A RestartCount above 0 is a crash you haven't explained yet. `Mixin apply ... failed` means a broken mod jar — usually fixed by `./ops update` pulling the current bundle, not by removing mods.

**Log surfaces.** Three different things get called "the logs":

| Surface | Command | Note |
| --- | --- | --- |
| Console | `./ops logs mc --tail 200` | **Filtered** by `config/log4j2-adventure.xml` |
| Raw game log | `./ops logs --latest --grep ERROR` | **Unfiltered** — shows what the console hides |
| Boot/init | `docker logs mc --tail 80` | The only place pre-game-log failures appear |

**Backups:** verify R2 credentials in `.env`; `restic snapshots` ("repository does not exist" → `restic init`); `df -h`; `docker logs mc-backup --tail 50`. See [T14](#t14) if counts look wrong.

**Voice chat:** UDP `24454` must be open in UFW **and** mapped in Docker. Icon error = mod version mismatch, re-import the `.mrpack`. Walls not muffling = Sound Physics is client-side and ships in the pack.

**World map:** see [T13](#t13).

**Uptime Kuma:** see [T6](#t6) and [T7](#t7).

**Discord:** see [T1](#t1). Registry ground truth is `GET /applications/<app_id>/guilds/<guild_id>/commands` with the bot token — the guild should list `register`, `unregister`, `mc`; global should be `[]`.

**Performance (lag, high MSPT):**

| Action | Command |
| --- | --- |
| Health check | `docker exec -i mc rcon-cli "spark health"` — TPS 20, MSPT < 50ms |
| Profile | `spark profiler start` … 30–60s … `spark profiler stop` |
| Pre-generate | idle-tasks runs Chunky automatically when empty; progress via `/mc status` in Discord |
| Reduce load | Lower `VIEW_DISTANCE`/`SIMULATION_DISTANCE` in `.env` |

**Chunk corruption:** `./ops wipe-chunk --block <x> <z>` moves the region file aside so it regenerates. The server must be stopped or the chunks unloaded — the script refuses while mc runs unless `--force`.

---

## Deep dives

Long-form write-ups that don't fit an entry:

| File | Covers |
| --- | --- |
| [`docs/known-issues/carpet-supplementaries-piston-crash.md`](docs/known-issues/carpet-supplementaries-piston-crash.md) | Carpet's piston mixin vs Supplementaries — the tick-loop crash, and why `scripts/patch-mod-data.py` strips one mixin on every deploy |

## Adding an entry

1. Pick the next unused number in the right prefix. **Never reuse a retired ID.**
2. Add `<a id="tN"></a>` immediately above the heading — the anchor is the contract, the title is not.
3. Write **symptom → cause → fix**, in that order. Keep the incident date; it's the evidence the trap is real.
4. Add a row to the [symptom index](#symptom-index).
5. Link to it from wherever someone would hit it — a script header comment, a doc, a skill. Don't restate it there.
