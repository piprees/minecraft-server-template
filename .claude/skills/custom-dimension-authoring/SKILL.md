---
name: custom-dimension-authoring
description: Author or edit a custom-dimensions Minecraft dimension config (config/custom-dimensions/dimensions/<slug>.json for platform defaults, overlay/config/custom-dimensions/dimensions/<slug>.json for consumer overrides) from a short theme prompt. Use whenever asked to create a new dimension, reskin/rebalance an existing one, tune its difficulty or portal, or troubleshoot why a dimension config won't boot or won't roll seeds. Covers the full JSON schema, the mood/difficulty scoring philosophy, structure wants/shuns, portal setup, and the seed-rolling validation workflow. Always consult this before hand-writing or hand-editing a dimension JSON file — the schema has silent-failure traps that are easy to get wrong from general Minecraft/JSON knowledge alone.
---

# Custom Dimension Authoring

You are writing a config file for the **custom-dimensions** Fabric mod (platform repo: `minecraft-server-template`, consumer repos like `elfydd`). Given a short prompt describing a dimension's theme and difficulty, your job is to produce a single JSON file that is valid, boots without errors, and can have a seed rolled and scored for it.

## MANDATORY: read the references before writing anything

This skill has five reference files. **You must read ALL of them before writing any JSON.** They contain the exact valid values for every field — biome ids, structure short names, mood weights, type/noise combinations. Guessing any of these values risks a silent failure (the mod won't crash — it'll just silently drop your biome, ignore your structure, or fall back to defaults, and the dimension will be wrong with no error message).

| File | What it contains | Why you need it |
| --- | --- | --- |
| `references/schema-reference.md` | Full field-by-field JSON schema, every key's type, timing (creation-time vs boot-re-read), and valid values | Know what you can write |
| `references/scoring-internals.md` | Mood weight tables, placement bands, difficulty derivation, seed-bank keying | Know how the roller will judge your config |
| `references/structure-names.md` | All 130+ valid structure short names, grouped by theme | Pick the right structures without inventing names |
| `references/biome-catalogue.md` | Every installed biome id grouped by family (overworld/nether/end/paradise_lost) and namespace — **the only biome ids that will actually work** | Pick biomes that exist and belong to the right family |
| `references/portals-and-exits.md` | Aura policy, immersive portals, exit portals and shrines, single-use and anchored portals, exit conditions | Get a player in and back out again |

**Also read 2-3 existing dimension configs** from `config/custom-dimensions/dimensions/`, with a similar size/mood to the one you're building. Good anchors:

- `the_gauntlet.json` — small (2048), brutal, `multi_biome`, compressed noise
- `the_wuthering_wisteria.json` — pocket (256), peaceful, `multi_biome`
- `the_blossom_gardens.json` — large (8192), peaceful, `multi_biome`, wide noise
- `the_basalt_spires.json` — pocket (1024), nether type
- `the_end_citadel.json` — medium (4096), end type
- `overworld.json` — an ordinary dimension like any other; names no `type` because `getType()` supplies the family

**And always check blocks, structures, and items against the json files inside the extractors** (`config/custom-dimensions/extractors/`) — the mod silently ignores unknown ids. These catalogues are authoritative; don't guess ids from memory or the wiki, just choose from the lists.

| File | What it contains | Why you need it |
| --- | --- | --- |
| `config/custom-dimensions/extractors/biomes.json` | All installed biome ids extracted from mods | Pick biomes that exist and belong to the right family |
| `config/custom-dimensions/extractors/blocks.json` | All installed block ids | Pick blocks that exist and are valid for portal frames, aura palettes, etc. |
| `config/custom-dimensions/extractors/entities.json` | All installed entity ids | Pick mobs that exist and are valid for spawn filters, etc. |
| `config/custom-dimensions/extractors/structures.json` | All installed structure short names | Pick structures that exist and are valid for `structures.wants`/`structures.shuns` |
| `config/custom-dimensions/extractors/registries.json` | The live registries: biomes, every biome tag with its resolved membership, each structure's step, terrain adaptation and valid-biome tag, each set's placement | The only file that resolves a tag. The four above are jar scans, so `c:is_overworld` and every other `c:*` tag is unanswerable from them |

Real shipped files are more reliable than any description of the schema, including this one.

## Where the file goes

```
config/custom-dimensions/dimensions/<slug>.json          # platform default
overlay/config/custom-dimensions/dimensions/<slug>.json  # consumer override
```

