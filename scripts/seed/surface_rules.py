#!/usr/bin/env python3
"""surface_rules.py — Biome surface colours + vegetation density for the map renderer.

Maps biome IDs to surface block colours (MC official map colour system) and
vegetation density multipliers. Replaces flat biome colours with terrain-accurate
appearance when viewed from above.

Usage:
    python3 surface_rules.py              # print colour table for all known biomes
    python3 surface_rules.py --format csv # CSV output
"""

# MC MaterialColor base colours (shade variant 2 = ×1.0, the brightest).
# Source: net.minecraft.world.level.material.MapColor in 1.21.1.
# We store at ×0.86 (shade 1, "normal") to match MC's default map rendering.
SURFACE_COLOURS = {
    "grass":          (109, 153, 48),   # MaterialColor.GRASS × 0.86
    "sand":           (213, 201, 140),  # MaterialColor.SAND × 0.86
    "red_sand":       (168, 88, 36),    # MaterialColor.COLOR_ORANGE × 0.86
    "stone":          (104, 104, 104),  # MaterialColor.STONE × 0.86
    "snow":           (220, 220, 220),  # MaterialColor.SNOW × 0.86
    "mycelium":       (128, 94, 168),   # MaterialColor.COLOR_PURPLE × 0.86
    "podzol":         (111, 74, 42),    # MaterialColor.PODZOL × 0.86
    "mud":            (92, 75, 57),     # MaterialColor.COLOR_BROWN-ish
    "gravel":         (109, 109, 109),  # MaterialColor.STONE variant
    "water":          (55, 55, 220),    # MaterialColor.WATER × 0.86
    "water_warm":     (55, 150, 220),   # warm bias
    "water_cold":     (40, 40, 195),    # cold bias
    "water_frozen":   (150, 160, 210),  # ice tint
    "netherrack":     (112, 2, 0),      # MaterialColor.NETHER × 0.86
    "crimson_nylium": (148, 63, 63),    # MaterialColor.CRIMSON_NYLIUM × 0.86
    "warped_nylium":  (22, 126, 134),   # MaterialColor.WARPED_NYLIUM × 0.86
    "soul_sand":      (80, 60, 40),     # MaterialColor.COLOR_BROWN × 0.86
    "basalt":         (60, 60, 60),     # MaterialColor.DEEPSLATE × 0.86
    "end_stone":      (213, 201, 140),  # MaterialColor.SAND × 0.86 (end_stone uses SAND)
    "void":           (20, 20, 30),
}

# MC MaterialColor shade multipliers (applied to base colour)
MC_SHADE_DARK = 0.71    # height < north neighbour
MC_SHADE_NORMAL = 0.86  # height == north neighbour (our base)
MC_SHADE_BRIGHT = 1.0   # height > north neighbour

# Leaf block colours (MaterialColor.PLANT × biome grass tint).
# These are the top-down colours you see in unmined for tree canopies.
LEAF_COLOURS = {
    "oak":       (55, 113, 37),
    "birch":     (108, 140, 55),
    "spruce":    (40, 75, 32),
    "dark_oak":  (40, 90, 28),
    "jungle":    (48, 130, 34),
    "acacia":    (90, 130, 42),
    "cherry":    (220, 140, 175),
    "mangrove":  (55, 95, 35),
    "azalea":    (70, 115, 45),
    "pale_oak":  (145, 155, 140),
    # Nether
    "crimson":   (148, 20, 20),
    "warped":    (20, 110, 110),
    # Paradise Lost / modded
    "wisteria":  (155, 115, 180),
    "highlands": (60, 120, 50),
}
TRUNK_COLOUR = (100, 72, 36)

