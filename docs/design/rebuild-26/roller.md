# The roller

Zones guarantee the layout ([worldgen.md](worldgen.md#zones)); rolling picks the seed that makes everything else good. The roller samples our actual noise and biome source for each candidate seed without generating chunks, scores it against the definition's wants, and renders the best to choose from. Work packages W6 and W6b ([work-plan.md](work-plan.md)).

## Commands

| Command | What it does |
| --- | --- |
| `/customdim render <dimension> [seed] [radius]` | Draws a top-down map of biomes, height, zones and structure starts as a PNG under `data/renders/`, from the same sampler as the roller. Needs no chunks generated and takes seconds; it is what you look at when tuning a definition |
| `/customdim roll <dimension> [count]` | Scores that many seeds in the background, keeps the best ten, renders each and writes a contact sheet |
| `/customdim pick` | Writes the chosen seed into the definition |

A dimension is only rolled before it is first created, since worldgen is creation-time ([D2](../../../TROUBLESHOOTING.md#d2)).

## The sampler

For a candidate seed, the sampler builds the real generator state — the noise router, our biome source and the structure placement state — on a worker thread, without a level and without generating chunks. It samples a coarse grid out to a radius: surface height, biome, zone, water, and which structure placements would start where.

## Scoring

Scoring carries over from the 1.21.1 mod into core, scored against the definition's wants:

- biome coverage near spawn;
- spawn on dry, walkable ground;
- how much of each zone reads as intended;
- structures wanted within a distance;
- water and height ranges;
- anything the definition shuns.

The definition's `seedRoll` field is reshaped around what the new roller scores (F3).

## Contact sheet

`data/renders/<dimension>/index.html` shows the best ten renders and opens from disk.

## Gates

- **Agreement:** for three reference definitions, the sampler's biome and height at 50 random points match a generated world exactly. Rolls are blocked until it matches.
- **Render accuracy:** a render matches the same area walked in-game, zone edges included.
- **Performance:** 100 seeds score in under ten minutes on the server, in the background, without dropping TPS below 19.
