# TROUBLESHOOTING.md at the switch

A list, not an edit. Classified from each entry's heading and first lines (`ts-first.txt` in the evidence) against what the switch deletes: the 1.21.1 mod (`mods/custom-dimensions/`), its v1 schema and tools, and the mods the register cuts. Retired ids join AGENTS.md's retired list and are never reused.

## Retire

| Id | Why it stops applying |
| --- | --- |
| T18 | `CreateWorldsMixin` and the post-boot `createWorlds` filter are the 1.21.1 mod's; 26.3 levels are lazy Fantasy levels |
| T19 | `DimensionManager`'s biome source is the 1.21.1 mod's; v2 biome lists compile to a layout with no overlaps (`BiomeLayout`) |
| T24 | `StructureWeightSampler` callbacks were a 1.21.1 mixin target |
| T25 | Third-party HEAD cancels on the 1.21.1 forced-start path; 26.3 forced starts are one-chunk sets in structure control |
| T27 | `KernelDensity` fill kernels are the 1.21.1 mod's |
| T32 | `CandidateRender` thumbnails and the seed viewer are the 1.21.1 roller's |
| T34 | A region-injecting mod repaints dimensions: the firewall refuses such a mod at start |
| T35 | `config/custom-dimensions/extractors/` catalogues and `check-content-coverage.py` (deleted) |
| T36 | `sky_islands`/`nether_islands` are v1 types; v2 has library terrains |
| T37 | `MultiverseConfig.getCustomDimensions()` as the 1.21.1 runtime used it |
| T38 | The 1.21.1 boot line's biome-source counts |
| T39 | The 1.21.1 `Scorecard.percentage()` tiers; the 26.3 scorer is roller.md's |
| T40 | `seedRoll.water` and `settingsOverrides.seaLevel` are v1 fields |
| T51 | Noise-managed placement as the 1.21.1 structure model computed it |
| T52 | Site count vs pool variety in the 1.21.1 model (`/customdim site-validity`) |
| T55 | BetterEnd's dense features absorbed into the 1.21.1 structure pool; BetterEnd is cut |
| T56 | Placement-time rules on the 1.21.1 site model |
| T58 | `check-band-reach.py` (deleted) and the climate grid it read |
| T60 | `_thumb.json` and the candidate bank (deleted with the 1.21.1 roller) |
| T62 | The 1.21.1 measurement bank |
| T64 | `customdim` measurements over RCON on the 1.21.1 main thread; 26.3 render, roll and survey run off it ([T120](#t120) holds the 26.x rule) |
| T65 | `check-biome-bands.py` (deleted) |
| T66 | `extractors/biomes.json` from optional built-in datapacks |
| T69 | The boot line's `mixed-in` field |
| T70 | `InputHash` of the 1.21.1 mod's classes; the 26.3 pack hashes its inputs (`inputs.sha256`) |
| T71 | Clamp rails in `check-biome-bands.py` (deleted) |
| T72 | `sample-noise` and `sample-climate-grid.sh` (deleted) |
| T73 | The 1.21.1 `Scorer` ceiling |
| T74 | Once-per-boot lines of the 1.21.1 mod (`biome source built`) |
| T75 | BetterEnd's `TerrainGenerator` mixin; the BetterX family is cut |
| T76 | End-settings depth windows of the 1.21.1 biome bands |
| T79 | Better Caves' aquifer cast; YUNG's caves is cut |
| T82 | BetterEnd under Sodium; BetterEnd is cut |
| T84 | Better Caves' carving floor; cut |
| T114 | `/customdim load` and `execute in` writes; the command does not exist on 26.3 |
| P2 | `LEVEL_TYPE=flat` and the 1.21.1 `createDimensionOptions` template |
| D3 | The `level.dat` scrub: Fantasy never writes its level stems to `world_gen_settings.dat` (mod README § Runtime levels); `customdim delete` removes a level |
| K7 | `config/custom-dimensions/climate-axes.json` (deleted) |
| K12 | Six Serilum configs unread on 1.21.1; on 26.3 [T136](#t136) applies |

## Reword

| Id | What changes |
| --- | --- |
| D2 | The creation-time fields are v2's (`generator`, `terrain`, `biomes`, `biomeLayout`, `zones`, `liquids`, `materials`, `carvers`, `ores`, `dimensionType`, `seed`), and the pack logs one WARN naming D2 when one changes under existing chunks; the `registerDimensions`/`level.dat` mechanism is the 1.21.1 one |
| D6 | The 26.3 mod has no `C2meConfigPatch`; whether c2me's density-function compiler still has to stay off on 26.3 is unmeasured — measure in the rehearsal, then reword or retire |
| D8 | Rewritten by `edits.py` step `webmap`: generated layers for every dimension from its generator, the explored layer only once it has saved chunks |
| D9 | `level.dat` growth came from the 1.21.1 mod inlining generators; check whether the base worlds' pack entries can grow it on 26.3, then reword or retire |
| T13 | Rewritten by `edits.py` step `webmap`: the mod's export into `data/map-web`, `MAP_INTERVAL`, `./ops map` |
| T31 | Seed per dimension is v2's `seed` (or `"env"`); the `ServerWorldSeedMixin` wording goes |
| T33 | `structures.force` over a void: re-measure against 26.3 forced starts, which skip the biome check only |
| T48 | Moog's sets now reach the world through bridges and structure control; re-measure the spacing claim |
| T53 | Still the rule 26.3 forced starts follow (worldgen.md § Structure control cites it); drop the 1.21.1 measurement's wording |
| T54 | Still applies; point at `-PmeasureStructures` and the mod's `structure-sizes.json` |
| T59 | `ParameterRange` ties: v2 layouts carve overlaps away, so the tie can only arise in a hand-written climate box |
| T109 | A consumer dimension's namespace: the 26.3 namespace is `settings.json`'s, and consumer definitions have no route yet |
| T85 | Copper frames: check against 26.3 portal frames (Polymer block) |
| T91–T116 | The 1.21.1 companion's renderer (`WorldRenderer`, Yarn names). Review with the 26.3 companion's owner: keep what its renderer shares, retire the rest |
| T178 | "A linked 26.x consumer refuses the 1.21.1 dimension files": still true for a consumer whose `data/config/` predates the switch; reword around `./dev refresh-config` |
| T177 | `.minecraft-version` in a linked checkout: drop the 1.21.1 example |

Everything else (T1–T12, T14–T17, T30, T41–T47, T49, T50, T57, T61, T63, T67, T68, T77, T78, T80, T83, T87, T88, T90, T117–T215, P1, P3–P6, K1, K2, K5, K6, K9, K17) applies after the switch as written, so far as its first lines show.
