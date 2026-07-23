# Vanilla "Custom" world settings → per-dimension config support

> **Status (2026-07-23): Tiers 1–3 SHIPPED.** Tier 1: `coordinateScale`, `effects`, `infiniburn`, `monsterSpawnLightLevel`, `monsterSpawnBlockLightLimit`. Tier 2: `checkerboard` type (+`checkerboardScale`), superflat `layers`/`flatBiome`, `seedRoll: {skip: true}` (added to both the mod schema and `rollable()` — it previously existed nowhere); `CheckerboardBiomeSampler` parity live-verified probe-for-probe. Tier 3: `settingsOverrides` (seaLevel/defaultBlock/defaultFluid/disableMobGeneration via ChunkGeneratorSettings record clone), per-biome `parameters` (`{id, parameters}` biomes entries → explicit hypercubes in `buildMixedSource`, mirrored in `build_mixed_entries`), per-set `structures.spacing` (explicit values through the `DimensionStructures` rebuild, mirrored in tier-1 roller maths; live-verified: villages 64 blocks apart under spacing 5). Multi-noise parity is region-level, not probe-exact — the pure-Python climate sampler is a screening approximation (server measurement remains ground truth for finalists). Only the precision-placement section (biomePatches, fixed structures) remains the open roadmap; the 2026-07-22 handoff sketches are merged in below.

Analysis of the Minecraft wiki "Custom" world-type page against what the custom-dimensions mod (1.21.1) already does, what's cheap to add, and what we should refuse. Grounded in the actual code: `DimensionConfig.Environment`, `DimensionTypeBuilder`, `DimensionManager.createDimensionOptions`, and the jar-baked `data/adventure/worldgen/` datapack.

**Health warning on the source**: that wiki page is 1.16-era. In 1.21.1: the `altitude` biome parameter is gone (replaced by `continentalness`, `erosion`, `depth`); `vanilla_layered` biome source is gone; the whole `density_factor`/`top_slide` noise shape was replaced by density functions in 1.18; and 23w05a removed the Import/Export Settings UI entirely — which is irrelevant to us, because the mod registers everything at runtime and never goes through that screen. Treat the page as a field checklist, not a format reference. Current formats: misode.github.io/worldgen (1.21.1).

## Where we already are

Our `environment` block already covers MOST of the dimension-type surface, with partial-override semantics (unset fields inherit the base type) and a fall-back-don't-crash failure policy. Generator-side we have 10 types (`void`, `superflat`, `single_biome`, `multi_biome`, `nether`, `end`, `sky_islands`, `nether_islands`, `amplified`, `large_biomes`), per-dimension seeds, biome lists, `noiseSettings` presets (jar-baked `adventure:wide`/`compressed`/`void` from `gen-terrain-presets.py`), and `structureDensity` (rescaled placement copies, registry never mutated).

## Support matrix

### Dimension type (`environment` block)

