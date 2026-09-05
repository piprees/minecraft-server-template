# Troubleshooting

> **Single source of truth for problems.** Every known trap, platform quirk, and open issue lives here; nothing in this repo should describe a problem in its own words — link here instead. **Every entry has a permanent ID.** Cite it from anywhere: `TROUBLESHOOTING.md#t14`, `../TROUBLESHOOTING.md#d3`, or just "see T14" in a script comment. IDs are stable — an entry keeps its ID even if the surrounding list is reordered, retitled, or thinned out. When an entry stops being true, delete the entry and its ID; never renumber the rest.

| Prefix | Range | What it covers |
| --- | --- | --- |
| **T** | [T1–T14, T16–T19, T22–T27, T30–T80, T82–T95](#architecture-traps) | Architecture traps — each has caused a real production incident |
| **P** | [P1–P6](#macos-local-dev) | macOS local-dev quirks (BSD tooling, toolchain) |
| **D** | [D1–D6, D8–D9](#dimension-lifecycle) | Custom-dimension lifecycle on a live world |
| **K** | [K1–K2, K5–K7, K9](#known-issues) | Open issues — unfixed, on the watch list |

Related contracts: [`AGENTS.md`](AGENTS.md) (how to behave), [`COMMANDS.md`](COMMANDS.md) (command reference), [`mods/AGENTS.md`](mods/AGENTS.md) (in-house mod development, including portal-subsystem specifics).

---

## Start here

```bash
./ops doctor          # one SSH round-trip, full checklist, PASS/WARN/FAIL per item, exit 1 on any FAIL
```

Run this first. It covers deploy drift, disk, every expected container, `ONLINE_MODE`/`ENFORCE_WHITELIST`, RCON + TPS, backup age, the Discord command registry, the modpack mirror, kuma-init, fail2ban and recent mc errors.

**Never, while diagnosing:**

| Never | Why |
| --- | --- |
| `docker logs -f`, `live-logs.sh`, `gh run watch`; unbounded SSH wait loops | Stream or block forever. One `sleep N` outside a loop is fine |
| `docker restart mc` on production | Skips the countdown, kick, save-flush and whitelist dance. Use `deploy.sh` or Discord `/mc restart` |
| Retry a failing Kuma login | See [T7](#t7) — the password is never the problem, and retrying risks fail2ban |
| `/locate` (any form) on a server with players | Blocks the main thread until every client times out and drops. See [T17](#t17) |
| Poll a CI run repeatedly; touch the game port on an interval | Wastes context; defeats autopause |

**RCON silence usually means autopause, not an outage.** The JVM freezes after 10 minutes empty; `docker ps` still shows healthy. Silence + healthy container = paused.

---

## Symptom index

| Symptom | Go to |
| --- | --- |
| Slash commands (`/mc`, `/register`) vanished after an mc restart | [T1](#t1) |
| `sshd -T` still reports `passwordauthentication yes` after hardening | [T41](#t41) |
| Spoofed private source still passes the rate limits despite `rp_filter=1` | [T42](#t42) |
| A jail disabled in `jail.d/*.conf` is enabled again after `./ops harden` | [T43](#t43) |
| Voice chat broke for everyone right after a firewall change | [T44](#t44) |
| Players dropped from the game port well below the stated rate limit | [T45](#t45) |
| `./ops harden` ran an old script after a successful `./ops update` | [T46](#t46) |
| A dimension vanished from the map or pre-gen queue with no error | [T47](#t47) |
| A Moog's structure set's live spacing is larger than its jar and config say | [T48](#t48) |
| Which structures can spawn where disagrees with the jars | [T49](#t49) |
| A probe returns nothing, or the same value everywhere | [T50](#t50) |
| A copper portal frame stops lighting and nothing changed | [T85](#t85) |
| A subagent shows "running" but has produced nothing for hours | [T86](#t86) |
| Every mutation "reddens nothing", or a probe reports no activity | [T88](#t88) |
| Two arrival zones for one destination after a break and re-light | [T89](#t89) |
| A reimplementation of the mod's arithmetic is one block out at negative coordinates | [T87](#t87) |
| A portal renders once, then draws sky on every approach after a traversal | [T93](#t93), [T94](#t94) |
| A portal's far side is thin and never fills, at 7 chunks and 0 render sections | [T95](#t95) |
| A portal's far side does not zoom when a spyglass, a drawn bow or a speed effect zooms the world | [T96](#t96) |
| Structure spacing arithmetic disagrees with the world | [T51](#t51) |
| mc watchdog-crashes a few minutes after a local world reset, parked in `save-all` | [T57](#t57) |
| The same few structures repeat endlessly in one dimension | [T52](#t52) |
| One wanted structure fills half the dimension | [T53](#t53) |
| Structures overlap, or a huge one gets a tiny one's clearance | [T54](#t54) |
| A worldgen mod's biomes generate but its landforms never appear | [T55](#t55) |
| A placement rule keyed on the assigned structure changes nothing | [T56](#t56) |
| A biome band is reported dead but the biome generates | [T58](#t58) |
| A listed biome holds no ground and its band looks correct | [T59](#t59) |
| A banked measurement describes a world the config no longer produces | [T60](#t60) |
| An `rcon-cli` batch exits 0 having answered only the first few commands | [T61](#t61) |
| The newest measurement of a dimension is of a config nobody shipped | [T62](#t62) |
| A check reports success having examined nothing | [T63](#t63) |
| The server watchdog-crashes during a read-only diagnostic sweep | [T64](#t64) |
| A band changes nothing, and the band checker reports overlapping pairs | [T65](#t65) |
| A biome is in the jar catalogue, its mod is loaded, and it is in no live registry | [T66](#t66) |
| A batched loop runs one iteration and exits 0 | [T67](#t67) |
| A fresh world's first boot logs `Failed to load server level` and then starts fine | [T68](#t68) |
| A dimension listing a dozen biomes generates one or two, bands green | [T69](#t69) |
| A comment-only edit makes a roll re-measure what the bank already held | [T70](#t70) |
| A pack-wide score threshold keeps selecting the same dimensions | [T73](#t73) |
| A band boundary is moved off a clamp rail, every arm is green, and generation order still decides | [T71](#t71) |
| A climate window needs measuring at a different seed and every route needs a restart | [T72](#t72) |
| A grep over `docker logs` finds none of the boot lines on a healthy container | [T74](#t74) |
| A boot crash-loops with `Biome source config is not set` after a biome source is replaced | [T75](#t75) |
| An End-settings dimension puts one biome across almost the whole map | [T76](#t76) |
| A pinned mod is absent from the local server and nothing errored | [T77](#t77) |
| A local config change had no effect and the boot was green | [T78](#t78) |
| `ClassCastException` from Better Caves repeats through chunk generation | [T79](#t79) |
| `IndexOutOfBoundsException: bitIndex < 0` from Better Caves; chunks fail at `minecraft:carvers` | [T84](#t84) |
| Every player is kicked at join: "registry entries that are unknown to this client" | [T80](#t80) |
| Random client crashes near water: `Missing Palette entry`, `be_getWaterColor` in the stack | [T82](#t82) |
| `./dev` answers `No Prism instance found for '--<some flag>'`, and the instance exists | [T83](#t83) |
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
| Launchers download HTML instead of mod JARs | [T16](#t16) |
| RCON output truncated, concatenated, or empty; `/locate` never returns | [T17](#t17) |
| `Unknown dimension 'adventure:<slug>'` on a healthy server | [T18](#t18) |
| One listed biome covers a whole dimension; the rest never appear | [T19](#t19) |
| Structures generating in a void/superflat dimension | [T22](#t22) |
| `structures.mode`/`exclude` listing a Moog's/YUNG's set does nothing | [T23](#t23) |
| A `structures.force` position never generates its structure; vanilla fortresses/mineshafts/strongholds never found organically | [T25](#t25) |
| A RETURN/`@ModifyReturnValue` hook on StructureWeightSampler never fires | [T24](#t24) |
| Flat slabs of terrain under floating-island structures; buildings on cliff shelves or sunk into the floor | [T26](#t26) |
| A fill kernel makes solid terrain in open sky | [T27](#t27) |
| A fix verified in `.stack/` is missing later; `current` moved | [T30](#t30) |
| New seed set but the world regenerates the old terrain; an overlay `seed`/`spawn` override looks ignored | [T31](#t31) |
| A seed's map and its banked facts contradict each other | [T32](#t32) |
| Forced structures generate nothing in a `void` dimension; `produced no start` | [T33](#t33) |
| A dimension generates biomes its `biomes` list never named; `structure-census` reports `FACTS ENGINE DISAGREES` | [T34](#t34) |
| A mod is installed and loaded but its biomes are in no catalogue, or a catalogue count is lower than the jars hold | [T35](#t35) |
| A sky_islands or nether_islands world is one island at origin ringed by void | [T36](#t36) |
| "the mod does not run in the reserved four" — reasoning from `getCustomDimensions()` | [T37](#t37) |
| A boot-line biome count disagrees with what the world generated, either way | [T38](#t38) |
| A dimension is stuck below 85 however many seeds are rolled | [T39](#t39) |
| A dimension is full of rivers and lakes; `seedRoll.water` changes nothing | [T40](#t40) |
| Mod build fails with a misleading Gradle task error | [P4](#p4) |
| Local mc dies with `SIGBUS`, or every level load fails on the Distant Horizons database | [P5](#p5) |
| The local Minecraft CLIENT dies mid-session, `hs_err_pid*.log` says `SIGBUS` on a `C2 CompilerThread` | [P6](#p6) |
| A worldgen config change had no effect | [D2](#d2) |
| `Tried to read NBT tag that was too big`; `level.dat` growing every boot | [D9](#d9) |
| Boot hangs after deleting a dimension's world directory | [D3](#d3), [K1](#k1) |
| Custom dimensions all generate identical terrain | [D6](#d6) |
| `Error upgrading chunk`, RCON i/o-timeout, container healthy | [K1](#k1) |
| `TheChunkSystem` ConcurrentModificationException | [K2](#k2) |
| The map looks imprecise on steep terrain | [K5](#k5) |
| A chunk never finishes generating; RCON accepted but never answered, one thread pegged, nothing thrown | [K6](#k6) |
| An analysis says a biome band generates nothing but the game finds it | [K7](#k7) |
| `Expected directory, got <level>/entities` on a first visit | [K9](#k9) |
| `locate biome` returns a position but players never reach the biome | [K7](#k7) |
| Can't connect / server won't start / backups failing / lag | [Common symptoms](#common-symptoms) |

---

## Architecture traps

<a id="t1"></a>
### T1 — Shared Discord bot token

Slash commands (`/mc`, `/register`) disappear after an mc restart. dcintegration (in mc) and discord-sync log in as the same bot; with `[commands] enabled = true` in the live `data/config/Discord-Integration.toml` the mod bulk-overwrites the command registry at every mc boot. Keep it `false` — deploy.sh enforces it, discord-sync purges the global registry at boot. `config/dcintegration/config.toml` is an intent doc, **not** the live schema.

<a id="t2"></a>
### T2 — Seed container must re-run on deploy

Config or mod changes don't take effect; mc boots with stale files. `defaults-seed` lays platform defaults + consumer overlay into the shared volumes at boot, so it must be recreated before mc starts. Nginx configs are bind-mounted — nav-proxy and pack-web additionally need force-recreate.

<a id="t3"></a>
### T3 — mcrcon and threads

`signal only works in main thread` in discord-sync logs: `mcrcon` arms SIGALRM, which raises under `asyncio.to_thread`. Use `discord-sync.py`'s `ThreadSafeRcon` (socket timeouts) for all RCON code in the bot.

<a id="t4"></a>
### T4 — Mod sync is seed-resolved, never API-at-boot

mc crash-loops with Modrinth `429 Too Many Requests` on every restart. `MODRINTH_PROJECTS` made itzg re-resolve ~160 versions through api.modrinth.com on every sync boot; it is gone — do not reintroduce it. The seed's `resolve-mods.py` resolves pins to direct URLs, cached forever in the `stack-mods` volume (version IDs are immutable); `scripts/sync-mods.sh` downloads only jars missing from `data/mods/`, host-side, between the seed and mc's start; `MODS_FILE`/`DATAPACKS_FILE` stay empty so mc makes zero network requests at boot, and a failed required resolution fails the seed and blocks the boot loudly. Jars are managed: deploy.sh/dev-up.sh prune anything not in `mods-manifest.txt` (`local-mods/` exempt), so ship extra jars via `overlay/mods-extra.txt` or `local-mods/`, never by hand.

<a id="t5"></a>
### T5 — Whitelist as a door lock

"You are not whitelisted" despite the role, after a failed deploy: deploy.sh clears the whitelist to block joins during the restart and restores it after, so a deploy that dies mid-way leaves it cleared. The itzg image restores from env on next boot, or restore via RCON. `service.sh` refuses raw `mc` lifecycle operations (`die "Refusing raw MC lifecycle operation..."`) — use deploy.sh or Discord `/mc restart`.

<a id="t6"></a>
### T6 — Kuma is config-driven

Monitors deleted in the Kuma UI reappear after a deploy: `config/uptime-kuma/kuma-config.json` is authoritative and kuma-init re-syncs every deploy. `KUMA_API_KEY` is a socket.io **session token** (`kuma-token.sh`), not the Prometheus API key.

<a id="t7"></a>
### T7 — Kuma auth is human-gated; do NOT debug it by retrying logins

`authIncorrectCreds` in kuma-init/kuma-provision logs. The admin account has 2FA, so password-only logins always fail whatever the password — it is missing a TOTP, and usually the session token was wiped by a full deploy regenerating the server `.env` while the `KUMA_API_KEY` secret was empty. **On `authIncorrectCreds`, stop calling:** retries risk Kuma's rate limit and the host's fail2ban, and a locked account means rebuilding Kuma. Recovery is human-only — `./ops reauth-kuma` → add the token to the consumer `.env` → `./ops github-env-sync`; it must live in the GitHub `production` secret, not just `.env`. A stuck maintenance window clears without credentials:

```bash
docker exec uptime-kuma sqlite3 /app/data/kuma.db "DELETE FROM maintenance WHERE title='Deploy in progress';"
docker restart uptime-kuma
```

<a id="t8"></a>
### T8 — Chunky markers

Chunky re-generates chunks already done: completion is tracked by `data/.chunky-*-complete` marker files. Delete them to force re-generation (e.g. after a border change).

<a id="t9"></a>
### T9 — Infra deploys must never recreate mc; `--no-recreate` is load-bearing

Players dropped mid-session with no countdown after a sidecar-only change. Full deploys create mc **with** a temporary Modrinth override then delete the override file, so a plain `docker compose up -d` afterwards sees mc as config-drifted and recreates it. The infra step's first `up -d` carries `--no-recreate`, sidecars update via an explicit `--force-recreate --no-deps` list, and `scripts/dc.sh` blocks a bare `up -d` on the server. Only deploy.sh, after the countdown, may recreate mc.

<a id="t10"></a>
### T10 — Raw pack overrides clobber player settings

Players' keybinds, voice chat or video options reset after a pack update: Prism re-applies everything in the `modpack/overrides/` root on every update. Only `servers.dat` ships raw; all client defaults go under `modpack/overrides/configureddefaults/` (merge/copy-if-missing), sourced via `scripts/client-defaults.sh --diff`/`--sync`. Curate by hand — never bulk-copy the instance config dir (`NCR-Encryption.json` contains a secret).

<a id="t11"></a>
### T11 — The `.deployed` state file can lie

CI deploys go green but the server isn't running the new code. The consumer-diff half of tier detection reads `consumer_sha` from `~/server/.deployed`, and a sha recorded for a deploy that never completed downgrades every following push to pull tier. Guarded in `deploy-reusable.yml`: state is written only on success, a state file with no mc container forces a full deploy, and the stack comparison reads `readlink .stack/current` on the server rather than trusting the state file. If a server has `.deployed` but no containers, delete the file or dispatch the workflow manually (manual dispatch always deploys full).

<a id="t12"></a>
### T12 — Hand-patched shared volumes are reverted by every seed run

A local test of unreleased seed-image content (mod list, nginx templates) passes, but the change was never live — the "pass" came through the old config. `stack-config`/`stack-mods` contents come from the **defaults-seed image** (rebuilt only by CI on push), and the one-shot `seed` re-runs on any `compose up` without `--no-deps`, silently restoring image defaults over hand-patches. Patch the volume, recreate ONLY the consuming service with `--force-recreate --no-deps` (or a plain `docker restart mc`), then verify the **rendered** state, never the patch: `docker exec nav-proxy grep <expected> /etc/nginx/conf.d/default.conf`.

<a id="t13"></a>
### T13 — The map is a static render with no live link to mc

Map problems get looked for in mc, or a quiet render log is read as a failure. `unmined-render` renders every on-disk dimension to webp tiles plus a self-contained OpenLayers viewer under `data/unmined-web/maps/<name>/`; nav-proxy serves that bind mount directly at `map.DOMAIN` (per-dimension URLs are `map.DOMAIN/<slug>`) — no upstream, no RCON interface, so the map survives mc restarts and autopause, and if `map.DOMAIN` is down the problem is nav-proxy or the tunnel, never the game server. Diagnose with `./ops map status` or `docker logs unmined-render`; force a pass with `./ops map render`. Renders are incremental (unchanged dimensions skipped by mtime marker), so a near-silent pass is normal. `UNMINED_INTERVAL` (default `6h`) sets the gap and `0` disables the loop; `PREGEN_BORDER_RADIUS` (8192) bounds `overworld`, `the_end` and `paradise_lost`, with `the_nether` at /8. Dimension discovery is automatic — see [D8](#d8).

<a id="t14"></a>
### T14 — Restic retention groups by hostname

Snapshot count and R2 usage grow without bound despite the retention policy. `restic forget` groups by `(host, paths)`, the mc-backup container's hostname defaulted to its container id, and every full deploy recreates the sidecar — so each deploy era became its own retention group with stranded snapshots. Fixed by `hostname: "${BRAND_SLUG:-adventure}-mc-backup"` pinned in `docker-compose.yml`, brand-scoped so servers sharing a bucket keep separate groups. One-off cleanup across dead hosts:

```bash
docker exec -u 1000 mc-backup restic forget --group-by paths --keep-last N --prune   # plain --keep-last keeps N PER DEAD HOST
docker exec -u 1000 mc-backup restic stats --mode raw-data                           # real usage, not snapshot count
```

<a id="t16"></a>
### T16 — The mod mirror and packwiz index are build output

Launchers download HTML instead of mod JARs, or packwiz auto-update serves stale mods. `modpack/dist/mods/` and `modpack/dist/packwiz/` are generated and pruned by `build-modpack.sh` — never hand-edit them. Downloads route via `mods.DOMAIN/mods/` (a tunnel path-rule straight to pack-web, bypassing nav-proxy) with Modrinth's CDN as the `.mrpack` fallback. Invariants: `/mods/` in `pack-web.conf` must return a clean 404 (not the site-wide 301-to-homepage) or launchers download HTML instead of falling back; `.toml` files must never become edge-cached (they are the update signal, and `.toml` isn't in Cloudflare's default cache list); don't publish this pack on Modrinth — their upload validation rejects non-whitelisted mirror URLs.

<a id="t17"></a>
### T17 — RCON cannot carry an answer, and a green check may be reading nothing

- **Symptom:** a diagnostic command "works" but its output is one run-on string with lines concatenated and the end missing; or a check passes against a server that is paused, mid-restart, or returning empty responses under load; or `/locate` never returns and the terminal appears wedged.
- **Cause:** RCON concatenates feedback lines with no separator and truncates at a few KB, and carries no error type — a parse failure, a timeout and an empty result are indistinguishable, so an empty response under load reads exactly like success. Slow commands are slow because of the COMMAND, not the channel: locating a vanilla village in the stock overworld times out at 120 s, and Chunky pre-generation does not change it.
- **On a server with players, `/locate` is not merely slow — it kicks them.** It blocks the main thread long enough that every connected client times out and drops. Treat it as a player-affecting command, never a read-only probe; use `/customdim structure-census` for placement questions and `/customdim occupant` for "what is actually in this chunk".
- **Fix:** do not parse RCON output. Diagnostic commands write JSON under `.seed-rolling/` (a sibling of `data/`, outside `deploy.sh`'s config sync and `./dev refresh-config`) and answer with a summary plus a path; `./dev verify` says where verification for each artefact lives, and the full table is in [`docs/mod-internals/diagnostics.md`](docs/mod-internals/diagnostics.md). Check the boot log for a `worldgen config changed` WARN before trusting any worldgen assertion ([D2](#d2)) — a world created under an older config makes every check measure history.

<a id="t18"></a>
### T18 — A custom dimension is created on first entry, so it is absent until then

`execute in adventure:<slug> run seed` answers `Unknown dimension` on a healthy server with the config plainly present. `CreateWorldsMixin` filters the `createWorlds` loop down to `overworld`, `the_nether`, `the_end` and `paradise_lost`; the other ~78 are built by `DimensionManager.getOrCreateDimension` when a player first enters one, because Distant Horizons and c2me build per-level state from `ServerWorldEvents.LOAD` and paying that per dimension per boot is what this avoids. Load one with `rcon-cli "customdim load <bare_slug>"` and poll — creation is queued to `END_SERVER_TICK`, so it lands a tick or two later, not immediately. Those four load eagerly because MC asks for them by key from paths with no lazy-creation hook. That is a loading difference; they are managed like every other dimension ([AGENTS.md § Dimensions](AGENTS.md#dimensions)).

<a id="t19"></a>
### T19 — A listed biome the source cannot place swallows the whole dimension

- **Symptom:** a `multi_biome` (or any biome-listed) dimension generates as one biome nearly everywhere, and seed rolling reports the rest as "not found" however many candidates are rolled. The outcome is structural, not unlucky.
- **Cause:** `DimensionManager.buildMixedSource` places a listed biome in four tiers — an explicit `parameters` object, then the base source's own cells for it ("native"), then the cells its declaring mod registered with TerraBlender for that family ("natural"), and finally the leftover hypercubes dealt **round-robin** to whatever is left ("mixed-in"). Round-robin is arbitrary placement: one such biome receives every leftover cell while the natives keep only what they literally claim, and across the 34 dimensions measured before the natural tier existed those biomes held 74–100% of the area.
- **A biome reaches round-robin only when nothing readable declares where it goes, and there are at least six ways to declare it.** A negative across the mechanisms you happen to know is not a negative — that reading has been wrong twice, on two different mods, because the mechanism was one further along this list:

  | mechanism | read it from | who uses it here |
  | --- | --- | --- |
  | A TerraBlender region | `Regions.get(RegionType)` -> `Region.addBiomes` | Nature's Spirit (47 biomes, 5 regions), Wilder Wild, YUNG's Cave Biomes, Underground Worlds |
  | A mixin into vanilla's `OverworldBiomeBuilder` | the base source's own entries — it is already native | Galosphere |
  | A datapack dimension or parameter-list entry | the base source's own entries | Incendium (13 inline entries in `data/minecraft/dimension/the_nether.json`), Terralith, Nullscape |
  | Fabric's `NetherBiomes.addNetherBiome` | the vanilla `minecraft:nether` parameter list | BetterNether, via wover's `BiomeSourceManagerImpl.didLoadBiomeData` |
  | A Lithostitched region registered PROGRAMMATICALLY at runtime | the mod's own config file — there is no injector JSON to find | Regions Unexplored (`RULithostitched.init` on `AddRegionsEvent`) |
  | A mod's own biome-source model with no cells at all | nothing — there is no climate data | BetterEnd (wover tag + weighted picker + `BiomeMap`) |

- **Regions Unexplored declares 64 of its 78 biomes**, in `data/config/regions_unexplored/common.json` (lenient JSON — parse it as [T35](#t35) requires). The model is substitution rather than a cell: `can_replace` a host biome, take a `weight`ed share of the region it occupies, optionally narrowed by a per-axis range. 43 of the 64 carry no `parameters` at all and inherit the replaced biome's cell entirely, so a reader that returns hypercubes without consulting the host has nothing to return. **14 declare nothing anywhere** and are the RU biomes a band is genuinely the only mechanism for: `arid_mountains`, `barley_fields`, `cold_deciduous_forest`, `deciduous_forest`, `frozen_tundra`, `golden_boreal_taiga`, `mauve_hills`, `mountains`, `pumpkin_fields`, `redstone_abyss`, `rocky_meadow`, `scorching_caves`, `steppe`, `temperate_grove`.
- **TerraBlender has OVERWORLD and NETHER region types and no END**, and its cells are shaped for that family's climate router, so the natural tier applies only where the base source IS the overworld's or the nether's. An End, `sky_islands` or `paradise_lost` source keeps round-robin, which at least guarantees the biome appears.
- **Vanilla and Nullscape End biomes still need a band.** `minecraft:end_barrens`/`minecraft:end_midlands` are placed by erosion in `TheEndBiomeSource`, which Nullscape replaces wholesale; Nullscape gives `minecraft:the_end` and `minecraft:end_highlands` the **same** full-span hypercube, so one of that pair can never win a nearest-point lookup, and it gates `nullscape:void_barrens`/`nullscape:crystal_peaks` on `temperature >= 0` — an octave -11 noise that barely moves inside one dimension.
- **Fix:** give the biome an explicit hypercube. An object-form entry (`{"id": ..., "parameters": {...}}`) withdraws it from every other tier, and once no biome is left foreign the leftover pool is dropped rather than dealt out. Band on an axis the dimension's climate noise actually crosses at its own radius — typically **weirdness** for overworld and End dimensions, **continentalness** for `paradise_lost` clones (humidity spans 0.207 across a 512-radius world against weirdness's 0.783).
- **An explicit band outranks every other tier, which is why a band written as plumbing hides the placement it stood in for.** A band is for a placement an author chose; one written merely to make a biome appear substitutes a guess for what the biome's own mod already declared, and the two look identical in the file. Once a mechanism can be read, the bands standing in for it come out — see [`docs/design/biome-placement.md`](docs/design/biome-placement.md).
- **A natural-tier biome carries far more than its region's own cells.** A region built with `Region.addModifiedVanillaOverworldBiomes` returns vanilla's whole point list with its substitutions applied, so cell counts run to thousands and spread over three orders of magnitude — measured `natures_spirit:tundra` 19,936, `cypress_fields` 556, `underground_desert` 9, against a vanilla base table of 1,715 cells across 150 biomes. A large count is not a large share ([T38](#t38)). TerraBlender consults one region per position through its uniqueness noise; without that layer cells compete directly by nearest point, and region weight has no effect. Placement is the author's, the region layout is not.
- **The usable axis belongs to the noise ROUTER, not the dimension family.** Measured with `customdim sample-noise` over 11 points spanning the playable radius, one representative per (`type`, `noiseSettings`) — the table is `config/custom-dimensions/climate-axes.json`:

  | type | noiseSettings | axis | span |
  | --- | --- | --- | --- |
  | `multi_biome` | — | `weirdness` | 1.772 |
  | `multi_biome` | `adventure:compressed` | `weirdness` | 1.316 |
  | `multi_biome` | `adventure:wide` | `erosion` | 1.300 |
  | `cave` | — | `temperature` | 1.066 |
  | `nether` | — | `temperature` | 1.126 |
  | `nether_islands` | — | `erosion` | 1.136 |
  | `nether_islands` | `adventure:compressed` | `temperature` | 1.654 |
  | `end` | — | `continentalness` | 1.113 |
  | `sky_islands` | — | `continentalness` | 1.291 |
  | `paradise_lost:*` | — | `temperature` | 1.277 |
  | `void` | — | `weirdness` | 1.055 |
  | `void` | `adventure:void` | `erosion` | 0.909 |

  `overworld`, `checkerboard` and `superflat` answer `Not a MultiNoiseBiomeSource` and have no climate point to band on. In a `cave` dimension weirdness measures a span of **0.00** across one distinct value — completely inert.
- **One representative per combination is a starting point, not a guarantee.** Measure your own dimension: `the_crumbling_reaches` (`end`, border 2048) gives weirdness 1.099 against continentalness 0.895, the reverse of the `end` row.
- **Band SIZE is a separate question and a 121-point grid cannot answer it.**
  `scripts/check-band-share.py` reports each band's share over the 41x41 grids
  in `config/custom-dimensions/grids-41/`; read [K7](#k7) before treating a
  small one as a defect.
- **Rank candidate axes by DISTRIBUTION, not span.** `the_red_monument` (`adventure:void`) gives weirdness a span of 2.000 across just THREE distinct values, seven of eleven samples pinned at -0.50 — the widest span and the worst possible axis. Count distinct values across the radius before choosing. `biomes` is creation-time worldgen config ([D2](#d2)) and changing it re-keys the generation fingerprint, so every affected dimension needs a re-roll, not a rescore.

<a id="t22"></a>
### T22 — `"groups": []` suppressed noise only, so void/superflat dims generated every structure set

Structures — villages, towers, ships, all 367 sets — generating in `void`/`superflat` dimensions on vanilla grid placement; dimensions carrying an explicit `structureDensity: "none"` masked it, any relying on the type's `"groups": []` alone leaked. `NoiseGroupPlan.resolve()` answered "suppressed" for a type enabling no groups, and the legacy density path then returned `null` for a default config — which keeps the world's original `StructurePlacementCalculator` intact. Now `suppressesAllSets()` is true only for "type enables no groups" (and "no world type"), and `DimensionStructures.transformed()` drops every organic set; exit shrines keep their opt-in handling and `structures.force` still appends. The boot line states the reason: `density=normal+suppressed(type void enables no groups)`. `structureDensity: "none"`, `structures.mode: "none"` and `structures.noise: false` keep their own meanings (the last deliberately keeps the vanilla grids).

<a id="t23"></a>
### T23 — `structures.mode`/`structures.exclude` never reached pass-through sets on the noise path

`structures.mode: "reject"` listing a custom-placement set (Moog's `mvs:`/`mns:`/`mes:`/`mss:`, YUNG's, Supplementaries galleons) does nothing, and `structures.exclude` never applies to those sets in any path. The mode filter ran only on the legacy density path and exclude only inside `NoisePoolBuilder`, but the 227 custom-placement sets never enter the pool builder and the noise path's pass-through loop re-added them unfiltered. Fixed by one shared filter, `DimensionStructures.keepSet(setId, mode, modeList, exclude)`, applied in both the legacy mode check and the pass-through loop. The census artefact records a `passThrough` array, so a filtered set must be ABSENT from it.

<a id="t25"></a>
### T25 — Third-party HEAD cancels ate forced structure starts; the mod now performs them itself

- **Symptom:** a `structures.force` position sits in the live calculator (census `forced` block, boot line `+N forced`), the chunk generates fresh under that calculator, and yet no structure start exists — no `forced ... generated at chunk` INFO line, no structure blocks.
- **Cause:** all seven YUNG's structure mods `@Inject(HEAD, cancellable)` into `ChunkGenerator.trySetStructureStart` and cancel every start whose structure TYPE they replace — fortresses, mineshafts, strongholds, desert and jungle temples, ocean monuments, witch huts (config-gated, on by default). A forced `minecraft:fortress` died there regardless of placement class.
- **Fix (in place):** `ChunkGeneratorForcedStartMixin` performs forced start attempts itself from the `ForcedStartOverride` registry of (world, chunk, structure) triples, with an always-true biome predicate. It is **priority 900** because HEAD callbacks execute in application order (lower first) — at 1100 it sat below YUNG's cancel and never ran. Forced structures also get terrain-adaptation resolution from the full registries, so beards and kernels apply even when their organic set is biome-prefiltered out. Verify with a fresh throwaway dimension + `customdim load` + a bot at the forced position, then `grep 'generated at chunk' latest.log` and block probes inside the persisted piece boxes; census presence proves CONFIG, not generation.
- **Corollary:** vanilla fortresses, mineshafts, strongholds, desert and jungle temples, ocean monuments and witch huts never generate ORGANICALLY on this stack — the YUNG's replacements own those niches by design, and forcing one is the only way to place the vanilla structure.

<a id="t24"></a>
### T24 — RETURN-side callbacks on StructureWeightSampler never execute; a silent instrument there proves nothing

- **Symptom:** an `@Inject` at RETURN (or a MixinExtras `@ModifyReturnValue`) on `StructureWeightSampler.createStructureWeightSampler` or `sample` merges cleanly into the class but never runs, at any priority. A counter placed there reads zero.
- **Cause:** five mods transform the class (c2me accessors, YungsApi enhanced beardifier + aquifer masks, Moog's Structures enhanced beardifier, lithostitched adaptation override, custom-dimensions). Moog's replaces the factory's return value from a cancellable RETURN callback — a Mixin cancel emits an immediate return that skips callbacks inserted after it, and the instance the noise fill samples is Moog's rebuild, not the one a RETURN-side hook decorated.
- **Fix:** hook this class at HEAD or a constructor only — `KernelDensity` delivers kernel density via `ChunkNoiseSampler.<init>` for exactly this reason. Treat a silent RETURN-side instrument as *unmeasured*, never as evidence the code path is dead: a HEAD counter on `sample` logs ~37M invocations per boot, and a controlled two-world A/B (`beard_box`/`beard_thin` vs `none`; 46/46 piece-sets placement-identical, 312 identical probe columns) shows terrain filled below structure bases exactly where vanilla adaptation puts it. **Vanilla `BEARD_THIN`/`BEARD_BOX` work on this stack** — `structures.terrainAdaptation` and the shipped theme defaults are live behaviour, not dead config.

<a id="t26"></a>
### T26 — A theme default read `none` as "unset" and bearded 130 structure authors who meant it

- **Symptom:** flat rectangular slabs of terrain hanging in mid-air beneath structures on Terralith Skylands islands (overworld, y≈250–320); village buildings raised on shelves cut into cliffsides; other buildings sunk into the floor.
- **Cause:** `structure_type_defaults.json`'s `terrainAdaptation` table mapped `settlements → beard_thin`, `dungeons → bury`, `landmarks`/`endgame → beard_box`, applied by `TerrainAdaptationOverride.resolveName` wherever the structure's registry value was `none`. Vanilla's codec defaults that field to `none`, so "chose none" and "wrote nothing" are indistinguishable — and 23 of vanilla 1.21.1's 34 structures declare nothing, while every structure that wants a beard declares one (`village_* → beard_thin`, `ancient_city → beard_box`, `stronghold → bury`). The table overruled authors: `Dimension overworld: terrain adaptation overridden for 130 structure(s)`. `beard_box` fills a structure's whole bounding box, so on a floating island the box hangs below the island as a free-standing slab.
- **Fix (in place):** theme defaults are blend-strength only — `settlements` and `landmarks` map to the `ground_blend` kernel, `dungeons` and `endgame` to nothing; see [T27](#t27) for why magnitude is the whole mechanism. Note the granularity trap: this is the OVERWORLD, and Terralith puts Skylands biomes inside it, so a fix keyed on dimension type cannot work — one dimension holds both normal ground and floating islands.

<a id="t27"></a>
### T27 — Fill kernels do not self-exempt at altitude; 0.4583 is the entire mechanism

- **Symptom:** a fill kernel used as a general "integrate with the ground" default manufactures solid terrain in open sky — a `pedestal` on a floating island is a 64-block-deep cone, 100 blocks across, hanging in the air.
- **Cause:** `KernelDensity` adds the kernel term to the FINAL block-state density (`ChunkNoiseSamplerMixin`, `STORE ordinal 0`), whose terrain term ends in `squeeze`: `min(squeeze(0.64 × interpolated(blend_density(…))), noodle) + …`. `squeeze` clamps to [-1, 1], so the term is bounded to ±0.4583 and **pinned at −0.4583 in open sky however high you go**. There is no altitude awareness anywhere in the kernels.

  | raw terrain | final (squeezed) | +0.30 | +2.5 |
  | --- | --- | --- | --- |
  | ≤ −1.56 (open sky) | −0.458 (clamped) | air | solid |
  | −1.0 | −0.303 | air | solid |
  | −0.5 | −0.159 | solid | solid |

- **Fix:** pick magnitude by intent. Above ~0.46 a fill is a **guarantee** that manufactures terrain everywhere (`pedestal` +2.5, `platform_skirt` +2.0 — deliberately unconditional); below it a fill is a **blend** that can only finish terrain already near the surface (`ground_blend`, 0.30, 10 deep — the theme default). Never raise `ground_blend` to force a stubborn case; that converts it into a guarantee and puts terrain back in the sky. The `pedestal` row for `sky_islands` reading 60/60 air probes packed solid is the fill working everywhere, not a height cutoff.

<a id="t30"></a>
### T30 — A hand-patched `.stack/<version>/` is discarded the moment a newer release resolves

A fix verified in the consumer's bundle stops being present, and `readlink .stack/current` names a version you did not pull. A symbolic `STACK_VERSION` (`v5`, `latest`) resolves to the newest matching release on every `./dev`/`./ops` invocation, so publishing a release repoints `.stack/current` at a directory that never had the patch; `.stack/<version>/` is immutable release content by contract. Never hand-patch the bundle as anything but a throwaway probe, and never while a release is in flight — land the change in the platform repo, release it, `./dev update`, then confirm with `readlink .stack/current` plus a grep for the change in the file that will actually execute.

<a id="t31"></a>
### T31 — `.env SEED` does not drive terrain; the dimension config does

A world regenerates with the old terrain after a reset that set a new seed, or the seed reported in game does not match `.env`. `ServerWorldSeedMixin` overrides `ServerWorld.getSeed()` per dimension from each config file, the four reserved ones included (`overworld.json`, `the_nether.json`, `the_end.json`, `paradise_lost.json`); `.env SEED` seeds `level.dat` only, and the literal string `"env"` in a config `seed` field is the opt-in to read it. `reset-seed.sh` writes `SEED` into `.env`, which reads as though it were the lever and is not. Same for spawn: the overworld entry's `"spawn": [x, y, z]` replaces the `SPAWN_X/Y/Z` enforcement, with `[0, 64, 0]` as the "not chosen" sentinel that hands back to deploy.sh's env guard. Change seed and spawn in the dimension config — where the seed roller writes winners — and get that config onto the server BEFORE the world is deleted; a reset that runs first regenerates from the deployed config, not the one in your working tree. **Read an overlay file's `overrides` block, not its top level:** an overlay is `{"overrides": {...}}` (deep-merge), full replace, or `{}`, so checking top-level keys reports `seed`/`spawn` as absent while the merged config carries them.

<a id="t33"></a>
### T33 — A forced structure over void needs a `y`; without one it asks for ground and is refused

- **Symptom:** every `structures.force` entry in a `void` dimension logs `forced <id> produced no start at chunk [x, z] — the structure's own generation rejected the position`, and the dimension generates completely empty. The census still reports the forced placements, because that reflects config rather than generation.
- **Cause:** a structure decides whether it can generate by asking the chunk generator for the ground height. `type: "void"` produces no terrain at any column, so the answer is "nowhere" and the structure declines. Neither `structureDensity` nor `structures.mode` is involved — a controlled A/B on `the_red_monument` with both pinned at `none` and only `type` changed (void → end) went from 5 of 5 rejected to 0. Terrain adaptation cannot rescue it either: `pedestal` is an unconditional fill ([T27](#t27)) but it runs during the noise fill, a chunk-status *after* the start decision, so a declined start never reaches it.
- **Fix (in place):** `structures.force[].y`. Set it and `ForcedGroundLevel` pins every height query for the duration of that one start attempt, so the structure places at that height with nothing beneath it. Omit it and the old behaviour stands, which is what a placement meant to sit on terrain wants. `customdim lint` reports `force_needs_y` (ERROR) for a `void` dimension whose force entry omits it.
- **Known limit:** a structure whose start height is an absolute constant never asks for ground, so `y` cannot move it. The WARN says so explicitly when a pinned attempt still fails.
- **History worth keeping:** this is the residue of K3, which was *"structures.force generates no starts in fresh chunks"* — one symptom, two causes. [T25](#t25) fixed the third-party-cancel cause and closed the whole issue; the no-ground cause survived unnamed and untested for a release. Both causes looked identical before T25 added the WARN that distinguishes them.

<a id="t34"></a>
### T34 — A region-injecting mod repaints managed dimensions from the second boot onwards

- **Symptom:** `customdim structure-census <dim>` reports `FACTS ENGINE DISAGREES WITH THE LIVE WORLD` with hundreds of mismatches on a world with no fingerprint drift, and the dimension generates biomes its `biomes` list never named. It is clean on the boot that CREATES the world and wrong on every boot after — so a wipe appears to fix it and the next restart brings it back.
- **Cause:** TerraBlender (pulled in by `naturespirit`) walks the whole DIMENSION registry from a `MinecraftServer` mixin on `[main]`, before `registerDimensions` runs on `[Server thread]`. On the first boot our dimensions are not in the registry yet; on every later boot they are, decoded from `level.dat`, and any level stem whose dimension TYPE is in `terrablender:overworld_regions`/`nether_regions` over a `MultiNoiseBiomeSource` gets mutated in place — one search tree per registered region, picked by region-uniqueness noise in `getNoiseBiome`, plus every region's biomes appended to the source's memoised biome set. That set is what filters structure pools, so the pool is filtered against a biome list several times wider than the dimension's. Dimensions with an `environment` block escape: their runtime `{slug}_type` is in no tag. Dimensions with `biomePatches` escape too: TerraBlender's `LevelUtils.shouldApplyToBiomeSource` is `instanceof MultiNoiseBiomeSource` against the generator's TOP-LEVEL source, and theirs is a `PatchedBiomeSource` wrapping one.
- **Fix (in place):** `ConfiguredBiomeSource.restore` rebuilds the source from its own parameter entries — which the injection leaves untouched — before the `ServerWorld` is built, and WARNs with both counts. A wrapped source is rebuilt through its wrappers: unwrap to the multi-noise core, rebuild, re-wrap every one of this dimension's patch layers, wherever in the stack it sat. Another mod's wrapper in that stack (Lithostitched's biome injector) is DROPPED by the rebuild, because it exposes no way to re-wrap one — its injections come back by being named in `biomes`, like any other biome, and a palette nothing widened is never rebuilt so no wrapper is dropped without an injection to undo. **It needs no world wipe** — `level.dat`'s `WorldGenSettings` is encoded from the DIMENSION registry (`WorldGenSettings.encode`), which `restore` never writes to, so the rebuild shapes the in-memory `ServerWorld` only and applies from the next boot. The four reserved worlds have no config here and keep whatever the pack's biome mods give them. To put another mod's biomes in a managed dimension, name them in `biomes`; `buildMixedSource` deals them hypercubes in this mod's own noise.
- **Verifying a wipe:** `./dev clean --yes world` with the stack UP is undone by the container's shutdown save. `./dev down` first, or the world you test is the old one.

<a id="t35"></a>
### T35 — Mods ship lenient JSON, and a strict parser drops it without a word

- **Symptom:** a mod is installed, loaded and listed in `fabric.mod.json`, but its biomes appear in no catalogue under `config/custom-dimensions/extractors/` and `check-content-coverage.py` reports its namespace as absent rather than unreferenced. Nothing errors, and the count simply reads as though the mod ships nothing.
- **Cause:** worldgen JSON reaches the game through GSON in lenient mode, so `//` and `/* */` comments and trailing commas are legal and mods use them. Python's `json` rejects all three, and every extractor here caught `JSONDecodeError` and `continue`d. Measured: YUNG's Cave Biomes ships both its biomes behind a `// RAW_GENERATION` comment, and four Nature's Spirit biomes had been missing since that mod was added — the catalogue read 229 biomes against an actual 350.
- **Fix (in place):** `scripts/mcjson.py`, used by all four extractors. It strips comments and trailing commas string-aware, so a `//` inside a JSON string survives, and a file it still cannot read is named on stderr instead of skipped. **Never catch a parse error around mod data and continue** — that is the whole of this bug.
- **Consequence worth knowing:** a biome absent from the catalogue is one nobody can name, and a biome no dimension names never generates however many mods are installed. `scripts/check-content-coverage.py` is the standing check.

<a id="t36"></a>
### T36 — sky_islands and nether_islands are built on the End, origin island included

- **Symptom:** a `sky_islands` or `nether_islands` dimension generates one
  island at world origin ringed by a wide void, then sparse islands beyond it
  — an End knock-off wearing the wrong biomes, however its description reads.
  `settingsOverrides` with `defaultBlock`/`seaLevel` re-skins the blocks and
  changes nothing about the shape.
- **Cause:** both types build their generator from `endGen.getSettings()`
  (`DimensionManager`, the `sky_islands` and `nether_islands` cases), which on
  this stack is Nullscape's `minecraft:end` — noise router, origin island and
  void moat included. `settingsOverrides` cannot reach a noise router; its
  whitelist is `seaLevel`, `defaultBlock`, `defaultFluid` and
  `disableMobGeneration` ([T32](#t32) covers the related "the map disagrees"
  reading).
- **Fix:** `"settingsOverrides": {"endIsland": false}`. It walks the resolved
  router and substitutes the island term for scattered noise, so it works on
  any dimension whose generator carries one — End, sky_islands and
  nether_islands alike. Self-verifying at boot: the log says either
  `End origin island removed (N island term(s) -> scattered noise)` or
  `settingsOverrides.endIsland is false but this generator carries no End
  island term`.
- **Border size is what decides whether this bites.** A dimension small
  enough to sit inside the centre island never sees the moat, and the island
  IS its world — `the_starwell` at a 256 border is correct as it stands and
  must NOT get the flag. Anything past roughly a 1024 border shows the ring.
- **Creation-time.** This is worldgen ([D2](#d2)) and it moves the seed
  fingerprint, so an affected dimension needs a world wipe and a re-roll.

<a id="t37"></a>
### T37 — `getCustomDimensions()` says which worlds the mod CREATES, not which worlds its code RUNS IN

- **Symptom:** an investigation concludes this mod's worldgen is not in the code
  path for `minecraft:overworld`/`the_nether`/`the_end`/`paradise_lost`, and
  rules the mod out of a worldgen bug in one of them.
- **Cause:** `MultiverseConfig.getCustomDimensions()` filters on
  `!c.isReserved()`, and `registerDimensions()` iterates it — so the reserved
  four are not built by `createDimensionOptions`. That is a statement about
  world CREATION only. Generation behaviour is delivered by mixins on VANILLA
  classes, which carry no reserved filter and therefore run in every world:
  `NoiseStructureSelectionMixin` (`@Mixin(ChunkGenerator.class)`, the source of
  the `Noise pick: … rejected … (world …)` lines), `ChunkNoiseSamplerMixin`
  ([T27](#t27)), `NoiseChunkGeneratorForcedGroundMixin`,
  `ChunkGeneratorForcedStartMixin` ([T25](#t25)), `ServerWorldSeedMixin`
  ([T31](#t31)).
- **Fix:** to answer "does our code run here", read the mixin targets, not the
  config accessors. `AGENTS.md` § Dimensions states the invariant: the reserved
  four are "four dimensions among 82", and `custom-dimensions` "owns every
  generator in the pack".

<a id="t38"></a>
### T38 — Every count in the boot line is assignment-time, never an outcome

- **Symptom:** the same line misread in both directions. `biome source built
  (0 explicit of 0 banded, 228 native, 0 natural over 0 cell(s), 0 mixed-in of
  20 requested)` reads as the list being discarded, and a dimension gets "fixed"
  that was never broken; or a biome holding a large cell count is taken as
  present and is nowhere in the world.
- **Cause:** `DimensionManager.buildMixedSource` logs what it ASSEMBLED. A cell
  is an offer of a climate point, not ground. Which biome takes a point is
  settled afterwards by the nearest-hypercube lookup against the climate that
  dimension's noise router actually returns, and no count in the line consults
  it. Every one of them is an upper bound.
- **A large cell count is not safety.** `natures_spirit:cypress_fields` is
  assigned 556 cells by the natural tier and generates nowhere, while natives it
  competes with hold 1, 4, 4, 7, 26 and 36 and do generate. Counts from
  different tiers are not comparable: the whole vanilla overworld table is 150
  biomes over 1,715 cells, median 6 and max 72, so a natural-tier figure in the
  hundreds is a different unit, not a bigger share.
- **A band can hold cells and lose every one of them.** Two bands sharing a
  boundary are both at distance zero there and the incumbent keeps the cell
  ([T59](#t59)). The line is emitted before any lookup runs.
- **The six counts are not in the same units.** `explicit` and `native` are
  hypercube pairs; `banded` is `biomes` ENTRIES carrying a `parameters` block,
  counted from the config; `natural` is BIOMES, with its pair count beside it as
  `over N cell(s)`; `mixed-in` and `requested` are biomes. A vanilla overworld
  biome occupies many points in the multi-noise table, so 20 listed biomes
  legitimately build 228 pairs; nether and End tables are small, log close to
  1:1, and set a misleading expectation.
- **`explicit` below `banded` means bands were dropped**, one per entry naming
  an unlisted biome, carrying invalid parameters, or naming a biome absent from
  the registry. It is the only pair in the line comparing like with like: every
  other count is per-biome, so a lost entry leaves them all agreeing.
- **Fix:** the line answers one question — did the list survive assembly.
  `mixed-in` must be 0 and `requested` must match the config. For what the world
  produced, read `biomes.distinctCount` from a banked candidate (a dimension
  honouring a 20-biome list shows about 20; `amplified` and `large_biomes`
  genuinely discard theirs and show 130+), measure with `customdim facts`, and
  confirm with `customdim locate biome`, which searches rather than samples
  ([K7](#k7)).

<a id="t39"></a>
### T39 — A seed score is the mean of two tiers, so a general criterion is worth 2–3 wants

- **Symptom:** a dimension will not pass 85 however many seeds are screened,
  and the per-criterion detail shows nothing obviously catastrophic.
- **Cause:** `Scorecard.percentage()` averages the **configured** and
  **general** tier percentages; it is not `achieved / ceiling`. The general
  tier holds 4–6 criteria in a pocket dimension, so one of them at zero costs
  8–12 points of the headline, while a single want in a 13-criterion
  configured tier costs under 4. An `unmeasured` criterion leaves both the
  numerator and the denominator, so a want naming a structure outside the
  pool costs nothing — it is simply never measured.
- **`structures_form_places_not_noise` is a fixed tax below a 4096 border.**
  It scores Clark-Evans spacing against a lattice at 2.1491, and noise
  placement in a small disc measures 1.3–1.5 whatever the config says.
  Measured across the bank: ~0.50 at every radius up to 2048, 0.82 at 4096,
  0.99 at 8192, independent of `structureDensity`, noise profile and
  placement count. **A 1024-radius dimension's realistic maximum is ~96%**;
  chasing the last four points is chasing the criterion, not the world.
- **Fix:** rank blockers by tier cost — `50 / N` per point of a general-tier
  criterion, where N is that tier's criteria count — and read the per-criterion
  `best` across every banked candidate. A criterion whose best is capped on
  every seed is a config fault; one that varies is seed luck.

<a id="t40"></a>
### T40 — `seedRoll.water` is scored but never generated; `settingsOverrides.seaLevel` is the dial

- **Symptom:** a dimension is waterlogged — rivers and lakes over most of the
  playable disc — and setting or changing `seedRoll.water` does nothing to it.
- **Cause:** nothing in the generator reads `seedRoll.water`; it is a scoring
  word only, consumed by `Criteria.WaterMatchesIntent`. Sea level comes from
  the dimension's noise settings, which is **63** for `adventure:wide` and
  `adventure:compressed` alike.
- **Fix:** `"settingsOverrides": {"seaLevel": N}`, which the generator does
  read. Pick N from the terrain's own height distribution rather than by feel
  — the fraction of columns at or below N is what `waterFraction` measures.
  The two scored bands leave a dead zone between them (`none` is 0.00–0.20,
  `high` is 0.25–0.80), so a world landing at 0.22 scores nothing on either;
  either aim inside a band or leave `seedRoll.water` unset, which is not
  applicable rather than a loss.
- **Draining is usually free.** Flooding buys no score unless ocean, river or
  beach biomes are in the palette: `biome_variety_present` counts distinct
  biomes off the biome grid, which is fluid-independent.
- **Creation-time.** `settingsOverrides` is in the generation fingerprint
  ([D2](#d2)), so this needs a world wipe and a re-roll.

<a id="t56"></a>
### T56 — A site's ASSIGNED structure is not what stands there (63% of the time)

- **Symptom:** a placement-time rule keyed on "what structure is at this site"
  costs real world content and does not move the statistic it targets.
- **Built and reverted**, 2026-08-30: a minimum separation between copies of
  the SAME structure, to fix the measured tail where two copies of one
  structure sit 72–122 blocks apart. It keyed on
  {@code StructurePick.assignedStructure}. Measured on the full-border
  overworld:

  ```
  occupied sites          2606 -> 2230   (-14%)
  distinct occupants       269 ->  261
  same-structure pairs within 200 blocks   7.5% -> 5.1%
  MINIMUM separation, every rarity tier    107/72/122/113 -> 107/72/122/113
  ```

  **The minima did not move at all.** 14% of the world's content for nothing.
- **Cause:** the assignment is the HEAD of a site's candidate chain, and
  **1639 of 2606 occupied sites (63%) are filled by a re-draw**, so their
  occupant is a different structure from their assignment. A rule reading the
  assignment is reading the wrong variable for two thirds of the world.
- **It cannot be fixed at placement time.** Which candidate accepts depends on
  the biome at the site, and the placement field is deliberately biome-blind —
  that separation is what makes placement order-free and headless. Resolving
  the occupant during placement would couple the field to the biome source and
  destroy both properties.
- **Where repetition IS addressed:** `NoiseFieldIndex.Occupants` resolves the
  real occupant by walking the candidate chain against the biome at the site,
  then drops a placement repeating a higher-ranked one. It runs once per
  PLACEMENT, not per eligible chunk, which is what makes a biome sample
  affordable there — a few thousand rather than a few hundred thousand.
- **Do not move that walk into the eligibility pass.** The field decides where
  sites go without asking the biome, and that is what keeps the expensive part
  cheap and the placement order-free.
- `structure` vs `assigned` in a `/customdim site-validity` artefact is the
  measurement; they differ on exactly those 63%.

<a id="t55"></a>
### T55 — A mod's dense terrain features are absorbed into the structure model and vanish

- **Symptom:** a worldgen mod is installed, its biomes generate correctly, and
  the landforms it is known for never appear.
- **Measured**, `minecraft:the_end` with BetterEnd installed, radius 2048
  (~65,536 chunks), via `/customdim site-validity`:

  ```
  betterend:mountain           1 site    = 1 per 65,536 chunks
  betterend:painted_mountain   0 sites
  betterend:megalake           0 sites
  betterend:megalake_small     1 site

  what the mod asks for: mountain spacing 3 (~1 per 9 chunks)
                         megalake spacing 4 (~1 per 16 chunks)
  ```

  Roughly a **7,000x** reduction on `mountain`, and zero for two of the four.
- **The obvious confound is ruled out.** 238 of the 403 sites (59%) are in
  `betterend:` biomes, so the biome source is not the reason — the biomes are
  the dominant ones in that dimension.
- **Cause:** these are declared as structure SETS on `minecraft:random_spread`,
  which `NoisePoolBuilder.ABSORBED_PLACEMENT_TYPES` absorbs. A set built to
  fire every 3 chunks becomes one weighted member of a shared `deco` pool that
  places a few hundred sites across the whole disc. Their generation step gives
  them away: `betterend:megalake` is step `lakes` and `betterend:mountain` is
  `raw_generation`. **They are terrain features wearing a structure set.**
- **Not a BetterX problem.** Any mod expressing landforms as dense
  random_spread sets is absorbed and gutted the same way. Spacing under about
  8 is the tell — no adventure structure is meant to appear every 128 blocks.
- **A density band cannot fix it.** The smallest group (`deco`) is still ~96
  blocks of exclusion, an order of magnitude too sparse, and
  `structures.exclude` removes a set rather than passing it through.
- **THE FIX:** classify the set into the `ubiquitous` group
  (`NoisePoolBuilder.UBIQUITOUS_GROUP`). Such a set is not absorbed and keeps
  its own vanilla grid. Curate it in `scripts/gen-structure-groups.py`.
- **The trigger is CURATED, never derived from spacing.** This platform
  rescales spacing — the structure presets, `rescale()`, and Moog's 1.65x
  ([T48](#t48)) — so a rule keyed on it is circular and moves whenever density
  is tuned. Spacing is evidence for whoever curates, not a runtime gate.
- **Every caller of the absorb decision must use the set-id overload**
  (`noiseManaged(setId, placement)`). Two of the four were left on the old
  one-argument form and diverged from the live world by 63 census mismatches;
  `/customdim structure-census <dim>` is what catches that.
- **Do not** "fix" it by making `deco` denser. That would multiply every other
  deco structure in the pack to reach a handful of landforms — T52's
  anti-pattern, from the other direction.

<a id="t54"></a>
### T54 — A structure's declared jigsaw fields are not its footprint

- **Symptom:** a placement, spacing or collision calculation keyed on `size` or
  `max_distance_from_center` puts structures on top of each other, or gives a
  1-block cave marker the same elbow room as a 244-block castle.
- **Measured**, 781 structures, against real `StructureStart.getBoundingBox()`
  spans from `/customdim structure-sizes`:

  ```
  size (jigsaw pool depth)         Spearman +0.478  (n=740)
  max_distance_from_center         Spearman +0.357  (n=501)

  declared size 1 -> spans   5-72 blocks
  declared size 6 -> spans  1-244 blocks
  minecraft:village_plains  declares a bound of 80, spans 170
  minecraft:mineshaft       declares NEITHER field, spans 163
  ```

- **Cause:** `size` is the jigsaw pool's maximum expansion DEPTH and
  `max_distance_from_center` is the assembler's SEARCH BOUND. 280 of 783
  structures declare no bound and 246 of the rest sit on the default 80.
  Neither describes extent, and a non-jigsaw structure declares neither.
- **The answer is measured, and cheap.** `Structure.createStructureStart` takes
  the world only as a `HeightLimitView` and reads no block data, so an
  assembly needs no chunks and no pregen. The whole registry sweeps in five
  minutes and the result is in `config/custom-dimensions/structure-sizes.json`
  (jar-baked as `structure_sizes.json`).
- **Do not** reintroduce the declared fields as a proxy when the table looks
  stale. Re-measure: `/customdim structure-sizes minecraft:overworld 3 900`,
  then `mise run sizes <artefact>`. `mise run sizes --report <artefact>`
  reprints the correlation above from the artefact itself.
- **Sweep an OVERWORLD.** A nether reaches 727 of 783 and truncates deep
  structures against its ceiling — `minecraft:ancient_city` is 242 blocks in an
  overworld and 65 in a nether. Where both measure one, 73% agree exactly.
- A structure with no measurement is OMITTED from the table, never zeroed: a
  zero silently grants no personal space at all.

<a id="t53"></a>
### T53 — A want reached by re-draw becomes a universal filler

- **Symptom:** one `structures.wants` entry fills a large fraction of a
  dimension. Measured during the A2 fix: a single want produced **340
  `minecraft:monument` sites** in a 512-chunk radius, and the WANTED count for
  the overworld went 61 → 427 in one build.
- **Cause:** a wanted structure is admitted to the pool at full weight with its
  biome predicate bypassed. If that bypass travels with the structure as a
  re-draw CANDIDATE rather than staying with the site's own ASSIGNMENT, then
  every site whose chain nothing else fits falls through to it. A want stops
  meaning "admit this where the noise puts it" and starts meaning "use this
  wherever nothing else fits".
- **The rule:** the bypass belongs to the ASSIGNED structure only — depth 1 of
  the chain — never to a candidate reached by re-draw.
- **Why it nearly shipped:** the gate at the time was "violations reach zero",
  which this satisfied perfectly. A metric that can only move the right way is
  not a gate. The gate that caught it was `WANTED` held to an exact count,
  because that one could move in both directions.
- **Do not** raise `StructurePick.MAX_CANDIDATES` to reduce empty sites. 65% of
  fills already come from the re-draw chain and the depth histogram does not
  decay; a deeper chain is the same anti-pattern wearing a different name.
  Absence is a valid outcome.

<a id="t52"></a>
### T52 — Site count is decoupled from pool variety (ANTI-PATTERN)

- **Symptom:** a dimension generates far more structure sites than it has
  distinct structures to fill them, so a player meets the same few over and over.
- **Measured**, `adventure:the_end_citadel`, seed 4890946857129946416, via
  `/customdim site-validity`:

  ```
  3,265 sites over 205,887 chunks   = 1 per 63 chunks, ~127 blocks apart
                                    = 2.85x the overworld's site density
  35 distinct structures occupy them = ~93 copies of each
  mes:astral_meteorite   608 sites  (18.6% of the dimension)
  minecraft:end_city     393 sites  (12.0%)
  top three structures              = 37.6% of every site
  ```

  **393 end cities in one dimension.** A structure meant to be the landmark of an
  entire dimension appears 393 times.
- **Cause:** site count is derived from the noise profile and the dimension's
  border area alone. **The pool never enters the calculation.** Pool sizes across
  the shipped dimensions range from 42 to 353 structures — an 8x spread a
  pool-blind model cannot express.
- **This is an anti-pattern, not a tuning question.** Lowering a profile's
  admitted fraction does not fix it; it scales every dimension by the same factor
  and leaves the 42-structure dimension and the 353-structure dimension with the
  same site count.
- **The rule, and it is a CEILING not a target:**
  `maxSites = poolSize x REPETITION_CEILING`, per GROUP, with the exclusion
  radius solved for it over a few corrective rebuilds. Any density model that
  does not read the pool size is wrong by construction.
- **Read as a TARGET the rule is actively harmful.** Most dimensions already
  sit well under any budget, so a target inflates them — one 512-radius
  dimension would go from 36 sites to 1512. `NoiseStructurePlacement.forGroup`
  only ever raises exclusion, so it can only remove sites.
- **Observed copies land above the ceiling**, because the target counts the
  POOL and only about half a pool typically places. Low cover — a dimension
  naming far more structures than ever appear — is a SEPARATE defect and must
  not be fixed by loosening this.
- **Do not** treat it by shrinking `borders.player` — that is the symptom, costs
  the dimension the space it was given on purpose, and leaves every other
  dimension decoupled.
- **Trap within the trap:** `docs/mod-internals/worldgen-structures.md` cites
  62,556 positions for this dimension against an 8192 border. The config ships
  **4096**. Combining that stale count with the current border yields a wildly
  wrong density — measure with `site-validity`, never quote the doc.

<a id="t51"></a>
### T51 — Structure placement is noise-managed, not a grid; grid arithmetic describes nothing

- **Symptom:** a spacing, density or frequency calculation over the pack's
  structure sets produces a number nothing in the world matches — a mean gap of
  tens of blocks where the world shows hundreds — and a fix aimed at set
  spacing changes nothing.
- **Cause:** noise placement is the default for every managed dimension.
  `NoisePoolBuilder.noiseManaged` dissolves a set's own placement into one of
  seven meta-groups whenever the placement is vanilla `minecraft:random_spread`
  or a type in `ABSORBED_PLACEMENT_TYPES`. Measured against the runtime
  catalogue: **408 of 411 sets are absorbed** — 221
  `moogs_structures:advanced_random_spread`, 184 `minecraft:random_spread`, and
  one each of `betterdeserttemples:desert_temple`,
  `yungsapi:enhanced_random_spread`, `betterjungletemples:jungle_temple`. Only 2
  `minecraft:concentric_rings` and 1 `betterstrongholds:stronghold` keep their
  own grid. An absorbed set's `spacing`, `separation` and `frequency` are
  discarded; the group's `NoiseStructurePlacement` decides everything.
- **What the grid path still governs:** `DimensionStructures.rescale()` and
  `withExplicitSpacing()` guard on an EXACT `RandomSpreadStructurePlacement`
  class check, and both are reached only from the legacy grid path — used when
  the noise plan is suppressed (void and superflat dimensions) — plus the
  exit-shrine branch. `structureDensity` reaches the default path through
  `NoiseGroupPlan` group profiles instead.
- **`structures.spacing` is not a general control.** In `transformedNoise` the
  override map is passed only to `exitShrineSet`. An entry naming any other set
  is discarded.
- **Fix:** read the dimension's `structure profile` boot line, which states
  which path ran. `noise radius=...c groups=n/m positions=N` is the noise path;
  `density=... (N sets kept, N rescaled, N dropped)` is the grid path. Take
  placement-type counts from `config/custom-dimensions/extractors/registries.json`
  ([T49](#t49)), never from a jar scan, and take Moog's 1.65x
  ([T48](#t48)) as governing the pass-throughs, not the pack.

<a id="t50"></a>
### T50 — Measuring a live world: the probes that silently return nothing

- **Symptom:** a diagnostic reports zero hits, or a uniform value, and the
  conclusion drawn from it is confidently wrong.
- **Causes, all measured:**
  - `execute ... run say X` returns an **empty string** over RCON. The command
    runs; its output goes to chat, not the RCON response. Use `execute if
    block ...` with no `run`, which answers `Test passed`.
  - A block or biome query at coordinates outside loaded chunks answers `That
    position is not loaded`. `/locate` reads the structure grid without
    loading anything, so its coordinates are unprobeable until you
    `forceload add` them (and `forceload remove all` afterwards).
  - **Biomes are 3D.** The y-slice you sample is not the slice the game used
    for a structure's validity check — a marine structure can sit in a chunk
    whose y48-63 biome is `snowy_taiga`. Ask terrain, not biome: heightmaps
    give ocean floor and water surface separately.
  - **A dimension's band midpoint is not a deep read.** The overworld band is
    roughly -64..319, so its midpoint is y~127 — above the terrain on 3795 of
    4586 columns, i.e. sampling sky. A probe placed there returns a clean,
    confident, uniform answer rather than an error. Sample midway between the
    band floor and the column's OWN surface instead.
  - Heightmaps pack `ceil(log2(worldHeight+1))` bits. This world is 512 tall
    (`min_y -64`, Tectonic `max_y 448`), so entries are **10 bits, not
    vanilla's 9** — a 9-bit decode yields a plausible-looking constant.
  - `ps`/`pgrep` over SSH matches **your own command line**. A grep for a
    process name finds the SSH invocation containing that name.
- **Fix:** `scripts/scan-structure-placements.py` does this correctly offline
  from region files, and needs no server.

<a id="t49"></a>
### T49 — Static jar scans resolve convention tags wrongly; use the runtime catalogue

- **Symptom:** an analysis of which structures can generate where is
  confidently wrong. Structures appear at sea that "cannot" spawn there.
- **Cause:** `c:*` convention tags are populated by the Fabric Convention Tags
  API **at boot**, not in datapack JSON. A jar scan sees an almost-empty tag:
  `#c:is_overworld` resolves to **92 biomes with no oceans** statically and
  **305 biomes including 9 oceans** live. Cristel Lib also rewrites some
  placements before registry load ([T48](#t48)), and set membership differs
  both ways — the static scan lists sets for uninstalled mods and misses
  installed ones.
- **Fix:** `/customdim catalogue` writes the live registries to
  `.seed-rolling/catalogue/registries.json`; `scripts/extract-registries.py`
  pulls it to `config/custom-dimensions/extractors/registries.json`. That file
  is ground truth for biome tags, structure validity and placement.
  `structures.json`, `biomes.json`, `blocks.json` and `entities.json` remain
  jar scans — fine for ids and per-biome detail, wrong for anything
  tag-dependent.

<a id="t48"></a>
### T48 — Moog's structure sets generate 1.65× further apart than any file says

- **Symptom:** a `mes:`, `mns:`, `mss:`, `mtr:` or `mvs:` structure set reads one spacing in its jar, the same spacing in `config/cristellib/<mod>/structure_placement_config.json5`, and a larger one in the live registry — `mes:enderskog` is 52/22 in both files and 86/36 in the `/customdim catalogue` dump. 221 of the pack's 411 sets are affected, every one of them `moogs_structures:advanced_random_spread`.
- **Cause:** Moog's Structure Lib multiplies both `spacing` and `separation` by a hard-coded `1.65` in the `AdvancedRandomSpread` constructor (`com/finndog/moogs_structures/world/structures/placements/AdvancedRandomSpread.class`, `ldc2_w double 1.65d; dmul; Math.round`). It applies to every source of the value — the mod's own datapack, a world datapack override, and Cristel Lib's config alike — and no config key turns it off.
- **Fix (in place):** `effective_grid()` in `scripts/extract-structure-sets.py` applies the same factor, so the census, `config/custom-dimensions/extractors/structures.json` and the rarity tiers in `structure-groups.json` record what the registry holds. Verified: all 409 comparable sets match `extractors/registries.json` exactly.
- **Consequence worth knowing:** to set one of these sets' spacing, write the pre-multiplier number in `config/cristellib/<mod>/structure_placement_config.json5` and expect `round(value × 1.65)` in game. Measured: `mes:enderskog` at 40/18 in that file boots as 66/30.

---

<a id="t32"></a>
### T32 — A candidate's thumbnail is a window on spawn, not a picture of the world

A seed's map and its banked facts appear to contradict each other — the thumbnail shows an island and a coastline while `terrain.waterFraction` reports the world as almost entirely submerged, and play confirms the facts. `CandidateRender.Resolution.LOWRES` covers a fixed `THUMBNAIL_BLOCKS` (512) centred on the dimension's **declared spawn**, one block per pixel, while the facts cover the whole playable disc — 1024 blocks across for a 512-radius dimension, so the thumbnail is a quarter of the area, and the roller picks a habitable spawn, which makes that quarter the least representative part by construction. Measured on `the_wuthering_wisteria` (seed `-8181123680324586121`): facts 98.0% water, live world 97.3%, highres PNG 96.6%, lowres PNG **81.4%**. Compare like with like — `customdim render-check <dim> <seed>` puts world, facts and render on ONE grid and reports where they disagree, `render-check-headless` does facts ↔ render with no world — and judge wetness from the highres view or the facts, never from the thumbnail. Any "the map disagrees with the measurement" report needs the sampled AREA established first.

---

<a id="t59"></a>
### T59 — A zero-distance tie is settled by the previous lookup, not by the config

- **Symptom:** a biome named in a dimension's `biomes` list holds no ground, and
  nothing accounts for it — its band is inside the world's measured range, the
  overlap check passes, and the boot log reports it placed.
- **Cause:** `ParameterRange.getDistance` returns 0 anywhere in `[min, max]`,
  **both ends included**, so two bands sharing a boundary are both at squared
  distance exactly zero from a sample sitting on it.
  `SearchTree$TreeBranchNode.getResultingNode` replaces its incumbent only on
  `best > candidate` — strictly smaller — so neither tied band can displace the
  other and the incumbent keeps the cell.
- **The incumbent is the previous lookup's result.** `SearchTree.get` passes
  `previousResultNode.get()`, a `ThreadLocal<TreeLeafNode>` holding what that
  worker thread last resolved, into `getResultingNode` and stores the result
  back afterwards. So a tied band that won the previous column arrives at
  distance 0 and wins again; where the incumbent is some third biome at a
  positive distance, whichever tied band the traversal reaches first takes the
  cell and the other cannot beat zero.
- **The winner is therefore fixed by generation order**, deterministic given a
  worker thread's full state and derivable from no file. It follows that two
  generations of the same seed can disagree at a tied cell if chunk scheduling
  differs — the mechanism permits it; nothing here has measured it.
- **Boundaries land on ties because the noise piles up at its rails.** Weirdness
  saturates at +-1.0 and plateaus at +-0.5, so a partition cut on round numbers
  puts its boundaries exactly where the samples concentrate — a machine-fitted
  partition collides with the rails by construction, a hand-written one mostly
  does not. Most samples landing exactly on an internal boundary sit on those
  four values, and the share rises with sampling density. The exact proportion
  tracks how many partitions remain in the pack, so measure it rather than
  quoting one.
- **Fix:** do not let two bands share a boundary the world's climate returns.
  `scripts/check-biome-bands.py` reports a band whose entire reachable territory
  is one such value. It names the hazard and never which band dies, because a
  config cannot be read for that.

<a id="t58"></a>
### T58 — A sparse climate sample understates a world's range, and the checker treats it as proof

- **Symptom:** `scripts/check-band-reach.py` reports a biome band as
  `cannot generate` and fails the gate, but the biome does generate. Or the
  reverse: a band judged against a type representative measured at a different
  border reads clean when it is dead.
- **Cause:** `config/custom-dimensions/climate-axes.json` holds a measured
  range per dimension, and the checker treats a `perDimension` entry as proof
  while an `axes[]` representative is only INDICATIVE. A range sampled along a
  path rather than across the playable square understates every axis, because
  the true range is over `[-B,+B]^2` and any path inside it is a subset.
  Measured across 44 axes, diagonal against grid over the same square: median
  **x1.62**, range x1.00-x5.43. `MARGIN = 0.05` does not cover that, and an
  understated range makes a live band look dead. The tail is much worse than
  the median — `the_abyssal_shrine` weirdness reads 0.196 on a diagonal against
  0.847 on a grid (x4.32) — so a single dimension's ratio is not the number to
  generalise from.
- **Span grows with radius only until an axis saturates ITS ROUTER'S clamp**, so
  a representative measured at another border is not uniformly biased. Measured
  on `the_sun_kingdoms` (`multi_biome`/`adventure:wide`), one seed, one router,
  span against a 512 baseline: temperature x6.75 and erosion x2.13 still
  climbing at 4096, depth flat past 2048, and **weirdness x1.07 across an
  eightfold radius change**, already at its rail by 512. Weirdness carries 589
  of 1163 band-axis instances, so where it IS railed the radius argument fails
  precisely where it is load-bearing, and the bias that matters there is the
  sampling PATH. Density is not the mechanism: at a fixed radius, 440 points
  against 121 move span x1.00-x1.03 on every axis.
- **The rail is per-router, not universal**, and a band outside +-1 is dead only
  where its own dimension says so. Across 15 grids the global ranges reach
  temperature +1.083, continentalness -1.260, erosion -1.188 and weirdness
  +1.303 / -1.186. The mechanism above is one dimension's evidence: whether
  another router's weirdness saturates as early is unmeasured, and the cheap
  probe is the same nested-square run on a dimension whose weirdness reaches
  past the rail (`the_crucible` at -1.186, `the_buried_age` at +1.303).
- **`depth` cannot be judged by this check at all.** `customdim sample-noise`
  hard-codes `y=0` and depth is linear in y at -1/128 per block, so a
  surface/underground band pair is compared against a single-height reading.
  `check-band-reach.py` skips depth with a counted reason; the reductio is
  `the_ashgrove`, whose own measurement declared all 14 of its surface biomes
  ungeneratable. 342 of 1163 checks were on depth, and coverage without them is
  821 — exactly the pre-depth figure, so a past "342-band coverage win" was
  entirely this.
- **Fix:** sample the playable square, not a path through it. `borders.player`
  is a RADIUS and the vanilla world border is SQUARE (`WorldBorderManager`:
  `setCenter(0,0)`, `setSize(radius * 2)`), so an N x N grid over `[-B,+B]^2`
  covers the world exactly and nothing outside it. `customdim sample-noise`
  reads the noise router and needs no loaded chunks — only the ServerWorld must
  exist — so a grid costs nothing in generation. At ~46 ms per RCON call an
  11x11 grid is ~14 s per dimension. Harness:
  `.handoff/band-verdicts/grid-sample.sh`.
- **A wide span can be a clamped axis rather than a rich one.** Read `distinct`
  beside `min`/`max`: an axis reporting span 2.000 bounded at exactly +-1.000
  across a third of its samples has points pinned at the rails. `distinct` also
  measures sampling resolution, not only axis quality — the same square gives 75
  distinct at 121 points and 243 at 440, span unchanged — so entries compare
  only at equal point counts. A band
  containing a rail value still generates, so the band-reach verdict "live" is
  correct — but it collects every pinned point and the biome takes a
  disproportionate share, which is [T19](#t19) reached by another route. Two
  different questions; answering one does not answer the other.
- **Only a `perDimension` entry can produce a `cannot generate` verdict.**
  Adding rows to `axes[]` never converts an INDICATIVE finding, whatever the
  row contains — `measured_range` returns `"representative"` for all of them.
- A `customdim load` does not survive an `mc` restart ([T18](#t18)), so a
  sampling run must load each dimension in the same pass that samples it.
- **The shape that produces the dead bands is a run of equal steps from -2.0**,
  the schema's floor rather than any world's. The run stops where the author ran
  out of biomes and hands the rest of the axis to one wide catch-all, so judging
  widths across a whole chain finds nothing: at `7f5c5e98` `the_crucible`'s tail
  is 1.5215 against a 0.0694 body. The run is the signal, the tail is noise.
- **It was generated, not hand-written.** `the_highland_crossing` is 27 x 0.0889
  and `the_frozen_hearth` 21 x 0.1143, both ending at +0.4003 to four decimals —
  two band counts and one endpoint is computed output. The tool was
  `scripts/seed/biome_sampler.py` with `scripts/seed/biome_source_mixing.py`
  (which writes `[-2.0, 2.0]` directly), deleted at `89f9202c`. Whether it
  should return is open; that it existed is not.
- `check-biome-bands.py` catches the shape with no measurement, which is what a
  new dimension needs because it has no `perDimension` entry yet. Measured: 10
  dimension/axis pairs over 167 bands at `7f5c5e98`, and zero on eight clean
  states either side of it. Fit boundaries to the dimension's own range in
  `climate-axes.json`, ends clamped to +-2.0.
- **The target is that no listed biome is ignored, NOT that shares are equal.**
  A dominant biome with the others each occupying somewhere real is a good
  world; equal parts of everything is a quadrant world, and `checkerboard` is
  the type for that. Equal-area CDF fitting is one technique for the specific
  failure of bands that catch nothing — it is not the objective, and a band's
  share is information, never a pass/fail. Size slots so each band expects
  samples at its own filter pass rate: plain equal area assumes the fitted axis
  is a band's only constraint, and for a band carrying a second-axis filter it
  is not, which collapses slots into hairlines that catch nothing.

<a id="t60"></a>
### T60 — A candidate's `inputHash` names the roll it came from, not the newest measurement of it

- **Symptom:** a dimension's committed `_thumb.json` resolves cleanly to a
  banked candidate, the file parses, the grid is the right density — and the
  shares in it describe a world the current config has not produced for days.
- **Cause:** `_thumb.json` carries the `inputHash` and `seed` of the candidate
  that WON its roll. The bank is keyed by that hash, and the same
  (dimension, seed) is re-measured under a NEW hash every time the config or
  the mod's measurement-relevant bytes change. Nothing rewrites the thumb when
  that happens, so the thumb keeps pointing at the roll while newer
  measurements of the same seed accumulate under other keys. Measured:
  `the_luminous_caverns` holds **19 banked measurements of one seed across
  eight days**, its thumb points at the oldest, and `distinctCount` moved
  5 → 7 → 12 → 10 in a single morning.
- **Fix:** never read a share out of the thumb's candidate. Gather every record
  for the (dimension, seed) across all hashes and select on evidence, not on
  the thumb — see [T62](#t62) for which one.
- **`configFingerprint` is populated and usable, but only in records written by
  a mod carrying `DimensionFingerprints.canonical`.** Older records hold the
  four-character **string** `"null"` — quoted, not JSON null, so it decodes
  cleanly and reads like a value. Every one of them therefore matches every
  other, and matches nothing new. **A comparison must reject the literal
  `"null"` explicitly rather than treat it as a fingerprint**, or it corroborates
  every old record against every other old record. Same shape as
  [T62](#t62).

<a id="t61"></a>
### T61 — A batched `rcon-cli` command list exits 0 having answered only the first few

- **Symptom:** a sweep over many dimensions "completes" in a fraction of the
  expected time, the wrapper exits 0, and the output holds a prefix of the
  commands with no error anywhere. Or the output is entirely
  `Failed to connect to RCON server` and the exit code is still 0.
- **Cause:** `rcon-cli` reads a newline-separated command list from stdin and
  runs them in one connection. If the server restarts, pauses or drops that
  connection mid-list, the remaining commands are abandoned and the process
  still exits 0. Measured: a 82-command sweep answered 10 and exited 0; a later
  run answered 0, logged `dial tcp [::1]:25575: connect: connection refused`
  72 times, and exited 0. This is [T17](#t17)'s "cannot tell a timeout from a
  success" reaching a case T17 does not describe — the loss is the TAIL of a
  batch, not a truncated line.
- **Fix:** one `rcon-cli` invocation per command, appending to a file as it
  goes, so partial progress survives and the shortfall is visible as a row
  count. Count the answers against the commands and treat any shortfall as a
  failed run. Multiple commands as separate ARGUMENTS do not work either —
  `rcon-cli` concatenates them into one malformed command.

<a id="t62"></a>
### T62 — The newest banked measurement of a dimension can be of a config nobody shipped

- **Symptom:** taking the most recent record for a (dimension, seed) — the
  obvious correction to [T60](#t60) — gives shares that contradict the
  committed config, and a dimension appears to have lost most of its biomes.
- **Cause:** the bank records every measurement, including throwaway probes.
  A bands-removed experiment, a refit later reverted, or a clone under another
  slug all leave records that are newer than the shipped config's. Measured:
  `the_frozen_strait`'s newest record is a three-biome bands-removed probe,
  while the shipped config carries thirteen entries.
- **Fix:** select the newest record whose `biomes.distinctCount` equals what
  `customdim facts <dim> <seed>` returns from the running server now. Where no
  record matches, the dimension is UNMEASURABLE from the bank and must be
  reported as such rather than approximated — measured 71 of 82 corroborated,
  11 not. This makes the live probe the gate on every share rather than a spot
  check, which is what [K7](#k7) and the `swallowed-worlds` reversal ask for.
  A live reading is itself only valid for the jar that produced it: compare a
  jar's size and mtime against the checkout's before treating a sweep as
  current, because `./dev up` installs a jar and `./dev restart mc` does not.

<a id="t63"></a>
### T63 — A check that never ran reports success, and reads exactly like a pass

- **Symptom:** a comparison, gate or suite comes back green having examined
  nothing. There is no error, the exit code is right, and the output is the
  shape a pass produces.
- **Cause:** the check exits, skips or short-circuits before reaching the
  assertion, and nothing downstream distinguishes "zero failures found" from
  "zero cases examined". Six instances in one session, all different
  mechanisms, all reading as passes:

  | what happened | the tell |
  | --- | --- |
  | a comparison keyed on `e['score']` where the field is `value` | skip count equalled the population (3,268 of 3,268) |
  | a fixture consumer without a `dev` file, so the path resolver exited 1 before the arm ran | the tally line was absent from the output |
  | a batched `rcon-cli` list abandoned after 10 of 82 ([T61](#t61)) | answers fewer than commands |
  | `Task :test UP-TO-DATE` serving cached XML | `BUILD SUCCESSFUL` with no test execution |
  | a gate arm whose recall against the historical defect was zero | it had never fired on any state |
  | an uncorroborated dimension with an empty share map, so every share read 0.0 | every value identical, and identical to the "absent" value |

- **Fix:** a pass is only a pass if you can show the check ran on the population
  you think it ran on. Print the denominator next to the result, and read it:
  a zero denominator, a skip count equal to the population, a missing tally
  line, an exit code that could have come from another process, or a value
  indistinguishable from "not measured" are all the same fault. Prove a new
  gate by breaking its target and watching the specific counter move, never by
  watching the exit code — an exit code aggregates arms and cannot isolate one
  (the duplicate-band gate reached the same conclusion from its own injections).
- **Print the VALUE the check matched on, not only the denominator.** An error
  string, an empty map and a wrong-namespace filename all pass a presence test.
  Three instances in one session: a settle-watch polling for a non-empty RCON
  reply, satisfied within a second by `Failed to connect to RCON server ...
  connection refused`; the same trap a session earlier; and a bank query
  matching `facts__adventure_*` reporting a dimension unmeasured because
  reserved dimensions bank as `facts__minecraft_*`. **A check has to name the
  state it wants, not the absence of nothing** — and it should exit non-zero on
  an unusable result rather than warn, so the result cannot enter a table.

---

<a id="t64"></a>
### T64 — A `customdim` measurement over RCON runs on the main thread with no deadline

- **Symptom:** a read-only diagnostic sweep kills the server. `docker inspect mc`
  shows `RestartCount` incremented, `data/crash-reports/` holds
  `Description: Watching Server` / `java.lang.Error: Watchdog`, and the log says
  `A single server tick took 180.00 seconds (should be max 0.05)`. The rest of
  the sweep then returns `Failed to connect to RCON server ... connection
  refused` and exits 0 ([T61](#t61)).
- **Cause:** an RCON command executes on the **main thread**, and
  `FactsEngine.measure` is synchronous with no tick budget and no deadline. The
  Server thread stack at the kill names it exactly:

  ```
  SpikeSampler.sample -> FactsEngine.neighbourhood -> FactsEngine.safeColumnFraction
    -> FactsEngine.spawnFacts -> FactsEngine.measure -> FactsCommands.facts
  ```

  **No single call is long enough to do it — the tick holds SEVERAL.** Measured
  over 89 calls, in-mod duration:

  ```
  server contended (roller priming + a second operator)  median 10,721ms  max 65,310ms
  server exclusive (roller stopped)                      median  8,883ms  max 25,365ms
  ```

  The slowest call anywhere is **65,310 ms** against a 180,000 ms budget, so one
  command cannot blow it. The crash report says what did: **two RCON client
  threads blocked on two DISTINCT `CompletableFuture$Signaller` objects**, i.e.
  two commands queued on the same thread, serialising into one tick.

  ```
  "RCON Client /0:0:0:0:0:0:0:1 #16"  Id=676  WAITING on CompletableFuture$Signaller@3f92b969
  "RCON Client /0:0:0:0:0:0:0:1 #17"  Id=677  WAITING on CompletableFuture$Signaller@74417c1b
  ```

  **The hazard is two operators measuring at once, not any one dimension.**
  `the_amplified_reaches`, blamed for one such crash, costs 4,547 ms. Load
  changes duration by about 1.8x on the mean; it does not change a `facts`
  value.
- **The crash report names no dimension.** `grep -c adventure:` returns 0. Any
  claim about which dimension was in flight comes from the harness's own row,
  identifies that operator's call only, and says nothing about the other blocked
  client.
- **The roller is not exposed.** `RollPipeline` runs the same measurement on the
  `customdim-roll` thread. Only the RCON route puts it on the tick.
  [K5](#k5) records that `render-check` "polls chunk futures with a finite
  deadline for exactly this reason"; `facts` has no equivalent.
- **Fix, and it is scheduling rather than a budget.** **One operator on RCON at a
  time** — a second agent's `facts` call queued behind yours is what reaches
  180 s. Sweep with a fail-fast harness (one `rcon-cli` per command, abort the
  moment a reply is not the expected shape) so an interruption costs one call
  rather than the tail of the list, and check `docker logs mc --tail 20` for
  `customdim-roll` lines before starting. `MAX_TICK_TIME` is overridable in the
  consumer `.env` and raising it buys headroom, but every number taken under a
  raised budget must say so.

<a id="t65"></a>
### T65 — Two bands on different axes in one dimension are at distance zero from each other

- **Symptom:** a band is moved to a different climate axis to "use a better
  axis", and `scripts/check-biome-bands.py` reports N overlapping pairs where it
  reported none. Nothing in the file overlaps when read axis by axis.
- **Cause:** a band constrains the axes it names and leaves the rest at
  `[-2, 2]`. A humidity-only band and an erosion-only band therefore both contain
  any sample whose humidity is in the first window and whose erosion is in the
  second, and both are at squared distance **zero** from it — which is
  [T59](#t59)'s tie, reached without sharing a boundary. Measured: moving one of
  seven humidity bands to erosion produced `6 overlapping pair(s) of 7 explicit`.
- **Fix:** add the axis, do not swap it. A band carrying
  `{"humidity": [...], "erosion": [...]}` keeps its place in the humidity
  partition and takes the second axis as a further constraint.
  **Size the result, do not assume it:** a second-axis filter multiplies pass
  rates, and [T58](#t58) already warns that this "collapses slots into hairlines
  that catch nothing" — measured, a plausible erosion window intersected with an
  existing humidity slot took it from 33 grid cells to **2**.
- **Erosion is not a continuum in these routers, it is three or four shelves.**
  The 33 cells of one humidity slot carried erosion values
  `-0.672 x1, -0.646 x1, -0.575 x1, -0.550 x8, -0.521 x1, -0.500 x4, -0.103 x1,
  -0.062 x1, 0.105 x1, 0.300 x14` — 22 of 33 on three values. Put an erosion
  boundary in the EMPTY GAP between shelves, where it can neither tie nor collect
  a pile-up.

<a id="t66"></a>
### T66 — A jar scan sees biomes inside a mod's optional built-in datapacks

- **Symptom:** a biome appears in
  `config/custom-dimensions/extractors/biomes.json`, its mod is installed and
  loaded, and the biome is in no live registry. Naming it produces a band dropped
  at boot ([T38](#t38)) and a config that names something that can never
  generate.
- **Cause:** the extractors walk every `data/*/worldgen/biome/*.json` in a jar
  without asking which built-in pack contains it. A Fabric mod may ship several
  packs under `resources/` and register only some of them.
  Measured: `minecraft:pale_garden` sits at
  `resources/vanilla_backport_compat/data/minecraft/worldgen/biome/pale_garden.json`
  in `wwoo-fabric-2.6.7.jar`; the server's `Available Data Packs:` line lists
  `wwoo`, `wwoo:resources/wwoo_main` and `wwoo:resources/wwoo_remove_ores` and
  not that one, so it is not offered, never mind enabled. It is the ONLY
  difference between the jar scan (397 biomes) and `registries.json` (396), in
  either direction.
- **This is a different mechanism from [T49](#t49).** T49 is about convention
  tags being populated at boot; this is about PACK SELECTION.
- **Fix:** take the population from `registries.json`, the runtime catalogue.
  `scripts/check-content-coverage.py` reads the jar scan, so its denominator
  counts biomes nobody can name.
- **A pack the server does not offer cannot be enabled from the server side**,
  and enabling it is not always the answer: this one's biome references two
  1.21.4 vanilla features and five features built from blocks that do not exist
  here — `minecraft:pale*` over the 15,521-entry block catalogue is empty.

<a id="t67"></a>
### T67 — `docker exec -i` inside a `while read` loop eats the rest of the input

- **Symptom:** a loop over an N-line list runs the FIRST iteration, exits 0, and
  reports success. No error, right exit code, and a tally line that says
  `COMMANDS=1` against an N-line input. In a batched-RCON harness it looks
  exactly like [T61](#t61) — a run that "completed" far too fast — but the cause
  is on this side of the wire.
- **Cause:** `docker exec -i` (and `ssh`, and `rcon-cli` reading a command list)
  attaches STDIN and reads it to EOF. In

  ```bash
  while IFS= read -r line; do
    docker exec -i mc rcon-cli "$line"     # <- consumes the rest of the file
  done < list.txt
  ```

  the loop's stdin IS `list.txt`, so the first `exec` drains the remaining N-1
  lines into the container and the loop ends. Measured: a 108-row sweep ran 1.
- **Fix:** give the loop its own descriptor and the command an empty stdin.
  Either is enough; use both.

  ```bash
  while IFS= read -r line <&3; do
    docker exec -i mc rcon-cli "$line" </dev/null
  done 3< list.txt
  ```

  Drop `-i` entirely where the command needs no stdin.
- **The tell is the denominator, never the exit code.** Print what you issued
  against what the list held and read it ([T63](#t63)). Every symptom of this
  bug is indistinguishable from success without that line.

<a id="t68"></a>
### T68 — A fresh world's first boot logs a FATAL that belongs to Distant Horizons

- **Symptom:** the boot after `./dev reset-world` logs, inside thirty seconds,
  `Failed fixing Level-Data in level.dat (./world/./level.dat)`, then
  `[Server thread/FATAL] Failed to load server level, error:
  [SQLITE_READONLY_DBMOVED]`, then `Failed to load chunk 0,0` and
  `Failed to read chunk [0, 0]` — and then `Done (Ns)!` and a healthy
  container. Three unrelated faults that read as one cascade.
- **The FATAL is Distant Horizons', and "server level" is DH's level.** The
  exception type is `dh_sqlite.SQLiteException` and the stack is
  `DhServerWorld.getOrLoadLevel` -> `DhServerLevel.<init>` ->
  `ServerLevelModule` -> `FullDataSourceV2Repo` ->
  `DatabaseUpdater.runAutoUpdateScripts`, running
  `0010-sqlite-createInitialDataTables.sql` against one dimension's
  `data/DistantHorizons.sqlite` on the `dh-db` volume ([P5](#p5)). Minecraft's
  world loader is not in the stack. The FATAL level and the wording are DH's
  own. The cost is that dimension's LOD data for that boot; the next boot
  creates the database clean.
- **`Failed to load chunk 0,0` is a separate fault with a separate cause**, and
  reading it as fallout from the FATAL wastes the triage. Its cause line is
  `IllegalArgumentException: Expected directory, got /data/./world/entities`,
  thrown by vanilla's region storage from
  `C2MEStorageThread.scheduleChunkRead`. `Files.isDirectory` is false for a path
  that does not exist yet, and c2me's rewritten chunk IO surfaces that as a
  failed read rather than an empty one. `world/entities` appears seconds later
  and the message does not recur.
- **`Failed fixing Level-Data` is the datafixer on a `level.dat` that has no
  content to fix.** Fresh world, nothing to migrate.
- **All three are first-boot-only.** Measured: one occurrence on the boot that
  follows the wipe, zero across the three subsequent boots of the same world.
- **What actually threatens that boot is [T57](#t57), six minutes later**, and
  the healthy container immediately after `Done` says nothing about it. Read
  `data/crash-reports/` and `data/logs/*.log.gz` before calling a post-wipe boot
  good.
- **Confirm rather than assume**, on the boot after the one that logged it:
  `grep -c "Expected directory, got\|SQLITE_READONLY_DBMOVED" data/logs/latest.log`
  must be 0, and the dimension's database must now exist on the volume
  (`docker run --rm -v <project>_dh-db:/l alpine ls -la /l`).

---

## T58 addendum — the rail effect, with a number

To be appended to [T58](../../TROUBLESHOOTING.md#t58), which states this in prose
("a band containing a rail value still generates — but it collects every pinned
point and the biome takes a disproportionate share") and has never carried a
measurement of it.

- **Measured, on `the_frozen_strait`.** Commit `9a2ca470` re-fitted its
  weirdness partition and gave `minecraft:frozen_ocean` the band
  `"weirdness": [-1.0, -0.996]` — **0.004 wide**. That dimension's own measured
  weirdness is `min -1.0, max 0.95, span 1.95` over 1681 samples
  (`config/custom-dimensions/climate-axes.json`), and the min sits **exactly**
  on the rail. So the band covers **0.205% of the axis the world crosses**.
  The banked 41x41 measurement of that config gives `frozen_ocean`
  **19.8% of the ground** — a **97x** over-representation.
- **Why it matters more than a share statistic.** The biome read as having
  started generating: it held nothing before the re-fit and a fifth of the
  world after it. That looks like a fix and is not one. The band did not find
  the biome room; it parked on the point where the noise saturates and
  collected every pinned sample.
- **The probe:** move the band off the rail — anywhere strictly inside
  `(-1.0, 0.95)` — and re-measure. If the share collapses toward the band's
  width, the ground was the rail's and not the biome's.
- **The tell, before you have a probe:** a band whose endpoint is exactly
  `-1.0`, `-0.5`, `+0.5` or `+1.0`, whose width is a small fraction of the
  dimension's measured span, and whose share is a large one. All three
  together, on the same band.

<a id="t69"></a>
### T69 — A dimension listing a dozen biomes generates one, and the boot line's `mixed-in` field says why

- **Symptom:** a dimension whose author listed a dozen biomes and hand-banded
  several generates one or two. Every band looks correct, `check-biome-bands.py`
  and `check-band-reach.py` are green, and `customdim facts` reports bands
  holding **zero** cells while their climate windows are large and well inside
  the disc — measured at 407, 399, 327, 218 and 211 cells of ~1265 owning none.
- **The tell is one field in the boot line.**
  `Dimension X: biome source built (…, N mixed-in of M requested)` — `N` is
  `dealt.foreign().size()`, the biomes that declared no climate cells anywhere.
  `0 mixed-in` is healthy. Compare `the_gritlands` (13 banded, 0 mixed-in,
  every band holds ground) against a swallowed one reporting 7 or 8.
- **Cause:** `DimensionManager.dealRemaining` deals the **whole** leftover pool
  round-robin to biomes with no declared placement, and those cells join
  `declared`. A band is protected inside its own window — filler carries an
  offset one fixed-point unit above the heaviest band actually placed, so
  vanilla's `square(offset)` term makes it lose there — but the deal is not
  bounded, so filler still blankets every sample **outside** every band window.
  A dimension with no bands at all is unprotected everywhere.
- **This is not [T55](#t55) and not a band-reach fault.** The band is neither
  too small nor unreachable. Read a "band holds no ground" result against the
  boot line's `mixed-in` before reaching for the band.
- **Fix:** remove the bare entries whose namespaces declare no climate cells, or
  give them bands. With `foreign` empty, `dealRemaining` drops the pool whole and
  the author's placement is the only placement. Banding them instead needs every
  band in the dimension to carry the same axes — see [T65](#t65).
- **The floor is built on the heaviest band CELL, never on the default.** An
  authored `offset` is used raw and can exceed `BAND_OFFSET_BASE × g` — a floor
  derived from the default would sit under an authored band and hand filler that
  band's window.

<a id="t70"></a>
### T70 — A comment-only edit to a mod source file changes the compiled bytes, and invalidates every banked scorecard

- **Symptom:** a javadoc or comment is tidied, nothing else, and the next roll
  re-measures dimensions the bank already held — or a jar that should be
  identical to the one a measurement ran against has a different `InputHash`.
- **Cause:** the class file carries a `LineNumberTable` mapping bytecode offsets
  to **source line numbers**. `DimensionManager.class` holds 81 entries.
  Deleting comment lines above a method shifts every entry below it, so the
  compiled bytes move. `InputHash.hashArtefactPath` hashes the CRC of each jar
  entry under `MEASUREMENT_PATHS`, so the hash moves with them, and the bank is
  keyed by that hash — every scorecard for every dimension becomes unreachable.
- **Fix:** treat any edit under a path in `InputHash.MEASUREMENT_PATHS`
  (`command/InputHash.java:202`) as measurement-affecting, comments included.
  Read the list there rather than from memory — it is sixteen prefixes, not the
  two obvious ones, and it reaches `facts/`, `score/`, `mixin/`, `roll/Roller`,
  `SpikeSampler` and the jar-baked worldgen JSON as well as `config/` and
  `dimension/`. A compat mixin re-keys the bank exactly as a scorer change
  does. Land comment work in its own commit, before a roll or after the tag,
  never between a roll and the release it ships in.
- **A re-measure under a new key is this working, not a fault.** The bank is
  keyed on the hash, so any edit under those paths makes every dimension
  re-measure. The scores coming back identical is the expected outcome and the
  cost is the sweep, not the numbers.
- **Measure control and treatment through the SAME procedure.** Gradle answers
  `Task :compileJava UP-TO-DATE` when a source mtime has not moved, so a naive
  before/after reports an unchanged CRC having recompiled nothing. Delete the
  class and recompile for both arms, and confirm the control reproduces its own
  hash before believing the treatment.
- **`build/classes` is not what the hash reads.** `hashArtefactPath` walks the
  **jar**, whose classes are Loom-remapped and carry different bytes from
  `build/classes`. A comparison on `build/classes` is evidence about compilation,
  not about the artefact the bank is keyed on.

<a id="t71"></a>
### T71 — Pulling two bands apart to clear a clamp rail leaves a midpoint tie that no check can see

- **Symptom:** a shared band boundary is taken off a clamp rail by moving one
  band's edge and leaving a gap. `scripts/check-biome-bands.py` reports
  `0 shared boundaries on a clamp rail` and every other arm is clean, but the
  dimension still hands cells to generation order.
- **Cause:** `NoiseHypercube.getSquaredDistance` sums each axis's distance to
  the range, so a sample **inside the gap** is a fixed distance from both
  neighbours and exactly equidistant at the gap's midpoint — [T59](#t59)'s tie,
  recreated by the fix meant to remove it. Nothing reports it: it is not a
  shared boundary, so `rail_ties()` cannot see it, and `overlaps()` is
  open-interval, so two bands with a gap between them are disjoint. Measured on
  `the_gritlands`: taking the `terralith:gravel_desert` /
  `terralith:basalt_cliffs` boundary off the weirdness rail `-0.5` with a 0.001
  gap left the 41x41 grid sampling **-0.4995**, the exact midpoint of
  `(-0.5, -0.499)`.
- **Fix:** move the shared boundary, never the bands apart. Both edges take the
  same new value, so one band contains the rail outright and the other starts
  where it ends. The residual tie is then only at the new boundary — measured
  at 0-2 cells of 1681, against 42-406 sitting on the rail.
- **Choose the new value by rail membership, not by "the world never returns
  this value".** A rail is the router's clamping and survives a re-roll; a quiet
  non-rail value is a property of one seed and a re-roll voids it.
- **A gap is still correct where no rail is involved** — it is only a hazard
  when a pile-up sits beside it, which is what a rail is.

<a id="t72"></a>
### T72 — `sample-noise` takes no seed and reads the live world, so a climate window cannot be measured at another seed read-only

- **Symptom:** a probe is framed as "measure this dimension's climate at seed N"
  — regenerate its grid at a different seed, compare the windows — and there is
  no read-only way to run it. `scripts/sample-climate-grid.sh <slug> <border>
  [n]` takes no seed argument, and the command beneath it answers
  `Dimension not loaded`.
- **Cause:** `customdim sample-noise` is registered as `dimension x z`
  (`command/DimensionCommands.java:152-156`) and its body reads the live world —
  `resolveWorld(ctx)`, then `world.getChunkManager().getChunkGenerator()` and
  `.getNoiseConfig()` (`:1043-1060`). It does **not** take
  `SpikeSampler.base:238`'s `buildOptionsHeadless` path, which is why
  `customdim facts` accepts an arbitrary seed and this does not. And a `facts`
  artefact cannot substitute: it carries `spawn`, `biomes.shares`, `terrain` and
  `structures`, and **no climate axes at all**.
- **Fix:** measuring a window at another seed needs the seed changed in config
  plus a restart plus a world — a lifecycle change, not a read. Where the
  question is only *whether* windows move with the seed, the experiment is
  already banked: dimensions sharing `type`, `noiseSettings`, `borders.player`,
  `settingsOverrides`, `layers`, `flatBiome` and `checkerboardScale` but
  differing in `seed` **are** the same router at two seeds. Measured over 11
  such groups / 39 dimensions / 66 live (group, axis) pairs: **median edge
  spread 24.9% of the window's own width, max 123.8%.**
- **The windows are therefore not seed-robust, and `climate-axes.json` must not
  be regenerated between a roll and the release that ships it.** The projection
  is a transform, not a measurement of truth: the roll scores candidates through
  it, so production has to build through the same one. Regenerating it after the
  winners are chosen means winners selected under one transform and a world
  built under another. Freeze it across the roll; regenerate after a wipe, where
  it reaches only dimensions created later.

<a id="t83"></a>
### T83 — A consumer's `dev` lags the platform scaffold, and an unknown flag is read as an instance name

- **Symptom:** a `./dev` subcommand rejects a flag the platform's own scaffold
  documents. `./dev launch --dev-bridge` answers

  ```
  No Prism instance found for '--dev-bridge'.
  ```

  on a consumer whose instance exists and whose plain `./dev launch` works. The
  flag is quoted back as though it were data, so it reads as a missing instance
  rather than a missing feature.
- **Cause, two halves.** `examples/consumer/dev` reaches an existing consumer
  only through the explicit sync loops in its own `update)` case, so a
  consumer's copy is whatever its last `./dev update` — or a hand-edit — left
  there. Nothing compares versions and nothing warns. And `launch`'s argument
  loop ends in `*) LAUNCH_ARGS+=("$1")`, then passes `LAUNCH_ARGS[0]` to
  `resolve_client_instance`: an unrecognised flag becomes the instance name
  instead of an error.
- **`./dev update` repairs it and costs a linked checkout.** `update)` calls
  `stack_pull` before those loops, which repoints `.stack/current` at a pulled
  release bundle — the `./dev link` is gone and the next `./dev up` runs
  released scripts and jars rather than the checkout's ([T30](#t30) is the same
  directory reached from the other side).
- **Fix:** copy the one file, backed up first (safety rule 4), and leave
  `.stack/current` alone.

  ```bash
  cp <consumer>/dev <consumer>/dev.bak.$(date +%Y%m%d%H%M%S)
  cp <platform>/examples/consumer/dev <consumer>/dev
  readlink <consumer>/.stack/current      # confirm the link survived
  ```

  `diff` the two first where the consumer's copy carries local work: the
  platform's is not guaranteed to be a superset, and this overwrites it whole.

<a id="t82"></a>
### T82 — BetterEnd corrupts memory under Sodium on every water block, in every dimension

- **Symptom:** intermittent hard client crashes, minutes to hours apart, anywhere with water. `net.minecraft.world.chunk.EntryMissingException: Missing Palette entry for index N` with `be_getWaterColor` in the stack, sometimes preceded by Sodium's `Encountered exception while building chunk meshes`.
- **Cause, from `better-end-21.0.11.jar`:** `org.betterx.betterend.mixin.client.BiomeColorsMixin` injects into vanilla `BiomeColors.getAverageWaterColor` and, when `sodium` is loaded, reads `MinecraftClient.getInstance().world` instead of the passed-in `BlockRenderView`. That read happens on Sodium's chunk-build worker threads. Sodium declares `breaks betterend '<=21.0.11'` for exactly this (commit `74612be7`).
- **It is not a worldgen or End-specific fault.** The unsafe read is at bytecode offset 18; the `EndBlocks.BRIMSTONE` test that makes it BetterEnd-specific is at offset 109. Every water block meshed anywhere reaches the read first, so disabling BetterEnd's worldgen reduces exposure by nothing.
- **Fix:** `"sulfur_water_color": false` in the CLIENT's `config/betterend/client.json`. The config gate is the first instruction and returns immediately, so the unsafe read is never reached. Shipped at `modpack/overrides/config/betterend/client.json`.
- **Client-side mixin — editing the server's copy does nothing.** That is the trap: the change looks applied and protects nobody.
- Cost is one cosmetic tint: water within the 5x5 ring of a brimstone block renders normal biome-blue.
- Both mods stay in the pack. `modpack/overrides/config/fabric_loader_dependencies.json` removes sodium's `breaks` so they resolve; that override alone leaves the bug armed and is not a fix.
- No upstream path: BetterEnd issues 428, 519, 554, 566, 577, 589 are open, and sodium's range covers every 1.21.1 build.

<a id="t80"></a>
### T80 — A server mod the client needs is absent from the pack, and every player is kicked at join

- **Symptom:** every player is disconnected at join with

  ```
  Received N registry entries that are unknown to this client.
  This is usually caused by a mismatched mod set between the client and server.
  ```

  followed by the offending namespaces. Redeploying and rebuilding the pack changes nothing, because the pack is built correctly from a manifest that is wrong.
- **Cause:** `config/modrinth-mods.txt` gained mods that register static blocks, items and entities, and `modpack/adventure.mrpack.json`'s `_clientMods.required` never gained them. The two lists have no native link; only a human keeps them together.
- **The check that existed did not cover it.** `build-modpack.sh`'s parity lint tests one direction — a slug removed server-side but still client-required. This is the opposite direction, and it was unchecked.
- **Diagnosis:** `python3 ./scripts/check-client-parity.py`. It fails on any server mod whose Modrinth `client_side` is `required` and which is not in `_clientMods.required`, and on the reverse. It runs in `test-scripts.sh`, so `lint.yml` and the pre-push gate both gate on it.
- **Fix:** add the slug to `_clientMods.required` at the **same versionId the server pins**, so both sides run identical jars. A server mod that genuinely ships no client registry entries goes in `_clientMods._parityExempt` with a reason, never removed from the check.
- Modrinth's `client_side` is the mod author's declaration, so it is a proxy rather than proof. It is the only static signal available, and it named all nine of the mods that caused this.

<a id="t79"></a>
### T79 — Better Caves casts every aquifer sampler to its own duck interface, and the anonymous one does not carry it

- **Symptom:** repeated through chunk generation, **per chunk** rather than once
  per dimension:

  ```
  java.lang.ClassCastException: class net.minecraft.class_6350$1 cannot be cast
  to class com.yungnickyoung.minecraft.bettercaves.duck.ILiquidRegionsProvider
  ```

  Measured without the fix below: **312 occurrences over 25 distinct chunk
  positions** in one boot. Chunks still generate and nothing on disk is corrupt
  — vanilla logs `Not saving partially generated broken chunk` — so the code
  fixes the world with no wipe.
- **Cause:** `MasterController.carve` in Better Caves 3.1.5 does an
  unconditional `checkcast` to `ILiquidRegionsProvider` on the `AquiferSampler`
  it is handed. Better Caves' own `AquiferMixin` targets `AquiferSampler.Impl`
  only, and `class_6350$1` is an anonymous implementation of the
  `AquiferSampler` **interface** — the sea-level sampler, built where
  `aquifers_enabled` is false. It never carries the duck.
- **Fix (in place):** `BetterCavesAquiferDuckMixin`, a member-less
  `@Mixin(AquiferSampler.class)` **interface** whose only job is to bring
  `CompatMixinPlugin.preApply` to that target. The plugin adds
  `ILiquidRegionsProvider` as a superinterface and generates
  `bettercaves$getLiquidRegions()` returning null, gated on mod id
  `bettercaves`. `Impl` is untouched: it is a class, so its own concrete method
  beats an interface default and the dimensions that do have liquid regions
  keep them.
- **The technique needs an interface target.** The duck method is
  `ACC_PUBLIC, ACC_ABSTRACT`, and it lands as a usable default only because
  `AquiferSampler` is itself an interface. The same move against a class target
  adds an abstract method to a concrete class.
- **Answering null loses no feature.** There is no
  `instanceof ILiquidRegionsProvider` anywhere in the Better Caves jar — two
  `checkcast` sites, zero `instanceof` — so a universally applied interface
  cannot flip a Better Caves decision, only stop a cast throwing. Better Caves
  already handles the null (`AbstractCarver.carveBlock`).
- **The `preApply` log line is the only proof it applied.** The mixin carries no
  injectors, so `defaultRequire: 1` cannot catch a silent non-application. Two
  lines, in order: `Compatibility mixin BetterCavesAquiferDuckMixin: bettercaves
  present — applying` from `onLoad`, then `… net/minecraft/class_6350 now
  provides …ILiquidRegionsProvider — sea-level samplers answer null instead of
  failing the cast` from `preApply`. Grep for the second, never the first.
- **Do not pin Better Caves back to 3.1.4.** It has no cast, and it predates the
  ScopedValue rewrite that exists to fix c2me and Distant Horizons concurrency —
  this pack runs both, so pinning back trades a deterministic per-chunk failure
  for the race it fixes.
- **There is no config route out of it, on either config surface.**
  `BCConfigFabric` declares one field, `general`, and `ConfigGeneralFabric`
  declares none — read from the bytecode, so no keyed option exists anywhere in
  the config object. And `data/config/bettercaves/fabric-1_21_1/liquidregions.json`,
  the one real per-dimension knob, cannot reach this: `NoiseChunkMixin`'s
  `WrapOperation` targets the 7-parameter `AquiferSampler.aquifer` only, so with
  `aquifers_enabled` false that mixin never runs and the sampler is the
  anonymous sea-level one whatever the file says.
- **It is NOT what kills a server during dimension activation.** That is the
  watchdog on concurrent first-time chunk generation ([K6](#k6)) — a single tick
  exceeding 180s. The two appear together in the log and the exception is the
  more eye-catching, which is exactly why this entry exists.

<a id="t84"></a>
### T84 — Better Caves carves to a configured floor nothing clamps, and the carving mask indexes negatively

- **Symptom:** repeated through chunk generation, alongside
  `Error upgrading chunk [x, z] to "minecraft:carvers"`:

  ```
  java.lang.IndexOutOfBoundsException: bitIndex < 0: -7168
    at java.util.BitSet.set
    at net.minecraft.class_6643.method_38865
    at ...bettercaves.worldgen.carver.AbstractCarver.carveBlock(AbstractCarver.java:48)
  ```

  Measured on one boot: **260 throws over 22 distinct bit indices, and 130
  failed chunks**. The chunk's whole carver step dies, not one block.
- **Cause:** `CaveCarver$Builder.fromConfig` (offsets 202-215) assigns
  `bottomY`/`topY` straight from the carver datapack with no reference to the
  level, and `better_cave.json` ships `bottom_y: -63` on all four carvers.
  `carveColumn` loops down to that value; `AbstractCarver.carveBlock`'s FIRST
  instruction is `CarvingMask.set`, and the mask was built with the CHUNK's
  `minY`. A dimension on nether or End settings has a floor of 0, so every dig
  below it indexes the BitSet negatively. The 22 indices decode to 14-48 blocks
  below the floor.
- **Not the aquifer duck.** The mask write precedes the replaceable test and
  the aquifer entirely. [T79](#t79)'s mixin only stopped the earlier
  `ClassCastException` that was aborting carving before this could be reached.
- **Fix (in place):** `BetterCavesCarveFloorMixin` cancels `carveBlock` at HEAD
  when the y is outside `[bottomY, topY)`. Nothing is lost — `ProtoChunk`
  height-limits both its read and its write, so the mask is the only unguarded
  operation on the path, which is why it is the thing that throws.
- **The proof is the count against a DENOMINATOR.** `MASTER CONTROLLER` lines
  say whether Better Caves carved at all this boot; a zero-throw log with zero
  controllers has measured nothing ([T63](#t63)).

<a id="t85"></a>
### T85 — An unwaxed copper portal frame weathers out from under itself, and the portal stops working

- **Symptom:** a portal that worked stops lighting, and cannot be re-lit. On an
  already-lit zone it CLOSES on its own. Nothing in the config changed and
  nothing in the log said why.
- **Cause:** vanilla copper oxidation. `minecraft:copper_block` ->
  `exposed_copper` -> `weathered_copper` -> `oxidized_copper` on random ticks.
  A `frameBlock` with no `frameAccepts` matches exactly one id, so the first
  ring block to turn breaks the frame. Measured on the local Crucible rig:
  `3260, 84, 2883` had weathered to `minecraft:exposed_copper`, one block out
  of a fourteen-block ring, and ignition refused
  `OPENING_NOT_ENCLOSED ... the fill ran into minecraft:exposed_copper`.
- **Which dimensions are exposed:** only ones whose frame is an unwaxed,
  non-terminal copper form. Of the 82 shipped configs, `the_crucible` was the
  only one — `the_gauntlet` uses `oxidized_copper` (terminal, cannot weather
  further) and `the_highland_crossing` uses `raw_copper_block` (a mineral
  block, not in the weathering family). Check any NEW copper frame against
  both of those exemptions before assuming it is safe.
- **Fix (in place):** `the_crucible.json`'s `frameBlock` is a LIST of all eight
  forms — four oxidation stages and four waxed — plus an explicit
  `framePlaceBlock` so mod-built arrival frames stay pristine copper.
- **There is no `frameAccepts` config key.** `DimensionConfig.Portal` declares
  `frameBlock` as a `JsonElement` and `getFrameAcceptForms()` reads
  `acceptFormsFrom(frameBlock)`, which takes a plain id, a `#tag`, a LIST of
  those, or `{"colorGroup": ...}`. `frameAccepts` is a field on
  `PortalDefinition` — the runtime and persistence view, populated FROM the
  parsed `frameBlock` — which is where the name misleads. **Gson drops unknown
  config keys silently**, so a `frameAccepts` written into a dimension file
  parses clean, changes nothing, and reads as a fix. Precedent for the list
  form: `the_lost_outpost` and `the_violet_spire`, both of which also set
  `framePlaceBlock`.
- **A fully oxidised crucible frame becomes a DIFFERENT dimension's frame.**
  The accept list stops at `weathered_copper` because `oxidized_copper` is
  `the_gauntlet`'s only frame block and two dimensions may not share one
  (`PortalFrameCollisionTest`). So the last weathering step takes the same
  fourteen blocks out of `the_crucible`'s list and into `the_gauntlet`'s — a
  portal that changes destination by standing in the rain. Waxing any stage
  freezes it; all four waxed forms are accepted. This is a design boundary, not
  an oversight, but it is a live trap for a player who builds in plain copper
  and leaves it outdoors.
- **What the fix does NOT do.** `PortalHelper.isZoneValid` validates a live
  zone against `zone.definition` — the ignition-time snapshot deserialised
  from `portal_links.json` — not against current config. Zones are deliberately
  immutable snapshots, so a zone registered BEFORE the config change keeps its
  old single-form accept list and still closes when its frame weathers. The
  config fix repairs future ignitions and makes a weathered frame re-lightable;
  it does not rescue an already-registered zone. Re-lighting is what heals one.

<a id="t86"></a>
### T86 — A subagent reporting "running" can have been dead for hours; only artefacts say otherwise

- **Symptom:** `ListAgents` shows a teammate as `running`, and it has written
  nothing for hours. Measured here: an agent reported `running · started 9h ago`
  with its last artefact 8h24m old, having produced none of the three tasks it
  was sent.
- **Why the obvious check fails:** the documented trap is that `idle`,
  `available` and `finished` signals fire identically whether an agent is
  working, between turns, exited or empty-handed. `running` is no better — it
  is not a liveness probe. Queued `idle_notification` messages can also arrive
  hours late and replay reports already handled, which reads as fresh activity.
- **What to measure instead:** file mtimes under the work tree, the build
  outputs (`build/libs/*.jar`, `build/test-results/test/*.xml`), the scratchpad
  directory, and the live state the agent claimed to change. An agent that
  changed the world leaves the world changed.
- **Before killing one:** `SendMessage` resumes an agent with its full history,
  so ask a direct question first and say plainly that the work is being taken
  back. Kill only after silence plus a zero-artefact window. Never on a hunch —
  but a measured multi-hour gap is a measurement, not a hunch.

<a id="t87"></a>
### T87 — Java integer division truncates and Python's `//` floors, so a port of one disagrees with the other on every negative coordinate

- **Symptom:** a harness or planner that reimplements a piece of the mod's
  arithmetic agrees with it perfectly in testing and picks the wrong answer in
  the world. Nothing errors. Every assertion downstream still passes, because
  they are all consistent with the wrong number.
- **Cause:** Java's `/` on `int` truncates TOWARDS ZERO; Python's `//` FLOORS.
  They agree on every positive value and disagree on every negative one that is
  not exact. `PortalBreakLink.centreColumn` is `x / count` over an interior's
  block positions, and a portal room at negative coordinates is where a Python
  copy of it starts answering one block out. `-40785 / 6` is `-6797` in Java and
  `-6798` in Python. A centre column one block out picks a different arrival,
  so symmetric breaking matches nothing and a proximity test measures the wrong
  portal — both silently.
- **Fix:** port the rule, not the operator.

  ```python
  def java_int_div(total, count):
      """Java's `/` on ints: truncate TOWARDS ZERO, not floor."""
      quotient = abs(total) // count
      return -quotient if total < 0 else quotient
  ```

- **Fixture both signs, and put the .5 on an ODD coordinate.** A rectangular
  2-wide opening averages to `x0 + 0.5`. Python's `round()` is banker's
  rounding, so at an EVEN `x0` it happens to agree with truncation and the test
  passes while the code is wrong. `x0 = 228` agrees; `x0 = 229` does not. Every
  fixture for a ported integer division needs a positive case, a negative case,
  and a half that lands on an odd coordinate.
- **This class is not specific to division.** Any Java arithmetic reimplemented
  in Python (`%` on negatives, `>>` on signed ints, float-to-int narrowing) has
  the same trap. Pin it against the Java, not against intuition —
  `scripts/e2e/portal-matrix-selftest.sh` is the worked example.

<a id="t100"></a>
### T100 — A shader pack shades a portal's whole opening from the doorway

- **Symptom:** with a pack loaded the view through a portal is dull and flat
  while the world beside the frame is brightly lit, a hard-edged light shaft
  crosses the terrain with the opening lit differently from outside it, and a
  hard vertical edge runs the full height of the frame. At noon the opening's
  Rec.709 luma is 0.36x the source sand's beside it, against 1.12x with no pack.
- **Cause:** the destination is compressed into a depth slice at the portal
  surface (`ProjectionRenderer.depthSlice`), and a pack reconstructs a
  fragment's position from its screen coordinates and its depth and nothing
  else — it never reads the matrices the draw was submitted with. The whole
  opening therefore reports one point at the doorway.
  `/state.realtime.apertureSample` prints it: 1.637 blocks, on a block with no
  sky access, for a window onto terrain tens of blocks away.
- **Fix:** `apertureFarStamp` and `apertureFarStampEarly`. The stamp closing the
  pass writes `ProjectionRenderer.FAR_STAMP_DEPTH` instead of the surface's
  depth, and `WorldRendererApertureDepthMixin` puts the surface depth back at
  the translucent terrain draw so nothing that depth-tests behaves differently.
- **Two read points, not one, which is why one stamp cannot serve both.** Iris
  copies `depthtex1` and runs its deferred programs at the `translucent`
  profiler constant, bytecode 2213 of `WorldRenderer.render`; its composite and
  final passes run at RETURN. Every depth-testing draw in the frame sits
  between them — translucent terrain 2235/2370, particles 2317/2435, clouds
  2496, weather 2533/2599 — and no render phase sits in the gap, which is why
  the restore is a mixin. Fabric's `BEFORE_DEBUG_RENDER` is 2077 and its
  `AFTER_TRANSLUCENT` is 2445, both on the wrong side.
- **Measured, and it is a TRADE rather than a cure.** The opening changes
  essentially completely — 99.99% at noon on a 25-chunk feed — and the source
  terrain beside it 0.00%. Mean luminance rises and the ratio to the source sand
  goes 0.41 -> 0.61 against 1.12 with no pack. **But the contrast falls with it.**
  Rec.709 luma standard deviation over the opening, all at noon, camera H:

  | | 6-chunk feed | 25-chunk feed |
  | --- | --- | --- |
  | no pack | 69.4 | — |
  | pack, stamp off | 26.4 | 40.4 |
  | pack, stamp on | **34.5** | **16.4** |

  Where the destination behind the opening is genuinely far the flat depth helps;
  where it is near it hurts, and which wins depends on how much near terrain the
  opening holds. In the near-field band the stamp always hurts — 22.6 -> 8.5 and
  15.9 -> 8.5 against 27.4 with no pack — and the mean goes from a warm ground
  `(140,117,65)` to a cold `(95,131,126)`, which is the fog colour rather than
  the ground.
- **Why: one plane cannot describe a whole opening.** The stamp tells every pixel
  the same distance, so it is right for exactly one of them.
- **`apertureMeshDepth` is the shape that does not have that.** The meshed
  destination is drawn once more, depth only, over the far stamp, so each pixel
  carries the distance of the geometry visible there and the stamp only fills
  where no mesh does. It restores the near field exactly and improves the
  opening past the unstamped baseline:

  | | opening std | near-field std | near-field mean |
  | --- | --- | --- | --- |
  | no pack | 69.4 | 27.4 | `(140,117,65)` |
  | stamp off | 23.1 | 13.6 | `(59.0, 75.6, 50.5)` |
  | flat stamp | 16.4 | 8.5 | `(95,131,126)` — fog |
  | **plus `apertureMeshDepth`** | **32.9** | **13.5** | **`(59.0, 75.7, 50.6)`** |

- **Its cost is the pass running the clip a second time**: the emit line's
  `renderUs` goes 1727 -> 4238 microseconds per frame at the measurement camera,
  about 2.5 ms. That is the mod's own pass, not the whole frame.
- **All three switches ship OFF.**
- **With no pack the switch is bit-identical** — 0 changed pixels, maxDelta 0,
  inside the opening and out, on the same jar with `enableShaders` flipped.
- **An entity needs a different fix from the terrain, because it reads a
  different thing.** Terrain shading comes from the deferred passes, which read
  the depth BUFFER the stamps repair. An entity is shaded in the forward pass,
  from its OWN fragment's `gl_FragCoord.z`, which no stamp reaches. Measured on
  one cow, camera H, 25-chunk feed, all three switches off then on: the opening
  moves 23.52% of its pixels against a 0.68% floor and its luma std 24.3 -> 32.3,
  while the cow moves 1.1 luma against a 0.8 floor.
- **`drawActors` is that fix.** With `apertureFarStampEarly` and
  `apertureMeshDepth` both on, the actors are drawn after the far stamp and the
  mesh's own per-pixel depth, outside the slice, so they rasterise at their true
  depth and test against the mesh's. The cow's white patch — 90th-percentile
  luma, which does not move with the box — goes 71.2 to 107.8 head-on, and
  head-on and oblique then agree at 107.8 against 106.1, a 1.7 gap on an
  1.8 same-camera floor. Off without both switches: the mesh's own depth is the
  only thing an actor at true depth can test against.

<a id="t99"></a>
### T99 — An entity layer shades a portal's backdrop with the SOURCE world's light and fog

- **Symptom:** the destination's fog colour reaches the screen at 0.59x its
  authored value with NO shader pack loaded — `(192,216,255)` measured as
  `(113,128,151)` — while a pack renders it correctly. The opposite way round
  from the defect that put the backdrop on an entity layer in the first place.
- **Cause:** `rendertype_entity_cutout_no_cull.fsh` applies three modifiers
  `position_color.fsh` does not — `minecraft_mix_light` diffuse shading, the
  lightmap texel, and `linear_fog` toward the SOURCE world's fog colour. The
  colour arriving from the destination is already finished, so each is applied
  a second time.
- **Fix:** `ProjectionRenderer.BACKDROP_NORMAL` is `normalize(0.2, 1, -0.7)`.
  `DiffuseLighting.enableForLevel` passes that vector and its exact negation,
  so `minecraft_mix_light` reduces to `min(1, 0.6 * |d0 . n| + 0.4)` and a
  normal parallel to `d0` is the one orientation for which vanilla's diffuse is
  the identity. Measured `(189,213,251)`, 0.98x, with the pack case unchanged
  at `(56,98,136)` — opening 5.78% and control 3.82% against a same-condition
  floor of 8.54% and 5.20%.
- **Residual:** `linear_fog` is in the layer's own fragment shader and no
  choice of normal removes it. It costs the remaining 1.6%.
- **A pack never sees any of it.** Iris replaces the vanilla program, so the
  normal reaches only the pack's own `gbuffers_entities` — which is why the
  fix is free with a pack loaded and worth 41% without one.
- **Two conditions nobody has been in.** `lightMapColor` is
  `texelFetch(Sampler2, UV2/16, 0)` on the SOURCE world's lightmap, which is
  ~1.0 at sky 15 and midday and is not at night or with the source underground.
  And `linear_fog` grows with `vertexDistance`, so a portal seen from a hundred
  blocks away takes the SOURCE world's fog colour across the whole opening.

<a id="t98"></a>
### T98 — Terrain render layers submitted from a mod's own buffer draw nothing under a shader pack

- **Symptom:** a portal's destination shows only its flat fog colour with a
  shader pack loaded, and its real terrain with shaders off. The draw reports
  success: 2792 vertices emitted across solid, translucent and cutout_mipped in
  the same frame the opening reads as one uniform colour.
- **Bisected, not guessed.** With the backdrop quad switched off the opening is
  pixel-identical to the opening with BOTH halves off — so the terrain
  contributes nothing, and the backdrop was not covering it. Three families of
  draw at the same seam in the same frame: entity layers through
  `EntityRenderDispatcher` render; `POSITION_COLOR` renders and is tonemapped;
  `RenderLayers.getBlockLayer(state)` renders nothing.
- **Cause (INFERRED, not read from the pack):** `gbuffers_terrain` is written
  for chunk geometry and reads vertex attributes — `mc_Entity`, `at_midBlock` —
  that Iris supplies for Sodium's chunk geometry and not for a mod submitting
  through a `VertexConsumerProvider.Immediate`.
- **Fix:** `PortalRenderLayers.forDestination` draws captured block quads on the
  ENTITY layers over `SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE` instead —
  `getEntitySolid`, `getEntityCutoutNoCull`, `getEntityTranslucentCull`. The
  `ENTITY` vertex format is position, colour, uv, overlay, light, normal, which
  is exactly what `QuadCapture` already writes.
- **Cost:** there is no mipmapped entity cutout layer, so leaves and grass seen
  through a portal lose mipmapping.
- **Diagnosis without a rebuild:** `apertureBackdrop` and `apertureTerrain` on
  the dev bridge draw the two halves separately.

<a id="t97"></a>
### T97 — A second `WorldRenderer.render` drives a shader pack's whole pipeline twice in one frame

- **Symptom:** with an Iris shader pack loaded, stepping out of a portal smears
  a second world's geometry across the ENTIRE screen — stretched vertical
  streaks and black wedges over ground, frame, hillside and sky. It rotates
  with the camera, does not translate, is identical looking 180 degrees away
  from the portal, and survives the pass being switched off. Only a shader
  reload or a client relaunch clears it. Measured on the right-hand source
  hillside: 98.14% of pixels changed with the pass running, 99.65% after it
  stopped, against a ~7% noise floor.
- **Cause:** `net.irisshaders.iris.mixin.MixinLevelRenderer` injects four
  global per-frame handlers into `WorldRenderer.render` —
  `iris$setupPipeline`, `iris$beginLevelRender`, `iris$renderTerrainShadows`,
  `iris$endLevelRender`. Calling `render` on a second `WorldRenderer` runs the
  pack's shadow pass and final composite a second time over one set of GPU
  targets. Iris has exactly two representable pass states, main and shadow, on
  public statics in `ShadowRenderer`; there is no third for a second world.
- **Fix:** the destination is not rendered as a second world at all. It is
  drawn as ordinary geometry inside the source frame's single `render` call, at
  `WorldRenderEvents.BEFORE_BLOCK_OUTLINE` — bytecode 1952, past the last
  opaque `checkEmpty` at 1942 and before the translucent clear at 2194 — and
  clipped to the portal opening. The pack shades it for the same reason it
  shades any other geometry, with no reference to Iris anywhere in the mod.
- **Trap:** `WorldRenderEvents.AFTER_ENTITIES` is NOT in that window. Its `@At`
  is `CONSTANT stringValue=blockentities ordinal=0`, bytecode 1137, before the
  block-entity loop. Read the offset out of Fabric's own mixin; the phase name
  does not give it.
- **Trap:** at `BEFORE_ENTITIES` the context's `matrixStack()` is null and the
  position matrix has to be multiplied in by hand. At 1952 it is already on the
  `RenderSystem` model-view stack, so doing it again rotates the far side twice.
- **Trap:** 1.21.1's framebuffer has no stencil attachment, so a model drawn by
  a vanilla dispatcher cannot be masked by the hardware. `ClippedConsumers`
  catches each quad and clips it against the opening instead.
- **Trap:** every plane of the aperture cone runs through the camera, so the
  cone reaches back to the eye. The meshed volume starts at the portal surface
  and does not care; an entity can stand short of it, so the actor path adds
  the surface half-space.
- **Open, and the cause is measured rather than suspected: a pack shades
  destination geometry from the SOURCE world's shadow map and sun direction.**
  One entity, one light value, one jar, one frame renders as a dark silhouette
  head-on and correctly textured at 45 degrees oblique, and the destination
  carries a hard vertical brightness step across both its sky band and its
  water. A view-angle dependence and a shadow edge; destination geometry is
  drawn at SOURCE coordinates and the actor's source-space position sits inside
  the hillside behind the frame.
- **Two candidates are eliminated by measurement — start past them.** The light
  handed to the dispatcher is correct (`apertureEntityLight` reports `sky=15
  block=0`), and the clip is a no-op on the model (`apertureQuadsIn ==
  apertureQuadsOut`, so the interpolating branch never runs). What remains is a
  design question — whether destination geometry can stop being drawn at source
  coordinates — not a tuning one.

<a id="t96"></a>
### T96 — The destination pass draws at the field-of-view option, not the one the source frame uses

- **Symptom:** a spyglass, a drawn bow or anything else that zooms the world
  moves the source and the portal's frame while the view THROUGH the frame
  holds still at its old scale. Measured at the rig, source terrain 69.5% of
  pixels changed against the destination's own corner preview 0.40%.
- **Cause:** `GameRenderer.renderWorld` draws at
  `getFov(camera, tickDelta, true)`, which folds in the `fovMultiplier` lerp
  (spyglass 0.1, bow 0.85 at full draw, movement speed up to 1.5), the death
  squeeze and the water and lava submersion effect. The pass read
  `options.getFov()` instead, so it inherited none of them. The composite
  samples at `gl_FragCoord.xy / ScreenSize` and is in the right place only
  while the two projections agree.
- **Fix:** `SpectatorProjection` asks vanilla — `GameRendererFovInvoker` on
  the private `getFov` — rather than reproducing the arithmetic.
- **Trap:** drawing and culling take different answers. Vanilla culls the
  source at `max(getFov(camera, tickDelta, false), option)`, deliberately
  wider than it draws, so the chunks a spyglass is about to be lowered from
  are already built. Passing the drawn matrix to `setupFrustum` trades the
  mismatch for chunk pop-in on unzoom.

<a id="t95"></a>
### T95 — The arrival chunk ticket and the client feed want different amounts of the destination

- **Symptom:** a portal drawn locally fills on a first approach and shows
  exactly 7 chunks with `renderedSections: 0` on every approach where the
  viewer has not just been in the destination. The feed says so in one line:

  ```
  companion-send:destination-chunks player=X dimension=D sent=0 wanted=4 held=7 radius=16 idlePumps=1
  ```

  `wanted` above 0 with `sent=0` is the whole diagnosis — the feed asked for
  four columns and every one was non-resident.
- **Cause:** two consumers share one ticket. The block slab wants
  `ProjectionVolume.targetChunks(...)`, a preview box of about six columns;
  `DestinationFeed` may send only resident chunks and needs the filled 5x5
  `CORE_RADIUS` core before a renderer will build the middle of a 3x3. Sized
  for the slab alone, the feed delivers the ticketed set plus the arrival
  chunk and stops.
- **Fix:** `holdSet` composes what a zone tickets — the preview box always,
  plus the feed's core square for a viewer drawing the far side itself, and of
  that square only what is ALREADY resident. The local-drawer verdict comes
  from the players in ticket range, not from `ACTIVE`: the ticket is taken
  before the projection pass rebuilds a viewer's state, so `ACTIVE` names
  nobody on the first pass after a world change and one refresh is long enough
  for a destination to drain.
- **Trap:** a ticket is not a hold. `ChunkTicketManager.addTicket` builds it at
  `ChunkLevels.getLevelFromType(FULL)` minus the radius and the manager
  generates whatever reaches that level, so an unfiltered square would force
  first-time generation at a portal into fresh terrain — [K6](#k6). Filtering
  to resident columns is what makes it hold-only; `ImmersivePreloader` still
  owns generation.

<a id="t94"></a>
### T94 — A crossing's own frame and chunks arrive before the client tick that clears the old world's

- **Symptom:** a portal renders the destination on the first approach and draws
  sky on every approach after a traversal. The dev bridge reports `frames: 0`,
  `destinationWorlds: 0`, `renderedSections: 0` with `clientSideRefused: false`
  and an empty `spectatorRefusal`, and the client log shows the frame arriving
  and the world being dropped in the same second:

  ```
  companion-client:portal-frame  dimension=adventure:the_amplified_reaches aperture=3464, 80, 2592
  companion-client:local-projection dimension=adventure:the_amplified_reaches chunks=4
  companion-client:destination-world dimension=adventure:the_amplified_reaches dropped
  ```

- **Cause:** the server answers `AFTER_PLAYER_CHANGE_WORLD` by sending the new
  world's frame and first chunks immediately, and they are processed in the same
  packet batch as the dimension change. The client's reset compared
  `MinecraftClient.world` against a bound field on `END_CLIENT_TICK`, one tick
  later, and cleared what had already landed. Nothing recovers: `sendCompanion`
  re-sends a frame only when it differs from the one it last sent, and
  `DestinationFeed` skips every chunk key it believes is held.
- **Fix:** `WorldBinding` gives the reset one owner per world. The join binds
  and clears at the head of `MinecraftClient.joinWorld`, before any payload for
  that world is applied; the tick still calls the same path and finds the world
  already bound, so it does not clear a second time.
- **Trap:** a reset driven by comparing the live world against a remembered one
  is always a tick late. Clear where the world is replaced, not where the change
  is noticed.

<a id="t93"></a>
### T93 — The server keeps a record of what a client holds across a world change the client does not survive

- **Symptom:** the destination feed goes silent for the rest of a session after
  one traversal. The server sends a `companion-send:portal-frame` on every later
  approach and no `companion-send:destination-chunks` at all, and the client
  reports `destinationChunks: 0` with no refusal recorded.
- **Cause:** `DestinationFeed.SENT` and `DestinationEntityFeed.SENT` are per
  player, per destination, and were dropped only on JOIN, DISCONNECT and
  shutdown. `ImmersiveProjector.forgetInWorld` answers a world change with
  `state.forget()`, which touches neither. The client clears every destination
  world it holds on the same edge, so the server skipped chunk keys the client
  no longer had. `pump` logs only when it writes something, so the silence
  leaves no line.
- **Fix:** `CompanionNetwork.forgetDestinations` drops both feeds for that
  player and nothing else, called from the `AFTER_PLAYER_CHANGE_WORLD` handler.
  The handshake and the view declaration stay — the same client in a new
  dimension must not be put back on the server-drawn slab.
- **Trap:** the entity feed hides this. Its payload is a full snapshot and
  `changed()` differs as soon as anything moves, so it self-heals and only a
  perfectly still far side shows the fault.

<a id="t92"></a>
### T92 — A second world render must not point `MinecraftClient.world` at a world no client lifecycle stood up

- **Symptom:** the client log fills at thousands of lines a minute with
  `[Sound engine/WARN] Can not return client level proxy, client level clone has
  not been cached. This might only occur once on load.`, each followed by
  `[Sound engine/ERROR] Error executing task on Sound executor` and a
  `NullPointerException` in `SoundPhysics.evaluateEnvironment`.
- **Cause:** Sound Physics Remastered caches a cloned level **per `ClientWorld`
  instance** and fills that cache only from `MinecraftClient.setWorld` and
  `ClientWorld.tick`; `getLevelProxy()` then reads `MinecraftClient.world` live,
  on the sound engine's own thread. A destination `ClientWorld` is constructed
  directly and never ticked, so its cache is empty — and the spectator pass was
  writing `client.world` for the length of the destination render, which hands
  that world to every off-thread reader.
- **Fix:** the pass never writes `MinecraftClient.world`.
  `WorldRendererDestinationMixin` redirects the field reads inside `render`,
  `renderSky` and `renderWeather` to the renderer's own world, and
  `LightmapTextureManagerDestinationMixin` reads `DestinationLightmap`, held
  only across the pass's own `update` call and on the render thread.
- **Trap:** the mod's warning reads as a cache being invalidated. Nothing is
  invalidated — a different world object is read and it has never had a clone.
  Every mod holding per-`ClientWorld` state keyed off that field has this shape,
  so the rule is the field, not the mod.

<a id="t91"></a>
### T91 — A fed entity needs its tracked data before its first tick, and must never read the dirty list

- **Symptom:** entities reach a destination `ClientWorld` and vanish within about
  a second. The client log names them: `entity=201 left the world (item at
  1739.5,65.0,1297.5)`, all of one type.
- **Cause:** a spawn entry carries type and position, not tracked data. An
  `ItemEntity` therefore arrives holding an empty stack, and `ItemEntity.tick()`
  opens with `ItemStack.isEmpty()` then `invokevirtual discard:()V`. It deletes
  itself on its first tick. Any entity whose tick reads tracked data has the
  same shape.
- **Fix:** send each entity's tracked data as a vanilla
  `EntityTrackerUpdateS2CPacket` alongside the spawn entry and apply it BEFORE
  the entity's first tick. **The ordering is the fix, not the data.** It also
  buys fidelity for free — a baby is a baby, a sheep is the right colour.
- **Trap:** read `getChangedEntries()`, **never `getDirtyEntries()`**. The dirty
  list is consumed by vanilla's own tracker for players actually in that
  dimension; reading it here steals their update and desynchronises them.

<a id="t90"></a>
### T90 — Backgrounding `./dev restart mc` inside a tool kills it mid-recreate and leaves an orphan

- **Symptom:** `mc` disappears. `docker inspect mc` answers `healthy` on the
  first check and `no such object` on the next, and a `Created`-only container
  named `<hash>_mc` holds nothing. Downtime measured at ~8 minutes.
- **Cause:** `nohup ./dev restart mc &` inside the Bash tool. Docker compose had
  already renamed the running container out of the way when the process was
  killed, so the recreate never completed. The first `inspect` resolved the
  old container, which is why the healthcheck lied.
- **Fix:** remove the orphan, then `./dev up` — which also installs the local
  mod jars. **Never background a compose recreate yourself.** Use the tool's own
  backgrounding, or run it in the foreground and wait.
- **Trap:** one healthy `docker inspect` immediately after a restart proves
  nothing. Poll until `docker ps` shows the container by its real name.

<a id="t89"></a>
### T89 — A break cannot remove an arrival zone that has not been promoted yet, and the re-light builds a second

- **Symptom:** breaking a portal and re-lighting it leaves two `arrival-zone-v1`
  records for one destination, with a derelict arrival frame standing and the
  live one built directly on top of it. Measured at the nexus: interiors
  `750-751, 57-59, 750` and `750-751, 61-63, 750`, the two frames sharing y60.
- **Cause:** persisted arrival zones land in `PENDING_ARRIVAL_ZONES` at boot and
  are promoted to `ARRIVAL_ZONES` only when their world first **ticks**.
  `arrivalZoneAt` read the live map only, so breaking a source portal whose
  destination had not been loaded since the restart found nothing and skipped
  the removal. The same blind spot made `ensureArrivalZone`'s dedup create a
  second zone rather than reuse the pending one — **two independent routes to
  the same symptom**, so fixing only the break leaves registration live.
- **The server says both halves in one line:** `Source portal broken in
  <world> — closed its arrival in <dest> (6 cells, 0 cleared now, 6 deferred)`.
  `0 cleared now` is the cold destination; the cold destination is the
  never-ticked world that makes the lookup return null.
- **Fix (in place):** `arrivalZoneAt` searches both maps, with a
  `removeArrivalZone` helper — not optional, because a world holding only
  pending zones has no live list and removing from it throws. `getArrivalZones`
  is deliberately NOT widened: its callers are the tick loop and two diagnostic
  dumps, and widening it would silently change what those dumps mean.
- **Why the new arrival rises is NOT established.** The old frame ring is
  standing, but all six of its interior cells are `minecraft:water`, which is in
  `#minecraft:replaceable`, so `PortalSite.isClear` would call that space clear.
  The obvious explanation — "the old frame blocks the carve" — is unsupported by
  what is actually there. A flooded pocket is a known failure shape here and a
  standing solid ring is plausible, but nobody has distinguished them. **Start
  from the water, not the frame.**
- **Probe technique worth keeping:** identifying an unknown block by a candidate
  list of ids answered `UNIDENTIFIED` for five of six cells — a negative from a
  list is only as good as the list, the same fault as a predicate that matches
  nothing reading as a pass. Asking `#minecraft:replaceable` narrowed it to a
  family in one call. **Ask tags before ids when you do not know what you are
  looking at.**

<a id="t88"></a>
### T88 — A result file outlives the run that made it, so a build that never ran reads as a pass

- **Symptom:** every mutation in a sweep "reddens nothing", including ones that
  delete a line two tests assert on. Or a suite reports green minutes after a
  change that cannot possibly pass. Or a liveness probe reports no activity
  across a tree that is being written to.
- **Cause, one shape three ways.** `compileJava` fails, `test` never runs, and
  the previous run's `build/test-results/test/TEST-*.xml` stays on disk — a
  harness that parses the XML reads last time's answer as this time's. Measured
  in a shared tree with five agents: one agent mid-edit broke the compile and
  another's nine-mutation sweep silently reported nine survivals.
- **The same shape without gradle.** `find -newermt '-5 minutes'` is not a valid
  timestamp for this `find` (BSD/bfs want ISO 8601). It errors to **stderr** and
  prints nothing to stdout, so a probe piped through `head` shows an empty list
  — indistinguishable from "nothing has changed". That reading nearly got five
  working agents terminated.
- **Fix:** read the tool's OWN verdict, never a file it may not have rewritten.
  Gradle's `BUILD SUCCESSFUL` / `N tests completed` line is the authority; a
  background task's exit code is not (a `nohup … &` wrapper exits immediately).
  For a harness: record the result file's mtime before the run and refuse to
  answer unless it moves, reporting `DID NOT RUN` with the compiler's own error
  lines. For a probe: give it a positive control — assert something you know
  must read true before believing a negative — and do not swallow stderr.
- **Concurrent gradle on ONE project directory corrupts both runs.** Measured:
  two builds started together over a tree several agents were also building
  gave `NoClassDefFoundError` / `ClassNotFoundException` on 288 of 324 tests,
  in a source file untouched for a day. A clean re-run with nothing else
  building was `BUILD SUCCESSFUL` with zero failures. **A Gradle project is
  single-writer**, the same way the local stack is: serialise builds, and treat
  a suite that fails wholesale in unrelated classes as a race until a lone
  re-run says otherwise.
- **A green build with NO result file is the worst shape of all.** Measured:
  two `BUILD SUCCESSFUL` runs produced no `build/test-results/test/` at all,
  because another agent's clean removed it between the write and the read. A
  harness that counts from that directory sees zero tests, zero failures, and a
  successful build — nothing looks wrong. Defence: run the build and snapshot
  the XML to a timestamped directory **in one invocation**, then count from the
  snapshot, so no other process can clean the source between your write and your
  read. The four faces are: the file did not change (stale), the classpath moved
  under it (poisoned), the source tree was half-written (a real compile error in
  someone else's edit), and the file is not there at all (absent, read as
  success).
- **A sweep whose results each name a DIFFERENT test is not affected.** A stale
  file repeats one answer verbatim; it cannot invent a distinct, correct
  attribution per mutation. That is how to tell a poisoned sweep from a real one
  after the fact.

<a id="t78"></a>
### T78 — A config change does not reach the local server, and the boot is green on the old file

**The config is not the server.** A file synced is not a file re-read, a jar on
disk is not the jar being served, and a process started before a change does
not have it. Measured together in one session: a dimension config written at
17:13 against an mc started at 17:09 still served the old frame; a built jar
matching its served sha still needed `StartedAt` to post-date the jar's mtime
to mean anything; and `./dev restart mc` installs nothing at all. Check the
process, not the file.

- **Symptom:** an `overlay/config/` or platform `config/` change is made, the
  stack is brought up, and the server behaves as though the change never
  happened. No error. `docker exec mc cat /data/config/<path>` shows the OLD
  content while the file on disk shows the new.
- **Cause:** `data/config/` is a bind mount seeded **skip-if-exists** by
  `dev-up.sh`. A file that already exists is never overwritten. Neither
  `./dev up` nor `./dev update` copies over it — `./dev update` refreshes the
  bundle and the images, not this directory.
- **Fix:**

  ```bash
  ./dev refresh-config   # backs up to data/config.bak.<stamp> first
  ./dev up               # restart so the mod re-reads it
  ```

- **It bites hardest right after a release**, because the new bundle carries the
  new default config and the server keeps running the old one. Observed: a
  freshly released dimension config with a new igniter and a biome patch, on a
  server that had correctly installed the released JAR — the jar was new, the
  config was not, and every assertion about the config was measuring history.
- **Verify the SERVED file, never the source.** `docker exec mc cat
  /data/config/<path>`. Same rule as the jar ([the served artefact, not the one
  on disk](#t70) territory): a green boot proves the container started, not that
  it started on what you changed.

<a id="t77"></a>
### T77 — A linked local stack silently runs the mod set of the last released seed image, not the repo's

- **Symptom:** a mod pinned in `config/modrinth-mods.txt` is absent from the
  running local server. `/customdim catalogue` reports fewer biomes than
  production will have, and a dimension naming that mod's biome is skipped. No
  error, no failed boot, healthy container.
- **Cause:** `docker/defaults-seed/Dockerfile` COPYs `config/modrinth-mods.txt`
  into the image, so the mod list is baked in at BUILD time.
  `sync-mods.sh` fetches what the SEED resolved into the stack-mods volume, not
  what the pin file lists. A `defaults-seed:latest` pulled before the pin was
  added therefore never resolves it, and nothing downstream can tell.
  `./dev link` does not cover this — it reaches scripts, compose and in-house
  mod jars, never the seed image.
- **Why the usual checks miss it:** `test-scripts.sh` verifies the resolve cache
  covers every pin, which is a template-side check and passes. `sync-mods.sh`
  exits non-zero only when it cannot fetch what the seed EXPECTS, and a stale
  seed expects nothing. The pin count can even match the jar count by
  coincidence.
- **Fix:** `docker pull` the `defaults-seed` tag the stack uses, then `./dev up`.
  Verify by diffing resolved filenames against `data/mods`, never by absence of
  errors:

  ```bash
  python3 - <<'EOF'
  import json, os
  cache = json.load(open("config/modrinth-resolve-cache.json"))
  installed = set(os.listdir("<consumer>/data/mods"))
  pins = [l.strip().lstrip("?").replace("datapack:", "")
          for l in open("config/modrinth-mods.txt")
          if l.strip() and not l.startswith("#")]
  print([(p, cache[p]["filename"]) for p in pins
         if p in cache and cache[p]["filename"] not in installed])
  EOF
  ```

- **Run that check before any seed roll.** A roll on a short mod set banks
  winners scored against a registry production will not have, and the mod set is
  part of the bank key — the next boot re-primes all 82 dimensions from scratch
  (`Priming 82 dimension(s); 0 already have a committed pair`).

<a id="t76"></a>
### T76 — An End-settings dimension samples depth 40–80, and the highest-declared-depth biome takes the map

- **Symptom:** a dimension built on End settings places one biome almost
  everywhere. `the_blighted_maw` measured `incendium:infernal_dunes` at 85.6% of
  1681 cells over 14 distinct biomes, and that biome holds the highest depth
  bound of the dimension's 40 cells.
- **Cause:** `end`, `nether_islands` and `sky_islands` all build on
  `endGen.getSettings()`. Vanilla's End never used multi-noise placement, so
  `minecraft:end`'s router was never required to emit a normalised depth, and
  the dimensions borrowing it sample 40–80 where every declared cell is bound to
  ±2. Every cell is then on the same side of every sample, so the axis ranks
  purely by which cell declared the highest bound. Against a sample of 70 two
  cells 0.062 apart differ by `0.062 x 2 x 70 = 8.65` in squared distance, which
  the five axes that do vary cannot overturn.
- **Measured at every height, not just the diagnostic's.** `customdim
  column-ladder` reads the router's own `depth()` per y: 67.04..73.64 across the
  column, slope -0.0018/block and non-monotone. It is out of schema where the
  lookup reads it (quart y=16), so it is not a sampling-height artefact.
- **Fix:** `ProjectedSource.depthCarriesNoInformation` opens depth on every cell
  where the dimension's measured window in `climate-axes.json` does not
  intersect ±2. Opening equalises the term rather than removing it — every cell
  then sits at the same distance — so depth drops out of the ranking and the
  five informative axes decide. Ten dimensions qualify; the boot log names each.
- **A dimension whose depth bounds barely vary will not move, and that is
  correct.** The change is worth the spread of the declared bounds:
  `the_blighted_maw` (spread 0.750) went to 18 distinct topped at 26.9%, while
  `the_pale_reach` (9 of 9 cells already open) and `the_shattered_skies` (spread
  0.005) are unchanged to the digit.
- **`sample-climate-grid.sh` reports depth at y=0 while `customdim facts`
  resolves the biome at block y=64.** The header's old advice to subtract 0.5 for
  the difference assumes a -1/128 gradient that these routers do not have. Do
  not correct one to the other; sample the height you mean. The classification
  is robust to the choice regardless: the window misses ±2 at every height in
  the column, so no height moves a dimension across the threshold.
- **This decision reads `climate-axes.json`, so [T72] now governs it too.** Which
  dimensions get depth opened is part of the frozen transform, not a property of
  the jar. Regenerating that file between a roll and the release that ships it
  can flip a dimension across the threshold and build production through a
  different transform than the one the winners were scored under.

<a id="t75"></a>
### T75 — Replacing a mod's biome source crash-loops the boot on a mixin that already agreed to stand down

- **Symptom:** the server crash-loops immediately after the reserved worlds are
  built. `docker inspect mc` shows a climbing `RestartCount` and the log carries
  `java.lang.IllegalStateException: Biome source config is not set` at
  `org.betterx.betterend.world.generator.TerrainGenerator.initNoise`, reached
  from `onServerLevelInit` via BetterEnd's `ServerWorld.<init>` mixin. The
  overworld, `paradise_lost` and the nether all save cleanly first — only the
  End's construction throws.
- **Cause:** `TerrainGenerator.onServerLevelInit` decides correctly. A source
  that is not a `WoverEndBiomeSource` gets `be_setTarget(false)`, and BetterEnd's
  density hook (`NoiseChunkMixin.be_fillSlice`) then returns early. It goes on
  to call `initNoise` **unconditionally**, and `initNoise` throws when its static
  `config` is null — which it is, because that field is only ever set from a
  Wover source. The decision to stand down is what kills the boot.
- **Fix:** `BetterEndTerrainInitMixin` (`@Pseudo`, gated on `betterend` by
  `CompatMixinPlugin`) cancels `initNoise` on exactly the condition BetterEnd
  itself tests, so behaviour is unchanged wherever BetterEnd IS the target.
- **Cancelling an init is only safe if nothing reads what it would have built.**
  Check every entry point before doing it: here `be_fillSlice` gates on the same
  `be_isTarget` flag — which lives on the `ChunkGeneratorSettings`, not on the
  biome source — and `makeObsidianPlatform` touches none of the init-only
  statics. Miss one and the crash moves from boot to chunk generation, where it
  is far harder to attribute.
- **The general shape:** a mod that keys its own state off a source it expects to
  own will throw when that source is replaced, even where it has explicitly
  handled the replacement. Read the target's bytecode (`javap -p -c`) rather than
  its behaviour — the guard and the throw were nine bytecode offsets apart.

<a id="t74"></a>
### T74 — A long roll flushes the boot log out of the container's ring buffer, and every instrument that reads it goes silent

- **Symptom:** `docker logs mc | grep "biome source built"` returns **0 lines** on
  a container that has been up for hours and never restarted. Any diagnosis that
  reads a once-per-boot line — the biome-source counters, `appliedFactor`, the
  drift WARNs, `Registered dimension` — has nothing to work with, and reports
  zero findings rather than an error.
- **Cause:** Docker's json-file driver is a **ring buffer** with a size cap, and
  a seed roll writes continuously. Measured: after a roll reached 91,640 screens
  the boot lines were gone, and `--tail 30000` did not reach back far enough to
  find them. The container is healthy and `RestartCount` is still 0, so nothing
  looks wrong.
- **Fix:** snapshot the boot to a file **at boot**, and read the file afterwards
  rather than assuming the log persists:

  ```bash
  docker logs mc --tail 4000 > .handoff/boot-$(date -u +%Y%m%dT%H%M%SZ).log 2>&1
  ```

- **The tell is a zero with no error.** A grep over a rolled-out log is
  indistinguishable from a grep that found nothing wrong ([T63](#t63)). Print the
  line count of what you searched, not just the count of what matched.
- **Where a boot-only fact is still needed after a roll**, derive it from
  committed data instead and say so: the biome-source tiers can be modelled from
  `config/custom-dimensions/extractors/biome-table-{overworld,nether,end}.json`,
  which separates native from not-native but **cannot** separate `natural` from
  `foreign` — `declaredCellsForFamily` reads TerraBlender at runtime and no
  committed artefact carries it. That model is an upper bound, not the counter.

<a id="t73"></a>
### T73 — A seed's percentage is not comparable between dimensions, because the ceiling differs by 7x

- **Symptom:** a pack-wide triage rule like "re-roll anything under 85" selects
  the same dimensions every time, or a change to one criterion moves some
  dimensions by 13 points and others by nothing.
- **Cause:** `Scorer`'s headline is achieved/ceiling, and the ceiling is the
  count of criteria that APPLY to that dimension — a criterion that does not
  apply is excluded from both the score and the ceiling, deliberately. Measured
  over 3132 banked cards across 78 dimensions, distinct ceilings run
  **3, 5, 7, 8, 10, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21**. One criterion
  unit is therefore worth **33.33 points at ceiling 3 and 4.76 at ceiling 21**.
  Ten dimensions sit at ceiling <= 8; twelve at >= 19.
- **Consequence:** the same mark change costs 7x more percentage in a
  low-ceiling dimension, and low ceiling means FEWEST applicable criteria — the
  "few things to get right" dimensions are punished hardest by any criterion
  change.
- **Fix:** rank WITHIN a dimension's own roll, which is what the roller does.
  A cross-dimension percentage threshold needs to be per-dimension or it is
  measuring dimension shape. Do not carry a fixed threshold across a change to
  any criterion.
- **The arity of a `spawnFilter` is not the cause and was the obvious suspect.**
  Medians by filter arity are flat — arity 1: 4.21, 2: 3.59, 3: 4.58, 4: 3.91,
  10: 2.75. Check the ceiling, not the config.
- **The roller already knows.** `RollPipeline.SCORE_THRESHOLD` is 80.0 and its
  own javadoc says it is display only and gates nothing, "because the score is
  not comparable BETWEEN dimensions". `bestToPromote` returns `ranked.get(0)` —
  pure rank within one dimension's own leaderboard, never a threshold. So
  auto-promotion is immune; what is exposed is any number a HUMAN reads off a
  percentage and compares across dimensions.

## macOS local dev

<a id="p1"></a>
### P1 — `od -An -td8` produces single-byte values on BSD

The `8` is read as field width, not byte size. The unsigned variant `-tu8` works. For signed 64-bit random integers: `python3 -c "import os,struct; print(struct.unpack('<q', os.urandom(8))[0])"`.

<a id="p2"></a>
### P2 — `LEVEL_TYPE=flat` on the overworld breaks structure placement in ALL custom dimensions

The mod's `createDimensionOptions` uses `overworldOpts` as a template. With a flat overworld, `overworldOpts.chunkGenerator()` is a `FlatChunkGenerator` — the `multi_biome` case checks `instanceof NoiseChunkGenerator`, fails, and falls through to the default branch, which copies the flat generator. A/B tested: flat overworld → every locate returns "Could not find"; normal overworld → real distances.

<a id="p3"></a>
### P3 — `grep -P` does not exist on macOS

BSD grep has no PCRE. Use `grep -oE` (extended regex) or `sed`. BSD `grep -E` also doesn't support `\s` — use `[[:space:]]`.

<a id="p4"></a>
### P4 — `mise exec` is required for mod builds

`mods/mise.toml` pins `java = "temurin-21"`, but a global Java (e.g. 25) takes precedence. Gradle fails with a misleading task-creation error, not a clear wrong-Java message. Use `mise exec -- ./gradlew build`.

<a id="p5"></a>
### P5 — Distant Horizons' per-level SQLite databases live on the `dh-db` volume locally

DH opens one WAL-mode SQLite per level at `<level>/data/DistantHorizons.sqlite`. On the virtiofs bind mount its `-shm` shared mmap goes incoherent and the JVM takes a `SIGBUS` on the **Server thread** as a level is created — `hs_err_pid*.log` names a `libc`/`libsqlitejdbc` frame and a stack ending in `FullDataSourceV2Repo.<init>`, and `docker logs mc` shows `Minecraft server failed … exitCode: -1` with no crash report. Ledger fails the same way on its own thread.

`dev-up.sh` symlinks every level's database into the `dh-db` named volume, ext4 inside the VM, mounted at `/dh-db` outside `/data`. Names are flattened into the volume root (`overworld.sqlite`, `<ns>__<slug>.sqlite`), so `-wal`/`-shm` follow the resolved path off the share. Only the database moves; `raids.dat`, maps and the rest of each level's `data/` stay on the bind mount. Three rules carry over from Ledger, and breaking any one is silent: the mount must be **outside** `/data` (Docker Desktop reports a nested volume in `docker inspect` and never mounts it — verify with `/proc/mounts`), the volume must be chowned to **1000**, and `/dh-db` must be listed in `data/allowed_symlinks.txt` or vanilla refuses the world with `Found forbidden symlinks`. DH adds a fourth: it probes the file before opening it, so a dangling symlink reads as `Unable to read database file … check the permissions` and every level load fails.

Links are laid down for the three vanilla level paths, every level directory already on disk, and every dimension the config declares — so one created mid-session by `/customdim load` finds its link in place. A level created at runtime by something other than a `custom-dimensions` config file gets its database on the share until the next `./dev up`. `./dev reset-world` and `./dev clean world` remove the `ledger-db` and `dh-db` volumes alongside `data/world`, so a re-rolled world never inherits the previous one's LOD cache or ledger. Both resolve the volume names from `BRAND_SLUG`; neither guesses a project name, because a wrong one removes nothing and still reports success.

Local only — production is ext4 and needs none of this.

<a id="p6"></a>
### P6 — The local Minecraft client takes a `SIGBUS` inside the JIT compiler and takes the run with it

- **Symptom:** the Prism-launched client dies mid-session with no in-game error,
  no crash report and nothing in the game log. A driven run loses the dev bridge
  — `curl: (7)` on its port — and no `prismlauncher` process is left. The
  evidence is `hs_err_pid*.log` under
  `~/Library/Application Support/PrismLauncher/instances/<instance>/minecraft/`:

  ```
  SIGBUS (0xa)
  Current thread: "C2 CompilerThread<N>"
  Chunk::chop() -> Arena::destruct_contents() -> Compile::~Compile()
  ```

- **Cause: a JVM defect, not this pack and not the mods.** The faulting frames
  are HotSpot's own compiler-arena teardown, and the method being compiled
  differs every time (`net.minecraft.class_1097::emitBlockQuads`,
  `net.minecraft.class_765::method_3313`), so no single method is implicated.
  Measured on Temurin `21.0.11+10` / macOS `26.5.2 (25F84)` / arm64 /
  `-Xmx12114m`: five crashes in one evening, four in that teardown on a C2
  compiler thread and one in
  `ConcurrentHashTable<SymbolTableConfig>::unzip_bucket` on the Service Thread.
  **The garbage collector is ruled out** — four under `-XX:+UseZGC`, one under
  `-XX:+UseG1GC` — and so is resource exhaustion: 1.1 TiB disk, 86% of system
  memory and 1.2 GB swap free at the time.
- **Distinct from [P5](#p5)**, which is a `SIGBUS` in the SERVER JVM's Server
  thread on `libsqlitejdbc` and has a fix. This is the client JVM and has none.
- **Fix: none measured.** What it changes is how a failed run is read. **A
  client death invalidates every assertion downstream of it, and those failures
  are not findings about the code** — an e2e script driving the player through
  RCON after the client is gone fails on the player's absence, one assertion at
  a time. Check the client is alive before attributing a failure, read the
  assertions up to the loss as valid, and re-run the rest.
- **Prism rewrites `instance.cfg` from memory while it runs**, so a JVM-args
  edit sticks only once the launcher is quit. It also refuses a Java 25 override
  for a 1.21.1 instance.

---

## Dimension lifecycle

Operating on dimensions that already exist on a live world. For authoring the JSON see `.claude/skills/custom-dimension-authoring/SKILL.md`.

<a id="d1"></a>
### D1 — Per-dimension seeds only apply at world creation time

Changing a seed in config has no effect on an existing dimension — the seed baked into level data at creation persists. Local dev: wipe `data/world` to test seed changes.

<a id="d2"></a>
### D2 — ALL worldgen config is creation-time-only, and survives everything short of a full world wipe

`type`, `noiseSettings`, `biomes`, `seed`. The generator is serialised into `level.dat`'s `WorldGenSettings` at creation; `registerDimensions` skips keys already in the registry and vanilla re-persists the stored generator on every save. Deleting the region directory only regenerates old-style chunks, and `customdim destroy` unloads the world but does NOT scrub the level.dat entry. Applying a worldgen change requires wiping `data/world`. The mod fingerprints creation-time worldgen config (`config/custom-dimensions-fingerprints.json`) and WARNs at boot when a dimension has drifted from what its world was created with; it never deletes or regenerates on its own — the WARN is information, not a failure.

<a id="d3"></a>
### D3 — Fully removing a dimension requires a level.dat scrub

World-dir deletion alone is a boot wedge: a lingering `Data.WorldGenSettings.dimensions` entry re-creates the dim every boot, and with the world dir gone it REGENERATES spawn chunks each time, which can hit [K1](#k1) on the boot itself. **Order matters — every file must be gone BEFORE `docker start`:** stop mc; back up `data/world/level.dat`; `pip install nbtlib` in a scratch venv; delete the dimension keys from `Data.WorldGenSettings.dimensions`; delete the world dirs, config files, `custom-dimensions-fingerprints.json` entries and `portal_links.json` records referencing the dims; start mc.

<a id="d4"></a>
### D4 — `dev-up.sh` skip-if-exists blocks config upgrades

Config files are seeded with `if [[ ! -f "$dest" ]]`, so a consumer upgrading across a schema change keeps the old file without the new fields. Delete the target under `data/config/` before `./dev up`, or copy the new config over by hand.

<a id="d5"></a>
### D5 — Template and consumer configs must stay in sync

The platform copy under `config/` is the source of truth; the consumer copy under `data/config/` must be an exact copy. After any config edit, **always copy, never diff-and-decide**.

<a id="d6"></a>
### D6 — c2me DFC: self-patching; verify via log grep, not config inspection

`useDensityFunctionCompiler` must stay `false` or every custom dimension silently clones the main world. Patching is automatic: the customdimensions jar's preLaunch entrypoint (`C2meConfigPatch`) forces the key into `config/c2me.toml` on every boot, and c2me reads its config at mixin-bootstrap time — before any entrypoint — so each boot's write is consumed by the NEXT boot's read. The one gap is the very first boot in a fresh environment (new jar, no key on disk); `deploy.sh` (step 8c) and `dev-up.sh` pre-patch as a second layer, covering that boot on every scripted path. Only a hand-deleted `c2me.toml` followed by a bare restart boots unpatched once, and self-heals on the next boot.

**Verify by reading the file:** `docker exec mc grep useDensityFunctionCompiler /data/config/c2me.toml` must answer `useDensityFunctionCompiler = false`. c2me rewrites `c2me.toml` on every boot with its own comment block and keeps the value. A c2me pinned below `0.4.0-alpha.0.27` treats the key as unknown and strips it instead, so on those the file says nothing and the proof is the log line `Removing config entry .vanillaWorldGenOptimizations.useDensityFunctionCompiler because it is not used`. The preLaunch entrypoint's own line — `c2me density-function compiler forced off (config/c2me.toml)` — says only that the patch was written, not that c2me honoured it.

<a id="d8"></a>
### D8 — A new dimension appears on the map only once it has generated chunks

`unmined-render` discovers dimensions by scanning `data/world/dimensions/<ns>/<slug>/region/*.mca` — there is no map config to write and no reload to call. A dimension that exists in config but has never been visited or pre-generated has no region files and renders nothing. Visit it, or let Chunky pre-gen reach it, then force a pass with `./ops map render`.

<a id="d9"></a>
### D9 — An inlined generator setting grows `level.dat` on every boot until the world will not load

- **Symptom:** a world that booted fine for weeks stops loading with `net.minecraft.class_8801: Tried to read NBT tag that was too big; tried to allocate: 104857586 + 44 bytes where max allowed: 104857600`, and nothing in the config changed. The file is far smaller than 100 MB — 16.7 MB raw is enough, because `NbtSizeTracker` charges per TAG and a deeply nested surface rule is millions of tiny ones.
- **Cause, two halves:** `applySettingsOverrides` and `applySurfaceComposition` built a new `ChunkGeneratorSettings` and handed it over as `RegistryEntry.of(value)` — a DIRECT entry, which vanilla's `RegistryElementCodec` writes into `level.dat` in full (noise router and surface rule included) instead of writing an id. **TerraBlender then merges its surface rules into the generator settings on every boot** — idempotent while the settings are a registry REFERENCE rebuilt from the datapack each boot, but persisted inline the merged result becomes the next boot's input, so each boot wraps the previous merge in another `terrablender:merged` layer (+56,705 bytes per boot, linear). The write path has no size budget and the read path has a 100 MB one, so a server saves a `level.dat` it can never open again.
- **Fix (in place):** both call sites register their built settings under `{namespace}:{slug}_settings_overrides` / `_surface_composed` and hand back that reference — an inlined Compound of 164–171 KB becomes a reference String of 43–53 bytes. It does NOT rescue an already-infected world: a dimension whose poisoned entry decodes successfully is skipped by `registerDimensions`, never rebuilt, and keeps compounding, so those need the [D3](#d3) scrub or a world wipe. To find them, read `Data.WorldGenSettings.dimensions.<id>.generator.settings` — a `TAG_String` is healthy (a registry id), a `TAG_Compound` is inlined. Decode failures MASK the leak: an entry that fails to decode is rebuilt from config every boot, restarting its merge clean, so fixing unrelated decode errors can turn a two-dimension leak into a twelve-dimension one.

---

## Known issues

<a id="k1"></a>
### K1 — A chunk that cannot generate wedges the main thread under c2me

- **Symptom:** `Error upgrading chunk [x, z] to "minecraft:features"` (or `"minecraft:structure_starts"`), then RCON goes i/o-timeout while `docker ps` stays healthy — often with `Can't keep up! Running 3158ms or 63 ticks behind`.
- **Cause:** any throw during chunk generation leaves the chunk's future uncompleted, and anything waiting on it waits forever — an async caller does not save you, because the chunk still cannot generate and it wedges the server for any player who reaches the area, not only for a tool that scans it. Two known throws, both invalid identifier paths: `epic:chests/DungeonZombie` (`Non [a-z0-9/._-] character in path`, during feature placement when an Epic Dungeons dungeon generates), and `minecraft:emptY` from `lithostitched.worldgen.structure.AlternateJigsawGenerator$StructurePoolGenerator.getTemplatePoolKey` — that typo is a jigsaw block's `pool` tag inside `t_and_t-neoforge-fabric-1.13.9+1.21.1.jar` → `data/kaisyn/structure/outpost/towers/exclusives/nilotic/base_plate.nbt` (Towns & Towers, Nilotic outpost tower); vanilla's own jigsaw generator would throw identically. Second trigger: deleting a runtime dimension's world directory without scrubbing its level.dat entry — the next boot re-creates the dim, regenerates its spawn chunks, and a dungeon landing there wedges the BOOT ([D3](#d3)).
- **Fixed for the case throw** by `StructurePoolIdCaseMixin` (custom-dimensions), which lowercases a pool id at `StructurePools.of` — hooked on vanilla, so it covers every caller and every structure mod. Uppercase is forbidden in an `Identifier` path, so the repair can only touch an id that was going to throw; any other illegal character still throws loudly, logging one WARN per distinct offender. The general rule: the trigger is *any* mod whose chunk generation throws, so treat "a synchronous chunk load can hang forever" as the invariant and never write one on a path that must not stall — `customdim render-check` polls chunk futures with a finite deadline for exactly this reason, because a per-tick time budget is no defence when one bad chunk is enough and the budget is only checked between chunks.
- **Diagnosis and recovery:** spark's `Timed out waiting for world statistics` alone is NOT proof — it also fires through legitimately heavy boots (mass dimension creation can run 10+ min); confirm with `Error upgrading chunk` counts and whether the log has stopped advancing. Recover with `docker stop -t 90 mc && docker start mc` (local only; production restarts go through deploy.sh), plus the [D3](#d3) scrub when the trigger is a regenerating deleted dim.

<a id="k2"></a>
### K2 — c2me `TheChunkSystem` ConcurrentModificationException

`Error executing task on Chunk source main thread executor for <dim>` … `TheChunkSystem.lambda$onItemUpgrade$0`. c2me 0.4.0-alpha's chunk-system rewrite races vanilla's entity manager during heavy multi-dimension chunk activity (boot world creation, forceloads). Non-fatal — the executor catches it — but bursts correlate with degraded TPS during boots. **Do NOT filter it from logs**; it is a real error. If it starts crashing servers, the only lever on our side is removing c2me.

The CME is the loud half of this race. `MixinServerEntityManager` `@Overwrite`s vanilla's pending-unload drain, replacing an iterator plus `iterator.remove()` with `while (!isEmpty()) removeFirstLong()` — a shape that **cannot** throw a CME and spins instead, silently, on the main thread. It also removes each entry unconditionally where vanilla removes only when the unload succeeds, so a failed unload is dropped rather than retried. Absence of the CME is therefore not absence of the race.

<a id="k5"></a>
### K5 — the map is imprecise on steep terrain; cause not established

The rendered height disagrees with the facts on high-relief columns. The error is unsigned (`render − world` runs −71 to +91, mean +1.0, median 0) and scales monotonically with local relief: flattest quartile median |render − world| 2, steepest 20 (75.5% at ≥9 blocks). **It does not affect the facts, and it does not affect `terrain.waterFraction`** — the number the seed roller scores on — and both CI gate signals (`renderHeightSpread >= 1`, `medianSignedRenderMinusFacts == 0`) stay clean throughout.

**What is measured:** `finalDensity` ends in `squeeze`, which clamps to ±0.4583, so under deep rock it saturates and carries no surface — a column whose blocks end at 203 reads a flat +0.4585 from y≈150 to 250, putting the first zero-crossing 92 blocks high. `finalDensity` is not the field that dimension's blocks are placed from; the world, the facts and `getColumnSample` agree with each other and disagree with it.

**Ruled out by measurement — do not re-try these:** the interpolation rewrite (±9+ 608 → 594, then 594 → 594 at the marker), router trimming, cell geometry, off-corner sampling, the rung blind spot, and substituting `initialDensityWithoutJaggedness` (tried and REVERTED — it moved the wisteria anchor 8 → 1011 and stripped every ceilinged dimension of its ground). Vanilla's beardifier and this mod's `KernelDensity` are both structurally incapable of it ([T27](#t27)). The most promising unexplored lead is an off-thread benchmark of vanilla's own `getHeight`; the "~23 ms/column, so the honest height source is unaffordable" figure was wrong, because it came from `render-check`, which is tick-budget-bound at 12 ms a tick, not CPU-bound. `customdim column-ladder <dim> <seed> <x> <z>` prints one column's block ladder beside its density ladder and is the tool for this. **Related:** `the_abyssal_shrine` is excluded from the CI render-check gate — it has never satisfied it (median 2 / 1 / −10 across three seeds). Restore it when this closes.

<a id="k6"></a>
### K6 — A worldgen feature that never returns wedges the main thread

- **Symptom:** a dimension's first chunk never finishes generating. The container
  stays healthy, RCON's listener keeps accepting connections and no command
  completes. One `c2me-worker-N` thread is RUNNABLE at ~100%, every other worker
  is `WAITING` on its semaphore, and **nothing throws** on either log surface.
  It does not recover.
- **Cause — ESTABLISHED, from a watchdog thread dump.** A feature in the
  `minecraft:features` chunk step does not return. The main thread is parked in
  `ServerChunkManager.getChunkBlocking` → `MainThreadExecutor.runTasks`
  (`class_3215$class_4212.method_18857`), waiting on a chunk future that can
  never complete, and that call drains the chunk manager's queue rather than the
  server's — which is why RCON connections are accepted and never answered.
- **The known instance, FIXED:** `wilderwild`'s
  `SnowBlanketFeature.findLowestHeightForSnow` walks down looking for ground
  with no lower bound, and below the world floor every read is `VOID_AIR` —
  registered as air — so a column with no ground never ends the walk.
  `SnowBlanketVoidFloorMixin` (`@Pseudo`, gated on `isModLoaded("wilderwild")`)
  redirects that one read to return bedrock below `getBottomY()`. The walk then
  ends at the floor and the feature's own `height > bottomHeight` check skips
  the column, so the feature keeps working everywhere it already did.
- **Which dimensions were exposed:** a dimension hangs iff a biome in its list
  declares `minecraft:freeze_top_layer` AND its chunks contain a column with no
  solid, non-search-through block beneath the heightmap. Generator shape alone
  decides nothing — `the_end` is safe because `TheEndBiomeCreator` never calls
  `addFrozenTopLayer`, despite being full of void. Twelve qualified. The `cave`
  type is clean: it is built on `minecraft:caves`, which gets a bedrock floor.
- **The hang is temperature-independent.** The search runs for all 256 columns
  before any warmth check, so a jungle sky-island hangs exactly like a snowy
  one.
- **Fallback if the mixin ever has to be dropped:** `snowBelowTrees: false` in
  `config/wilderwild/worldgen.json5` is the exact and only gate on this feature
  (it costs snow under tree canopies; `snowTransitions` and `snowPiles` are
  separate). Shipping it needs the two-place treatment — the file plus a `COPY`
  in `docker/defaults-seed/Dockerfile`.
- **Concurrency is NOT the trigger.** Activating one dimension at a time,
  `the_nether` generated in 6 seconds and `paradise_lost` wedged alone with two
  workers idle. Earlier runs correlated the hang with three simultaneous
  forceloads because paradise_lost was always among them.
- **c2me is a bystander.** Its chunk system is what the main thread waits on,
  not what fails. The consolidating drain and the `ExecutorManager.tryLock`
  retry are both real and both innocent here.
- **This is [K1](#k1) without the throw.** K1 is "a chunk that cannot generate
  wedges the main thread" via an exception leaving the future uncompleted; this
  is the same wedge reached by a feature that simply never returns. Treat "a
  synchronous chunk load can hang forever" as the invariant either way.
- **Guard (in place):** `deploy.sh` activates reserved dimensions one at a time,
  skips any that already has region files, and waits on the region file — three
  unanswered `save-all flush` calls end the deploy in ~90s. `MAX_TICK_TIME`
  (180000) then crashes the JVM and writes the thread dump that names the
  offending feature. Measured end to end: 36s to a loud failure, dump at 180s.
- **Diagnosing a new instance:** read `data/crash-reports/` after the watchdog
  fires. The pegged `c2me-worker-N` stack names the feature and
  `Upgrading chunk [x, z] to <status> in world <dim>` names the chunk.
- **The worker threads tell you which wedge you have.** One worker RUNNABLE at
  ~100% is a worldgen feature that never returns — read its stack. **Every**
  worker `WAITING` on the semaphore, with the main thread still parked in
  `getChunkBlocking`, means nothing is generating at all: something called a
  blocking chunk load from the tick and the future has no work behind it. Read
  the main thread's stack instead — the frame under `getChunkBlocking` names
  the caller. `mods/AGENTS.md` forbids sync-loading from a tick path for this
  reason; probe with `getChunkManager().getWorldChunk(cx, cz, false)` or
  register a `ChunkTicketType.PORTAL` ticket and act on a later tick.

<a id="k9"></a>

### K9 — `Expected directory, got <level>/entities` on a level's first visit

- **Symptom:** during a dimension's first visit, repeated (six times, measured):

  ```
  java.util.concurrent.CompletionException: java.lang.IllegalArgumentException:
    Expected directory, got /data/./world/dimensions/adventure/<slug>/entities
  ```

- **Not confined to custom dimensions.** The same exception appears for the plain overworld (`/data/./world/entities`), so it is not a `custom-dimensions` fault.
- **Transient and self-healing.** The path is a directory afterwards, world generation completes, and the world is usable. Measured on `adventure:the_crucible`: the portal carved, the arrival landed at its declared column, and the container stayed `Health=healthy Restarts=0`.
- **Cause not established.** It looks like a race between whoever creates the level's `entities` directory and the entity-storage worker that expects to find one, but nothing has been measured to confirm that ordering.
- Diagnostic value: it is noise. Do not spend a debugging session chasing it when hunting an unrelated first-boot failure, and do not read it as evidence that a dimension failed to create.

<a id="k7"></a>

### K7 — a climate grid overstates absence, and a band it calls empty usually still generates

- **Symptom:** an analysis reports that N explicit bands "win no ground" or
  "generate nothing", while the game finds those biomes.
- **Cause:** the samples in `config/custom-dimensions/climate-axes.json` are an
  11x11 grid, 121 points. The game's own measurement is `FactsEngine.GRID = 41`,
  disc-clipped to about 1300 cells, read at block y 64 (`SpikeSampler`). A band
  holding a fraction of a percent gets several hits at that density and none at
  121, so "0 of 121" is a resolution floor.
- **Measured against a real 41x41 sweep of all 78 measurable dimensions: a
  121-point cloud calls 40 bands empty where 1681 points call 2 — 20x.**
  Thirty-eight are resolution artefacts, and the 2 are a strict subset of the
  40. `check-band-share.py` reads the dense grids in
  `config/custom-dimensions/grids-41/`; the comparison thins those same grids
  1-in-4 on both axes, so density is the only variable.
- **A "holds no cell" line from the 121-point cloud is wrong about 95% of the
  time.** Do not act on one. The two that survive density are
  `the_greenreach` `minecraft:lush_caves` and `the_rosebluff`
  `terralith:white_cliffs`, both at a 1024 border and both unprobed.
- **Axes can be individually reachable and jointly impossible, and
  `check-band-reach.py` cannot see it** — it tests each axis against that axis's
  own measured min/max, one at a time. `the_greenreach` `minecraft:lush_caves`
  wants depth >= 0.1 and weirdness <= -0.045; 73 columns are deep enough and
  635 are weird enough, and no column is both. Only the joint lookup finds it.
- **Depth is the other direction, and one sampled height is all there is.** The
  grids carry depth at y=0 and the gradient is -1/128 per block, so block y 64
  — what `facts` reads — is that value minus 0.5. Scoring a column at two
  invented depths instead hands a whole synthetic layer to the cave bands: it
  put a cave biome top in all 25 dimensions that band on depth, at shares up to
  79%, against 8 and 47% measured. It also reported `the_roothold`
  `incendium:withered_forest` empty because that band starts at depth 0.6 and
  neither invented layer reached it.
- **The mirror failure is an ABSENT depth, not an invented one, and it is worse
  because it looks plausible.** `config/custom-dimensions/climate-axes.json`
  carries five axes per dimension — `temp, humid, cont, eros, weird` — and **no
  depth column at all**, so a scorer reading it must pin depth to 0. A band that
  constrains only humidity pays nothing either way; every surface native pinned
  at `depth [-0.005, 0.000]` is then handed **0.2014** of free squared distance
  where the game charges it at y64. Measured on `the_frozen_strait`, that is
  **3.7x the entire offset term being swept** (0.0548 at offset 0.234) — the
  tool reported 10 of 13 biomes with bands at 22% where the live world reads 3
  and 100%, and the error was monotone and smooth enough to read as a real
  curve. **Score against `config/custom-dimensions/grids-41/<slug>.tsv.gz`** —
  six axes, 1681 points, `FactsEngine`'s own density — and apply the -0.5. A
  five-axis source cannot answer a six-axis question, and nothing about the
  output says so.
- **Confirmed against the game.** On `the_claymarsh` at seed
  135505505384991812, `customdim facts` reports 14 of its 15 biomes and
  `customdim score` gives 83.4%. `minecraft:swamp` holds a weirdness band 0.026
  wide of a 2.000 span, takes 1 cell of 1257, and `customdim locate biome` finds
  it at **(340, 64, -224)**. The same lookup at 41x41 predicts 15 of 15 — one
  more than the game, disagreeing only on one-cell biomes.
- **Probed against the game, on the synced configs.** Twelve `customdim facts`
  runs: `the_crucible` generates 25 of its 25 listed biomes where this grid
  claimed 9 empty, `the_highland_crossing` 30 of 30 against a claimed 6,
  `the_abyssal_shrine` 9 of 9 and `the_sculked_beyond` 12 of 12 against 1 each.
- **`customdim facts` is itself a floor.** It reads `FactsEngine.GRID` at one
  height, so at a 4096 border its points are 205 blocks apart. `minecraft:plains`
  is missing from `the_sun_kingdoms`' count and `locate biome` finds it 71
  blocks from spawn. Only `locate` searches the source rather than sampling it.
- **A 0.004-wide band generates.** Of ten such bands this grid called empty,
  seven are reachable inside `borders.player`, one of them at spawn
  (`the_lantern_pools` `terralith:warm_river`, distance 0) and one 71 blocks out
  (`the_sun_kingdoms` `minecraft:plains`). The minimum-width floor of an
  equal-area fit is not, on its own, a defect.
- **Three are genuinely unreachable**, and they are the residue worth acting on:
  `the_frozen_hearth` `minecraft:frozen_river` (not found at all), and
  `the_lantern_pools` `minecraft:warm_ocean` and `minecraft:mangrove_swamp`,
  which exist only 4480 and 4640 blocks out against a 512 border.
- **`locate biome` returning `done` is not "reachable".** It searches past
  `borders.player` and the reply's FIRST field is the distance — check it
  against the dimension's own border before reading a hit as success. A
  **`pending` reply is not a negative** either: the search has not finished.
  Only `not_found` is.
- **The instrument hierarchy, weakest last.** `locate biome` SEARCHES the biome
  source and is the only thing that proves presence. `customdim facts` SAMPLES
  at 41x41 and is a floor. A script samples at whatever density it chooses and
  is a floor under that. **None of the three answers encounterability** — that
  is a question about share, and presence and encounterability are different
  questions ([biome-placement.md](../docs/design/biome-placement.md)).
- **`the_claymarsh` is the counter-example, not the model.** Four biomes hold
  96% of it and eleven share 3% at a tenth of a percent each; `minecraft:swamp`
  is one cell of 1257. Every check passes and a player crossing that world meets
  four biomes.
- **Fix:** measure at the game's density before calling a band empty.
  `scripts/check-band-share.py` runs the lookup over the committed 41x41 grids
  and reports without gating. A dimension with no grid is named and skipped
  rather than guessed at; `scripts/sample-climate-grid.sh <slug> <border> 41`
  takes about five seconds to make one.
- **Narrow is not empty, and small is not a defect.** The target is that no
  listed biome is ignored, never that shares are equal.

## Common symptoms

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
| DNS / port | `dig mc.example.com` → server IP; connect on `:25577` (the SRV record usually makes the bare hostname work) |

**Crash-loop triage.** Boot failures die before the game log exists, so `data/logs/latest.log` looks fine while the container loops — and `latest.log` is reset on every boot. Check the container itself: `docker inspect mc --format '{{.RestartCount}} {{.State.Health.Status}}'`, `docker logs mc --tail 80` (init + mixin errors), `ls data/crash-reports/` (tick-loop crashes, local). A RestartCount above 0 is a crash you haven't explained yet. `Mixin apply ... failed` means a broken mod jar — usually fixed by `./ops update` pulling the current bundle, not by removing mods.

**Log surfaces.** Three different things get called "the logs": the console (`./ops logs mc --tail 200`) is **filtered** by `config/log4j2-adventure.xml`; the raw game log (`./ops logs --latest --grep ERROR`) is **unfiltered** and shows what the console hides; `docker logs mc --tail 80` is the only place pre-game-log failures appear.

**Performance (lag, high MSPT):** `docker exec -i mc rcon-cli "spark health"` (TPS 20, MSPT < 50ms); profile with `spark profiler start` … 30–60s … `spark profiler stop`; idle-tasks runs Chunky automatically when empty (progress via `/mc status` in Discord); lower `VIEW_DISTANCE`/`SIMULATION_DISTANCE` in `.env` to shed load.

**Backups:** verify R2 credentials in `.env`; `restic snapshots` ("repository does not exist" → `restic init`); `df -h`; `docker logs mc-backup --tail 50`. See [T14](#t14) if counts look wrong.

**Voice chat:** UDP `24454` must be open in UFW **and** mapped in Docker. Icon error = mod version mismatch, re-import the `.mrpack`. Walls not muffling = Sound Physics is client-side and ships in the pack.

**Chunk corruption:** `./ops wipe-chunk --block <x> <z>` moves the region file aside so it regenerates. The server must be stopped or the chunks unloaded — the script refuses while mc runs unless `--force`. **World map:** [T13](#t13). **Uptime Kuma:** [T6](#t6), [T7](#t7). **Discord:** [T1](#t1) — registry ground truth is `GET /applications/<app_id>/guilds/<guild_id>/commands` with the bot token; the guild should list `register`, `unregister`, `mc`, global should be `[]`.

---

## Deep dives

[`docs/known-issues/carpet-supplementaries-piston-crash.md`](docs/known-issues/carpet-supplementaries-piston-crash.md) — Carpet's piston mixin vs Supplementaries: the tick-loop crash, and why `scripts/patch-mod-data.py` strips one mixin on every deploy.

<a id="t41"></a>
### T41 — A hardening fix that lands in `sshd_config.d` must sort BEFORE cloud-init's

- **Symptom:** `harden.sh` reports "root login disabled, key-only auth active", but
  `sshd -T` still shows `passwordauthentication yes`.
- **Cause:** Ubuntu's `/etc/ssh/sshd_config` opens with
  `Include /etc/ssh/sshd_config.d/*.conf`, and sshd uses the **first value it
  obtains** for any keyword — the opposite of most config systems. A drop-in named
  `99-hardening.conf` is read *after* `50-cloud-init.conf` and loses to it.
- **Fix:** name it `00-hardening.conf`, and assert the result with `sshd -T` rather
  than trusting the write. `harden.sh` does both.

<a id="t42"></a>
### T42 — `rp_filter=1` does not close the spoofed-source bypass, and appears to

- **Symptom:** a spoofed RFC1918 source still reaches the host past every hashlimit,
  after setting `net.ipv4.conf.all.rp_filter=1`. `sysctl net.ipv4.conf.all.rp_filter`
  returns `1`, confirming a fix that is not in force.
- **Cause:** the kernel takes `max(all, interface)` for reverse-path filtering, and
  **loose is 2 while strict is 1** — so a per-interface 2 outranks a global 1. Ubuntu
  ships loose in two files (`systemd`'s `50-default.conf` and `procps`'s
  `10-network-security.conf`), and systemd's udev rule re-applies it to every new
  interface, so each Docker bridge is set back to 2 at creation.
- **Fix:** scope the trusted-source ACCEPT to the bridge interfaces
  (`-i docker0`, `-i br+`). The interface scope is the whole fix; the sysctl is not
  a defence-in-depth measure, it is a no-op.

<a id="t43"></a>
### T43 — fail2ban `jail.d/*.conf` loses to `jail.local`; use `.local`

- **Symptom:** a jail disabled in `/etc/fail2ban/jail.d/mc-source.conf` is enabled
  again after the next `./ops harden`, with nothing in the log saying so.
- **Cause:** fail2ban reads `jail.conf` → `jail.d/*.conf` → **`jail.local`** →
  `jail.d/*.local`, and `harden.sh` rewrites `jail.local` unconditionally.
- **Fix:** put operator overrides in `/etc/fail2ban/jail.d/mc-source.local`, which is
  read after `jail.local` and which `harden.sh` never touches.

<a id="t47"></a>
### T47 — `find | head` under `pipefail` reports a populated dimension as having no chunks

- **Symptom:** a dimension with hundreds of region files silently disappears from the map, the
  pre-generation queue, and deploy's activation wait. No error, no log line — `render_all` just
  reports a lower "map(s) considered" count than there are dimensions. Small dimensions keep
  working, so it reads as corruption or a stale mount rather than a bug.
- **Cause:** `find ... | head -1 | grep -q .`. `head -1` closes the pipe after one line; if `find`
  is still writing it dies of SIGPIPE (exit 141), and `set -o pipefail` makes that the pipeline's
  status, which `if ! ...` reads as "no chunk data". `find` only writes twice once its output
  passes the 4KiB stdout buffer, so the failure is deterministic on region count — roughly 150
  regions under `/world/region`, fewer for the longer paths under `dimensions/<ns>/<slug>/`.
  Measured on production: 355 matches → 141, 100 → 0, 36 → 0.
- **Fix (in place):** `[[ -n "$(find ... -print -quit)" ]]` at all four sites — `render-loop.sh`
  (render guard and the "no region changes" check), `idle-tasks.sh` `visited()`, `deploy.sh`
  `dim_has_region()`. `-quit` stops `find` itself and the command substitution uses no pipe, so
  SIGPIPE is impossible rather than unlikely.
- **Rule:** never pipe `find` into `head` in a script running `pipefail`. Use `-print -quit`.

<a id="t46"></a>
### T46 — `./ops update` and `./dev update` refresh different machines; `./ops harden` reads the local one

- **Symptom:** `./ops update` reports `v5.23.0 ready at .stack/current`, but a following
  `./ops harden` uploads and runs the previous version's script — completing successfully and
  writing `/root/.harden-done`.
- **Cause:** `./ops update` pulls the bundle **on the server**; `./dev update` pulls it
  **locally**. Both print the same `.stack/current` path. `harden.sh --remote` uploads *itself*,
  so it ships whatever the **local** bundle holds, regardless of what the server has.
- **Fix:** run `./dev update` before `./ops harden`, and verify the local bundle before shipping
  it:

  ```bash
  readlink .stack/current   # expect the version you just released
  ```

  From v5.24.0 `harden.sh` also compares the uploaded file's sha256 against `/root/harden.sh`
  and refuses to run on a mismatch, so a stale script aborts rather than hardening silently.

<a id="t45"></a>
### T45 — `before6.rules` has FOUR `# ok icmp codes` headings; the v4 anchor duplicates rules

- **Symptom:** after adding IPv6 rate limiting, legitimate players are dropped from
  the game port far below the advertised 6 connections/minute.
- **Cause:** `sed '/pattern/r file'` inserts after **every** match. `before.rules`
  carries one `# ok icmp codes` heading (INPUT is plural "codes", FORWARD is singular
  "code"), but `before6.rules` carries **four** — INPUT, OUTPUT and FORWARD twice.
  Four rules sharing one `--hashlimit-name` share one htable, so each packet spends
  four tokens and the effective limit is roughly a quarter of the figure written.
- **Fix:** anchor on `^# ok icmp codes for INPUT`, which is unique in both files, and
  assert `grep -c 'hashlimit-name mc-game6' == 1` afterwards rather than merely
  checking the marker is present.

<a id="t44"></a>
### T44 — Moving the UFW rate-limit block earlier kills voice chat

- **Symptom:** Simple Voice Chat breaks for everyone immediately after a firewall
  change, with no error anywhere.
- **Cause:** the hashlimit block sits **after** ufw's `RELATED,ESTABLISHED` accept in
  `before.rules`. That placement is load-bearing: the voice rule reads as
  "10 UDP packets/min", which would destroy a ~50 packet/sec stream, and it only
  works because established flows never reach it. Moving the block up exposes every
  packet of an ongoing conversation to the limit.
- **Fix:** leave the block where it is. It rates flow-opening packets only, which is
  the intent.


<a id="t57"></a>
### T57 — The backup sidecar's first run collides with a fresh world's priming pass

- **Symptom:** on a local stack a few minutes after `./dev reset-world` +
  `./dev up`, `mc` crashes with `java.lang.Error: Watchdog` and restarts
  (`RestartCount` 1, exit 0). The parked frame is a `save-all` reached through
  brigadier from RCON:
  `CompletableFuture.join` -> `class_5565.method_31758` ->
  `c2me$tryFlush` -> `replaceEntityFlushLogic` -> `MinecraftServer.method_3723`.
  The seed roller's priming pass restarts, and `render_pending` jumps back up.
- **Cause:** `mc-backup-local` waits a fixed `initial delay of 2m` after start,
  then runs `save-off` / `save-all flush` / `save-on`. On a fresh world that
  lands inside the roller's priming pass, which is creating and scoring every
  dimension at once. The entity flush blocks the main thread past
  `MAX_TICK_TIME` (180000) and the watchdog kills the JVM. The collision is
  deterministic, not a race that sometimes loses — the delay is fixed and
  priming always occupies that window.
- **Fix:** `docker stop mc-backup-local` before a fresh-world roll and start it
  again once priming has finished. The world survives — verify with
  `customdim structure-census <dim>` reporting `live and facts agree` — so this
  costs a restart, not data. `BACKUP_INITIAL_DELAY` widens the window and
  `MAX_TICK_TIME` widens the budget; both default to the values above.
- **Production's exposure is one window, and it is not the dimension sweep.**
  `deploy.sh` stops `mc-backup` for the duration of every deploy —
  `pause_backups` at `:310`, re-stopped at `:724` and `:793` after each compose
  up recreates it, `resume_backups` at `:1133`, plus an EXIT trap at `:315`.
  The `customdim load` activation sweep at `:973` sits inside that window, so
  no flush can reach it. `docker start` re-runs the entrypoint, so the
  `BACKUP_INITIAL_DELAY` timer begins at `:1133`. What remains is the
  `BACKUP_INITIAL_DELAY` after `deploy.sh` returns, when every dimension exists
  with spawn chunks generated.
- **`mc-backup` is not the only flush. `idle-tasks` issues its own a minute
  later and no variable reaches it.** `idle-tasks.sh:194-199` runs
  `save-all flush`, `sleep 5`, `spark gc`, then starts Chunky pre-generation
  across every dimension (`:332-354`). Its gate is `IDLE_GRACE: "3"` minutes
  and `POLL_INTERVAL: "30"` seconds, and it is dormant while `deploy.sh` holds
  `.skip-pause-deploying` (`:86`), so it wakes when the deploy returns and
  fires three minutes later — the same brigadier → `SaveAllCommand` →
  `c2me$tryFlush` → `CompletableFuture.join` frame, without a `save-off` to
  mark it. Post-reset with nobody online: +2m the sidecar's flush, +3m
  idle-tasks' flush and then Chunky.
- **Stop both sidecars; do not widen the window with `BACKUP_INITIAL_DELAY`.**
  `./ops ssh 'docker stop mc-backup idle-tasks'`, then `docker start` both once
  the map shows dimensions rendering. 2m is already the quietest point after a
  reset — creation is finished and Chunky has not started — so any value above
  ~3m moves the flush INTO Chunky pre-generation of a fresh 82-dimension world,
  which is more expensive than the window it left. Nothing clears first
  generation: that is hours of Chunky, and a value in hours deletes the first
  snapshot of the new world. A GitHub production variable also applies to every
  deploy afterwards. The roller's priming pass — the expensive case measured
  below — is local-profile-only and never runs on production.
- **An ESTABLISHED world's priming pass survives it.** Measured across three
  boots whose flush landed inside a priming pass over 78 existing level
  directories: windows of 7 s, 33 s and 35 s against `MAX_TICK_TIME` 180000,
  zero `Watchdog` occurrences, `RestartCount` 0 throughout. The collision is
  deterministic; the KILL is not. A fresh world's pass creates and scores every
  dimension at once and is far more expensive than one that only re-scores.
  Stop the sidecar anyway — the cost is a `docker stop`, and the case that bites
  is the one you are most likely to be running.
- **The sidecar's own log is not the check.** It reports `sleeping 12h` while
  the crash it caused is already in `data/crash-reports/`.
- **`RestartCount` belongs to the CURRENT container, not to the world.** A
  recreate — `./dev up`, `docker compose up`, anything that replaces the
  container — resets it to 0, so a container recreated after two watchdog kills
  reports `RestartCount=0 Health=healthy` and reads as a clean run. `docker
  logs` goes with it. What survives a recreate is `data/logs/*.log.gz` (one
  rotation per server start, so today's file count is today's boot count) and
  `data/crash-reports/`. Bracket a run with `gzcat data/logs/<date>-N.log.gz |
  head -1` and `| tail -1` before treating a series of measurements as one JVM.
- **The tell is silence, not the tick warning.** The last line before the kill
  is the sidecar's own `[Rcon: Automatic saving is now disabled]`, and nothing
  follows it for the whole watchdog budget. Measured: save-off at 16:22:26, `A
  single server tick took 180.00 seconds` at 16:25:26 — the difference to the
  second, three minutes of no output between them. A post-wipe boot that goes
  quiet right after a save-off is already dead.
- **`MAX_TICK_TIME` is a safety net, not the fix, and it moves where the server
  dies.** The watchdog is a liveness detector and a `save-all` flush during
  first generation is legitimately slow rather than hung; nothing in the
  watchdog separates them. Not issuing the save-all during priming is the fix —
  stop the sidecar, or push `BACKUP_INITIAL_DELAY` past the priming window.
  Raise `MAX_TICK_TIME` for the window and **put it back**: at 900000 a
  genuinely wedged main thread takes fifteen minutes to surface instead of
  three, and a value left in a consumer's `.env` survives every recreate.
- **Two crashes on one post-wipe world are not one fault.** Tell them apart by
  the main thread, never by the workers — all eight c2me workers park on a
  `Semaphore$NonfairSync` in both. `"Server thread" ... WAITING` inside
  `SaveAllCommand -> c2me$tryFlush -> CompletableFuture.join` is this entry.
  `"Server thread" ... RUNNABLE` inside `NoiseChunkGenerator.populateNoise` is
  not — that is the main thread doing worldgen too slowly ([K1](#k1),
  [K6](#k6)), and over RCON it is [T64](#t64).
- A roll running longer than `BACKUP_INTERVAL` (default 12h) meets the next
  backup and crashes again.

## Adding an entry

1. Pick the next unused number in the right prefix. **Never reuse a retired ID.**
2. Add `<a id="tN"></a>` immediately above the heading — the anchor is the contract, the title is not.
3. Write **symptom → cause → fix**, in that order, and nothing else. The symptom carries the exact error string so it is greppable. No discovery story, no dates, no what-was-tried-first.
4. Add a row to the [symptom index](#symptom-index).
5. Link to it from wherever someone would hit it — a script header comment, a doc, a skill. Don't restate it there.