# Per-biome tree type mix and canopy coverage (0.0 = no trees, 1.0 = solid canopy).
# Format: (coverage, [(tree_type, weight), ...])
BIOME_TREES = {
    # Overworld forests
    "minecraft:forest":              (0.75, [("oak", 4), ("birch", 1)]),
    "minecraft:flower_forest":       (0.65, [("oak", 3), ("birch", 2)]),
    "minecraft:birch_forest":        (0.70, [("birch", 1)]),
    "minecraft:old_growth_birch_forest": (0.75, [("birch", 1)]),
    "minecraft:dark_forest":         (0.88, [("dark_oak", 4), ("oak", 1)]),
    "minecraft:taiga":               (0.65, [("spruce", 1)]),
    "minecraft:snowy_taiga":         (0.60, [("spruce", 1)]),
    "minecraft:old_growth_pine_taiga": (0.78, [("spruce", 1)]),
    "minecraft:old_growth_spruce_taiga": (0.82, [("spruce", 1)]),
    "minecraft:jungle":              (0.90, [("jungle", 1)]),
    "minecraft:sparse_jungle":       (0.45, [("jungle", 1)]),
    "minecraft:bamboo_jungle":       (0.85, [("jungle", 1)]),
    "minecraft:cherry_grove":        (0.55, [("cherry", 1)]),
    "minecraft:grove":               (0.50, [("spruce", 1)]),
    "minecraft:windswept_forest":    (0.55, [("oak", 1), ("spruce", 1)]),
    "minecraft:savanna":             (0.15, [("acacia", 1)]),
    "minecraft:savanna_plateau":     (0.20, [("acacia", 1)]),
    "minecraft:windswept_savanna":   (0.10, [("acacia", 1)]),
    "minecraft:swamp":               (0.40, [("oak", 1)]),
    "minecraft:mangrove_swamp":      (0.80, [("mangrove", 1)]),
    "minecraft:wooded_badlands":     (0.35, [("oak", 1)]),
    "minecraft:pale_garden":         (0.75, [("pale_oak", 1)]),
    "minecraft:meadow":              (0.08, [("oak", 1)]),
    "minecraft:plains":              (0.04, [("oak", 1)]),
    "minecraft:sunflower_plains":    (0.04, [("oak", 1)]),
    # Terralith
    "terralith:forested_highlands":  (0.70, [("spruce", 2), ("oak", 1)]),
    "terralith:cloud_forest":        (0.80, [("oak", 2), ("spruce", 1)]),
    "terralith:alpine_highlands":    (0.35, [("spruce", 1)]),
    "terralith:siberian_taiga":      (0.72, [("spruce", 1)]),
    "terralith:shield":              (0.65, [("spruce", 2), ("birch", 1)]),
    "terralith:shield_clearing":     (0.30, [("spruce", 1), ("birch", 1)]),
    "terralith:lush_valley":         (0.50, [("oak", 2), ("birch", 1)]),
    "terralith:lavender_valley":     (0.40, [("birch", 1)]),
    "terralith:sakura_grove":        (0.60, [("cherry", 1)]),
    "terralith:sakura_valley":       (0.55, [("cherry", 1)]),
    "terralith:moonlight_grove":     (0.65, [("dark_oak", 1)]),
    "terralith:moonlight_valley":    (0.55, [("dark_oak", 1)]),
    "terralith:amethyst_canyon":     (0.50, [("oak", 1)]),
    "terralith:amethyst_rainforest": (0.85, [("jungle", 2), ("oak", 1)]),
    "terralith:birch_taiga":         (0.65, [("birch", 2), ("spruce", 1)]),
    "terralith:temperate_highlands": (0.35, [("oak", 1), ("spruce", 1)]),
    "terralith:highlands":           (0.25, [("oak", 1)]),
    "terralith:brushland":           (0.12, [("acacia", 1)]),
    "terralith:hot_shrubland":       (0.08, [("acacia", 1)]),
    "terralith:orchid_swamp":        (0.45, [("oak", 1), ("mangrove", 1)]),
    "terralith:snowy_cherry_grove":  (0.50, [("cherry", 1)]),
    "terralith:desert_oasis":        (0.25, [("jungle", 1)]),
    "terralith:yellowstone":         (0.30, [("spruce", 1)]),
    # Nature's Spirit
    "natures_spirit:fir_forest":     (0.70, [("spruce", 1)]),
    "natures_spirit:coniferous_covert": (0.65, [("spruce", 1)]),
    # Nether
    "minecraft:crimson_forest":      (0.70, [("crimson", 1)]),
    "minecraft:warped_forest":       (0.70, [("warped", 1)]),
    "incendium:withered_forest":     (0.60, [("crimson", 1)]),
    "incendium:inverted_forest":     (0.65, [("warped", 1)]),
    # Paradise Lost
    "paradise_lost:highlands_forest": (0.70, [("highlands", 1)]),
    "paradise_lost:wisteria_woods":  (0.75, [("wisteria", 1)]),
    "paradise_lost:highlands_shield": (0.30, [("highlands", 1)]),
    "paradise_lost:highlands":       (0.15, [("highlands", 1)]),
}


