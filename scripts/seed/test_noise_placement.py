#!/usr/bin/env python3
"""Tests for noise_placement.py (spike task F1).

These pin the Python side's own behaviour. The Java-vs-Python parity gate
(F4) lives in test_noise_parity.py, which diffs against real
`/customdim structure-census` output.
"""

import sys
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO / "scripts/seed"))

import noise_placement as npl  # noqa: E402

SEED = 0xC0FFEE

INNER = [1.5, 1.3, 1.0, 0.8, 0.5, 0.3, 0.1, 0.0, 0.0, 0.0]
OUTER = [0.0, 0.0, 0.1, 0.3, 0.6, 0.8, 1.0, 1.3, 1.5, 2.0]
EVEN = [1.0] * 10


def build(profile, exclusion, radial, radius):
    return npl.NoiseFieldIndex(SEED, profile, exclusion, radial, radius, 0, 0)


class TestNoise(unittest.TestCase):
    def test_deterministic(self):
        a = npl.StructureNoise(SEED)
        b = npl.StructureNoise(SEED)
        for cx in range(-20, 20):
            self.assertEqual(a.sample_chunk(cx, cx * 3, 0.025),
                             b.sample_chunk(cx, cx * 3, 0.025))

    def test_range(self):
        n = npl.StructureNoise(SEED)
        for i in range(-500, 500):
            v = n.sample_chunk(i, i * 7 + 3, 0.025)
            self.assertGreaterEqual(v, 0.0)
            self.assertLessEqual(v, 1.0)

    def test_no_chunk_is_seed_independent(self):
        """The lattice bug: Perlin is exactly 0 at a lattice point, which
        normalises to 0.5 for every seed. The irrational origin offsets stop
        any chunk coordinate landing on one."""
        seeds = [SEED, SEED * 31 + 7, 0, 1, -1, 1 << 40]
        for cx in range(-40, 120):
            for cz in range(-40, 120, 7):
                values = {npl.NATURAL.evaluate(s, cx, cz) for s in seeds}
                self.assertGreater(len(values), 1,
                                   "chunk (%d, %d) reads the same for every seed" % (cx, cz))

    def test_permutation_is_well_spread(self):
        n = npl.StructureNoise(SEED)
        values = [n.sample_chunk(i, i * 3 + 1, 0.025) for i in range(2000)]
        self.assertGreater(len(set(values)), 1700)
        self.assertLess(min(values), 0.25)
        self.assertGreater(max(values), 0.75)


class TestProfiles(unittest.TestCase):
    def test_constants_match_the_mod(self):
        self.assertEqual((npl.NATURAL.frequency, npl.NATURAL.threshold,
                          npl.NATURAL.exclusion_multiplier), (0.025, 0.68, 1.0))
        self.assertEqual((npl.DENSE.frequency, npl.DENSE.threshold,
                          npl.DENSE.exclusion_multiplier), (0.040, 0.45, 0.6))
        self.assertEqual((npl.SPARSE.frequency, npl.SPARSE.threshold,
                          npl.SPARSE.exclusion_multiplier), (0.015, 0.85, 1.5))
        self.assertEqual((npl.CLUSTER.coarse_frequency, npl.CLUSTER.frequency,
                          npl.CLUSTER.coarse_threshold, npl.CLUSTER.threshold,
                          npl.CLUSTER.exclusion_multiplier),
                         (0.008, 0.05, 0.90, 0.40, 0.4))

    def test_from_string(self):
        self.assertIs(npl.profile_from_string("natural"), npl.NATURAL)
        self.assertIs(npl.profile_from_string("SPARSE"), npl.SPARSE)
        self.assertIsNone(npl.profile_from_string("none"))
        self.assertIsNone(npl.profile_from_string(None))
        self.assertIsNone(npl.profile_from_string(""))
        self.assertIsNone(npl.profile_from_string("garbage"))

    def test_frequency_scale(self):
        self.assertEqual(npl.frequency_scale(512), 1.0)
        self.assertEqual(npl.frequency_scale(64), 8.0)
        self.assertEqual(npl.frequency_scale(0), 1.0)


