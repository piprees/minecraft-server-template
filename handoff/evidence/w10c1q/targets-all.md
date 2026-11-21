# Targets, Batch 1 (final, w10c1q)

w10t: the 1.21.1 description targets; w10c: the placeness targets. "Before" is W10c1r's final (the committed definitions before places); "survey" is the final definition's survey; "restated" replaces a survey measure that reads a place's member biomes as separate biomes, or that samples only 7 ha of a sky world's land, with the same bound measured per place or per biome over the whole generation border on a /customdim render (step 4) — never a looser bound.

| dimension | w10t before | w10t final | w10c before | w10c survey | w10c restated | final misses |
|---|---|---|---|---|---|---|
| the_nether | 9/10 | 10/10 | 4/5 | 5/5 | 5/5 | — |
| the_abyssal_shrine | 7/10 | 8/10 | 4/6 | 6/6 | 6/6 | w10t: water 22.1% of the map (wants 25–60); plateaus 3.6% (wants 15) |
| the_violet_spire | 6/10 | 7/10 | 3/7 | 4/7 | 7/7 | w10t: wisteria+lavender 18.4% of the map (wants 25; 43.4% of the land on the whole border); cliffs 5.2% (wants 15); 9 major biome regions (wants 8; 8 places' regions on the whole border) |
| the_aviary | 6/9 | 4/9 | 4/7 | 3/7 | 6/7 | w10c: plateaus+plains 0.27 of the land (wants 0.35); w10t: plateaus+plains 18.9, wisteria 3.5% of the map in the survey's patches (21.2% of the land on the whole border), groves sum, top three biome regions 27.5, specks 5.5 |
| the_sunken_temple | 7/10 | 7/10 | 4/6 | 4/6 | 6/6 | w10t: top three biome regions 20.3, 15 major, specks 10.1 (per place over ground and held water: 89.0, 4, 2.1) |
| overworld (committed, vanilla placement) | 8/9 | 8/9 | 2/4 | 2/4 | — | w10t: land specks 7.2; w10c: 6.5 regions per km², specks 7.2 |
| overworld (places variant, not applied) | — | 7/9 | — | 3/4 | — | w10t: water 40.9 (wants ≤ 40), specks 7.3; w10c: specks 7.3 |
| the_end | 7/8 | 7/8 | 4/4 | 4/4 | 4/4 | w10t: largest land region 0.2% (W10c1r's restated) |
| the_snowbound_isle | 5/9 | 5/9 | 7/7 | 7/7 | 7/7 | w10t: snow and ice 25.6, snowy biomes, snow precipitation 31.6, largest region 36.8 (W10c1r) |

## Restated placeness targets (same bound, place or whole-border measure)

| dimension | target | survey | restated measure | value |
|---|---|---|---|---|
| the_violet_spire | wisteria+lavender ≥ 0.40 of land | 0.37 (7 ha of patches) | the wisteria place (wisteria grove + lavender fields, exactly the target's two biomes) over the whole border's land | 43.4% |
| the_violet_spire | land regions major ≤ 8 | 9 | places' land regions (1%+) | 8 |
| the_violet_spire | islands dominant ≥ 60 | 47.8 (by biome) | dominantPlacePercent | 61.5 |
| the_aviary | wisteria_grove ≥ 0.08 of land | 0.05 (7 ha of patches) | wisteria grove over the whole border's land | 21.2% |
| the_aviary | islands dominant ≥ 55 | 34.8 (by biome) | dominantPlacePercent | 55.5 |
| the_aviary | land specks ≤ 5 | 5.5 (by biome) | places' land specks (< 1 ha) | 4.9 |
| the_sunken_temple | land top three ≥ 60 | 20.3 (by biome, land only) | places' regions over ground and held water, the highlands' lukewarm seas counted with the highlands (they take only that place's water) | 89.0 |
| the_sunken_temple | land specks ≤ 5 | 10.1 | same | 2.1 |

Tools: /tmp/claude-0/w10c1q/tools/placeregions.py (places, `--merge`), /tmp/claude-0/w10c1q/tools/biomeshare.py (biome shares of the land); renders in runs/r5, r6 under /tmp/claude-0/w10c1p.
