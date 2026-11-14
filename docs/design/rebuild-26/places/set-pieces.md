# Set pieces

A set piece is one of our own places, built at worldgen time from vanilla blocks and vanilla's own structure templates: a crater with a meteorite in it, a bone pit, a lava fall down a pit wall, a crashed ship nose-down in soul sand, a quarantine wall, a burned camp, a wart farm. It is a structure like any other: it has an id, it is placed by our structure control, the beardifier and `terrainAdaptation` see it, the survey's census counts it and the web map draws it. The mod ships no `.nbt`; nothing is copied from another mod or from vanilla.

The kit is generic. A set piece is data in a definition or the shared library; no code names a dimension, and every kind below serves any dimension that asks for it.

## What exists today

| Piece | What it does now | What set pieces add to it |
| --- | --- | --- |
| `StructureRuntime.control` | Builds each level's structure state: filter, swap, spacing, noise groups, forced starts and wants as one-chunk sets (`StructurePlacements.Fixed`), group sites (`StructurePlacements.Noise`) | Nothing new: a set piece's structure id is placed by `force`, `wants`, a group or a zone exactly as a mod's is |
| `StructureSites.choose` / `settle` | Picks the chunk for a forced start or a want in a seeded ring; moves a group site onto a chunk whose biome suits a pooled structure | The `accepts` predicate gains the set piece's seat test (ground present, not void), so a sky world's sites settle on island ground |
| `PinnedGround` | A forced start with `y` sees solid stone below `y` and air above while it finds its point | Seat `pinned` uses it unchanged |
| `StructureLoot` / `LootRules` | Adds an item at a chance to container loot inside a named structure's pieces | Applies to set pieces by their id or group |
| `StructureAdaptation` / Beardifier mixin | Replaces a structure's terrain adjustment per dimension | Applies by id; a set piece also names its own default |
| `StructureCensus`, `StructureStarts`, `StructureLayer` | Census of sites per structure id; starts confirmed without generating; 5-pixel markers on the roll render | Set pieces appear in all three because they are structures in the level's state; § Finding places adds names and coordinates |
| `ExitShrines` | Detects a beacon under a frame in a generated ruin and registers an exit | A set piece may carry the same beacon-and-frame contract, so a `gate` can be a real exit |

## In the definition

A definition gains `setPieces`: name to spec. A spec either stands alone or extends one from the shared library `definitions/set-pieces/<name>.json` (`"like": "crater"`) and overrides fields. The compiler writes each one as a worldgen structure `adventure:set_piece/<slug>/<name>` of our structure type `customdimensions:set_piece`, and, when it names a `group`, a structure set that joins that group's noise placement.

| Field | Meaning |
| --- | --- |
| `name` | The display name the survey, shots and map use ("The Bone Pit") |
| `like` | A library spec this one extends |
| `seat` | How it sits on the world (§ Seats): `ground`, `floor`, `roof`, `wall`, `water`, `pinned` |
| `support` | Share of the footprint's sample columns that must find a seat (0–1, default 0.8); below it the start declines. A sky world's set piece never starts over void |
| `slope` | Largest height spread across the footprint's samples, in blocks; above it the start declines |
| `biomes` | Biome ids or `#tags` the origin must be in; default every biome of the dimension |
| `elements` | The parts, drawn in order (§ Elements); each has its own offset, rotation, count and chance |
| `palette` | Named block lists the elements draw from (`ground`, `rubble`, `metal`, `bone`, `glow`…), each a weighted list of vanilla block states |
| `group`, `rarity`, `repetition` | Join a structure-control group (`deco`, `landmarks`, `dungeons`, …) at that rarity share, at most `repetition` copies |
| `terrainAdaptation` | Default adjustment (`none`, `beard_thin`, `beard_box`, `bury`, `encapsulate`); the definition's `structures.terrainAdaptation` overrides it |
| `size` | Bounding half-extent in blocks, checked against what the elements can reach; the compiler refuses a spec whose elements can exceed it. At most 64 (a 9 by 9 chunk box) |
| `marker` | `true` places an `ExitShrines` beacon-and-frame at the element named `gate` |

