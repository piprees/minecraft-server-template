# the_tiled_expanse — checkerboard (cellScale 3), sea at -64; place kind: place (a grid round a basalt square)
> "The endermen's first construction — a quartz-and-void expanse laid out on a grid around a vast basalt square, in geometric silence. No cities, no habitation. Pure mathematical architecture, built before they understood what living in a place meant."

- **First:** a vast basalt square at the centre (zone square radius 1050 at 0, 0).
- **Mostly:** a grid of quartz and void cells (checkerboard of 128-block cells). **Rarely:** four quartz squares at 1550 on each axis; End biomes in the grid.
- **Layers:** flat surface; void cells (1.21.1 void_barrens).
- **Atmosphere:** grey-blue sky and fog #8B9BAF, ambientLight 0.25, End effects (kept).
- **Place kind:** one composed place.
- **Baseline wrong (W9b):** grass_block 34.4% of the ground (End biomes on overworld noise take its grass); no void (`surface.voidPercent` 0.1); not flat: reliefMed 34, p95 111, cliffs 4.1, hills 32.4% ("geometric silence"); lava 8.0 in the basalt square; quartz 22.9 and basalt 18.6 are right.
- **v2 responsible:** checkerboard compiles to overworld noise (README § Generators), so the terrain is overworld hills; void_barrens mapped to end_barrens (land).
