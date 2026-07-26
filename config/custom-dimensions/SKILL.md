---
name: custom-dimension-authoring
description: Author or edit a custom-dimensions Minecraft dimension config (config/custom-dimensions/dimensions/<slug>.json for platform defaults, overlay/config/custom-dimensions/dimensions/<slug>.json for consumer overrides) from a short theme prompt. Use whenever asked to create a new dimension, reskin/rebalance an existing one, tune its difficulty or portal, or troubleshoot why a dimension config won't boot or won't roll seeds. Covers the full JSON schema, the mood/difficulty scoring philosophy, structure wants/shuns, portal setup, and the seed-rolling validation workflow. Always consult this before hand-writing or hand-editing a dimension JSON file — the schema has silent-failure traps that are easy to get wrong from general Minecraft/JSON knowledge alone.
---

# Custom Dimension Authoring

You are writing a config file for the **custom-dimensions** Fabric mod (platform repo: `minecraft-server-template`, consumer repos like `elfydd`). Given a short prompt describing a dimension's theme and difficulty, your job is to produce a single JSON file that is valid, boots without errors, and can have a seed rolled and scored for it.

## MANDATORY: read the references before writing anything

This skill has four reference files. **You must read ALL of them before writing any JSON.** They contain the exact valid values for every field — biome ids, structure short names, mood weights, type/noise combinations. Guessing any of these values risks a silent failure (the mod won't crash — it'll just silently drop your biome, ignore your structure, or fall back to defaults, and the dimension will be wrong with no error message).

| File | What it contains | Why you need it |
| --- | --- | --- |
| `references/schema-reference.md` | Full field-by-field JSON schema, every key's type, timing (creation-time vs boot-re-read), and valid values | Know what you can write |
| `references/scoring-internals.md` | Mood weight tables, placement bands, difficulty derivation, seed-group fingerprinting | Know how the roller will judge your config |
| `references/structure-names.md` | All 130+ valid structure short names, grouped by theme | Pick the right structures without inventing names |
| `references/biome-catalogue.md` | Every installed biome id grouped by family (overworld/nether/end/paradise_lost) and namespace — **the only biome ids that will actually work** | Pick biomes that exist and belong to the right family |

**Also read 2-3 existing dimension configs** with a similar size/mood to the one you're building. Good anchors:

- `the_gauntlet.json` — small (2048), brutal, `multi_biome`, compressed noise
- `the_wuthering_wisteria.json` — pocket (256), peaceful, `multi_biome`
- `the_blossom_gardens.json` — large (8192), peaceful, `multi_biome`, wide noise
- `the_basalt_spires.json` — pocket (1024), nether type
- `the_end_citadel.json` — medium (4096), end type
- `overworld.json` — base-world override (no type, just seed/spawn/scoring)

**And always check blocks, structures, and items against the json files inside the extractors** (`config/custom-dimensions/extractors/`) — the mod silently ignores unknown ids. These catalogues are authoritative; don't guess ids from memory or the wiki, just choose from the lists.

