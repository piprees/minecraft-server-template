#!/usr/bin/env python3
"""Tests for world-type rendering fidelity: mixed-source biome remapping,
island mask determinism, and cave render style selection.

Covers the three fidelity gaps fixed in the biome pipeline:
  1. Round-robin foreign biome remapping (build_mixed_entries)
  2. Island mask determinism (same seed -> same mask, different seed -> different)
  3. Cave render style selection by type

Execute: python3 -B scripts/seed/test_world_type_fidelity.py
"""

import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

BIOME_PARAMS = SCRIPT_DIR / "biome_params.json"
HAS_BIOME_PARAMS = BIOME_PARAMS.exists()
SKIP_REASON = "biome_params.json not present (CI or first checkout)"


class TestBuildMixedEntries(unittest.TestCase):
    """Round-robin foreign biome remapping matches the mod's buildMixedSource."""

    def setUp(self):
        from biome_source_mixing import build_mixed_entries
        self.build_mixed_entries = build_mixed_entries

    def _make_table(self, biomes, family="overworld"):
        """Create a minimal biome parameter table with one entry per biome."""
        entries = []
        for i, biome in enumerate(biomes):
            entries.append({
                "biome": biome,
                "family": family,
                "temperature": [-1.0, 1.0],
                "humidity": [-1.0, 1.0],
                "continentalness": [i * 0.1, i * 0.1 + 0.05],
                "erosion": [-1.0, 1.0],
                "depth": [0.0, 0.0],
                "weirdness": [-1.0, 1.0],
                "offset": 0.0,
            })
        return entries

    def test_all_native_no_foreign_drops_unlisted(self):
        table = self._make_table(["a", "b", "c", "d"])
        result = self.build_mixed_entries(table, ["a", "b"])
        biome_ids = [e["biome"] for e in result]
        self.assertEqual(sorted(biome_ids), ["a", "b"])

    def test_foreign_biome_takes_over_pool_regions(self):
        table = self._make_table(["a", "b", "c", "d"])
        result = self.build_mixed_entries(table, ["a", "foreign_x"])
        biome_ids = [e["biome"] for e in result]
        self.assertIn("a", biome_ids)
        self.assertIn("foreign_x", biome_ids)
        foreign_count = biome_ids.count("foreign_x")
        self.assertEqual(foreign_count, 3,
                         "foreign_x should claim all 3 pool regions (b, c, d)")

    def test_round_robin_multiple_foreign(self):
        table = self._make_table(["a", "b", "c", "d", "e", "f"])
        result = self.build_mixed_entries(table, ["a", "x", "y"])
        biome_ids = [e["biome"] for e in result]
        self.assertIn("a", biome_ids)
        x_count = biome_ids.count("x")
        y_count = biome_ids.count("y")
        self.assertGreater(x_count, 0, "foreign x should get pool regions")
        self.assertGreater(y_count, 0, "foreign y should get pool regions")
        self.assertEqual(x_count + y_count, 5,
                         "5 pool regions (b-f) should all be assigned")
        self.assertIn(abs(x_count - y_count), (0, 1),
                      "round-robin should distribute roughly evenly")

    def test_no_foreign_biomes_drops_pool_entirely(self):
        table = self._make_table(["a", "b", "c", "d"])
        result = self.build_mixed_entries(table, ["a", "b", "c"])
        biome_ids = [e["biome"] for e in result]
        self.assertNotIn("d", biome_ids)
        self.assertEqual(len(result), 3)

    def test_empty_biome_list_returns_empty(self):
        table = self._make_table(["a", "b"])
        result = self.build_mixed_entries(table, [])
        self.assertEqual(len(result), 0)

    def test_family_filter_restricts_base(self):
        table = self._make_table(["a", "b"], family="overworld")
        table += self._make_table(["c", "d"], family="nether")
        result = self.build_mixed_entries(table, ["a", "c"],
                                          family_filter="overworld")
        biome_ids = [e["biome"] for e in result]
        self.assertIn("a", biome_ids)
        self.assertIn("c", biome_ids)
        # Only overworld entries (a, b) form the base. "a" is native, "b"
        # goes to pool. "c" is foreign (nether family excluded from base).
        # Round-robin assigns b's single pool region to c.
        c_count = biome_ids.count("c")
        self.assertEqual(c_count, 1,
                         "c is foreign, gets b's single pool region")

    def test_preserves_climate_parameters(self):
        table = self._make_table(["a", "b", "c"])
        result = self.build_mixed_entries(table, ["a", "foreign"])
        for entry in result:
            self.assertIn("temperature", entry)
            self.assertIn("continentalness", entry)
            self.assertIn("biome", entry)

    def test_order_determinism(self):
        table = self._make_table(["a", "b", "c", "d", "e"])
        r1 = self.build_mixed_entries(table, ["a", "x", "y"])
        r2 = self.build_mixed_entries(table, ["a", "x", "y"])
        self.assertEqual([e["biome"] for e in r1], [e["biome"] for e in r2])


