package com.customdimensions.client.realtime;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The seam that forces a box walk without moving the player.
 *
 * <p>A rebuild otherwise needs the chunk feed to change, which needs the
 * player to travel — and the only way to do that at a settled portal is to
 * leave its activation band.
 *
 * <p>What this covers is the bookkeeping. That the held projection keeps
 * drawing through the rebuild is a {@code ProjectionStore} property, and
 * constructing a {@code ClientProjection} needs {@code Blocks.AIR}, which
 * this module's test classpath cannot bootstrap.
 */
class RebuildSeamTest {

    @Test
    void anEmptyViewHasNothingToRebuild() {
        RealtimeView.clear();
        assertEquals(0, RealtimeView.rebuildAll(), "a view holding nothing reported work to do");
    }

    @Test
    void forgettingAnUnknownOpeningIsHarmless() {
        RealtimeView.clear();
        RealtimeView.forget(new BlockPos(3464, 80, 2592));
        RealtimeView.forget(null);
        assertEquals(0, RealtimeView.rebuildAll());
    }

    /** Clear is the same drop, so a world change cannot leave a bookmark behind. */
    @Test
    void clearDropsEveryBookmark() {
        RealtimeView.clear();
        assertEquals(0, RealtimeView.rebuildAll(), "clear left a bookmark behind");
    }
}
