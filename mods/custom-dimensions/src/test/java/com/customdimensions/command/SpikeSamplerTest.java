package com.customdimensions.command;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SpikeSampler}'s pure surface: the zero-tolerance comparison
 * ({@link SpikeSampler.Sample#matches}) and the probe coordinates
 * (deterministic, off-lattice sampling points). Everything else the sampler
 * does — building a headless rig, sampling a biome or a climate point —
 * reads a live {@code MinecraftServer}'s registries and cannot run without
 * one; there is no Bootstrap-backed test harness in this suite
 * (mods/AGENTS.md's verification loop covers that against a running server
 * via {@code /customdim spike-compare}). {@code spike-compare} fails loudly
 * in its own command output on a mismatch rather than relying on an external
 * checker.
 */
class SpikeSamplerTest {

    private static SpikeSampler.Sample sample(int x, int z, String biome, Integer height,
                                              double[] climate) {
        return new SpikeSampler.Sample(x, z, biome, null, height, null, climate, null);
    }

    private static SpikeSampler.Sample absent(int x, int z, String reason) {
        return new SpikeSampler.Sample(x, z, null, reason, null, reason, null, reason);
    }

    // --- Sample.matches: zero tolerance -----------------------------------

    @Test
    void identicalSamplesMatch() {
        double[] climate = {0.1, 0.2, 0.3, 0.4, 0.5, 0.6};
        SpikeSampler.Sample a = sample(10, -20, "minecraft:plains", 64, climate.clone());
        SpikeSampler.Sample b = sample(10, -20, "minecraft:plains", 64, climate.clone());
        assertTrue(a.matches(b));
        assertTrue(b.matches(a));
    }

    @Test
    void differentCoordinatesNeverMatch() {
        double[] climate = {0.1, 0.2, 0.3, 0.4, 0.5, 0.6};
        SpikeSampler.Sample a = sample(10, -20, "minecraft:plains", 64, climate.clone());
        SpikeSampler.Sample b = sample(11, -20, "minecraft:plains", 64, climate.clone());
        assertFalse(a.matches(b));
    }

    @Test
    void aDifferentBiomeIsAMismatch() {
        double[] climate = {0.1, 0.2, 0.3, 0.4, 0.5, 0.6};
        SpikeSampler.Sample a = sample(0, 0, "minecraft:plains", 64, climate.clone());
        SpikeSampler.Sample b = sample(0, 0, "minecraft:desert", 64, climate.clone());
        assertFalse(a.matches(b));
    }

    @Test
    void aDifferentHeightIsAMismatch() {
        double[] climate = {0.1, 0.2, 0.3, 0.4, 0.5, 0.6};
        SpikeSampler.Sample a = sample(0, 0, "minecraft:plains", 64, climate.clone());
        SpikeSampler.Sample b = sample(0, 0, "minecraft:plains", 65, climate.clone());
        assertFalse(a.matches(b));
    }

    @Test
    void theTiniestClimateDifferenceIsAMismatch() {
        // Zero tolerance, bit-exact: this is the whole point of the gate — a
        // rounded or tolerant comparison would call this pair equal.
        double[] a = {0.1, 0.2, 0.3, 0.4, 0.5, 0.6};
        double[] b = {0.1, 0.2, 0.3, 0.4, 0.5, 0.6 + 1e-12};
        assertFalse(sample(0, 0, "minecraft:plains", 64, a).matches(
                sample(0, 0, "minecraft:plains", 64, b)));
    }

    @Test
    void bothSidesAbsentForTheSameReasonMatch() {
        SpikeSampler.Sample a = absent(5, 5, "no noise config: generator is FlatChunkGenerator");
        SpikeSampler.Sample b = absent(5, 5, "no noise config: generator is FlatChunkGenerator");
        assertTrue(a.matches(b));
    }

    @Test
    void oneSideAbsentAndTheOtherPresentIsAMismatch() {
        SpikeSampler.Sample a = absent(5, 5, "no noise config");
        SpikeSampler.Sample b = sample(5, 5, "minecraft:plains", 64,
                new double[] {0.1, 0.2, 0.3, 0.4, 0.5, 0.6});
        assertFalse(a.matches(b));
        assertFalse(b.matches(a));
    }

    @Test
    void differentAbsenceReasonsAreAMismatch() {
        // Both sides failed to answer, but for different reasons — that is
        // itself a divergence worth catching, not a match by virtue of both
        // being null.
        SpikeSampler.Sample a = absent(5, 5, "getHeight threw NullPointerException");
        SpikeSampler.Sample b = absent(5, 5, "climate-only rig: the terrain router is stripped");
        assertFalse(a.matches(b));
    }

    @Test
    void nullAgainstNonNullOtherIsNeverAMatch() {
        SpikeSampler.Sample a = sample(0, 0, "minecraft:plains", 64,
                new double[] {0.1, 0.2, 0.3, 0.4, 0.5, 0.6});
        assertFalse(a.matches(null));
    }

    // --- probe: deterministic, off-lattice coordinates ---------------------

    @Test
    void probeIsDeterministic() {
        assertEquals(java.util.Arrays.toString(SpikeSampler.probe(7, 4000)),
                java.util.Arrays.toString(SpikeSampler.probe(7, 4000)));
        assertEquals(java.util.Arrays.toString(SpikeSampler.probe(3, 500, 99L)),
                java.util.Arrays.toString(SpikeSampler.probe(3, 500, 99L)));
    }

    @Test
    void probeStaysWithinTheRequestedSpan() {
        for (int i = 0; i < 200; i++) {
            int[] p = SpikeSampler.probe(i, 1000);
            assertTrue(p[0] >= -1000 && p[0] < 1000, "x=" + p[0] + " out of span");
            assertTrue(p[1] >= -1000 && p[1] < 1000, "z=" + p[1] + " out of span");
        }
    }

    @Test
    void probeVariesAcrossTheIndexRatherThanRepeatingAFixedSet() {
        // A comparison that always samples the same handful of points could
        // pass with a whole region of the noise field never read.
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            int[] p = SpikeSampler.probe(i, 4000);
            seen.add(p[0] + "," + p[1]);
        }
        assertTrue(seen.size() > 40, "only " + seen.size() + " distinct points across 50 probes");
    }

    @Test
    void probeIsNotLatticeAligned() {
        // Grid-aligned probes are the classic way to make two samplers agree
        // for the wrong reason: Perlin is exactly zero on its lattice.
        int onLattice = 0;
        for (int i = 0; i < 100; i++) {
            int[] p = SpikeSampler.probe(i, 4000);
            if (p[0] % 16 == 0 && p[1] % 16 == 0) {
                onLattice++;
            }
        }
        assertTrue(onLattice < 10, onLattice + "/100 probes landed on a 16-block lattice");
    }

    @Test
    void differentSaltsGiveDifferentPoints() {
        int[] a = SpikeSampler.probe(0, 4000, 1L);
        int[] b = SpikeSampler.probe(0, 4000, 2L);
        assertFalse(a[0] == b[0] && a[1] == b[1],
                "two different salts probed the identical point");
    }

    @Test
    void theSaltFreeOverloadIsSaltZero() {
        assertEquals(java.util.Arrays.toString(SpikeSampler.probe(4, 4000)),
                java.util.Arrays.toString(SpikeSampler.probe(4, 4000, 0L)));
    }
}
