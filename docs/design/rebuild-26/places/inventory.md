# Structure inventory

Every structure the 1.21.1 dimension configs name, by theme, with what the 26.3 pack has for it and which dimensions want it. The gaps at the end of each theme are the input to [set-pieces.md](set-pieces.md).

Sources: the 82 configs in `config/custom-dimensions/dimensions/` (`structures.force`, `terrainAdaptation`, `rarity` and `wants` keys, and `seedRoll.wants`), short names resolved through `structure_aliases.json`; availability from `namespaces.csv`; stand-ins from `definitions/id-mapping.csv`; the 26.3 catalogue from the reviewed bridges (`mods/custom-dimensions-26/bridges/`) and vanilla 26.3's `worldgen/structure`. A dimension listed under "Wanted by" names the id in its config; `the_aviary` is 1.21.1's `paradise_lost`.

## The numbers

| Measure | Count |
| --- | --- |
| Structures the 26.3 pack registers | 586: 534 from nine structure mods through their bridges, 52 vanilla; every one sized in `structure-sizes.json` |
| Entries in `structure_themes.json` (sets and structures) | 426: 289 in a namespace the 26.3 pack has (`adventure:exit_shrines` included), 137 in one it does not |
| Distinct structures the 1.21.1 configs name | 169: 63 kept, 106 lost |
| Lost ones with a stand-in in `id-mapping.csv` | 106 of 106 |
| Stand-ins that are a different thing (weak) | 12 |

