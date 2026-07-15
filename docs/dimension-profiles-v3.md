# v3 per-dimension worldgen profiles (proposal)

Proposed `noiseSettings` / `structureDensity` assignments for all 74
dimensions, for human review BEFORE they are written into
`config/multiverse_config.json` (confirm-before-proceed, AGENTS.md).

Semantics:

- `noiseSettings` unset (`—`) = the type's current generator. For
  overworld-derived types that now means the new global wide tune from
  `config/tectonic.json`; pinning `adventure:wide` freezes that shape against
  consumer overlay overrides, `adventure:compressed` gives tight dramatic
  relief (erosion/ridge 0.45, vertical 1.5).
- `structureDensity` unset (`—`) = normal. `dense` boosts dungeon+loot ~2x
  and landmarks/maritime mildly; `sparse` halves dungeon/loot/landmark
  frequency and thins settlements; peaceful dims (hostileSpawning:false) get
  the automatic no-dungeon overlay regardless.
- EXPERIMENT/FLAG rows apply overworld-derived noise to nether/end dimension
  types: stone-based terrain and water can appear where they normally cannot.
  They exist because the brief asked for rolling nether dims and chaotic
  solid-end dims — drop the flag rows if the look is unwanted; seed rolls
  will measure them either way.

| # | dimension | type | noiseSettings | structureDensity | rationale |
| --- | --- | --- | --- | --- | --- |
| 1 | the_claymarsh | overworld | — | sparse | wetland wilderness — quiet marshes |
| 2 | the_scorched_mesa | overworld | adventure:compressed | — | tight dramatic mesa relief |
| 3 | the_gritlands | overworld | — | sparse | barren gritty flats |
| 4 | the_roothold | overworld | — | — | deep forest, standard density |
| 5 | the_overgrowth | overworld | adventure:compressed | sparse | dense tangled growth, nature reclaimed the ruins |
| 6 | the_greenreach | overworld | adventure:wide | — | pastoral breadbasket — pinned wide (immune to overlay overrides) |
| 7 | the_rosebluff | overworld | adventure:compressed | — | clifftop bluffs |
| 8 | the_greywoods | overworld | — | — | gloomy woodland |
| 9 | the_miredeep | overworld | — | sparse | empty deep mire |
| 10 | the_verdant_hollow | overworld | adventure:wide | — | broad green valleys |
| 11 | the_whitestone_ford | overworld | adventure:wide | — | wide river country; villages boosted by global default |
| 12 | the_needlefall | overworld | adventure:compressed | — | steep pine cliffs |
| 13 | the_chalk_meadows | overworld | adventure:wide | — | rolling chalk downs |
| 14 | the_stonemantle | overworld | adventure:compressed | — | craggy stone country |
| 15 | the_ashgrove | overworld | — | — | ash forest |
| 16 | the_crystal_vale | overworld | adventure:compressed | — | packed crystal valleys |
| 17 | the_darkpine_depths | overworld | — | — | dark forest |
| 18 | the_dripping_pines | overworld | — | — | rainy pine forest |
| 19 | the_ruined_timberland | overworld | — | dense | ruin-strewn woods — more to find |
| 20 | the_shallows | overworld | adventure:wide | — | broad shallow seas |
| 21 | the_lantern_pools | overworld | — | sparse | serene lantern pools |
| 22 | the_dustbowl | overworld | adventure:wide | sparse | vast empty dust plains |
| 23 | the_lost_outpost | overworld | — | dense | outpost-and-ruin theme |
| 24 | the_frozen_strait | overworld | adventure:wide | — | wide frozen channels |
| 25 | the_icebound_rift | void | — | — | void — no worldgen |
| 26 | the_sculked_beyond | void | — | — | void — no worldgen |
| 27 | the_slatemouth | void | — | — | void — no worldgen |
| 28 | the_furnace_halls | nether | — | — | classic nether halls |
| 29 | the_bloodroot_wastes | nether | — | — |  |
| 30 | the_basalt_spires | nether | — | — | basalt drama is native nether |
| 31 | the_blackstone_keep | nether | — | dense | a keep: boosted dungeons |
| 32 | the_molten_flats | nether | — | — |  |
| 33 | the_obsidian_sanctum | nether | — | dense | sanctum: boosted dungeons + loot |
| 34 | the_ember_fields | nether | adventure:wide | — | EXPERIMENT: rolling ember plains — overworld-shaped nether (FLAG: stone base, possible water pools) |
| 35 | the_twisted_groves | nether | — | — |  |
| 36 | the_soulfields | nether | adventure:wide | — | EXPERIMENT: rolling soul meadows (same FLAG as ember_fields) |
| 37 | the_blighted_maw | nether | — | dense | hard dim (task list): boosted dungeons + loot |
| 38 | the_teal_corruption | nether | — | — |  |
| 39 | the_weeping_vault | nether | — | dense | hard dim (task list) |
| 40 | the_boneyard | nether | — | dense | hard dim (task list) |
| 41 | the_buried_age | nether | — | — |  |
| 42 | the_luminous_caverns | nether | — | sparse | serene glowing caves |
| 43 | the_fungal_lanterns | nether | — | sparse | serene fungal groves |
| 44 | the_gilded_pit | nether | — | dense | gilded: loot-theme boost |
| 45 | the_wailing_narrows | nether | — | — |  |
| 46 | the_forged_depths | nether | — | dense | hard dim (task list) |
| 47 | the_glacial_drift | overworld | adventure:wide | sparse | empty glacial wastes |
| 48 | the_sunken_temple | overworld | — | dense | temple-hunting dimension |
| 49 | the_snowbound_isle | overworld | — | — | snowy island |
| 50 | the_abyssal_shrine | overworld | — | dense | ocean monuments and shrines |
| 51 | the_pale_reach | overworld | — | sparse | pale desolate reach |
| 52 | the_violet_spire | overworld | adventure:compressed | — | dramatic spires |
| 53 | the_amplified_reaches | amplified | — | — | keeps amplified preset |
| 54 | the_underdark | multi_biome | — | dense | hard dim (task list): deep-dark caves |
| 55 | the_canvas | superflat | — | peaceful (auto) | superflat, peaceful; flat generator is already structure-free |
| 56 | the_blossom_gardens | multi_biome | adventure:wide | peaceful (auto) | peaceful (hostileSpawning:false -> auto no-dungeon overlay); wide pastoral |
| 57 | the_whispering_wilds | multi_biome | — | sparse | untouched wilderness |
| 58 | the_sun_kingdoms | multi_biome | adventure:wide | — | vast desert kingdoms |
| 59 | the_frozen_hearth | multi_biome | adventure:wide | — | wide tundra |
| 60 | the_highland_crossing | multi_biome | adventure:compressed | — | mountain drama |
| 61 | the_crucible | multi_biome | adventure:compressed | dense | hard dim (task list): claustrophobic jungle gauntlet |
| 62 | the_gauntlet | multi_biome | adventure:compressed | dense | hard dim (task list): brutal peaks |
| 63 | the_crimson_nexus | multi_biome | — | — | nether biomes on overworld noise is already its character |
| 64 | the_souldrift | multi_biome | — | — |  |
| 65 | the_shattered_skies | sky_islands | — | — | end-noise floating islands ARE the character; MSS sky sets are custom-placement (unscalable) |
| 66 | the_burning_archipelago | nether_islands | — | — | nether islands keep end-noise shape |
| 67 | the_endless_expanse | large_biomes | — | — | keeps large_biomes preset |
| 68 | the_end_citadel | end | — | dense | citadel: end cities + phantom citadels boosted |
| 69 | the_tiled_expanse | end | — | — |  |
| 70 | the_pillared_void | end | — | sparse | minimal, lonely pillars |
| 71 | the_catalyst_maw | end | adventure:compressed | dense | EXPERIMENT: chaotic solid landmass in the end + saturated structures (FLAG: water seas in the end) |
| 72 | the_crumbling_reaches | end | adventure:compressed | dense | EXPERIMENT: same as catalyst_maw |
| 73 | the_red_monument | end | — | — |  |
| 74 | the_fractured_halls | end | — | dense | halls: boosted end dungeons |

Totals: 25 dimensions pin a noise preset (13 wide,
12 compressed), 30 set a structure density
(18 dense, 12 sparse), the rest keep
type defaults. Seeds are NOT changed here — Phase 3 rolls new per-dimension
seeds for every row whose terrain or structure profile changed, and a human
locks the winners.