| File | What it contains | Why you need it |
| --- | --- | --- |
| ./extractors/biomes.json | All installed biome ids extracted from mods | Pick biomes that exist and belong to the right family |
| ./extractors/blocks.json | All installed block ids | Pick blocks that exist and are valid for portal frames, aura palettes, etc. |
| ./extractors/entities.json | All installed entity ids | Pick mobs that exist and are valid for spawn filters, etc. |
| ./extractors/structures.json | All installed structure short names | Pick structures that exist and are valid for `structures.wants`/`structures.shuns` |

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
7. **Set `portal`** — frame block, igniter, colour/particle, sounds, scale. See [Portal scale guide](#portal-scale-guide). Check `igniterItem` uniqueness: `grep -h igniterItem dimensions/*.json | sort`.
8. **Set `seedRoll`** — `mood`, `spawnFilter` (3-8 biomes, all must appear in your `biomes` list AND exist in `biome-catalogue.md` for that family), `description`.
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
| `amplified` | Amplified terrain (extreme heights). | overworld | Very tall, dramatic | 1 |
| `large_biomes` | Large biomes (biome regions 4× bigger). | overworld | Huge biome regions | 1 |
| `superflat` | Flat world. Never rollable. | — | Flat | 1 |
| `paradise_lost:paradise_lost` | Clone of the Paradise Lost skylands dimension. | paradise_lost | Floating skylands | 6 |
| `single_biome` | One biome, `biomes` must have exactly 1 entry. | overworld | Single biome terrain | 0 |
| `checkerboard` | Deterministic biome grid, overworld noise. | overworld | Biome checkerboard | 0 |

**Common mistake: using `overworld` when you mean `multi_biome`.** `overworld` uses ALL registered biomes — your `biomes` list only affects what the roller scores, not what generates. If you want a "jungle-only dimension" or "frozen peaks dimension", use `multi_biome`. The original `the_overgrowth` had `type: "overworld"` with a jungle biome list, which meant every overworld biome could appear (deserts, oceans, etc.) — only the roller cared about the jungle list, not the generator.

**Void dimensions: keep biomes from ONE family.** A void dim with `minecraft:deep_dark` (overworld) AND `minecraft:the_end` (end) will confuse the roller — it can't determine which family's noise config to use for sampling. If you want an end-themed void, use only end-family biomes from `references/biome-catalogue.md`.

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

From `scripts/seed/dimension_profiles.py`:

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
    "spawnFilter": ["minecraft:ice_spikes", "minecraft:frozen_peaks", "terralith:glacial_chasm"],
    "description": "A frozen crystal cave dimension, medium difficulty."
  },
  "spawn": [0, 64, 0]
}
```

## `portal.aura.subsume` — what the aura may eat

Every linked portal grows an **aura**: it slowly converts blocks in an annulus around both ends, so the two worlds bleed into each other. `subsume` decides what it is allowed to convert, and **it is a design statement about the dimension, not a safety setting**.

```jsonc
"portal": {
  "aura": { "subsume": "everything" }   // "none" | "natural" (default) | "everything"
}
```

| Value | Behaviour | Use for |
| --- | --- | --- |
| `none` | Never replaces an existing block. Still adds flora to bare ground, but no fire and no fluids. | Infrastructure and discretion — `exitPortal` fixtures that must stay recognisable landmarks, and pocket dims meant to be unassuming and easy to hide. |
| `natural` (default) | Converts natural terrain; never anything crafted or shaped. A beach portal takes the sand and leaves the cobblestone wall standing in it. | Everything ordinary. |
| `everything` | Converts whatever it reaches, player builds included. | **Dangerous worlds.** The encroachment IS the story. |

**When to reach for `everything`.** Any dimension that is aggressive, ultra-hard, or whose description names consuming, feeding, corruption, blight, sculk or the void. Opening a portal to one of those should carry visible risk — the dimension telling the truth about itself. The shipped set was audited on exactly that rule: `difficulty.mobMultiplier >= 2.0` **or** a description that names one of those forces. 24 of 84 dimensions qualify.

Two judgement calls that rule does NOT make for you, both real cases from that audit: `the_dripping_pines` ("ruins **rotting** under the pines") and `the_glacial_drift` ("islands calving into the **void**", `mobMultiplier` 0.8) both hit a keyword while being gentle, scenic dimensions. The word describes scenery, not a force acting on the world. Both keep the default. **Read the description; don't just match the word.**

**Say so in the description.** An `everything` dimension is a promise that a build near its portal is at risk. That belongs in the player-facing text, not just the JSON.

**Claims are an absolute veto, and `everything` does not bypass them.** The mod asks Open Parties and Claims before converting anything; a claimed chunk is never touched, under any policy. One rule, no exceptions — an exception would make the guarantee unexplainable to players. This is also what makes the feature fair: claiming land is how a player says "I am prepared to host this thing", and choosing not to claim is a decision with consequences.

**How `natural` tells crafted from natural**: the `#adventure:aura_protected` block tag, shipped in the mod's jar datapack. Planks, wool, concrete, bricks, cobblestone, stone bricks, polished/cut/smooth variants, metal and copper blocks, glazed terracotta and friends are all in it. Plain stone, dirt, sand, gravel, deepslate and logs are not — those are the world, and the aura is allowed to spread through the world. Extend it from a consumer datapack rather than editing the mod.

**There is no revert.** Original block states are deliberately not persisted. Claims are the protection mechanism; rebuilding is quick.

