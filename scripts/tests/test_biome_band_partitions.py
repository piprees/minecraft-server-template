#!/usr/bin/env python3
"""Tests for check-biome-bands.py — all three arms, and the constants they rely on.

The refusal arm guards a defect upstream of check-band-reach.py: -2.0 is where
the schema starts, not where a world does, so a partition stepped from it puts
most of its bands out of reach. Measured against `7f5c5e98`, which carries the
defect, `anchored_equal_run` fires on 10 dimension/axis pairs covering 167 bands,
and on none of the eight clean states measured either side of it.

Every constant below is pinned by a test that fails when it moves, because a
threshold nothing exercises is a threshold that can be widened to nothing.
"""
import importlib.util
import sys
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent
_spec = importlib.util.spec_from_file_location(
    "check_biome_bands", SCRIPTS / "check-biome-bands.py")
bands = importlib.util.module_from_spec(_spec)
sys.modules["check_biome_bands"] = bands
_spec.loader.exec_module(bands)


def steps(start, width, n, end=None):
    """n equal bands of `width` from `start`, optionally with a catch-all tail."""
    out, cur = [], start
    for _ in range(n):
        out.append((round(cur, 6), round(cur + width, 6)))
        cur = round(cur + width, 6)
    if end is not None:
        out.append((cur, end))
    return out


def entries(bands_, axis="weirdness", **others):
    out = []
    for i, (lo, hi) in enumerate(bands_):
        params = {axis: [lo, hi]}
        params.update(others)
        out.append((f"test:biome_{i}", params))
    return out


class AnchoredRunTests(unittest.TestCase):
    """The shape that actually ships: equal steps from the floor, then a tail."""

    def test_the_crucible_as_it_shipped_is_caught(self):
        # 17 bands of 0.0694 from -2.0, then one catch-all of 1.5215.
        run, step = bands.anchored_equal_run(steps(-2.0, 0.0694, 17, end=2.0))
        self.assertEqual(run, 17)
        self.assertAlmostEqual(step, 0.0694, places=4)

    def test_a_run_that_stops_far_short_of_the_axis_end_is_caught(self):
        # 15 x 0.047 from -2.0 ends at -1.295 and never approaches +2.0.
        run, _ = bands.anchored_equal_run(steps(-2.0, 0.047, 15))
        self.assertEqual(run, 15)

    def test_slices_rounded_to_one_decimal_place_are_caught(self):
        # The most natural hand-authoring idiom there is: 3, 6 and 9 equal cuts
        # of -2..2 written to one decimal.
        for n in (3, 6, 9):
            width = 4.0 / n
            b = [(round(-2.0 + width * i, 1), round(-2.0 + width * (i + 1), 1))
                 for i in range(n)]
            b = [(lo, hi) for lo, hi in b]
            for i in range(len(b) - 1):
                b[i + 1] = (b[i][1], b[i + 1][1])
            run, _ = bands.anchored_equal_run(b)
            self.assertGreaterEqual(run, bands.MIN_RUN, f"{n} rounded slices escaped")

    def test_one_nudged_boundary_does_not_hide_the_run(self):
        b = steps(-2.0, 0.2, 8)
        b[2] = (b[2][0], round(b[2][1] + 0.03, 6))
        b[3] = (b[2][1], b[3][1])
        run, _ = bands.anchored_equal_run(b)
        self.assertGreaterEqual(run, bands.MIN_RUN)

    def test_a_second_axis_filter_per_band_does_not_hide_the_run(self):
        # Every band carries its own temperature filter, so grouping by the
        # other axes a band constrains puts each in a group of one.
        ex = []
        for i, (lo, hi) in enumerate(steps(-2.0, 0.1, 12)):
            ex.append((f"test:b{i}", {"weirdness": [lo, hi],
                                      "temperature": [i * 0.01, i * 0.01 + 0.005]}))
        run, _ = bands.anchored_equal_run(bands.bands_on(ex, "weirdness"))
        self.assertEqual(run, 12)

    def test_an_equal_area_fit_passes(self):
        # Boundaries from a measured range, ends clamped to the axis.
        run, _ = bands.anchored_equal_run(
            [(-2.0, -0.161), (-0.161, -0.103), (-0.103, -0.045), (-0.045, 0.013), (0.013, 2.0)])
        self.assertEqual(run, 0)

    def test_a_uniform_fit_over_the_measured_range_passes(self):
        # refit.py's rail fallback: even steps over the MEASURED range, with the
        # outermost pair clamped. The first step is nothing like the interior.
        run, _ = bands.anchored_equal_run(
            [(-2.0, -0.15), (-0.15, -0.05), (-0.05, 0.05), (0.05, 0.15), (0.15, 2.0)])
        self.assertEqual(run, 0)

    def test_a_run_that_does_not_start_at_the_floor_passes(self):
        run, _ = bands.anchored_equal_run(steps(-1.0, 0.2, 8))
        self.assertEqual(run, 0)

    def test_two_equal_steps_are_not_a_partition(self):
        run, _ = bands.anchored_equal_run(steps(-2.0, 0.3, 2, end=2.0))
        self.assertEqual(run, 0)


