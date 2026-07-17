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

# Placement bands as fractions of the playable radius.
BANDS = {
    "near_spawn": (0.00, 0.30),
    "spread": (0.20, 0.70),
    "near_border": (0.50, 1.00),
    "beyond_border": (0.80, 3.00),
}

# structureDensity shifts placement expectations.
DENSITY_SHIFT = {
    "dense": {"near_border": "spread", "beyond_border": "near_border"},
    "sparse": {"near_spawn": "spread", "spread": "near_border"},
}

# Structures that make no sense in a peaceful dimension.
HOSTILE_STRUCTURES = {
    "ancient_city", "trial_chambers", "fortress", "bastion", "sanctum",
    "wda", "mansion", "monument",
}

# name -> (locate id, band). Ids verified against the shipped mod set
# (Dungeons and Taverns uses the nova_structures namespace).
BATTERY = {
    "overworld": [
        ("village", "#minecraft:village", "near_spawn"),
        ("tavern", "nova_structures:tavern_birch", "near_spawn"),
        ("mineshaft", "minecraft:mineshaft", "near_spawn"),
        ("trial_chambers", "minecraft:trial_chambers", "spread"),
        ("ancient_city", "minecraft:ancient_city", "near_border"),
        ("monument", "minecraft:monument", "near_border"),
        ("mansion", "minecraft:mansion", "beyond_border"),
        ("wda", "dungeons_arise:coliseum", "beyond_border"),
    ],
    "nether": [
        ("fortress", "betterfortresses:fortress", "spread"),
        ("bastion", "minecraft:bastion_remnant", "spread"),
        ("sanctum", "incendium:sanctum", "near_border"),
    ],
    "end": [
        ("end_city", "minecraft:end_city", "spread"),
    ],
}

# Terrain targets (relief = max-min of grid heights, grain = mean |dh|
# between adjacent grid points, water = fraction of grid points with
# water at y=62). Keyed by noiseSettings.
TERRAIN_TARGETS = {
    "adventure:compressed": {"relief": (40, 160), "grain": (6, 26), "water": (0.0, 0.30)},
    "adventure:wide": {"relief": (10, 60), "grain": (0, 6), "water": (0.05, 0.45)},
    None: {"relief": (18, 90), "grain": (2, 14), "water": (0.0, 0.45)},
}

