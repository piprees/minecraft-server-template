package com.customdimensions.core.score;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.customdimensions.core.score.RollFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every kept criterion: a passing case, a failing case and an absent-input case. */
class CriteriaTest {

    private static double score(Criterion.Result r) {
        return assertInstanceOf(Criterion.Result.Score.class, r).value();
    }

    @Test
    void namesakeSpawnInANamedBiomeWithCoverageScoresHigh() {
        var c = new Criteria.SpawnReadsAsNamesake();
        RollTarget t = target("{\"spawnFilter\": [\"minecraft:plains\", \"minecraft:forest\"]}");
        assertTrue(c.applicable(t));
        assertTrue(score(c.evaluate(plain(), t)) > 0.7);
    }

    @Test
    void namesakeSpawnElsewhereWithNoCoverageScoresZero() {
        var c = new Criteria.SpawnReadsAsNamesake();
        RollTarget t = target("{\"spawnFilter\": [\"minecraft:desert\"]}");
        assertEquals(0.0, score(c.evaluate(plain(), t)));
    }

    @Test
    void namesakeIsUnmeasuredWithoutASpawnBiomeAndNotAskedWithoutAFilter() {
        var c = new Criteria.SpawnReadsAsNamesake();
        RollFacts f = facts(spawn(null, 0.5), plain().biomes(), plain().terrain(), plain().structures(), List.of());
        assertInstanceOf(Criterion.Result.Unmeasured.class, c.evaluate(f, target("{\"spawnFilter\": [\"minecraft:plains\"]}")));
        assertFalse(c.applicable(target("{}")));
    }

    @Test
    void headlineShareInsideTheBandScoresOneAndASwampScoresLow() {
        var c = new Criteria.HeadlineBiomeDominatesAppropriately();
        assertEquals(1.0, score(c.evaluate(plain(), target("{}"))));
        RollFacts swamp = facts(plain().spawn(), biomes(Map.of("minecraft:plains", 0.95, "minecraft:river", 0.05), 0.1),
                plain().terrain(), plain().structures(), List.of());
        assertTrue(score(c.evaluate(swamp, target("{}"))) < 0.1);
    }

    @Test
    void headlineIsUnmeasuredWithNoSamplesAndNotAskedOfASingleBiome() {
        var c = new Criteria.HeadlineBiomeDominatesAppropriately();
        RollFacts empty = facts(plain().spawn(), biomes(Map.of(), 0.1), plain().terrain(), plain().structures(), List.of());
        assertInstanceOf(Criterion.Result.Unmeasured.class, c.evaluate(empty, target("{}")));
        assertFalse(c.applicable(target("test", "{}", 1000, 1)));
    }

    @Test
    void varietyCountsDistinctBiomesAgainstTheDeclaredOnes() {
        var c = new Criteria.BiomeVarietyPresent();
        assertEquals(0.75, score(c.evaluate(plain(), target("{}"))), 1e-9);
        assertEquals(1.0, score(c.evaluate(plain(), target("test", "{}", 1000, 2))));
        RollFacts empty = facts(plain().spawn(), biomes(Map.of(), 0.1), plain().terrain(), plain().structures(), List.of());
        assertInstanceOf(Criterion.Result.Unmeasured.class, c.evaluate(empty, target("{}")));
    }

    @Test
    void edgesNearSpawnScoreAgainstTheIdealDensity() {
        var c = new Criteria.BiomeEdgesNearSpawn();
        assertEquals(1.0, score(c.evaluate(plain(), target("{}"))));
        RollFacts one = facts(plain().spawn(), biomes(Map.of("minecraft:plains", 1.0), 0.0), plain().terrain(), plain().structures(), List.of());
        assertEquals(0.0, score(c.evaluate(one, target("{}"))));
        RollFacts none = facts(plain().spawn(), biomes(Map.of("minecraft:plains", 1.0), null), plain().terrain(), plain().structures(), List.of());
        assertInstanceOf(Criterion.Result.Unmeasured.class, c.evaluate(none, target("{}")));
    }

    @Test
    void terrainWordScoresReliefOrGroundAgainstItsBand() {
        var c = new Criteria.TerrainMatchesPreset();
        assertEquals(1.0, score(c.evaluate(plain(), target("{\"terrain\": \"rolling\"}"))));
        assertTrue(score(c.evaluate(plain(), target("{\"terrain\": \"extreme\"}"))) < 1.0);
        RollFacts islands = facts(plain().spawn(), plain().biomes(), terrain(0.3, 0.0, 10.0, 60, 90), plain().structures(), List.of());
        assertEquals(1.0, score(c.evaluate(islands, target("{\"terrain\": \"islands\"}"))));
        assertTrue(score(c.evaluate(islands, target("{\"terrain\": \"void\"}"))) < 1.0);
    }

    @Test
    void terrainIsUnmeasuredWithoutGroundAndNotAskedForAnUnknownWord() {
        var c = new Criteria.TerrainMatchesPreset();
        RollFacts voidWorld = facts(plain().spawn(), plain().biomes(), terrain(0.0, 0.0, null, null, null), plain().structures(), List.of());
        assertInstanceOf(Criterion.Result.Unmeasured.class, c.evaluate(voidWorld, target("{\"terrain\": \"hilly\"}")));
        assertFalse(c.applicable(target("{\"terrain\": \"wobbly\"}")));
        assertFalse(c.applicable(target("{}")));
    }

