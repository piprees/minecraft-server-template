# Places by dimension

Every dimension's places, from its 1.21.1 description and config and, for Batches 1 and 2, the owner's notes (quoted from [owner-review-1.md](../../../../mods/custom-dimensions-26/fidelity/feedback/owner-review-1.md)). Grouped by [batch](../../../../mods/custom-dimensions-26/fidelity/batches.md); the 21 terrain picks, which no batch holds, come last.

Each place gives its source:

- `id`: a structure the 26.3 pack registers, placed by our structure control (`force`, `wants`, a group or a zone). Ids are the v2 definitions' own where they already name one.
- **SP** `kind`: a set piece of ours ([set-pieces.md](set-pieces.md)).
- **EX** `explorify:<id>`: waits on explorify's dep-review ([explorify.md](explorify.md)); the id after "else" places until it lands.

"Near spawn", "spread" and "near border" are the 1.21.1 `seedRoll.wants` distances, kept as structure-control `wants` rings. Every set piece on a sky or void world uses a seat with `support`, so nothing starts over void.

## Batch 1: base worlds and the aviary's clones

### the_aviary

Owner: keep (no note).

- **Coral towers**: towers that grew like coral. `mss:large_tower`; **SP** `column` (`spire`) with `crown` for the grown ones.
- **Birdcages on the rims**: **SP** `column` cage on island edges (the stand-in `mss:small_tower` is a tower).
- **The grown vault**: sealed treasure room in the rock under an island's surface. **SP** `cell` with `chest` (stand-in `nova_structures:trial_dungeon`).
- **Remains** near spawn: `mvs:statue_ruins`.
- **The palace ruin** near border: `mss:castle_ruin`.

### overworld

Owner: "The mountain in the middle of the first screenshot is very broken, it looks cool but worth checking if this was intentional, everything else seems right".

- **The village** near spawn: `#minecraft:village`.
- **The tavern** near spawn: `nova_structures:tavern_*` by biome.
- **Campsite and farmstead** near spawn: **EX** `explorify:campsite`, `explorify:farmstead`; else `mvs:campsite`, `nova_structures:remnant_ruin_farmer`.
- **The ruined portal** near spawn: `minecraft:ruined_portal`.
- **Watchtowers, mineshafts, outposts, trial chambers, monuments** spread: `nova_structures:firewatch_tower_forest`, `minecraft:mineshaft`, `minecraft:pillager_outpost`, `minecraft:trial_chambers`, `minecraft:monument`.
- **The ancient cities** near border, "something stirs in them": `minecraft:ancient_city`.
- **Mansion and coliseum** near border: `minecraft:mansion`, `mss:arena`.

### the_nether

Owner: "Yeah quartz flats could be there, but rare".

- **Fortresses** in reach: `minecraft:fortress`, `mns:mega_fortress`.
- **Bastions**: `minecraft:bastion_remnant`.
- **Piglin villages**: `towns_and_towers:exclusives/village_piglin`.
- **The sanctum** near the rim: `nova_structures:sealing_halls`.
- **Bridges over the lava**: `mns:bridge_1`–`6`.
- **Bones in the valleys**: `minecraft:nether_fossil`.

### the_end

Owner: "We need to check the end islands, the central biome is more or less plain classic end here which is reasonable, but the end islands are where the variety starts". Rulings: the central island stays classic; End cities in a denser dungeons group; elytra at 8% a chest in End dungeon chests.

- **The central island**: vanilla's fountain and pillars, unchanged.
- **End cities and phantom citadels** within reach of the gateways: `minecraft:end_city`, `mes:phantom_citadel` (dungeons group, dense).
- **Enderkeeps and monoliths** spread: `mes:enderkeep_courtyard`, `mes:monolith`.
- **The outer islands' variety**: `mes:enderskog`, `enderbloom_grove`, `placid_prairie`, `ender_spire`, `mystical_archway`, `nova_structures:end_lighthouse`, `end_castle`.
- **The fallen star**: `mes:astral_meteorite` in **SP** `crater`.
- **A whole mega ship**, rare: `mes:mega_ship`.

### the_snowbound_isle

Owner: "It looks like structures are spawning “underground” floating in the void which shouldn’t happen".

- **The igloo** near spawn: `minecraft:igloo`.
- **Towers that grew like the trees**: `mss:large_tower` (seat `ground`, support 0.8); **SP** `column` (`spire`, snow `crown`).
- **Cold dungeons**: `nova_structures:surface_dungeon_snowy`.
- **The frozen dungeon** near border: `epic:small_ice_dungeon`.
- **The birdcage** near border: **SP** `column` cage.
- **The guide post** near spawn: **EX** `explorify:guide_post_cold`; else `mvs:small_spruce_lantern`.
- **Remains** near spawn: `mvs:statue_ruins`.

### the_abyssal_shrine

Owner: "This circle lake is too perfect, the edges should not have void around them. It’s a cool idea for a place though. Same issue with the spawning structures underground."

- **The shrine**: the thing worshipped, on the plateau rim over the deepest hollow. **SP** `platform` with `column` pillars, steps down to the water and a `chest`.
- **The drowned monument** in the hollow, guardians circling: `minecraft:monument` by `force` in the basin.
- **The underwater dungeon**: `ati_structures:underwater_dungeon`.
- **The sky palace ruin**: `mss:castle_ruin`.
- **The vault** (1.21.1 forced): **SP** `cell` with `chest`; stand-in `nova_structures:trial_dungeon`.
- **The warden monument** near border: `ati_structures:warden_monument`, seat `ground` (not under the islands).

### the_violet_spire

Owner: "I’m not seeing any crystals here, perhaps this dimension should also be a night-only dimension. Something should grow on top of some of the spires". Ruling: night-only, crystals and growth on spire tops.

- **Crowned spires**: amethyst clusters and growth on spire tops. **SP** `crown`.
- **Aurel towers** near spawn: `mss:large_tower`.
- **Birdcages**: **SP** `column` cage.
- **Wizard towers**: `ddd:wizard_tower`.
- **Mystical archways and enderwatch towers**: `mes:mystical_archway`, `mes:enderwatch_tower`.
- **Shrine towers**: `nova_structures:shrine_tower`.
- **The relic temple and the palace ruin** near border: `ati_structures:fortified_temple`, `mss:castle_ruin`.

### the_sunken_temple

