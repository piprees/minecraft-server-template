package com.customdimensions.client.render;

import com.customdimensions.client.CompanionPayloads;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The clip against the portal opening, over a frustum whose every expected
 * number is worked out from the fixture rather than from the code under test.
 *
 * <p>The opening is 2x2 in the plane {@code z = 0} and the camera sits at
 * {@code (1, 1, -5)}, dead centre and five blocks in front. The cone through
 * the opening therefore widens by {@code (z + 5) / 5} about {@code x = y = 1}:
 * the half-width is 1 at the opening, 2 at {@code z = 5}, 3 at {@code z = 10}.
 * Every bound asserted below is read off that, so a test can fail without the
 * fixture agreeing with it.
 */
class ProjectionRendererClipTest {

    /** Walked in the order {@code apertureCorners} walks it: a0b0, a0b1, a1b1, a1b0. */
    private static final double[] APERTURE = {
        0.0, 0.0, 0.0,
        0.0, 2.0, 0.0,
        2.0, 2.0, 0.0,
        2.0, 0.0, 0.0,
    };

    private static final double CAM_X = 1.0;
    private static final double CAM_Y = 1.0;
    private static final double CAM_Z = -5.0;

    private static final float TOLERANCE = 1.0e-4f;

    @Test
    void theMarkerIsTheAgreedLiteral() {
        assertEquals("companion-client:emit", ProjectionRenderer.EMIT_MARKER);
    }

    @Test
    void aQuadWellInsideTheConeSurvivesWhole() {
        Recorder drawn = clip(quad(5.0f, 0.0f, 1.0f, 0.0f, 1.0f));

        assertEquals(4, ProjectionRenderer.clipVertices, "the clip did not keep all four corners");
        assertEquals(4, drawn.count());
        assertEquals(0.0f, drawn.min(0), TOLERANCE);
        assertEquals(1.0f, drawn.max(0), TOLERANCE);
    }

    /**
     * The direct H1 check: a quad on the opening's own axis, deep in the cone.
     * An inverted sign convention discards this one and leaves the backdrop
     * drawing alone.
     */
    @Test
    void aQuadOnTheOpeningsAxisIsNotDiscarded() {
        Recorder drawn = clip(quad(10.0f, 0.5f, 1.5f, 0.5f, 1.5f));

        assertEquals(4, ProjectionRenderer.clipVertices);
        assertEquals(4, drawn.count(), "the clip discarded geometry the camera is looking at");
    }

    @Test
    void aQuadOutsideTheConeIsDroppedEntirely() {
        // At z = 5 the cone spans -1..3 on both in-plane axes.
        Recorder drawn = clip(quad(5.0f, 10.0f, 11.0f, 10.0f, 11.0f));

        assertEquals(0, ProjectionRenderer.clipVertices);
        assertEquals(0, drawn.count());
    }

    @Test
    void aStraddlingQuadIsCutOnTheConeAndNowhereElse() {
        // Half-width 2 about x = 1 at z = 5, so the cut lands on x = 3 exactly.
        Recorder drawn = clip(quad(5.0f, 2.0f, 4.0f, 0.0f, 1.0f));

        assertEquals(4, drawn.count(), "a quad cut by one plane is still a quad");
        assertEquals(2.0f, drawn.min(0), TOLERANCE);
        assertEquals(3.0f, drawn.max(0), TOLERANCE);
    }

    /**
     * At the opening's own plane the cone's cross-section IS the opening, so a
     * quad spanning far past the frame comes back as the frame. This is what
     * puts the geometry in the same coordinate frame as the aperture: read the
     * mesh a block out and these bounds move with it.
     */
    @Test
    void atTheOpeningsPlaneTheConeIsTheOpening() {
        Recorder drawn = clip(quad(0.0f, -5.0f, 5.0f, -5.0f, 5.0f));

        assertTrue(drawn.count() >= 4, "the opening clipped itself away");
        assertEquals(0.0f, drawn.min(0), TOLERANCE);
        assertEquals(2.0f, drawn.max(0), TOLERANCE);
        assertEquals(0.0f, drawn.min(1), TOLERANCE);
        assertEquals(2.0f, drawn.max(1), TOLERANCE);
    }

    /**
     * Every plane runs through the camera, so the cone has a mirror image
     * behind it. Keeping that would draw the destination over the player's own
     * back.
     */
    @Test
    void theMirroredConeBehindTheCameraIsNotKept() {
        Recorder drawn = clip(quad(-10.0f, 0.5f, 1.5f, 0.5f, 1.5f));

        assertEquals(0, ProjectionRenderer.clipVertices);
        assertEquals(0, drawn.count());
    }

    @Test
    void bothQuadsOfALayerAreClippedIndependently() {
        float[] inside = quad(5.0f, 0.0f, 1.0f, 0.0f, 1.0f);
        float[] outside = quad(5.0f, 10.0f, 11.0f, 10.0f, 11.0f);
        float[] both = new float[inside.length + outside.length];
        System.arraycopy(inside, 0, both, 0, inside.length);
        System.arraycopy(outside, 0, both, inside.length, outside.length);

        Recorder drawn = clip(both);

        assertEquals(4, ProjectionRenderer.clipVertices);
        assertEquals(4, drawn.count());
    }

    /**
     * A layer that clips to nothing is only honest if its geometry was within
     * reach of the opening in the first place. The emit line reports both boxes
     * so the two cases can be told apart without another run.
     */
    @Test
    void aLayersGeometryBoxIsTheSpanOfItsOwnVertices() {
        float[] near = quad(5.0f, 0.0f, 1.0f, 2.0f, 3.0f);
        float[] far = quad(9.0f, -4.0f, 7.0f, 6.0f, 6.5f);
        float[] both = new float[near.length + far.length];
        System.arraycopy(near, 0, both, 0, near.length);
        System.arraycopy(far, 0, both, near.length, far.length);

        // x spans -4..7, y spans 2..6.5, z spans 5..9 across the two quads.
        assertEquals("[-4.0..7.0, 2.0..6.5, 5.0..9.0]",
                ProjectionRenderer.meshBounds(new ProjectionMesh.Layer(null, both, both.length)));
    }