Placement stays in `structures`: `force: [{structure: "adventure:set_piece/the_boneyard/bone_pit", x, z, radius}]`, `wants: {"adventure:set_piece/the_boneyard/crater": {min, max}}`, zone `structures.wants`, `shuns`, `replace` (a set piece may stand in for a mod's structure that is missing). A set piece placed only by `force` or `wants` needs no `group`.

## Seats

How the start finds its origin (`findGenerationPoint`) and what "ground" means. Each samples the footprint's corners and centre (nine columns) from the level's base columns, as vanilla's `nether_fossil` and `ruined_portal` structures sample theirs.

| Seat | Origin | Declines when |
| --- | --- | --- |
| `ground` | The surface height (`WORLD_SURFACE_WG`) under the origin | Fewer than `support` samples find solid ground above the world's lowest 8 blocks (void), or the spread exceeds `slope` |
| `floor` | The first air above solid ground scanning up from a band's bottom: a Nether or cave floor under a roof (`band: [minY, maxY]`, default the dimension's lava sea to its roof less 8) | No floor in the band with headroom `clear` (default 6) |
| `roof` | The underside of the roof or a ceiling, scanning up from the floor; elements hang down | No ceiling within `reach` (default 48) |
| `wall` | A face: from the floor seat, the nearest column within `reach` whose height rises by `rise` or more; the origin is on that face, rotated to face out | No face found; used for falls and things built into pit walls |
| `water` | The water surface (`OCEAN_FLOOR_WG` for `bed`) | No water of `depth` or more |
| `pinned` | `y` given by the forced start, through `PinnedGround` | Never; for a ledge or overlook that must exist on every seed |

## Elements

Every element is drawn by one structure piece of ours (`customdimensions:set_piece_part`) with its own bounding box; `postProcess` writes only inside the chunk being generated, as every vanilla piece does. Randomness comes from the piece's seeded random, so one seed gives the same blocks every time. Liquids placed by an element schedule their tick, so a fall flows from the first moment.

### Shapes

| Kind | Parameters | Builds |
| --- | --- | --- |
| `crater` | `radius`, `depth`, `rim` (raise in blocks), `lining` palette, `scorch` (radius of ground re-skinned round it), `core` (an element at the bottom) | A bowl carved into the seat with a raised, broken rim; the meteorite, the bomb site, the pock mark |
| `pit` | `radius` or `shape` (`round`, `rift`), `depth`, `fill` palette and `fillDepth`, `walls` palette, `ledges` | A shaft or bowl down from the seat, its floor filled (bones, soul sand, lava, gold), its walls dressed |
| `fall` | `liquid` (`lava`, `water`), `drop`, `width`, `pool` (radius at the base) | A source at the top of a `wall` seat and a pool at its foot; the curtain is the liquid's own flow |
| `wall` | `length`, `height`, `thickness`, `towers` (every n blocks), `breaches` (count), `palette` | A curtain wall that follows the ground between two ends, towers on it, gaps broken through |
| `scatter` | `count`, `radius`, `items` (weighted elements), `spacing` | Many small elements over an area: skulls, bones, rubble, carts, small templates, graves |
| `line` | `from`, `to` (offsets or `"ground"`/`"roof"`), `style` (`chain`, `pipe`, `bars`, `bridge`, `rail`), `sag` | A run of blocks between two anchors: hanging chains, pipelines on trestles, a rope bridge, a rail gallery |
| `column` | `height`, `radius`, `taper`, `style` (`chimney`, `pillar`, `spire`, `stack`, `cage`), `top` (an element) | A vertical: a smoking chimney (campfire under it), a stack of pearls in an end-stone cradle, a ruined pillar, a birdcage of bars |
| `field` | `radius`, `edge` (noise), `cover` palette (top block), `over` (block on top: fire, snow layer, sculk vein) | A surface treatment: soul-fire field, ash drift, sculk scar, petal carpet |
| `crown` | `find` (`tops`: local maxima over `rise`), `radius`, `growth` palette | Growth on high points round the origin: amethyst and crystals on spire tops |
| `platform` | `width`, `depth`, `rail`, `stairs` (down to the seat below), `palette` | A terrace, ledge or landing that always exists: an overlook above a pit |

### Assemblies

