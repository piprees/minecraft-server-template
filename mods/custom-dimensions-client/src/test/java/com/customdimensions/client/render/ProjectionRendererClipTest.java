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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
            assertEquals(0.0, corners[i * 3 + 2], TOLERANCE,
                    "the opening is not on the slab's near face");
        }
    }

    /**
     * The other side of the same frame. A slab extending towards -Z starts at
     * {@code 1500 - 24}, so the opening sits at the far end of the volume's own
     * span — and the plane keeps the aperture's own coordinate rather than
     * gaining the {@code + 1} the positive direction gets.
     */
    @Test
    void theOpeningTracksTheSlabWhenItExtendsTheOtherWay() {
        ClientProjection projection = projection(Direction.NORTH, new BlockPos(1492, 93, 1476));
        double[] corners = ProjectionRenderer.apertureCorners(projection, projection.origin());

        for (int i = 0; i < 4; i++) {
            assertEquals(24.0, corners[i * 3 + 2], TOLERANCE,
                    "the opening is not on the slab's near face");
        }
    }

    /**
     * The opening must land INSIDE the volume's own footprint, not merely on its
     * near face. {@code origin} is the min corner of the SOURCE cells the server
     * walked, so an origin taken from destination space instead would put the
     * opening hundreds of blocks outside this box and the clip would then
     * discard every quad — the reported symptom exactly.
     */
    @Test
    void theOpeningLiesInsideTheVolumeItIsMeasuredAgainst() {
        ClientProjection projection = projection(Direction.SOUTH, new BlockPos(1492, 93, 1501));
        double[] corners = ProjectionRenderer.apertureCorners(projection, projection.origin());

        for (int i = 0; i < 4; i++) {
            assertInside(corners[i * 3], SIZE_X, "x");
            assertInside(corners[i * 3 + 1], SIZE_Y, "y");
            assertInside(corners[i * 3 + 2], SIZE_Z, "z");
        }
    }

    private static void assertInside(double coordinate, int size, String axis) {
        assertTrue(coordinate >= 0.0 && coordinate <= size,
                "the opening's " + axis + " = " + coordinate + " is outside the volume's 0.." + size
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
