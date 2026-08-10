package com.customdimensions.command;

import org.junit.jupiter.api.Test;

import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ColumnScan} drives the fix for the_boneyard: a ceilinged
 * dimension's heightmap answers the roof, identically for every column, so
 * {@code the_boneyard}'s terrain facts were flat (relief 0, grain 0, min and
 * max height both 192 — the roof). Everything here drives the pure scan with
 * a synthetic {@code IntPredicate}, in the same style as
 * {@code PortalSiteTest}. No Minecraft runtime, no world.
 */
class ColumnScanTest {

    private static final IntPredicate ALL_OPAQUE = y -> true;
    private static final IntPredicate NONE_OPAQUE = y -> false;

    // === findRoofY ========================================================

    @Test
    void roofIsTheHighestOpaqueBlockInRange() {
        IntPredicate opaque = y -> y <= 27;

        assertEquals(27, ColumnScan.findRoofY(60, 0, opaque));
    }

    @Test
    void openColumnHasNoRoof() {
        assertEquals(ColumnScan.NONE, ColumnScan.findRoofY(60, 0, NONE_OPAQUE));
    }

    // === findRoofUndersideY ===============================================

    @Test
    void undersideSkipsTheWholeContiguousSlab() {
        // The roof is not a one-block lid — it runs from y=25 to y=60.
        // Starting a few blocks under the top would open a pocket inside the
        // rock rather than in the space below it.
        IntPredicate opaque = y -> y >= 25 && y <= 60;

        assertEquals(24, ColumnScan.findRoofUndersideY(60, 0, opaque),
                "the first open block below the slab, not a few blocks under its top");
    }

    @Test
    void undersideIsNoneWhenTheColumnIsOpaqueToTheBottomBound() {
        assertEquals(ColumnScan.NONE, ColumnScan.findRoofUndersideY(60, 0, ALL_OPAQUE));
    }

    // === findPlayableFloorY ===============================================

    @Test
    void floorIsOneAboveTheHighestOpaqueBlock() {
        IntPredicate opaque = y -> y <= 16;

        assertEquals(17, ColumnScan.findPlayableFloorY(24, 0, opaque));
    }

    @Test
    void noOpaqueBlockInRangeHasNoFloor() {
        assertEquals(ColumnScan.NONE, ColumnScan.findPlayableFloorY(24, 0, NONE_OPAQUE));
    }

    @Test
    void aOneBlockSliverIsRejectedForTheDeeperRoomBelowIt() {
        // y=20 is a single open cell with solid rock at both 19 and 21 — a
        // player standing on 19 would have their head inside the block at
        // 21. Ground at 10, with two clear cells above it, is the real floor.
        IntPredicate opaque = y -> y == 21 || y == 19 || y == 10;

        assertEquals(11, ColumnScan.findPlayableFloorY(20, 0, opaque),
                "the one-cell sliver at 19/20 must be skipped for the room with headroom at 10");
    }

    @Test
    void twoOpenCellsIsExactlyEnoughClearance() {
        // Ground at 10, open at 11 and 12, solid again at 13 — the minimum
        // that fits a player, and must not be rejected as too shallow.
        IntPredicate opaque = y -> y != 11 && y != 12;

        assertEquals(11, ColumnScan.findPlayableFloorY(20, 0, opaque));
    }

    // === scan — the four synthetic columns ================================

    @Test
    void normalOpenColumnFindsTheFloorUnderTheRoof() {
        // Roof 25..30 (contiguous to the world top), open 17..24, ground at
        // 16, more solid rock underneath down to 0 — a typical ceilinged
        // column with a real playable pocket.
        IntPredicate opaque = y -> y >= 25 || y <= 16;

        ColumnScan.Result result = ColumnScan.scan(30, 0, opaque);

        assertTrue(result.isPresent());
        assertEquals(17, result.floorY());
    }

    @Test
    void entombedColumnHasNoPlayableFloor() {
        // THE BONEYARD CASE, reproduced at the dimension's real scale:
        // netherrack packed solid from the roof all the way to the world
        // floor — no pocket anywhere in this column, so there is nothing to
        // carve INTO from this instrument's point of view.
        ColumnScan.Result result = ColumnScan.scan(200, -64, ALL_OPAQUE);

        assertFalse(result.isPresent());
        assertEquals(ColumnScan.NONE, result.floorY());
        assertTrue(result.absentReason().contains("no open interior"),
                "reason: " + result.absentReason());
    }

    @Test
    void columnSolidToBedrockHasNoPlayableFloor() {
        // A shallow band, uniformly opaque — there is not even room to
        // distinguish a roof from a floor, and the scan must still refuse
        // rather than invent one.
        ColumnScan.Result result = ColumnScan.scan(5, 0, ALL_OPAQUE);

        assertFalse(result.isPresent());
        assertEquals(ColumnScan.NONE, result.floorY());
    }

    @Test
    void openColumnWithNoRoofIsNotACeilingCase() {
        // Nothing opaque anywhere: this scan has no roof to walk under, and
        // reports that distinctly from an entombed or bottomless column.
        ColumnScan.Result result = ColumnScan.scan(30, 0, NONE_OPAQUE);

        assertFalse(result.isPresent());
        assertTrue(result.absentReason().contains("no roof"),
                "reason: " + result.absentReason());
    }

    @Test
    void waterAboveTheFloorDoesNotMoveTheFloor() {
        // Roof 25..30, water 17..20 (not opaque — translucent, not a full
        // solid cube), open 21..24, ground at 16. The floor must land on the
        // rock under the water, not on the water's own surface.
        IntPredicate opaque = y -> y >= 25 || y <= 16;

        ColumnScan.Result result = ColumnScan.scan(30, 0, opaque);

        assertTrue(result.isPresent());
        assertEquals(17, result.floorY(), "the floor sits under the water, not at its surface");
    }

    @Test
    void bottomlessOpenInteriorHasNoFloor() {
        // A roof with an open shaft beneath it that never hits solid ground
        // before the bottom bound — a floating roof over a void.
        IntPredicate opaque = y -> y >= 25;

        ColumnScan.Result result = ColumnScan.scan(30, 10, opaque);

        assertFalse(result.isPresent());
        assertTrue(result.absentReason().contains("no solid ground"),
                "reason: " + result.absentReason());
    }
}
