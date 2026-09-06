package com.customdimensions.client.render;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The per-pose clip record. The emit line is on a wall-clock cadence and drops
 * to DEBUG after the first of a session, so this is the only surface a second
 * pose can be read from — anything a measurement needs has to be here.
 */
class ClipTallyTest {

    private static final BlockPos APERTURE = new BlockPos(3464, 80, 2592);

    @BeforeEach
    @AfterEach
    void emptyTally() {
        ClipTally.clear();
    }

    @Test
    void aPortalThatHasNotDrawnAnswersNothing() {
        assertNull(ClipTally.of(APERTURE));
    }

    /** The bucket counts exist here and nowhere else, so they must round-trip. */
    @Test
    void aLayerCarriesItsBucketCounts() {
        ClipTally.open(APERTURE, 1.0, 2.0, 3.0, -4.12, 8);
        ClipTally.layer(APERTURE, "solid", 6, 28, 857, 240, new int[] {1895, 415, 0, 4});

        ClipTally.Layer layer = ClipTally.of(APERTURE).layers().get(0);
        assertEquals(6, layer.kept(), "kept buckets were lost between the draw and the reader");
        assertEquals(28, layer.total(), "total buckets were lost between the draw and the reader");
        assertEquals(857, layer.quadsIn());
        assertEquals(240, layer.emitted());
    }

    /** Opening discards the previous frame, or a pose reads the one before it. */
    @Test
    void openingAgainDropsTheLastFramesLayers() {
        ClipTally.open(APERTURE, 1.0, 2.0, 3.0, -4.12, 8);
        ClipTally.layer(APERTURE, "solid", 6, 28, 857, 240, new int[4]);
        ClipTally.open(APERTURE, 9.0, 8.0, 7.0, -12.5, 8);

        ClipTally.Portal portal = ClipTally.of(APERTURE);
        assertEquals(0, portal.layers().size(), "a pose kept the previous pose's layers");
        assertEquals(-12.5, portal.camToPlane());
    }

    /** A layer arriving for an unopened portal is dropped, not a crash. */
    @Test
    void aLayerWithoutAPortalIsIgnored() {
        ClipTally.layer(APERTURE, "solid", 6, 28, 857, 240, new int[4]);
        assertNull(ClipTally.of(APERTURE));
    }

    /** The caller reuses its rejectedBy array every layer, so it has to be copied. */
    @Test
    void rejectedByIsCopiedRatherThanAliased() {
        int[] shared = {1, 2, 3, 4};
        ClipTally.open(APERTURE, 1.0, 2.0, 3.0, -4.12, 8);
        ClipTally.layer(APERTURE, "solid", 6, 28, 857, 240, shared);
        shared[0] = 999;

        int[] held = ClipTally.of(APERTURE).layers().get(0).rejectedBy();
        assertNotSame(shared, held);
        assertEquals(1, held[0], "the tally aliased the caller's array and every layer reads alike");
    }
}
