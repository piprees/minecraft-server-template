package com.customdimensions.client.realtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which field of view the destination pass asks for, recorded.
 *
 * <p>The defect this covers is a wrong value passed, so the fake records
 * every question as well as answering it: a pass that reads the option is
 * indistinguishable from one that reads the effective view whenever the two
 * agree, and they agree on every frame nobody is zooming.
 */
class SpectatorProjectionTest {

    /** A spyglass: the option is untouched and the effective view is a tenth of it. */
    private static final double OPTION = 70.0;
    private static final double ZOOMED = 7.0;

    @Test
    void aZoomedFrameIsDrawnAtTheZoomedView() {
        Fov view = new Fov(ZOOMED, OPTION, OPTION);
        assertEquals(ZOOMED, SpectatorProjection.render(view), 1.0e-9,
                "the destination was drawn at " + SpectatorProjection.render(view)
                        + " while the source frame used " + ZOOMED + "; asked " + view.asked);
    }

    /** The positive control: with nothing zooming, the two answers agree. */
    @Test
    void anUnzoomedFrameIsDrawnAtTheOption() {
        Fov view = new Fov(OPTION, OPTION, OPTION);
        assertEquals(OPTION, SpectatorProjection.render(view), 1.0e-9);
    }

    @Test
    void theDrawnViewIsTheOneTheZoomAppliesTo() {
        Fov view = new Fov(ZOOMED, OPTION, OPTION);
        SpectatorProjection.render(view);
        assertTrue(view.asked.contains("fov(changing)"),
                "the drawn view never asked for the changing field of view: " + view.asked);
    }

    /**
     * Culling at the zoomed view drops every chunk outside a spyglass's cone,
     * and they pop back in when it is lowered. Vanilla culls the source at the
     * wider of the unzoomed view and the option, so the destination does too.
     */
    @Test
    void aZoomedFrameIsCulledAtTheUnzoomedView() {
        Fov view = new Fov(ZOOMED, OPTION, OPTION);
        assertEquals(OPTION, SpectatorProjection.frustum(view), 1.0e-9,
                "culled at " + SpectatorProjection.frustum(view) + "; asked " + view.asked);
    }

    /** A narrowed option still culls at vanilla's 70-degree floor. */
    @Test
    void aNarrowOptionIsCulledAtTheWiderUnzoomedView() {
        Fov view = new Fov(30.0, 70.0, 30.0);
        assertEquals(70.0, SpectatorProjection.frustum(view), 1.0e-9,
                "culled at " + SpectatorProjection.frustum(view) + "; asked " + view.asked);
    }

    /** Submersion narrows the unzoomed view below the option, and the option wins. */
    @Test
    void anOptionWiderThanTheUnzoomedViewCullsAtTheOption() {
        Fov view = new Fov(90.0, 60.0, 110.0);
        assertEquals(110.0, SpectatorProjection.frustum(view), 1.0e-9,
                "culled at " + SpectatorProjection.frustum(view) + "; asked " + view.asked);
    }

    /** Vanilla's answers, and a record of which one was asked for. */
    private static final class Fov implements SpectatorProjection.ViewFov {

        private final List<String> asked = new ArrayList<>();
        private final double changing;
        private final double steady;
        private final double option;

        private Fov(double changing, double steady, double option) {
            this.changing = changing;
            this.steady = steady;
            this.option = option;
        }

        @Override
        public double fov(boolean changing) {
            this.asked.add(changing ? "fov(changing)" : "fov(steady)");
            return changing ? this.changing : this.steady;
        }

        @Override
        public double option() {
            this.asked.add("option");
            return this.option;
        }
    }
}
