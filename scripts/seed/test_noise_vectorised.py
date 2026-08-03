#!/usr/bin/env python3
"""Tests for the vectorised noise placement path.

`noise_placement` has two implementations of the same maths: a scalar one
that is the reference (and what the Java-parity fixtures in
test_noise_parity.py pin) and a numpy one that is ~40x faster. The whole
value of the fast path rests on it being BIT-IDENTICAL, not close — a
placement that moves by one chunk is a different world.

So every test here is an equality test between the two, never a tolerance.
The module is skipped wholesale when numpy is absent, because then only the
scalar path exists and there is nothing to compare.

The geometry memo (noise_placement.geometry) is exercised here too: it is
the other change that could silently alter a result, by serving one
dimension's layout to another.
"""

import sys
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO / "scripts/seed"))

import noise_placement as npl  # noqa: E402

HAVE_NUMPY = npl.HAVE_NUMPY
if HAVE_NUMPY:
    import numpy as np


def scalar_index(*args, **kwargs):
    """Build a NoiseFieldIndex with the vectorised path disabled."""
    saved = npl.HAVE_NUMPY
    npl.HAVE_NUMPY = False
    try:
        return npl.NoiseFieldIndex(*args, **kwargs)
    finally:
        npl.HAVE_NUMPY = saved


def vector_index(*args, **kwargs):
    saved = npl.HAVE_NUMPY
    npl.HAVE_NUMPY = True
    try:
        return npl.NoiseFieldIndex(*args, **kwargs)
    finally:
        npl.HAVE_NUMPY = saved


#: Small enough to run in a test suite, large enough that every code path is
#: reached: multiple exclusion classes, cluster gating, and a radial curve
#: with a hard zero.
EVEN = [1.0] * 10
INNER = [1.6, 1.5, 1.3, 1.1, 0.9, 0.7, 0.5, 0.35, 0.25, 0.2]
OUTER = [0.2, 0.25, 0.35, 0.5, 0.7, 0.9, 1.1, 1.3, 1.5, 1.6]
ZEROED = [0.0, 0.0, 0.5, 1.0, 1.5, 1.5, 1.0, 0.5, 0.0, 0.0]

CASES = [
    ("natural/even/e3", npl.NATURAL, 3, EVEN, 48),
    ("natural/inner/e6", npl.NATURAL, 6, INNER, 64),
    ("sparse/outer/e20", npl.SPARSE, 20, OUTER, 96),
    ("dense/even/e4", npl.DENSE, 4, EVEN, 56),
    ("cluster/even/e5", npl.CLUSTER, 5, EVEN, 72),
    ("natural/zeroed/e4", npl.NATURAL, 4, ZEROED, 64),
    ("sparse/none/e12", npl.SPARSE, 12, None, 80),
]

SEEDS = [0xC0FFEE, 1, -1, 4412011349903857317, -7719283746183746, 0]


@unittest.skipUnless(HAVE_NUMPY, "numpy not installed; only the scalar path exists")
class TestKernelParity(unittest.TestCase):
    """The two leaf kernels, compared sample for sample."""

    def test_sample_row_is_bit_identical(self):
        noise = npl.StructureNoise(0xDECAFBAD)
        freq = npl.NATURAL.frequency * npl.frequency_scale(256)
        for cz in (-257, -1, 0, 1, 129, 4096):
            with self.subTest(chunk_z=cz):
                xs = list(range(-300, 301))
                ref = npl.sample_row(noise.permutation, xs, cz, freq)
                got = npl.sample_row_np(
                    noise.permutation_np,
                    np.array(xs, dtype=np.float64), cz, freq)
                # Exact equality, not assertAlmostEqual: a single ulp of
                # difference is a different world.
                self.assertEqual(np.count_nonzero(np.array(ref) != got), 0)

    def test_priority_is_bit_identical(self):
        for seed in SEEDS:
            for cz in (-4096, -1, 0, 7, 1024):
                with self.subTest(seed=seed, chunk_z=cz):
                    xs = list(range(-200, 201))
                    ref = np.array([npl.priority(seed, x, cz) for x in xs],
                                   dtype=np.uint64)
                    got = npl.priority_np(seed, np.array(xs, dtype=np.int64), cz)
                    self.assertEqual(np.count_nonzero(ref != got), 0)

    def test_chunk_keys_match_chunk_pos_to_long(self):
        xs = [-70000, -256, -1, 0, 1, 255, 70000]
        zs = [-70000, -3, 0, 5, 70000, -1, 2]
        ref = [npl.chunk_pos_to_long(x, z) for x, z in zip(xs, zs)]
        got = npl._chunk_keys_np(np.array(xs, dtype=np.int64),
                                 np.array(zs, dtype=np.int64))
        self.assertEqual(ref, got.tolist())