class ConstantTests(unittest.TestCase):
    """Each constant is load-bearing; moving it must break something."""

    def setUp(self):
        self.saved = {k: getattr(bands, k) for k in
                      ("MIN_RUN", "WIDTH_ABS_TOL", "WIDTH_REL_TOL", "AXIS_FLOOR",
                       "FREE_EPSILON")}

    def tearDown(self):
        for k, v in self.saved.items():
            setattr(bands, k, v)

    def test_min_run_of_three_is_what_separates_a_split_from_a_partition(self):
        b = steps(-2.0, 0.3, 3, end=2.0)
        self.assertEqual(bands.anchored_equal_run(b)[0], 3)
        bands.MIN_RUN = 6
        self.assertEqual(bands.anchored_equal_run(b)[0], 0,
                         "raising MIN_RUN must lose a three-step partition")

    def test_the_absolute_tolerance_is_sized_for_decimal_rounding(self):
        # Rounding both boundaries of a band to one decimal place moves its
        # width by up to 0.1, which is what this tolerance has to absorb.
        b = steps(-2.0, 0.2, 6)
        b[1] = (b[1][0], round(b[1][1] + 0.1, 6))
        b[2] = (b[1][1], b[2][1])
        self.assertGreaterEqual(bands.anchored_equal_run(b)[0], bands.MIN_RUN)
        bands.WIDTH_ABS_TOL = 0.001
        bands.WIDTH_REL_TOL = 0.0
        self.assertEqual(bands.anchored_equal_run(b)[0], 0,
                         "tightening the tolerance must lose a rounded partition")

    def test_the_relative_tolerance_carries_wide_steps(self):
        b = steps(-2.0, 5.0, 4)
        b[1] = (b[1][0], round(b[1][1] + 0.09, 6))
        b[2] = (b[1][1], b[2][1])
        bands.WIDTH_ABS_TOL = 0.0
        self.assertGreaterEqual(bands.anchored_equal_run(b)[0], bands.MIN_RUN)
        bands.WIDTH_REL_TOL = 0.0
        self.assertEqual(bands.anchored_equal_run(b)[0], 0)

    def test_the_floor_is_the_anchor_and_not_just_any_start(self):
        self.assertEqual(bands.anchored_equal_run(steps(-1.5, 0.2, 9))[0], 0)
        bands.AXIS_FLOOR = -1.5
        self.assertEqual(bands.anchored_equal_run(steps(-1.5, 0.2, 9))[0], 9,
                         "the anchor is what makes this the schema's floor")


