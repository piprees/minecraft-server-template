# Places, not perlin

The area inside a dimension's border reads as one coherent place, or as a deliberate meeting of a few: wide continents, broad ranges, big biome regions. It does not read as a sample of a noise field, a scatter of small biome blobs. Beyond the border it may change.

## Measuring it

`/customdim survey <slug>` writes `placeness` into the dossier, from the coarse map over the world border (`core/.../survey/Placeness.java`). `/customdim survey <slug> 1 1` measures it in about 15 seconds.

| Key | What it says |
| --- | --- |
| `biomeRegions`, `landformRegions` | Connected (4-neighbour) regions of one surface biome or one landform class: `perKm2` (regions of 1 ha or more), `largestPercent`, `top3Percent`, `major` (regions of 1% or more), `medianHa`, `areaWeightedMeanHa`, `edgeKmPerKm2` (boundary length), `nearEdgePercent` (within 64 blocks of a boundary between non-speck regions), `specksPercent` (in regions under 1 ha) |
| `correlationLength` | The semivariogram range of surface height and of continentalness, in blocks; `beyond` when it exceeds half the border |
| `dominant` | The largest connected biome and landform region and their share; `percent` is the larger |
| `at.centre`, `at.spawn` | The biome and landform region there, its share, and the distance to the nearest biome boundary |
| `biomesPercent` | Every surface biome's share of the coarse map: a listed biome missing here is not placed |
| `islands` | 4-connected land cells of the coarse map: `count`, `sizesHa` (`p50`, `p90`, `max`) and `dominantBiomePercent`, the area-weighted mean share of each island's land in its own commonest biome (100: every island one place) |
| `voidBiomesPercent` | Each biome's share of the void cells, read at y 64 |
| `at.centre.heightAboveMedianBlocks`, `at.spawn.heightAboveMedianBlocks` | The surface there less the median surface of the border's land cells: spawn relief |

Beside `placeness` the dossier carries border-wide measures read without the patches (`core/.../survey/SurveyMetrics.java`):

| Key | What it says |
| --- | --- |
| `structures.census.<id>` | Every site the level's structure placement (structure control applied) picks inside the border whose biome at the chunk centre takes the structure: `count`, `nearestBlocks` and `meanBlocks` from spawn, and `byRing`, counts in five rings of equal width from spawn to the border (a corner site past the last ring counts in it); no chunk is generated, so a structure's own terrain checks are not applied |
| `surface.groundBlocksPercentByBand` | The surveyed columns split at the 33rd and 67th percentiles of surface height (`bounds`) into `low`, `middle` and `high`, each with its ground blocks' shares of the band |
| `atmosphere.definitionAtmosphere.skyLuminance`, `fogLuminance` | The relative luminance (0 black, 1 white) of the definition's sky and fog colours |

A dimension reads as a **place** when one connected region holds at least 50% of the border, as a **meeting** when the largest three hold at least 60% with at most eight major regions, and as **noise** otherwise.

## The controls

Both live in `terrain`, with or without a `base`; without one they shape the `overworld`, `large_biomes` and `amplified` presets' own terrain (`TerrainLibrary.shapePreset`). Absent, they change nothing in the compiled pack.

