#!/usr/bin/env python3
"""Tests for the gate that catches a partition cut from the schema's axis.

The defect this guards is upstream of check-band-reach.py: -2..2 is what a band
may legally declare, not what a world crosses, so equal slices of it put most
bands out of reach. One dimension shipped 17 dead bands of 25 that way. Nothing
emits those partitions — no script, no mod path, no recipe — so the only place
to catch the shape is before it ships.
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


def cut(bounds):
    return [(bounds[i], bounds[i + 1]) for i in range(len(bounds) - 1)]


def entries(bounds, axis="weirdness", **others):
    """One explicit biome entry per band, all sharing the same other axes."""
    out = []
    for i, (lo, hi) in enumerate(cut(bounds)):
        params = {axis: [lo, hi]}
        params.update(others)
        out.append((f"test:biome_{i}", params))
    return out


class SchemaSliceTests(unittest.TestCase):
    def test_equal_slices_of_the_whole_axis_are_caught(self):
        self.assertTrue(bands.slices_the_schema(cut([-2.0, -1.2, -0.4, 0.4, 1.2, 2.0])))

    def test_the_twenty_five_band_case_is_caught(self):
        step = 4.0 / 25
        self.assertTrue(bands.slices_the_schema(
            cut([round(-2.0 + step * i, 4) for i in range(26)])))

    def test_an_equal_area_fit_passes(self):
        # Boundaries from a measured range of -0.19..0.10, ends clamped to the
        # axis so no sample falls off it. Unequal by construction.
        self.assertFalse(bands.slices_the_schema(
            cut([-2.0, -0.161, -0.103, -0.045, 0.013, 2.0])))

    def test_a_uniform_fit_over_the_measured_range_passes(self):
        # refit.py's fallback when the CDF stacks on a rail: interior boundaries
        # evenly spaced over the MEASURED range, ends still clamped to +/-2.
        self.assertFalse(bands.slices_the_schema(
            cut([-2.0, -0.15, -0.05, 0.05, 0.15, 2.0])))

    def test_two_bands_splitting_the_axis_are_left_alone(self):
        # A cold half and a warm half is a decision; three equal slices is not.
        self.assertFalse(bands.slices_the_schema(cut([-2.0, 0.0, 2.0])))

    def test_equal_slices_inside_the_axis_are_not_this_fault(self):
        # Reachability is check-band-reach.py's measured question. This gate
        # only knows the shape of a cut taken from the schema's own range.
        self.assertFalse(bands.slices_the_schema(cut([-1.0, -0.5, 0.0, 0.5, 1.0])))

    def test_a_chain_stopping_short_of_the_axis_end_is_not_caught(self):
        self.assertFalse(bands.slices_the_schema(cut([-2.0, -0.8, 0.4, 1.6])))


class ChainTests(unittest.TestCase):
    def test_surface_and_cave_bands_are_separate_partitions(self):
        surface = entries([-2.0, -1.2, -0.4, 0.4, 1.2, 2.0], depth=[-2.0, 0.1])
        caves = entries([-2.0, 0.0, 2.0], depth=[0.1, 2.0])
        got = bands.chains(surface + caves, "weirdness")
        self.assertEqual(sorted(len(c) for c in got), [2, 5])

    def test_a_gap_breaks_a_chain_in_two(self):
        got = bands.chains(entries([-2.0, -1.0]) + entries([0.5, 1.0, 2.0]), "weirdness")
        self.assertEqual(sorted(len(c) for c in got), [1, 2])

    def test_an_axis_no_entry_states_yields_no_chain(self):
        self.assertEqual(bands.chains(entries([-2.0, 0.0, 2.0]), "temperature"), [])

    def test_the_sliced_surface_chain_is_caught_and_the_cave_pair_is_not(self):
        surface = entries([-2.0, -1.2, -0.4, 0.4, 1.2, 2.0], depth=[-2.0, 0.1])
        caves = entries([-2.0, 0.0, 2.0], depth=[0.1, 2.0])
        caught = [c for c in bands.chains(surface + caves, "weirdness")
                  if bands.slices_the_schema(c)]
        self.assertEqual([len(c) for c in caught], [5])


if __name__ == "__main__":
    unittest.main()