| Kind | Parameters | Builds |
| --- | --- | --- |
| `template` | `id` (a vanilla structure template, below), `rotation` (`random` or fixed), `mirror`, `processors` (a vanilla processor list id), `integrity` (0–1, blocks kept), `tilt` (`none`, `nose`, `side`), `embed` (blocks sunk below the seat) | One vanilla template placed whole, aged, broken or half-buried |
| `wreck` | A `template` plus `crater` and `scatter` of its own blocks round it | A crash site: the ship, the trench it ploughed, debris |
| `skeleton` | `length`, `parts` (vanilla fossil templates by id), `curve` | A giant's or dragon's bones laid as a chain of fossil spines with a skull at the head |
| `camp` | `state` (`living`, `abandoned`, `burned`), `tents` (count; vanilla `abandoned_camp/tent/*` or our `hovel`), `fire`, `stores` (chests with loot tables) | A camp or its remnants; `burned` swaps wood for charcoal-coloured blocks and leaves fire and ash |
| `hovel` | `size`, `palette`, `roof` (`flat`, `pitched`, `dome`), `state` | A small dwelling of the dimension's own materials: a piglin hovel of blackstone and wart blocks, a shack |
| `farm` | `crop` (`nether_wart`, `crimson_fungus`, `warped_fungus`, `wheat`, `mushroom`), `plots`, `irrigation` (`water`, `lava`, `none`), `fence` | Tilled plots, rows of crop, channels, composters and a hovel or two |
| `works` | `columns` (chimneys), `lines` (pipes, chains), `machines` (levers, pistons, observers, hoppers, cauldrons, blast furnaces in a palette), `slag` (a `field`) | Industry: chimneys, pipe runs, chain hoists, a quench pool, slag heaps |
| `cell` | `count`, `bars`, `captive` (entity type, `persistent`, `name`), `holder` (`floor`, `minecart`, `boat`) | Cages and cells; an enderman is held in a minecart behind bars, since a riding enderman does not teleport (SP4 tests it on 26.3) |
| `gate` | `frame` (`nether`, `end`, `custom` block list), `state` (`broken`, `attempt`), `marker` | A portal frame left broken, or an attempted gateway (frame, end rods, a bedrock plinth, brewing stands); with `marker` it is a working exit |

### Things inside

Any element may carry these.

| Thing | Parameters | Notes |
| --- | --- | --- |
| `spawner` | `entity`, `delay`, `range`, `count` | A spawner block with its entity set in `postProcess`; a ghast spawner needs headroom the element checks |
| `chest` | `loot` (a vanilla or pack loot table id), `kind` (`chest`, `barrel`, `trapped_chest`, `decorated_pot`) | Per-player through slashloot like any structure chest; `structures.loot` rules add to it |
| `mob` | `entity`, `name`, `count`, `persistent`, `attributes` (`max_health`, `attack_damage`, `armor`, `scale`…), `equipment`, `effects` | Spawned into the world in `postProcess`, as vanilla's swamp hut spawns its witch; the easter eggs are these: a buffed iron golem with a chest of iron beside it |
| `archaeology` | `share`, `loot` | Suspicious sand or gravel with a loot table, as vanilla's trail ruins processor lays it |

## Vanilla templates reused by id

`template` elements load a template from the running server's own data with `StructureTemplateManager.getOrCreate(<id>)` and place it with `StructurePlaceSettings` (rotation, mirror, processors, integrity through vanilla's `BlockRotProcessor`). The compiler checks every id against the vanilla extraction it already reads (`compiler/build/vanilla/<version>/data/minecraft/structure/`); nothing is copied into the repo or the jar. Processor ids are vanilla's own (`data/minecraft/worldgen/processor_list/`).

| Template family (26.3 count) | Ids | Used for |
| --- | --- | --- |
| End city ship (1) | `minecraft:end_city/ship` | The crashed End ship (`wreck` with `tilt: nose`, `embed` 6–10, `integrity` 0.7) |
| End city pieces (19) | `minecraft:end_city/tower_piece`, `bridge_piece`, `fat_tower_top` … | Collapsed End towers lying in rubble |
| Fossils (16) | `minecraft:fossil/spine_1`–`4`, `skull_1`–`4` (and `_coal`) | `skeleton` chains: giants' and dragons' bones |
| Nether fossils (14) | `minecraft:nether_fossils/fossil_1`–`14` | Bones in soul sand, bone pits |
| Ruined portals (13) | `minecraft:ruined_portal/portal_1`–`10`, `giant_portal_1`–`3` | `gate` with `state: broken`; shipping yards of dead portals |
| Ancient city pieces (58) | `minecraft:ancient_city/structures/*` (`camp_1`–`3`, `tall_ruin_1`–`4`, `small_statue`, `ice_box_1`, `barracks`) | Outposts of the deep dark beyond the cities; sculk shrines |
| Bastion pieces (167) | `minecraft:bastion/units/*`, `bastion/bridge/*`, `bastion/treasure/*` | Piglin ruins, walls and gates outside a bastion |
| Trail ruins (84) | `minecraft:trail_ruins/buildings/*`, `tower/*`, with `minecraft:trail_ruins_houses_archaeology` | Buried houses under ash or lava rock with archaeology in them |
| Shipwrecks (20) | `minecraft:shipwreck/*` (`with_mast_degraded`, `sideways_full_degraded` …) | Wrecks on dry land, in ice, on a reef |
| Pillager outpost features (11) | `minecraft:pillager_outpost/feature_cage1`, `feature_cage2`, `feature_tent1`, `feature_logs`, `feature_targets` | Cages, tents and training grounds in camps and barracks |
| Abandoned camp (18 biome sets) | `minecraft:abandoned_camp/tent/<biome>/*`, `camp/*` | `camp` tents in the biome's own wood |
| Underwater ruins (48), igloo (3), desert well (2), sulfur springs (10) | `minecraft:underwater_ruin/*`, `igloo/top`, `desert_well/well`, `spring/*` | Sunken temples' outbuildings, lone shelters, wells, hot springs |