| Vanilla field | Status | Notes |
| --- | --- | --- |
| `ultrawarm` | ✅ have (`ultraWarm`) |  |
| `natural` | ✅ have |  |
| `has_skylight` | ✅ have (`hasSkylight`) |  |
| `has_ceiling` | ✅ have (`hasCeiling`) | logical flag only, no real roof |
| `ambient_light` | ✅ have (`ambientLight`) |  |
| `fixed_time` | ✅ have (`fixedTime`) |  |
| `piglin_safe` | ✅ have (`piglinSafe`) |  |
| `bed_works` | ✅ have (`bedWorks`) |  |
| `respawn_anchor_works` | ✅ have (`respawnAnchorWorks`) |  |
| `has_raids` | ✅ have (`hasRaids`) |  |
| `min_y` / `height` / `logical_height` | ✅ have | validated (×16, ±2032, logicalHeight ≤ height); proven live with the y=500 oracle (Phase 4) |
| `coordinate_scale` | ✅ have (Tier 1) | `DimensionTypeBuilder.build()` currently inherits `base.coordinateScale()`. Add `env.coordinateScale` (clamp 0.00001–30000000). **Trap**: our portals do their own scaling via `portal.scale` — setting BOTH double-applies for vanilla travel mechanics; document that `coordinate_scale` affects vanilla portal maths while `portal.scale` is ours, pick one per dimension. |
| `effects` | ✅ have (Tier 1) | One of `minecraft:overworld`/`the_nether`/`the_end`. Server-registrable; the client renders the matching skybox/fog. This is the legit replacement for the ignored `skyColor`/`fogColor` fields — and it should feed the map shell's per-family background too. Custom effect ids need a client mod: refuse. |
| `infiniburn` | ✅ have (Tier 1) | String block-tag id → `TagKey.of(RegistryKeys.BLOCK, ...)`. No existence validation possible for tags at parse time — document “typo = nothing burns forever”, fall back to base on malformed id. |
| `monster_spawn_light_level` | ✅ have (Tier 1) | Accept an int (→ `ConstantIntProvider`) or `[min,max]` (→ uniform). Nice synergy with the difficulty system — e.g. gauntlet-style dims where mobs spawn at light 7 (pre-1.18 rules) or 15 (everywhere). |
| `monster_spawn_block_light_limit` | ✅ have (Tier 1) | Plain int 0–15. |
| `skyColor` / `fogColor` | ❌ won't (as-is) | Client rendering; already ignored with a log line. `effects` (above) is the supported 3-flavour version. Full custom colours would need biome `special_effects` overrides — see biome section. |

### Root-level Custom world fields

| Vanilla field | Status | Notes |
| --- | --- | --- |
| `seed` | ✅ have | per-dimension `seed` + worldSeed; c2me DFC patch is the standing caveat |
| `generate_features` | ✅ effectively | `structureDensity` whole-set drops + `peaceful` overlay cover the useful semantics per dimension |
| `bonus_chest` | ❌ N/A | world-creation-screen concern; meaningless for runtime dimensions |
| `legacy_custom_options` | ❌ N/A | pre-1.13 relic |

### Generator

| Vanilla concept | Status | Notes |
| --- | --- | --- |
| `type: minecraft:noise` | ✅ have | all ten of our types resolve to NoiseChunkGenerator variants |
| `type: minecraft:flat` + custom `layers` | ✅ have (Tier 2) | `layers: [{block, height}]` (bottom-up, vanilla semantics) + `flatBiome`; any invalid entry → warn + the whole default stack. The AGENTS trap still stands: a flat OVERWORLD breaks `multi_biome` structure placement in every custom dim — flat _custom dims_ are fine. Structures/features still generate on flat terrain (vanilla behaviour). |
| `type: minecraft:debug` | ❌ won't | no gameplay value, chunk-render hostile, and `rollable()` would need excluding. Trivial if ever wanted; don't. |
| `settings:` preset id | ✅ have | vanilla ids + `adventure:*` jar presets |
| `settings:` inline noise-settings object | ❌ won't (inline) → 🟢 route exists | Arbitrary inline 1.21.1 noise settings (density functions, noise router, surface rules) are a validation and support nightmare, break seed-roll scoring assumptions, and interact with the c2me DFC patch. The supported path is the one we already run: author JSON under `mods/custom-dimensions/src/main/resources/data/adventure/worldgen/noise_settings/` (generated by `gen-terrain-presets.py`), rebuild the jar, reference by id. If demand grows: a `settingsOverrides` whitelist (`sea_level`, `default_block`, `default_fluid`, `disable_mob_generation`) that clones a preset's `ChunkGeneratorSettings` record with those fields swapped — medium effort, safe surface. |
| `biome_source: multi_noise` (biomes + parameter intervals) | 🟡 medium | We build multi-noise sources from biome LISTS with vanilla parameters. Custom per-biome `parameters` intervals (temperature/humidity/continentalness/erosion/depth/weirdness/offset) would give real biome-layout control: extend `biomes` entries to optionally be `{id, parameters:{...}}` and construct `MultiNoiseBiomeSource` from explicit entries. Medium because the seed roller's `biome_params.json` sampling must honour the same overrides or scoring lies. |
| `biome_source: fixed` | ✅ have | `single_biome` |
| `biome_source: checkerboard` | ✅ have (Tier 2) | `type: "checkerboard"` + `checkerboardScale` (0–62, default 2). Grid formula (quart coords): `floorMod((qx >> s+2) + (qz >> s+2), n)` — mirrored in `CheckerboardBiomeSampler` (biome_sampler.py), live-verified probe-for-probe including negative coords. Layout is seed-independent; terrain/structures still roll. |
| `biome_source: the_end` | ✅ have | `end` type |
| `structures` per-structure `spacing`/`separation`/`salt` | 🟡 medium | `DimensionStructures` already rebuilds the placement calculator with rescaled UNREGISTERED copies — extending `structureDensity` with an optional per-structure map `{"minecraft:village": {spacing, separation}}` slots straight into that machinery. Skip `salt` overrides (footgun, zero value for us). |

