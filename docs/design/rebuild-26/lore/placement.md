# Placement

How places are put into the world so that they read as history rather than an algorithm, at each dimension's scale. The world is lived in and long past its events (RB-44); every place fits where it stands (RB-45); links are wordless (RB-43); most ground carries nothing (RB-42). What is told is in [stories.md](stories.md); the kit is [../places/set-pieces.md](../places/set-pieces.md); field status is checked against `mods/custom-dimensions-26/schema/definition.schema.json`.

Status words: **exists** (in the schema today), **planned** (specified in set-pieces.md, not yet in the schema), **gap** (in neither; a requirement below).

## Fitting the setting

Every place, ours or a pack mod's, meets five tests: it sits on its ground; it stands where its builders would choose; its materials suit the place or came from its makers' home; it is weathered to its age and scaled to the dimension; it has an approach. A mod structure that fails one is re-skinned, adapted or filtered; one that still cannot fit is not placed in that dimension.

| Kind | Terrain and height | Water | Shelter | Plausibly near | Approach |
| --- | --- | --- | --- | --- | --- |
| Village, town | gentle ground, slope under ~4 over the footprint, valley floor or low terrace | beside a river, lake or spring; never on a sea cliff | lee of a ridge or forest edge | fields, a ford, a road, a graveyard on the rise above | worn paths out to water and to the next settlement |
| Dense city, capital | a basin, a plateau or a pit the dimension is built round; packed footprints | its own wells or lava channels | walls or the pit's rim | its works, its holy place, its dead | a gate, a stair down from the arrival, a main street |
| Castle, keep, hold | a summit, spur or ridge end overlooking a pass, a ford or a valley mouth | a well inside; water within sight | none needed: it is the shelter | the pass or crossing it guards, a camp below, graves | a switchback path or stair up the spur |
| Fortress (Nether) | across a chasm or along a lava sea's shore at floor height | lava below, never perched over void | the cavern roof | bridges, towers, a bastion a valley away | bridges and corridors that touch ground at both ends |
| Bastion | a basin floor or a basalt delta, anchored to the ground below it | lava moat or none | under a roof | gold works, piglin farms, the dead of the war | a ramp or bridge to the cavern floor |
| Outpost, watchtower, lookout | a high point with a view along a road, pass or coast | none needed | none needed | the road or border it watches, a camp, targets | a path up from the road |
| Camp | a hollow, a bench above a stream, the foot of a cliff | within a short walk of water | an overhang, trees, a rock wall | a road, a dig, a ruin it was using, a grave | none beyond a worn path; that is the point |
| Farm, farmstead | flat, open, low; terraced on a slope | a stream or pond for its fields | a windbreak of trees | its settlement or a single house; a well; fence lines | a cart track to the road |
| Factory, works, foundry | flat or levelled ground on the resource: lava, ore, basalt | water or lava to quench | none | slag heaps, pipelines, barracks, a rail or chain line to a quarry | a road, rail or pipeline route |
| Lab | apart from the works, a little higher; or underground beside the thing studied | a cistern or cauldrons | walls, a cave | the thing studied (a breach, a cell), a works | a single path or stair; a sealed door |
| Dungeon, crypt, catacomb | under a ruin, a hill or a graveyard; entrance on a slope or in a cellar | dry; drowned only if the place flooded | underground | the ruin above it, a graveyard, older ruins under it | a stair down from a ruin or a cellar door |
| Lair, nest | a cave mouth, a ravine side, a forest floor under canopy | any | deep shade or rock | bones, a ruined camp, animal trails | none built; a trampled way in |
| Creepy place (graveyard, mausoleum, haunted ruin, witch hut) | a rise above a settlement, a dark wood, a marsh hummock | marsh edge for a hut | trees, fog, a hollow | the settlement whose dead they are, a chapel, a lone grave | a lych path; a plank walk over the marsh |
| Shrine, temple, chapel | a high point, a spring, a cliff top or an island's centre | a spring or pool where the description has one | none | a pilgrims' path, cairns, a well | steps up; a causeway |
| Tower (wizard, fire, lighthouse) | a peak, a ridge, a headland or a river mouth for a light | a light only at water | none | a path, a cairn, a settlement it serves | a path or stair; a jetty for a lighthouse |
| Cavern settlement | a cave floor with headroom, near light (glow, lava, shroomlight) | an underground pool | the cavern itself | a mine, a ladder shaft, a rail end | a tunnel, a ladder, a rail |
| Ship, wreck | on a reef, a shore, in ice; a crashed ship nose-down in its own trench | at or under water, or the trench it ploughed | none | debris along its line of fall, a cold hearth of survivors | the wreck line itself |
| Portal study, broken portal | where the target dimension would reach through (RB-41): Nether deep and hot, sky worlds on a peak, deeps near an ancient city, water worlds at the shore | per target | a sheltered study beside it | a camp, a grave on the hill, a quarry for the frame block | a path to the nearest road |
| Wall, bridge | along a ridge or across a chasm, both ends on ground | over water or lava for a bridge | none | the place it defends or connects | it is the approach |
| Vault, cell | in rock under a surface, behind a door or a seal | dry | rock | what guards it, what was taken from it | a sealed door; none for a grown vault |
| Grown things (P10) | island tops and edges, summits | none | none | nothing made | none: nobody came |

