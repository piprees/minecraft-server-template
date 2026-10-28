# The roller

Zones guarantee the layout ([worldgen.md](worldgen.md#zones)); rolling picks the seed that makes everything else good. The roller samples our actual noise and biome source for each candidate seed without generating chunks, scores it against the definition's wants, and renders the best to choose from. Work packages W6 and W6b ([work-plan.md](work-plan.md)).

## Commands

| Command | What it does |
| --- | --- |
| `/customdim render <dimension> [seed] [radius] [step] [centreX centreZ]` | Draws top-down maps, round 0,0 or the given centre, of surface biome and height (zones and structure starts join as layers) as PNGs with a JSON sidecar under `renders/<dimension>/` in the game directory, from the same sampler as the roller, off the server thread. Needs no chunks generated; a 256-pixel overworld render (radius 1024, step 8) takes about 20 s on two threads here, a 1024-pixel one about six minutes (the Nether's, with a 128-block column, about 40 s). It is what you look at when tuning a definition |
| `/customdim roll <dimension> [count]` | Scores `count` seeds (default 100, at most 500) in the background, keeps the best ten, renders each (biome, height, structure starts) beside its scorecard JSON, and writes the contact sheet `renders/<dimension>/index.html` and the ranking `roll.json` |
| `/customdim pick <dimension> <seed>`, `/customdim pick <dimension> rank <n>` | Writes the seed, or the last roll's rank `n`, into the definition file; it applies after the next restart |

Both are refused for a dimension whose region folder holds chunks, since worldgen is creation-time ([D2](../../../TROUBLESHOOTING.md#d2)): the reply names the folder and says to delete it with the server stopped to reset the dimension. The default count is the performance gate's 100, which fits in ten minutes; the cap bounds a roll at under an hour.

## The sampler

For a candidate seed, the roller builds the real generator state — the noise router, the biome source and the structure placement state (`ChunkGenerator.createState`, so the firewall's filter applies, with the dimension's structure control applied as the level applies it, recorded nowhere) — on a worker thread, without a level and without generating chunks. It samples:

- a disc of 128 columns a side round the definition's spawn, covering the player border up to 2,048 blocks: biome shares and variety, ground, surface water, the 2nd to 98th percentile and interquartile range of ground heights, and zones;
- a spawn patch of radius 128 at an 8-block step: the spawn biome, the share of dry columns with no step over 3 blocks to a neighbour, and the biome edge density;
- structure starts of the wanted, shunned and gate structures out to the player border, up to 8,192 blocks: each chunk the set's placement picks, the structure chosen as `ChunkGenerator.createStructures` chooses it (weighted, seeded by the chunk, the next pick tried when one does not start), and confirmed as generation confirms it: `Structure.generate` with the structure's own generation point, biome and terrain checks, the control's biome bypass for forced and wanted sites, and the zones' shuns. A scored start is one the level records. Scoring reads the nearest start of each structure (and counts end cities), so the roller confirms sites nearest first and stops at one of each, 64 end cities.

Scoring needs the disc to reach the player border for biome and terrain shares (it stops at 2,048 blocks, so a larger border is scored on its inner disc), and structure starts to reach the border, since `near_border` wants sit at 55–100 % of it.

## Scoring

`Scorer` runs every criterion over the seed's `RollFacts` against the definition's `roll` field. A failed gate rejects the seed; a criterion that does not apply or could not be measured is left out of both score and ceiling; the headline is the mean of the configured and general tiers. The contact sheet ranks scored seeds before rejected ones, then by headline.

| Criterion | Reads | Asked when |
| --- | --- | --- |
| `spawn_reads_as_namesake` | spawn biome and the named biomes' shares | `spawnFilter` |
| `headline_biome_dominates_appropriately` | largest biome share, best between 30 and 55 % | more than one biome declared |
| `biome_variety_present` | distinct biomes against the declared count | more than one biome declared |
| `biome_edges_near_spawn` | edge density in the spawn patch, full marks at 0.10 | always |
| `terrain_matches_preset` | relief for `flat` to `extreme`, ground share for `islands` and `void` | `terrain` |
| `water_matches_intent` | surface water share | `water` |
| `height_range_matches_intent` | share of ground heights inside the envelope | `heightRange` |
| `playable_ground_covers_the_disc` | ground share | `terrain` is not `islands` or `void` |
| `fortress_reachable_in_nether` (gate) | a fortress within half the border | `the_nether` |
| `end_city_reachable_in_end` (gate) | an end city inside the border | `the_end` |
| `end_supplies_enough_elytra` | end city starts, one ship per three | `the_end` |
| `spawn_is_playable` | safe share of the spawn patch, full marks at 25 % | `allowHazardousSpawn` is not true |
| `wants:<name>` | the nearest start against the band (`near_spawn` 0–15 %, `spread` 10–75 %, `near_border` 55–100 % of the border) | each want |
| `shuns:<name>` | the nearest start's distance as a share of the border; none scores full | each name in `roll.shuns` or `structures.shuns` |
| `zone_reads_as_intended:<id>` | share of the zone's columns carrying its biomes | each zone that sets biomes |

`mood` is read and not scored. Zones are read through the `ZoneMap` interface; `DefinitionZones` maps circle zones with biomes (other shapes are listed on the sheet as not scored).

Not carried over, because they measured facts the sampler does not produce:

- `structures_form_places_not_noise` scored the Clark–Evans spacing of the 1.21.1 noise structure groups (`NoiseGroupPlan`), which placement in 26.3 does not use;
- `first_encounter_distance` needed the 1.21.1 catalogue's hostile classification of structures, which is not ported.

## Contact sheet

`renders/<dimension>/index.html` shows the best ten renders with their scores and scorecards, and opens from disk.

## Gates

- **Agreement:** for the overworld, the Nether and The Aviary, the sampler's biome at 50 random points matches a generated world exactly, its noise columns equal the level generator's, and its height matches wherever no carver, feature or structure changed the column; every height mismatch falls in a class the self-test names (`removed`, `flooded`, `added`, `filled`, `structure`). The structure starts the roller scores within 384 blocks of 0,0 in the overworld and the Nether are exactly the starts those chunks record: none scored that generation drops, none recorded that the roller misses. `RollSelfTest` checks both on every self-test run.
- **Render accuracy:** a render matches the same area walked in-game, zone edges included.
- **Performance:** 100 seeds score in under ten minutes on the server, in the background, without dropping TPS below 19.
