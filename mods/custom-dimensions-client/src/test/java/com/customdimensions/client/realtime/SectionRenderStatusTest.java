package com.customdimensions.client.realtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The loop that makes a fed chunk renderable, asserted against the sequence
 * 1.21.1's own {@code scheduleRenderChunk} emits.
 *
 * <p>The positive control is the whole test: a section holding blocks MUST be
 * marked not-empty at its own section Y and MUST get a render scheduled there.
 * Asserting only that nothing throws passes on an empty method, which is
 * exactly the defect — every section flagged not-ready and a pass drawing sky.
 */
class SectionRenderStatusTest {

    /** A 24-section overworld column: bottom Y -64, so bottom section -4. */
    private static final int OVERWORLD_BOTTOM_SECTION = -4;

    private static final class Recorder implements SectionRenderStatus.Sink {

        final List<String> calls = new ArrayList<>();

        @Override
        public void sectionStatus(int sectionY, boolean empty) {
            calls.add("status " + sectionY + " " + empty);
        }

        @Override
        public void scheduleBlockRenders(int chunkX, int sectionY, int chunkZ) {
            calls.add("render " + chunkX + " " + sectionY + " " + chunkZ);
        }
    }

    /** {@code sectionIndexToCoord} — index 0 is the bottom section, not Y 0. */
    @Test
    void anIndexIsOffsetByTheBottomSection() {
        assertEquals(-4, SectionRenderStatus.sectionCoord(OVERWORLD_BOTTOM_SECTION, 0));
        assertEquals(4, SectionRenderStatus.sectionCoord(OVERWORLD_BOTTOM_SECTION, 8));
        assertEquals(19, SectionRenderStatus.sectionCoord(OVERWORLD_BOTTOM_SECTION, 23));
        assertEquals(0, SectionRenderStatus.sectionCoord(0, 0));
    }

    /**
     * Both calls, both per section, status before render — vanilla's order,
     * because a render scheduled against a section still flagged not-ready is
     * dropped by the chunk builder.
     */
    @Test
    void everySectionGetsBothCallsInVanillasOrder() {
        Recorder sink = new Recorder();
        boolean[] empty = new boolean[24];
        SectionRenderStatus.mark(217, 162, OVERWORLD_BOTTOM_SECTION, empty, sink);

        assertEquals(48, sink.calls.size(), "24 sections owe 48 calls");
        assertEquals("status -4 false", sink.calls.get(0));
        assertEquals("render 217 -4 162", sink.calls.get(1));
        assertEquals("status 19 false", sink.calls.get(46));
        assertEquals("render 217 19 162", sink.calls.get(47));
    }

    /**
     * The positive control: a section with blocks in it is marked not-empty at
     * its own Y. Getting this wrong by one index puts the terrain's status on
     * the section above it, which is invisible until nothing draws.
     */
    @Test
    void aSectionHoldingBlocksIsMarkedNotEmptyAtItsOwnY() {
        Recorder sink = new Recorder();
        boolean[] empty = new boolean[24];
        java.util.Arrays.fill(empty, true);
        empty[8] = false;

        SectionRenderStatus.mark(0, 0, OVERWORLD_BOTTOM_SECTION, empty, sink);

        assertTrue(sink.calls.contains("status 4 false"),
                "the one section holding blocks was never marked renderable");
        assertTrue(sink.calls.contains("render 0 4 0"),
                "no render was scheduled for the section holding blocks");
        assertTrue(sink.calls.contains("status 3 true"),
                "an empty section below it was not marked empty");
    }

    /** Chunk coordinates are carried through unchanged, negatives included. */
    @Test
    void theChunksOwnCoordinatesReachEveryRender() {
        Recorder sink = new Recorder();
        SectionRenderStatus.mark(-47, -47, 0, new boolean[2], sink);

        assertEquals(
                List.of("status 0 false", "render -47 0 -47",
                        "status 1 false", "render -47 1 -47"),
                sink.calls);
    }

    @Test
    void aMissingArrayOrSinkIsAnsweredNotThrown() {
        Recorder sink = new Recorder();
        SectionRenderStatus.mark(0, 0, 0, null, sink);
        SectionRenderStatus.mark(0, 0, 0, new boolean[4], null);
        assertTrue(sink.calls.isEmpty());
    }
}
