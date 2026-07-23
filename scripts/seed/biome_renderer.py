#!/usr/bin/env python3
"""biome_renderer.py — Generate terrain-aware biome map images from seeds, no MC server.

Uses the BiomeSampler (modded multinoise reimplementation) to sample biomes
on a dense grid, then renders a terrain map using:
  - Surface-block colours from surface_rules.py (MC map colours per biome)
  - Spline-based terrain heights from terrain_height.py (Terralith's offset spline)
  - Water depth shading from continentalness thresholds
  - Vegetation density overlay per biome
  - Hillshade from computed terrain heights

~0.5-2s per 1024x1024 image depending on the noise config complexity.

Usage:
    python3 biome_renderer.py render --seed 12345 --output map.png
    python3 biome_renderer.py render --seed 12345 --family nether --output nether.png
    python3 biome_renderer.py batch --config <dir> --seedtest <dir>
"""
import argparse
import struct
import sys
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from biome_sampler import BiomeSampler, load_noise_configs  # noqa: E402
from surface_rules import surface_and_density, tree_canopy, TRUNK_COLOUR  # noqa: E402
from terrain_height import TerrainEvaluator, SPLINES_PATH, ridges_folded  # noqa: E402

BIOME_COLOURS = {
    "minecraft:ocean": (0, 0, 112), "minecraft:deep_ocean": (0, 0, 80),
    "minecraft:cold_ocean": (32, 32, 128), "minecraft:deep_cold_ocean": (24, 24, 96),
    "minecraft:frozen_ocean": (112, 112, 180), "minecraft:deep_frozen_ocean": (80, 80, 140),
    "minecraft:lukewarm_ocean": (0, 80, 160), "minecraft:deep_lukewarm_ocean": (0, 64, 128),
    "minecraft:warm_ocean": (0, 128, 200), "minecraft:frozen_river": (160, 160, 220),
    "minecraft:river": (0, 0, 180),
    "minecraft:beach": (230, 210, 140), "minecraft:snowy_beach": (220, 220, 230),
    "minecraft:stony_shore": (136, 136, 136),
    "minecraft:plains": (120, 180, 60), "minecraft:sunflower_plains": (180, 200, 50),
    "minecraft:snowy_plains": (230, 240, 250), "minecraft:ice_spikes": (180, 220, 250),
    "minecraft:desert": (218, 200, 120), "minecraft:badlands": (180, 100, 40),
    "minecraft:eroded_badlands": (200, 120, 50), "minecraft:wooded_badlands": (160, 110, 60),
    "minecraft:meadow": (100, 200, 80), "minecraft:cherry_grove": (230, 140, 180),
    "minecraft:forest": (30, 120, 30), "minecraft:flower_forest": (80, 160, 80),
    "minecraft:birch_forest": (60, 160, 80), "minecraft:old_growth_birch_forest": (70, 170, 90),
    "minecraft:dark_forest": (20, 80, 20), "minecraft:pale_garden": (180, 190, 180),
    "minecraft:taiga": (40, 100, 60), "minecraft:snowy_taiga": (60, 120, 100),
    "minecraft:old_growth_pine_taiga": (50, 90, 50),
    "minecraft:old_growth_spruce_taiga": (40, 80, 40),
    "minecraft:jungle": (30, 140, 20), "minecraft:sparse_jungle": (60, 150, 50),
    "minecraft:bamboo_jungle": (50, 160, 30),
    "minecraft:savanna": (180, 170, 80), "minecraft:savanna_plateau": (160, 150, 70),
    "minecraft:windswept_hills": (96, 96, 96), "minecraft:windswept_gravelly_hills": (120, 120, 120),
    "minecraft:windswept_forest": (70, 110, 70), "minecraft:windswept_savanna": (150, 140, 70),
    "minecraft:swamp": (48, 80, 48), "minecraft:mangrove_swamp": (60, 90, 50),
    "minecraft:grove": (100, 140, 120), "minecraft:snowy_slopes": (200, 210, 220),
    "minecraft:frozen_peaks": (190, 200, 220), "minecraft:jagged_peaks": (180, 190, 200),
    "minecraft:stony_peaks": (140, 140, 140),
    "minecraft:mushroom_fields": (180, 100, 180),
    "minecraft:deep_dark": (10, 10, 20),
    "minecraft:lush_caves": (60, 140, 60), "minecraft:dripstone_caves": (120, 100, 80),
    "minecraft:nether_wastes": (140, 60, 60), "minecraft:soul_sand_valley": (80, 60, 40),
    "minecraft:crimson_forest": (160, 30, 30), "minecraft:warped_forest": (20, 120, 120),
    "minecraft:basalt_deltas": (80, 80, 80),
    "minecraft:the_end": (80, 80, 120), "minecraft:end_highlands": (130, 130, 80),
    "minecraft:end_midlands": (140, 140, 100), "minecraft:end_barrens": (110, 110, 80),
    "minecraft:small_end_islands": (90, 90, 70),
    "minecraft:the_void": (20, 20, 20),
    # Terralith
    "terralith:yellowstone": (170, 160, 60), "terralith:volcanic_peaks": (60, 40, 40),
    "terralith:volcanic_crater": (80, 30, 20), "terralith:caldera": (100, 50, 30),
    "terralith:highlands": (80, 140, 60), "terralith:forested_highlands": (50, 120, 50),
    "terralith:rocky_mountains": (110, 110, 110), "terralith:scarlet_mountains": (160, 60, 40),
    "terralith:cloud_forest": (60, 130, 90), "terralith:alpine_highlands": (90, 130, 110),
    "terralith:siberian_taiga": (50, 80, 70), "terralith:shield": (70, 100, 70),
    "terralith:shield_clearing": (90, 140, 70),
    "terralith:lush_valley": (70, 170, 60), "terralith:lavender_valley": (140, 100, 180),
    "terralith:sakura_grove": (220, 130, 170), "terralith:sakura_valley": (210, 120, 160),
    "terralith:moonlight_grove": (60, 80, 120), "terralith:moonlight_valley": (50, 70, 110),
    "terralith:amethyst_canyon": (130, 80, 180), "terralith:amethyst_rainforest": (120, 90, 170),
    "terralith:birch_taiga": (70, 130, 80), "terralith:temperate_highlands": (100, 150, 80),
    "terralith:brushland": (160, 150, 60), "terralith:hot_shrubland": (180, 160, 70),
    "terralith:cold_shrubland": (130, 130, 100), "terralith:fractured_savanna": (170, 130, 60),
    "terralith:arid_highlands": (170, 140, 80), "terralith:steppe": (160, 160, 100),
    "terralith:desert_canyon": (200, 170, 100), "terralith:desert_oasis": (80, 140, 40),
    "terralith:desert_spires": (210, 180, 110),
    "terralith:snowy_cherry_grove": (200, 160, 190),
    "terralith:orchid_swamp": (80, 90, 60), "terralith:warm_river": (0, 60, 160),
    "terralith:deep_warm_ocean": (0, 50, 130),
    "terralith:basalt_cliffs": (70, 70, 70), "terralith:warped_mesa": (30, 100, 100),
    "terralith:cave/infested_caves": (90, 60, 90),
    # Incendium (nether)
    "incendium:ash_barrens": (100, 80, 70), "incendium:volcanic_deltas": (120, 40, 20),
    "incendium:infernal_dunes": (160, 100, 40), "incendium:toxic_heap": (80, 100, 40),
    "incendium:weeping_valley": (120, 40, 60), "incendium:withered_forest": (80, 50, 50),
    "incendium:inverted_forest": (40, 80, 80), "incendium:quartz_flats": (200, 200, 190),
    # Nullscape (end)
    "nullscape:void_barrens": (60, 50, 70), "nullscape:shadowlands": (40, 30, 60),
    # Nature's Spirit
    "natures_spirit:fir_forest": (40, 90, 50), "natures_spirit:coniferous_covert": (50, 80, 60),
    # Paradise Lost
    "paradise_lost:highlands": (80, 160, 100), "paradise_lost:highlands_forest": (50, 130, 60),
    "paradise_lost:highlands_shield": (70, 120, 80),
    "paradise_lost:continental_plateau": (100, 140, 90),
    "paradise_lost:calcite_craglands": (180, 170, 160),
    "paradise_lost:wisteria_woods": (160, 120, 180),
    "paradise_lost:autumnal_tundra": (180, 140, 80),
}