def tree_canopy(biome_id):
    """Return (coverage, [(leaf_colour, weight), ...]) or None for non-treed biomes."""
    if biome_id in BIOME_TREES:
        coverage, types = BIOME_TREES[biome_id]
        return coverage, [(LEAF_COLOURS.get(t, LEAF_COLOURS["oak"]), w) for t, w in types]
    bid = biome_id.lower()
    if "jungle" in bid and "sparse" not in bid:
        return 0.85, [(LEAF_COLOURS["jungle"], 1)]
    if "dark_forest" in bid or "dark" in bid and "forest" in bid:
        return 0.85, [(LEAF_COLOURS["dark_oak"], 1)]
    if "taiga" in bid or "spruce" in bid or "pine" in bid or "fir" in bid:
        return 0.65, [(LEAF_COLOURS["spruce"], 1)]
    if "forest" in bid or "grove" in bid or "woods" in bid:
        return 0.65, [(LEAF_COLOURS["oak"], 3), (LEAF_COLOURS["birch"], 1)]
    if "cherry" in bid or "sakura" in bid:
        return 0.55, [(LEAF_COLOURS["cherry"], 1)]
    if "swamp" in bid or "mangrove" in bid:
        return 0.50, [(LEAF_COLOURS["mangrove"], 1)]
    if "savanna" in bid:
        return 0.15, [(LEAF_COLOURS["acacia"], 1)]
    return None

