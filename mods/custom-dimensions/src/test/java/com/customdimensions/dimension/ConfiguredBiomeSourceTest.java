package com.customdimensions.dimension;

import com.customdimensions.dimension.ConfiguredBiomeSource.Layers;
import com.customdimensions.dimension.ConfiguredBiomeSource.Outcome;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every decision the [T34] rebuild makes: which source carries the palette, when
 * to rebuild, what to put back round it, and when to refuse.
 *
 * <p>Driven through a {@link Layers} implementation over records. Two probes
 * established that this suite cannot bootstrap Minecraft — loading
 * {@code PatchedBiomeSource} dies with "Not bootstrapped (called from registry
 * ResourceKey[minecraft:root / minecraft:game_event])", and
 * {@code Bootstrap.initialize()} dies with an {@code IllegalAccessError} because
 * Loom's access wideners are applied by the loader, not a plain JUnit JVM — so
 * the collaborator is injected rather than constructed. {@code Patch} is modelled
 * field for field, so the fidelity these assert is the real record's.
 */
class ConfiguredBiomeSourceTest {

    /** Mirrors PatchedBiomeSource.Patch field for field; the biome entry is its id. */
    private record Patch(String biome, int centerX, int centerZ, int radius,
                         Optional<String> replace, int blend, String scope, String shape) {
    }

    /**
     * A biome source: a multi-noise core, this mod's patch layers over it
     * (outermost first), and another mod's layers, which cannot be re-wrapped.
     */
    private record Source(String core, List<List<Patch>> layers, List<String> foreign) {
        static Source of(String core, List<Patch>... layers) {
            return new Source(core, List.of(layers), List.of());
        }

        static Source withInjector(String core, List<Patch>... layers) {
            return new Source(core, List.of(layers), List.of(INJECTOR));
        }
    }

    /** Stands for Lithostitched's InjectorBiomeSource: real, and un-re-wrappable. */
    private static final String INJECTOR =
            "dev.worldgen.lithostitched.impl.worldgen.biomeinjector.internal.InjectorBiomeSource";

    private static final List<Patch> STAMPS = List.of(
            new Patch("minecraft:basalt_deltas", 0, 0, 240, Optional.empty(), 8, "clip", "circle"),
            new Patch("minecraft:crimson_forest", 512, -512, 96, Optional.of("minecraft:plains"),
                    16, "clip", "square"),
            new Patch("minecraft:warped_forest", -1024, 256, 64, Optional.of("*"), 0, "global", "circle"));
    private static final List<Patch> SECOND_LAYER = List.of(
            new Patch("nullscape:crystal_peaks", -240, 200, 110, Optional.empty(), 32, "clip", "circle"));

    private static final Source PATCHED_WIDENED = Source.of("widened", STAMPS);
    private static final Source PATCHED_OWN = Source.of("own", STAMPS);
    private static final Source BARE_WIDENED = Source.of("widened");
    private static final Source BARE_OWN = Source.of("own");

    /**
     * The production shape, over records: the core is the source stripped of its
     * layers, a rebuild renames the core, and re-wrapping folds the layers back.
     */
    private static class Model implements Layers<Source> {
        int rebuilds;
        int rewraps;
        final Set<Outcome> reported = EnumSet.noneOf(Outcome.class);
        private final int reported0;
        private final int palette0;
        private final boolean reachableCore;
        /** Set to drop, reverse or empty the layers a re-wrap puts back. */
        java.util.function.UnaryOperator<List<List<Patch>>> mangle = l -> l;

        Model(int reported, int palette) {
            this(reported, palette, true);
        }

        Model(int reported, int palette, boolean reachableCore) {
            this.reported0 = reported;
            this.palette0 = palette;
            this.reachableCore = reachableCore;
        }

        @Override
        public Source core(Source source) {
            return this.reachableCore ? new Source(source.core(), List.of(), List.of()) : null;
        }

        @Override
        public List<String> foreignLayers(Source source) {
            return source.foreign();
        }

        @Override
        public int reported(Source core) {
            return this.reported0;
        }

