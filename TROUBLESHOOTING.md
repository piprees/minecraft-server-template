# Troubleshooting

> **Single source of truth for problems.** Every known trap, platform quirk, and open issue lives here; nothing in this repo should describe a problem in its own words — link here instead. **Every entry has a permanent ID.** Cite it from anywhere: `TROUBLESHOOTING.md#t14`, `../TROUBLESHOOTING.md#d3`, or just "see T14" in a script comment. IDs are stable — an entry keeps its ID even if the surrounding list is reordered, retitled, or thinned out. When an entry stops being true, delete the entry and its ID; never renumber the rest.

| Prefix | Range | What it covers |
| --- | --- | --- |
| **T** | [T1–T14, T16–T19, T22–T27, T30–T37](#architecture-traps) | Architecture traps — each has caused a real production incident |
| **P** | [P1–P4](#macos-local-dev) | macOS local-dev quirks (BSD tooling, toolchain) |
| **D** | [D1–D6, D8–D9](#dimension-lifecycle) | Custom-dimension lifecycle on a live world |
| **K** | [K1–K2, K5–K6](#known-issues) | Open issues — unfixed, on the watch list |

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
| Mod build fails with a misleading Gradle task error | [P4](#p4) |
| A worldgen config change had no effect | [D2](#d2) |
| `Tried to read NBT tag that was too big`; `level.dat` growing every boot | [D9](#d9) |
| Boot hangs after deleting a dimension's world directory | [D3](#d3), [K1](#k1) |
| Custom dimensions all generate identical terrain | [D6](#d6) |
| `Error upgrading chunk`, RCON i/o-timeout, container healthy | [K1](#k1) |
| `TheChunkSystem` ConcurrentModificationException | [K2](#k2) |
| The map looks imprecise on steep terrain | [K5](#k5) |
| A fresh-world deploy stops answering RCON forever, no exception, one thread pegged | [K6](#k6) |
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
### T19 — A listed biome with no climate parameters swallows the whole dimension

- **Symptom:** a `multi_biome` (or any biome-listed) dimension generates as one biome nearly everywhere, and seed rolling reports the rest as "not found" however many candidates are rolled. The outcome is structural, not unlucky.
- **Cause:** `DimensionManager.buildMixedSource` splits the biome list into biomes that already have a hypercube in the base source ("native") and those that do not ("foreign"), then deals every leftover hypercube to the foreign biomes **round-robin** — so a single foreign biome receives all of them while the natives keep only what they literally claim. Across the 34 affected dimensions the foreign biomes held 74–100% of the area. Without climate parameters: all 47 **Nature's Spirit** biomes (they place through their own layer, not the vanilla multi-noise list), and `minecraft:end_barrens`/`minecraft:end_midlands` (vanilla places those by erosion in `TheEndBiomeSource`, which Nullscape replaces wholesale). Nullscape also gives `minecraft:the_end` and `minecraft:end_highlands` the **same** full-span hypercube, so one of that pair can never win a nearest-point lookup, and gates `nullscape:void_barrens`/`nullscape:crystal_peaks` on `temperature >= 0` — an octave -11 noise that barely moves inside one dimension.
- **Fix:** give the biome an explicit hypercube. An object-form entry (`{"id": ..., "parameters": {...}}`) withdraws it from the foreign machinery, and once no foreign biomes remain the leftover pool is dropped rather than dealt out. Band on an axis the dimension's climate noise actually crosses at its own radius — **weirdness** for overworld and End dimensions, **continentalness** for `paradise_lost` clones (humidity spans 0.207 across a 512-radius world against weirdness's 0.783). `biomes` is creation-time worldgen config ([D2](#d2)) and changing it re-keys the generation fingerprint, so every affected dimension needs a re-roll, not a rescore.

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
- **Cause:** TerraBlender (pulled in by `naturespirit`) walks the whole DIMENSION registry from a `MinecraftServer` mixin on `[main]`, before `registerDimensions` runs on `[Server thread]`. On the first boot our dimensions are not in the registry yet; on every later boot they are, decoded from `level.dat`, and any level stem whose dimension TYPE is in `terrablender:overworld_regions`/`nether_regions` over a `MultiNoiseBiomeSource` gets mutated in place — one search tree per registered region, picked by region-uniqueness noise in `getNoiseBiome`, plus every region's biomes appended to the source's memoised biome set. That set is what filters structure pools, so the pool is filtered against a biome list several times wider than the dimension's. Dimensions with an `environment` block escape: their runtime `{slug}_type` is in no tag.
- **Fix (in place):** `ConfiguredBiomeSource.restore` rebuilds the source from its own parameter entries — which the injection leaves untouched — before the `ServerWorld` is built, and WARNs with both counts. The four reserved worlds have no config here and keep whatever the pack's biome mods give them. To put another mod's biomes in a managed dimension, name them in `biomes`; `buildMixedSource` deals them hypercubes in this mod's own noise.
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

<a id="t32"></a>
### T32 — A candidate's thumbnail is a window on spawn, not a picture of the world

A seed's map and its banked facts appear to contradict each other — the thumbnail shows an island and a coastline while `terrain.waterFraction` reports the world as almost entirely submerged, and play confirms the facts. `CandidateRender.Resolution.LOWRES` covers a fixed `THUMBNAIL_BLOCKS` (512) centred on the dimension's **declared spawn**, one block per pixel, while the facts cover the whole playable disc — 1024 blocks across for a 512-radius dimension, so the thumbnail is a quarter of the area, and the roller picks a habitable spawn, which makes that quarter the least representative part by construction. Measured on `the_wuthering_wisteria` (seed `-8181123680324586121`): facts 98.0% water, live world 97.3%, highres PNG 96.6%, lowres PNG **81.4%**. Compare like with like — `customdim render-check <dim> <seed>` puts world, facts and render on ONE grid and reports where they disagree, `render-check-headless` does facts ↔ render with no world — and judge wetness from the highres view or the facts, never from the thumbnail. Any "the map disagrees with the measurement" report needs the sampled AREA established first.

---

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
### K6 — Concurrent first-time chunk generation in several dimensions wedges the main thread

- **Symptom:** on a world with no region files (a `reset-seed`, or a first
  deploy), the deploy's dimension-activation step stops returning. The
  container stays healthy, RCON's listener keeps accepting connections
  (`Thread RCON Client ... started` repeats in `data/logs/latest.log`) and no
  command after the last forceload confirmation ever completes. One
  `c2me-worker-N` thread sits RUNNABLE at ~100%, the rest of the JVM is idle,
  and **nothing throws** — no exception, no `Error upgrading chunk`, no GC
  warning, at info level or above. It does not recover; measured at 26 min and
  2h47m before being killed by hand.
- **Trigger, established:** more than one dimension generating its first
  chunk at the same time. `forceload add` returns as soon as its ticket
  registers, so issuing it for the nether, the End and paradise_lost back to
  back puts three first-time generations on c2me's single JVM-wide chunk
  executor (`ChunkSystemExecutors`' fields are `static final`). The same
  forceload alone on a quiet server finishes in ~2 min on 4 cores.
- **Cause: NOT ESTABLISHED.** Leading candidate, read from bytecode but by a
  single reader: `com.ishland.flowsched.executor.ExecutorManager.tryLock()`
  retries a check-then-act on a mutable `freed` flag through an internal
  `goto` with no bound and no backoff, and never reaches its
  listener-registration line while that flag flaps. That shape is a livelock —
  real CPU, no exception, no log line, no bound — and it matches every
  measurement. Refuted by direct read: thread-pool starvation (the dispatch
  path has no blocking wait at all), `recoverFromErrors` retry storms and K2's
  CME (both need a throw that never happened).
- **Guard (in place):** `deploy.sh` activates the reserved dimensions one at a
  time, skips any that already has region files, and waits for the region file
  rather than for RCON — three unanswered `save-all flush` calls end the
  deploy in ~90s instead of ~114 min. The 78-dimension setup loop breaks after
  `DIMENSION_FAILURE_LIMIT` consecutive failures. `MAX_TICK_TIME` is a real
  threshold again, so a stalled tick produces a thread dump.
- **Next reproduction, before killing anything:** read the pegged thread's
  name — it discriminates directly between the two candidate mechanisms
  (`c2me-worker-N` → the `tryLock` livelock; `c2me-sched` → the single-flight
  consolidating executor).

  ```bash
  docker top mc -eo pid,ppid,pcpu,comm            # java is NOT pid 1
  docker exec mc kill -QUIT <java_pid>            # JVM thread dump, no tooling needed
  docker exec mc sh -c 'for t in /proc/<java_pid>/task/*; do echo "$(cat $t/comm) $(awk "{print \$3}" $t/stat)"; done'
  ```

- **Forensics go to the file, never `docker logs`.** The console appender
  DENYs `.*Thread RCON.*` and the chunk-executor errors; reading it instead of
  `data/logs/latest.log` reports zero and inverts the conclusion.

---

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

## Adding an entry

1. Pick the next unused number in the right prefix. **Never reuse a retired ID.**
2. Add `<a id="tN"></a>` immediately above the heading — the anchor is the contract, the title is not.
3. Write **symptom → cause → fix**, in that order, and nothing else. The symptom carries the exact error string so it is greppable. No discovery story, no dates, no what-was-tried-first.
4. Add a row to the [symptom index](#symptom-index).
5. Link to it from wherever someone would hit it — a script header comment, a doc, a skill. Don't restate it there.
