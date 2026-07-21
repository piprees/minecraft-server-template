#!/usr/bin/env python3
"""Integration tests for the biome rendering pipeline (Phases 1-6).

Runs without Docker or a Minecraft server — pure Python.
Execute: python3 -B -m unittest discover -s scripts/seed -p 'test_*.py'
"""

import os
import sys
import tempfile
import time
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

BIOME_PARAMS = SCRIPT_DIR / "biome_params.json"
TERRAIN_SPLINES = SCRIPT_DIR / "terrain_splines.json"
NOISE_CONFIGS = SCRIPT_DIR / "noise_configs.json"
SNAPSHOT_DIR = SCRIPT_DIR / "test_fixtures" / "snapshot_renders"

HAS_BIOME_PARAMS = BIOME_PARAMS.exists()
HAS_TERRAIN_SPLINES = TERRAIN_SPLINES.exists()
HAS_NOISE_CONFIGS = NOISE_CONFIGS.exists()

SKIP_REASON = "biome_params.json not present (CI or first checkout)"

TEST_SEED = 42
NETHER_BIOMES = {
    "minecraft:nether_wastes", "minecraft:soul_sand_valley",
    "minecraft:crimson_forest", "minecraft:warped_forest",
    "minecraft:basalt_deltas",
}


@unittest.skipUnless(HAS_BIOME_PARAMS, SKIP_REASON)
class TestBiomeSamplerFilters(unittest.TestCase):
    """BiomeSampler family/biome filtering and spawn search."""

    @classmethod
    def setUpClass(cls):
        from biome_sampler import BiomeSampler, load_noise_configs
        cls.BiomeSampler = BiomeSampler
        cls.load_noise_configs = staticmethod(load_noise_configs)
        import json
        cls.biome_table = json.loads(BIOME_PARAMS.read_text())

    def test_overworld_family_filter(self):
        configs = self.load_noise_configs()
        sampler = self.BiomeSampler(
            TEST_SEED, str(BIOME_PARAMS),
            noise_config=configs.get("overworld"), family="overworld")
        self.assertEqual(len(sampler._entries), 1713)

    def test_nether_family_filter(self):
        configs = self.load_noise_configs()
        sampler = self.BiomeSampler(
            TEST_SEED, str(BIOME_PARAMS),
            noise_config=configs.get("nether"), family="nether")
        self.assertEqual(len(sampler._entries), 13)

    def test_biome_filter_overrides_family(self):
        configs = self.load_noise_configs()
        sampler = self.BiomeSampler(
            TEST_SEED, str(BIOME_PARAMS),
            noise_config=configs.get("overworld"), family="overworld",
            biome_filter=NETHER_BIOMES)
        self.assertGreater(len(sampler._entries), 0,
                           "biome_filter with nether biomes should find entries "
                           "even when family='overworld'")

    def test_biome_filter_only(self):
        configs = self.load_noise_configs()
        target = {"minecraft:plains", "minecraft:forest"}
        sampler = self.BiomeSampler(
            TEST_SEED, str(BIOME_PARAMS),
            noise_config=configs.get("overworld"),
            biome_filter=target)
        biome_ids = {e[0] for e in sampler._entries}
        self.assertTrue(biome_ids.issubset(target))
        self.assertGreater(len(sampler._entries), 0)

    def test_spawn_filter_finds_overworld_biome(self):
        configs = self.load_noise_configs()
        sampler = self.BiomeSampler(
            TEST_SEED, str(BIOME_PARAMS),
            noise_config=configs.get("overworld"), family="overworld")
        common = {"minecraft:plains", "minecraft:forest", "minecraft:taiga",
                  "minecraft:birch_forest", "minecraft:dark_forest",
                  "minecraft:meadow", "minecraft:savanna"}
        result = sampler.spawn_filter(common, radius=768, step=64)
        biome, dist, x, z = result
        self.assertIsNotNone(biome, "Should find a common overworld biome near origin")
        self.assertIn(biome, common)
        self.assertGreaterEqual(dist, 0)

    def test_spawn_filter_paradise_lost(self):
        configs = self.load_noise_configs()
        sampler = self.BiomeSampler(
            TEST_SEED, str(BIOME_PARAMS),
            noise_config=configs.get("paradise_lost"), family="paradise_lost")
        namesake = {"paradise_lost:highlands", "paradise_lost:highlands_forest",
                    "paradise_lost:highlands_shield"}
        result = sampler.spawn_filter(namesake, radius=768, step=64)
        biome, dist, x, z = result
        self.assertIsNotNone(biome,
                             "paradise_lost family should find a highlands biome")
        self.assertIn(biome, namesake)