class TestFieldIndex(unittest.TestCase):
    def test_determinism(self):
        self.assertEqual(build(npl.NATURAL, 3, EVEN, 64).positions(),
                         build(npl.NATURAL, 3, EVEN, 64).positions())

    def test_different_seeds_differ(self):
        a = npl.NoiseFieldIndex(SEED, npl.NATURAL, 3, EVEN, 64, 0, 0)
        b = npl.NoiseFieldIndex(SEED + 1, npl.NATURAL, 3, EVEN, 64, 0, 0)
        self.assertNotEqual(a.positions(), b.positions())

    def test_exclusion_is_enforced(self):
        for exclusion in (1, 3, 6, 12, 20):
            index = build(npl.NATURAL, exclusion, EVEN, 96)
            positions = index.positions()
            self.assertGreater(len(positions), 1)
            for i, (px, pz) in enumerate(positions):
                for qx, qz in positions[i + 1:]:
                    d = (px - qx) ** 2 + (pz - qz) ** 2
                    self.assertGreater(d, exclusion * exclusion,
                                       "(%d,%d) and (%d,%d) too close at exclusion %d"
                                       % (px, pz, qx, qz, exclusion))

    def test_exclusion_holds_for_every_profile(self):
        for profile in (npl.NATURAL, npl.DENSE, npl.SPARSE, npl.CLUSTER):
            index = build(profile, 5, EVEN, 96)
            positions = index.positions()
            for i, (px, pz) in enumerate(positions):
                for qx, qz in positions[i + 1:]:
                    self.assertGreater((px - qx) ** 2 + (pz - qz) ** 2, 25,
                                       "%s placed two within 5 chunks" % profile.id)

    def test_profile_ordering(self):
        dense = len(build(npl.DENSE, 4, EVEN, 128))
        natural = len(build(npl.NATURAL, 4, EVEN, 128))
        sparse = len(build(npl.SPARSE, 4, EVEN, 128))
        self.assertGreater(dense, natural)
        self.assertGreater(natural, sparse)

    def test_bigger_exclusion_places_fewer(self):
        self.assertGreater(len(build(npl.NATURAL, 2, EVEN, 96)),
                           len(build(npl.NATURAL, 16, EVEN, 96)))

    def test_inner_curve_biases_towards_spawn(self):
        index = build(npl.NATURAL, 3, INNER, 64)
        self.assertGreater(len(index), 10)
        limit = 64 * 0.30
        within = sum(1 for x, z in index.positions()
                     if (x * x + z * z) ** 0.5 <= limit)
        self.assertGreater(within / len(index), 0.60)

    def test_outer_curve_biases_towards_the_border(self):
        index = build(npl.NATURAL, 3, OUTER, 64)
        self.assertGreater(len(index), 10)
        limit = 64 * 0.45
        outer = sum(1 for x, z in index.positions()
                    if (x * x + z * z) ** 0.5 > limit)
        self.assertGreater(outer / len(index), 0.60)

    def test_zero_curve_places_nothing(self):
        self.assertEqual(len(build(npl.NATURAL, 3, [0.0] * 10, 64)), 0)

    def test_even_curve_matches_no_curve(self):
        self.assertEqual(build(npl.NATURAL, 3, EVEN, 48).positions(),
                         build(npl.NATURAL, 3, None, 48).positions())

    def test_every_position_is_inside_the_radius(self):
        index = build(npl.DENSE, 2, EVEN, 40)
        for x, z in index.positions():
            self.assertLessEqual((x * x + z * z) ** 0.5, 40)

    def test_radius_is_capped(self):
        index = build(npl.SPARSE, 20, EVEN, 100000)
        for x, z in index.positions():
            self.assertLessEqual((x * x + z * z) ** 0.5, npl.MAX_RADIUS_CHUNKS)

    def test_spawn_offset(self):
        index = npl.NoiseFieldIndex(SEED, npl.DENSE, 2, EVEN, 30, 500, -300)
        self.assertGreater(len(index), 0)
        for x, z in index.positions():
            self.assertLessEqual(((x - 500) ** 2 + (z + 300) ** 2) ** 0.5, 30)

    def test_start_for_stays_within_the_probed_cell(self):
        index = build(npl.NATURAL, 6, EVEN, 96)
        spacing = index.spacing
        for cell_x in range(-8, 9):
            for cell_z in range(-8, 9):
                got = index.start_for(cell_x * spacing, cell_z * spacing)
                self.assertEqual(npl._floor_div(got[0], spacing), cell_x)
                self.assertEqual(npl._floor_div(got[1], spacing), cell_z)

    def test_populated_cells_answer_with_a_placement(self):
        index = build(npl.NATURAL, 6, EVEN, 96)
        for pos in index.positions():
            start = index.start_for(*pos)
            self.assertTrue(index.is_placement(*start))


class TestRadialWeight(unittest.TestCase):
    def test_interpolation(self):
        ramp = [float(i) for i in range(10)]
        self.assertAlmostEqual(npl.radial_weight(ramp, 0, 100), 0.0)
        self.assertAlmostEqual(npl.radial_weight(ramp, 100, 100), 9.0)
        self.assertAlmostEqual(npl.radial_weight(ramp, 250, 100), 9.0)
        self.assertAlmostEqual(npl.radial_weight(ramp, 50, 100), 4.5)
        self.assertAlmostEqual(npl.radial_weight(ramp, 25, 100), 2.25)

    def test_degenerate(self):
        self.assertEqual(npl.radial_weight(None, 10, 100), 1.0)
        self.assertEqual(npl.radial_weight([], 10, 100), 1.0)
        self.assertEqual(npl.radial_weight([3.0, 9.0], 10, 0), 3.0)
        self.assertEqual(npl.radial_weight([0.0, 1.0], -5, 100), 0.0)


