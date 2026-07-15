# dim-pastoral.profile — peaceful/pastoral dimensions (the_blossom_gardens,
# the_greenreach, the_chalk_meadows...): villages near, gentle rolling
# terrain, some water but not ocean-locked.
option|reject_bad_spawn|false
option|grid|3|512
locate|village|self|#minecraft:village
metric|structure_village_dist|near|30|1500
metric|terrain_grain|low|30|5|15
metric|terrain_relief|window|20|10|60|50
metric|water_fraction|window|20|0.02|0.3|0.3
