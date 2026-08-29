package com.customdimensions.dimension;

import org.junit.jupiter.api.Test;

/**
 * The field's value distribution, and what fraction of chunks each profile's
 * threshold admits BEFORE exclusion thinning. Boot-line site counts are
 * post-exclusion and cannot answer this.
 *
 * A measurement rig: it prints and asserts nothing, deliberately. There is no
 * agreed target admission fraction to assert against yet.
 */
class NoiseDistributionTest {

    private static final int RADIUS_CHUNKS = 512;

    @Test
    void reportDistributionAndThresholdAdmission() {
        long seed = 1419601956218173845L;
        double scale = NoiseProfile.frequencyScale(RADIUS_CHUNKS);

        for (NoiseProfile p : new NoiseProfile[]{
                NoiseProfile.SPARSE, NoiseProfile.NATURAL, NoiseProfile.DENSE}) {
            int[] hist = new int[20];
            long n = 0;
            long above = 0;
            double freq = p.frequency() * scale;
            StructureNoise sampler = NoiseProfile.sampler(seed);
            for (int cx = -RADIUS_CHUNKS; cx <= RADIUS_CHUNKS; cx += 3) {
                for (int cz = -RADIUS_CHUNKS; cz <= RADIUS_CHUNKS; cz += 3) {
                    if (cx * cx + cz * cz > RADIUS_CHUNKS * RADIUS_CHUNKS) {
                        continue;
                    }
                    double v = sampler.sampleChunk(cx, cz, freq);
                    hist[Math.min(19, (int) (v * 20))]++;
                    n++;
                    if (v > p.threshold()) {
                        above++;
                    }
                }
            }
            System.out.printf("%n%-8s freq=%.4f thresh=%.2f  admitted=%.2f%% of %d chunks%n",
                    p.id(), freq, p.threshold(), 100.0 * above / n, n);
            for (int i = 0; i < 20; i++) {
                double lo = i / 20.0;
                int bar = (int) Math.round(60.0 * hist[i] / n * 20);
                System.out.printf("  %.2f-%.2f %5.2f%% %s%s%n", lo, lo + 0.05,
                        100.0 * hist[i] / n, "#".repeat(Math.min(bar, 60)),
                        lo < p.threshold() && lo + 0.05 > p.threshold() ? "   <-- threshold" : "");
            }
        }
    }
}
