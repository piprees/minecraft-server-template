package com.customdimensions.dimension;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The [T34] rebuild's treatment of a wrapped source: a dimension with
 * {@code biomePatches} must come out of a rebuild still wearing its patches, in
 * order, and a dimension without them must come out exactly as it did before.
 *
 * <p>Driven with a record rather than biome sources: {@code BiomeSource}
 * initialises {@code Registries}, and this suite cannot bootstrap Minecraft —
 * a probe confirmed it dies with "Not bootstrapped". Which wrapper class the
 * caller unwraps is covered by the running server, not here.
 */
class ConfiguredBiomeSourceTest {

    /** A biome source: the multi-noise core, plus the patches wrapped round it. */
    private record Source(String core, List<String> patches) {
    }

    private static final List<String> PATCHES = List.of("stamp", "clipped_swap", "global_swap");
    private static final Source REBUILT = new Source("rebuilt", List.of());

    private static Source rebuild() {
        return REBUILT;
    }

    // What restore() hands in for a patched dimension: the rebuilt core, back
    // inside the patches read off the source it unwrapped.
    private static UnaryOperator<Source> rewrapWith(List<String> patches) {
        return rebuilt -> new Source(rebuilt.core(), patches);
    }

    @Test
    void aWidenedPatchedSourceIsRebuiltAndKeepsEveryPatchInOrder() {
        Source widened = new Source("widened", PATCHES);

        Source restored = ConfiguredBiomeSource.restored(
                widened, 228, 20, ConfiguredBiomeSourceTest::rebuild, rewrapWith(widened.patches()));

        assertEquals("rebuilt", restored.core(), "the injected palette must not survive the rebuild");
        assertEquals(PATCHES, restored.patches(), "the patches must survive it, in config order");
    }

    @Test
    void aPatchedSourceNobodyWidenedIsHandedBackUntouched() {
        Source patched = new Source("own", PATCHES);

        Source restored = ConfiguredBiomeSource.restored(patched, 20, 20, () -> {
            throw new AssertionError("rebuilt a source nothing had widened");
        }, rewrapWith(patched.patches()));

        assertSame(patched, restored);
    }

    @Test
    void aWidenedUnpatchedSourceIsRebuiltAndNothingIsWrappedRoundIt() {
        Source widened = new Source("widened", List.of());

        Source restored = ConfiguredBiomeSource.restored(
                widened, 228, 20, ConfiguredBiomeSourceTest::rebuild, UnaryOperator.identity());

        assertSame(REBUILT, restored, "the 72 unpatched dimensions must get the bare rebuild");
    }

    @Test
    void anUnpatchedSourceNobodyWidenedIsHandedBackUntouched() {
        Source own = new Source("own", List.of());

        Source restored = ConfiguredBiomeSource.restored(own, 20, 20, () -> {
            throw new AssertionError("rebuilt a source nothing had widened");
        }, UnaryOperator.identity());

        assertSame(own, restored);
    }

    @Test
    void theWrapperGoesRoundTheREBUILDNeverRoundTheSourceItReplaced() {
        // The bug this guards is silent: wrapping the widened source instead of
        // the rebuilt one leaves the injected palette in place and still looks
        // like a patched dimension on every log line.
        Source widened = new Source("widened", PATCHES);

        Source restored = ConfiguredBiomeSource.restored(widened, 228, 20,
                ConfiguredBiomeSourceTest::rebuild, rebuilt -> {
                    assertSame(REBUILT, rebuilt, "re-wrap was handed the wrong source");
                    return new Source(rebuilt.core(), widened.patches());
                });

        assertEquals("rebuilt", restored.core());
    }

    @Test
    void oneBiomeOverThePaletteIsAlreadyWidening() {
        // getBiomes() is a distinct SET, so an injection that adds a single
        // biome moves the count by one and nothing else reports it.
        Source widened = new Source("widened", PATCHES);
        AtomicInteger rebuilds = new AtomicInteger();

        assertSame(widened, ConfiguredBiomeSource.restored(widened, 20, 20,
                () -> { rebuilds.incrementAndGet(); return REBUILT; }, rewrapWith(PATCHES)));
        assertEquals(0, rebuilds.get());

        assertEquals("rebuilt", ConfiguredBiomeSource.restored(widened, 21, 20,
                () -> { rebuilds.incrementAndGet(); return REBUILT; }, rewrapWith(PATCHES)).core());
        assertEquals(1, rebuilds.get());
    }
}
