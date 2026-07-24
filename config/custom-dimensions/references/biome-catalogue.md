# Biome Catalogue

Every biome id available on this server, grouped by **family** (which dimension types they work with) and **namespace** (which mod provides them). Source: `scripts/seed/biome_params.json` (extracted from the running server) + `config/custom-dimensions/extractors/biomes.json` for Nature's Spirit biomes.

**Only use ids listed here.** A biome id not in this file will be silently filtered out by the mod — no error, no warning, and if your entire biomes list gets filtered to empty, the dimension falls back to `minecraft:plains`.

**Family matters.** Biomes are registered per-family in the multinoise parameter table. A biome from the wrong family won't appear in the roller's sampler for that dimension type, causing `spawnFilter` rejections and zero candidates:
- `type: "overworld"`, `"multi_biome"`, `"cave"`, `"amplified"`, `"large_biomes"`, `"sky_islands"`, `"checkerboard"`, `"single_biome"` → use **overworld** biomes
- `type: "nether"`, `"nether_islands"` → use **nether** biomes
- `type: "end"` → use **end** biomes
- `type: "paradise_lost:paradise_lost"` → use **paradise_lost** biomes
- `type: "void"` → use biomes from **one** family (don't mix)

**Nature's Spirit biomes** (47 biomes, `natures_spirit:` namespace) are installed on the server and used in shipped dimensions, but are NOT yet in `biome_params.json` — the roller's sampler doesn't have climate parameters for them. They are safe to use in `biomes` lists (the mod will generate them correctly), but **do not put Nature's Spirit biomes in `seedRoll.spawnFilter`** — the roller can't evaluate spawn candidates against them. Use a vanilla or Terralith biome in `spawnFilter` that appears near the same terrain, and list Nature's Spirit biomes only in the main `biomes` array.

---

## Overworld family (148 biomes in roller + 47 Nature's Spirit)

Use with: `multi_biome`, `overworld`, `cave`, `amplified`, `large_biomes`, `sky_islands`, `checkerboard`, `single_biome`, `void` (if all biomes are overworld-family).

### minecraft (55 biomes)

```
minecraft:badlands                    minecraft:bamboo_jungle
minecraft:beach                       minecraft:birch_forest
minecraft:cherry_grove                minecraft:cold_ocean
minecraft:dark_forest                 minecraft:deep_cold_ocean
minecraft:deep_dark                   minecraft:deep_frozen_ocean
minecraft:deep_lukewarm_ocean         minecraft:deep_ocean
minecraft:desert                      minecraft:dripstone_caves
minecraft:eroded_badlands             minecraft:flower_forest
minecraft:forest                      minecraft:frozen_ocean
minecraft:frozen_peaks                minecraft:frozen_river
minecraft:grove                       minecraft:ice_spikes
minecraft:jagged_peaks                minecraft:jungle
minecraft:lukewarm_ocean              minecraft:lush_caves
minecraft:mangrove_swamp              minecraft:meadow
minecraft:mushroom_fields             minecraft:ocean
minecraft:old_growth_birch_forest     minecraft:old_growth_pine_taiga
minecraft:old_growth_spruce_taiga     minecraft:plains
minecraft:river                       minecraft:savanna
minecraft:savanna_plateau             minecraft:snowy_beach
minecraft:snowy_plains                minecraft:snowy_slopes
minecraft:snowy_taiga                 minecraft:sparse_jungle
minecraft:stony_peaks                 minecraft:stony_shore
minecraft:sunflower_plains            minecraft:swamp
minecraft:taiga                       minecraft:warm_ocean
minecraft:windswept_forest            minecraft:windswept_gravelly_hills
minecraft:windswept_hills             minecraft:windswept_savanna
minecraft:wooded_badlands
```

### terralith (93 biomes)

```
terralith:alpha_islands               terralith:alpha_islands_winter
terralith:alpine_grove                terralith:alpine_highlands
terralith:amethyst_canyon             terralith:amethyst_rainforest
terralith:ancient_sands               terralith:arid_highlands
terralith:ashen_savanna               terralith:basalt_cliffs
terralith:birch_taiga                 terralith:blooming_plateau
terralith:blooming_valley             terralith:brushland
terralith:bryce_canyon                terralith:caldera
terralith:cave/andesite_caves         terralith:cave/deep_caves
terralith:cave/diorite_caves          terralith:cave/frostfire_caves
terralith:cave/fungal_caves           terralith:cave/granite_caves
terralith:cave/infested_caves         terralith:cave/mantle_caves
terralith:cave/thermal_caves          terralith:cave/tuff_caves
terralith:cave/underground_jungle     terralith:cloud_forest
terralith:cold_shrubland              terralith:deep_warm_ocean
terralith:desert_canyon               terralith:desert_oasis
terralith:desert_spires               terralith:emerald_peaks
terralith:forested_highlands          terralith:fractured_savanna
terralith:frozen_cliffs               terralith:glacial_chasm
terralith:granite_cliffs              terralith:gravel_beach
terralith:gravel_desert               terralith:haze_mountain
terralith:highlands                   terralith:hot_shrubland
terralith:ice_marsh                   terralith:jungle_mountains
terralith:lavender_forest             terralith:lavender_valley
terralith:lush_desert                 terralith:lush_valley
terralith:mirage_isles                terralith:moonlight_grove
terralith:moonlight_valley            terralith:orchid_swamp
terralith:painted_mountains           terralith:red_oasis
terralith:rocky_jungle                terralith:rocky_mountains
terralith:rocky_shrubland             terralith:sakura_grove
terralith:sakura_valley               terralith:sandstone_valley
terralith:savanna_badlands            terralith:savanna_slopes
terralith:scarlet_mountains           terralith:shield
terralith:shield_clearing             terralith:shrubland
terralith:siberian_grove              terralith:siberian_taiga
terralith:skylands_autumn             terralith:skylands_spring
terralith:skylands_summer             terralith:skylands_winter
terralith:snowy_badlands              terralith:snowy_cherry_grove
terralith:snowy_maple_forest          terralith:snowy_shield
terralith:steppe                      terralith:stony_spires
terralith:temperate_highlands         terralith:tropical_jungle
terralith:valley_clearing             terralith:volcanic_crater
terralith:volcanic_peaks              terralith:warm_river
terralith:warped_mesa                 terralith:white_cliffs
terralith:white_mesa                  terralith:windswept_spires
terralith:wintry_forest               terralith:wintry_lowlands
terralith:yellowstone                 terralith:yosemite_cliffs
terralith:yosemite_lowlands
```

### natures_spirit (47 biomes) — NOT in biome_params.json, see warning above

```
natures_spirit:alpine_clearings       natures_spirit:alpine_highlands
natures_spirit:arid_highlands         natures_spirit:arid_savanna
natures_spirit:aspen_forest           natures_spirit:bamboo_wetlands
natures_spirit:blooming_dunes         natures_spirit:blooming_highlands
natures_spirit:boreal_taiga           natures_spirit:carnation_fields
natures_spirit:cedar_thicket          natures_spirit:coniferous_covert
natures_spirit:cypress_fields         natures_spirit:dusty_slopes
natures_spirit:fir_forest             natures_spirit:floral_ridges
natures_spirit:flowering_shrubland    natures_spirit:golden_wilds
natures_spirit:heather_fields         natures_spirit:lavender_fields
natures_spirit:lively_dunes           natures_spirit:maple_woodlands
natures_spirit:marigold_meadows       natures_spirit:marsh
natures_spirit:oak_savanna            natures_spirit:prairie
natures_spirit:red_peaks              natures_spirit:redwood_forest
natures_spirit:scorched_dunes         natures_spirit:shrubby_highlands
natures_spirit:shrubland              natures_spirit:sleeted_slopes
natures_spirit:snowcapped_red_peaks   natures_spirit:snowy_fir_forest
natures_spirit:snowy_redwood_forest   natures_spirit:sparse_tropical_woods
natures_spirit:stratified_desert      natures_spirit:tropical_basin
natures_spirit:tropical_shores        natures_spirit:tropical_woods
natures_spirit:tundra                 natures_spirit:white_cliffs
natures_spirit:windswept_sugi_forest  natures_spirit:wisteria_forest
natures_spirit:wooded_drylands        natures_spirit:woody_highlands
natures_spirit:xeric_plains
```

---

## Nether family (13 biomes)

Use with: `nether`, `nether_islands`, `void` (if all biomes are nether-family).

### incendium (8 biomes)

```
incendium:ash_barrens                 incendium:infernal_dunes
incendium:inverted_forest             incendium:quartz_flats
incendium:toxic_heap                  incendium:volcanic_deltas
incendium:weeping_valley              incendium:withered_forest
```

### minecraft (5 biomes)

```
minecraft:basalt_deltas               minecraft:crimson_forest
minecraft:nether_wastes               minecraft:soul_sand_valley
minecraft:warped_forest
```

---

## End family (6 biomes)

Use with: `end`, `void` (if all biomes are end-family).

### minecraft (3 biomes)

```
minecraft:end_highlands               minecraft:small_end_islands
minecraft:the_end
```

### nullscape (3 biomes)

```
nullscape:crystal_peaks               nullscape:shadowlands
nullscape:void_barrens
```

---

## Paradise Lost family (10 biomes)

Use with: `paradise_lost:paradise_lost`, `void` (if all biomes are paradise_lost-family).

### paradise_lost (10 biomes)

```
paradise_lost:autumnal_tundra         paradise_lost:calcite_craglands
paradise_lost:continental_plateau     paradise_lost:highlands
paradise_lost:highlands_forest        paradise_lost:highlands_grand_glade
paradise_lost:highlands_shield        paradise_lost:highlands_thicket
paradise_lost:tradewinds              paradise_lost:wisteria_woods
```

---

## Quick-reference: biome themes

When picking biomes by theme, use this grouping as a starting point, then verify every id against the lists above.

| Theme | Good biome sources |
| --- | --- |
| Frozen / ice | `minecraft:ice_spikes`, `minecraft:frozen_peaks`, `minecraft:snowy_slopes`, `minecraft:grove`, `terralith:glacial_chasm`, `terralith:frozen_cliffs`, `terralith:skylands_winter`, `terralith:cave/frostfire_caves` |
| Jungle / tropical | `minecraft:jungle`, `minecraft:bamboo_jungle`, `minecraft:sparse_jungle`, `minecraft:mangrove_swamp`, `terralith:tropical_jungle`, `terralith:cloud_forest`, `terralith:rocky_jungle`, `terralith:jungle_mountains`, `terralith:cave/underground_jungle`, `natures_spirit:tropical_basin`, `natures_spirit:tropical_woods` |
| Desert / arid | `minecraft:desert`, `minecraft:badlands`, `minecraft:eroded_badlands`, `terralith:desert_canyon`, `terralith:desert_spires`, `terralith:ancient_sands`, `terralith:lush_desert`, `terralith:gravel_desert`, `natures_spirit:scorched_dunes`, `natures_spirit:stratified_desert` |
| Dark / underground | `minecraft:deep_dark`, `minecraft:dripstone_caves`, `minecraft:lush_caves`, `terralith:cave/deep_caves`, `terralith:cave/mantle_caves`, `terralith:cave/infested_caves`, `terralith:cave/fungal_caves` |
| Mountain / peaks | `minecraft:jagged_peaks`, `minecraft:stony_peaks`, `minecraft:windswept_hills`, `minecraft:windswept_gravelly_hills`, `terralith:rocky_mountains`, `terralith:caldera`, `terralith:haze_mountain`, `terralith:granite_cliffs`, `terralith:basalt_cliffs`, `terralith:windswept_spires` |
| Pastoral / gentle | `minecraft:plains`, `minecraft:meadow`, `minecraft:sunflower_plains`, `minecraft:cherry_grove`, `minecraft:flower_forest`, `terralith:blooming_plateau`, `terralith:blooming_valley`, `terralith:lavender_valley`, `terralith:lush_valley`, `natures_spirit:prairie`, `natures_spirit:marigold_meadows` |
| Swamp / wetland | `minecraft:swamp`, `minecraft:mangrove_swamp`, `terralith:orchid_swamp`, `natures_spirit:marsh`, `natures_spirit:bamboo_wetlands` |
| Volcanic / lava | `terralith:volcanic_crater`, `terralith:volcanic_peaks`, `terralith:basalt_cliffs`, `terralith:scarlet_mountains`, `incendium:volcanic_deltas`, `incendium:ash_barrens` |
| Forest / woodland | `minecraft:forest`, `minecraft:dark_forest`, `minecraft:birch_forest`, `minecraft:old_growth_spruce_taiga`, `terralith:forested_highlands`, `terralith:moonlight_grove`, `natures_spirit:aspen_forest`, `natures_spirit:redwood_forest`, `natures_spirit:fir_forest` |
| Cherry / blossom | `minecraft:cherry_grove`, `terralith:sakura_grove`, `terralith:sakura_valley`, `paradise_lost:wisteria_woods`, `natures_spirit:wisteria_forest` |
| Nether | See nether family above — all 13 biomes |
| End | See end family above — all 6 biomes |
| Skylands | `paradise_lost:*` (all 10), or `terralith:skylands_*` (4 seasonal variants) for overworld-family sky islands |
