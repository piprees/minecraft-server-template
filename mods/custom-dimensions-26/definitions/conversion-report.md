# Conversion report

`./gradlew :compiler:convertDefinitions` writes this file and the schema v2 definitions beside it
from the 1.21.1 definitions in `config/custom-dimensions/`. Every run rewrites both; edit the v1
files, not these. The mapping is `docs/design/rebuild-26/schema-v2.md`.

An id is flagged when no mod owning its namespace is kept (`mod-register.csv`, `namespaces.csv`);
`minecraft` and `adventure` never are. "Where" is the path in the v1 file, and "(key)" marks an
id used as an object key. Flagged ids are carried unchanged; W8 replaces them.

## Totals

- Files: 82 definitions and settings.json
- Flagged ids: 790 occurrences of 313 distinct ids in 24 namespaces
- Dropped fields: 9
- Rewrites: 4
- W8 decisions: 23

### Flagged ids by namespace

| Namespace | Owners' decisions | Occurrences | Distinct ids |
| --- | --- | ---: | ---: |
| adventuredungeons | waiting | 2 | 1 |
| betterdungeons | waiting | 3 | 3 |
| betterend | rebuilt by us | 5 | 5 |
| betterfortresses | waiting | 3 | 1 |
| betternether | rebuilt by us | 19 | 16 |
| chipped | cut | 1 | 1 |
| dungeons_arise | waiting | 13 | 8 |
| dungeons_plus | waiting | 1 | 1 |
| explorify | waiting | 4 | 3 |
| friendsandfoes | waiting | 2 | 1 |
| galosphere | rebuilt by us | 4 | 3 |
| incendium | rebuilt by us | 105 | 14 |
| natures_spirit | rebuilt by us | 55 | 30 |
| nullscape | rebuilt by us | 32 | 4 |
| palegardenbackport | replaced | 1 | 1 |
| paradise_lost | rebuilt by us | 39 | 15 |
| philipsruins | rebuilt by us | 9 | 6 |
| regions_unexplored | rebuilt by us | 131 | 71 |
| structory | cut | 9 | 8 |
| structory_towers | cut | 9 | 5 |
| terralith | rebuilt by us | 301 | 87 |
| undergroundworlds | rebuilt by us | 6 | 4 |
| wilderwild | rebuilt by us | 33 | 23 |
| yungscavebiomes | rebuilt by us | 3 | 2 |

### Dropped fields by name

| Field | Count |
| --- | ---: |
| `seedRoll.family` | 6 |
| `settingsOverrides.endIsland` | 3 |

### Rewrites

| Rewrite | Count |
| --- | ---: |
| `paradise_lost:paradise_lost` → `adventure:the_aviary` | 4 |

### W8 decisions

| Decision | Count |
| --- | ---: |
| base world paradise_lost converts to the_aviary | 1 |
| terrain.base: null where v1 named a preset | 22 |

## overworld.json

Flagged ids (3):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `natures_spirit:prairie` | rebuilt by us | `seedRoll.spawnFilter[6]` |
| `terralith:blooming_plateau` | rebuilt by us | `seedRoll.spawnFilter[5]` |
| `terralith:lush_valley` | rebuilt by us | `seedRoll.spawnFilter[7]` |

## paradise_lost.json → the_aviary.json

Flagged ids (4):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `paradise_lost:highlands` | rebuilt by us | `seedRoll.spawnFilter[0]` |
| `paradise_lost:highlands_forest` | rebuilt by us | `seedRoll.spawnFilter[1]` |
| `paradise_lost:tradewinds` | rebuilt by us | `seedRoll.spawnFilter[3]` |
| `paradise_lost:wisteria_woods` | rebuilt by us | `seedRoll.spawnFilter[2]` |

W8 decisions:

- written as the_aviary.json (adventure:the_aviary) with preset floating_islands; its Paradise Lost ids are flagged above

## the_abyssal_shrine.json

Flagged ids (10):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `nullscape:shadowlands` | rebuilt by us | `biomes[0].id` |
| `paradise_lost:calcite_craglands` | rebuilt by us | `biomes[8].id`, `seedRoll.spawnFilter[0]` |
| `paradise_lost:continental_plateau` | rebuilt by us | `biomes[3].id`, `seedRoll.spawnFilter[1]` |
| `paradise_lost:highlands_grand_glade` | rebuilt by us | `biomes[4].id`, `seedRoll.spawnFilter[2]` |
| `paradise_lost:highlands_thicket` | rebuilt by us | `biomes[7].id` |
| `paradise_lost:paradise_lost` | rebuilt by us | `type` |
| `paradise_lost:vault` | rebuilt by us | `structures.force[0].structure` |

Dropped:

- `seedRoll.family: "overworld"`

Rewrites:

- `generator.of`: `paradise_lost:paradise_lost` → `adventure:the_aviary`

## the_amplified_reaches.json

Nothing flagged, dropped, rewritten or left for W8.

## the_ashgrove.json

Flagged ids (16):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `regions_unexplored:arid_mountains` | rebuilt by us | `biomes[11].id` |
| `regions_unexplored:ashen_woodland` | rebuilt by us | `biomes[12].id`, `seedRoll.spawnFilter[0]` |
| `regions_unexplored:dry_bushland` | rebuilt by us | `biomes[10].id` |
| `regions_unexplored:joshua_desert` | rebuilt by us | `biomes[9].id` |
| `regions_unexplored:saguaro_desert` | rebuilt by us | `biomes[0].id` |
| `structory:abandoned_chapel` | cut | `structures.force[1].structure` |
| `structory:graveyard` | cut | `structures.force[0].structure` |
| `terralith:ashen_savanna` | rebuilt by us | `biomes[1].id`, `seedRoll.spawnFilter[1]` |
| `terralith:basalt_cliffs` | rebuilt by us | `biomes[8].id` |
| `terralith:cave/andesite_caves` | rebuilt by us | `biomes[15].id` |
| `terralith:savanna_badlands` | rebuilt by us | `biomes[5].id` |
| `terralith:volcanic_crater` | rebuilt by us | `biomes[3].id` |
| `terralith:volcanic_peaks` | rebuilt by us | `biomes[2].id` |
| `wilderwild:arid_savanna` | rebuilt by us | `biomes[13].id` |

## the_basalt_spires.json

Flagged ids (7):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `incendium:ash_barrens` | rebuilt by us | `biomes[2].id` |
| `incendium:quartz_flats` | rebuilt by us | `biomes[5].id` |
| `incendium:volcanic_deltas` | rebuilt by us | `biomes[1].id`, `seedRoll.spawnFilter[1]` |
| `regions_unexplored:ancient_delta` | rebuilt by us | `biomes[7].id` |
| `regions_unexplored:blackstone_basin` | rebuilt by us | `biomes[6].id` |
| `structory_towers:warped_greatsword` | cut | `structures.terrainAdaptation.structory_towers:warped_greatsword (key)` |

W8 decisions:

- v1 noiseSettings adventure:compressed is built from Terralith and Tectonic data; terrain.base is null

## the_blackstone_keep.json

Flagged ids (5):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `betternether:crimson_glowing_woods` | rebuilt by us | `biomes[5].id` |
| `betternether:wart_forest` | rebuilt by us | `biomes[7].id` |
| `betternether:wart_forest_edge` | rebuilt by us | `biomes[8].id` |
| `incendium:ash_barrens` | rebuilt by us | `biomes[2].id` |
| `regions_unexplored:blackstone_basin` | rebuilt by us | `biomes[1].id` |

## the_blighted_maw.json

Flagged ids (8):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `incendium:ash_barrens` | rebuilt by us | `biomes[3]` |
| `incendium:nether_reactor` | rebuilt by us | `structures.terrainAdaptation.incendium:nether_reactor (key)`, `structures.force[0].structure` |
| `incendium:toxic_heap` | rebuilt by us | `biomes[0]`, `seedRoll.spawnFilter[0]` |
| `incendium:withered_forest` | rebuilt by us | `biomes[2]`, `seedRoll.spawnFilter[2]` |
| `philipsruins:sculk_dungeon` | rebuilt by us | `structures.force[1].structure` |

## the_bloodroot_wastes.json

Flagged ids (4):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `incendium:inverted_forest` | rebuilt by us | `biomes[2].id` |
| `incendium:weeping_valley` | rebuilt by us | `biomes[1].id`, `seedRoll.spawnFilter[1]` |
| `regions_unexplored:infernal_holt` | rebuilt by us | `biomes[5].id` |

## the_blossom_gardens.json

Flagged ids (21):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `dungeons_arise:mushroom_house` | waiting | `structures.force[0].structure` |
| `natures_spirit:carnation_fields` | rebuilt by us | `biomes[19].id` |
| `natures_spirit:floral_ridges` | rebuilt by us | `biomes[26].id` |
| `natures_spirit:lavender_fields` | rebuilt by us | `biomes[25].id` |
| `natures_spirit:marigold_meadows` | rebuilt by us | `biomes[27].id` |
| `natures_spirit:wisteria_forest` | rebuilt by us | `biomes[18].id` |
| `regions_unexplored:clover_plains` | rebuilt by us | `biomes[22].id` |
| `regions_unexplored:flower_fields` | rebuilt by us | `biomes[28].id` |
| `regions_unexplored:glistering_meadow` | rebuilt by us | `biomes[23].id` |
| `regions_unexplored:orchard` | rebuilt by us | `biomes[24].id` |
| `regions_unexplored:poppy_fields` | rebuilt by us | `biomes[21].id` |
| `terralith:blooming_plateau` | rebuilt by us | `biomes[12]` |
| `terralith:blooming_valley` | rebuilt by us | `biomes[8]` |
| `terralith:lavender_forest` | rebuilt by us | `biomes[20]` |
| `terralith:lavender_valley` | rebuilt by us | `biomes[7]` |
| `terralith:lush_valley` | rebuilt by us | `biomes[13]` |
| `terralith:moonlight_grove` | rebuilt by us | `biomes[11]` |
| `terralith:moonlight_valley` | rebuilt by us | `biomes[10]` |
| `terralith:sakura_grove` | rebuilt by us | `biomes[9]` |
| `terralith:sakura_valley` | rebuilt by us | `biomes[17]` |
| `terralith:temperate_highlands` | rebuilt by us | `biomes[14]` |

