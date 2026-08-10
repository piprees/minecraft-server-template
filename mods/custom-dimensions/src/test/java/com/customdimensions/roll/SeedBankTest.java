package com.customdimensions.roll;

import com.customdimensions.facts.Measured;
import com.customdimensions.facts.SeedFacts;
import com.customdimensions.score.Scorecard;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The candidate file's shape (round-trips through {@code parseSummary}), the
 * leaderboard's ranking and corruption-tolerance, and the rejected-seed set —
 * the properties {@code SeedBank} exists to hold. None of this touches a
 * filesystem: {@code writeCandidate}/{@code appendRejected}/{@code leaderboard}/
 * {@code rejectedSeeds} are the IO seam and are exercised live, not here.
 */
class SeedBankTest {

    private static Measured<String> goneStr() {
        return Measured.absent("not measured in this fixture");
    }

    /** A minimally valid, fully-absent facts record — enough for toJson() to run without a real measurement. */
    private static SeedFacts fixtureFacts(String dimension, long seed) {
        Measured<Integer> goneInt = Measured.absent("not measured in this fixture");
        Measured<Double> goneDouble = Measured.absent("not measured in this fixture");
        return new SeedFacts("v1.2.3", dimension, seed, "2026-08-10T00:00:00Z", "fp", 4096,
                new SeedFacts.SpawnFacts(
                        Measured.of(new SeedFacts.Column(0, 0, false)),
                        goneStr(), goneInt, goneDouble, Measured.absent("not measured in this fixture")),
                new SeedFacts.BiomeFacts(
                        Measured.absent("not measured in this fixture"), goneInt, goneDouble, goneDouble),
                new SeedFacts.TerrainFacts(goneDouble, goneDouble, goneDouble, goneInt, goneInt),
                new SeedFacts.StructureFacts(
                        Measured.absent("not measured in this fixture"),
                        Measured.absent("not measured in this fixture"),
                        Measured.absent("not measured in this fixture"),
                        Measured.absent("not measured in this fixture"),
                        Measured.absent("not measured in this fixture"),
                        goneDouble, goneDouble, goneInt));
    }

    private static Scorecard fixtureScorecard(String dimension, long seed, double achieved, double ceiling) {
        return new Scorecard(dimension, seed, Scorecard.Verdict.SCORED, "", achieved, ceiling, List.of());
    }

    // -------------------------------------------------------------- candidateJson

    @Test
    void aCandidateFileRoundTripsItsRankingFieldsThroughParseSummary() {
        SeedFacts facts = fixtureFacts("adventure:the_boneyard", 111L);
        Scorecard card = fixtureScorecard("adventure:the_boneyard", 111L, 6.0, 8.0);

        String body = SeedBank.candidateJson("adventure:the_boneyard", 111L, facts, card,
                "abc123hash", "v1.2.3", "2026-08-10T00:00:00Z");
        SeedBank.CandidateSummary summary = SeedBank.parseSummary(body);

        assertEquals(111L, summary.seed());
        assertEquals(6.0, summary.achieved(), 1e-9);
        assertEquals(8.0, summary.ceiling(), 1e-9);
        assertEquals(75.0, summary.percentage(), 1e-9);
        assertEquals("SCORED", summary.verdict());
        // The full facts and scorecard must both actually be in the file, not
        // just the ranking numbers — that is the whole point of one file per
        // candidate carrying the whole record.
        assertTrue(body.contains("\"inputHash\": \"abc123hash\""));
        assertTrue(body.contains("\"facts\": {"));
        assertTrue(body.contains("\"scorecard\": {"));
    }

    @Test
    void aCorruptCandidateFileIsSkippedNotFatal() {
        SeedBank.CandidateSummary summary = SeedBank.parseSummary("{not json");
        assertNull(summary);
    }

    @Test
    void aCandidateWithNoPercentageIsSkipped() {
        // Scorer.score never gives a SCORED verdict without a percentage, but
        // parseSummary must not assume that — an absent one is unusable, not
        // zero.
        String body = "{\"seed\": 1, \"achieved\": 0.0, \"ceiling\": 0.0, "
                + "\"percentage\": null, \"verdict\": \"INVALID_CONFIG\"}";
        assertNull(SeedBank.parseSummary(body));
    }

    // -------------------------------------------------------------------- rank

    @Test
    void rankSortsHighestPercentageFirstAndDropsCorruptEntries() {
        SeedFacts facts = fixtureFacts("adventure:the_boneyard", 0L);
        String low = SeedBank.candidateJson("adventure:the_boneyard", 1L, facts,
                fixtureScorecard("adventure:the_boneyard", 1L, 2.0, 10.0), "h", "v1", "t");
        String high = SeedBank.candidateJson("adventure:the_boneyard", 2L, facts,
                fixtureScorecard("adventure:the_boneyard", 2L, 9.0, 10.0), "h", "v1", "t");

        List<SeedBank.CandidateSummary> ranked = SeedBank.rank(List.of(low, "{garbage", high));

        assertEquals(2, ranked.size(), "the corrupt entry must be dropped, not counted or fatal");
        assertEquals(2L, ranked.get(0).seed(), "the highest percentage must lead");
        assertEquals(1L, ranked.get(1).seed());
    }

    // -------------------------------------------------------------------- rejected

    @Test
    void rejectedSeedsRoundTripThroughParseRejectedSeeds() {
        String body = SeedBank.rejectedJson("adventure:the_boneyard",
                Set.of(111L, 222L, 333L), "v1.2.3", "2026-08-10T00:00:00Z");
        Set<Long> back = SeedBank.parseRejectedSeeds(body);

        assertEquals(Set.of(111L, 222L, 333L), back);
    }

    // --------------------------------------------------------- path pinning

    /**
     * {@code Artefacts.rollingDir()}/{@code overlayDimensionsDir()} call
     * {@code FabricLoader.getInstance().getGameDir()}, which throws
     * {@code IllegalStateException: invoked too early?} in this Gradle test
     * harness (unlike {@code getConfigDir()}/{@code getModContainer()},
     * which work fine here) — there is no fabric-loader-junit stub wired
     * into this module's test source set. So the live root cannot be pinned
     * by a test in this repo today; what CAN be pinned with no Fabric API is
     * the sub-path {@link SeedBank#dimensionDir} builds under whatever root
     * it is given — namespaced by release, colons sanitised, the candidate
     * file living directly inside it.
     */
    @Test
    void theCandidateDirectorySuffixIsNamespacedByReleaseAndSanitisesTheDimensionId() {
        Path root = Path.of("/wherever/.seed-rolling");
        Path dir = SeedBank.dimensionDirUnder(root, "v1.2.3", "adventure:the_boneyard");
        Path candidate = dir.resolve(42L + ".json");

        assertEquals(root.resolve("candidates").resolve("v1.2.3").resolve("adventure__the_boneyard"), dir);
        assertEquals(dir, candidate.getParent(),
                "a candidate file must live directly inside its dimension's directory");
        assertFalse(dir.toString().contains("custom-dimensions"),
                "the candidate directory must never look like the server's staged config dir: " + dir);
    }
}