# Biome ID → surface type. Checked before keyword fallback.
_BIOME_SURFACE = {
    # --- Overworld: water ---
    "minecraft:ocean": "water",
    "minecraft:deep_ocean": "water",
    "minecraft:cold_ocean": "water_cold",
    "minecraft:deep_cold_ocean": "water_cold",
    "minecraft:frozen_ocean": "water_frozen",
    "minecraft:deep_frozen_ocean": "water_frozen",
    "minecraft:lukewarm_ocean": "water_warm",
    "minecraft:deep_lukewarm_ocean": "water_warm",
    "minecraft:warm_ocean": "water_warm",
    "minecraft:frozen_river": "water_frozen",
    "minecraft:river": "water",
    "terralith:warm_river": "water_warm",
    "terralith:deep_warm_ocean": "water_warm",
    # --- Overworld: sand ---
    "minecraft:beach": "sand",
    "minecraft:snowy_beach": "sand",
    "minecraft:desert": "sand",
    "minecraft:sunflower_plains": "grass",
    "terralith:desert_canyon": "sand",
    "terralith:desert_oasis": "grass",
    "terralith:desert_spires": "sand",
    # --- Overworld: red_sand / badlands ---
    "minecraft:badlands": "red_sand",
    "minecraft:eroded_badlands": "red_sand",
    "minecraft:wooded_badlands": "red_sand",
    # --- Overworld: stone ---
    "minecraft:stony_shore": "stone",
    "minecraft:stony_peaks": "stone",
    "minecraft:windswept_hills": "stone",
    "minecraft:windswept_gravelly_hills": "gravel",
    "terralith:rocky_mountains": "stone",
    "terralith:basalt_cliffs": "stone",
    "terralith:volcanic_peaks": "stone",
    "terralith:volcanic_crater": "stone",
    "terralith:caldera": "stone",
    "terralith:scarlet_mountains": "stone",
    "paradise_lost:calcite_craglands": "stone",
    # --- Overworld: snow ---
    "minecraft:snowy_plains": "snow",
    "minecraft:ice_spikes": "snow",
    "minecraft:snowy_slopes": "snow",
    "minecraft:frozen_peaks": "snow",
    "minecraft:jagged_peaks": "snow",
    "terralith:snowy_cherry_grove": "snow",
    # --- Overworld: mycelium ---
    "minecraft:mushroom_fields": "mycelium",
    # --- Overworld: podzol ---
    "minecraft:old_growth_pine_taiga": "podzol",
    "minecraft:old_growth_spruce_taiga": "podzol",
    # --- Overworld: mud ---
    "minecraft:mangrove_swamp": "mud",
    # --- Overworld: grass (everything else) ---
    "minecraft:plains": "grass",
    "minecraft:meadow": "grass",
    "minecraft:cherry_grove": "grass",
    "minecraft:forest": "grass",
    "minecraft:flower_forest": "grass",
    "minecraft:birch_forest": "grass",
    "minecraft:old_growth_birch_forest": "grass",
    "minecraft:dark_forest": "grass",
    "minecraft:pale_garden": "grass",
    "minecraft:taiga": "grass",
    "minecraft:snowy_taiga": "grass",
    "minecraft:jungle": "grass",
    "minecraft:sparse_jungle": "grass",
    "minecraft:bamboo_jungle": "grass",
    "minecraft:savanna": "grass",
    "minecraft:savanna_plateau": "grass",
    "minecraft:windswept_forest": "grass",
    "minecraft:windswept_savanna": "grass",
    "minecraft:swamp": "grass",
    "minecraft:grove": "grass",
    "minecraft:deep_dark": "stone",
    "minecraft:lush_caves": "grass",
    "minecraft:dripstone_caves": "stone",
    # --- Terralith overworld ---
    "terralith:yellowstone": "grass",
    "terralith:highlands": "grass",
    "terralith:forested_highlands": "grass",
    "terralith:cloud_forest": "grass",
    "terralith:alpine_highlands": "grass",
    "terralith:siberian_taiga": "grass",
    "terralith:shield": "grass",
    "terralith:shield_clearing": "grass",
    "terralith:lush_valley": "grass",
    "terralith:lavender_valley": "grass",
    "terralith:sakura_grove": "grass",
    "terralith:sakura_valley": "grass",
    "terralith:moonlight_grove": "grass",
    "terralith:moonlight_valley": "grass",
    "terralith:amethyst_canyon": "grass",
    "terralith:amethyst_rainforest": "grass",
    "terralith:birch_taiga": "grass",
    "terralith:temperate_highlands": "grass",
    "terralith:brushland": "grass",
    "terralith:hot_shrubland": "grass",
    "terralith:cold_shrubland": "grass",
    "terralith:fractured_savanna": "grass",
    "terralith:arid_highlands": "grass",
    "terralith:steppe": "grass",
    "terralith:orchid_swamp": "grass",
    "terralith:warped_mesa": "grass",
    "terralith:cave/infested_caves": "stone",
    # --- Nether ---
    "minecraft:nether_wastes": "netherrack",
    "minecraft:soul_sand_valley": "soul_sand",
    "minecraft:crimson_forest": "crimson_nylium",
    "minecraft:warped_forest": "warped_nylium",
    "minecraft:basalt_deltas": "basalt",
    "incendium:ash_barrens": "netherrack",
    "incendium:volcanic_deltas": "netherrack",
    "incendium:infernal_dunes": "netherrack",
    "incendium:toxic_heap": "netherrack",
    "incendium:weeping_valley": "crimson_nylium",
    "incendium:withered_forest": "netherrack",
    "incendium:inverted_forest": "warped_nylium",
    "incendium:quartz_flats": "netherrack",
    # --- End ---
    "minecraft:the_end": "end_stone",
    "minecraft:end_highlands": "end_stone",
    "minecraft:end_midlands": "end_stone",
    "minecraft:end_barrens": "end_stone",
    "minecraft:small_end_islands": "end_stone",
    "nullscape:void_barrens": "end_stone",
    "nullscape:shadowlands": "end_stone",
    # --- Void ---
    "minecraft:the_void": "void",
    # --- Nature's Spirit ---
    "natures_spirit:fir_forest": "grass",
    "natures_spirit:coniferous_covert": "grass",
    # --- Paradise Lost ---
    "paradise_lost:highlands": "grass",
    "paradise_lost:highlands_forest": "grass",
    "paradise_lost:highlands_shield": "grass",
    "paradise_lost:continental_plateau": "grass",
    "paradise_lost:wisteria_woods": "grass",
    "paradise_lost:autumnal_tundra": "grass",
}


