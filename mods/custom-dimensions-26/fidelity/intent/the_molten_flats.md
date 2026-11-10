# the_molten_flats — noise:nether; place kind: place (one lava flat)
> "Industrial lava flats — piglin pipelines and reactors built over the melt. The infrastructure still runs, still pumps, still processes. The workers are gone. The machines don't care."

- **First:** flat magma and ash ground at lava level, pipelines across it (spawnFilter nether_wastes, infernal_dunes, volcanic_deltas).
- **Mostly:** flats of magma, ash and basalt just above a lava sheet. **Rarely:** dunes (infernal_dunes), reactors near the border.
- **Layers:** roof, a flat floor near the lava line.
- **Atmosphere:** vanilla Nether (no 1.21.1 environment).
- **Signature structures:** pipelines spread, nether reactor near border, crimson well near spawn, ruined labs.
- **Place kind:** one place: a flat.
- **Baseline wrong (W9g b1 full):** not flat: `terrain.reliefMedian` 115, `terrain.slopes.flatUnder15Percent` 31.9, cliffPercent 25.4 (the vanilla Nether's walls); ash_barrens placed on 0.1% though listed; magma_land 54.5% is the right ground.
- **v2 responsible:** nether preset, which the kit cannot flatten (`terrain` refused); weights magma_land 55.9, ash_barrens 1.8.