**Materials.** A place is built from the ground around it (the dimension's `materials` palette, by biome and depth) or from its makers' home: piglin places carry blackstone and gold wherever they stand; an enderman's material in a villager's hut is a trail mark (stories.md § 5). A mod structure whose palette contradicts both is re-skinned for that dimension.

**Scale.** A dimension under 1024 blocks of radius holds no place wider than about an eighth of its radius, except its namesake (the capital, the sanctum, the monument). A grand ruin is one a player would travel to see; a dimension has at most two, and the overworld about six.

## The overworld as a history map

Radius 8192, about 211 km². Distance from spawn reads as age: the present at the centre, the deepest past at the rim, where the description puts the ancient cities "near the border".

| Band | Radius (blocks) | Area | What it is | Lived-in signs | Trail places | Grand ruins |
| --- | --- | --- | --- | --- | --- | --- |
| Hearth | 0–1024 | 3.3 km² | the present: the village, the tavern, the campsite, the farmstead; the stone gate (trail 3) | about 12 per km², recent | the stone gate | none |
| Settled country | 1024–3072 | 26 km² | the joint age: roads, farms, watchtowers, honest mineshafts, swamp huts | about 4 per km², old | road fragments (3), a hut or two (5) | none |
| Frontier | 3072–5632 | 70 km² | the falling away: outposts, keeps on peaks, abandoned camps in valleys, trial chambers, monuments; most portal studies | about 1.5 per km², old and ancient (fence lines with no farm, hearths under moss) | about 3 outposts (4), 6–10 portal studies, the boneyard study (1) | two or three |
| Rim | 5632–8192 | 111 km² | older than everyone: the ancient cities deep below, the mansion, the coliseum, buried and layered ruins | about 0.5 per km², ancient and deep (buried, layered) | 2–3 digs over cities (2), the mansion (5) | the cities, the mansion, the coliseum |

The highest summit's birdcage (trail 7) is placed by height, not band. Band edges are noise-jittered so the map never shows a ring.

Means: `structures.wants` with `min`/`max` rings (**exists**; the overworld definition has no wants yet, PL0 wires them); `structures.radial` per group, ten points spawn to border (**exists**): settlements falling outward, ruins and dungeons rising; zones with an `inner`/`outer` ring and `structures.wants`/`shuns` lists (**exists**); `age` by band (**gap** G5); lived-in density by band (**gap** G6).

## Anchor and satellites

A trail's anchor is placed first, by `force` or a `want` (**exists**). Inside one dimension, satellites take their site from the anchor's, by terrain logic:

| Relation | Reads as | Distance (small world / overworld) |
| --- | --- | --- |
| `below` | a camp at the foot of the keep's spur | 32–128 / 64–400 |
| `on_approach` | a grave or marker on the way in | 48–192 / 100–800 |
| `in_sight` | a lookout that can see it, on the nearest high point | 64–256 / 200–1500 |
| `downstream`, `upstream` | a mill, a ford, a wreck along the water it stands on | 64–256 / 200–1500 |
| `on_pass` | the crossing between its valley and the next | 128–512 / 400–2000 |
| `across` | the other side of the chasm, strait or lake | the gap's width |

Across dimensions, satellites have no distance: they are linked by marks alone. Two anchors of different trails keep at least a quarter of the dimension's radius apart (1024 in the overworld), and the ground between them stays quiet.

Means: `structures.force` and `wants` for the anchor (**exists**); `story` on a set piece (**planned**, named in set-pieces.md § Deep-time links); satellite placement relative to the anchor, relations and anchor separation (**gap** G1, G2, G4); terrain predicates (**gap** G3).

## Age, shown the same way everywhere

Four ages. Every place of one age wears the same way, whichever kit built it, so that age reads without words and a player learns it.

| Age | Integrity | Burial | Weathering | Growth | Archaeology |
| --- | --- | --- | --- | --- | --- |
| Recent (camps, holdouts) | 0.9–1 | none | cracked here and there; doors open | grass at the footings | none |
| Old (the joint age, the war) | 0.7–0.85 | footings sunk 1–2 | stone to mossy and cracked; copper to weathered; planks missing; wool gone | vines, leaves, saplings in rooms | rare |
| Ancient (the empire, the cities' builders) | 0.5–0.7 | lower storey buried; `bury` | mossy and cracked throughout; copper oxidised; roofs gone | trees through floors; roots | common, in the buried storey |
| Deep (before everyone) | 0.3–0.5 | mostly buried; only tops show | eroded to rubble outlines | ground over it | most of what is left |

**Layering:** a younger place may stand on an older one, each at its own age: a camp (recent) on a trail ruin (old) on a buried hall (ancient). It is rare; a layered site is a find in itself.

Means: template `integrity`, `embed` and `processors` (**planned**, SP2); `terrainAdaptation` `bury`, `encapsulate`, `pedestal`, `platform_skirt`, `ground_blend` (**exists**); camp `state` (**planned**, SP4); `archaeology` (**planned**, SP4); one `age` value driving all columns, per element and by band (**gap** G5); seating one element on another (**gap** G7); applying age to mod structures (**gap** G13).

## Small dimensions

Most dimensions are a twentieth of the overworld's area or less; they carry one or two roles, never a full trail.

| Player radius | Dimensions | Roles each | Lived-in signs |
| --- | --- | --- | --- |
| 256 | 3 (the_emberglass_foundry, the_starwell, the_tidepools) | none beyond its own places | 0–2 |
| 512 | 10 | at most one satellite | 1–4 |
| 683 | 6 | anchor or one satellite | 2–6 |
| 1024 | 34 | anchor, or one or two satellites | 4–12 |
| 2048 | 6 | one or two | 8–20 |
| 4096 | 22 | one or two | 10–30 |
| 8192 | the overworld | anchor of trails 2 and 3, satellite of 1, 4, 5, 7 | about 300 |

The worlds in § What stays empty override these counts.

Trail roles: the_boneyard (anchor 1); the_burning_archipelago, the_wailing_narrows, the_bloodroot_wastes, the_ember_fields, the_molten_flats, the_soulfields, the_crumbling_reaches (1); the_underdark, the_sculked_beyond, the_souldrift, the_gauntlet (2); the_blossom_gardens, the_whitestone_ford, the_stonemantle (3); the_highland_crossing (3 and 4); the_lost_outpost (anchor 4), the_sun_kingdoms (4); the_crystal_vale (anchor 5), the_darkpine_depths, the_greywoods, the_roothold (5); the_gilded_pit (anchor 6), the_buried_age, the_weeping_vault, the_obsidian_sanctum (6), and the_slatemouth by its fall alone; the_aviary (anchor 7), the_snowbound_isle, the_pale_reach (7). The other 51 dimensions carry their own places and the lived-in layer only.

Each small world's way onward is its portal study and its found recipes, never a sign: a study for a trail's next world stands in the trail's dimensions more often than elsewhere.

## What stays empty

| Where | Why |
| --- | --- |
| Most of every world: open fields, most cliffs, most coasts and seas | a find means something only against emptiness (RB-42); no added wreck or treasure at sea beyond vanilla's own and the trails' |
| The hearth band's ruins | the present is lived in, not ruined; the stone gate is the one old thing |
| The ground between two trail anchors | stories do not stack |
| the_canvas | "No history, no ruins, no narrative"; not even the lived-in layer |
| the_slatemouth | the void is the place |
| the_starwell, the_red_monument | fixed, quiet worlds; nothing hunts and nothing is added |
| the_glacial_drift | "Nothing was ever built": grown things only, no lived-in signs |
| the_tidepools | one wreck is "the only evidence anyone else was ever here" |
| the_icebound_rift | the citadel alone |
| the_chalk_meadows | "nothing sinister for miles": no dungeon, sculk or study; lived-in signs only |
| the_end's central island | the owner's ruling: classic, unchanged |

## Means and their fields

| Means | Field | Status |
| --- | --- | --- |
| Rings by distance | `structures.wants` `{min, max}` | exists |
| Density by distance | `structures.radial` | exists |
| Band and region scoping | `zones[]` with `inner`/`outer`/`centre`, `structures.wants`/`shuns` | exists |
| Fixed anchors | `structures.force` (`x`, `z`, `y`, `radius`) | exists |
| Copy ceilings | `structures.repetition`, `rarity` | exists |
| Ground fit | `structures.terrainAdaptation` (incl. `bury`, `pedestal`, `platform_skirt`, `ground_blend`) | exists |
| Dimension palettes | `materials` | exists |
| Portal aura on studies | `portals[].aura.palette` | exists |
| Loot additions | `structures.loot` (item id, chance, structures) | exists, id only |
| Mob scaling | `difficulty.mobMultiplier`, `depthScaling`, `attributes` | exists, no proximity |
| Our places, seats, support, slope | `setPieces`, `seat`, `support`, `slope` | planned (SP2) |
| Aged templates | `template` `integrity`, `embed`, `tilt`, `processors` | planned (SP2) |
| Camps, hovels, gates, studies | `camp`, `hovel`, `gate` (`attempt` with `study`) | planned (SP4) |
| Archaeology, mobs, chests | `archaeology`, `mob`, `chest` | planned (SP4) |
| Story membership | `story` | planned, unspecified |
| Everything in the gaps below | — | gap |

## Gaps: requirements for the kit, DL1 and LR1

Requirements only; the design of each is its package's.

**SP2 (set-piece type, compiler, seats)**

- **G1 Stories.** A shared story library (`definitions/stories/<id>.json`) and a `story` field on set pieces and on any placed structure; the compiler emits a structure tag per story and per anchor, for maps and loot to target.
- **G2 Satellites.** A satellite places relative to its anchor's chosen site, by relation (`below`, `on_approach`, `in_sight`, `downstream`, `upstream`, `on_pass`, `across`) and a distance range, deterministic from the seed; anchors resolve first.
- **G3 Site filters, for every structure (ours and mods').** Slope; minimum and maximum surface height; relative height in a window (summit, spur, valley floor); distance to water, shore or river; distance to a cliff face; shelter (overhang, hollow, canopy); biome. Extends `StructureSites.accepts` (places README, question 4) and applies to wants, groups and zones.
- **G4 Separation.** A minimum distance between anchors of different stories, and a quiet radius round each anchor where groups place nothing beyond its satellites.
- **G5 Age.** One `age` value (`recent`, `old`, `ancient`, `deep`) on a set piece or element that sets integrity, burial, weathering swaps (stone to mossy or cracked, copper oxidation stage, planks dropped, wool removed), cobwebs, vines and archaeology share; a dimension default and a default by band (a radial).

**SP3 (shapes, seats)**

- **G6 The lived-in layer.** Tiny set pieces (half-size 8 or less) in a group whose density is per km², by band (a radial) and by biome, with siting relations: on a path line, at a view edge (a local high point with a drop), by water, in a hollow. A `line` style `path` that follows the terrain between two sites as worn path and coarse dirt.
- **G7 Layering.** An element seated on another element's top or inside its footprint, each with its own age.
- **G8 Approach.** An `approach` any place can carry, ours or a mod's: a path to the nearest path or site, steps cut into a slope, a bridge over a gap, a ladder or chain down a face, a debris line along a vector.
- **G9 Footings.** For ours and mods' alike: fill under a footprint down to ground in the dimension's own stone (never air stilts), cut terraces on a slope, and decline when the footprint's spread exceeds its slope limit rather than slice a cliff. A terrain-adaptation override per structure, beyond the vanilla set.

**SP4 (assemblies, things inside)**

- **G10 Things.** `banner` (standing or wall; base colour and pattern layers), `stand` (an armour stand in equipment), `frame` (an item frame holding an item or a filled map), `lectern` (a written book of a few lines from a text pool), `pot` (a decorated pot with sherds and contents), and a chest `arrangement` (fixed items in fixed slots) beside its loot table.
- **G11 Items with components** wherever items appear: trim pattern and material, dye colour, banner patterns, map decorations; checked at compile.
- **G12 Remains.** A preset: bones, a skull and a stand in gear, lying or seated; or a persistent skeleton holding the gear.
- **G13 Re-skin.** A per-dimension block-swap processor applied to mod structures by id or group through structure control, drawing on `materials`, and the same `age` weathering as our own pieces.

**DL1 (difficulty and loot)**

- **G14 Danger by nearness (RB-39).** A difficulty rule by distance to named structures, groups or story anchors: radius, curve and the attributes it raises.
- **G15 Loot by danger and size (RB-39).** Structure and buried-treasure loot scaled by the dimension's difficulty and border radius; easy and peaceful dimensions poorer.
- **G16 Loot entries with components and targets.** `structures.loot` takes item stacks with components and loot-table ids, and targets a story tag; story places add their marks (trim templates, sherds, banner patterns) at a chance.

**LR1 (portal lore)**

- **G17 Study placement.** For each target dimension, where its studies may stand in each source dimension (biomes, height band, depth, near a feature such as a fossil, lava or an ancient city) and how many; the partial frame from the target's `portals[0]` shape and `frameBlock`; the igniter precursor from a table keyed by `igniterItem`; the aura part-transform from `portals[0].aura.palette`.
- **G18 Unlabelled maps.** Exploration maps whose destination is a study or a story tag, with a red X and no name, in a share of buried-treasure and dungeon chests.
- **G19 Frame slates.** The RB-40 chest arrangement (frame shape in its block, the precursor in the middle) built from the target's `portals[0]`, using G10's `arrangement`.
- **G20 Words checked.** The study notes' text pool is a few fragmentary lines per study; the compiler refuses any line containing a dimension slug or display name, a people's name (piglin, enderman, villager, human, illager, witch) or a person's name (RB-43).
- **G21 Descriptions stay out of the game.** The definitions' `description` fields name peoples, dimensions and mods; no player-facing surface shows them.
