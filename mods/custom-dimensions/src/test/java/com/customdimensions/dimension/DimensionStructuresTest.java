package com.customdimensions.dimension;

import com.customdimensions.config.DimensionConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DimensionStructuresTest {

    /**
     * Derived shrine spacing must stay bit-identical to the mirror in
     * scripts/seed/fast_roller.py (roller parity): clamp(radius/32,
     * 12, 48), separation = spacing / 2. The expected values below are
     * duplicated in test_dimension_profiles.py — change both together.
     */
    @Test
    void derivedShrineSpacingMatchesRollerMirror() {
        assertSpacing(256, 12, 6);     // small pocket clamps up to 12
        assertSpacing(384, 12, 6);     // 384/32 = 12 exactly
        assertSpacing(512, 16, 8);
        assertSpacing(1024, 32, 16);
        assertSpacing(1536, 48, 24);   // 48 exactly
        assertSpacing(8192, 48, 24);   // default border clamps down to 48
    }

    private static void assertSpacing(int radius, int spacing, int separation) {
        DimensionConfig.SpacingOverride out = DimensionStructures.derivedShrineSpacing(radius);
        assertEquals(spacing, out.spacing, "spacing for radius " + radius);
        assertEquals(separation, out.separation, "separation for radius " + radius);
    }
}
