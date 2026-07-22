# Vanilla "Custom" world settings → per-dimension config support

Analysis of the Minecraft wiki "Custom" world-type page against what the
custom-dimensions mod (1.21.1) already does, what's cheap to add, and what
we should refuse. Grounded in the actual code: `DimensionConfig.Environment`,
`DimensionTypeBuilder`, `DimensionManager.createDimensionOptions`, and the
jar-baked `data/adventure/worldgen/` datapack.

**Health warning on the source**: that wiki page is 1.16-era. In 1.21.1:
the `altitude` biome parameter is gone (replaced by `continentalness`,
`erosion`, `depth`); `vanilla_layered` biome source is gone; the whole
`density_factor`/`top_slide` noise shape was replaced by density functions
in 1.18; and 23w05a removed the Import/Export Settings UI entirely — which
is irrelevant to us, because the mod registers everything at runtime and
never goes through that screen. Treat the page as a field checklist, not a
format reference. Current formats: misode.github.io/worldgen (1.21.1).

## Where we already are

Our `environment` block already covers MOST of the dimension-type surface,
with partial-override semantics (unset fields inherit the base type) and a
fall-back-don't-crash failure policy. Generator-side we have 10 types
(`void`, `superflat`, `single_biome`, `multi_biome`, `nether`, `end`,
`sky_islands`, `nether_islands`, `amplified`, `large_biomes`), per-dimension
seeds, biome lists, `noiseSettings` presets (jar-baked
`adventure:wide`/`compressed`/`void` from `gen-terrain-presets.py`), and
`structureDensity` (rescaled placement copies, registry never mutated).

## Support matrix

### Dimension type (`environment` block)

| Vanilla field | Status | Notes |
| --- | --- | --- |
| `ultrawarm` | ✅ have (`ultraWarm`) | |
| `natural` | ✅ have | |
| `has_skylight` | ✅ have (`hasSkylight`) | |
| `has_ceiling` | ✅ have (`hasCeiling`) | logical flag only, no real roof |
| `ambient_light` | ✅ have (`ambientLight`) | |
| `fixed_time` | ✅ have (`fixedTime`) | |
| `piglin_safe` | ✅ have (`piglinSafe`) | |
| `bed_works` | ✅ have (`bedWorks`) | |
| `respawn_anchor_works` | ✅ have (`respawnAnchorWorks`) | |
| `has_raids` | ✅ have (`hasRaids`) | |
| `min_y` / `height` / `logical_height` | ✅ have | validated (×16, ±2032, logicalHeight ≤ height); proven live with the y=500 oracle (Phase 4) |
| `coordinate_scale` | 🟡 easy | `DimensionTypeBuilder.build()` currently inherits `base.coordinateScale()`. Add `env.coordinateScale` (clamp 0.00001–30000000). **Trap**: our portals do their own scaling via `portal.scale` — setting BOTH double-applies for vanilla travel mechanics; document that `coordinate_scale` affects vanilla portal maths while `portal.scale` is ours, pick one per dimension. |
| `effects` | 🟡 easy, high value | One of `minecraft:overworld`/`the_nether`/`the_end`. Server-registrable; the client renders the matching skybox/fog. This is the legit replacement for the ignored `skyColor`/`fogColor` fields — and it should feed the map shell's per-family background too. Custom effect ids need a client mod: refuse. |
| `infiniburn` | 🟡 easy | String block-tag id → `TagKey.of(RegistryKeys.BLOCK, ...)`. No existence validation possible for tags at parse time — document “typo = nothing burns forever”, fall back to base on malformed id. |
| `monster_spawn_light_level` | 🟡 easy | Accept an int (→ `ConstantIntProvider`) or `[min,max]` (→ uniform). Nice synergy with the difficulty system — e.g. gauntlet-style dims where mobs spawn at light 7 (pre-1.18 rules) or 15 (everywhere). |
| `monster_spawn_block_light_limit` | 🟡 easy | Plain int 0–15. |
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
| `type: minecraft:flat` + custom `layers` | 🟡 easy | `superflat` exists but layers are fixed. Add optional `layers: [{block, height}]` + `flatBiome` to the config → `FlatChunkGeneratorConfig`. Remember the AGENTS trap: a flat OVERWORLD breaks `multi_biome` structure placement in every custom dim (template-generator fallthrough) — flat *custom dims* are fine. |
| `type: minecraft:debug` | ❌ won't | no gameplay value, chunk-render hostile, and `rollable()` would need excluding. Trivial if ever wanted; don't. |
| `settings:` preset id | ✅ have | vanilla ids + `adventure:*` jar presets |
| `settings:` inline noise-settings object | ❌ won't (inline) → 🟢 route exists | Arbitrary inline 1.21.1 noise settings (density functions, noise router, surface rules) are a validation and support nightmare, break seed-roll scoring assumptions, and interact with the c2me DFC patch. The supported path is the one we already run: author JSON under `mods/custom-dimensions/src/main/resources/data/adventure/worldgen/noise_settings/` (generated by `gen-terrain-presets.py`), rebuild the jar, reference by id. If demand grows: a `settingsOverrides` whitelist (`sea_level`, `default_block`, `default_fluid`, `disable_mob_generation`) that clones a preset's `ChunkGeneratorSettings` record with those fields swapped — medium effort, safe surface. |
| `biome_source: multi_noise` (biomes + parameter intervals) | 🟡 medium | We build multi-noise sources from biome LISTS with vanilla parameters. Custom per-biome `parameters` intervals (temperature/humidity/continentalness/erosion/depth/weirdness/offset) would give real biome-layout control: extend `biomes` entries to optionally be `{id, parameters:{...}}` and construct `MultiNoiseBiomeSource` from explicit entries. Medium because the seed roller's `biome_params.json` sampling must honour the same overrides or scoring lies. |
| `biome_source: fixed` | ✅ have | `single_biome` |
| `biome_source: checkerboard` | 🟡 easy | `CheckerboardBiomeSource` + optional `scale` (0–62). ~20 lines as a new `checkerboard` case; fun for puzzle/pocket dims. Roller support: biome sampler needs a checkerboard mode (cheap, it's deterministic geometry). |
| `biome_source: the_end` | ✅ have | `end` type |
| `structures` per-structure `spacing`/`separation`/`salt` | 🟡 medium | `DimensionStructures` already rebuilds the placement calculator with rescaled UNREGISTERED copies — extending `structureDensity` with an optional per-structure map `{"minecraft:village": {spacing, separation}}` slots straight into that machinery. Skip `salt` overrides (footgun, zero value for us). |

## Mod changes required (by tier)

**Tier 1 — config plumbing only (one small PR):**
`Environment` gains `coordinateScale`, `effects`, `infiniburn`,
`monsterSpawnLightLevel`, `monsterSpawnBlockLightLimit`;
`DimensionTypeBuilder.build()` stops inheriting those five from base when
set. Validation: clamp ranges, whitelist the three effects ids, malformed →
warn + base type (existing policy). Tests mirror the Phase 4 height oracle:
boot a fixture dim with `effects: the_nether` + `monster_spawn_light_level: 15`
and assert via `/execute in ... run` probes + registry dump.

**Tier 2 — generator additions (independent, small):**
`checkerboard` case; `superflat` custom `layers`. Each needs the matching
seed-roll touch (`rollable()`, biome sampler) or an explicit
`seedRoll: {skip: true}` default so the roller doesn't waste cycles.

**Tier 3 — the deep end (only on demand):**
per-biome multi-noise `parameters`, `settingsOverrides` whitelist,
per-structure spacing overrides. Each is genuinely useful but each must
land TOGETHER with its Python-roller counterpart, or candidate scores stop
describing the worlds we actually generate.

## Cross-cutting gotchas

1. **Existing chunks don't migrate.** Type fields (light, effects, raids…)
   apply instantly on next boot, but `min_y`/`height` changes against a
   dimension with existing region files leave lighting/heightmap artefacts —
   same class of trap as per-dimension seeds. Fine pre-wipe; document as
   creation-time-ish.
2. **Registry sync timing.** Types must register before any client logs in
   (already handled — boot-time registration). Tier 1 fields ride the same
   entry, no new sync work.
3. **c2me DFC** stays force-disabled; nothing here changes that, but any
   `settingsOverrides` work must re-run the two-dims locate oracle.
4. **Roller parity is the real cost.** Every generator-affecting knob that
   the Python pipeline can't model degrades candidate scoring silently.
   That's why Tier 3 is gated, and why Tier 1 (pure dimension-type fields,
   zero worldgen impact) is the easy win.
