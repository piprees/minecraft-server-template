# dim-hard-overworld.profile — hard overworld-flavoured dimensions
# (the_underdark, the_crucible, the_gauntlet): dungeons close, dramatic
# terrain. No spawn-biome taste, no early reject (dimension rolls measure
# every candidate). Format documented in classic.profile.
option|reject_bad_spawn|false
option|grid|3|512
locate|ancient_city|self|minecraft:ancient_city
locate|trial_chambers|self|minecraft:trial_chambers
metric|structure_ancient_city_dist|near|20|2000
metric|structure_trial_chambers_dist|near|15|1500
metric|terrain_relief|high|30|30|120
metric|terrain_grain|high|20|4|16
metric|water_fraction|window|15|0|0.3|0.3
