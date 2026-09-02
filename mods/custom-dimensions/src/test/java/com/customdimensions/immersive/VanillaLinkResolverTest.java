package com.customdimensions.immersive;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The decision behind "no point showing a preview of somewhere we are not".
 *
 * <p>{@link VanillaLinkResolver#chooseNearest} is the whole ruling in one
 * pure function: it picks what {@code PortalForcer.getPortalPos} would pick
 * out of the portals it can see, and returns nothing when an unread chunk
 * could hold something nearer. The half that reads chunks and points of
 * interest is only covered by inspection — it needs a live world.
 */
class VanillaLinkResolverTest {

    /** No unread chunk is closer than any candidate. */
    private static final double ALL_READ = Double.MAX_VALUE;

    @Test
    void picksTheNearestPortal() {
        BlockPos search = new BlockPos(0, 64, 0);
        BlockPos near = new BlockPos(10, 64, 0);
        BlockPos far = new BlockPos(80, 64, 0);

        assertEquals(near, VanillaLinkResolver.chooseNearest(Arrays.asList(far, near), search, ALL_READ));
    }

    /** Vanilla breaks a distance tie on the lowest Y; so does this. */
    @Test
    void breaksDistanceTiesOnLowestY() {
        BlockPos search = new BlockPos(0, 64, 0);
        BlockPos overhead = new BlockPos(0, 74, 0);
        BlockPos aside = new BlockPos(10, 64, 0);

        // Both are 10 blocks away. The lower one wins.
        assertEquals(aside, VanillaLinkResolver.chooseNearest(
                Arrays.asList(overhead, aside), search, ALL_READ));
    }

    @Test
    void noPortalMeansNoAnswer() {
        assertNull(VanillaLinkResolver.chooseNearest(
                Collections.emptyList(), new BlockPos(0, 64, 0), ALL_READ));
    }

    /**
     * An unread chunk closer than the best candidate makes the answer "not
     * sure", which is a refusal, not a fallback to the visible portal.
     */
    @Test
    void refusesWhenAnUnreadChunkCouldHoldSomethingNearer() {
        BlockPos search = new BlockPos(0, 64, 0);
        BlockPos candidate = new BlockPos(100, 64, 0);
        double unreadAt50Blocks = 50.0 * 50.0;

        assertNull(VanillaLinkResolver.chooseNearest(
                Collections.singletonList(candidate), search, unreadAt50Blocks));
    }

    @Test
    void acceptsWhenEveryUnreadChunkIsFurtherAway() {
        BlockPos search = new BlockPos(0, 64, 0);
        BlockPos candidate = new BlockPos(30, 64, 0);
        double unreadAt50Blocks = 50.0 * 50.0;

        assertEquals(candidate, VanillaLinkResolver.chooseNearest(
                Collections.singletonList(candidate), search, unreadAt50Blocks));
    }

    /**
     * The candidate distance is 3D and the unread bound is horizontal, so a
     * candidate high overhead is refused where a level one would be kept. The
     * asymmetry errs towards refusing, which is the outcome the ruling wants.
     */
    @Test
    void heightCountsAgainstACandidateButNotAgainstTheBound() {
        BlockPos search = new BlockPos(0, 64, 0);
        double unreadAt100Blocks = 100.0 * 100.0;
        BlockPos overhead = new BlockPos(0, 200, 0);
        BlockPos level = new BlockPos(0, 64, 90);

        assertNull(VanillaLinkResolver.chooseNearest(
                Collections.singletonList(overhead), search, unreadAt100Blocks));
        assertEquals(level, VanillaLinkResolver.chooseNearest(
                Collections.singletonList(level), search, unreadAt100Blocks));
    }

    @Test
    void aColumnInsideAChunkIsZeroAwayFromIt() {
        assertEquals(0.0, VanillaLinkResolver.nearestSquaredHorizontal(0, 0, 7, 9));
        assertEquals(0.0, VanillaLinkResolver.nearestSquaredHorizontal(-1, -1, -16, -1));
    }

    @Test
    void measuresToTheNearestEdgeOfAChunk() {
        // Chunk 2 spans x 32..47; a column at x=0 is 32 blocks from its edge.
        assertEquals(32.0 * 32.0, VanillaLinkResolver.nearestSquaredHorizontal(2, 0, 0, 0));
        // Diagonal: 32 out on x, 32 out on z.
        assertEquals(32.0 * 32.0 * 2, VanillaLinkResolver.nearestSquaredHorizontal(2, 2, 0, 0));
    }

    @Test
    void measuresNegativeChunksFromTheirFarEdge() {
        // Chunk -1 spans x -16..-1; a column at x=0 is 1 block from its edge.
        assertEquals(1.0, VanillaLinkResolver.nearestSquaredHorizontal(-1, 0, 0, 0));
    }

    /** The ordering is total, so the same candidates always resolve the same way. */
    @Test
    void theChoiceDoesNotDependOnCandidateOrder() {
        BlockPos search = new BlockPos(0, 64, 0);
        List<BlockPos> forwards = Arrays.asList(
                new BlockPos(20, 64, 0), new BlockPos(5, 40, 0), new BlockPos(5, 30, 0));
        List<BlockPos> backwards = Arrays.asList(
                new BlockPos(5, 30, 0), new BlockPos(5, 40, 0), new BlockPos(20, 64, 0));

        assertEquals(VanillaLinkResolver.chooseNearest(forwards, search, ALL_READ),
                VanillaLinkResolver.chooseNearest(backwards, search, ALL_READ));
    }
}
