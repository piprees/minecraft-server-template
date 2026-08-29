package com.customdimensions.dimension;

import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Affinity decides pool membership: NoisePoolBuilder drops an entry scoring
 * 0.0 unless wants/include names it.
 */
class NoisePoolAffinityTest {

    private static Identifier id(String path) {
        return Identifier.of("minecraft", path);
    }

    private static Set<Identifier> dimension(String... paths) {
        return Arrays.stream(paths).map(NoisePoolAffinityTest::id)
                .collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void emptyBiomeListScoresZero() {
        // contains() over an empty list is false everywhere, so vanilla never
        // places it. Five installed structures resolve to an empty list; scoring
        // them "generates anywhere" put one in a deep ocean.
        assertEquals(0.0, NoisePoolBuilder.affinityOf(
                Collections.emptyList(), dimension("plains", "ocean")));
    }

    @Test
    void everyBiomeMatchingScoresOne() {
        assertEquals(1.0, NoisePoolBuilder.affinityOf(
                List.of(id("plains"), id("forest")), dimension("plains", "forest", "ocean")));
    }

    @Test
    void partialMatchScoresTheFraction() {
        assertEquals(0.5, NoisePoolBuilder.affinityOf(
                List.of(id("plains"), id("desert")), dimension("plains", "ocean")));
    }

    @Test
    void noBiomeMatchingScoresZero() {
        assertEquals(0.0, NoisePoolBuilder.affinityOf(
                List.of(id("desert"), id("badlands")), dimension("plains", "ocean")));
    }

    @Test
    void unresolvableBiomeDilutesRatherThanDisappears() {
        // A null id counts towards the total. Dropping it instead would round a
        // half-matching structure up to a full match.
        assertEquals(0.5, NoisePoolBuilder.affinityOf(
                Arrays.asList(id("plains"), null), dimension("plains")));
    }
}
