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

    // === the ceiling clip =================================================

    @Test
    void whatHangsFromTheRoofIsNotTheGround() {
        // Roof 25..30. A stalactite tip at 23, one block under the open cell
        // at 24, and the real floor at 16. The clip discards CEILING_CLIP
        // blocks below the underside, so the tip is out of range and the
        // ground below it answers.
        IntPredicate opaque = y -> y >= 25 || y == 23 || y <= 16;

        ColumnScan.Result result = ColumnScan.scan(30, 0, opaque);

        assertTrue(result.isPresent(), result.absentReason());
        assertEquals(17, result.floorY(),
                "the stalactite at 23 sits inside the clip and must not read as ground");
    }

    @Test
    void groundIsTakenWithoutRequiringRoomToStandOnIt() {
        // A one-cell gap: solid at 19, open at 20, solid at 21..30 as roof.
        // The old rule rejected this for want of headroom and carried on down
        // to 10. Over a density field that test rejected real ground, so it is
        // gone: the highest solid below the clip is the answer, headroom or
        // not.
        IntPredicate opaque = y -> y >= 21 || y == 19 || y <= 10;

        ColumnScan.Result result = ColumnScan.scan(30, 0, opaque);

        assertTrue(result.isPresent(), result.absentReason());
        assertEquals(11, result.floorY(),
                "underside 20, clip 3 puts the search at 17, so 19 is above it and 10 answers");
    }

    @Test
    void aColumnWithNothingBelowTheClipHasNoGround() {
        // Roof 25..30, open all the way down from 24. Nothing solid under the
        // clip at all, so there is no ground rather than a floor at the world
        // bottom.
        IntPredicate opaque = y -> y >= 25;

        ColumnScan.Result result = ColumnScan.scan(30, 0, opaque);

        assertFalse(result.isPresent());
        assertTrue(result.absentReason().contains("no solid ground"), result.absentReason());
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

    // === holdsFluid ========================================================
    //
    // A groundless column (this scan already refuses one above) is not the
    // same question as a WET one — an aquifer can leave a floorless column
    // air all the way down. These pin the probe scan itself; GridFactsTest
    // pins how its result feeds waterFraction.

    @Test
    void aGroundlessColumnCanStillHoldFluid() {
        // No floor anywhere (NONE_OPAQUE, so scan() itself would refuse this
        // column), but fluid sits at 30..40 regardless — an aquifer pocket
        // with nothing solid under it in this band.
        IntPredicate fluid = y -> y >= 30 && y <= 40;

        assertTrue(ColumnScan.holdsFluid(60, 0, fluid));
    }

    @Test
    void aGroundlessColumnWithNoFluidAnywhereIsDry() {
        assertFalse(ColumnScan.holdsFluid(60, 0, NONE_OPAQUE));
    }

    @Test
    void oneFluidCellAtTheBottomBoundStillCounts() {
        // The scan must cover the whole [bottom, top] band inclusive, not
        // stop short of either end.
        IntPredicate fluid = y -> y == 0;

        assertTrue(ColumnScan.holdsFluid(60, 0, fluid));
    }
}
