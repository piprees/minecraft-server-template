#!/usr/bin/env python3
"""test_depth_axis.py — depth climate parameter: exact evaluation and tracking.

Tests the §6.2.1 depth axis work: the PresetTerrainEvaluator's biome-y depth
method, the BiomeSampler's depth_evaluator wiring, the depth_exact flag per
family, and regression of existing overworld/adventure-preset behaviour.

The oracle y convention is quart y=16 (DimensionCommands.java line 942:
biomeSource.getBiome(qx, 16, qz, sampler)), which is BiomeCoords.fromBlock(64)
= sea level. Density functions receive this as raw coordinate 16.
"""

import json
import tempfile
import unittest
from pathlib import Path

from preset_terrain import PresetTerrainEvaluator, supported_presets

SCRIPT_DIR = Path(__file__).resolve().parent
BIOME_PARAMS = SCRIPT_DIR / "biome_params.json"
HAS_PARAMS = BIOME_PARAMS.exists()
SKIP_NO_PARAMS = "biome_params.json not present (CI or first checkout)"

SEED = 987654321
# A grid exercising both positive and negative coordinates
GRID = [(x, z) for x in (-4096, -256, 0, 1000, 8192)
        for z in (-2048, 0, 512, 4096)]


class TestPresetTerrainBiomeDepth(unittest.TestCase):
    """PresetTerrainEvaluator.depth_for_biome: exact depth at quart y=16."""

    def test_biome_depth_differs_from_y0(self):
        """The adventure:wide tectonic depth graph has a y_clamped_gradient
        from -2048 to 2048. At y=0 the gradient contributes 1.0; at block
        y=64 (quart y=16, the biome-sampling y) it contributes 0.5 — a 0.5
        difference. The density function evaluator operates in block space:
        QuartPos.toBlock(16) = 64."""
        ev = PresetTerrainEvaluator("adventure:wide", SEED)
        for x, z in GRID[:4]:
            d0 = ev.depth(x, z, y=0)
            d64 = ev.depth_for_biome(x, z)
            # The y_clamped_gradient(-2048, 2048, 17, -15) shifts by
            # (64/4096)*32 = 0.5 between y=0 and block y=64.
            self.assertAlmostEqual(d0 - d64, 0.5, places=10,
                                   msg=f"depth shift at ({x},{z})")

    def test_biome_depth_deterministic(self):
        a = PresetTerrainEvaluator("adventure:wide", SEED)
        b = PresetTerrainEvaluator("adventure:wide", SEED)
        for x, z in GRID:
            self.assertEqual(a.depth_for_biome(x, z),
                             b.depth_for_biome(x, z), (x, z))

    def test_biome_depth_seed_sensitive(self):
        a = PresetTerrainEvaluator("adventure:wide", SEED)
        b = PresetTerrainEvaluator("adventure:wide", SEED + 1)
        diffs = sum(1 for x, z in GRID
                    if a.depth_for_biome(x, z) != b.depth_for_biome(x, z))
        self.assertGreater(diffs, len(GRID) // 2)

    def test_both_presets_supported(self):
        for preset in supported_presets():
            ev = PresetTerrainEvaluator(preset, SEED)
            for x, z in GRID[:4]:
                d = ev.depth_for_biome(x, z)
                # Depth values for overworld-like dimensions are typically
                # in [-2, 3] — bounds from the graph structure.
                self.assertGreater(d, -3.0, (preset, x, z))
                self.assertLess(d, 4.0, (preset, x, z))

    def test_biome_y_constant_is_64(self):
        """Block Y = QuartPos.toBlock(16) = 64."""
        self.assertEqual(PresetTerrainEvaluator.BIOME_SAMPLE_BLOCK_Y, 64)

    def test_depth_at_biome_y_vs_explicit_y(self):
        """depth_for_biome(x, z) must equal depth(x, z, y=64)."""
        ev = PresetTerrainEvaluator("adventure:compressed", SEED)
        for x, z in GRID:
            self.assertEqual(ev.depth_for_biome(x, z),
                             ev.depth(x, z, y=64), (x, z))

    def test_y0_regression_unchanged(self):
        """Existing depth(y=0) behaviour must not change."""
        ev = PresetTerrainEvaluator("adventure:wide", SEED)
        d0 = ev.depth(1000, 1000, y=0)
        d64 = ev.depth(1000, 1000, y=64)
        self.assertAlmostEqual(d0 - d64, 0.5, places=6)


class TestBiomeSamplerDepthExact(unittest.TestCase):
    """BiomeSampler.depth_exact flag and depth_evaluator wiring."""

    @unittest.skipUnless(HAS_PARAMS, SKIP_NO_PARAMS)
    def test_default_sampler_depth_not_exact(self):
        from biome_sampler import BiomeSampler
        s = BiomeSampler(SEED, str(BIOME_PARAMS))
        self.assertFalse(s.depth_exact)

    @unittest.skipUnless(HAS_PARAMS, SKIP_NO_PARAMS)
    def test_sampler_with_evaluator_depth_exact(self):
        from biome_sampler import BiomeSampler
        s = BiomeSampler(SEED, str(BIOME_PARAMS),
                         depth_evaluator=lambda x, z: 0.5)
        self.assertTrue(s.depth_exact)

    @unittest.skipUnless(HAS_PARAMS, SKIP_NO_PARAMS)
    def test_depth_evaluator_called_in_sample_climate(self):
        from biome_sampler import BiomeSampler
        calls = []

        def mock_depth(x, z):
            calls.append((x, z))
            return 0.42

        s = BiomeSampler(SEED, str(BIOME_PARAMS), depth_evaluator=mock_depth)
        climate = s.sample_climate(256, 512)
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0], (256, 512))
        self.assertAlmostEqual(climate["depth"], 0.42)

    @unittest.skipUnless(HAS_PARAMS, SKIP_NO_PARAMS)
    def test_default_depth_is_zero(self):
        from biome_sampler import BiomeSampler
        s = BiomeSampler(SEED, str(BIOME_PARAMS))
        climate = s.sample_climate(0, 0)
        self.assertEqual(climate["depth"], 0.0)