# Namesake biome lists. spawn = biomes that would sell the name at 0,0
# (ordered, most iconic first); the first N are also probed on the grid.
# Dims with a config biome list use that list for variety measurement;
# these spawn lists still say which of them should greet you at spawn.
# Water preference overrides ("high" for sea dims, "none" for dry ones)
# adjust the terrain water target.
THEMES = {
    # --- overworld dims ---
    "the_claymarsh": {"spawn": ["minecraft:swamp", "minecraft:mangrove_swamp", "terralith:orchid_swamp"], "mood": "serene", "water": "high"},
    "the_scorched_mesa": {"spawn": ["minecraft:badlands", "minecraft:eroded_badlands", "minecraft:wooded_badlands", "terralith:desert_canyon"], "mood": "dramatic", "water": "none"},
    "the_gritlands": {"spawn": ["minecraft:desert", "terralith:steppe", "terralith:brushland"], "mood": "desolate", "water": "none"},
    "the_roothold": {"spawn": ["minecraft:dark_forest", "minecraft:old_growth_spruce_taiga", "terralith:shield"], "mood": "standard"},
    "the_overgrowth": {"spawn": ["minecraft:jungle", "terralith:cloud_forest", "minecraft:dark_forest"], "mood": "dramatic"},
    "the_greenreach": {"spawn": ["minecraft:plains", "minecraft:sunflower_plains", "minecraft:meadow", "terralith:blooming_plateau"], "mood": "pastoral"},
    "the_rosebluff": {"spawn": ["minecraft:cherry_grove", "terralith:sakura_grove", "terralith:white_cliffs"], "mood": "scenic"},
    "the_greywoods": {"spawn": ["minecraft:dark_forest", "minecraft:taiga", "terralith:birch_taiga"], "mood": "standard"},
    "the_miredeep": {"spawn": ["minecraft:swamp", "minecraft:mangrove_swamp", "terralith:orchid_swamp"], "mood": "desolate", "water": "high"},
    "the_verdant_hollow": {"spawn": ["terralith:lush_valley", "minecraft:meadow", "minecraft:forest"], "mood": "pastoral"},
    "the_whitestone_ford": {"spawn": ["minecraft:river", "terralith:white_cliffs", "minecraft:plains"], "mood": "pastoral", "water": "high"},
    "the_needlefall": {"spawn": ["minecraft:old_growth_pine_taiga", "minecraft:taiga", "minecraft:grove"], "mood": "dramatic"},
    "the_chalk_meadows": {"spawn": ["minecraft:meadow", "minecraft:plains", "terralith:blooming_plateau"], "mood": "pastoral"},
    "the_stonemantle": {"spawn": ["minecraft:stony_peaks", "minecraft:windswept_hills", "terralith:rocky_mountains"], "mood": "dramatic", "water": "none"},
    "the_ashgrove": {"spawn": ["terralith:ashen_savanna", "terralith:volcanic_peaks", "minecraft:taiga"], "mood": "standard"},
    "the_crystal_vale": {"spawn": ["terralith:amethyst_rainforest", "terralith:amethyst_canyon", "terralith:emerald_peaks"], "mood": "scenic"},
    "the_darkpine_depths": {"spawn": ["minecraft:old_growth_spruce_taiga", "minecraft:dark_forest", "terralith:moonlight_grove"], "mood": "standard"},
    "the_dripping_pines": {"spawn": ["minecraft:old_growth_pine_taiga", "terralith:siberian_taiga", "minecraft:taiga"], "mood": "standard"},
    "the_ruined_timberland": {"spawn": ["minecraft:forest", "minecraft:birch_forest", "minecraft:dark_forest"], "mood": "adventurous"},
    "the_shallows": {"spawn": ["minecraft:warm_ocean", "minecraft:lukewarm_ocean", "minecraft:beach"], "mood": "serene", "water": "sea"},
    "the_lantern_pools": {"spawn": ["minecraft:lush_caves", "terralith:desert_oasis", "minecraft:warm_ocean"], "mood": "serene", "water": "high"},
    "the_dustbowl": {"spawn": ["minecraft:desert", "terralith:steppe", "terralith:brushland"], "mood": "desolate", "water": "none"},
    "the_lost_outpost": {"spawn": ["minecraft:savanna", "minecraft:plains", "minecraft:taiga"], "mood": "adventurous"},
    "the_frozen_strait": {"spawn": ["minecraft:frozen_ocean", "minecraft:deep_frozen_ocean", "minecraft:snowy_beach"], "mood": "desolate", "water": "sea"},
    "the_glacial_drift": {"spawn": ["minecraft:snowy_plains", "minecraft:ice_spikes", "terralith:glacial_chasm"], "mood": "desolate"},
    "the_sunken_temple": {"spawn": ["minecraft:warm_ocean", "minecraft:deep_lukewarm_ocean", "minecraft:jungle"], "mood": "adventurous", "water": "sea"},
    "the_snowbound_isle": {"spawn": ["minecraft:snowy_plains", "minecraft:snowy_taiga", "minecraft:snowy_beach"], "mood": "standard", "water": "high"},
    "the_abyssal_shrine": {"spawn": ["minecraft:deep_ocean", "minecraft:deep_cold_ocean", "minecraft:deep_lukewarm_ocean"], "mood": "adventurous", "water": "sea"},
    "the_pale_reach": {"spawn": ["minecraft:snowy_plains", "minecraft:ice_spikes", "terralith:frozen_cliffs"], "mood": "desolate"},
    "the_violet_spire": {"spawn": ["terralith:amethyst_canyon", "terralith:haze_mountain", "minecraft:jagged_peaks"], "mood": "dramatic"},
    "the_amplified_reaches": {"spawn": ["minecraft:windswept_hills", "minecraft:jagged_peaks", "minecraft:stony_peaks"], "mood": "dramatic"},
    "the_endless_expanse": {"spawn": ["minecraft:plains", "minecraft:forest", "minecraft:desert", "minecraft:taiga"], "mood": "standard"},
    # --- nether dims ---
    "the_furnace_halls": {"spawn": ["minecraft:nether_wastes", "minecraft:basalt_deltas"], "mood": "adventurous"},
    "the_bloodroot_wastes": {"spawn": ["minecraft:crimson_forest", "incendium:weeping_valley"], "mood": "standard"},
    "the_basalt_spires": {"spawn": ["minecraft:basalt_deltas", "incendium:volcanic_deltas"], "mood": "dramatic"},
    "the_blackstone_keep": {"spawn": ["minecraft:basalt_deltas", "minecraft:nether_wastes"], "mood": "hard"},
    "the_molten_flats": {"spawn": ["minecraft:nether_wastes", "incendium:infernal_dunes", "incendium:volcanic_deltas"], "mood": "standard"},
    "the_obsidian_sanctum": {"spawn": ["minecraft:soul_sand_valley", "minecraft:nether_wastes"], "mood": "hard"},
    "the_ember_fields": {"spawn": ["minecraft:crimson_forest", "minecraft:nether_wastes"], "mood": "standard"},
    "the_twisted_groves": {"spawn": ["minecraft:warped_forest", "incendium:inverted_forest"], "mood": "standard"},
    "the_soulfields": {"spawn": ["minecraft:soul_sand_valley", "incendium:ash_barrens"], "mood": "standard"},
    "the_blighted_maw": {"spawn": ["incendium:toxic_heap", "minecraft:soul_sand_valley", "incendium:withered_forest"], "mood": "hard"},
    "the_teal_corruption": {"spawn": ["minecraft:warped_forest", "incendium:inverted_forest"], "mood": "standard"},
    "the_weeping_vault": {"spawn": ["incendium:weeping_valley", "minecraft:crimson_forest"], "mood": "hard"},
    "the_boneyard": {"spawn": ["minecraft:soul_sand_valley", "incendium:ash_barrens"], "mood": "hard"},
    "the_buried_age": {"spawn": ["minecraft:nether_wastes", "incendium:quartz_flats"], "mood": "adventurous"},
    "the_luminous_caverns": {"spawn": ["minecraft:nether_wastes", "incendium:quartz_flats", "minecraft:crimson_forest"], "mood": "serene"},
    "the_fungal_lanterns": {"spawn": ["minecraft:crimson_forest", "minecraft:warped_forest"], "mood": "serene"},
    "the_gilded_pit": {"spawn": ["minecraft:nether_wastes", "minecraft:basalt_deltas"], "mood": "hard"},
    "the_wailing_narrows": {"spawn": ["minecraft:soul_sand_valley", "minecraft:basalt_deltas"], "mood": "standard"},
    "the_forged_depths": {"spawn": ["minecraft:basalt_deltas", "incendium:volcanic_deltas"], "mood": "hard"},
    # --- end dims ---
    "the_end_citadel": {"spawn": ["minecraft:end_highlands", "minecraft:end_midlands"], "mood": "hard"},
    "the_tiled_expanse": {"spawn": ["minecraft:end_midlands", "minecraft:end_barrens"], "mood": "standard"},
    "the_pillared_void": {"spawn": ["minecraft:small_end_islands", "minecraft:end_barrens"], "mood": "desolate"},
    "the_catalyst_maw": {"spawn": ["minecraft:end_highlands", "minecraft:end_midlands"], "mood": "hard"},
    "the_crumbling_reaches": {"spawn": ["minecraft:end_highlands", "minecraft:end_barrens"], "mood": "hard"},
    "the_red_monument": {"spawn": ["minecraft:end_barrens", "minecraft:end_midlands"], "mood": "standard"},
    "the_fractured_halls": {"spawn": ["minecraft:end_highlands", "minecraft:end_midlands"], "mood": "hard"},
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
    return None  # void / superflat


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


def build_profile(dim, config):
    """Full per-dimension profile: measurement plan + scoring parameters."""
    name = dim["name"]
    dim_type = dim.get("type")
    fam = family_of(dim_type)
    scales = portal_scales(config)
    scale = scales.get(name, 1.0)
    radius = DEFAULT_BORDER_RADIUS / scale
    density = dim.get("structureDensity")
    peaceful = dim.get("hostileSpawning") is False
    noise = dim.get("noiseSettings")
    theme = THEMES.get(name, {})
    config_biomes = [b.strip() for b in (dim.get("biome") or "").split(",") if b.strip()]

    mood = theme.get("mood", "standard")
    if fam == "nether" and mood == "standard":
        mood = nether_difficulty(scale)
    if peaceful:
        mood = "serene"
    if density == "dense" and mood in ("standard", "adventurous"):
        mood = "adventurous"

    # Spawn probe list: namesake first, then (for listed dims) the config
    # biomes so a non-namesake-but-listed spawn still identifies itself.
    spawn_probes = list(theme.get("spawn", []))
    for b in config_biomes:
        if b not in spawn_probes:
            spawn_probes.append(b)
    namesake = set(theme.get("spawn", []) or config_biomes[:4])

    # Biome variety battery: locate biome for listed biomes (voids,
    # multi_biome, islands); otherwise the namesake list. Locate biome is
    # ~1s per call, so long lists are sampled evenly down to 8.
    variety_biomes = config_biomes if config_biomes else list(theme.get("spawn", []))[:4]
    if len(variety_biomes) > 8:
        step = len(variety_biomes) / 8.0
        variety_biomes = [variety_biomes[int(i * step)] for i in range(8)]

    # Structure battery with bands shifted by density; peaceful drops
    # hostile structures.
    battery = []
    if fam:
        for sname, sid, band in BATTERY[fam]:
            if peaceful and sname in HOSTILE_STRUCTURES:
                continue
            battery.append((sname, sid, shifted_band(band, density)))

    terrain = dict(TERRAIN_TARGETS.get(noise, TERRAIN_TARGETS[None]))
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
    wpref = theme.get("water")
    if wpref == "sea":
        terrain["water"] = (0.5, 1.0)
    elif wpref == "high":
        terrain["water"] = (0.25, 0.8)
    elif wpref == "none":
        terrain["water"] = (0.0, 0.10)

    is_void = dim_type == "void"
    is_islands = dim_type in ("sky_islands", "nether_islands")

    weights = dict(MOOD_WEIGHTS[mood])
    if is_void:
        # No terrain in a void — variety and namesake carry the score.
        weights = {"namesake": 30, "variety": 55, "terrain": 15, "structures": 0}

    return {
        "name": name,
        "blurb": MOOD_BLURBS[mood] + (" (Void: no terrain generates, but the biome layout"
                                      " is real — variety and namesake carry the score.)"
                                      if is_void else ""),
        "type": dim_type,
        "family": fam,
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