def biome_surface(biome_id: str) -> str:
    """Return the surface type key for a biome ID."""
    if biome_id in _BIOME_SURFACE:
        return _BIOME_SURFACE[biome_id]
    bid = biome_id.lower()
    if "ocean" in bid:
        if "frozen" in bid or "cold" in bid:
            return "water_cold"
        if "warm" in bid or "lukewarm" in bid or "tropical" in bid:
            return "water_warm"
        return "water"
    if "river" in bid:
        if "frozen" in bid:
            return "water_frozen"
        return "water"
    if "desert" in bid or "dune" in bid:
        return "sand"
    if "beach" in bid or "shore" in bid and "stony" not in bid:
        return "sand"
    if "badlands" in bid or "mesa" in bid or "red_sand" in bid:
        return "red_sand"
    if "mushroom" in bid or "mycelium" in bid:
        return "mycelium"
    if "mangrove" in bid:
        return "mud"
    if "ice_spikes" in bid or "frozen_peak" in bid or "snowy_slope" in bid:
        return "snow"
    if "snowy_plain" in bid:
        return "snow"
    if "stony" in bid or "rocky" in bid or "basalt_cliff" in bid:
        return "stone"
    if "gravel" in bid:
        return "gravel"
    if "old_growth" in bid and ("pine" in bid or "spruce" in bid):
        return "podzol"
    if "nether" in bid or "netherrack" in bid:
        return "netherrack"
    if "crimson" in bid:
        return "crimson_nylium"
    if "warped" in bid and "forest" in bid:
        return "warped_nylium"
    if "soul" in bid:
        return "soul_sand"
    if "basalt" in bid:
        return "basalt"
    if "end" in bid and ("highland" in bid or "midland" in bid or "barren" in bid
                         or "island" in bid or "the_end" in bid):
        return "end_stone"
    if "void" in bid or "nullscape" in bid:
        return "void"
    if "snow" in bid or "frozen" in bid or "ice" in bid:
        return "snow"
    if "swamp" in bid or "marsh" in bid or "bog" in bid:
        return "grass"
    if "incendium" in bid:
        return "netherrack"
    if "forest" in bid or "taiga" in bid or "grove" in bid or "jungle" in bid:
        return "grass"
    if "plain" in bid or "meadow" in bid or "prairie" in bid or "steppe" in bid:
        return "grass"
    if "savanna" in bid or "shrub" in bid or "brush" in bid:
        return "grass"
    if "highland" in bid or "plateau" in bid or "valley" in bid:
        return "grass"
    return "grass"


# --- Vegetation density ---

_VERY_DENSE = 0.62
_DENSE = 0.78
_MODERATE = 0.90
_OPEN = 1.00

