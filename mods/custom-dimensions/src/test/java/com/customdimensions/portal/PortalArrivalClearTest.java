package com.customdimensions.portal;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which cells arrival construction empties. A portal is a frame with nothing
 * inside it, so the interior belongs in that set alongside the egress pocket —
 * {@code egressCells} excludes interior cells by construction, so it cannot
 * carry the whole job on its own.
 */
class PortalArrivalClearTest {

    private static final int DEPTH = 2;

    @Test
    void emptiesEveryInteriorCell() {
        Set<BlockPos> interior = PortalSite.standardInterior(10, 60, -5, Direction.Axis.X);

        Set<BlockPos> emptied = PortalSite.arrivalCells(interior, Direction.Axis.X, DEPTH);

        assertTrue(emptied.containsAll(interior),
                "a frame placed round cells nobody emptied is a frame full of water");
    }

    @Test
    void stillEmptiesTheEgressPocket() {
        Set<BlockPos> interior = PortalSite.standardInterior(10, 60, -5, Direction.Axis.X);

        Set<BlockPos> emptied = PortalSite.arrivalCells(interior, Direction.Axis.X, DEPTH);

        assertTrue(emptied.containsAll(PortalSite.egressCells(interior, Direction.Axis.X, DEPTH)),
                "stepping out of the portal is not negotiable");
    }

    @Test
    void egressAloneNeverCoversTheInterior() {
        Set<BlockPos> interior = PortalSite.standardInterior(0, 64, 0, Direction.Axis.Z);

        Set<BlockPos> egress = PortalSite.egressCells(interior, Direction.Axis.Z, DEPTH);

        assertFalse(egress.stream().anyMatch(interior::contains),
                "why arrivalCells exists: the egress carve is defined to skip these");
    }

    @Test
    void horizontalArrivalPadIsEmptiedToo() {
        Set<BlockPos> interior = PortalSite.standardInterior(0, 70, 0, Direction.Axis.Y);

        Set<BlockPos> emptied = PortalSite.arrivalCells(interior, Direction.Axis.Y, DEPTH);

        assertTrue(emptied.containsAll(interior));
    }

    @Test
    void anEmptyInteriorEmptiesNothing() {
        assertTrue(PortalSite.arrivalCells(Set.of(), Direction.Axis.X, DEPTH).isEmpty());
        assertTrue(PortalSite.arrivalCells(null, Direction.Axis.X, DEPTH).isEmpty());
    }
}
