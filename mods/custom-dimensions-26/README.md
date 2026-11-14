# custom-dimensions for Minecraft 26.3

The rebuild of `mods/custom-dimensions` on Minecraft 26.3. It stays out of the
release bundle until the `v6.0.0` switch; the plan of record is
[`docs/design/rebuild-26/`](../../docs/design/rebuild-26/README.md).

| Module | What it holds | Minecraft? |
| --- | --- | --- |
| `core` | The definition model and its maths (zones, …) | No |
| `compiler` | Definitions to datapack JSON, using vanilla function types | No |
| `server` | The Fabric mod: runtime levels, portals, rules, the companion feed, the self-test | Yes |

`core` and `compiler` ship nested inside the mod jar (`META-INF/jars/`).

## Build and test

Java 25 (`mise.toml`). From this directory:

```bash
./gradlew build                                   # all modules, unit tests included
./gradlew :server:runServer -PselfTest=/tmp/st.txt  # boot 26.3, run the self-test, stop
```

`server/run/` needs `eula.txt` (`eula=true`) on first use; its
`server.properties` wants `online-mode=false` and `max-tick-time=-1`. The self-test writes
one line per check to the report; a line starting `FAIL` is a failure. It activates and
unloads every lazy dimension in turn, so it runs for a few minutes; it sets
`pause-when-empty-seconds` to 0 while it runs, because an empty server stops ticking.

