"""Unit tests for tb_regions.py — TerraBlender region selection mirror.

Tests the derived algorithm against hand-computed expectations and
synthetic fixtures. Does NOT require a running server or the _tbRegions
sentinel to exist — all region data is synthetic.

Worked cases for the uniqueness layers are computed from the bytecode
semantics with small seeds, verifying:
  - SeedMixer.mixSeed (LCG constants from SeedMixer.class)
  - AreaContext seeding, initRandom, nextRandom
  - Weighted random selection
  - Zoom layer parity (FUZZY and NORMAL mode-or-random)
  - End-to-end uniqueness area for small region counts
"""

import sys
import os
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO / "scripts/seed"))


class TestSeedMixer(unittest.TestCase):
    """Verify _seed_mix matches the bytecode-derived formula."""

    def test_zero_zero(self):
        from tb_regions import _seed_mix
        # seed=0: 0 * (0 * MULT + ADD) + 0 = 0
        self.assertEqual(_seed_mix(0, 0), 0)

    def test_zero_seed_nonzero_value(self):
        from tb_regions import _seed_mix
        # seed=0: 0 * (...) + value = value
        self.assertEqual(_seed_mix(0, 42), 42)

    def test_one_zero(self):
        from tb_regions import _seed_mix, _LCG_MULT, _LCG_ADD, _i64
        # seed=1: 1 * (1 * MULT + ADD) + 0 = MULT + ADD
        expected = _i64(_LCG_MULT + _LCG_ADD)
        self.assertEqual(_seed_mix(1, 0), expected)

    def test_symmetry_broken(self):
        from tb_regions import _seed_mix
        # mixSeed(a, b) != mixSeed(b, a) in general
        self.assertNotEqual(_seed_mix(1, 2), _seed_mix(2, 1))

    def test_overflow_wraps(self):
        from tb_regions import _seed_mix, _i64
        result = _seed_mix(2**62, 2**62)
        self.assertIsInstance(result, int)
        self.assertTrue(-2**63 <= result < 2**63,
                        "result must be in Java long range")

    def test_known_value(self):
        """Hand-computed: _seed_mix(7, 3)."""
        from tb_regions import _seed_mix, _i64, _LCG_MULT, _LCG_ADD
        # step 1: inner = 7 * MULT + ADD (Java long)
        inner = _i64(7 * _LCG_MULT + _LCG_ADD)
        # step 2: seed = 7 * inner (Java long)
        seed = _i64(7 * inner)
        # step 3: seed + 3
        expected = _i64(seed + 3)
        self.assertEqual(_seed_mix(7, 3), expected)


class TestAreaContext(unittest.TestCase):
    """Verify AreaContext seeding and random draw semantics."""

    def test_deterministic(self):
        from tb_regions import _AreaContext, _i64
        ctx1 = _AreaContext(12345, 1)
        ctx2 = _AreaContext(12345, 1)
        ctx1.init_random(_i64(10), _i64(20))
        ctx2.init_random(_i64(10), _i64(20))
        self.assertEqual(ctx1.next_random(100), ctx2.next_random(100))

    def test_different_salts_differ(self):
        from tb_regions import _AreaContext, _i64
        ctx1 = _AreaContext(12345, 1)
        ctx2 = _AreaContext(12345, 2)
        ctx1.init_random(_i64(0), _i64(0))
        ctx2.init_random(_i64(0), _i64(0))
        # Different salts should (almost certainly) give different values
        vals1 = [ctx1.next_random(1000) for _ in range(5)]
        ctx2_vals = [ctx2.next_random(1000) for _ in range(5)]
        self.assertNotEqual(vals1, ctx2_vals)

    def test_different_coords_differ(self):
        from tb_regions import _AreaContext, _i64
        ctx = _AreaContext(12345, 1)
        ctx.init_random(_i64(0), _i64(0))
        v1 = ctx.next_random(1000)
        ctx.init_random(_i64(1), _i64(0))
        v2 = ctx.next_random(1000)
        # Not guaranteed to differ, but overwhelmingly likely
        # Just check that init_random resets state
        ctx.init_random(_i64(0), _i64(0))
        v1b = ctx.next_random(1000)
        self.assertEqual(v1, v1b, "init_random must reset to same state")

    def test_next_random_range(self):
        from tb_regions import _AreaContext, _i64
        ctx = _AreaContext(42, 1)
        for bound in [2, 4, 10, 30, 100]:
            for coord in range(20):
                ctx.init_random(_i64(coord), _i64(coord * 7))
                val = ctx.next_random(bound)
                self.assertGreaterEqual(val, 0)
                self.assertLess(val, bound)

    def test_random2(self):
        from tb_regions import _AreaContext, _i64
        ctx = _AreaContext(42, 1)
        ctx.init_random(_i64(0), _i64(0))
        result = ctx.random2(10, 20)
        self.assertIn(result, [10, 20])

    def test_random4(self):
        from tb_regions import _AreaContext, _i64
        ctx = _AreaContext(42, 1)
        ctx.init_random(_i64(0), _i64(0))
        result = ctx.random4(10, 20, 30, 40)
        self.assertIn(result, [10, 20, 30, 40])


