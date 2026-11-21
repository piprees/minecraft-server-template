# K7 kit gaps: what the place layout cannot express

## 1. A small heart at the centre (`core` and `at` do not hold a site)

`core` puts the first place's site at the centre and `at` pins a site, but a power diagram's cell need not contain its own site: where the centre place is smaller than a neighbour, the neighbour's solved weight carries its cell over the centre. Measured with the compiled field (K7Drafts, 128-cell grid, seed 20261022):

| Dimension | Centre place (share) | What took the centre |
| --- | --- | --- |
| the_teal_corruption | warped_forest 20% (`core`, then `at` for all four places with the others 1,500+ blocks out) | badlands 50% both times |
| the_emberglass_foundry | columns 20% (`core`; `at` for all four) | blackstone_basin 52% |
| the_gilded_pit | blackstone_basin 20% (`core`; `at` for all four) | nether_wastes 50% |
| the_forged_depths | forge 20% (`at` [0, 0]) | crimson_pinewood 55% |

Every centre place that is also the largest held (27 drafts: `core` or `at` [0, 0]), and the_endless_expanse's dark_forest (8%) held its corner site [1650, -1650]. Convex cells allow a small centre with larger places round it, so this is the solver, not the diagram: the weight solve could keep each pinned site inside its own cell (bound `w_j - w_i < |s_i - s_j|²` for every neighbour j, moving the other sites outwards when it binds). Wanted by: the_teal_corruption ("it came through at the centre and it is still spreading": teal inside, red rock outside, targets `at.centre.biomeRegionPercent` ≥ 10 and `landBiomeRegions.largestPercent` ≥ 50 together), the_gilded_pit (the gilded landing ledge in the pit), the_forged_depths (the forge at spawn in the deep), the_emberglass_foundry (basalt columns at spawn). Not a one-parameter change: the solver needs a site constraint, so nothing was built.

## 2. Places stacked by depth (a vertical meeting)

the_fungal_lanterns: "lit orange from below and teal from above", "crimson (orange) below, warped (teal) above". Places cut x and z only; every height in a column is one place. Climate boxes on `depth` already bind a biome to the floors or the ceilings, so this needs no new kit; it is out of reach of places, and the draft is not a places draft.

## 3. A place over a sky cluster takes its share of the border, not of the islands

the_blighted_maw (a tight cluster round spawn, an empty sky past 480 blocks) and any `radial` sky world: automatic places put most of their area in the empty sky, so a place's share of the land follows how much of the cluster its cell covers. `sectors` (every wedge meets the centre) is the arrangement that gives each place its share of a radial cluster, by construction of the wedges. Not a gap, a recipe; recorded because no draft uses it (the maw is one place).

## 4. Rings round a centre

No description in Batches 2–6 asks for concentric places. the_soulfields ("graves begin some way out and thicken all the way to the edge") is structures by distance, not biomes; the_teal_corruption's spread is gap 1.
