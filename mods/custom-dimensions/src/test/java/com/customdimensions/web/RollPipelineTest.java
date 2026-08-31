package com.customdimensions.web;

import com.customdimensions.config.DimensionConfig;
import com.customdimensions.facts.Measured;
import com.customdimensions.facts.SeedFacts;
import com.customdimensions.roll.SeedBank;
import com.google.gson.Gson;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    private static final Gson GSON = new Gson();

    /** A type-less, environment-less config — {@link RollPipeline#assumedFloorY} falls back to -64. */
    private static final DimensionConfig PLAIN_DIM = new DimensionConfig();

    /** A minimally valid, fully-absent facts record with a chosen spawn column/height, no grid. */
    private static SeedFacts fixtureFacts(Measured<SeedFacts.Column> column,
                                          Measured<Integer> surfaceHeight) {
        return fixtureFacts(4096, column, surfaceHeight, Measured.absent("not measured in this fixture"));
    }

    /** As above, with an explicit playable radius and grid — what {@link RollPipeline#spawnFromGrid} reads. */
    private static SeedFacts fixtureFacts(int playableRadius, Measured<SeedFacts.Column> column,
                                          Measured<Integer> surfaceHeight, Measured<SeedFacts.Grid> grid) {
        Measured<String> goneStr = Measured.absent("not measured in this fixture");
        Measured<Integer> goneInt = Measured.absent("not measured in this fixture");
        Measured<Double> goneDouble = Measured.absent("not measured in this fixture");
        return new SeedFacts("v1.2.3", "adventure:the_boneyard", 111L, "2026-08-10T00:00:00Z", "fp",
                playableRadius,
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
                        Measured.absent("not measured in this fixture"),
                        goneDouble, goneDouble, goneInt,
                        Measured.absent("not measured in this fixture"),
                        Measured.absent("not measured in this fixture"),
                        Measured.absent("not measured in this fixture"),
                        Measured.absent("not measured in this fixture")),
                grid);
    }

    /** A {@code side x side} grid, row-major ({@code z * side + x}), every cell null until overridden. */
    private static SeedFacts.Grid fixtureGrid(int side, List<String> biomeIds,
                                              java.util.Map<Integer, Integer> heightByIndex,
                                              java.util.Map<Integer, Integer> biomeIdxByIndex) {
        int n = side * side;
        List<Integer> height = new ArrayList<>(java.util.Collections.<Integer>nCopies(n, null));
        List<Integer> biome = new ArrayList<>(java.util.Collections.<Integer>nCopies(n, null));
        heightByIndex.forEach(height::set);
        biomeIdxByIndex.forEach(biome::set);
        int heightMeasured = (int) height.stream().filter(java.util.Objects::nonNull).count();
        return new SeedFacts.Grid(side, biomeIds, biome, height, n, heightMeasured);
    }

    @Test
    @DisplayName("the spawn promoted is the candidate's own measured column and surface height")
    void spawnToPromoteUsesTheCandidatesOwnMeasurement() {
        SeedFacts facts = fixtureFacts(
                Measured.of(new SeedFacts.Column(120, -340, true)), Measured.of(85));

        assertArrayEquals(new int[]{120, 85, -340}, RollPipeline.spawnToPromote(facts, PLAIN_DIM));
    }

    @Test
    @DisplayName("a real surface at the declared column is used even when the grid disagrees")
    void spawnToPromoteIgnoresTheGridWhenTheDeclaredColumnHasGround() {
        // If the grid were consulted it would answer a different position —
        // a real surface at the declared column must win without a search.
        SeedFacts.Grid grid = fixtureGrid(5, List.of(),
                java.util.Map.of(17, 70), java.util.Map.of());
        SeedFacts facts = fixtureFacts(1000,
                Measured.of(new SeedFacts.Column(120, -340, true)), Measured.of(85), Measured.of(grid));

        assertArrayEquals(new int[]{120, 85, -340}, RollPipeline.spawnToPromote(facts, PLAIN_DIM));
    }

    @Test
    @DisplayName("no recorded spawn and no banked grid writes nothing")
    void spawnToPromoteIsNullWhenUnmeasured() {
        SeedFacts facts = fixtureFacts(
                Measured.absent("no safe column found"), Measured.absent("no safe column found"));

        assertNull(RollPipeline.spawnToPromote(facts, PLAIN_DIM));
    }

    @Test
    @DisplayName("half a spawn and no banked grid writes nothing")
    void spawnToPromoteIsNullWhenOnlyHalfMeasured() {
        SeedFacts facts = fixtureFacts(
                Measured.of(new SeedFacts.Column(1, 2, true)), Measured.absent("surface unmeasured"));

        assertNull(RollPipeline.spawnToPromote(facts, PLAIN_DIM));
    }

    @Test
    @DisplayName("nothing is written when there is no candidate to read at all")
    void spawnToPromoteIsNullWhenFactsAreNull() {
        assertNull(RollPipeline.spawnToPromote(null, PLAIN_DIM));
    }

    // ------------------------------------------------------------- assumedFloorY

    @Test
    @DisplayName("nether and end default to floor 0, everything else to -64")
    void assumedFloorYDefaultsByType() {
        DimensionConfig nether = new DimensionConfig();
        nether.setType("nether");
        DimensionConfig end = new DimensionConfig();
        end.setType("end");
        DimensionConfig voidType = new DimensionConfig();
        voidType.setType("void");

        assertEquals(0, RollPipeline.assumedFloorY(nether));
        assertEquals(0, RollPipeline.assumedFloorY(end));
        assertEquals(-64, RollPipeline.assumedFloorY(voidType));
        assertEquals(-64, RollPipeline.assumedFloorY(PLAIN_DIM), "an untyped config falls back to -64");
    }

    @Test
    @DisplayName("an explicit environment.minY wins over the type default")
    void assumedFloorYPrefersAnExplicitOverride() {
        DimensionConfig config = GSON.fromJson(
                "{\"type\": \"end\", \"environment\": {\"minY\": -16}}", DimensionConfig.class);

        assertEquals(-16, RollPipeline.assumedFloorY(config));
    }

    // -------------------------------------------------------------- spawnFromGrid

    @Test
    @DisplayName("a void declared column falls back to the nearest real-ground grid cell")
    void spawnToPromoteFallsBackToTheGridWhenTheDeclaredColumnIsVoid() {
        // 5x5 grid, radius 1000 -> step 500, half 2. Centre (index 12) is
        // void too. Index 7 (0,-500) is one step out and real; index 6
        // (-500,-500) is farther (diagonal) — the nearer one must win.
        SeedFacts.Grid grid = fixtureGrid(5, List.of(),
                java.util.Map.of(12, -64, 7, 70, 6, 65), java.util.Map.of());
        SeedFacts facts = fixtureFacts(1000,
                Measured.of(new SeedFacts.Column(0, 0, true)), Measured.of(-64), Measured.of(grid));

        assertArrayEquals(new int[]{0, 70, -500}, RollPipeline.spawnToPromote(facts, PLAIN_DIM));
    }

    @Test
    @DisplayName("no real ground anywhere in the grid means no spawn is written")
    void spawnFromGridIsNullWhenEveryCellIsVoidOrUnsampled() {
        SeedFacts.Grid grid = fixtureGrid(5, List.of(),
                java.util.Map.of(12, -64), java.util.Map.of());   // one floor reading, the rest null

        assertNull(RollPipeline.spawnFromGrid(
                fixtureFacts(1000, Measured.of(new SeedFacts.Column(0, 0, true)), Measured.of(-64),
                        Measured.of(grid)),
                PLAIN_DIM, -64));
    }

    @Test
    @DisplayName("no banked grid at all means no spawn is written")
    void spawnToPromoteIsNullWhenTheColumnIsVoidAndNoGridWasBanked() {
        SeedFacts facts = fixtureFacts(
                Measured.of(new SeedFacts.Column(0, 0, true)), Measured.of(-64));

        assertNull(RollPipeline.spawnToPromote(facts, PLAIN_DIM));
    }

    @Test
    @DisplayName("a namesake-biome cell wins over a nearer ordinary cell within twice the distance")
    void spawnFromGridPrefersANamesakeBiomeCellWithinTwiceTheNearestDistance() {
        // Index 7 (0,-500) is the nearest real cell, one step out, ordinary
        // biome. Index 6 (-500,-500) is the namesake biome, sqrt(2) steps
        // out — exactly twice the nearest cell's squared distance, which
        // the rule still accepts ("within twice", inclusive).
        DimensionConfig def = new DimensionConfig();
        DimensionConfig.SeedRoll seedRoll = new DimensionConfig.SeedRoll();
        seedRoll.spawnFilter = List.of("adventure:boneyard_meadow");
        def.setSeedRoll(seedRoll);
        SeedFacts.Grid grid = fixtureGrid(5, List.of("adventure:plains", "adventure:boneyard_meadow"),
                java.util.Map.of(7, 70, 6, 65),
                java.util.Map.of(7, 0, 6, 1));
        SeedFacts facts = fixtureFacts(1000,
                Measured.of(new SeedFacts.Column(0, 0, true)), Measured.of(-64), Measured.of(grid));

        assertArrayEquals(new int[]{-500, 65, -500}, RollPipeline.spawnToPromote(facts, def));
    }

    @Test
    @DisplayName("a namesake-biome cell too far away loses to the nearest ordinary cell")
    void spawnFromGridIgnoresANamesakeBiomeCellBeyondTwiceTheNearestDistance() {
        // 7x7 grid, radius 3000 -> step 1000, half 3. Nearest real cell is
        // one step out (ordinary biome); the namesake cell is three steps
        // out — nine times the nearest cell's squared distance, well
        // outside the factor-of-two allowance.
        DimensionConfig def = new DimensionConfig();
        DimensionConfig.SeedRoll seedRoll = new DimensionConfig.SeedRoll();
        seedRoll.spawnFilter = List.of("adventure:boneyard_meadow");
        def.setSeedRoll(seedRoll);
        int near = 3 * 7 + 4;   // (gx=4, gz=3) -> x=1000, z=0
        int far = 3 * 7 + 6;    // (gx=6, gz=3) -> x=3000, z=0
        SeedFacts.Grid grid = fixtureGrid(7, List.of("adventure:plains", "adventure:boneyard_meadow"),
                java.util.Map.of(near, 70, far, 65),
                java.util.Map.of(near, 0, far, 1));
        SeedFacts facts = fixtureFacts(3000,
                Measured.of(new SeedFacts.Column(0, 0, true)), Measured.of(-64), Measured.of(grid));

        assertArrayEquals(new int[]{1000, 70, 0}, RollPipeline.spawnToPromote(facts, def));
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

    // --- allLayoutsIdentical: the seed-invariance detector (B6) --------------

    @Test
    @DisplayName("a board whose candidates all share one biome layout is flagged")
    void identicalLayoutsAreFlagged() {
        java.util.Map<String, Double> layout =
                java.util.Map.of("minecraft:nether_wastes", 0.6, "minecraft:crimson_forest", 0.4);
        assertTrue(RollPipeline.allLayoutsIdentical(List.of(layout, layout, layout)));
    }

    @Test
    @DisplayName("one differing candidate is enough to clear the flag")
    void oneDifferentLayoutClearsIt() {
        java.util.Map<String, Double> a = java.util.Map.of("minecraft:nether_wastes", 1.0);
        java.util.Map<String, Double> b = java.util.Map.of("minecraft:nether_wastes", 0.9,
                "incendium:quartz_flats", 0.1);
        assertFalse(RollPipeline.allLayoutsIdentical(List.of(a, a, b)));
    }

    @Test
    @DisplayName("equal shares under a different biome set are not identical")
    void sameSharesDifferentBiomesAreNotIdentical() {
        assertFalse(RollPipeline.allLayoutsIdentical(List.of(
                java.util.Map.of("minecraft:the_end", 1.0),
                java.util.Map.of("nullscape:shadowlands", 1.0))));
    }

    @Test
    @DisplayName("one candidate is not a comparison, so it is never flagged")
    void oneCandidateIsNotEvidence() {
        assertFalse(RollPipeline.allLayoutsIdentical(
                List.of(java.util.Map.of("minecraft:the_end", 1.0))));
    }

    @Test
    @DisplayName("empty and null layouts are absent measurements, not identical ones")
    void absentMeasurementsAreNotEvidence() {
        java.util.List<java.util.Map<String, Double>> empties = new ArrayList<>();
        empties.add(java.util.Map.of());
        empties.add(null);
        empties.add(java.util.Map.of());
        assertFalse(RollPipeline.allLayoutsIdentical(empties));
        assertFalse(RollPipeline.allLayoutsIdentical(List.of()));
        assertFalse(RollPipeline.allLayoutsIdentical(null));
    }

    @Test
    @DisplayName("unusable layouts are skipped, and the usable ones still decide")
    void unusableLayoutsAreSkippedNotCounted() {
        java.util.Map<String, Double> layout = java.util.Map.of("minecraft:the_end", 1.0);
        java.util.List<java.util.Map<String, Double>> mixed = new ArrayList<>();
        mixed.add(null);
        mixed.add(layout);
        mixed.add(java.util.Map.of());
        mixed.add(layout);
        assertTrue(RollPipeline.allLayoutsIdentical(mixed));
    }

    // --- B5: an unmeasured declared column must still reach the grid ---------

    @Test
    @DisplayName("an UNMEASURED declared column falls back to the grid, not to nothing")
    void spawnToPromoteFallsBackToTheGridWhenTheDeclaredColumnIsUnmeasured() {
        // The void between islands: the generator answers no surface at all,
        // so the height is ABSENT rather than a below-floor number. Index 7
        // is (0,-500) and real.
        SeedFacts.Grid grid = fixtureGrid(5, List.of(),
                java.util.Map.of(7, 70), java.util.Map.of());
        SeedFacts facts = fixtureFacts(1000,
                Measured.of(new SeedFacts.Column(0, 0, true)),
                Measured.absent("the generator answered no surface height at spawn"),
                Measured.of(grid));

        assertArrayEquals(new int[]{0, 70, -500}, RollPipeline.spawnToPromote(facts, PLAIN_DIM));
    }

    @Test
    @DisplayName("an absent column and an absent height still reach the grid")
    void spawnToPromoteFallsBackToTheGridWhenNothingAboutSpawnWasMeasured() {
        SeedFacts.Grid grid = fixtureGrid(5, List.of(),
                java.util.Map.of(7, 70), java.util.Map.of());
        SeedFacts facts = fixtureFacts(1000,
                Measured.absent("no safe column found"),
                Measured.absent("no safe column found"),
                Measured.of(grid));

        assertArrayEquals(new int[]{0, 70, -500}, RollPipeline.spawnToPromote(facts, PLAIN_DIM));
    }

    @Test
    @DisplayName("an unmeasured column with a groundless grid still writes nothing")
    void spawnToPromoteIsNullWhenUnmeasuredAndTheGridHasNoGround() {
        SeedFacts.Grid grid = fixtureGrid(5, List.of(),
                java.util.Map.of(12, -64), java.util.Map.of());
        SeedFacts facts = fixtureFacts(1000,
                Measured.of(new SeedFacts.Column(0, 0, true)),
                Measured.absent("the generator answered no surface height at spawn"),
                Measured.of(grid));

        assertNull(RollPipeline.spawnToPromote(facts, PLAIN_DIM));
    }

    // --- orchestratorCount: dimensions must finish in waves ------------------

    @Test
    @DisplayName("a full roll never runs one orchestrator per dimension")
    void orchestratorCountIsCappedBelowTheTargetCount() {
        // 81 dimensions, 10 measure workers: 20 in flight, so boards complete
        // in waves and the render cores have something to draw throughout.
        assertEquals(20, RollPipeline.orchestratorCount(81, 10));
        assertTrue(RollPipeline.orchestratorCount(81, 10) < 81,
                "one orchestrator per dimension is what leaves the render cores idle");
    }

    @Test
    @DisplayName("a small roll is never given more orchestrators than dimensions")
    void orchestratorCountNeverExceedsTheTargets() {
        assertEquals(3, RollPipeline.orchestratorCount(3, 10));
        assertEquals(1, RollPipeline.orchestratorCount(1, 10));
    }

    @Test
    @DisplayName("a tiny measure budget still runs two dimensions at once")
    void orchestratorCountKeepsAFloor() {
        assertEquals(2, RollPipeline.orchestratorCount(81, 1));
        assertEquals(1, RollPipeline.orchestratorCount(1, 1), "but never more than there are targets");
    }
}
