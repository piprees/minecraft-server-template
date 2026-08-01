package com.customdimensions.dimension;

import net.minecraft.util.math.BlockBox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure kernel maths — the shapes must be sane before a fixture ever boots:
 * pedestal/skirt only ever FILL below the base, the moat only ever CARVES
 * its ring, and everything decays to zero outside its documented range.
 */
class TerrainKernelTest {

    private static final BlockBox BOX = new BlockBox(0, 64, 0, 15, 72, 15);

    private static double at(TerrainKernel k, int x, int y, int z) {
        int dx = Math.max(0, Math.max(BOX.getMinX() - x, x - BOX.getMaxX()));
        int dz = Math.max(0, Math.max(BOX.getMinZ() - z, z - BOX.getMaxZ()));
        return k.contribution(dx, dz, y, BOX.getMinY(), BOX);
    }

    @Test
    void parseKnowsTheKernelNamesAndNothingElse() {
        assertEquals(TerrainKernel.PEDESTAL, TerrainKernel.parse("pedestal"));
        assertEquals(TerrainKernel.PLATFORM_SKIRT, TerrainKernel.parse("Platform_Skirt"));
        assertEquals(TerrainKernel.MOAT, TerrainKernel.parse("moat"));
        assertNull(TerrainKernel.parse("beard_thin"));
        assertNull(TerrainKernel.parse("drain"));
        assertNull(TerrainKernel.parse(null));
    }

    @Test
    void pedestalFillsBelowTheBaseAndNeverAbove() {
        assertTrue(at(TerrainKernel.PEDESTAL, 8, 60, 8) > 0.5, "under the footprint");
        assertEquals(0.0, at(TerrainKernel.PEDESTAL, 8, 65, 8), "inside the box");
        assertEquals(0.0, at(TerrainKernel.PEDESTAL, 8, 80, 8), "above the box");
        assertEquals(0.0, at(TerrainKernel.PEDESTAL, 8, 64 - 80, 8), "beyond depth cap");
    }

    @Test
    void pedestalConeWidensWithDepth() {
        // 30 out at depth 4: far outside the cone. Same offset at depth 44:
        // inside it (radius 2 + 0.75*44 = 35).
        assertEquals(0.0, at(TerrainKernel.PEDESTAL, BOX.getMaxX() + 30, 60, 8));
        assertTrue(at(TerrainKernel.PEDESTAL, BOX.getMaxX() + 30, 20, 8) > 0.5);
    }

    @Test
    void skirtIsFlatUnderTheApronAndSlopesOut() {
        assertTrue(at(TerrainKernel.PLATFORM_SKIRT, 8, 60, 8) > 0.5, "under the footprint");
        assertTrue(at(TerrainKernel.PLATFORM_SKIRT, BOX.getMaxX() + 3, 60, 8) > 0.5,
                "inside the apron");
        assertEquals(0.0, at(TerrainKernel.PLATFORM_SKIRT, BOX.getMaxX() + 30, 62, 8),
                "far outside a shallow skirt");
        assertEquals(0.0, at(TerrainKernel.PLATFORM_SKIRT, 8, 70, 8), "inside the box");
    }

    @Test
    void moatCarvesTheRingOnly() {
        assertTrue(at(TerrainKernel.MOAT, BOX.getMaxX() + 5, 64, 8) < -0.3,
                "in the channel at base level");
        assertEquals(0.0, at(TerrainKernel.MOAT, 8, 64, 8), "footprint untouched");
        assertEquals(0.0, at(TerrainKernel.MOAT, BOX.getMaxX() + 40, 64, 8),
                "far outside the ring");
        assertEquals(0.0, at(TerrainKernel.MOAT, BOX.getMaxX() + 5, 64 - 20, 8),
                "well below the floor");
    }
}
