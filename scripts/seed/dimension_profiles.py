#!/usr/bin/env python3
"""dimension_profiles.py — per-dimension seed-roll scoring profiles.

Derives a measurement plan and a scoring profile for every dimension in
config/multiverse_config.json. This is the single source of truth for
WHAT gets measured (locate battery, biome probes, terrain grid) and HOW
candidates are judged (placement bands, terrain targets, weights).

Philosophy (from the worldgen brief):
  - Dimensions represent their namesake but are never single-biome.
  - Hard dims (dense + hostile + small playable radius) must be WORTH IT:
    hostile structures close, brutal terrain, places to hide/explore/fight.
  - Easy/peaceful dims are relaxing but not boring: scenery, variety,
    gentle structures.
  - Nether rule: the smaller the playable world (higher portal scale),
    the harder it should be; larger worlds are easier and more varied.
  - Voids are dead worlds: no terrain, but the biome layout is still
    there (mob spawning, sounds, fog) — variety of listed biomes matters.
  - Every playable radius = PLAYER_BORDER_RADIUS / portal scale; structure
    quality is judged by placement bands RELATIVE to that radius.

Used by score-dimensions.py (plan / score / finalise). No CLI here.
"""

# Vanilla world border radius the placement bands are relative to.
DEFAULT_BORDER_RADIUS = 8192

# Families share a locate battery (structure id -> band).
OVERWORLD_FAMILY = {"overworld", "multi_biome", "amplified", "large_biomes", "sky_islands"}
NETHER_FAMILY = {"nether", "nether_islands"}
END_FAMILY = {"end"}

# Placement bands as fractions of the playable radius — every band fits
# INSIDE the world. A structure the dimension shouldn't contain is a shun,
# not a far-away band (a 1024-radius wasteland has no "3000 blocks out").
BANDS = {
    "near_spawn": (0.00, 0.30),
    "spread": (0.15, 0.65),
    "near_border": (0.45, 1.00),
}

# structureDensity shifts placement expectations of WANTS.
DENSITY_SHIFT = {
    "dense": {"near_border": "spread", "spread": "near_spawn"},
    "sparse": {"near_spawn": "spread", "spread": "near_border"},
}

# Wants that make no sense in a peaceful dimension (dropped there; the
# peaceful generation overlay strips dungeons anyway).
HOSTILE_STRUCTURES = {
    "ancient_city", "trial_chambers", "fortress", "bastion", "sanctum",
    "coliseum", "mansion", "monument", "pillager_outpost", "bandit_towers",
    "bandit_village", "illager_fort", "keep_kayra", "infested_temple",
}