@unittest.skipUnless(HAVE_NUMPY, "numpy not installed; only the scalar path exists")
class TestFieldParity(unittest.TestCase):
    """Whole placement sets, compared element for element."""

    def test_positions_identical_across_profiles_and_seeds(self):
        for label, profile, excl, radial, radius in CASES:
            for seed in SEEDS:
                with self.subTest(case=label, seed=seed):
                    a = scalar_index(seed, profile, excl, radial, radius)
                    b = vector_index(seed, profile, excl, radial, radius)
                    # positions() is ORDERED; comparing lists pins the sort
                    # as well as the contents.
                    self.assertEqual(a.positions(), b.positions())
                    self.assertEqual(a.spacing, b.spacing)
                    self.assertEqual(a.by_region, b.by_region)

    def test_identical_with_a_non_zero_anchor(self):
        """The mod always anchors at (0, 0), but the constructor accepts an
        offset and the two paths index it differently — one adds it to the
        row array, the other to each chunk."""
        for anchor in ((16, -32), (-129, 400)):
            with self.subTest(anchor=anchor):
                a = scalar_index(0xC0FFEE, npl.NATURAL, 4, INNER, 56,
                                 anchor[0], anchor[1])
                b = vector_index(0xC0FFEE, npl.NATURAL, 4, INNER, 56,
                                 anchor[0], anchor[1])
                self.assertEqual(a.positions(), b.positions())

    def test_degenerate_radii(self):
        for radius in (0, 1, 2):
            with self.subTest(radius=radius):
                a = scalar_index(0xC0FFEE, npl.NATURAL, 3, EVEN, radius)
                b = vector_index(0xC0FFEE, npl.NATURAL, 3, EVEN, radius)
                self.assertEqual(a.positions(), b.positions())

    def test_fully_suppressed_curve_places_nothing(self):
        a = scalar_index(0xC0FFEE, npl.NATURAL, 3, [0.0] * 10, 48)
        b = vector_index(0xC0FFEE, npl.NATURAL, 3, [0.0] * 10, 48)
        self.assertEqual(a.positions(), [])
        self.assertEqual(b.positions(), [])


