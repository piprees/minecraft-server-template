# Custom Dimensions

Runtime dimension creation with custom portal frames, configurable igniters, coordinate scaling, coloured particles, and bidirectional travel for Minecraft 1.21.1 Fabric.

## Features

- **12 world types** -- overworld/multi_biome, nether, end, void, superflat, cave, checkerboard, sky_islands, nether_islands, amplified, large_biomes, single_biome — plus `ns:path` clone types for any registered dimension
- **Checkerboard dimensions** -- `type: "checkerboard"` tiles the `biomes` list in a fixed grid (seed-independent layout, seeded terrain); `checkerboardScale` 0-62 (default 2) sets the cell size (`2^(scale+4)` blocks)
- **Custom superflat layers** -- `layers` (bottom-up `{block, height}` list) and `flatBiome` on `type: "superflat"`; invalid config falls back to the whole default bedrock/dirt/grass stack
- **Generator settings overrides** -- `settingsOverrides` swaps `seaLevel`, `defaultBlock`, `defaultFluid`, `disableMobGeneration` on the type's (or noiseSettings preset's) generator settings; per-field warn-and-keep-base on invalid values
- **Per-biome placement parameters** -- `biomes` entries may be `{id, parameters}` objects with explicit multi-noise intervals (number or `[min,max]` per axis, -2..2); the biome claims exactly that region, unset axes span everything
- **Per-set structure spacing** -- `structures.spacing` maps structure SET ids to exact `{spacing, separation}` values, overriding the theme-based `structureDensity` factors for those sets (boot-re-read; new chunks only)
- **Biome patches** -- `biomePatches` overrides the generated layout in three modes: stamp (`{biome, x, z, radius}` claims the area), clipped swap (`replace` recolours only the matching biome inside the area, organic shape kept), and global swap (`scope: "global"` replaces a biome dimension-wide, or uses the area as a selector for every biome touching it). `shape: circle|square`, `blend` edge jitter (default 8 blocks). A stamp at (0,0) guarantees the spawn biome. Backed by a codec-registered `PatchedBiomeSource` wrapper so the generator persists cleanly
- **Custom portal frames** -- any block as the frame, any item as the igniter; `frameBlock` accepts a plain id, `#ns:tag`, a list, or `{"colorGroup": "<dye>"}`, with `framePlaceBlock` naming the concrete block mod-built frames use
- **Portal shape presets** -- optional `shape`: `door` (1x2), `doorway` (2x3), `end_exit` (horizontal ring, optional `centreBlock` pedestal); absent = free-form flood-fill. Shapes imply orientation; mod-built exit portals follow the dimension's shape
- **Per-part frame materials** -- `frameMaterials` {top, sides, bottom} each accepting any frame form ("stone base, log pillars, plank lintel"); flood-fill accepts the union, validation checks each ring position's part; mod-built frames place per part (vertical portals only)
- **Portal auras** -- portals affect their surroundings: by default each linked pair leaks the other side's sampled nature through (terrain, flora, trees, fluids), bounded by per-side budgets; `portal.aura` overrides palettes, adds explicit conversions (obsidian→crying) and fire, or switches it off
- **Immersive portals** -- `portal.immersive` turns a portal into a window: the destination's real terrain is visible through the frame with natural parallax (server-sent fake blocks, masked to what you could actually see through the aperture), its biome ambience leaks back, and items, projectiles, XP orbs, mobs and villagers walk through. Server-side only — a vanilla client gets all of it, no client mod. `true` for defaults or an object to tune `previewDepth` / `previewRadius` / `refreshInterval` / `activationRange` / `audio` / `entityPassthrough`. Boot-re-read like the rest of `portal`. Known limits (vanilla's dimension-change screen still shows, approximate lighting and biome colours, far-side entities invisible) and the client mod that would lift them are specified in [`client/SPEC.md`](client/SPEC.md)
- **Horizontal portals** -- floor and ceiling portals (Y-axis) alongside vertical X/Z portals
- **Per-dimension seeds** -- each dimension can use its own world seed
- **Coordinate scaling** -- `portal.scale` is the Nether-style travel ratio, stated the way people say it: **"8 nether : 1 over"**. One block walked in the DESTINATION is worth `scale` blocks back home, so **entering divides and returning multiplies**.
  - `scale: 8` -- walk 10 blocks in the dimension, you have covered 80 at home. A portal at overworld `(1888, -3624)` arrives at `(236, -453)`.
  - `scale: 1` -- no compaction; coordinates match 1:1.
  - `scale: 0.125` -- the inverse: a *sprawling* dimension, 10 blocks there is 1.25 at home. **This is almost always a mistake.** An earlier version of this file gave `0.125` as the way to write "nether-style 1:8", two unit tests were written to match it, and the code multiplied on entry for months — arrivals landed outside their own dimension's border, where vanilla forbids breaking or placing anything, and the symptom looked like a protection bug. All 52 non-1.0 dimensions use whole ratios; `ShippedDimensionReachabilityTest` fails the build if a fractional scale reappears.
  - A dimension's `borders.player` must be `overworldBorder / scale`, or portals built near the overworld border arrive outside the destination's border -- where vanilla forbids breaking or placing any block, stranding the player.
