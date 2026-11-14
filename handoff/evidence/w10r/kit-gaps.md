# W10r kit gaps

## 1. Per-dimension mob spawns (the_wailing_narrows)
- **Description asks:** "the zombified remnants of the boneyard war were driven here": the owner wants a zombie-haunted hell-hole. The kit can only choose biomes whose own vanilla spawn lists carry zombies (nether_wastes and crimson_forest spawn zombified piglins; soul sand valley spawns skeletons, ghasts, endermen; basalt deltas magma cubes).
- **Kit lacks:** a definition field that adds, weights or removes natural spawns for the dimension (or per listed biome) without choosing biomes for their mobs. `difficulty.mobMultiplier` scales counts only.
- **Generic shape:** `spawns: {add: [{entity, weight, min, max, category}], remove: [entity], biomes: {<id>: {...}}}` compiled to the runtime's per-level spawn list swap (the same mechanism `ores.remove` uses for placed features), with a self-test counting spawns in a fixture.
- **Other users:** the_boneyard (skeletons, ghasts, bats: owner's note), the_molten_flats (imprisoned witches and endermen), the_ember_fields (pacifist piglins), the_buried_age.

## 2. Biome expression when a definition removes a biome's features on purpose (the_forged_depths)
- **Description asks:** the owner's ruling for the forged depths: "deader, no trees". `ores.remove` now takes crimson_pinewood's pines and the crimson forest's fungi out, so the two biomes stand as scorched crimson nylium and roots.
- **Kit lacks:** the survey judges crimson_pinewood and crimson_forest by their trees and reports both "placed, not expressed", although their ground (crimson nylium 28.8% of the top blocks, crimson roots 6.1%) is there and the missing trees are the definition's choice.
- **Generic shape:** the survey reads the definition's `ores.remove` and judges a biome whose tree features were removed by its ground alone (the rule-aware ground flag it already uses), saying so in the dossier's expression row.
- **Other users:** any dimension that strips a biome's vegetation for a dead or burnt version of it (the_boneyard, the_buried_age).

## 3. A pinned landing on every seed (the_gilded_pit)
- **Description asks:** "Every portal drops you at the same ledge overlooking what remains"; the owner: "engineered so no matter the seed, it makes sense".
- **Kit has now:** a fixed-centre radial pit with a small warp and the spawn 16 blocks behind the lip (render: the lip at z -484 to -479 on this seed), so the warp's reach decides the distance on other seeds.
- **Kit lacks:** terrain pinned at the spawn: a flat shelf at a set height reaching to a layout's edge whatever the seed. The places kit's `platform` set piece with `seat: pinned` (places/dimensions.md) is the planned answer; a terrain-side `landing` (a disc of the floor held at floorHeight, ending at the layout's lip) would make the ledge part of the ground rather than a structure.
- **Other users:** any arrival that must overlook a feature: the_canvas, the_red_monument.