        @Override
        public int palette(Source core) {
            return this.palette0;
        }

        @Override
        public Source rebuild(Source core) {
            this.rebuilds++;
            return new Source("rebuilt", List.of(), List.of());
        }

        /** A rebuild carries this mod's layers and no other mod's: that is the drop. */
        @Override
        public Source rewrap(Source source, Source rebuiltCore) {
            this.rewraps++;
            return new Source(rebuiltCore.core(), this.mangle.apply(source.layers()), List.of());
        }

        @Override
        public boolean preserved(Source source, Source rebuilt) {
            return ConfiguredBiomeSource.preserved(source.layers(), rebuilt.layers());
        }

        @Override
        public void report(Outcome outcome, Source source, Source core) {
            this.reported.add(outcome);
        }
    }

    // --- the rebuild and the layers' round trip ------------------------------

    @Test
    void aWidenedPatchedSourceIsRebuiltAndKeepsEveryPatchInOrder() {
        Model model = new Model(228, 20);

        Source restored = ConfiguredBiomeSource.restored(PATCHED_WIDENED, model);

        assertEquals("rebuilt", restored.core(), "the injected palette must not survive the rebuild");
        assertEquals(List.of(STAMPS), restored.layers(), "the patches must survive it, in config order");
        assertEquals(1, model.rebuilds);
        assertEquals(1, model.rewraps);
        assertTrue(model.reported.contains(Outcome.REBUILT));
        assertFalse(model.reported.contains(Outcome.FOREIGN_LAYER_DROPPED),
                "nothing foreign was in this stack, so nothing was dropped");
    }

    @Test
    void nestedPatchLayersAllComeBack() {
        Model model = new Model(228, 20);

        Source restored = ConfiguredBiomeSource.restored(
                Source.of("widened", STAMPS, SECOND_LAYER), model);

        assertEquals(List.of(STAMPS, SECOND_LAYER), restored.layers(),
                "every layer comes back, outermost first");
    }

    @Test
    void aPatchedSourceNobodyWidenedIsHandedBackUntouched() {
        Model model = new Model(20, 20);

        assertSame(PATCHED_OWN, ConfiguredBiomeSource.restored(PATCHED_OWN, model));
        assertEquals(0, model.rebuilds, "rebuilt a source nothing had widened");
        assertEquals(0, model.rewraps);
    }

    @Test
    void aWidenedUnpatchedSourceIsRebuiltAndNothingIsWrappedRoundIt() {
        Model model = new Model(228, 20);

        Source restored = ConfiguredBiomeSource.restored(BARE_WIDENED, model);

        assertEquals("rebuilt", restored.core(), "the 72 unpatched dimensions must get the rebuild");
        assertEquals(List.of(), restored.layers(), "and nothing wrapped round it");
    }

    @Test
    void anUnpatchedSourceNobodyWidenedIsHandedBackUntouched() {
        Model model = new Model(20, 20);

        assertSame(BARE_OWN, ConfiguredBiomeSource.restored(BARE_OWN, model));
        assertEquals(0, model.rebuilds, "rebuilt a source nothing had widened");
    }

    @Test
    void thePaletteIsMeasuredOnTheCORENeverOnTheWrapper() {
        // A wrapper's own biome set counts its patch biomes too, so measuring it
        // would read a stamp as an injection and rebuild on every single load.
        List<Source> measured = new ArrayList<>();
        Model model = new Model(20, 20) {
            @Override
            public int reported(Source core) {
                measured.add(core);
                return super.reported(core);
            }

            @Override
            public int palette(Source core) {
                measured.add(core);
                return super.palette(core);
            }
        };

        ConfiguredBiomeSource.restored(PATCHED_WIDENED, model);

        assertFalse(measured.isEmpty(), "the palette was never measured");
        for (Source seen : measured) {
            assertEquals(List.of(), seen.layers(),
                    "the palette must be measured on the core, not on a layered source");
        }
    }

