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

# The shipped curves (structure-type-defaults.json). Relative DENSITY per
# radial decile, spawn -> border, each normalised to an area-weighted mean of
# 1.0 so a curve redistributes content without changing how much there is.
INNER = [2.8, 2.3, 1.9, 1.6, 1.35, 1.15, 0.95, 0.8, 0.65, 0.55]
OUTER = [0.3, 0.35, 0.45, 0.55, 0.65, 0.8, 0.95, 1.1, 1.3, 1.55]
EVEN = [1.0] * 10
# A deliberate hard edge — the one thing a 0.0 in a curve still means.
OUTER_HALF_ONLY = [0.0] * 5 + [1.0] * 5


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

    @staticmethod
    def _density(index, radius, decile, bins=10):
        """Placements per unit area in one radial decile, normalised so a
        uniform layout reads 1.0 everywhere. Density, not share: decile 9 is
        19% of the disc and decile 0 is 1%, so raw counts would read every
        uniform layout as border-biased."""
        hist = [0] * bins
        for x, z in index.positions():
            b = min(bins - 1, int((x * x + z * z) ** 0.5 / radius * bins))
            hist[b] += 1
        if not len(index):
            return 0.0
        # Annulus areas go as 2i+1 and sum to 100 over ten bins.
        return (hist[decile] / len(index)) / ((2 * decile + 1) / 100.0)

    def test_inner_curve_is_denser_near_spawn(self):
        index = build(npl.NATURAL, 3, INNER, 512)
        self.assertGreater(len(index), 100)
        near = self._density(index, 512, 0)
        far = self._density(index, 512, 9)
        self.assertGreater(near, 2.0 * far,
                           "inner asks for 2.8 vs 0.55 (5.1x), measured %.2f vs %.2f"
                           % (near, far))

    def test_outer_curve_is_denser_at_the_border(self):
        index = build(npl.NATURAL, 3, OUTER, 512)
        self.assertGreater(len(index), 100)
        near = self._density(index, 512, 0)
        far = self._density(index, 512, 9)
        self.assertGreater(far, 2.0 * near,
                           "outer asks for 0.3 vs 1.55 (5.2x), measured %.2f vs %.2f"
                           % (near, far))

    def test_a_taper_thins_the_border_without_emptying_it(self):
        """The regression the 2026-07-29 change exists for.

        The curve used to multiply into the noise before the threshold test, so
        the moment a taper fell below the profile's threshold the band went to
        absolute zero — 33 dimensions had no village past a third of their
        radius. A taper must thin, never delete.
        """
        index = build(npl.NATURAL, 3, INNER, 512)
        for decile in range(10):
            self.assertGreater(
                self._density(index, 512, decile), 0.0,
                "decile %d is empty under a curve that only tapers" % decile)

    def test_a_zero_in_the_curve_still_suppresses_absolutely(self):
        """0.0 is now the ONLY way to ask for a hard edge, so it has to keep
        working or an author cannot express one at all."""
        index = build(npl.NATURAL, 3, OUTER_HALF_ONLY, 512)
        self.assertGreater(len(index), 100)
        for x, z in index.positions():
            self.assertGreater((x * x + z * z) ** 0.5 / 512.0, 0.44)

    def test_zero_curve_places_nothing(self):
        self.assertEqual(len(build(npl.NATURAL, 3, [0.0] * 10, 64)), 0)

    def test_exclusion_scales_as_the_inverse_square_root_of_the_weight(self):
        """d = base / sqrt(weight), so density (which goes as 1/d^2) is
        directly proportional to the weight. Mirrors
        NoiseFieldIndex.exclusionFor — Java's Math.round is floor(x + 0.5), so
        the mirror must not use Python's banker's rounding."""
        self.assertEqual(npl.exclusion_for(20, 1.0), 20)
        self.assertEqual(npl.exclusion_for(20, 4.0), 10)
        self.assertEqual(npl.exclusion_for(20, 0.25), 40)
        # Capped so the neighbourhood scan stays bounded.
        self.assertEqual(npl.exclusion_for(20, npl.MIN_RADIAL_WEIGHT), 80)
        self.assertEqual(npl.exclusion_for(20, 0.0001), 80)
        # Never below one chunk, whatever the peak.
        self.assertEqual(npl.exclusion_for(1, 3.0), 1)
        # Zero is not a separation, it is a suppression.
        self.assertEqual(npl.exclusion_for(20, 0.0), 0)

    def test_spacing_comes_from_the_curves_peak_not_its_base(self):
        """The locate cell must fit the DENSEST packing the curve can ask for.
        Sized from the base, a cell would hold two placements wherever the
        weight peaks and by_region would silently drop all but the first."""
        index = build(npl.NATURAL, 6, INNER, 512)
        self.assertEqual(index.spacing, npl.exclusion_for(6, 2.8) * 2)
        self.assertLess(index.spacing, 12)
        # A uniform curve reproduces the unscaled separation exactly, or every
        # `even` group in the shipped config would have moved for nothing.
        self.assertEqual(build(npl.NATURAL, 7, EVEN, 64).spacing, 14)
        self.assertEqual(build(npl.NATURAL, 7, None, 64).spacing, 14)

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
        # settlements -> inner, dungeons -> outer per the type table. Compared
        # against the named curves rather than literal values: what this owns is
        # the MAPPING, and retuning a curve's numbers (as 2026-07-29 did) should
        # not read as a broken precedence chain.
        curves = self.defaults["curves"]
        self.assertEqual(groups["settlements"]["radial"], curves["inner"])
        self.assertEqual(groups["dungeons"]["radial"], curves["outer"])

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