FALLBACK_COLOUR = (128, 128, 128)

# Structure marker colours, keyed by keyword matched against the battery
# structure name (approximate the structure's dominant build material).
STRUCT_MAT = {
    "village": (104, 104, 104),     # cobblestone
    "pillager": (60, 60, 60),       # dark stone
    "mansion": (90, 60, 30),        # dark oak planks
    "monument": (70, 130, 130),     # prismarine
    "temple": (180, 170, 140),      # sandstone
    "jungle_temple": (80, 90, 50),  # mossy cobblestone
    "desert_pyramid": (210, 195, 145), # sandstone
    "fortress": (55, 10, 10),       # nether brick
    "stronghold": (80, 80, 80),     # stone brick
    "mineshaft": (100, 72, 36),     # oak planks
    "shipwreck": (90, 60, 30),      # planks
    "ruined_portal": (40, 10, 40),  # obsidian
    "bastion": (30, 30, 30),        # blackstone
    "end_city": (200, 160, 200),    # purpur
    "witch": (60, 80, 30),          # swamp hut
    "igloo": (220, 220, 220),       # snow
    "ancient_city": (20, 20, 30),   # deepslate
    "trail_ruins": (140, 100, 60),  # terracotta
    "outpost": (100, 72, 36),       # oak planks
    "ocean_ruin": (70, 100, 80),    # mossy stone
    "dungeon": (80, 80, 80),        # cobblestone
    "treasure": (180, 170, 140),    # sandstone
    "sanctum": (100, 60, 60),       # deepslate
    "tower": (104, 104, 104),       # stone
    "citadel": (80, 60, 40),        # dark planks
    "keep": (50, 50, 50),           # blackstone
    "shrine": (130, 110, 80),       # stone brick
    "camp": (100, 72, 36),          # planks
    "ruin": (90, 90, 80),           # cracked stone
    "vault": (60, 50, 50),          # deepslate
}
_FOOTPRINT_LARGE = ("mansion", "monument", "fortress", "bastion")


