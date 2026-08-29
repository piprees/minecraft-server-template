# Custom Dimensions

Runtime dimension creation for Minecraft 1.21.1 Fabric: custom portal frames, configurable igniters, coordinate scaling, per-dimension worldgen, and bidirectional travel. One JSON file per dimension, read at boot.

- Development contract (mixins, verification loop, jar gate): [`mods/AGENTS.md`](../AGENTS.md)
- Internals: [portals](../../docs/mod-internals/portals.md) · [worldgen & structures](../../docs/mod-internals/worldgen-structures.md) · [noise placement design intent](../../docs/design/noise-placement.md) · [diagnostics & seed rolling](../../docs/mod-internals/diagnostics.md) · [architecture](../../docs/mod-internals/architecture.md)

**Requirements:** Minecraft 1.21.1, Fabric Loader 0.16+, Fabric API, Java 21.

## What it does

- **Dimensions from config** — one self-contained JSON per dimension; global defaults in `settings.json`; consumer overlays merge, replace or skip per file.
- **12 world types** plus `ns:path` clones of any registered dimension, per-dimension seeds, noise presets, biome patches, per-set structure control.
- **Custom portals** — any frame block, any igniter, vertical or horizontal, optional shape templates and per-part materials; anchor, single-use and exit variants, exit shrines, and `exits` rules for leaving without a portal.
- **Portal auras** — each linked pair leaks the other side's sampled nature through, bounded by budgets.
- **Immersive portals** — see the destination through the frame, hear its biome, walk things through. Server-side only.
- **Per-dimension difficulty, mob control, borders and dimension types**, and idle unloading of empty worlds.

## Commands

All commands live under `/customdim` and require permission level 4.

> **There is no `/dimension create` and no `/portal link`.** Dimensions are created at boot from `config/custom-dimensions/dimensions/*.json`, and portals are configured in each dimension's `portal` block.

| Command | What it does |
| --- | --- |
| `/customdim create <name> <type> <seed> [noiseSettings] [structureDensity] [biome…]` | Create a runtime dimension. Prefer a config file; this is a debugging tool. |
| `/customdim destroy <name>` | Unload a runtime dimension. Does **not** scrub its `level.dat` entry — full removal procedure in the [`dimension-lifecycle-operations` skill](../../.claude/skills/dimension-lifecycle-operations/references/removal-procedure.md). |
| `/customdim list` | List managed dimensions. |
| `/customdim load <name>` | Queue a world load (drained on `END_SERVER_TICK`). |
| `/customdim locate biome\|structure <dimension> <id> [timeout]` | Async locate; returns a ticket UUID. |
| `/customdim locate-result <uuid>` | Collect the result of an async locate. |
| `/customdim sample-noise <dimension> <x> <z>` | Generation ground-truth oracle: the router climate point at `(x & ~3, 0, z & ~3)`. |
| `/customdim eval-df <dimension> <df_id> <x> <y> <z>` | Evaluate any registry density function through the dimension's own noise binding — the node-level oracle for DF-graph parity work. |
| `/customdim occupant <dimension> <chunkX> <chunkZ>` | Read a LOADED chunk's live `StructureStart`s (never generates); appends to the world save's own `customdimensions/census/occupancy__<ns>__<slug>.json`. |
| `/customdim structure-audit [group]` | Classify every structure set (group/rarity/theme); writes `.seed-rolling/lint/<hash>.structure-audit.json`. |
| `/customdim structure-census <dimension>` | Needs a LOADED dimension. Compares the live `StructurePlacementCalculator` against a headless `FactsEngine` measurement of the same seed; mismatches inline, no file. |
| `/customdim carver-draw <dimension> <chunkX> <chunkZ>` | Replay vanilla's would-be first draw beside the noise assignment for a chunk. |
| `/customdim render-check <dimension> <seed> [radius]` | Three heights and three water verdicts per column on ONE grid — the live try-out world, the facts' sampler, and the renderer's own rule. Runs across ticks; re-run to poll. |
| `/customdim render-check-headless <dimension> <seed> [radius]` | The same, facts ↔ render only. No world needed — the variant CI runs. |
| `/customdim render-check-reset` | Drop every render-check job so a check can be re-run in the same session. |
| `/customdim column-ladder <dimension> <seed> <x> <z>` | One column's block-state ladder beside its density ladder, with the first y they disagree about. `render-check` says WHICH columns disagree; this says why. |
| `/customdim lint [dimension]` | Every config fault that would score as a bad seed: a `want` naming a structure the dimension cannot place, a biome it never produces, a portal that cannot be lit. Returns the ERROR count. |
| `/customdim facts <dimension> <seed>` | Measure one (dimension, seed) — spawn, biomes, terrain, full structure census. Inline; writes nothing. |
| `/customdim score <dimension> <seed>` | Measure fresh, then judge against the dimension's configured intent. Inline; writes nothing. |
| `/customdim spike-compare <dimension> <seed> <count> <span>` | Headless sampler vs the live world, zero-tolerance; mismatches inline (capped). |
| `/customdim debug-prng <seed>` | PRNG diagnostics. |

