package com.customdimensions.server.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class MismatchTest {
    @Test
    void sameSolidityIsNoMismatch() {
        assertNull(Mismatch.kind(true, true, false, false, false, 0, true));
        assertNull(Mismatch.kind(false, false, false, true, false, 1, false));
    }

    @Test
    void solidNoiseOpenChunkIsRemovedOrFlooded() {
        assertEquals("removed", Mismatch.kind(true, false, false, false, false, 0, false));
        assertEquals("flooded", Mismatch.kind(true, false, true, false, false, 0, false));
        assertEquals("removed", Mismatch.kind(true, false, false, false, true, 0.5f, true), "a beard never explains a removal");
    }

    @Test
    void aNoiseBlockOverNoiseAirIsUnexplainedWithoutABeard() {
        assertEquals("unexplained", Mismatch.kind(false, true, false, false, false, 0, true));
        assertEquals("unexplained", Mismatch.kind(false, true, false, false, false, -0.3f, true), "a negative beard digs, never adds");
    }

    @Test
    void aStructureBeardExplainsTheNoiseBlockItAdds() {
        assertEquals("beard", Mismatch.kind(false, true, false, false, false, 0.2f, true));
        assertEquals("beard", Mismatch.kind(false, true, false, true, false, 0.2f, false), "the surface rules' block on a beard");
    }

    @Test
    void aPieceWinsOverABeard() {
        assertEquals("structure", Mismatch.kind(false, true, false, false, true, 0.2f, true));
    }

    @Test
    void foreignBlocksAreAddedOrFilled() {
        assertEquals("added", Mismatch.kind(false, true, false, false, false, 0, false));
        assertEquals("filled", Mismatch.kind(false, true, false, true, false, 0, false));
    }
}