    /**
     * Which edge of the opening cut a quad away is the difference between a
     * destination that is out of sight and a clip that is misbehaving. The
     * index order is {@code apertureCorners}' walk, so for this upright fixture
     * it reads left, top, right, bottom — pinned here because the emit line is
     * unreadable if the order is guessed.
     */
    @Test
    void aRejectedQuadIsCountedAgainstTheEdgeThatCutIt() {
        // At z = 5 the cone spans -1..3 on both axes. One quad past each edge,
        // each of them inside the other three.
        float[] layer = concat(
                quad(5.0f, -11.0f, -10.0f, 0.0f, 1.0f),
                quad(5.0f, 0.0f, 1.0f, 10.0f, 11.0f),
                quad(5.0f, 10.0f, 11.0f, 0.0f, 1.0f),
                quad(5.0f, 0.0f, 1.0f, -11.0f, -10.0f));

        Recorder drawn = clip(layer);

        assertEquals(0, drawn.count(), "no quad of this layer is inside the cone");
        assertArrayEquals(new int[] {1, 1, 1, 1}, ProjectionRenderer.rejectedBy);
    }

    /**
     * Geometry entirely below the sightline dies on one edge and only that one.
     * This is the signature that separates "the destination is out of view"
     * from "the clip is eating the layer".
     */
    @Test
    void geometryBelowTheSightlineDiesOnTheBottomEdgeAlone() {
        Recorder drawn = clip(concat(
                quad(5.0f, 0.0f, 1.0f, -20.0f, -19.0f),
                quad(9.0f, 1.0f, 2.0f, -30.0f, -29.0f)));

        assertEquals(0, drawn.count());
        assertArrayEquals(new int[] {0, 0, 0, 2}, ProjectionRenderer.rejectedBy);
    }

    @Test
    void aSurvivingQuadIsCountedAgainstNoEdge() {
        clip(quad(5.0f, 0.0f, 1.0f, 0.0f, 1.0f));

        assertArrayEquals(new int[] {0, 0, 0, 0}, ProjectionRenderer.rejectedBy);
    }

    /**
     * Every quad is accounted for exactly once: emitted, or charged to the one
     * edge that cut it. Without this the counts can be read against a quad
     * total they do not cover, and a shortfall looks like lost geometry when it
     * is only a seam in the counting.
     */
    @Test
    void everyQuadIsEitherKeptOrChargedToExactlyOneEdge() {
        float[] layer = concat(
                quad(5.0f, -11.0f, -10.0f, 0.0f, 1.0f),
                quad(5.0f, 0.0f, 1.0f, 10.0f, 11.0f),
                quad(5.0f, 10.0f, 11.0f, 0.0f, 1.0f),
                quad(5.0f, 0.0f, 1.0f, -11.0f, -10.0f),
                quad(5.0f, 0.0f, 1.0f, 0.0f, 1.0f),
                quad(9.0f, 1.0f, 2.0f, 1.0f, 2.0f));

        Recorder drawn = clip(layer);

        int charged = 0;
        for (int count : ProjectionRenderer.rejectedBy) {
            charged += count;
        }
        assertEquals(4, charged, "a rejected quad was charged to no edge, or to two");
        assertEquals(8, ProjectionRenderer.clipVertices, "the two quads in view did not survive");
        assertEquals(6, charged + drawn.count() / 4, "quads in did not equal quads accounted for");
    }

    /**
     * A quad the clip merely trims is kept, not rejected. Counting a trim as a
     * rejection would make a portal that is drawing perfectly well read as one
     * losing geometry on every edge.
     */
    @Test
    void aQuadTheClipMerelyTrimsIsNotChargedToAnEdge() {
        Recorder drawn = clip(quad(5.0f, 2.0f, 4.0f, 0.0f, 1.0f));

        assertArrayEquals(new int[] {0, 0, 0, 0}, ProjectionRenderer.rejectedBy);
        assertEquals(4, drawn.count());
    }

    /**
     * The counts belong to the layer just emitted. Carried over they would
     * accumulate across every layer and every frame, and the field would read
     * as a fault on a portal that is drawing perfectly well.
     */
    @Test
    void theCountsBelongToOneLayerNotToEverySinceStartup() {
        clip(quad(5.0f, 0.0f, 1.0f, -20.0f, -19.0f));
        assertArrayEquals(new int[] {0, 0, 0, 1}, ProjectionRenderer.rejectedBy);

        clip(quad(5.0f, 0.0f, 1.0f, 0.0f, 1.0f));
        assertArrayEquals(new int[] {0, 0, 0, 0}, ProjectionRenderer.rejectedBy,
                "the previous layer's rejections were carried into this one");
    }

    @Test
    void theHighestVertexIsReportedWithItsPosition() {
        float[] layer = concat(
                quad(5.0f, 0.0f, 1.0f, 2.0f, 3.0f),
                quad(9.0f, 6.0f, 7.0f, 4.0f, 8.5f));

        // The top of the second quad: y 8.5, at x 6..7 and z 9.
        assertEquals("[6.0, 8.5, 9.0]", ProjectionRenderer.highestVertex(
                new ProjectionMesh.Layer(null, layer, layer.length)));
    }

    @Test
    void anEmptyLayerSaysSoRatherThanReportingABackwardsBox() {
        assertEquals("[empty]", ProjectionRenderer.meshBounds(
                new ProjectionMesh.Layer(null, new float[0], 0)));
    }

    @Test
    void aCameraOnTheFrameEdgeDegeneratesRatherThanBuildingAJunkFrustum() {
        assertFalse(ProjectionRenderer.buildPlanes(APERTURE.clone(), 0.0, 0.0, 0.0),
                "a camera standing on an opening corner built four planes anyway");
    }

