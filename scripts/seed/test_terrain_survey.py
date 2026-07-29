#!/usr/bin/env python3
"""Tests for terrain_survey.py — terrain measured over the whole world.

The 3x3 climate grid the roller measures spans a 1024-block box at spawn for an
8192-radius dimension: 0.1% of the render's area. That is why a winner could
report `water 0%` beside a render a third ocean. This module re-measures
post-hoc; what these tests pin is that it measures the same QUANTITIES on the
same SCALES, because a surveyed candidate and an unsurveyed one are ranked
against each other.
"""
import importlib.util
import unittest
from pathlib import Path

import terrain_survey as ts
import fast_roller

SCORE_PATH = Path(__file__).with_name("score-dimensions.py")
SPEC = importlib.util.spec_from_file_location("score_dimensions_ts", SCORE_PATH)
score_dimensions = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(score_dimensions)


class MirrorTests(unittest.TestCase):
    """Anything duplicated from fast_roller has to stay duplicated correctly, or
    surveyed and unsurveyed candidates stop being comparable."""

    def test_water_biomes_match_the_rollers_set(self):
        self.assertEqual(ts.WATER_BIOMES, fast_roller.WATER_BIOMES)

    def test_height_curve_matches_the_rollers(self):
        for cont in (-2.0, -1.2, -0.455, -0.3, -0.11, 0.0, 0.03, 0.4, 0.8, 1.0, 2.0):
            self.assertAlmostEqual(
                ts.cont_to_height(cont), fast_roller._cont_to_height(cont), places=9,
                msg="continentalness %s" % cont)


class GridMetricTests(unittest.TestCase):
    def test_relief_is_max_minus_min(self):
        heights = {(0, 0): 64.0, (0, 1): 100.0, (1, 0): 40.0, (1, 1): 70.0}
        relief, _grain, _water, _land = ts.metrics_from_grid(heights, [0], grid=2)
        self.assertAlmostEqual(relief, 60.0)

    def test_grain_averages_orthogonal_neighbours_only(self):
        # (0,0)-(0,1) = 10, (0,0)-(1,0) = 20, (1,0)-(1,1) = 10, (0,1)-(1,1) = 20.
        # The diagonal is deliberately not counted.
        heights = {(0, 0): 60.0, (0, 1): 70.0, (1, 0): 80.0, (1, 1): 90.0}
        _relief, grain, _water, _land = ts.metrics_from_grid(heights, [0], grid=2)
        self.assertAlmostEqual(grain, 15.0)

    def test_water_is_the_sample_fraction(self):
        _r, _g, water, _l = ts.metrics_from_grid({(0, 0): 64.0}, [1, 0, 0, 1], grid=2)
        self.assertAlmostEqual(water, 0.5)

    def test_a_single_sample_has_no_relief_or_grain(self):
        relief, grain, _w, _l = ts.metrics_from_grid({(0, 0): 64.0}, [0], grid=1)
        self.assertEqual((relief, grain), (0.0, 0.0))

    def test_a_void_records_no_land(self):
        """A void samples no heights at all, so land_fraction must be 0 rather
        than a fake floor — score_candidate reads it to confirm the void is
        actually empty."""
        _r, _g, _w, land = ts.metrics_from_grid({}, [0] * 81, grid=9)
        self.assertEqual(land, 0.0)

    def test_land_fraction_is_against_the_whole_grid(self):
        heights = {(0, 0): 64.0, (0, 1): 64.0}
        _r, _g, _w, land = ts.metrics_from_grid(heights, [0, 0], grid=3)
        self.assertAlmostEqual(land, 2 / 9.0)


class FakeSampler:
    """Records where it was asked, and answers ocean on one half of the world."""

    def __init__(self):
        self.points = []

    def biome_at(self, x, z):
        self.points.append((x, z))
        return "minecraft:ocean" if x < 0 else "minecraft:plains"

    def sample_climate(self, x, z):
        return {"continentalness": 0.0, "erosion": 0.0}