class OverlapTests(unittest.TestCase):
    """The arm with the longest measured record: 225 overlapping pairs at 3bf040df."""

    def test_two_entries_claiming_the_same_region_overlap(self):
        self.assertTrue(bands.overlaps({"weirdness": [-1.0, 0.0]},
                                       {"weirdness": [-0.5, 0.5]}))

    def test_touching_bands_do_not_overlap(self):
        self.assertFalse(bands.overlaps({"weirdness": [-1.0, 0.0]},
                                        {"weirdness": [0.0, 1.0]}))

    def test_a_shared_band_in_different_depth_layers_does_not_overlap(self):
        # A hypercube intersects only if it intersects on EVERY axis, which is
        # what lets a surface and a cave entry share a weirdness band.
        self.assertFalse(bands.overlaps({"weirdness": [-1.0, 0.0], "depth": [-2.0, 0.1]},
                                        {"weirdness": [-1.0, 0.0], "depth": [0.1, 2.0]}))

    def test_an_unstated_axis_spans_the_whole_range(self):
        self.assertTrue(bands.overlaps({"weirdness": [-1.0, 0.0]}, {"temperature": [0.0, 1.0]}))


class NativeStarvationTests(unittest.TestCase):
    """Explicit bands covering an axis end to end leave bare-string natives unreachable."""

    def test_full_coverage_leaves_no_free_width(self):
        self.assertAlmostEqual(bands.covered([(-2.0, 0.0), (0.0, 2.0)]), 4.0)

    def test_a_gap_is_where_a_native_lives(self):
        self.assertAlmostEqual(bands.covered([(-2.0, -1.0), (1.0, 2.0)]), 2.0)

    def test_overlapping_spans_are_counted_once(self):
        self.assertAlmostEqual(bands.covered([(-2.0, 0.0), (-1.0, 1.0)]), 3.0)

    def test_the_free_epsilon_is_the_line_between_a_gap_and_a_sliver(self):
        # 4.0 - covered must exceed FREE_EPSILON for a native to have a home.
        sliver = bands.covered([(-2.0, 1.98)]) + bands.covered([(1.99, 2.0)])
        self.assertLess(4.0 - sliver, bands.FREE_EPSILON)
        self.assertGreater(4.0 - bands.covered([(-2.0, 1.0)]), bands.FREE_EPSILON)


if __name__ == "__main__":
    unittest.main()


class DeadRepeats(unittest.TestCase):
    """A repeat is a fault only when it states nothing new."""

    def test_two_bands_for_one_biome_are_legal(self):
        # Vanilla's parameter table gives one biome a hypercube per climate
        # region. Gating on a repeated id would fail the shipped configs that
        # band a biome at two points on an axis deliberately.
        arr = [{"id": "minecraft:taiga", "parameters": {"weirdness": [-2.0, -1.0]}},
               {"id": "minecraft:taiga", "parameters": {"weirdness": [0.5, 1.0]}}]
        self.assertEqual([], bands.dead_repeats(arr))

    def test_identical_parameters_are_reported(self):
        arr = [{"id": "minecraft:taiga", "parameters": {"weirdness": [-2.0, -1.0]}},
               {"id": "minecraft:taiga", "parameters": {"weirdness": [-2.0, -1.0]}}]
        found = bands.dead_repeats(arr)
        self.assertEqual(1, len(found))
        self.assertEqual("minecraft:taiga", found[0][0])
        self.assertIn("identical parameters", found[0][1])

    def test_key_order_does_not_disguise_an_identical_block(self):
        # The comparison is over parsed keys, not the source text.
        arr = [{"id": "minecraft:taiga",
                "parameters": {"weirdness": [-2.0, -1.0], "depth": [0.0, 1.0]}},
               {"id": "minecraft:taiga",
                "parameters": {"depth": [0.0, 1.0], "weirdness": [-2.0, -1.0]}}]
        self.assertEqual(1, len(bands.dead_repeats(arr)))

    def test_a_bare_entry_beside_a_banded_one_is_reported(self):
        # The biome list is deduplicated to a set before placement, so the bare
        # entry cannot reach the layout at all.
        arr = ["minecraft:taiga",
               {"id": "minecraft:taiga", "parameters": {"weirdness": [-2.0, -1.0]}}]
        found = bands.dead_repeats(arr)
        self.assertEqual(1, len(found))
        self.assertIn("bare", found[0][1])

    def test_a_bare_entry_alone_is_not_a_repeat(self):
        arr = ["minecraft:taiga", {"id": "minecraft:jungle",
                                   "parameters": {"weirdness": [0.0, 1.0]}}]
        self.assertEqual([], bands.dead_repeats(arr))


