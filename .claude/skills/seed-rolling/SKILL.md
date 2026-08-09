---
name: seed-rolling
description: Runs and diagnoses the pure-Python seed roller in scripts/seed/ that scores candidate seeds for every custom dimension and the four base worlds (overworld, the_nether, the_end, paradise_lost) against a dimension's seedRoll block — namesake, variety, terrain, and structures — without booting Minecraft after warmup. Covers ./dev seed-roll / seed-rescore / seed-status / seed-viewer, the candidate-bank lifecycle (45s auto-write, Ctrl+C finalises, rejected seeds banked forever), seed-group generation fingerprinting, and the GET /fork-schema oracle. Use when a dimension rolls zero candidates, seed-status reports a winner as STALE or DRIFTED, deciding re-roll vs rescore after a seedRoll edit, tuning mood weights or structure wants/shuns bands, or chasing a "config invalid — skipped" Gson crash. Does not cover the dimension JSON schema — see custom-dimension-authoring for that.
---

# Seed Rolling

`scripts/seed/` is a pure-Python reimplementation of Minecraft's multinoise biome source, Terralith's terrain spline, and structure placement maths — it scores thousands of candidate seeds per second for every custom dimension without running the game (after a one-time warmup that does need Docker). This skill covers running it, reading its output, and diagnosing it when scores are bad or candidates don't appear.

**This skill does not describe the dimension JSON schema.** For `seedRoll` field syntax, biome ids, structure short names, or the mood/difficulty philosophy, read `.claude/skills/custom-dimension-authoring/SKILL.md` (`custom-dimension-authoring`) first — this skill assumes a dimension config already exists and focuses on the roller itself.

## Decision tree — the commands look interchangeable and are not

| Situation | Command | Why |
| --- | --- | --- |
| A dimension's worldgen fields changed (`type`, `noiseSettings`, `biomes`, `structureDensity`, etc.) | `./dev seed-roll --dims <slug>` | Banked measurements describe a world the config no longer generates — only re-rolling produces valid data |
| Only `seedRoll` weights/wants/mood changed, worldgen untouched | `./dev seed-rescore` | Rescoring replays banked measurements against the current config for free; re-rolling is not free |
| Want to see what's banked before doing anything | `./dev seed-status` | Counts, winner, freshness (`fresh`/`STALE`/`DRIFTED`) per target, no Docker needed |
| Want to override the score ranking by eye | `./dev seed-viewer` → pick a candidate → "Use this seed" | Pins a human pick (`winnerPinned: true`) above the score ranking; survives future rescoring |
| Starting completely clean | `./dev seed-roll --reset` | Wipes `.seedtest/` and every `candidates/*.json` store |

All four subcommands (`seed-roll`, `seed-rescore`, `seed-status`, `seed-viewer`) are wired in `examples/consumer/dev` and documented in the root `README.md`. `seed-roll` is a shell wrapper (`scripts/seed/roll-all.sh`) around warmup + `fast_roller.py` + `score-dimensions.py finalise`; the other three call `score-dimensions.py` subcommands or `viewer-server.py` directly.

## Lifecycle facts you must not get wrong

- **Winners auto-write into the individual dimension config files every 45 seconds** during a roll session (one timestamped backup per session — `.config-backed-up` marker in `.seedtest/` prevents repeat backups; `--no-write` disables writing entirely and only measures + scores).
- **Ctrl+C finalises** with whatever has been measured so far — it is not a discard. Re-runs resume from banked state; nothing is re-measured.
- **Rejected seeds are banked in `.seedtest/candidates/{slug}.json`'s `rejected` map and never retried** across sessions. `--reset` wipes the whole of `.seedtest/`, bank included; a plain re-run of `seed-roll` does not. (See § Where the roller's state lives — the bank lives under `.seedtest/`, not `data/`, so resetting a world does not touch it.)
- **Void and superflat dimensions are skipped** — `dimension_profiles.rollable()` excludes `superflat` unconditionally and `void` unless it has a `biomes`/`biome` list (a void with no listed biomes has nothing to measure: no terrain, no listed biomes to locate).
- Base-world entries (`overworld.json`, `the_nether.json`, `the_end.json`, `paradise_lost.json` — filenames matching `dimension_profiles.BASE_WORLD_IDS`) roll too, as `worlds[]` entries. The overworld winner becomes the top-level `worldSeed`; nether/end/paradise_lost winners are written as `seed` on their `worlds[]` entry. **New chunks generate on the winning seed; existing chunks keep the old terrain** — only a world wipe (`./ops reset-seed` on production) regenerates everything.

