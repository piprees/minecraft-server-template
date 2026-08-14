# Valid Structure Short Names

Ground truth: `mods/custom-dimensions/src/main/java/com/customdimensions/dimension/StructureAliases.java`. These are the short names usable in `structures.wants`/`structures.shuns` and `seedRoll.wants`/`seedRoll.shuns`. A short name resolves to a full structure id (e.g. `village` → `#minecraft:village`); you can also use a full `<namespace>:<path>` id directly if a structure you want isn't in this list, but prefer a listed short name when one exists — it's guaranteed to be pattern-matched correctly. **Every id here is already verified against the shipped mod/datapack registries** — do not invent new short names, they will resolve to nothing and be silently dropped from scoring.

## Settlements & civilisation

`village`, `tavern`, `farmstead`, `watchtower`, `guide_post_warm`, `guide_post_cold`, `campsite`, `dark_settlement`, `outcast_grassy`, `outcast_desert`, `pillager_outpost`, `pillager_lookout`, `nomad_outpost`, `taiga_outpost`, `foraging_outpost`, `mirage_outpost`, `quarter_outpost`, `engineer_tower`, `firetower`, `lighthouse`

## Ruins & the-world-went-wrong

`ruined_portal`, `mineshaft`, `ruin_grassy`, `swamp_ruin`, `jungle_ruin`, `forest_ruin`, `northern_ruin`, `taiga_ruin`, `taiga_ruin_deep`, `abandoned_camp`, `abandoned_chapel`, `graveyard`, `old_manor`, `field_ruins`, `badlands_ruins`, `ancient_ruins`, `ancient_crypt`, `pumpkin_ruins`, `mausoleum`, `ruins`, `ruins_desert`, `ruins_snow`, `ruins_standard`, `desert_shrine`, `badlands_pyramid`, `black_spiral`, `mangrove_hut`, `supply_cache_desert`

## Dungeons

`trial_chambers`, `ancient_city`, `skeleton_dungeon`, `spider_dungeon`, `zombie_dungeon`, `cold_dungeon`, `frozen_dungeon`, `lush_dungeon`, `muddy_dungeon`, `webbed_dungeon`, `infested_dungeon`, `mouldy_dungeon`, `dusty_tomb`, `scorched_tomb`, `deepwater_dungeon`, `ice_dungeon_l`, `ice_dungeon_m`, `sand_dungeon_l`, `sculk_dungeon`, `bone_dungeon`, `underground_camp`, `coldlair`, `murkydungeon`

## Epic / boss-tier (see also `scoring-internals.md`'s `ENDGAME_STRUCTURES` set — most of these overlap it)

`coliseum`, `keep_kayra`, `infested_temple`, `abandoned_temple`, `bandit_towers`, `bandit_village`, `illager_fort`, `illager_campsite`, `jungle_tree_house`, `giant_mushroom`, `wizard_tower`, `ancient_temple`, `relic_temple`, `mansion`, `monument`, `heavenly_rider`, `heavenly_conqueror`, `heavenly_challenger`, `typhon`, `shiraz_palace`, `plague_asylum`, `mechanical_nest`, `kisegi_sanctuary`, `thornborn_towers`, `undead_pirate_ship`, `illager_corsair`, `illager_galley`, `ceryneian_hind`, `scorched_mines`, `mining_complex`, `foundry`

## Dungeons & Taverns boss/mega structures

`creeping_crypt`, `undead_crypt`, `illager_hideout`, `shrine_tower`, `trident_trial`, `lone_citadel`, `stray_fort`, `illager_manor`

## Misc mod-specific boss structures

`iceologer_citadel` (Friends & Foes), `mns_nether_tower` (Moogs Nether Structures), `nether_temple`, `antiquus_crypta` (Philip's Ruins mega crypt), `sky_castle_tower` (Moogs Sky Structures)

## Ocean & frozen (vanilla + Philip's Ruins)

`shipwreck`, `buried_treasure`, `igloo`, `desert_pyramid`, `ocean_ruins`, `ocean_fortress`, `ocean_pillar`

## Nether

`fortress`, `bastion`, `sanctum`, `forbidden_castle`, `piglin_village`, `nether_reactor`, `ruined_lab`, `infernal_altar`, `nether_tower`, `pipeline`, `giant_skull`, `nether_graveyard`, `crimson_forge`, `copper_tower`, `blackstone_pillars`, `blackstone_walls`, `nether_bridge`, `crimson_well`, `crimson_fungus`, `nether_brick_hall`, `warped_greatsword`, `warped_outpost`, `strange_outpost`, `nether_dungeon`, `lost_soul_dungeon`, `nether_lava_ruins`, `start_nether_ruin`

## End

`end_city`, `phantom_citadel`, `enderkeep`, `enderwatch_tower`, `ender_spire`, `monolith`, `ruined_pillar`, `mystical_archway`, `manuscript_shrine`, `mythic_garden`, `astral_hideaway`, `endscraps`, `mega_ship_crashed`, `mega_ship_deepslate`, `dragon_skeleton`, `end_tower`, `end_ruins`, `end_gate_fortress`

## Sky islands (Moogs Sky Structures)

`sky_castle_ruin`, `sky_arena`, `sky_house`, `sky_volcano`

## Paradise Lost family

`para_remains`, `aurel_tower`, `para_vault`, `para_palace`, `birdcage`

## In-house

`exit_shrine` — this mod's own scattered jigsaw exit ruin (`adventure:exit_shrine`). Pair with the top-level `exitShrines: {"enabled": true}` block if you want these to actually spawn at a raised frequency — listing `exit_shrine` in `wants` alone only affects roller scoring, not placement odds.

## Default wants (fallback when a dimension has no `seedRoll` block at all)

If you skip `seedRoll`/`structures` entirely, the roller falls back to modest generic wants by family — don't rely on this, write explicit wants instead, but it's worth knowing what "nothing configured" actually scores against:

- overworld family: `village: near_spawn`, `mineshaft: spread`, `trial_chambers: spread`, `ancient_city: near_border`
- nether family: `fortress: spread`, `bastion: spread`
- end family: `end_city: spread`