- **Coloured particles** -- hex colour per portal, rendered on both source and target sides
- **Per-portal cooldown** -- configurable teleport cooldown (0-200 ticks) per portal link
- **Portal sound effects** -- configurable ignition, entry, and exit sounds per portal (JSON config only)
- **Bidirectional travel** -- target-side portals are built automatically; stepping in returns you
- **Anchor portals** -- `portal.anchor` gives a dimension one fixed landing (End-gateway style): every source portal arrives at the anchor, no per-source target portal is ever built, and the exit mode (`origin` | `bed` | `worldSpawn`) decides where leaving takes you
- **Single-use portals** -- `portal.singleUse` starts a countdown at first traversal, then the frame breaks (`destroy` | `decay` | `partial`); the countdown persists in `portal_links.json` and survives restarts
- **Exit portals** -- `exitPortal` builds a mod-maintained frame near dimension spawn as a guaranteed way home (rebuilt if broken); config validation WARNs at boot when a strandable dimension (singleUse or anchor) lacks one
- **Exit shrines** -- `exitShrines` scatters `adventure:exit_shrine` jigsaw ruins (jar datapack; templates from `scripts/gen-exit-shrine.py`) whose beacon-marked frames self-register as exit zones on chunk load; the structure set ships at frequency 0.001 and is raised to full only for opted-in dims. Shrine frames are rebuilt in the dimension's own `framePlaceBlock` at registration (one template, any material), and spacing derives from `borders.player` (clamp(radius/32, 12..48) chunks — a 256-radius pocket gets 1-2 shrines, not a grid) unless `structures.spacing` sets it explicitly
- **Dimension links** -- every exit target (`exitPortal.target`, `portal.anchor.exit`, `exits` rules) accepts `{"dimension": "ns:slug", "arrival": "anchor"|"spawn"|[x,y,z]}` alongside the `bed`/`worldSpawn`/`origin` shorthands — dimensions compose into chains and hubs
- **Exit conditions** -- a per-dimension `exits` block maps triggers to targets: `void` (fires before vanilla void damage), `death` / `death:<cause>` / `death:mob:<id>` (cancel-and-teleport or respawn-redirect — death is not always final), `enderPearl`, `fallFrom`; safe arrivals with slow falling, per-player anti-loop cooldown, boot validation for death-only exits and dangling links
- **Idle dimension unloading** -- empty dimensions are saved and unloaded after a configurable idle period (default 5 min), re-created on demand
- **Per-dimension mob control** -- disable hostile mob spawning per dimension for peaceful pocket worlds
- **Per-dimension difficulty** -- `difficulty.mobMultiplier` scales hostile mob health/damage/armor at spawn (attribute modifiers, persisted in NBT); optional `depthScaling` makes mobs harder underground; `playerLuck` boosts loot quality while inside the dimension (absorbed from the configurable-difficulty mod)
- **Per-dimension world borders** -- `borders.player` sets each world's vanilla border at boot; `borders.generation` is tooling metadata for Chunky/render bounds
- **Custom dimension types** -- an `environment` block (fixedTime, ceiling/skylight, ultraWarm, natural, bedWorks, respawnAnchorWorks, piglinSafe, hasRaids, minY/height/logicalHeight, ambientLight) registers a per-dimension `DimensionType` as `{ns}:{slug}_type`; unset fields inherit the base type (skyColor/fogColor are client-side and configurator-only)
- **Per-dimension config files** -- one self-contained JSON per dimension under `config/custom-dimensions/dimensions/` (portal, difficulty, borders, seedRoll included); global defaults in `settings.json`; consumer overlays merge/replace/skip per file. Portal link state saved to `portal_links.json`

## Requirements

- Minecraft 1.21.1
- Fabric Loader 0.16+
- Fabric API
- Java 21

## Commands

All commands live under one root, `/customdim`, and require permission level 4.

> **There is no `/dimension create` and no `/portal link`.** Both were
> documented here for a long time after they stopped existing. Dimensions are
> created at boot from `config/custom-dimensions/dimensions/*.json`, and
> portals are configured in each dimension's `portal` block — there is no
> runtime portal-linking command at all. See `mods/AGENTS.md` § Portal system.

