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


class RampSampler:
    """Height rises linearly with |x|, so relief is a known function of the
    window sampled. That is the whole property under test: relief is an
    absolute block figure, so a wider window reports a bigger number for
    identical terrain."""

    def __init__(self):
        self.points = []
        self.climate_points = []

    def biome_at(self, x, z):
        self.points.append((x, z))
        return "minecraft:ocean" if x < 0 else "minecraft:plains"

    def sample_climate(self, x, z):
        self.climate_points.append((x, z))
        # cont_to_height is monotonic, so a monotonic continentalness gives a
        # monotonic height and max-minus-min scales with the span.
        return {"continentalness": x / 8192.0, "erosion": x / 8192.0}


class ReliefSpanTests(unittest.TestCase):
    """Relief and grain are capped; water is not. See terrain_survey.RELIEF_SPAN.

    Uncapped, relief measured the WINDOW rather than the world: median 15 blocks
    at radius 256 against 96 at radius 8192 across the real bank, a ~6x spread
    that no single TERRAIN_TARGETS window can describe.
    """

    def test_relief_uses_the_capped_window_not_the_radius(self):
        wide = ts.survey(RampSampler(), 8192, grid=9, relief_span=0)
        capped = ts.survey(RampSampler(), 8192, grid=9, relief_span=2048)
        self.assertGreater(wide["relief"], capped["relief"],
                           "a wider window must report more relief on the same ramp")
        self.assertEqual(capped["reliefSpan"], 2048)
        self.assertEqual(wide["reliefSpan"], 8192)

    def test_climate_is_sampled_only_inside_the_cap(self):
        s = RampSampler()
        ts.survey(s, 8192, grid=9, relief_span=2048)
        xs = [x for x, _z in s.climate_points]
        self.assertEqual(min(xs), -2048)
        self.assertEqual(max(xs), 2048)

    def test_water_still_spans_the_full_radius(self):
        """Capping water would walk back the bug the survey exists to fix — a
        winner reporting 0% water beside a render a third ocean."""
        s = RampSampler()
        got = ts.survey(s, 8192, grid=9, relief_span=2048)
        xs = [x for x, _z in s.points]
        self.assertEqual(min(xs), -8192)
        self.assertEqual(max(xs), 8192)
        self.assertAlmostEqual(got["water"], 4 / 9.0, places=4)

    def test_a_dimension_smaller_than_the_cap_walks_once(self):
        """The common case for pocket dimensions, and it must not cost twice."""
        s = RampSampler()
        got = ts.survey(s, 512, grid=9, relief_span=2048)
        self.assertEqual(got["reliefSpan"], 512)
        self.assertEqual(len(s.climate_points), 81, "one 9x9 walk, not two")
        self.assertEqual(len(s.points), 81)

    def test_two_sizes_agree_once_both_are_inside_the_cap(self):
        """The point of the cap: same terrain, same window, same number."""
        a = ts.survey(RampSampler(), 4096, grid=9, relief_span=2048)
        b = ts.survey(RampSampler(), 8192, grid=9, relief_span=2048)
        self.assertAlmostEqual(a["relief"], b["relief"], places=6)
        self.assertAlmostEqual(a["grain"], b["grain"], places=6)
        # ...and their water figures still differ by world, not by window.
        self.assertAlmostEqual(a["water"], b["water"], places=4)


