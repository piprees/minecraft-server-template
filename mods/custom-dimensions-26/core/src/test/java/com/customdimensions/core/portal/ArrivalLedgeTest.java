package com.customdimensions.core.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.customdimensions.core.BlockPos;
import com.customdimensions.core.Direction;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ArrivalLedgeTest {
    @Test
    void anXOpeningStandsOnFootingInFrontAndBehind() {
        Set<BlockPos> interior = ArrivalSite.standardInterior(-6797, 64, -6000, Direction.Axis.X);
        Set<BlockPos> ledge = ArrivalSite.ledge(interior, Direction.Axis.X);
        assertEquals(Set.of(new BlockPos(-6797, 63, -6001), new BlockPos(-6796, 63, -6001),
                new BlockPos(-6797, 63, -5999), new BlockPos(-6796, 63, -5999)), ledge);
    }

    @Test
    void aZOpeningStandsOnFootingEitherSideOnX() {
        Set<BlockPos> interior = ArrivalSite.standardInterior(10, 70, 20, Direction.Axis.Z);
        Set<BlockPos> ledge = ArrivalSite.ledge(interior, Direction.Axis.Z);
        assertEquals(4, ledge.size());
        assertTrue(ledge.stream().allMatch(p -> p.getY() == 69 && (p.x() == 9 || p.x() == 11)));
    }

    @Test
    void aFlatOpeningNeedsNone() {
        assertTrue(ArrivalSite.ledge(ArrivalSite.standardInterior(0, 64, 0, Direction.Axis.Y), Direction.Axis.Y).isEmpty());
    }
}