    /**
     * The opening and the mesh must be in ONE coordinate frame or the clip cuts
     * against the wrong place. {@code ProjectionMesh.build} writes a vertex at
     * {@code source - origin}; this asserts the opening lands there too, over
     * the portal that was measured in game — a 2x3 opening at
     * {@code 1500-1501, 101-103, 1500} whose slab starts at {@code z = 1501}.
     */
    @Test
    void theOpeningIsMeasuredFromTheSameOriginTheMeshIs() {
        ClientProjection projection = projection(Direction.SOUTH, new BlockPos(1492, 93, 1501));
        double[] corners = ProjectionRenderer.apertureCorners(projection, projection.origin());

        // 1500 - 1492 and (1501 + 1) - 1492 on X; 101 - 93 and (103 + 1) - 93 on Y.
        assertEquals(8.0, corners[0], TOLERANCE);
        assertEquals(8.0, corners[1], TOLERANCE);
        assertEquals(8.0, corners[3], TOLERANCE);
        assertEquals(11.0, corners[4], TOLERANCE);
        assertEquals(10.0, corners[6], TOLERANCE);
        assertEquals(11.0, corners[7], TOLERANCE);
        assertEquals(10.0, corners[9], TOLERANCE);
        assertEquals(8.0, corners[10], TOLERANCE);
        for (int i = 0; i < 4; i++) {
            assertEquals(-0.5, corners[i * 3 + 2], TOLERANCE,
                    "the opening is not at the aperture block's mid-plane");
        }
    }

    /**
     * The other side of the same frame. A slab extending towards -Z starts at
     * {@code 1500 - 24}, so the opening sits at the far end of the volume's own
     * span, half a block proud of it on the camera's side.
     */
    @Test
    void theOpeningTracksTheSlabWhenItExtendsTheOtherWay() {
        ClientProjection projection = projection(Direction.NORTH, new BlockPos(1492, 93, 1476));
        double[] corners = ProjectionRenderer.apertureCorners(projection, projection.origin());

        for (int i = 0; i < 4; i++) {
            assertEquals(24.5, corners[i * 3 + 2], TOLERANCE,
                    "the opening is not at the aperture block's mid-plane");
        }
    }

    /**
     * The surface bisects the aperture block rather than sitting against one of
     * its faces, so half the frame's depth reads on each side. The aperture
     * block is {@code z = 1500} either way; the answer is {@code 1500.5}
     * whichever direction the slab runs, which is what a face-relative
     * expression cannot give.
     */
    @Test
    void theOpeningSitsAtTheApertureBlocksMidPlaneWhicheverWayTheSlabRuns() {
        assertEquals(1500.5, projection(Direction.SOUTH, new BlockPos(1492, 93, 1501)).planeCoord(),
                TOLERANCE, "a slab running +Z put the surface on a face of the block");
        assertEquals(1500.5, projection(Direction.NORTH, new BlockPos(1492, 93, 1476)).planeCoord(),
                TOLERANCE, "a slab running -Z put the surface on a face of the block");
    }

    /**
     * The aperture block's own two faces, which is what the sightline through a
     * one-block-deep hole is actually bounded by.
     */
    @Test
    void theApertureBlockIsOneBlockDeepOnTheNormalAxis() {
        ClientProjection projection = projection(Direction.NORTH, new BlockPos(1492, 93, 1476));

        assertEquals(1500.0, projection.apertureMinCoord(), TOLERANCE);
        assertEquals(1501.0, projection.apertureMaxCoord(), TOLERANCE);
    }

    /**
     * The opening must land in the volume's own footprint, not hundreds of
     * blocks away. {@code origin} is the min corner of the SOURCE cells the
     * server walked, so an origin taken from destination space instead would put
     * the opening outside this box and the clip would then discard every quad —
     * the reported symptom exactly. The normal axis is allowed half a block of
     * overhang and no more: the slab starts at the aperture block's far face, so
     * the surface bisecting that block is half a block outside it.
     */
    @Test
    void theOpeningLiesInsideTheVolumeItIsMeasuredAgainst() {
        ClientProjection projection = projection(Direction.SOUTH, new BlockPos(1492, 93, 1501));
        double[] corners = ProjectionRenderer.apertureCorners(projection, projection.origin());

        for (int i = 0; i < 4; i++) {
            assertInside(corners[i * 3], 0.0, SIZE_X, "x");
            assertInside(corners[i * 3 + 1], 0.0, SIZE_Y, "y");
            assertInside(corners[i * 3 + 2], -0.5, SIZE_Z + 0.5, "z");
        }
    }

    private static void assertInside(double coordinate, double min, double max, String axis) {
        assertTrue(coordinate >= min && coordinate <= max,
                "the opening's " + axis + " = " + coordinate + " is outside " + min + ".." + max
                        + " — the opening and the mesh are not in one coordinate frame");
    }

    /**
     * The grid is indexed in SOURCE space with destination contents:
     * {@code ProjectionStream.build} walks source cells, samples
     * {@code toTarget} of each, and stores them y-fastest, then z, then x. The
     * client has to subtract the same source-space origin and walk the same
     * order, or {@code ProjectionMesh.build} meshes the wrong cells.
     *
     * <p>Asserted as strides rather than by restating the server's expression: a
     * test that copies the formula it is meant to pin passes whatever the
     * formula becomes.
     */
    @Test
    void steppingTheGridMovesInTheServersOwnOrder() {
        BlockPos origin = new BlockPos(1492, 93, 1501);
        ClientProjection projection = projection(Direction.SOUTH, origin);
        int base = projection.indexOf(1497, 96, 1508);

        assertEquals(0, projection.indexOf(1492, 93, 1501), "the origin is not cell zero");
        assertEquals(base + 1, projection.indexOf(1497, 97, 1508), "y is not the fastest axis");
        assertEquals(base + SIZE_Y, projection.indexOf(1497, 96, 1509),
                "z does not stride by one row of y");
        assertEquals(base + SIZE_Y * SIZE_Z, projection.indexOf(1498, 96, 1508),
                "x does not stride by one whole z-by-y slice");
        assertEquals(SIZE_X * SIZE_Y * SIZE_Z - 1,
                projection.indexOf(1492 + SIZE_X - 1, 93 + SIZE_Y - 1, 1501 + SIZE_Z - 1),
                "the far corner is not the last cell");
    }