@unittest.skipUnless(HAS_PARAMS, SKIP_NO_PARAMS)
class TestBuildFromSpecDepth(unittest.TestCase):
    """build_from_spec wires depth correctly per family/noise_settings."""

    @classmethod
    def setUpClass(cls):
        from biome_sampler import sampler_spec, build_from_spec, load_noise_configs
        cls.sampler_spec = staticmethod(sampler_spec)
        cls.build_from_spec = staticmethod(build_from_spec)
        cls.noise_configs = load_noise_configs()
        cls.params = str(BIOME_PARAMS)

    def _spec(self, **overrides):
        """Build a minimal spec with overrides."""
        base = {
            "noise_family": "overworld",
            "dim_type": "multi_biome",
            "biomes": [],
            "parameters": {},
            "patches": [],
            "checkerboard_scale": None,
            "suppressed_biomes": [],
            "noise_settings": None,
        }
        base.update(overrides)
        return base

    def test_adventure_wide_depth_exact(self):
        spec = self._spec(noise_settings="adventure:wide")
        sampler = self.build_from_spec(SEED, spec, self.params, self.noise_configs)
        self.assertTrue(sampler.depth_exact)

    def test_adventure_compressed_depth_exact(self):
        spec = self._spec(noise_settings="adventure:compressed")
        sampler = self.build_from_spec(SEED, spec, self.params, self.noise_configs)
        self.assertTrue(sampler.depth_exact)

    def test_adventure_wide_depth_nonzero(self):
        """Adventure presets have non-trivial depth from the router graph."""
        spec = self._spec(noise_settings="adventure:wide")
        sampler = self.build_from_spec(SEED, spec, self.params, self.noise_configs)
        depths = [sampler.sample_climate(x, z)["depth"] for x, z in GRID[:8]]
        non_zero = [d for d in depths if abs(d) > 0.001]
        self.assertGreater(len(non_zero), 0,
                           "at least some depth values must be non-zero")

    def test_paradise_lost_depth_exact_and_zero(self):
        """Paradise Lost biome entries all have depth=(0,0), so depth=0.0 is
        provably correct — the biome source ignores depth."""
        spec = self._spec(noise_family="paradise_lost", noise_settings=None)
        sampler = self.build_from_spec(SEED, spec, self.params, self.noise_configs)
        self.assertTrue(sampler.depth_exact)
        climate = sampler.sample_climate(0, 0)
        self.assertEqual(climate["depth"], 0.0)

    def test_standard_overworld_depth_not_exact(self):
        """Standard overworld noise_settings is not in-repo."""
        spec = self._spec(noise_family="overworld", noise_settings=None)
        sampler = self.build_from_spec(SEED, spec, self.params, self.noise_configs)
        self.assertFalse(sampler.depth_exact)

    def test_nether_depth_not_exact(self):
        spec = self._spec(noise_family="nether", noise_settings=None)
        sampler = self.build_from_spec(SEED, spec, self.params, self.noise_configs)
        self.assertFalse(sampler.depth_exact)

    def test_end_depth_not_exact(self):
        spec = self._spec(noise_family="end", noise_settings=None)
        sampler = self.build_from_spec(SEED, spec, self.params, self.noise_configs)
        self.assertFalse(sampler.depth_exact)

    def test_checkerboard_depth_exact(self):
        """Checkerboard ignores climate entirely — depth is moot."""
        spec = self._spec(dim_type="checkerboard",
                          biomes=["minecraft:plains", "minecraft:desert"],
                          noise_settings=None)
        sampler = self.build_from_spec(SEED, spec, self.params, self.noise_configs)
        self.assertTrue(sampler.depth_exact)

    def test_adventure_wide_depth_matches_evaluator(self):
        """The depth from build_from_spec must match a standalone
        PresetTerrainEvaluator at quart y=16."""
        ev = PresetTerrainEvaluator("adventure:wide", SEED)
        spec = self._spec(noise_settings="adventure:wide")
        sampler = self.build_from_spec(SEED, spec, self.params, self.noise_configs)
        for x, z in GRID[:6]:
            expected = ev.depth_for_biome(x, z)
            actual = sampler.sample_climate(x, z)["depth"]
            self.assertAlmostEqual(actual, expected, places=10,
                                   msg=f"depth mismatch at ({x},{z})")