# Short name -> locate id. Every id verified against the worldgen registries
# extracted from the shipped jars/datapacks (Structory, Philip's Ruins,
# Explorify, Dungeons Plus, Epic Dungeons, Adventure Dungeons, YUNG's,
# Dungeons Arise, MNS, MES, MSS, Incendium, Nullscape).
STRUCTS = {
    # settlements + civilisation
    "village": "#minecraft:village", "tavern": "nova_structures:tavern_birch",
    "farmstead": "explorify:farmstead", "watchtower": "explorify:watchtower/plains",
    "guide_post_warm": "explorify:guide_post_warm", "guide_post_cold": "explorify:guide_post_cold",
    "campsite": "explorify:campsite", "dark_settlement": "explorify:dark_forest_settlement",
    "outcast_grassy": "structory:outcast_villager_grassy", "outcast_desert": "structory:outcast_villager_desert",
    "pillager_outpost": "minecraft:pillager_outpost", "pillager_lookout": "structory_towers:pillager_lookout",
    "nomad_outpost": "structory_towers:nomad_outpost", "taiga_outpost": "structory_towers:taiga_outpost",
    "foraging_outpost": "structory_towers:foraging_outpost", "mirage_outpost": "structory_towers:mirage_outpost",
    "quarter_outpost": "structory_towers:quarter_outpost", "engineer_tower": "structory_towers:engineer_tower",
    "firetower": "structory:firetower", "lighthouse": "structory_towers:lighthouse",
    # ruins + the-world-went-wrong
    "ruined_portal": "minecraft:ruined_portal", "mineshaft": "minecraft:mineshaft",
    "ruin_grassy": "structory:ruin_grassy", "swamp_ruin": "structory:swamp_ruin",
    "jungle_ruin": "structory:jungle_ruin", "forest_ruin": "structory:dense_forest_ruin",
    "northern_ruin": "structory:northern_ruin", "taiga_ruin": "structory:taiga_ruin_surface",
    "taiga_ruin_deep": "structory:taiga_ruin_underground",
    "abandoned_camp": "structory:abandoned_camp", "abandoned_chapel": "structory:abandoned_chapel",
    "graveyard": "structory:graveyard", "old_manor": "structory:old_manor",
    "field_ruins": "philipsruins:field_stone_ruins", "badlands_ruins": "philipsruins:badlands_structures",
    "ancient_ruins": "philipsruins:ancient_ruins", "ancient_crypt": "philipsruins:ancient_crypt",
    "pumpkin_ruins": "philipsruins:pumpkin_ruins",
    "mausoleum": "explorify:mausoleum", "ruins": "explorify:ruins",
    "ruins_desert": "adventuredungeons:ruins_desert", "ruins_snow": "adventuredungeons:ruins_snow",
    "ruins_standard": "adventuredungeons:ruins_standard",
    "desert_shrine": "explorify:desert_shrine", "badlands_pyramid": "explorify:badlands_pyramid",
    "black_spiral": "explorify:black_spiral", "mangrove_hut": "explorify:mangrove_hut",
    "supply_cache_desert": "explorify:supply_cache/desert",
    # dungeons
    "trial_chambers": "minecraft:trial_chambers", "ancient_city": "minecraft:ancient_city",
    "skeleton_dungeon": "betterdungeons:skeleton_dungeon", "spider_dungeon": "betterdungeons:spider_dungeon",
    "zombie_dungeon": "betterdungeons:zombie_dungeon",
    "cold_dungeon": "dungeons_plus:cold_dungeon", "frozen_dungeon": "dungeons_plus:frozen_dungeon",
    "lush_dungeon": "dungeons_plus:lush_dungeon", "muddy_dungeon": "dungeons_plus:muddy_dungeon",
    "webbed_dungeon": "dungeons_plus:webbed_dungeon", "infested_dungeon": "dungeons_plus:infested_dungeon",
    "mouldy_dungeon": "dungeons_plus:mouldy_dungeon", "dusty_tomb": "dungeons_plus:dusty_tomb",
    "scorched_tomb": "dungeons_plus:scorched_tomb", "deepwater_dungeon": "dungeons_plus:deepwater_dungeon",
    "ice_dungeon_l": "epic:large_ice_dungeon", "ice_dungeon_m": "epic:medium_ice_dungeon",
    "sand_dungeon_l": "epic:large_sand_dungeon", "sculk_dungeon": "philipsruins:sculk_dungeon",
    "bone_dungeon": "philipsruins:bone_dungeon", "underground_camp": "adventuredungeons:underground_camp",
    "coldlair": "adventuredungeons:coldlair", "murkydungeon": "adventuredungeons:murkydungeon",
    # epic
    "coliseum": "dungeons_arise:coliseum", "keep_kayra": "dungeons_arise:keep_kayra",
    "infested_temple": "dungeons_arise:infested_temple", "abandoned_temple": "dungeons_arise:abandoned_temple",
    "bandit_towers": "dungeons_arise:bandit_towers", "bandit_village": "dungeons_arise:bandit_village",
    "illager_fort": "dungeons_arise:illager_fort", "illager_campsite": "dungeons_arise:illager_campsite",
    "jungle_tree_house": "dungeons_arise:jungle_tree_house", "giant_mushroom": "dungeons_arise:giant_mushroom",
    "wizard_tower": "structory_towers:wizard_tower", "ancient_temple": "structory_towers:ancient_temple",
    "relic_temple": "structory_towers:sacred_relic_temple",
    "mansion": "minecraft:mansion", "monument": "minecraft:monument",
    # ocean / frozen vanilla
    "shipwreck": "minecraft:shipwreck", "buried_treasure": "minecraft:buried_treasure",
    "igloo": "minecraft:igloo", "desert_pyramid": "minecraft:desert_pyramid",
    "ocean_ruins": "philipsruins:ocean_ruins", "ocean_fortress": "philipsruins:ocean_fortress",
    "ocean_pillar": "structory_towers:ocean_pillar",
    # nether
    "fortress": "betterfortresses:fortress", "bastion": "minecraft:bastion_remnant",
    "sanctum": "incendium:sanctum", "forbidden_castle": "incendium:forbidden_castle",
    "piglin_village": "incendium:piglin_village", "nether_reactor": "incendium:nether_reactor",
    "ruined_lab": "incendium:ruined_lab", "infernal_altar": "incendium:infernal_altar",
    "nether_tower": "incendium:abandoned_tower", "pipeline": "incendium:pipeline",
    "giant_skull": "mns:giant_skull", "nether_graveyard": "mns:grave_yard",
    "crimson_forge": "mns:crimson_forge", "copper_tower": "mns:copper_tower",
    "blackstone_pillars": "mns:large_blackstone_pillars", "blackstone_walls": "mns:large_blackstone_walls",
    "nether_bridge": "mns:bridge_1", "crimson_well": "mns:crimson_lava_well",
    "crimson_fungus": "mns:medium_crimson_fungus", "nether_brick_hall": "mns:large_nether_brick",
    "warped_greatsword": "structory_towers:warped_greatsword",
    "warped_outpost": "structory_towers:nether/warped_outpost",
    "strange_outpost": "structory_towers:nether/strange_outpost",
    "nether_dungeon": "betterdungeons:small_nether_dungeon",
    "lost_soul_dungeon": "philipsruins:lost_soul_dungeon", "nether_lava_ruins": "philipsruins:nether_lava_ruins",
    "start_nether_ruin": "philipsruins:start_nether_ruin",
    # end
    "end_city": "minecraft:end_city", "phantom_citadel": "mes:phantom_citadel",
    "enderkeep": "mes:enderkeep_courtyard", "enderwatch_tower": "mes:enderwatch_tower",
    "ender_spire": "mes:ender_spire", "monolith": "mes:monolith",
    "ruined_pillar": "mes:ruined_pillar", "mystical_archway": "mes:mystical_archway",
    "manuscript_shrine": "mes:manuscript_shrine", "mythic_garden": "mes:mythic_garden",
    "astral_hideaway": "mes:astral_hideaway", "endscraps": "mes:endscraps",
    "mega_ship_crashed": "mes:mega_ship_crashed", "mega_ship_deepslate": "mes:mega_ship_crashed_deepslate",
    "dragon_skeleton": "nullscape:dragon_skeleton", "end_tower": "structory_towers:end/end_tower",
    "end_ruins": "philipsruins:end_ruins", "end_gate_fortress": "philipsruins:end_gate_fortress",
    # sky islands (MSS places on the floating islands)
    "sky_castle_ruin": "mss:castle_ruin", "sky_arena": "mss:arena",
    "sky_house": "mss:small_oak_house", "sky_volcano": "mss:volcano",
}

