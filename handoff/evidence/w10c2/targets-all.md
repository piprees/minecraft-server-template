# Targets, Batch 2 (W10c2)

w10t: the 1.21.1 description targets; w10c: the placeness targets (`fidelity/targets/w10c/<slug>.json`, measured with `placeness.places` where the definition lays places). "Before" is the head definition's full survey (r1/r4), "final" the applied definition's (r6, seeded survey patches after K9).

| dimension | w10t before | w10t final | w10c final | fidelity | final misses |
|---|---|---|---|---|---|
| the_blackstone_keep | 2/9 | 9/9 | 6/6 | 4 | — |
| the_bloodroot_wastes | 2/7 | 7/7 | 6/6 | 4 | — |
| the_obsidian_sanctum | 4/7 | 7/7 | 6/6 | 3 | Very dark as a player sees it: the frames are pale (a named exception, waiting on the kit). |
| the_furnace_halls | 2/8 | 8/8 | 6/6 | 4 | Nether brick on the ground is a fortress's, so the description set's brick share of the sampled ground is restated as fortresses inside the border (10, wants at least 8). The nearest fortress stands 329 blocks from spawn; "fortress corridors from spawn" waits on the seed. |
| the_buried_age | 3/7 | 5/7 | 1/3 | 4 | Lava cover in the survey's sample is 58% (wants 60 to 75); across the whole border it is 71%.; Biome specks are 14.6% (wants at most 10): seven small biomes laid on noise over islands.; The highest land reaches 106 (wants at most 100) where the floor meets the roof.; Four of seven biomes are mostly under lava (a named exception, waiting on the kit). |
| the_boneyard | 1/10 | 8/10 | 3/3 | 3 | Deep dark is 1.3% of the surface (wants at most 1) and 0.1% of the caves (wants at least 5).; Basalt deltas (4% of the border on the render) was not found in the survey's sample, so its ground is not judged.; The haze is teal, not bone-pale. |
| the_ember_fields | 3/10 | 9/10 | 4/4 | 4 | Patch relief is 80 blocks (wants at most 60): the measure counts the columns where the floor meets the low roof (the highest 5% at 114); the floor itself has a median of 43 and 5% below 37.; The sulphur caves and the toxic undergrowth are too small in the sample to judge (46 and 32 columns). |
| the_wailing_narrows | 4/9 | 9/9 | 4/4 | 4 | Restated: steep slopes are 39% (wanted 55): the mesa tops and chasm floors are flat and only the walls are steep, so the walls are measured as the drop between the mesa tops (a quarter of the floor at 81 or higher) and the chasm floors (a quarter at 22 or lower).; Restated: the survey's basin share (12.5%, wanted 35) reads a cut floor wider than about 128 blocks as plains; a quarter of the floor or more lies in the chasms, 50 blocks under the mesas (the survey reports quartiles, so 35% itself is not measured). |
| the_molten_flats | 4/9 | 9/9 | 4/4 | 4 | Restated: the survey's patch relief reads 101 because every patch holds a few columns read in pockets of the roof mass (up to 119); the flat is judged by the floor's 95th percentile, 44 (wanted at most 50). |
| the_gilded_pit | 1/9 | 9/9 | 4/4 | 4 | Restated: the survey's basin share (10.8%, wanted 25) reads a pit floor wider than about 128 blocks as plains; a quarter of the floor or more lies in the pit, 40 blocks or more under the ledge. |
| the_forged_depths | 4/8 | 8/8 | 4/4 | 4 | Restated: the survey's basin share (4.3%, wanted 20) reads a deep floor wider than about 128 blocks as plains; a quarter of the floor or more lies in the deep, 20 blocks or more under the rim. |