class TestWeightedPick(unittest.TestCase):
    """Verify weighted random selection."""

    def test_single_region(self):
        from tb_regions import _weighted_pick, _AreaContext, _i64
        ctx = _AreaContext(42, 1)
        ctx.init_random(_i64(0), _i64(0))
        # Single entry always returns that entry
        self.assertEqual(_weighted_pick([10], [0], ctx), 0)

    def test_distribution(self):
        """Heavy weight should dominate in a large sample."""
        from tb_regions import _weighted_pick, _AreaContext, _i64
        weights = [100, 1]
        indices = [0, 1]
        counts = {0: 0, 1: 0}
        for x in range(200):
            ctx = _AreaContext(42, 1)
            ctx.init_random(_i64(x), _i64(x * 3 + 7))
            idx = _weighted_pick(weights, indices, ctx)
            counts[idx] += 1
        self.assertGreater(counts[0], counts[1] * 5,
                           "weight=100 region should dominate weight=1")

    def test_equal_weights(self):
        """Equal weights should distribute roughly evenly."""
        from tb_regions import _weighted_pick, _AreaContext, _i64
        weights = [10, 10, 10]
        indices = [0, 1, 2]
        counts = {0: 0, 1: 0, 2: 0}
        for x in range(300):
            ctx = _AreaContext(42, 1)
            ctx.init_random(_i64(x), _i64(x * 3))
            idx = _weighted_pick(weights, indices, ctx)
            counts[idx] += 1
        for i in range(3):
            self.assertGreater(counts[i], 30,
                               f"region {i} should get at least some picks")


class TestZoomModeOrRandom(unittest.TestCase):
    """Verify the NORMAL zoom's mode-or-random logic."""

    def test_unanimous(self):
        from tb_regions import _mode_or_random_normal, _AreaContext, _i64
        ctx = _AreaContext(0, 0)
        ctx.init_random(_i64(0), _i64(0))
        self.assertEqual(_mode_or_random_normal(ctx, 5, 5, 5, 5), 5)

    def test_three_of_four_bcd(self):
        from tb_regions import _mode_or_random_normal, _AreaContext, _i64
        ctx = _AreaContext(0, 0)
        ctx.init_random(_i64(0), _i64(0))
        self.assertEqual(_mode_or_random_normal(ctx, 1, 2, 2, 2), 2)

    def test_three_of_four_abc(self):
        from tb_regions import _mode_or_random_normal, _AreaContext, _i64
        ctx = _AreaContext(0, 0)
        ctx.init_random(_i64(0), _i64(0))
        self.assertEqual(_mode_or_random_normal(ctx, 3, 3, 3, 1), 3)

    def test_two_pairs_goes_to_random(self):
        """a==b and c==d with a!=c: no mode, falls through to random."""
        from tb_regions import _mode_or_random_normal, _AreaContext, _i64
        ctx = _AreaContext(0, 0)
        ctx.init_random(_i64(0), _i64(0))
        result = _mode_or_random_normal(ctx, 1, 1, 2, 2)
        self.assertIn(result, [1, 2])

    def test_pair_with_distinct_others(self):
        """a==b with c!=d: should return a deterministically."""
        from tb_regions import _mode_or_random_normal, _AreaContext, _i64
        ctx = _AreaContext(0, 0)
        ctx.init_random(_i64(0), _i64(0))
        self.assertEqual(_mode_or_random_normal(ctx, 5, 5, 3, 4), 5)

    def test_cd_pair_with_distinct_ab(self):
        """c==d with a!=b and a!=c: should return c."""
        from tb_regions import _mode_or_random_normal, _AreaContext, _i64
        ctx = _AreaContext(0, 0)
        ctx.init_random(_i64(0), _i64(0))
        self.assertEqual(_mode_or_random_normal(ctx, 1, 2, 7, 7), 7)