5. **`environment` overlay semantics** already give consumers per-dim
   overrides (`"overrides"` deep-merge) — new fields inherit that for free.

## Precision placement — beyond rolling the dice

Question: since the mod owns the dimension pipeline, can we PLACE biomes and
structures instead of rolling seeds until they land well? Yes — and it
inverts the roller's job from "search for luck" to "verify constraints".

### Biome patches (medium effort, huge payoff)

A biome source is a pure function (x, y, z) → biome. The mod already swaps
sources per dimension, so it can WRAP any source with an override layer:

    "biomePatches": [
      { "biome": "minecraft:cherry_grove", "x": 0, "z": 0, "radius": 96 },
      { "biome": "terralith:moonlight_grove", "x": 1500, "z": -800, "radius": 200 }
    ]

Delegate to the wrapped source everywhere except inside patches. Effects:
correct surface rules, features, mob spawns, grass/water tint inside the
patch; multi-noise generation everywhere else. The killer app is a
**guaranteed spawn biome at (0,0)** — which deletes the spawn-filter
lottery (the 0.1–0.5% acceptance-rate problem from 2026-07-17) entirely.
Caveats: 1.18+ terrain SHAPE is mostly biome-independent (density
functions), so a desert patch in mountains is a sandy mountain — patch
radius should respect terrain mood; blend the edge (1–2 chunk noise jitter
on the boundary) or patches look stamped.

### Fixed structures (two routes)

1. **Post-gen `/place structure`** (cheap, ships tomorrow): deploy.sh's
   one-time dimension setup already forceloads a chunk — extend it to run
   `execute in <dim> run place structure minecraft:ancient_city X Y Z`
   from a config list. Baked into chunks, survives forever, zero runtime
   cost. Limitations: placement is "as generated at that spot" (no terrain
   adaptation beyond the structure's own rules), and it's creation-time
   only (marker-gated like the rest of one-time setup).
2. **Custom StructurePlacement type** (proper, medium): register a
   `customdimensions:fixed` placement that returns exact chunk positions
   from config. `DimensionStructures` already rebuilds each world's
   placement calculator with unregistered copies — injecting synthetic
   placements is the same machinery. This gets real generation-time
   placement (terrain adaptation, locate support, maps) and composes with
   structureDensity.

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

So yes: hyper-customisable is genuinely reachable — "multi-biome world,
cherry grove at spawn, ancient city at 800 north, sky islands preset" is
all config once biomePatches + fixed structures land. The discipline that
keeps it honest: every placement feature must ALSO land in the Python
pipeline (sampler honours patches, locate oracle knows fixed structures),
and the roller's role shifts to scoring the organic remainder — which
makes rolls CHEAPER (fewer constraints to luck into), not obsolete.