- **`terrain.scales`** stretches one router noise each, on top of `scale` (which stretches all of them and the library's own noises): `continents` (continent and ocean size), `erosion` (range and valley width), `ridges` (weirdness: ridge, river and peak spacing) and `climate` (temperature and vegetation). Larger is broader; each is 0.25–4.
- **`terrain.landShare`** (0–1) holds that share of the player border to land for any seed, `scales` and `layout`: when the level loads, `customdimensions:land_share` samples the seed's continentalness on a 96 × 96 grid over the border and shifts it so the share lies at or above the coast (`TerrainLibrary.withLandShare`, `server/.../zone/LandShareFunction.java`). It is measured at load because a small border sees only a few continent features of a seed's noise, so no compile-time coast holds it. Terrain and biome regions read the shifted value alike. It acts on the overworld presets' own terrain (coast -0.19) and on a land terrain (its `coast`), not on `islands`, roofed, End or sky terrains.
- A **sea under the land** (`liquids.seaLevel` more than 20 blocks below the sea floor: a land terrain's `seaFloor`, or y 36 on the overworld presets) holds no water: biome regions lay the land biomes on the ocean's area and add no default sea; a listed ocean biome or a `biomeLayout.roles` entry still takes it.
- **`terrain.layout`** adds a shape to continentalness, which both the land and the biomes read: `radial` (1 within `radius` of `centre`, falling to −1 over `falloff`: a continent), `ring` (1 at `radius`, −1 a `falloff` either side: a rim round a basin), `linear` (−1 to 1 across a band `falloff` wide through `centre`, rising towards `direction`: a coast). `strength` (0–1) scales the shape before it is added; the noise stays.
- Zones stay the tool for landmarks.

## Biome regions

`biomeLayout` `{"mode": "regions"}` lays a noise generator's biome list out as large regions instead of bands (`compiler/.../RegionLayout.java`, `server/.../zone/ClimateRegions.java`).

- A listed biome **without** a `climate` is regional: it takes one cell of the region `axes` (default temperature × humidity), a share of the world border (`borders.player`, else `borders.generation`) equal to its `weights` entry (default 1) over the sum. The share holds for any seed and any `scales`: `customdimensions:zoned` samples the seed's climate on a 96 × 96 grid over the border once per level and maps each point to its rank there, the second axis ranked within the first axis's column.
- A listed biome **with** a `climate` is bound to the land and keeps its box, which wins over every region: oceans on continentalness, peaks on erosion, caves on depth. A bound box on a region axis reads it in rank space.
- On a terrain with a coastline (a library land or `islands` terrain, or the vanilla overworld router) every biome has a **role** (`compiler/.../BiomeRoles.java`): `ocean`, `river`, `shore` and `peak` from vanilla's `is_ocean`, `is_river`, `is_beach` and `is_mountain` tags, `shore` and `cave` also from vanilla's own placements (all in the coast band, all below the surface), a library biome's `role` field, else `cave` for the caves realm and `land`; `biomeLayout.roles` overrides any. Regional biomes are laid out per role, each role's weights over its own area: `land`, `peak` and `wetland` above the coastline, `ocean` below it, `shore` on the shore (three blocks under sea level to three over, read from the terrain's height profile), `river` where weirdness 0 crosses the coast (the vanilla router's rivers inland), `cave` at depth 0.2 and below. The runtime ranks a point among the samples of its own role's area. With land biomes and no ocean listed, a water sea takes the vanilla ocean that suits their temperature, or `biomeLayout.sea`; a lava or dry sea takes none, and with only land biomes regional keeps one layout over the whole border.
- Over a void (the end preset's own islands or an `end` terrain, or a terrain over a void) `biomeLayout.over: land` ranks the region axes over the cells that hold land, so each regional biome takes its weight's share of the islands, not of the void between: the End's on its erosion (the island density) from -0.0625, a sky terrain's on its place cue (`worldgen.md` § Terrain library) from -0.7. An island tends to carry one biome when the regions are larger than the islands: stretch them with `terrain.scales.climate`. On a terrain over a void a biome with the role `underside` takes the islands more than 8 blocks below their tops (hanging gardens: the router's depth there is measured from the islands' tops), and one with `basin` the open water of `basin_islands`' lake masses; under `over: land` an ocean biome there takes `basin` unless `roles` says otherwise. Each group is ranked over its own ground.
- With two axes the regional biomes fill columns along the first axis in list order and rows along the second, the direction alternating by column: consecutive biomes in the list share a border. Put a biome between the two it should sit between.
- Region size follows the axes' own noise: `terrain.scales.climate` stretches temperature and humidity for fewer, larger regions. More regional biomes means more, smaller regions.
- Zones keep their channels over the regions; a zone with its own `biomes` reads the raw climate.

## Choosing them

- Stretch the noise the biome list is cut on. A list banded on weirdness grows its regions with `scales.ridges`; a temperature-by-humidity grid with `scales.climate`. A scale on an axis no box names changes no biome.
- Climate boxes keep their bounds while the stretched climate covers less of its range inside the border, so the rarest bands can vanish ([T174](../../../TROUBLESHOOTING.md#t174)). After any change, check every listed biome in `placeness.biomesPercent` and re-cut the boxes at the seed's quantiles where one is gone ([T168](../../../TROUBLESHOOTING.md#t168)).
- Library noises (islands, shatter, spires, channel) follow `scale` only: a terrain built from them keeps its short correlation length under `scales`.
- Use a layout where the description composes the place around something: a gate at the centre, a keep on an island. A layout that lowers the centre of a land terrain whose `coast` is near −0.15 floods it.
- A deliberately broken world (a gauntlet, a crucible, a wasteland) stays a noise sample.

## The 1.21.1 tune

`adventure:wide` ran Tectonic with continents 0.1, erosion 0.12, ridges 0.18 and climate 0.15 against Tectonic's 0.13, 0.25, 0.25 and 0.25; `adventure:compressed` with 0.13, 0.45, 0.45 and 0.35. As stretches of the default that is `scales` `{continents: 1.3, erosion: 2.08, ridges: 1.39, climate: 1.67}` for wide, the overworld's setting, and about `{erosion: 0.56, ridges: 0.56, climate: 0.71}` for compressed.

## Each dimension

Fast surveys of the definitions with their biome lists as bands and without `scales` or `layout`, and with the settings below (regions: `biomeLayout`, weights from the banded shares):

| Dimension | Why | Setting | Biome largest / top 3 % | Landform largest / top 3 % | Reads |
|---|---|---|---|---|---|
| `overworld` | The 1.21.1 wide tune | `scales` continents 1.3, erosion 2.08, ridges 1.39, climate 1.67 | 2.9 / 7.8 → 4.6 / 12.1 | 11.3 / 28.4 → 17.1 / 35.9 | noise |
| `the_basalt_spires` | One basalt land round the central bastion, lava beyond | regions; `scales` climate 2; `layout` radial at [0, 0], radius 500, falloff 900, strength 0.3 | 10.6 / 28.8 → 16.9 / 42.5 | 39.4 / 54.9 → 66.6 / 70.9 | place |
| `the_blossom_gardens` | Everything built round the gate: a continent centred on spawn, wide tune | regions; `scales` continents 1.3, erosion 2, ridges 1.4, climate 4 (the survey figures are at climate 3); `layout` radial at [768, 640], radius 700, falloff 900, strength 0.35 | 2.2 / 6.1 → 7.2 / 18.3 | 10.0 / 27.4 → 64.8 / 73.9 | place |
| `the_crimson_nexus` | A meeting of many biomes: twelve regions, a few each, caves bound on depth | regions; `scales` ridges 1.4, climate 3 | 0.8 / 1.6 → 14.6 / 34.0 | 6.7 / 17.2 → 9.5 / 18.6 | noise |
| `the_crucible` | Deliberately broken | regions; `scales` climate 2 | 58.2 / 59.0 → 60.0 / 63.0 | 7.2 / 17.4 | place |
| `the_crystal_vale` | A vale between peaks | regions; `scales` ridges 1.3 | 8.6 / 20.5 → 17.0 / 44.5 | 47.4 / 55.0 → 50.1 / 56.2 | place |
| `the_darkpine_depths` | One dark wood | regions; `scales` erosion 1.3, ridges 1.5 | 14.2 / 32.5 → 43.0 / 72.3 | 19.9 / 37.7 | noise |
| `the_frozen_hearth` | One frozen world: wide tune; peaks, river and beach bound on their weirdness bands | regions; `scales` continents 1.3, erosion 2, ridges 1.8, climate 3 | 2.1 / 5.1 → 15.5 / 35.4 | 29.1 / 40.8 → 44.3 / 46.4 | noise |
| `the_frozen_strait` | Library islands follow `scale`; 1.3 and 1.6 drop listed biomes | regions | 16.1 / 29.1 → 16.2 / 29.7 | 21.6 / 46.7 | noise |
| `the_gauntlet` | Deliberately broken | regions; `scales` climate 2 | 7.9 / 17.1 → 11.5 / 23.6 | 35.1 / 40.0 | noise |
| `the_greenreach` | Already a place; `ridges` 1.3 drops two listed biomes, and regions raise the prairie's lone oaks past the treeless target (1.3 → 3.6 per hectare) | unchanged | 41.6 / 57.9 | 51.9 / 61.5 | place |
| `the_gritlands` | Deliberately broken | regions | 17.8 / 32.5 → 39.0 / 72.4 | 46.1 / 74.0 | meeting |
| `the_highland_crossing` | Highlands meeting at passes; the ridge web stays, peaks and slopes bound on erosion | regions; `scales` climate 4 | 4.2 / 9.4 → 8.6 / 18.3 | 24.9 / 34.0 | noise |
| `the_needlefall` | Deliberately broken | regions | 24.6 / 59.6 → 24.6 / 59.3 | 48.6 / 59.6 | noise |
| `the_overgrowth` | Deliberately broken, by its interleaved biome bands: regions take the tangle away (edges 61 → 9 km/km²) | unchanged | 8.4 / 19.0 | 13.5 / 30.4 | noise |
| `the_scorched_mesa` | Painted canyon country | regions; `scales` ridges 1.5 | 20.7 / 46.3 → 46.5 / 78.2 | 29.7 / 38.9 → 43.3 / 50.3 | meeting |
| `the_shallows` | Already a place (water); the islands are library noise, so the oceans are regions | regions | 15.7 / 40.7 → 40.8 / 76.6 | 77.3 / 80.8 | place |
| `the_stonemantle` | Quarry country; its peak and shore slices are regions, not terrain | regions; `scales` ridges 1.5 | 17.2 / 49.3 → 37.6 / 72.3 | 36.0 / 58.0 → 36.0 / 58.7 | noise |
| `the_sun_kingdoms` | Kingdoms on one continent; rivers, shores and badlands bound to the land | regions; `scales` climate 3; `layout` radial at [0, 0], radius 900, falloff 1200, strength 0.3 | 7.6 / 17.0 → 17.7 / 27.3 | 13.3 / 27.7 → 31.3 / 56.7 | noise |
| `the_twisted_groves` | Groves stacked into ridges | regions; `scales` ridges 1.5 | 8.8 / 25.3 → 25.8 / 62.6 | 13.3 / 22.5 → 13.0 / 23.2 | noise |
| `the_verdant_hollow` | Broad valleys; a ring floods the hollow, so scales only | regions; `scales` erosion 1.3, ridges 1.5 | 13.9 / 34.7 → 35.0 / 75.6 | 20.5 / 41.8 | noise |
| `the_whitestone_ford` | Already a place; `continents` and `erosion` drop a listed biome | regions | 60.1 / 93.2 → 60.4 / 93.5 | 36.3 / 41.9 | place |