W8 decisions:

- v1 noiseSettings adventure:wide is built from Terralith and Tectonic data; terrain.base is null

## the_boneyard.json

Flagged ids (11):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `betterdungeons:skeleton_dungeon` | waiting | `structures.force[3].structure` |
| `betternether:bone_reef` | rebuilt by us | `biomes[1].id` |
| `betternether:soul_plain` | rebuilt by us | `biomes[3].id` |
| `betternether:sulfuric_bone_reef` | rebuilt by us | `biomes[4].id` |
| `incendium:ash_barrens` | rebuilt by us | `biomes[5].id`, `seedRoll.spawnFilter[1]` |
| `incendium:toxic_heap` | rebuilt by us | `biomes[9].id` |
| `incendium:withered_forest` | rebuilt by us | `biomes[10].id` |
| `philipsruins:bone_dungeon` | rebuilt by us | `structures.force[2].structure` |
| `regions_unexplored:ancient_delta` | rebuilt by us | `biomes[8].id` |
| `regions_unexplored:blackstone_basin` | rebuilt by us | `biomes[6].id` |

## the_buried_age.json

Flagged ids (7):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `betternether:flooded_deltas` | rebuilt by us | `biomes[2].id` |
| `betternether:old_swampland` | rebuilt by us | `biomes[1].id` |
| `betternether:upside_down_forest` | rebuilt by us | `biomes[3].id` |
| `betternether:upside_down_forest_cleared` | rebuilt by us | `biomes[4].id` |
| `incendium:quartz_flats` | rebuilt by us | `biomes[8].id`, `seedRoll.spawnFilter[1]` |
| `regions_unexplored:ancient_delta` | rebuilt by us | `biomes[6].id` |

## the_burning_archipelago.json

Flagged ids (15):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `incendium:ash_barrens` | rebuilt by us | `biomes[5]`, `seedRoll.spawnFilter[5]` |
| `incendium:infernal_dunes` | rebuilt by us | `biomes[6]`, `seedRoll.spawnFilter[6]` |
| `incendium:toxic_heap` | rebuilt by us | `biomes[9]` |
| `incendium:volcanic_deltas` | rebuilt by us | `biomes[4]`, `seedRoll.spawnFilter[1]` |
| `incendium:weeping_valley` | rebuilt by us | `biomes[10]` |
| `incendium:withered_forest` | rebuilt by us | `biomes[7]` |
| `nullscape:void_barrens` | rebuilt by us | `biomes[11].id` |
| `paradise_lost:calcite_craglands` | rebuilt by us | `biomes[14].id` |
| `regions_unexplored:infernal_holt` | rebuilt by us | `biomes[13].id` |
| `regions_unexplored:inferno` | rebuilt by us | `biomes[12].id` |
| `structory_towers:nether/warped_outpost` | cut | `structures.force[0].structure` |
| `terralith:volcanic_peaks` | rebuilt by us | `biomes[15].id` |

## the_canvas.json

Nothing flagged, dropped, rewritten or left for W8.

## the_catalyst_maw.json

Flagged ids (13):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `incendium:ash_barrens` | rebuilt by us | `biomes[5]` |
| `incendium:nether_reactor` | rebuilt by us | `structures.force[1].structure` |
| `incendium:toxic_heap` | rebuilt by us | `biomes[2]`, `seedRoll.spawnFilter[2]` |
| `incendium:withered_forest` | rebuilt by us | `biomes[3]`, `seedRoll.spawnFilter[3]` |
| `nullscape:shadowlands` | rebuilt by us | `biomes[6].id`, `seedRoll.spawnFilter[4]` |
| `philipsruins:sculk_dungeon` | rebuilt by us | `structures.force[0].structure` |
| `terralith:caldera` | rebuilt by us | `biomes[15].id` |
| `terralith:moonlight_grove` | rebuilt by us | `biomes[11].id` |
| `terralith:moonlight_valley` | rebuilt by us | `biomes[8].id` |
| `terralith:volcanic_crater` | rebuilt by us | `biomes[14].id` |

W8 decisions:

- v1 noiseSettings adventure:compressed is built from Terralith and Tectonic data; terrain.base is null

## the_chalk_meadows.json

Flagged ids (7):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `natures_spirit:carnation_fields` | rebuilt by us | `biomes[1].id` |
| `natures_spirit:heather_fields` | rebuilt by us | `biomes[0].id` |
| `natures_spirit:lavender_fields` | rebuilt by us | `biomes[7].id` |
| `regions_unexplored:chalk_cliffs` | rebuilt by us | `biomes[8].id` |
| `regions_unexplored:clover_plains` | rebuilt by us | `biomes[9].id` |
| `terralith:blooming_plateau` | rebuilt by us | `biomes[2].id` |
| `terralith:cave/tuff_caves` | rebuilt by us | `biomes[11].id` |

## the_claymarsh.json

Flagged ids (10):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `natures_spirit:bamboo_wetlands` | rebuilt by us | `biomes[4].id` |
| `natures_spirit:marsh` | rebuilt by us | `biomes[0].id`, `seedRoll.spawnFilter[0]` |
| `regions_unexplored:fen` | rebuilt by us | `biomes[10].id` |
| `regions_unexplored:marsh` | rebuilt by us | `biomes[9].id` |
| `regions_unexplored:muddy_river` | rebuilt by us | `biomes[11].id` |
| `terralith:cave/fungal_caves` | rebuilt by us | `biomes[14].id` |
| `terralith:moonlight_valley` | rebuilt by us | `biomes[8].id` |
| `terralith:orchid_swamp` | rebuilt by us | `biomes[3].id` |
| `wilderwild:cypress_wetlands` | rebuilt by us | `biomes[12].id` |

## the_crimson_nexus.json

Flagged ids (21):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `incendium:inverted_forest` | rebuilt by us | `biomes[11].id` |
| `incendium:quartz_flats` | rebuilt by us | `biomes[19].id`, `biomePatches[1].biome` |
| `incendium:volcanic_deltas` | rebuilt by us | `biomes[15].id` |
| `incendium:weeping_valley` | rebuilt by us | `biomes[18].id` |
| `incendium:withered_forest` | rebuilt by us | `biomes[14].id` |
| `regions_unexplored:arid_mountains` | rebuilt by us | `biomes[20].id` |
| `regions_unexplored:outback` | rebuilt by us | `biomes[21].id` |
| `regions_unexplored:redstone_abyss` | rebuilt by us | `biomes[16].id` |
| `terralith:ashen_savanna` | rebuilt by us | `biomes[5]` |
| `terralith:basalt_cliffs` | rebuilt by us | `biomes[4]` |
| `terralith:cave/mantle_caves` | rebuilt by us | `biomes[10]` |
| `terralith:cave/thermal_caves` | rebuilt by us | `biomes[9]` |
| `terralith:scarlet_mountains` | rebuilt by us | `biomes[1]`, `seedRoll.spawnFilter[1]` |
| `terralith:volcanic_crater` | rebuilt by us | `biomes[2]`, `seedRoll.spawnFilter[2]` |
| `terralith:volcanic_peaks` | rebuilt by us | `biomes[3]` |
| `terralith:warped_mesa` | rebuilt by us | `biomes[0].id`, `seedRoll.spawnFilter[0]` |
| `terralith:yellowstone` | rebuilt by us | `biomes[8]` |

W8 decisions:

- v1 noiseSettings adventure:compressed is built from Terralith and Tectonic data; terrain.base is null

## the_crucible.json

Flagged ids (19):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `dungeons_arise:jungle_tree_house` | waiting | `structures.force[0].structure` |
| `incendium:inverted_forest` | rebuilt by us | `biomes[22].id` |
| `regions_unexplored:sparse_rainforest` | rebuilt by us | `biomes[16].id` |
| `regions_unexplored:tropical_river` | rebuilt by us | `biomes[15].id` |
| `regions_unexplored:tropics` | rebuilt by us | `biomes[24].id` |
| `terralith:cave/underground_jungle` | rebuilt by us | `biomes[12].id` |
| `terralith:cloud_forest` | rebuilt by us | `biomes[19].id`, `seedRoll.spawnFilter[2]` |
| `terralith:mirage_isles` | rebuilt by us | `biomes[9].id` |
| `terralith:moonlight_grove` | rebuilt by us | `biomes[8].id` |
| `terralith:moonlight_valley` | rebuilt by us | `biomes[10].id` |
| `terralith:orchid_swamp` | rebuilt by us | `biomes[7].id` |
| `terralith:rocky_jungle` | rebuilt by us | `biomes[11].id` |
| `terralith:tropical_jungle` | rebuilt by us | `biomes[23].id`, `seedRoll.spawnFilter[3]` |
| `undergroundworlds:underground_jungle` | rebuilt by us | `biomes[20].id` |
| `wilderwild:birch_jungle` | rebuilt by us | `biomes[14].id` |
| `wilderwild:rainforest` | rebuilt by us | `biomes[13].id` |
| `wilderwild:sparse_birch_jungle` | rebuilt by us | `biomes[21].id` |