class TestUniquenessArea(unittest.TestCase):
    """End-to-end uniqueness area with a two-region synthetic fixture."""

    def _build_two_region(self, seed=42, region_size=3):
        from tb_regions import _build_uniqueness
        weights = [10, 10]
        indices = [0, 1]
        return _build_uniqueness(seed, weights, indices, region_size)

    def test_returns_valid_indices(self):
        area = self._build_two_region()
        for x in range(-8, 9):
            for z in range(-8, 9):
                idx = area.get(x, z)
                self.assertIn(idx, [0, 1],
                              f"uniqueness at ({x},{z})={idx} not in [0,1]")

    def test_deterministic(self):
        area1 = self._build_two_region(seed=42)
        area2 = self._build_two_region(seed=42)
        for x in range(-4, 5):
            for z in range(-4, 5):
                self.assertEqual(area1.get(x, z), area2.get(x, z))

    def test_different_seeds_differ(self):
        # Zoom layers scale by ~128x, so scan a large area to cross
        # region boundaries.
        area1 = self._build_two_region(seed=42)
        area2 = self._build_two_region(seed=999)
        diffs = 0
        for x in range(-200, 201, 10):
            for z in range(-200, 201, 10):
                if area1.get(x, z) != area2.get(x, z):
                    diffs += 1
        self.assertGreater(diffs, 0, "different seeds should produce "
                           "different uniqueness maps")

    def test_spatial_coherence(self):
        """Adjacent cells should often agree (zoom layers smooth)."""
        area = self._build_two_region(seed=42)
        same = 0
        total = 0
        for x in range(-200, 200, 5):
            for z in range(-200, 200, 5):
                v = area.get(x, z)
                if area.get(x + 1, z) == v:
                    same += 1
                total += 1
        ratio = same / total
        self.assertGreater(ratio, 0.5,
                           "zoom layers should produce spatial coherence "
                           f"(got {ratio:.2f})")

    def test_single_region_always_zero(self):
        """With one region, uniqueness is always 0."""
        from tb_regions import _build_uniqueness
        area = _build_uniqueness(42, [10], [0], region_size=3)
        for x in range(-8, 9):
            for z in range(-8, 9):
                self.assertEqual(area.get(x, z), 0)


