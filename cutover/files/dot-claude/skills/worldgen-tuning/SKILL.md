---
name: worldgen-tuning
description: Tunes terrain shape, biome placement and structure frequency with the custom-dimensions worldgen kit on Minecraft 26.3. Covers the terrain library (terrain.base, setting, params, scale, heightMultiplier, scales, layout), generator types and presets, biomeLayout regions and roles, zones, liquids, materials, carvers and ores, structure control (density, spacing, noise groups, wants and shuns, force, terrainAdaptation) and bridges for structure mods, and how each change is measured. Use when changing terrain proportions, making a world read as one place, adjusting structure density in a dimension or across the pack, admitting a structure mod, or diagnosing a terrain, biome or structure that does not appear. Not for portals and exits (custom-dimension-authoring) or live-world operations (dimension-lifecycle-operations).
---

# Worldgen tuning

Every dimension runs on vanilla's noise generator with settings `custom-dimensions` compiles from its definition (`config/custom-dimensions/dimensions/<slug>.json`). There is no other worldgen mod to tune: the firewall refuses to start beside one ([T142](../../../TROUBLESHOOTING.md#t142)). Every dial below is a definition field; the authoritative ranges are in `mods/custom-dimensions-26/schema/definition.schema.json`, the meaning in [worldgen.md](../../../docs/design/rebuild-26/worldgen.md).