With `-PselfTest`, `runServer` reads a copy of `definitions/` with the fixture
dimensions in `server/src/selftest/definitions/` added (a void death, a kept
inventory, every win trigger, dry and flooded aquifers, a palette, an ore set, structure control and five structure densities), and each library terrain's `*_default.json` from
`server/src/selftest/gallery/`. The fixtures are never in the release jar.
`-PselfTestOnly=exits` runs only the compiled-pack and exit sections; `-PselfTestOnly=commands` the compiled-pack and command sections; `-PselfTestOnly=materials` the compiled-pack and materials sections; `-PselfTestOnly=biomes` the compiled-pack and biome library sections; `-PselfTestOnly=render` the compiled-pack and render sections; `-PselfTestOnly=firewall` the compiled-pack and firewall sections; `-PselfTestOnly=terrain` the compiled-pack and terrain sections; `-PselfTestOnly=zones` the compiled-pack and zone sections; `-PselfTestOnly=structures` the compiled-pack and structure sections; `-PselfTestOnly=roll` the compiled-pack and roll sections; `-PselfTestOnly=rollperf` those and the 100-seed roll timing, which no other run includes; `generators`, `difficulty`, `survey`, `seeds` and `gamerules` likewise run the compiled-pack section and that one. `-PselfTestOnly=lod` runs the compiled-pack section and the LOD section, which needs Distant Horizons on the server (`-Pdh`; [Distant Horizons LODs](#distant-horizons-lods)); a run without DH writes no LOD lines. `-PselfTestOnly=mixins` runs the compiled-pack and firewall sections and the mixin lines: one per entry in `customdimensions.mixins.json` whose effect shows without generating terrain, each naming its mixin. Every run carries them and the seed lines (`LevelSeedSelfTest`), which check the six seed, game-rule and weather mixins are merged, and the runtime-registry line (`RuntimeRegistry`); the structures and zones sections name the other four (`StructureBiomeBypassMixin`, `BeardifierAdaptationMixin`, `NoiseBasedAquiferMixin`, `DryFluidFeatureMixin`).

`runServer` also loads Open Parties and Claims and its Forge Config API Port
dependency from the Modrinth maven (`localRuntime`, versions in
`gradle.properties`), so the aura claim veto is exercised; neither is a compile
dependency or nested in the jar. `-PfirewallProbe` adds The Lost Cities, a 26.3
Fabric mod whose `lostcities:lostcity` dimension the firewall refuses.

The persistence check takes two boots of the same world:

```bash
./gradlew :server:runServer -PselfTest=/tmp/st.txt -PselfTestPersist=write   # places a marker, records 8 surface heights, lights a portal, sets its aura budgets
./gradlew :server:runServer -PselfTest=/tmp/st.txt -PselfTestPersist=verify  # reopens the level, compares them, finds the portal, its link and its aura budgets
``` CI runs
both (`.github/workflows/mod26.yml`), and a weekly canary builds against the
newest snapshot (`.github/workflows/mod26-canary.yml`).

## The datapack compiler

`compiler` turns `definitions/` into an ordinary datapack. Vanilla's files are
inputs, never committed: `:compiler:vanillaData` downloads the server jar for
`minecraft_version`, checks its sha1 against Mojang's manifest, and extracts
`data/minecraft/**` and the data generator's reports into
`compiler/build/vanilla/<version>/`.

```bash
./gradlew :compiler:compileDatapack           # build/datapack/customdimensions/ + compile-report.md
./gradlew :compiler:applyIdMapping            # definitions/id-mapping.csv applied into compiler/build/id-mapping/ (-PwriteDefinitions=true: in place)
scripts/vanilla-boot-check.sh                 # boot that pack on vanilla, no mods
./gradlew :compiler:test -PupdateGolden=true  # rewrite the golden files after an intended change
```

| Output | Where |
| --- | --- |
| Dimension types | `data/adventure/dimension_type/<slug>.json` |
| Noise settings (liquids, default block, palette, veins, `endIsland: false` or a terrain) | `data/adventure/worldgen/noise_settings/<slug>.json` |
| A definition's terrain from the library (`terrain.base`): height, offset, factor, jaggedness, depth, sloped cheese, final density, surface levels, the stretched climate | `data/adventure/worldgen/density_function/terrain/<slug>/*.json` |
| The terrain library's own noises | `data/adventure/worldgen/noise/terrain/<noise>.json` |
| Material rules (palettes, veins) | `data/adventure/worldgen/material_rule/<slug>.json` |
| Added ores | `data/adventure/worldgen/{feature,placed_feature}/ores/<slug>/<id>.json` |
| The biome library, every biome in every compile ([below](#biome-library)) | `data/adventure/worldgen/biome/<id>.json`, `data/adventure/worldgen/{feature,placed_feature}/biome/<id>/<name>.json` |
| What the runtime applies to a level's generator (lava level, added ores, removed features) | `data/customdimensions/worldgen_runtime/<slug>.json` |
| Structure control, every dimension ([below](#structure-control)) | `data/customdimensions/structure_control/<slug>.json` |
| The End's terrain without its central island | `data/adventure/worldgen/density_function/end_without_central_island/{islands,sloped_cheese}.json` |
| Fixed time of day | `data/adventure/timeline/fixed_time/<timeline>_<tick>.json` |
| The dimension's sky and fog over its biomes' (`atmosphere.overBiomes`): one constant `override` track per colour, first in the dimension type's `timelines`, so it lands after every biome's own and before the day's darkening | `data/adventure/timeline/atmosphere/<slug>.json` |
| Day length (`atmosphere.dayLength` or the settings' default), shared by every dimension on one length: its clock, made the dimension type's `default_clock`, and each whole-day timeline warped to it | `data/adventure/world_clock/day_length/<day>_<night>.json`, `data/adventure/timeline/day_length/<timeline>_<day>_<night>.json` (ticks) |
| Base worlds, loaded at start | `data/minecraft/dimension/{overworld,the_nether,the_end}.json`, `data/adventure/dimension/the_aviary.json` |
| Every other dimension, opened lazily by the runtime | `data/customdimensions/lazy_dimension/<slug>.json` |
| The compiled form of a file vanilla reads that names a type of ours, bound by the runtime at start in place of its vanilla form ([worldgen.md](../../docs/design/rebuild-26/worldgen.md#types-of-ours-and-vanilla-forms)) | `data/customdimensions/runtime_registry/<ns>/<registry>/<path>.json` |

Per-release format differences live in `VersionTable`; the self-test decodes
every emitted file with the game's own codecs.

### Biome library

Our own biomes, built from vanilla 26.3 blocks and feature types, one file per
biome in `compiler/src/main/resources/customdimensions/biomes/<id>.json`
(format: `schema/biome.schema.json`; rules: [worldgen.md](../../docs/design/rebuild-26/worldgen.md#biome-library)).
`:compiler:biomeLibraryIndex` lists the files in `index.txt` beside them, so
the library reads the same from the class path in tests, in dev and from the
nested jar. Every compile validates the library (`BiomeLibrary.validate`) and
writes each biome as `<namespace>:<id>` with its features as
`<namespace>:biome/<id>/<name>`; a definition lists it in `biomes`, a zone's
`biomes` or a `swap` target exactly as it lists a vanilla biome. A new biome
is a new file: `BiomeLibraryTest` validates every shipped biome against the
schema and vanilla's registries, and the self-test's biomes section shows it
on generated chunks.

| Biome | Realm | What it is |
| --- | --- | --- |
| `ashen_woodland` | overworld | Burnt birch wood, burnt many times: bare and stripped birch trunks with a few leaves, charred coal-block trunks and stubs, fallen and sunk coal-block logs, fallen stripped logs, dead bushes, coarse dirt with gravel and podzol patches over gravel seamed with coal, white ash and a grey haze |
| `chalk_cliffs` | overworld | Calcite on every steep face seamed with diorite, pale turf, gravel coves, calcite boulders, wind-bent oaks, sea pickles in bright shallows |
| `maple_woodland` | overworld | Crimson domed oaks and orange-crowned birch trunks over green turf, red leaf litter, podzol, pumpkins, firefly bushes, drifting red leaves |
| `redwood_grove` | overworld | Two-by-two spruce giants of 32 to 45 blocks with bare lower trunks, young spruces, mossy fallen trunks, ferns, moss on podzol, dim green fog |
| `lavender_fields` | overworld | Open meadow carpeted in alliums and lilacs, birches with bee nests, lilac-tinted grass and sky |
| `cypress_wetland` | overworld | Clay-bottomed shallows in grass over mud on level ground, flat-crowned mangrove-log cypresses standing in the water, lily pads on the water, cane, dripleaf, fireflies, green mist |
| `prairie` | overworld | Knee-high tall and dry grass under golden grass colour, wildflowers, sunflowers, lone fancy oaks, horses |
| `highland_fells` | overworld | Treeless brown-green moor, pink-petal and allium heather, dry tussocks, coarse dirt, mossy cobblestone outcrops, sheep |
| `saguaro_desert` | overworld | Red sand over red sandstone, branched cactus saguaros with flowers, dead bushes, armadillos, orange horizon |
| `tropical_shores` | overworld | Sand over sandstone, leaning jungle-log palms with cocoa, melons, parrots, coral and sea pickles in turquoise water |
| `parched_forest` | overworld | Small olive-yellow oaks and bare snags on yellowed grass, coarse dirt and packed mud, packed-mud termite mounds, bushes, dry grass |
| `aspen_forest` | overworld | Dense tall birch trunks under narrow gold crowns, ferns, golden leaf litter, drifting yellow leaves |
| `desert_oasis` | overworld | Grass-ringed turquoise pools on level ground in sand over sandstone, sand and mud rims, palms on the grass, sugar cane, lily pads on the water, ferns, cactus, camels |
| `dry_steppe` | overworld | Short pale-olive turf with gravel and coarse dirt scars, short and tall dry grass, lone bushes, andesite stones, horses |
| `barley_fields` | overworld | Drifts of ripe wheat on dry farmland under gold grass, hay bales, poppies, lone fancy oaks |
| `wisteria_grove` | overworld | Oaks with purple crowns and long purple vine curtains, moss, lilacs, alliums, drifting petals |
| `magnolia_woodland` | overworld | Short cherry-trunked trees in white (pale oak leaves flecked with cherry) and pink blossom, pink petals, lily of the valley, pale fog |
| `carnation_fields` | overworld | Open drifts of poppies, red and pink tulips, peonies and rose bushes, low oaks with bee nests |
| `alpine_scree` | overworld | Gravel slopes over andesite, gravel and cobblestone fans, tuff boulders, grass tufts, goats |
| `alpine_meadow` | overworld | Steep turf with stone cliffs, blue orchids, cornflowers and alliums among andesite boulders, low spruces, snow patches from about y 140 |
| `cloud_forest` | overworld | Vine-hung, moss-topped jungle trees and giants, moss and mud floors, ferns, big dripleaf, parrots, close white mist, rain |
| `orchid_marsh` | overworld | Treeless clay-bottomed pools on level ground, sugar cane reeds, blue orchids, lily pads on the water, frogs |
| `muddy_river` | overworld | Brown water over clay and mud beds, mud banks, packed mud and mangrove roots at the edge, reeds |
| `alpha_grove` | overworld | Saturated green grass and foliage, plain blob oaks and nothing else on the ground, gravel beaches, the old pale sky blue |
| `pumpkin_fields` | overworld | Long dull orange grass, loose pumpkin patches, rare carved pumpkins and jack o'lanterns, hay, coarse dirt, gnarled dark oaks with leaf litter, orange fog |
| `joshua_desert` | overworld | Sand and coarse dirt, Joshua trees forked two or three times with spiky needle crowns, dead bushes, cactus flowers, white-hot sky |
| `baobab_savanna` | overworld | Dry yellow grass, lone baobabs: 3x3 bark trunks swelling to 5x5, short boughs, small flat crowns; red sand patches |
| `fungal_fen` | overworld | Giant mushrooms standing in broad shallow pools of dark water on level ground, mycelium and mud with grassy tussocks, tall grass, large ferns and sugar cane at the pool edges, muddy roots, shelf fungi on fallen logs, lily pads on the water |
| `dying_forest` | overworld | Spruces thinning, browning or bare, snapped trunks, fallen spruce logs, podzol and coarse dirt under snow, grey sky |
| `sugi_forest` | overworld | Very tall straight red-barked trunks with flat stacked needle tiers, moss floors, ferns, mossy boulders, green mist |
| `moonlight_grove` | overworld | Slim birch trunks under silver pale-oak crowns hung with pale moss, glow lichen on the trunks, fireflies, blue dusk fog |
| `cedar_forest` | overworld | Tall narrow dark cones of dark oak leaves on spruce trunks, young cedars, fallen cedars, ferns on podzol, low grey fog |
| `temperate_rainforest` | overworld | Vine-clad spruces and two-by-two giants on moss and mud, mossy fallen logs, deep ferns, rain |
| `ashen_savanna` | overworld | Grey grass over coarse dirt, blackened forked acacias with few leaves, snags, dry grass, white ash |
| `bayou` | overworld | Brown shallows over clay and mud, dark trees hung with pale moss, cypress knees, reed beds, lily pads |
| `rock_pools` | overworld | Grey stone shelves at the water line with shallow clear pools of seagrass and sea pickles |
| `old_growth_oakwood` | overworld | Two-by-two oaks with broad crowns and surface roots, young oaks, dark willows over clay ponds, leaf litter |
| `sculk_spread` | overworld | Sculk patches and veins over the dimension's own ground, rare catalysts, bare dead trunks, teal gloom |
| `crystal_hollows` | caves | Calcite and smooth basalt walls, open geodes in every chunk, budding amethyst seams, cluster-covered floor and ceiling patches, glow lichen, purple motes and amethyst chimes |
| `lichen_caves` | caves | Tuff ceilings and walls, moss and mossy cobblestone floors, hanging roots, dripleaf pools, dense glow lichen, grey-green fog |
| `bioshroom_caves` | caves | Giant mushrooms with shroomlight and froglight-speckled caps on mycelium and mud, small mushrooms, spores in blue-green fog |
| `magmatic_caves` | caves | Basalt ceilings, blackstone and basalt walls, magma floors, obsidian-rimmed lava pools, lava falls, smoke and embers in red fog |
| `cinnabar_veins` | caves | Cinnabar ceilings and walls over tuff, polished cinnabar columns, resin clumps, extra redstone ore, red motes |
| `mineral_strata` | caves | Walls banded in five-block layers of granite, diorite, andesite, tuff and calcite, ore seams, granite and diorite boulders, glow lichen, white dust |
| `buried_dunes` | caves | Sandstone roofs and walls, sand drifts with dead bushes, cut-sandstone pillars, husks, falling sand dust |
| `gelatinous_caves` | caves | Slime and honey pillars round coloured froglight cores floor to roof, slime and honey drips from calcite ceilings, honeycomb floor crusts, sea-pickle pools |
| `root_hollows` | caves | Mangrove-root strands floor to roof and wall to wall, rooted dirt and mud floors, hanging roots, glow berries, dripstone |
| `sculk_depths` | caves | Caverns grown over with sculk on floor, wall and roof, catalysts, sensors, bone seams, near-black teal fog |
| `frosted_caves` | caves | Packed ice ceilings, ice and blue-ice walls, icicles, snow floors, powder snow, strays |
| `salt_caves` | caves | Calcite and diorite walls seamed with pink and white terracotta, salt crusts, salt stalagmites and drips, brine pools |
| `nether_grasslands` | nether | Netherrack plains under crimson and warped nylium patches, sprouts, roots, small fungi, glowstone mounds, orange haze |
| `magma_land` | nether | Magma floors cracked with netherrack, obsidian-rimmed lava pools, fire, heavy red haze |
| `gravel_desert` | nether | Gravel floors, buried and standing bone blocks, dead bushes in coarse dirt hollows, skeletons, pale dust |
| `upside_down_forest` | nether | Nether wart canopy on every roof, hanging crimson stems tipped with shroomlight, weeping-vine curtains, falling spores |
| `blackstone_basin` | nether | Blackstone throughout, polished blackstone terraces, gilded seams, blackstone column clusters, lava lakes, black smoke |
| `ash_barrens` | nether | Tuff over smooth basalt, tuff and soul soil heaps, basalt stumps, lying and buried bone, thick white ash, grey fog |
| `withered_forest` | nether | Short stripped crimson and warped trunks on soul soil, sparse dead bushes round them, fallen stems, wither roses, soul fire, thick blue-grey fog |
| `quartz_flats` | nether | Quartz-paved plains, quartz ore seams, glowstone-capped quartz pillars, loose quartz slabs, roof glowstone, pale fog, an ultra-rare 1x1 water pit in a broken quartz-brick ruin |
| `crimson_pinewood` | nether | Tall crimson stems under tiered, pointed wart crowns with shroomlights, roots and small fungi, crimson spores |
| `redstone_abyss` | nether | Redstone ore, cinnabar and blackstone seams, redstone ore and cinnabar outcrops on the floors, natural Nether light only, electric sparks, scarlet fog |
| `mycotoxic_undergrowth` | nether | Moss floors, giant fungi with slime-block caps and verdant froglights, twisting vines, hanging green cave vines, poisonous green haze |
| `nether_jungle` | nether | Tall stripped crimson and warped stems under wart canopies, dense weeping-vine lianas, twisting vines, nylium patches, red-green fog |
| `bone_reef` | nether | Soul sand plains with curved bone ribs in loose cages, sunken spines, buried bone, skulls, bone-pale haze |
| `chorus_forest` | end | Branching chorus close enough to be a forest, tall chorus stalks tipped with end rods, budding chorus flowers, purple fog and portal sparks |
| `dust_wastelands` | end | Sand over sandstone heaped into dunes, half-buried end stone boulders, dead bushes and dry grass, yellow-grey dust haze |
| `umbral_wastes` | end | Blackstone islands, obsidian spikes, long thin kinked cracks of crying obsidian through the ground, soul fire on soul soil, drifting ash |
| `foggy_mushroomland` | end | Mycelium and moss, giant mushrooms capped with moss and pearlescent froglights, spore blossoms under the caps, mooshrooms, close fog |
| `glowing_grasslands` | end | Moss meadows over end stone, moss carpets, small dripleaf, verdant froglight clusters, glow lichen |
| `sulphur_springs` | end | Water pools ringed with sulfur over wet potent sulfur and magma beds, sulfur crusts and spiked vents, steam |
| `lantern_woods` | end | Pale oaks on pale moss hung with soul lanterns and pale moss, pale moss carpets, soul motes |
| `megalake` | end | Broad shallow lakes running together over the level ground, held in by sand shores, springs pouring from higher ground into them, a few springs at island edges falling into the void, clay and sand beds with sea pickles, deep blue water |
| `umbrella_jungle` | end | Tall purpur stalks under drooping domes of cherry blossom and flowering azalea leaves hung with strands of pale moss, moss on the end stone, purple motes |
| `neon_oasis` | end | Cyan pools on level ground lit from below by sea lanterns, coral reefs in the pools and coral rims at the water's edge, sea-lantern-crowned dark prismarine pillars, sand shores, glow lichen |
| `ice_starfield` | end | Snow-covered islands, packed-ice spikes, blue-ice shards, packed-ice sheets, snow drifts, white sparks and snowflakes |
| `painted_mountains` | end | Islands banded in orange, white, pink and purple terracotta by height, stepped banded terracotta peaks, banded end-stone spires |
| `sheared_highlands` | end | High island tops split by parallel fins of polished basalt, fallen basalt blades, tuff scree, bare end stone |
| `sky_highlands` | sky | Pale grass over calcite cliffs, quartz outcrops, small round pale oaks, white and pale-blue flowers, sheep, white sky |
| `golden_skygrove` | sky | Gold-tinted round oaks with bee nests and gold poplars on golden grass and podzol, leaf litter, golden dandelions, drifting gold leaves, gold light |
| `windswept_isles` | sky | Bare stone with calcite cliffs, wind-bent spruces, cobblestone boulders, deep drifts of loose snow, snowflakes and wind |
| `cloud_reef` | sky | Snow and calcite cloud islands with soft snow drifts, a bleached reef of dead grey coral and bone-block trees, claws and mushrooms hung with dead fans, a few end rods, close pale mist with drifting motes |
| `hanging_gardens` | sky | Undersides hung with moss, long glow-berry cave vines and hanging roots, flowery grass tops with azaleas, green-gold light |
| `mirage_isles` | sky | Sparse pale isles of calcite pavement through thin turf, lone silver pale oaks, mirror pools, warm white glare |

### Generators

Every generator starts from the vanilla file of record and changes only what
the definition sets.

| `generator` | Compiles to | Tuning |
| --- | --- | --- |
| `noise`, preset `overworld`, `large_biomes`, `amplified`, `caves`, `floating_islands`, `nether`, `end` | `minecraft:noise` with the preset's noise settings; a definition's `biomes` become a multi-noise layout with no overlaps (`BiomeLayout`: climate boxes first, then vanilla's entries, or a temperature-by-humidity grid when a listed biome has none); the End keeps `minecraft:the_end` unless it lists biomes, which then spread over the overworld's temperature and vegetation | `liquids.seaLevel` → `sea_level`, `liquids.defaultFluid` → `default_fluid`, `liquids.aquifers` → the `aquifers` object kept (`on`), removed (`off`, `flooded`) or removed with air as the fluid (`dry`), `liquids.lavaLevel` and `liquids.basins` → the runtime; `materials.defaultBlock` → `default_block` on 26.3 and the last entry of the material rule on 26.4, and in place of the preset rule's own fill of its default block (the End's rule is one unconditional `end_stone`, the Nether's ends in `netherrack`), so the ground no palette claims is the definition's; palettes (`top`, `filler`, `deep`, `cliffs`, `beaches`, `ceilings`, per-biome `biomes`, per-depth `bands`) and `ores.veins` → the dimension's material rule, with a listed vanilla Nether biome's own ground off the nether preset and `moss_block` on the caves preset's dry floors where vanilla lays grass; `ores.add` and `ores.remove` → placed features and the runtime; `endIsland: false` (End only) swaps vanilla's `end/islands` for a copy that keeps only `end_outer_islands`; `biomeLayout.over: land` ranks the regions over the End's islands (its erosion, the island density, from -0.0625) or a sky terrain's land (its place cue). Fields: [schema-v2.md](../../docs/design/rebuild-26/schema-v2.md#materials-liquids-and-ores) |
| `checkerboard` | overworld noise, `minecraft:checkerboard` source | `cellScale` → `scale`: cells of 2^(cellScale+4) blocks; `voidBiomes` → the router's final density held at -1 in the cells of those biomes (`customdimensions:checker_cells`, vanilla's cell index), so they carry no terrain; `liquids`, `materials` and `ores` as for `noise` |
| `single_biome` | the noise of its `preset` (overworld unless it names one, with that preset's dimension type), `minecraft:fixed` source | `biome`, `preset`; `liquids`, `materials` and `ores` as for `noise` |
| `flat` | `minecraft:flat`, no features or lakes | `layers`, `biome` |
| `void` | with one known biome or none, `minecraft:flat` with no layers; with more, or a `biomeLayout`, `minecraft:noise` on the overworld's climate with a final density of -1, no aquifers and its sea at the bottom of the world, so no block generates | its biome, else `minecraft:the_void`; a list laid out as a noise world's, by climate box or `biomeLayout` |
| `clone` | the generator of the definition `of` names, under the clone's own dimension type, seed and atmosphere | its own `terrain` in place of the source's, compiling its own noise settings; its own `liquids` and `materials` over the source's; its own `biomes` and `biomeLayout` over the source's |

`zones` lay shapes over any noise generator (`ZonesCompiler`, [worldgen.md § Zones](../../docs/design/rebuild-26/worldgen.md#zones)):
each zone's mask is `<namespace>:zones/<slug>/<index>/mask`; a zone's terrain
compiles under `<namespace>:terrain/<slug>/zone_<index>/` and is blended into
the router; a zone that sets or swaps biomes wraps the biome source in
`customdimensions:zoned`. A stamp of a biome the game lacks is a skipped biome
in the report.

`terrain` picks a terrain from the library (`compiler/.../terrain/`, one enum
constant per terrain with its parameters, ranges, defaults and
`gentle`/`extreme` settings): a land terrain for any noise generator but the
`caves`, `nether` and `end` presets, and `roofed`, a floor under a ceiling
(`RoofedTerrain`), for `caves` and `nether`, where it replaces only the
preset's 3D noise inside vanilla's slides, and `end` (`EndTerrain`) for the
`end` preset, which shapes the End's island density and gives it to the router's
erosion, so the End's biome source follows the shaped islands. `spires` takes
`terrain.floor` (`land`, `sea`, `void`, `islands`); over a void or on islands it is a
terrain over a void like `floating_islands`. On `floating_islands`, `basin_islands` and
spires off the land, `terrain.layout` moves the coverage noise. `floating_islands`
and `basin_islands` stack up to four island layers (`tiers`), each `tierSpacing`
above the last and smaller by `tierShrink`, with at least `tierGap` blocks of open
sky over the tier below (`<prefix>/tier<k>_top`, `tier<k>_bottom`); the compiler
refuses a stack whose top tier rises over the dimension's top. `floating_islands`
also flattens its tops (`flatness`), steps them into plateau treads
(`terraces`, `terraceStep`) and raises spires on the lowest tier's tops
(`spires`, `spireHeight`, `spireThickness`, `spireTaper`), several to a broad
island. `basin_islands` under a `ring` layout holds the ring's hollow as one
lake mass `hollowDepth` deep (`ringLakes` 0 makes it the only one). A terrain over a
void whose biome layout ranks over land or gives a biome the `basin` role writes
its place cue (`<prefix>/place`: -1 void, -0.4 basin water, 0.5 land) as the router's
continentalness; one with an `underside` biome writes `<prefix>/island_depth`, the
depth below the islands' tops, as the router's depth. A `roofed` floor with a
`liquids.seaLevel` over part of it writes `<prefix>/coast`, (floor − sea level) / 64,
as the router's continentalness when a listed biome takes the `ocean` or `shore`
role or regions rank `over: land`: the ocean biomes take the floor under the sea, a
shore biome 3 blocks either side of it, the land biomes the dry floor, each ranked
over its own ground (no river band: a roofed floor refuses the `river` role).
`biomeLayout.belowFloor` (biome to blocks, 4–256) binds a listed biome to every
point that many blocks or more under a `roofed` floor: the router's depth there
reads from 1.5 up, 0.5 per 256 blocks deeper, so each such biome takes its band
(the deepest to the axis's end) under every place and none of the open floor,
while the depth above keeps `(floorHeight − y) / 128` for `climate.depth` boxes.
`terrain.landShare` (0–1)
holds that share of the player border to land for any seed: the terrain's
continentalness (`<prefix>/continents`, after its layout) is wrapped in
`customdimensions:land_share`, which samples the seed's own continentalness on a
96 × 96 grid over the border when the level loads and shifts it so the share
lies at or above the coast (a land terrain's `coast`, the overworld presets'
-0.19); terrain and biomes both read the shifted value. A sea under the land
(`liquids.seaLevel` below the sea floor less 20 blocks: `seaFloor` on a land
terrain, y 36 on the overworld presets) holds no water, so biome regions lay
the land biomes on the ocean's area and add no default sea; a listed ocean or a
`biomeLayout.roles` entry still takes it. Without a `base` the `caves`, `nether`
and `end` presets take `terrain.scales.climate`, `climateShift` and `climateWarp` alone
(`TerrainLibrary.CLIMATE_ONLY`), which stretch, shift and warp the climate their biomes are
laid out on. `terrain.climateShift` adds to the router's temperature and vegetation
(a shift, not a clamp: boxes past the shifted range lose their ground to the box at
the end); `terrain.climateWarp` moves where they are sampled along the
`<namespace>:terrain/warp_x` and `warp_z` noises, so climate boundaries between biomes
wander. The terrains and their parameters are in
[worldgen.md § Terrain library](../../docs/design/rebuild-26/worldgen.md#terrain-library).

`biomeLayout.places` (with `mode: regions`) lays the regional land biomes out
as places: each biome, or each named `groups` entry, takes one contiguous
region of the border (`borders.player`, else `borders.generation`, the
radius players see) whose share is its weight. The compiler solves a power
diagram over one site per place (`compiler/.../terrain/PlaceField.java`,
`compiler/.../datapack/PlaceLayout.java`) and writes it as vanilla density
functions under `<namespace>:places/<slug>/`, which become the router's first
region axis; `customdimensions:zoned` reads that axis as it is (`regions.places`
in `ClimateRegions`) and ranks only the second, which mixes a group's biomes
inside its place. Sites come from `at` (blocks), a named `arrangement` (`ring`,
`sectors`, `bands`, `core`, turned by `direction`), or, by default, four solved
layouts of which the level's seed picks one and one of the square's eight
symmetries; `neighbours` and `apart` pairs are honoured or refused at compile
time. Edges wander by `warp` (else `terrain.climateWarp`, else 0.1 of a
wavelength of half the radius), at most 0.15 of the wavelength.

A clone's `of` resolves to a slug: `paradise_lost:paradise_lost` to
`the_aviary`, a base world's level stem (`minecraft:the_nether`) to its slug,
and `<namespace>:<slug>` to that definition. A clone of a clone resolves
through the chain; a cycle stops the compile with the chain in the message.
A clone whose `of` no definition provides is reported and not compiled.

The compile report flags every dimension type whose height leaves the range
of the Bedrock dimension Geyser shows it as (`BedrockHeights`): the type's
`skybox` picks it (`none` the Nether, y 0–128, bottom only; `end` the End,
y 0–256; otherwise the Overworld, y -64–320). It is a warning; the pack is
written either way.

The self-test's generator section (`GeneratorSelfTest`, after the runtime-level
checks) opens one shipped definition per variant, force-loads a fixed area,
and reads its heightmaps, blocks and biomes: overworld heights and water,
large biomes against the overworld, amplified heights, the caves ceiling,
floating-island undersides, a clone against The Aviary (fixture
`selftest_clone`), the Nether's roof and lava sea, the End's island and void
ring, the flat layer stack, checkerboard cells, an empty void and a sea level
over floating islands (fixture `selftest_sea_level`). The terrain section (`TerrainSelfTest`, after it) opens each terrain's gallery
fixture at its default setting, scans its base height off the server thread,
force-loads real chunks where the terrain's feature is and measures it (land
and ocean, the share on one basin floor, peak rise, terrace treads, canyon cut and
dry stepped canyon floors against the rivers fixture, river water, air under sky islands, open water around islands, a void column
and its rim, cliff and chasm steps, spire rise). Each line also states the same
measure on `terrain_continents_default`, loaded in the same kind of area as the
control, and most thresholds require a margin over it. It checks every block lies inside
Bedrock's overworld range, measures each library noise's spread and the cost
per column against the overworld's. `-PterrainGallery=<dir>` adds every
gallery setting and renders each as a greyscale height PNG with an
`index.html`; `-PselfTestOnly=terrain` runs that section alone.
No shipped definition uses `single_biome`, so its
line reads `SKIP` and `GeneratorVariantsTest` covers it.

The materials section (`MaterialsSelfTest`) opens the fixtures
`selftest_dry`, `selftest_flooded`, `selftest_palette` and `selftest_ores` and
scans every block of a 64x64 area: no water below sea level in the dry one,
lava below its lava level and none above, water in at least 95% of open cells
below sea level in the flooded one, the palette's top and filler in at least
95% of plains land columns and its deep block in at least 90% (ore veins
and blobs take some), the desert's own top only in the desert, and the added
ore present with the removed diamonds and the vein blocks absent, against the
overworld's same area. `-PselfTestOnly=materials` runs the compiled-pack and
materials sections only.

The biomes section (`BiomeLibrarySelfTest`) opens `selftest_biome_library`:
the library's ashen woodland and crystal hollows beside plains in 16-block
cells, with diamonds removed and an ore added for the level. It checks both
library biomes are in the registry with the compiled file's attributes,
climate and feature lists (the firewall strips nothing from them), that the
level's ore edits reach plains and leave both library biomes' lists as they
are, and, over 96x96 generated columns, that both appear, at least 50% of the
ashen woodland's land columns are topped with its palette's coarse dirt and
90% with its ground blocks, its trunk feature stood birch and stripped birch
logs upright, and the crystal hollows hold clusters and budding amethyst at
over twice any other biome's density per column (geodes and patches reach
across the 16-block cells). Then it opens each library biome's showcase,
`selftest_biome_<id>` (a `single_biome` level holding the biome alone: on
overworld terrain with spawn at 0, 0, where a cave biome fills the caverns
too; on Nether terrain for a Nether biome; on End terrain with spawn at 1408,
0 on the outer islands for an End biome, since the central island and the void
round it fill the first thousand blocks; over floating islands for a sky
biome), one at a time, and over the 96x96 columns round its spawn
checks the biome is at the surface of every column, its palette's top block
tops at least 50% of the land columns where the palette has one (under a
roof, the floor of the highest open space), and each of
its own features left a block only it places: the first of what it grows
(`vegetation_feature`) or tops a block column with, its main block (the first
block under `trunk_provider`, `to_place`, `state`, `state_provider`,
`ground_state`, `provider` or `block`), then any other block it places, that
neither the level's terrain blocks, the palette, the vanilla features the biome
lists nor its other own features place, with the features it names expanded
and its tests and replacement targets skipped. Failing that, a block only it
and some of its sibling features place is counted for them together; a feature
with neither is named in the line as placing no block of its own and is not
counted. The ashen woodland's line also counts its coal blocks on or above
the ground and its coal blocks and coal ore in the six blocks under it (both
must be there), and each water biome's (cypress wetland, desert oasis, orchid
marsh, muddy river, megalake, sulphur springs, fungal fen, neon oasis) its lily pads with no water under them (must be none), its
pool cells (source water topping a column) open on a side to neither water nor
a solid block (must be none, with some pool water), its dirt, grass, mud or clay
cells with water on all four sides, the share of pool columns whose water runs
unbroken to a solid floor, the share of the level's columns pools cover, its
columns topped by flowing water (streams, never counted as holes), and its
springs: a water source in a cliff face under the surface, open on one side,
counted as pouring into a lake when its fall lands within 24 blocks and four
blocks across of source water, and as falling into the void when nothing solid
lies under the open side.
One line per biome carries the counts, and the
server log names the standing column of each counted block nearest spawn (at any
height for a cave or Nether biome, else on the surface).
`-PselfTestOnly=biomes` runs the compiled-pack and biomes sections only.

## Runtime levels

`server/.../level/` opens the dimensions. The four base worlds load at start
from the pack; every lazy stem is opened through Fantasy as a persistent level
`<namespace>:<slug>`, stored in `<world>/dimensions/<namespace>/<slug>/`.

| Piece | What it does |
| --- | --- |
| `PackInstaller` | Compiles the definitions into `<world>/datapacks/customdimensions/` at mod initialisation, before the world loads ([below](#the-pack-at-start-up)) |
| `RuntimeRegistry` | First at server start: binds each `runtime_registry/` file to its registry entry in place of the vanilla form, so base worlds and lazy levels generate from the compiled forms |
| `LevelIndex` | At start, decodes every lazy stem from the server's resources and pairs it with its definition (seed, spawn, borders) |
| `WorldgenRuntime` | Applies `worldgen_runtime/` to a level's generator before it generates: base worlds at server start, a lazy level as its stem is decoded. A lava level replaces the generator's fluid rule; a dry zone wraps it in `DryFluidPicker` (no sea or lava under the compiled mask and 16 blocks beyond, with `NoiseBasedAquiferMixin` and `DryFluidFeatureMixin` keeping aquifers, springs and lakes out); added and removed placed features replace that generator's per-biome feature lists, leaving the shared biomes alone. Two accessor mixins reach the fields |
| `ZonedBiomeSource` (`zone/`) | `customdimensions:zoned`: a base biome source with zone layouts and swaps over it. The router's temperature carries the zone whose biomes hold, as the base temperature plus 16 per channel; channel 0 is the base. Registered in `ZoneTypes` with `customdimensions:zone_distance`, the sector and polygon distance function. Neither type reaches a client |
| `LevelManager` | Activation queue, one at a time (K6); spawn chunk loaded through a ticket, never on the tick; unload and delete polled until the level leaves `levelKeys()`; idle unload after `idleUnloadMinutes` |

`-Pdefinitions=<dir>` points `runServer` at another definitions folder. Definitions are read from `-Dcustomdimensions.definitions`, else from
`config/custom-dimensions/dimensions/` (with its `settings.json`) under the game directory. Each level's
border is `2 × borders.player` around 0,0; a definition without one takes
`settings.json`'s `defaults.borders.player`. Fantasy never writes its level
stems to `world_gen_settings.dat`, so a deleted or unloaded level is not loaded
on the next boot.

### The pack at start-up

The mod compiles its own pack (RB-33). `PackInstaller` reads vanilla's
`data/minecraft/**` from the Minecraft jar the server runs and builds the
multi-noise parameter lists from `MultiNoiseBiomeSourceParameterList.knownPresets()`
with the codec of the data generator's biome-parameters report
(`RuntimeVanilla`); `-Dcustomdimensions.vanilla=<dir>` swaps in an extracted
copy. The pack is compiled at every start-up (about 3 s) and rewritten only
when the result differs from the files on disk, so a compiler change reaches
the pack whatever the mod's version; `inputs.sha256` records the hash of the
definition files, `settings.json`, the mod version and the Minecraft version.
A rewrite that changes a dimension's noise
settings or level-stem generator while its region folder holds chunks logs one
WARN naming [D2](../../TROUBLESHOOTING.md#d2) per dimension. Without a
definitions folder the mod logs one INFO and the world keeps the pack it has.
`runServer` takes this path; the self-test's equivalence line compiles the
definitions from `-Dcustomdimensions.vanilla.reference` (the
`:compiler:vanillaData` folder) and from the running game and compares the
files byte for byte.

### Commands

`/customdim`, permission level 4. Replies are the `customdim.*` templates in
`config/messages.json`. One class per concern in `server/.../command/`.

| Command | What it does |
| --- | --- |
| `list` | Every dimension: slug, state (`base`, `lazy`, `queued`, `opening`, `active`, `unloading`, `deleting`), players in it, loaded chunks |
| `info <dimension>` | Generator (type, preset or clone source), seed, dimension type height, difficulty, peaceful, portals (frame, igniter), exits, wins, borders, state and any Bedrock height warning |
| `tp <dimension> [player]` | To the spawn column's surface (0,0 without a spawn), opening a lazy level first; a level with a ceiling or an empty column keeps the spawn's y. The console names the player |
| `activate <dimension>`, `unload <dimension>` | Open or close a lazy level; a base world is always loaded |
| `delete <dimension> [confirm]` | Without `confirm`, says which level folder would go; with it, `LevelManager.delete`. Base worlds are refused; the definition stays |
| `create <slug> [template]` | Writes `<definitions>/<slug>.json` from the template (default `overworld`) with a fresh seed and one new portal colour, without the template's `spawn`, portal `anchor`s and `wins`; reads it back, compiles it with the other definitions in a temporary folder, and replies that it opens after the next restart (RB-34). The copy keeps the template's frame and igniter |
| `audit` | Writes `<world>/customdimensions/audit.md`: definitions compiled afresh against the pack (stale, missing, in the pack with no definition), level folders with no definition, portal links to unknown dimensions, frame blocks and igniters the server lacks, Bedrock height warnings, skipped biomes and unsupported fields, and the firewall's allowed mods, unreviewed bridges, switched-off structure sets and biome removals |
| `catalogue` | Writes `../.seed-rolling/catalogue/registries.json` beside the game directory for `scripts/extract-registries.py`: biomes, biome tags, structures, structure sets (placement as the game's codec writes it), blocks, items, features, placed features, entity types, and every tag of those registries with its members |
| `render <dimension> [seed] [radius] [step] [centreX centreZ]` | Draws the dimension top-down from the `Sampler` into `renders/<dimension>/` under the game directory, off the server thread, without generating chunks ([below](#rendering)). One render at a time |
| `map export <dimension>\|all [generated\|explored\|force]` | The web map behind map.DOMAIN into `map-web/` under the game directory, one dimension at a time off the server thread: generated layers (skipped while their fingerprint holds; `force` redraws them) and the explored layer (only region files changed since the last export), then the viewer shell and the manifest ([below](#the-web-map)). Shares the one render job |
| `roll <dimension> [count]` | Scores `count` seeds (default 100, at most 500) from the `Sampler` off the server thread, renders the best ten and writes the contact sheet `renders/<dimension>/index.html` ([below](#rolling)). Refused for a dimension whose region folder holds chunks. Shares the one render job |
| `pick <dimension> <seed>`, `pick <dimension> rank <n>` | Writes the seed, or the last roll's rank `n`, into the dimension's definition file; it applies after the next restart. Refused for a dimension whose region folder holds chunks |
| `survey <dimension> [patches] [size]` | Samples real chunks (24 patches of 3x3 chunks by default, at most 96 of 8x8, plus adaptive ones) and writes a dossier to `customdim-survey/<dimension>/` under the game directory, `placeness` included ([placeness.md](../../docs/design/rebuild-26/placeness.md)); `1 1` is the fast placeness-only form. A lazy level opens for it and closes after. Shares the one render job |
| `e2e-state [player]`, `use <x> <y> <z> [face]` | Registered only under `-Dcustomdimensions.e2e=true`, for the e2e harness: `e2e-state` writes the live state the assertions read to `../.seed-rolling/e2e-state.json`; `use`, run as a player, uses the main-hand item on a block face as a right-click does |

## Seeds

Every level generates from its definition's `seed`, the four base worlds
included; `"env"` or no seed takes the world seed (`level-seed`, which
`reset-seed` writes from `SEED`). `execute in <dimension> run seed`,
`customdim info`, `render`, `roll` and the survey all answer that seed
(decisions.md RB-37). `server/.../level/LevelSeeds` chooses each base world's
seed as the server starts, before vanilla creates the levels, and each lazy
level's as it opens; `ServerLevelSeedMixin` answers a base world's
`getSeed()` from it, so the noise, biomes, structures, features, slime chunks
and the End's spikes and gateways all follow it, and `MinecraftServerLevelsMixin`
gives each base world the biome-zoom seed of its own seed, the one its clients
are sent. The level's structure check and dragon fight read the level's seed
for lazy levels too.

Each level records the seed it generated from in
`<world>/dimensions/<namespace>/<path>/customdimensions-seed.json`. A region
folder without chunks takes the definition's seed. One with chunks keeps the
recorded seed (or, before any record, the seed it had: the world seed for a
base world, the definition's for a lazy one), so its terrain stays continuous;
when that differs from the definition's, the start logs one WARN naming both
seeds and the remedy. `pick` is refused for a dimension with chunks, so a seed
changes only on a fresh level: before a base world's first start, or after
`reset-seed`.

## Game rules

A definition's `gameRules` maps 26.3 game rule ids (`keep_inventory`, or a
mod's `othermod:rule`) to a boolean or a whole number, and `settings.json`'s
`defaults.gameRules` gives values to every dimension (decisions.md RB-38). A
level answers each rule with its definition's value, else the defaults', else
the server-wide store's; two dimensions hold different values of one rule at
once, and the game's own behaviour follows in each. The compile refuses an
unknown `minecraft` rule, a value of the wrong type or outside the rule's
bounds, and the server-wide rules below, naming the definition and the rule; a
mod's rule is checked against the live registry when the server starts, and a
bad one stops the start.

Nearly every read of a rule in 26.3 passes through `ServerLevel.getGameRules()`,
and `ServerLevelGameRulesMixin` answers it from a `LevelGameRules.View` over the
store; Fantasy's levels fall through to the same method. A write through a view
reaches the store, so `/gamerule <rule> <value>` keeps its server-wide meaning
(the deploy's `mc_game_rules` relies on it) and a dimension's own value stays in
force; `execute in <dimension> run gamerule <rule>` answers the value in force
there, and `customdim info` lists the dimension's own values.
`ServerPlayerRestoreMixin` judges `keep_inventory` on respawn by the dimension
the player died in, which decided the drop; `deaths`, when set, wins over the
rule.

A client holds `immediate_respawn`, `limited_crafting` and
`reduced_debug_info`. Vanilla sends them at login from the player's level and
to everyone from the store when one changes; `LevelClientRules` sends a player
their level's values after every change of level and every respawn, and
`MinecraftServerGameRulesMixin` sends each player their own level's values
after a server-wide change of one of the three.

The server keeps one weather (`MinecraftServer.getWeatherData()`), which every
level with a sky and no ceiling, the End aside, reads; `ServerLevelWeatherMixin`
lets only the first of them advance it each tick (`SharedWeather`), so it runs
at one pace however many such levels are open. A dimension
that sets its own `advance_weather` has weather of its own instead
(`ServerLevelGameRulesMixin` on `getWeatherData()`): a copy of the server's
when it is first read, saved in the level's own data storage and advanced or
held by its rule; `/weather` run in that dimension changes it, and a
server-wide `/weather` leaves it.

Refused in a definition: `advance_time`. The world clocks are one per day
length (T222) and `ServerClockManager` reads the rule through the overworld,
so no dimension can hold time still on its own; the compile error says to set
`atmosphere.fixedTime` for still time or `atmosphere.dayLength` for a
different pace. The management API's rule methods read and write the store.

## Rendering

`server/.../render/` draws a dimension from its generator alone; the
image-making half (grid, palette, layers, PNG and sidecar) is game-free in
`core/.../render/`.

`/customdim render <dimension> [seed] [radius] [step] [centreX centreZ]` renders
a base world or a lazy dimension, open or not. Without a seed it takes the seed
the level has or would have ([Seeds](#seeds)). Radius 16 to 4096 blocks round 0,0, or round the
centre given after the step (files `<seed>-r<radius>-at<x>_<z>-*`), default 1024; the step
defaults to the smallest that keeps the image at 1024 pixels a side (2 at the
default radius, 8 at 4096) and is refused below it. The command checks its
arguments and starts the job on the server thread; building the sampler,
sampling and writing run on `RenderJob`'s pool of low-priority daemon threads
(`-Dcustomdimensions.render.threads`, default 2, fewer under 4 cores). It
replies when it starts, at 25, 50 and 75 % and when it finishes, and refuses a
second render while one runs. At the default radius a render samples 1,048,576 columns: about six minutes for the overworld, two for the End, 40 seconds for the Nether on two threads here.

| File in `renders/<dimension>/` | What it holds |
| --- | --- |
| `<seed>-r<radius>-biome.png` | Each pixel in its surface biome's colour: a fixed colour per vanilla 26.3 biome (`BiomePalette`), any other id a colour from its hash |
| `<seed>-r<radius>-height.png` | Surface height in grey (dark to light across the dry surface's 1st to 99th percentile, a darker line where a 16-block band changes, lit from the north-west); water blue, darker with depth; lava orange; a column with no solid block black |
| `<seed>-r<radius>-zones.png` | Drawn for a dimension with zones, from the compiled masks: the height layer tinted by each zone's colour as far as its mask reaches, a white line where a mask crosses one half (where its biomes take over) and a black line at the outer edge of its blend |
| `<seed>-r<radius>.json` | Seed and its source, radius, step, side, centre, the surface and biome-y rules, sea level, timings (prepare, sample, images, columns per second, threads), the biome legend with colours and pixel counts, and the height range |

Pixel `(px, py)` is the column at the north-west corner of its step-by-step
cell; north is up. Each image is a `RenderLayer` (core); structure starts join
as a layer.

### The Sampler

`Sampler` is the generator of one dimension under one seed, without a level:

| Call | What it gives |
| --- | --- |
| `Sampler.forDimension(server, manager, slug, seed)` | The sampler for a base or lazy dimension; a null seed takes the dimension's own (`defaultSeed`) |
| `Sampler.of(dimension, generator, minY, height, seed, seedSource, registries)` | The sampler for any `ChunkGenerator` |
| `sample(x, z)` | `Column`: `height`, `roofed`, `fluid` (over the surface), `waterAtSeaLevel`, `biome` |
| `biome(x, y, z)` | The generator's noise biome there, `y` clamped into the dimension |
| `noiseColumn(x, z)` | Every block the noise stage fills in the column |
| `surface(blockAt, top, minY)` | The surface rule, applied to any column (the self-test applies it to real chunks) |

It builds its `RandomState` as the level does (`RandomState.create` with the
generator's noise settings, or the flat-world form for a generator without
them) and reads the generator's own `getBaseColumn`. The surface is the
`OCEAN_FLOOR_WG` heightmap's: one above the top block that blocks motion, so
water and lava lie over it. A column whose noise is solid at the noise's top
has a roof (the Nether, caves); its surface is the first floor under the
roof's underside, the ground a player walks on; a column solid from the roof down takes the roof's height. The biome is read at the
surface height. Every call is thread-safe; each thread keeps its own biome
resolver.

The sampler sees the noise stage: what surface rules, carvers, features and
structures change afterwards is not in it. The self-test's accuracy gate
(`RenderSelfTest`) samples 50 columns within 256 blocks in the overworld, the
Nether, the End, The Aviary and `the_underdark`, generates those chunks and
compares; every mismatch is classed by diffing the chunk's column against the
noise column (`removed`, `flooded`, `added`, `filled`, `structure`), and a
column no class explains fails; each dimension's columns and classes are written
to `renders/<dimension>/accuracy.tsv`. It also checks that the sampler's noise
columns equal the live level generator's, renders the overworld at radius
1024, step 8 while counting the server's ticks, and checks the refusals.

### The fast renderer

`/customdim fastrender <dimension> [stride] [threads]` (`render/fast/`) covers the
dimension's world border (its generation border, or 1024 blocks, when it has
none). The stride defaults to the one that keeps the image near 2048 pixels a
side (4 for a 4096-block border, 8 for 8192) and runs 1 to 16 with the image
at most 4096 pixels; threads default to the render's. It shares the render's
one-job slot and writes `renders/<dimension>/fast-<seed>-r<radius>*`.

Each worker builds a `TileSampler` from the random state, noise settings,
biome source and global fluid picker the level generates with, and samples
tiles of 16 by 16 columns (8 by 8 from a stride of 16, where columns share no
interpolation corners and a smaller tile's window spans less relief). When the
final density holds an `interpolated`
term, `LatticeScanner` reads every block of a tile in one batch: each
`interpolated` term becomes a `LatticeInterpolation`, which samples its input
once on the cell corners under the tile's columns and fills the blocks as
vanilla interpolates a cell (the same lerps and the same running sum up each
cell), and the terms outside it (squeeze, min, range choices, the beardifier)
are vanilla's own, per block. A block's density is the one a noise column of
the same column gives it, bit for bit, and a layer thinner than a cell is
kept. Inside the terms each `lerp` and `old_blended_noise` is a `LazyLerp`,
which samples each side only on the runs of blocks up a column that use it
(vanilla's batch path samples both sides everywhere); the values are
vanilla's. A density with no `interpolated` term goes to `RunScanner`, which
scouts rows 8 blocks apart and samples block by block only the cells whose
rows differ in sign, so a layer thinner than a row's spacing between two rows
is missed there; `-Dcustomdimensions.fastrender.scan=refine` picks it for a
measurement, and `-Dcustomdimensions.fastrender.lazy=off` turns `LazyLerp`
off.

The pre-scan (whole columns every 64 blocks, which also picks the cut) sets
each tile's window of heights (`ScanWindow`): a world with roofs in 2 % of
its columns is scanned whole; a world with floating runs in 2 % (sky tiers,
islands over a void) from 16 blocks under the lowest run found to 16 over the
highest near the tile; any other from 32 blocks under the lowest surface near
the tile to 16 over the highest, its run list holding the runs in that window,
the lowest cut off at its floor. A tile whose window has ground at its top is
widened to the noise top, and one with a column without ground (or, with
tiers, ground at the floor) to the noise floor. Windows start on cell rows, so
the blocks keep their whole-column values. `-Dcustomdimensions.fastrender.window=off`
scans every tile whole.

A column is a list of solid runs, topmost first, at most 24 (`ColumnRuns`);
its surface is read from the runs as the `Sampler` reads a noise column.
`RunDress` puts the level's compiled material rule at each run's top and at a
roof's underside, and the fluid picker's fluid at the lowest open block over
each run; an aquifer's local water tables need a noise chunk and are not
sampled. Which layers and windows are used follows from the runs, never from
the dimension's name. A generator that is not noise based is read through its
own noise columns.

`./gradlew :server:runFastBench -PfastBench=<slug>:<seed>:<radius>:<step>:<threads>[,...]`
runs the same render headless (`FastBench`): it compiles the pack, loads the
world and dimension registries from it, vanilla's data and the mods' data as
a start does, binds the runtime registry, renders each case twice (the first
warms the JIT) into `-PfastBenchOut=<dir>` (default `server/run-bench/out`)
and exits, writing a `summary.txt` line per case with `Sampler.sample`'s rate
over 256 × 256 of the grid's columns beside the sidecar's. No level, world or
network; structure footprints need a server's templates and the worldgen
runtime (lava levels, basins, dry zones) is not applied, so both sides use the
noise settings' own fluid picker. `-PfastBenchJvm='<flags>'` passes JVM flags
(a flight recording, the properties above).

| File in `renders/<dimension>/` | What it holds |
| --- | --- |
| `<seed>-r<radius>-biome.png` | Each pixel in its surface biome's colour: a fixed colour per vanilla 26.3 biome (`BiomePalette`), any other id a colour from its hash |
| `<seed>-r<radius>-height.png` | Surface height in grey (dark to light across the dry surface's 1st to 99th percentile, a darker line where a 16-block band changes, lit from the north-west); water blue, darker with depth; lava orange; a column with no solid block black |
| `<seed>-r<radius>-zones.png` | Drawn for a dimension with zones, from the compiled masks: the height layer tinted by each zone's colour as far as its mask reaches, a white line where a mask crosses one half (where its biomes take over) and a black line at the outer edge of its blend |
| `<seed>-r<radius>.json` | Seed and its source, radius, step, side, centre, the surface and biome-y rules, sea level, timings (prepare, sample, images, columns per second, threads), the biome legend with colours and pixel counts, and the height range |

Pixel `(px, py)` is the column at the north-west corner of its step-by-step
cell; north is up. Each image is a `RenderLayer` (core); structure starts join
as a layer.

#### Waves

`/customdim fastrender <dimension> wave [threads]` (`FastWave`) renders the
dimension in passes over the larger of its player border (the world border)
and generation border, else 1024 blocks: the first at the stride that gives
about 512 pixels across, each next at half the stride (rounded up) until the
image is 1024 pixels (`WavePlan`, core): two passes for a border of 2048 or
more. It replies after each pass, whose
files are complete by then. `/customdim fastrender all wave [threads]` does
every dimension one at a time in one job; a dimension that fails is reported
and the rest go on. A wave shares the render's one-job slot; it draws no
comparison and no run list, and structure footprints only on its last pass.

One pre-scan (whole columns every 64 blocks over the first pass's grid, or
every four first-pass strides when that is wider) serves every pass: it picks the scan windows, and `SliceChoice` (core) picks
the fixed heights each pass also draws top-down from the noise range, the
sea level and the runs it found, never from the dimension's name:

| Slice | When | Height |
| --- | --- | --- |
| `sea-level` | the sea level lies inside the noise range, above its floor | the top block of the sea, one under the settings' sea level |
| `caves`, `deep-caves` | a surface world whose median surface stands 48 blocks over the noise floor | halfway between the median surface and `deep-caves`; 16 over the noise floor |
| `ceiling`, `caves`, `deep-caves` | a roof over 2 % of columns | the roof's median underside; halfway between the median floor and it, when 24 apart; 16 over the noise floor, under a floor 48 over it |
| `sky-1` … `sky-4` | floating runs under 2 % of columns, no roof | the median of each band of run middles (the block halfway down each run of the pre-scan): heights holding 2 % of the most middles at one height, gaps up to 8 bridged, split where the counts averaged over 17 heights dip to 0.85 of the peaks either side, bands of 5 % of the mass, top first |

A slice inside a tile's scan window is read from its runs; outside it the
final density is sampled on that one row (`ColumnModel.solidRow`). Solid
ground is drawn in the material rule's block; open space in the fluid
picker's fluid where nothing solid is over it or the level has no aquifers,
else black (a cave) or slate (open sky). The biome at the slice's height is
drawn beside it where it differs from the surface biome anywhere.

A roofed world's first pass is scanned from its floor (`ScanWindow.FLOOR`)
when that holds on the pre-scan's own columns: from 32 blocks under the
lowest floor nearby to a start under the roof, the noise top less the roof's
median thickness less 8, or 8 over the floors' 0.99 quantile when that is
higher. A run reaching the start is the roof's and is dropped, or, when it
also reaches the noise floor, read as solid to the noise top; the floor is
drawn in place of the roof. The pre-scan reads its own whole columns that
way first, and the first pass scans from the floor only when 95 % of them
keep their floor within one block (`fast.cut.floorAgreement`);
`-Dcustomdimensions.fastrender.floorfirst=off` scans whole columns.

| File in `renders/<dimension>/wave-<seed>/` | What it holds |
| --- | --- |
| `pass<N>-s<stride>-<layer>.png` | The fast render's layers (no `cut` under a floor-first pass), and `slice-<name>.png` and `slice-<name>-biome.png` |
| `pass<N>-s<stride>-sheet.png` | The contact sheet: the map (the floor, roof off, for a roofed world), biome, hillshade, tiers, slices and structures at 384 pixels each, and a legend of slice colours and the 24 biomes with most pixels |
| `pass<N>-s<stride>.json` | The pass's sidecar, as a fast render's, with `fast.slices` (rows sampled and read from runs, solid share and biome-differs share per height) and `fast.floorFirst` |
| `wave.json` | Rewritten after each pass: radius and its rule, noise range, sea level, the pre-scan (spacing, roofed and floating shares, median surface, roof underside and thickness, floor window top and agreement, bands, solid columns and run middles per height), the slices and why, each pass's stride, side, times (`timings` splits terrain, slice rows and slice columns) and files, and the biome legend |

### The web map

`/customdim map export` writes map.DOMAIN's files (`map/`, `server/.../map/`;
the image half in `core/.../render/map/`). The idle task sends
`customdim map export all` once pre-generation has nothing left to do, at most
every `MAP_INTERVAL` (6 hours), and nginx serves the folder as it stands.

**Tiles.** Each dimension covers the larger of its player and generation
borders. Zoom 0 holds the whole border in one 256-pixel tile; each zoom halves
the blocks per pixel down to 4 (`MapTiles`; 8 past a 16,384-block border).
Pixel `i` of zoom `z` is the block `-radius + i * blocksPerPixel[z]`, so a
coarse pixel is a fine pixel's column: the finest image is drawn once and the
coarser zooms are halved from it (`TilePyramid`: the alpha-weighted mean of
each 2 × 2 block, the north-west pixel for biomes). A tile with nothing in it
is not written.

**Generated layers** (`MapExport`). The fast renderer draws the border at the
finest scale in pieces of 1024 pixels with 4 of margin, after one pre-scan
that also picks the cuts (`SliceChoice`), without painting its own images.
Layers: `roofless` (the floor under a roof, first on a world roofed over 2 %
of its columns, its terrain then named Roof), `terrain`, `biomes`, a
`cut-<name>` per fixed height, and a `tier-<n>` per sky band (the highest run
whose top lies in the band). Structure starts, with their structure control
applied, go to `markers.json`. The tiles are staged and swapped in whole; the
fingerprint (`MapFingerprint`: the format, the palette, the seed, the radius,
the generator and its noise settings as their codecs write them) skips an
unchanged dimension.

**Colour.** `core/.../map-palette.json`, written by
`scripts/map-palette.py` from the 26.3 client jar, holds each vanilla block's
alpha-weighted mean colour of its highest upward face texture and that
texture's opacity, which blocks the client tints, and the grass, foliage and
dry-foliage colormaps as 33 × 33 samples; no texture is copied. A block the
palette does not name takes its map colour. A tinted block takes its biome's
tint (`BiomeTint`: the override, else the colormap at the biome's temperature
and downfall, read through `Biome.NETWORK_CODEC`; dark forest's modifier; the
mean of swamp's two). Water hides a quarter of what is under it per block and
darkens towards 0.65 of its colour at 24 blocks; ice, glass and leaves lie
over what is under them by their opacity. Relief is Lambert light from the
north-west at 45 degrees on the slope in blocks per block, so it reads the
same at every zoom (`MapShade`).

**Explored layer** (`ExploredExport`, `SavedChunks`). Every finished saved
chunk inside the border, drawn with the same palette, tints, water and relief
and cut into `explored/<z>/<x>/<y>.png` at the same zooms, transparent where
nothing is saved; each pixel is the mean of its 4 × 4 columns. Which chunks a
region file holds comes from its location table through a read-only channel.
A chunk of an open level is read through the level's own storage
(`ChunkMap.read`, which queues on the storage's IO worker, serves a chunk
waiting to be saved from memory and is safe from any thread); a level that is
not open is read from the region file, read-only, with vanilla's
`RegionFileVersion`. Nothing is loaded into the world or written. A region is
read again only when its file's modification time changed; its cells are
cached in `.cache/explored/`. A chunk that cannot be read is skipped and its
region retried next time.

| File in `map-web/` | What it holds |
| --- | --- |
| `index.html`, `app.js`, `app.css` | The viewer, copied from the jar (`map-shell/`) when they differ: every dimension in the sidebar with its thumbnail, one map with a layer switcher, the explored layer over it (on by default), structures as markers, a legend for biomes and cuts |
| `manifest.json` | Every dimension, drawn or not: `slug, name, description, spawn, background, renderedAt, exploredAt, version, thumb, thumbVersion, layers, explored` |
| `maps/<slug>/layers.json` | Radius, tile size, zooms, blocks per pixel and side per zoom, the layers (`id, kind, name, y, bottom, top, path`), the cuts and why, the biome and cut legends, the fingerprint and the export's times and sizes |
| `maps/<slug>/tiles/<layer>/<z>/<x>/<y>.png`, `thumb.png`, `markers.json` | The generated tiles, the first layer's zoom 0, the structure starts |
| `maps/<slug>/explored/<z>/<x>/<y>.png`, `explored.json` | The explored tiles and their index by zoom, the regions and chunks read and columns per second |
| `.export-finished` | Stamped when an export ends, with a one-line summary: the idle task's sign to let autopause back |

`./gradlew :server:runFastBench -PfastBench=<slug>:<seed>:<radius>:map:<threads>`
runs the generated export headless (twice: the second skips by fingerprint),
with `-PfastBenchRegions=<dir>` also the explored layer from
`<dir>/<slug>/region/`, and writes a servable site to `<out>/map/`.

### Distant Horizons LODs

With Distant Horizons on the server, the mod answers DH's world generation
from the fast renderer's core instead of generated chunks, so `/dh pregen` and
DH's generation for players far away both take terrain from it (`lod/`). DH is
a compile-only dependency: `DistantHorizonsLods` touches no DH class, and the
mod runs the same without DH. When DH loads a server level (every base world
and lazy dimension alike), `DhLodGenerators` binds a `DhLodGenerator` to it
through DH's API (`IDhApiWorldGenerator`, return type `API_DATA_SOURCES`), and
lets it go when DH unloads the level.

DH asks for a data source of 64 × 64 columns at a detail level `d` (a column
every `2^d` blocks, 0 to 12) on its own world generator threads. `LodSource`
samples the 64 × 64 columns at that spacing, in tiles of 16, each thread with
its own `TileSampler` built from the level's random state, noise settings,
biome source and global fluid picker; it never samples every block and
shrinks. `LodColumn` turns each column's runs (`ColumnRuns`, dressed by
`RunDress`) into contiguous segments from the level's top to its floor,
topmost first: the open space over each run (air, and the run's fluid up to
its level), the run's top block from the material rule, its body in the
default block, and its underside block. A segment carries the light of the
block above it, as DH's chunk import records it: sky light 15 over the topmost
run and in an open band at least 16 blocks tall under a run of an unroofed
column (sky tiers, islands over a void), 0 in a shorter band (a cave), under a
roof and in a level without sky light, less one per block of fluid; block
light is the emission of the block above (lava's 15). Each run's biome is the
noise biome at its top; its open space and fluid take it too. The segments go
to DH as block-sized data points marked `LIGHT`, so DH takes them as
finished and does not ask again; DH merges equal neighbours itself.

Detail levels 0 and 1 (`LodSource.FINE_DETAIL`) keep every run of the whole
noise column, roof included. Coarser levels take a shortcut chosen from a
pre-scan of whole columns over the level's reach (every 64 blocks or more, at
most 128 a side, on the first coarse request); the reach is the larger of the
player and generation borders (`LevelMaths.lodReach`), 1,024 blocks without
either. A level roofed in 2 % of the pre-scan's columns has its roof left out:
it is searched down from the highest block open between the ceiling and the
floor under it in the most columns when that block is open in 90 % of them
(`ROOF_START_SHARE`), whole columns otherwise. A level with floating runs in
2 % of its columns is searched from 16 blocks over its topmost tier band to
16 under its lowest run; any other from 32 blocks under its lowest surface to
16 over its highest, ground cut off at the window's floor carried down to the
noise floor. A tile whose ground reaches its window's top, or that loses
ground at its floor, is scanned again to the noise top or floor, so each
column keeps the runs a whole column has in the window. The shortcut follows
from the pre-scan's runs, never from the level's name. Measured headless
(`-PfastBenchLod`, seed 20261022, 16,384 columns per level and detail 2 to 8):
surface heights equal to whole columns' (the floor under the roof for a roofed
level) in 100 % of columns on the overworld, the Nether, the End and
`the_underdark` and 99.87 % or more on The Aviary, and every run inside the
window kept. In the Nether no height is open in more than 55 % of floored
columns, so it scans whole columns and drops the roof.

Not drawn: structures, trees and other features, and carvers' caves, which
come after the noise stage; an aquifer's local water tables (the global fluid
picker's sea and lava levels are drawn). Where a player or Chunky has made
chunks, DH builds those LODs from the chunks as they load.

`./gradlew :server:runFastBench -PfastBench=<slug>:<seed>:<reach>:<step>:<threads>[,...] -PfastBenchLod=<detail>[,...]`
measures each case's `LodSource` headless instead of rendering (`LodBench`):
the pre-scan's shape and rule, each coarse level's shortcut against whole
columns (heights within one block and exact, runs kept, runs per column), and
one request per level answered end to end with every column checked as DH
checks one (no gap, no empty segment, down to the floor). `runServer -Pdh`
also has DH check every column the generator gives it
(`-Dcustomdimensions.dh.validate=true`).

The self-test's LOD section (`-Pdh -PselfTestOnly=lod`;
`-PselfTestLod=<slug>,...`, default `overworld,the_nether,the_aviary`)
checks in each dimension that the generator is bound, compares the shortcut at
detail 2, 4 and 6 with whole columns (heights within one block in 95 %, runs
kept in 90 %), runs `/dh pregen start <id> 0 0 32` and waits for it (15
minutes at most), passing when the generator served full-detail requests and
none failed, then reads DH's stored columns on a 6 × 6 grid within 440 blocks
of 0,0 and generates the real chunks there: surface heights (trees left out)
within one block in 90 % of columns, the block under the surface in 60 % and
the biome in 90 %, every column written to `lod-check-<slug>.tsv` in the run
directory.

## Rolling

`/customdim roll <dimension> [count]` picks the seed for everything zones do
not fix (roller.md). For each seed `Roll` samples, on `RenderJob`'s pool, a
disc of 128 columns a side round the definition's spawn covering the player
border up to 2,048 blocks, and `FactsBuilder`'s spawn patch (radius 128, step
8); reads the wanted, shunned and gate structures' starts from the generator's
placement state (`StructureStarts`) out to the player border, up to 8,192
blocks; turns both into `RollFacts` (core) and scores them with `Scorer` and
`Criteria` against the definition's `roll` field (`RollSpec`). A definition
with a fixed seed has it rolled first, so the sheet shows where it stands. The
roll replies when it starts, at 25, 50 and 75 % and when it finishes; it is
refused while a render or roll runs, for `count` outside 1 to 500, for
`roll.skip`, and for a dimension whose region folder holds chunks (D2).

| File in `renders/<dimension>/` | What it holds |
| --- | --- |
| `index.html` | The contact sheet: the best ten, best first, each with its three images, score, structure legend and scorecard table; relative paths only, so it opens from disk |
| `roll.json` | The ranking `pick <dimension> rank <n>` reads: rank, seed, verdict, percentage, scorecard file, and the disc and structure radii |
| `<seed>-r<radius>-structures.png` | The biome image at a third of its brightness with a white-rimmed square, one colour per structure, at each structure start in the disc |
| `<seed>-r<radius>-scorecard.json` | The seed's `Scorecard`: verdict, tiers and every criterion's outcome, target and evidence |

Biome and height images and the sidecar are the render's. A structure start
is a chunk the set's placement picks, with the structure chosen as
`ChunkGenerator.createStructures` chooses it (weighted, seeded by the chunk)
and its biome checked at the chunk's middle on the sampled surface; checks a
structure makes later (terrain, jigsaw fit) are not made, so a start is a
candidate generation can still drop.

`pick` replaces `seed` in the definition file through the converter's
`JsonWriter`, keeping every other field and the key order, reads the result
back and replies that it applies after the next restart.

`RollSelfTest` runs the render accuracy gate at another 50 columns in the
overworld, the Nether and The Aviary (`renders/<dimension>/agreement.tsv`);
checks every structure-start candidate within 384 blocks of the start nearest 0,0 in the
overworld and the Nether against the starts those chunks record at
`structure_starts` (a recorded start with no candidate fails, unless the
structure's biomes exclude the chunk middle's: class `point`); rolls five
seeds of the first lazy dimension with wants and no chunks and checks its
sheet; picks by seed and by rank into a copy of that definition; and checks
the refusals.

## Firewall

`server/.../firewall/` keeps every generator this mod's (worldgen.md §
[Firewall and bridges](../../docs/design/rebuild-26/worldgen.md#firewall-and-bridges));
the rules are game-free in `core/.../firewall/`.

| Piece | What it does |
| --- | --- |
| `WorldgenFirewall` | At SERVER_STARTING, before any level exists: reads the mods, their mixin targets, the level stems, dimension types, noise settings, biome source and chunk generator types; refuses to start (logs the findings at ERROR and throws, so the server writes a crash report and exits 0) or logs the allowed mods and every switched-off structure set by namespace |
| `MixinTargets` | Each mod's mixin configs from its `fabric.mod.json` and the targets of each `@Mixin` annotation, read with ASM; Mixin's own config registry is empty by then |
| `StructureStateMixin` | Drops switched-off sets from `ChunkGeneratorStructureState.createForNormal`, for base and Fantasy levels alike |
| Biome modification | Fabric's `POST_PROCESSING` phase: in each vanilla biome, removes placed features, and entries of the `natural_mob_spawns` attribute, that vanilla's biome file and the reviewed bridges lack |
| `AllowList`, `WorldgenClasses`, `Firewall` (core) | The allow list, the worldgen class set and the verdict, in one place each |
| `BridgeReader`, `BridgeGenerator` (core) | Bridges in `config/custom-dimensions/bridges/` (or `-Dcustomdimensions.bridges=<dir>`; `runServer` reads the reviewed set in `bridges/`), [bridges.md](../../docs/design/rebuild-26/bridges.md); `:core:bridge -Pjar=… -Pout=…` writes a first one from a jar |

A malformed bridge also refuses the start, naming the file and field. The
self-test's firewall section checks the audit passed, that switching off
`minecraft:villages` removes it from a fresh overworld structure state while
`pillager_outposts` stays, and that every vanilla biome's features match
vanilla's file.

## Structure control

`server/.../structure/` applies each dimension's compiled `structure_control/`
file (worldgen.md § [Structure control](../../docs/design/rebuild-26/worldgen.md#structure-control));
the resolution is game-free in `core/.../structure/StructureSpec` and the
compiler's `StructuresCompiler`, which writes the compile report's
`Structures` and `Structure warnings` lists.

| Piece | What it does |
| --- | --- |
| `StructureRuntime` | Loads the files at SERVER_STARTING; rebuilds a level's structure state from the one vanilla made: filters, shuns, replacements, spacing, density, noise groups (`NoiseFieldIndex`, built in parallel), forced starts and wants; keeps the per-level biome bypass and terrain adjustments |
| `ChunkMapStructuresMixin` | Hands the state to `StructureRuntime` as `ChunkMap` builds it, so it composes with the firewall's `StructureStateMixin` |
| `StructureBiomeBypassMixin` | A forced or wanted structure skips the biome check in its own chunk; an included set wherever its grid places it |
| `BeardifierAdaptationMixin` | The Beardifier reads the dimension's terrain adjustment for each structure |
| `StructurePlacements` | The runtime-only placements: one chunk, and a noise group's sites |
| `StructureReflection` | The private members of the structure state, spreading placements and the structure manager it reads, resolved by name |
| `StructureSizes` | `-PmeasureStructures=<file>`: generates every registered structure's start without a level (overworld, then Nether, then End, up to 24 chunks each, biome check off), writes the bounding-box spans and fills the bridges' `size` slots in `-Dcustomdimensions.bridges`, then stops |

`server/src/main/resources/structure-sizes.json` holds the sizes measured on
26.3 for vanilla's structures and every bridged one; the mod reads it from its
own jar, since a measurement belongs to the game version, not to a server's
config. Measure with the 26.3 list's structure mods loaded:

```bash
./gradlew :server:runServer -PstructureMods=/path/to/mod/jars -PmeasureStructures=src/main/resources/structure-sizes.json
```

`-PstructureMods=<dir>` adds the jars the committed bridges adopt (ATi, Epic
Dungeons, Deadly Deadly Dungeon, Dungeons and Taverns, Towns and Towers, Moog's
five packs and their libraries) to `runServer`. `ShippedBridgesTest` fails when
a bridged structure has no size.

The self-test's structure section opens `selftest_structures` and the five
`selftest_density_*` fixtures. It reads the level's structure state over the
100x100 chunks round spawn for the placement checks (no set places a shunned
village or outpost, so no candidate chunk of 10,000 holds one; mineshafts
rejected, trail ruins excluded, desert portals replaced, the pyramid set at its
spacing), generates the forced igloo's chunk, a mountain ruined portal forced
within 200 blocks, the wanted ruined portal 100–400 blocks out and 24 chunks
where vanilla's village grid would start a village, and reads their starts; it
checks the igloo's `bury` reached the Beardifier, and that dense, normal and
sparse order the noise sites and normal and sparse the vanilla-grid candidates.

## Difficulty

`server/.../difficulty/` applies each definition's `difficulty` block over
`settings.json`'s `defaults.difficulty`, read into core's `Difficulty`. Fabric
events only; no mixin.

| Field | What it does |
| --- | --- |
| `mobMultiplier` | A monster-category mob takes `customdimensions:dimension_difficulty` (`add_multiplied_total`, multiplier − 1) the first time it enters any level; 0 or less leaves it unscaled |
| `attributes` | `health`, `damage`, `armor` on unless set false; `speed` (a quarter of the excess) and `knockback` off unless set true |
| `depthScaling` | Multiplies `mobMultiplier` linearly from `minMultiplier` at `startY` (default 64, 1.0) to `maxMultiplier` at `endY` (default −64, 1.5), by the mob's height when it is scaled |
| `playerLuck` | `customdimensions:dimension_luck`, `playerLuck − 1` added to luck, set on join, respawn and every level change, removed in a dimension without it |
| `hostileSpawning: false` | The natural spawner skips the monster category there, and any monster that enters (spawner, egg, command, portal, chunk load) is discarded before it ticks |

A mob is scaled once: the persistent `customdimensions:difficulty_scaled`
attachment marks it, so a chunk reload or a dimension change keeps the scaling
it spawned with (RB-32). A scaled mob starts at full health. Per-dimension spawn
tables are not written: no definition field asks for one, and vanilla's
`gameplay/natural_mob_spawns` attribute cannot make a dimension peaceful (RB-32).

The self-test scales a zombie in the dimension with the highest
`mobMultiplier`, unloads and reloads its chunk, moves it to the overworld, checks
the overworld's depth factor at two heights, runs a peaceful dimension's natural
spawner for 1200 ticks against a 600-tick control with the rule lifted, places a
spawn-egg and a spawner zombie there, and moves a Fabric fake player in and out
to check its luck.

## Portals

`server/.../portal/` runs every portal through one Polymer block,
`customdimensions:portal`, implementing vanilla's `Portal`. Upright on X or Z a
vanilla client sees a Nether portal; flat it sees an End portal. The geometry,
ignition sweep, refusal reasons, arrival-site search, decay and the links file
are game-free in `core/.../portal/`.

| Piece | What it does |
| --- | --- |
| `Ignition` | Fabric's use-block event: igniter on a frame, every definition the item lights a candidate, the closest refusal logged (`portal ignition refused`) |
| `PortalTravel` | The destination: scaled column or anchor, an existing arrival, the far end's own lit portal, or a new arrival at an open or carved site; the way back by exit mode, origin or source column. Every entity crosses; a lazy target opens through `LevelManager` for a player only, and the entity waits in the portal until it is ready (RB-29) |
| `PortalRegistry` | Source and arrival zones and per-cell return targets, saved to `<world>/data/customdimensions/portal_links.json` in the 1.21.1 record format; a world without one reads `config/portal_links.json` |
| `Portals` | Per-level pass: single-use countdown, particles in the portal's colour, frame check (a covered nylium or grass block that decays still counts, T124); a frame break closes both ends |
| `ExitPortals` | `exitPortal`: built near spawn while the level is open, rebuilt when broken |
| `PortalTickets` | Arrival and exit-portal chunk tickets, released before `LevelManager` unloads a level |
| `PortalAuras` | Each end of a link slowly takes on the far side's nature (below) |
| `ClaimsCompat` | Open Parties and Claims' server API by reflection: fail-open, one WARN and off if a lookup throws |

### Auras

The first time a source portal links to an arrival (an existing one, the far
end's own lit portal or a new one), both ends are sampled: the top five solid
blocks, plants, logs as their `worldgen/feature` trees and still fluids in a
9x7x9 box. The source zone keeps the far side's sample and an aura site at the
arrival keeps the source's. An `aura.palette` replaces a sample, and a side
with nothing resident to sample takes its definition's `materials.defaultBlock`.
Nothing loads a chunk to sample.

Passes run from the level's own tick, so a lazy level that is not ticking runs
none and an aura holds no ticket. Every `interval` ticks a pass tries
`blocksPerPass` random cells within `radius` (and 4 below to 5 above), until
the lifetime `budget` is spent: fire at `fireChance`, a fluid only in a hollow
(two units), a configured `aura.trees` feature, a plant, an `aura.conversions`
entry, or the dominant palette block on an exposed solid face. `sides` picks
the ends. Writes use `UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE`.

What an aura may replace is the live `subsume` of the dimension whose nature
leaks (the link's target, at both ends): `none` replaces nothing and adds no
fire or fluid, `natural` (the default) spares `#adventure:aura_protected`, and
`everything` replaces anything. Under every policy it never touches a claimed
chunk, the opening and its neighbours (the frame) of any portal in the level,
a block entity, bedrock or a portal; a source's passes also leave its own
frame materials alone, so a second frame built beside it stands, lit or not. The tag lists crafted blocks and is checked against the 26.3 block list
by `AuraDataTest`. Budgets and palettes are saved with the portal links every
1200 ticks while they change, on exhaustion and at shutdown; 1.21.1 aura fields
and `aura-site-v1` records load.

The self-test's portal matrix lights a bay for every definition's portal in
the overworld, plus a flat bay where the definition allows one, drops an item
through, follows it back, breaks one frame block and checks both ends closed.
The aura checks stand a portal into a `none`, a `natural` and an `everything`
dimension in a cobblestone box with planks and stone set in it, drive 300
seeded passes each, then repeat it in claimed chunks, and check the frames,
the per-pass and lifetime budgets, link sampling and the palette fallback.

## Exits, deaths and wins

`server/.../exit/` runs a definition's `exits`, `deaths` and `wins` through
Fabric's events; the mod has no mixin for them.

| Piece | What it does |
| --- | --- |
| `ExitConditions` | `exits`: `void` (8 blocks below the floor, from the start of the level's tick), `fallFrom` (`minHeight`, default 100), `enderPearl` (the throw is cancelled and the pearl kept), `death`, `death:<damage type>` and `death:mob:<entity>`, most specific first. `teleport` leaves at once and cancels a death, `respawnAt` moves the respawned player once the target is ready, `kill` keeps vanilla's death. A 100-tick cooldown per player stops loops; sky drops arrive with slow falling |
| `DeathRules` | `deaths.inventory` and `deaths.experience`: kept ones are taken in a late allow-death phase and handed back by the copy-from event; dropped ones fall even with `keep_inventory` on. A death a totem will stop, or that another listener cancels, takes nothing |
| `Wins` | Grants `<namespace>:wins/<slug>/<id>` and the root tab, broadcasts the `message` key from `config/messages.json`, sends the player on when `exit` is set. `kill` counts through the advancement's criteria; the other triggers are checked every 10 ticks for players in the level |
| `ExitShrines` (`portal/`) | `exitShrines`: a beacon under a 2x3 frame ring, found on chunk load, registered from the level's tick as a permanent exit zone with the shrine's target, its ring laid in the dimension's frame block; saved with the portal links |
| `ExitHooks` | Registers the events |

Exit targets resolve through `PortalTravel.exitTransition`, the same code
an arrival's exit mode uses. `MessageFile` reads `config/messages.json` under
the server directory once; a missing key or file falls back to built-in
defaults (`portal.no_site`, `win.earned`).

The self-test's exit section uses Fabric's fake player in the fixtures: a void
death with `keep_inventory` on drops everything and respawns in
`selftest_wins`; a death in `selftest_keep` keeps the inventory and level; the
void and `death:lava` rules send the player to world spawn; `the_pillared_void`'s
ender-pearl rule reaches `the_tiled_expanse`; a hand-built shrine registers and
leads to the overworld; each of the six win triggers fires once and never
twice. The persistence pass records a shrine and a granted win and finds both
after the restart.

## Companion feed

`server/.../companion/` streams a portal's far side to clients running the
optional companion mod, the only path for rendered previews (RB-03). A vanilla
client is sent nothing from this package: every payload leaves through
`CompanionNetwork.send`, which refuses any player whose hello did not name this
jar's exact version. The choices are game-free in `core/.../companion/`.

| Piece | What it does |
| --- | --- |
| `CompanionNetwork` | Registers the payloads; admits a client whose `Hello` names this release, serves any other as vanilla; holds each companion's declared view |
| `CompanionFeed` | Every 4 ticks, for each companion whose view draws locally: the nearest lit opening within 16 blocks, its `PortalFrame`, its destination chunks nearest-first through the opening, the entities within 48 blocks of the far portal, and a `ProjectionClear` when the companion walks off, the portal breaks, the view is declined or the destination unloads |
| `FeedPlan` (core) | The chunk order: a filled core round the arrival, then the wedge through the opening, capped at the declared render distance |
| `FeedBudget` (core) | Per companion: 4 chunks a pass and a 256 KiB/s byte bucket holding one second; frames, snapshots and clears always go and are charged |
| `EntitySnapshot` (core) | Up to 24 entities nearest the far portal; a snapshot equal to the last at wire resolution sends nothing |

Reads never load a chunk (T126): chunks come from `getChunkNow`, entities from
the entity sections already held. The one load is the pre-load: while a
companion watches, `PortalTickets` holds the arrival's 5x5 chunks (refreshed
every 100 ticks, released before `LevelManager` unloads the level, T118). A lazy
destination opens only for a companion that draws it, once per approach
(RB-29); after an unload it reopens only when the companion leaves and comes
back. A block change reaches viewers through one mixin on
`ServerChunkCache.blockChanged`, which drops that chunk from every viewer's sent
set. The destination's time needs no payload: 26.3's world clocks are
server-wide and vanilla syncs them to every client.

The self-test's companion section lights a portal into an open lazy dimension
and stands a companion, a vanilla player and a mismatched-version companion at
it, each on a listener recording every custom payload. It checks that only the
companion is fed, within the budget; that live payloads round-trip with the
server's registries; that a block changed on the far side is sent again; that
an item crossing is handed over; that walking away,
breaking the frame and unloading the destination each send a clear; that the
pre-load opens a lazy destination only for a declared companion and does not
reopen one unloaded while the companion stays; and that no lazy level is left
active.

## Wire contract

Channel ids and field order match the 1.21.1 mod's. Bodies marked vanilla are
written by the running game's own codec. The bytes of every channel are pinned in
`server/src/test/resources/companion-fixtures/` (`CompanionWireFixturesTest`;
`-PwriteFixtures` rewrites them), and `mods/custom-dimensions-client-26` keeps a
copy its own codecs must decode and re-encode. The release that built each jar is
the protocol version (`Hello`).

| Channel (`customdimensions:`) | Direction | Fields | When |
| --- | --- | --- | --- |
| `handshake/v2` | client to server | `modVersion` string | Once on join |
| `portal-view/v2` | client to server | `renderLocally` bool, `keepSlab` bool, `maxRenderDistance` varint, `viewDepth` varint | On join and on every change; only `renderLocally` true is fed, `keepSlab` is the client's own setting |
| `preloaded/v1` | server to client | `destination` id | Just before a companion crosses into a destination whose arrival is resident |
| `portal-frame/v1` | server to client | `destination` id, `dimensionType` id, `apertureOrigin` pos, `aperture` varint count + pos, `portalAxis` varint, `normal` varint, `dx` `dy` `dz` int, `skyColor` `fogColor` int RGB (-1 unset) | When a watch starts and whenever the frame changes (the viewer crosses to the other side) |
| `destination-chunk/v1` | server to client | `destination` id, vanilla `ClientboundLevelChunkWithLightPacket` | Up to the budget each pass, until the wedge is sent; again after a block in it changes |
| `destination-entities/v1` | server to client | `destination` id, varint count + vanilla `ClientboundAddEntityPacket`, varint count + vanilla `ClientboundSetEntityDataPacket`, varint count + varint departed ids (each at most 256) | The first pass of a watch, then every 4 ticks when anything moved or left |
| `entity-handover/v1` | server to client | `destination` id, `fromId` varint, `tick` varlong, vanilla `ClientboundAddEntityPacket`, varint count (at most 1) + vanilla `ClientboundSetEntityDataPacket` | The tick a non-player entity crosses into a destination the companion is watching |
| `projection-clear/v1` | server to client | `apertureOrigin` pos | When a watch stops |