# What each mood is optimising for — shown in the viewer so a human can
# judge the judge.
MOOD_BLURBS = {
    "hard": "A hard world: hostile structures close enough to matter, brutal "
            "terrain with places to hide and fight. Going here must be worth it.",
    "adventurous": "Structure-led exploration: plenty to find at sane "
                   "distances, terrain interesting but traversable.",
    "dramatic": "Terrain is the star — high relief, craggy grain. Structures "
                "are seasoning, not the meal.",
    "scenic": "Stunning and iconic at spawn; gentle exploration, low threat.",
    "pastoral": "Rolling, liveable, buildable. Settlements near, dungeons far.",
    "serene": "Relaxing but not boring: soft terrain, gentle structures, "
              "no hostile pressure.",
    "desolate": "Empty and evocative — the namesake mood carries it; sparse "
                "everything, wide horizons.",
    "standard": "A believable, balanced world — variety, fair structure "
                "spread, vanilla-plus feel.",
}

# Mood -> component weights (namesake, variety, terrain, structures).
# Normalised at use; hostile-structure emphasis rides inside structures.
MOOD_WEIGHTS = {
    "hard": {"namesake": 15, "variety": 15, "terrain": 25, "structures": 45},
    "adventurous": {"namesake": 15, "variety": 15, "terrain": 20, "structures": 50},
    "dramatic": {"namesake": 20, "variety": 15, "terrain": 40, "structures": 25},
    "scenic": {"namesake": 30, "variety": 20, "terrain": 35, "structures": 15},
    "pastoral": {"namesake": 25, "variety": 20, "terrain": 30, "structures": 25},
    "serene": {"namesake": 30, "variety": 20, "terrain": 30, "structures": 20},
    "desolate": {"namesake": 30, "variety": 10, "terrain": 40, "structures": 20},
    "standard": {"namesake": 20, "variety": 20, "terrain": 30, "structures": 30},
}


def family_of(dim_type):
    if dim_type in OVERWORLD_FAMILY:
        return "overworld"
    if dim_type in NETHER_FAMILY:
        return "nether"
    if dim_type in END_FAMILY:
        return "end"
    if dim_type and ":" in dim_type:
        # Generic clone types (ns:path): family guess from the id.
        if "nether" in dim_type:
            return "nether"
        if "end" in dim_type:
            return "end"
        return "overworld"
    return None  # void / superflat


