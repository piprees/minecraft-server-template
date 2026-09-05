package com.customdimensions.client.render;

import com.customdimensions.client.CompanionPayloads;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a destination actor lands in the volume the mesh was built from, and
 * which ones are worth submitting at all.
 */
class DestinationActorsTest {

    private static final double TOLERANCE = 1.0e-9;

    /**
     * Destination is source PLUS the offset, so reading a destination
     * coordinate back into source space subtracts it. Dividing by the portal's
     * scale a second time is the failure this pins: the server already spent it
     * deriving the offset, and at the measured nexus offset of -750 a second
     * division is four blocks wrong for every one the actor moves.
     */
    @Test
    void aDestinationCoordinateComesBackBySubtractingTheOffset() {
        // A source cell at x = 1500 maps to destination 750 under offset -750.
        assertEquals(8.0, DestinationActors.local(750.0, -750, 1492, 0.0), TOLERANCE);
    }

    @Test
    void anUnshiftedPortalLeavesASourceCoordinateWhereItStands() {
        assertEquals(8.0, DestinationActors.local(1500.0, 0, 1492, 0.0), TOLERANCE);
    }

    /**
     * The mesh is drawn half a block proud of where it was sampled so its near
     * face lands on the portal surface. An actor gets the same move or it stands
     * half a block behind the ground it is on.
     */
    @Test
    void theSurfaceShiftMovesAnActorWithTheTerrainUnderIt() {
        assertEquals(8.5, DestinationActors.local(750.0, -750, 1492, 0.5), TOLERANCE);
        assertEquals(7.5, DestinationActors.local(750.0, -750, 1492, -0.5), TOLERANCE);
    }

    @Test
    void anActorInsideTheCapturedVolumeIsSubmitted() {
        ClientProjection projection = projection();

        assertFalse(DestinationActors.outsideVolume(projection, 9.0, 9.0, 12.0));
        assertFalse(DestinationActors.outsideVolume(projection, 0.0, 0.0, 0.0));
    }

    /**
     * The clip would cut an actor the opening does not frame anyway; the bound
     * is what stops a mob at the far end of the destination world being
     * submitted every frame. The margin is what keeps a model whose origin has
     * just left the box from popping while its body is still in view.
     */
    @Test
    void anActorWellPastTheCapturedVolumeIsNotSubmitted() {
        ClientProjection projection = projection();

        assertTrue(DestinationActors.outsideVolume(projection, -3.0, 9.0, 12.0));
        assertTrue(DestinationActors.outsideVolume(projection, 9.0, 9.0, 27.0));
        assertFalse(DestinationActors.outsideVolume(projection, -1.0, 9.0, 12.0),
                "an actor one block outside the box was dropped with its model still in view");
    }

    private static final int SIZE_X = 18;
    private static final int SIZE_Y = 19;
    private static final int SIZE_Z = 24;

    private static ClientProjection projection() {
        List<BlockPos> aperture = new ArrayList<>();
        for (int x = 1500; x <= 1501; x++) {
            for (int y = 101; y <= 103; y++) {
                aperture.add(new BlockPos(x, y, 1500));
            }
        }
        return new ClientProjection(new CompanionPayloads.Projection(
                Identifier.of("adventure", "the_crimson_nexus"),
                aperture.get(0), aperture,
                Direction.Axis.X.ordinal(), Direction.SOUTH.ordinal(),
                new BlockPos(1492, 93, 1501), SIZE_X, SIZE_Y, SIZE_Z,
                new int[0], new byte[0],
                -1, -1, -1, -1, -1));
    }
}