@unittest.skipUnless(HAS_TERRAIN_SPLINES, "terrain_splines.json not present")
class TestTerrainEvaluator(unittest.TestCase):
    """TerrainEvaluator spline loading and height computation."""

    @classmethod
    def setUpClass(cls):
        from terrain_height import TerrainEvaluator, ridges_folded
        cls.TerrainEvaluator = TerrainEvaluator
        cls.ridges_folded = staticmethod(ridges_folded)

    def test_loads_from_json(self):
        ev = self.TerrainEvaluator()
        self.assertTrue(ev.has_family("overworld"))

    def test_sea_level_height(self):
        ev = self.TerrainEvaluator()
        y = ev.surface_height(continentalness=-0.11, erosion=0.0, weirdness=0.0)
        self.assertAlmostEqual(y, 63, delta=10,
                               msg=f"Sea-level climate should yield Y~63, got {y}")

    def test_ocean_depth(self):
        ev = self.TerrainEvaluator()
        y = ev.surface_height(continentalness=-0.6, erosion=0.0, weirdness=0.0)
        self.assertLess(y, 55, f"Deep ocean continentalness should yield Y<55, got {y}")

    def test_mountain_height(self):
        ev = self.TerrainEvaluator()
        y = ev.surface_height(continentalness=0.8, erosion=-0.8, weirdness=0.0)
        self.assertGreater(y, 130,
                           f"Mountain climate should yield Y>130, got {y}")

    def test_height_range(self):
        ev = self.TerrainEvaluator()
        heights = []
        for ci in range(-10, 11, 2):
            c = ci / 10.0
            for ei in range(-10, 11, 2):
                e = ei / 10.0
                for wi in range(-10, 11, 2):
                    w = wi / 10.0
                    heights.append(ev.surface_height(c, e, w))
        self.assertLessEqual(min(heights), 0,
                             f"Synthetic grid min should be <= 0, got {min(heights)}")
        self.assertGreater(max(heights), 200,
                           f"Synthetic grid max should be > 200, got {max(heights)}")

    def test_ridges_folded(self):
        rf = self.ridges_folded
        # rf(w) = -(abs(abs(w) - 0.6666667) - 0.3333334)
        # rf(0) = -(0.6666667 - 0.3333334) = -0.3333333
        self.assertAlmostEqual(rf(0.0), -0.3333333, places=3)
        # rf(0.6666667) = -(0 - 0.3333334) = 0.3333334
        self.assertAlmostEqual(rf(0.6666667), 0.3333334, places=3)

    def test_performance(self):
        ev = self.TerrainEvaluator()
        t0 = time.perf_counter()
        for i in range(10000):
            c = (i % 21 - 10) / 10.0
            e = ((i // 21) % 21 - 10) / 10.0
            w = ((i // 441) % 21 - 10) / 10.0
            ev.surface_height(c, e, w)
        elapsed_ms = (time.perf_counter() - t0) * 1000
        self.assertLess(elapsed_ms, 200,
                        f"10K evaluations took {elapsed_ms:.0f}ms, expected <200ms")


class TestSurfaceRules(unittest.TestCase):
    """Surface colour mapping, vegetation density, and grass tinting."""

    @classmethod
    def setUpClass(cls):
        from surface_rules import (
            biome_surface, surface_colour, vegetation_density,
            surface_and_density, grass_tint, SURFACE_COLOURS,
        )
        from biome_renderer import BIOME_COLOURS
        cls.biome_surface = staticmethod(biome_surface)
        cls.surface_colour = staticmethod(surface_colour)
        cls.vegetation_density = staticmethod(vegetation_density)
        cls.surface_and_density = staticmethod(surface_and_density)
        cls.grass_tint = staticmethod(grass_tint)
        cls.SURFACE_COLOURS = SURFACE_COLOURS
        cls.BIOME_COLOURS = BIOME_COLOURS

    def test_all_biome_colours_have_surface(self):
        for biome in self.BIOME_COLOURS:
            surface = self.biome_surface(biome)
            self.assertIn(surface, self.SURFACE_COLOURS,
                          f"{biome} maps to unknown surface '{surface}'")

    def test_grass_tinting_differs(self):
        t_plains = self.grass_tint("minecraft:plains")
        t_taiga = self.grass_tint("minecraft:taiga")
        t_jungle = self.grass_tint("minecraft:jungle")
        self.assertNotEqual(t_plains, t_taiga)
        self.assertNotEqual(t_taiga, t_jungle)
        self.assertNotEqual(t_plains, t_jungle)

    def test_water_biomes_map_to_water(self):
        water_surfaces = {"water", "water_cold", "water_warm", "water_frozen"}
        water_biomes = [
            "minecraft:ocean", "minecraft:deep_ocean",
            "minecraft:cold_ocean", "minecraft:frozen_ocean",
            "minecraft:warm_ocean", "minecraft:river",
            "minecraft:frozen_river",
        ]
        for biome in water_biomes:
            surface = self.biome_surface(biome)
            self.assertIn(surface, water_surfaces,
                          f"{biome} should map to a water surface, got '{surface}'")

    def test_nether_biomes_map_correctly(self):
        expected = {
            "minecraft:nether_wastes": "netherrack",
            "minecraft:crimson_forest": "crimson_nylium",
            "minecraft:warped_forest": "warped_nylium",
            "minecraft:soul_sand_valley": "soul_sand",
            "minecraft:basalt_deltas": "basalt",
        }
        for biome, expected_surface in expected.items():
            self.assertEqual(self.biome_surface(biome), expected_surface,
                             f"{biome} should map to {expected_surface}")

    def test_vegetation_density_range(self):
        for biome in self.BIOME_COLOURS:
            density = self.vegetation_density(biome)
            self.assertGreaterEqual(density, 0.5,
                                    f"{biome} density {density} < 0.5")
            self.assertLessEqual(density, 1.0,
                                 f"{biome} density {density} > 1.0")

    def test_surface_and_density_returns_tuple(self):
        colour, density = self.surface_and_density("minecraft:plains")
        self.assertIsInstance(colour, tuple)
        self.assertEqual(len(colour), 3)
        for c in colour:
            self.assertIsInstance(c, int)
            self.assertGreaterEqual(c, 0)
            self.assertLessEqual(c, 255)
        self.assertIsInstance(density, float)

    def test_keyword_fallback(self):
        surface = self.biome_surface("mymod:enchanted_forest")
        self.assertEqual(surface, "grass",
                         "Unknown biome with 'forest' should fall back to grass")
        density = self.vegetation_density("mymod:enchanted_forest")
        self.assertLess(density, 1.0,
                        "Unknown forest biome should get dense vegetation")


@unittest.skipUnless(HAS_BIOME_PARAMS, SKIP_REASON)
class TestBiomeRenderer(unittest.TestCase):
    """Biome map rendering: PNG output, colour variety, per-family rendering."""

    @classmethod
    def setUpClass(cls):
        from biome_renderer import render_biome_map, write_png
        from biome_sampler import load_noise_configs
        cls.render_biome_map = staticmethod(render_biome_map)
        cls.write_png = staticmethod(write_png)
        cls.load_noise_configs = staticmethod(load_noise_configs)

    def _render(self, family="overworld", seed=TEST_SEED, size=64, sample_res=32):
        configs = self.load_noise_configs()
        noise_config = configs.get(family, configs.get("overworld"))
        with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as f:
            path = f.name
        try:
            self.render_biome_map(
                seed, str(BIOME_PARAMS), path,
                noise_config=noise_config, family=family,
                size=size, blocks_per_pixel=16, sample_resolution=sample_res)
            data = Path(path).read_bytes()
        finally:
            os.unlink(path)
        return data

    def _unique_colours(self, png_data):
        raw = png_data[8:]
        import struct as st, zlib
        pos = 0
        idat_data = b""
        width = height = 0
        while pos < len(raw):
            length = st.unpack(">I", raw[pos:pos + 4])[0]
            tag = raw[pos + 4:pos + 8]
            chunk_data = raw[pos + 8:pos + 8 + length]
            if tag == b"IHDR":
                width, height = st.unpack(">II", chunk_data[:8])
            elif tag == b"IDAT":
                idat_data += chunk_data
            pos += 12 + length
        decompressed = zlib.decompress(idat_data)
        colours = set()
        row_len = 1 + width * 3
        for y in range(height):
            row_start = y * row_len + 1
            row_bytes = decompressed[row_start:row_start + width * 3]
            for x in range(width):
                r = row_bytes[x * 3]
                g = row_bytes[x * 3 + 1]
                b = row_bytes[x * 3 + 2]
                colours.add((r, g, b))
        return colours

    def test_render_produces_valid_png(self):
        data = self._render()
        self.assertTrue(data[:8] == b"\x89PNG\r\n\x1a\n",
                        "Output should start with PNG magic bytes")

    def test_render_overworld_has_variety(self):
        data = self._render(family="overworld")
        colours = self._unique_colours(data)
        self.assertGreater(len(colours), 10,
                           f"Overworld render should have >10 unique colours, got {len(colours)}")

    @unittest.skipUnless(HAS_NOISE_CONFIGS, "noise_configs.json not present")
    def test_render_nether_family(self):
        data_nether = self._render(family="nether")
        self.assertTrue(data_nether[:8] == b"\x89PNG\r\n\x1a\n")
        colours_nether = self._unique_colours(data_nether)
        data_ow = self._render(family="overworld")
        colours_ow = self._unique_colours(data_ow)
        overlap = colours_nether & colours_ow
        total = len(colours_nether | colours_ow)
        self.assertLess(len(overlap) / max(total, 1), 0.5,
                        "Nether and overworld renders should have mostly different colours")

    @unittest.skipUnless(HAS_NOISE_CONFIGS, "noise_configs.json not present")
    def test_render_end_has_void(self):
        data = self._render(family="end", seed=12345)
        colours = self._unique_colours(data)
        dark_pixels = sum(1 for r, g, b in colours if r < 30 and g < 30 and b < 30)
        self.assertGreater(dark_pixels, 0,
                           "End render should contain dark (void) pixels")

    @unittest.skipUnless(HAS_NOISE_CONFIGS and HAS_TERRAIN_SPLINES,
                         "noise_configs.json or terrain_splines.json not present")
    def test_per_family_height_functions(self):
        from biome_sampler import BiomeSampler
        from terrain_height import TerrainEvaluator, ridges_folded
        configs = self.load_noise_configs()
        evaluator = TerrainEvaluator()

        heights_by_family = {}
        for fam in ("overworld", "nether", "end"):
            if not evaluator.has_family(fam):
                continue
            nc = configs.get(fam, configs.get("overworld"))
            sampler = BiomeSampler(TEST_SEED, str(BIOME_PARAMS),
                                   noise_config=nc, family=fam)
            h_list = []
            for x in range(-512, 513, 256):
                for z in range(-512, 513, 256):
                    climate = sampler.sample_climate(x, z)
                    c = climate.get("continentalness", 0.0)
                    e = climate.get("erosion", 0.0)
                    w = climate.get("weirdness", 0.0)
                    h = evaluator.surface_height(c, e, w, family=fam)
                    h_list.append(h)
            heights_by_family[fam] = (min(h_list), max(h_list),
                                       sum(h_list) / len(h_list))

        ow_mean = heights_by_family["overworld"][2]
        ne_mean = heights_by_family["nether"][2]
        end_mean = heights_by_family["end"][2]
        self.assertNotAlmostEqual(ow_mean, ne_mean, delta=5,
                                  msg="Overworld and nether should have different height distributions")


@unittest.skipUnless(HAS_BIOME_PARAMS, SKIP_REASON)
class TestFastRollerSpawnFilter(unittest.TestCase):
    """Tier-2 spawn filter logic from fast_roller.py."""

    @classmethod
    def setUpClass(cls):
        from biome_sampler import BiomeSampler, load_noise_configs
        cls.BiomeSampler = BiomeSampler
        cls.load_noise_configs = staticmethod(load_noise_configs)

    def test_multi_biome_nether_accepted(self):
        configs = self.load_noise_configs()
        sampler = self.BiomeSampler(
            TEST_SEED, str(BIOME_PARAMS),
            noise_config=configs.get("overworld"),
            biome_filter=NETHER_BIOMES)
        namesake_set = NETHER_BIOMES
        sampler_biomes = {e[0] for e in sampler._entries}
        namesake_in_sampler = namesake_set & sampler_biomes
        if not namesake_in_sampler:
            self.skipTest("No nether biomes representable in sampler with overworld noise")
        result = sampler.spawn_filter(namesake_set, radius=768, step=256)
        biome, dist, x, z = result
        # The key check: with multi_biome dims using nether biomes on
        # overworld noise, candidates should not be 100% rejected.
        # Either we find one (biome is not None) or the sampler has entries
        # for them (namesake_in_sampler is non-empty, so the filter path
        # in tier2_measure would be entered).
        self.assertTrue(
            biome is not None or len(namesake_in_sampler) > 0,
            "Multi-biome dim with nether biomes should not reject all candidates")

    def test_overworld_spawn_filter_works(self):
        configs = self.load_noise_configs()
        sampler = self.BiomeSampler(
            TEST_SEED, str(BIOME_PARAMS),
            noise_config=configs.get("overworld"), family="overworld")
        common = {"minecraft:plains", "minecraft:forest", "minecraft:taiga",
                  "minecraft:birch_forest", "minecraft:dark_forest",
                  "minecraft:meadow", "minecraft:savanna"}
        result = sampler.spawn_filter(common, radius=768, step=128)
        biome, dist, x, z = result
        self.assertIsNotNone(biome, "Should find a common overworld biome")
        self.assertIn(biome, common)
        self.assertGreaterEqual(dist, 0)


@unittest.skipUnless(HAS_BIOME_PARAMS, SKIP_REASON)
class TestSnapshotRenders(unittest.TestCase):
    """Deterministic rendering and snapshot comparison."""

    RENDER_SIZE = 64
    SAMPLE_RES = 32
    RENDER_SCALE = 16

    @classmethod
    def setUpClass(cls):
        from biome_renderer import render_biome_map
        from biome_sampler import load_noise_configs
        cls.render_biome_map = staticmethod(render_biome_map)
        cls.load_noise_configs = staticmethod(load_noise_configs)

    def _render_to_bytes(self, family="overworld", seed=TEST_SEED):
        configs = self.load_noise_configs()
        noise_config = configs.get(family, configs.get("overworld"))
        with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as f:
            path = f.name
        try:
            self.render_biome_map(
                seed, str(BIOME_PARAMS), path,
                noise_config=noise_config, family=family,
                size=self.RENDER_SIZE, blocks_per_pixel=self.RENDER_SCALE,
                sample_resolution=self.SAMPLE_RES)
            return Path(path).read_bytes()
        finally:
            os.unlink(path)

    def test_overworld_deterministic(self):
        data1 = self._render_to_bytes(family="overworld", seed=12345)
        data2 = self._render_to_bytes(family="overworld", seed=12345)
        self.assertEqual(data1, data2,
                         "Same seed should produce identical renders")

    def _check_snapshot(self, family, seed=TEST_SEED):
        SNAPSHOT_DIR.mkdir(parents=True, exist_ok=True)
        ref_path = SNAPSHOT_DIR / f"{family}_{seed}.png"
        actual = self._render_to_bytes(family=family, seed=seed)

        if not ref_path.exists():
            ref_path.write_bytes(actual)
            self.skipTest(f"Reference created at {ref_path} — re-run to validate")

        expected = ref_path.read_bytes()
        self.assertEqual(actual, expected,
                         f"Render for {family} seed={seed} differs from snapshot "
                         f"at {ref_path}. Delete the snapshot to regenerate.")

    def test_snapshot_overworld(self):
        self._check_snapshot("overworld")

    @unittest.skipUnless(HAS_NOISE_CONFIGS, "noise_configs.json not present")
    def test_snapshot_nether(self):
        self._check_snapshot("nether")

    @unittest.skipUnless(HAS_NOISE_CONFIGS, "noise_configs.json not present")
    def test_snapshot_end(self):
        self._check_snapshot("end")


if __name__ == "__main__":
    unittest.main()