A template element is drawn after the shapes it sits in, so a ship lies in its own crater. A template too big for its `size` is a compile error, measured by the same tool that fills `structure-sizes.json`.

## Composition

A set piece is its elements in order: shapes first, then assemblies, then the things inside them. Offsets are in blocks from the origin, in the piece's rotation; `count` and `chance` repeat or drop an element; `around` places copies on a ring. The library holds the reusable ones (`crater`, `bone_pit`, `lava_fall`, `burned_camp`, `wart_farm`, `chimney_works`, `prison_block`, `broken_gateway`, `crash_site`, `overlook`), each a few elements with a palette a dimension overrides. Example, the_boneyard's crash site:

```json
"crash_site": {
  "like": "crash_site",
  "name": "The Crashed Ship",
  "seat": "floor",
  "palette": { "ground": ["minecraft:soul_sand", "minecraft:soul_soil"], "scorch": ["minecraft:basalt", "minecraft:blackstone"] },
  "elements": [
    { "kind": "crater", "radius": 14, "depth": 5, "rim": 2, "lining": "scorch" },
    { "kind": "template", "id": "minecraft:end_city/ship", "tilt": "nose", "embed": 8, "integrity": 0.7, "offset": [0, -2, 0] },
    { "kind": "scatter", "count": 24, "radius": 30, "items": [{ "kind": "template", "id": "minecraft:fossil/skull_2" }, { "block": "minecraft:purpur_block" }] }
  ]
}
```

## Finding places

The owner's ruling is that shots labelled as places show one. A set piece is a structure, so every tool already sees it; three changes make it findable by name.

| Tool | Change |
| --- | --- |
| Survey dossier | `structures.census.<id>` gains `sites`: the first 16 site coordinates nearest spawn. A new `places.named` lists each set piece and each forced or wanted start: `id`, `name`, `x`, `z`, the seat height once its chunk is generated, and its `size` |
| `pick-shots.py` | One `place-<name>` shot per `places.named` entry (cap per dimension): the camera at the piece's size plus 12 blocks, at seat height plus a third of the size, facing the origin; alternates on the other three sides. These come before the terrain types in the shot list |
| `review-packet.py` | A "Places" section first: each named place's shot, its id and source (`set piece`, mod id) |
| Web map | `maps/<slug>/places.json` with every named place; the viewer shows a labelled pin per place over the structure layer |

## Work packages

Each is about 180 steps, in this order. SP1 comes first because it makes the pack's own structures show as places in the reviews before any set piece exists. SP1–SP4 are kit work in a non-server lane except their self-test runs; SP5–SP7 are authoring in the server lane.

### SP1 — Places in the tools

Census `sites`, dossier `places.named` (every forced start and want now; set pieces join it when SP2 lands), `pick-shots.py` place shots, `review-packet.py` Places section, map `places.json` and the viewer's pins.

Acceptance:
- Python unit tests for `pick-shots.py` and `review-packet.py` on a dossier fixture with three named places: three `place-*` shots come first, each facing its origin, each with three alternates; a dossier without `places.named` gives the shot list it gives today.
- Self-test `survey`: the dossier for a fixture dimension with two forced starts and one want lists all three with coordinates inside the border, and the census lists `sites` nearest first.
- The rig shoots one fixture dimension and every `place-*` frame passes the frame gate with the structure label.