    /**
     * Every cell gets an index of its own and together they fill the array
     * exactly. The strides and the bounds cases above imply this, so it is a
     * second opinion rather than new coverage — but it is the only assertion
     * here that walks all of the cells instead of the few this file picked, so
     * it survives a rewrite of the formula that the sampled points were updated
     * to match.
     */
    @Test
    void everyCellOfTheVolumeGetsItsOwnIndex() {
        BlockPos origin = new BlockPos(1492, 93, 1501);
        ClientProjection projection = projection(Direction.SOUTH, origin);
        boolean[] taken = new boolean[SIZE_X * SIZE_Y * SIZE_Z];

        for (int lx = 0; lx < SIZE_X; lx++) {
            for (int ly = 0; ly < SIZE_Y; ly++) {
                for (int lz = 0; lz < SIZE_Z; lz++) {
                    int index = projection.indexOf(
                            origin.getX() + lx, origin.getY() + ly, origin.getZ() + lz);
                    assertTrue(index >= 0 && index < taken.length,
                            "cell " + lx + "," + ly + "," + lz + " indexed to " + index);
                    assertFalse(taken[index], "two cells share index " + index);
                    taken[index] = true;
                }
            }
        }
    }

    @Test
    void aPositionOutsideTheDescribedBoxHasNoCell() {
        BlockPos origin = new BlockPos(1492, 93, 1501);
        ClientProjection projection = projection(Direction.SOUTH, origin);

        assertEquals(-1, projection.indexOf(1491, 93, 1501));
        assertEquals(-1, projection.indexOf(1492, 92, 1501));
        assertEquals(-1, projection.indexOf(1492, 93, 1500));
        assertEquals(-1, projection.indexOf(1492 + SIZE_X, 93, 1501));
        assertEquals(-1, projection.indexOf(1492, 93 + SIZE_Y, 1501));
        assertEquals(-1, projection.indexOf(1492, 93, 1501 + SIZE_Z));
    }

    /**
     * The block directly behind the opening has to be a cell of the volume, and
     * its local position has to fall within the opening's own rectangle. That is
     * the two frames touching, over the payload the server actually sends.
     */
    @Test
    void theCellBehindTheOpeningIsUnderTheOpening() {
        BlockPos origin = new BlockPos(1492, 93, 1501);
        ClientProjection projection = projection(Direction.SOUTH, origin);
        double[] corners = ProjectionRenderer.apertureCorners(projection, projection.origin());

        // One block past the plane on the normal axis, centred in the opening.
        assertTrue(projection.indexOf(1500, 102, 1501) >= 0,
                "the first slab layer behind the opening is not a cell of the volume");
        double localX = 1500 - origin.getX();
        double localY = 102 - origin.getY();
        assertTrue(localX >= corners[0] && localX <= corners[6],
                "the cell behind the opening is not under it on X");
        assertTrue(localY >= corners[1] && localY <= corners[4],
                "the cell behind the opening is not under it on Y");
    }

    /**
     * The opening is a hole one block deep, so both faces of the aperture block
     * frame it and both are in front of a camera standing outside the frame.
     */
    @Test
    void bothFacesOfTheApertureBlockFrameTheOpening() {
        ClientProjection projection = projection(Direction.NORTH, new BlockPos(1492, 93, 1476));
        double[] tunnel = new double[24];

        // The aperture block is local z 24..25; the camera is at 25.5.
        assertEquals(2, ProjectionRenderer.tunnelFaces(projection, projection.origin(), 25.5, tunnel));
        for (int i = 0; i < 4; i++) {
            assertEquals(24.0, tunnel[i * 3 + 2], TOLERANCE, "the first face is not the far one");
            assertEquals(25.0, tunnel[12 + i * 3 + 2], TOLERANCE, "the second face is not the near one");
        }
    }

    /**
     * The reported defect, in numbers. The camera stands beside the frame — 1.5
     * blocks past the opening's low-X edge and half a block clear of its near
     * face — so the frame's own block lies on every sightline into the opening
     * and the destination must not be visible at all.
     *
     * <p>At {@code z = 20} the far face's cone spans {@code x 12.0..19.3} and
     * the near face's spans {@code x 23.0..45.0}; a quad at {@code x 13..15}
     * therefore sits inside the first and outside the second. Both figures are
     * read off the fixture: the cone through a rectangle at depth {@code d}
     * scales about the camera by {@code (camZ - z) / (camZ - d)}.
     */
    @Test
    void aCameraBesideTheFrameSeesNothingThroughItsOwnBlock() {
        Recorder drawn = tunnelClip(Direction.NORTH, new BlockPos(1492, 93, 1476),
                6.5, 9.5, 25.5, quad(20.0f, 13.0f, 15.0f, 9.0f, 10.0f));

        assertEquals(0, drawn.count(), "the destination drew through the frame's own block");
        assertEquals(0, ProjectionRenderer.clipVertices);
    }

