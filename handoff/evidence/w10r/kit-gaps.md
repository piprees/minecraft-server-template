# W10r kit gaps

## 1. Per-dimension mob spawns (the_wailing_narrows)
- **Description asks:** "the zombified remnants of the boneyard war were driven here": the owner wants a zombie-haunted hell-hole. The kit can only choose biomes whose own vanilla spawn lists carry zombies (nether_wastes and crimson_forest spawn zombified piglins; soul sand valley spawns skeletons, ghasts, endermen; basalt deltas magma cubes).
- **Kit lacks:** a definition field that adds, weights or removes natural spawns for the dimension (or per listed biome) without choosing biomes for their mobs. `difficulty.mobMultiplier` scales counts only.
- **Generic shape:** `spawns: {add: [{entity, weight, min, max, category}], remove: [entity], biomes: {<id>: {...}}}` compiled to the runtime's per-level spawn list swap (the same mechanism `ores.remove` uses for placed features), with a self-test counting spawns in a fixture.
- **Other users:** the_boneyard (skeletons, ghasts, bats: owner's note), the_molten_flats (imprisoned witches and endermen), the_ember_fields (pacifist piglins), the_buried_age.
