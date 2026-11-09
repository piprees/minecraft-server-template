# Places, not perlin

The area inside a dimension's border reads as one coherent place, or as a deliberate meeting of a few: wide continents, broad ranges, big biome regions. It does not read as a sample of a noise field, a scatter of small biome blobs. Beyond the border it may change.

## Measuring it

`/customdim survey <slug>` writes `placeness` into the dossier, from the coarse map over the generation border (`core/.../survey/Placeness.java`). `/customdim survey <slug> 1 1` measures it in about 15 seconds.

| Key | What it says |
| --- | --- |
| `biomeRegions`, `landformRegions` | Connected (4-neighbour) regions of one surface biome or one landform class: `perKm2` (regions of 1 ha or more), `largestPercent`, `top3Percent`, `major` (regions of 1% or more), `medianHa`, `areaWeightedMeanHa`, `edgeKmPerKm2` (boundary length), `nearEdgePercent` (within 64 blocks of a boundary between non-speck regions), `specksPercent` (in regions under 1 ha) |
| `correlationLength` | The semivariogram range of surface height and of continentalness, in blocks; `beyond` when it exceeds half the border |
| `dominant` | The largest connected biome and landform region and their share; `percent` is the larger |
| `at.centre`, `at.spawn` | The biome and landform region there, its share, and the distance to the nearest biome boundary |
| `biomesPercent` | Every surface biome's share of the coarse map: a listed biome missing here is not placed |

A dimension reads as a **place** when one connected region holds at least 50% of the border, as a **meeting** when the largest three hold at least 60% with at most eight major regions, and as **noise** otherwise.

## The controls

Both live in `terrain`, with or without a `base`; without one they shape the `overworld`, `large_biomes` and `amplified` presets' own terrain (`TerrainLibrary.shapePreset`). Absent, they change nothing in the compiled pack.

