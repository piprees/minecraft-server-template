package com.customdimensions.immersive;

import com.customdimensions.portal.PortalHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ProjectionVolumeTest {

    /** A 2x3 doorway interior in the XY plane (zone axis X) at z. */
    private static Set<BlockPos> doorwayX(int x, int y, int z) {
        Set<BlockPos> out = new HashSet<>();
        for (int dx = 0; dx < 2; dx++) {
            for (int dy = 0; dy < 3; dy++) {
                out.add(new BlockPos(x + dx, y + dy, z));
            }
        }
        return out;
    }

    /** A 2x3 doorway interior in the ZY plane (zone axis Z) at x. */
    private static Set<BlockPos> doorwayZ(int x, int y, int z) {
        Set<BlockPos> out = new HashSet<>();
        for (int dz = 0; dz < 2; dz++) {
            for (int dy = 0; dy < 3; dy++) {
                out.add(new BlockPos(x, y + dy, z + dz));
            }
        }
        return out;
    }

    /** A 3x3 horizontal pad (zone axis Y) at y. */
    private static Set<BlockPos> pad(int x, int y, int z) {
        Set<BlockPos> out = new HashSet<>();
        for (int dx = 0; dx < 3; dx++) {
            for (int dz = 0; dz < 3; dz++) {
                out.add(new BlockPos(x + dx, y, z + dz));
            }
        }
        return out;
    }

    @Test
    void normalAxisFollowsThePortalPlane() {
        assertEquals(Direction.Axis.Z, ProjectionVolume.normalAxis(Direction.Axis.X));
        assertEquals(Direction.Axis.X, ProjectionVolume.normalAxis(Direction.Axis.Z));
        assertEquals(Direction.Axis.Y, ProjectionVolume.normalAxis(Direction.Axis.Y));
    }

    @Test
    void testVerticalPortalXAxis() {
        Set<BlockPos> interior = doorwayX(10, 64, 20);
        List<BlockPos> volume = ProjectionVolume.computeSourcePositions(
                interior, Direction.Axis.X, Direction.SOUTH, 4, 1);

        // 2 wide + 2*1 pad = 4, 3 tall + 2*1 pad = 5, depth 4.
        assertEquals(4 * 5 * 4, volume.size());
        Set<BlockPos> positions = new HashSet<>(volume);
        assertEquals(volume.size(), positions.size(), "no duplicate positions");

        for (BlockPos p : positions) {
            // The slab starts one block PAST the plane and never touches it.
            assertTrue(p.getZ() >= 21 && p.getZ() <= 24, "depth range: " + p);
            assertTrue(p.getX() >= 9 && p.getX() <= 12, "padded width: " + p);
            assertTrue(p.getY() >= 63 && p.getY() <= 67, "padded height: " + p);
        }
        // The portal plane itself is excluded, so no interior block is faked.
        for (BlockPos p : interior) {
            assertFalse(positions.contains(p), "interior must keep its real blocks");
        }
        assertTrue(positions.contains(new BlockPos(10, 64, 21)));
        assertTrue(positions.contains(new BlockPos(9, 63, 24)));
    }

    @Test
    void testVerticalPortalZAxis() {
        Set<BlockPos> interior = doorwayZ(-5, 70, -30);
        List<BlockPos> volume = ProjectionVolume.computeSourcePositions(
                interior, Direction.Axis.Z, Direction.WEST, 4, 1);

        assertEquals(4 * 5 * 4, volume.size());
        for (BlockPos p : volume) {
            // WEST is -X, so the slab sits below the plane's X.
            assertTrue(p.getX() >= -9 && p.getX() <= -6, "depth range: " + p);
            assertTrue(p.getZ() >= -31 && p.getZ() <= -28, "padded width: " + p);
            assertTrue(p.getY() >= 69 && p.getY() <= 73, "padded height: " + p);
        }
    }

    @Test
    void testHorizontalPortal() {
        Set<BlockPos> interior = pad(0, 100, 0);
        List<BlockPos> volume = ProjectionVolume.computeSourcePositions(
                interior, Direction.Axis.Y, Direction.DOWN, 4, 1);

        // 3 + 2 pad on both horizontal axes, depth downward.
        assertEquals(5 * 5 * 4, volume.size());
        for (BlockPos p : volume) {
            assertTrue(p.getY() >= 96 && p.getY() <= 99, "depth extends downward: " + p);
            assertTrue(p.getX() >= -1 && p.getX() <= 3);
            assertTrue(p.getZ() >= -1 && p.getZ() <= 3);
        }
    }

    @Test
    void defaultSettingsProduceTheDocumentedPacketBudget() {
        // 2x3 doorway, depth 8, radius 2 -> 6 * 7 * 8 = 336 positions, the
        // worst-case initial send the packet-budget analysis is built on.
        assertEquals(336, ProjectionVolume.computeSourcePositions(
                doorwayX(0, 64, 0), Direction.Axis.X, Direction.NORTH, 8, 2).size());
    }

    @Test
    void degenerateInputsProduceNoVolume() {
        assertTrue(ProjectionVolume.computeSourcePositions(
                Set.of(), Direction.Axis.X, Direction.NORTH, 8, 2).isEmpty());
        assertTrue(ProjectionVolume.computeSourcePositions(
                doorwayX(0, 64, 0), Direction.Axis.X, Direction.NORTH, 0, 2).isEmpty());
        assertTrue(ProjectionVolume.computeSourcePositions(
                doorwayX(0, 64, 0), Direction.Axis.X, null, 8, 2).isEmpty());
        // radius 0 is legal: the slab is exactly the interior's footprint.
        assertEquals(2 * 3 * 8, ProjectionVolume.computeSourcePositions(
                doorwayX(0, 64, 0), Direction.Axis.X, Direction.NORTH, 8, 0).size());
    }

    @Test
    void farSideIsAlwaysOppositeTheViewer() {
        Set<BlockPos> interior = doorwayX(10, 64, 20);
        // Viewer north of the plane (smaller Z) -> project south.
        assertEquals(Direction.SOUTH, ProjectionVolume.viewerFarSide(
                interior, Direction.Axis.X, new BlockPos(10, 64, 14), null));
        // Viewer south of it -> project north.
        assertEquals(Direction.NORTH, ProjectionVolume.viewerFarSide(
                interior, Direction.Axis.X, new BlockPos(10, 64, 26), null));

        Set<BlockPos> zInterior = doorwayZ(-5, 70, -30);
        assertEquals(Direction.EAST, ProjectionVolume.viewerFarSide(
                zInterior, Direction.Axis.Z, new BlockPos(-12, 70, -30), null));
        assertEquals(Direction.WEST, ProjectionVolume.viewerFarSide(
                zInterior, Direction.Axis.Z, new BlockPos(2, 70, -30), null));

        // Horizontal portal: a viewer above sees down through it.
        assertEquals(Direction.DOWN, ProjectionVolume.viewerFarSide(
                pad(0, 100, 0), Direction.Axis.Y, new BlockPos(1, 104, 1), null));
        assertEquals(Direction.UP, ProjectionVolume.viewerFarSide(
                pad(0, 100, 0), Direction.Axis.Y, new BlockPos(1, 96, 1), null));
    }

    @Test
    void farSideHoldsSteadyWhileTheViewerIsInThePlane() {
        Set<BlockPos> interior = doorwayX(10, 64, 20);
        BlockPos inDoorway = new BlockPos(10, 64, 20);
        // Standing in the doorway must not flip an established side...
        assertEquals(Direction.NORTH, ProjectionVolume.viewerFarSide(
                interior, Direction.Axis.X, inDoorway, Direction.NORTH));
        assertEquals(Direction.SOUTH, ProjectionVolume.viewerFarSide(
                interior, Direction.Axis.X, inDoorway, Direction.SOUTH));
        // ...and with nothing established, the positive side is the default.
        assertEquals(Direction.SOUTH, ProjectionVolume.viewerFarSide(
                interior, Direction.Axis.X, inDoorway, null));
    }

    @Test
    void firstLayerCoordNamesTheLayerNearestThePlane() {
        Set<BlockPos> interior = doorwayX(10, 64, 20);
        assertEquals(21, ProjectionVolume.firstLayerCoord(interior, Direction.SOUTH));
        assertEquals(19, ProjectionVolume.firstLayerCoord(interior, Direction.NORTH));

        Set<BlockPos> zInterior = doorwayZ(-5, 70, -30);
        assertEquals(-4, ProjectionVolume.firstLayerCoord(zInterior, Direction.EAST));
        assertEquals(-6, ProjectionVolume.firstLayerCoord(zInterior, Direction.WEST));

        Set<BlockPos> horizontal = pad(0, 100, 0);
        assertEquals(101, ProjectionVolume.firstLayerCoord(horizontal, Direction.UP));
        assertEquals(99, ProjectionVolume.firstLayerCoord(horizontal, Direction.DOWN));

        // Degenerate input must not leak Integer.MAX_VALUE arithmetic into
        // a coordinate comparison.
        assertEquals(0, ProjectionVolume.firstLayerCoord(Set.of(), Direction.NORTH));
        assertEquals(0, ProjectionVolume.firstLayerCoord(horizontal, null));
    }

    /**
     * 4a sends invisible LIGHT blocks for the first depth layer and 4e
     * samples that same layer for its air/solid/unknown decision. Both
     * identify it by coordinate rather than by walking the slab, so that
     * coordinate must select exactly one layer of the volume — no more, no
     * fewer, on either side of the plane.
     */
    @Test
    void firstLayerCoordSelectsExactlyOneVolumeLayer() {
        Set<BlockPos> interior = doorwayX(10, 64, 20);
        for (Direction normal : new Direction[]{Direction.SOUTH, Direction.NORTH}) {
            List<BlockPos> volume = ProjectionVolume.computeSourcePositions(
                    interior, Direction.Axis.X, normal, 8, 2);
            int first = ProjectionVolume.firstLayerCoord(interior, normal);
            long onFirst = volume.stream()
                    .filter(p -> ProjectionVolume.coordOn(p, normal.getAxis()) == first)
                    .count();
            // 2 wide + 2*2 pad = 6, 3 tall + 2*2 pad = 7.
            assertEquals(6 * 7, onFirst, "one full layer for " + normal);
            assertEquals(6 * 7, ProjectionVolume.computeSourcePositions(
                    interior, Direction.Axis.X, normal, 1, 2).size(),
                    "a depth-1 volume IS the first layer for " + normal);
            // And it is the layer nearest the plane, not the far end.
            for (BlockPos p : volume) {
                int coord = ProjectionVolume.coordOn(p, normal.getAxis());
                if (normal == Direction.SOUTH) {
                    assertTrue(coord >= first, "slab starts at the first layer: " + p);
                } else {
                    assertTrue(coord <= first, "slab starts at the first layer: " + p);
                }
            }
        }
    }

    @Test
    void coordOnPicksTheRequestedAxis() {
        BlockPos pos = new BlockPos(1, 2, 3);
        assertEquals(1, ProjectionVolume.coordOn(pos, Direction.Axis.X));
        assertEquals(2, ProjectionVolume.coordOn(pos, Direction.Axis.Y));
        assertEquals(3, ProjectionVolume.coordOn(pos, Direction.Axis.Z));
    }

    // ---- sightline mask ------------------------------------------------
    //
    // The reported defect: "the new immersive blocks appear to be happening
    // outside of the portal frame rather than only inside it, so the server
    // is rendering stuff when I just look in the general direction of the
    // portal". computeSourcePositions pads the slab by previewRadius on both
    // in-plane axes, and every one of those padded columns sits behind the
    // frame WALL. These tests pin the rule that fixes it: a position is only
    // sent if the segment from the player's eye to its centre goes through
    // the opening.

    /**
     * Allocating form — the scratch probe is exercised separately. The ring
     * is derived rather than passed so every test uses the same occluder set
     * the send path does; {@code normalAxis} is an involution, so applying it
     * to a normal axis gives the portal axis back.
     */
    private static boolean sees(Vec3d eye, BlockPos block, Direction.Axis normalAxis,
            int planeCoord, Set<BlockPos> interior) {
        return ProjectionVolume.seesThroughOpening(eye, block, normalAxis, planeCoord, interior,
                ProjectionVolume.frameRing(interior, ProjectionVolume.normalAxis(normalAxis)), null);
    }

    @Test
    void planeCoordNamesTheOpeningsPlane() {
        // A zone interior is one block thick, so the plane is a single
        // coordinate on the normal axis.
        assertEquals(20, ProjectionVolume.planeCoord(doorwayX(10, 64, 20), Direction.Axis.Z));
        assertEquals(-5, ProjectionVolume.planeCoord(doorwayZ(-5, 70, -30), Direction.Axis.X));
        assertEquals(100, ProjectionVolume.planeCoord(pad(0, 100, 0), Direction.Axis.Y));
        // Degenerate input must not leak MAX_VALUE arithmetic into a plane.
        assertEquals(0, ProjectionVolume.planeCoord(Set.of(), Direction.Axis.Z));
        assertEquals(0, ProjectionVolume.planeCoord(doorwayX(10, 64, 20), null));
    }

    @Test
    void directlyBehindTheOpeningIsVisibleAndPastTheFrameIsNot() {
        Set<BlockPos> interior = doorwayX(10, 64, 20);
        // Eye 6 blocks north of the plane, centred on the 2x3 opening.
        Vec3d eye = new Vec3d(11.0, 65.5, 14.5);

        // Straight through the doorway.
        assertTrue(sees(eye, new BlockPos(10, 64, 21), Direction.Axis.Z, 20, interior));
        assertTrue(sees(eye, new BlockPos(11, 66, 24), Direction.Axis.Z, 20, interior));

        // The first ring of previewRadius padding IS shown: a sliver of each
        // of these really does come through the edge of the opening, and the
        // rest of the cube is behind the frame block in front of it.
        assertTrue(sees(eye, new BlockPos(9, 64, 21), Direction.Axis.Z, 20, interior),
                "part of the column beside the frame is visible through the opening");
        assertTrue(sees(eye, new BlockPos(12, 65, 21), Direction.Axis.Z, 20, interior));

        // Two columns out, and the rows above and below: no part of these is
        // on a sightline through the opening at this depth. These are the
        // positions the tester saw destination blocks in.
        assertFalse(sees(eye, new BlockPos(8, 65, 21), Direction.Axis.Z, 20, interior),
                "two columns out is behind the wall, not the opening");
        assertFalse(sees(eye, new BlockPos(10, 68, 21), Direction.Axis.Z, 20, interior),
                "a row above the frame is behind the wall, not the opening");
        assertFalse(sees(eye, new BlockPos(10, 62, 21), Direction.Axis.Z, 20, interior));
    }

    @Test
    void theVisibleConeWidensWithDepth() {
        Set<BlockPos> interior = doorwayX(10, 64, 20);
        Vec3d eye = new Vec3d(11.0, 65.5, 14.5);

        // Two columns out from the frame: hidden right behind the wall,
        // visible once far enough back that the sightline through the
        // opening has spread to reach it. This is the frustum, and it is
        // what makes previewRadius a bound on the cone rather than a
        // description of what gets sent.
        assertFalse(sees(eye, new BlockPos(8, 65, 21), Direction.Axis.Z, 20, interior));
        assertFalse(sees(eye, new BlockPos(8, 65, 24), Direction.Axis.Z, 20, interior));
        assertTrue(sees(eye, new BlockPos(8, 65, 26), Direction.Axis.Z, 20, interior));
        assertTrue(sees(eye, new BlockPos(8, 65, 28), Direction.Axis.Z, 20, interior));
    }

    /**
     * The whole default slab, from a typical viewing position: the layer
     * against the plane shows exactly the opening and nothing else, the
     * visible window never shrinks with depth, and the total sent is a
     * fraction of the candidate set.
     */
    @Test
    void theMaskReducesTheDefaultSlabToACone() {
        Set<BlockPos> interior = doorwayX(10, 64, 20);
        Vec3d eye = new Vec3d(11.0, 65.5, 14.5);
        List<BlockPos> volume = ProjectionVolume.computeSourcePositions(
                interior, Direction.Axis.X, Direction.SOUTH, 8, 2);
        assertEquals(336, volume.size());

        int[] perLayer = new int[9];
        Set<BlockPos> firstLayerVisible = new HashSet<>();
        int visible = 0;
        for (BlockPos pos : volume) {
            if (!sees(eye, pos, Direction.Axis.Z, 20, interior)) {
                continue;
            }
            visible++;
            perLayer[pos.getZ() - 20]++;
            if (pos.getZ() == 21) {
                firstLayerVisible.add(pos);
            }
        }

        // Against the plane, the visible set is the opening plus exactly one
        // ring of surround — the blocks a sliver of which really does come
        // through the edge of the doorway. Nothing beyond the ring, which is
        // where the bleed into the real world used to start.
        Set<BlockPos> expectedFirstLayer = new HashSet<>();
        for (BlockPos p : interior) {
            expectedFirstLayer.add(new BlockPos(p.getX(), p.getY(), 21));
        }
        for (BlockPos p : ProjectionVolume.frameRing(interior, Direction.Axis.X)) {
            expectedFirstLayer.add(new BlockPos(p.getX(), p.getY(), 21));
        }
        assertEquals(expectedFirstLayer, firstLayerVisible);
        assertEquals(16, firstLayerVisible.size(), "6 aperture cells + a 10-cell ring");

        for (int layer = 2; layer <= 8; layer++) {
            assertTrue(perLayer[layer] >= perLayer[layer - 1],
                    "the cone must not narrow with depth at layer " + layer);
        }
        // 16, 16, 16, 20, 24, 34, 38, 38 by depth layer, 202 of 336 in total.
        //
        // Three rules have been tried here and the number is the clearest
        // record of the difference: centre-only gave 149 and leaked geometry
        // past the frame edge at grazing angles; whole-shadow-inside-the-
        // aperture gave 84 and was reported as barely a window at all
        // ("almost the opposite of our recent change... it's too
        // conservative"). Touch-the-aperture-stay-inside-the-ring gives 202,
        // and is the only one of the three that is a statement about what is
        // physically visible rather than a threshold.
        assertEquals(202, visible, "cone size from a centred eye 6 blocks out");
    }

    @Test
    void anObliqueEyeSlidesTheVisibleWindow() {
        Set<BlockPos> interior = doorwayX(10, 64, 20);
        // Three blocks off to -X of the opening, same distance in front.
        Vec3d oblique = new Vec3d(8.0, 65.5, 14.5);
        Vec3d centred = new Vec3d(11.0, 65.5, 14.5);

        // The window slides with the viewer, and the give-away is the NEAR
        // side: standing off to -X, the padding column on that same side is
        // now hidden behind the frame, while the centred eye still catches a
        // sliver of it through the opening's left edge. Same block, same
        // depth, two eyes, opposite answers — that asymmetry is the parallax,
        // and it is why the mask cannot be baked into the volume and shared.
        assertTrue(sees(centred, new BlockPos(9, 65, 21), Direction.Axis.Z, 20, interior));
        assertFalse(sees(oblique, new BlockPos(9, 65, 21), Direction.Axis.Z, 20, interior));

        // And the FAR side of the opening opens up to the oblique eye all the
        // way down the slab, because every one of those sightlines crosses
        // the doorway.
        assertTrue(sees(oblique, new BlockPos(12, 65, 21), Direction.Axis.Z, 20, interior));
        assertTrue(sees(oblique, new BlockPos(12, 65, 27), Direction.Axis.Z, 20, interior));

        // Far round the side, only a thin sliver of the slab is still on a
        // true sightline through the doorway (a steep angle across the
        // opening), and nothing near the viewer's side of the frame is.
        // Before the mask all 336 positions went out from ANY angle, which
        // is what put destination terrain over the real world for a player
        // merely looking in the portal's general direction.
        Vec3d beside = new Vec3d(2.0, 65.5, 14.5);
        assertFalse(sees(beside, new BlockPos(9, 65, 21), Direction.Axis.Z, 20, interior));
        assertFalse(sees(beside, new BlockPos(8, 65, 24), Direction.Axis.Z, 20, interior));

        int besideVisible = 0;
        int centredVisible = 0;
        for (BlockPos pos : ProjectionVolume.computeSourcePositions(
                interior, Direction.Axis.X, Direction.SOUTH, 8, 2)) {
            if (sees(beside, pos, Direction.Axis.Z, 20, interior)) {
                besideVisible++;
            }
            if (sees(centred, pos, Direction.Axis.Z, 20, interior)) {
                centredVisible++;
            }
        }
        assertTrue(besideVisible * 5 < centredVisible,
                "an oblique viewer should see a sliver, not a wall: "
                        + besideVisible + " vs " + centredVisible);
    }

    /**
     * The rule itself, from a single eye: a block only fractionally behind
     * the opening IS shown, and the same column one block nearer — where
     * perspective has spread its shadow a cell further out — is not.
     *
     * Both halves are reported artefacts. Showing only fully-contained blocks
     * was *"almost the opposite of our recent change... it's too
     * conservative"*; showing anything whose centre clears was *"i'm side-on
     * to the portal, it shouldn't have those leaves there"*.
     */
    @Test
    void aBlockIsShownUntilItsShadowRunsPastTheFrame() {
        Set<BlockPos> interior = doorwayX(10, 64, 20);
        // Close and off to one side: the grazing geometry where a block's
        // shadow is at its widest.
        Vec3d grazing = new Vec3d(10.2, 65.5, 18.0);

        // Depth 1: the shadow reaches cell x=13, two cells past the opening
        // and one past the ring. Those rays miss the frame entirely, so this
        // block would be seen hanging in open air beside the portal.
        assertFalse(sees(grazing, new BlockPos(13, 65, 21), Direction.Axis.Z, 20, interior));

        // Depth 2, same column, same eye: perspective has pulled the shadow
        // in to the opening and its ring, so every ray that reaches this
        // block either goes through the doorway or dies on a frame block.
        assertTrue(sees(grazing, new BlockPos(13, 65, 22), Direction.Axis.Z, 20, interior));

        // The ring really is what draws that line: widen the opening by the
        // column the depth-1 shadow spills into and it becomes visible.
        Set<BlockPos> wider = new HashSet<>(interior);
        for (int y = 64; y <= 66; y++) {
            wider.add(new BlockPos(12, y, 20));
        }
        assertTrue(sees(grazing, new BlockPos(13, 65, 21), Direction.Axis.Z, 20, wider));
    }

    /**
     * The safety half of the rule, swept over the whole default slab from
     * several viewing positions: no ray that reaches a projected block gets
     * there without passing through the opening or dying on a frame block.
     * That is the property that keeps destination geometry off the real
     * world, and the pinned counts above are only a sample of it.
     *
     * <p>Checked by ray-marching sample points strictly INSIDE each block
     * rather than re-deriving the implementation's shadow rectangle — the
     * boundary conventions of the two would otherwise have to agree exactly,
     * which would make this a copy of the code it is testing rather than a
     * check on it. Sampling inside is also the stronger statement: the
     * implementation bounds the true shadow with an axis-aligned rectangle,
     * so every ray tested here is inside a region it already vouched for.
     *
     * <p>The other half — that something IS visible through the opening — is
     * pinned by the per-case tests and by the first-layer set equality above.
     * It is deliberately not asserted here: the implementation's rectangle
     * can touch the aperture where the true (hexagonal) shadow only grazes
     * it, and erring towards showing is the harmless direction.
     */
    @Test
    void nothingShownIsVisiblePastTheFrame() {
        Set<BlockPos> interior = doorwayX(10, 64, 20);
        Set<BlockPos> ring = ProjectionVolume.frameRing(interior, Direction.Axis.X);
        List<BlockPos> volume = ProjectionVolume.computeSourcePositions(
                interior, Direction.Axis.X, Direction.SOUTH, 8, 2);
        Vec3d[] eyes = {
                new Vec3d(11.0, 65.5, 14.5),
                new Vec3d(8.0, 65.5, 14.5),
                new Vec3d(13.5, 62.0, 18.0),
                new Vec3d(2.0, 70.0, 10.0),
        };
        double[] offsets = {0.05, 0.5, 0.95};
        for (Vec3d eye : eyes) {
            for (BlockPos pos : volume) {
                if (!sees(eye, pos, Direction.Axis.Z, 20, interior)) {
                    continue;
                }
                for (double oz : offsets) {
                    double t = (20.5 - eye.z) / ((pos.getZ() + oz) - eye.z);
                    for (double ox : offsets) {
                        for (double oy : offsets) {
                            int cx = (int) Math.floor(eye.x + t * (pos.getX() + ox - eye.x));
                            int cy = (int) Math.floor(eye.y + t * (pos.getY() + oy - eye.y));
                            BlockPos cell = new BlockPos(cx, cy, 20);
                            assertTrue(interior.contains(cell) || ring.contains(cell),
                                    "a ray to " + pos + " from " + eye
                                            + " reaches it past the frame, through " + cell);
                        }
                    }
                }
            }
        }
    }

    @Test
    void anIrregularInteriorMasksPerBlockNotByBoundingBox() {
        // The same doorway with its top-right cell missing — a flood-filled
        // frame is under no obligation to be a rectangle.
        Set<BlockPos> notched = doorwayX(10, 64, 20);
        notched.remove(new BlockPos(11, 66, 20));
        Vec3d eye = new Vec3d(11.0, 65.5, 14.5);

        // A notch changes the RING as well as the opening: (12, 66) borders
        // no remaining aperture cell, so the block behind it has nothing to
        // be occluded by and nothing to be seen through.
        BlockPos pastTheNotch = new BlockPos(12, 66, 21);
        assertFalse(sees(eye, pastTheNotch, Direction.Axis.Z, 20, notched),
                "a bounding-box test would wrongly show this one");
        assertTrue(sees(eye, pastTheNotch, Direction.Axis.Z, 20, doorwayX(10, 64, 20)),
                "and it is the notch that made the difference");

        // Same story one row up, where the notch removes the only aperture
        // cell the sightline could have crossed.
        assertFalse(sees(eye, new BlockPos(11, 67, 21), Direction.Axis.Z, 20, notched));
        assertTrue(sees(eye, new BlockPos(11, 67, 21), Direction.Axis.Z, 20, doorwayX(10, 64, 20)));

        // The rest of the opening is unaffected by the notch.
        assertTrue(sees(eye, new BlockPos(10, 64, 21), Direction.Axis.Z, 20, notched));
    }

    @Test
    void aViewerInTheDoorwaySeesThroughItRatherThanNothing() {
        Set<BlockPos> interior = doorwayX(10, 64, 20);
        // Standing IN the plane, eye past its midpoint: the crossing is
        // behind the eye. Masking there would blank the entire preview in
        // the last half-block before a traversal.
        Vec3d past = new Vec3d(11.0, 65.5, 20.7);
        assertTrue(sees(past, new BlockPos(8, 62, 28), Direction.Axis.Z, 20, interior));
        assertTrue(sees(past, new BlockPos(10, 64, 21), Direction.Axis.Z, 20, interior));
        // Eye level with the block: parallel to the plane, never crosses.
        Vec3d level = new Vec3d(11.0, 65.5, 21.5);
        assertTrue(sees(level, new BlockPos(10, 64, 21), Direction.Axis.Z, 20, interior));
    }

    /**
     * The reported inversion, pinned: *"I cannot see the portal from my
     * direction at all because the planks are in the way, but it suddenly
     * pops; when I take one more step forward it goes away"* — symmetric on
     * both sides of the frame.
     *
     * viewerFarSide only flips the slab on a BLOCK boundary, so there is a
     * one-block band on the normal axis in which the eye is already past the
     * plane's midpoint while the slab is still on the far side. That band is
     * infinite in the in-plane axes, and the whole slab — padding included —
     * used to be declared visible anywhere inside it. Walking sideways past
     * a portal crosses it. So do the fake blocks a player could then walk
     * into and mine, which are those same padded columns.
     */
    @Test
    void beingLevelWithThePlaneIsNotTheSameAsBeingInTheDoorway() {
        Set<BlockPos> interior = doorwayX(10, 64, 20);
        BlockPos near = new BlockPos(10, 64, 21);
        BlockPos far = new BlockPos(8, 62, 28);

        // In the doorway: everything, exactly as before.
        Vec3d inDoorway = new Vec3d(11.0, 65.5, 20.7);
        assertTrue(sees(inDoorway, near, Direction.Axis.Z, 20, interior));
        assertTrue(sees(inDoorway, far, Direction.Axis.Z, 20, interior));

        // Level with the plane but well to one side, then the other, then
        // above: the frame is between this eye and the opening every time.
        for (Vec3d beside : new Vec3d[]{
                new Vec3d(2.0, 65.5, 20.7),
                new Vec3d(20.0, 65.5, 20.7),
                new Vec3d(11.0, 70.5, 20.7)}) {
            assertFalse(sees(beside, near, Direction.Axis.Z, 20, interior),
                    "projected into the plane band from " + beside);
            assertFalse(sees(beside, far, Direction.Axis.Z, 20, interior),
                    "projected into the plane band from " + beside);
        }
    }

    @Test
    void theMaskFollowsThePortalOrientation() {
        // Z-axis doorway: plane normal is X.
        Set<BlockPos> zInterior = doorwayZ(-5, 70, -30);
        Vec3d westOfIt = new Vec3d(-10.5, 71.5, -29.0);
        assertTrue(sees(westOfIt, new BlockPos(-4, 71, -30), Direction.Axis.X, -5, zInterior));
        assertFalse(sees(westOfIt, new BlockPos(-4, 71, -32), Direction.Axis.X, -5, zInterior));
        assertFalse(sees(westOfIt, new BlockPos(-4, 74, -30), Direction.Axis.X, -5, zInterior));

        // Horizontal pad: plane normal is Y, viewer above looking down.
        Set<BlockPos> horizontal = pad(0, 100, 0);
        Vec3d above = new Vec3d(1.5, 106.5, 1.5);
        assertTrue(sees(above, new BlockPos(1, 98, 1), Direction.Axis.Y, 100, horizontal));
        assertFalse(sees(above, new BlockPos(-1, 99, -1), Direction.Axis.Y, 100, horizontal));
    }

    @Test
    void aReusedScratchProbeAnswersIdenticallyToAFreshOne() {
        // The send path reuses one Mutable across the whole volume to keep
        // the mask allocation-free; a Mutable hashes and compares as its
        // coordinates, so it must be a valid key for the interior set.
        Set<BlockPos> interior = doorwayX(10, 64, 20);
        Vec3d eye = new Vec3d(11.0, 65.5, 14.5);
        BlockPos.Mutable scratch = new BlockPos.Mutable();
        for (BlockPos pos : ProjectionVolume.computeSourcePositions(
                interior, Direction.Axis.X, Direction.SOUTH, 8, 2)) {
            assertEquals(sees(eye, pos, Direction.Axis.Z, 20, interior),
                    ProjectionVolume.seesThroughOpening(
                            eye, pos, Direction.Axis.Z, 20, interior,
                            ProjectionVolume.frameRing(interior, Direction.Axis.X), scratch),
                    "scratch probe disagreed at " + pos);
        }
    }

    @Test
    void testTargetMapping() {
        // Interior columns x = 100/101, z = 200; average truncates to
        // (100, 200). At scale 2 (2 dim : 1 over) entry HALVES: (50, 100).
        Set<BlockPos> interior = doorwayX(100, 64, 200);
        ProjectionVolume.TargetMapping mapping = ProjectionVolume.scaledMapping(interior, 2.0);

        assertEquals(50, mapping.arrivalX());
        assertEquals(100, mapping.arrivalZ());
        assertEquals(-50, mapping.dx());
        assertEquals(-100, mapping.dz());
        assertEquals(64, mapping.interiorMinY());

        // The interior's floor row lands exactly on the arrival surface.
        assertEquals(new BlockPos(50, 72, 100),
                ProjectionVolume.toTarget(new BlockPos(100, 64, 200), mapping, 72));
        // Height above the floor is preserved, and depth/width offsets ride along.
        assertEquals(new BlockPos(51, 74, 101),
                ProjectionVolume.toTarget(new BlockPos(101, 66, 201), mapping, 72));
        // Positions below the portal floor map below the arrival surface.
        assertEquals(new BlockPos(50, 70, 100),
                ProjectionVolume.toTarget(new BlockPos(100, 62, 200), mapping, 72));
    }

    @Test
    void scaleOneIsAPureVerticalRemap() {
        Set<BlockPos> interior = doorwayX(100, 64, 200);
        ProjectionVolume.TargetMapping mapping = ProjectionVolume.scaledMapping(interior, 1.0);
        assertEquals(0, mapping.dx());
        assertEquals(0, mapping.dz());
        assertEquals(new BlockPos(100, 90, 200),
                ProjectionVolume.toTarget(new BlockPos(100, 64, 200), mapping, 90));
    }

    @Test
    void targetChunksCoverBothSidesAndTheArrivalColumn() {
        // Scale 1, so target coordinates equal source coordinates.
        Set<BlockPos> interior = doorwayX(100, 64, 200);
        ProjectionVolume.TargetMapping mapping = ProjectionVolume.scaledMapping(interior, 1.0);
        List<ChunkPos> chunks = ProjectionVolume.targetChunks(
                interior, Direction.Axis.X, mapping, 8, 2);

        // X: 100..101 padded by 2 -> 98..103 (chunk 6).
        // Z: 200 reaching 8 blocks BOTH ways -> 192..208 (chunks 12 and 13).
        assertEquals(Set.of(new ChunkPos(6, 12), new ChunkPos(6, 13)), new HashSet<>(chunks));
        assertEquals(2, chunks.size(), "no duplicate chunk columns");
        // The arrival column's chunk must be in the set — it is what
        // arrivalSurfaceY samples the heightmap from.
        assertTrue(chunks.contains(new ChunkPos(mapping.arrivalX() >> 4, mapping.arrivalZ() >> 4)));
    }

    @Test
    void targetChunksFollowTheScaledOffset() {
        Set<BlockPos> interior = doorwayX(100, 64, 200);
        ProjectionVolume.TargetMapping mapping = ProjectionVolume.scaledMapping(interior, 2.0);
        List<ChunkPos> chunks = ProjectionVolume.targetChunks(
                interior, Direction.Axis.X, mapping, 8, 2);

        // dx = -50, dz = -100: X 48..53 (chunk 3), Z 92..108 (chunks 5, 6).
        assertEquals(Set.of(new ChunkPos(3, 5), new ChunkPos(3, 6)), new HashSet<>(chunks));
        assertTrue(chunks.contains(new ChunkPos(mapping.arrivalX() >> 4, mapping.arrivalZ() >> 4)));
    }

    @Test
    void targetChunksForAnchorPortalsLandOnTheAnchor() {
        Set<BlockPos> interior = doorwayX(100, 64, 200);
        ProjectionVolume.TargetMapping mapping = ProjectionVolume.anchorMapping(interior, -1000, 3000);
        List<ChunkPos> chunks = ProjectionVolume.targetChunks(
                interior, Direction.Axis.X, mapping, 8, 2);

        assertTrue(chunks.contains(new ChunkPos(-1000 >> 4, 3000 >> 4)),
                "the anchor's own chunk must be held: " + chunks);
        assertEquals(Set.of(new ChunkPos(-63, 187), new ChunkPos(-63, 188)), new HashSet<>(chunks));
    }

    @Test
    void horizontalPortalDepthDoesNotWidenTheChunkFootprint() {
        // A Y-normal portal reaches vertically, so depth must not expand the
        // chunk columns — only the radius pad does.
        Set<BlockPos> interior = pad(0, 100, 0);
        ProjectionVolume.TargetMapping mapping = ProjectionVolume.scaledMapping(interior, 1.0);
        List<ChunkPos> shallow = ProjectionVolume.targetChunks(
                interior, Direction.Axis.Y, mapping, 1, 2);
        List<ChunkPos> deep = ProjectionVolume.targetChunks(
                interior, Direction.Axis.Y, mapping, 16, 2);
        assertEquals(new HashSet<>(shallow), new HashSet<>(deep));
        // x/z 0..2 padded by 2 -> -2..4, i.e. chunks -1 and 0 on both axes.
        assertEquals(4, deep.size());
    }

    @Test
    void targetChunksStayBoundedAtMaximumSettings() {
        // A large interior at the config maxima (depth 16, radius 4) must
        // still ticket a handful of chunks, not a region.
        Set<BlockPos> wide = new HashSet<>();
        for (int dx = 0; dx < 16; dx++) {
            for (int dy = 0; dy < 8; dy++) {
                wide.add(new BlockPos(dx, 64 + dy, 0));
            }
        }
        List<ChunkPos> chunks = ProjectionVolume.targetChunks(
                wide, Direction.Axis.X, ProjectionVolume.scaledMapping(wide, 1.0), 16, 4);
        assertTrue(chunks.size() <= 12, "unbounded chunk ticket set: " + chunks.size());
        assertFalse(chunks.isEmpty());
    }

    @Test
    void targetChunksAreEmptyForDegenerateInput() {
        assertTrue(ProjectionVolume.targetChunks(
                Set.of(), Direction.Axis.X,
                new ProjectionVolume.TargetMapping(0, 0, 0, 0, 0), 8, 2).isEmpty());
        assertTrue(ProjectionVolume.targetChunks(
                doorwayX(0, 64, 0), Direction.Axis.X, null, 8, 2).isEmpty());
    }

    @Test
    void testAnchorTargetMapping() {
        // Anchor portals translate the interior's MIN corner onto the
        // anchor position — matching ServerWorldMixin.teleportToAnchor,
        // which uses minX/minZ and NOT the averaged centre.
        Set<BlockPos> interior = doorwayX(100, 64, 200);
        ProjectionVolume.TargetMapping mapping = ProjectionVolume.anchorMapping(interior, -1000, 3000);

        assertEquals(-1000, mapping.arrivalX());
        assertEquals(3000, mapping.arrivalZ());
        assertEquals(-1100, mapping.dx());
        assertEquals(2800, mapping.dz());
        assertEquals(64, mapping.interiorMinY());

        assertEquals(new BlockPos(-1000, 80, 3000),
                ProjectionVolume.toTarget(new BlockPos(100, 64, 200), mapping, 80));
        assertEquals(new BlockPos(-999, 82, 3000),
                ProjectionVolume.toTarget(new BlockPos(101, 66, 200), mapping, 80));
        // Scale is irrelevant for anchors: they never consult it.
        assertNotEquals(ProjectionVolume.scaledMapping(interior, 2.0).dx(), mapping.dx());
    }
}
