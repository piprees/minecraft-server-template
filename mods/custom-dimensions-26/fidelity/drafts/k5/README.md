# K5 drafts: spires on shared islands, a drowned hollow, a climate shift, wandering edges

Each draft is its `definitions/` file with the changes below and nothing else; the full set compiles with all four in place. Try them in the order given, one survey each (`/customdim survey <slug>`), and keep what moves the measure named.

## the_violet_spire.json

The terrain becomes `floating_islands` carrying spires, in place of `spires` on islets of their own.

1. **`base` floating_islands, `size` 2.5, `coverage` 0.3, `thickness` 14, `underside` 50, `spread` 10, `flatness` 0.5, `altitude` 120** (broad violet islands). Should move `placeness.islands.count` down and the largest island's share up: a few islands hundreds of blocks across in place of 82 islets of at most 5 hectares.
2. **`spires` 0.08, `spireHeight` 100, `spireThickness` 2, `spireTaper` 0.25** (spires on them). About 8% of the island tops under spires, each footed on its island and rising to y 234 (under the preset's 256). Should move `landforms.sharesPercent.mountains` up from the islands' near zero and keep the sky layer's steep faces in `cliffs` (`materials.biomes` amethyst and purpur are unchanged). A broad island at `size` 2.5 is about 300 blocks across and the spire noise's features about 64 at `spireThickness` 2, so several spires stand on each. If they crowd, lower `spires`; if they read as needles, raise `spireThickness`.
3. **`climateWarp` 96 blocks, `wavelength` 512.** Bends the region edges, which a climate stretched 3 times lays nearly straight; no biome's share changes.
4. **`edgeJitter` 40 and 32 on the two zones** (the ice starfield and the sparse jungle). Their discs' edges wander by about that much; jitter was available and unset.

## the_abyssal_shrine.json

**`hollowDepth` 40, `ringLakes` 0, `gaps` 0.15** on the existing ring (radius 300, falloff 160, strength 0.9, extreme). The ring's hollow, 220 blocks in radius (radius less half the falloff), is one lake 40 deep at its heart with a rim 10 over the water line, and no other lake mass, so the ring's islands and plateaus stand round one drowned centre. The hollow is about 14% of the 512-block border. Should move the basin role's share (the `basin` water, `liquids.basins`) onto one connected body: `placeness` the largest water body near the whole water share, and the drowned share well under the 76% the many-basin layout gave. Then try `ringLakes` 1: the ring's own small basins return round the lake. `gaps` 0.15 cuts a few spillways in the rim; 0 holds it whole.

## overworld.json

**`scales.climate` 3 and `climateShift.temperature` 0.25.** The review needs broad regions (a climate stretched 3 to 4 times), which on this seed spreads the cold to 37% with 47% snow. +0.25 moves every column about one vanilla temperature band warmer. It is a shift, not a clamp: the frozen band keeps only the land whose temperature was under −0.7, so snowy biomes shrink rather than vanish, and the hot band (deserts, badlands, savanna) takes all that lies past its lower edge and grows. Should move the survey's snow cover and frozen biome shares down; check the hot shares do not overshoot, and step the shift by 0.05 either way. A seed with temperate land near spawn is the other route.

## the_aviary.json

**`climateWarp` 192 blocks, `wavelength` 1024.** The straight north–south boundary between the cherry and meadow regions is a cut of the regions' rank space on a climate stretched 4 times; the warp moves where the climate is sampled, so the cut bends by about 192 blocks every kilometre or so. Region shares stay as weighted (`ClimateRegions` ranks the warped climate). Judge it on the biome render; a wavelength near the island size (about 400) breaks single islands between regions, longer bends the boundary across them.
