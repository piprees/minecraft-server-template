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
from surface_rules import surface_and_density  # noqa: E402
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


def render_biome_map(seed, biome_params_path, output_path,
                     noise_config=None, family=None, biome_filter=None,
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
            (surf_col, density) = surface_and_density(biome)
            id_col = biome_colour(biome)
            # 55/45: pure surface makes grass biomes identical; pure identity loses terrain character
            colour = (
                int(surf_col[0] * 0.55 + id_col[0] * 0.45),
                int(surf_col[1] * 0.55 + id_col[1] * 0.45),
                int(surf_col[2] * 0.55 + id_col[2] * 0.45),
            )
            row.append((biome, colour, density))
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

    # Spatial smoothing for non-overworld: box filter removes high-frequency
    # noise from spline transitions (nether void/lava boundaries) and
    # aliased weirdness noise (paradise_lost xz_scale=1.0).
    _SMOOTH_RADIUS = {"nether": 2, "end": 1, "paradise_lost": 6}
    sr = _SMOOTH_RADIUS.get(family, 0)
    if sr > 0:
        smoothed = [[0.0] * sample_resolution for _ in range(sample_resolution)]
        for sy in range(sample_resolution):
            for sx in range(sample_resolution):
                total = 0.0
                count = 0
                y0 = max(0, sy - sr)
                y1 = min(sample_resolution, sy + sr + 1)
                x0 = max(0, sx - sr)
                x1 = min(sample_resolution, sx + sr + 1)
                for ny in range(y0, y1):
                    for nx in range(x0, x1):
                        total += heights[ny][nx]
                        count += 1
                smoothed[sy][sx] = total / count
        heights = smoothed

    # Biome colour anti-aliasing for high-frequency families: paradise_lost's
    # weirdness noise (xz_scale=1.0, 64-block wavelength) oscillates faster
    # than the sample grid (16-block steps), causing biome aliasing. A large
    # box filter on the colour grid smooths the mottled texture.
    _COLOUR_SMOOTH_RADIUS = {"paradise_lost": 6}
    csr = _COLOUR_SMOOTH_RADIUS.get(family, 0)
    if csr > 0:
        smoothed_grid = []
        for sy in range(sample_resolution):
            row = []
            for sx in range(sample_resolution):
                tr, tg, tb, td = 0, 0, 0, 0.0
                count = 0
                y0 = max(0, sy - csr)
                y1 = min(sample_resolution, sy + csr + 1)
                x0 = max(0, sx - csr)
                x1 = min(sample_resolution, sx + csr + 1)
                for ny in range(y0, y1):
                    for nx in range(x0, x1):
                        _, (cr, cg, cb), d = grid[ny][nx]
                        tr += cr; tg += cg; tb += cb; td += d
                        count += 1
                row.append((grid[sy][sx][0],
                            (tr // count, tg // count, tb // count),
                            td / count))
            smoothed_grid.append(row)
        grid = smoothed_grid

    pixels = bytearray(size * size * 3)
    for py in range(size):
        sy = min(py // upscale, sample_resolution - 1)
        for px in range(size):
            sx = min(px // upscale, sample_resolution - 1)
            biome_id, (r, g, b), veg_density = grid[sy][sx]
            c = climates[sy][sx]
            cont = c.get("continentalness", 0.0)
            weird = c.get("weirdness", 0.0)
            h = heights[sy][sx]

            # Hillshade from terrain heights (stronger for compressed dimensions)
            hn = heights[max(0, sy - 1)][sx]
            hs = heights[min(sample_resolution - 1, sy + 1)][sx]
            he = heights[sy][min(sample_resolution - 1, sx + 1)]
            hw = heights[sy][max(0, sx - 1)]

            shade_k = 0.10 if family == "end" else (0.08 if family in ("nether", "paradise_lost") else 0.12)
            dzdx = (he - hw) * shade_k
            dzdy = (hs - hn) * shade_k
            slope = (dzdx * dzdx + dzdy * dzdy) ** 0.5
            light = (-dzdx * 0.7 - dzdy * 0.7) / max(slope, 0.01)
            shade = 0.6 + 0.4 * max(-1.0, min(1.0, light))

            # Weirdness micro-texture
            weird_tex = 1.0 + weird * 0.03
            r = int(r * weird_tex)
            g = int(g * weird_tex)
            b = int(b * weird_tex)

            # Vegetation density (blend towards dark green, not black)
            if veg_density < 1.0:
                canopy_r, canopy_g, canopy_b = 20, 40, 10
                f = veg_density
                r = int(r * (0.3 + 0.7 * f) + canopy_r * (1 - f))
                g = int(g * (0.4 + 0.6 * f) + canopy_g * (1 - f))
                b = int(b * (0.3 + 0.7 * f) + canopy_b * (1 - f))

            # Void/lava rendering for non-overworld
            if family == "end" and h < 5.0:
                r, g, b = 8, 5, 15
                shade = 1.0
            elif family == "nether" and h < 5.0:
                r, g, b = 60, 20, 5
                shade = 1.0

            # Water depth gradient
            is_water = "ocean" in biome_id or "river" in biome_id
            if is_water:
                if cont < -0.455:
                    depth_factor = 0.65 + 0.2 * max(0, (cont + 1.05) / 0.595)
                elif cont < -0.19:
                    depth_factor = 0.80 + 0.15 * max(0, (cont + 0.455) / 0.265)
                else:
                    depth_factor = 0.95
                depth_factor = max(0.55, min(1.0, depth_factor))
                r = int(r * depth_factor)
                g = int(g * depth_factor)
                b = int(b * depth_factor)

            # Apply hillshade (strongly reduced on water)
            final_shade = 0.85 + 0.15 * shade if is_water else shade
            r = max(0, min(255, int(r * final_shade)))
            g = max(0, min(255, int(g * final_shade)))
            b = max(0, min(255, int(b * final_shade)))

            off = (py * size + px) * 3
            pixels[off] = r
            pixels[off + 1] = g
            pixels[off + 2] = b

    write_png(output_path, bytes(pixels), size, size)
    return size


def overlay_structures(png_path, seed, dim_name, config_path, size, blocks_per_pixel):
    """Draw structure markers on an existing render PNG. Uses structure_placement
    to compute positions, then draws coloured dots with labels at the edge."""
    from structure_placement import load_structure_sets, nearest_structure
    from dimension_profiles import load_config, load_difficulty, build_profile

    config = load_config(config_path)
    difficulty = load_difficulty(config_path)
    all_dims = {d["name"]: d for d in config.get("dimensions", [])}
    all_dims.update({w["name"]: w for w in config.get("worlds", [])})
    if dim_name not in all_dims:
        return

    profile = build_profile(all_dims[dim_name], config, difficulty)
    struct_sets_dir = Path(config_path).parent.parent / ".seedtest" / ".structure_sets"
    if not struct_sets_dir.exists():
        return

    struct_sets = load_structure_sets(str(struct_sets_dir))
    struct_to_sets = {}
    for set_id, cfg in struct_sets.items():
        for s in cfg["structures"]:
            struct_to_sets.setdefault(s["id"], []).append(set_id)

    total_blocks = size * blocks_per_pixel
    half = total_blocks // 2

    # Read existing PNG pixels
    data = Path(png_path).read_bytes()
    if not data.startswith(b"\x89PNG"):
        return

    import zlib as _zlib
    pos = 8
    width = height = 0
    idat = b""
    while pos < len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        tag = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + length]
        if tag == b"IHDR":
            width, height = struct.unpack(">II", body[:8])
        elif tag == b"IDAT":
            idat += body
        elif tag == b"IEND":
            break
        pos += 12 + length

    raw = _zlib.decompress(idat)
    pixels = bytearray(width * height * 3)
    for y in range(height):
        off = y * (width * 3 + 1) + 1
        pixels[y * width * 3:(y + 1) * width * 3] = raw[off:off + width * 3]

    # Plot structure markers
    colours = [(255, 80, 80), (80, 200, 255), (255, 200, 50), (80, 255, 120),
               (200, 120, 255), (255, 160, 80), (120, 200, 200), (200, 200, 100)]
    markers = []
    for i, (sname, sid, _spec, kind) in enumerate(profile["battery"]):
        clean = sid.lstrip("#")
        set_cfg = None
        if clean in struct_to_sets:
            set_cfg = struct_sets[struct_to_sets[clean][0]]
        elif clean in struct_sets:
            set_cfg = struct_sets[clean]
        if not set_cfg:
            continue

        result = nearest_structure(
            int(seed), set_cfg["spacing"], set_cfg["separation"],
            set_cfg["salt"], spread_type=set_cfg.get("spread_type", "linear"),
            frequency=set_cfg.get("frequency", 1.0), search_radius=30)
        if not result:
            continue
        dist, bx, bz = result
        # Convert block coords to pixel coords
        px = int((bx + half) / total_blocks * width)
        py = int((bz + half) / total_blocks * height)
        if 0 <= px < width and 0 <= py < height:
            col = colours[i % len(colours)]
            markers.append((px, py, sname, col, kind))

    # Draw markers: filled circles with dark border, sized for visibility
    for mx, my, _name, col, kind in markers:
        r_dot = 6 if kind == "want" else 4
        for dy in range(-r_dot - 1, r_dot + 2):
            for dx in range(-r_dot - 1, r_dot + 2):
                nx, ny = mx + dx, my + dy
                if 0 <= nx < width and 0 <= ny < height:
                    dist_sq = dx * dx + dy * dy
                    off = (ny * width + nx) * 3
                    if dist_sq <= r_dot * r_dot:
                        pixels[off:off + 3] = bytes(col)
                    elif dist_sq <= (r_dot + 1) * (r_dot + 1):
                        pixels[off:off + 3] = bytes([20, 20, 20])

    write_png(png_path, bytes(pixels), width, height)


FAMILY_NOISE = {
    "overworld": "overworld", "nether": "nether", "end": "end",
    "paradise_lost": "paradise_lost", None: "overworld",
}
TYPE_NOISE_OVERRIDE = {"paradise_lost:paradise_lost": "paradise_lost"}


def _render_one(task):
    """Multiprocessing worker: render one candidate."""
    seed, dim_name, family, dim_type, biome_params_path, output_path, size, scale, sample_res = task
    configs = load_noise_configs()
    noise_family = TYPE_NOISE_OVERRIDE.get(dim_type, FAMILY_NOISE.get(family, "overworld"))
    noise_config = configs.get(noise_family, configs.get("overworld"))
    try:
        render_biome_map(seed, biome_params_path, output_path,
                         noise_config=noise_config, family=noise_family,
                         size=size, blocks_per_pixel=scale,
                         sample_resolution=sample_res)
        return dim_name, seed, True
    except Exception as e:
        return dim_name, seed, str(e)


def batch_render(config_path, seedtest_path, biome_params_path,
                 top=10, size=512, scale=8, sample_resolution=128,
                 workers=0, dims_filter=None):
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

        for _score, seed in scored[:top]:
            out = renders_dir / name / f"{seed}.png"
            if out.exists():
                continue
            out.parent.mkdir(parents=True, exist_ok=True)
            tasks.append((int(seed), name, fam, dim_type, biome_params_path,
                          str(out), size, scale, sample_resolution))

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
                if (rendered + failed) % 50 == 0:
                    print(f"  [{rendered + failed}/{len(tasks)}] {rendered} ok, {failed} failed")
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
    single.add_argument("--scale", type=int, default=4)
    single.add_argument("--biome-params",
                        default=str(Path(__file__).resolve().parent / "biome_params.json"))

    batch = sub.add_parser("batch", help="Render top-N candidates per dimension")
    batch.add_argument("--config", required=True)
    batch.add_argument("--seedtest", required=True)
    batch.add_argument("--biome-params",
                       default=str(Path(__file__).resolve().parent / "biome_params.json"))
    batch.add_argument("--top", type=int, default=10)
    batch.add_argument("--size", type=int, default=512)
    batch.add_argument("--scale", type=int, default=8)
    batch.add_argument("--sample-res", type=int, default=128)
    batch.add_argument("--workers", type=int, default=0)
    batch.add_argument("--dims", help="Comma-separated dimension names")

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
                            workers=args.workers, dims_filter=args.dims)
    else:
        ap.print_help()


if __name__ == "__main__":
    main()