@unittest.skipUnless(HAS_BIOME_PARAMS, SKIP_REASON)
class TestMixedSourceIntegration(unittest.TestCase):
    """Integration: BiomeSampler with the mixed-source helper."""

    @classmethod
    def setUpClass(cls):
        from biome_sampler import BiomeSampler, load_noise_configs
        cls.BiomeSampler = BiomeSampler
        cls.load_noise_configs = staticmethod(load_noise_configs)

    def test_wisteria_foreign_biome_appears_in_samples(self):
        """the_wuthering_wisteria has paradise_lost:wisteria_woods as a
        foreign biome on overworld noise. It should claim substantial area."""
        configs = self.load_noise_configs()
        biome_list = [
            "paradise_lost:wisteria_woods",
            "minecraft:cherry_grove",
            "terralith:sakura_grove",
            "minecraft:meadow",
            "terralith:lavender_valley",
            "terralith:blooming_valley",
            "minecraft:flower_forest",
            "terralith:moonlight_valley",
            "terralith:lush_valley",
        ]
        sampler = self.BiomeSampler(
            5004021960434439426, str(BIOME_PARAMS),
            noise_config=configs.get("overworld"), family="overworld",
            biome_filter=biome_list)

        biome_counts = {}
        for x in range(-256, 257, 32):
            for z in range(-256, 257, 32):
                biome = sampler.biome_at(x, z)
                biome_counts[biome] = biome_counts.get(biome, 0) + 1

        self.assertIn("paradise_lost:wisteria_woods", biome_counts,
                       "wisteria_woods should appear in sampled biomes")
        wisteria_count = biome_counts.get("paradise_lost:wisteria_woods", 0)
        total = sum(biome_counts.values())
        wisteria_frac = wisteria_count / total
        self.assertGreater(wisteria_frac, 0.05,
                           f"wisteria_woods should claim >5% of area, got {wisteria_frac:.1%}")

    def test_all_native_list_only_produces_listed_biomes(self):
        """A biome list with only native overworld biomes should produce
        only those biomes in the output."""
        configs = self.load_noise_configs()
        biome_list = [
            "minecraft:plains",
            "minecraft:forest",
            "minecraft:birch_forest",
        ]
        sampler = self.BiomeSampler(
            42, str(BIOME_PARAMS),
            noise_config=configs.get("overworld"), family="overworld",
            biome_filter=biome_list)

        biomes_seen = set()
        for x in range(-512, 513, 64):
            for z in range(-512, 513, 64):
                biomes_seen.add(sampler.biome_at(x, z))

        self.assertTrue(biomes_seen.issubset(set(biome_list)),
                        f"Only listed biomes should appear, but got: "
                        f"{biomes_seen - set(biome_list)}")