Owner: "I think the water level needs to be higher to touch the bottoms of the skyland islands. There’s no temples mentioned in the screenshots, I would say that’s essential being a namesake of the biome. Much like the others, I’m seeing underground “places” that are… just empty walls? Worth revisiting how you detect a “place”, I would expect structures and decorative elements in a “place”."

- **The sunken temple** (the namesake): `mtr:ocean_temple` by `force` in the largest basin, and a second near border.
- **Monuments**: `minecraft:monument` spread through the basins.
- **The sinking temple**: `ati_structures:sinking_temple`.
- **Half-drowned ruins on the shores**: **SP** `template` of `minecraft:underwater_ruin/big_*` with `embed`.
- **Underwater dungeon and conduit ruins**: `ati_structures:underwater_dungeon`, `nova_structures:conduit_ruin`.
- **Sky towers**: `mss:large_tower` near spawn, `mss:small_tower` spread.
- **The palace ruin** near border: `mss:castle_ruin`.

## Batch 2: Nether presets

### the_forged_depths

Owner: "I think this dimension needs to be deader, no trees, much more craggy and stuffed to the max with structures, this is a capital city and it should be a SLOG to get anywhere."

- **The last forge still burning** near spawn: `mns:crimson_forge`.
- **The reactors**: **SP** `works` round a quench `pit` (stand-in `mns:crimson_forge`).
- **The city**: `nova_structures:piglin_donjon`, `nether_keep`, `piglin_outstation`, `mns:large_house_1`, `medium_house`, `mns:large_blackstone_walls` in packed `settlements` and `landmarks` groups, repetition raised.
- **Bastions and the fortress**: `minecraft:bastion_remnant`, `mns:mega_fortress`.
- **Ruined labs**: **SP** `hovel` with `works` machines (stand-in `mns:copper_tower`).
- **Chimney stacks and slag heaps**: **SP** `works` (`columns`, `slag`).

### the_buried_age

Owner: "Structures, structures, structures! Broken and abandoned things, desolated camps and bones, archaeological stuff, think pompeii but still cooking".

- **The start ruin** round an old portal, near spawn: `mns:ruined_portal`.
- **Towers that kept their top floor**: `mns:nether_tower`, `terrainAdaptation: bury`.
- **Nether lava ruins**: `mns:large_nether_brick`.
- **Pompeii**: houses buried in lava rock, their roofs showing, archaeology inside. **SP** `template` of `minecraft:trail_ruins/buildings/*` with `embed` and `archaeology`, in a magma `field`.
- **The dig**: an excavation pit with suspicious gravel. **SP** `pit` with `archaeology`.
- **Desolated camps**: **SP** `camp` (`abandoned`).
- **Bones**: **SP** `skeleton` of `minecraft:nether_fossils/*`.
- **Pipelines**: **SP** `line` (`pipe`) on `column` trestles (stand-in `mns:bridge_1`).
- **The copper tower** near border: `mns:copper_tower`.

### the_blackstone_keep

Owner: "Failing to see any places or landmarks in your screenshots. Some random netherrack and lava is not a place or a structure. A fortress is a place. A bastion is a place. An interesting pit in the nether with bones or something is a place. A huge lava waterfall is a place. A field of unnatural fire is stretching it but probably a place. If people would name it something, then it’s likely a place, otherwise it’s a small detail on the way to a place."

- **The bastion at spawn**: `minecraft:bastion_remnant` by `force`.
- **The frontier wall**: blackstone walls and towers facing the threat from below. **SP** `wall`; `mns:large_blackstone_walls`.
- **Blackstone pillars**: `mns:large_blackstone_pillars`.
- **The forbidden castle**: `nova_structures:nether_keep`.
- **Nether and copper towers**: `mns:nether_tower`, `mns:copper_tower`.
- **The breach**: the pit the threat came up from, ringed by a wall. **SP** `pit` with `wall`.
- **The great lava fall**: **SP** `fall` on a `wall` seat.
- **The unnatural fire field**: **SP** `field` of soul fire.

### the_gilded_pit

Owner: "The mentioned structures here are essential to showcasing this dimension, and the ledge overlooking it might need to be engineered so no matter the seed, it makes sense. It should be a dangerous climb down but from the cliff it should be clear where to go".