## Scoring model — one table

Every candidate gets 0–100 from four weighted components (weights come from `MOOD_WEIGHTS` in `dimension_profiles.py`, keyed by `seedRoll.mood`):

| Component | What it measures | Notes |
| --- | --- | --- |
| **namesake** | Spawn biome matches `seedRoll.spawnFilter` (or first 4 config biomes if unset) | Full credit (1.0) only within 48 blocks of origin; partial credit scales down to 1024 blocks away; total absence within the 768-block sampling window rejects the candidate outright (see Zero candidates below) |
| **variety** | Listed biomes actually locatable near spawn (`locate`-equivalent), proximity-weighted so fringe/border-only biomes don't inflate a monoculture | Sampled down to 8 evenly-spaced biomes for lists longer than 8 |
| **terrain** | Relief / grain / water fraction from a 3×3 climate grid vs targets keyed by `noiseSettings` (`compressed` wants violence, `wide` wants gentle rolling, voids want zero land, islands want real gaps) | Mood shifts the targets: `hard`/`dramatic` want more relief, `serene`/`pastoral` want less |
| **structures** | For a noise dimension: how well each group's radial spread matches its curve, plus whether the group is populated at all, plus wants/shuns scored from exact block distances from the candidate's spawn. For a suppressed one: the old want/shun distance battery | A mob difficulty ≥ 2.0 shifts weight FROM namesake/variety INTO structures — dangerous worlds must be worth it |