W8 decisions:

- v1 noiseSettings adventure:compressed is built from Terralith and Tectonic data; terrain.base is null

## the_crumbling_reaches.json

Flagged ids (6):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `nullscape:crystal_peaks` | rebuilt by us | `biomes[8].id` |
| `nullscape:dragon_skeleton` | rebuilt by us | `structures.force[1].structure` |
| `nullscape:shadowlands` | rebuilt by us | `biomes[0].id` |
| `nullscape:void_barrens` | rebuilt by us | `biomes[5].id` |
| `terralith:basalt_cliffs` | rebuilt by us | `biomes[6].id` |
| `terralith:stony_spires` | rebuilt by us | `biomes[7].id` |

## the_crystal_vale.json

Flagged ids (15):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `regions_unexplored:alpha_grove` | rebuilt by us | `biomes[6].id` |
| `regions_unexplored:hyacinth_deeps` | rebuilt by us | `biomes[10].id` |
| `regions_unexplored:prismachasm` | rebuilt by us | `biomes[9].id` |
| `regions_unexplored:rocky_meadow` | rebuilt by us | `biomes[7].id` |
| `regions_unexplored:temperate_grove` | rebuilt by us | `biomes[8].id` |
| `structory_towers:wizard_tower` | cut | `structures.terrainAdaptation.structory_towers:wizard_tower (key)`, `structures.force[0].structure` |
| `terralith:amethyst_canyon` | rebuilt by us | `biomes[1]`, `seedRoll.spawnFilter[1]` |
| `terralith:amethyst_rainforest` | rebuilt by us | `biomes[0]`, `seedRoll.spawnFilter[0]` |
| `terralith:cave/diorite_caves` | rebuilt by us | `biomes[12]` |
| `terralith:emerald_peaks` | rebuilt by us | `biomes[2]` |
| `terralith:moonlight_grove` | rebuilt by us | `biomes[5]`, `biomePatches[0].biome` |

W8 decisions:

- v1 noiseSettings adventure:compressed is built from Terralith and Tectonic data; terrain.base is null

## the_darkpine_depths.json

Flagged ids (15):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `betterdungeons:zombie_dungeons` | waiting | `structures.rarity.betterdungeons:zombie_dungeons (key)` |
| `explorify:dark_forest_settlements` | waiting | `structures.rarity.explorify:dark_forest_settlements (key)` |
| `natures_spirit:fir_forest` | rebuilt by us | `biomes[7].id` |
| `natures_spirit:snowy_fir_forest` | rebuilt by us | `biomes[2].id` |
| `regions_unexplored:blackwood_taiga` | rebuilt by us | `biomes[10].id` |
| `terralith:birch_taiga` | rebuilt by us | `biomes[9].id` |
| `terralith:cave/deep_caves` | rebuilt by us | `biomes[13].id` |
| `terralith:moonlight_grove` | rebuilt by us | `biomes[0].id`, `seedRoll.spawnFilter[1]` |
| `terralith:moonlight_valley` | rebuilt by us | `biomePatches[3].biome`, `biomePatches[4].biome` |
| `wilderwild:dark_birch_forest` | rebuilt by us | `biomes[5].id` |
| `wilderwild:dark_taiga` | rebuilt by us | `biomes[11].id`, `seedRoll.spawnFilter[0]` |
| `wilderwild:old_growth_dark_forest` | rebuilt by us | `biomes[4].id` |

W8 decisions:

- v1 noiseSettings adventure:compressed is built from Terralith and Tectonic data; terrain.base is null

## the_dripping_pines.json

Flagged ids (6):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `natures_spirit:boreal_taiga` | rebuilt by us | `biomes[1].id` |
| `natures_spirit:fir_forest` | rebuilt by us | `biomes[5].id` |
| `regions_unexplored:pine_slopes` | rebuilt by us | `biomes[6].id` |
| `terralith:cave/tuff_caves` | rebuilt by us | `biomes[9].id` |
| `terralith:siberian_taiga` | rebuilt by us | `biomes[0].id` |
| `wilderwild:temperate_rainforest` | rebuilt by us | `biomes[7].id` |

## the_dustbowl.json

Flagged ids (13):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `dungeons_plus:dusty_tomb` | waiting | `structures.force[1].structure` |
| `explorify:farmstead` | waiting | `structures.force[0].structure` |
| `natures_spirit:wooded_drylands` | rebuilt by us | `biomes[1].id` |
| `natures_spirit:xeric_plains` | rebuilt by us | `biomes[8].id` |
| `regions_unexplored:dry_bushland` | rebuilt by us | `biomes[9].id` |
| `regions_unexplored:outback` | rebuilt by us | `biomes[10].id` |
| `regions_unexplored:steppe` | rebuilt by us | `biomes[11].id` |
| `terralith:ancient_sands` | rebuilt by us | `biomes[0].id` |
| `terralith:arid_highlands` | rebuilt by us | `biomes[5].id` |
| `terralith:cave/andesite_caves` | rebuilt by us | `biomes[12].id` |
| `terralith:hot_shrubland` | rebuilt by us | `biomes[7].id` |
| `terralith:steppe` | rebuilt by us | `biomes[2].id`, `seedRoll.spawnFilter[0]` |

## the_ember_fields.json

Flagged ids (15):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `betterend:sulphur_springs` | rebuilt by us | `biomes[5].id` |
| `betterfortresses:fortress` | waiting | `structures.terrainAdaptation.betterfortresses:fortress (key)` |
| `betternether:nether_grasslands` | rebuilt by us | `biomes[0].id` |
| `betternether:nether_mushroom_forest` | rebuilt by us | `biomes[4].id` |
| `betternether:poor_nether_grasslands` | rebuilt by us | `biomes[1].id` |
| `betternether:wart_forest_edge` | rebuilt by us | `biomes[3].id` |
| `incendium:infernal_dunes` | rebuilt by us | `biomes[11].id`, `seedRoll.spawnFilter[1]` |
| `incendium:piglin_village` | rebuilt by us | `structures.force[0].structure` |
| `incendium:quartz_flats` | rebuilt by us | `biomes[7].id` |
| `incendium:volcanic_deltas` | rebuilt by us | `biomes[12].id`, `seedRoll.spawnFilter[2]` |
| `incendium:weeping_valley` | rebuilt by us | `biomes[8].id` |
| `regions_unexplored:glistering_meadow` | rebuilt by us | `biomes[2].id` |
| `terralith:yellowstone` | rebuilt by us | `biomes[6].id` |

## the_emberglass_foundry.json

Flagged ids (8):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `dungeons_arise:foundry` | waiting | `structures.force[0].structure` |
| `galosphere:crystal_canyons` | rebuilt by us | `biomes[0].id` |
| `incendium:quartz_flats` | rebuilt by us | `biomes[1].id` |
| `regions_unexplored:blackstone_basin` | rebuilt by us | `biomes[2].id` |
| `regions_unexplored:scorching_caves` | rebuilt by us | `biomes[3].id`, `seedRoll.spawnFilter[1]` |
| `terralith:cave/thermal_caves` | rebuilt by us | `biomes[5].id`, `seedRoll.spawnFilter[0]` |

## the_end.json

Nothing flagged, dropped, rewritten or left for W8.

## the_end_citadel.json

Flagged ids (2):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `nullscape:crystal_peaks` | rebuilt by us | `biomes[1].id` |
| `nullscape:void_barrens` | rebuilt by us | `biomes[5].id` |

## the_endless_expanse.json

Flagged ids (2):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `terralith:brushland` | rebuilt by us | `seedRoll.spawnFilter[5]` |
| `terralith:steppe` | rebuilt by us | `seedRoll.spawnFilter[2]` |

## the_forged_depths.json

Flagged ids (8):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `betternether:crimson_glowing_woods` | rebuilt by us | `biomes[3].id` |
| `betternether:crimson_pinewood` | rebuilt by us | `biomes[2].id` |
| `betternether:magma_land` | rebuilt by us | `biomes[5].id` |
| `incendium:quartz_flats` | rebuilt by us | `biomes[0].id`, `seedRoll.spawnFilter[2]` |
| `incendium:volcanic_deltas` | rebuilt by us | `biomes[6].id`, `seedRoll.spawnFilter[1]` |
| `regions_unexplored:inferno` | rebuilt by us | `biomes[7].id` |

## the_fractured_halls.json

Flagged ids (5):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `nullscape:crystal_peaks` | rebuilt by us | `biomes[8].id` |
| `nullscape:shadowlands` | rebuilt by us | `biomes[0].id` |
| `terralith:amethyst_canyon` | rebuilt by us | `biomes[4].id` |
| `terralith:moonlight_grove` | rebuilt by us | `biomes[3].id` |
| `terralith:moonlight_valley` | rebuilt by us | `biomes[2].id` |

## the_frozen_hearth.json