class TieHazards(unittest.TestCase):
    """Bands that can only ever tie, so nothing in the config decides them.

    `ParameterRange.getDistance` returns 0 anywhere in [min, max] and
    `SearchTree` replaces its incumbent only on a strictly smaller distance —
    read from the 1.21.1 bytecode, not from documented semantics. Which of two
    tied bands generates is deliberately NOT asserted anywhere below: it turns
    on `SearchTree.get`'s ThreadLocal incumbent and on traversal order.
    """

    # weirdness saturates at +/-1.0, so those values are returned constantly.
    RAILED = {"weird": [-1.0] * 6 + [0.0] * 100 + [1.0] * 6}

    def test_a_band_reaching_only_the_saturation_point_cannot_outrank(self):
        # [1.0, 2.0] against a world whose weirdness maxes at 1.0: its whole
        # territory is the single value its neighbour already claims.
        ex = [("mod:high", {"weirdness": [1.0, 2.0]}),
              ("mod:mid", {"weirdness": [0.5, 1.0]})]
        found = bands.tie_hazards(ex, self.RAILED)
        self.assertEqual([("mod:high", "weirdness", 1.0, "mod:mid", 6)], found)

    def test_a_narrower_rival_does_not_convict(self):
        # The rival must cover this band on every OTHER axis, or it can be the
        # more distant of the two and this band wins the cell outright.
        ex = [("mod:high", {"weirdness": [1.0, 2.0]}),
              ("mod:mid", {"weirdness": [0.5, 1.0], "temperature": [0.1, 0.2]})]
        self.assertEqual([], bands.tie_hazards(ex, self.RAILED))

    def test_a_wider_rival_convicts_across_axes(self):
        # terralith:alpine_grove's shape: it constrains temperature too, and
        # its rival constrains nothing else, so the rival is never further away.
        ex = [("mod:high", {"weirdness": [1.0, 2.0], "temperature": [0.1, 0.2]}),
              ("mod:mid", {"weirdness": [0.5, 1.0]})]
        found = bands.tie_hazards(ex, self.RAILED)
        self.assertEqual(1, len(found))
        self.assertEqual("mod:high", found[0][0])

    def test_a_band_with_room_to_win_is_not_reported(self):
        # [0.0, 1.0] reaches 0.0 as well as 1.0, and at 0.0 it is alone.
        ex = [("mod:wide", {"weirdness": [0.0, 1.0]}),
              ("mod:mid", {"weirdness": [-1.0, 0.0]})]
        self.assertEqual([], bands.tie_hazards(ex, self.RAILED))

    def test_a_point_the_world_never_returns_is_not_reported(self):
        # A band pinned to one value is harmless when the climate never returns
        # it. The band must be REACHABLE for this to exercise the sample check
        # rather than the reachability line above it.
        ex = [("mod:pin", {"weirdness": [0.5, 0.5]}),
              ("mod:wide", {"weirdness": [-1.0, 1.0]})]
        self.assertEqual([], bands.tie_hazards(ex, {"weird": [-1.0] * 10 + [1.0] * 10}))

    def test_no_samples_means_no_verdict(self):
        ex = [("mod:high", {"weirdness": [1.0, 2.0]}),
              ("mod:mid", {"weirdness": [0.5, 1.0]})]
        self.assertEqual([], bands.tie_hazards(ex, {}))
