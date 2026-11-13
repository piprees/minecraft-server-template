# Kit gaps (W10c1p)

## 1. The survey measures biomes, not places
- **Description asks:** a meeting of a few places (the_aviary, the_violet_spire, the_sunken_temple, overworld), each place a group of biomes mixed inside it.
- **Kit lacks:** `placeness.biomeRegions`, `landBiomeRegions` and `islands.dominantBiomePercent` count a group's members as separate regions, so a place of three biomes reads as three or more regions and an island in one place but two members reads as split. The placeness targets then measure the inner mix, not the places.
- **Generic feature:** when a definition has `biomeLayout.places`, the survey adds `placeness.places`: per place (group or ungrouped regional biome) its share of the border and of the land, its land regions (count of 1%+, largest, specks), the same top-three/major/specks over places, and `islands.dominantPlacePercent`. Stand-in used in this pass: `/tmp/claude-0/w10c1p/tools/placeregions.py` over a `/customdim render` (matches the survey on the_nether: top three 67.1 vs 67.0, 6 regions).
- **Would use it:** every dimension with places and groups (the four above, and any later meeting dimension).

## 2. A ring layout's hollow has a perfectly round rim
- **Description asks:** the_abyssal_shrine's drowned hollow held by a ring of plateaus, read as a natural rim, not a drawn circle.
- **Kit lacks:** the hollow's field is a function of distance from the layout centre only; `gaps` cuts the rim (0.3 leaves it whole at r512/2) and there is no warp on `terrain.layout`.
- **Generic feature:** `terrain.layout.warp` (`blocks`, `wavelength`), the same warp noises `climateWarp` uses, moving the point the layout shape (and a hollow's field) is sampled at, capped like places' warp so the ring stays one ring.
- **Would use it:** the_abyssal_shrine, any `radial` or `ring` layout (the_end cluster, floating-islands radial worlds), whose edges are circles today.

## 3. The survey samples the player border; places are laid over the generation border (W10c1q)
- **Description asks:** a meeting of places judged where the places are: the_sunken_temple's highlands heart at spawn, the_aviary's five skyland places.
- **Kit lacks:** `PlaceLayout` lays places over the smaller of the player and generation borders (2,048 blocks on the sky worlds), while `/customdim survey` stratifies its patches over the player border (4,096): about three quarters of the patches fall outside the place field, where the outer places run on. Measured: the_sunken_temple's heart holds 49% of the land inside 2,048 (render) and 0.6% of the survey's surface (sky highlands); the_aviary's survey found no forest, birch forest or cloud reef although each covers 2.7–4.7% of the land inside 2,048. Patches are also drawn per slug, so a lazy copy of identical terrain samples other ground (violet: wisteria+lavender 0.49 vs 0.37 of land; aviary: plateaus+plains 0.33 vs 0.27).
- **Generic feature:** the survey stratifies over the place field's radius when the definition has places (or always over the smaller border, as the places do), and reports `placeness.places` (kit gap 1) from the same patches; patch positions drawn from the dimension's seed, not its slug, so variants of one terrain sample the same ground.
- **Would use it:** every dimension whose generation border is smaller than its player border (all the sky worlds of Batch 1) and every places dimension.