### SP2 — The set-piece structure type

Register `customdimensions:set_piece` (structure type) and `customdimensions:set_piece_part` (piece type); `setPieces` in the schema and the converter; the compiler writes the structures and group sets; seats `ground`, `floor`, `pinned`; elements `template` and `scatter` of blocks; `chest` and `spawner`; the `size` check; set pieces in `places.named`.

Acceptance:
- Unit: the spec codec round-trips; an unknown field, a template id vanilla lacks, or elements that can reach past `size` stop the compile with the slug and the set piece's name.
- Unit: the same seed and spec write byte-identical blocks into a test region twice; a different seed writes different ones.
- Self-test `setpieces`: a fixture dimension forces one piece of each seat at fixed coordinates; each generates with its expected block counts (template blocks present, chest with its loot table, spawner with its entity); a `ground` piece forced over void declines and the log says so; a piece in a group appears in the census and in `places.named`.
- A vanilla client joins a server with the types registered (smoke-test-26 join gate).

### SP3 — Shapes

`crater`, `pit`, `fall`, `wall`, `line`, `column`, `field`, `crown`, `platform`; seats `roof`, `wall`, `water`; liquid ticks; `terrainAdaptation` default per piece.

Acceptance:
- Unit, per shape: block counts and bounding box for fixed parameters; the carved volume of a crater and pit within 10% of its formula; a `wall` follows a stepped test ground without gaps.
- Self-test: a lava `fall` on a pit wall flows after generation (lava below the source within 20 ticks); a `crown` puts growth only on local maxima; a `platform` pinned at a height exists on three seeds.
- Edge cases: a shape crossing a chunk border is continuous (blocks on both sides match the formula); a shape at the world border clips without error; a `roof` seat in a dimension without a roof declines.

### SP4 — Assemblies and things inside

`wreck`, `skeleton`, `camp`, `hovel`, `farm`, `works`, `cell`, `gate` (with `marker` through `ExitShrines`); `mob` with name, attributes and equipment; `archaeology`.

Acceptance:
- Self-test: a `wreck` of `minecraft:end_city/ship` places with its tilt and keeps its elytra frame at `integrity` 1; a `cell` holds its captive (an enderman in a minecart is in the same block after 200 ticks); a `mob` easter egg exists once, persistent, named, with its attributes; a `gate` with `marker` registers an exit zone.
- Unit: `burned` camps swap wood for their burned palette; a farm's crops are on blocks that hold them (wart on soul sand).
- Bad paths: a `mob` entity id that does not exist, a loot table that does not exist and a processor list that does not exist each stop the compile.

### SP5 — Places for Batches 1 and 2 (19 dimensions)

Author the set pieces and the structure wiring in [dimensions.md](dimensions.md) for Batches 1–2 (the owner's notes given), survey, shoot and write the review packets.

Acceptance: every place listed for these dimensions is in `places.named` inside the player border on the review seed; each has a shot that shows it; no structure starts over void on the four sky worlds (census sites against the void map); the owner's named ones (the_boneyard's ships, craters, skulls, spawners and golem; the_gilded_pit's ledge and its four landmarks; the_molten_flats' works, barracks, broken portals and cells) each appear.

### SP6 — Places for Batches 3 and 4 (22 dimensions)

As SP5. Acceptance adds: End-island pieces seat only on island ground; the_canvas keeps only its forced structures.

### SP7 — Places for Batches 5 and 6 and the 21 terrain picks (41 dimensions)

As SP5, with the shared library pieces reused rather than redrawn.

## Deep-time links (RB-41, RB-42)

- **Portal studies.** A `gate` with `state: attempt` gains a `study` (enchanting table, bookshelves, lectern with notes that stop short), a partial frame of the target dimension's frame block, an igniter precursor in a chest, and, when the target's `portal.aura` is on, the ground around it part-transformed into the target's blocks. Its placement reads the target dimension (biome, height, depth). Some treasure maps name it as their destination. It never carries the full recipe.
- **Never named (RB-43).** Nothing a set piece places (book, sign, item name, map) names a faction, dimension, epoch or person; objects tell it without words where they can, and notes are few and fragmentary.
- **Linked places.** A set piece or structure may name a `story` it belongs to; places in one story share faction, materials, banners and loot, and are placed near one another (a camp by the broken portal, a grave above the battlefield, the keep whose banners match). Most terrain carries nothing; a story is rare by design.