Flagged ids (10):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `natures_spirit:snowy_fir_forest` | rebuilt by us | `biomes[20].id` |
| `natures_spirit:tundra` | rebuilt by us | `biomes[16].id` |
| `regions_unexplored:cold_boreal_taiga` | rebuilt by us | `biomes[19].id` |
| `regions_unexplored:frozen_pine_taiga` | rebuilt by us | `biomes[17].id` |
| `regions_unexplored:golden_boreal_taiga` | rebuilt by us | `biomes[21].id` |
| `terralith:alpine_grove` | rebuilt by us | `biomes[15]` |
| `terralith:birch_taiga` | rebuilt by us | `biomes[12]` |
| `terralith:rocky_mountains` | rebuilt by us | `biomes[13]` |
| `terralith:wintry_forest` | rebuilt by us | `biomes[14]` |
| `wilderwild:snowy_old_growth_pine_taiga` | rebuilt by us | `biomes[18].id` |

W8 decisions:

- v1 noiseSettings adventure:wide is built from Terralith and Tectonic data; terrain.base is null

## the_frozen_strait.json

Flagged ids (7):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `regions_unexplored:frozen_tundra` | rebuilt by us | `biomes[5].id`, `seedRoll.spawnFilter[1]` |
| `regions_unexplored:icy_heights` | rebuilt by us | `biomes[9].id` |
| `terralith:cave/frostfire_caves` | rebuilt by us | `biomes[11].id` |
| `terralith:frozen_cliffs` | rebuilt by us | `biomes[10].id` |
| `terralith:ice_marsh` | rebuilt by us | `biomes[4].id` |
| `wilderwild:tundra` | rebuilt by us | `biomes[6].id` |

W8 decisions:

- v1 noiseSettings adventure:wide is built from Terralith and Tectonic data; terrain.base is null

## the_fungal_lanterns.json

Flagged ids (5):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `incendium:inverted_forest` | rebuilt by us | `biomes[2].id`, `seedRoll.spawnFilter[2]` |
| `incendium:quartz_flats` | rebuilt by us | `biomes[4].id` |
| `regions_unexplored:infernal_holt` | rebuilt by us | `biomes[5].id` |
| `terralith:cave/fungal_caves` | rebuilt by us | `biomes[3].id` |

## the_furnace_halls.json

Flagged ids (2):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `betterfortresses:fortress` | waiting | `structures.force[0].structure` |
| `incendium:volcanic_deltas` | rebuilt by us | `biomes[3]` |

## the_gauntlet.json

Flagged ids (25):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `dungeons_arise:keep_kayra` | waiting | `structures.terrainAdaptation.dungeons_arise:keep_kayra (key)` |
| `dungeons_arise:scorched_mines` | waiting | `structures.force[0].structure` |
| `incendium:ash_barrens` | rebuilt by us | `biomes[20].id` |
| `natures_spirit:snowcapped_red_peaks` | rebuilt by us | `biomes[23].id` |
| `nullscape:crystal_peaks` | rebuilt by us | `biomes[18].id` |
| `regions_unexplored:icy_heights` | rebuilt by us | `biomes[22].id` |
| `regions_unexplored:spires` | rebuilt by us | `biomes[21].id` |
| `regions_unexplored:towering_cliffs` | rebuilt by us | `biomes[19].id` |
| `terralith:basalt_cliffs` | rebuilt by us | `biomes[11]`, `seedRoll.spawnFilter[7]` |
| `terralith:caldera` | rebuilt by us | `biomes[10]`, `seedRoll.spawnFilter[3]` |
| `terralith:desert_spires` | rebuilt by us | `biomes[28].id`, `biomePatches[0].biome` |
| `terralith:granite_cliffs` | rebuilt by us | `biomes[13]` |
| `terralith:haze_mountain` | rebuilt by us | `biomes[12]` |
| `terralith:rocky_mountains` | rebuilt by us | `biomes[9]`, `seedRoll.spawnFilter[6]` |
| `terralith:scarlet_mountains` | rebuilt by us | `biomes[15]` |
| `terralith:white_cliffs` | rebuilt by us | `biomes[14]` |
| `terralith:windswept_spires` | rebuilt by us | `biomes[17]` |
| `undergroundworlds:underground_desert` | rebuilt by us | `biomes[24]` |
| `wilderwild:dying_mixed_forest` | rebuilt by us | `biomes[25]` |
| `wilderwild:snowy_dying_forest` | rebuilt by us | `biomes[26]` |
| `wilderwild:snowy_dying_mixed_forest` | rebuilt by us | `biomes[27]` |

W8 decisions:

- v1 noiseSettings adventure:compressed is built from Terralith and Tectonic data; terrain.base is null

## the_gilded_pit.json

Flagged ids (12):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `betterfortresses:fortress` | waiting | `structures.force[1].structure` |
| `betternether:gravel_desert` | rebuilt by us | `biomes[0].id` |
| `betternether:poor_nether_grasslands` | rebuilt by us | `biomes[1].id` |
| `dungeons_arise:foundry` | waiting | `structures.force[6].structure` |
| `dungeons_arise:keep_kayra` | waiting | `structures.terrainAdaptation.dungeons_arise:keep_kayra (key)`, `structures.force[7].structure` |
| `dungeons_arise:shiraz_palace` | waiting | `structures.terrainAdaptation.dungeons_arise:shiraz_palace (key)`, `structures.force[5].structure` |
| `incendium:forbidden_castle` | rebuilt by us | `structures.terrainAdaptation.incendium:forbidden_castle (key)`, `structures.force[2].structure` |
| `philipsruins:antiquus_crypta` | rebuilt by us | `structures.force[8].structure` |
| `regions_unexplored:inferno` | rebuilt by us | `biomes[5].id` |

## the_glacial_drift.json

Flagged ids (12):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `natures_spirit:sleeted_slopes` | rebuilt by us | `biomes[10].id` |
| `natures_spirit:tundra` | rebuilt by us | `biomes[3].id`, `seedRoll.spawnFilter[0]` |
| `nullscape:void_barrens` | rebuilt by us | `biomes[2].id` |
| `paradise_lost:autumnal_tundra` | rebuilt by us | `biomes[5].id` |
| `paradise_lost:calcite_craglands` | rebuilt by us | `biomes[8].id` |
| `paradise_lost:palace` | rebuilt by us | `structures.terrainAdaptation.paradise_lost:palace (key)`, `structures.force[0].structure` |
| `terralith:frozen_cliffs` | rebuilt by us | `biomes[4].id` |
| `terralith:glacial_chasm` | rebuilt by us | `biomes[0].id` |
| `terralith:skylands_winter` | rebuilt by us | `biomes[1].id` |
| `terralith:snowy_shield` | rebuilt by us | `biomes[6].id` |

Dropped:

- `settingsOverrides.endIsland: false (only the end preset reads it; a no-op on preset floating_islands)`
- `seedRoll.family: "overworld"`

## the_greenreach.json

Flagged ids (12):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `regions_unexplored:barley_fields` | rebuilt by us | `biomes[9].id` |
| `regions_unexplored:clover_plains` | rebuilt by us | `biomes[0].id`, `seedRoll.spawnFilter[0]` |
| `regions_unexplored:grassland` | rebuilt by us | `biomes[12].id` |
| `regions_unexplored:poppy_fields` | rebuilt by us | `biomes[10].id` |
| `regions_unexplored:prairie` | rebuilt by us | `biomes[11].id` |
| `regions_unexplored:pumpkin_fields` | rebuilt by us | `biomes[13].id` |
| `terralith:brushland` | rebuilt by us | `biomes[4].id` |
| `terralith:cave/tuff_caves` | rebuilt by us | `biomes[15].id` |
| `terralith:highlands` | rebuilt by us | `biomes[8].id` |
| `terralith:shield_clearing` | rebuilt by us | `biomes[7].id` |
| `terralith:steppe` | rebuilt by us | `biomes[5].id` |

W8 decisions:

- v1 noiseSettings adventure:wide is built from Terralith and Tectonic data; terrain.base is null

## the_greywoods.json

Flagged ids (9):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `natures_spirit:cedar_thicket` | rebuilt by us | `biomes[0].id` |
| `natures_spirit:fir_forest` | rebuilt by us | `biomes[3].id` |
| `regions_unexplored:fungal_fen` | rebuilt by us | `biomes[6].id` |
| `terralith:birch_taiga` | rebuilt by us | `biomes[2].id`, `seedRoll.spawnFilter[0]` |
| `terralith:cave/infested_caves` | rebuilt by us | `biomes[10].id` |
| `terralith:moonlight_grove` | rebuilt by us | `biomes[4].id` |
| `wilderwild:dark_birch_forest` | rebuilt by us | `biomes[8].id` |
| `wilderwild:dying_forest` | rebuilt by us | `biomes[7].id` |

## the_gritlands.json

Flagged ids (14):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `betterdungeons:zombie_dungeon` | waiting | `structures.force[3].structure` |
| `philipsruins:field_stone_ruins` | rebuilt by us | `structures.force[1].structure` |
| `regions_unexplored:chalk_cliffs` | rebuilt by us | `biomes[10].id` |
| `regions_unexplored:mauve_hills` | rebuilt by us | `biomes[9].id` |
| `regions_unexplored:outback` | rebuilt by us | `biomes[0].id` |
| `terralith:ashen_savanna` | rebuilt by us | `biomes[3].id` |
| `terralith:basalt_cliffs` | rebuilt by us | `biomes[2].id` |
| `terralith:cave/andesite_caves` | rebuilt by us | `biomes[11].id` |
| `terralith:cold_shrubland` | rebuilt by us | `biomes[8].id` |
| `terralith:gravel_desert` | rebuilt by us | `biomes[1].id`, `seedRoll.spawnFilter[1]` |
| `terralith:rocky_shrubland` | rebuilt by us | `biomes[7].id` |
| `terralith:volcanic_peaks` | rebuilt by us | `biomes[4].id`, `seedRoll.spawnFilter[2]` |