    @Test
    void oneBiomeOverThePaletteIsAlreadyWidening() {
        // getBiomes() is a distinct SET, so an injection that adds a single
        // biome moves the count by one and nothing else reports it.
        Model equal = new Model(20, 20);
        assertSame(PATCHED_WIDENED, ConfiguredBiomeSource.restored(PATCHED_WIDENED, equal));
        assertEquals(0, equal.rebuilds);

        Model over = new Model(21, 20);
        assertEquals("rebuilt", ConfiguredBiomeSource.restored(PATCHED_WIDENED, over).core());
        assertEquals(1, over.rebuilds);
    }

    // --- refusals ------------------------------------------------------------

    @Test
    void aSourceWithNoReachableCoreIsHandedBackUntouched() {
        Model model = new Model(228, 20, false);

        assertSame(PATCHED_WIDENED, ConfiguredBiomeSource.restored(PATCHED_WIDENED, model));
        assertEquals(0, model.rebuilds);
    }

    @Test
    void aWidenedSourceIsRebuiltThroughAnotherModsLayerAndOurPatchesComeBack() {
        // patched > lithostitched injector > multi_noise. Refusing here let a
        // foreign wrapper decide whether the [T34] defence ran at all.
        Model model = new Model(228, 20);
        Source withInjector = Source.withInjector("widened", STAMPS);

        Source restored = ConfiguredBiomeSource.restored(withInjector, model);

        assertEquals("rebuilt", restored.core(), "a foreign layer must not veto the rebuild");
        assertEquals(List.of(STAMPS), restored.layers(),
                "this dimension's own patches must survive the rebuild, in config order");
        assertEquals(1, model.rebuilds);
    }

    @Test
    void droppingAnotherModsLayerIsReportedAsItsOwnOutcome() {
        // A reader of the log must be able to tell the two rebuilds apart: one
        // costs another mod its injections, the other costs nothing.
        Model model = new Model(228, 20);

        ConfiguredBiomeSource.restored(Source.withInjector("widened", STAMPS), model);

        assertTrue(model.reported.contains(Outcome.FOREIGN_LAYER_DROPPED));
        assertFalse(model.reported.contains(Outcome.REBUILT),
                "a dropped foreign layer must not report as an ordinary rebuild");
    }

    @Test
    void anotherModsLayerIsLEFTALONEWhenNothingWidenedThePalette() {
        // The drop is a consequence of undoing a [T34] injection, never routine:
        // no injection to undo, no rebuild, and the injector keeps working.
        Model model = new Model(20, 20);
        Source withInjector = Source.withInjector("own", STAMPS);

        assertSame(withInjector, ConfiguredBiomeSource.restored(withInjector, model),
                "a palette nothing widened must be handed back with its foreign layer intact");
        assertEquals(0, model.rebuilds, "dropped another mod's layer with nothing to fix");
        assertEquals(0, model.rewraps);
        assertTrue(model.reported.isEmpty());
    }

    @Test
    void aRebuildAcrossAnotherModsLayerThatLosesOurPatchesIsStillRefused() {
        Model model = new Model(228, 20);
        model.mangle = layers -> List.of();
        Source withInjector = Source.withInjector("widened", STAMPS);

        assertSame(withInjector, ConfiguredBiomeSource.restored(withInjector, model),
                "losing our patches is refused whether or not a foreign layer is in the way");
        assertTrue(model.reported.contains(Outcome.PATCHES_LOST));
        assertFalse(model.reported.contains(Outcome.FOREIGN_LAYER_DROPPED));
    }

    @Test
    void aRebuildThatDROPSThePatchesIsRefusedAndTheSourceKept() {
        // The worst case: a re-wrap that quietly loses every patch. Better a
        // stale palette than a dimension generating with no patches at all.
        Model model = new Model(228, 20);
        model.mangle = layers -> List.of();

        assertSame(PATCHED_WIDENED, ConfiguredBiomeSource.restored(PATCHED_WIDENED, model));
        assertTrue(model.reported.contains(Outcome.PATCHES_LOST));
        assertFalse(model.reported.contains(Outcome.REBUILT));
    }

