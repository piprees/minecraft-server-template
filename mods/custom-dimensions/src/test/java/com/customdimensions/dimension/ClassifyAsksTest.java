package com.customdimensions.dimension;

import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How an ask reaches the ground when the base source is kept whole:
 * {@link DimensionManager#classifyAsks}.
 *
 * <p>This is the decision half of {@code buildAdditiveSource}. The other half
 * builds the source itself and needs a live {@code Registry} and real
 * hypercubes, which this suite cannot bootstrap — that part is exercised by
 * the running server, not here.
 */
class ClassifyAsksTest {

    private static Identifier id(String s) {
        return Identifier.of(s);
    }

    private static Set<Identifier> ids(String... s) {
        return java.util.Arrays.stream(s).map(ClassifyAsksTest::id)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private static List<String> names(List<Identifier> got) {
        return got.stream().map(Identifier::toString).toList();
    }

    @Test
    void a_biome_the_base_already_places_needs_nothing() {
        DimensionManager.Appended out = DimensionManager.classifyAsks(
                ids("minecraft:plains"), Set.of(), ids("minecraft:plains"), Set.of());
        assertEquals(List.of("minecraft:plains"), names(out.arriving()));
        assertTrue(out.declared().isEmpty());
        assertTrue(out.unplaceable().isEmpty());
    }

    @Test
    void a_biome_its_mod_declared_takes_that_placement() {
        DimensionManager.Appended out = DimensionManager.classifyAsks(
                ids("natures_spirit:prairie"), Set.of(), Set.of(), ids("natures_spirit:prairie"));
        assertEquals(List.of("natures_spirit:prairie"), names(out.declared()));
        assertTrue(out.unplaceable().isEmpty());
    }

    @Test
    void a_biome_with_neither_does_not_generate_and_is_named() {
        DimensionManager.Appended out = DimensionManager.classifyAsks(
                ids("regions_unexplored:cold_deciduous_forest"), Set.of(), Set.of(), Set.of());
        assertEquals(List.of("regions_unexplored:cold_deciduous_forest"), names(out.unplaceable()));
        assertTrue(out.arriving().isEmpty());
        assertTrue(out.declared().isEmpty());
    }

    @Test
    void a_band_wins_over_the_base_already_placing_it() {
        // The author banding a biome the base also places is an override, and
        // an override that lost to the base would be silent.
        DimensionManager.Appended out = DimensionManager.classifyAsks(
                ids("minecraft:plains"), ids("minecraft:plains"), ids("minecraft:plains"), Set.of());
        assertTrue(out.arriving().isEmpty(), "a banded ask must not be counted as arriving");
        assertTrue(out.declared().isEmpty());
        assertTrue(out.unplaceable().isEmpty());
    }

    @Test
    void a_band_wins_over_a_declared_placement() {
        DimensionManager.Appended out = DimensionManager.classifyAsks(
                ids("natures_spirit:prairie"), ids("natures_spirit:prairie"),
                Set.of(), ids("natures_spirit:prairie"));
        assertTrue(out.declared().isEmpty(), "a band overrides the mod's own placement");
        assertTrue(out.unplaceable().isEmpty());
    }

    @Test
    void the_base_wins_over_a_declared_placement() {
        // Appending declared cells for a biome the base already places would
        // give it a second home it never asked for.
        DimensionManager.Appended out = DimensionManager.classifyAsks(
                ids("terralith:moonlight_grove"), Set.of(),
                ids("terralith:moonlight_grove"), ids("terralith:moonlight_grove"));
        assertEquals(List.of("terralith:moonlight_grove"), names(out.arriving()));
        assertTrue(out.declared().isEmpty());
    }

    @Test
    void every_ask_lands_in_exactly_one_outcome() {
        DimensionManager.Appended out = DimensionManager.classifyAsks(
                ids("minecraft:plains", "natures_spirit:prairie", "regions_unexplored:steppe",
                        "wilderwild:flower_field"),
                ids("wilderwild:flower_field"),
                ids("minecraft:plains"),
                ids("natures_spirit:prairie"));
        assertEquals(List.of("minecraft:plains"), names(out.arriving()));
        assertEquals(List.of("natures_spirit:prairie"), names(out.declared()));
        assertEquals(List.of("regions_unexplored:steppe"), names(out.unplaceable()));
        // The banded one appears in no bucket: its cell comes from the band.
        assertEquals(3, out.arriving().size() + out.declared().size() + out.unplaceable().size());
    }

    @Test
    void nothing_asked_produces_nothing_to_place() {
        DimensionManager.Appended out = DimensionManager.classifyAsks(
                List.of(), Set.of(), ids("minecraft:plains"), ids("natures_spirit:prairie"));
        assertTrue(out.arriving().isEmpty());
        assertTrue(out.declared().isEmpty());
        assertTrue(out.unplaceable().isEmpty());
    }

    @Test
    void ask_order_is_preserved_so_the_warning_reads_as_the_file_does() {
        DimensionManager.Appended out = DimensionManager.classifyAsks(
                ids("regions_unexplored:steppe", "regions_unexplored:mountains",
                        "regions_unexplored:barley_fields"),
                Set.of(), Set.of(), Set.of());
        assertEquals(List.of("regions_unexplored:steppe", "regions_unexplored:mountains",
                "regions_unexplored:barley_fields"), names(out.unplaceable()));
    }
}