W8 decisions:

- v1 noiseSettings adventure:compressed is built from Terralith and Tectonic data; terrain.base is null

## the_highland_crossing.json

Flagged ids (23):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `natures_spirit:heather_fields` | rebuilt by us | `biomes[28].id`, `seedRoll.spawnFilter[3]` |
| `natures_spirit:red_peaks` | rebuilt by us | `biomes[18].id` |
| `regions_unexplored:fen` | rebuilt by us | `biomes[27].id`, `seedRoll.spawnFilter[2]` |
| `regions_unexplored:highland_fields` | rebuilt by us | `biomes[25].id` |
| `regions_unexplored:mauve_hills` | rebuilt by us | `biomes[22].id` |
| `regions_unexplored:mountains` | rebuilt by us | `biomes[23].id` |
| `regions_unexplored:pine_slopes` | rebuilt by us | `biomes[21].id` |
| `regions_unexplored:spires` | rebuilt by us | `biomes[26].id` |
| `regions_unexplored:towering_cliffs` | rebuilt by us | `biomes[24].id` |
| `terralith:basalt_cliffs` | rebuilt by us | `biomes[17].id` |
| `terralith:blooming_plateau` | rebuilt by us | `biomes[14].id` |
| `terralith:caldera` | rebuilt by us | `biomes[13].id` |
| `terralith:forested_highlands` | rebuilt by us | `biomes[9].id` |
| `terralith:granite_cliffs` | rebuilt by us | `biomes[16].id` |
| `terralith:haze_mountain` | rebuilt by us | `biomes[11].id` |
| `terralith:highlands` | rebuilt by us | `biomes[7].id` |
| `terralith:rocky_mountains` | rebuilt by us | `biomes[10].id` |
| `terralith:temperate_highlands` | rebuilt by us | `biomes[8].id` |
| `terralith:white_cliffs` | rebuilt by us | `biomes[12].id` |
| `terralith:windswept_spires` | rebuilt by us | `biomes[20].id` |
| `terralith:yosemite_cliffs` | rebuilt by us | `biomes[19].id` |

W8 decisions:

- v1 noiseSettings adventure:compressed is built from Terralith and Tectonic data; terrain.base is null

## the_icebound_rift.json

Flagged ids (13):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `friendsandfoes:citadel` | waiting | `structures.terrainAdaptation.friendsandfoes:citadel (key)`, `structures.force[0].structure` |
| `nullscape:void_barrens` | rebuilt by us | `biomes[2].id` |
| `terralith:alpha_islands_winter` | rebuilt by us | `biomes[9].id`, `seedRoll.spawnFilter[2]` |
| `terralith:cave/frostfire_caves` | rebuilt by us | `biomes[8].id` |
| `terralith:frozen_cliffs` | rebuilt by us | `biomes[7].id` |
| `terralith:glacial_chasm` | rebuilt by us | `biomes[6].id`, `seedRoll.spawnFilter[1]` |
| `undergroundworlds:underground_tundra` | rebuilt by us | `biomes[10].id` |
| `wilderwild:frozen_caves` | rebuilt by us | `biomes[11].id` |
| `wilderwild:mesoglea_caves` | rebuilt by us | `biomes[4].id` |
| `yungscavebiomes:frosted_caves` | rebuilt by us | `biomes[0].id` |

## the_lantern_pools.json

Flagged ids (12):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `explorify:campsite` | waiting | `structures.force[0].structure` |
| `philipsruins:ancient_ruins` | rebuilt by us | `structures.force[1].structure` |
| `regions_unexplored:glistering_meadow` | rebuilt by us | `biomes[10].id` |
| `regions_unexplored:tropics` | rebuilt by us | `biomes[11].id` |
| `terralith:cave/tuff_caves` | rebuilt by us | `biomes[9]` |
| `terralith:desert_oasis` | rebuilt by us | `biomes[0]` |
| `terralith:lush_valley` | rebuilt by us | `biomes[2]` |
| `terralith:orchid_swamp` | rebuilt by us | `biomes[3]` |
| `terralith:warm_river` | rebuilt by us | `biomes[1]`, `seedRoll.spawnFilter[1]` |
| `wilderwild:oasis` | rebuilt by us | `biomes[12].id`, `seedRoll.spawnFilter[0]` |

## the_lost_outpost.json

Flagged ids (16):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `dungeons_arise:bandit_towers` | waiting | `structures.force[0].structure` |
| `dungeons_arise:illager_campsite` | waiting | `structures.force[2].structure` |
| `natures_spirit:golden_wilds` | rebuilt by us | `biomes[1].id` |
| `natures_spirit:oak_savanna` | rebuilt by us | `biomes[0].id`, `seedRoll.spawnFilter[0]` |
| `regions_unexplored:deciduous_forest` | rebuilt by us | `biomes[9].id` |
| `regions_unexplored:shrubland` | rebuilt by us | `biomes[8].id` |
| `terralith:cave/granite_caves` | rebuilt by us | `biomes[10].id` |
| `terralith:cave/infested_caves` | rebuilt by us | `biomes[11].id` |
| `terralith:fractured_savanna` | rebuilt by us | `biomes[4].id`, `seedRoll.spawnFilter[1]` |
| `terralith:savanna_badlands` | rebuilt by us | `biomes[5].id` |
| `terralith:savanna_slopes` | rebuilt by us | `biomes[6].id` |
| `wilderwild:arid_savanna` | rebuilt by us | `biomes[3].id`, `seedRoll.spawnFilter[2]`, `biomePatches[0].biome` |

## the_luminous_caverns.json

Flagged ids (14):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `adventuredungeons:underground_camp` | waiting | `structures.force[0].structure`, `structures.terrainAdaptation.adventuredungeons:underground_camp (key)` |
| `galosphere:crystal_canyons` | rebuilt by us | `biomes[9].id` |
| `galosphere:lichen_caves` | rebuilt by us | `biomes[7].id` |
| `galosphere:pink_salt_caves` | rebuilt by us | `biomes[11].id` |
| `incendium:quartz_flats` | rebuilt by us | `biomes[8].id` |
| `terralith:cave/andesite_caves` | rebuilt by us | `biomes[4].id` |
| `terralith:cave/diorite_caves` | rebuilt by us | `biomes[5].id` |
| `terralith:cave/thermal_caves` | rebuilt by us | `biomes[1].id`, `seedRoll.spawnFilter[1]` |
| `terralith:cave/tuff_caves` | rebuilt by us | `biomes[2].id` |
| `undergroundworlds:underground_desert` | rebuilt by us | `biomes[13].id` |
| `yungscavebiomes:frosted_caves` | rebuilt by us | `biomes[6].id` |
| `yungscavebiomes:lost_caves` | rebuilt by us | `biomes[12].id` |

## the_miredeep.json

Flagged ids (8):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `natures_spirit:marsh` | rebuilt by us | `biomes[2].id`, `seedRoll.spawnFilter[0]` |
| `regions_unexplored:bayou` | rebuilt by us | `biomes[8].id` |
| `regions_unexplored:old_growth_bayou` | rebuilt by us | `biomes[9].id` |
| `structory:old_manor` | cut | `structures.force[0].structure` |
| `terralith:cave/fungal_caves` | rebuilt by us | `biomes[10].id` |
| `terralith:moonlight_valley` | rebuilt by us | `biomes[7].id` |
| `terralith:orchid_swamp` | rebuilt by us | `biomes[0].id` |

## the_molten_flats.json

Flagged ids (8):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `incendium:ash_barrens` | rebuilt by us | `biomes[0].id` |
| `incendium:infernal_dunes` | rebuilt by us | `biomes[3].id`, `seedRoll.spawnFilter[1]` |
| `incendium:nether_reactor` | rebuilt by us | `structures.terrainAdaptation.incendium:nether_reactor (key)` |
| `incendium:pipeline` | rebuilt by us | `structures.terrainAdaptation.incendium:pipeline (key)` |
| `incendium:volcanic_deltas` | rebuilt by us | `biomes[5].id`, `seedRoll.spawnFilter[2]` |
| `regions_unexplored:inferno` | rebuilt by us | `biomes[4].id` |

## the_needlefall.json

Flagged ids (8):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `natures_spirit:coniferous_covert` | rebuilt by us | `biomes[2].id` |
| `natures_spirit:fir_forest` | rebuilt by us | `biomes[0].id` |
| `regions_unexplored:boreal_taiga` | rebuilt by us | `biomes[7].id` |
| `regions_unexplored:pine_taiga` | rebuilt by us | `biomes[6].id` |
| `structory:firetower` | cut | `structures.terrainAdaptation.structory:firetower (key)` |
| `terralith:cave/frostfire_caves` | rebuilt by us | `biomes[8].id` |
| `terralith:cave/tuff_caves` | rebuilt by us | `biomes[9].id` |
| `terralith:siberian_taiga` | rebuilt by us | `biomes[1].id` |

W8 decisions:

- v1 noiseSettings adventure:compressed is built from Terralith and Tectonic data; terrain.base is null

## the_nether.json

