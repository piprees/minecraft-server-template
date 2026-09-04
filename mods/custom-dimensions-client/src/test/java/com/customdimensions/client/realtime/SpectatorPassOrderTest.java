package com.customdimensions.client.realtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What one spectator pass does, recorded.
 *
 * <p>Three invariants live here that no other test in this module can reach.
 * A hidden opening must cost one quad and not a world render, so nothing may
 * be allocated, cleared or rendered before the gate has answered. The query
 * must be issued on a frame that drew NOTHING, or the gate refuses once and
 * never un-refuses. And the destination must be composited through the
 * opening's own quad rather than blitted to a corner — the corner is scaffold
 * and the quad is the product, and a recorder is the only place the difference
 * is visible without a screenshot.
 */
class SpectatorPassOrderTest {

    @Test
    void theGateIsAskedBeforeAnythingIsAllocated() {
        Recorder steps = new Recorder();
        SpectatorPass.runPass(steps);
        assertEquals("visible", steps.script.get(0),
                "something ran before the gate answered: " + steps.script);
    }

    @Test
    void anOccludedPortalRendersNothing() {
        Recorder steps = new Recorder();
        steps.visible = false;
        assertFalse(SpectatorPass.runPass(steps));
        assertEquals(List.of("visible"), steps.script);
    }

    @Test
    void aVisiblePortalRendersIntoAClearedTarget() {
        Recorder steps = new Recorder();
        assertTrue(SpectatorPass.runPass(steps));
        assertEquals(List.of("visible", "prepareTarget", "clearTarget", "renderDestination"),
                steps.script);
    }

    /**
     * The gate reads a query issued a frame ago. A frame that skipped the pass
     * must still issue one, or the answer never changes and a portal that
     * comes back into view stays refused for the rest of the session.
     */
    @Test
    void theQueryIsIssuedOnAFrameThatDrewNothing() {
        Recorder steps = new Recorder();
        SpectatorPass.runComposite(steps, true, false);
        assertEquals(List.of("issueOcclusionQuery"), steps.script);
    }

    @Test
    void noQueryIsIssuedForAPortalTheCheapTestsRejected() {
        Recorder steps = new Recorder();
        SpectatorPass.runComposite(steps, false, false);
        assertEquals(List.of(), steps.script);
    }

    @Test
    void theDestinationIsCompositedThroughTheQuad() {
        Recorder steps = new Recorder();
        SpectatorPass.runComposite(steps, true, true);
        assertEquals(List.of("issueOcclusionQuery", "compositeThroughQuad"), steps.script);
    }

    @Test
    void nothingIsCompositedWhenNothingWasDrawn() {
        Recorder steps = new Recorder();
        SpectatorPass.runComposite(steps, true, false);
        assertFalse(steps.script.contains("compositeThroughQuad"),
                "a frame with no render still composited: " + steps.script);
    }

    @Test
    void theCornerIsSuppressedWhenItIsOff() {
        Recorder steps = new Recorder();
        SpectatorPass.runCorner(steps, true, false);
        assertEquals(List.of(), steps.script);
    }

    @Test
    void theCornerStillDrawsWhileItIsOn() {
        Recorder steps = new Recorder();
        SpectatorPass.runCorner(steps, true, true);
        assertEquals(List.of("blitCorner"), steps.script);
    }

    @Test
    void theCornerDrawsNothingWhenNothingWasRendered() {
        Recorder steps = new Recorder();
        SpectatorPass.runCorner(steps, false, true);
        assertEquals(List.of(), steps.script);
    }

    private static final class Recorder implements SpectatorSteps, SpectatorPresent {

        private final List<String> script = new ArrayList<>();

        private boolean visible = true;

        @Override
        public boolean visible() {
            this.script.add("visible");
            return this.visible;
        }

        @Override
        public void prepareTarget() {
            this.script.add("prepareTarget");
        }

        @Override
        public void clearTarget() {
            this.script.add("clearTarget");
        }

        @Override
        public void renderDestination() {
            this.script.add("renderDestination");
        }

        @Override
        public void issueOcclusionQuery() {
            this.script.add("issueOcclusionQuery");
        }

        @Override
        public void compositeThroughQuad() {
            this.script.add("compositeThroughQuad");
        }

        @Override
        public void blitCorner() {
            this.script.add("blitCorner");
        }
    }
}