def world_family(dimension_id):
    """Family for a 'worlds' entry (vanilla + static mod dimensions)."""
    if dimension_id == "minecraft:the_nether":
        return "nether"
    if dimension_id == "minecraft:the_end":
        return "end"
    return "overworld"


def load_difficulty(config_path):
    """Per-dimension mob difficulty multipliers from
    config/configurable-difficulty/configurable-difficulty.json5 (sibling of
    the multiverse config). Tolerant of // comments; {} when absent."""
    import json
    import re
    from pathlib import Path
    p = Path(config_path).parent / "configurable-difficulty" / "configurable-difficulty.json5"
    if not p.exists():
        return {}
    text = re.sub(r"^\s*//.*$", "", p.read_text(), flags=re.M)
    try:
        data = json.loads(text)
    except json.JSONDecodeError:
        return {}
    return data.get("dimensionMultipliers", {})


def mood_from_difficulty(mult):
    if mult <= 0.0:
        return "serene"
    if mult <= 0.9:
        return "scenic"
    if mult <= 1.2:
        return "standard"
    if mult <= 1.7:
        return "adventurous"
    return "hard"


def rollable(dim):
    """A dimension is rollable unless it is a void/superflat WITHOUT biomes."""
    t = dim.get("type")
    if t in ("void", "superflat"):
        return bool(dim.get("biome"))
    return True


def portal_scales(config):
    return {p["targetDimension"].split(":", 1)[1]: float(p.get("scale", 1.0))
            for p in config.get("portals", [])}


def shifted_band(band, density):
    return DENSITY_SHIFT.get(density or "", {}).get(band, band)


def grid_pitch(radius):
    """Sample pitch so the 3x3 grid spans ~half the playable radius."""
    return max(64, min(512, int(radius / 4)))


def nether_difficulty(scale):
    """Nether rule: smaller playable world (bigger scale) = harder."""
    if scale >= 12:
        return "hard"
    if scale >= 8:
        return "adventurous"
    return "standard"


# Terrain targets by noiseSettings flavour (matched on the id's path so
# consumer preset namespaces work too): relief = max-min of grid heights,
# grain = mean |dh| between adjacent points, water = wet grid fraction.
TERRAIN_TARGETS = {
    "compressed": {"relief": (40, 160), "grain": (6, 26), "water": (0.0, 0.30)},
    "wide": {"relief": (10, 60), "grain": (0, 6), "water": (0.05, 0.45)},
    None: {"relief": (18, 90), "grain": (2, 14), "water": (0.0, 0.45)},
}


def terrain_targets_for(noise):
    key = (noise or "").rsplit(":", 1)[-1] or None
    return dict(TERRAIN_TARGETS.get(key, TERRAIN_TARGETS[None]))


# Generic wants for dimensions with NO seedRoll block (e.g. a consumer's
# own dimensions before they curate one). Deliberately modest.
DEFAULT_WANTS = {
    "overworld": {"village": "near_spawn", "mineshaft": "spread",
                  "trial_chambers": "spread", "ancient_city": "near_border"},
    "nether": {"fortress": "spread", "bastion": "spread"},
    "end": {"end_city": "spread"},
}


def resolve_struct(name):
    """wants/shuns entries are STRUCTS short names or raw '<ns>:<path>' ids."""
    return STRUCTS.get(name, name if ":" in name else None)


