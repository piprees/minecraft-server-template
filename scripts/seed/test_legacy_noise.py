"""Legacy-random-source climate mirror — pinned against the live server.

The three truth points were measured on the released v5.1.0 stack via
`customdim sample-noise adventure:the_luminous_caverns <x> <z>` (world
seed -2092939776176210055, minecraft:caves settings,
legacy_random_source=true). sample-noise reports the biome layer's own
MultiNoiseSampler values quantised to 1e-4, so the tolerance below IS
the oracle's resolution, not slack.
"""

import unittest

from legacy_noise import JavaRandom, legacy_climate_samplers

SEED = -2092939776176210055

# (block_x, block_z) -> (temperature, humidity) from sample-noise.
LIVE_TRUTH = {
    (-768, -320): (-0.0084, 0.2044),
    (64, -320): (0.2262, 0.1764),
    (768, 448): (0.2018, 0.2012),
}


class JavaRandomTests(unittest.TestCase):
    def test_first_draws_match_java_util_random(self):
        # java.util.Random(42): nextInt() -> -1170105035, nextInt() -> 234785527
        rng = JavaRandom(42)
        self.assertEqual(rng.next(32), -1170105035)
        self.assertEqual(rng.next(32), 234785527)

    def test_next_int_bound_matches_java(self):
        # java.util.Random(42): nextInt(100) -> 30, then 63 (run live, not
        # recalled — temurin-21, 2026-08-05)
        rng = JavaRandom(42)
        self.assertEqual(rng.next_int(100), 30)
        self.assertEqual(rng.next_int(100), 63)

    def test_next_double_matches_java(self):
        # java.util.Random(42).nextDouble() -> 0.7275636800328681
        rng = JavaRandom(42)
        self.assertAlmostEqual(rng.next_double(), 0.7275636800328681, places=15)

    def test_bits_below_32_are_non_negative(self):
        rng = JavaRandom(-2092939776176210055)
        for _ in range(64):
            self.assertGreaterEqual(rng.next(31), 0)
            self.assertGreaterEqual(rng.next(26), 0)


class LegacyClimateTests(unittest.TestCase):
    def test_live_truth_points(self):
        temperature, vegetation = legacy_climate_samplers(SEED)
        for (bx, bz), (jt, jh) in LIVE_TRUTH.items():
            qx, qz = bx >> 2, bz >> 2
            self.assertAlmostEqual(
                temperature.sample(qx, 0.0, qz), jt, delta=5e-4,
                msg=f"temperature at block ({bx},{bz})")
            self.assertAlmostEqual(
                vegetation.sample(qx, 0.0, qz), jh, delta=5e-4,
                msg=f"vegetation at block ({bx},{bz})")

    def test_axes_are_independent_streams(self):
        temperature, vegetation = legacy_climate_samplers(SEED)
        self.assertNotAlmostEqual(
            temperature.sample(0, 0, 0), vegetation.sample(0, 0, 0), places=6)

    def test_deterministic(self):
        t1, v1 = legacy_climate_samplers(SEED)
        t2, v2 = legacy_climate_samplers(SEED)
        for q in ((0, 0), (100, -100), (-192, -80)):
            self.assertEqual(t1.sample(q[0], 0, q[1]), t2.sample(q[0], 0, q[1]))
            self.assertEqual(v1.sample(q[0], 0, q[1]), v2.sample(q[0], 0, q[1]))


if __name__ == "__main__":
    unittest.main()
