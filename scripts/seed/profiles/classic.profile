# classic.profile — the original seed-roller taste, expressed as a profile.
#
# FORMAT (flat pipe-separated lines; parseable on macOS bash 3.2 with
# `while IFS='|' read` and by scripts/seed/score-report.py):
#
#   option|<key>|<value>
#       reject_bad_spawn|true   skip a world seed early when the spawn biome
#                               is in neither biome list (world rolls only)
#       grid|off                no terrain grid
#       grid|<n>|<pitch>        n x n height/water grid, <pitch> blocks apart
#   locate|<name>|<where>|<structure-or-#tag>
#       <where>: overworld | nether | end | self  (self = the rolled dimension)
#       measured as structure_<name>_dist (+ _x/_z rows); miss stored as -1
#   metric|<name>|<direction>|<weight>|<params...>
#       near|<cap>              closer is better, zero at cap, miss = 0
#       far|<cap>               farther is better, full marks at cap or miss
#       window|<lo>|<hi>|<fall> full inside [lo,hi], linear falloff over <fall>
#       low|<good>|<zero>       full at <= good, zero at >= zero
#       high|<zero>|<good>      full at >= good, zero at <= zero
#       tier                    spawn_biome: green = full, ok = half, else 0
#       proximity|<cap>|<a>|<b> manhattan distance between locates a and b
#   biome|green|<id>  /  biome|ok|<id>
#       spawn-biome tiers; also the early-reject allowlist for world rolls
#
# Weights must sum to 100.

option|reject_bad_spawn|true
option|grid|off

locate|stronghold|overworld|betterstrongholds:stronghold
locate|village|overworld|#minecraft:village
locate|portal|overworld|minecraft:ruined_portal
locate|fortress|nether|betterfortresses:fortress
locate|bastion|nether|minecraft:bastion_remnant

metric|structure_stronghold_dist|near|25|2000
metric|structure_village_dist|near|20|2000
metric|structure_fortress_dist|near|15|1500
metric|structure_bastion_dist|near|15|1500
metric|fort_bast_proximity|proximity|15|1000|fortress|bastion
metric|spawn_biome|tier|10

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