    /**
     * The other half of the same rule: whichever face is the narrower at a given
     * depth is the one that binds. From dead in front the FAR face is narrower,
     * so a quad inside the near face's cone and outside the far face's is cut —
     * the case a near-face-only clip would let through.
     *
     * <p>Camera at the stance the working screenshot was taken from. At
     * {@code z = 20} the far cone spans {@code x 6.88..11.38} and the near cone
     * {@code x 5.96..12.50}.
     */
    @Test
    void theNarrowerFaceBindsFromDeadInFrontToo() {
        Recorder outside = tunnelClip(Direction.NORTH, new BlockPos(1492, 93, 1476),
                8.9, 9.62, 27.2, quad(20.0f, 11.6f, 12.2f, 9.0f, 10.0f));

        assertEquals(0, outside.count(), "a quad past the far face's cone was drawn anyway");
        assertArrayEquals(new int[] {0, 0, 1, 0}, ProjectionRenderer.rejectedBy,
                "the cut was not charged to the opening's high-X edge");

        Recorder inside = tunnelClip(Direction.NORTH, new BlockPos(1492, 93, 1476),
                8.9, 9.62, 27.2, quad(20.0f, 8.0f, 10.0f, 9.0f, 11.0f));

        assertEquals(4, inside.count(), "the tunnel cut away what the opening actually frames");
    }

    /**
     * Stepping into the frame. Once the camera is past a face, that face frames
     * nothing and its cone is behind the camera — kept, it would discard the
     * whole destination for the last half block before the crossing.
     */
    @Test
    void aFaceTheCameraHasAlreadyCrossedFramesNothing() {
        ClientProjection projection = projection(Direction.NORTH, new BlockPos(1492, 93, 1476));
        double[] tunnel = new double[24];

        // Inside the aperture block, past its near face at local z = 25.
        assertEquals(1, ProjectionRenderer.tunnelFaces(projection, projection.origin(), 24.8, tunnel));
        assertEquals(24.0, tunnel[2], TOLERANCE, "the face left standing is not the one ahead");

        Recorder drawn = tunnelClip(Direction.NORTH, new BlockPos(1492, 93, 1476),
                8.9, 9.62, 24.8, quad(20.0f, 8.0f, 10.0f, 9.0f, 11.0f));

        assertEquals(4, drawn.count(), "the destination vanished as the camera entered the frame");
    }

    /**
     * The backdrop writes depth with the test forced to always pass, so an
     * unclipped one paints the opening's shape straight over whatever stands in
     * front of the frame — the same defect as the mesh, in flat fog colour. It
     * goes through the tunnel too.
     */
    @Test
    void theBackdropIsCutByTheSameTunnelTheMeshIs() {
        ClientProjection projection = projection(Direction.NORTH, new BlockPos(1492, 93, 1476));
        float[] poly = new float[QuadCapture.STRIDE * 16];
        float[] scratch = new float[QuadCapture.STRIDE * 16];

        // planeLocal is the surface at local z 24.5; facing is -1 for NORTH.
        assertEquals(0, backdrop(projection, 6.5, 9.5, 25.5, poly, scratch),
                "the backdrop covered an opening the frame's own block hides");
        assertEquals(4, backdrop(projection, 8.9, 9.62, 27.2, poly, scratch),
                "the backdrop was cut away from dead in front of the opening");
    }

    /**
     * The backdrop sits past the far end of the slab. Drawn short of it, it
     * would cut through the destination's own terrain.
     */
    @Test
    void theBackdropSitsBeyondEveryCellOfTheSlab() {
        ClientProjection projection = projection(Direction.NORTH, new BlockPos(1492, 93, 1476));
        float[] poly = new float[QuadCapture.STRIDE * 16];
        float[] scratch = new float[QuadCapture.STRIDE * 16];

        assertEquals(4, backdrop(projection, 8.9, 9.62, 27.2, poly, scratch));
        for (int v = 0; v < 4; v++) {
            // Surface 24.5, slab 24 deep, margin 2: 24.5 - 26 on a -Z normal.
            assertEquals(-1.5f, poly[v * QuadCapture.STRIDE + 2], TOLERANCE,
                    "the backdrop landed short of the far end of the slab");
        }
    }

    /**
     * The destination's own near face belongs ON the surface that bisects the
     * aperture block. The server starts the slab at that block's FAR face, so
     * drawn at its literal coordinates the image sits half a block back and the
     * frame's four inner faces show around it.
     *
     * <p>The quad is the slab's first layer, {@code z = 0} in the volume's own
     * space, dead in front of a camera five blocks out. Its emitted depth is the
     * whole assertion: {@code -0.5} is the surface, {@code 0.0} is the back of
     * the opening.
     */
    @Test
    void theSlabsNearFaceIsDrawnOnThePortalSurface() {
        Recorder drawn = tunnelClip(Direction.SOUTH, new BlockPos(1492, 93, 1501),
                9.0, 9.5, -5.0, quad(0.0f, 8.5f, 9.5f, 9.0f, 10.0f));

        assertEquals(4, drawn.count(), "the slab's first layer clipped away");
        assertEquals(-0.5f, drawn.min(2), TOLERANCE,
                "the slab is drawn at the aperture block's far face, not on the surface");
        assertEquals(-0.5f, drawn.max(2), TOLERANCE);
    }

    /**
     * The same face on a slab running the other way. Read off the fixture: the
     * NORTH slab spans local {@code 0..24} with its camera-facing end at
     * {@code 24}, and the surface is at {@code 24.5}, so this one moves towards
     * the camera by the same half block in the opposite direction.
     */
    @Test
    void theSlabsNearFaceIsDrawnOnTheSurfaceWhicheverWayTheSlabRuns() {
        Recorder drawn = tunnelClip(Direction.NORTH, new BlockPos(1492, 93, 1476),
                8.9, 9.62, 29.5, quad(24.0f, 8.5f, 9.5f, 9.0f, 10.0f));

        assertEquals(4, drawn.count(), "the slab's first layer clipped away");
        assertEquals(24.5f, drawn.min(2), TOLERANCE,
                "the slab is drawn at the aperture block's far face, not on the surface");
        assertEquals(24.5f, drawn.max(2), TOLERANCE);
    }