- **The ledge**: one landing on every seed overlooking the pit, the way down in sight. **SP** `platform` (`seat: pinned`) at the arrival.
- **The climb down**: chains and broken stairs on the pit wall. **SP** `line` (`chain`) and `platform` steps.
- **The bastion**: `minecraft:bastion_remnant`.
- **The forge**: `mns:crimson_forge`.
- **The palace**: `nova_structures:piglin_donjon` (stand-in for When Dungeons Arise's palace; owner decides, README).
- **The forbidden castle**: `nova_structures:nether_keep`.
- **The fortress, trial chambers, keep and crypt** (1.21.1 forced): `mns:mega_fortress`, `minecraft:trial_chambers`, `ati_structures:dark_keep`, `nova_structures:undead_crypt`.
- **The sanctum, walls and towers** near border: `nova_structures:sealing_halls`, `mns:large_blackstone_walls`, `mns:nether_tower`.
- **Gold falls**: **SP** `fall` of lava past gold-veined walls into the pit.

### the_molten_flats

Owner: "I think this place could be even flatter, with less debris on the fields. We could de-emphasise the magma and instead have pockets of it, using black stone and obsidian to box the magma in. Bubbles of lava could be present still in the centres of the larger magma puddles, like its cooling off. It should have structures here that feel like industry, chains, levers, pipes, chimneys; they cleared this place, strip mined it, then built on the polluted land only to run out of space and quench the lava to build on that instead. Long since abandoned and monsters have moved in. They produced weapons and armour for the wars here; it should be sparse but some rough structures more like buildings/forts should remain, old barracks and shipping areas with broken portals, attempts at end-gateways and implied science, imprisoned witches and endermen, stacks of pearls and potions".

- **The works**: chains, levers, pipes and chimneys. **SP** `works`.
- **Quenched pads**: building plots on boxed, cooled lava. **SP** `field` (blackstone and obsidian over magma) under the works.
- **Pipelines**: **SP** `line` (`pipe`); `mns:bridge_1`.
- **The reactor** near border: **SP** `works` round a quench `pit` (stand-in `mns:crimson_forge`).
- **The forts**: `mns:mega_fortress`, `nova_structures:piglin_outstation`.
- **Old barracks**: **SP** `hovel` rows with a `chest` of arms.
- **The shipping yard**: **SP** `platform` with broken `gate`s (`minecraft:ruined_portal/*`) and `stack`s.
- **The gateway attempt**: **SP** `gate` (`attempt`, end frame).
- **The prison**: imprisoned witches and endermen. **SP** `cell`.
- **The laboratory**: implied science, pearls and potions. **SP** `hovel` with `works` machines, `column` (`stack`) of pearls, brewing `chest`s.
- **The crimson well** near spawn: `mns:crimson_lava_well`.
- **Copper and nether towers**: `mns:copper_tower`, `mns:nether_tower`.

### the_bloodroot_wastes

Owner: "Yes keep the fog, the biome map is basically I’ve colour here so Tara not great. Lots of focus again on natural scenery and not any emphasis on real places".

- **The forges**: `mns:crimson_forge`.
- **Crimson wells** near spawn: `mns:crimson_lava_well`, `mns:medium_crimson_well`.
- **The fungus** near spawn: `mns:medium_crimson_fungus`.
- **The fortress**: `mns:mega_fortress`.
- **The survivors' camps**: `nova_structures:piglin_camp`, `piglin_camp_collony`.
- **Workshops**: **SP** `hovel` with `works` (stand-in `mns:copper_tower` for the ruined lab).
- **The pipeline** near border: **SP** `line` (`pipe`).

### the_obsidian_sanctum

Owner: "Much improved, just needs some structures like the others I’ve mentioned".

- **The sanctum** near spawn: `nova_structures:sealing_halls`.
- **Altars** near spawn: `mns:smoking_shrine`, `mns:shrine`.
- **The forbidden castle** near border: `nova_structures:nether_keep`.
- **Nether temples**: `mtr:nether_temple`.
- **Prayer pillars**: obsidian and crying obsidian. **SP** `column`.
- **The weeping fall**: **SP** `fall` of lava down obsidian cliffs.

### the_boneyard

Owner: "This is super cool. Some environmental storytelling here would be helpful, a couple of crashed end-ships and meteorites/pock marks of craters from explosions would sell it. Maybe have some (as in, very few) skeleton skulls on the floor. We could add spawners for skeletons too, and ghasts, and bats. Maybe a super buffed iron golem still fighting somewhere as an Easter egg with a chest with loads of iron. The strewn remnants of a piglin camp and a witch hut somewhere would be interesting, but might suit other dimensions more- this place should be dead or like BLAME! sorts of autonomous, only the non-living survived".

- **The crashed End ships** (two): **SP** `wreck` of `minecraft:end_city/ship`; `mes:mega_ship_crashed` (1.21.1 forced).
- **Craters and pock marks**: **SP** `crater`s in a `scatter`, a few with a meteorite `core`.
- **Bones of giants**: `mns:giant_skull` (two forced); **SP** `skeleton` of nether fossils.
- **The bone pit**: **SP** `pit` of bone blocks, a few skeleton skulls, skeleton `spawner`s.
- **Ghast and bat roosts**: **SP** `spawner`s in a `roof` seat.
- **The last golem**: a buffed iron golem with a chest of iron, once per world. **SP** `mob` (`iron_golem`, raised `max_health` and `attack_damage`, persistent) and `chest`, on a small `crater` (`force`, one copy).
- **Nether graveyards**: `mns:grave_yard`.
- **Catacomb and crypt** (1.21.1 forced): `nova_structures:catacomb`, `small_undead_crypt`.
- **The skeleton camp**: `nova_structures:nether_skeleton_camp_soul`.
- **The sculk scar**, where it fed on the war: **SP** `field` of sculk.
- **Strewn piglin camp** (owner: perhaps elsewhere): **SP** `scatter` of a burned `camp`; held for the owner (README).

### the_furnace_halls

Owner: "Amazing, this looks metal as fuck (good thing). Some cool and interesting structures here would really set it off."

- **The mega fortress** at the far end: `mns:mega_fortress` by `force`.
- **Fortress halls** near spawn: `minecraft:fortress`.
- **Nether-brick halls**: `mns:large_nether_brick`.
- **Fort skeleton camps and towers**: `nova_structures:nether_skeleton_camp_fort`, `nether_skeleton_tower_fort`.
- **The furnace works**: blast furnaces, chimneys and chains. **SP** `works`.
- **The reactor** near border: **SP** `works` (stand-in `mns:crimson_forge`).
- **Copper towers, walls and pipelines**: `mns:copper_tower`, `mns:large_blackstone_walls`; **SP** `line` (`pipe`).

### the_wailing_narrows

Owner: "Yeah keep the fog across a dimension unless there’s a good reason not to, this place feels very similar to the others now and isn’t giving “vile” or “zombie”; de-emphasise or remove the quartz biome and ensure this place feels like some dodgy crevice filled hell-hole rather than a real place anyone would dare settle, it just happened to become a fallout region rather than a destination in itself".

- **Bridges over the wail** near spawn: `mns:bridge_1`–`6`.
- **The quarantine wall** that did not hold: **SP** `wall` with `breaches`.
- **Zombie camps**: fallen tents and zombified piglin spawners. **SP** `camp` (`abandoned`) with `spawner`s.
- **Hanging chains** across the narrows: **SP** `line` (`chain`).
- **The fortress**: `mns:mega_fortress`.
- **Giant skulls**: `mns:giant_skull`.
- **Blackstone pillars** near border: `mns:large_blackstone_pillars`.
- **The ancient city** (1.21.1 forced): `minecraft:ancient_city`.

### the_ember_fields

Owner: "This place is cool, I’d like to see more netherwart and mushroom “farming”, structures for homes or hovels, let’s make it feel like this is where a Pacifist splinter of the piglins hid and forgot they were even hiding after so many millennia".

- **Piglin villages**: `towns_and_towers:exclusives/village_piglin`.
- **Wart and fungus farms**: **SP** `farm`; `mns:nether_wart_farm`.
- **Homes and hovels**: **SP** `hovel`; `mns:medium_house`, `mns:warped_house`.
- **The colony**: `nova_structures:piglin_camp_collony`.
- **Crimson wells and fungus** near spawn: `mns:crimson_lava_well`, `mns:medium_crimson_fungus`.
- **Bridges**: `mns:bridge_1`.
- **The fortress at the rim**: `mns:mega_fortress` near border.
- **The altar** near border: `mns:smoking_shrine`.

## Batch 3: End presets and floating islands

### the_blighted_maw

- **The reactors**: **SP** `works` (stand-in `mns:crimson_forge`, 1.21.1 forced).
- **The lab** near spawn: **SP** `hovel` with `works` (stand-in `mns:copper_tower`).
- **The sculk dungeon** (1.21.1 forced): `ati_structures:warden_monument`.
- **Bastion and fortress**: `minecraft:bastion_remnant`, `mns:mega_fortress`.
- **Pipes between the islands**: **SP** `line` (`pipe`) from rim to rim.
- **The blight**: **SP** `field` of sculk over island tops.

### the_catalyst_maw

- **The catalyst** near spawn: `ati_structures:warden_monument`.
- **Consumed reactors and bastions**: `mns:crimson_forge`, `minecraft:bastion_remnant` under **SP** `field` of sculk.
- **The fortress** near border: `mns:mega_fortress`.
- **The lab**: **SP** `hovel` with `works`.
- **Deep-dark outposts**: **SP** `template` of `minecraft:ancient_city/structures/*`.

### the_fractured_halls

- **The gate fortress** near spawn: `mes:enderkeep_courtyard`.
- **Enderkeeps**: `mes:enderkeep_courtyard` spread.
- **The astral hideaway** near spawn: `mes:astral_hideaway`.
- **Ender spires**: `mes:ender_spire`.
- **The crashed ship** near border: `mes:mega_ship_crashed`.
- **Endscraps**: `mes:endscraps`.
- **The walls still building**: **SP** `wall` of end-stone bricks between keeps.
- **Crystal ridges**: **SP** `crown`.

### the_crumbling_reaches

- **The deepslate mega ship** near spawn: `mes:mega_ship_deepslate` (1.21.1 forced).
- **Crashed ships** everywhere: `mes:mega_ship_crashed`, `mega_ship_crashed_2`, `mega_ship_crashed_deepslate` (common).
- **The dragon's skeleton** near spawn: **SP** `skeleton` (stand-in `mns:giant_skull`).
- **Wreckage fields**: collapsed End towers. **SP** `scatter` of `minecraft:end_city/*` templates at low `integrity`.
- **Ruined pillars and endscraps**: `mes:ruined_pillar`, `mes:endscraps`.
- **Crystal peaks**: **SP** `crown`.

### the_end_citadel

- **The citadel** at spawn: `minecraft:end_city` (1.21.1 forced) and end cities near spawn.
- **Phantom citadels**: `mes:phantom_citadel`.
- **Enderwatch towers** near spawn: `mes:enderwatch_tower`.
- **Ender spires and monoliths**: `mes:ender_spire`, `mes:monolith`.
- **Skybridges** between citadels: **SP** `line` (`bridge`) of purpur.

### the_pillared_void

- **The pillars**: terrain (G12) with `mes:ruined_pillar` near spawn.
- **The dragon's skeleton**: **SP** `skeleton` (stand-in `mns:giant_skull`, 1.21.1 forced).
- **Ender spires and archways**: `mes:ender_spire`, `mes:mystical_archway`.
- **Endscraps**: `mes:endscraps`.
- **The monolith** near border: `mes:monolith`.
- **Bastion and Nether portal** (1.21.1 forced): `minecraft:bastion_remnant`, `minecraft:ruined_portal_nether`.

### the_burning_archipelago

- **The warped outpost** near spawn: `mvs:large_warped_tower`.
- **The ruined portal** near spawn: `minecraft:ruined_portal`.
- **Bastions and the fortress**: `minecraft:bastion_remnant` (1.21.1 forced), `mns:mega_fortress`.
- **Skulls of the war**: `mns:giant_skull`.
- **Nether towers and forges**: `mns:nether_tower`, `mns:crimson_forge`.
- **The battle across the straits**: **SP** `scatter` of piglin and End wreckage.
- **Smouldering craters**: **SP** `crater` with a fire `field`.

### the_pale_reach

- **Remains** spread: `mvs:statue_ruins`.
- **Birdcages** drifting in the whiteness: **SP** `column` cage.
- **Aurel towers**: `mss:large_tower`.
- **The vault** (1.21.1 forced): **SP** `cell` (stand-in `nova_structures:trial_dungeon`).
- **The frozen dungeon** near border: `epic:small_ice_dungeon`.

### the_starwell

- **The archway**: `mes:mystical_archway` (forced).
- **The monolith**: `mes:monolith` (want, 0–256).
- **Ruined pillars**: `mes:ruined_pillar` (want, 0–256).

### the_shattered_skies

- **Sky houses** near spawn: `mss:small_oak_house`.
- **Castle ruins**: `mss:castle_ruin`.
- **The sky arena**: `mss:arena`.
- **The sky volcano and castle tower** near border: `mss:volcano`, `mss:castle_tower`.
- **The camp** near spawn: `minecraft:abandoned_camp_cherry_grove`.
- **The guide post** near spawn: **EX** `explorify:guide_post_warm`; else `mvs:small_oak_lantern`.

### the_glacial_drift

- **The palace** (1.21.1 forced): `mss:castle_ruin`.
- **Remains and the igloo** near spawn: `mvs:statue_ruins`, `minecraft:igloo`.
- **Aurel towers and birdcages**: `mss:large_tower`; **SP** `column` cage.
- **The vault** near border: **SP** `cell`.

## Batch 4: caves and void or special generators

### the_emberglass_foundry

- **The foundry** (1.21.1 forced): `mns:crimson_forge`.
- **The mining complex**: `ati_structures:quarry` (want, 0–256).
- **The proving ground**: `mns:small_arena`.
- **Thermal vents**: **SP** `column` (`chimney`) over magma.

### the_underdark

- **Ancient cities** spread: `minecraft:ancient_city`.
- **The warden monument** near spawn: `ati_structures:warden_monument`.
- **The camp** near spawn: `nova_structures:deepslate_camp`.
- **Trial chambers and mineshafts**: `minecraft:trial_chambers`, `minecraft:mineshaft`.
- **Dungeons**: `ati_structures:silverfish_nest`, `nova_structures:catacomb`, `epic:small_ice_dungeon`, `nova_structures:small_creeping_crypt`.
- **Outposts of the migration**: **SP** `template` of `minecraft:ancient_city/structures/*`.

### the_weeping_vault

- **The forbidden castle and the sanctum** (1.21.1 forced): `nova_structures:nether_keep`, `nova_structures:sealing_halls`.
- **Nether dungeons** near spawn: `mns:small_nether_brick` (want, 0–102).
- **The bastion**: `minecraft:bastion_remnant` (want, 68–512).
- **Altars**: `mns:smoking_shrine`.
- **The nether tower** near border: `mns:nether_tower`.
- **The sealed thing**: still inside. **SP** `cell` of crying obsidian with a `mob`.

### the_sculked_beyond

- **The sculk dungeon** near spawn: `ati_structures:warden_monument` (1.21.1 forced).
- **The ancient city** near border: `minecraft:ancient_city` (1.21.1 forced).
- **The explorers' last camp** near spawn: `nova_structures:deepslate_camp`; **SP** `camp` (`abandoned`).
- **Trial chambers**: `minecraft:trial_chambers`.
- **The bone dungeon**: `nova_structures:catacomb`.
- **Sculk deposits**: `ati_structures:sculk_deposit`.

### the_luminous_caverns

- **The underground camp, copper tower and ancient city** (1.21.1 forced): `nova_structures:deepslate_camp`, `mns:copper_tower`, `minecraft:ancient_city`.
- **Strange outposts**: `mns:warped_dome`.
- **The lush dungeon** near spawn: `ati_structures:jungle_grotto`.
- **The sculk dungeon and mineshafts**: `ati_structures:warden_monument`, `minecraft:mineshaft`.

### the_fungal_lanterns

- **Crimson fungus** near spawn: `mns:medium_crimson_fungus` (want, 0–154).
- **Warped outposts**: `mvs:large_warped_tower`.
- **The greatsword**: `mns:sword`.
- **The strange outpost and the bridge** near border: `mns:warped_dome`, `mns:bridge_1`.

### the_slatemouth

No structures (1.21.1 `structureDensity: none`). The void is the place; falling returns players to the capital.

### the_icebound_rift

- **The citadel** hanging where the world was (1.21.1 forced, `terrainAdaptation: none`): `nova_structures:stray_fort`.

### the_red_monument

- **The monolith** (forced): `mes:monolith`.
- **Manuscript shrines** (two), **the mythic garden** and **the archway** at a fixed distance (forced): `mes:manuscript_shrine`, `mes:mythic_garden`, `mes:mystical_archway`.

### the_tiled_expanse

- **The monolith** (forced): `mes:monolith`.
- **Ruined pillars** near spawn: `mes:ruined_pillar`.
- **Archways and ender spires**: `mes:mystical_archway`, `mes:ender_spire`.
- **The end city and the manuscript shrine** near border: `minecraft:end_city`, `mes:manuscript_shrine`.

### the_canvas

Owner: forced structures only.

- **The small oak house and the ancient city** (forced, 1200, -880): `mss:small_oak_house`, `minecraft:ancient_city`.

## Batch 5: overworld presets, worst half

### the_tidepools

- **The drowned wreck**: `minecraft:shipwreck` (want, 0–256).
- **The ocean ruin and the ocean tower** (1.21.1 forced): `minecraft:ocean_ruin_warm`, `mvs:ocean_tower` (`bury`).
- **Buried treasure**: `minecraft:buried_treasure` (want, 0–256).

### the_lost_outpost

- **The pillager outpost and the illager camp** near spawn (1.21.1 forced): `minecraft:pillager_outpost`, `nova_structures:illager_camp`.
- **Bandit towers** (1.21.1 forced): `ati_structures:illager_tower`.
- **Lookouts**: `mvs:small_pillager_tower`; `towns_and_towers:pillager_outpost_*` by biome.
- **Nomad camps**: `ati_structures:nomadic_camp`.
- **The bandit village and the fort** near border: `nova_structures:illager_barracks`, `ati_structures:old_fort`.
- **Training grounds**: **SP** `template` of `minecraft:pillager_outpost/feature_targets` and `feature_cage1`.

### the_greywoods

- **The graveyard** near spawn: `nova_structures:remnant_graveyard`.
- **Chapels**: `ati_structures:haunted_ruin`.
- **The witches' houses**: `nova_structures:hamlet`, `nova_structures:witch_villa`.
- **The manor** near border: `ati_structures:manor`.
- **The mausoleum** near border: **EX** `explorify:mausoleum`; else `nova_structures:remnant_shrine_sentinel_mass_grave`.
- **Dungeons**: `epic:small_plains_dungeon`, `nova_structures:small_undead_crypt`.
- **Swamp ruins**: `mvs:small_swamp_house`.
- **Field graves**: **SP** `scatter` of headstones.

### the_wuthering_wisteria

All 1.21.1 forced (`mode: allow`, empty list).

- **The campsite**: **EX** `explorify:campsite`; else `mvs:campsite`.
- **The grassy ruin**: `mvs:small_ruin`.
- **The flower-forest village**: `towns_and_towers:village_flower_forest`.
- **The oriental outpost**: `towns_and_towers:exclusives/pillager_outpost_oriental`.
- **The vault**: **SP** `cell` (stand-in `nova_structures:trial_dungeon`).

### the_souldrift

- **The breach**: where the sculk broke through to the surface. **SP** `pit` with a sculk `field` and `minecraft:ancient_city/structures/*` pieces; `minecraft:ancient_city` (1.21.1 forced) below.
- **The graveyard** near spawn: `nova_structures:remnant_graveyard`.
- **Mausoleums and tombs**: `nova_structures:remnant_shrine_sentinel_mass_grave`, `epic:small_sand_dungeon`, `nova_structures:surface_dungeon_desert`.
- **The black spiral** near border: **EX** `explorify:black_spiral`; else `ati_structures:dark_tower`.
- **Ancient ruins** near border: `minecraft:trail_ruins`.
- **Ruins**: `nova_structures:wild_ruin`.

### the_dustbowl

- **The farmstead** in the north, the last holdout: **EX** `explorify:farmstead`; else **SP** `hovel` with `farm` (stand-in `nova_structures:remnant_ruin_farmer`).
- **The dusty tomb** (1.21.1 forced): `epic:small_sand_dungeon`.

### the_roothold

- **The creeping crypt and the wither cavern** (1.21.1 forced): `nova_structures:creeping_crypt`, `ati_structures:wither_cavern`.
- **The witches' experiment**: a lab the forest grew round. **SP** `hovel` with `works`, under a root `field`.
- **Forest ruins**: `mvs:log_ruin`.
- **Manor and mansion** near border: `ati_structures:manor`, `minecraft:mansion`.
- **Dungeons**: `ati_structures:spider_nest`, `jungle_grotto`, `silverfish_nest`, `rotting_temple_underground`.

### the_ruined_timberland

- **The harvest festival**: pumpkin ruins with the stalls still up, near spawn. **SP** `camp` (`abandoned`) with a `scatter` of pumpkins, hay and stalls (stand-in `nova_structures:remnant_ruin_farmer`).
- **Forest ruins** near spawn: `mvs:log_ruin`.
- **Ruins and the manor**: `nova_structures:wild_ruin`, `ati_structures:manor`.
- **The abandoned temple**: `ati_structures:rotting_temple`.
- **Graveyards and crypts**: `nova_structures:remnant_graveyard`, `small_undead_crypt`.
- **The ancient crypt and the mausoleum** near border: `nova_structures:undead_crypt`, `remnant_shrine_sentinel_mass_grave`.

### the_whispering_wilds

- **The old manor and the sculk dungeon** (1.21.1 forced): `ati_structures:manor`, `ati_structures:warden_monument`.
- **Abandoned camps** near spawn: `minecraft:abandoned_camp_old_growth_spruce_taiga`; **SP** `camp` (`abandoned`).
- **The campsite and the foraging outpost** near spawn: `mvs:campsite`, `ati_structures:overgrown_outpost`.
- **Fire towers**: `nova_structures:firewatch_tower_taiga`.
- **Forest, northern and taiga ruins** (buried): `mvs:log_ruin`, `mvs:small_ruin`, `nova_structures:remnant_taiga_castle`.

### the_rosebluff

- **Chapels on the bluffs**: `ati_structures:haunted_ruin`.
- **Shrine towers**, the pilgrimage sites: `nova_structures:shrine_tower`.
- **The guide post** near spawn: **EX** `explorify:guide_post_warm`; else `mvs:small_oak_lantern`.
- **The village** near border: `#minecraft:village`.

## Batch 6: overworld presets, better half and the two vanilla presets

### the_lantern_pools

- **The campsite and the ancient ruins** (1.21.1 forced): `mvs:campsite`, `minecraft:trail_ruins`.
- **Desert shrines**: **EX** `explorify:desert_shrine`; else `epic:sand_obelisk`.
- **Mirage outposts**: `ati_structures:desert_outpost`.
- **Chapels**: `ati_structures:haunted_ruin`.
- **The guide post** near spawn: **EX** `explorify:guide_post_warm`; else `mvs:small_oak_lantern`.

### the_chalk_meadows

- **The village** near spawn: `#minecraft:village` (`moat`).
- **Farmstead, guide post and campsite** near spawn: **EX** `explorify:farmstead`, `guide_post_warm`, `campsite`; else `nova_structures:remnant_ruin_farmer`, `mvs:small_oak_lantern`, `mvs:campsite`.
- **Taverns and chapels**: `nova_structures:tavern_*`, `ati_structures:haunted_ruin`.

### the_miredeep

- **The old manor** (1.21.1 forced): `ati_structures:manor`.
- **The mangrove hut** near spawn: **EX** `explorify:mangrove_hut`; else `nova_structures:mangrove_witch_hut`.
- **Sunken ruins**: **SP** `template` with `tilt` and `embed`; `mvs:small_swamp_house`.
- **The mausoleum and graveyards**: `nova_structures:remnant_shrine_sentinel_mass_grave`, `remnant_graveyard`.
- **The muddy dungeon** near border: `nova_structures:surface_dungeon_swamp`.
- **Abandoned camps**: `minecraft:abandoned_camp_swamp`.

### the_teal_corruption

- **The piglin village and the warped outpost** (1.21.1 forced): `towns_and_towers:exclusives/village_piglin`, `mvs:large_warped_tower`.
- **The centre where it came through**: **SP** `crater` with a warped `field`.
- **Outposts in the tide**: **SP** `hovel` in warped palette.
- **The greatsword, bastion and forge**: `mns:sword`, `minecraft:bastion_remnant`, `mns:crimson_forge`.
- **Bridges and strange outposts**: `mns:bridge_1`, `mns:warped_dome`.

### the_claymarsh

- **Things half-sunk in the clay**: **SP** `template` with `tilt` and `embed`.
- **Swamp ruins**: `mvs:small_swamp_house`.
- **The mangrove hut** near spawn: `nova_structures:mangrove_witch_hut`.
- **The muddy dungeon and ruins** near border: `nova_structures:surface_dungeon_swamp`, `wild_ruin`.
- **Graveyards and abandoned camps**: `nova_structures:remnant_graveyard`, `minecraft:abandoned_camp_swamp`.

### the_soulfields

- **The nether graveyard** at the edge of the empty plain: `mns:grave_yard` near spawn ring.
- **Grave fields** thickening to the border: **SP** `scatter` of graves under `radial`.
- **Giant skulls**: `mns:giant_skull` (`pedestal`).
- **The lost soul tower and the bone dungeon**: `nova_structures:nether_skeleton_tower_soul`, `nova_structures:catacomb`.
- **Bone pits**: **SP** `pit` of bone.

### the_dripping_pines

- **The foraging outpost** near spawn: `ati_structures:overgrown_outpost`.
- **Rotting camps**: `minecraft:abandoned_camp_taiga`; **SP** `camp` (`abandoned`, mossy palette).
- **Buried ruins and northern ruins**: `nova_structures:underground_house`, `mvs:small_ruin`.
- **The mouldy dungeon**: `ati_structures:rotting_temple_underground`.
- **The cold lair** near border: `epic:small_ice_dungeon`.

### the_ashgrove

- **The burned settlement**: **SP** `camp` and `hovel`s (`burned`).
- **The graveyard and the chapel** (1.21.1 forced): `nova_structures:remnant_graveyard`, `ati_structures:haunted_ruin`.
- **The abandoned camp** near spawn: `minecraft:abandoned_camp_savanna`.
- **Ruins**: `mvs:small_ruin`, `nova_structures:remnant_ruin_farmer`, `wild_ruin`.
- **Scorched tombs** near border: `nova_structures:surface_dungeon_desert`, `epic:small_sand_dungeon`.

### the_amplified_reaches

- **The fire tower** near spawn: `nova_structures:firewatch_tower_taiga`.
- **Keeps on the peaks**: `ati_structures:dark_keep`, wanted on summits (how a want prefers height is a question in the README).
- **Engineers' towers**: `ati_structures:steam_house`.
- **Wizard towers and ancient temples**: `ddd:wizard_tower`, `ati_structures:ancient_temple`.
- **Nomad camps and campsites**: `ati_structures:nomadic_camp`, `mvs:campsite`.
- **What they left in the valleys**: **SP** `camp` (`abandoned`) seated low.

### the_endless_expanse

- **The village, tavern, farmstead and mineshaft** near spawn: `#minecraft:village`, `nova_structures:tavern_*`, `nova_structures:remnant_ruin_farmer` (**EX** `explorify:farmstead`), `minecraft:mineshaft`.
- **The ruined portal** near spawn: `minecraft:ruined_portal`.
- **Outposts, watchtowers and pyramids** spread: `minecraft:pillager_outpost`, `nova_structures:firewatch_tower_forest`, `minecraft:desert_pyramid`.
- **The mansion, the coliseum and the ancient city** at the far edge: `minecraft:mansion`, `mss:arena`, `minecraft:ancient_city`.

## Terrain picks

### the_basalt_spires

- **The bastion** anchoring the centre (forced): `minecraft:bastion_remnant`.
- **Bridges slung between columns**: **SP** `line` (`bridge`) between pillar tops; `mns:bridge_1`–`6`.
- **Blackstone pillars and the greatsword**: `mns:large_blackstone_pillars`, `mns:sword`.
- **Nether towers**: `mns:nether_tower`.
- **The forge** near border: `mns:crimson_forge`.

### the_blossom_gardens

- **The mushroom house** (1.21.1 forced): `mvs:large_mushroom`.
- **The village and farmstead** near spawn: `#minecraft:village`, `nova_structures:remnant_ruin_farmer` (**EX** `explorify:farmstead`).
- **Taverns and chapels**: `nova_structures:tavern_cherry`, `ati_structures:haunted_ruin`.
- **Guide post and campsite** near spawn: `mvs:small_oak_lantern`, `mvs:campsite` (**EX** both).
- **The lighthouse** near border: `ati_structures:lighthouse`.

### the_crimson_nexus

- **The fortress punched through** (forced): `minecraft:fortress` in **SP** `crater` with a crimson `field`.
- **The forge** (forced): `mns:crimson_forge`.
- **Wizard towers and the ancient temple**: `ddd:wizard_tower`, `ati_structures:ancient_temple`.
- **The scorched mines**: `nova_structures:badlands_miner_outpost`.
- **The keep and bandit towers** near border: `ati_structures:dark_keep`, `ati_structures:illager_tower`.

### the_crucible

- **The tree house** (1.21.1 forced): `nova_structures:illager_treehouse`.
- **Jungle ruins and the underground camp** near spawn: `nova_structures:jungle_ruins`, `deepslate_camp`.
- **Infested temples**: `mtr:jungle_temple`.
- **Webbed, murky and lush dungeons**: `ati_structures:arachnid_dwelling`, `nova_structures:small_creeping_crypt`, `ati_structures:jungle_grotto`.
- **Trial chambers**: `minecraft:trial_chambers`.

### the_crystal_vale

- **Wizard towers** (forced): `ddd:wizard_tower`.
- **Shrine towers and chapels**: `nova_structures:shrine_tower`, `ati_structures:haunted_ruin`.
- **The guide post** near spawn: `mvs:small_oak_lantern` (**EX** `explorify:guide_post_warm`).
- **The relic temple** near border: `ati_structures:fortified_temple`.

### the_darkpine_depths

- **The graveyard** near spawn: `nova_structures:remnant_graveyard`.
- **Dark settlements**: `nova_structures:hamlet` (**EX** `explorify:dark_forest_settlement`), `witch_villa`.
- **The manor**: `ati_structures:manor`.
- **Dungeons**: `epic:small_plains_dungeon`, `ati_structures:spider_nest`.
- **Crypts and the mansion** near border: `nova_structures:creeping_crypt`, `undead_crypt`, `minecraft:mansion`.

### the_frozen_hearth

- **The village and igloo** near spawn: `#minecraft:village`, `minecraft:igloo`.
- **Campsite and guide post** near spawn: `mvs:campsite`, `mvs:small_spruce_lantern` (**EX** both).
- **Taiga outposts and fire towers**: `towns_and_towers:pillager_outpost_taiga`, `nova_structures:firewatch_tower_taiga`.
- **Dungeons out in the ice**: `nova_structures:surface_dungeon_snowy`; `epic:large_ice_dungeon`, `small_ice_dungeon` near border.

### the_frozen_strait

- **The igloo** (forced) and the guide post near spawn: `minecraft:igloo`, `mvs:small_spruce_lantern` (**EX**).
- **Wrecks in the ice**: `minecraft:shipwreck`; **SP** `template` of `minecraft:shipwreck/*` embedded in the sheet.
- **Ice dungeons**: `epic:medium_ice_dungeon`, `small_ice_dungeon`.
- **Ocean ruins**: `minecraft:ocean_ruin_cold`.
- **Buried treasure and the lighthouse** near border: `minecraft:buried_treasure`, `ati_structures:lighthouse`.

### the_gauntlet

- **The scorched mines** (forced): `nova_structures:badlands_miner_outpost`.
- **The wizard tower** near spawn: `ddd:wizard_tower`.
- **Ancient cities and trial chambers** (common): `minecraft:ancient_city`, `minecraft:trial_chambers`.
- **Large dungeons**: `epic:large_dungeon`, `epic:large_ice_dungeon`, `ddd:major_dungeon_*`.
- **The keep, the coliseum and the lone citadel** near border: `ati_structures:dark_keep`, `mss:arena`, `nova_structures:lone_citadel`.
- **Where other worlds press in**: **SP** `crown` of crystals and an ash `field` on the ridgelines.

### the_greenreach

- **Village, tavern, farmstead, guide post and campsite** near spawn: `#minecraft:village`, `nova_structures:tavern_oak`, `remnant_ruin_farmer`, `mvs:small_oak_lantern`, `mvs:campsite` (**EX** for the explorify three).
- **Watchtowers and honest mineshafts**: `nova_structures:firewatch_tower_forest`, `minecraft:mineshaft`.
- **Farms and windmills**: `mvs:windmill`, `mvs:barn`, `mvs:wooden_wheat_farm`.
- **The lighthouse** near border: `ati_structures:lighthouse`.

### the_gritlands

All 1.21.1 forced; nothing organised survives.

- **The ruined portal** near border: `minecraft:ruined_portal_mountain`.
- **Stone ruins**: `nova_structures:wild_ruin`.
- **The dead mineshaft**: `minecraft:mineshaft`.
- **The dungeon**: `epic:small_plains_dungeon`.

### the_highland_crossing

- **Guide posts on the passes** near spawn: **EX** `explorify:guide_post_cold`; else `mvs:small_spruce_lantern`.
- **Taiga outposts and the campsite** near spawn: `towns_and_towers:pillager_outpost_taiga`, `mvs:campsite`.
- **Fire towers and watchtowers**: `nova_structures:firewatch_tower_taiga`, `firewatch_tower_forest`.
- **Engineers' towers and nomad camps**: `ati_structures:steam_house`, `ati_structures:nomadic_camp`.
- **The border keeps and lighthouses**: `ati_structures:dark_keep`, `ati_structures:lighthouse`.

### the_needlefall

- **The fire tower** near spawn: `nova_structures:firewatch_tower_taiga` (`pedestal`).
- **Foraging outpost and campsite** near spawn: `ati_structures:overgrown_outpost`, `mvs:campsite`.
- **Taiga outposts and ruins**: `towns_and_towers:pillager_outpost_taiga`, `nova_structures:remnant_taiga_castle`, `mvs:small_ruin`.
- **The cold dungeon and the stray fort** near border: `nova_structures:surface_dungeon_snowy`, `nova_structures:stray_fort`.

### the_overgrowth

- **The mansion** swallowed (forced, `bury`): `minecraft:mansion`.
- **The reclaimed village**: `nova_structures:ruin_town`.
- **Jungle ruins and the abandoned camp** near spawn: `nova_structures:jungle_ruins`, `minecraft:abandoned_camp_bamboo_jungle`.
- **Infested temples and tree houses**: `mtr:jungle_temple`, `nova_structures:illager_treehouse`.
- **Dungeons**: `ati_structures:jungle_grotto`, `nova_structures:small_creeping_crypt`; `ati_structures:arachnid_dwelling` near border.
- **Giant mushrooms**: `mvs:large_mushroom`.

### the_scorched_mesa

- **The pyramids**: `mtr:badlands_temple` (**EX** `explorify:badlands_pyramid`).
- **The spirals**: **EX** `explorify:black_spiral`; else `ati_structures:dark_tower`.
- **Badlands and desert ruins**: `nova_structures:badlands_miner_outpost`, `nova_structures:desert_ruins`.
- **Tombs** near border: `epic:small_sand_dungeon`.

### the_shallows

- **Wrecks and buried treasure** near spawn: `minecraft:shipwreck`, `minecraft:buried_treasure`.
- **Drowned ruins**: `minecraft:ocean_ruin_warm`.
- **Lighthouses and ocean towers**: `ati_structures:lighthouse`, `mvs:ocean_tower`.
- **The ancient vessel and the wreckage**: `ati_structures:ancient_vessel`, `towns_and_towers:wreckage_ocean`.
- **The ocean fortress** near border: `mtr:ocean_temple`.

### the_stonemantle

- **The mineshaft** near spawn: `minecraft:mineshaft`.
- **Garrison outposts and engineers' towers**: `ati_structures:small_keep`, `ati_structures:steam_house`.
- **Quarries**: `ati_structures:quarry`.
- **Old workings**: **SP** `pit` with `line` (`rail`) and `column` props.
- **Fire towers**: `nova_structures:firewatch_tower_taiga`.
- **The ancient temple, the deep mines and the cold lair** near border: `ati_structures:ancient_temple`, `nova_structures:badlands_miner_outpost`, `epic:small_ice_dungeon`.

### the_sun_kingdoms

- **The desert village and the desert shrine** near spawn: `#minecraft:village`, `epic:sand_obelisk` (**EX** `explorify:desert_shrine`).
- **The walls round the villages**: **SP** `wall` ring forced at a forced village's coordinates.
- **The bandits' village**: `nova_structures:illager_barracks`.
- **Sand dungeons and the pyramid**: `epic:large_sand_dungeon`, `minecraft:desert_pyramid`.
- **Watchtowers, nomad and mirage outposts**: `nova_structures:firewatch_tower_forest`, `ati_structures:nomadic_camp`, `ati_structures:desert_outpost`.
- **The keep on the horizon and the tomb** near border: `ati_structures:dark_keep`, `epic:small_sand_dungeon`.

### the_twisted_groves

- **The greatsword** in the marsh (forced): `mns:sword`.
- **Warped outposts** near spawn: `mvs:large_warped_tower`.
- **Strange outposts**: `mns:warped_dome`.
- **Crimson fungus** near border: `mns:medium_crimson_fungus`.

### the_verdant_hollow

- **The village, farmstead, campsite and guide post** near spawn: `#minecraft:village`, `nova_structures:remnant_ruin_farmer`, `mvs:campsite`, `mvs:small_oak_lantern` (**EX** for the explorify three).
- **Giant mushrooms**: `mvs:large_mushroom`, `mvs:mushroom_statue`, `mvs:mushroom_well`.
- **Taverns and chapels**: `nova_structures:tavern_oak`, `ati_structures:haunted_ruin`.

### the_whitestone_ford

- **The village and farmstead** near spawn: `#minecraft:village` (`moat`), `nova_structures:remnant_ruin_farmer` (**EX**).
- **Lighthouses** along the water: `ati_structures:lighthouse`.
- **Watchtowers**: `nova_structures:firewatch_tower_forest`.
- **Guide post and campsite** near spawn: `mvs:small_oak_lantern`, `mvs:campsite` (**EX**).
- **Taverns**: `nova_structures:tavern_oak`.
