package com.customdimensions.web;

import com.customdimensions.config.DimensionConfig;
import com.customdimensions.facts.Measured;
import com.customdimensions.facts.SeedFacts;
import com.customdimensions.roll.SeedBank;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The pure decisions {@code RollPipeline} makes without a live server — which
 * seed a roll promotes to current, and with which spawn — plus a structural
 * guard against the removed "skip a dimension that already holds enough"
 * behaviour, which cannot otherwise be unit tested: {@code rollOne},
 * {@code run} and {@code start} need a live {@code MinecraftServer} end to
 * end, the same gap every other {@code RollPipeline} method has (no test
 * file existed for this class before).
 */
class RollPipelineTest {

    private static SeedBank.CandidateSummary summary(long seed, double percentage) {
        return new SeedBank.CandidateSummary(seed, percentage / 10, 10.0, percentage, "SCORED");
    }

    @Test
    @DisplayName("the seed a roll promotes to current is the top of the leaderboard")
    void bestToPromoteIsTheTopOfTheLeaderboard() {
        List<SeedBank.CandidateSummary> ranked = List.of(summary(1L, 90.0), summary(2L, 80.0));

        assertEquals(1L, RollPipeline.bestToPromote(ranked));
    }

    @Test
    @DisplayName("nothing is promoted when the bank is empty")
    void bestToPromoteIsNullWhenTheBankIsEmpty() {
        assertNull(RollPipeline.bestToPromote(List.of()));
    }

    // ---------------------------------------------------------- spawnToPromote

    /** A minimally valid, fully-absent facts record with a chosen spawn column/height. */
    private static SeedFacts fixtureFacts(Measured<SeedFacts.Column> column,
                                          Measured<Integer> surfaceHeight) {
        Measured<String> goneStr = Measured.absent("not measured in this fixture");
        Measured<Integer> goneInt = Measured.absent("not measured in this fixture");
        Measured<Double> goneDouble = Measured.absent("not measured in this fixture");
        return new SeedFacts("v1.2.3", "adventure:the_boneyard", 111L, "2026-08-10T00:00:00Z", "fp", 4096,
                new SeedFacts.SpawnFacts(column, goneStr, surfaceHeight, goneDouble,
                        Measured.absent("not measured in this fixture"),
                        Measured.absent("not measured in this fixture"),
                        Measured.absent("not measured in this fixture")),
                new SeedFacts.BiomeFacts(
                        Measured.absent("not measured in this fixture"), goneInt, goneDouble, goneDouble),
                new SeedFacts.TerrainFacts(goneDouble, goneDouble, goneDouble, goneDouble,
                        goneInt, goneInt),
                new SeedFacts.StructureFacts(
                        Measured.absent("not measured in this fixture"),
                        Measured.absent("not measured in this fixture"),
                        Measured.absent("not measured in this fixture"),
                        Measured.absent("not measured in this fixture"),
                        Measured.absent("not measured in this fixture"),
                        goneDouble, goneDouble, goneInt,
                        Measured.absent("not measured in this fixture"),
                        Measured.absent("not measured in this fixture"),
                        Measured.absent("not measured in this fixture"),
                        Measured.absent("not measured in this fixture")),
                Measured.absent("not measured in this fixture"));
    }

    @Test
    @DisplayName("the spawn promoted is the candidate's own measured column and surface height")
    void spawnToPromoteUsesTheCandidatesOwnMeasurement() {
        SeedFacts facts = fixtureFacts(
                Measured.of(new SeedFacts.Column(120, -340, true)), Measured.of(85));

        assertArrayEquals(new int[]{120, 85, -340}, RollPipeline.spawnToPromote(facts));
    }

    @Test
    @DisplayName("nothing is written when the candidate has no recorded spawn")
    void spawnToPromoteIsNullWhenUnmeasured() {
        SeedFacts facts = fixtureFacts(
                Measured.absent("no safe column found"), Measured.absent("no safe column found"));

        assertNull(RollPipeline.spawnToPromote(facts));
    }

    @Test
    @DisplayName("nothing is written when only half the spawn was measured")
    void spawnToPromoteIsNullWhenOnlyHalfMeasured() {
        SeedFacts facts = fixtureFacts(
                Measured.of(new SeedFacts.Column(1, 2, true)), Measured.absent("surface unmeasured"));

        assertNull(RollPipeline.spawnToPromote(facts));
    }

    @Test
    @DisplayName("nothing is written when there is no candidate to read at all")
    void spawnToPromoteIsNullWhenFactsAreNull() {
        assertNull(RollPipeline.spawnToPromote(null));
    }

    /**
     * {@code rollOne} used to take a {@code boolean topUp} flag and skip a
     * dimension already holding {@code WANTED} candidates when it was false —
     * a full sweep silently rolling fewer seeds than a targeted one for the
     * same request. That parameter is gone; a reflective lookup of the exact
     * five-parameter signature throws {@code NoSuchMethodException} the
     * moment it, or anything shaped like it, comes back.
     */
    @Test
    @DisplayName("rollOne carries no top-up flag — a roll always means N more, never a skip")
    void rollOneNoLongerTakesASkipFlag() {
        assertDoesNotThrow(() -> RollPipeline.class.getDeclaredMethod("rollOne",
                MinecraftServer.class, DimensionConfig.class, int.class,
                ExecutorService.class, int.class));
    }
}
