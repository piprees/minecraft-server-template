# Places

The plan for structures and set pieces in all 82 dimensions of the 26.3 rebuild.

## What a place is

The owner's ruling ([owner-review-1.md](../../../../mods/custom-dimensions-26/fidelity/feedback/owner-review-1.md)): a place is somewhere people would name: a fortress, a bastion, a pit full of bones, a huge lava waterfall, a crashed ship, a camp. Terrain and biome regions are the setting, not the place, and shots labelled as places must show one. Structures are essential to every dimension, and they never float in the void under a sky world's islands.

## Where the places come from

1. **The pack's structure mods, placed by our structure control.** The 26.3 pack registers 586 structures: 534 from nine structure mods through their reviewed bridges and 52 vanilla, every one sized. Every one of the 169 structures the 1.21.1 configs named is either kept (63) or has a 26.3 stand-in that places today (106); 12 of those stand-ins are a different thing. The pack did not lose its structure library; it lost the stories no structure mod ever told. [inventory.md](inventory.md)
2. **explorify, through dep-review.** The only one of the 24 waiting structure mods with a 26.3 Fabric build (`v1.6.6`, 2026-11-08); 28 dimensions named one of its structures, three of its stand-ins are weak. [explorify.md](explorify.md)
3. **Our own set pieces.** Places built at worldgen time from vanilla blocks and vanilla's own templates by id (the End ship, fossils, ruined portals, ancient-city pieces): craters, pits, falls, walls, camps, farms, works, cells, wrecks, skeletons, easter eggs. A generic, data-driven structure type of ours, placed by the same structure control. 36 story gaps need one. [set-pieces.md](set-pieces.md)

Every dimension's places and the source of each: [dimensions.md](dimensions.md).

## Work packages, in order

| Package | Size | Lane | What | Done when |
| --- | --- | --- | --- | --- |
| PL0 | ~120 steps | server | Wire the kept structures and stand-ins in [dimensions.md](dimensions.md) into the v2 definitions (`force`, `wants`, groups, zones); no code | Every listed mod structure of Batches 1–2 starts inside the player border on the review seed (census), none over void on the sky worlds |
| SP1 | ~180 | non-server, self-test | Places in the tools: census coordinates, `places.named`, place shots, the Places section in the review packet, map pins | Review shots show each named place first (set-pieces.md § SP1) |
| EX | ~60 | non-server, smoke test | explorify through dep-review: mod list, register, bridge, sizes | The join gate passes and explorify's structures start where wanted (explorify.md § Steps) |
| SP2 | ~180 | non-server, self-test | The set-piece structure type, schema, compiler; seats ground, floor, pinned; templates, scatter, chests, spawners | set-pieces.md § SP2 |
| SP3 | ~180 | non-server, self-test | Shapes: crater, pit, fall, wall, line, column, field, crown, platform | set-pieces.md § SP3 |
| SP4 | ~180 | non-server, self-test | Assemblies: wreck, skeleton, camp, hovel, farm, works, cell, gate; mobs and archaeology | set-pieces.md § SP4 |
| SP5 | ~180 | server | Places for Batches 1–2 (19 dimensions) | set-pieces.md § SP5 |
| SP6 | ~180 | server | Places for Batches 3–4 (22) | set-pieces.md § SP6 |
| SP7 | ~180 | server | Places for Batches 5–6 and the 21 terrain picks (41) | set-pieces.md § SP7 |

PL0 and SP1 run in parallel and need no new kit: the pack's own structures become places the reviews can see. EX can run beside them. SP2–SP4 are one kit build; SP5–SP7 author with it.

## What the owner decides

1. **explorify:** add it in the `v6.0.0` major, and for each of its 13 stand-ins keep the 26.3 stand-in or restore the explorify id. Proposed: restore the three weak ones (`farmstead`, `guide_post_warm`, `guide_post_cold`) and `campsite`, keep the rest.
2. **The 12 weak stand-ins** (a forge for a reactor, a bridge for a pipeline, a copper tower for a lab, a skull for a dragon's skeleton, a tower for a birdcage, a trial dungeon for a sky vault, a farm ruin for the harvest festival, a dome for the strange outpost, the donjon for the palace, and explorify's three): keep each placing until its set piece lands (proposed), or drop it now.
3. **The waiting mods' signature builds:** set pieces tell the stories no mod has; they do not rebuild a waiting mod's builds. The one where that bites is the_gilded_pit's palace (When Dungeons Arise): wait for its 26.3 build with the donjon standing in (proposed), or design a palace set piece.
4. **Easter eggs and spawners:** the_boneyard's buffed iron golem with its chest of iron, once per world; skeleton, ghast and bat spawners; skulls "very few".
5. **The piglin camp remnants and the witch hut** the owner floated for the_boneyard: proposed for the_ember_fields (the camp, as a burned outlier of the pacifists) and the_greywoods (the witch hut), keeping the boneyard dead.
6. **Over void:** a set piece whose footprint is not on ground declines (proposed), or builds a rock root down to the island below it.
7. **The capital's density** (the_forged_depths, "a SLOG"): structures packed so close their footprints touch, with the repetition ceiling raised for its `settlements` and `landmarks` groups.
8. **Vanilla templates by id:** set pieces reuse vanilla's own End ship, fossils, ruined portals, ancient-city, bastion, trail-ruin and shipwreck templates at worldgen time, never copied.

## Questions for the lead (a boot answers them)

1. Does a vanilla 26.3 client join with `customdimensions:set_piece` and `customdimensions:set_piece_part` registered? Structure types and piece types are not in vanilla's synced registries; the join gate confirms Fabric's registry sync leaves them out (SP2).
2. Does `StructureTemplateManager.getOrCreate("minecraft:end_city/ship")` resolve inside a runtime level created through Fantasy, during chunk generation?
3. Does a riding enderman stay put on 26.3 (the `cell` design holds captives in minecarts)?
4. Keeps on peaks (the_amplified_reaches, the_needlefall): should a want prefer height? That would extend `StructureSites`' predicate with a minimum surface height, the same column sampling the `ground` seat uses.
5. explorify: does its jar add to a synced registry (join gate); does Cristel Lib's config for it stay out of our structure control's way; which of its sets does the bridge tool list on 26.3?
6. The 26.3 mod list's `# === structures ===` header matches none of `never_automerge_sections` in `.github/dep-review/policy.json` (they name 1.21.1's `structures (need yungs-api)`); is that intended, or should towns-and-towers and dungeons-and-taverns updates route to the next-major PR as the other structure mods do?
