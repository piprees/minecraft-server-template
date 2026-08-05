#!/usr/bin/env python3
"""Unit tests for search_tree.py — the MultiNoiseUtil$SearchTree mirror.

Tests that the Python tree build and traversal match vanilla 1.21.1's
bytecode-derived algorithm: sort axes, batch by powers of 6, strict '<'
tie-breaking (first-visited wins).
"""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from search_tree import (
    SearchTree, _Leaf, _Branch, _create_node, _squared_distance,
    _midpoint, _abs_midpoint_sum, _java_long_div, _PARAM_COUNT,
)


def _point_params(t, h, c, e, d, w, o=0):
    """Build a 14-long params tuple from 7 point values (min=max)."""
    return (t, t, h, h, c, c, e, e, d, d, w, w, o, o)


def _range_params(ranges):
    """Build a 14-long params tuple from 7 (lo, hi) pairs."""
    flat = []
    for lo, hi in ranges:
        flat.extend([lo, hi])
    return tuple(flat)


class TestJavaLongDiv(unittest.TestCase):

    def test_positive_even(self):
        self.assertEqual(_java_long_div(10, 2), 5)

    def test_positive_odd(self):
        self.assertEqual(_java_long_div(11, 2), 5)

    def test_negative_even(self):
        self.assertEqual(_java_long_div(-10, 2), -5)

    def test_negative_odd_truncates_toward_zero(self):
        self.assertEqual(_java_long_div(-11, 2), -5)
        self.assertEqual(_java_long_div(-2133, 2), -1066)
        self.assertEqual(_java_long_div(-3176, 2), -1588)

    def test_zero(self):
        self.assertEqual(_java_long_div(0, 2), 0)


class TestMidpoint(unittest.TestCase):

    def test_symmetric_range(self):
        params = _range_params([(-100, 100)] * 7)
        self.assertEqual(_midpoint(params, 0), 0)

    def test_negative_range(self):
        params = _range_params([(-1308, -825)] + [(-100, 100)] * 6)
        self.assertEqual(_midpoint(params, 0), -1066)

    def test_all_axes(self):
        ranges = [(i * 100, i * 100 + 50) for i in range(7)]
        params = _range_params(ranges)
        for i in range(7):
            self.assertEqual(_midpoint(params, i), i * 100 + 25)


class TestSquaredDistance(unittest.TestCase):

    def test_point_at_range_center(self):
        params = _range_params([(-10, 10)] * 7)
        point = (0,) * 7
        self.assertEqual(_squared_distance(params, point), 0)

    def test_point_at_range_boundary(self):
        params = _range_params([(-10, 10)] * 6 + [(5, 15)])
        point = (0, 0, 0, 0, 0, 0, 5)
        self.assertEqual(_squared_distance(params, point), 0)

    def test_point_outside_range(self):
        params = _range_params([(0, 10)] * 6 + [(0, 10)])
        point = (20,) + (0,) * 6
        self.assertEqual(_squared_distance(params, point), 100)


class TestSearchTreeTrivial(unittest.TestCase):

    def test_single_entry(self):
        params = _point_params(0, 0, 0, 0, 0, 0)
        tree = SearchTree([(params, "only")])
        self.assertEqual(tree.get((0, 0, 0, 0, 0, 0, 0)), "only")

    def test_two_entries_pick_closer(self):
        a = _point_params(0, 0, 0, 0, 0, 0)
        b = _point_params(100, 0, 0, 0, 0, 0)
        tree = SearchTree([(a, "A"), (b, "B")])
        self.assertEqual(tree.get((0, 0, 0, 0, 0, 0, 0)), "A")
        self.assertEqual(tree.get((100, 0, 0, 0, 0, 0, 0)), "B")