@unittest.skipUnless(HAS_BIOME_PARAMS, SKIP_REASON)
class TestIslandMaskDeterminism(unittest.TestCase):
    """Island mask must be deterministic: same seed = same mask."""

    def test_same_seed_same_mask(self):
        from biome_renderer import _island_noise
        seed = 12345
        results1 = [_island_noise(seed, x * 100, z * 100)
                     for x in range(-5, 6) for z in range(-5, 6)]
        results2 = [_island_noise(seed, x * 100, z * 100)
                     for x in range(-5, 6) for z in range(-5, 6)]
        self.assertEqual(results1, results2,
                         "Same seed must produce identical island noise values")

    def test_different_seed_different_mask(self):
        from biome_renderer import _island_noise
        results_a = [_island_noise(111, x * 100, z * 100)
                      for x in range(-5, 6) for z in range(-5, 6)]
        results_b = [_island_noise(222, x * 100, z * 100)
                      for x in range(-5, 6) for z in range(-5, 6)]
        self.assertNotEqual(results_a, results_b,
                            "Different seeds should produce different island noise")

    def test_island_coverage_range(self):
        from biome_renderer import _island_noise
        threshold = 0.58
        land = 0
        total = 0
        for x in range(-50, 51, 2):
            for z in range(-50, 51, 2):
                val = _island_noise(42, x * 50, z * 50)
                if val > threshold:
                    land += 1
                total += 1
        coverage = land / total
        self.assertGreater(coverage, 0.10,
                           f"Island coverage {coverage:.1%} too low (expected >10%)")
        self.assertLess(coverage, 0.60,
                        f"Island coverage {coverage:.1%} too high (expected <60%)")


@unittest.skipUnless(HAS_BIOME_PARAMS, SKIP_REASON)
class TestCaveRenderStyle(unittest.TestCase):
    """Cave-type dimensions must render with the cave style."""

    def test_cave_render_is_dark(self):
        """Cave renders should be predominantly dark (deep greys)."""
        from biome_renderer import render_biome_map
        from biome_sampler import load_noise_configs
        import struct
        import zlib

        configs = load_noise_configs()
        noise_config = configs.get("overworld")
        with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as f:
            path = f.name
        try:
            render_biome_map(
                42, str(BIOME_PARAMS), path,
                noise_config=noise_config, family="overworld",
                dim_type="cave",
                size=64, blocks_per_pixel=16, sample_resolution=32)
            data = Path(path).read_bytes()
        finally:
            os.unlink(path)

        self.assertTrue(data[:8] == b"\x89PNG\r\n\x1a\n")

        raw = data[8:]
        pos = 0
        idat_data = b""
        width = height = 0
        while pos < len(raw):
            length = struct.unpack(">I", raw[pos:pos + 4])[0]
            tag = raw[pos + 4:pos + 8]
            chunk_data = raw[pos + 8:pos + 8 + length]
            if tag == b"IHDR":
                width, height = struct.unpack(">II", chunk_data[:8])
            elif tag == b"IDAT":
                idat_data += chunk_data
            pos += 12 + length

        decompressed = zlib.decompress(idat_data)
        dark_count = 0
        total = 0
        row_len = 1 + width * 3
        for y in range(height):
            row_start = y * row_len + 1
            for x in range(width):
                off = row_start + x * 3
                r = decompressed[off]
                g = decompressed[off + 1]
                b = decompressed[off + 2]
                brightness = (r + g + b) / 3.0
                if brightness < 80:
                    dark_count += 1
                total += 1

        dark_frac = dark_count / total
        self.assertGreater(dark_frac, 0.70,
                           f"Cave render should be >70% dark pixels, got {dark_frac:.1%}")

    def test_cave_differs_from_overworld(self):
        """Cave render must look different from an overworld render of the same seed."""
        from biome_renderer import render_biome_map
        from biome_sampler import load_noise_configs

        configs = load_noise_configs()
        noise_config = configs.get("overworld")
        renders = {}
        for dtype in (None, "cave"):
            with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as f:
                path = f.name
            try:
                render_biome_map(
                    42, str(BIOME_PARAMS), path,
                    noise_config=noise_config, family="overworld",
                    dim_type=dtype,
                    size=64, blocks_per_pixel=16, sample_resolution=32)
                renders[dtype] = Path(path).read_bytes()
            finally:
                os.unlink(path)

        self.assertNotEqual(renders[None], renders["cave"],
                            "Cave render must differ from overworld render")


if __name__ == "__main__":
    unittest.main()
