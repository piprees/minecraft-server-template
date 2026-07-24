#!/usr/bin/env python3
"""Regression tests for preset_terrain.py (exact preset terrain heights).

The live exactness oracle (Python depth vs `customdim sample-noise` depth on
a real server) can't run in CI — these tests pin everything that can break
silently: determinism, seed/preset sensitivity, graph closure (every node
type in the depth graph supported), and physical bounds.
"""

import unittest

from preset_terrain import PresetTerrainEvaluator, supported_presets

SEED = 987654321
GRID = [(x, z) for x in (-8192, -256, 0, 1000, 65536)
        for z in (-4096, 0, 512, 123456)]


class TestPresetTerrain(unittest.TestCase):

    def test_both_presets_supported(self):
        self.assertEqual(supported_presets(),
                         ["adventure:compressed", "adventure:wide"])

    def test_unknown_preset_rejected(self):
        with self.assertRaises(ValueError):
            PresetTerrainEvaluator("adventure:nope", SEED)

    def test_deterministic_across_instances(self):
        a = PresetTerrainEvaluator("adventure:wide", SEED)
        b = PresetTerrainEvaluator("adventure:wide", SEED)
        for x, z in GRID:
            self.assertEqual(a.depth(x, z), b.depth(x, z), (x, z))

    def test_seed_changes_terrain(self):
        a = PresetTerrainEvaluator("adventure:wide", SEED)
        b = PresetTerrainEvaluator("adventure:wide", SEED + 1)
        diffs = sum(1 for x, z in GRID if a.depth(x, z) != b.depth(x, z))
        self.assertGreater(diffs, len(GRID) // 2)

    def test_presets_differ(self):
        a = PresetTerrainEvaluator("adventure:wide", SEED)
        b = PresetTerrainEvaluator("adventure:compressed", SEED)
        diffs = sum(1 for x, z in GRID if a.depth(x, z) != b.depth(x, z))
        self.assertGreater(diffs, len(GRID) // 2)

    def test_graph_closure_and_bounds(self):
        # Evaluating a wide spread exercises every node type in the depth
        # graph — an unsupported type or dangling ref raises ValueError.
        for preset in supported_presets():
            ev = PresetTerrainEvaluator(preset, SEED)
            for x, z in GRID:
                h = ev.surface_height(x, z)
                self.assertGreaterEqual(h, -64, (preset, x, z))
                self.assertLessEqual(h, 512, (preset, x, z))

    def test_gradient_formula(self):
        # depth is linear in y through the terratonic gradient:
        # depth(y) = depth(0) - y/128 wherever no other y-dependence exists.
        ev = PresetTerrainEvaluator("adventure:wide", SEED)
        d0 = ev.depth(1000, 1000, y=0)
        d64 = ev.depth(1000, 1000, y=64)
        self.assertAlmostEqual(d0 - d64, 0.5, places=6)


if __name__ == "__main__":
    unittest.main()