## Immersive portals (`portal.immersive`) — ON by default

Every portal is immersive unless it says otherwise. You see the destination's terrain through the frame, hear its biome ambience, and can throw items through. Server-side only — no client mod, and a vanilla client gets all of it.

```jsonc
"portal": {
  // nothing at all           -> immersive, every default. This is the norm.
  "immersive": false          // the opt-out
  // "immersive": {
  //   "previewDepth": 8,     // blocks projected behind the frame (1-16)
  //   "previewRadius": 2,    // how far the view cone may widen (0-4)
  //   "refreshInterval": 4,  // ticks between delta refreshes (min 2)
  //   "activationRange": 24, // blocks from the portal (1-64)
  //   "audio": true,         // far-side biome ambience
  //   "entityPassthrough": true  // items/projectiles/orbs cross; mobs never do
  // }
}
```

**Write `"immersive": false` only for a deliberate reason**, and say what it is in a comment or the description. Legitimate reasons: a dimension whose portal should read as mundane, or one whose destination is visually meaningless (pure `void` type — you would be projecting nothing).

**`previewDepth` is a look, not a performance dial.** 8 is the sweet spot. 16 shows lighting artefacts; 4 reads as wallpaper rather than a window. Don't lower it to "save" anything — the projection only runs for players within `activationRange` and sends deltas after the first pass.

**It is boot-re-read**, like the rest of the portal block, so it applies to dimensions that already exist without a world wipe.

Known limitations, all inherent to the server-side approach and all documented in `mods/custom-dimensions/immersive/PHASE-5-CLIENT-COMPANION.md`: no entities visible on the far side, source-dimension lighting and biome colours, block entities without their contents, geometry snapping as you walk (block is the granularity), and the vanilla loading screen on the actual transition. None of these are worth reporting as bugs.

## Advanced features (use only when the theme requires them)

These features appear in 0-1 of the 84 shipped dimensions. Don't add them speculatively — only when the theme specifically calls for the mechanic. Full schema in `references/schema-reference.md`.

### Exit portals and exit shrines

- **`exitPortal`**: `{"enabled": true, "pos": "spawn", "target": "bed"}` — the mod builds and maintains a portal frame near the dimension's spawn so players can get home. Particularly important for dims with `singleUse` or `anchor` portals where the entry portal may be destroyed or one-way.
- **`exitShrines`**: `{"enabled": true, "target": "bed"}` — scattered jigsaw exit ruins throughout the dimension. Worldgen is creation-time; the beacon detection that actually teleports players is boot-re-read.

### Portal auras — tuning only

The aura runs by default and needs no config. This block is for tuning it; the policy question (**what it is allowed to eat**) is `subsume`, documented in its own section above because every dimension has an answer to it.

- **`portal.aura`**: `{"enabled": false}` switches it off entirely.
- Tunables: `radius` (default 8, max 32), `interval` (ticks, default 40, min 10), `blocksPerPass` (default 2, max 16), `budget` (lifetime conversions per side, default 300, `-1` = endless), `sides` (`"source"`/`"target"`/`"both"`).
- Palette overrides: `palette` (terrain block ids), `flora`, `trees` (ConfiguredFeature ids), `fluids`, `conversions` (`{"from": "to"}`, `from` may be `#tag`), `fireChance` (0-1, default 0).

**Trees are never inferred**, only planted from an explicit `aura.trees`. A sampled tree palette turned a beach portal into an impassable dark-oak thicket — a tree's footprint is orders of magnitude bigger than the block that seeded it. Set `trees` only when a forest IS the effect you want.

**Test at a sped-up cadence**, never the default: `{"interval": 10, "blocksPerPass": 8}`.

### Single-use portals

- **`portal.singleUse`**: `{"enabled": true, "delaySeconds": 10, "breakMode": "decay"}` — the portal self-destructs after first traversal. `breakMode`: `"destroy"` (removed), `"decay"` (blocks swap via `decayMap`), `"partial"` (1-2 blocks decay, repairable). Pair with `exitPortal` or `exitShrines` so players can get home.

### Anchored portals

- **`portal.anchor`**: `{"pos": "spawn", "exit": "bed"}` — every source portal lands at one fixed position in the destination, rather than matching coordinates. Good for pocket dims that are essentially a single room/arena.