_BIOME_DENSITY = {
    # Very dense canopy
    "minecraft:dark_forest": _VERY_DENSE,
    "minecraft:old_growth_pine_taiga": _VERY_DENSE,
    "minecraft:old_growth_spruce_taiga": _VERY_DENSE,
    "minecraft:jungle": _VERY_DENSE,
    "minecraft:bamboo_jungle": _VERY_DENSE,
    "minecraft:mangrove_swamp": _VERY_DENSE,
    "terralith:amethyst_rainforest": _VERY_DENSE,
    "terralith:cloud_forest": _VERY_DENSE,
    # Dense canopy
    "minecraft:forest": _DENSE,
    "minecraft:flower_forest": _DENSE,
    "minecraft:birch_forest": _DENSE,
    "minecraft:old_growth_birch_forest": _DENSE,
    "minecraft:taiga": _DENSE,
    "minecraft:snowy_taiga": _DENSE,
    "minecraft:cherry_grove": _DENSE,
    "minecraft:windswept_forest": _DENSE,
    "terralith:forested_highlands": _DENSE,
    "terralith:sakura_grove": _DENSE,
    "terralith:sakura_valley": _DENSE,
    "terralith:moonlight_grove": _DENSE,
    "terralith:moonlight_valley": _DENSE,
    "terralith:birch_taiga": _DENSE,
    "terralith:siberian_taiga": _DENSE,
    "terralith:shield": _DENSE,
    "terralith:lavender_valley": _DENSE,
    "terralith:amethyst_canyon": _DENSE,
    "natures_spirit:fir_forest": _DENSE,
    "natures_spirit:coniferous_covert": _DENSE,
    "paradise_lost:highlands_forest": _DENSE,
    "paradise_lost:wisteria_woods": _DENSE,
    "minecraft:crimson_forest": _DENSE,
    "minecraft:warped_forest": _DENSE,
    "incendium:withered_forest": _DENSE,
    "incendium:inverted_forest": _DENSE,
    # Moderate
    "minecraft:sparse_jungle": _MODERATE,
    "minecraft:swamp": _MODERATE,
    "minecraft:pale_garden": _MODERATE,
    "minecraft:savanna": _MODERATE,
    "minecraft:savanna_plateau": _MODERATE,
    "minecraft:grove": _MODERATE,
    "minecraft:wooded_badlands": _MODERATE,
    "minecraft:windswept_savanna": _MODERATE,
    "terralith:lush_valley": _MODERATE,
    "terralith:highlands": _MODERATE,
    "terralith:alpine_highlands": _MODERATE,
    "terralith:temperate_highlands": _MODERATE,
    "terralith:shield_clearing": _MODERATE,
    "terralith:orchid_swamp": _MODERATE,
    "terralith:desert_oasis": _MODERATE,
    "terralith:snowy_cherry_grove": _MODERATE,
    "terralith:yellowstone": _MODERATE,
    "paradise_lost:highlands": _MODERATE,
    "paradise_lost:highlands_shield": _MODERATE,
    "paradise_lost:continental_plateau": _MODERATE,
    "paradise_lost:autumnal_tundra": _MODERATE,
    # Open (no trees) — everything else defaults here
}


def vegetation_density(biome_id: str) -> float:
    """Return a darkness multiplier (0.0-1.0) representing canopy density.
    1.0 = open (no trees), 0.62 = very dense canopy."""
    if biome_id in _BIOME_DENSITY:
        return _BIOME_DENSITY[biome_id]
    bid = biome_id.lower()
    if "jungle" in bid and "sparse" not in bid:
        return _VERY_DENSE
    if "dark_forest" in bid:
        return _VERY_DENSE
    if "old_growth" in bid:
        return _VERY_DENSE
    if "mangrove" in bid:
        return _VERY_DENSE
    if "rainforest" in bid:
        return _VERY_DENSE
    if "swamp" in bid or "marsh" in bid or "bog" in bid:
        return _VERY_DENSE
    if "forest" in bid or "taiga" in bid or "grove" in bid or "woods" in bid:
        return _DENSE
    if "sparse" in bid or "savanna" in bid or "shrub" in bid:
        return _MODERATE
    return _OPEN


# --- Grass temperature tinting ---