A void dimension overrides the weights entirely (`namesake 30 / variety 55 / terrain 15 / structures 0` — there's no terrain to score). `clearSpawnRadius` (from `structures.clearSpawnRadius` or the mood default in `MOOD_CLEAR_SPAWN`) penalises any battery structure found too close to spawn regardless of its want range.

## Rollability rules

A dimension rolls only if `dimension_profiles.rollable()` says yes:

- Not `"seedRoll": {"skip": true}` (the explicit opt-out).
- Not `type: "superflat"`.
- If `type: "void"`, it must carry a `biomes` (or legacy `biome`) list — an empty void has nothing to measure.
- Everything else (including `checkerboard`, whose biome grid is seed-independent but whose terrain/structures still vary) rolls.

## Zero candidates — the headline diagnostic

Check causes **in this order**, cheapest first:

1. **`seedRoll.spawnFilter` names a biome absent from `biome_params.json` for that dimension's family.** This is by far the most common cause. `fast_roller.tier2_measure` only gates on the spawn filter when at least one namesake biome is representable by the sampler for that family (`namesake_in_sampler = namesake_set & sampler_biomes`); if NONE of the filter biomes exist in the sampled family, every candidate is rejected outright. Cross-check every `spawnFilter` entry against `.claude/skills/custom-dimension-authoring/references/biome-catalogue.md` (the `custom-dimension-authoring` skill) (or `GET /fork-schema`, below) for the dimension's actual family — not just "does this biome exist in the game", but "does it exist for THIS family's noise config".
2. **The dimension's family wasn't captured by warmup.** `.seedtest/biome_params.json` needs ≥5 entries tagged with a family for it to be usable; `roll-all.sh`'s warmup phase re-triggers automatically when a family's count is implausibly low, but only if Docker is available and mod jars exist in the shared cache (`${MOD_CACHE_DIR:-~/.cache/adventure-mods}`, filled by `./dev up`) or in `data/mods/`.
3. **The dimension isn't rollable** — see Rollability rules above. Check with `./dev seed-status` (a non-rollable dim never appears in its output at all) or by re-reading the config against `dimension_profiles.rollable()`.

**How the actual spawn gate works (verify this against `fast_roller.py`, not older prose):** for each survivor seed, `tier2_measure` samples a 768-block-radius, 256-block-step grid (`biome_sampler.spawn_filter`) for the nearest matching namesake biome. If nothing matches anywhere in that grid, the candidate is rejected and banked (never re-rolled) and the worker draws a fresh seed. If something matches, the candidate is **always accepted** — a match within 48 blocks scores full namesake credit; anything further out (up to the 768-block search radius) still banks `spawn_filter_dist` and earns partial proximity credit in scoring, capped below a true 48-block spawn. There is no multi-stage widening gate keyed on an attempt counter in the current pipeline — if you see that description elsewhere in older documentation, trust `fast_roller.py`'s `tier2_measure` instead; it's the code that actually runs for `./dev seed-roll`.

## `GET /fork-schema` — the canonical valid-values oracle

`scripts/seed/viewer-server.py` builds this JSON blob lazily on first request (cached for the server's lifetime) from `dimension_profiles.py` + `biome_params.json`: every dimension `type`, `noiseSettings` preset, `structureDensity` value, mood (with its blurb), band name and its fraction range, every `STRUCTS` short name, every hostile structure name, water preference, and **every installed biome id grouped by namespace**. It is built from the same source the roller scores against, so it can never drift out of sync the way a hand-written reference table can.

```bash
./dev seed-viewer                                       # starts the server on :8765
curl -s http://127.0.0.1:8765/fork-schema | python3 -m json.tool | less
```

Prefer this over guessing a biome id or structure short name from memory — it's what the viewer's own fork/create/edit form validates against (`_validate_fork_config` in `viewer-server.py`), and a bad value there returns a field-level error rather than silently producing a zero-candidate dimension.

## Warmup — when it must re-run

Warmup (`roll-all.sh` phase 1) extracts structure sets from mod JARs into `.seedtest/.structure_sets/` and dumps `biome_params.json` from a short-lived, stripped-down MC server boot (`/customdim dump-biome-params`, ~90s). It needs **Docker** and mod jars in the shared cache (`${MOD_CACHE_DIR:-~/.cache/adventure-mods}`, filled by `./dev up`), falling back to `data/mods/` only if the cache is empty — the roller never requires `data/` to exist. It re-runs automatically when:

- `.seedtest/.structure_sets/` doesn't exist yet.
- `.seedtest/biome_params.json` doesn't exist, a family's tagged biome count is implausibly low (< 5), or the table carries no `_tbRegions` sentinel — `roll-all.sh` checks all of these before every roll.
- `.seedtest/structure_pools.json` doesn't exist.

**Every generation of the table's schema needs its own freshness check.** The shipped copy carries `family` tags but no `_tbRegions`, so a check that asks only about the older field passes on a table missing the newer half and the warmup never re-runs — scoring every TerraBlender-placed biome against a flat union ([T29](../../../TROUBLESHOOTING.md#t29)). Adding a field to the table means adding the question that detects its absence.

**Confirm the warmup produced what you expect**, rather than that it ran: the summary prints `TB region tables: <type>: N regions`, and warns when there are none. An entry count alone cannot tell a full table from a stub.
- You've changed worldgen mods and need `biome_catalog.json`/`biome_params.json` refreshed by hand: `scripts/seed/extract-biome-catalog.py <data-dir>`.

**The live biome parameter table is `<consumer>/.seedtest/biome_params.json`**, seeded once from the read-only copy the bundle ships at `scripts/seed/biome_params.json` and never written back to it — and never written into `.stack/<version>/`, which `./dev update` replaces wholesale.

A warmup failure ("ERROR: biome param dump failed") leaves scoring incomplete but does not stop the roll — dimensions in the affected family will simply zero-candidate per the diagnostic above.

## Noise structure placement and the roller

Structure positions come from a seeded noise field per structure GROUP — not a vanilla grid re-derived from spacing/separation/salt — mirrored bit-for-bit in `scripts/seed/noise_placement.py`.

- `noise_census(world_seed, dim_name, dim_config, type_defaults)` returns `{group: [(chunk_x, chunk_z), ...]}` — a complete census of the dimension, not a nearest-instance search.
- Ground truth is `/customdim structure-census <dim>` on a running server, which dumps the LIVE placement calculator plus each group's resolved inputs. `test_noise_parity.py` diffs the two with zero tolerance; the committed fixtures live in `scripts/seed/testdata/census/`.
- **The mirror must stay bit-exact.** Java longs wrap and `>>>` is unsigned; ranks compare UNSIGNED; `Math.round` is `floor(x + 0.5)`, not Python's banker's rounding. The module docstring lists every such rule. Change `StructureNoise.java` / `NoiseFieldIndex.java` and `noise_placement.py` together, then re-run the parity test.

### Scoring: `distribution_match`, not nearest distance

`census_scoring.py` scores a seed's LAYOUT, because a nearest-instance distance no longer describes a world where whole groups share one noise field:

```
structures = 0.6 * census + 0.4 * battery
census     = mean over groups of 0.7*distribution_match + 0.3*count_satisfaction
```

`distribution_match` bins each group's positions by radial decile, converts to a per-annulus DENSITY (equal-width bins cover unequal areas) and cosine-compares that with the group's radial curve. Wants and shuns score from exact block distances from the candidate's spawn — except for forced placements and the sets that keep grid placement, which stay positional because for them the old model is still true. A dimension with no noise groups scores exactly as it did before.

Censuses are **banked per candidate** (`noiseCensus`, keyed by `noise_fingerprint()`), so the first `seed-rescore` after this change prints `noise census: computing N candidate layout(s)` and takes about an hour over a full bank; every rescore after it is free. It reports an ETA every 5% — if you see no progress line at all, you are on an older build, not a hung job.

**It runs on `max(2, cpu_count * 2 // 3)` processes, and `--census-workers N` overrides that.** The flag is accepted by `score-dimensions.py`, by `roll-all.sh` (also as `ROLL_CENSUS_WORKERS`), and therefore by `./dev seed-roll`. Lower it when you need the machine for something else — a local Minecraft server booting beside an unrestricted run takes noticeably longer. Full detail: `references/scoring-model.md`.

### Exact identity and the "not exactly measurable" convention

Structure identity is exact throughout. `StructurePick` (Java) is mirrored by `noise_placement.pick_seed`/`resolve_structure` (Python); tag resolution uses `structure_tags.resolve_tag` over warmup-extracted tag JSONs (nested refs, `required:false`, cycle-safe). All substring tag matching is deleted; unavailable tag data returns -1/skip ("not exactly measurable"), never an estimate.

Three measurement domains carry this convention:

- **Pass-through battery.** Measurement runs ONLY for `VERIFIED_PASSTHROUGH_TYPES` (currently `minecraft:random_spread`) at frequency 1.0. Any other placement type is banked as not exactly measurable. `test_passthrough_parity.py` is the only way onto the allowlist.
- **Depth axis.** Evaluated exactly for adventure presets at block y=64 (= `QuartPos.toBlock(16)`, the oracle's convention). `paradise_lost` is provably depth-free. Other families carry `depth_exact=False` (not exactly measurable) until their router graphs are extracted.
- **Census summary.** `byStructure` reports `{count, nearest}` per structure in the summary; `hist` is display-only and NOTHING is scored from it.

### Fingerprint impact — expect a wave of DRIFTED

`generation_payload()` gained a **conditional** `noisePlacement` key and a conditional `structureSelection` key (`"pick-v1"` — the pick algorithm version; changing it re-deals every site). Of the shipped set: 73 dimensions carry it and will report **DRIFTED** (correct — noise genuinely changed their worlds, so their banked measurements describe a world the config no longer generates, and only a re-roll fixes it); 5 suppressed dimensions keep byte-identical fingerprints.

### Base worlds

The four base worlds take noise placement like any other dimension. Their generator is vanilla's, so their files name no `type` and `monolith_from_dir` stamps the family from `BASE_WORLD_TYPES` (`overworld`, `nether`, `end`, `paradise_lost:paradise_lost`) — mirroring `DimensionConfig.getType()`. An explicit `type` in the file wins.

- **A base world's payload carries a `baseWorld` key**, so it forms a seed group of exactly itself. It never shares measurements with a custom dimension that agrees on every other field: vanilla builds a base world from the level's world preset, the mod templates a custom dimension off `overworldOpts`, and the roller measures the two through different plumbing (`world_family` vs `family_of`).
- **`is_world` is keyed on identity** — `name`/`dimensionId` against `BASE_WORLD_IDS` — never on whether a `type` is present. Keying it on the field sends a base world down the custom-dimension path, where `paradise_lost` resolves family `overworld` (wrong biome sampler, every measurement invalid) and the scale comes from `portal.scale` instead of the top-level `scale`.
- `monolith_from_dir` carries `structures`, `structureDensity` and `exitShrines` into the `worlds[]` entry alongside the type; without them the roller scores a world whose structures the mod is placing while believing it places none.

The progression floors that gate the Nether and the End are in `scripts/check-noise-regression.py`.

**Two fields are generation-affecting:** `borders.player` (sets the scanned radius AND the noise frequency scale) and `difficulty.mobMultiplier` (drives the peaceful/hostile group shifts). Editing either re-rolls the dimension.

**A base world's winner only takes effect through its config file.** `.env SEED` seeds `level.dat`, not terrain ([T31](../../../TROUBLESHOOTING.md#t31)), so a winner must reach the server's dimension config BEFORE the world is deleted — push the overlay, let the deploy land, then reset. Resetting first regenerates from the deployed config and wastes the wipe. Custom dimensions are exempt: they are created on first entry, so their winners apply without a wipe as long as nobody has entered them.

## The viewer

`./dev seed-viewer` is a Tailwind v4 page whose stylesheet and scripts live in `scripts/seed/web/` and are copied into `<seedtest>/assets/` at finalise time. **The built CSS is committed** — `web/build.sh` compiles it with the Tailwind standalone CLI (one executable, no Node, no npm) and consumers never run it. Nothing is fetched at runtime, so the viewer works offline and from a `file://` open of `.seedtest/index.html` as well as over `http://127.0.0.1:8765/`.

Tokens follow shadcn/ui's contract (`--background/--card/--primary/--border/--ring/--chart-N/--radius`). **Do not reuse a contract name in the legacy bridge**: `--border: var(--color-border)` is a cycle, because `@theme inline` defines `--color-border` as `var(--border)`, and CSS drops a cyclic custom property — every card silently fell back to `currentColor`. The legacy names are `--edge` / `--rule` / `--action` / `--r-*` for this reason.

What it shows beyond a grid of scores:

| Feature | What it answers |
| --- | --- |
| **Contribution bar** + four fixed component colours | What SHAPE is this candidate — two seeds can share a total and be nothing alike |
| **Relief chip** (3×3 from the sampled height/water grid) | Terrain shape for the majority of candidates whose real render has not been produced yet |
| **Per-component deltas** vs the current winner | The four subtractions nobody should do in their head |
| **Compare** (pick two tiles) | Both renders side by side with a weighted per-component diff and Use left / Use right |
| **All criteria** dartboard (`D` in the lightbox) | Every structure band drawn at once on the map, spawn at centre, coloured by pass/fail |
| **Scatter** (⁘ toggle) | The whole bank as one dataset — outliers, and which component actually discriminates |

Every score component owns one colour (`--chart-1..4` via `.comp-<key>`) used identically in its swatch, bar, contribution segment and delta. A component's colour is its name.

### The URL

The expanded dimension and the open candidate live in the PATH; filters, sort and search stay in the query string:

```
/                                        nothing open
/the-nether?family=nether                the_nether expanded, nether filter on
/the-nether/4412011349903857317          ...with that candidate's lightbox open
```

Underscores become hyphens (`the_nether` → `the-nether`); dimension names are `^[a-z][a-z0-9_]*$`, so the slug round-trips exactly. The sums are in `web/route.js` and nowhere else, `viewer-server.ViewerHandler._is_page_route` mirrors the same shape server-side (a real file on disk always wins, and `_API_ROOTS` keeps `/fork-schema` an endpoint), and `test_web_routing.py` pins both against one list of hand-worked cases.

Three things to know before touching it:

- **`viewer_template.html` injects `<base href="/">`** in an inline head script, before the stylesheet link. Without it the two-segment route resolves `assets/app.css` against `/the-nether/` and the page loads unstyled with no renders. It is skipped on `file://`, where `/` would mean the filesystem root.
- **Seeds are strings end to end.** They are signed 64-bit and routinely exceed 2^53; parsing one to a Number picks a different candidate.
- **A route that cannot be honoured is cleaned out of the address bar** rather than left lying: an unknown dimension, or a seed whose tile has been demoted out of the top ten by a re-rank. `applyRoute` is also what re-expands the card after the roller swaps `#grid` on a re-rank.

## Where the roller's state lives

Everything the roller derives is under `<consumer>/.seedtest/`, and nothing else. Not `data/` (the mc container's tree, deleted to reset a world) and not `.stack/<version>/` (an immutable release directory, replaced wholesale by `./dev update`).

| State | Path |
| --- | --- |
| Candidate bank | `.seedtest/candidates/{slug}.json` |
| Census positions (per candidate) | `.seedtest/census-positions/<dim>/<seed>.json.gz` (schemaVersion 2, noise fingerprint + poolHash stamps) |
| Live biome parameter table | `.seedtest/biome_params.json` (seeded from the bundle's read-only copy) |
| Structure sets | `.seedtest/.structure_sets/` |
| Warmup boot dir | `.seedtest/base/` |
| Renders | `.seedtest/renders/{slug}/` |
| Winners | `overlay/config/custom-dimensions/dimensions/{slug}.json` (committed) |

**The bank root is not derived — it is passed in.** Every entry point (`fast_roller.py`, `score-dimensions.py`, `viewer-server.py`, `biome_renderer.py batch`) must call `candidates.set_bank_root(<seedtest>)` **before the first read or write**, because `candidates_dir()` falls back to the legacy in-config location when no root is set. `fast_roller.main` sets it immediately after `parse_args()` — setting it later leaves `load_seen_seeds()` reading the legacy path and re-rolling already-rejected seeds forever. The fallback prints a `WARNING: candidate bank root not set` line to stderr — if you ever see it outside the unit tests, an entry point is missing the call and the bank is going somewhere you do not want it (`test_fast_roller.BankRootOrderingTests` guards the ordering).

## Traps

1. **The fingerprint corollary.** Any new generation-affecting config field must be added to `dimension_profiles.generation_payload()` or seed-group rolling silently lies — two dimensions sharing a fingerprint AND a seed are literal world clones sharing a doubly-measured world. Example: derived exit-shrine spacing makes `borders.player` generation-affecting for `exitShrines` dims, so the payload carries a conditional `shrineSpacing` key, added only when `exitShrines.enabled` and no explicit `structures.spacing` override exists for `adventure:exit_shrines` — an always-present key would DRIFT every candidate store at once, so non-shrine fingerprints stay byte-stable.
2. **Fingerprint drift is a warning, not an error, and only a re-roll fixes it.** `./dev seed-status` prints `DRIFTED (generation config changed since measurement — re-roll)` when a winner's stamped fingerprint no longer matches the dimension's current `generation_fingerprint()`. Rescoring cannot fix this — the banked measurements describe a world the config no longer generates. `STALE` (a plain config-hash mismatch, no fingerprint involved) is the milder case `seed-rescore` DOES fix for free. **Two stamp kinds:** `fingerprint` is written by `candidates.merge_rows` on the fold that supplies a candidate's measurements (never back-stamped — see the T22-style shell trap in its comment); candidates measured before stamping existed carry `fingerprintAssumed` instead, written once by `candidates.ensure_fingerprints` at the first roll/rescore fold to touch the store — the assumption cannot claim historical drift, but any generation-affecting change AFTER it reads as DRIFTED. Drift checks read `candidates.effective_fingerprint` (measured wins over assumed).
3. **Measurements never transfer across differing biome lists, even "similar" ones.** `generation_payload()` includes the FULL ordered biome list — one biome's difference re-deals the whole layout and re-keys the fingerprint. Same-or-nothing.
4. **`structures.wants` vs `seedRoll.wants` use different value formats — mixing them crashes Gson with "config invalid — skipped".** `structures.wants` is `{"short_name": {"min": N, "max": M}}` (absolute blocks, `Map<String, StructureWant>` server-side); `seedRoll.wants` is `{"short_name": "near_spawn"|"spread"|"near_border"}` (band-name strings, roller-only, free-form). Putting a band string in `structures.wants` or a `{min,max}` object in `seedRoll.wants` crashes the mod's Gson parser at boot. Same family of bug: `structures.shuns` must be MAP form (`{"village": {}}`); `seedRoll.shuns` accepts a bare list. This is duplicated from `custom-dimension-authoring` on purpose — the roller (and the viewer's fork-form validation in `viewer-server.py`) is where the crash actually surfaces.
5. **The roller reads the bundle and `overlay/`, never `data/`.** In a consumer, `roll-all.sh` and `dev`'s `resolve_seed_config` both resolve to `.stack/current/stack/config/custom-dimensions` plus `overlay/config/custom-dimensions` (exported as `SEED_OVERLAY_DIR`), and `dimension_profiles.monolith_from_dir` merges the overlay exactly as the mod does at boot. `data/` belongs to the mc container and is wiped to reset a world, "which is not an event the roller should even notice". So a dimension written into `overlay/config/custom-dimensions/dimensions/` is visible to the next roll immediately — no `./dev up` re-stage needed. Anything that edits `data/config/custom-dimensions/` instead is editing the server's copy and will not change a roll at all.
6. **`collective` must not be stripped from a seed-rolling warmup base dir.** 9+ mods depend on it; without it Fabric throws a `FormattedException` listing every missing dependent at boot. Check `roll-all.sh`'s `STRIP_PATTERNS` list before adding new mods to strip.
7. **DistantHorizons configs leak into seedtest dirs.** `roll-all.sh`'s `prepare_base_dir` already deletes `config/DistantHorizons` from the warmup base dir — if you build a custom warmup harness, copy this or expect 70+ map-loading warnings at boot.
8. **macOS: `od -An -td8` produces single-byte values, not 64-bit longs** — the `8` is a field-width argument, not a byte-size one. Use `-tu8` for unsigned, or `python3 -c "import os,struct; print(struct.unpack('<q', os.urandom(8))[0])"` for a signed 64-bit random seed (this is exactly what `fast_roller.random_seed()` and `score-dimensions.random_signed_seed()` do). **`grep -P` does not exist on macOS BSD grep** — use `grep -oE` instead.
9. **`LEVEL_TYPE=flat` on the overworld breaks structure placement in every custom dimension**, not just the flat one. The mod's `createDimensionOptions` templates off `overworldOpts`; a flat overworld makes that a `FlatChunkGenerator`, which fails the `instanceof NoiseChunkGenerator` check the `multi_biome` case (and others) rely on. Confirmed by A/B test (`AGENTS.md` platform traps). If every locate suddenly returns "Could not find" across the whole roll, check the overworld's `LEVEL_TYPE` before suspecting the roller.
10. **The dimension TYPE decides the noise family, not `profile["family"]`.** `build_profile()` reports family `overworld` for a custom dimension of type `paradise_lost:paradise_lost` — right for scoring, wrong for anything that samples or draws the world. Go through `biome_renderer.resolve_noise_family(dim_type, family)`, which mirrors `fast_roller._TYPE_NOISE_OVERRIDE`. The viewer's on-demand hi-res render keyed on family alone and drew every such dimension as an overworld world — oceans, badlands, cherry groves — next to a batch thumbnail of the correct skylands (guarded by `test_biome_pipeline.TestNoiseFamilyResolution`).
11. **`sample-noise` vs headless evaluation divergence is a solved mechanism, not a mystery.** Two causes, both mirrored: (a) value-identical noise params (Tectonic's `overlay.datapack` gives `minecraft:continentalness`/`erosion`/`ridge` the same params as its `tectonic:parameter/*` copies) make the DF tree's holders canonicalise, so the server seeds by the canonical `minecraft:` id — `preset_terrain.KNOWN_NOISE_ALIASES` seeds the same way, and every alias entry is verified by per-octave origin comparison (`customdim debug-prng`) before it may be added; (b) embedded resource-pack overlays override noise params the plain jar walk misses — `seed_worker`'s extraction applies `resourcepacks/*/overlay.*/data/` on top of base pack data. With both in place the mirror matches the live c2me-modded server at zero tolerance on the closed chains (`BIOME_PARITY_STRICT=1`); c2me introduces no climate delta there. `customdim eval-df` is the node-level oracle for walking any residual chain divergence.

## Validation — do not skip

```bash
./dev seed-status                          # counts, winner, freshness, DRIFTED warnings — no Docker needed
./dev seed-roll --dims <slug> --no-write   # measure + score without committing a winner
python3 -m pytest scripts/seed/            # the existing test suite (10 test modules)
```

The test suite exists — `test_biome_pipeline.py`, `test_biome_parity.py`, `test_dimension_profiles.py`, `test_fast_roller.py`, `test_map_renderer.py`, `test_passthrough_parity.py`, `test_preset_terrain.py`, `test_render_integration.py`, `test_score_dimensions.py`, `test_seed_worker.py`, `test_viewer_server.py`, `test_world_type_fidelity.py` — and changing any scoring logic in `dimension_profiles.py`, `fast_roller.py`, or `score-dimensions.py` means running it before trusting the change.

Shell-level verification scripts: `scripts/seed/verify-occupancy.sh` (bot harness for occupancy checks), `scripts/seed/migrate-structure-identity.sh` + `scripts/seed/verify-structure-identity.py` (one-time migration + gate), `scripts/seed/refresh-biome-fixtures.sh` + `test_biome_parity.py` (biome gate — fixtures committed under `testdata/biome_grid/`; the open residues fail only under `BIOME_PARITY_STRICT=1` and skip honestly otherwise).

## References

- `references/scoring-model.md` — mood weight tables, placement bands, density shifts, terrain targets, and how a `seedRoll` block maps to a score component by component.
- `references/seed-groups.md` — generation fingerprints, `generation_payload()` field-by-field, drift, and injective winner assignment within a group.
- `references/troubleshooting.md` — zero candidates, flat/wrong-family renders, wrong colours, warmup failures, expanded with the exact check commands.
- `.claude/skills/custom-dimension-authoring/SKILL.md` (`custom-dimension-authoring`) — the dimension JSON schema itself. Read this first if you haven't already; this skill assumes it.
