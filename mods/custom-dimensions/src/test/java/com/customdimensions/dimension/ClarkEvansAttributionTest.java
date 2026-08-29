package com.customdimensions.dimension;

import net.minecraft.util.math.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which input moves the dispersion statistic, isolated one at a time.
 * Clark-Evans is meanNN / (0.5 * sqrt(area / n)): 1.0 random, below clustered.
 */
class ClarkEvansAttributionTest {

    private static final long SEED = 0xC0FFEEL;
    private static final int RADIUS = 192;

    private static final NoiseProfile OLD_NATURAL = new NoiseProfile.Simple(
            "natural-old", 0.025, 0.68, 2.0);

    private static double clarkEvans(List<ChunkPos> pos, int radiusChunks) {
        int n = pos.size();
        if (n < 2) {
            return Double.NaN;
        }
        double total = 0;
        for (ChunkPos a : pos) {
            double best = Double.MAX_VALUE;
            for (ChunkPos b : pos) {
                if (a == b) {
                    continue;
                }
                double dx = a.x - (double) b.x;
                double dz = a.z - (double) b.z;
                best = Math.min(best, Math.sqrt(dx * dx + dz * dz));
            }
            total += best;
        }
        double area = Math.PI * radiusChunks * (double) radiusChunks;
        return (total / n) / (0.5 * Math.sqrt(area / n));
    }

    private static List<ChunkPos> field(NoiseProfile profile,
                                        NoiseFieldIndex.Footprints fp,
                                        NoiseFieldIndex.Occupants occ) {
        return new NoiseFieldIndex(SEED, profile, 3, null, RADIUS, 0, 0, 0, fp, occ)
                .positions();
    }

    private static NoiseFieldIndex.Footprints varyingSizes() {
        return (x, z) -> 0.6 + 1.9 * (Math.floorMod(x * 73856093 ^ z * 19349663, 256) / 255.0);
    }

    private static NoiseFieldIndex.Occupants varyingOccupants() {
        return (x, z) -> Math.floorMod(x * 2654435761L + z * 40503L, 260);
    }

    @Test
    void raisingTheThresholdIsWhatClustersTheField() {
        double before = clarkEvans(field(OLD_NATURAL, null, null), RADIUS);
        double after = clarkEvans(field(NoiseProfile.NATURAL, null, null), RADIUS);
        assertTrue(after < before,
                "threshold 0.68 -> 0.82 must lower Clark-Evans; got "
                + before + " -> " + after);
        assertTrue(after < 1.0,
                "the raised threshold alone should read clustered; got " + after);
    }

    @Test
    void footprintsAloneDoNotClusterTheField() {
        double plain = clarkEvans(field(NoiseProfile.NATURAL, null, null), RADIUS);
        double sized = clarkEvans(field(NoiseProfile.NATURAL, varyingSizes(), null), RADIUS);
        assertTrue(sized > plain,
                "per-site footprints enforce separation, so they must RAISE Clark-Evans; got "
                + plain + " -> " + sized);
    }

    @Test
    void theRepetitionPassAloneBarelyMovesIt() {
        double sized = clarkEvans(field(NoiseProfile.NATURAL, varyingSizes(), null), RADIUS);
        double both = clarkEvans(
                field(NoiseProfile.NATURAL, varyingSizes(), varyingOccupants()), RADIUS);
        assertTrue(Math.abs(both - sized) < 0.25,
                "a varied occupant field should barely move dispersion; got "
                + sized + " -> " + both);
    }

    @Test
    void theThresholdDominatesTheCombination() {
        double oldAll = clarkEvans(
                field(OLD_NATURAL, varyingSizes(), varyingOccupants()), RADIUS);
        double newAll = clarkEvans(
                field(NoiseProfile.NATURAL, varyingSizes(), varyingOccupants()), RADIUS);
        double thresholdOnly = clarkEvans(field(NoiseProfile.NATURAL, null, null), RADIUS);
        double baseline = clarkEvans(field(OLD_NATURAL, null, null), RADIUS);

        double thresholdEffect = Math.abs(thresholdOnly - baseline);
        double everythingElse = Math.abs(newAll - thresholdOnly);
        assertTrue(thresholdEffect > everythingElse,
                "threshold effect " + thresholdEffect
                + " should exceed the rest " + everythingElse
                + " (old-all " + oldAll + ", new-all " + newAll + ")");
    }
}
