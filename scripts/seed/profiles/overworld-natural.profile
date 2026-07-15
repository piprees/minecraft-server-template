# overworld-natural.profile — the v3 main-world taste: sparse and natural.
# Villages, taverns and fortified villages ("castles") close; mega-dungeons
# FAR (a bounded /locate miss is a positive signal for sparseness); wide
# rolling terrain (low grain), some relief, not ocean-locked.
# Format documented in classic.profile. Weights sum to 100.

option|reject_bad_spawn|true
option|grid|3|512

locate|village|overworld|#minecraft:village
locate|fortified_village|overworld|terralith:fortified_village
locate|tavern|overworld|nova_structures:tavern_birch
locate|wda|overworld|dungeons_arise:coliseum
locate|ancient_city|overworld|minecraft:ancient_city

metric|structure_village_dist|near|18|1500
metric|structure_fortified_village_dist|near|12|3000
metric|structure_tavern_dist|near|8|1500
metric|structure_wda_dist|far|12|1500
metric|structure_ancient_city_dist|far|8|1200
metric|spawn_biome|tier|12
metric|terrain_grain|low|15|6|18
metric|terrain_relief|window|8|20|80|60
metric|water_fraction|window|7|0|0.35|0.3

# Spawn tiers: same lists as classic (green pastoral starts).
biome|green|minecraft:plains
biome|green|minecraft:sunflower_plains
biome|green|minecraft:meadow
biome|green|minecraft:savanna
biome|green|minecraft:forest
biome|green|minecraft:birch_forest
biome|green|minecraft:flower_forest
biome|green|minecraft:cherry_grove
biome|green|minecraft:old_growth_birch_forest
biome|green|minecraft:sparse_jungle
biome|green|minecraft:savanna_plateau
biome|green|terralith:blooming_valley
biome|green|terralith:lush_valley
biome|green|terralith:lavender_valley
biome|green|terralith:blooming_plateau
biome|green|terralith:sakura_valley
biome|green|terralith:sakura_grove
biome|green|terralith:temperate_highlands
biome|green|terralith:brushland
biome|green|terralith:steppe
biome|green|terralith:shrubland
biome|green|terralith:moonlight_valley
biome|green|terralith:moonlight_grove
biome|green|terralith:orchid_swamp
biome|green|terralith:alpine_grove
biome|green|terralith:lush_desert
biome|green|terralith:arid_highlands
biome|green|terralith:forested_highlands
biome|green|terralith:birch_taiga
biome|green|terralith:shield
biome|green|terralith:shield_clearing
biome|ok|minecraft:dark_forest
biome|ok|minecraft:taiga
biome|ok|minecraft:old_growth_pine_taiga
biome|ok|minecraft:old_growth_spruce_taiga
biome|ok|minecraft:jungle
biome|ok|minecraft:bamboo_jungle
biome|ok|minecraft:windswept_hills
biome|ok|minecraft:windswept_forest
biome|ok|minecraft:windswept_gravelly_hills
biome|ok|minecraft:wooded_badlands
biome|ok|minecraft:river
biome|ok|minecraft:beach
biome|ok|minecraft:stony_shore
biome|ok|minecraft:mangrove_swamp
biome|ok|minecraft:snowy_plains
biome|ok|minecraft:snowy_taiga
biome|ok|minecraft:grove
biome|ok|minecraft:desert
biome|ok|terralith:cloud_forest
biome|ok|terralith:haze_mountain
biome|ok|terralith:rocky_mountains
biome|ok|terralith:caldera
biome|ok|terralith:mirage_isles
biome|ok|terralith:granite_cliffs
biome|ok|terralith:highlands
biome|ok|terralith:basalt_cliffs
biome|ok|terralith:hot_shrubland
biome|ok|terralith:desert_canyon
biome|ok|terralith:desert_oasis
biome|ok|terralith:fractured_savanna
biome|ok|terralith:red_oasis
biome|ok|terralith:savanna_badlands
biome|ok|terralith:savanna_slopes
biome|ok|terralith:white_cliffs