class TestClassification(unittest.TestCase):
    def test_rarity_thresholds(self):
        for spacing, expected in [(0, "common"), (24, "common"), (25, "uncommon"),
                                  (45, "uncommon"), (46, "rare"), (80, "rare"),
                                  (81, "endgame"), (600, "endgame")]:
            self.assertEqual(npl.rarity_for_spacing(spacing), expected, spacing)
        self.assertEqual(npl.rarity_for_spacing(-1), "uncommon")
        self.assertEqual(npl.rarity_for_spacing(None), "uncommon")

    def test_salt_is_stable_and_well_separated(self):
        a = npl.salt_of("the_overgrowth")
        self.assertEqual(a, npl.salt_of("the_overgrowth"))
        self.assertNotEqual(a, npl.salt_of("the_overgrowti"))
        self.assertGreater(abs(a - npl.salt_of("the_overgrowti")), 1_000_000)
        self.assertGreater(abs(npl.salt_of("deco") - npl.salt_of("dungeons")), 1_000_000)
        self.assertEqual(npl.salt_of(None), 0)
        self.assertEqual(npl.salt_of(""), 0)

    def test_java_round_matches_java_not_python(self):
        # Java's Math.round is floor(x + 0.5); Python's round() is banker's.
        self.assertEqual(npl._java_round(0.5), 1)
        self.assertEqual(npl._java_round(1.5), 2)
        self.assertEqual(npl._java_round(2.5), 3)
        self.assertEqual(round(0.5), 0)  # the trap this guards against


class TestGroupResolution(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.defaults = npl.load_type_defaults(REPO / "config/custom-dimensions")
        if cls.defaults is None:
            raise unittest.SkipTest("structure-type-defaults.json not found")

    def test_zero_config_gets_type_defaults(self):
        groups = npl.resolve_groups({"type": "multi_biome"}, self.defaults)
        self.assertEqual(len(groups), 7)
        self.assertIs(groups["settlements"]["profile"], npl.NATURAL)
        self.assertIs(groups["dungeons"]["profile"], npl.SPARSE)
        self.assertAlmostEqual(groups["settlements"]["radial"][0], 1.5)
        self.assertAlmostEqual(groups["dungeons"]["radial"][0], 0.0)

    def test_suppression(self):
        for config in ({"type": "multi_biome", "structureDensity": "none"},
                       {"type": "void"},
                       {"type": "superflat"},
                       {},
                       {"type": "multi_biome", "structures": {"mode": "none"}},
                       {"type": "multi_biome", "structures": {"noise": False}}):
            self.assertEqual(npl.resolve_groups(config, self.defaults), {}, config)

    def test_density_and_per_group_precedence(self):
        groups = npl.resolve_groups(
            {"type": "multi_biome", "structureDensity": "dense",
             "structures": {"noise": {"dungeons": "sparse"}}}, self.defaults)
        self.assertIs(groups["dungeons"]["profile"], npl.SPARSE)
        self.assertIs(groups["deco"]["profile"], npl.DENSE)

    def test_peaceful_shift_beats_density(self):
        """Regression: structureDensity used to resurrect suppressed groups."""
        groups = npl.resolve_groups(
            {"type": "cave", "structureDensity": "sparse",
             "difficulty": {"mobMultiplier": 0.0}}, self.defaults)
        self.assertNotIn("dungeons", groups)
        self.assertIn("deco", groups)

    def test_explicit_config_beats_the_peaceful_shift(self):
        groups = npl.resolve_groups(
            {"type": "cave", "difficulty": {"mobMultiplier": 0.0},
             "structures": {"noise": {"dungeons": "dense"}}}, self.defaults)
        self.assertIs(groups["dungeons"]["profile"], npl.DENSE)

    def test_hostile_shift_changes_curves(self):
        groups = npl.resolve_groups(
            {"type": "multi_biome", "difficulty": {"mobMultiplier": 2.5}}, self.defaults)
        self.assertEqual(groups["dungeons"]["radial"], [1.0] * 10)

    def test_exclusion_scales_with_the_profile(self):
        dense = npl.resolve_groups(
            {"type": "multi_biome", "structures": {"noise": "dense"}}, self.defaults)
        sparse = npl.resolve_groups(
            {"type": "multi_biome", "structures": {"noise": "sparse"}}, self.defaults)
        self.assertEqual(dense["deco"]["exclusion"], 2)
        self.assertEqual(sparse["deco"]["exclusion"], 5)

    def test_census_produces_positions_per_group(self):
        census = npl.noise_census(
            12345, "the_test",
            {"type": "multi_biome", "borders": {"player": 1024}}, self.defaults)
        self.assertEqual(set(census), {"deco", "settlements", "dungeons",
                                       "landmarks", "maritime", "endgame", "loot"})
        self.assertTrue(any(census.values()), "census placed nothing anywhere")

    def test_census_is_empty_when_suppressed(self):
        self.assertEqual(
            npl.noise_census(12345, "the_test",
                             {"type": "multi_biome", "structureDensity": "none"},
                             self.defaults), {})


if __name__ == "__main__":
    unittest.main()