The pack lost no theme outright: every structure a 1.21.1 config named has a 26.3 stand-in that places today. What it lost is the stories the descriptions tell that no structure mod ever had, and twelve stand-ins that are a different thing (a forge for a reactor, a bridge for a pipeline, a skull for a dragon's skeleton). Those are what set pieces are for.

Each theme's story gaps follow its table. The last column counts the ones that need a set piece; the rest are met by placement, a kept structure, explorify or an owner's decision.

| Theme | Named | Kept | Lost, close stand-in | Lost, weak stand-in | Dimensions wanting it | Gaps needing a set piece |
| --- | --- | --- | --- | --- | --- | --- |
| Ruins | 18 | 2 | 15 | 1 | 25 | 5 |
| Dungeons | 28 | 9 | 19 | 0 | 29 | 2 |
| Towers and keeps | 30 | 7 | 23 | 0 | 29 | 2 |
| Villages and camps | 16 | 4 | 9 | 3 | 31 | 5 |
| Nether | 36 | 17 | 14 | 5 | 28 | 10 |
| End | 20 | 15 | 4 | 1 | 10 | 5 |
| Sky | 9 | 4 | 3 | 2 | 10 | 4 |
| Underground | 5 | 2 | 3 | 0 | 18 | 2 |
| Ocean | 7 | 3 | 4 | 0 | 6 | 1 |
| **Total** | **169** | **63** | **94** | **12** | | **36** |

## Ruins

26.3 has more ruins than 1.21.1 named: `mvs:small_ruin`, `log_ruin`, `statue_ruins`, `castle_ruins`; `nova_structures:wild_ruin`, `ruin_town`, `desert_ruins`, `jungle_ruins` and 34 `remnant_*` pieces (graveyards, farms, smithies, a sawmill, a school); `ati_structures:haunted_ruin`, `stray_ruins`, `ruined_castle`, `old_home`; `minecraft:trail_ruins` and the ruined portals.

| 1.21.1 id | On 26.3 | Wanted by |
| --- | --- | --- |
| `adventuredungeons:ruins_desert` | lost; `nova_structures:desert_ruins` | the_scorched_mesa |
| `adventuredungeons:ruins_standard` | lost; `nova_structures:wild_ruin` | the_claymarsh, the_ruined_timberland, the_souldrift |
| `explorify:mausoleum` | lost; `nova_structures:remnant_shrine_sentinel_mass_grave` | the_greywoods, the_miredeep, the_ruined_timberland, the_souldrift |
| `minecraft:ruined_portal` | kept | overworld, the_burning_archipelago, the_endless_expanse |
| `minecraft:ruined_portal_mountain` | kept | the_gritlands |
| `philipsruins:ancient_ruins` | lost; `minecraft:trail_ruins` | the_lantern_pools, the_souldrift |
| `philipsruins:badlands_structures` | lost; `nova_structures:badlands_miner_outpost` | the_scorched_mesa |
| `philipsruins:field_stone_ruins` | lost; `nova_structures:wild_ruin` | the_ashgrove, the_gritlands |
| `philipsruins:pumpkin_ruins` | lost; stand-in `nova_structures:remnant_ruin_farmer` (weak) | the_ashgrove, the_ruined_timberland |
| `structory:abandoned_chapel` | lost; `ati_structures:haunted_ruin` | the_ashgrove, the_blossom_gardens, the_chalk_meadows, the_crystal_vale, the_greywoods, the_lantern_pools, the_rosebluff, the_verdant_hollow |
| `structory:dense_forest_ruin` | lost; `mvs:log_ruin` | the_roothold, the_ruined_timberland, the_whispering_wilds |
| `structory:graveyard` | lost; `nova_structures:remnant_graveyard` | the_ashgrove, the_claymarsh, the_darkpine_depths, the_greywoods, the_miredeep, the_ruined_timberland, the_souldrift |
| `structory:jungle_ruin` | lost; `nova_structures:jungle_ruins` | the_crucible, the_overgrowth |
| `structory:northern_ruin` | lost; `mvs:small_ruin` | the_dripping_pines, the_needlefall, the_whispering_wilds |
| `structory:old_manor` | lost; `ati_structures:manor` | the_darkpine_depths, the_greywoods, the_miredeep, the_roothold, the_ruined_timberland, the_whispering_wilds |
| `structory:ruin_grassy` | lost; `mvs:small_ruin` | the_ashgrove, the_wuthering_wisteria |
| `structory:swamp_ruin` | lost; `mvs:small_swamp_house` | the_claymarsh, the_greywoods, the_miredeep |
| `structory:taiga_ruin_surface` | lost; `nova_structures:remnant_taiga_castle` | the_needlefall, the_whispering_wilds |

Story gaps:

1. **The burned settlement** (the_ashgrove): ruins that burned, charcoal and ash, not merely old. `camp` and `hovel` with `state: burned`.
2. **Half-sunk in the clay** (the_claymarsh, the_miredeep): ruins tilted and swallowed by mud. `template` with `tilt` and `embed`.
3. **Pompeii, still cooking** (the_buried_age): houses buried in lava rock with only their tops showing, archaeology inside. `template` of `minecraft:trail_ruins/*` with `embed`, `archaeology`, and a `field` of magma and basalt.
4. **The unfinished harvest festival** (the_ruined_timberland): pumpkin ruins with the stalls still up. `scatter` of carved pumpkins, hay and stalls round a `camp` (`abandoned`); stands in for the weak `philipsruins:pumpkin_ruins` stand-in.
5. **The only evidence** (the_tidepools, the_gritlands): one wreck or one ruin and nothing else. Placed by `force`; no new kind.
6. **Field graves** (the_soulfields, the_greywoods): graves thickening towards the border. `scatter` of headstones under `radial`.

## Dungeons

26.3 has every 1.21.1 dungeon theme: Epic Structures' twelve (plains, sand and ice at three sizes, obelisks), Deadly Deadly Dungeon's nine (major and minor in three climates, a spider lair, a monolithic tower, a wizard tower), Dungeons and Taverns' crypts, catacomb, `surface_dungeon_*` and `trial_dungeon`s, ATi's nests, grottoes and rotting temples, and vanilla's trial chambers, mansion and pyramids.

| 1.21.1 id | On 26.3 | Wanted by |
| --- | --- | --- |
| `adventuredungeons:coldlair` | lost; `epic:small_ice_dungeon` | the_dripping_pines, the_stonemantle, the_underdark |
| `adventuredungeons:murkydungeon` | lost; `nova_structures:small_creeping_crypt` | the_crucible, the_overgrowth, the_underdark |
| `betterdungeons:skeleton_dungeon` | lost; `nova_structures:small_undead_crypt` | the_boneyard, the_greywoods, the_ruined_timberland |
| `betterdungeons:spider_dungeon` | lost; `ati_structures:spider_nest` | the_darkpine_depths, the_roothold |
| `betterdungeons:zombie_dungeon` | lost; `epic:small_plains_dungeon` | the_darkpine_depths, the_greywoods, the_gritlands |
| `betterdungeons:zombie_dungeons` | lost; `epic:small_plains_dungeon` | the_darkpine_depths |
| `dungeons_arise:abandoned_temple` | lost; `ati_structures:rotting_temple` | the_ruined_timberland |
| `dungeons_arise:infested_temple` | lost; `mtr:jungle_temple` | the_crucible, the_overgrowth |
| `dungeons_plus:cold_dungeon` | lost; `nova_structures:surface_dungeon_snowy` | the_frozen_hearth, the_needlefall, the_snowbound_isle |
| `dungeons_plus:dusty_tomb` | lost; `epic:small_sand_dungeon` | the_ashgrove, the_dustbowl, the_scorched_mesa, the_souldrift, the_sun_kingdoms |
| `dungeons_plus:frozen_dungeon` | lost; `epic:small_ice_dungeon` | the_frozen_hearth, the_frozen_strait, the_pale_reach, the_snowbound_isle |
| `dungeons_plus:infested_dungeon` | lost; `ati_structures:silverfish_nest` | the_roothold, the_underdark |
| `dungeons_plus:lush_dungeon` | lost; `ati_structures:jungle_grotto` | the_crucible, the_luminous_caverns, the_overgrowth, the_roothold |
| `dungeons_plus:mouldy_dungeon` | lost; `ati_structures:rotting_temple_underground` | the_dripping_pines, the_roothold |
| `dungeons_plus:muddy_dungeon` | lost; `nova_structures:surface_dungeon_swamp` | the_claymarsh, the_miredeep |
| `dungeons_plus:scorched_tomb` | lost; `nova_structures:surface_dungeon_desert` | the_ashgrove, the_souldrift |
| `dungeons_plus:webbed_dungeon` | lost; `ati_structures:arachnid_dwelling` | the_crucible, the_overgrowth |
| `epic:large_dungeons` | kept | the_gauntlet |
| `epic:large_ice_dungeon` | kept | the_frozen_hearth, the_gauntlet |
| `epic:large_sand_dungeon` | kept | the_sun_kingdoms |
| `epic:medium_ice_dungeon` | kept | the_frozen_strait |
| `minecraft:desert_pyramid` | kept | the_endless_expanse, the_sun_kingdoms |
| `minecraft:mansion` | kept | overworld, the_darkpine_depths, the_endless_expanse, the_overgrowth, the_roothold |
| `minecraft:trial_chambers` | kept | overworld, the_crucible, the_gauntlet, the_gilded_pit, the_sculked_beyond, the_underdark |
| `nova_structures:creeping_crypt` | kept | the_darkpine_depths, the_roothold |
| `nova_structures:undead_crypt` | kept | the_darkpine_depths |
| `philipsruins:ancient_crypt` | lost; `nova_structures:undead_crypt` | the_ruined_timberland |
| `philipsruins:antiquus_crypta` | lost; `nova_structures:undead_crypt` | the_gilded_pit |

Story gaps:

1. **Spawner pits** (the_boneyard: skeletons, ghasts, bats): a dungeon that is a hole in the ground, not a room. `pit` with `spawner`s.
2. **The sealed thing** (the_weeping_vault): crying obsidian round something still inside. `cell` with crying-obsidian `bars` and a `mob` that is not let out.

## Towers and keeps

26.3 has ATi's keeps (`dark_keep`, `small_keep`, `wither_keep`, `woodland_keep`, `deepslate_keep`), forts and castles, eleven `nova_structures:firewatch_tower_*`, `lone_citadel`, `stray_fort`, `stray_outlook`, MVS's cartographer, diorite, red and jungle towers, MSS's arena and castle tower, and the towns-and-towers pillager outposts in fifteen styles.

| 1.21.1 id | On 26.3 | Wanted by |
| --- | --- | --- |
| `dungeons_arise:bandit_towers` | lost; `ati_structures:illager_tower` | the_crimson_nexus, the_lost_outpost |
| `dungeons_arise:coliseum` | lost; `mss:arena` | overworld, the_endless_expanse, the_gauntlet |
| `dungeons_arise:illager_fort` | lost; `ati_structures:old_fort` | the_lost_outpost |
| `dungeons_arise:keep_kayra` | lost; `ati_structures:dark_keep` | the_amplified_reaches, the_crimson_nexus, the_gauntlet, the_gilded_pit, the_highland_crossing, the_sun_kingdoms |
| `dungeons_arise:mining_complex` | lost; `ati_structures:quarry` | the_emberglass_foundry |
| `dungeons_arise:scorched_mines` | lost; `nova_structures:badlands_miner_outpost` | the_crimson_nexus, the_gauntlet, the_stonemantle |
| `explorify:badlands_pyramid` | lost; `mtr:badlands_temple` | the_scorched_mesa |
| `explorify:black_spiral` | lost; `ati_structures:dark_tower` | the_scorched_mesa, the_souldrift |
| `explorify:desert_shrine` | lost; `epic:sand_obelisk` | the_lantern_pools, the_sun_kingdoms |
| `explorify:watchtower/plains` | lost; `nova_structures:firewatch_tower_forest` | overworld, the_endless_expanse, the_greenreach, the_highland_crossing, the_sun_kingdoms, the_whitestone_ford |
| `friendsandfoes:citadel` | lost; `nova_structures:stray_fort` | the_icebound_rift |
| `minecraft:pillager_outpost` | kept | overworld, the_endless_expanse, the_lost_outpost |
| `minecraft:pillager_outposts` | kept | the_lost_outpost |
| `mss:arena` | kept | the_shattered_skies |
| `nova_structures:lone_citadel` | kept | the_gauntlet |
| `nova_structures:shrine_tower` | kept | the_crystal_vale, the_rosebluff, the_violet_spire |
| `nova_structures:stray_fort` | kept | the_needlefall |
| `structory:firetower` | lost; `nova_structures:firewatch_tower_taiga` | the_amplified_reaches, the_frozen_hearth, the_highland_crossing, the_needlefall, the_stonemantle, the_whispering_wilds |
| `structory_towers:ancient_temple` | lost; `ati_structures:ancient_temple` | the_amplified_reaches, the_crimson_nexus, the_stonemantle |
| `structory_towers:engineer_tower` | lost; `ati_structures:steam_house` | the_amplified_reaches, the_highland_crossing, the_stonemantle |
| `structory_towers:foraging_outpost` | lost; `ati_structures:overgrown_outpost` | the_dripping_pines, the_needlefall, the_whispering_wilds |
| `structory_towers:lighthouse` | lost; `ati_structures:lighthouse` | the_blossom_gardens, the_frozen_strait, the_greenreach, the_highland_crossing, the_shallows, the_whitestone_ford |
| `structory_towers:mirage_outpost` | lost; `ati_structures:desert_outpost` | the_lantern_pools, the_sun_kingdoms |
| `structory_towers:nomad_outpost` | lost; `ati_structures:nomadic_camp` | the_amplified_reaches, the_highland_crossing, the_lost_outpost, the_sun_kingdoms |
| `structory_towers:pillager_lookout` | lost; `mvs:small_pillager_tower` | the_lost_outpost |
| `structory_towers:quarter_outpost` | lost; `ati_structures:small_keep` | the_stonemantle |
| `structory_towers:sacred_relic_temple` | lost; `ati_structures:fortified_temple` | the_crystal_vale, the_violet_spire |
| `structory_towers:taiga_outpost` | lost; `towns_and_towers:pillager_outpost_taiga` | the_frozen_hearth, the_highland_crossing, the_needlefall |
| `structory_towers:wizard_tower` | lost; `ddd:wizard_tower` | the_amplified_reaches, the_crimson_nexus, the_crystal_vale, the_gauntlet, the_violet_spire |
| `towns_and_towers:exclusives/pillager_outpost_oriental` | kept | the_wuthering_wisteria |

Story gaps:

1. **Guide posts on the passes** (the_highland_crossing, the_frozen_hearth and seven others): the lanterns that stand in for explorify's guide posts are not signposts. explorify ([explorify.md](explorify.md)), else a `column` with signs.
2. **Keeps on the peaks** (the_amplified_reaches, the_needlefall): a keep that stands on a summit, not in a valley. Placement, not a new kind; how a want prefers height is a question in the [README](README.md).
3. **The quarantine wall** (the_wailing_narrows): a wall across the chasms that did not hold. `wall` with `breaches`.

## Villages and camps

26.3 has villages in 28 towns-and-towers styles (the piglin village among them), Dungeons and Taverns' villages, taverns in thirteen woods, hamlet, illager camps, barracks and manors, `piglin_camp`, `piglin_camp_collony`, `parched_camp`, `bogged_camp`, `stray_camp`, MVS's campsites, farms, windmills and stalls, ATi's nomadic camp, ice tribe and jungle settlement, and vanilla's eighteen abandoned camps.

| 1.21.1 id | On 26.3 | Wanted by |
| --- | --- | --- |
| `#minecraft:village` | kept | overworld, the_blossom_gardens, the_chalk_meadows, the_endless_expanse, the_frozen_hearth, the_greenreach, the_rosebluff, the_sun_kingdoms, the_verdant_hollow, the_whitestone_ford |
| `dungeons_arise:bandit_village` | lost; `nova_structures:illager_barracks` | the_lost_outpost, the_sun_kingdoms |
| `dungeons_arise:illager_campsite` | lost; `nova_structures:illager_camp` | the_lost_outpost |
| `dungeons_arise:jungle_tree_house` | lost; `nova_structures:illager_treehouse` | the_crucible, the_overgrowth |
| `dungeons_arise:mushroom_house` | lost; `mvs:large_mushroom` | the_blossom_gardens, the_overgrowth, the_verdant_hollow |
| `explorify:campsite` | lost; `mvs:campsite` | overworld, the_amplified_reaches, the_blossom_gardens, the_chalk_meadows, the_frozen_hearth, the_greenreach, the_highland_crossing, the_lantern_pools, the_needlefall, the_verdant_hollow, the_whispering_wilds, the_whitestone_ford, the_wuthering_wisteria |
| `explorify:dark_forest_settlement` | lost; `nova_structures:hamlet` | the_darkpine_depths, the_greywoods |
| `explorify:dark_forest_settlements` | lost; `nova_structures:hamlet` | the_darkpine_depths |
| `explorify:farmstead` | lost; stand-in `nova_structures:remnant_ruin_farmer` (weak) | overworld, the_blossom_gardens, the_chalk_meadows, the_dustbowl, the_endless_expanse, the_greenreach, the_verdant_hollow, the_whitestone_ford |
| `explorify:guide_post_cold` | lost; stand-in `mvs:small_spruce_lantern` (weak) | the_frozen_hearth, the_frozen_strait, the_highland_crossing, the_snowbound_isle |
| `explorify:guide_post_warm` | lost; stand-in `mvs:small_oak_lantern` (weak) | the_blossom_gardens, the_chalk_meadows, the_crystal_vale, the_greenreach, the_lantern_pools, the_rosebluff, the_shattered_skies, the_verdant_hollow, the_whitestone_ford |
| `explorify:mangrove_hut` | lost; `nova_structures:mangrove_witch_hut` | the_claymarsh, the_miredeep |
| `minecraft:igloo` | kept | the_frozen_hearth, the_frozen_strait, the_glacial_drift, the_snowbound_isle |
| `nova_structures:tavern_birch` | kept | overworld, the_blossom_gardens, the_chalk_meadows, the_endless_expanse, the_greenreach, the_verdant_hollow, the_whitestone_ford |
| `structory:abandoned_camp` | lost; `minecraft:abandoned_camp_forest` | the_ashgrove, the_claymarsh, the_dripping_pines, the_miredeep, the_overgrowth, the_shattered_skies, the_whispering_wilds |
| `towns_and_towers:village_flower_forest` | kept | the_wuthering_wisteria |

Story gaps:

1. **The last farmstead** (the_dustbowl): one living holdout, not a ruin. explorify's `farmstead`, else `hovel` with a `farm`.
2. **Desolated camps** (the_buried_age, the_boneyard, the_whispering_wilds, the_dripping_pines): tents fallen, fires cold. `camp` with `state: abandoned`.
3. **The pacifist piglins' hovels and farms** (the_ember_fields): nether-wart and fungus farming, homes and hovels. `farm` and `hovel` beside the piglin village.
4. **Strewn remnants of a piglin camp** (the_boneyard, owner: perhaps elsewhere): `scatter` of a burned `camp`'s parts.
5. **A witch hut** (the_boneyard, owner: perhaps elsewhere): kept out of the boneyard by the owner's "only the non-living survived"; `nova_structures:mangrove_witch_hut` or `witch_villa` where it fits.
6. **Barracks and shipping yards** (the_molten_flats): `hovel` rows, a `platform` loading yard and `gate`s left broken.

## Nether

26.3 has MNS's 52 (forges, wells, walls, pillars, bridges, the mega fortress, arenas, a train, houses, a wart farm, shrines, skulls), Dungeons and Taverns' Nether set (`nether_keep`, `nether_port`, `piglin_donjon`, `piglin_outstation`, `sealing_halls`, `toxic_lair`, eight skeleton camps and towers), MTR's nether temple, ATi's wither keep and cavern, and vanilla's fortress, bastion, nether fossils and Nether ruined portals.

| 1.21.1 id | On 26.3 | Wanted by |
| --- | --- | --- |
| `ati_structures:wither_cavern` | kept | the_roothold |
| `betterdungeons:small_nether_dungeon` | lost; `mns:small_nether_brick` | the_boneyard, the_weeping_vault |
| `betterfortresses:fortress` | lost; `mns:mega_fortress` | the_blighted_maw, the_bloodroot_wastes, the_burning_archipelago, the_catalyst_maw, the_ember_fields, the_forged_depths, the_furnace_halls, the_gilded_pit, the_molten_flats, the_nether, the_wailing_narrows |
| `dungeons_arise:foundry` | lost; `mns:crimson_forge` | the_emberglass_foundry, the_gilded_pit |
| `dungeons_arise:shiraz_palace` | lost; stand-in `nova_structures:piglin_donjon` (weak) | the_gilded_pit |
| `incendium:abandoned_tower` | lost; `mns:nether_tower` | the_basalt_spires, the_blackstone_keep, the_buried_age, the_burning_archipelago, the_gilded_pit, the_molten_flats, the_weeping_vault |
| `incendium:forbidden_castle` | lost; `nova_structures:nether_keep` | the_blackstone_keep, the_gilded_pit, the_obsidian_sanctum, the_weeping_vault |
| `incendium:infernal_altar` | lost; `mns:smoking_shrine` | the_boneyard, the_ember_fields, the_obsidian_sanctum, the_weeping_vault |
| `incendium:nether_reactor` | lost; stand-in `mns:crimson_forge` (weak) | the_blighted_maw, the_catalyst_maw, the_forged_depths, the_furnace_halls, the_molten_flats |
| `incendium:piglin_village` | lost; `towns_and_towers:exclusives/village_piglin` | the_ember_fields, the_nether, the_teal_corruption |
| `incendium:pipeline` | lost; stand-in `mns:bridge_1` (weak) | the_basalt_spires, the_blighted_maw, the_bloodroot_wastes, the_buried_age, the_furnace_halls, the_molten_flats |
| `incendium:ruined_lab` | lost; stand-in `mns:copper_tower` (weak) | the_blighted_maw, the_bloodroot_wastes, the_catalyst_maw, the_forged_depths, the_molten_flats |
| `incendium:sanctum` | lost; `nova_structures:sealing_halls` | the_gilded_pit, the_nether, the_obsidian_sanctum, the_weeping_vault |
| `minecraft:bastion_remnant` | kept | the_basalt_spires, the_blackstone_keep, the_blighted_maw, the_burning_archipelago, the_catalyst_maw, the_forged_depths, the_gilded_pit, the_nether, the_pillared_void, the_teal_corruption, the_weeping_vault |
| `minecraft:fortress` | kept | the_crimson_nexus |
| `minecraft:ruined_portal_nether` | kept | the_pillared_void |
| `mns:bridge_1` | kept | the_ember_fields, the_fungal_lanterns, the_nether, the_teal_corruption, the_wailing_narrows |
| `mns:copper_tower` | kept | the_blackstone_keep, the_buried_age, the_furnace_halls, the_luminous_caverns, the_molten_flats |
| `mns:crimson_forge` | kept | the_basalt_spires, the_bloodroot_wastes, the_burning_archipelago, the_crimson_nexus, the_forged_depths, the_gilded_pit, the_teal_corruption |
| `mns:crimson_lava_well` | kept | the_bloodroot_wastes, the_ember_fields, the_molten_flats |
| `mns:giant_skull` | kept | the_boneyard, the_burning_archipelago, the_soulfields, the_wailing_narrows |
| `mns:grave_yard` | kept | the_boneyard, the_soulfields |
| `mns:large_blackstone_pillars` | kept | the_basalt_spires, the_blackstone_keep, the_wailing_narrows |
| `mns:large_blackstone_walls` | kept | the_blackstone_keep, the_furnace_halls, the_gilded_pit |
| `mns:large_nether_brick` | kept | the_furnace_halls |
| `mns:medium_crimson_fungus` | kept | the_bloodroot_wastes, the_ember_fields, the_fungal_lanterns, the_twisted_groves |
| `mns:mega_fortress` | kept | the_furnace_halls |
| `mtr:nether_temple` | kept | the_obsidian_sanctum |
| `nova_structures:skeleton_camp_soul` | kept | the_boneyard |
| `philipsruins:bone_dungeon` | lost; `nova_structures:catacomb` | the_boneyard, the_sculked_beyond, the_soulfields, the_underdark |
| `philipsruins:lost_soul_dungeon` | lost; `nova_structures:nether_skeleton_tower_soul` | the_soulfields |
| `philipsruins:nether_lava_ruins` | lost; `mns:large_nether_brick` | the_buried_age |
| `philipsruins:start_nether_ruin` | lost; `mns:ruined_portal` | the_buried_age |
| `structory_towers:nether/strange_outpost` | lost; stand-in `mns:warped_dome` (weak) | the_fungal_lanterns, the_luminous_caverns, the_teal_corruption, the_twisted_groves |
| `structory_towers:nether/warped_outpost` | lost; `mvs:large_warped_tower` | the_burning_archipelago, the_fungal_lanterns, the_teal_corruption, the_twisted_groves |
| `structory_towers:warped_greatsword` | lost; `mns:sword` | the_basalt_spires, the_fungal_lanterns, the_teal_corruption, the_twisted_groves |

Story gaps:

1. **The reactor** (the_forged_depths, the_molten_flats, the_blighted_maw, the_catalyst_maw, the_furnace_halls): a stand-in forge is not a reactor. `works` round a quench `pit` with chimneys.
2. **The pipeline** (six dimensions): long pipes on trestles over the lava. `line` with `style: pipe` between `column`s.
3. **The ruined lab** (five dimensions): implied science: brewing stands, cauldrons, broken glass, pearls and potions in chests. `hovel` with `works` machines and `chest`s.
4. **The bone pit** (the_boneyard, the_soulfields): `pit` filled with bone blocks and a few skeleton skulls, `spawner`s at the bottom.
5. **The great lava fall** (the_blackstone_keep, the_gilded_pit, the_obsidian_sanctum): `fall` on a `wall` seat.
6. **The field of unnatural fire** (the_blackstone_keep, owner: "stretching it"): `field` of soul fire on soul soil.
7. **The crashed End ship** (the_boneyard; the_crumbling_reaches in the End): `wreck` of `minecraft:end_city/ship` in its crater, beside the kept `mes:mega_ship_crashed`.
8. **Meteor craters and pock marks** (the_boneyard): `crater`s in a `scatter`, some with a `core`.
9. **The industrial ruin** (the_molten_flats): chains, levers, pipes and chimneys on quenched lava; cells with witches and endermen; stacks of pearls and potions. `works`, `cell`, `column` (`stack`).
10. **Piglin farms and hovels** (the_ember_fields): see Villages and camps 3.
11. **The ledge over the capital** (the_gilded_pit): one landing on every seed with the way down clear. `platform` with `seat: pinned`.
12. **The palace** (the_gilded_pit): `dungeons_arise:shiraz_palace` waits on When Dungeons Arise; `nova_structures:piglin_donjon` stands in. Owner decides between waiting and a set piece (README).

## End

26.3 has MES's 25 (cities, citadels, spires, keeps, monoliths, archways, the mega ships whole and crashed, meteorite, groves), Dungeons and Taverns' `end_castle`, `end_lighthouse` and `end_ship`, and vanilla's End city.

| 1.21.1 id | On 26.3 | Wanted by |
| --- | --- | --- |
| `mes:astral_hideaway` | kept | the_fractured_halls |
| `mes:ender_spire` | kept | the_fractured_halls, the_pillared_void, the_tiled_expanse |
| `mes:enderkeep_courtyard` | kept | the_end, the_fractured_halls |
| `mes:enderwatch_tower` | kept | the_end_citadel |
| `mes:endscraps` | kept | the_pillared_void |
| `mes:manuscript_shrine` | kept | the_red_monument, the_tiled_expanse |
| `mes:mega_ship_crashed` | kept | the_boneyard, the_crumbling_reaches, the_fractured_halls |
| `mes:mega_ship_crashed_deepslate` | kept | the_crumbling_reaches |
| `mes:mega_ship_deepslate` | kept | the_crumbling_reaches |
| `mes:monolith` | kept | the_end, the_end_citadel, the_pillared_void, the_red_monument, the_starwell, the_tiled_expanse |
| `mes:mystical_archway` | kept | the_pillared_void, the_red_monument, the_starwell, the_tiled_expanse, the_violet_spire |
| `mes:mythic_garden` | kept | the_red_monument |
| `mes:phantom_citadel` | kept | the_end, the_end_citadel |
| `mes:ruined_pillar` | kept | the_crumbling_reaches, the_pillared_void, the_starwell, the_tiled_expanse |
| `minecraft:end_city` | kept | the_end, the_end_citadel, the_tiled_expanse |
| `nullscape:dragon_skeleton` | lost; stand-in `mns:giant_skull` (weak) | the_crumbling_reaches, the_pillared_void |
| `philipsruins:end_gate_fortress` | lost; `mes:enderkeep_courtyard` | the_fractured_halls |
| `philipsruins:end_ruins` | lost; `mes:endscraps` | the_crumbling_reaches, the_fractured_halls |
| `structory_towers:end/end_tower` | lost; `mes:ender_spire` | the_end_citadel, the_fractured_halls |
| `structory_towers:rare_towers` | lost; `mes:enderwatch_tower` | the_violet_spire |

Story gaps:

1. **The dragon's skeleton** (the_pillared_void, the_crumbling_reaches): `mns:giant_skull` is a skull. `skeleton` of fossil spines and skulls, long enough to read as a dragon.
2. **Wreckage fields** (the_crumbling_reaches): collapsed End-city towers in rubble. `template` of `minecraft:end_city/*` pieces with low `integrity` in a `scatter`.
3. **Variety on the outer islands** (the_end, owner): the central island stays classic; the outer islands carry MES's set and our `crater`, `wreck` and `crown` pieces.
4. **Attempted gateways** (the_molten_flats, the_tiled_expanse): `gate` with `state: attempt`.
5. **Crystal growth** (the_fractured_halls, the_crumbling_reaches): `crown` with amethyst on ridges.

## Sky

26.3 has MSS's 35 (castle ruin and tower, large and small towers, arena, volcano, houses in calcite, diorite and deepslate, ponds and trees) and MVS's floating islands.

| 1.21.1 id | On 26.3 | Wanted by |
| --- | --- | --- |
| `mss:castle_ruin` | kept | the_shattered_skies |
| `mss:castle_tower` | kept | the_shattered_skies |
| `mss:small_oak_house` | kept | the_canvas, the_shattered_skies |
| `mss:volcano` | kept | the_shattered_skies |
| `paradise_lost:aurel_tower` | lost; `mss:large_tower` | the_aviary, the_glacial_drift, the_pale_reach, the_snowbound_isle, the_sunken_temple, the_violet_spire |
| `paradise_lost:birdcage` | lost; stand-in `mss:small_tower` (weak) | the_aviary, the_glacial_drift, the_pale_reach, the_snowbound_isle, the_sunken_temple, the_violet_spire |
| `paradise_lost:palace` | lost; `mss:castle_ruin` | the_abyssal_shrine, the_aviary, the_glacial_drift, the_sunken_temple, the_violet_spire |
| `paradise_lost:remains` | lost; `mvs:statue_ruins` | the_aviary, the_glacial_drift, the_pale_reach, the_snowbound_isle, the_sunken_temple |
| `paradise_lost:vault` | lost; stand-in `nova_structures:trial_dungeon` (weak) | the_abyssal_shrine, the_aviary, the_glacial_drift, the_pale_reach, the_sunken_temple, the_wuthering_wisteria |

Story gaps:

1. **Birdcages** (six dimensions): the stand-in `mss:small_tower` is a tower. A `column` cage of bars on an island edge.
2. **The vault** (six dimensions): a sealed treasure room grown into the rock, not a trial dungeon. `cell` with a `chest` and `seat: ground` under the island's surface.
3. **Towers that grew** (the_aviary, the_snowbound_isle): organic, coral-like towers. `column` with `style: spire` and `crown`.
4. **Crystals and growth on spire tops** (the_violet_spire, owner): `crown`.

## Underground

26.3 has vanilla's ancient cities, mineshafts and trial chambers, Dungeons and Taverns' cave chambers (with archaeology, colonies and dungeons), cave hut, underground house, bunker and deepslate camp, and ATi's underground monument, sculk deposit, warden monument, quarry and pufferfish cave. The cave-biome sets 1.21.1 had from Subsurface (12) and Underground Worlds (9) are our biome library's work, not structures.

| 1.21.1 id | On 26.3 | Wanted by |
| --- | --- | --- |
| `adventuredungeons:underground_camp` | lost; `nova_structures:deepslate_camp` | the_crucible, the_luminous_caverns, the_sculked_beyond, the_underdark |
| `minecraft:ancient_city` | kept | overworld, the_canvas, the_endless_expanse, the_gauntlet, the_luminous_caverns, the_sculked_beyond, the_souldrift, the_underdark, the_wailing_narrows |
| `minecraft:mineshaft` | kept | overworld, the_endless_expanse, the_greenreach, the_gritlands, the_luminous_caverns, the_stonemantle, the_underdark |
| `philipsruins:sculk_dungeon` | lost; `ati_structures:warden_monument` | the_abyssal_shrine, the_blighted_maw, the_catalyst_maw, the_luminous_caverns, the_sculked_beyond, the_underdark, the_whispering_wilds |
| `structory:taiga_ruin_underground` | lost; `nova_structures:underground_house` | the_dripping_pines |

Story gaps:

1. **Outposts of the deep dark** (the_underdark, the_sculked_beyond, the_souldrift): ancient-city pieces beyond the cities. `template` of `minecraft:ancient_city/structures/*`.
2. **Old workings** (the_stonemantle, the_gritlands): galleries, rails and props in the stone. `line` (`rail`, `bars`) and `column` (`pillar`) in a `pit`.
3. **The copper towers and strange outposts** (the_luminous_caverns): kept as `mns:copper_tower` and `mns:warped_dome`; no gap.

## Ocean

26.3 has vanilla's monuments, shipwrecks, ocean ruins and buried treasure, MTR's ocean temple, MVS's ocean tower, small ship and prismarine houses, Dungeons and Taverns' conduit ruins and trident trial, ATi's sinking temple, underwater dungeon, ancient vessel and pufferfish cave, and towns-and-towers' ocean village and wreckage.

| 1.21.1 id | On 26.3 | Wanted by |
| --- | --- | --- |
| `dungeons_plus:deepwater_dungeon` | lost; `ati_structures:underwater_dungeon` | the_abyssal_shrine, the_sunken_temple |
| `minecraft:buried_treasure` | kept | the_frozen_strait, the_shallows, the_tidepools |
| `minecraft:monument` | kept | overworld, the_abyssal_shrine, the_sunken_temple |
| `minecraft:shipwreck` | kept | the_frozen_strait, the_shallows, the_tidepools |
| `philipsruins:ocean_fortress` | lost; `mtr:ocean_temple` | the_shallows, the_sunken_temple |
| `philipsruins:ocean_ruins` | lost; `minecraft:ocean_ruin_warm` | the_frozen_strait, the_shallows, the_tidepools |
| `structory_towers:ocean_pillar` | lost; `mvs:ocean_tower` | the_shallows, the_tidepools |

Story gaps:

1. **Wrecks in the ice** (the_frozen_strait): `template` of `minecraft:shipwreck/*` with `embed` into the sheet.
2. **The drowned temple on the skyland** (the_sunken_temple, owner: "essential, being the namesake"): kept `mtr:ocean_temple` and `minecraft:monument`, placed by `force` in the basins; no new kind.
