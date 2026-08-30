package com.customdimensions.dimension;

import com.customdimensions.config.DimensionConfig;
import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Pair;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Explicit band placement and withdrawal: {@link DimensionManager#explicitBands}.
 *
 * <p>Driven with strings for cells rather than hypercubes: resolving one
 * initialises {@code Registries}, and this suite cannot bootstrap Minecraft.
 * The parameters-to-hypercube step is {@code hypercubeFrom}, which the running
 * server exercises; what is asked here is which bands place and which biomes
 * are then withdrawn from native and round-robin placement.
 */
class ExplicitBandsTest {

    private static DimensionConfig.BiomeBand band(String id, String axis, double lo, double hi) {
        JsonObject params = new JsonObject();
        JsonObject range = new JsonObject();
        range.addProperty("lo", lo);
        range.addProperty("hi", hi);
        params.add(axis, range);
        return new DimensionConfig.BiomeBand(id, params);
    }

    /** A cell that names its band, so two bands for one biome are distinguishable. */
    private static String cellOf(DimensionConfig.BiomeBand b) {
        return b.id() + b.parameters();
    }

    private static Set<Identifier> allowed(String... ids) {
        java.util.LinkedHashSet<Identifier> out = new java.util.LinkedHashSet<>();
        for (String id : ids) {
            out.add(Identifier.of(id));
        }
        return out;
    }

    @Test
    void oneBiomeNamedTwicePlacesBothOfItsCells() {
        // Keying bands by biome id keeps one and drops the rest, and the ground
        // a dropped band would hold falls to whichever neighbour is nearest —
        // silently, because every other count in the boot line is per-biome.
        DimensionConfig.BiomeBand first = band("minecraft:taiga", "weirdness", -2.0, -1.0);
        DimensionConfig.BiomeBand second = band("minecraft:taiga", "weirdness", 0.522, 1.0);

        DimensionManager.Placed<String> placed = DimensionManager.explicitBands(
                List.of(first, second), allowed("minecraft:taiga"), ExplicitBandsTest::cellOf);

        assertEquals(List.of(
                Pair.of(cellOf(first), Identifier.of("minecraft:taiga")),
                Pair.of(cellOf(second), Identifier.of("minecraft:taiga"))), placed.cells());
    }

    @Test
    void aBiomeNamedTwiceIsWithdrawnOnce() {
        // ids is the withdrawal set the native and round-robin tiers consult.
        // Two bands must not withdraw twice, nor fail to withdraw at all.
        DimensionManager.Placed<String> placed = DimensionManager.explicitBands(
                List.of(band("minecraft:taiga", "weirdness", -2.0, -1.0),
                        band("minecraft:taiga", "weirdness", 0.522, 1.0)),
                allowed("minecraft:taiga"), ExplicitBandsTest::cellOf);

        assertEquals(Set.of(Identifier.of("minecraft:taiga")), placed.ids());
    }

    @Test
    void bandsKeepFileOrderAcrossBiomes() {
        // Interleaved duplicates are what the shipped configs actually carry:
        // snowy_plains, taiga, snowy_plains, taiga. Order is the config's.
        DimensionConfig.BiomeBand a = band("minecraft:snowy_plains", "weirdness", -2.0, -1.0);
        DimensionConfig.BiomeBand b = band("minecraft:taiga", "weirdness", -0.064, 0.017);
        DimensionConfig.BiomeBand c = band("minecraft:snowy_plains", "weirdness", 0.508, 0.522);
        DimensionConfig.BiomeBand d = band("minecraft:taiga", "weirdness", 0.522, 1.0);

        DimensionManager.Placed<String> placed = DimensionManager.explicitBands(
                List.of(a, b, c, d), allowed("minecraft:snowy_plains", "minecraft:taiga"),
                ExplicitBandsTest::cellOf);

        assertEquals(List.of(cellOf(a), cellOf(b), cellOf(c), cellOf(d)),
                placed.cells().stream().map(Pair::getFirst).toList());
    }

    @Test
    void aBandForAnUnlistedBiomePlacesNothingAndWithdrawsNothing() {
        // Withdrawing an unlisted biome would take a native placement away
        // from a biome the dimension never asked to band.
        DimensionManager.Placed<String> placed = DimensionManager.explicitBands(
                List.of(band("minecraft:jungle", "weirdness", 0.0, 1.0)),
                allowed("minecraft:taiga"), ExplicitBandsTest::cellOf);

        assertTrue(placed.cells().isEmpty());
        assertTrue(placed.ids().isEmpty());
    }

    @Test
    void aBandWhoseParametersDoNotParsePlacesNothingAndWithdrawsNothing() {
        // hypercubeFrom returns null on an invalid axis. The biome must fall
        // back to plain-listed behaviour, which means staying available to the
        // native tier — withdrawing it would delete it from the dimension.
        DimensionManager.Placed<String> placed = DimensionManager.explicitBands(
                List.of(band("minecraft:taiga", "weirdness", 0.0, 1.0)),
                allowed("minecraft:taiga"), b -> null);

        assertTrue(placed.cells().isEmpty());
        assertFalse(placed.ids().contains(Identifier.of("minecraft:taiga")));
    }

    @Test
    void oneUnusableBandDoesNotSuppressItsBiomesOtherBands() {
        DimensionConfig.BiomeBand good = band("minecraft:taiga", "weirdness", 0.5, 1.0);

        DimensionManager.Placed<String> placed = DimensionManager.explicitBands(
                List.of(band("minecraft:taiga", "weirdness", 9.0, 9.0), good),
                allowed("minecraft:taiga"),
                b -> b.parameters().toString().contains("9.0") ? null : cellOf(b));

        assertEquals(List.of(Pair.of(cellOf(good), Identifier.of("minecraft:taiga"))),
                placed.cells());
        assertEquals(Set.of(Identifier.of("minecraft:taiga")), placed.ids());
    }

    @Test
    void noBandsPlacesNothing() {
        DimensionManager.Placed<String> none = DimensionManager.explicitBands(
                null, allowed("minecraft:taiga"), ExplicitBandsTest::cellOf);
        assertTrue(none.cells().isEmpty());
        assertTrue(none.ids().isEmpty());

        DimensionManager.Placed<String> empty = DimensionManager.explicitBands(
                List.of(), allowed("minecraft:taiga"), ExplicitBandsTest::cellOf);
        assertTrue(empty.cells().isEmpty());
        assertTrue(empty.ids().isEmpty());
    }
}