_GRASS_TINT = {
    # Warm / temperate
    "minecraft:plains": (1.0, 0.95, 0.7),
    "minecraft:sunflower_plains": (1.0, 0.95, 0.7),
    "minecraft:meadow": (0.9, 1.0, 0.7),
    "minecraft:flower_forest": (0.9, 1.0, 0.65),
    "minecraft:forest": (0.85, 1.0, 0.7),
    "minecraft:birch_forest": (0.85, 1.0, 0.75),
    "minecraft:old_growth_birch_forest": (0.85, 1.0, 0.75),
    "minecraft:dark_forest": (0.7, 0.9, 0.6),
    "minecraft:pale_garden": (1.1, 1.1, 1.15),
    "minecraft:cherry_grove": (0.95, 1.0, 0.75),
    # Savanna / hot
    "minecraft:savanna": (1.1, 0.9, 0.55),
    "minecraft:savanna_plateau": (1.1, 0.9, 0.55),
    "minecraft:windswept_savanna": (1.1, 0.9, 0.55),
    # Jungle / tropical
    "minecraft:jungle": (0.85, 1.05, 0.6),
    "minecraft:sparse_jungle": (0.9, 1.05, 0.6),
    "minecraft:bamboo_jungle": (0.85, 1.05, 0.55),
    # Swamp
    "minecraft:swamp": (0.75, 0.85, 0.6),
    "minecraft:mangrove_swamp": (0.75, 0.85, 0.55),
    # Taiga / cool
    "minecraft:taiga": (0.75, 1.0, 0.85),
    "minecraft:snowy_taiga": (0.7, 0.95, 0.9),
    "minecraft:old_growth_pine_taiga": (0.7, 0.95, 0.8),
    "minecraft:old_growth_spruce_taiga": (0.65, 0.9, 0.8),
    "minecraft:grove": (0.75, 1.0, 0.9),
    # Windswept
    "minecraft:windswept_hills": (0.8, 0.95, 0.8),
    "minecraft:windswept_forest": (0.8, 0.95, 0.8),
    # Lush caves
    "minecraft:lush_caves": (0.8, 1.1, 0.6),
    # Terralith
    "terralith:yellowstone": (1.0, 0.95, 0.65),
    "terralith:highlands": (0.9, 1.0, 0.75),
    "terralith:forested_highlands": (0.8, 1.0, 0.7),
    "terralith:cloud_forest": (0.75, 1.05, 0.85),
    "terralith:alpine_highlands": (0.8, 1.0, 0.85),
    "terralith:siberian_taiga": (0.65, 0.9, 0.85),
    "terralith:shield": (0.75, 0.95, 0.8),
    "terralith:shield_clearing": (0.8, 1.0, 0.75),
    "terralith:lush_valley": (0.85, 1.05, 0.6),
    "terralith:lavender_valley": (0.9, 0.85, 0.9),
    "terralith:sakura_grove": (0.95, 0.9, 0.8),
    "terralith:sakura_valley": (0.95, 0.9, 0.8),
    "terralith:moonlight_grove": (0.7, 0.85, 1.0),
    "terralith:moonlight_valley": (0.7, 0.85, 1.0),
    "terralith:amethyst_canyon": (0.8, 0.8, 0.95),
    "terralith:amethyst_rainforest": (0.8, 0.85, 0.9),
    "terralith:birch_taiga": (0.8, 1.0, 0.8),
    "terralith:temperate_highlands": (0.9, 1.0, 0.75),
    "terralith:brushland": (1.05, 0.9, 0.6),
    "terralith:hot_shrubland": (1.1, 0.9, 0.55),
    "terralith:cold_shrubland": (0.8, 0.95, 0.85),
    "terralith:fractured_savanna": (1.1, 0.9, 0.55),
    "terralith:arid_highlands": (1.1, 0.9, 0.6),
    "terralith:steppe": (1.05, 0.95, 0.65),
    "terralith:desert_oasis": (0.9, 1.05, 0.6),
    "terralith:orchid_swamp": (0.75, 0.85, 0.65),
    "terralith:warped_mesa": (0.7, 0.9, 0.9),
    "terralith:snowy_cherry_grove": (0.8, 0.95, 0.9),
    # Nature's Spirit
    "natures_spirit:fir_forest": (0.7, 0.95, 0.8),
    "natures_spirit:coniferous_covert": (0.7, 0.95, 0.85),
    # Paradise Lost
    "paradise_lost:highlands": (0.85, 1.0, 0.75),
    "paradise_lost:highlands_forest": (0.8, 1.0, 0.7),
    "paradise_lost:highlands_shield": (0.8, 0.95, 0.8),
    "paradise_lost:continental_plateau": (0.9, 1.0, 0.75),
    "paradise_lost:wisteria_woods": (0.85, 0.85, 0.95),
    "paradise_lost:autumnal_tundra": (1.1, 0.85, 0.55),
}

