<a id="t269"></a>
### T269 — A biome's own fog and sky replace the dimension's

- **Symptom:** a dimension's `atmosphere.fogColor`/`skyColor` shows only where a biome sets none: on the nether preset (no fog in vanilla's Nether type) or over a library biome with its own fog, frames read the biome's colour (the_obsidian_sanctum over `withered_forest`: mean luma 58.7 against 16.9 wanted).
- **Cause:** the game applies the dimension type's attributes, then the biome's (positional), then the type's timelines in list order (`EnvironmentAttributeSystem`: static layers, then dynamic). A biome attribute is an override.
- **Fix:** `atmosphere.overBiomes: true` writes the colours as `<ns>:atmosphere/<slug>`, a constant `override` track each, first in the type's `timelines`; the day's `multiply` tracks still darken them. Never write per-dimension biome copies for this: a copied biome loses its id's tags, structure biome checks and palettes.

<a id="t270"></a>
### T270 — Above the Nether's bedrock roof is open air

- **Symptom:** on a roofed dimension the survey's vista summit sat at y 128 and pick-shots put the far camera at 140, over the roof; surface landmark cameras stood at y 128 (the_blackstone_keep: 2 of 21).
- **Cause:** the nether-type dimension is 256 high with 128 logical; a column solid from floor to roof has its "surface" at the roof's top, and the air above the bedrock gives it a clear eye.
- **Fix:** the coarse sample keeps each column's roof (`ColumnSamples.roof`); under a ceiling the vista is the highest floor with `Landforms.OPEN_UNDER_ROOF` (16) or more blocks open under its roof, and a surface camera needs a roof over it (an open one first).

<a id="t271"></a>
### T271 — A roofed world's depth is measured from floorHeight, not the floor

- **Symptom:** a `climate.depth` box meant for "under the battlefield" takes the deep floors of chasms and pits first (deep_dark from 0.15: 14.8% of the surface, 3.8% of the caves), or nearly nothing.
- **Cause:** `RoofedTerrain`'s router depth is `(floorHeight − y) / 128`; a chasm floor and the rock under a shallow floor read the same depth.
- **Fix:** `biomeLayout.belowFloor` (biome to blocks, 4–256): points that many blocks under the actual floor read depth from 1.5 up (`RoofedTerrain.BAND`), a range no floor reaches on either preset, so the bound biome holds the underground under every place and none of the open floor. Router values keep `BAND_EDGE` (0.0005) off a band's edge: a value exactly on two touching boxes' shared edge is a nearest-distance tie.
