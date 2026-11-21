# the_violet_spire: targets

Measured on the survey of the current definition (dossier keys). "from" is the phrase of the description or the note each number comes from.

| set | key | test | measured | passes | from |
|---|---|---|---|---|---|
| description | `surface.biomesPercent.adventure:wisteria_grove + surface.biomesPercent.adventure:lavender_fields` | >= 25 | 23.9 | no | "Wisteria islands"; 1.21.1 spawnFilter lavender_fields |
| description | `surface.snowIcePercent` | <= 8 | 0.0 | yes | "Violet spires ... wisteria ... amethyst": not a snowfield |
| description | `surface.topBlocksPercent.minecraft:amethyst_block + surface.topBlocksPercent.minecraft:amethyst_cluster + surface.topBlocksPercent.minecraft:budding_amethyst + surface.topBlocksPercent.minecraft:purpur_block + surface.topBlocksPercent.minecraft:purple_terracotta + surface.topBlocksPercent.minecraft:magenta_terracotta` | >= 2 | 8.9 | yes | "amethyst canyons", "endermen architecture": violet blocks on the surface |
| description | `terrain.slopes.cliffPercent` | >= 15 | 3.6 | no | "Violet spires", "canyons": vertical faces |
| description | `terrain.surfaceHeight.p95` | >= 200 | 236.0 | yes | "spires in the sky": tall masses above the plateaus |
| description | `surface.voidPercent` | between [15, 60] | 52.7 | yes | "in the sky": skylands with open sky between |
| description | `atmosphere.definitionAtmosphere.ambientLight?` | between [0.15, 0.4] | 0.25 | yes | "Arcane but not hostile"; 1.21.1 ambientLight 0.25 |
| description | `placeness.places.landRegions.top3Percent` | >= 60 | 79.4 | yes | place kind meeting ("two worlds overlapped"): the largest three regions hold most of the land; measured by place (the wisteria, skylands and End-ward bands), since one place holds several biomes |
| description | `placeness.places.landRegions.major` | <= 8 | 7.0 | yes | place kind meeting: a few places, not many; measured by place (the wisteria, skylands and End-ward bands), since one place holds several biomes |
| description | `placeness.places.landRegions.specksPercent` | <= 5 | 0.8 | yes | place kind meeting: no scatter of biome specks; measured by place (the wisteria, skylands and End-ward bands), since one place holds several biomes |
| placeness | `surface.biomesPercent.adventure:wisteria_grove + surface.biomesPercent.adventure:lavender_fields` | >= 0.4 | 0.51 | yes | "Wisteria islands" and lavender at spawn: the violet half, over land columns |
| placeness | `terrain.surfaceHeight.p95` | >= 200 | 236.0 | yes | "Violet spires in the sky" |
| placeness | `surface.voidPercent` | between [15, 60] | 52.7 | yes | sky islands with sky between |
| placeness | `placeness.places.landRegions.top3Percent` | >= 60 | 79.4 | yes | place kind meeting of two worlds; measured by place (the wisteria, skylands and End-ward bands), since one place holds several biomes |
| placeness | `placeness.places.landRegions.major` | <= 8 | 7.0 | yes | place kind meeting; measured by place (the wisteria, skylands and End-ward bands), since one place holds several biomes |
| placeness | `placeness.places.landRegions.specksPercent` | <= 5 | 0.8 | yes | place kind meeting; measured by place (the wisteria, skylands and End-ward bands), since one place holds several biomes |
| placeness | `placeness.places.islands.dominantPlacePercent` | >= 60 | 65.5 | yes | each island one of the two worlds; measured by place (the wisteria, skylands and End-ward bands), since one place holds several biomes |
