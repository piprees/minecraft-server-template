package com.customdimensions.client.realtime;

import com.customdimensions.client.CompanionPayloads;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalFramesTest {

    private static final BlockPos NEXUS = new BlockPos(1500, 101, 1500);
    private static final BlockPos CRUCIBLE = new BlockPos(3260, 85, 2883);

    private static CompanionPayloads.PortalFrame frame(BlockPos origin, String destination, int dx) {
        return new CompanionPayloads.PortalFrame(
                Identifier.of(destination), Identifier.of("adventure:wide"), origin,
                List.of(origin), 2, 5, dx, -44, dx, -1, -1);
    }

    @BeforeEach
    void emptyStore() {
        PortalFrames.clear();
    }

    @AfterEach
    void leaveNothingBehind() {
        PortalFrames.clear();
    }

    @Test
    void anEmptyStoreAnswersNothingRatherThanNull() {
        assertEquals(0, PortalFrames.count());
        assertTrue(PortalFrames.all().isEmpty());
        assertTrue(PortalFrames.destinations().isEmpty());
        assertNull(PortalFrames.get(NEXUS));
    }

    @Test
    void aFrameIsFoundByTheKeyTheServerClearsWith() {
        CompanionPayloads.PortalFrame sent = frame(NEXUS, "adventure:the_crimson_nexus", -750);

        assertTrue(PortalFrames.accept(sent));

        assertSame(sent, PortalFrames.get(NEXUS));
        assertEquals(1, PortalFrames.count());
    }

    /**
     * The geometry does not move, so the server resends it only on a change.
     * A resend that changes nothing must not read as one, or everything built
     * from the frame is thrown away and rebuilt every pass.
     */
    @Test
    void aResendThatChangesNothingIsNotAChange() {
        PortalFrames.accept(frame(NEXUS, "adventure:the_crimson_nexus", -750));

        assertFalse(PortalFrames.accept(frame(NEXUS, "adventure:the_crimson_nexus", -750)));
    }

    @Test
    void aFrameWithADifferentTransformIsAChange() {
        PortalFrames.accept(frame(NEXUS, "adventure:the_crimson_nexus", -750));

        assertTrue(PortalFrames.accept(frame(NEXUS, "adventure:the_crimson_nexus", -1125)));
        assertEquals(-1125, PortalFrames.get(NEXUS).dx());
    }

    @Test
    void aNullFrameIsIgnoredRatherThanStored() {
        assertFalse(PortalFrames.accept(null));
        assertEquals(0, PortalFrames.count());
    }

    @Test
    void removeAndClearBothEmptyTheStore() {
        PortalFrames.accept(frame(NEXUS, "adventure:the_crimson_nexus", -750));
        PortalFrames.accept(frame(CRUCIBLE, "adventure:the_crucible", -2445));

        PortalFrames.remove(NEXUS);
        assertEquals(1, PortalFrames.count());
        assertNull(PortalFrames.get(NEXUS));

        PortalFrames.clear();
        assertEquals(0, PortalFrames.count());
    }

    @Test
    void removingSomethingThatWasNeverThereIsNotAnError() {
        PortalFrames.remove(NEXUS);
        PortalFrames.remove(null);
        assertEquals(0, PortalFrames.count());
    }

    /**
     * A world is stood up per DESTINATION, not per portal. Two portals onto
     * one dimension must ask for it once.
     */
    @Test
    void twoPortalsOntoOneDimensionNameItOnce() {
        PortalFrames.accept(frame(NEXUS, "adventure:the_crimson_nexus", -750));
        PortalFrames.accept(frame(CRUCIBLE, "adventure:the_crimson_nexus", -2445));

        assertEquals(2, PortalFrames.count());
        assertEquals(1, PortalFrames.destinations().size());
        assertTrue(PortalFrames.destinations()
                .contains(Identifier.of("adventure:the_crimson_nexus")));
    }
}