class FingerprintTests(unittest.TestCase):
    def test_radius_and_grid_are_part_of_validity(self):
        """Neither is in the generation payload, and both decide where the
        samples are taken — a cached survey keyed on the payload alone would be
        read as current after a border change."""
        self.assertNotEqual(ts.fingerprint("abc", 1024), ts.fingerprint("abc", 8192))
        self.assertNotEqual(ts.fingerprint("abc", 1024, grid=9),
                            ts.fingerprint("abc", 1024, grid=17))
        self.assertEqual(ts.fingerprint("abc", 1024), ts.fingerprint("abc", 1024))

    def test_the_relief_span_is_part_of_validity(self):
        """Changing the cap changes what relief MEANS, so it has to re-measure
        the bank. Without this, re-fitted windows would score cached numbers
        taken over a different box."""
        self.assertNotEqual(ts.fingerprint("abc", 8192, relief_span=2048),
                            ts.fingerprint("abc", 8192, relief_span=4096))

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

    def test_relief_and_grain_come_from_a_capped_survey(self):
        """TERRAIN_TARGETS is now fitted to the capped window, so the surveyed
        figures are the ones it describes."""
        rows = self.rows([0] * 9, [60, 60, 60, 60, 90, 60, 60, 60, 60])
        rows["_terrain"] = {"water": 0.3, "land": 1.0, "relief": 77.0,
                            "grain": 9.0, "reliefSpan": 2048}
        relief, grain, _w, _l = score_dimensions.terrain_metrics(rows)
        self.assertAlmostEqual(relief, 77.0)
        self.assertAlmostEqual(grain, 9.0)

    def test_a_survey_with_no_relief_span_is_not_trusted_for_relief(self):
        """A survey cached before the cap measured relief over the FULL radius.
        That is a different figure on a different window, and the re-fitted
        windows would score it as if it were this one. The 3x3 fallback is wrong
        by a known bounded amount; a full-radius relief would be wrong by an
        unknown one. reliefSpan is in the survey fingerprint, so this branch is
        transitional — but it must not silently mis-score while it lasts.
        """
        rows = self.rows([0] * 9, [60, 60, 60, 60, 90, 60, 60, 60, 60])
        rows["_terrain"] = {"water": 0.3, "land": 1.0, "relief": 999.0,
                            "grain": 42.0}
        relief, grain, water, _l = score_dimensions.terrain_metrics(rows)
        self.assertAlmostEqual(relief, 30.0, msg="3x3 relief, not the stale survey")
        self.assertNotAlmostEqual(grain, 42.0)
        # Water is scale-free and was never the problem, so it still applies.
        self.assertAlmostEqual(water, 0.3)

    def test_no_survey_leaves_every_metric_exactly_as_before(self):
        rows = self.rows([1, 0, 0, 0, 0, 0, 0, 0, 0], [64] * 8 + [100])
        relief, grain, water, land = score_dimensions.terrain_metrics(rows)
        self.assertAlmostEqual(relief, 36.0)
        self.assertAlmostEqual(water, 1 / 9.0)
        self.assertAlmostEqual(land, 1.0)
        self.assertGreater(grain, 0.0)


class _ClimateSampler:
    """Programmable (biome, continentalness) per point for water tests."""

    def __init__(self, fn):
        self._fn = fn

    def _climate(self, x, z):
        biome, cont = self._fn(x, z)
        return biome, {"temperature": 0.0, "humidity": 0.0,
                       "continentalness": cont, "erosion": 0.0,
                       "depth": 0.0, "weirdness": 0.0}

    def biome_at(self, x, z):
        return self._climate(x, z)[0]

    def biome_and_climate(self, x, z):
        return self._climate(x, z)

    def sample_climate(self, x, z):
        return self._climate(x, z)[1]


class WaterMeasuresWaterTests(unittest.TestCase):
    """Phase 8: water is what the terrain does, not what the biome is
    called — surveyed height below the effective sea level counts, with
    biome identity kept as the floor."""

    def test_sunken_terrain_is_wet_without_water_biomes(self):
        # cont -0.5 -> height ~53, below sea level 63, biome is a MEADOW.
        wisteria = _ClimateSampler(lambda x, z: ("minecraft:meadow", -0.5))
        wet = ts.survey(wisteria, 256, sea_level=63)
        dry = ts.survey(wisteria, 256, sea_level=None)
        self.assertEqual(1.0, wet["water"], "every column sits below sea level")
        self.assertEqual(0.0, dry["water"], "biome-identity only without a sea level")

    def test_high_ground_stays_dry(self):
        dustbowl = _ClimateSampler(lambda x, z: ("terralith:ancient_sands", 0.5))
        self.assertEqual(0.0, ts.survey(dustbowl, 256, sea_level=63)["water"])

    def test_water_biome_is_wet_regardless_of_height(self):
        # An ocean biome on high-cont terrain is still water (the floor).
        odd = _ClimateSampler(lambda x, z: ("minecraft:ocean", 0.5))
        self.assertEqual(1.0, ts.survey(odd, 256, sea_level=63)["water"])

    def test_custom_sea_level_moves_the_line(self):
        # height ~53: dry below a 50 sea level, wet below a 60 one.
        s = _ClimateSampler(lambda x, z: ("minecraft:meadow", -0.5))
        self.assertEqual(0.0, ts.survey(s, 256, sea_level=50)["water"])
        self.assertEqual(1.0, ts.survey(s, 256, sea_level=60)["water"])


if __name__ == "__main__":
    unittest.main()