def _struct_style(name):
    """(colour, footprint radius in px) for a battery structure name."""
    for keyword, col in STRUCT_MAT.items():
        if keyword in name:
            if keyword == "village":
                return col, 8
            if keyword in _FOOTPRINT_LARGE:
                return col, 5
            return col, 3
    return FALLBACK_COLOUR, 3


def biome_colour(biome_id):
    if biome_id in BIOME_COLOURS:
        return BIOME_COLOURS[biome_id]
    if "ocean" in biome_id or "river" in biome_id:
        return (0, 40, 140)
    if "forest" in biome_id or "taiga" in biome_id or "grove" in biome_id:
        return (40, 110, 50)
    if "desert" in biome_id or "sand" in biome_id or "badlands" in biome_id:
        return (200, 170, 100)
    if "snow" in biome_id or "frozen" in biome_id or "ice" in biome_id:
        return (210, 220, 240)
    if "swamp" in biome_id or "marsh" in biome_id:
        return (50, 80, 50)
    if "nether" in biome_id or "crimson" in biome_id or "basalt" in biome_id:
        return (140, 50, 50)
    if "warped" in biome_id:
        return (30, 110, 110)
    if "end" in biome_id or "void" in biome_id:
        return (80, 70, 100)
    if "paradise" in biome_id or "highlands" in biome_id:
        return (80, 150, 90)
    if "incendium" in biome_id:
        return (150, 70, 40)
    if "nullscape" in biome_id:
        return (60, 50, 80)
    return FALLBACK_COLOUR