@unittest.skipUnless(HAVE_NUMPY, "numpy not installed; only the scalar path exists")
class TestRankTies(unittest.TestCase):
    """Ties are COMMON and ANTIPODAL, and the fast path must break them the
    same way the scalar path does.

    `priority` mixes `seed ^ (cx*PX) ^ (cz*PZ)` through a bijection. Negating
    a value leaves every bit at and below its lowest set bit alone and flips
    the rest, so when cx and cz share a trailing-zero count, (cx, cz) and
    (-cx, -cz) yield the identical XOR and the identical rank. Measured
    25,699 duplicates among 836,056 eligible chunks in one overworld census.

    The vectorised select originally refused any tie and fell back to scalar,
    which silently disabled it for every real dimension.
    """

    def test_ties_are_antipodal_pairs(self):
        """Pins both the rate and the mechanism.

        A sub-sampled grid hides this — the collision needs BOTH (cx, cz) and
        (-cx, -cz) present, so stepping over rows makes the ranks look
        perfectly unique and the tie-break path untested.
        """
        r = 32
        xs = np.arange(-r, r + 1, dtype=np.int64)
        rows = [npl.priority_np(0xC0FFEE, xs, dz) for dz in range(-r, r + 1)]
        allr = np.concatenate(rows)
        uniq, counts = np.unique(allr, return_counts=True)
        self.assertLess(uniq.size, allr.size,
                        "expected structural rank collisions; if this passes "
                        "cleanly the tie-break path is untested")
        side = 2 * r + 1
        for value in uniq[counts > 1][:20]:
            idx = np.nonzero(allr == value)[0]
            cells = [(int(i % side) - r, int(i // side) - r) for i in idx]
            (ax, az), (bx, bz) = cells[0], cells[1]
            self.assertEqual((ax, az), (-bx, -bz),
                             f"tie {cells[:2]} is not an antipodal pair")
            self.assertEqual(
                (ax & -ax).bit_length(), (az & -az).bit_length(),
                "antipodal ranks should only collide when the two "
                "coordinates share a trailing-zero count")

    def test_antipodal_tie_resolves_identically_end_to_end(self):
        """A whole field whose origin region carries real ties.

        Antipodal cells sit 2*sqrt(cx^2 + cz^2) apart, so a small radius with
        a large exclusion is what forces a tied pair to compete.
        """
        for seed in (0xC0FFEE, 7, -3):
            with self.subTest(seed=seed):
                a = scalar_index(seed, npl.DENSE, 12, EVEN, 24)
                b = vector_index(seed, npl.DENSE, 12, EVEN, 24)
                self.assertEqual(a.positions(), b.positions())

    def test_forced_tie_inside_an_exclusion_disc(self):
        """Two eligible chunks with the SAME rank, close enough to compete.

        Built by hand because the natural rate of a tie that also falls
        inside one exclusion disc is low — this is the case the chunk-key
        rule exists for, and the only way to exercise it deterministically.
        """
        geom = npl.geometry(EVEN, 8, 3)
        side = geom["side"]
        r = geom["radius"]
        ranks = np.zeros((side, side), dtype=np.uint64)
        cells = [(0, 0), (1, 0), (-4, 3), (5, -5)]
        for dx, dz in cells:
            ranks[dz + r, dx + r] = np.uint64(12345)      # all tied
        dxs = np.array([c[0] for c in cells], dtype=np.int64)
        dzs = np.array([c[1] for c in cells], dtype=np.int64)

        got = npl._select_np(geom, ranks, dxs, dzs, 0, 0)
        self.assertIsNotNone(got, "tie must be resolved, not refused")
        kept_np = sorted(zip(got[0].tolist(), got[1].tolist()))

        eligible = bytearray(side * side)
        rank_list = ranks.reshape(-1).tolist()
        for dx, dz in cells:
            eligible[(dz + r) * side + (dx + r)] = 1
        kept_scalar = sorted(
            npl.NoiseFieldIndex._select_scalar(
                geom, eligible, rank_list, cells, 0, 0))
        self.assertEqual(kept_np, kept_scalar)

    def test_zero_rank_falls_back_rather_than_guessing(self):
        """A real rank of 0 is indistinguishable from `_disc_max`'s padding,
        so the vectorised path must refuse it instead of answering wrongly."""
        geom = npl.geometry(EVEN, 6, 3)
        side, r = geom["side"], geom["radius"]
        ranks = np.zeros((side, side), dtype=np.uint64)
        ranks[r, r] = np.uint64(0)          # eligible but zero-ranked
        got = npl._select_np(geom, ranks,
                             np.array([0], dtype=np.int64),
                             np.array([0], dtype=np.int64), 0, 0)
        self.assertIsNone(got)


class TestGeometryMemo(unittest.TestCase):
    """The memo must be a pure cache — same answer, just fewer rebuilds."""

    def test_repeated_builds_agree(self):
        a = npl.geometry(INNER, 40, 5)
        b = npl.geometry(INNER, 40, 5)
        self.assertIs(a, b)
        self.assertEqual(a["rows"], b["rows"])

    def test_distinct_keys_do_not_collide(self):
        g1 = npl.geometry(INNER, 40, 5)
        g2 = npl.geometry(OUTER, 40, 5)
        g3 = npl.geometry(INNER, 40, 6)
        g4 = npl.geometry(INNER, 41, 5)
        self.assertNotEqual(g1["excl_of"], g2["excl_of"])
        self.assertNotEqual(g1["distinct_excls"], g3["distinct_excls"])
        self.assertNotEqual(g1["side"], g4["side"])

    def test_eviction_does_not_change_results(self):
        """Overflow the memo, then rebuild the first entry and compare."""
        before = npl.geometry(INNER, 32, 4)
        rows_before = [(dz, list(dx)) for dz, dx in before["rows"]]
        excl_before = list(before["excl_of"])
        for i in range(npl._GEOMETRY_MAX + 3):
            npl.geometry(EVEN, 24 + i, 3)
        after = npl.geometry(INNER, 32, 4)
        self.assertEqual([(dz, list(dx)) for dz, dx in after["rows"]],
                         rows_before)
        self.assertEqual(list(after["excl_of"]), excl_before)

    def test_memo_is_bounded(self):
        for i in range(npl._GEOMETRY_MAX * 2):
            npl.geometry(EVEN, 8 + i, 3)
        self.assertLessEqual(len(npl._GEOMETRY), npl._GEOMETRY_MAX)

    def test_suppressed_cells_are_excluded_from_rows(self):
        """A zero radial weight is a hard suppression, and `rows` is where
        that filter now lives — if it leaked, those chunks would be sampled
        and could place."""
        geom = npl.geometry([0.0, 0.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 0.0, 0.0],
                            50, 4)
        r = geom["radius"]
        for dz, live in geom["rows"]:
            for dx in live:
                frac = (dx * dx + dz * dz) ** 0.5 / r
                self.assertGreater(
                    npl.radial_weight(
                        [0.0, 0.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 0.0, 0.0],
                        (dx * dx + dz * dz) ** 0.5, r),
                    0.0, f"suppressed chunk at fraction {frac:.3f} is live")


if __name__ == "__main__":
    unittest.main()
