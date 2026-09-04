package com.customdimensions.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One reset per world, whoever notices first.
 *
 * <p>A crossing reaches the client as a join followed, in the same batch, by
 * the new world's portal frame and its first chunks. A second reset after
 * those have landed throws them away, and the server re-sends neither: it
 * holds the frame it last sent and the chunks it believes are held.
 */
class WorldBindingTest {

    private static final Object OVERWORLD = new Object();
    private static final Object AMPLIFIED_REACHES = new Object();

    @Test
    void aFreshBindingChangesOnItsFirstWorld() {
        assertTrue(new WorldBinding().bind(OVERWORLD));
    }

    @Test
    void theTickAfterAJoinDoesNotResetAgain() {
        WorldBinding binding = new WorldBinding();
        binding.bind(AMPLIFIED_REACHES);

        assertFalse(binding.bind(AMPLIFIED_REACHES),
                "the world the join already bound was reset a second time");
    }

    @Test
    void aDifferentWorldIsAChange() {
        WorldBinding binding = new WorldBinding();
        binding.bind(OVERWORLD);

        assertTrue(binding.bind(AMPLIFIED_REACHES));
    }

    /** Back and forth is the whole symptom: every crossing is a change. */
    @Test
    void comingBackIsAChangeToo() {
        WorldBinding binding = new WorldBinding();
        binding.bind(OVERWORLD);
        binding.bind(AMPLIFIED_REACHES);

        assertTrue(binding.bind(OVERWORLD));
    }

    /** A disconnect leaves nothing bound, so the next join is a change. */
    @Test
    void clearingReArmsTheSameWorld() {
        WorldBinding binding = new WorldBinding();
        binding.bind(OVERWORLD);
        binding.clear();

        assertTrue(binding.bind(OVERWORLD));
    }

    /**
     * {@code MinecraftClient.world} is null between worlds, and null is a
     * world like any other here — binding it must not re-arm the one before.
     */
    @Test
    void nullIsAWorldLikeAnyOther() {
        WorldBinding binding = new WorldBinding();
        binding.bind(null);

        assertFalse(binding.bind(null), "the gap between worlds reset twice");
        assertTrue(binding.bind(OVERWORLD));
    }
}
