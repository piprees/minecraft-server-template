package com.customdimensions.dimension;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The site budget is a ceiling on repetition, never a target. See T52. */
class RepetitionCeilingTest {

    @Test
    void everyProfileSharesTheCeiling() {
        for (NoiseProfile p : java.util.List.of(NoiseProfile.SPARSE, NoiseProfile.NATURAL,
                NoiseProfile.DENSE, NoiseProfile.PACKED, NoiseProfile.CLUSTER)) {
            assertEquals(NoiseProfile.REPETITION_CEILING, p.repetitionBudget(), p.id());
        }
    }

    @Test
    void aLargerExclusionNeverYieldsMoreSites() {
        // The correction only ever raises exclusion, so it can only remove
        // sites. A dimension already under the ceiling must not be inflated.
        int small = new NoiseFieldIndex(0xC0FFEEL, NoiseProfile.NATURAL, 4, null,
                96, 0, 0, 0).positions().size();
        int large = new NoiseFieldIndex(0xC0FFEEL, NoiseProfile.NATURAL, 12, null,
                96, 0, 0, 0).positions().size();
        assertTrue(large < small,
                "raising exclusion must reduce sites; got " + small + " -> " + large);
    }
}