@unittest.skipUnless(HAS_PARAMS, SKIP_NO_PARAMS)
class TestDepthAxisRegressions(unittest.TestCase):
    """Existing overworld/adventure behaviour must not change for the
    other 5 climate parameters."""

    def test_other_climate_params_unchanged_with_depth_evaluator(self):
        """Adding a depth_evaluator must not affect temperature, humidity,
        continentalness, erosion, or weirdness."""
        from biome_sampler import BiomeSampler, load_noise_configs
        nc = load_noise_configs()["overworld"]
        without = BiomeSampler(SEED, str(BIOME_PARAMS), noise_config=nc)
        with_eval = BiomeSampler(SEED, str(BIOME_PARAMS), noise_config=nc,
                                 depth_evaluator=lambda x, z: 0.42)
        for x, z in GRID[:6]:
            c1 = without.sample_climate(x, z)
            c2 = with_eval.sample_climate(x, z)
            for p in ("temperature", "humidity", "continentalness",
                      "erosion", "weirdness"):
                self.assertEqual(c1[p], c2[p], f"{p} at ({x},{z})")

    def test_sampler_spec_includes_noise_settings(self):
        from biome_sampler import sampler_spec
        dim = {
            "type": "multi_biome",
            "family": "overworld",
            "create_args": {"biome": "", "noiseSettings": "adventure:wide"},
        }
        spec = sampler_spec(dim)
        self.assertEqual(spec["noise_settings"], "adventure:wide")

    def test_sampler_spec_noise_settings_none_when_absent(self):
        from biome_sampler import sampler_spec
        dim = {"type": "multi_biome", "family": "overworld"}
        spec = sampler_spec(dim)
        self.assertIsNone(spec["noise_settings"])

    def test_depth_ordering_sea_vs_deep(self):
        """At the same (x, z), depth at y=0 must exceed depth at block y=64
        for adventure presets — closer to the surface means shallower
        (lower depth value in the noise_router's convention)."""
        ev = PresetTerrainEvaluator("adventure:wide", SEED)
        for x, z in GRID[:4]:
            d0 = ev.depth(x, z, y=0)
            d64 = ev.depth_for_biome(x, z)
            self.assertGreater(d0, d64,
                               f"depth should decrease with y at ({x},{z})")


if __name__ == "__main__":
    unittest.main()