## Mod changes required (by tier)

**Tier 1 — config plumbing only — ✅ SHIPPED:** `Environment` gains `coordinateScale`, `effects`, `infiniburn`, `monsterSpawnLightLevel`, `monsterSpawnBlockLightLimit`; `DimensionTypeBuilder.build()` stops inheriting those five from base when set. Validation: clamp ranges, whitelist the three effects ids, malformed → warn + base type (existing policy). Tests mirror the Phase 4 height oracle: boot a fixture dim with `effects: the_nether` + `monster_spawn_light_level: 15` and assert via `/execute in ... run` probes + registry dump.

**Tier 2 — generator additions — ✅ SHIPPED:** `checkerboard` case (+`checkerboardScale`, rollable, `CheckerboardBiomeSampler` parity); `superflat` custom `layers` + `flatBiome` (roller keeps skipping superflat); `seedRoll: {skip: true}` honoured as the first check in `rollable()`. New creation-time fields joined the fingerprint drift detection (old records compare clean — no false drift warns).

**Tier 3 — the deep end (only on demand):** per-biome multi-noise `parameters`, `settingsOverrides` whitelist, per-structure spacing overrides. Each is genuinely useful but each must land TOGETHER with its Python-roller counterpart, or candidate scores stop describing the worlds we actually generate.

## Cross-cutting gotchas

1. **Existing chunks don't migrate.** Type fields (light, effects, raids…) apply instantly on next boot, but `min_y`/`height` changes against a dimension with existing region files leave lighting/heightmap artefacts — same class of trap as per-dimension seeds. Fine pre-wipe; document as creation-time-ish.
2. **Registry sync timing.** Types must register before any client logs in (already handled — boot-time registration). Tier 1 fields ride the same entry, no new sync work.
3. **c2me DFC** stays force-disabled; nothing here changes that, but any `settingsOverrides` work must re-run the two-dims locate oracle.
4. **Roller parity is the real cost.** Every generator-affecting knob that the Python pipeline can't model degrades candidate scoring silently. That's why Tier 3 is gated, and why Tier 1 (pure dimension-type fields, zero worldgen impact) is the easy win.
5. **`environment` overlay semantics** already give consumers per-dim overrides (`"overrides"` deep-merge) — new fields inherit that for free.

## Precision placement — beyond rolling the dice

Question: since the mod owns the dimension pipeline, can we PLACE biomes and structures instead of rolling seeds until they land well? Yes — and it inverts the roller's job from "search for luck" to "verify constraints".

### Biome patches (medium effort, huge payoff)

A biome source is a pure function (x, y, z) → biome. The mod already swaps sources per dimension, so it can WRAP any source with an override layer:

    "biomePatches": [
      { "biome": "minecraft:cherry_grove", "x": 0, "z": 0, "radius": 96 },
      { "biome": "terralith:moonlight_grove", "x": 1500, "z": -800, "radius": 200 }
    ]

Delegate to the wrapped source everywhere except inside patches. Effects: correct surface rules, features, mob spawns, grass/water tint inside the patch; multi-noise generation everywhere else. The killer app is a **guaranteed spawn biome at (0,0)** — which deletes the spawn-filter lottery (the 0.1–0.5% acceptance-rate problem from 2026-07-17) entirely. Caveats: 1.18+ terrain SHAPE is mostly biome-independent (density functions), so a desert patch in mountains is a sandy mountain — patch radius should respect terrain mood; blend the edge (1–2 chunk noise jitter on the boundary) or patches look stamped.