class TestStructurePick(unittest.TestCase):
    """Mirrors StructurePickTest's worked cases (precision plan §3.6)."""

    def test_resolve_weighted_three_entries(self):
        """[a:1, b:1, c:2] total=4 -> 0=a, 1=b, 2=c, 3=c."""
        pool = [("a", 1), ("b", 1), ("c", 2)]
        self.assertEqual(npl.resolve_structure(pool, 0), "a")
        self.assertEqual(npl.resolve_structure(pool, 1), "b")
        self.assertEqual(npl.resolve_structure(pool, 2), "c")
        self.assertEqual(npl.resolve_structure(pool, 3), "c")
        # Wraps: pick_value=4 -> 4%4=0 -> a
        self.assertEqual(npl.resolve_structure(pool, 4), "a")

    def test_single_entry(self):
        pool = [("only", 5)]
        for pv in range(10):
            self.assertEqual(npl.resolve_structure(pool, pv), "only")

    def test_empty_pool_returns_none(self):
        self.assertIsNone(npl.resolve_structure([], 42))

    def test_zero_total_weight_returns_none(self):
        self.assertIsNone(npl.resolve_structure([("x", 0), ("y", 0)], 42))

    def test_input_order_independence(self):
        """The caller sorts by id; shuffling the input must not change the
        output, because both sides sort before walking."""
        import random
        pool_a = [("alpha", 3), ("beta", 2), ("gamma", 5)]
        pool_b = [("gamma", 5), ("alpha", 3), ("beta", 2)]
        pool_c = [("beta", 2), ("gamma", 5), ("alpha", 3)]
        sorted_a = sorted(pool_a)
        sorted_b = sorted(pool_b)
        sorted_c = sorted(pool_c)
        for pv in range(20):
            result_a = npl.resolve_structure(sorted_a, pv)
            result_b = npl.resolve_structure(sorted_b, pv)
            result_c = npl.resolve_structure(sorted_c, pv)
            self.assertEqual(result_a, result_b, "pv=%d" % pv)
            self.assertEqual(result_a, result_c, "pv=%d" % pv)

    def test_duplicate_id_adjacency(self):
        """[(x,2),(x,3),(y,5)] sorted is [(x,2),(x,3),(y,5)]; the cumulative
        walk over that equals walking merged {x:5, y:5} for all targets."""
        split = sorted([("x", 2), ("x", 3), ("y", 5)])
        merged = sorted([("x", 5), ("y", 5)])
        for pv in range(20):
            self.assertEqual(
                npl.resolve_structure(split, pv),
                npl.resolve_structure(merged, pv),
                "pv=%d: split and merged disagree" % pv)

    def test_pick_rank_decorrelation(self):
        """pick_seed XORs a different salt, so priority(pickSeed, cx, cz) !=
        priority(noiseSeed, cx, cz) over a grid."""
        noise_seed = 123456789
        ps = npl.pick_seed(noise_seed)
        self.assertNotEqual(ps, noise_seed)
        different = 0
        for cx in range(-10, 11):
            for cz in range(-10, 11):
                if npl.priority(ps, cx, cz) != npl.priority(noise_seed, cx, cz):
                    different += 1
        # Virtually all must differ; a few coincidences are possible.
        self.assertGreater(different, 400)

    def test_pick_salt_is_stable(self):
        npl._ensure_pick_salt()
        self.assertEqual(npl.PICK_SALT, npl.salt_of("structure_pick") & npl.M64)

    def test_pick_seed_deterministic(self):
        a = npl.pick_seed(0xDEADBEEF)
        b = npl.pick_seed(0xDEADBEEF)
        self.assertEqual(a, b)
        self.assertNotEqual(a, npl.pick_seed(0xDEADBEEF + 1))


class TestPoolHash(unittest.TestCase):
    def test_empty_pool_hashes(self):
        h = npl.pool_hash({})
        self.assertIsInstance(h, str)
        self.assertEqual(len(h), 12)

    def test_deterministic(self):
        pools = {"settlements": {"minecraft:village_plains": 1, "minecraft:village_desert": 1}}
        self.assertEqual(npl.pool_hash(pools), npl.pool_hash(pools))

    def test_different_weights_differ(self):
        p1 = {"g": {"a": 1, "b": 2}}
        p2 = {"g": {"a": 2, "b": 1}}
        self.assertNotEqual(npl.pool_hash(p1), npl.pool_hash(p2))

    def test_different_groups_differ(self):
        p1 = {"g1": {"a": 1}}
        p2 = {"g2": {"a": 1}}
        self.assertNotEqual(npl.pool_hash(p1), npl.pool_hash(p2))


if __name__ == "__main__":
    unittest.main()