Flagged ids (1):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `incendium:quartz_flats` | rebuilt by us | `seedRoll.spawnFilter[2]` |

## the_obsidian_sanctum.json

Flagged ids (2):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `chipped:runic_carved_obsidian` | cut | `portal.frameBlock` |
| `incendium:withered_forest` | rebuilt by us | `biomes[2]` |

## the_overgrowth.json

Flagged ids (8):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `natures_spirit:tropical_basin` | rebuilt by us | `biomes[2].id` |
| `regions_unexplored:rainforest` | rebuilt by us | `biomes[8].id` |
| `terralith:cave/infested_caves` | rebuilt by us | `biomes[11].id` |
| `terralith:cave/underground_jungle` | rebuilt by us | `biomes[10].id` |
| `terralith:cloud_forest` | rebuilt by us | `biomes[3].id` |
| `terralith:jungle_mountains` | rebuilt by us | `biomes[1].id` |
| `terralith:rocky_jungle` | rebuilt by us | `biomes[0].id` |
| `wilderwild:rainforest` | rebuilt by us | `biomes[9].id` |

W8 decisions:

- v1 noiseSettings adventure:compressed is built from Terralith and Tectonic data; terrain.base is null

## the_pale_reach.json

Flagged ids (9):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `nullscape:void_barrens` | rebuilt by us | `biomes[3].id` |
| `palegardenbackport:pale_garden` | replaced | `biomePatches[0].biome` |
| `paradise_lost:calcite_craglands` | rebuilt by us | `biomes[6].id` |
| `paradise_lost:vault` | rebuilt by us | `structures.terrainAdaptation.paradise_lost:vault (key)`, `structures.force[0].structure` |
| `terralith:frozen_cliffs` | rebuilt by us | `biomes[1].id` |
| `terralith:skylands_winter` | rebuilt by us | `biomes[0].id` |
| `terralith:white_cliffs` | rebuilt by us | `biomes[5].id` |
| `terralith:wintry_lowlands` | rebuilt by us | `biomes[2].id` |

Dropped:

- `settingsOverrides.endIsland: false (only the end preset reads it; a no-op on preset floating_islands)`
- `seedRoll.family: "overworld"`

## the_pillared_void.json

Flagged ids (3):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `nullscape:crystal_peaks` | rebuilt by us | `biomes[5].id` |
| `nullscape:dragon_skeleton` | rebuilt by us | `structures.force[0].structure` |
| `nullscape:void_barrens` | rebuilt by us | `biomes[0].id` |

## the_red_monument.json

Flagged ids (4):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `nullscape:crystal_peaks` | rebuilt by us | `biomes[7].id` |
| `nullscape:shadowlands` | rebuilt by us | `biomes[0].id` |
| `terralith:red_oasis` | rebuilt by us | `biomes[4].id` |
| `terralith:scarlet_mountains` | rebuilt by us | `biomes[2].id` |

## the_roothold.json

Flagged ids (8):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `incendium:withered_forest` | rebuilt by us | `biomes[11].id` |
| `natures_spirit:redwood_forest` | rebuilt by us | `biomes[0].id` |
| `regions_unexplored:old_growth_forest` | rebuilt by us | `biomes[6].id` |
| `regions_unexplored:willow_forest` | rebuilt by us | `biomes[7].id` |
| `terralith:cave/infested_caves` | rebuilt by us | `biomes[10].id` |
| `terralith:forested_highlands` | rebuilt by us | `biomes[5].id` |
| `terralith:shield` | rebuilt by us | `biomes[2].id` |
| `wilderwild:old_growth_dark_forest` | rebuilt by us | `biomes[8].id` |

## the_rosebluff.json

Flagged ids (8):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `natures_spirit:wisteria_forest` | rebuilt by us | `biomes[0].id` |
| `regions_unexplored:magnolia_woodland` | rebuilt by us | `biomes[10].id` |
| `regions_unexplored:wisteria_grove` | rebuilt by us | `biomes[9].id` |
| `terralith:blooming_valley` | rebuilt by us | `biomes[7].id` |
| `terralith:cave/diorite_caves` | rebuilt by us | `biomes[12].id` |
| `terralith:lavender_forest` | rebuilt by us | `biomes[3].id` |
| `terralith:sakura_grove` | rebuilt by us | `biomes[1].id` |
| `terralith:white_cliffs` | rebuilt by us | `biomes[4].id` |

## the_ruined_timberland.json

Flagged ids (6):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `natures_spirit:aspen_forest` | rebuilt by us | `biomes[0].id` |
| `natures_spirit:maple_woodlands` | rebuilt by us | `biomes[1].id` |
| `regions_unexplored:autumnal_maple_forest` | rebuilt by us | `biomes[6].id` |
| `terralith:cave/deep_caves` | rebuilt by us | `biomes[8].id` |
| `terralith:moonlight_grove` | rebuilt by us | `biomes[4].id` |
| `wilderwild:maple_forest` | rebuilt by us | `biomes[7].id` |

## the_scorched_mesa.json

Flagged ids (11):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `natures_spirit:stratified_desert` | rebuilt by us | `biomes[4].id` |
| `regions_unexplored:arid_mountains` | rebuilt by us | `biomes[12].id` |
| `regions_unexplored:joshua_desert` | rebuilt by us | `biomes[11].id` |
| `regions_unexplored:saguaro_desert` | rebuilt by us | `biomes[10].id` |
| `terralith:bryce_canyon` | rebuilt by us | `biomes[2].id` |
| `terralith:cave/thermal_caves` | rebuilt by us | `biomes[13].id` |
| `terralith:desert_canyon` | rebuilt by us | `biomes[5].id` |
| `terralith:painted_mountains` | rebuilt by us | `biomes[0].id` |
| `terralith:savanna_badlands` | rebuilt by us | `biomes[8].id` |
| `terralith:white_mesa` | rebuilt by us | `biomes[1].id` |
| `wilderwild:magmatic_caves` | rebuilt by us | `biomes[14].id` |

W8 decisions:

- v1 noiseSettings adventure:compressed is built from Terralith and Tectonic data; terrain.base is null

## the_sculked_beyond.json

Flagged ids (13):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `incendium:withered_forest` | rebuilt by us | `biomes[8].id` |
| `nullscape:shadowlands` | rebuilt by us | `biomes[4].id` |
| `philipsruins:sculk_dungeon` | rebuilt by us | `structures.force[0].structure` |
| `regions_unexplored:bioshroom_caves` | rebuilt by us | `biomes[10].id` |
| `regions_unexplored:mycotoxic_undergrowth` | rebuilt by us | `biomes[9].id` |
| `regions_unexplored:redstone_abyss` | rebuilt by us | `biomes[0].id` |
| `terralith:cave/deep_caves` | rebuilt by us | `biomes[7].id` |
| `terralith:cave/fungal_caves` | rebuilt by us | `biomes[5].id` |
| `terralith:cave/infested_caves` | rebuilt by us | `biomes[2].id`, `seedRoll.spawnFilter[1]` |
| `terralith:cave/mantle_caves` | rebuilt by us | `biomes[3].id` |
| `terralith:cave/tuff_caves` | rebuilt by us | `biomes[6].id` |
| `undergroundworlds:spider_cave` | rebuilt by us | `biomes[11].id` |

## the_shallows.json

Flagged ids (7):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `natures_spirit:tropical_shores` | rebuilt by us | `biomes[3].id` |
| `regions_unexplored:rocky_reef` | rebuilt by us | `biomes[7].id` |
| `regions_unexplored:tropical_river` | rebuilt by us | `biomes[6].id` |
| `terralith:cave/tuff_caves` | rebuilt by us | `biomes[10].id` |
| `terralith:deep_warm_ocean` | rebuilt by us | `biomes[4].id` |
| `terralith:mirage_isles` | rebuilt by us | `biomes[0].id` |
| `wilderwild:warm_beach` | rebuilt by us | `biomes[8].id` |

W8 decisions:

- v1 noiseSettings adventure:wide is built from Terralith and Tectonic data; terrain.base is null

## the_shattered_skies.json

Flagged ids (17):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `terralith:alpine_grove` | rebuilt by us | `biomes[9]` |
| `terralith:blooming_plateau` | rebuilt by us | `biomes[12]` |
| `terralith:blooming_valley` | rebuilt by us | `biomes[6]`, `seedRoll.spawnFilter[3]` |
| `terralith:lavender_valley` | rebuilt by us | `biomes[11]` |
| `terralith:lush_valley` | rebuilt by us | `biomes[10]`, `seedRoll.spawnFilter[9]` |
| `terralith:mirage_isles` | rebuilt by us | `biomes[18]` |
| `terralith:sakura_grove` | rebuilt by us | `biomes[8]`, `seedRoll.spawnFilter[8]` |
| `terralith:sakura_valley` | rebuilt by us | `biomes[7]` |
| `terralith:shrubland` | rebuilt by us | `biomes[13]` |
| `terralith:skylands_autumn` | rebuilt by us | `biomes[15]` |
| `terralith:skylands_spring` | rebuilt by us | `biomes[14]`, `seedRoll.spawnFilter[2]` |
| `terralith:skylands_summer` | rebuilt by us | `biomes[16]` |
| `terralith:skylands_winter` | rebuilt by us | `biomes[17]` |

Dropped:

- `settingsOverrides.endIsland: false (only the end preset reads it; a no-op on preset floating_islands)`

## the_slatemouth.json

