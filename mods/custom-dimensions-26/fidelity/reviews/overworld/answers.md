# overworld — review answers

Evidence: the facts table in `review.md` (O/V/M/S rows, from `/tmp/claude-0/w10c1/reviews/overworld` and the W9c final survey), the shots' frame census. Every statement cites a row or a dossier key.

1. **Do the shots show the dominant character?** Yes for ordinary land: far shows forested land, a lake and a mountain; surface-t3 a forest, t2 snowy taiga, t1 open ocean, t7 a mangrove swamp. Plains with a village in sight is not among them: plains is 7.8% of columns (V4).

2. **Unsupported claims.** The shot captions here are the census captions, built from each frame's rays, so they carry no claim the census does not. No caption claims more than the census.

3. **1.21.1 elements absent from the dossier.** The 1.21.1 definition lists no biomes (a base world), so there is no list to compare (S:a, S:b n/a). Placed v2 biomes no 1.21.1 biome maps to: V1 snowy_taiga 15.8%, V2 ocean 13.9%, V3 forest 12.7%, V4 plains 7.8%, V5 deep_ocean 6.5%, V6 ice_spikes 6.5%.

4. **Measured elements that contradict the intent.** (Literal checks: a description word that names another place still fires.) V6 ice_spikes (6.5% of the surface) is flagged features name trees, none on 0.5 ha of land; V9 beach (5.6% of the surface) is flagged mostly water.

5. **Fidelity: 3/5** — Ordinary overworld land, forests and seas on its own seed (V1-V19 expressed; M:water 36.6%), but cold: snow or ice on 28.2% (M:snow) with snowy_taiga the largest biome (V1 15.8%), and the regions are a scatter (`placeness.landBiomeRegions.top3Percent` 10.2, `specksPercent` 8.2). Named exception: broader regions need `terrain.scales.climate` 3-4, which on this seed spreads the cold (snow 36.9% and 47.1% measured); a seed chosen for temperate land near spawn (the seed package) or a per-dimension temperature shift would close it.