    @Test
    void aRebuildThatREORDERSThePatchesIsRefused() {
        Model model = new Model(228, 20);
        model.mangle = layers -> List.of(reversed(layers.get(0)));

        assertSame(PATCHED_WIDENED, ConfiguredBiomeSource.restored(PATCHED_WIDENED, model),
                "order is part of precedence: local patches resolve in config order");
        assertTrue(model.reported.contains(Outcome.PATCHES_LOST));
    }

    @Test
    void aRebuildThatCHANGESANYPATCHFIELDIsRefused() {
        // Every field matters: a lost blend is a razor edge where a wobble
        // belongs, a lost radius is a patch of the wrong size.
        for (int i = 0; i < STAMPS.size(); i++) {
            for (Patch mangled : variantsOf(STAMPS.get(i))) {
                Model model = new Model(228, 20);
                int index = i;
                model.mangle = layers -> List.of(swap(layers.get(0), index, mangled));

                assertSame(PATCHED_WIDENED, ConfiguredBiomeSource.restored(PATCHED_WIDENED, model),
                        "a changed field must not read as preserved: " + mangled);
            }
        }
    }

    @Test
    void aRebuildThatLOSESALAYERIsRefused() {
        Model model = new Model(228, 20);
        model.mangle = layers -> List.of(layers.get(0));
        Source nested = Source.of("widened", STAMPS, SECOND_LAYER);

        assertSame(nested, ConfiguredBiomeSource.restored(nested, model));
        assertTrue(model.reported.contains(Outcome.PATCHES_LOST));
    }

    // --- preserved() itself ---------------------------------------------------

    @Test
    void theSameLayersInTheSameOrderArePreserved() {
        assertTrue(ConfiguredBiomeSource.preserved(List.of(STAMPS), List.of(List.copyOf(STAMPS))));
        assertTrue(ConfiguredBiomeSource.preserved(List.of(), List.of()));
    }

    @Test
    void reorderedOrEmptiedLayersAreNotPreserved() {
        assertFalse(ConfiguredBiomeSource.preserved(List.of(STAMPS), List.of(reversed(STAMPS))));
        assertFalse(ConfiguredBiomeSource.preserved(List.of(STAMPS), List.of()));
        assertFalse(ConfiguredBiomeSource.preserved(List.of(STAMPS), List.of(List.of())));
    }

    // --- the layer walk ------------------------------------------------------

    /** A stack for {@link ConfiguredBiomeSource#layersOf}: a named layer over what it wraps. */
    private record Layer(String name, Layer under) {
        static Layer stack(String... names) {
            Layer out = new Layer("core", null);
            for (int i = names.length - 1; i >= 0; i--) {
                out = new Layer(names[i], out);
            }
            return out;
        }
    }

    private static final java.util.function.Predicate<Layer> AT_CORE = l -> "core".equals(l.name());
    private static final java.util.function.UnaryOperator<Layer> PEEL =
            l -> l.under() == null ? l : l.under();

    @Test
    void ourPatchLayersUNDERAnotherModsAreStillCollected() {
        // The property the whole re-wrap rests on: wrappersOf filters this list,
        // so a patch layer the walk skips is a patch layer silently dropped.
        List<Layer> walked = ConfiguredBiomeSource.layersOf(
                Layer.stack("patched-outer", "injector", "patched-inner"), AT_CORE, PEEL);

        assertEquals(List.of("patched-outer", "injector", "patched-inner"), names(walked),
                "every layer down to the core, outermost first");
    }

    @Test
    void aSourceThatIsAlreadyTheCoreHasNoLayers() {
        assertEquals(List.of(), names(ConfiguredBiomeSource.layersOf(
                Layer.stack(), AT_CORE, PEEL)));
    }

    @Test
    void aLayerNothingCanUnwrapEndsTheWalkAndIsItselfCounted() {
        // An opaque layer is the deepest this mod gets, and naming it is how a
        // reader learns which mod to look at.
        List<Layer> walked = ConfiguredBiomeSource.layersOf(
                new Layer("patched", new Layer("opaque", null)), AT_CORE, PEEL);

        assertEquals(List.of("patched", "opaque"), names(walked));
    }

