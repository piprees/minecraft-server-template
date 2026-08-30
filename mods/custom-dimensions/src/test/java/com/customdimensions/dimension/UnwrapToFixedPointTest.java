package com.customdimensions.dimension;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Termination rules of {@link DimensionManager#unwrapToFixedPoint}, the loop behind
 * {@code multiNoiseOf}'s biome-source unwrap.
 *
 * <p>Driven with strings rather than biome sources: {@code BiomeSource} initialises
 * {@code Registries}, and this suite cannot bootstrap Minecraft. Which wrapper each
 * step unwraps is covered by the running server, not here.
 */
class UnwrapToFixedPointTest {

    private static final UnaryOperator<String> DROP_FIRST = s -> s.substring(1);

    @Test
    void stopsAsSoonAsTheTargetIsReached() {
        assertEquals("x", DimensionManager.unwrapToFixedPoint("aaax", s -> s.equals("x"), DROP_FIRST));
    }

    @Test
    void neverStepsWhenTheStartAlreadyMatches() {
        assertEquals("x", DimensionManager.unwrapToFixedPoint("x", s -> s.equals("x"), s -> {
            throw new AssertionError("stepped past a source that was already the target");
        }));
    }

    @Test
    void stopsWhenAStepMakesNoProgress() {
        String opaque = "opaque";
        AtomicInteger steps = new AtomicInteger();
        String result = DimensionManager.unwrapToFixedPoint(opaque, s -> false, s -> {
            steps.incrementAndGet();
            return s;
        });
        assertSame(opaque, result);
        // One step proves it stopped on no-progress; without that it spends the whole bound.
        assertEquals(1, steps.get());
    }

    @Test
    void spendsNoMoreThanTheDepthBound() {
        AtomicInteger steps = new AtomicInteger();
        DimensionManager.unwrapToFixedPoint("start", s -> false, s -> {
            steps.incrementAndGet();
            return s + "+";
        });
        assertEquals(DimensionManager.MAX_UNWRAP_DEPTH, steps.get());
    }

    @Test
    void unwrapsThroughMoreThanOneLayer() {
        assertEquals("core", DimensionManager.unwrapToFixedPoint(
                "wrap:wrap:core", s -> !s.startsWith("wrap:"), s -> s.substring("wrap:".length())));
    }
}