- **`terrain.scales`** stretches one router noise each, on top of `scale` (which stretches all of them and the library's own noises): `continents` (continent and ocean size), `erosion` (range and valley width), `ridges` (weirdness: ridge, river and peak spacing) and `climate` (temperature and vegetation). Larger is broader; each is 0.25–4.
- **`terrain.layout`** adds a shape to continentalness, which both the land and the biomes read: `radial` (1 within `radius` of `centre`, falling to −1 over `falloff`: a continent), `ring` (1 at `radius`, −1 a `falloff` either side: a rim round a basin), `linear` (−1 to 1 across a band `falloff` wide through `centre`, rising towards `direction`: a coast). `strength` (0–1) scales the shape before it is added; the noise stays.
- Zones stay the tool for landmarks.

## Choosing them

- Stretch the noise the biome list is cut on. A list banded on weirdness grows its regions with `scales.ridges`; a temperature-by-humidity grid with `scales.climate`. A scale on an axis no box names changes no biome.
- Climate boxes keep their bounds while the stretched climate covers less of its range inside the border, so the rarest bands can vanish ([T174](../../../TROUBLESHOOTING.md#t174)). After any change, check every listed biome in `placeness.biomesPercent` and re-cut the boxes at the seed's quantiles where one is gone ([T168](../../../TROUBLESHOOTING.md#t168)).
- Library noises (islands, shatter, spires, channel) follow `scale` only: a terrain built from them keeps its short correlation length under `scales`.
- Use a layout where the description composes the place around something: a gate at the centre, a keep on an island. A layout that lowers the centre of a land terrain whose `coast` is near −0.15 floods it.
- A deliberately broken world (a gauntlet, a crucible, a wasteland) stays a noise sample.

## The 1.21.1 tune

`adventure:wide` ran Tectonic with continents 0.1, erosion 0.12, ridges 0.18 and climate 0.15 against Tectonic's 0.13, 0.25, 0.25 and 0.25; `adventure:compressed` with 0.13, 0.45, 0.45 and 0.35. As stretches of the default that is `scales` `{continents: 1.3, erosion: 2.08, ridges: 1.39, climate: 1.67}` for wide, the overworld's setting, and about `{erosion: 0.56, ridges: 0.56, climate: 0.71}` for compressed.

## Each dimension

Fast surveys of the definitions without and with their `scales` and `layout`:

| Dimension | Why | Setting | Biome largest / top 3 % | Landform largest / top 3 % | Reads |
|---|---|---|---|---|---|
| `overworld` | The 1.21.1 wide tune | `scales` continents 1.3, erosion 2.08, ridges 1.39, climate 1.67 | 2.9 / 7.8 → 4.6 / 12.1 | 11.3 / 28.4 → 17.1 / 35.9 | noise |
| `the_basalt_spires` | One basalt land round the central bastion, lava beyond | `layout` radial at [0, 0], radius 500, falloff 900, strength 0.3 | 10.6 / 28.8 | 39.4 / 54.9 → 66.6 / 70.9 | noise → place |
| `the_blossom_gardens` | Everything built round the gate: a continent centred on spawn, wide tune | `scales` continents 1.3, erosion 2.0, ridges 1.4, climate 1.5; `layout` radial at [768, 640], radius 700, falloff 900, strength 0.35 | 2.2 / 6.1 → 5.6 / 12.5 | 10.0 / 27.4 → 64.8 / 73.9 | noise → place |
| `the_crimson_nexus` | A meeting of many biomes; its 14 weirdness bands stay narrow, `ridges` thins the specks | `scales` ridges 1.4 | 0.8 / 1.6 → 0.8 / 2.0 | 6.7 / 17.2 → 9.5 / 18.6 | noise |
| `the_crucible` | Deliberately broken | unchanged | 58.2 / 59.0 | 7.2 / 17.4 | place |
| `the_crystal_vale` | A vale between peaks | `scales` ridges 1.3 | 8.6 / 20.5 → 8.5 / 22.0 | 47.4 / 55.0 → 50.1 / 56.2 | noise → place |
| `the_darkpine_depths` | One dark wood | `scales` erosion 1.3, ridges 1.5 | 14.2 / 32.5 → 40.4 / 55.8 | 19.9 / 37.7 | noise |
| `the_frozen_hearth` | One frozen world: wide tune, broad weirdness bands | `scales` continents 1.3, erosion 2.0, ridges 1.8, climate 1.5 | 2.1 / 5.1 → 11.7 / 18.6 | 29.1 / 40.8 → 44.3 / 46.4 | noise |
| `the_frozen_strait` | Library islands follow `scale`; 1.3 and 1.6 drop listed biomes | unchanged | 16.1 / 29.1 | 21.6 / 46.7 | noise |
| `the_gauntlet` | Deliberately broken | unchanged | 7.9 / 17.1 | 35.1 / 40.0 | noise |
| `the_greenreach` | Already a place; `ridges` 1.3 drops two listed biomes | unchanged | 41.6 / 57.9 | 51.9 / 61.5 | place |
| `the_gritlands` | Deliberately broken | unchanged | 17.8 / 32.5 | 46.1 / 74.0 | meeting |
| `the_highland_crossing` | Highlands meeting at passes; the ridge web stays | `scales` climate 2.0 | 4.2 / 9.4 → 8.1 / 19.0 | 24.9 / 34.0 | noise |
| `the_needlefall` | Deliberately broken | unchanged | 24.6 / 59.6 | 48.6 / 59.6 | noise |
| `the_overgrowth` | Deliberately broken | unchanged | 8.4 / 19.0 | 13.5 / 30.4 | noise |
| `the_scorched_mesa` | Painted canyon country | `scales` ridges 1.5 | 20.7 / 46.3 → 26.8 / 51.5 | 29.7 / 38.9 → 43.3 / 50.3 | noise |
| `the_shallows` | Already a place (water) | unchanged | 15.7 / 40.7 | 77.3 / 80.8 | place |
| `the_stonemantle` | Quarry country | `scales` ridges 1.5 | 17.2 / 49.3 → 23.3 / 47.1 | 36.0 / 58.0 → 36.0 / 58.7 | noise |
| `the_sun_kingdoms` | Kingdoms on one continent; `scales` shrink its biome regions, so a layout | `layout` radial at [0, 0], radius 900, falloff 1200, strength 0.3 | 7.6 / 17.0 → 6.5 / 13.6 | 13.3 / 27.7 → 31.3 / 56.7 | noise |
| `the_twisted_groves` | Groves stacked into ridges | `scales` ridges 1.5 | 8.8 / 25.3 → 11.7 / 29.2 | 13.3 / 22.5 → 13.0 / 23.2 | noise |
| `the_verdant_hollow` | Broad valleys; a ring floods the hollow, so scales only | `scales` erosion 1.3, ridges 1.5 | 13.9 / 34.7 → 13.7 / 39.7 | 20.5 / 41.8 | noise |
| `the_whitestone_ford` | Already a place; `continents` and `erosion` drop a listed biome | unchanged | 60.1 / 93.2 | 36.3 / 41.9 | place |