    /**
     * The offset is the gap between the surface and the slab's camera-facing
     * end, not a constant with a sign picked per direction. Both numbers below
     * come from the fixture's own geometry — surface {@code 1500.5} against a
     * slab ending at {@code 1501} one way and {@code 1500} the other.
     */
    @Test
    void theSurfaceOffsetIsTheGapBetweenTheSurfaceAndTheSlabsNearFace() {
        assertEquals(-0.5,
                projection(Direction.SOUTH, new BlockPos(1492, 93, 1501)).surfaceOffset(),
                TOLERANCE);
        assertEquals(0.5,
                projection(Direction.NORTH, new BlockPos(1492, 93, 1476)).surfaceOffset(),
                TOLERANCE);
    }

    /**
     * Moved and then cut, never cut and then moved. The cone narrows towards
     * the opening, so geometry shifted half a block closer must be clipped where
     * it now stands: this quad's high-X edge is inside the far cone at
     * {@code z = 0.0} and outside it at {@code z = -0.5}.
     *
     * <p>The far face sits at local {@code z = 0} and the camera at
     * {@code -5}, so the cone scales by {@code (5 + z) / 5} about
     * {@code x = 9}: half-width 1.0 at {@code z = 0} and 0.9 at {@code -0.5},
     * putting the high-X edge at {@code 10.0} and {@code 9.9}.
     */
    @Test
    void theSlabIsClippedWhereItIsDrawnAndNotWhereItWasSent() {
        Recorder drawn = tunnelClip(Direction.SOUTH, new BlockPos(1492, 93, 1501),
                9.0, 9.5, -5.0, quad(0.0f, 9.0f, 9.95f, 9.0f, 10.0f));

        assertEquals(9.9f, drawn.max(0), TOLERANCE,
                "the quad was cut against the cone at the depth it was sent at");
    }

    /**
     * The depth stamp goes ON the surface, not at the backdrop's distance and
     * not at either face of the aperture block. Anything vanilla draws after the
     * projection is tested against this value, so the surface is the one place
     * that divides "in front of the window" from "behind it".
     */
    @Test
    void theApertureStampSitsOnThePortalSurface() {
        ClientProjection projection = projection(Direction.NORTH, new BlockPos(1492, 93, 1476));
        float[] poly = new float[QuadCapture.STRIDE * 16];
        float[] scratch = new float[QuadCapture.STRIDE * 16];

        assertEquals(4, stamp(projection, 8.9, 9.62, 27.2, poly, scratch));
        for (int v = 0; v < 4; v++) {
            assertEquals(24.5f, poly[v * QuadCapture.STRIDE + 2], TOLERANCE,
                    "the stamp is not on the surface that bisects the aperture block");
        }
    }

    /**
     * The stamp writes depth with the test forced to always pass, exactly as the
     * backdrop does, so an uncut one would occlude the world in the opening's
     * shape well outside the frame. It goes through the tunnel too.
     */
    @Test
    void theApertureStampIsCutByTheSameTunnelTheMeshIs() {
        ClientProjection projection = projection(Direction.NORTH, new BlockPos(1492, 93, 1476));
        float[] poly = new float[QuadCapture.STRIDE * 16];
        float[] scratch = new float[QuadCapture.STRIDE * 16];

        assertEquals(0, stamp(projection, 6.5, 9.5, 25.5, poly, scratch),
                "the stamp covered an opening the frame's own block hides");
        assertEquals(4, stamp(projection, 8.9, 9.62, 27.2, poly, scratch),
                "the stamp was cut away from dead in front of the opening");
    }

    /**
     * The stamp has to be nearer the camera than every cell of the slab, or it
     * would occlude the destination it is meant to publish the depth of. Read
     * off the fixture: the slab's camera-facing end is local {@code 24} and the
     * camera is at {@code 27.2}, so the surface at {@code 24.5} is the nearer.
     */
    @Test
    void theApertureStampIsNearerTheCameraThanTheSlabItself() {
        ClientProjection projection = projection(Direction.NORTH, new BlockPos(1492, 93, 1476));
        float[] poly = new float[QuadCapture.STRIDE * 16];
        float[] scratch = new float[QuadCapture.STRIDE * 16];
        stamp(projection, 8.9, 9.62, 27.2, poly, scratch);

        // The NORTH slab spans local 0..SIZE_Z with its camera-facing end at SIZE_Z.
        assertTrue(poly[2] > SIZE_Z,
                "the stamp sits behind the slab and would hide the destination");
        assertTrue(poly[2] < 27.2, "the stamp sits behind the camera");
    }

    /**
     * The pass's own cost, in microseconds, as mean over the span and peak
     * single frame. Read against the same figure from another stance — the only
     * comparison free of what the rest of the scene costs.
     */
    @Test
    void theCostSummaryIsMeanThenPeakInMicroseconds() {
        // 3 frames totalling 600us, worst of them 400us.
        assertEquals("200/400", ProjectionRenderer.costSummary(3, 600_000L, 400_000L));
    }

    /**
     * A span with no frames in it reports so rather than dividing by zero. A
     * silent 0 would read as a pass that costs nothing, which is the one answer
     * that must never come from an absence of measurement.
     */
    @Test
    void aSpanWithNoFramesSaysSoRatherThanReportingZero() {
        assertEquals("n/a", ProjectionRenderer.costSummary(0, 0L, 0L));
        assertEquals("n/a", ProjectionRenderer.costSummary(-1, 5_000L, 5_000L));
    }

    /**
     * The ordinary path: mask off, draw, mask back on.
     */
    @Test
    void aDepthOnlyDrawTurnsTheColourMaskOffAndBackOn() {
        List<Boolean> calls = new ArrayList<>();

        ProjectionRenderer.withColourMaskOff(calls::add, () -> { });

        assertEquals(List.of(Boolean.FALSE, Boolean.TRUE), calls);
    }

