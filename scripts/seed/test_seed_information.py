#!/usr/bin/env python3
"""Tests for seed_information.py — the bits-of-search metric.

The maths is small and the failure modes are all at the edges (nothing
succeeded, nothing was drawn, one draw), so that is where the coverage is.
"""
import math
import unittest

import seed_information as si


class BitsTests(unittest.TestCase):
    def test_everything_qualifies_costs_almost_nothing(self):
        b, bound = si.bits(1000, 1000)
        self.assertLess(b, 0.01)
        self.assertFalse(bound)

    def test_half_qualifying_costs_about_one_bit(self):
        b, _ = si.bits(500, 1000)
        self.assertAlmostEqual(b, 1.0, places=1)

    def test_one_in_a_thousand_costs_about_ten_bits(self):
        b, _ = si.bits(1, 1000)
        self.assertAlmostEqual(b, 9.7, places=0)

    def test_nothing_qualifying_is_a_lower_bound_not_infinity(self):
        """The whole point: a=0 is unbounded, but the answer has to be a
        number a human can read next to the draw count."""
        b, bound = si.bits(0, 400)
        self.assertTrue(bound)
        self.assertTrue(math.isfinite(b))
        # (0 + 0.5) / (400 + 1) -> about 9.6 bits
        self.assertAlmostEqual(b, 9.65, places=1)

    def test_the_bound_grows_with_the_draw_count(self):
        """Evidence, not assertion: 0 of 4000 is a stronger statement than
        0 of 400 and must read as a bigger number."""
        small, _ = si.bits(0, 400)
        large, _ = si.bits(0, 4000)
        self.assertGreater(large, small)

    def test_no_draws_is_zero_not_a_division_error(self):
        self.assertEqual(si.bits(0, 0), (0.0, False))

    def test_smoothing_separates_zero_from_one(self):
        none, _ = si.bits(0, 100)
        one, _ = si.bits(1, 100)
        self.assertGreater(none, one)


class CandidateCriteriaTests(unittest.TestCase):
    """A criterion is MET at the scorer's full credit, which is the same
    condition the detail panel paints as severity 0."""

    PROFILE = {
        "radius": 1000.0,
        "namesake": ["minecraft:plains"],
        "variety_biomes": ["minecraft:plains", "minecraft:forest"],
        "terrain": {"relief": (10, 50), "grain": (2, 8), "water": (0.0, 0.4)},
        "battery": [],
        "forced_structures": [],
    }

    def _met(self, rows, profile=None):
        return si.candidate_criteria(profile or self.PROFILE, rows, None,
                                     lambda sid: None, {})

    def test_spawn_is_met_only_by_a_filter_biome(self):
        self.assertTrue(self._met({"spawn_biome": "minecraft:plains"})["spawn"])
        self.assertFalse(self._met({"spawn_biome": "minecraft:desert"})["spawn"])

    def test_terrain_is_met_inside_the_band_only(self):
        rows = {"spawn_biome": "x",
                "height_r0c0": "60", "height_r0c1": "60", "height_r1c0": "90",
                "water_r0c0": "0"}
        met = self._met(rows)
        # relief 30 is inside 10-50; water 0 is inside 0-0.4
        self.assertTrue(met["terrain:relief"])
        self.assertTrue(met["terrain:water"])

    def test_terrain_outside_the_band_is_not_met(self):
        rows = {"spawn_biome": "x", "height_r0c0": "0", "height_r0c1": "500",
                "water_r0c0": "0"}
        self.assertFalse(self._met(rows)["terrain:relief"])

    def test_a_variety_biome_beyond_the_radius_is_not_met(self):
        rows = {"spawn_biome": "x", "biome_minecraft:forest_dist": "5000"}
        self.assertFalse(self._met(rows)["biome:minecraft:forest"])

    def test_a_variety_biome_inside_the_radius_is_met(self):
        rows = {"spawn_biome": "x", "biome_minecraft:forest_dist": "500"}
        self.assertTrue(self._met(rows)["biome:minecraft:forest"])

    def test_a_namesake_biome_is_not_double_counted_as_variety(self):
        rows = {"spawn_biome": "minecraft:plains"}
        self.assertNotIn("biome:minecraft:plains", self._met(rows))

    def test_a_grid_want_is_met_only_inside_its_band(self):
        profile = dict(self.PROFILE, battery=[
            ("village", "#minecraft:village", (100.0, 500.0), "want")])
        rows = {"spawn_biome": "x", "structure_village_dist": "300"}
        self.assertTrue(self._met(rows, profile)["want:village"])
        rows["structure_village_dist"] = "900"
        self.assertFalse(self._met(rows, profile)["want:village"])

    def test_a_grid_shun_is_met_when_the_structure_is_absent(self):
        profile = dict(self.PROFILE, battery=[
            ("monument", "minecraft:monument", 400.0, "shun")])
        self.assertTrue(self._met(
            {"spawn_biome": "x", "structure_monument_dist": "-1"},
            profile)["shun:monument"])
        self.assertFalse(self._met(
            {"spawn_biome": "x", "structure_monument_dist": "100"},
            profile)["shun:monument"])


class ChainRuleTests(unittest.TestCase):
    """Fi_total = Fi_spawn + Fi_rest_given_spawn, exactly.

    The decomposition exists because counting only `candidates` divides out
    the spawn filter's cost, which for a fussy dimension is most of the cost.
    If the two ever stop adding up, one of them is measuring the wrong set.
    """

    def _store(self, accepted, rejected, measured_ok):
        cands = {}
        for i in range(accepted):
            cands[str(i)] = {"measurements": {"errors": "0", "spawn_biome":
                                              "minecraft:plains" if i < measured_ok
                                              else "minecraft:desert"},
                             "scores": {}}
        return {"candidates": cands,
                "rejected": {str(1000 + i): "spawn" for i in range(rejected)},
                "abandoned": {}, "configHash": "", "winner": None,
                "winnerPinned": False}

    def test_spawn_and_rest_add_to_the_total(self):
        profile = {"radius": 1000.0, "namesake": ["minecraft:plains"],
                   "variety_biomes": [], "terrain": {}, "battery": [],
                   "forced_structures": []}
        rep = si.dimension_report(
            "t", {"type": "overworld"}, profile,
            self._store(accepted=50, rejected=50, measured_ok=25), None,
            lambda sid: None)
        self.assertEqual(rep["drawn"], 100)
        self.assertEqual(rep["measured"], 50)
        self.assertEqual(rep["met"], 25)
        self.assertAlmostEqual(rep["fi_spawn"] + rep["fi_rest"],
                               rep["fi_total"], places=9)

    def test_a_dimension_nothing_was_drawn_for_reports_nothing(self):
        profile = {"radius": 1.0, "namesake": [], "variety_biomes": [],
                   "terrain": {}, "battery": [], "forced_structures": []}
        self.assertIsNone(si.dimension_report(
            "t", {"type": "overworld"}, profile,
            self._store(0, 0, 0), None, lambda sid: None))


if __name__ == "__main__":
    unittest.main()
