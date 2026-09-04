package com.customdimensions.portal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether a portal cell may occupy a block.
 *
 * <p>Vanilla's {@code PortalForcer.isBlockStateValid} is
 * {@code isReplaceable() && getFluidState().isEmpty()}. The fluid conjunct is
 * what keeps an arrival out of a pond; the replaceable half is what keeps
 * grass and snow layers acceptable, so a rule that rejects everything is a
 * regression rather than a fix.
 *
 * <p>Driven by the three block reads rather than real states: a plain JUnit
 * JVM cannot initialise {@code Blocks} ("Not bootstrapped", the same wall
 * {@code ConfiguredBiomeSourceTest} documents).
 */
class PortalCellClearTest {

    // air, replaceable, fluid — as each block answers them in 1.21.1.
    private static final boolean[] AIR = {true, true, false};
    private static final boolean[] WATER_SOURCE = {false, true, true};
    private static final boolean[] LAVA_SOURCE = {false, true, true};
    private static final boolean[] SHORT_GRASS = {false, true, false};
    private static final boolean[] SNOW_LAYER = {false, true, false};
    private static final boolean[] WATERLOGGED_SLAB = {false, false, true};
    private static final boolean[] STONE = {false, false, false};

    private static boolean isClear(boolean[] block) {
        return PortalSite.isClear(block[0], block[1], block[2]);
    }

    @Test
    void airIsClear() {
        assertTrue(isClear(AIR));
    }

    @Test
    void waterSourceIsNotClear() {
        assertFalse(isClear(WATER_SOURCE),
                "water is replaceable, and an arrival placed in it is submerged");
    }

    @Test
    void lavaSourceIsNotClear() {
        assertFalse(isClear(LAVA_SOURCE));
    }

    @Test
    void shortGrassIsClear() {
        assertTrue(isClear(SHORT_GRASS),
                "replaceable plants stay acceptable, as they are in vanilla");
    }

    @Test
    void snowLayerIsClear() {
        assertTrue(isClear(SNOW_LAYER));
    }

    @Test
    void waterloggedBlockIsNotClear() {
        assertFalse(isClear(WATERLOGGED_SLAB));
    }

    @Test
    void stoneIsNotClear() {
        assertFalse(isClear(STONE));
    }
}