### Exit conditions

- **`exits`**: Map of trigger → action. Triggers: `"void"`, `"death"`, `"death:lava"`, `"enderPearl"`, `"fallFrom"`. Actions: `"teleport"` (intercepts the event), `"respawnAt"` (die normally, respawn at target), `"kill"` (void trigger only). Example: `{"void": {"target": "origin", "action": "teleport"}}`.

### Custom environment (DimensionType)

- **`environment`**: Registers a custom DimensionType (`{ns}:{slug}_type`). Fields like `skyColor`, `fogColor`, `ambientLight`, `fixedTime`, `effects` (`minecraft:overworld`/`the_nether`/`the_end`), `hasCeiling`, `bedWorks`, etc. Most fields are boot-re-read; `minY`, `height`, `logicalHeight`, `coordinateScale` are creation-time. Invalid heights fall back to the base type rather than crashing.

### Forced structure placement

- **`structures.force`**: `[{"structure": "minecraft:ancient_city", "x": 1200, "z": -800}]` — exact placement at specific coordinates. Uses full structure ids (not short names, not set ids). Runtime.

### Structure filtering

- **`structures.mode`** + **`structures.list`**: `"allow"`/`"reject"`/`"none"` filter on organic structure sets. Only structure SET ids work here (not short names). Runtime.

### Structure spacing overrides

- **`structures.spacing`**: `{"minecraft:villages": {"spacing": 32, "separation": 8}}` — rescale how frequently a structure set generates. Uses structure SET ids. Runtime (new chunks only).

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
9. **Peaceful dims auto-drop dungeon-theme structures** — don't `want` hostile structures in a peaceful dimension.
10. **Don't mix biome families** — a void dim with overworld AND end biomes confuses the roller (it can't pick a family for noise sampling). Stick to one family per dimension.
11. **`type: "overworld"` uses ALL biomes**, not just the ones in your `biomes` list. Your list only affects roller scoring. Use `multi_biome` for a curated biome selection.
12. **Don't change `portal.scale` on existing dimensions** — it shifts all portal coordinates and can strand players. Treat it as effectively permanent after first play.
13. **`spawnFilter` biomes must exist in the biome parameter table for the dimension's family** — a biome id that exists in-game but isn't in the roller's table for that family causes every candidate to be rejected (zero candidates). Cross-check against `references/biome-catalogue.md`.
14. **Immersive is ON when you say nothing** — `"immersive": false` is the opt-out, not `true` the opt-in. Writing `"immersive": true` is harmless but redundant.
15. **`subsume: "everything"` is destructive by design** and belongs in the dimension's `description` as well as its JSON. Never add it to a peaceful or scenic dimension because a keyword matched — see the `subsume` section.
16. **Aura `trees` are never inferred, only configured.** Sampling them turned a beach into an impassable thicket.

## Validation (do not skip this)

```bash
# 1. Boot the local server (validates all dimension configs)
./dev up
docker logs mc 2>&1 | grep -iE 'custom-dimensions|WARN|ERROR' | tail -40

# 2. Confirm the dimension loaded
docker exec -i mc rcon-cli "execute in adventure:<slug> run seed"
```

Loud failures: invalid JSON, `structures.wants`/`shuns` format violations. Silent failures: unknown block/item ids, empty biome list (falls back to plains), tag-only `frameBlock` without `framePlaceBlock`, unrecognised portal `shape`.

## Seed rolling

```bash
./dev up                            # stage config first
./dev seed-roll                     # roll all dimensions (default)
./dev seed-roll --dims <slug>       # roll a single dimension
./dev seed-roll --pool 10000 --count 200  # bigger screening pool
./dev seed-rescore                  # recompute scores vs current configs (no re-rolling)
./dev seed-status                   # candidate-bank status: counts, winners, freshness
./dev seed-viewer                   # interactive picker + background rendering
```

**Rollable requirements**: not `skip: true`, not `superflat`, `void` needs a `biomes` list.

**Zero candidates?** Most common cause: `seedRoll.spawnFilter` lists a biome that doesn't exist in `biome_params.json` for that family. Check `references/biome-catalogue.md`.
