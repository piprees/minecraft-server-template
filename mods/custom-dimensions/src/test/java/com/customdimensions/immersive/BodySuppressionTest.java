package com.customdimensions.immersive;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 8c — a fake block must never be painted into a body.
 *
 * <p>Client-side collision against a fake block is inherent to the
 * server-side approach: the client will not walk through a block it believes
 * is real, and the server has nothing there to mine. A fake block inside
 * somebody is therefore an unmineable wall only they can see, and being
 * unable to move loses the player entirely.
 *
 * <p>These pin {@link ProjectionVolume#occupiedCells}, the pure decision
 * behind the invariant. The bookkeeping half — that a cell suppressed
 * because somebody walked into it is RESTORED and dropped from
 * {@code lastSent} on the same pass — lives in {@code PlayerProjectionState}
 * and is asserted in game via the "suppressed by bodies" count.
 */
class BodySuppressionTest {

    /** A standing player: 0.6 wide, 1.8 tall, centred on (x, z). */
    private static Set<BlockPos> standing(double x, double y, double z, int pad) {
        return ProjectionVolume.occupiedCells(
                x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3, pad);
    }

    @Test
    void coversTheCellTheFeetAreIn() {
        Set<BlockPos> cells = standing(10.5, 64.0, 20.5, 0);

        assertTrue(cells.contains(new BlockPos(10, 64, 20)), "feet cell");
    }

    @Test
    void coversTheCellTheHeadIsIn() {
        Set<BlockPos> cells = standing(10.5, 64.0, 20.5, 0);

        assertTrue(cells.contains(new BlockPos(10, 65, 20)),
                "a 1.8-tall body occupies two rows");
    }

    @Test
    void paddingCoversTheStepTakenBetweenPasses() {
        // The refresh interval is 4 ticks by default; with no padding the
        // projection paints into the cell the player is walking into, which
        // collides exactly the same.
        Set<BlockPos> unpadded = standing(10.5, 64.0, 20.5, 0);
        Set<BlockPos> padded = standing(10.5, 64.0, 20.5, 1);

        assertFalse(unpadded.contains(new BlockPos(11, 64, 20)));
        assertTrue(padded.contains(new BlockPos(11, 64, 20)), "one step east");
        assertTrue(padded.contains(new BlockPos(9, 64, 20)), "one step west");
        assertTrue(padded.contains(new BlockPos(10, 64, 21)), "one step south");
        assertTrue(padded.contains(new BlockPos(10, 63, 20)), "the cell below");
    }

    @Test
    void aBodyStraddlingABlockBoundaryClaimsBothCells() {
        // Standing exactly on a boundary: the box spans x=9.7..10.3, so both
        // columns are occupied and both must be suppressed.
        Set<BlockPos> cells = ProjectionVolume.occupiedCells(
                9.7, 64.0, 20.5, 10.3, 65.8, 21.5, 0);

        assertTrue(cells.contains(new BlockPos(9, 64, 20)));
        assertTrue(cells.contains(new BlockPos(10, 64, 20)));
    }

    @Test
    void aBoxEndingExactlyOnABoundaryDoesNotClaimTheNextCell() {
        // Regression guard on the ceil-minus-one rule: a naive floor(max)
        // would widen every body by a full cell in each axis, for free, on
        // every pass.
        Set<BlockPos> cells = ProjectionVolume.occupiedCells(
                10.0, 64.0, 20.0, 11.0, 65.0, 21.0, 0);

        assertTrue(cells.contains(new BlockPos(10, 64, 20)));
        assertFalse(cells.contains(new BlockPos(11, 64, 20)),
                "maxX of exactly 11.0 must not claim column 11");
        assertFalse(cells.contains(new BlockPos(10, 65, 20)));
        assertFalse(cells.contains(new BlockPos(10, 64, 21)));
    }

    @Test
    void suppressionSetIsNeverEmptyForARealBody() {
        // A degenerate empty set would silently disable the invariant — the
        // exact "silent absence" failure mode this feature keeps producing.
        assertFalse(standing(0.5, 0.0, 0.5, 0).isEmpty());
        assertFalse(standing(-100.5, 250.0, -3624.5, 1).isEmpty());
    }

    @Test
    void worksAtNegativeCoordinates() {
        // floor() vs truncation: at negative coordinates a cast-to-int would
        // pick the wrong cell and leave the player's actual cell paintable.
        Set<BlockPos> cells = standing(-10.5, 64.0, -20.5, 0);

        assertTrue(cells.contains(new BlockPos(-11, 64, -21)),
                "floor(-10.8) is -11, not -10");
    }

    @Test
    void theViewersOwnBodyIsSuppressedToo() {
        // Not a special case in the code — every player in the world is
        // folded into one set — but it is the case that matters most, since
        // the viewer is the one standing in their own preview.
        Set<BlockPos> viewer = standing(1887.5, 248.0, -3624.5, 1);

        assertTrue(viewer.contains(new BlockPos(1887, 248, -3624)),
                "the ember-fields aperture cell the reporter stood in");
    }

    @Test
    void paddingScalesWithTheRequestedRadius() {
        Set<BlockPos> pad2 = standing(0.5, 0.0, 0.5, 2);

        assertTrue(pad2.contains(new BlockPos(2, 0, 0)));
        assertTrue(pad2.contains(new BlockPos(-2, 0, 0)));
        assertFalse(pad2.contains(new BlockPos(3, 0, 0)));
    }
}