def build_profile(dim, config, difficulty=None):
    """Full per-dimension profile from the dimension's config entry — the
    'seedRoll' block (mood/spawnFilter/water/wants/shuns/description) is the
    single source of truth; sensible generics cover entries without one.
    Handles both runtime dimensions and 'worlds' entries (vanilla + static
    mod dimensions, marked by is_world/scale on the entry)."""
    name = dim["name"]
    is_world = "type" not in dim  # worlds entries have dimensionId only
    dim_type = dim.get("type", "world")
    if is_world:
        fam = world_family(dim.get("dimensionId", ""))
        scale = float(dim.get("scale", 1.0))
    else:
        fam = family_of(dim_type)
        scale = portal_scales(config).get(name, 1.0)
    radius = DEFAULT_BORDER_RADIUS / scale
    density = dim.get("structureDensity")
    peaceful = dim.get("hostileSpawning") is False
    noise = dim.get("noiseSettings")
    sr = dim.get("seedRoll") or {}
    config_biomes = [b.strip() for b in (dim.get("biome") or "").split(",") if b.strip()]

    # Mob difficulty (configurable-difficulty.json5) is the tiebreaker for
    # mood when the config doesn't set one, and always shown in the viewer.
    dim_id = dim.get("dimensionId", "")
    mob_difficulty = (difficulty or {}).get(dim_id)

    mood = sr.get("mood", "standard")
    if mood not in MOOD_WEIGHTS:
        mood = "standard"
    if "mood" not in sr:
        if mob_difficulty is not None:
            mood = mood_from_difficulty(mob_difficulty)
        elif fam == "nether":
            mood = nether_difficulty(scale)
        if peaceful:
            mood = "serene"
        elif density == "dense" and mood in ("standard", "adventurous"):
            mood = "adventurous"

    # Spawn identity: the seedRoll spawnFilter (candidates whose spawn misses
    # it are rejected); probes also cover the config list so a listed-but-
    # off-filter spawn still identifies itself in the data.
    namesake = list(sr.get("spawnFilter") or config_biomes[:4])
    spawn_probes = list(namesake)
    for b in config_biomes:
        if b not in spawn_probes:
            spawn_probes.append(b)

    # Biome variety battery: locate biome for listed biomes (voids,
    # multi_biome, islands); otherwise the spawn filter. Locate biome is
    # ~1s per call, so long lists are sampled evenly down to 8.
    variety_biomes = config_biomes if config_biomes else namesake[:4]
    if len(variety_biomes) > 8:
        step = len(variety_biomes) / 8.0
        variety_biomes = [variety_biomes[int(i * step)] for i in range(8)]

    # Structure battery: wants (band-scored, density-shifted; peaceful drops
    # hostile ones) + shuns (presence inside the radius costs points).
    wants = sr.get("wants") if ("wants" in sr or "shuns" in sr) else DEFAULT_WANTS.get(fam or "", {})
    battery = []
    for sname, band in (wants or {}).items():
        sid = resolve_struct(sname)
        if sid is None or band not in BANDS:
            continue
        if peaceful and sname in HOSTILE_STRUCTURES:
            continue
        battery.append((sname, sid, shifted_band(band, density), "want"))
    for sname in sr.get("shuns", []):
        sid = resolve_struct(sname)
        if sid is not None:
            battery.append((sname, sid, None, "shun"))

    terrain = terrain_targets_for(noise)
    # Mood modulation: hard/dramatic want more violence, serene less.
    if mood in ("hard", "dramatic"):
        lo, hi = terrain["relief"]
        terrain["relief"] = (lo * 1.25, hi * 1.4)
        lo, hi = terrain["grain"]
        terrain["grain"] = (max(lo, 3), hi * 1.3)
    elif mood in ("serene", "pastoral"):
        lo, hi = terrain["relief"]
        terrain["relief"] = (lo * 0.7, hi * 0.8)
    # Water preference overrides.
    wpref = sr.get("water")
    if wpref == "sea":
        terrain["water"] = (0.5, 1.0)
    elif wpref == "high":
        terrain["water"] = (0.25, 0.8)
    elif wpref == "none":
        terrain["water"] = (0.0, 0.10)

    is_void = dim_type == "void"
    is_islands = dim_type in ("sky_islands", "nether_islands") \
        or "paradise_lost" in (dim_type or "") \
        or dim.get("dimensionId") == "paradise_lost:paradise_lost"

    weights = dict(MOOD_WEIGHTS[mood])
    if is_void:
        # No terrain in a void — variety and namesake carry the score.
        weights = {"namesake": 30, "variety": 55, "terrain": 15, "structures": 0}
    elif mob_difficulty is not None and mob_difficulty >= 2.0:
        # Very dangerous worlds must be WORTH IT: structures matter more.
        weights["structures"] += 10
        weights["namesake"] = max(5, weights["namesake"] - 5)
        weights["variety"] = max(5, weights["variety"] - 5)

    return {
        "name": name,
        "blurb": sr.get("description")
        or MOOD_BLURBS[mood] + (" (Void: no terrain generates, but the biome layout"
                                " is real — variety and namesake carry the score.)"
                                if is_void else ""),
        "type": dim_type,
        "family": fam,
        "is_world": is_world,
        "dimension_id": dim.get("dimensionId", ""),
        "mob_difficulty": mob_difficulty,
        "scale": scale,
        "radius": radius,
        "density": density,
        "peaceful": peaceful,
        "noise": noise,
        "mood": mood,
        "spawn_probes": spawn_probes,
        "namesake": sorted(namesake),
        "variety_biomes": variety_biomes,
        "battery": battery,
        "terrain": terrain,
        "weights": weights,
        "is_void": is_void,
        "is_islands": is_islands,
        "grid_pitch": grid_pitch(radius),
        "create_args": {
            "type": dim_type,
            "noiseSettings": noise,
            "structureDensity": density,
            "biome": dim.get("biome"),
        },
    }