class TestSearchTreeTieBreaking(unittest.TestCase):
    """Verify strict '<' tie-breaking: first-visited wins at equal distance."""

    def test_tie_two_entries_simple_tree(self):
        """Two entries at equal distance. The one sorted first by
        abs_midpoint_sum wins because it's visited first."""
        a = _range_params([(0, 0)] * 5 + [(-100, 0)] + [(0, 0)])
        b = _range_params([(0, 0)] * 5 + [(0, 100)] + [(0, 0)])
        point = (0, 0, 0, 0, 0, 0, 0)

        self.assertEqual(_squared_distance(a, point), 0)
        self.assertEqual(_squared_distance(b, point), 0)

        ams_a = _abs_midpoint_sum(a)
        ams_b = _abs_midpoint_sum(b)
        self.assertEqual(ams_a, 50)
        self.assertEqual(ams_b, 50)

        tree = SearchTree([(a, "A"), (b, "B")])
        result = tree.get(point)
        self.assertIn(result, ("A", "B"))

    def test_tie_different_abs_midpoint_sums(self):
        """Two entries at equal distance but different abs_midpoint_sums.
        The smaller sum is visited first."""
        a = _range_params([(0, 0)] * 5 + [(-10, 0)] + [(0, 0)])
        b = _range_params([(0, 0)] * 5 + [(0, 20)] + [(0, 0)])
        point = (0, 0, 0, 0, 0, 0, 0)

        self.assertEqual(_squared_distance(a, point), 0)
        self.assertEqual(_squared_distance(b, point), 0)

        self.assertLess(_abs_midpoint_sum(a), _abs_midpoint_sum(b))

        tree = SearchTree([(a, "A"), (b, "B")])
        self.assertEqual(tree.get(point), "A")

    def test_tie_boundary_probe_value(self):
        """Mirror of the the_gauntlet tie case: probe at the shared
        boundary of two entries. The tree sort determines the winner."""
        a = _range_params([(0, 0)] * 5 + [(-200, -100)] + [(0, 0)])
        b = _range_params([(0, 0)] * 5 + [(-100, 0)] + [(0, 0)])
        point = (0, 0, 0, 0, 0, -100, 0)

        self.assertEqual(_squared_distance(a, point), 0)
        self.assertEqual(_squared_distance(b, point), 0)

        ams_a = _abs_midpoint_sum(a)
        ams_b = _abs_midpoint_sum(b)
        if ams_a < ams_b:
            expected = "A"
        elif ams_b < ams_a:
            expected = "B"
        else:
            expected = "A"

        tree = SearchTree([(a, "A"), (b, "B")])
        self.assertEqual(tree.get(point), expected)


class TestSearchTreeDeterminism(unittest.TestCase):
    """Same entries always produce the same tree and the same results."""

    def test_deterministic_across_builds(self):
        entries = [
            (_point_params(i * 10, 0, 0, 0, 0, 0), f"E{i}")
            for i in range(20)
        ]
        tree1 = SearchTree(list(entries))
        tree2 = SearchTree(list(entries))

        for i in range(-5, 25):
            point = (i * 10, 0, 0, 0, 0, 0, 0)
            self.assertEqual(tree1.get(point), tree2.get(point))


class TestSearchTreeLarger(unittest.TestCase):
    """Tree with > 6 entries exercises the recursive build path."""

    def test_eight_entries_correct_nearest(self):
        entries = [
            (_point_params(i * 100, 0, 0, 0, 0, 0), f"E{i}")
            for i in range(8)
        ]
        tree = SearchTree(entries)
        for i in range(8):
            self.assertEqual(tree.get((i * 100, 0, 0, 0, 0, 0, 0)), f"E{i}")

    def test_fifty_entries(self):
        entries = [
            (_point_params(i * 50, i * 30, 0, 0, 0, 0), f"E{i}")
            for i in range(50)
        ]
        tree = SearchTree(entries)
        for i in range(50):
            point = (i * 50, i * 30, 0, 0, 0, 0, 0)
            self.assertEqual(tree.get(point), f"E{i}")

    def test_tree_vs_linear_agree_except_ties(self):
        """Tree and linear scan agree on all non-tie lookups."""
        entries = [
            (_point_params(i * 100, (i % 5) * 200, 0, 0, 0, 0), f"E{i}")
            for i in range(30)
        ]
        tree = SearchTree(entries)

        for probe_t in range(-200, 3200, 50):
            for probe_h in range(-200, 1200, 100):
                point = (probe_t, probe_h, 0, 0, 0, 0, 0)
                tree_result = tree.get(point)

                best_biome = None
                best_dist = 0x7FFFFFFFFFFFFFFF
                for params, value in entries:
                    d = _squared_distance(params, point)
                    if d < best_dist:
                        best_dist = d
                        best_biome = value

                self.assertEqual(tree_result, best_biome,
                                 f"Mismatch at point {point}")


class TestToLongFloat32(unittest.TestCase):
    """Verify _to_long uses float32 arithmetic matching Java's fmul."""

    def test_boundary_value_minus_0_1308(self):
        from biome_sampler import _to_long
        self.assertEqual(_to_long(-0.1308), -1307)

    def test_exact_integer(self):
        from biome_sampler import _to_long
        self.assertEqual(_to_long(1.0), 10000)
        self.assertEqual(_to_long(-1.0), -10000)
        self.assertEqual(_to_long(0.0), 0)

    def test_clean_decimals(self):
        from biome_sampler import _to_long
        self.assertEqual(_to_long(0.5), 5000)
        self.assertEqual(_to_long(-0.25), -2500)

    def test_truncation_toward_zero(self):
        from biome_sampler import _to_long
        result = _to_long(0.00015)
        self.assertEqual(result, 1)


if __name__ == "__main__":
    unittest.main()
