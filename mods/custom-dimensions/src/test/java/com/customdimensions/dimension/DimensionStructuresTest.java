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

    @Test
    void normalizedModeValidation() {
        assertNull(DimensionStructures.normalizedMode("d", null));
        assertNull(DimensionStructures.normalizedMode("d", block(null)));
        assertNull(DimensionStructures.normalizedMode("d", block("")));
        assertNull(DimensionStructures.normalizedMode("d", block("bogus"))); // warn + off
        assertEquals("allow", DimensionStructures.normalizedMode("d", block("ALLOW")));
        assertEquals("reject", DimensionStructures.normalizedMode("d", block("reject")));
        assertEquals("none", DimensionStructures.normalizedMode("d", block("none")));
    }

    private static DimensionConfig.Structures block(String mode) {
        DimensionConfig.Structures s = new DimensionConfig.Structures();
        s.mode = mode;
        return s;
    }

    /**
     * FixedStructurePlacement.Index contract (mirrored in the roller:
     * scripts/seed/structure_placement.py treats forced structures as
     * constants): membership is exact, and startFor answers with the
     * region's forced position for ANY probe chunk in that region — that is
     * what vanilla's locateRandomSpreadStructure calls while ring-probing.
     * (Index, not the placement itself: StructurePlacement's static init
     * needs Bootstrap, which unit tests deliberately avoid.)
     */
    @Test
    void fixedPlacementRegionAndMembership() {
        var index = new FixedStructurePlacement.Index(java.util.List.of(
                new net.minecraft.util.math.ChunkPos(100, -150),
                new net.minecraft.util.math.ChunkPos(-7, 3)));

        // any probe chunk in the containing 32-region resolves to the forced pos
        assertEquals(new net.minecraft.util.math.ChunkPos(100, -150),
                index.startFor(96, -160));
        assertEquals(new net.minecraft.util.math.ChunkPos(100, -150),
                index.startFor(127, -129));
        assertEquals(new net.minecraft.util.math.ChunkPos(-7, 3),
                index.startFor(-1, 0));
        // empty region: returns its origin, which is not a member
        var elsewhere = index.startFor(5000, 5000);
        assertFalse(index.isForced(elsewhere.x, elsewhere.z));
        // exact membership only
        assertTrue(index.isForced(100, -150));
        assertTrue(index.isForced(-7, 3));
        assertFalse(index.isForced(101, -150));
        assertFalse(index.isForced(0, 0));
    }

    @Test
    void fixedPlacementSharedRegionKeepsFirstForLocate() {
        var a = new net.minecraft.util.math.ChunkPos(10, 10);
        var b = new net.minecraft.util.math.ChunkPos(20, 20); // same 32-region
        var index = new FixedStructurePlacement.Index(java.util.List.of(a, b));
        assertEquals(a, index.startFor(10, 10));
        // both still generate
        assertTrue(index.isForced(10, 10));
        assertTrue(index.isForced(20, 20));
    }
}