def write_png(path, pixels, width, height):
    """Write an RGB pixel buffer as a PNG file. No dependencies."""
    def chunk(tag, data):
        raw = tag + data
        return struct.pack(">I", len(data)) + raw + struct.pack(">I", zlib.crc32(raw) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    raw_rows = b""
    for y in range(height):
        raw_rows += b"\x00"
        raw_rows += pixels[y * width * 3:(y + 1) * width * 3]

    out = b"\x89PNG\r\n\x1a\n"
    out += chunk(b"IHDR", ihdr)
    out += chunk(b"IDAT", zlib.compress(raw_rows, 6))
    out += chunk(b"IEND", b"")
    Path(path).write_bytes(out)


_terrain_evaluator = None


def _get_terrain_evaluator():
    global _terrain_evaluator
    if _terrain_evaluator is None and SPLINES_PATH.exists():
        _terrain_evaluator = TerrainEvaluator()
    return _terrain_evaluator


def _evaluator_for_family(family):
    ev = _get_terrain_evaluator()
    if ev is None:
        return None
    fam = family or "overworld"
    if ev.has_family(fam):
        return ev
    return None


SEA_LEVEL = 63


def _pixel_hash(seed, x, z):
    """Fast deterministic hash for per-pixel decisions (tree placement etc)."""
    v = ((seed * 6364136223846793005 + 1442695040888963407)
         ^ (x * 73856093) ^ (z * 19349663)) & 0xFFFFFFFF
    v = ((v >> 16) ^ v) * 0x45d9f3b & 0xFFFFFFFF
    return v


# --- Island and cave dimension type constants ---
ISLAND_TYPES = frozenset({"sky_islands", "nether_islands", "paradise_lost:paradise_lost"})
CAVE_TYPES = frozenset({"cave"})


def _island_noise(seed, x, z, scale=200):
    """Seeded 2D value noise for island mask generation.

    Multiple octaves of smoothed pixel hash produce 0-1 values.
    Same seed produces the same mask; different seeds differ.
    """
    val = 0.0
    amp = 1.0
    freq = 1.0 / scale
    for octave in range(4):
        ix = int(x * freq) if x * freq >= 0 else int(x * freq) - 1
        iz = int(z * freq) if z * freq >= 0 else int(z * freq) - 1
        fx = x * freq - ix
        fz = z * freq - iz
        fx = fx * fx * (3 - 2 * fx)
        fz = fz * fz * (3 - 2 * fz)
        v00 = (_pixel_hash(seed + octave * 7919, ix, iz) & 0xFFFF) / 65535.0
        v10 = (_pixel_hash(seed + octave * 7919, ix + 1, iz) & 0xFFFF) / 65535.0
        v01 = (_pixel_hash(seed + octave * 7919, ix, iz + 1) & 0xFFFF) / 65535.0
        v11 = (_pixel_hash(seed + octave * 7919, ix + 1, iz + 1) & 0xFFFF) / 65535.0
        v0 = v00 * (1 - fx) + v10 * fx
        v1 = v01 * (1 - fx) + v11 * fx
        val += (v0 * (1 - fz) + v1 * fz) * amp
        amp *= 0.5
        freq *= 2.0
    return val / 1.875


ISLAND_THRESHOLD = 0.55


def render_biome_map(seed, biome_params_path, output_path,
                     noise_config=None, family=None, dim_type=None,
                     biome_filter=None,
                     size=1024, blocks_per_pixel=4,
                     sample_resolution=256):
    """Render a terrain-aware biome map image.

    Samples biomes at sample_resolution×sample_resolution internally,
    then scales up to the output size. Uses surface-block colours,
    spline-based terrain heights, water depth shading, and vegetation
    density for a terrain-like appearance.
    """
    sampler = BiomeSampler(int(seed), biome_params_path,
                           noise_config=noise_config, family=family,
                           biome_filter=biome_filter)

    evaluator = _evaluator_for_family(family)
    eval_family = family or "overworld"

    total_blocks = size * blocks_per_pixel
    half = total_blocks // 2
    if sample_resolution > size:
        sample_resolution = size
    sample_step = total_blocks // sample_resolution
    upscale = size // sample_resolution

    grid = []
    climates = []
    heights = []
    for sy in range(sample_resolution):
        row = []
        crow = []
        hrow = []
        z = -half + sy * sample_step
        for sx in range(sample_resolution):
            x = -half + sx * sample_step
            biome, climate = sampler.biome_and_climate(x, z)
            (surf_col, _density) = surface_and_density(biome)
            canopy = tree_canopy(biome)
            row.append((biome, surf_col, canopy))
            crow.append(climate)
            cont = climate.get("continentalness", 0.0)
            ero = climate.get("erosion", 0.0)
            weird = climate.get("weirdness", 0.0)
            if evaluator is not None and evaluator.has_family(eval_family):
                h = evaluator.surface_height(cont, ero, weird, family=eval_family)
            else:
                h = max(0.0, min(200.0, 63.0 + cont * 40.0 - ero * 20.0 + ridges_folded(weird) * 15.0))
            hrow.append(h)
        grid.append(row)
        climates.append(crow)
        heights.append(hrow)

    # Height stats for hypsometric tinting
    all_h = [h for row in heights for h in row if h > 0]
    h_min = min(all_h) if all_h else 0
    h_max = max(all_h) if all_h else 200
    h_range = max(h_max - h_min, 1)

    is_ow = family in (None, "overworld")
    is_nether = family == "nether"
    is_end = family == "end"
    is_pl = family == "paradise_lost"
    is_islands = dim_type in ISLAND_TYPES
    is_cave = dim_type in CAVE_TYPES

    iseed = int(seed)

    pixels = bytearray(size * size * 3)
    for py in range(size):
        sy = min(py // upscale, sample_resolution - 1)
        for px in range(size):
            sx = min(px // upscale, sample_resolution - 1)
            biome_id, (r, g, b), canopy_info = grid[sy][sx]
            c = climates[sy][sx]
            cont = c.get("continentalness", 0.0)
            h = heights[sy][sx]

            # Block coordinates for this pixel (for deterministic hashing)
            bx = -half + px * blocks_per_pixel
            bz = -half + py * blocks_per_pixel

            # --- Island mask ---
            island_void = False
            if is_islands:
                island_void = _island_noise(iseed, bx, bz) < ISLAND_THRESHOLD

            # --- Tree canopy simulation ---
            # Per-pixel decision: is the top block leaves, trunk, or ground?
            is_canopy = False
            if canopy_info is not None and h > 1 and not island_void and not is_cave:
                coverage, leaf_types = canopy_info
                ph = _pixel_hash(iseed, bx, bz)
                pct = (ph & 0xFF) / 255.0
                if pct < coverage:
                    is_canopy = True
                    # Pick tree type from weighted list
                    total_w = sum(w for _, w in leaf_types)
                    pick = ((ph >> 8) & 0xFF) % total_w
                    leaf_col = leaf_types[0][0]
                    acc = 0
                    for col, w in leaf_types:
                        acc += w
                        if pick < acc:
                            leaf_col = col
                            break
                    # Per-crown colour variation (simulates individual trees)
                    crown_var = ((ph >> 16) & 0xF) - 7
                    r = max(0, min(255, leaf_col[0] + crown_var))
                    g = max(0, min(255, leaf_col[1] + crown_var * 2))
                    b = max(0, min(255, leaf_col[2] + crown_var))
                elif pct < coverage + 0.03:
                    # Trunk pixel (rare, ~3%)
                    r, g, b = TRUNK_COLOUR

            # --- Coral in warm shallow water ---
            is_water = "ocean" in biome_id or "river" in biome_id
            if is_islands or is_cave:
                is_water = False
            if is_water and "warm" in biome_id and cont > -0.3:
                ph = _pixel_hash(iseed, bx + 999, bz + 999)
                if (ph & 0xFF) < 25:
                    coral_idx = (ph >> 8) % 5
                    coral_cols = [(210, 80, 120), (200, 170, 50), (100, 50, 180),
                                  (40, 150, 200), (220, 100, 140)]
                    r, g, b = coral_cols[coral_idx]

            # --- MC-style height shading ---
            hn = heights[max(0, sy - 1)][sx]
            h_diff = h - hn
            if is_water or h < 1:
                shade = 1.0
            elif h_diff > 1.5:
                shade = 1.0   # MC shade 2: brighter (higher than north)
            elif h_diff < -1.5:
                shade = 0.82  # MC shade 0: darker (lower than north)
            else:
                shade = 0.92  # MC shade 1: normal

            # Enhanced hillshade: blend MC discrete shading with directional light
            hs_s = heights[min(sample_resolution - 1, sy + 1)][sx]
            he = heights[sy][min(sample_resolution - 1, sx + 1)]
            hw = heights[sy][max(0, sx - 1)]
            shade_k = 0.15 if not is_ow else 0.12
            dzdx = (he - hw) * shade_k
            dzdy = (hs_s - hn) * shade_k
            slope = (dzdx * dzdx + dzdy * dzdy) ** 0.5
            light = (-dzdx * 0.7 - dzdy * 0.7) / max(slope, 0.01)
            dir_shade = 0.6 + 0.4 * max(-1.0, min(1.0, light))
            ao = 1.0 - min(0.15, slope * 0.08)
            # 60% MC shading + 40% directional for depth
            shade = shade * 0.6 + dir_shade * ao * 0.4

            # --- Hypsometric tinting ---
            if h > 0 and not is_water:
                ht = (h - h_min) / h_range
                if is_ow:
                    r = int(r * (0.96 + 0.08 * ht))
                    g = int(g * (0.97 + 0.06 * ht))
                    b = int(b * (1.0 + 0.10 * ht))
                elif is_nether:
                    r = int(r * (1.0 + 0.06 * (1.0 - ht)))
                elif is_end or is_pl:
                    f = 0.97 + 0.06 * ht
                    r = int(r * f); g = int(g * f); b = int(b * (f + 0.02))

            # --- Snow line ---
            if is_ow and h > 170 and not is_canopy and not is_islands and not is_cave:
                snow_t = min(1.0, (h - 170) / 60.0)
                snow_t *= snow_t
                r = int(r * (1.0 - snow_t * 0.4) + 235 * snow_t * 0.4)
                g = int(g * (1.0 - snow_t * 0.3) + 240 * snow_t * 0.3)
                b = int(b * (1.0 - snow_t * 0.3) + 250 * snow_t * 0.3)

            # --- Void/lava ---
            is_void = False
            if is_end and h < 1.0:
                r, g, b = 8, 5, 15; shade = 1.0; is_void = True
            elif is_nether and h < 1.0:
                r, g, b = 60, 20, 5; shade = 1.0; is_void = True
            elif is_pl and h < 40:
                r, g, b = 160, 190, 220; shade = 1.0; is_void = True
            elif is_islands and island_void:
                r, g, b = 8, 5, 15; shade = 1.0; is_void = True

            # --- Void edge glow ---
            if not is_void and (is_end or is_nether or is_pl) and not is_islands:
                for dy2, dx2 in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                    ny, nx = sy + dy2, sx + dx2
                    if 0 <= ny < sample_resolution and 0 <= nx < sample_resolution:
                        ah = heights[ny][nx]
                        if (is_end and ah < 1.0) or (is_nether and ah < 1.0) or (is_pl and ah < 40):
                            if is_end:
                                r = min(255, int(r * 0.7 + 80 * 0.3))
                                g = min(255, int(g * 0.7 + 60 * 0.3))
                                b = min(255, int(b * 0.6 + 140 * 0.4))
                            elif is_nether:
                                r = min(255, int(r * 0.6 + 200 * 0.4))
                                g = min(255, int(g * 0.7 + 80 * 0.3))
                                b = min(255, int(b * 0.8 + 20 * 0.2))
                            else:
                                r = min(255, int(r * 0.7 + 200 * 0.3))
                                g = min(255, int(g * 0.7 + 220 * 0.3))
                                b = min(255, int(b * 0.7 + 240 * 0.3))
                            break

            # --- Island void edge glow ---
            if not is_void and is_islands:
                for dby, dbx in ((-blocks_per_pixel, 0), (blocks_per_pixel, 0),
                                  (0, -blocks_per_pixel), (0, blocks_per_pixel)):
                    if _island_noise(iseed, bx + dbx, bz + dby) < ISLAND_THRESHOLD:
                        r = min(255, int(r * 0.7 + 80 * 0.3))
                        g = min(255, int(g * 0.7 + 60 * 0.3))
                        b = min(255, int(b * 0.6 + 140 * 0.4))
                        break

            # --- Water depth ---
            if is_water:
                if cont < -0.455:
                    df = 0.65 + 0.2 * max(0, (cont + 1.05) / 0.595)
                elif cont < -0.19:
                    df = 0.80 + 0.15 * max(0, (cont + 0.455) / 0.265)
                else:
                    df = 0.95
                df = max(0.55, min(1.0, df))
                r = int(r * df); g = int(g * df); b = int(b * df)

            # --- Cave darkening and texture ---
            if is_cave and not is_void:
                ph_cave = _pixel_hash(iseed, bx + 12345, bz + 67890)
                mottled = 0.25 + 0.10 * ((ph_cave & 0xFF) / 255.0)
                r = int(r * mottled)
                g = int(g * mottled)
                b = int(b * mottled)
                rock_r, rock_g, rock_b = 35, 32, 30
                blend = 0.3 + 0.2 * (((ph_cave >> 8) & 0xFF) / 255.0)
                r = int(r * (1 - blend) + rock_r * blend)
                g = int(g * (1 - blend) + rock_g * blend)
                b = int(b * (1 - blend) + rock_b * blend)
                # Subtle contour-like patterns suggesting cave passages
                passage = _island_noise(iseed + 999, bx, bz, scale=80)
                if 0.48 < passage < 0.52:
                    r = min(255, r + 8)
                    g = min(255, g + 6)
                    b = min(255, b + 5)

            # --- Contour lines ---
            if not is_water and not is_void and not is_cave and h > 10:
                ci = 20 if is_ow else 15
                cb = h % ci
                if cb < 1.5 or cb > ci - 1.5:
                    r = int(r * 0.88); g = int(g * 0.88); b = int(b * 0.88)

            # --- Final shade ---
            r = max(0, min(255, int(r * shade)))
            g = max(0, min(255, int(g * shade)))
            b = max(0, min(255, int(b * shade)))

            off = (py * size + px) * 3
            pixels[off] = r
            pixels[off + 1] = g
            pixels[off + 2] = b

    write_png(output_path, bytes(pixels), size, size)
    return size


def write_png_rgba(path, pixels, width, height):
    """Write an RGBA pixel buffer as a PNG file. No dependencies."""
    def chunk(tag, data):
        raw = tag + data
        return struct.pack(">I", len(data)) + raw + struct.pack(">I", zlib.crc32(raw) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    raw_rows = b""
    for y in range(height):
        raw_rows += b"\x00"
        raw_rows += pixels[y * width * 4:(y + 1) * width * 4]

    out = b"\x89PNG\r\n\x1a\n"
    out += chunk(b"IHDR", ihdr)
    out += chunk(b"IDAT", zlib.compress(raw_rows, 6))
    out += chunk(b"IEND", b"")
    Path(path).write_bytes(out)


def render_structure_overlay(structure_all, out_path, size, total_blocks):
    """Write a transparent RGBA overlay PNG plotting EVERY placement in
    structure_all ({name: [(dist, x, z), ...]}, banked on the candidate by
    viewer-server's finalise enrichment via find_all_in_radius).

    size/total_blocks must match the base render the overlay stacks over —
    the block→pixel mapping is identical to render_biome_map's. Placements
    are RandomSpread rolls without biome validation: markers show where a
    structure start CAN roll, not a guarantee it generates."""
    half = total_blocks // 2
    pixels = bytearray(size * size * 4)  # zeroed = fully transparent
    for sname, hits in sorted(structure_all.items()):
        col, r_fp = _struct_style(sname)
        border = (max(0, col[0] - 70), max(0, col[1] - 70), max(0, col[2] - 70))
        for _dist, bx, bz in hits:
            cx = int((bx + half) / total_blocks * size)
            cy = int((bz + half) / total_blocks * size)
            if cx < -r_fp - 1 or cx > size + r_fp or cy < -r_fp - 1 or cy > size + r_fp:
                continue
            for dy in range(-r_fp - 1, r_fp + 2):
                for dx in range(-r_fp - 1, r_fp + 2):
                    npx, npy = cx + dx, cy + dy
                    if not (0 <= npx < size and 0 <= npy < size):
                        continue
                    ring = max(abs(dx), abs(dy))
                    off = (npy * size + npx) * 4
                    if ring <= r_fp:
                        pixels[off:off + 3] = bytes(col)
                        pixels[off + 3] = 255
                    elif pixels[off + 3] == 0:
                        pixels[off:off + 3] = bytes(border)
                        pixels[off + 3] = 200
    write_png_rgba(out_path, bytes(pixels), size, size)


FAMILY_NOISE = {
    "overworld": "overworld", "nether": "nether", "end": "end",
    "paradise_lost": "paradise_lost", None: "overworld",
}
TYPE_NOISE_OVERRIDE = {
    "paradise_lost:paradise_lost": "paradise_lost",
    "sky_islands": "overworld",
    "nether_islands": "nether",
}


def _render_one(task):
    """Multiprocessing worker: render one candidate."""
    (seed, dim_name, family, dim_type, biome_csv, biome_params_path,
     output_path, size, scale, sample_res) = task
    configs = load_noise_configs()
    noise_family = TYPE_NOISE_OVERRIDE.get(dim_type, FAMILY_NOISE.get(family, "overworld"))
    noise_config = configs.get(noise_family, configs.get("overworld"))
    biome_filter = [b.strip() for b in biome_csv.split(",") if b.strip()] if biome_csv else None
    try:
        render_biome_map(seed, biome_params_path, output_path,
                         noise_config=noise_config, family=noise_family,
                         dim_type=dim_type, biome_filter=biome_filter,
                         size=size, blocks_per_pixel=scale,
                         sample_resolution=sample_res)
        return dim_name, seed, True
    except Exception as e:
        return dim_name, seed, str(e)


def batch_render(config_path, seedtest_path, biome_params_path,
                 top=10, size=1024, scale=8, sample_resolution=256,
                 workers=0, dims_filter=None, suffix=""):
    """Render biome maps for top-N candidates per dimension. No MC server."""
    import multiprocessing
    import time

    from dimension_profiles import load_config, load_difficulty, build_profile, rollable
    import candidates as cmod

    config = load_config(config_path)
    difficulty = load_difficulty(config_path)

    dims = {d["name"]: d for d in config["dimensions"] if rollable(d)}
    worlds = {w["name"]: w for w in config.get("worlds", [])}
    all_targets = {**worlds, **dims}
    if dims_filter:
        wanted = {d.strip() for d in dims_filter.split(",")}
        all_targets = {k: v for k, v in all_targets.items() if k in wanted}

    cdir = cmod.candidates_dir(Path(config_path))
    renders_dir = Path(seedtest_path) / "renders"
    tasks = []

    for name, dim in all_targets.items():
        profile = build_profile(dim, config, difficulty)
        store = cmod.load_store(cdir / f"{name}.json")
        scored = []
        for seed, cand in store["candidates"].items():
            best_score = max((s.get("total", 0) for s in cand.get("scores", {}).values()), default=0)
            if best_score > 0:
                scored.append((best_score, seed))
        scored.sort(reverse=True)

        dim_type = dim.get("type", "")
        fam = profile.get("family", "overworld")

        dim_scale = profile.get("scale", 1.0)
        effective_scale = max(1, int(scale / dim_scale))

        for _score, seed in scored[:top]:
            out = renders_dir / name / f"{seed}{suffix}.png"
            if out.exists():
                continue
            out.parent.mkdir(parents=True, exist_ok=True)
            tasks.append((int(seed), name, fam, dim_type, dim.get("biome") or None,
                          biome_params_path, str(out), size, effective_scale, sample_resolution))

    if not tasks:
        print("All candidates already have renders.")
        return 0

    num_workers = workers or min(multiprocessing.cpu_count(), len(tasks))
    print(f"Rendering {len(tasks)} biome maps ({size}x{size} px, {size*scale//1000}km view)")
    print(f"  Workers: {num_workers}, sample resolution: {sample_resolution}")

    t0 = time.time()
    rendered = 0
    failed = 0
    if num_workers > 1 and len(tasks) > 1:
        with multiprocessing.Pool(num_workers) as pool:
            for dim_name, seed, result in pool.imap_unordered(_render_one, tasks):
                if result is True:
                    rendered += 1
                else:
                    failed += 1
                if (rendered + failed) % 10 == 0 or rendered + failed == len(tasks):
                    print(f"  [{rendered + failed}/{len(tasks)}] {rendered} ok, {failed} failed", flush=True)
    else:
        for task in tasks:
            dim_name, seed, result = _render_one(task)
            if result is True:
                rendered += 1
            else:
                failed += 1

    elapsed = time.time() - t0
    print(f"Rendered {rendered}/{len(tasks)} in {elapsed:.0f}s "
          f"({elapsed/max(rendered,1):.1f}s/render, {failed} failed)")
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="command")

    single = sub.add_parser("render", help="Render one seed")
    single.add_argument("--seed", type=int, required=True)
    single.add_argument("--output", required=True)
    single.add_argument("--family", default="overworld")
    single.add_argument("--size", type=int, default=1024)
    single.add_argument("--scale", type=int, default=8)
    single.add_argument("--biome-params",
                        default=str(Path(__file__).resolve().parent / "biome_params.json"))

    batch = sub.add_parser("batch", help="Render top-N candidates per dimension")
    batch.add_argument("--config", required=True)
    batch.add_argument("--seedtest", required=True)
    batch.add_argument("--biome-params",
                       default=str(Path(__file__).resolve().parent / "biome_params.json"))
    batch.add_argument("--top", type=int, default=10)
    batch.add_argument("--size", type=int, default=1024)
    batch.add_argument("--scale", type=int, default=8)
    batch.add_argument("--sample-res", type=int, default=256)
    batch.add_argument("--workers", type=int, default=0)
    batch.add_argument("--dims", help="Comma-separated dimension names")
    batch.add_argument("--suffix", default="",
                       help="Filename suffix before .png (e.g. '_hires')")

    args = ap.parse_args()

    if args.command == "render":
        configs = load_noise_configs()
        noise_config = configs.get(args.family)
        if not noise_config:
            sys.exit(f"Unknown family '{args.family}'. Available: {', '.join(configs.keys())}")
        import time
        t0 = time.time()
        sz = render_biome_map(args.seed, args.biome_params, args.output,
                              noise_config=noise_config, family=args.family,
                              size=args.size, blocks_per_pixel=args.scale)
        elapsed = time.time() - t0
        print(f"Rendered {sz}x{sz} px ({sz*args.scale}x{sz*args.scale} blocks) "
              f"in {elapsed:.1f}s → {args.output}")

    elif args.command == "batch":
        return batch_render(args.config, args.seedtest, args.biome_params,
                            top=args.top, size=args.size, scale=args.scale,
                            sample_resolution=args.sample_res,
                            workers=args.workers, dims_filter=args.dims,
                            suffix=args.suffix)
    else:
        ap.print_help()


if __name__ == "__main__":
    main()
