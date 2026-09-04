package com.customdimensions.client.realtime;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Which sections of one fed column carry light, in wire order.
 *
 * <p>The nibble arrays arrive as one stream shared by every inited section, so
 * a section that consumes one out of turn shifts every later section's light
 * onto the wrong Y. A silent section — neither inited nor uninited — consumes
 * nothing at all, which is what keeps the stream in step.
 *
 * <p>No Minecraft types, so the order is asserted without a bootstrapped game.
 */
public final class LightSteps {

    /** One section to enqueue. {@code fromWire} false means an empty nibble. */
    public record Step(int section, boolean fromWire) {}

    private LightSteps() {}

    public static List<Step> of(int sections, BitSet inited, BitSet uninited) {
        List<Step> steps = new ArrayList<>();
        for (int section = 0; section < sections; section++) {
            boolean isInited = inited != null && inited.get(section);
            boolean isUninited = uninited != null && uninited.get(section);
            if (!isInited && !isUninited) {
                continue;
            }
            steps.add(new Step(section, isInited));
        }
        return steps;
    }

    /** How many nibble arrays the wire must carry for these flags. */
    public static int nibblesNeeded(int sections, BitSet inited, BitSet uninited) {
        int needed = 0;
        for (Step step : of(sections, inited, uninited)) {
            if (step.fromWire()) {
                needed++;
            }
        }
        return needed;
    }
}