Flagged ids (4):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `incendium:quartz_flats` | rebuilt by us | `biomes[2].id` |
| `incendium:toxic_heap` | rebuilt by us | `biomes[3].id` |
| `nullscape:shadowlands` | rebuilt by us | `biomes[6].id` |
| `nullscape:void_barrens` | rebuilt by us | `biomes[4].id` |

## the_snowbound_isle.json

Flagged ids (8):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `natures_spirit:snowy_fir_forest` | rebuilt by us | `biomes[3].id` |
| `paradise_lost:aurel_tower` | rebuilt by us | `structures.terrainAdaptation.paradise_lost:aurel_tower (key)` |
| `paradise_lost:autumnal_tundra` | rebuilt by us | `biomes[0].id` |
| `paradise_lost:highlands_shield` | rebuilt by us | `biomes[1].id` |
| `paradise_lost:paradise_lost` | rebuilt by us | `type` |
| `terralith:frozen_cliffs` | rebuilt by us | `biomes[6].id` |
| `terralith:skylands_winter` | rebuilt by us | `biomes[5].id` |
| `terralith:snowy_cherry_grove` | rebuilt by us | `biomes[4].id` |

Dropped:

- `seedRoll.family: "overworld"`

Rewrites:

- `generator.of`: `paradise_lost:paradise_lost` → `adventure:the_aviary`

## the_souldrift.json

Flagged ids (18):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `incendium:ash_barrens` | rebuilt by us | `biomes[11].id` |
| `incendium:infernal_dunes` | rebuilt by us | `biomes[16].id` |
| `incendium:toxic_heap` | rebuilt by us | `biomes[14].id` |
| `incendium:volcanic_deltas` | rebuilt by us | `biomes[17].id` |
| `nullscape:shadowlands` | rebuilt by us | `biomes[9].id` |
| `regions_unexplored:mycotoxic_undergrowth` | rebuilt by us | `biomes[18].id` |
| `regions_unexplored:prismachasm` | rebuilt by us | `biomes[20].id` |
| `regions_unexplored:redstone_abyss` | rebuilt by us | `biomes[12].id` |
| `regions_unexplored:redstone_caves` | rebuilt by us | `biomes[19].id` |
| `terralith:ashen_savanna` | rebuilt by us | `biomes[0]`, `seedRoll.spawnFilter[0]` |
| `terralith:basalt_cliffs` | rebuilt by us | `biomes[5]` |
| `terralith:gravel_desert` | rebuilt by us | `biomes[2]` |
| `terralith:moonlight_grove` | rebuilt by us | `biomes[6]` |
| `terralith:volcanic_crater` | rebuilt by us | `biomes[3]`, `seedRoll.spawnFilter[2]` |
| `terralith:white_cliffs` | rebuilt by us | `biomes[8]` |
| `wilderwild:dying_mixed_forest` | rebuilt by us | `biomes[21]` |

## the_soulfields.json

Flagged ids (4):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `incendium:ash_barrens` | rebuilt by us | `biomes[1].id`, `seedRoll.spawnFilter[1]` |
| `incendium:weeping_valley` | rebuilt by us | `biomes[3].id` |
| `incendium:withered_forest` | rebuilt by us | `biomes[4].id` |

## the_starwell.json

Flagged ids (8):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `nullscape:shadowlands` | rebuilt by us | `biomePatches[0].biome` |
| `terralith:alpha_islands` | rebuilt by us | `biomes[2]`, `seedRoll.spawnFilter[2]` |
| `terralith:alpha_islands_winter` | rebuilt by us | `biomes[3]` |
| `terralith:mirage_isles` | rebuilt by us | `biomes[0]`, `seedRoll.spawnFilter[0]` |
| `terralith:skylands_winter` | rebuilt by us | `biomes[1]`, `seedRoll.spawnFilter[1]` |

## the_stonemantle.json

Flagged ids (13):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `regions_unexplored:mauve_hills` | rebuilt by us | `biomes[12].id` |
| `regions_unexplored:mountains` | rebuilt by us | `biomes[10].id` |
| `regions_unexplored:pine_slopes` | rebuilt by us | `biomes[11].id` |
| `regions_unexplored:spires` | rebuilt by us | `biomes[9].id` |
| `regions_unexplored:towering_cliffs` | rebuilt by us | `biomes[8].id` |
| `terralith:basalt_cliffs` | rebuilt by us | `biomes[5].id` |
| `terralith:cave/deep_caves` | rebuilt by us | `biomes[14].id` |
| `terralith:cave/granite_caves` | rebuilt by us | `biomes[13].id`, `seedRoll.spawnFilter[1]` |
| `terralith:rocky_mountains` | rebuilt by us | `biomes[0].id`, `seedRoll.spawnFilter[0]` |
| `terralith:scarlet_mountains` | rebuilt by us | `biomes[6].id` |
| `terralith:volcanic_peaks` | rebuilt by us | `biomes[7].id` |

W8 decisions:

- v1 noiseSettings adventure:compressed is built from Terralith and Tectonic data; terrain.base is null

## the_sun_kingdoms.json

Flagged ids (22):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `dungeons_arise:keep_kayra` | waiting | `structures.terrainAdaptation.dungeons_arise:keep_kayra (key)` |
| `natures_spirit:oak_savanna` | rebuilt by us | `biomes[18].id` |
| `natures_spirit:scorched_dunes` | rebuilt by us | `biomes[26].id` |
| `natures_spirit:stratified_desert` | rebuilt by us | `biomes[17].id` |
| `regions_unexplored:baobab_savanna` | rebuilt by us | `biomes[22].id` |
| `regions_unexplored:joshua_desert` | rebuilt by us | `biomes[24].id` |
| `terralith:ancient_sands` | rebuilt by us | `biomes[19]` |
| `terralith:arid_highlands` | rebuilt by us | `biomes[3]` |
| `terralith:brushland` | rebuilt by us | `biomes[7]` |
| `terralith:desert_canyon` | rebuilt by us | `biomes[14]` |
| `terralith:desert_oasis` | rebuilt by us | `biomes[13]` |
| `terralith:desert_spires` | rebuilt by us | `biomes[25]` |
| `terralith:fractured_savanna` | rebuilt by us | `biomes[5]` |
| `terralith:hot_shrubland` | rebuilt by us | `biomes[4]` |
| `terralith:lush_desert` | rebuilt by us | `biomes[12]` |
| `terralith:red_oasis` | rebuilt by us | `biomes[15]` |
| `terralith:savanna_badlands` | rebuilt by us | `biomes[6]` |
| `terralith:savanna_slopes` | rebuilt by us | `biomes[16]` |
| `terralith:shrubland` | rebuilt by us | `biomes[9]` |
| `terralith:steppe` | rebuilt by us | `biomes[8]` |
| `wilderwild:oasis` | rebuilt by us | `biomes[20].id` |
| `wilderwild:parched_forest` | rebuilt by us | `biomes[21].id` |

W8 decisions:

- v1 noiseSettings adventure:wide is built from Terralith and Tectonic data; terrain.base is null

## the_sunken_temple.json

Flagged ids (7):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `paradise_lost:highlands` | rebuilt by us | `biomes[1].id`, `seedRoll.spawnFilter[0]` |
| `paradise_lost:paradise_lost` | rebuilt by us | `type` |
| `paradise_lost:tradewinds` | rebuilt by us | `biomes[0].id` |
| `terralith:deep_warm_ocean` | rebuilt by us | `biomes[3].id` |
| `terralith:mirage_isles` | rebuilt by us | `biomes[6].id` |
| `terralith:skylands_summer` | rebuilt by us | `biomes[5].id` |

Dropped:

- `seedRoll.family: "overworld"`

Rewrites:

- `generator.of`: `paradise_lost:paradise_lost` → `adventure:the_aviary`

## the_teal_corruption.json

Flagged ids (10):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `incendium:inverted_forest` | rebuilt by us | `biomes[4].id` |
| `incendium:piglin_village` | rebuilt by us | `structures.force[0].structure` |
| `structory_towers:nether/warped_outpost` | cut | `structures.force[1].structure` |
| `terralith:ashen_savanna` | rebuilt by us | `biomes[7].id` |
| `terralith:cave/infested_caves` | rebuilt by us | `biomes[11].id` |
| `terralith:cave/thermal_caves` | rebuilt by us | `biomes[10].id` |
| `terralith:scarlet_mountains` | rebuilt by us | `biomes[6].id` |
| `terralith:volcanic_crater` | rebuilt by us | `biomes[8].id` |
| `terralith:warped_mesa` | rebuilt by us | `biomes[1].id`, `seedRoll.spawnFilter[2]` |

## the_tidepools.json

Flagged ids (8):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `philipsruins:ocean_ruins` | rebuilt by us | `structures.force[0].structure` |
| `regions_unexplored:grassy_beach` | rebuilt by us | `biomes[6].id` |
| `regions_unexplored:gravel_beach` | rebuilt by us | `biomes[4].id` |
| `regions_unexplored:rocky_reef` | rebuilt by us | `biomes[0].id` |
| `structory_towers:ocean_pillar` | cut | `structures.terrainAdaptation.structory_towers:ocean_pillar (key)`, `structures.force[1].structure` |
| `terralith:cave/tuff_caves` | rebuilt by us | `biomes[8].id` |
| `terralith:granite_cliffs` | rebuilt by us | `biomes[3].id` |

## the_tiled_expanse.json

