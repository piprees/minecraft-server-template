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
        assertEquals(TerrainKernel.DRAIN, TerrainKernel.parse("drain"));
        assertEquals(TerrainKernel.GROUND_BLEND, TerrainKernel.parse("Ground_Blend"));
        assertNull(TerrainKernel.parse("beard_thin"));
        assertNull(TerrainKernel.parse(null));
    }

    @Test
    void drainShapesNoTerrainOnlyItsDryBox() {
        assertEquals(0.0, at(TerrainKernel.DRAIN, 8, 60, 8), "density-neutral");
        var pieces = java.util.List.of(
                new TerrainKernel.Piece(BOX, TerrainKernel.DRAIN, 0),
                new TerrainKernel.Piece(BOX, TerrainKernel.MOAT, 0));
        var boxes = TerrainKernel.drainBoxes(pieces);
        assertEquals(1, boxes.size(), "only DRAIN pieces yield dry boxes");
        BlockBox dry = boxes.get(0);
        assertEquals(BOX.getMinX() - 12, dry.getMinX());
        assertEquals(BOX.getMaxX() + 12, dry.getMaxX());
        assertEquals(BOX.getMinY() - 2, dry.getMinY());
        assertEquals(BOX.getMaxY() + 6, dry.getMaxY());
        assertTrue(TerrainKernel.drainBoxes(java.util.List.of()).isEmpty());
        assertTrue(TerrainKernel.drainBoxes(null).isEmpty());
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

    /**
     * The load-bearing property, and the reason this kernel exists: the
     * final density it is added to ends in `squeeze`, which pins open sky at
     * -0.4583. A fill that stays under that can only finish terrain already
     * close to the surface; one that exceeds it packs air solid at any
     * altitude. This is what a guarantee-strength theme default got wrong.
     */
    @Test
    void groundBlendStaysInsideTheSqueezeBand() {
        // The 0.64 mul means the clamp bites at raw <= -1.5625; anything
        // below that is the same pinned floor, which is the whole point.
        double sqFloor = squeeze(-10.0);         // open sky, clamped
        assertEquals(-0.4583, sqFloor, 1e-4);
        assertEquals(sqFloor, squeeze(-1.5625), 1e-9, "the clamp, not a slope");
        double peak = at(TerrainKernel.GROUND_BLEND, 8, 63, 8);
        assertTrue(peak > 0.0, "must fill something");
        assertTrue(peak < -sqFloor,
                "fill " + peak + " must stay under " + (-sqFloor)
                + " or it makes open sky solid at any altitude");
        // Concretely: pinned sky stays air, near-surface air fills.
        assertTrue(sqFloor + peak < 0.0, "open sky must remain air");
        assertTrue(squeeze(-0.32) + peak > 0.0, "near-surface air must fill");
    }

    /** Vanilla DensityFunctionTypes.squeeze, over the final density's 0.64 mul. */
    private static double squeeze(double raw) {
        double d = Math.max(-1.0, Math.min(1.0, 0.64 * raw));
        return d / 2.0 - d * d * d / 24.0;
    }

    @Test
    void groundBlendIsShallowAndTight() {
        assertTrue(at(TerrainKernel.GROUND_BLEND, 8, 63, 8) > 0.0, "just under the base");
        assertEquals(0.0, at(TerrainKernel.GROUND_BLEND, 8, 64, 8), "at the base");
        assertEquals(0.0, at(TerrainKernel.GROUND_BLEND, 8, 70, 8), "inside the box");
        assertEquals(0.0, at(TerrainKernel.GROUND_BLEND, 8, 64 - 11, 8),
                "beyond the 10-block depth cap");
        // A pedestal at the same depth reaches 30+ blocks out; a blend must not.
        assertEquals(0.0, at(TerrainKernel.GROUND_BLEND, BOX.getMaxX() + 12, 60, 8),
                "no lateral cone");
        assertTrue(at(TerrainKernel.PEDESTAL, BOX.getMaxX() + 12, 60, 8) > 0.0,
                "control: the pedestal DOES reach there");
    }

    @Test
    void groundBlendNeverCarves() {
        for (int y = 0; y <= 90; y++) {
            for (int x = -20; x <= 40; x += 5) {
                assertTrue(at(TerrainKernel.GROUND_BLEND, x, y, 8) >= 0.0,
                        "carved at (" + x + ", " + y + ")");
            }
        }
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
