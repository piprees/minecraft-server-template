package com.customdimensions.dimension;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The [T34] rebuild's treatment of a wrapped source: a dimension with
 * {@code biomePatches} must come out of a rebuild still wearing its patches, in
 * order and field for field, and a dimension without them must come out exactly
 * as it did before.
 *
 * <p>Driven with records rather than biome sources. Two probes established that
 * this suite cannot bootstrap Minecraft: loading {@code PatchedBiomeSource} dies
 * with "Not bootstrapped (called from registry ResourceKey[minecraft:root /
 * minecraft:game_event])", and {@code Bootstrap.initialize()} dies with an
 * {@code IllegalAccessError} because Loom's access wideners are applied by the
 * loader, not by a plain JUnit JVM. {@code Patch} is modelled field for field
 * below so the fidelity these assert is the fidelity the real record has.
 */
class ConfiguredBiomeSourceTest {

    /** Mirrors PatchedBiomeSource.Patch field for field; the biome entry is its id. */
    private record Patch(String biome, int centerX, int centerZ, int radius,
                         Optional<String> replace, int blend, String scope, String shape) {
    }

    /** A biome source: the multi-noise core, plus the patches wrapped round it. */
    private record Source(String core, List<Patch> patches) {
    }

    private static final List<Patch> PATCHES = List.of(
            new Patch("minecraft:basalt_deltas", 0, 0, 240, Optional.empty(), 8, "clip", "circle"),
            new Patch("minecraft:crimson_forest", 512, -512, 96, Optional.of("minecraft:plains"),
                    16, "clip", "square"),
            new Patch("minecraft:warped_forest", -1024, 256, 64, Optional.of("*"), 0, "global", "circle"));

    private static final Source REBUILT = new Source("rebuilt", List.of());

    private static Source rebuild() {
        return REBUILT;
    }

    // What restore() hands in for a patched dimension: the rebuilt core, back
    // inside the patches read off the source it unwrapped.
    private static UnaryOperator<Source> rewrapWith(List<Patch> patches) {
        return rebuilt -> new Source(rebuilt.core(), patches);
    }

    private static List<Patch> reversed(List<Patch> patches) {
        List<Patch> out = new ArrayList<>(patches);
        java.util.Collections.reverse(out);
        return List.copyOf(out);
    }

    // --- restored(): the rebuild and the wrapper's round trip ----------------

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
        AtomicInteger rewraps = new AtomicInteger();

        Source restored = ConfiguredBiomeSource.restored(widened, 228, 20,
                ConfiguredBiomeSourceTest::rebuild, rebuilt -> {
                    rewraps.incrementAndGet();
                    assertSame(REBUILT, rebuilt, "re-wrap was handed the wrong source");
                    return new Source(rebuilt.core(), widened.patches());
                });

        assertEquals(1, rewraps.get(), "a widened patched source must be re-wrapped exactly once");
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

    // --- preserved(): the guard that refuses a rebuild which lost the patches -

    @Test
    void theSamePatchesInTheSameOrderArePreserved() {
        assertTrue(ConfiguredBiomeSource.preserved(PATCHES, List.copyOf(PATCHES)));
    }

    @Test
    void patchesHandedBackInADIFFERENTORDERAreNotPreserved() {
        assertFalse(ConfiguredBiomeSource.preserved(PATCHES, reversed(PATCHES)),
                "order is part of precedence: local patches resolve in config order");
    }

    @Test
    void aSinglePatchFieldChangingIsNotPreserved() {
        // Every field matters: a lost blend is a razor edge where a wobble
        // belongs, a lost scope turns a global swap into a local one.
        for (int i = 0; i < PATCHES.size(); i++) {
            Patch p = PATCHES.get(i);
            List<List<Patch>> mangled = List.of(
                    swap(i, new Patch("minecraft:plains", p.centerX(), p.centerZ(), p.radius(),
                            p.replace(), p.blend(), p.scope(), p.shape())),
                    swap(i, new Patch(p.biome(), p.centerX() + 1, p.centerZ(), p.radius(),
                            p.replace(), p.blend(), p.scope(), p.shape())),
                    swap(i, new Patch(p.biome(), p.centerX(), p.centerZ() + 1, p.radius(),
                            p.replace(), p.blend(), p.scope(), p.shape())),
                    swap(i, new Patch(p.biome(), p.centerX(), p.centerZ(), p.radius() + 1,
                            p.replace(), p.blend(), p.scope(), p.shape())),
                    swap(i, new Patch(p.biome(), p.centerX(), p.centerZ(), p.radius(),
                            Optional.of("minecraft:swamp"), p.blend(), p.scope(), p.shape())),
                    swap(i, new Patch(p.biome(), p.centerX(), p.centerZ(), p.radius(),
                            p.replace(), p.blend() + 1, p.scope(), p.shape())),
                    swap(i, new Patch(p.biome(), p.centerX(), p.centerZ(), p.radius(),
                            p.replace(), p.blend(), "global".equals(p.scope()) ? "clip" : "global",
                            p.shape())),
                    swap(i, new Patch(p.biome(), p.centerX(), p.centerZ(), p.radius(),
                            p.replace(), p.blend(), p.scope(),
                            "square".equals(p.shape()) ? "circle" : "square")));
            for (List<Patch> candidate : mangled) {
                assertFalse(ConfiguredBiomeSource.preserved(PATCHES, candidate),
                        "a changed field must not read as preserved: " + candidate.get(i));
            }
        }
    }

    @Test
    void anEmptyPatchListIsNotPreserved() {
        // The worst case the guard exists for: a re-wrap that quietly drops
        // every patch and reports success.
        assertFalse(ConfiguredBiomeSource.preserved(PATCHES, List.of()));
        assertFalse(ConfiguredBiomeSource.preserved(PATCHES, null));
    }

    @Test
    void anUnwrappedSourceHasNothingToPreserve() {
        // The 72: no wrapper went in, so nothing can have been lost, whatever
        // came back.
        assertTrue(ConfiguredBiomeSource.preserved(null, null));
        assertTrue(ConfiguredBiomeSource.preserved(null, PATCHES));
    }

    private static List<Patch> swap(int index, Patch replacement) {
        List<Patch> out = new ArrayList<>(PATCHES);
        out.set(index, replacement);
        return List.copyOf(out);
    }
}