    @Test
    void waterScoresTheSurfaceWaterFractionAgainstTheWord() {
        var c = new Criteria.WaterMatchesIntent();
        assertEquals(1.0, score(c.evaluate(plain(), target("{\"water\": \"none\"}"))));
        assertTrue(score(c.evaluate(plain(), target("{\"water\": \"sea\"}"))) < 0.5);
        assertFalse(c.applicable(target("{}")));
    }

    @Test
    void heightRangeScoresTheShareOfGroundInsideTheEnvelope() {
        var c = new Criteria.HeightRangeMatchesIntent();
        assertEquals(1.0, score(c.evaluate(plain(), target("{\"heightRange\": [40, 130]}"))));
        assertEquals(0.5, score(c.evaluate(plain(), target("{\"heightRange\": [85, 200]}"))), 1e-9);
        RollFacts none = facts(plain().spawn(), plain().biomes(), terrain(0.0, 0.0, null, null, null), plain().structures(), List.of());
        assertInstanceOf(Criterion.Result.Unmeasured.class, c.evaluate(none, target("{\"heightRange\": [40, 130]}")));
        assertFalse(c.applicable(target("{\"heightRange\": [50, 50]}")));
    }

    @Test
    void groundCoverIsTheGroundFractionAndNotAskedOfIslands() {
        var c = new Criteria.PlayableGroundCoversTheDisc();
        assertEquals(1.0, score(c.evaluate(plain(), target("{}"))));
        RollFacts half = facts(plain().spawn(), plain().biomes(), terrain(0.5, 0.0, 10.0, 60, 70), plain().structures(), List.of());
        assertEquals(0.5, score(c.evaluate(half, target("{}"))));
        assertFalse(c.applicable(target("{\"terrain\": \"islands\"}")));
    }

    @Test
    void fortressGatePassesNearAndFailsFarAndIsOnlyAskedOfTheNether() {
        var c = new Criteria.FortressReachableInNether();
        RollTarget nether = target(Criteria.NETHER, "{}", 1000, 2);
        assertTrue(c.applicable(nether));
        assertFalse(c.applicable(target("{}")));
        Set<String> pool = Set.of("minecraft:fortress");
        assertInstanceOf(Criterion.Result.Pass.class, c.evaluate(withStructures(structures(pool, Map.of(),
                List.of(start("minecraft:fortress", 300)))), nether));
        assertInstanceOf(Criterion.Result.Fail.class, c.evaluate(withStructures(structures(pool, Map.of(),
                List.of(start("minecraft:fortress", 800)))), nether));
        assertInstanceOf(Criterion.Result.Fail.class, c.evaluate(withStructures(structures(pool, Map.of(), List.of())), nether));
        assertInstanceOf(Criterion.Result.Unmeasured.class, c.evaluate(withStructures(structures(Set.of(), Map.of(), List.of())), nether));
        assertEquals(500.0, c.band(nether)[1]);
    }

