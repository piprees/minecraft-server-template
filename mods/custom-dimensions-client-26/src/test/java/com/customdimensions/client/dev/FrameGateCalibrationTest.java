package com.customdimensions.client.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The gate on real rig frames: each row is one terrain-pick camera's measures (its census and image, at render distance
 * 32, noon), labelled bad or good from the contact sheets. The gate's misses are part of the record: a frame that is
 * bad for its subject (an unclear structure, a readable but dull cave) is not a frame the gate can see.
 */
class FrameGateCalibrationTest {
    private record Frame(String name, boolean bad, boolean rejected, FrameGate.Measures measures) {
    }

    private static final List<Frame> FRAMES = List.of(
            new Frame("the_highland_crossing-underground-t1", true, true, new FrameGate.Measures(0.5, "minecraft:cobweb", 100.0, 0.0, 0.0, 0.0, 0.0, 143.3, 0.0)),
            new Frame("the_shallows-underground-t2", true, true, new FrameGate.Measures(0.5, "minecraft:cobweb", 100.0, 0.0, 0.0, 0.0, 0.0, 128.2, 0.3)),
            new Frame("the_gritlands-far", true, true, new FrameGate.Measures(512.0, "none", 0.0, 63.1, 22.6, 0.0, 0.0, 65.8, 71.3)),
            new Frame("the_gritlands-lip", true, true, new FrameGate.Measures(512.0, "none", 0.0, 73.5, 26.1, 0.0, 0.0, 61.7, 72.9)),
            new Frame("the_gritlands-underground-t7", true, false, new FrameGate.Measures(7.0, "minecraft:blackstone", 24.1, 1.3, 23.9, 23.7, 0.0, 62.6, 14.7)),
            new Frame("the_gritlands-surface-t2", true, false, new FrameGate.Measures(7.5, "minecraft:gravel", 18.8, 16.8, 0.0, 10.9, 0.1, 132.5, 14.7)),
            new Frame("the_crucible-surface-t2", true, true, new FrameGate.Measures(3.9, "minecraft:stone", 27.1, 0.0, 0.0, 0.0, 0.0, 85.8, 1.7)),
            new Frame("the_crucible-sky-t3", true, false, new FrameGate.Measures(8.2, "minecraft:short_grass", 11.2, 14.3, 0.0, 15.6, 0.0, 69.1, 20.6)),
            new Frame("the_greenreach-underground-t1", true, false, new FrameGate.Measures(4.3, "minecraft:deepslate", 19.1, 0.0, 0.0, 0.0, 0.0, 54.0, 2.2)),
            new Frame("the_crucible-surface-lm1", true, true, new FrameGate.Measures(7.6, "minecraft:water", 6.5, 0.0, 0.0, 0.0, 0.0, 26.8, 2.5)),
            new Frame("the_greenreach-far", true, false, new FrameGate.Measures(203.8, "none", 0.0, 39.2, 0.0, 0.0, 0.7, 136.2, 7.9)),
            new Frame("the_shallows-far", true, true, new FrameGate.Measures(192.4, "none", 0.0, 35.8, 0.0, 0.0, 44.3, 178.5, 9.3)),
            new Frame("the_crucible-far", true, true, new FrameGate.Measures(223.0, "none", 0.0, 42.5, 0.0, 0.0, 33.3, 82.5, 19.4)),
            new Frame("the_greenreach-feature-magma_block", true, false, new FrameGate.Measures(9.5, "minecraft:lava", 21.6, 0.0, 0.0, 0.0, 0.0, 62.5, 0.0)),
            new Frame("the_twisted_groves-underground-t1", true, true, new FrameGate.Measures(5.0, "minecraft:sculk_vein", 13.8, 0.0, 0.0, 0.0, 0.0, 4.7, 0.0)),
            new Frame("the_twisted_groves-surface-t2", true, true, new FrameGate.Measures(6.6, "minecraft:grass_block", 22.1, 20.8, 0.0, 0.3, 0.0, 11.6, 25.1)),
            new Frame("the_twisted_groves-feature-clay", true, true, new FrameGate.Measures(10.2, "none", 0.0, 0.0, 0.0, 0.0, 0.0, 15.2, 0.0)),
            new Frame("the_basalt_spires-surface-t3", true, true, new FrameGate.Measures(14.3, "minecraft:grass_block", 18.8, 0.0, 0.0, 3.4, 0.0, 9.7, 26.9)),
            new Frame("the_darkpine_depths-feature-creaking_heart", true, false, new FrameGate.Measures(7.9, "none", 0.0, 0.0, 0.0, 0.0, 0.0, 54.3, 1.8)),
            new Frame("the_basalt_spires-surface-t2", true, true, new FrameGate.Measures(8.5, "minecraft:blackstone", 10.6, 0.0, 0.0, 0.9, 0.0, 23.9, 17.8)),
            new Frame("the_crimson_nexus-far", false, false, new FrameGate.Measures(78.1, "none", 0.0, 0.9, 0.0, 0.0, 0.0, 47.4, 17.4)),
            new Frame("the_crystal_vale-far", false, true, new FrameGate.Measures(191.8, "none", 0.0, 11.8, 0.0, 0.0, 7.7, 95.8, 0.1)),
            new Frame("the_frozen_hearth-far", false, false, new FrameGate.Measures(196.1, "none", 0.0, 32.2, 0.0, 0.0, 0.0, 202.7, 12.7)),
            new Frame("the_scorched_mesa-far", false, false, new FrameGate.Measures(215.8, "none", 0.0, 25.7, 0.0, 0.0, 0.0, 146.8, 22.3)),
            new Frame("the_highland_crossing-far", false, true, new FrameGate.Measures(350.0, "none", 0.0, 13.4, 0.0, 0.0, 5.6, 134.4, 11.5)),
            new Frame("the_crystal_vale-surface-t1", false, false, new FrameGate.Measures(11.0, "minecraft:short_grass", 12.0, 0.0, 0.0, 2.2, 0.0, 82.4, 3.5)),
            new Frame("the_frozen_hearth-surface-t3", false, false, new FrameGate.Measures(23.9, "minecraft:ice", 18.8, 44.4, 0.0, 0.0, 0.0, 162.0, 4.0)),
            new Frame("the_scorched_mesa-surface-t1", false, false, new FrameGate.Measures(17.7, "minecraft:red_sand", 18.8, 27.3, 0.0, 6.0, 0.0, 131.6, 1.3)),
            new Frame("the_crimson_nexus-surface-t1", false, false, new FrameGate.Measures(19.3, "minecraft:dead_bush", 15.9, 33.0, 0.0, 8.0, 0.0, 116.9, 2.3)),
            new Frame("the_highland_crossing-surface-t1", false, false, new FrameGate.Measures(5.6, "minecraft:grass_block", 24.1, 0.0, 0.0, 7.7, 0.0, 107.2, 0.1)),
            new Frame("the_scorched_mesa-surface-t2", false, true, new FrameGate.Measures(21.0, "minecraft:dead_bush", 17.1, 24.9, 0.0, 17.5, 16.4, 129.2, 1.5)),
            new Frame("the_frozen_hearth-surface-t1", false, false, new FrameGate.Measures(7.2, "minecraft:grass_block", 16.6, 34.4, 0.0, 1.3, 0.0, 170.3, 0.4)));

    @Test
    void eachFrameGetsItsRecordedVerdict() {
        for (Frame f : FRAMES) {
            assertEquals(f.rejected(), !FrameGate.reasons(f.measures(), false).isEmpty(), f.name() + " " + FrameGate.reasons(f.measures(), false));
        }
    }

    @Test
    void confusionCounts() {
        long badRejected = FRAMES.stream().filter(f -> f.bad() && f.rejected()).count();
        long goodRejected = FRAMES.stream().filter(f -> !f.bad() && f.rejected()).count();
        assertEquals(20, FRAMES.stream().filter(Frame::bad).count());
        assertEquals(13, badRejected);
        assertEquals(12, FRAMES.stream().filter(f -> !f.bad()).count());
        assertEquals(3, goodRejected);
    }
}
