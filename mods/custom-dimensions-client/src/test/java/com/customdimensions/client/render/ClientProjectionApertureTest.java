package com.customdimensions.client.render;

import com.customdimensions.client.CompanionPayloads;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which aperture cell a light probe should stand in.
 *
 * <p>A corner cell of the opening is the one most likely to be shadowed by the
 * frame, so a probe placed there measures the frame rather than the portal.
 */
class ClientProjectionApertureTest {

    /** The nexus rig: 2 wide, 3 tall, plane z = 1500. The middle row wins. */
    @Test
    void theCentreOfTheRigIsTheMiddleRow() {
        assertEquals(new BlockPos(1500, 102, 1500), opening(2, 3).apertureCentre());
    }

    @Test
    void aSingleCellOpeningIsItsOwnCentre() {
        assertEquals(new BlockPos(1500, 101, 1500), opening(1, 1).apertureCentre());
    }

    /**
     * Order-independent: the aperture arrives as a set, so it may come
     * shuffled. An EVEN span is the case that bites — two cells sit the same
     * distance from the mean, and without a tie-break whichever came first
     * wins.
     */
    @Test
    void anEvenSpanBreaksItsTieTheSameWayInAnyOrder() {
        List<BlockPos> cells = cells(2, 3);
        assertEquals(new BlockPos(1500, 102, 1500), projection(cells).apertureCentre());
        Collections.reverse(cells);
        assertEquals(new BlockPos(1500, 102, 1500), projection(cells).apertureCentre());
    }

    /** An odd span has an exact centre and never reaches the tie-break. */
    @Test
    void anOddSpanLandsOnItsOwnCentreCell() {
        List<BlockPos> cells = cells(3, 3);
        BlockPos straight = projection(cells).apertureCentre();
        Collections.reverse(cells);
        assertEquals(straight, projection(cells).apertureCentre());
        assertEquals(new BlockPos(1501, 102, 1500), straight);
    }

    private static ClientProjection opening(int wide, int tall) {
        return projection(cells(wide, tall));
    }

    private static List<BlockPos> cells(int wide, int tall) {
        List<BlockPos> aperture = new ArrayList<>();
        for (int x = 1500; x < 1500 + wide; x++) {
            for (int y = 101; y < 101 + tall; y++) {
                aperture.add(new BlockPos(x, y, 1500));
            }
        }
        return aperture;
    }

    private static ClientProjection projection(List<BlockPos> aperture) {
        return new ClientProjection(new CompanionPayloads.Projection(
                Identifier.of("adventure", "the_crimson_nexus"),
                aperture.get(0), aperture,
                Direction.Axis.X.ordinal(), Direction.SOUTH.ordinal(),
                new BlockPos(1492, 93, 1501), 18, 19, 24,
                new int[0], new byte[0],
                -1, -1, -1, -1, -1));
    }
}
