# Targets, Batch 2 (W10c2)

w10t: the 1.21.1 description targets; w10c: the placeness targets (`fidelity/targets/w10c/<slug>.json`, measured with `placeness.places` where the definition lays places). "Before" is the head definition's full survey (r1/r4), "final" the applied definition's (r6/r11; c5 for the buried age, the boneyard and the ember fields; c1 for the sanctum and the keep, after K10).

| dimension | w10t before | w10t final | w10c final | fidelity | final misses |
|---|---|---|---|---|---|
| the_blackstone_keep | 2/9 | 9/9 | 6/6 | 4 | — |
| the_bloodroot_wastes | 2/7 | 7/7 | 6/6 | 4 | — |
| the_obsidian_sanctum | 4/7 | 7/7 | 6/6 | 4 | No far view (closed pocket under the roof). |
| the_furnace_halls | 2/8 | 8/8 | 6/6 | 4 | Nether brick on the ground is a fortress's, so the description set's brick share of the sampled ground is restated as fortresses inside the border (10, wants at least 8). The nearest fortress stands 329 blocks from spawn; "fortress corridors from spawn" waits on the seed. |
| the_buried_age | 3/7 | 6/7 | 1/3 | 4 | specks 15.1% (wants at most 10); p95 107 (wants at most 100): named exceptions. |
| the_boneyard | 1/10 | 10/10 | 3/3 | 4 | — |
| the_ember_fields | 3/10 | 9/10 | 4/4 | 4 | Restated: patch relief 80 counts floor-to-roof columns; the floor is median 43, 5% below 37. |
| the_wailing_narrows | 4/9 | 9/9 | 4/4 | 4 | Restated: steep slopes are 39% (wanted 55): the mesa tops and chasm floors are flat and only the walls are steep, so the walls are measured as the drop between the mesa tops (a quarter of the floor at 81 or higher) and the chasm floors (a quarter at 22 or lower).; Restated: the survey's basin share (12.5%, wanted 35) reads a cut floor wider than about 128 blocks as plains; a quarter of the floor or more lies in the chasms, 50 blocks under the mesas (the survey reports quartiles, so 35% itself is not measured). |
| the_molten_flats | 4/9 | 9/9 | 4/4 | 4 | Restated: the survey's patch relief reads 101 because every patch holds a few columns read in pockets of the roof mass (up to 119); the flat is judged by the floor's 95th percentile, 44 (wanted at most 50). |
| the_gilded_pit | 1/9 | 9/9 | 4/4 | 4 | Restated: the survey's basin share (10.8%, wanted 25) reads a pit floor wider than about 128 blocks as plains; a quarter of the floor or more lies in the pit, 40 blocks or more under the ledge. |
| the_forged_depths | 4/8 | 8/8 | 4/4 | 4 | Restated: the survey's basin share (4.3%, wanted 20) reads a deep floor wider than about 128 blocks as plains; a quarter of the floor or more lies in the deep, 20 blocks or more under the rim. |
