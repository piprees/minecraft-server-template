# W10c3 kit gaps

## 1. Overworld biomes on the end preset take End stone
- Asked: the_catalyst_maw's "dark overworld" bleed (dark forest, mangrove, pale garden, old birch), the_fractured_halls' moonlight groves (pale garden, old-growth birch).
- Measured (r1): on the end preset these biomes' top block is end stone (mangrove_swamp 95.0% end stone, dark_forest 50.0%), 0 trees a hectare, so they are placed but not expressed.
- Kit lacks: a default palette for a vanilla overworld biome listed on the end preset, as listed Nether biomes already get (worldgen.md § materials: crimson_forest crimson nylium off the nether preset).
- Generic feature: on the end (and nether) preset, a listed vanilla overworld biome without a definition or library palette takes grass_block over dirt (mangrove_swamp mud, desert sand, the snowy biomes snow) after the definition's palette, so its trees stand.
- Workaround used: `materials.biomes` per biome in the definition.
- Others: any End or Nether preset listing an overworld biome (the_crumbling_reaches stony_peaks, the_catalyst_maw).

## 2. Sky-world biome specks count islands
- Asked: every placeness target over a sky world ("no scatter of biome specks").
- Measured (r1): `placeness.landBiomeRegions.specksPercent` 12-46% on floating_islands and End worlds whose islands are each 78-98% one biome (`placeness.islands.dominantBiomePercent`); islands' median size is 0.2-1.6 ha, so every islet under 1 ha is a "speck" whatever its biome.
- Kit lacks: a speck measure that separates a biome scatter from a scatter of islets.
- Generic feature: `placeness.islands.biomeSpecksPercent` - land in biome regions under 1 ha inside islands of 1 ha or more, as a share of the land of those islands (and the same per place).
- Others: every floating_islands, basin_islands, spires-over-void and End dimension (Batches 1, 3, 4).

## 3. The tree-name heuristic flags sparse-tree biomes (deep_dark, ice_spikes, plains, meadow, snowy_plains)
- Measured (r1, catalyst_maw_a): minecraft:deep_dark "placed, not expressed: features name trees, none on 0.8 ha" while its top blocks are sculk 24.6% and sculk vein 8.1%.
- Cause: vanilla's deep_dark carries `minecraft:trees_plains` (SurveyJob.treeBiomes matches any feature path containing "tree" or "forest").
- Generic fix: a biome whose own surface carries a decoration share above the land median (sculk, here) is expressed by it; or treeBiomes counts only biomes whose tree features place at a measurable count in vanilla's own biome (count > 0.5 a chunk).
- Also measured (r2-r3, 64-75 patches): ice_spikes "features name trees, none on 1.4-1.6 ha" with snow 37.0% and packed ice 17.9% of its tops (the_glacial_drift); meadow none on 0.9-1.1 ha (the_aviary, the_shattered_skies), plains 0.3 ha, snowy_plains 0.3 ha (the_pale_reach). Vanilla places these biomes' trees at a fraction of a tree a chunk, so a hectare or two of sky-island land often holds none; the flag then holds a listed biome under the done bar whatever else it carries.
- Generic fix: TREELESS_COLUMNS scaled by the biome's own tree rate (the summed `count` of its tree features' placement in vanilla's biome), so a sparse-tree biome is judged on enough land to expect three trees; or a biome whose tops differ from the dimension's default (snow, packed ice, sculk) is expressed by them.

## 4. The end preset's empty ring puts spawn over the void
- Asked: the_end_citadel ("an end city on highland ground at spawn"), the_burning_archipelago ("a warped outpost ... on a smouldering island" near spawn), the_fractured_halls (end gate fortress at spawn), the_crumbling_reaches (crashed ship near spawn), the_pillared_void (ruined pillar near spawn).
- Measured (r1-r3): with `generator.endIsland` false the End keeps vanilla's empty ring (about 1,000 blocks round 0, 0) and every one of these spawns (102-512 blocks out) stands over the void on every seed (`placeness.at.spawn.landform` void). A border-wide radial layout fills the ring but replaces vanilla's islands with the terrain's own and joins them into one mass (citadel: largest island 3,378 ha, island dominance 60 -> 47; fractured place dominance 92 -> 64).
- Kit lacks: an `end` terrain parameter that fills the ring with vanilla-like outer islands (the outer-island density continued inward) without a layout.
- Generic feature: `terrain.params.ringFill` 0-1 on `end`: blends `end/islands`' outer-island term over the inner ring, so the island field is continuous from the centre; or a `spawnIsland` that places one island of the dimension's own palette under spawn.
- Others: every End preset with endIsland false (8 of Batch 3's and Batch 4's).

## 5. End peaks stay gentle
- Asked: the_crumbling_reaches "shearing crystal peaks" (`terrain.slopes.cliffPercent` >= 10).
- Measured (r1-r3): `peaks` 0.6-1.0, `peakWidth` 0.02-0.05, `islandHeight` 16-48 give cliffPercent 3.9-6.3 (p95 82-112): the ridge term rises over 0.25 of island density, so it slopes.
- Kit lacks: a sheer term on End island tops.
- Generic feature: `peakSharpness` (power on the ridge term) or `shear` (a stepped offset along one axis, as sheared_highlands' fins do in features), so ridges stand as cliffs.
- Others: the_fractured_halls ("broken crystal ridges"), the_pillared_void if it ever moves back to the end preset.