Implementation notes (2026-07-22 handoff): a `PatchedBiomeSource extends BiomeSource` wrapping (delegate, patches) — `getBiomes()` is the union, `getBiomeForNoiseGen(x,y,z)` answers from a patch when inside one, else delegates. Biome-source coordinates are QUARTS (block >> 2), so convert the radius; `CODEC` is a required abstract in 1.21.1 — a codec that round-trips delegate + patch list server-side is all it needs. Natural home: `DimensionManager.createDimensionOptions` (every generator case builds its source there — wrap the result when patches are configured). Pipeline parity is part of the same change, not a follow-up: `scripts/seed/biome_sampler.py` applies the same override before scoring. Oracle: fixture dim, cherry_grove patch at (0,0) — `execute in <dim> run locate biome minecraft:cherry_grove` returns ~0; a probe outside the radius returns the base source's biome.

### Fixed structures (two routes)

1. **Post-gen `/place structure`** (cheap, ships tomorrow): deploy.sh's one-time dimension setup already forceloads a chunk — extend it to run `execute in <dim> run place structure minecraft:ancient_city X Y Z` from a config list. Baked into chunks, survives forever, zero runtime cost. Limitations: placement is "as generated at that spot" (no terrain adaptation beyond the structure's own rules), and it's creation-time only (marker-gated like the rest of one-time setup).
2. **Custom StructurePlacement type** (proper, medium): register a `customdimensions:fixed` placement that returns exact chunk positions from config. `DimensionStructures` already rebuilds each world's placement calculator with unregistered copies — injecting synthetic placements is the same machinery. This gets real generation-time placement (terrain adaptation, locate support, maps) and composes with structureDensity.

   Implementation notes (2026-07-22 handoff): register the placement type at mod init; inject synthetic (structure set → fixed placement) pairs during the rebuild. Access points are `StructurePlacementAccessor` and `StructurePlacementCalculatorInvoker` — the invoker exists because the public Stream create() zeroes concentric-ring seeds, so the private ctor is the one to use. A `"structures": {"mode": allow|reject|none, "list": [...], "force": [{structure, x, z}]}` shape must coexist with the existing seed-roll `Structures` class (wants/shuns) — read `c89e1e1` first. "none" is a whole-set drop (structureDensity already does these); the peaceful overlay drops sets through a parallel path — unifying them while in there leaves things tidier. Pipeline parity: `scripts/seed/structure_placement.py` treats filtered sets as absent and forced structures as constants (known distance, guaranteed scoring hits) — rolls then only hunt the organic remainder. Oracle: fixture dim forcing an end_city near spawn — `locate structure` returns the configured spot; with `"mode": "none"` every locate is "Could not find".

### How deep does it go?

Combining the above with what already exists:

| Layer | Mechanism | Status |
| --- | --- | --- |
| Heights, light, effects, spawn rules | environment block | ✅ + Tier 1 |
| Terrain character | noiseSettings jar presets | ✅ (gen-terrain-presets.py) |
| Biome mix | biomes list / multi_noise | ✅ |
| Exact biome at exact spot | biomePatches wrapper | 🟡 medium |
| Structure density/themes | structureDensity | ✅ |
| Exact structure at exact spot | /place (v1) or fixed placement (v2) | 🟡 easy / medium |
| Guaranteed spawn biome | biomePatches at 0,0 | 🟡 falls out |
| Custom biomes (own colours/features) | jar-baked worldgen/biome JSON | 🟡 medium, client-visible tints work |
| Terrain shape at exact spots | authored density functions | 🔴 hard, real worldgen authoring |
| Custom skyboxes beyond the 3 vanilla effects | — | ❌ client mod territory |

So yes: hyper-customisable is genuinely reachable — "multi-biome world, cherry grove at spawn, ancient city at 800 north, sky islands preset" is all config once biomePatches + fixed structures land. The discipline that keeps it honest: every placement feature must ALSO land in the Python pipeline (sampler honours patches, locate oracle knows fixed structures), and the roller's role shifts to scoring the organic remainder — which makes rolls CHEAPER (fewer constraints to luck into), not obsolete.