Flagged ids (14):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `betterend:chorus_forest` | rebuilt by us | `biomes[6]` |
| `betterend:dragon_graveyards` | rebuilt by us | `biomes[7]` |
| `betterend:dust_wastelands` | rebuilt by us | `biomes[8]` |
| `betterend:neon_oasis` | rebuilt by us | `biomes[9]` |
| `incendium:quartz_flats` | rebuilt by us | `biomes[0]`, `seedRoll.spawnFilter[0]`, `biomePatches[1].biome`, `biomePatches[2].biome`, `biomePatches[3].biome`, `biomePatches[4].biome` |
| `nullscape:crystal_peaks` | rebuilt by us | `biomes[2]` |
| `nullscape:shadowlands` | rebuilt by us | `biomes[3]` |
| `nullscape:void_barrens` | rebuilt by us | `biomes[1]`, `seedRoll.spawnFilter[1]` |

## the_twisted_groves.json

Flagged ids (10):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `incendium:ash_barrens` | rebuilt by us | `biomes[7].id` |
| `incendium:inverted_forest` | rebuilt by us | `biomes[5].id` |
| `incendium:toxic_heap` | rebuilt by us | `biomes[1].id` |
| `incendium:withered_forest` | rebuilt by us | `biomes[0].id` |
| `regions_unexplored:marsh` | rebuilt by us | `biomes[6].id`, `biomePatches[0].biome`, `seedRoll.spawnFilter[0]` |
| `structory_towers:warped_greatsword` | cut | `structures.force[0].structure` |
| `terralith:cave/fungal_caves` | rebuilt by us | `biomes[8].id` |
| `terralith:warped_mesa` | rebuilt by us | `biomes[3].id` |

W8 decisions:

- v1 noiseSettings adventure:compressed is built from Terralith and Tectonic data; terrain.base is null

## the_underdark.json

Flagged ids (6):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `incendium:withered_forest` | rebuilt by us | `biomes[5].id` |
| `regions_unexplored:fungal_fen` | rebuilt by us | `biomes[10].id` |
| `terralith:cave/underground_jungle` | rebuilt by us | `biomes[12].id` |
| `terralith:moonlight_grove` | rebuilt by us | `biomes[2].id`, `seedRoll.spawnFilter[2]` |
| `undergroundworlds:spider_cave` | rebuilt by us | `biomes[13].id` |

## the_verdant_hollow.json

Flagged ids (11):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `regions_unexplored:clover_plains` | rebuilt by us | `biomes[8].id` |
| `regions_unexplored:flower_fields` | rebuilt by us | `biomes[12].id` |
| `regions_unexplored:glistering_meadow` | rebuilt by us | `biomes[7].id` |
| `regions_unexplored:orchard` | rebuilt by us | `biomes[9].id` |
| `regions_unexplored:rocky_meadow` | rebuilt by us | `biomes[11].id` |
| `regions_unexplored:temperate_grove` | rebuilt by us | `biomes[10].id` |
| `terralith:cave/fungal_caves` | rebuilt by us | `biomes[14].id` |
| `terralith:lavender_valley` | rebuilt by us | `biomes[4].id` |
| `terralith:lush_valley` | rebuilt by us | `biomes[0].id`, `seedRoll.spawnFilter[0]` |
| `terralith:temperate_highlands` | rebuilt by us | `biomes[5].id` |

W8 decisions:

- v1 noiseSettings adventure:wide is built from Terralith and Tectonic data; terrain.base is null

## the_violet_spire.json

Flagged ids (20):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `natures_spirit:lavender_fields` | rebuilt by us | `biomes[10].id`, `seedRoll.spawnFilter[0]` |
| `nullscape:crystal_peaks` | rebuilt by us | `biomes[2].id`, `biomePatches[0].biome` |
| `paradise_lost:aurel_tower` | rebuilt by us | `structures.force[0].structure`, `structures.terrainAdaptation.paradise_lost:aurel_tower (key)`, `structures.rarity.paradise_lost:aurel_tower (key)` |
| `paradise_lost:birdcage` | rebuilt by us | `structures.rarity.paradise_lost:birdcage (key)` |
| `paradise_lost:highlands` | rebuilt by us | `biomes[5].id` |
| `paradise_lost:highlands_forest` | rebuilt by us | `biomes[8].id` |
| `paradise_lost:highlands_thicket` | rebuilt by us | `biomes[6].id` |
| `paradise_lost:paradise_lost` | rebuilt by us | `type` |
| `paradise_lost:wisteria_woods` | rebuilt by us | `biomes[7].id` |
| `regions_unexplored:lavender_wisteria_sapling` | rebuilt by us | `portal.igniterItem` |
| `structory_towers:rare_towers` | cut | `structures.rarity.structory_towers:rare_towers (key)` |
| `terralith:amethyst_canyon` | rebuilt by us | `biomes[0].id`, `seedRoll.spawnFilter[1]` |
| `terralith:haze_mountain` | rebuilt by us | `biomes[3].id` |
| `terralith:skylands_spring` | rebuilt by us | `biomes[4].id` |
| `terralith:windswept_spires` | rebuilt by us | `biomes[1].id` |

Dropped:

- `seedRoll.family: "overworld"`

Rewrites:

- `generator.of`: `paradise_lost:paradise_lost` → `adventure:the_aviary`

## the_wailing_narrows.json

Flagged ids (1):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `incendium:quartz_flats` | rebuilt by us | `biomes[2]` |

## the_weeping_vault.json

Flagged ids (7):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `incendium:abandoned_tower` | rebuilt by us | `structures.terrainAdaptation.incendium:abandoned_tower (key)` |
| `incendium:forbidden_castle` | rebuilt by us | `structures.terrainAdaptation.incendium:forbidden_castle (key)`, `structures.force[0].structure` |
| `incendium:sanctum` | rebuilt by us | `structures.force[1].structure` |
| `incendium:weeping_valley` | rebuilt by us | `biomes[0].id`, `seedRoll.spawnFilter[0]` |
| `incendium:withered_forest` | rebuilt by us | `biomes[4].id` |

## the_whispering_wilds.json

Flagged ids (21):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `natures_spirit:aspen_forest` | rebuilt by us | `biomes[23].id` |
| `natures_spirit:cedar_thicket` | rebuilt by us | `biomes[17].id` |
| `natures_spirit:redwood_forest` | rebuilt by us | `biomes[24].id`, `seedRoll.spawnFilter[0]` |
| `philipsruins:sculk_dungeon` | rebuilt by us | `structures.force[1].structure` |
| `regions_unexplored:alpha_grove` | rebuilt by us | `biomes[22].id` |
| `regions_unexplored:old_growth_forest` | rebuilt by us | `biomes[19].id` |
| `regions_unexplored:redwoods` | rebuilt by us | `biomes[18].id` |
| `regions_unexplored:silver_birch_forest` | rebuilt by us | `biomes[20].id` |
| `regions_unexplored:sparse_redwoods` | rebuilt by us | `biomes[21].id` |
| `structory:dense_forest_ruin` | cut | `structures.terrainAdaptation.structory:dense_forest_ruin (key)` |
| `structory:northern_ruin` | cut | `structures.terrainAdaptation.structory:northern_ruin (key)` |
| `structory:old_manor` | cut | `structures.force[0].structure` |
| `structory:taiga_ruin_surface` | cut | `structures.terrainAdaptation.structory:taiga_ruin_surface (key)` |
| `terralith:birch_taiga` | rebuilt by us | `biomes[13]` |
| `terralith:cloud_forest` | rebuilt by us | `biomes[15]` |
| `terralith:moonlight_grove` | rebuilt by us | `biomes[14]` |
| `terralith:moonlight_valley` | rebuilt by us | `biomes[10]` |
| `terralith:orchid_swamp` | rebuilt by us | `biomes[11]` |
| `terralith:shield` | rebuilt by us | `biomes[12]` |
| `terralith:shield_clearing` | rebuilt by us | `biomes[16]` |

## the_whitestone_ford.json

Flagged ids (6):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `natures_spirit:white_cliffs` | rebuilt by us | `biomes[1].id` |
| `regions_unexplored:cold_river` | rebuilt by us | `biomes[6].id` |
| `terralith:cave/tuff_caves` | rebuilt by us | `biomes[8].id` |
| `terralith:warm_river` | rebuilt by us | `biomes[2].id` |
| `terralith:white_cliffs` | rebuilt by us | `biomes[0].id` |
| `wilderwild:warm_river` | rebuilt by us | `biomes[7].id` |

W8 decisions:

- v1 noiseSettings adventure:wide is built from Terralith and Tectonic data; terrain.base is null

## the_wuthering_wisteria.json

Flagged ids (9):

| Id | Owners' decisions | Where |
| --- | --- | --- |
| `explorify:campsite` | waiting | `structures.force[0].structure` |
| `natures_spirit:wisteria_forest` | rebuilt by us | `biomes[0].id` |
| `paradise_lost:vault` | rebuilt by us | `structures.force[4].structure` |
| `regions_unexplored:wisteria_grove` | rebuilt by us | `biomes[6].id` |
| `structory:ruin_grassy` | cut | `structures.force[1].structure` |
| `terralith:cave/mantle_caves` | rebuilt by us | `biomes[8].id` |
| `terralith:moonlight_valley` | rebuilt by us | `biomes[1].id` |
| `terralith:sakura_grove` | rebuilt by us | `biomes[2].id` |
| `terralith:sakura_valley` | rebuilt by us | `biomes[4].id` |

## settings.json

Nothing flagged, dropped, rewritten or left for W8.