| Command | What it does |
| --- | --- |
| `/customdim create <name> <type> <seed> [noiseSettings] [structureDensity] [biome…]` | Create a runtime dimension. Prefer a config file; this is a debugging tool. |
| `/customdim destroy <name>` | Unload a runtime dimension. Does **not** scrub its `level.dat` entry — see the level.dat trap in `AGENTS.md`. |
| `/customdim list` | List managed dimensions. |
| `/customdim load <name>` | Queue a world load (drained on `END_SERVER_TICK`). |
| `/customdim locate biome <dimension> <biome_id> [timeout]` | Async biome locate; returns a ticket UUID. |
| `/customdim locate structure <dimension> <structure_id> [timeout]` | Async structure locate; returns a ticket UUID. |
| `/customdim locate-result <uuid>` | Collect the result of an async locate. |
| `/customdim dump-biome-params <dimension>` | Dump TerraBlender + mod biome parameters (feeds the seed roller's `biome_params.json`). |
| `/customdim sample-noise <dimension> <x> <z>` | Generation ground-truth oracle: the router climate point at `(x & ~3, 0, z & ~3)`. |
| `/customdim sample-biome-grid <dimension> <radius> <step>` | Sample the biome layout on a grid. |
| `/customdim debug-prng <seed>` | PRNG diagnostics. |

## Examples

Dimensions and portals are **config**, not commands. A pocket dimension with a
cherry-blossom portal is one file, `config/custom-dimensions/dimensions/cherry_pocket.json`:

```jsonc
{
  "type": "single_biome",
  "seed": 98765,
  "biomes": ["minecraft:cherry_grove"],
  "borders": { "player": 256, "generation": 256 },
  "difficulty": { "hostileSpawning": false },
  "portal": {
    "frameBlock": "minecraft:cherry_log",
    "framePlaceBlock": "minecraft:cherry_log",
    "igniterItem": "minecraft:cherry_sapling",
    "color": "#FF9EC6",
    "lightLevel": 8,
    "scale": 1.0
  }
}
```

A nether-style hub at **8 destination blocks : 1 overworld block**. Note the
scale is `8`, not `0.125` — entering DIVIDES (see § Coordinate scaling in
Features). The border must be `overworldBorder / scale`:

```jsonc
{
  "type": "nether",
  "borders": { "player": 1024, "generation": 1024 },
  "portal": {
    "frameBlock": "minecraft:obsidian",
    "igniterItem": "minecraft:flint_and_steel",
    "color": "#AA0000",
    "lightLevel": 11,
    "scale": 8.0,
    "cooldown": 5
  }
}
```

`PortalSafetyValidator` WARNs at boot if `borders.player` is too small for the
scale, naming the usable source radius and the border that would fix it. It
never auto-fixes.

**Horizontal floor portal:** build a frame flat on the ground (e.g. a ring of
obsidian), then right-click the top face with the igniter. The portal detects
the horizontal plane and creates a Y-axis portal you walk onto.

## Configuration

All configuration is stored inside the server's data directory under `config/`.

### `custom-dimensions/` (v4 — preferred)

One file per dimension; the slug comes from the filename. Base-world filenames (`overworld.json`, `the_nether.json`, `the_end.json`, `paradise_lost.json`) override existing worlds (seed/spawn) instead of creating new ones — `"seed": "env"` reads the `SEED` environment variable.

```
config/custom-dimensions/
├── settings.json              # namespace, idleUnloadMinutes, frames, defaults
├── dimensions/
│   ├── cherry_pocket.json     # one self-contained file per dimension
│   ├── overworld.json         # base-world override (seed, spawn)
│   └── ...
└── overlay/dimensions/        # consumer overrides (staged by deploy.sh/dev-up.sh
                               # from overlay/config/custom-dimensions/)
```

`dimensions/cherry_pocket.json`:

```json
{
  "type": "single_biome",
  "seed": 98765,
  "biomes": ["minecraft:cherry_grove"],
  "difficulty": { "hostileSpawning": false },
  "portal": {
    "frameBlock": "minecraft:cherry_blossom",
    "igniterItem": "minecraft:cherry_blossom_petals",
    "color": "FF9EC6",
    "lightLevel": 8,
    "scale": 1.0,
    "cooldown": 40,
    "sounds": { "ignite": "block.portal.trigger", "enter": "block.portal.travel", "exit": "block.portal.travel" }
  }
}
```

`settings.json`:

```json
{
  "namespace": "adventure",
  "idleUnloadMinutes": 5,
  "frames": {
    "overworld": "minecraft:mossy_stone_bricks",
    "nether": "minecraft:obsidian",
    "end": "minecraft:end_stone_bricks"
  },
  "defaults": {
    "frameBlock": "minecraft:crying_obsidian",
    "borders": { "player": 8192, "generation": 8192 },
    "difficulty": { "mobMultiplier": 1.0 }
  }
}
```

Consumer overlay resolution (files in `overlay/dimensions/`): a file with a top-level `"overrides"` object deep-merges over the platform default; a file without one replaces the platform default entirely; an empty `{}` skips the dimension; overlay-only files are consumer-added dimensions namespaced by the `BRAND_SLUG` environment variable.

### Noise presets (`noiseSettings`)

`"noiseSettings": "adventure:wide" | "adventure:compressed"` swaps the dimension's `ChunkGeneratorSettings` for a jar-baked preset (wide: Terratonic + ultrasmooth semantics, 512-block build height; compressed: tighter climate bands, 1.5x vertical scale). Both are generated by `scripts/gen-terrain-presets.py` from the pinned Tectonic + Terralith jars — regenerate and commit whenever either pin bumps.

**The presets are self-contained: removing Tectonic and/or Terralith does not break the boot.** Three mechanisms, all enforced by the generator's final audit:

- **Density functions** are cloned into the adventure namespace (`adventure:{wide,compressed}/{tectonic,terralith,minecraft}/...`) with config-driven nodes inlined. DF ids carry no seed, so renaming is generation-neutral. The closure covers the Terralith-jar graph (`terralith:overworld/extra_terrain_sum` and friends) and both mods' invented `minecraft:`-namespace DFs (`minecraft:overworld/noise_router/*`).
- **Noises** keep their ORIGINAL ids as byte-identical copies under `data/tectonic/` and `data/terralith/` in the jar — vanilla seeds each noise by MD5-hashing its id string, so a rename would shift terrain on every existing world. When the real mod is present its pack outranks ours (each Fabric mod is its own datapack; Tectonic's built-in pack sits above them all) and the byte-identical duplicate is harmless in any order.
- **Vanilla-shipped `minecraft:` ids** (climate/aquifer/cave noises, `overworld/caves/*` DFs) are never copied — vanilla always provides them; our copy would leak into the real overworld whenever the owning mod is removed. Consequence: with Terralith removed, adventure-dim aquifers/cave shapes revert to vanilla semantics (subtle, generation-only, no boot risk); with mods present, generation is bit-identical to the pre-hardening jar (locate-oracle verified).

Conflict note baked into the design: Terratonic's overlay patches two `terralith:` DFs (`overworld/extra_terrain_base`, `overworld/spike/size_spline`) with different content than Terralith's own copies — the adventure clones use the Terratonic variants because that's what generation resolves today (Tectonic's pack outranks Terralith's), and cloning sidesteps the pack-order question entirely.

### Fixed structure placements and set filtering (`structures.mode` / `force`)

The `structures` block gains precision-placement controls alongside the
existing roller `wants`/`shuns` and runtime `spacing`:

```json
"structures": {
  "mode": "allow",
  "list": ["minecraft:villages", "adventure:exit_shrines"],
  "force": [
    { "structure": "minecraft:ancient_city", "x": 1200, "z": -800 }
  ]
}
```

- **`mode`** filters the ORGANIC structure sets at the per-world calculator
  rebuild: `allow` keeps only sets in `list`, `reject` drops sets in
  `list`, `none` drops every organic set. The exit-shrines opt-in precedes
  the filter (an opted-in dim keeps its shrines even under `allow`/`none`).
  Unknown modes warn and disable the filter.
- **`force`** places an exact structure at an exact spot: each entry
  becomes a synthetic single-structure set with a
  `customdimensions:fixed` placement (a `RandomSpreadStructurePlacement`
  subclass, so vanilla `/locate` finds it and density rescaling exempts
  it). STRUCTURE ids, block coordinates; the start lands in that block's
  chunk. Unknown structures (removed mods) warn and skip — never a boot
  break. Forced placements are additive after `mode` — `"mode": "none"` +
  `force` = ONLY the forced structures.
- **The biome predicate does NOT apply**: `force` is a literal override,
  so an overworld structure forced into a nether dimension generates.
  `ChunkGeneratorForcedBiomeMixin` replaces vanilla's
  `Predicate<RegistryEntry<Biome>>` with one that always passes, for
  forced start attempts only — every other set keeps vanilla behaviour
  exactly. Each forced position that generates logs one INFO line:
  `Dimension <slug>: forced <structure> generated at chunk [x, z]
  (biome predicate bypassed)`.
- **Out-of-biome forced structures generate but are NOT locatable.**
  `/locate` reads `StructurePlacementCalculator`'s structure→placement
  index, which vanilla builds only for structures whose valid biomes
  intersect the dimension's biome source; that index is untouched on
  purpose. Verify with `/customdim structure-census` and the log line.
- Like `spacing`, this is a RUNTIME rebuild (re-read every boot, newly
  generated chunks only) — not creation-time worldgen. One forced position
  per 32-chunk region is locatable; extras in the same region still
  generate (warned at boot).
- Roller parity: filtered sets measure as absent, forced structures as
  constant distances (`structure_placement.forced_distance`/`mode_drops`);
  both fields join `generation_payload()` conditionally, so existing
  fingerprints stay byte-stable. The fork-config GUI does not expose these
  fields yet.

### Difficulty, exits, and the remaining fields

Complete reference for everything not covered by its own section above —
each snippet is copyable as-is into a dimension file.

**Difficulty** (hostile-mob scaling; peaceful dims drop dungeon-theme
structure sets automatically):

```json
"difficulty": {
  "hostileSpawning": true,
  "mobMultiplier": 1.5,
  "attributes": { "health": true, "damage": true, "armor": false,
                  "speed": false, "knockback": false },
  "playerLuck": 0.5,
  "depthScaling": { "enabled": true, "startY": 0, "endY": -64,
                    "minMultiplier": 1.0, "maxMultiplier": 2.0 }
}
```

- `mobMultiplier` scales hostile mobs only (`0` = effectively peaceful);
  `attributes` booleans choose WHICH attributes the multiplier touches
  (default: health + damage). `playerLuck` is a flat luck attribute bonus
  applied on join/world change. `depthScaling` ramps the multiplier from
  `minMultiplier` at `startY` to `maxMultiplier` at `endY` (deeper =
  harder).

**Exits** (`"exits"` block — leaving the dimension without a portal).
Triggers: `"void"` (fell below minY), `"death"` (any death),
`"death:<cause>"` (specific damage type), `"enderPearl"` (pearl thrown —
consumed, no teleport), `"fallFrom"` (fell `minHeight` blocks). Targets:
`"bed"` | `"worldSpawn"` | `"origin"` (where the player entered from), or
`{"dimension": "adventure:the_gauntlet", "arrival": "anchor"}` where
`arrival` is `"anchor"` | `"spawn"` | `[x, y, z]`. Actions: `"teleport"`
(intercepts — for death triggers this CANCELS the death), `"respawnAt"`
(die normally, respawn at the target — needs a real player to verify;
carpet bots can't respawn), `"kill"` (explicit vanilla void death):

```json
"exits": {
  "void": { "target": "origin", "action": "teleport" },
  "death": { "target": { "dimension": "minecraft:overworld",
                          "arrival": "spawn" }, "action": "respawnAt" },
  "enderPearl": { "target": "worldSpawn", "action": "teleport" },
  "fallFrom": { "minHeight": 100, "target": "origin", "action": "teleport" }
}
```

**Endgame gating** (`structures.endgame`) — keeps end-game structures
(theme map's endgame ids) away from spawn, or bans them:

```json
"structures": { "endgame": { "allow": true, "safeRadius": 1500 } }
```

**Cosmetic / identity fields**:

```json
"description": "A wind-scoured test of the peaks.",
"dimensionId": "adventure:the_gauntlet",
"portal": { "particleType": "minecraft:end_rod" }
```

- `description` is documentation-only (surfaced by tooling, never parsed).
- `dimensionId` is LEGACY — omit it; the id derives from
  `{namespace}:{filename}` (base worlds map to their vanilla ids).
- `portal.particleType` overrides the coloured portal particles with any
  particle id (`color` is ignored when set).

**Environment** (`"environment"` block → registers `{ns}:{slug}_type`;
invalid heights fall back to the base type, never a crash). Full field
list, vanilla dimension-type semantics: `skyColor`, `fogColor`,
`ambientLight` (0–1), `fixedTime` (tick of day, locks the sun),
`hasCeiling`, `hasSkylight`, `ultraWarm` (nether water rules), `natural`
(false = compasses/beds go weird), `bedWorks`, `respawnAnchorWorks`,
`piglinSafe`, `hasRaids`, `minY`, `height`, `logicalHeight`,
`coordinateScale` (nether-style travel ratio), `effects`
(`minecraft:overworld|the_nether|the_end` sky rendering), `infiniburn`
(block tag), `monsterSpawnLightLevel` (int or int-provider),
`monsterSpawnBlockLightLimit`:

```json
"environment": {
  "skyColor": "#4A2C6B", "fogColor": "#2A1A3E",
  "ambientLight": 0.3, "fixedTime": 18000,
  "hasSkylight": true, "hasCeiling": false,
  "ultraWarm": false, "natural": false,
  "bedWorks": false, "respawnAnchorWorks": true,
  "piglinSafe": false, "hasRaids": false,
  "minY": -64, "height": 512, "logicalHeight": 512,
  "coordinateScale": 1.0, "effects": "minecraft:the_end",
  "infiniburn": "#minecraft:infiniburn_overworld",
  "monsterSpawnLightLevel": 7, "monsterSpawnBlockLightLimit": 0
}
```

### Frame materials and orientation

`frameBlock` accepts four forms — what the frame ACCEPTS at ignition and
zone validation:

```jsonc
"portal": {
  "frameBlock": "minecraft:cherry_planks",            // single block id (classic)
  "frameBlock": "#minecraft:logs",                    // any block in a tag
  "frameBlock": ["minecraft:oak_planks", "#minecraft:logs"],  // union list (ids + tags)
  "frameBlock": { "colorGroup": "red" },              // "any red block" — sugar for
                                                      // #adventure:red_blocks (16 dye-colour
                                                      // tags ship in the jar datapack: wool,
                                                      // concrete, concrete powder, terracotta,
                                                      // glazed terracotta, stained glass)

  // Accepting is NOT placing: when the mod BUILDS a frame (arrival portals,
  // exitPortal), it needs one concrete block. Defaults: the plain frameBlock,
  // a list's first plain id, "<colour>_wool" for colour groups; tag-only
  // configs without it fall back to obsidian (boot WARN).
  "framePlaceBlock": "minecraft:oak_log",

  // Which axes ignition may consider. Absent = "any" (all three — the
  // pre-existing behaviour). "vertical" = X or Z, "horizontal" = Y
  // (end-portal style), "vertical_x" / "vertical_z" lock one axis.
  "orientation": "vertical_x"
}
```

Mixed frames are legal: any combination of accepted blocks bounds a valid
portal, and single-use decay resolves each frame block individually through
the decay map. Zones persist the accept forms they were ignited with —
changing a dimension's `frameBlock` later never invalidates existing portals
retroactively (immutable-snapshot rule, same as anchor/singleUse). Invalid
tag ids, unknown colour names, and unknown orientations WARN at boot and
never crash; a tag frame without `framePlaceBlock` WARNs that mod-built
frames fall back to obsidian.

Two hard-won rules from live verification (2026-07-23):

- **Persisted zone records always store a plain block id in `frameBlock`**
  (the placement block; accept forms ride in `frameAccepts`). Older mod
  builds `Identifier.of()` that field in an uncaught world-tick path — a
  `#tag` there crash-loops any server that downgrades.
- **Registered portal blocks are immune to neighbour-update popping**
  (`NetherPortalProtectionMixin`): vanilla re-validates portal frames as
  obsidian-only on ANY adjacent block change, and netherportalspread's
  corruption spread was silently deleting custom-framed arrival portals
  seconds after creation. Player-built vanilla portals are untouched.

### Per-part frame materials

`frameMaterials` gives different frame segments different requirements —
"stone base, log pillars, plank lintel". Mutually exclusive with
`frameBlock` (both present WARNs; frameMaterials wins):

```jsonc
"portal": {
  "frameMaterials": {
    "top": "minecraft:oak_planks",   // each part takes ANY accept form:
    "sides": "#minecraft:logs",      // id, #tag, list, {"colorGroup": ...}
    "bottom": "minecraft:stone"
  },
  // sides is tag-only, so mod-built frames need a concrete block for it
  "framePlaceBlock": "minecraft:oak_log"
}
```

The flood-fill accepts the UNION of all parts (any listed material bounds
the fill); validation then classifies each ring position — below the
interior's lowest row = `bottom`, above its highest = `top`, everything
else = `sides` — and checks that part's matcher. Parts left out accept
the union. **Vertical portals only (v1)**: horizontal (Y-axis) fills and
`end_exit`/`horizontal` configs validate against the union and WARN at
boot (top/bottom has no meaning on a flat ring).

Mod-built frames are built in kind: arrival portals and `exitPortal`
place each part's first plain id (else `framePlaceBlock`, else obsidian).
Zone records persist `framePartAccepts` (plain strings); older jars
ignore the field and validate against the union — graceful downgrade.

### Portal shapes

An optional `"shape"` constrains the geometry a player must build. Absent
(or `"standard"`) keeps free-form flood-fill — any frame-bounded shape up
to 128 interior blocks, today's behaviour:

```jsonc
"portal": {
  "shape": "door",       // exactly 1x2 interior (a single door), vertical
  "shape": "doorway",    // exactly 2x3 interior (the vanilla Nether opening), vertical
  "shape": "end_exit",   // horizontal ring (any footprint), end-portal style
  "shape": "end_gateway",// frameless 1-block teleporter (see below)

  // Explicit template: legend roles are "frame" (must match the frame
  // material), "interior" (must exactly cover the ignited opening), and
  // anything else = don't care. Row-major; for vertical portals the top
  // row is the highest Y and the template auto-tries both X and Z axes;
  // for horizontal portals rows map to +Z.
  "shape": {
    "type": "pattern",
    "template": ["FFFFF", "FF.FF", "F...F", "FF.FF", "FFFFF"],
    "legend": { "F": "frame", ".": "interior" }
  },

  // end_exit only: a pedestal block placed at the interior's centre cell
  // on ignition (dragon egg, trophy). Source-side scenery — arrival pads
  // and mod-built exit portals never get one (the exit-portal intact
  // check requires every interior cell to be a portal block).
  "centreBlock": "minecraft:dragon_egg"
}
```

**`end_gateway`** is fundamentally different: no frame, no flood-fill —
the igniter is used ON a block face (like placing a torch) and a real
`END_GATEWAY` block appears there, beam and all. `frameBlock` is not
required. Vanilla gateway travel is suppressed for mod-owned gateway
positions (`EndGatewaySuppressionMixin` cancels
`EndGatewayBlock.onEntityCollision`; player-placed vanilla gateways
elsewhere keep vanilla rules) — traversal and return trips run through
the same zone tick and return-target machinery as every other custom
portal (`isPortalBlock`/`collectPortalArea` recognise gateways). Zone
validity is simply "the gateway block still exists"; breaking it clears
the zone. Arrivals and `exitPortal`s for gateway dimensions are single
floating gateway blocks.

Shapes imply an orientation default (`door`/`doorway` → `"vertical"`,
`end_exit` → `"horizontal"`); an explicit `"orientation"` always wins, and
a contradictory combination (e.g. `door` + `horizontal`) WARNs at boot as
never-ignitable. Unknown shape names WARN at boot and reject every
ignition until fixed — never a crash, never auto-fixed. Validation runs
after the flood-fill (`PortalShape`, pure geometry): wrong-size interiors
simply don't ignite.

Mod-built frames follow the dimension's shape: arrival portals reuse the
source interior as always, and `exitPortal` builds a 1x2 frame for `door`
dims, the classic 2x3 for `doorway`/`standard`, and a horizontal 3x3
`END_PORTAL` pad ringed in the placement block for `end_exit`. Zone
records persist `shape`/`centreBlock` as plain strings — older jars ignore
the unknown fields (downgrade-safe), and pre-shape records restore as
`standard`.

### Immersive portals

```jsonc
"portal": {
  "immersive": true
  // or, to tune:
  // "immersive": {
  //   "enabled": true,           // an explicit false here = not immersive
  //   "previewDepth": 8,         // blocks projected behind the frame (1-16)
  //   "previewRadius": 2,        // candidate padding beyond the aperture (0-4)
  //   "refreshInterval": 4,      // ticks between delta refreshes (min 2)
  //   "activationRange": 24,     // blocks from the portal (1-64)
  //   "audio": true,             // biome ambience from the far side
  //   "entityPassthrough": true  // items, projectiles, orbs, mobs, villagers
  // }
}
```

Boot-re-read, like the rest of `portal` — changes apply without a world
wipe. Not serialised into `portal_links.json`: it is re-stamped onto
restored zones from live config every boot, so turning it on or off takes
effect for portals that already exist.

What it does, all server-side:

- **Preview.** The destination's real blocks are sent to nearby clients as
  fake block updates at real coordinates, so parallax is free. Each
  position is masked against that player's eye — only what could genuinely
  be seen through the aperture is sent, and anything that falls out of
  sight is restored the same pass.
- **Audio.** The far side's biome loop and mood sounds play at the portal.
  Most overworld biomes have no loop sound, so overworld-to-overworld
  portals are quiet by design; nether-family destinations are distinctive.
- **Pass-through.** Items, projectiles, XP orbs and falling blocks cross
  with their velocity intact, and so do living entities — you can lead a
  villager or a cow through, and build farms across a portal. Leads are
  detached before crossing (a cross-world leash is unrecoverable in
  vanilla). Vehicles with passengers are not handled yet.
- **Interior particles are suppressed** while immersive is on — the preview
  is the visual — leaving the coloured edge particles to trace the frame.

Known limits, and none of them are bugs:

- Vanilla's dimension-change **screen** still appears on traversal. The
  pre-loading removes the generation *stall*, not the screen, which is
  client-side.
- Lighting is approximated with invisible light at the aperture; the
  destination's real light levels are not reproduced.
- Water, grass and foliage take the SOURCE biome's colours — those are
  computed client-side from the biome the client thinks it is in.
- Entities on the far side are not visible.
- Gateway portals (`shape: end_gateway`) get particles, not projection.

`client/SPEC.md` specifies the client mod that
would lift each of those, and records which ones a client mod cannot help
with either.

### Portal auras

Portals affect their surroundings. **By default** (no config) every
linked pair leaks the OTHER side's nature through: at link time (arrival
creation, the only moment both ends are loaded) each side's terrain is
sampled — a solid-block histogram (top 5 = terrain palette), small
plants, logs mapped to tree features, still surface fluids — and slow
bounded passes then convert each side's surroundings using the far
side's palette. Sampling the real loaded terrain (not biome registries)
is deliberate: surface rules live in worldgen noise settings and aren't
practically queryable, and sampling captures modded terrain for free.

```jsonc
"portal": {
  "aura": {
    "enabled": false,        // explicit off switch (absent = on, derived)
    "radius": 12,            // blocks from portal centre (default 8, max 32)
    "interval": 40,          // ticks between passes (default 40, min 10)
    "blocksPerPass": 2,      // conversion attempts per pass (max 16)
    "budget": 300,           // lifetime conversions per side; -1 = endless
    "sides": "both",         // "source" | "target" | "both"

    // Emission override: replaces the SAMPLED palette this dimension
    // leaks into the other side. Empty list = emit nothing.
    "palette": ["minecraft:netherrack", "minecraft:blackstone"],
    "flora": ["minecraft:crimson_fungus"],
    "trees": ["minecraft:crimson_fungus"],   // ConfiguredFeature ids
    "fluids": ["minecraft:lava"],

    // Extras on top of either mode:
    "conversions": { "minecraft:obsidian": "minecraft:crying_obsidian" },
    "fireChance": 0.08       // per-pass ignition on exposed surfaces
  }
}
```

Guard rails (all enforced): the exclusion set (interior + frame ring +
registered portal positions) is never converted; passes are chunk-loaded
guarded and never load terrain; containers/block entities and bedrock are
never touched; fluids form only in depressions (solid floor + ≥3
enclosing walls) and count double against the budget; feature-placement
failures are silent no-ops. Palettes and budgets persist (zone records
and `aura-site-v1` records in `portal_links.json` — plain ids only;
older jars log unknown records as malformed and drop them, so a
downgrade quietly stops auras without crashing). Anchor arrivals sample
once — the first link wins.

**Nether-corruption preset** (the netherportalspread replacement — that
mod was retired in v3.7.0 in favour of auras; two spread engines fought
around the same portals and it converted custom arrival frames). Opt a
nether-y dimension in with:

```jsonc
"portal": {
  "aura": {
    "palette": ["minecraft:netherrack", "minecraft:blackstone",
                "minecraft:magma_block", "minecraft:crimson_nylium"],
    "flora": ["minecraft:crimson_fungus", "minecraft:crimson_roots"],
    "trees": ["minecraft:crimson_fungus"],
    "fluids": ["minecraft:lava"],
    "conversions": { "minecraft:obsidian": "minecraft:crying_obsidian" },
    "fireChance": 0.08,
    "budget": -1              // endless creep, netherportalspread-style
  }
}
```

### Anchor, single-use, and exit portals

Unlike worldgen config (creation-time-only, baked into `level.dat`), the whole portal block — anchor, singleUse, exitPortal included — is re-read every boot, so these features apply to existing dimensions without a world wipe.

```json
{
  "portal": {
    "frameBlock": "minecraft:crying_obsidian",
    "igniterItem": "minecraft:ender_eye",
    "anchor": { "pos": "spawn", "exit": "bed" },
    "singleUse": {
      "enabled": true,
      "delaySeconds": 10,
      "breakMode": "decay",
      "decayMap": { "minecraft:obsidian": "minecraft:crying_obsidian" }
    }
  },
  "exitPortal": { "enabled": true, "pos": "spawn", "target": "bed" }
}
```

**`portal.anchor`** — every source portal for this dimension lands at one fixed position; no per-source target portal or `portal_links.json` return entry is written. `pos` is `[x, y, z]` or `"spawn"` (the dimension's `spawn`, falling back to the border centre); Y is surface-resolved on arrival. `exit` controls the anchor arrival portal: `"origin"` (default — back where you came from, fast travel preserved), `"bed"` (your respawn point, obstruction-checked, never consumes respawn-anchor charges), or `"worldSpawn"`. `"bed"` is still a fast-travel primitive (enter anywhere, exit at your bed) — use `"origin"` when denying travel advantage matters.

**`portal.singleUse`** — the countdown starts at the source portal's first traversal and persists with the zone, so a restart resumes it. On expiry the interior clears and the frame breaks per `breakMode`: `"destroy"` (blocks removed, no drops), `"decay"` (each frame block swapped via the decay map — defaults cover obsidian→crying_obsidian, the cracked-brick families, `*_log`→stripped, `*_planks`→air; `decayMap` entries override), or `"partial"` (1–2 deterministically-picked frame blocks decay; the frame looks — and is — repairable and re-ignitable; note the pick doesn't check reachability, so a frame partly buried in terrain can decay a buried block). The igniter is not refunded.

**`exitPortal`** — the mod builds a small frame (the dimension's own `frameBlock`) at a deterministic offset from `pos` (`"spawn"` or `[x, y, z]`), registered as a permanent exit targeting the overworld with `target` semantics (`"bed"` default | `"worldSpawn"` | `"origin"`), and rebuilds it whenever it's found broken. Boot validation logs a WARN (never a crash, never an auto-fix) for any dimension with `singleUse.enabled` or an `anchor` but no exit portal — stranding by config is a bug, not a feature.

### `portal_links.json`

Persists the position and metadata of target-side portal blocks. Managed automatically; do not edit by hand.

### Sound effects

Sound fields (`igniteSound`, `enterSound`, `exitSound`) are config-file-only -- not exposed in commands. Accept any Minecraft sound ID (e.g., `entity.enderman.teleport`, `block.amethyst_block.chime`).

### Idle unloading

`idleUnloadMinutes` (default 5) controls how long a dimension with no players stays loaded before being saved and removed from memory. Vanilla dimensions (overworld, nether, end) and paradise_lost are never unloaded. Dimensions with forceloaded chunks are never unloaded. Re-created automatically when a player teleports in.

## Building

```bash
mise install                         # ensure Java 21
gradle wrapper --gradle-version 8.13 # one-time, generates gradlew
./gradlew build                      # output: build/libs/customdimensions-1.0.5-fork.jar
```

## Testing

```bash
./gradlew test
```

Tests cover config serialisation round-trips, definition defaults, colour parsing, direction arrays, and dimension manager state. Minecraft-dependent tests (registry lookups, block state checks) require the game test harness and are not included.

## Installation

Copy the built JAR to the server's `mods/` directory, or to `overlay/mods/` in a consumer repo for automatic deployment.

```bash
cp build/libs/customdimensions-1.0.5-fork.jar ../../overlay/mods/
```

## Fork notes

This is a fixed and extended fork of the Custom Dimensions mod (MIT licensed). The original had three bugs preventing it from working on 1.21.1:

1. **NetherPortalBlockMixin** targeted methods that don't exist on `NetherPortalBlock` in 1.21.1 (they live on `AbstractBlock`), causing a crash on startup. Removed entirely.
2. **MinecraftServerAccessor** and **SimpleRegistryAccessor** were not listed in the mixin config, causing `ClassCastException` at runtime. Registered.
3. **RefMap** was missing from the JAR. Proper Fabric Loom build generates it automatically.

All intermediary names (`class_XXXX`, `method_XXXX`, `field_XXXX`) have been translated to Yarn 1.21.1+build.3 human-readable names. New features (horizontal portals, per-dimension seeds, world type presets, sound effects, cooldown config, idle unloading, mob spawning control) were added on top of the fixed base.

## Licence

MIT
