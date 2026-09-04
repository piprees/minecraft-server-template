package com.customdimensions.client.realtime;

import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The light loop's order, which is the half of it that can be silently wrong.
 *
 * <p>Every inited section takes the next nibble off one shared stream, so a
 * step that consumes out of turn does not fail — it shifts every later
 * section's light onto the wrong Y and the column simply looks mislit.
 */
class LightStepsTest {

    private static BitSet bits(int... set) {
        BitSet out = new BitSet();
        for (int index : set) {
            out.set(index);
        }
        return out;
    }

    @Test
    void aSilentSectionIsSkippedAndConsumesNothing() {
        List<LightSteps.Step> steps = LightSteps.of(6, bits(0, 5), bits(2));
        assertEquals(List.of(
                new LightSteps.Step(0, true),
                new LightSteps.Step(2, false),
                new LightSteps.Step(5, true)), steps);
        assertEquals(2, LightSteps.nibblesNeeded(6, bits(0, 5), bits(2)));
    }

    @Test
    void sectionsComeOutInWireOrderLowestFirst() {
        List<LightSteps.Step> steps = LightSteps.of(4, bits(3, 1), bits());
        assertEquals(1, steps.get(0).section());
        assertEquals(3, steps.get(1).section());
    }

    /**
     * A section flagged both ways takes a nibble. Uninited is the absence of
     * data; inited is data, and data wins or the stream desynchronises.
     */
    @Test
    void aSectionFlaggedBothWaysStillTakesItsNibble() {
        assertEquals(List.of(new LightSteps.Step(1, true)),
                LightSteps.of(3, bits(1), bits(1)));
        assertEquals(1, LightSteps.nibblesNeeded(3, bits(1), bits(1)));
    }

    @Test
    void aFlagPastTheSectionCountIsNotReachedAtAll() {
        assertEquals(List.of(), LightSteps.of(2, bits(7), bits()));
        assertEquals(0, LightSteps.nibblesNeeded(2, bits(7), bits()));
    }

    @Test
    void nothingFlaggedIsNoStepsRatherThanAnEmptyNibblePerSection() {
        assertTrue(LightSteps.of(24, bits(), bits()).isEmpty());
        assertEquals(0, LightSteps.nibblesNeeded(24, bits(), bits()));
    }

    @Test
    void absentFlagsAreTreatedAsNothingSetRatherThanThrowing() {
        assertTrue(LightSteps.of(4, null, null).isEmpty());
        assertEquals(List.of(new LightSteps.Step(2, false)), LightSteps.of(4, null, bits(2)));
        assertEquals(List.of(new LightSteps.Step(2, true)), LightSteps.of(4, bits(2), null));
    }

    /** An overworld-height column: 24 sections plus one either side of it. */
    @Test
    void everySectionOfAFullColumnIsWalked() {
        BitSet all = new BitSet();
        all.set(0, 26);
        assertEquals(26, LightSteps.of(26, all, bits()).size());
        assertEquals(26, LightSteps.nibblesNeeded(26, all, bits()));
    }
}