class CoverageTests(unittest.TestCase):
    def test_the_grid_reaches_the_border_on_every_side(self):
        """The whole point of the change. A grid that stops short of the border
        is the 3x3 problem again with more samples."""
        sampler = FakeSampler()
        ts.survey(sampler, 8192, grid=9)
        xs = {x for x, _z in sampler.points}
        zs = {z for _x, z in sampler.points}
        self.assertEqual(min(xs), -8192)
        self.assertEqual(max(xs), 8192)
        self.assertEqual(min(zs), -8192)
        self.assertEqual(max(zs), 8192)
        self.assertEqual(len(sampler.points), 81)

    def test_half_an_ocean_reads_as_half_water(self):
        """The 3x3 sampled -512, 0, +512 and would have called this 1/3 water at
        best. Over the full radius it is the 4 negative columns of 9."""
        got = ts.survey(FakeSampler(), 8192, grid=9)
        # Stored rounded to 5 places to keep the candidate store small.
        self.assertAlmostEqual(got["water"], 4 / 9.0, places=4)

    def test_a_void_survey_skips_the_climate_entirely(self):
        got = ts.survey(FakeSampler(), 1024, is_void=True, grid=3)
        self.assertEqual(got["land"], 0.0)
        self.assertEqual(got["relief"], 0.0)

    def test_a_zero_radius_world_does_not_divide_by_zero(self):
        got = ts.survey(FakeSampler(), 0, grid=9)
        self.assertEqual(got["radius"], 0)


class FingerprintTests(unittest.TestCase):
    def test_radius_and_grid_are_part_of_validity(self):
        """Neither is in the generation payload, and both decide where the
        samples are taken — a cached survey keyed on the payload alone would be
        read as current after a border change."""
        self.assertNotEqual(ts.fingerprint("abc", 1024), ts.fingerprint("abc", 8192))
        self.assertNotEqual(ts.fingerprint("abc", 1024, grid=9),
                            ts.fingerprint("abc", 1024, grid=17))
        self.assertEqual(ts.fingerprint("abc", 1024), ts.fingerprint("abc", 1024))

    def test_a_missing_generation_fingerprint_still_keys(self):
        self.assertTrue(ts.fingerprint(None, 512))


class ScoringIntegrationTests(unittest.TestCase):
    """What terrain_metrics does with a survey, and what it deliberately does
    NOT do with one."""

    def rows(self, water_flags, heights):
        rows = {}
        for i, h in enumerate(heights):
            rows["height_r%dc%d" % (i // 3, i % 3)] = str(h)
        for i, w in enumerate(water_flags):
            rows["water_r%dc%d" % (i // 3, i % 3)] = str(w)
        return rows

    def test_water_comes_from_the_survey(self):
        rows = self.rows([0] * 9, [64] * 9)
        _r, _g, water, _l = score_dimensions.terrain_metrics(rows)
        self.assertEqual(water, 0.0)
        rows["_terrain"] = {"water": 0.309, "land": 1.0, "relief": 77.0, "grain": 9.0}
        _r, _g, water, _l = score_dimensions.terrain_metrics(rows)
        self.assertAlmostEqual(water, 0.309)

    def test_relief_and_grain_deliberately_stay_on_the_3x3(self):
        """TERRAIN_TARGETS is fitted to what a 1024-block box produces. Surveyed
        relief is ~3x larger for the same world, so switching would put two of
        four sampled dimensions completely outside their window — a re-fit is a
        design decision, not part of this fix."""
        rows = self.rows([0] * 9, [60, 60, 60, 60, 90, 60, 60, 60, 60])
        rows["_terrain"] = {"water": 0.3, "land": 1.0, "relief": 999.0, "grain": 42.0}
        relief, grain, _w, _l = score_dimensions.terrain_metrics(rows)
        self.assertAlmostEqual(relief, 30.0)
        self.assertNotAlmostEqual(grain, 42.0)

    def test_no_survey_leaves_every_metric_exactly_as_before(self):
        rows = self.rows([1, 0, 0, 0, 0, 0, 0, 0, 0], [64] * 8 + [100])
        relief, grain, water, land = score_dimensions.terrain_metrics(rows)
        self.assertAlmostEqual(relief, 36.0)
        self.assertAlmostEqual(water, 1 / 9.0)
        self.assertAlmostEqual(land, 1.0)
        self.assertGreater(grain, 0.0)


if __name__ == "__main__":
    unittest.main()
