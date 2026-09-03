package com.customdimensions.portal;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The vanilla-portal fill reads real block states, and
 * {@code World.getBlockState} resolves through {@code getChunk(create = true)}
 * — a read outside the loaded region generates terrain on the calling thread
 * and the watchdog kills the server ([K1]/[K6]).
 *
 * <p>Its callers prove a square of
 * {@link PortalAdoption#FOOTPRINT_RADIUS} chunks resident first, so the fill
 * is only ever safe inside that square. A {@code /setblock} chain of
 * {@code nether_portal} longer than the radius walks straight out of it.
 *
 * <p>Every case here drives the real loop through a view that records each
 * position it was asked for, so what is asserted is where the walk actually
 * read — not arithmetic in a helper the loop might not consult.
 */
class PortalAreaBoundTest {

    private static final int RADIUS = PortalAdoption.FOOTPRINT_RADIUS;

    /** Away from the coordinates other suites register zones at. */
    private static final BlockPos BASE = new BlockPos(3000, 70, -3000);

    /**
     * A block map standing in for the world, recording every position the
     * walk asked about. Anything unnamed is not a portal block.
     */
    private static final class RecordingView implements PortalBlockView {
        private final Map<BlockPos, Direction.Axis> nether = new HashMap<>();
        private final Set<BlockPos> end = new HashSet<>();
        private final List<BlockPos> reads = new ArrayList<>();

        RecordingView nether(BlockPos pos, Direction.Axis axis) {
            this.nether.put(pos, axis);
            return this;
        }

        RecordingView end(BlockPos pos) {
            this.end.add(pos);
            return this;
        }

        @Override
        public boolean isEndPortal(BlockPos pos) {
            this.reads.add(pos);
            return this.end.contains(pos);
        }

        @Override
        public Direction.Axis netherPortalAxis(BlockPos pos) {
            this.reads.add(pos);
            return this.nether.get(pos);
        }

        boolean wasRead(BlockPos pos) {
            return this.reads.contains(pos);
        }

        /** The furthest any read strayed from {@code start}, per axis (Chebyshev). */
        int furthestRead(BlockPos start) {
            int furthest = 0;
            for (BlockPos pos : this.reads) {
                furthest = Math.max(furthest, Math.abs(pos.getX() - start.getX()));
                furthest = Math.max(furthest, Math.abs(pos.getY() - start.getY()));
                furthest = Math.max(furthest, Math.abs(pos.getZ() - start.getZ()));
            }
            return furthest;
        }
    }

    /** {@code cells} nether portal blocks in a row along X, all on one axis. */
    private static RecordingView netherRowX(BlockPos from, int cells, Direction.Axis axis) {
        RecordingView view = new RecordingView();
        for (int i = 0; i < cells; i++) {
            view.nether(from.add(i, 0, 0), axis);
        }
        return view;
    }

    /** {@code cells} nether portal blocks stacked up the Y column. */
    private static RecordingView netherColumnY(BlockPos from, int cells, Direction.Axis axis) {
        RecordingView view = new RecordingView();
        for (int i = 0; i < cells; i++) {
            view.nether(from.add(0, i, 0), axis);
        }
        return view;
    }

    private static RecordingView endRow(BlockPos from, int cells, Direction.Axis along) {
        RecordingView view = new RecordingView();
        for (int i = 0; i < cells; i++) {
            view.end(along == Direction.Axis.X ? from.add(i, 0, 0) : from.add(0, 0, i));
        }
        return view;
    }

    // === the shape the fill has always collected ========================

    @Test
    void aVanillaSizedOpeningCollectsEveryCell() {
        RecordingView view = new RecordingView();
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 3; y++) {
                view.nether(BASE.add(x, y, 0), Direction.Axis.X);
            }
        }
        assertEquals(6, PortalHelper.collectPortalArea(view, BASE).size());
    }

    @Test
    void aStartThatIsNoPortalAtAllCollectsNothing() {
        assertTrue(PortalHelper.collectPortalArea(new RecordingView(), BASE).isEmpty());
    }

    @Test
    void aCellOnTheOtherAxisIsNotPartOfTheArea() {
        RecordingView view = netherRowX(BASE, 3, Direction.Axis.X);
        view.nether(BASE.add(3, 0, 0), Direction.Axis.Z);
        assertEquals(Set.of(BASE, BASE.add(1, 0, 0), BASE.add(2, 0, 0)),
                PortalHelper.collectPortalArea(view, BASE));
    }

    @Test
    void anEndPortalStartTakesTheHorizontalWalk() {
        RecordingView view = new RecordingView();
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                view.end(BASE.add(x, 0, z));
            }
        }
        // A nether portal beside it belongs to a different walk entirely.
        view.nether(BASE.add(3, 0, 0), Direction.Axis.X);
        assertEquals(9, PortalHelper.collectPortalArea(view, BASE).size());
    }

    /**
     * Vanilla's widest opening is 21x21 — 441 cells. Capping the fill at
     * {@code MAX_PORTAL_BLOCKS} (128) would refuse a portal a player is
     * allowed to build, which is why the bound is a distance and not a count.
     */
    @Test
    void theWidestLegalVanillaOpeningIsCollectedWhole() {
        RecordingView view = new RecordingView();
        for (int x = 0; x < 21; x++) {
            for (int y = 0; y < 21; y++) {
                view.nether(BASE.add(x, y, 0), Direction.Axis.X);
            }
        }
        Set<BlockPos> area = PortalHelper.collectPortalArea(view, BASE);
        assertEquals(441, area.size(), "a legal 21x21 opening, started from its corner");
        assertTrue(area.size() > 128, "441 cells is what a MAX_PORTAL_BLOCKS cap would refuse");
        assertEquals(21, view.furthestRead(BASE),
                "the frame ring of the widest legal opening is 21 from the corner it started at");
    }

    // === the bound ======================================================

    @Test
    void aChainWalkingPastTheProvenFootprintIsRefusedNotTruncated() {
        RecordingView view = netherRowX(BASE, 200, Direction.Axis.X);
        assertTrue(PortalHelper.collectPortalArea(view, BASE).isEmpty(),
                "a truncated area is handed to PortalAdoption.adopt, which spends claimAttempt "
                + "on it and then reads its ring — the fill must refuse instead");
    }

    @Test
    void theFillNeverReadsBeyondTheProvenFootprint() {
        RecordingView view = netherRowX(BASE, 200, Direction.Axis.X);
        PortalHelper.collectPortalArea(view, BASE);
        assertFalse(view.wasRead(BASE.add(RADIUS + 1, 0, 0)),
                "the block one past the residency square was read — that read is the cold-chunk "
                + "generation the bound exists to stop, so it must not happen even to decide to "
                + "refuse");
        assertEquals(RADIUS, view.furthestRead(BASE),
                "the walk must reach the proven radius and stop exactly there");
    }

    @Test
    void aColumnWalkingUpwardsIsBoundedInYToo() {
        RecordingView view = netherColumnY(BASE, 400, Direction.Axis.X);
        assertTrue(PortalHelper.collectPortalArea(view, BASE).isEmpty());
        assertEquals(RADIUS, view.furthestRead(BASE),
                "Y costs no chunk read, but an unbounded walk is still an unbounded loop");
    }

    @Test
    void theEndPortalWalkTakesTheSameBoundOnX() {
        RecordingView view = endRow(BASE, 200, Direction.Axis.X);
        assertTrue(PortalHelper.collectPortalArea(view, BASE).isEmpty());
        assertEquals(RADIUS, view.furthestRead(BASE));
        assertFalse(view.wasRead(BASE.add(RADIUS + 1, 0, 0)));
    }

    @Test
    void theEndPortalWalkTakesTheSameBoundOnZ() {
        RecordingView view = endRow(BASE, 200, Direction.Axis.Z);
        assertTrue(PortalHelper.collectPortalArea(view, BASE).isEmpty());
        assertEquals(RADIUS, view.furthestRead(BASE));
        assertFalse(view.wasRead(BASE.add(0, 0, RADIUS + 1)));
    }

    /**
     * The exact number, from both sides. The residency gate proves
     * {@code [x - 23, x + 23] x [z - 23, z + 23]}; a walk that reads 24 is
     * outside what was proved, and one that stops at 22 refuses a portal it
     * had permission to read.
     */
    @Test
    void theBoundIsExactlyTheRadiusTheResidencyGateProves() {
        RecordingView reaches = netherRowX(BASE, RADIUS, Direction.Axis.X);
        assertEquals(RADIUS, PortalHelper.collectPortalArea(reaches, BASE).size(),
                "cells out to " + (RADIUS - 1) + " probe the frame at " + RADIUS
                        + ", which the gate proved resident");

        RecordingView oversteps = netherRowX(BASE, RADIUS + 1, Direction.Axis.X);
        assertTrue(PortalHelper.collectPortalArea(oversteps, BASE).isEmpty(),
                "one cell further and the frame probe lands at " + (RADIUS + 1)
                        + ", outside the square the gate proved");
    }

    @Test
    void theBoundIsCentredOnTheStartBlockNotTheWorldOrigin() {
        BlockPos far = new BlockPos(1_000_000, 70, -1_000_000);
        RecordingView view = netherRowX(far, 200, Direction.Axis.X);
        assertTrue(PortalHelper.collectPortalArea(view, far).isEmpty());
        assertEquals(RADIUS, view.furthestRead(far));
    }

    /**
     * Nothing about a legal portal changes. A walk started from the middle of
     * a wide opening reaches both edges, and every read stays well inside the
     * bound.
     */
    @Test
    void aLegalOpeningStartedFromItsMiddleIsUnaffected() {
        RecordingView view = new RecordingView();
        for (int x = -10; x <= 10; x++) {
            for (int y = -10; y <= 10; y++) {
                view.nether(BASE.add(x, y, 0), Direction.Axis.X);
            }
        }
        assertEquals(441, PortalHelper.collectPortalArea(view, BASE).size());
        assertEquals(11, view.furthestRead(BASE), "10 to the edge, 11 to its frame");
    }
}