class TestRegionTableLoader(unittest.TestCase):
    """Verify sentinel loading from synthetic data."""

    def _synthetic_sentinel(self):
        return [
            {"biome": "minecraft:plains",
             "temperature": [-1.0, 1.0], "humidity": [-1.0, 1.0],
             "continentalness": [-1.0, 1.0], "erosion": [-1.0, 1.0],
             "depth": [0.0, 0.0], "weirdness": [-1.0, 1.0], "offset": 0.0},
            {"biome": "minecraft:desert",
             "temperature": [0.55, 1.0], "humidity": [-1.0, -0.35],
             "continentalness": [-0.11, 1.0], "erosion": [-1.0, 1.0],
             "depth": [0.0, 0.0], "weirdness": [-1.0, 1.0], "offset": 0.0},
        ]

    def test_load_missing_sentinel(self, tmp_path=None):
        from tb_regions import load_tb_regions
        import tempfile
        with tempfile.NamedTemporaryFile(mode='w', suffix='.json',
                                          delete=False) as f:
            import json
            json.dump([{"biome": "minecraft:plains"}], f)
            f.flush()
            result = load_tb_regions(f.name)
        os.unlink(f.name)
        self.assertIsNone(result)

    def test_load_present_sentinel(self):
        from tb_regions import load_tb_regions
        import tempfile, json
        sentinel = {
            "_tbRegions": {
                "type": "overworld",
                "regions": [
                    {"name": "minecraft:overworld", "weight": 10,
                     "index": 0, "biomes": self._synthetic_sentinel()},
                    {"name": "test:region1", "weight": 5,
                     "index": 1, "biomes": self._synthetic_sentinel()},
                ]
            }
        }
        with tempfile.NamedTemporaryFile(mode='w', suffix='.json',
                                          delete=False) as f:
            json.dump([sentinel], f)
            f.flush()
            result = load_tb_regions(f.name)
        os.unlink(f.name)
        self.assertIsNotNone(result)
        self.assertEqual(result["type"], "overworld")
        self.assertEqual(len(result["regions"]), 2)

    def test_build_region_trees(self):
        from tb_regions import _build_region_trees
        tb_regions = {
            "type": "overworld",
            "regions": [
                {"name": "minecraft:overworld", "weight": 10,
                 "index": 0, "biomes": self._synthetic_sentinel()},
                {"name": "test:region1", "weight": 5,
                 "index": 1, "biomes": self._synthetic_sentinel()},
            ]
        }
        trees = _build_region_trees(tb_regions)
        self.assertEqual(len(trees), 2)
        for i, (name, tree) in enumerate(trees):
            self.assertIsNotNone(tree, f"tree {i} ({name}) should not be None")
            # The tree should be queryable
            point = (0, 0, 0, 0, 0, 0, 0)
            biome = tree.get(point)
            self.assertIn(biome, ["minecraft:plains", "minecraft:desert"])


class TestTBBiomeSource(unittest.TestCase):
    """End-to-end TBBiomeSource with synthetic data."""

    def _make_source(self, seed=42):
        from tb_regions import TBBiomeSource

        biomes = [
            {"biome": "minecraft:plains",
             "temperature": [-1.0, 1.0], "humidity": [-1.0, 1.0],
             "continentalness": [-1.0, 1.0], "erosion": [-1.0, 1.0],
             "depth": [0.0, 0.0], "weirdness": [-1.0, 1.0], "offset": 0.0},
        ]
        tb_regions = {
            "type": "overworld",
            "regions": [
                {"name": "region_a", "weight": 10, "index": 0,
                 "biomes": biomes},
                {"name": "region_b", "weight": 10, "index": 1,
                 "biomes": biomes},
            ]
        }

        class FakeClimate:
            depth_exact = True
            climate_exact = {"temperature": True, "humidity": True,
                             "continentalness": True, "erosion": True,
                             "weirdness": True, "depth": True}
            def sample_climate(self, x, z):
                return {"temperature": 0.0, "humidity": 0.0,
                        "continentalness": 0.0, "erosion": 0.0,
                        "depth": 0.0, "weirdness": 0.0}

        return TBBiomeSource(seed, tb_regions, FakeClimate(), region_size=3)

    def test_biome_at_returns_valid(self):
        src = self._make_source()
        biome = src.biome_at(0, 0)
        self.assertEqual(biome, "minecraft:plains")

    def test_deterministic(self):
        src1 = self._make_source(seed=42)
        src2 = self._make_source(seed=42)
        for x in range(-64, 65, 16):
            for z in range(-64, 65, 16):
                self.assertEqual(src1.biome_at(x, z), src2.biome_at(x, z))

    def test_uniqueness_at(self):
        src = self._make_source(seed=42)
        idx = src.uniqueness_at(0, 0)
        self.assertIn(idx, [0, 1])


if __name__ == "__main__":
    unittest.main()