**Every worldgen change is creation-time only** ([D2](../../../TROUBLESHOOTING.md#d2)): it reaches chunks generated after the next boot. Tune on a dev server or with `render`, never on a live world with players' builds.

## The dials

| Want | Dial | Notes |
| --- | --- | --- |
| A different landform | `terrain.base` and `terrain.setting` (`gentle`, `default`, `extreme`) | Fourteen library terrains, each with its parameters in `terrain.params` ([worldgen.md § Terrain library](../../../docs/design/rebuild-26/worldgen.md#terrain-library)): twelve for the land and sky presets, `roofed` (a cavern floor under a ceiling: plains, pits, chasms, corridors, ledges, pillars) the only one on `caves` and `nether`, `end` (islands grown, raised, ridged and gathered) the only one on `end` |
| Broader or tighter features overall | `terrain.scale` (0.25–4) | Stretches every terrain noise and the biome climate |
| Broader continents, ranges or valleys only | `terrain.scales.continents`, `.ridges`, `.erosion` (each 0.25–4) | Multiplies `scale` per router noise |
| Fewer, larger biome regions | `terrain.scales.climate`, or `biomeLayout: {"mode": "regions"}` with `weights`, `roles` and, over a void, `over: land` | [placeness.md](../../../docs/design/rebuild-26/placeness.md) § Biome regions |
| One region per biome, or a few places meeting | `biomeLayout.places` (with `mode: regions`): each land biome or named `groups` entry is one contiguous region of the border, its share its weight; `arrangement` `auto` (the seed picks), `ring`, `sectors`, `bands`, `core`, or `at` sites in blocks; `neighbours` and `apart` pairs; `warp` `blocks` up to 0.15 of `wavelength` | Survey `placeness.biomeRegions.major` should equal the places; at most 16 places; stretching `scales.climate` no longer moves the regions, only a group's inner mix |
| Taller or flatter | `terrain.heightMultiplier` (0.25–3) and the shared land parameters `landHeight`, `relief`, `roughness`, `coast`, `seaFloor` | A low `landHeight` pairs with a low `liquids.seaLevel` |
| A world composed round a point | `terrain.layout` (`radial`, `ring`, `linear`, with `strength`) | A layout that lowers a land terrain's centre floods it ([T203](../../../TROUBLESHOOTING.md#t203)); on `roofed` it places the pits, on `end` and the sky terrains it gathers the islands |
| A set share of land, whatever the seed | `terrain.landShare` (0–1) | Measured over the player border when the level loads; the overworld presets and the land terrains, not islands, roofed, End or sky terrains |
| Islands stacked over islands | `terrain.params.tiers` (2–4) with `tierSpacing`, `tierGap`, `tierShrink`, `tierCoverage` (`floating_islands`, `basin_islands`) | Each tier floats `tierSpacing` over the last with `tierGap` (16+) of open sky under it, so the survey reads it as a sky layer; a stack over the dimension's top is refused at compile |
| Spires on broad sky islands | `terrain.params.spires` (0–0.3) with `spireHeight`, `spireThickness`, `spireTaper` (`floating_islands`) | Every island parameter keeps its meaning, so a low `coverage` and a high `size` give a few broad islands carrying several spires each; `spires` with `terrain.floor: islands` gives each spire an islet of its own |
| One drowned hollow inside a ring | `terrain.params.hollowDepth` (0–60) on `basin_islands` with `terrain.layout.type: ring`, `ringLakes` 0 for no other lake | The hollow's edge is `radius` − `falloff` / 2; `liquids.basins` fills it. Without a ring the compile refuses it |
| Region or band edges ruler-straight | `terrain.climateWarp.blocks` (0–1024), `wavelength` | Moves where the climate is sampled, so every biome boundary bends; zone edges take `edgeJitter` instead |
| A seed too cold or too dry | `terrain.climateShift.temperature`, `vegetation` (−1–1) | Acts on multi-noise sources such as the overworld preset's own; region layouts and sky biome lists rank the climate, so a shift leaves their shares unchanged |
| Flat plateau tops on sky islands | `terrain.params.flatness` (0–1), `terraces`, `terraceStep` (`floating_islands`) | The survey files level ground by its tilt: a high `spread` reads as hills whatever the top, so lower `spread` before raising `flatness` |
| No caves breaking a rolling land | `terrain.params.crust` (`continents`, `islands`) | Blocks under the ground no noise cave opens into |
| A landmark, a ring, a meeting of two places | `zones` | Shape, blend, jitter; terrain, biomes, swaps, a floating `mass`, dry aquifers, scoped wants and shuns |
| Sea, lava, aquifers | `liquids` | `seaLevel`, `lavaLevel`, `defaultFluid`, `aquifers` |
| Ground blocks | `materials` | `top`, `filler`, `deep`, `cliffs`, `beaches`, `ceilings`, per-biome overrides, `bands` by depth (deepest first, over the per-biome palettes) |
| Caves and ores | `carvers`, `ores` | `carvers` adds or removes vanilla carvers by name; `ores.veins`, `add`, `remove` |
| A non-noise world | `generator.type` | `checkerboard`, `single_biome`, `flat`, `void`, `clone` ([mod README § Generators](../../../mods/custom-dimensions-26/README.md#generators)) |

The gallery fixtures in `mods/custom-dimensions-26/server/src/selftest/gallery/` show every terrain at its three settings; `-PterrainGallery` on `runServer` opens them.

## Structures

`structures` per definition compiles to `structure_control/<slug>.json` ([worldgen.md § Structure control](../../../docs/design/rebuild-26/worldgen.md#structure-control)):

- `density`: `none`, `sparse`, `normal`, `dense`; `noise: false` keeps vanilla grids scaled by theme.
- `spacing` keeps one set on its own grid with the given values.
- Noise groups (`deco`, `loot`, `settlements`, `maritime`, `dungeons`, `landmarks`, `endgame`) place a group at the sites of one noise field; `radial` shapes the exclusion with distance; `clearSpawnRadius` clears dungeons and endgame round spawn.
- `wants` land as forced starts between `min` and `max` blocks of spawn; `shuns` leave every set; `force`, `include`, `exclude`, `replace` and `mode`/`list` do what their names say.
- `terrainAdaptation` replaces the Beardifier's adjustment round a structure, tag or group.

Footprints are measured, never proxied from jigsaw fields ([T54](../../../TROUBLESHOOTING.md#t54)): `-PmeasureStructures` on `runServer` writes `structure-sizes.json` and fills each bridge's `size`.

**A structure mod joins through a bridge**: a reviewed file in `config/custom-dimensions/bridges/` (`schema/bridge.schema.json`) naming the namespaces it may own and its structures' groups and sizes. Without one the firewall switches its sets off ([bridges.md](../../../docs/design/rebuild-26/bridges.md)). `/customdim audit` lists unreviewed bridges and switched-off sets.

## Measure every change

1. `/customdim render <slug> <seed> <radius> <step>` draws the world top-down from the sampler without generating chunks (`renders/<slug>/`).
2. `/customdim survey <slug>` measures real chunks into a dossier: placeness, surface and terrain shares, biome expression; `check-targets.py` holds it to the dimension's targets.
3. `/customdim roll <slug>` scores seeds against `roll` and renders the best ten; `pick` writes one.
4. The rig shoots the places the dossier names (`pick-shots.py`, `rig.sh biomes`). View each image before calling a change done.

## Traps

- A stretched climate keeps the boxes' bounds and starves the rarest bands ([T174](../../../TROUBLESHOOTING.md#t174), [T168](../../../TROUBLESHOOTING.md#t168)).
- Biome regions on continentalness follow the noise, not a weak layout ([T207](../../../TROUBLESHOOTING.md#t207)); a climate layout can put a biome where the terrain cannot hold it ([T208](../../../TROUBLESHOOTING.md#t208)).
- A chasm below y −54 fills with lava ([T196](../../../TROUBLESHOOTING.md#t196)); a dry zone keeps a lava sheet below −54 ([T146](../../../TROUBLESHOOTING.md#t146)) and needs a margin past its mask ([T148](../../../TROUBLESHOOTING.md#t148)).
- Raising a noise dimension's floor re-rolls its caves and decorations ([T164](../../../TROUBLESHOOTING.md#t164)).
- A steep `factor` steps the ground at the noise-cell grid ([T149](../../../TROUBLESHOOTING.md#t149)).
- Structure control moves vanilla sets into group sets ([T145](../../../TROUBLESHOOTING.md#t145)); a want's biome bypass belongs to its assigned structure only ([T53](../../../TROUBLESHOOTING.md#t53)).