    @Test
    void endCityGateAndElytraSupplyReadEndCityStarts() {
        var gate = new Criteria.EndCityReachableInEnd();
        var elytra = new Criteria.EndSuppliesEnoughElytra();
        RollTarget end = target(Criteria.END, "{}", 1000, 2);
        Set<String> pool = Set.of("minecraft:end_city");
        List<RollFacts.Start> thirty = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) thirty.add(start("minecraft:end_city", 100 + i * 20));
        RollFacts many = withStructures(structures(pool, Map.of(), thirty));
        assertInstanceOf(Criterion.Result.Pass.class, gate.evaluate(many, end));
        assertEquals(0.5, score(elytra.evaluate(many, end)), 1e-9);
        RollFacts none = withStructures(structures(pool, Map.of(), List.of()));
        assertInstanceOf(Criterion.Result.Fail.class, gate.evaluate(none, end));
        assertEquals(0.0, score(elytra.evaluate(none, end)));
        assertInstanceOf(Criterion.Result.Unmeasured.class, elytra.evaluate(withStructures(structures(Set.of(), Map.of(), List.of())), end));
        assertFalse(elytra.applicable(target("{}")));
    }

    @Test
    void reachabilityIsUnmeasuredWhenTheSearchStopsShortOfTheFloor() {
        var c = new Criteria.FortressReachableInNether();
        RollTarget nether = target(Criteria.NETHER, "{}", 1000, 2);
        RollFacts p = withStructures(structures(Set.of("minecraft:fortress"), Map.of(), List.of()));
        RollFacts shortSearch = new RollFacts(p.dimension(), p.seed(), 1000, 1000, 200, p.spawn(), p.biomes(), p.terrain(),
                p.structures(), List.of());
        assertInstanceOf(Criterion.Result.Unmeasured.class, c.evaluate(shortSearch, nether));
    }

    @Test
    void spawnIsPlayableRampsToAmpleAndIsNotAskedWhenHazardsAreAllowed() {
        var c = new Criteria.SpawnIsPlayable();
        assertEquals(1.0, score(c.evaluate(plain(), target("{}"))));
        RollFacts thin = facts(spawn("minecraft:plains", 0.05), plain().biomes(), plain().terrain(), plain().structures(), List.of());
        assertEquals(0.2, score(c.evaluate(thin, target("{}"))), 1e-9);
        RollFacts none = facts(spawn("minecraft:plains", 0.0), plain().biomes(), plain().terrain(), plain().structures(), List.of());
        assertEquals(0.0, score(c.evaluate(none, target("{}"))));
        RollFacts gone = facts(spawn("minecraft:plains", null), plain().biomes(), plain().terrain(), plain().structures(), List.of());
        assertInstanceOf(Criterion.Result.Unmeasured.class, c.evaluate(gone, target("{}")));
        assertFalse(c.applicable(target("{\"allowHazardousSpawn\": true}")));
    }

    @Test
    void aWantInsideItsBandScoresOneAndOutsideItRampsDown() {
        RollTarget t = target("{\"wants\": {\"village\": \"near_spawn\"}}");
        Criterion c = Criteria.forTarget(t, List.of()).stream().filter(x -> x.id().equals("wants:village")).findFirst().orElseThrow();
        Map<String, List<String>> targets = Map.of("village", List.of("minecraft:village_plains"));
        Set<String> pool = Set.of("minecraft:village_plains");
        assertEquals(1.0, score(c.evaluate(withStructures(structures(pool, targets, List.of(start("minecraft:village_plains", 100)))), t)));
        assertEquals(0.6, score(c.evaluate(withStructures(structures(pool, targets, List.of(start("minecraft:village_plains", 250)))), t)), 1e-9);
        assertEquals(0.0, score(c.evaluate(withStructures(structures(pool, targets, List.of())), t)));
        assertEquals(150.0, c.band(t)[1]);
    }

    @Test
    void aWantIsUnmeasuredWhenUnknownOrOutOfThePool() {
        RollTarget t = target("{\"wants\": {\"village\": \"spread\"}}");
        Criterion c = Criteria.forTarget(t, List.of()).stream().filter(x -> x.id().equals("wants:village")).findFirst().orElseThrow();
        assertInstanceOf(Criterion.Result.Unmeasured.class, c.evaluate(withStructures(structures(Set.of(), Map.of(), List.of())), t));
        assertInstanceOf(Criterion.Result.Unmeasured.class, c.evaluate(withStructures(structures(Set.of(),
                Map.of("village", List.of("minecraft:village_plains")), List.of())), t));
    }

    @Test
    void aShunScoresDistanceAndAbsenceScoresOne() {
        RollTarget t = target("{\"shuns\": [\"monument\"]}");
        Criterion c = Criteria.forTarget(t, List.of()).stream().filter(x -> x.id().equals("shuns:monument")).findFirst().orElseThrow();
        Map<String, List<String>> targets = Map.of("monument", List.of("minecraft:monument"));
        assertEquals(1.0, score(c.evaluate(withStructures(structures(Set.of(), targets, List.of())), t)));
        assertEquals(0.25, score(c.evaluate(withStructures(structures(Set.of(), targets, List.of(start("minecraft:monument", 250)))), t)), 1e-9);
        assertInstanceOf(Criterion.Result.Unmeasured.class, c.evaluate(withStructures(structures(Set.of(), Map.of(), List.of())), t));
    }

    @Test
    void aZoneScoresItsShareOfIntendedColumns() {
        RollTarget t = target("{}");
        Criterion c = Criteria.forTarget(t, List.of("ring")).stream().filter(x -> x.id().equals("zone_reads_as_intended:ring"))
                .findFirst().orElseThrow();
        RollFacts p = plain();
        RollFacts zoned = facts(p.spawn(), p.biomes(), p.terrain(), p.structures(),
                List.of(new RollFacts.ZoneShare("ring", List.of("minecraft:desert"), 40, 30)));
        assertEquals(0.75, score(c.evaluate(zoned, t)), 1e-9);
        RollFacts emptyZone = facts(p.spawn(), p.biomes(), p.terrain(), p.structures(),
                List.of(new RollFacts.ZoneShare("ring", List.of("minecraft:desert"), 0, 0)));
        assertInstanceOf(Criterion.Result.Unmeasured.class, c.evaluate(emptyZone, t));
        assertInstanceOf(Criterion.Result.Unmeasured.class, c.evaluate(p, t));
    }

    @Test
    void forTargetAddsOneCriterionPerWantShunAndZone() {
        RollTarget t = target("{\"wants\": {\"village\": \"spread\", \"tower\": \"sideways\"}, \"shuns\": [\"monument\"]}");
        List<String> ids = Criteria.forTarget(t, List.of("a")).stream().map(Criterion::id).toList();
        assertTrue(ids.containsAll(List.of("wants:village", "shuns:monument", "zone_reads_as_intended:a")));
        assertFalse(ids.contains("wants:tower"));
        assertEquals(Criteria.all().size() + 3, ids.size());
    }
}
