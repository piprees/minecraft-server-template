#!/usr/bin/env python3
"""Tests for score-biome-table.py — the lookup it models is vanilla's, so the
arithmetic has to match the 1.21.1 bytecode rather than merely look plausible.
"""
import importlib.util
import sys
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent
_spec = importlib.util.spec_from_file_location(
    "score_biome_table", SCRIPTS / "score-biome-table.py")
sbt = importlib.util.module_from_spec(_spec)
sys.modules["score_biome_table"] = sbt
_spec.loader.exec_module(sbt)


def cube(offset=0.0, **axes):
    """A hypercube in fixed point; unnamed axes span everything."""
    out = {a: (sbt.fx(-2.0), sbt.fx(2.0)) for a in sbt.AXES}
    for a, (lo, hi) in axes.items():
        out[a] = (sbt.fx(lo), sbt.fx(hi))
    out["offset"] = sbt.fx(offset)
    return out


def point(**axes):
    p = [0] * 6
    for a, v in axes.items():
        p[sbt.AXES.index(a)] = sbt.fx(v)
    return p


class AxisDistance(unittest.TestCase):
    def test_zero_anywhere_inside(self):
        self.assertEqual(0, sbt.axis_distance(-100, 100, 0))

    def test_zero_at_both_endpoints(self):
        # The T59 property: a range CONTAINS its ends, so two bands sharing a
        # boundary are both at distance zero from a sample sitting on it.
        self.assertEqual(0, sbt.axis_distance(-100, 100, -100))
        self.assertEqual(0, sbt.axis_distance(-100, 100, 100))

    def test_measures_from_the_nearer_end(self):
        self.assertEqual(50, sbt.axis_distance(-100, 100, 150))
        self.assertEqual(50, sbt.axis_distance(-100, 100, -150))


class Distance(unittest.TestCase):
    def test_an_unconstrained_axis_is_free(self):
        # This is the whole asymmetry: a band leaving five axes open pays
        # nothing on them however far the sample is.
        self.assertEqual(0, sbt.distance(cube(humidity=(-0.1, 0.1)),
                                         point(humidity=0.0, temperature=1.9)))

    def test_the_offset_is_paid_unconditionally(self):
        # Slot 6's noise value is a hard zero in the game, so offset squared is
        # added no matter where the sample sits. It is the only term a band
        # cannot avoid, which is why it is the only knob that breaks the tie.
        inside = point(humidity=0.0)
        self.assertEqual(0, sbt.distance(cube(humidity=(-0.1, 0.1)), inside))
        self.assertEqual(sbt.fx(0.1) ** 2,
                         sbt.distance(cube(0.1, humidity=(-0.1, 0.1)), inside))

    def test_axes_sum_in_squares(self):
        c = cube(temperature=(0.0, 0.0), humidity=(0.0, 0.0))
        self.assertEqual(sbt.fx(0.3) ** 2 + sbt.fx(0.4) ** 2,
                         sbt.distance(c, point(temperature=0.3, humidity=0.4)))


class Score(unittest.TestCase):
    def test_the_nearer_cell_wins(self):
        table = [(cube(temperature=(0.0, 0.0)), "near"),
                 (cube(temperature=(1.0, 1.0)), "far")]
        held, _ = sbt.score(table, [point(temperature=0.1)])
        self.assertEqual({"near": 1}, held)

    def test_a_zero_distance_band_beats_a_constrained_native(self):
        # The ratchet, in miniature: the band is inside on its one axis and
        # free on the rest; the native misses on one and pays for it.
        table = [(cube(humidity=(-0.5, 0.5)), "band"),
                 (cube(humidity=(-0.5, 0.5), temperature=(0.5, 0.6)), "native")]
        held, _ = sbt.score(table, [point(humidity=0.0, temperature=0.0)])
        self.assertEqual({"band": 1}, held)

    def test_an_offset_lets_the_exact_native_win(self):
        table = [(cube(0.1, humidity=(-0.5, 0.5)), "band"),
                 (cube(humidity=(-0.5, 0.5), temperature=(-0.1, 0.1)), "native")]
        held, _ = sbt.score(table, [point(humidity=0.0, temperature=0.0)])
        self.assertEqual({"native": 1}, held)

    def test_ties_are_counted_not_hidden(self):
        table = [(cube(temperature=(0.0, 0.0)), "a"),
                 (cube(temperature=(0.0, 0.0)), "b")]
        held, ties = sbt.score(table, [point(temperature=0.0)])
        self.assertEqual(1, ties)
        self.assertEqual({"a": 1}, held, "first wins, and the tie is reported")


class BandOffset(unittest.TestCase):
    def test_only_band_biomes_are_touched(self):
        table = [(cube(), "band"), (cube(), "native")]
        out = dict((b, c["offset"]) for c, b in
                   sbt.with_band_offset(table, {"band"}, 0.1))
        self.assertEqual(sbt.fx(0.1), out["band"])
        self.assertEqual(0, out["native"])

    def test_the_original_table_is_not_mutated(self):
        table = [(cube(), "band")]
        sbt.with_band_offset(table, {"band"}, 0.1)
        self.assertEqual(0, table[0][0]["offset"])


class Samples(unittest.TestCase):
    DOC = {"perDimension": {"d": {"samples": {
        "temp": [0.0] * 9, "humid": [0.0] * 9, "cont": [0.0] * 9,
        "eros": [0.0] * 9, "weird": [float(i) / 10 for i in range(9)]}}}}

    def test_the_square_keeps_every_point(self):
        self.assertEqual(9, len(sbt.samples(self.DOC, "d")))

    def test_the_disc_drops_the_corners(self):
        # A 3x3 grid over [-1,1]^2 has four corners at radius sqrt(2) > 1.
        self.assertEqual(5, len(sbt.samples(self.DOC, "d", disc=True)))

    def test_depth_is_never_read_from_the_samples(self):
        # sample-noise hard-codes y=0, so the file carries no depth column and
        # a depth of 0 is the only honest value ([T58]).
        self.assertEqual(0, sbt.samples(self.DOC, "d")[0][sbt.AXES.index("depth")])


if __name__ == "__main__":
    unittest.main()