    /**
     * {@code RenderLayer.draw} has no exception table — three instructions, no
     * try/finally — so a throw out of the draw skips the layer's end action. A
     * colour mask left off writes no colour for the rest of the frame, which is
     * a black screen with a log line as its only trace.
     */
    @Test
    void aThrowFromInsideTheDrawStillRestoresTheColourMask() {
        List<Boolean> calls = new ArrayList<>();
        RuntimeException boom = new RuntimeException("draw failed");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> ProjectionRenderer.withColourMaskOff(calls::add, () -> {
                    throw boom;
                }));

        assertSame(boom, thrown, "the failure was swallowed instead of propagating");
        assertEquals(List.of(Boolean.FALSE, Boolean.TRUE), calls,
                "the colour mask was left off after the draw threw");
    }

    /**
     * The box the frustum gate tests, in WORLD space. Read off the fixture: the
     * aperture cells are x 1500-1501, y 101-103, z 1500, so the block they
     * occupy runs x 1500..1502, y 101..104, z 1500..1501.
     */
    @Test
    void theApertureBoxIsTheApertureBlockInWorldSpace() {
        net.minecraft.util.math.Box box = ProjectionRenderer.apertureBox(
                projection(Direction.SOUTH, new BlockPos(1492, 93, 1501)));

        assertEquals(1500.0, box.minX, TOLERANCE);
        assertEquals(1502.0, box.maxX, TOLERANCE);
        assertEquals(101.0, box.minY, TOLERANCE);
        assertEquals(104.0, box.maxY, TOLERANCE);
        assertEquals(1500.0, box.minZ, TOLERANCE);
        assertEquals(1501.0, box.maxZ, TOLERANCE);
    }

    /**
     * The same box whichever way the slab runs. The gate is about where the
     * OPENING is, not where the destination was sampled, so a NORTH-facing
     * portal on the same frame must produce the same box.
     */
    @Test
    void theApertureBoxDoesNotMoveWithTheSlabsDirection() {
        net.minecraft.util.math.Box south = ProjectionRenderer.apertureBox(
                projection(Direction.SOUTH, new BlockPos(1492, 93, 1501)));
        net.minecraft.util.math.Box north = ProjectionRenderer.apertureBox(
                projection(Direction.NORTH, new BlockPos(1492, 93, 1476)));

        assertEquals(south, north, "the gate's box followed the slab instead of the opening");
    }

    /**
     * A box one block deep on the normal axis and no thinner. Collapsed to the
     * plane it would fail a frustum test at grazing angles and the portal would
     * blink out while still visible.
     */
    @Test
    void theApertureBoxIsAWholeBlockDeepOnTheNormalAxis() {
        net.minecraft.util.math.Box box = ProjectionRenderer.apertureBox(
                projection(Direction.SOUTH, new BlockPos(1492, 93, 1501)));

        assertEquals(1.0, box.maxZ - box.minZ, TOLERANCE);
    }

    /**
     * The slice runs from the surface's nearest point to nine tenths of the way
     * to the nearest point half a block behind it — short of the whole half
     * block so the backdrop still beats source terrain starting at the aperture
     * block's far face.
     */
    @Test
    void theDepthSliceStopsShortOfTheHalfBlockBehindTheSurface() {
        double[] slice = ProjectionRenderer.depthSlice(0.9880, 0.9892);

        assertEquals(0.9880, slice[0], 1.0e-9, "the slice does not start at the surface");
        assertEquals(0.9880 + 0.0012 * 0.9, slice[1], 1.0e-9,
                "the slice does not stop short of the half block");
    }

    /**
     * A degenerate or out-of-range slice is refused rather than clamped. Applied
     * as a depth range it would compress the destination into nothing, or invert
     * it, and the portal would draw as a flat plate or not at all.
     */
    @Test
    void aSliceThatCannotBeFormedIsRefusedRatherThanClamped() {
        assertNull(ProjectionRenderer.depthSlice(Double.NaN, 0.99), "NaN surface accepted");
        assertNull(ProjectionRenderer.depthSlice(0.99, Double.NaN), "NaN half block accepted");
        assertNull(ProjectionRenderer.depthSlice(0.99, 0.99), "a zero-thickness slice was formed");
        assertNull(ProjectionRenderer.depthSlice(0.99, 0.98), "an inverted slice was formed");
        assertNull(ProjectionRenderer.depthSlice(-0.01, 0.5), "a slice starting behind the eye");
        assertNull(ProjectionRenderer.depthSlice(0.5, 1.01), "a slice past the far plane");
    }

    /**
     * The whole point, as a number: the destination's depths all land inside the
     * slice, so a real block in front of the surface — nearer than the slice's
     * own start — beats every one of them under an ordinary LEQUAL test.
     */
    @Test
    void everyDepthInTheSliceIsBehindABlockInFrontOfTheSurface() {
        double surface = 0.9880;
        double blockInFront = 0.9875;
        double[] slice = ProjectionRenderer.depthSlice(surface, 0.9892);

        assertTrue(slice[0] > blockInFront,
                "the slice starts nearer than a block in front of the surface");
        assertTrue(slice[1] > slice[0], "the slice has no depth to sort the destination in");
        assertTrue(slice[1] < 0.9892, "the slice reaches the half block it must stop short of");
    }

    /**
     * The guard restores its state even when the draw throws, whatever the state
     * is. This is the seam the colour mask and the depth range both go through,
     * and the depth range has no {@code RenderSystem} cache and no vanilla phase
     * to put it back.
     */
    @Test
    void theGlStateGuardRestoresAfterAThrowAndReturnsTheDrawsValue() {
        List<String> calls = new ArrayList<>();

        assertEquals("drawn", ProjectionRenderer.withGlState(
                () -> calls.add("apply"), () -> calls.add("restore"), () -> {
                    calls.add("draw");
                    return "drawn";
                }));
        assertEquals(List.of("apply", "draw", "restore"), calls);

        calls.clear();
        RuntimeException boom = new RuntimeException("draw failed");
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> ProjectionRenderer.withGlState(
                        () -> calls.add("apply"), () -> calls.add("restore"), () -> {
                            throw boom;
                        }));

        assertSame(boom, thrown, "the failure was swallowed instead of propagating");
        assertEquals(List.of("apply", "restore"), calls,
                "the state was left set after the draw threw");
    }

    private static int stamp(ClientProjection projection, double camA, double camB,
            double camNormal, float[] poly, float[] scratch) {
        double[] tunnel = new double[24];
        int faces = ProjectionRenderer.tunnelFaces(projection, projection.origin(), camNormal, tunnel);
        if (!ProjectionRenderer.buildTunnelPlanes(tunnel, faces, camA, camB, camNormal)) {
            return 0;
        }
        double planeLocal = projection.planeCoord() - projection.origin().getZ();
        return ProjectionRenderer.aperturePolygon(projection, tunnel, camA, camB, camNormal,
                planeLocal, poly, scratch);
    }

    private static int backdrop(ClientProjection projection, double camA, double camB,
            double camNormal, float[] poly, float[] scratch) {
        double[] tunnel = new double[24];
        int faces = ProjectionRenderer.tunnelFaces(projection, projection.origin(), camNormal, tunnel);
        assertTrue(ProjectionRenderer.buildTunnelPlanes(tunnel, faces, camA, camB, camNormal));
        double planeLocal = projection.planeCoord() - projection.origin().getZ();
        return ProjectionRenderer.backdropPolygon(projection, tunnel, camA, camB, camNormal,
                planeLocal, -1.0, poly, scratch);
    }

    /** Builds the tunnel for one projection and camera, then clips one layer. */
    private static Recorder tunnelClip(Direction normal, BlockPos origin,
            double camA, double camB, double camNormal, float[] data) {
        ClientProjection projection = projection(normal, origin);
        double[] tunnel = new double[24];
        int faces = ProjectionRenderer.tunnelFaces(projection, projection.origin(), camNormal, tunnel);
        assertTrue(ProjectionRenderer.buildTunnelPlanes(tunnel, faces, camA, camB, camNormal),
                "the tunnel degenerated for a camera outside the frame");
        Recorder recorder = new Recorder();
        float shift = (float) projection.surfaceOffset();
        ProjectionRenderer.emitClipped(new ProjectionMesh.Layer(null, data, data.length),
                recorder, new MatrixStack().peek(), 0.0f, 0.0f, shift);
        return recorder;
    }

    private static final int SIZE_X = 18;
    private static final int SIZE_Y = 19;
    private static final int SIZE_Z = 24;

    /** The measured portal: 2 wide, 3 tall, plane Z = 1500, portal axis X. */
    private static ClientProjection projection(Direction normal, BlockPos origin) {
        List<BlockPos> aperture = new ArrayList<>();
        for (int x = 1500; x <= 1501; x++) {
            for (int y = 101; y <= 103; y++) {
                aperture.add(new BlockPos(x, y, 1500));
            }
        }
        return new ClientProjection(new CompanionPayloads.Projection(
                Identifier.of("adventure", "the_crimson_nexus"),
                aperture.get(0), aperture,
                Direction.Axis.X.ordinal(), normal.ordinal(),
                origin, SIZE_X, SIZE_Y, SIZE_Z,
                new int[0], new byte[0],
                -1, -1, -1, -1, -1));
    }

    /** Runs the production emit path over one layer and records what it drew. */
    private static Recorder clip(float[] data) {
        assertTrue(ProjectionRenderer.buildPlanes(APERTURE.clone(), CAM_X, CAM_Y, CAM_Z),
                "the frustum degenerated for a camera five blocks in front of the opening");
        Recorder recorder = new Recorder();
        ProjectionRenderer.emitClipped(new ProjectionMesh.Layer(null, data, data.length),
                recorder, new MatrixStack().peek());
        return recorder;
    }

    private static float[] concat(float[]... quads) {
        int total = 0;
        for (float[] quad : quads) {
            total += quad.length;
        }
        float[] out = new float[total];
        int at = 0;
        for (float[] quad : quads) {
            System.arraycopy(quad, 0, out, at, quad.length);
            at += quad.length;
        }
        return out;
    }

    /** One axis-aligned quad at depth {@code z}, in the volume's own space. */
    private static float[] quad(float z, float x0, float x1, float y0, float y1) {
        float[][] corners = {{x0, y0}, {x0, y1}, {x1, y1}, {x1, y0}};
        float[] data = new float[QuadCapture.STRIDE * 4];
        for (int i = 0; i < 4; i++) {
            int at = i * QuadCapture.STRIDE;
            data[at] = corners[i][0];
            data[at + 1] = corners[i][1];
            data[at + 2] = z;
            data[at + 3] = 1.0f;
            data[at + 4] = 1.0f;
            data[at + 5] = 1.0f;
            data[at + 6] = 1.0f;
            data[at + 10] = 10.0f;
            data[at + 11] = 15.0f;
            data[at + 12] = 15.0f;
            data[at + 15] = 1.0f;
        }
        return data;
    }

    private static final class Recorder implements VertexConsumer {

        private final List<float[]> vertices = new ArrayList<>();

        int count() {
            return this.vertices.size();
        }

        float min(int axis) {
            float out = Float.MAX_VALUE;
            for (float[] vertex : this.vertices) {
                out = Math.min(out, vertex[axis]);
            }
            return out;
        }

        float max(int axis) {
            float out = -Float.MAX_VALUE;
            for (float[] vertex : this.vertices) {
                out = Math.max(out, vertex[axis]);
            }
            return out;
        }

        @Override
        public VertexConsumer vertex(float x, float y, float z) {
            this.vertices.add(new float[] {x, y, z});
            return this;
        }

        @Override
        public VertexConsumer color(int red, int green, int blue, int alpha) {
            return this;
        }

        @Override
        public VertexConsumer texture(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer overlay(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer light(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            return this;
        }
    }
}