_DEFAULT_TINT = (0.9, 1.0, 0.75)


def grass_tint(biome_id: str) -> tuple[float, float, float]:
    """Return an RGB multiplier for grass colour based on biome temperature."""
    if biome_id in _GRASS_TINT:
        return _GRASS_TINT[biome_id]
    bid = biome_id.lower()
    if "jungle" in bid or "tropical" in bid or "rainforest" in bid:
        return (0.85, 1.05, 0.6)
    if "savanna" in bid or "arid" in bid or "hot" in bid:
        return (1.1, 0.9, 0.55)
    if "swamp" in bid or "marsh" in bid or "bog" in bid:
        return (0.75, 0.85, 0.6)
    if "taiga" in bid or "siberian" in bid or "boreal" in bid:
        return (0.75, 1.0, 0.85)
    if "snowy" in bid or "frozen" in bid or "cold" in bid or "ice" in bid:
        return (0.7, 0.95, 0.9)
    if "cherry" in bid or "sakura" in bid:
        return (0.95, 0.9, 0.8)
    if "dark" in bid:
        return (0.7, 0.9, 0.6)
    if "steppe" in bid or "brush" in bid or "shrub" in bid:
        return (1.05, 0.95, 0.65)
    if "meadow" in bid or "flower" in bid or "lush" in bid:
        return (0.9, 1.0, 0.7)
    if "highland" in bid or "plateau" in bid:
        return (0.9, 1.0, 0.75)
    if "moonlight" in bid:
        return (0.7, 0.85, 1.0)
    if "lavender" in bid or "amethyst" in bid or "wisteria" in bid:
        return (0.85, 0.85, 0.95)
    return _DEFAULT_TINT


def _clamp(v: int) -> int:
    if v < 0:
        return 0
    if v > 255:
        return 255
    return v


def surface_colour(biome_id: str) -> tuple[int, int, int]:
    """Return the final RGB colour for this biome's surface appearance."""
    surface = biome_surface(biome_id)
    r, g, b = SURFACE_COLOURS[surface]
    if surface == "grass":
        tr, tg, tb = grass_tint(biome_id)
        r = _clamp(int(r * tr))
        g = _clamp(int(g * tg))
        b = _clamp(int(b * tb))
    return (r, g, b)


def surface_and_density(biome_id: str) -> tuple[tuple[int, int, int], float]:
    """Return (colour, vegetation_density) for the renderer."""
    return surface_colour(biome_id), vegetation_density(biome_id)


# --- CLI verification ---

def _print_table(fmt: str = "table") -> None:
    from biome_renderer import BIOME_COLOURS

    all_biomes = sorted(BIOME_COLOURS.keys())
    if fmt == "csv":
        print("biome,surface,r,g,b,density,hex")
        for biome in all_biomes:
            (r, g, b), density = surface_and_density(biome)
            print(f"{biome},{biome_surface(biome)},{r},{g},{b},{density:.2f},#{r:02x}{g:02x}{b:02x}")
    else:
        print(f"{'Biome':<55} {'Surface':<15} {'Colour':>7}  {'Density':>7}  {'Hex':>7}")
        print("-" * 100)
        for biome in all_biomes:
            (r, g, b), density = surface_and_density(biome)
            label = biome_surface(biome)
            density_str = f"{density:.2f}"
            hex_str = f"#{r:02x}{g:02x}{b:02x}"
            print(f"{biome:<55} {label:<15} ({r:>3},{g:>3},{b:>3})  {density_str:>7}  {hex_str:>7}")


if __name__ == "__main__":
    import argparse
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--format", choices=["table", "csv"], default="table")
    args = ap.parse_args()
    _print_table(args.format)