- The **filename is the dimension id** — never set `dimensionId` in the JSON.
- `<slug>` must be lowercase alphanumeric with `_`/`-`/`/`. Convention: `the_<name>` (83 of 84 existing dims follow it).
- **Namespace**: if `<slug>` matches a platform-shipped filename, it keeps `adventure:<slug>`. A genuinely new slug gets `{BRAND_SLUG}:<slug>` (e.g. `elfydd:<slug>`). Check with: `ls config/custom-dimensions/dimensions/ | grep -i <slug>`.
- **Overlay rules**: no `"overrides"` key = full replacement. `"overrides": {...}` = deep-merge over platform default. Empty `{}` = dimension disabled.

## The workflow

1. **Read all four reference files** (above). Do not skip this.
2. **Pick the shape**: `type`, target `mood`, playable size (`borders.player`), and `portal.scale`. See [Dimension type guide](#dimension-type-guide) and [Portal scale guide](#portal-scale-guide).
3. **Check for name/namespace collisions** and pick `<slug>`.
4. **Pick biomes** from `references/biome-catalogue.md`. Only use ids listed there — anything else is silently dropped. Match biomes to the dimension's family (see type guide). For `multi_biome`, list 8-20 biomes.
5. **Set `borders`, `difficulty`, `structureDensity`** per the [size↔difficulty table](#size--difficulty-the-philosophy).
6. **Set `structures.wants`/`structures.shuns`** using short names from `references/structure-names.md`. See [Traps](#traps-read-this-before-you-write-json) — the two blocks (`structures` vs `seedRoll`) use different value formats.
7. **Set `portal`** — frame block, igniter, colour/particle, sounds, scale. See [Portal scale guide](#portal-scale-guide). Check `igniterItem` uniqueness: `grep -h igniterItem config/custom-dimensions/dimensions/*.json | sort`.
8. **Set `seedRoll`** — `mood`, `spawnFilter` (3-8 biomes, all must appear in your `biomes` list AND exist in `biome-catalogue.md` for that family).
9. **Set `spawn`** — `[0, 64, 0]` (roller overwrites this).
10. **Validate** — see [Validation](#validation-do-not-skip-this).

## Dimension type guide

Every dimension needs a `type`. This is the most consequential choice — it determines the terrain generator, which biome families are valid, and what the world feels like. All types are **creation-time-only** (changing after world creation has no effect without a full world wipe).

| Type | When to use | Biome family | Terrain feel | Shipped count |
| --- | --- | --- | --- | --- |
| `multi_biome` | **Default choice for themed dims.** Curated biome subset over overworld noise. | overworld | Standard overworld terrain filtered to your biomes | 11 |
| `overworld` | Full overworld with ALL biomes (not curated). Use when the theme is "the whole world but with different scoring/difficulty" — NOT for themed subsets (use `multi_biome` instead). | overworld | Full overworld, every biome can appear | 24 |
| `nether` | Nether cave terrain. Only nether-family biomes work (`minecraft:*_forest`, `incendium:*`, etc). | nether | Bedrock ceiling, lava sea, cave-based | 18 |
| `end` | End terrain — islands over void. Only end-family biomes work. | end | Floating islands, void between them | 6 |
| `cave` | Cave world with overworld climate but a bedrock ceiling. | overworld | Underground feel, overworld biomes | 4 |
| `void` | No terrain at all. Biome layout still drives mob spawning, ambient sounds, and fog colour — variety matters even though nothing generates. Must have a `biomes` list to be rollable. | any (but biomes must match a single family for the roller — don't mix overworld + end biomes) | Empty void | 2 |
| `sky_islands` | Floating islands. | overworld | Islands in the sky, void below | 2 |
| `nether_islands` | End-island-style bones with nether biomes. | nether | Floating nether islands | 2 |
| `amplified` | Amplified terrain (extreme heights). **IGNORES `biomes` — see below.** | n/a | Very tall, dramatic | 1 |
| `large_biomes` | Large biomes (biome regions 4× bigger). **IGNORES `biomes` — see below.** | n/a | Huge biome regions | 1 |
| `superflat` | Flat world. Never rollable. | — | Flat | 1 |
| `paradise_lost:paradise_lost` | Clone of the Paradise Lost skylands dimension. | paradise_lost | Floating skylands | 6 |
| `single_biome` | One biome, `biomes` must have exactly 1 entry. | overworld | Single biome terrain | 0 |
| `checkerboard` | Deterministic biome grid, overworld noise. | overworld | Biome checkerboard | 0 |

**Common mistake: using `overworld` when you mean `multi_biome`.** `overworld` uses ALL registered biomes — your `biomes` list only affects what the roller scores, not what generates. If you want a "jungle-only dimension" or "frozen peaks dimension", use `multi_biome`. The original `the_overgrowth` had `type: "overworld"` with a jungle biome list, which meant every overworld biome could appear (deserts, oceans, etc.) — only the roller cared about the jungle list, not the generator.

**Void dimensions: keep biomes from ONE family.** A void dim with `minecraft:deep_dark` (overworld) AND `minecraft:the_end` (end) will confuse the roller — it can't determine which family's noise config to use for sampling. If you want an end-themed void, use only end-family biomes from `references/biome-catalogue.md`.

## sky_islands and nether_islands inherit the End's origin island

Both build from `endGen.getSettings()`, so they carry Nullscape's End noise
router — origin island and void moat included. A large one reads as an End
knock-off wearing the wrong biomes, and `settingsOverrides`'
`defaultBlock`/`seaLevel` cannot change it because the island lives in the
noise router.

Add `"settingsOverrides": {"endIsland": false}` unless the dimension is small
enough to sit inside the island, in which case the island IS the world and the
flag would hollow it out — `the_starwell` at a 256 border is the shipped
example of that. Roughly: past a 1024 border you want the flag.

Full detail: [TROUBLESHOOTING.md#t36](../../../TROUBLESHOOTING.md#t36).

## Two types discard the biome list

`amplified` and `large_biomes` clone the world preset's overworld
`DimensionOptions` wholesale and never call `resolveListedSource`
(`DimensionManager.java:729-757`). A biome list on either generates a plain
overworld, and a `seedRoll.spawnFilter` naming a biome that cannot occur
rejects every candidate — an empty board with no error.

The two shipped users, `the_amplified_reaches` and `the_endless_expanse`, both
carry an empty `biomes` array. That is not an oversight; it is the only honest
config for these types.

**Want amplified relief with a curated palette?** Use `multi_biome` with
`noiseSettings: "adventure:compressed"`. It composes with a biome list, which
amplified does not.

## Cross-family biomes and surface composition

A biome from another family generates on THIS dimension's terrain wearing its
OWN family's surface blocks. A nether biome in a `multi_biome` world comes out
as nylium and basalt on overworld terrain, and brings its mob spawns with it.
That is deliberate, and it is the pack's strongest lever.

**`sky_islands` has THREE "which family" questions with THREE different
answers.** Reading one and applying it to another is how this gets inverted:

| question | `sky_islands` answer | decided by |
| --- | --- | --- |
| which base BIOME SOURCE | **overworld** | `resolveListedSource(def, reg, overworldOpts, overworldOpts)` — both arguments |
| which terrain SETTINGS | **end** | `endGen.getSettings()` |
| which SURFACE RULE host | **end** | `BiomeFamilies.surfaceHostFamily` |

So "sky_islands reports end" is true of the surface rule and false of the biome
source. Overworld biomes ARE native in a `sky_islands` dimension and place
themselves; they are simultaneously foreign for surface composition and do get
dressed. Both are correct at once.

**Get the direction right — three agents have inverted it.**

| biome's family vs the surface host | result |
| --- | --- |
| **different** (foreign) | **composed** — gets its home family's live surface rule |
| same (native) | nothing — keeps the host's rule |

`applySurfaceComposition` builds a map of biomes whose family differs from the
host and gates each borrowed rule ahead of the host's. Native biomes are the
ones that get left alone.

**The host is `BiomeFamilies.surfaceHostFamily(type, noiseSettings)`, not
`hostFamily`.** They differ in two cases:

- `sky_islands` and `nether_islands` report **`end`** — they build on the End's
  settings record, so an overworld biome in a `sky_islands` world is foreign
  and does get dressed.
- An explicit `noiseSettings` reports the preset's family (**overworld**),
  because the preset replaces the whole settings record including its surface
  rule.

`hostFamily` answers a different question — which structure groups apply — and
reading it here re-skins exactly the wrong biomes.

## Noise settings

`noiseSettings` controls terrain shape within a given type. Creation-time-only.

| Value | Effect | When to use | Shipped usage |
| --- | --- | --- | --- |
| (omitted / `null`) | Vanilla defaults for the type | Most dimensions — fine for standard terrain | 54 dims |
| `adventure:compressed` | Tighter climate bands, 1.5× vertical scale, more relief per horizontal distance | Pocket/hard dims where compressed terrain adds drama in a small space; also pairs well with `multi_biome` when you want more height variation | 11 dims |
| `adventure:wide` | Broad realistic relief, 512-block build height, gentler gradients | Large scenic/pastoral dims where sweeping landscapes matter more than tight terrain features | 12 dims |

**Rule of thumb**: compressed for small/hard dims, wide for large/scenic dims, default for everything else. Don't combine `adventure:compressed` with a large (8192) peaceful dimension — it creates unnecessarily violent terrain in a relaxing world. Don't use `adventure:wide` on a 512-radius pocket dim — the terrain features are too broad to be visible in the small space.

## Portal scale guide

`portal.scale` controls coordinate scaling between the overworld and the custom dimension. **This is a design-level decision, not a cosmetic one — changing it after players have built portals can strand them.**

| Scale | What it means | Playable world size (at 8192 OW border) | Use for | Shipped count |
| --- | --- | --- | --- | --- |
| `1.0` | 1:1 with overworld. Walk 100 blocks in dim = 100 blocks in OW. | Full-sized, same as overworld | Large worlds, base-building dims, dimensions meant to feel "real" | 26 |
| `4.0` | 1:4 compression. Walk 100 blocks in dim = 400 blocks in OW. | Effectively 4× smaller playable area | Small brutal gauntlets (the_gauntlet, the_crucible) | 2 |
| `8.0` | 1:8 (nether-like). Walk 100 blocks in dim = 800 blocks in OW. | Pocket worlds. Most common for 1024-border dims. | The standard pocket dimension scale | 36 |
| `12.0` | 1:12 extreme compression. | Very tight. 683-block borders typical. | Extreme pocket dims, desolate/hard | 5 |
| `16.0` | 1:16 maximum compression. | Tiny. 512-block borders typical. | Claustrophobic pocket dims | 9 |

**Scale and border interact.** The 84 shipped dimensions follow a clear pattern:

- `scale=1.0` → `borders.player=8192` (or `4096`)
- `scale=4.0` → `borders.player=2048`
- `scale=8.0` → `borders.player=1024`
- `scale=12.0` → `borders.player=683`
- `scale=16.0` → `borders.player=512`

**Do not change scale on a dimension that already has one** unless you understand the consequences: every portal link's arrival coordinates shift, and players who've built near the border may find themselves outside it. Scale is effectively permanent after first play.

**Nether-family dimensions** auto-derive mood from scale when `seedRoll.mood` isn't set: `scale >= 12` → hard, `scale >= 8` → adventurous, else standard. Higher scale = smaller playable nether = harder.

## Size ↔ difficulty: the philosophy

The scoring model's own philosophy (see `references/scoring-internals.md`):

> Hard dims (dense + hostile + small playable radius) must be WORTH IT: hostile structures close, brutal terrain, places to hide/explore/fight. Easy/peaceful dims are relaxing but not boring: scenery, variety, gentle structures.

Small worlds are almost never "medium" — they're either brutal or fully peaceful.

| `borders.player` | Feel | `difficulty.mobMultiplier` | `structureDensity` | typical `seedRoll.mood` | typical `portal.scale` |
| --- | --- | --- | --- | --- | --- |
| 256–512 (pocket) | Peaceful retreat OR brutal gauntlet | `0.0` or `2.5–3.5` | `none`/`sparse` or `dense` | `serene` or `hard` | `16.0` |
| 683 (tight) | Usually desolate or hard | `1.0–2.5` | `normal`/`sparse` | `desolate` / `hard` | `12.0` |
| 1024 (pocket) | Standard pocket — broad range | `0.0–2.0` | varies | varies | `8.0` |
| 2048 (small) | Small, usually hard | `1.5–2.5` | `dense` or `normal` | `hard` / `adventurous` | `4.0` |
| 4096 (medium) | Medium | `1.0–1.5` | `normal` | `adventurous` / `standard` / `dramatic` | `1.0` |
| 8192 (full) | Large, varied, easier | `0.0–1.3` | `normal`/`sparse` | `standard` / `scenic` / `pastoral` / `serene` / `desolate` | `1.0` |

`playerLuck` (loot bonus) climbs with difficulty: brutal pocket dims carry `2.0-3.0` alongside high `mobMultiplier`. Peaceful dims also often carry `2.0-3.0` as a reward for a dimension with nothing to fight.

## Minimal worked example

A complete, valid, medium-difficulty `multi_biome` dimension:

```json
{
  "type": "multi_biome",
  "description": "A frozen crystal cave dimension, medium difficulty.",
  "biomes": [
    "minecraft:ice_spikes",
    "minecraft:frozen_peaks",
    "minecraft:snowy_slopes",
    "minecraft:grove",
    "minecraft:dripstone_caves",
    "minecraft:deep_dark",
    "terralith:glacial_chasm",
    "terralith:skylands_winter"
  ],
  "borders": { "player": 4096, "generation": 4096 },
  "difficulty": { "mobMultiplier": 1.3 },
  "structureDensity": "normal",
  "portal": {
    "frameBlock": "minecraft:packed_ice",
    "igniterItem": "minecraft:ender_eye",
    "color": "88CCFF",
    "lightLevel": 11,
    "scale": 1.0,
    "cooldown": 40,
    "sounds": {
      "ignite": "block.portal.trigger",
      "enter": "block.portal.travel",
      "exit": "block.portal.travel"
    }
  },
  "structures": {
    "wants": { "igloo": { "min": 256, "max": 1200 }, "trial_chambers": { "min": 1800, "max": 4096 } },
    "shuns": { "village": {} }
  },
  "seedRoll": {
    "mood": "adventurous",
    "spawnFilter": ["minecraft:ice_spikes", "minecraft:frozen_peaks", "terralith:glacial_chasm"]
  },
  "spawn": [0, 64, 0]
}
```

## Portals and exits

The aura policy (`portal.aura.subsume`), immersive portals, exit portals and
shrines, single-use and anchored portals, and exit conditions all live in
[`references/portals-and-exits.md`](references/portals-and-exits.md). Read it
before writing any `portal` block beyond frame/igniter/colour/scale — the
aura's `subsume` value is a design statement about the dimension, not a
safety setting.

## Advanced features (use only when the theme requires them)

These appear in 0-1 of the shipped dimensions. Don't add them speculatively.
Full schema in `references/schema-reference.md`.

### Custom environment (DimensionType)

- **`environment`**: Registers a custom DimensionType (`{ns}:{slug}_type`). Fields like `skyColor`, `fogColor`, `ambientLight`, `fixedTime`, `effects` (`minecraft:overworld`/`the_nether`/`the_end`), `hasCeiling`, `bedWorks`, etc. Most fields are boot-re-read; `minY`, `height`, `logicalHeight` are creation-time. Travel scale is `portal.scale`. Invalid heights fall back to the base type rather than crashing.

### Forced structure placement

- **`structures.force`**: `[{"structure": "minecraft:ancient_city", "x": 1200, "z": -800}]` — exact placement at specific coordinates. Uses full structure ids (not short names, not set ids). Runtime. The start attempt is performed by the mod itself, so it survives the structure's biome predicate AND other mods' start cancels (all seven YUNG's mods suppress the vanilla types they replace — forcing one is the only way to place the vanilla structure; [TROUBLESHOOTING.md#t25](../../../TROUBLESHOOTING.md#t25)). Forced structures resolve terrain adaptation (beards/kernels) like any other structure, appear in the census `forced` block, and log `forced <id> generated at chunk [x, z]` on first generation. An out-of-border force warns at boot.
- **`structures.force[].y`** — optional, and it is what makes a placement independent of terrain. Without it the structure asks the generator where the ground is and declines when the answer is "nowhere", which is correct for a placement meant to sit on terrain and fatal in a `void` dimension. With it, every height query during that one start attempt answers with your `y`, so the structure generates exactly there and hangs in open air over void, lava or nothing at all. `customdim lint` raises `force_needs_y` (ERROR) for a force entry in a `void` dimension that omits it. Caveat: a structure whose start height is an absolute constant ignores ground queries and lands where its own config says — `y` cannot move those. See [TROUBLESHOOTING.md#t33](../../../TROUBLESHOOTING.md#t33).

### Structure filtering

- **`structures.mode`** + **`structures.list`**: `"allow"`/`"reject"`/`"none"` filter on organic structure sets. Only structure SET ids work here (not short names). `"none"` is a deprecated alias for `structureDensity: "none"`. Runtime.

### Structure spacing overrides

- **`structures.spacing`**: `{"minecraft:villages": {"spacing": 32, "separation": 8}}` — rescale how frequently a structure set generates. Uses structure SET ids. Runtime (new chunks only).

## Noise structure placement — the DEFAULT, no config needed

Every managed dimension gets noise-placed structures. You do not opt in; you override parts of it or switch it off. A dimension with nothing but `type` and `biomes` gets structures appropriate to those biomes, at sensible density, distributed by a seeded noise field rather than a fixed grid.

**The biome filter is what makes zero-config work.** Structures whose own valid-biome list does not intersect your `biomes` are dropped from the pool automatically — a jungle `multi_biome` gets jungle temples and not igloos without either being named. Structures matching MORE of your biomes are weighted higher, so generation leans towards what belongs.

Sets are sorted into seven **groups**, and each active group is placed independently:

| Group         | What is in it                                                 | Default profile |
| ------------- | ------------------------------------------------------------- | --------------- |
| `deco`        | small environmental detail, ruins, guide posts, dead trees    | `natural`       |
| `settlements` | villages, taverns, farmsteads, campsites, outposts            | `natural`       |
| `dungeons`    | hostile interiors: dungeons, trial chambers, crypts           | `sparse`        |
| `landmarks`   | towers, temples, monuments, castles, igloos, pyramids         | `sparse`        |
| `maritime`    | ships, ocean ruins, monuments, lighthouses                    | `natural`       |
| `endgame`     | flagship mega-content: coliseums, mega ships, mega fortresses | `sparse`        |
| `loot`        | caches, shrines, buried treasure                              | `natural`       |

Four **profiles** control the shape of a group's distribution:

| Profile   | Feel                                          | Chunks above threshold |
| --------- | --------------------------------------------- | ---------------------- |
| `natural` | even, slightly sparser than vanilla           | ~22%                   |
| `dense`   | structures around every corner                | ~59%                   |
| `sparse`  | wide empty stretches; finding one is an event | ~5%                    |
| `cluster` | mostly empty, then a dense pocket             | ~2%                    |
| `none`    | this group does not generate here             | —                      |

### What you can write

```jsonc
{
  "structureDensity": "sparse", // every group uses this profile
  "structures": {
    "noise": "cluster", // same, but as a profile name
    // ...or per group:
    "noise": { "dungeons": "sparse", "settlements": "none" },
    "radial": {
      // 10 points, spawn -> border, 0.0-3.0
      "settlements": [1.5, 1.2, 1.0, 0.7, 0.4, 0.2, 0.0, 0.0, 0.0, 0.0],
    },
    "rarity": { "minecraft:trial_chambers": "common" }, // set id -> tier
    "exclude": ["minecraft:villages"], // out of the pool
    "include": ["mes:phantom_citadel"], // in, past the biome filter
    "force": [{ "structure": "explorify:farmstead", "x": -87, "z": -312, "exclusive": true }],
  },
}
```

**Precedence, most specific first:** `structures.noise.<group>` -> `structures.noise` as a string -> `structureDensity` -> the difficulty shifts -> the world type's defaults -> the group's own default.

**Difficulty shifts happen on their own.** `mobMultiplier >= 2.0` spreads dungeons evenly and brings endgame in from the border; `mobMultiplier <= 0.5` suppresses `dungeons` and `endgame` entirely. A peaceful dimension therefore has no dungeons even if you also set `structureDensity: "dense"` — the shift outranks the density dial. Only naming the group explicitly (`"noise": {"dungeons": "dense"}`) puts it back.

**`force` is exclusive by default.** Forcing a structure removes it from the noise pool everywhere else in that dimension — "put exactly this here" almost always means "and nowhere else". Add `"exclusive": false` to keep organic copies too. Other structures in the same group are unaffected.

### `overworld`, `the_nether`, `the_end`, `paradise_lost`

Four dimensions among 82, managed like the rest — every field in this schema
applies to them, noise placement included ([AGENTS.md § Dimensions](../../../AGENTS.md#dimensions)).
Leaving one out of a change needs a reason specific to that dimension.

They name no `type` because `DimensionConfig.getType()` supplies the family
(`overworld`, `nether`, `end`, `paradise_lost:paradise_lost`); writing one moves
that dimension onto another family's group set. Their generators come from live
registry entries this mod reads and rebuilds, so their surface rules and
settings are as changeable as any other dimension's.

Two things to hold in mind when tuning them:

- **Placement is boot-re-read, so no world wipe is needed** — but already-generated chunks keep the structures they have, and the boundary shows, the same way a `structures.spacing` change shows. The overworld is the world everyone is already standing in.
- **The Nether gates blaze rods on fortresses and the End gates elytra on end cities.** `/customdim structure-census` reports the nearest live instance of each against the reachability floor (512 blocks for a fortress, 2048 for an end city — `CensusCommands`' `REACHABILITY_FLOOR_BLOCKS`, matching `score/Criteria.java`). Re-run it after any change to either world's groups, density or border.

### Switching it off

- `"structureDensity": "none"` — no noise at all. `force` still works. This is the `the_dustbowl` pattern and is unchanged from before noise existed.
- `"structures": {"noise": false}` — falls back to vanilla grid placement. An escape hatch for one major version; prefer fixing the config.
- `type: "void"` and `type: "superflat"` never get noise.

### Traps specific to noise

- **It is creation-time worldgen.** Changing any of these affects only newly generated chunks; existing chunks keep what they have. The seed roller fingerprints all of it, so a change re-rolls that dimension.
- **`borders.player` and `difficulty.mobMultiplier` are now generation-affecting.** They used to be scoring/runtime only. The border sets both the scanned radius and the noise frequency scale; the multiplier drives the shifts above. Changing either changes the world.
- **Only `structures.noise` can ENABLE a group the world type does not list.** `NoiseGroupPlan.resolve` adds any group named there — the type's own list is a default, not a gate — so `{"endgame": "sparse"}` on a `cave` dimension does real work. `structures.radial` is NOT symmetrical: `explicitGroups` reads only `noise`, so a `radial` curve naming a group the type does not enable is silently dead. Name the group under `noise` as well as `radial`.
- **An unknown profile name suppresses the group** rather than silently becoming `natural`, and warns at boot. An unknown group name in `noise` or `radial` is ignored with a warning.
- **A radial curve must be exactly 10 values in 0.0-3.0** or it is rejected with a warning and the type default is used. A trailing `0.0` suppresses that band absolutely — it does not merely reduce it.
- **Not every structure is noise-placed.** A set is noise-managed if its placement is vanilla `minecraft:random_spread` or a type in `NoisePoolBuilder.ABSORBED_PLACEMENT_TYPES`; anything else keeps its own grid. Read the split from `placementType` in `config/custom-dimensions/extractors/registries.json` — currently 376 of 380 sets are managed, and the four that are not are `minecraft:concentric_rings` twice (vanilla strongholds, DnT end_castle), `betterstrongholds:stronghold`, and Supplementaries' galleons. (Explorify and Towns & Towers ship plain `minecraft:random_spread` and ARE noise-managed; Cristel Lib only patches their spacing numbers.) Pass-throughs ignore noise fields, radial curves and rarity, but `structures.mode` and `structures.exclude` DO apply to them ([TROUBLESHOOTING.md#t23](../../../TROUBLESHOOTING.md#t23)), and `customdim lint` warns `want_is_passthrough` when a want names one.

## Traps (read this before you write JSON)

1. **`structures.wants` and `seedRoll.wants` use DIFFERENT value formats — mixing them up crashes the mod.**
   - `structures.wants`: `{"short_name": {"min": N, "max": M}}` — absolute block distances.
   - `seedRoll.wants`: `{"short_name": "near_spawn"|"spread"|"near_border"}` — band names.
   - Putting a band-name in `structures.wants` or an object in `seedRoll.wants` crashes Gson.
2. **`structures.shuns` must be MAP form** — `{"village": {}}` not `["village"]`. List form crashes Gson. (`seedRoll.shuns` DOES accept a bare list.)
3. **All worldgen fields are creation-time-only** — `type`, `noiseSettings`, `biomes`, `seed`, etc. No effect after world creation without a full `data/world` wipe.
4. **Portal/runtime fields ARE re-read every boot** — `portal`, `difficulty`, `borders.player`, `structureDensity`, etc. Safe to iterate.
5. **Unknown block/item ids fail silently.** A typo'd `frameBlock` = portal never ignites, no error. Grep existing configs to verify ids.
6. **`color` is 6-digit hex, no `#`**: `"88CCFF"`, not `"#88CCFF"`.
7. **`lightLevel` is 0-15** (most use `11`). **`cooldown` is in ticks** (20/sec, default `40`=2s).
8. **Tag-only `frameBlock`** needs explicit `framePlaceBlock` or it falls back to obsidian silently.
9. **Peaceful dims drop dungeon-theme structures on the GRID PATH ONLY.** `DimensionStructures.transformed()` returns `transformedNoise()` before reaching that drop, so a noise-managed dimension (almost all of them) is shaped instead by `NoiseGroupPlan`'s `mobMultiplier` shift — which an explicit `structures.noise.{"dungeons": ...}` outranks. A hostile want in a peaceful noise dimension is therefore live config, not dead; `the_luminous_caverns` is the shipped example.
10. **Don't mix biome families** — a void dim with overworld AND end biomes confuses the roller (it can't pick a family for noise sampling). Stick to one family per dimension.
11. **`type: "overworld"` uses ALL biomes**, not just the ones in your `biomes` list. Your list only affects roller scoring. Use `multi_biome` for a curated biome selection.
12. **Don't change `portal.scale` on existing dimensions** — it shifts all portal coordinates and can strand players. Treat it as effectively permanent after first play.
13. **A listed biome with no climate parameters swallows the dimension.** Every Nature's Spirit biome, plus `minecraft:end_barrens` and `minecraft:end_midlands`, is absent from the multi-noise parameter table, and the mod deals ALL leftover climate regions to such biomes round-robin — one of them takes 74–100% of the world and the rest of your list never appears. Give those entries an explicit `{"id": ..., "parameters": {"<axis>": [lo, hi]}}` band. **Every biome from a mod that places through TerraBlender or a lithostitched injector is in the same position** — Regions Unexplored, Wilder Wild, Galosphere, YUNG's Cave Biomes and Underground Worlds all are. The axis to band on differs by family, measured on this stack with `customdim sample-noise`:

    The axis belongs to the noise ROUTER, not the dimension family, and it is
    measured — the full table is `config/custom-dimensions/climate-axes.json`
    and [T19](../../../TROUBLESHOOTING.md#t19). Highlights, and note that
    `sky_islands` and `end` do NOT take weirdness:

    | type | noiseSettings | axis |
    | --- | --- | --- |
    | `multi_biome` | — / `adventure:compressed` | `weirdness` |
    | `multi_biome` | `adventure:wide` | `erosion` |
    | `cave` | — | `temperature` (weirdness is inert: span 0.00) |
    | `nether` | — | `temperature` |
    | `nether_islands` | — / `adventure:compressed` | `erosion` / `temperature` |
    | `end`, `sky_islands` | — | `continentalness` |
    | `paradise_lost:*` | — | `temperature` |
    | `void` | — / `adventure:void` | `weirdness` / `erosion` |

    Measure your own dimension with `customdim sample-noise` before relying on
    a row, and rank candidates by DISTINCT VALUES across the radius, not by
    span — `adventure:void` gives weirdness the widest span (2.000) across
    three distinct values, which is the worst axis in that dimension.

    **Then fit the boundaries to that measurement, not to -2..2.** -2..2 is
    what the schema will accept, not what a world crosses: a dimension crosses
    a fraction of it, centred wherever its own noise sits, and the range per
    dimension is in `config/custom-dimensions/climate-axes.json`. Fit the
    boundaries inside that range and clamp only the outermost pair to `-2.0`
    and `2.0` so nothing falls off the axis. **The objective is that no band
    catches nothing** — a dominant biome with the others each holding somewhere
    real is a good world, and equal shares are a technique for reaching that,
    never the target ([T58](../../../TROUBLESHOOTING.md#t58)). Stepping equal
    widths from -2.0 gave one dimension 17 bands of 25 that cannot generate;
    `scripts/check-biome-bands.py` refuses that shape and
    `scripts/check-band-reach.py` measures what reaches.

    Leave a biome a plain string when something already places it — the base source's own cells, or the cells its mod registered with TerraBlender for that family. Once no biome is left foreign the leftover pool is dropped rather than dealt out. The boot line `biome source built (N explicit, M native, P natural over C cell(s), 0 mixed-in of K requested)` is the check — **0 mixed-in is the pass**, whichever tier got it there. A band written over a biome that would place naturally is authorship, not a fix. `scripts/check-content-coverage.py` lists installed biomes no dimension names. See [TROUBLESHOOTING.md#t19](../../../TROUBLESHOOTING.md#t19) and [#t35](../../../TROUBLESHOOTING.md#t35).
14. **`spawnFilter` biomes must exist in the biome parameter table for the dimension's family** — a biome id that exists in-game but isn't in the roller's table for that family causes every candidate to be rejected (zero candidates). Cross-check against `references/biome-catalogue.md`.
15. **Immersive is ON when you say nothing** — `"immersive": false` is the opt-out, not `true` the opt-in. Writing `"immersive": true` is harmless but redundant.
16. **`subsume: "everything"` is destructive by design** and belongs in the dimension's `description` as well as its JSON. Never add it to a peaceful or scenic dimension because a keyword matched — see the `subsume` section.
17. **Aura `trees` are never inferred, only configured.** Sampling them turned a beach into an impassable thicket.

## Validation (do not skip this)

```bash
# 1. Boot the local server (validates all dimension configs)
./dev up
docker logs mc 2>&1 | grep -iE 'custom-dimensions|WARN|ERROR' | tail -40

# 2. Confirm the dimension loaded
docker exec -i mc rcon-cli "execute in adventure:<slug> run seed"
```

Loud failures: invalid JSON, `structures.wants`/`shuns` format violations. Silent failures: unknown block/item ids, empty biome list (falls back to plains), tag-only `frameBlock` without `framePlaceBlock`, unrecognised portal `shape`.

**Operating on a dimension that already exists on a live world** (removing one, changing a field on a world with players in it, the fingerprint drift warning, confirming it's on the map) is a different job from authoring — see `dimension-lifecycle-operations` (`.claude/skills/dimension-lifecycle-operations/SKILL.md`).

## Seed rolling

Seed rolling lives in the custom-dimensions mod, driven by `/customdim` subcommands.

**Rollable requirements**: not `skip: true`, not `superflat`, `void` needs a `biomes` list.

**Zero candidates?** Most common cause: `seedRoll.spawnFilter` lists a biome that doesn't exist in the roller's biome parameter table for that family (see trap 13 above). Check `references/biome-catalogue.md`.