**Read the artefact, never the RCON line** — RCON concatenates feedback with no separator, truncates at a few KB, and cannot tell a timeout from a success. Artefact paths and the full contract: [`mods/AGENTS.md` § Diagnostic artefacts](../AGENTS.md#diagnostic-artefacts).

There is no offline checker script. `./dev verify` states where each verification lives: worldgen drift is a boot-time WARN (`DimensionFingerprints`), portal state is validated on load (`PortalStateValidator`), config faults are `/customdim lint`. The old Python checkers are JUnit tests under `src/test/java/` — `ScorecardDistributionTest` (catches a scoring model gone useless) and `DimensionLintTest`.

### Seed roller — a browser tool, not a command

Rolling, looking, trying a candidate out and picking one all happen on a page the mod hosts. `./dev seeds` opens it (default `http://127.0.0.1:8765/`; `SEED_VIEWER_PORT` moves it, `0` turns it off). Only `docker-compose.local.yml` publishes the port — nothing listens in the cloud profile, and **the page has no authentication**.

| Route | What it does |
| --- | --- |
| `GET /` | Every dimension at once, a card per candidate, and a modal carrying the render, the score and the scorecard's own per-criterion reasons |
| `GET /api/bank` | The same bank as JSON |
| `POST /pipeline/start` `{count, dim}` · `POST /pipeline/stop` · `GET /pipeline-status` | Roll on a background thread; the server stays playable and RCON keeps answering throughout |
| `POST /tryout` `{dim, seed}` · `POST /tryout/back` · `GET /tryout/status` | Build a throwaway world from that candidate's seed, teleport in creative and flying, come back to the overworld at 0,0 |
| `POST /pick` `{dim, seed}` | Write the seed — and, if you are standing in that candidate's try-out, your position as the dimension's spawn — into the consumer's committed overlay |
| `POST /render` `{dim, seed, resolution}` | Draw a candidate's map; low-res comes from its persisted grid, high-res samples a fresh wider one |
| `GET /census/<dim>/<seed>` | One candidate's structure census — banked counts and nearest distances, plus every noise-managed site as `[x, z, structureId]` by group |

A try-out world never enters the DIMENSION registry, so it never reaches `level.dat` and needs no scrub; it expires ten minutes after the last person leaves. **Every mod rebuild mints a new `InputHash`, which starts a fresh bank** — roll after the jar settles, not during.

Changing the page means rebuilding the mod (`ViewerPage` and `SeedServer` read the markup and assets out of the jar), and **a CSS change takes one step more:** `web/app.css` is Tailwind source, `./build-viewer-css.sh` compiles it to `web/app.built.css`, and Gradle never runs the compile — an unbuilt edit rebuilds with the old stylesheet and says nothing. Commit both files. Utilities come only from `@apply` inside `app.css`, so a Tailwind class written into the HTML or JS generates no rule.

## Configuration

Everything lives in the server's data directory under `config/`.

```
config/custom-dimensions/
├── settings.json              # namespace, idleUnloadMinutes, frames, defaults
├── dimensions/<slug>.json     # one self-contained file per dimension
└── overlay/dimensions/        # consumer overrides, staged by deploy.sh/dev-up.sh
                               # from overlay/config/custom-dimensions/
```

The slug comes from the filename. Four filenames are reserved (`overworld.json`, `the_nether.json`, `the_end.json`, `paradise_lost.json`): they resolve to existing dimension ids instead of creating new ones, and are managed like every other dimension — each field below applies to them ([AGENTS.md § Dimensions](../../AGENTS.md#dimensions)). `"seed": "env"` reads the `SEED` environment variable. **Overlay resolution:** a top-level `"overrides"` object deep-merges over the platform default; a file without one replaces it entirely; an empty `{}` skips the dimension; overlay-only files are consumer-added dimensions namespaced by `BRAND_SLUG`.

A whole dimension, `dimensions/cherry_pocket.json`:

```json
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
    "color": "#FF9EC6", "lightLevel": 8, "scale": 1.0, "cooldown": 40,
    "sounds": { "ignite": "block.portal.trigger", "enter": "block.portal.travel", "exit": "block.portal.travel" }
  }
}
```

`settings.json`:

```json
{
  "namespace": "adventure",
  "idleUnloadMinutes": 5,
  "frames": { "overworld": "minecraft:mossy_stone_bricks", "nether": "minecraft:obsidian",
              "end": "minecraft:end_stone_bricks" },
  "defaults": { "frameBlock": "minecraft:crying_obsidian",
                "borders": { "player": 8192, "generation": 8192 },
                "difficulty": { "mobMultiplier": 1.0 } }
}
```

### Coordinate scaling

`portal.scale` is the Nether-style travel ratio, stated the way people say it: **"8 nether : 1 over"**. One block walked in the DESTINATION is worth `scale` blocks back home, so **entering divides and returning multiplies**.

- `scale: 8` — walk 10 blocks in the dimension, you have covered 80 at home. A portal at overworld `(1888, -3624)` arrives at `(236, -453)`.
- `scale: 1` — coordinates match 1:1.
- `scale: 0.125` — the inverse: a *sprawling* dimension. **This is almost always a mistake**, and it is the wrong way to write "nether-style 1:8". All 52 non-1.0 dimensions use whole ratios; `ShippedDimensionReachabilityTest` fails the build if a fractional scale reappears.

**`borders.player` must be `overworldBorder / scale`.** Otherwise portals built near the overworld border arrive outside the destination's border, where vanilla forbids breaking or placing any block and the player is stranded. `PortalSafetyValidator` WARNs at boot with the usable source radius and the border that would fix it; it never auto-fixes.

### Worldgen

- **`type`** — `overworld`/`multi_biome`, `nether`, `end`, `void`, `superflat`, `cave`, `checkerboard`, `sky_islands`, `nether_islands`, `amplified`, `large_biomes`, `single_biome`, or `ns:path` to clone any registered dimension. The four reserved filenames name no type; `DimensionConfig.getType()` supplies the family. Writing one moves that dimension onto another family's group set.
- **`checkerboard`** tiles the `biomes` list in a fixed grid (seed-independent layout, seeded terrain); `checkerboardScale` 0-62 (default 2) sets the cell size, `2^(scale+4)` blocks.
- **`superflat`** takes `layers` (bottom-up `{block, height}` list) and `flatBiome`; invalid config falls back to the whole default bedrock/dirt/grass stack.
- **`settingsOverrides`** swaps `seaLevel`, `defaultBlock`, `defaultFluid`, `disableMobGeneration` on the type's (or preset's) generator settings; invalid values warn and keep the base per field.
- **`biomes`** entries may be `{id, parameters}` objects with explicit multi-noise intervals (a number or `[min, max]` per axis, -2..2). The biome claims exactly that region; unset axes span everything.
- **`biomePatches`** overrides the generated layout in three modes: stamp (`{biome, x, z, radius}` claims the area), clipped swap (`replace` recolours only the matching biome inside the area, organic shape kept), and global swap (`scope: "global"` replaces a biome dimension-wide, or uses the area as a selector for every biome touching it). `shape: circle|square`, `blend` edge jitter (default 8 blocks). A stamp at (0,0) guarantees the spawn biome. Backed by a codec-registered `PatchedBiomeSource` wrapper so the generator persists cleanly.
- **`borders`** — `player` sets the world's vanilla border at boot; `generation` is tooling metadata for Chunky/render bounds.

Worldgen fields (`type`, `noiseSettings`, `biomes`, `seed`) are **creation-time-only**: changing one on an existing world needs a wipe ([D2](../../TROUBLESHOOTING.md#d2)).

#### Noise presets (`noiseSettings`)

`"noiseSettings": "adventure:wide" | "adventure:compressed"` swaps the dimension's `ChunkGeneratorSettings` for a jar-baked preset (wide: Terratonic + ultrasmooth semantics, 512-block build height; compressed: tighter climate bands, 1.5x vertical scale). Both are generated by `scripts/gen-terrain-presets.py` from the pinned Tectonic + Terralith jars — **regenerate and commit whenever either pin bumps**.

The presets are self-contained: removing Tectonic and/or Terralith does not break the boot. Density functions are cloned into the adventure namespace, noises keep their original ids as byte-identical copies, and vanilla-shipped `minecraft:` ids are never copied. With Terralith removed, adventure-dim aquifers and cave shapes revert to vanilla semantics (generation-only, no boot risk); with the mods present, generation is bit-identical to the pre-hardening jar. The rules behind that split — and why breaking them repaints the real overworld — are in [worldgen-structures.md](../../docs/mod-internals/worldgen-structures.md#worldgen-self-containment-optional-mods-hardening).

### Structures

Noise placement is the default for every managed dimension: sets are sorted into seven meta-groups, biome-filtered against the dimension's own biome source, and each active group gets one placement. `structureDensity` picks the profile; the peaceful shift (difficulty-driven) outranks it.

```json
"structures": {
  "wants": { "shipwreck": { "min": 0, "max": 256 } },
  "shuns": { "monument": {} },
  "mode": "allow",
  "list": ["minecraft:villages", "adventure:exit_shrines"],
  "spacing": { "minecraft:villages": { "spacing": 24, "separation": 8 } },
  "force": [ { "structure": "minecraft:ancient_city", "x": 1200, "z": -800 } ],
  "endgame": { "allow": true, "safeRadius": 1500 }
}
```

- **`wants`** and **`shuns`** name structures by short name (`references/structure-names.md`). A want multiplies its pool weight by 1.2 and also bypasses the biome-affinity filter; a shun divides it by 1.5. Pool weights are carried at 15 units each (`NoisePoolBuilder.WEIGHT_RESOLUTION`), so both factors are exact integers at every weight down to 1 — a rounded 1.2 would discard the want on precisely the rare and endgame structures wants usually name. The scale is uniform, so no unfavoured structure's share of the draw moves. A shun never reaches zero: discouraging is not removing, and `exclude` is what removes. Naming a structure in both cancels (lint reports it), and a name resolving to a `#tag` is dropped by both. The same numbers drive the roller's scorecard, so what a roll searches for is what the world is more likely to make. Falls back to `seedRoll.wants`/`seedRoll.shuns` when the block names none.
- **`mode`** filters the ORGANIC sets at the per-world calculator rebuild: `allow` keeps only sets in `list`, `reject` drops them, `none` drops every organic set. The exit-shrines opt-in precedes the filter. Unknown modes warn and disable the filter.
- **`spacing`** maps structure SET ids to exact `{spacing, separation}` values, overriding the theme-based `structureDensity` factors for those sets. **Grid placement only** — a noise-managed set has no grid to space, so its group's `NoiseStructurePlacement` decides placement and the override is inapplicable. It applies to `adventure:exit_shrines`, to the pass-through sets, and to every set in a dimension whose noise plan is suppressed (void, superflat). Naming any other set WARNs at boot ([T51](../../TROUBLESHOOTING.md#t51)); the control for a noise-managed dimension is `structureDensity` and the per-group profile.
- **`force`** places an exact STRUCTURE at exact block coordinates; the start lands in that block's chunk. Each entry becomes a synthetic single-structure set with a `customdimensions:fixed` placement (a `RandomSpreadStructurePlacement` subclass, so vanilla `/locate` finds it and density rescaling exempts it). Unknown structures (removed mods) warn and skip — never a boot break. Forced placements are additive after `mode`, so `"mode": "none"` + `force` = ONLY the forced structures.
  - The mod performs the start attempt itself (`ChunkGeneratorForcedStartMixin`), bypassing the structure's own biome gate and other mods' cancellable HEAD injects — all seven YUNG's structure mods cancel every vanilla start of the type they replace ([T25](../../TROUBLESHOOTING.md#t25)). Every other set keeps vanilla behaviour exactly, and forced structures still get terrain adaptation (beard/kernel).
  - **Out-of-biome forced structures generate but are NOT locatable** — `/locate` reads an index vanilla builds only for structures whose valid biomes intersect the biome source, and that index is untouched on purpose. Verify with `/customdim structure-census` and the boot log's `forced <structure> generated at chunk [x, z]` INFO line (a WARN when the structure's own generation rejects the position). One forced position per 32-chunk region is locatable; extras in the same region still generate (warned at boot).
- **`endgame`** keeps end-game structures away from spawn (`safeRadius`) or bans them (`allow: false`).
- `wants`, `shuns`, `mode`, `spacing` and `force` are a RUNTIME rebuild — re-read every boot, newly generated chunks only — not creation-time worldgen.
- Roller parity: filtered sets measure as absent, forced structures as constant distances (`structure_placement.forced_distance`/`mode_drops`). The fork-config GUI does not expose these fields yet.

**Assignment.** Every noise-managed site has exactly one assigned structure (`StructurePick.assignedStructure`, a deterministic weighted selection from the group's pool). Only the assigned structure can start there, its biome predicate bypassed; a structural rejection leaves the site empty and is appended to the world save's own `customdimensions/census/rejections__<ns>__<slug>.json`. Vanilla `/locate` on a multi-structure set walks placements without knowing which structure occupies each site, so `structure-census` and `/customdim occupant` are the sanctioned instruments for "where is structure X" and "what occupies this chunk".

**Exit shrines** (`exitShrines`) scatter `adventure:exit_shrine` jigsaw ruins (jar datapack; templates from `scripts/gen-exit-shrine.py`) whose beacon-marked frames self-register as exit zones on chunk load. The set ships at frequency 0.001 and is raised to full only for opted-in dimensions. Shrine frames are rebuilt in the dimension's own `framePlaceBlock` at registration (one template, any material), and spacing derives from `borders.player` — `clamp(radius/32, 12..48)` chunks, so a 256-radius pocket gets 1-2 shrines rather than a grid — unless `structures.spacing` sets it explicitly.

### Difficulty

```json
"difficulty": {
  "hostileSpawning": true, "mobMultiplier": 1.5, "playerLuck": 0.5,
  "attributes": { "health": true, "damage": true, "armor": false, "speed": false, "knockback": false },
  "depthScaling": { "enabled": true, "startY": 0, "endY": -64, "minMultiplier": 1.0, "maxMultiplier": 2.0 }
}
```

`mobMultiplier` scales hostile mobs only at spawn, as attribute modifiers persisted in NBT (`0` = effectively peaceful); `attributes` booleans choose WHICH attributes it touches (default health + damage). `playerLuck` is a flat luck bonus applied on join and world change, boosting loot quality inside the dimension. `depthScaling` ramps the multiplier from `minMultiplier` at `startY` to `maxMultiplier` at `endY`. Peaceful dimensions drop dungeon-theme structure sets automatically on the GRID path; a noise-managed dimension takes `NoiseGroupPlan`'s `mobMultiplier` shift instead, which a per-group `structures.noise` entry outranks.

### Exits

Leaving the dimension without a portal. Triggers: `"void"` (fell below minY, fires before vanilla void damage), `"death"` / `"death:<cause>"` / `"death:mob:<id>"`, `"enderPearl"` (pearl thrown — consumed, no teleport), `"fallFrom"` (fell `minHeight` blocks). Targets: `"bed"` | `"worldSpawn"` | `"origin"` (where the player entered from), or a dimension link. Actions: `"teleport"` (intercepts — for a death trigger this CANCELS the death), `"respawnAt"` (die normally, respawn at the target — needs a real player to verify; carpet bots can't respawn), `"kill"` (explicit vanilla void death).

```json
"exits": {
  "void": { "target": "origin", "action": "teleport" },
  "death": { "target": { "dimension": "minecraft:overworld", "arrival": "spawn" }, "action": "respawnAt" },
  "enderPearl": { "target": "worldSpawn", "action": "teleport" },
  "fallFrom": { "minHeight": 100, "target": "origin", "action": "teleport" }
}
```

**Dimension links.** Every exit target (`exitPortal.target`, `portal.anchor.exit`, `exits` rules) also accepts `{"dimension": "ns:slug", "arrival": "anchor"|"spawn"|[x, y, z]}`, so dimensions compose into chains and hubs. Arrivals are safe (slow falling), with a per-player anti-loop cooldown; boot validation covers death-only exits and dangling links.

### Environment

An `"environment"` block registers a per-dimension `DimensionType` as `{ns}:{slug}_type`. Unset fields inherit the base type; invalid heights fall back to it rather than crashing. Vanilla dimension-type semantics throughout; `skyColor`/`fogColor` are client-side and configurator-only.

```json
"environment": {
  "skyColor": "#4A2C6B", "fogColor": "#2A1A3E", "ambientLight": 0.3, "fixedTime": 18000,
  "hasSkylight": true, "hasCeiling": false, "ultraWarm": false, "natural": false,
  "bedWorks": false, "respawnAnchorWorks": true, "piglinSafe": false, "hasRaids": false,
  "minY": -64, "height": 512, "logicalHeight": 512,
  "effects": "minecraft:the_end", "infiniburn": "#minecraft:infiniburn_overworld",
  "monsterSpawnLightLevel": 7, "monsterSpawnBlockLightLimit": 0
}
```

`ambientLight` is 0–1, `fixedTime` a tick of day (locks the sun), `natural: false` makes compasses and beds go weird, `effects` picks `minecraft:overworld|the_nether|the_end` sky rendering, `infiniburn` is a block tag, `monsterSpawnLightLevel` an int or int-provider.

**Cosmetic and identity fields:** `"description"` is the dimension's one description, read by the mod and shown in the viewer. `portal.particleType` (any particle id, e.g. `minecraft:end_rod`) overrides the coloured particles, and `color` is ignored when it is set. `dimensionId` is LEGACY — omit it; the id derives from `{namespace}:{filename}`, and the four reserved filenames resolve to their existing ids.

## Portals

Frames may be vertical (X or Z) or horizontal (floor and ceiling, Y-axis): build the ring flat on the ground and right-click the top face with the igniter. Particles are a hex `color` per portal, rendered on both sides. `cooldown` is 0-200 ticks. Sound fields (`sounds.ignite`/`enter`/`exit`) are config-file-only and accept any Minecraft sound id. Target-side portals are built automatically and stepping back in returns you.

**The whole `portal` block is re-read every boot** — unlike worldgen, frame materials, shapes, auras, immersive settings, anchors, single-use and exit portals all apply to existing dimensions without a world wipe.

### Frame materials and orientation

`frameBlock` accepts four forms — what the frame ACCEPTS at ignition and zone validation:

```jsonc
"portal": {
  "frameBlock": "minecraft:cherry_planks",                     // single block id
  "frameBlock": "#minecraft:logs",                             // any block in a tag
  "frameBlock": ["minecraft:oak_planks", "#minecraft:logs"],   // union list (ids + tags)
  "frameBlock": { "colorGroup": "red" },                       // any red block: sugar for
                                                               // #adventure:red_blocks
  "framePlaceBlock": "minecraft:oak_log",                      // what mod-built frames PLACE
  "orientation": "vertical_x"                                  // axes ignition may consider
}
```

**Accepting is not placing:** when the mod builds a frame (arrival portals, `exitPortal`) it needs one concrete block. `framePlaceBlock` defaults to the plain `frameBlock`, a list's first plain id, or `<colour>_wool` for colour groups; a tag-only config without it falls back to obsidian with a boot WARN. The 16 dye-colour tags ship in the jar datapack (wool, concrete, concrete powder, terracotta, glazed terracotta, stained glass). `orientation` absent = "any"; `"vertical"` = X or Z, `"horizontal"` = Y (end-portal style), `"vertical_x"`/`"vertical_z"` lock one axis.

Mixed frames are legal: any combination of accepted blocks bounds a valid portal, and single-use decay resolves each frame block individually. Zones persist the accept forms they were ignited with, so changing a dimension's `frameBlock` later never invalidates existing portals retroactively. Invalid tag ids, unknown colour names and unknown orientations WARN at boot and never crash.

Two rules: **persisted zone records always store a plain block id in `frameBlock`** (accept forms ride in `frameAccepts`) — a `#tag` there crash-loops any server that downgrades to an older jar. And **registered portal blocks are immune to neighbour-update popping** (`NetherPortalProtectionMixin`), because vanilla re-validates portal frames as obsidian-only on ANY adjacent block change and block-converting mods were silently deleting custom-framed arrivals; player-built vanilla portals are untouched.

### Per-part frame materials

`frameMaterials` gives frame segments different requirements — "stone base, log pillars, plank lintel". Mutually exclusive with `frameBlock` (both present WARNs; `frameMaterials` wins):

```jsonc
"portal": {
  "frameMaterials": {
    "top": "minecraft:oak_planks",   // each part takes ANY accept form:
    "sides": "#minecraft:logs",      // id, #tag, list, {"colorGroup": ...}
    "bottom": "minecraft:stone"
  },
  "framePlaceBlock": "minecraft:oak_log"   // sides is tag-only, so mod-built frames need this
}
```

The flood-fill accepts the UNION of all parts; validation then classifies each ring position — below the interior's lowest row = `bottom`, above its highest = `top`, everything else = `sides` — and checks that part's matcher. Parts left out accept the union. Mod-built frames place each part's first plain id (else `framePlaceBlock`, else obsidian). **Vertical portals only (v1)**: horizontal and `end_exit` configs validate against the union and WARN at boot. Zone records persist `framePartAccepts`; older jars ignore the field and validate against the union.

### Portal shapes

An optional `"shape"` constrains the geometry a player must build. Absent (or `"standard"`) keeps free-form flood-fill — any frame-bounded shape up to 128 interior blocks:

```jsonc
"portal": {
  "shape": "door",        // exactly 1x2 interior, vertical
  "shape": "doorway",     // exactly 2x3 interior (the vanilla Nether opening), vertical
  "shape": "end_exit",    // horizontal ring (any footprint), end-portal style
  "shape": "end_gateway", // frameless 1-block teleporter (see below)

  "shape": { "type": "pattern",                               // explicit template
             "template": ["FFFFF", "FF.FF", "F...F", "FF.FF", "FFFFF"],
             "legend": { "F": "frame", ".": "interior" } },
  "centreBlock": "minecraft:dragon_egg"   // end_exit only: pedestal at the centre cell
}
```

Template legend roles are `frame` (must match the frame material), `interior` (must exactly cover the ignited opening), and anything else = don't care. Row-major; for vertical portals the top row is the highest Y and the template auto-tries both X and Z axes, for horizontal portals rows map to +Z. `centreBlock` is source-side scenery placed on ignition — arrival pads and mod-built exit portals never get one, because their intact check requires every interior cell to be a portal block.

**`end_gateway`** is fundamentally different: no frame, no flood-fill — the igniter is used ON a block face (like placing a torch) and a real `END_GATEWAY` block appears there, beam and all. `frameBlock` is not required. Vanilla gateway travel is suppressed for mod-owned gateway positions (`EndGatewaySuppressionMixin`; player-placed vanilla gateways elsewhere keep vanilla rules), and traversal runs through the same zone tick and return-target machinery as every other portal. Zone validity is "the gateway block still exists". Arrivals and exit portals for gateway dimensions are single floating gateway blocks.

Shapes imply an orientation default (`door`/`doorway` → vertical, `end_exit` → horizontal); an explicit `"orientation"` always wins, and a contradictory combination WARNs at boot as never-ignitable. Unknown shape names WARN and reject every ignition until fixed. Mod-built frames follow the dimension's shape: `exitPortal` builds 1x2 for `door` dims, the classic 2x3 for `doorway`/`standard`, and a horizontal 3x3 `END_PORTAL` pad ringed in the placement block for `end_exit`. Pre-shape zone records restore as `standard`.

### Immersive portals

```jsonc
"portal": {
  "immersive": {
    "enabled": true,        // an explicit false here = not immersive
    "previewDepth": 8,      // blocks projected behind the frame (1-16)
    "previewRadius": 2,     // candidate padding beyond the aperture (0-4)
    "refreshInterval": 4,   // ticks between delta refreshes (min 2)
    "activationRange": 24,  // blocks from the portal (1-64)
    "audio": true,          // biome ambience from the far side
    "entityPassthrough": true   // items, projectiles, orbs, mobs, villagers
  }
}
```

Absent (or `true`) means immersive with all defaults; `"immersive": false` is the opt-out. It is not serialised into `portal_links.json` — it is re-stamped onto restored zones from live config every boot, so turning it on or off reaches portals that already exist. All server-side; a vanilla client gets everything:

- **Preview.** The destination's real blocks are sent to nearby clients as fake block updates at real coordinates, so parallax is free. Each position is masked against that player's eye, and anything falling out of sight is restored the same pass.
- **Audio.** The far side's biome loop and mood sounds play at the portal. Most overworld biomes have no loop sound, so overworld-to-overworld portals are quiet by design.
- **Pass-through.** Items, projectiles, XP orbs, falling blocks and living entities cross with velocity intact — lead a villager through, or build farms across a portal. Leads are detached before crossing (a cross-world leash is unrecoverable in vanilla). Vehicles with passengers are not handled yet.
- **Interior particles are thinned, not removed** — about a twelfth of the normal density.

Known limits, none of them bugs: vanilla's dimension-change **screen** still appears (pre-loading removes the generation stall, not the screen, which is client-side); lighting is approximated with invisible light at the aperture; water, grass and foliage take the SOURCE biome's colours (the client computes them from the biome it thinks it is in); far-side entities are invisible; gateway portals get particles, not projection. [`client/SPEC.md`](client/SPEC.md) specifies the client mod that would lift each, and which ones a client mod cannot help with either.

### Portal auras

**By default** (no config) every linked pair leaks the OTHER side's nature through: at link time each side's terrain is sampled — a solid-block histogram (top 5 = terrain palette), small plants, logs mapped to tree features, still surface fluids — and slow bounded passes then convert each side's surroundings using the far side's palette. Sampling the real loaded terrain rather than biome registries is deliberate: surface rules live in worldgen noise settings and aren't practically queryable, and sampling captures modded terrain for free.

```jsonc
"portal": {
  "aura": {
    "enabled": false,        // explicit off switch (absent = on, derived)
    "subsume": "natural",    // what may be converted: none | natural | everything
    "radius": 12,            // blocks from portal centre (default 8, max 32)
    "interval": 40,          // ticks between passes (default 40, min 10)
    "blocksPerPass": 2,      // conversion attempts per pass (max 16)
    "budget": 300,           // lifetime conversions per side; -1 = endless creep
    "sides": "both",         // "source" | "target" | "both"

    // Emission override: replaces the SAMPLED palette this dimension leaks into
    // the other side (empty list = emit nothing). This set plus fireChance IS
    // the nether-corruption preset, which replaced the netherportalspread mod.
    "palette": ["minecraft:netherrack", "minecraft:blackstone",
                "minecraft:magma_block", "minecraft:crimson_nylium"],
    "flora": ["minecraft:crimson_fungus", "minecraft:crimson_roots"],
    "trees": ["minecraft:crimson_fungus"],   // ConfiguredFeature ids
    "fluids": ["minecraft:lava"],

    // Extras on top of either mode:
    "conversions": { "minecraft:obsidian": "minecraft:crying_obsidian" },
    "fireChance": 0.08       // per-pass ignition on exposed surfaces
  }
}
```

`subsume` is `natural` by default (only blocks tagged `#adventure:aura_protected` are eligible), `none` withholds fire and fluids as well as replacements, and `everything` converts anything — but **land claimed in Open Parties and Claims is never converted under any mode**, and `subsume` gates explicit `conversions` too.

Guard rails, all enforced: the exclusion set (interior + frame ring + registered portal positions) is never converted; passes are chunk-loaded guarded and never load terrain; containers, block entities and bedrock are never touched; fluids form only in depressions (solid floor + ≥3 enclosing walls) and count double against the budget; feature-placement failures are silent no-ops. Palettes and budgets persist as plain ids in `portal_links.json` (older jars log unknown records as malformed and drop them, so a downgrade quietly stops auras without crashing). Anchor arrivals sample once — the first link wins.

### Anchor, single-use, and exit portals

```json
{
  "portal": {
    "frameBlock": "minecraft:crying_obsidian",
    "igniterItem": "minecraft:ender_eye",
    "anchor": { "pos": "spawn", "exit": "bed" },
    "singleUse": { "enabled": true, "delaySeconds": 10, "breakMode": "decay",
                   "decayMap": { "minecraft:obsidian": "minecraft:crying_obsidian" } }
  },
  "exitPortal": { "enabled": true, "pos": "spawn", "target": "bed" }
}
```

**`portal.anchor`** — every source portal for this dimension lands at one fixed position; no per-source target portal or `portal_links.json` return entry is written. `pos` is `[x, y, z]` or `"spawn"` (the dimension's `spawn`, falling back to the border centre); Y is surface-resolved on arrival. `exit` controls the anchor arrival portal: `"origin"` (default — back where you came from, fast travel preserved), `"bed"` (your respawn point, obstruction-checked, never consumes respawn-anchor charges), or `"worldSpawn"`. `"bed"` is still a fast-travel primitive — use `"origin"` when denying travel advantage matters.

**`portal.singleUse`** — the countdown starts at the source portal's first traversal and persists with the zone, so a restart resumes it. On expiry the interior clears and the frame breaks per `breakMode`: `"destroy"` (blocks removed, no drops), `"decay"` (each frame block swapped via the decay map — defaults cover obsidian→crying_obsidian, the cracked-brick families, `*_log`→stripped, `*_planks`→air; `decayMap` entries override), or `"partial"` (1–2 deterministically-picked frame blocks decay, leaving a repairable and re-ignitable frame; the pick doesn't check reachability, so a frame partly buried in terrain can decay a buried block). The igniter is not refunded.

**`exitPortal`** — the mod builds a small frame in the dimension's own material at a deterministic offset from `pos` (`"spawn"` or `[x, y, z]`), registers it as a permanent exit with `target` semantics (`"bed"` default | `"worldSpawn"` | `"origin"` | a dimension link), and rebuilds it whenever it is found broken. Boot validation WARNs (never crashes, never auto-fixes) for any dimension with `singleUse.enabled` or an `anchor` but no exit portal — stranding by config is a bug, not a feature.

### State and idle unloading

`portal_links.json` persists the position and metadata of portal zones and target-side portal blocks. Managed automatically; do not edit by hand.

`idleUnloadMinutes` (default 5) controls how long a dimension with no players stays loaded before being saved and removed from memory; it is re-created automatically when a player teleports in. Vanilla dimensions (overworld, nether, end) and paradise_lost are never unloaded, nor are dimensions with forceloaded chunks.

## Building, testing, installing

```bash
gradle wrapper --gradle-version 8.13    # one-time, generates gradlew
mise install                            # ensure Java 21
mise exec -- ./gradlew build            # output: build/libs/customdimensions-<mod_version>.jar
mise exec -- ./gradlew test

cd ~/Projects/elfydd && ./dev link      # once: symlinks build/libs into the bundle slot
cd ~/Projects/elfydd && ./dev up        # installs the current build
```

Always `mise exec --`: `mods/mise.toml` pins `java = "temurin-21"`, and `mise exec --` is what applies that pin ([P4](../../TROUBLESHOOTING.md#p4)). Tests cover config serialisation round-trips, definition defaults, colour parsing, direction arrays, dimension manager state, and the pure scoring/lint logic. Minecraft-dependent checks (registry lookups, block states) run through the verification loop in [`mods/AGENTS.md`](../AGENTS.md#verification-loop), not JUnit.

**Never copy the jar into a server's `data/mods/` by hand** — that directory is managed, and a hand-placed jar is overwritten or pruned on the next boot.

**To ship it:** cut a platform release. `release.yml` builds the mod, stages the remapped jar as `dist/local-mods/customdimensions.jar`, `build-stack-bundle.sh` packs it into the stack bundle, and `deploy.sh` / `dev-up.sh` install it. Nothing is published to Modrinth and no jar is committed to git. Full workflow: `.claude/skills/local-stack-testing/SKILL.md` § Linked local development.

## Fork notes

A fixed and extended fork of the Custom Dimensions mod (MIT). Three bugs stopped the original working on 1.21.1: `NetherPortalBlockMixin` targeted methods that live on `AbstractBlock` in 1.21.1 (removed entirely); `MinecraftServerAccessor` and `SimpleRegistryAccessor` were missing from the mixin config, causing `ClassCastException` at runtime (registered); and the jar shipped without a refmap (a proper Loom build generates it). All intermediary names were translated to Yarn 1.21.1+build.3.

## Licence

MIT