    @Test
    void aStackWithNoCoreIsBoundedByMAXUNWRAPDEPTH() {
        String[] deep = new String[DimensionManager.MAX_UNWRAP_DEPTH + 4];
        java.util.Arrays.fill(deep, "patched");
        Layer endless = Layer.stack(deep);

        assertEquals(DimensionManager.MAX_UNWRAP_DEPTH,
                ConfiguredBiomeSource.layersOf(endless, AT_CORE, PEEL).size(),
                "an unbounded walk over a cyclic source spins the boot thread");
    }

    @Test
    void aPatchLayerUNDERAnInjectorIsCollectedAndPreservedNoticesItGoing() {
        // The inverted stack, injector > patched > core. A walk that stops at the
        // injector collects nothing on either side, so preserved() compares []
        // against [] and passes a rebuild that dropped the dimension's patches —
        // the guard blinded by the same defect it guards against.
        List<String> before = ourLayers(ConfiguredBiomeSource.layersOf(
                Layer.stack("injector", "patched"), AT_CORE, PEEL));
        List<String> afterARebuildDroppedIt = ourLayers(ConfiguredBiomeSource.layersOf(
                Layer.stack(), AT_CORE, PEEL));

        assertEquals(List.of("patched"), before,
                "an injector on top must not hide the patch layer beneath it");
        assertFalse(ConfiguredBiomeSource.preserved(before, afterARebuildDroppedIt),
                "a patch dropped from under an injector must not read as preserved");
    }

    /** The layers this mod owns, mirroring the instanceof filter in wrappersOf. */
    private static List<String> ourLayers(List<Layer> walked) {
        List<String> out = new ArrayList<>();
        for (Layer layer : walked) {
            if (layer.name().startsWith("patched")) {
                out.add(layer.name());
            }
        }
        return out;
    }

    private static List<String> names(List<Layer> layers) {
        List<String> out = new ArrayList<>();
        for (Layer layer : layers) {
            out.add(layer.name());
        }
        return out;
    }

    private static List<Patch> reversed(List<Patch> patches) {
        List<Patch> out = new ArrayList<>(patches);
        java.util.Collections.reverse(out);
        return List.copyOf(out);
    }

    private static List<Patch> swap(List<Patch> patches, int index, Patch replacement) {
        List<Patch> out = new ArrayList<>(patches);
        out.set(index, replacement);
        return List.copyOf(out);
    }

    private static List<Patch> variantsOf(Patch p) {
        return List.of(
                new Patch("minecraft:plains", p.centerX(), p.centerZ(), p.radius(),
                        p.replace(), p.blend(), p.scope(), p.shape()),
                new Patch(p.biome(), p.centerX() + 1, p.centerZ(), p.radius(),
                        p.replace(), p.blend(), p.scope(), p.shape()),
                new Patch(p.biome(), p.centerX(), p.centerZ() + 1, p.radius(),
                        p.replace(), p.blend(), p.scope(), p.shape()),
                new Patch(p.biome(), p.centerX(), p.centerZ(), p.radius() + 1,
                        p.replace(), p.blend(), p.scope(), p.shape()),
                new Patch(p.biome(), p.centerX(), p.centerZ(), p.radius(),
                        Optional.of("minecraft:swamp"), p.blend(), p.scope(), p.shape()),
                new Patch(p.biome(), p.centerX(), p.centerZ(), p.radius(),
                        p.replace(), p.blend() + 1, p.scope(), p.shape()),
                new Patch(p.biome(), p.centerX(), p.centerZ(), p.radius(),
                        p.replace(), p.blend(), "global".equals(p.scope()) ? "clip" : "global", p.shape()),
                new Patch(p.biome(), p.centerX(), p.centerZ(), p.radius(),
                        p.replace(), p.blend(), p.scope(),
                        "square".equals(p.shape()) ? "circle" : "square"));
    }
}
