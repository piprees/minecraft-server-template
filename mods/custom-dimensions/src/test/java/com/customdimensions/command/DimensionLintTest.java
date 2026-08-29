package com.customdimensions.command;

import com.customdimensions.config.DimensionConfig;
import com.google.gson.Gson;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The self-contained half of {@link DimensionLint}'s checks: every finding
 * carries a fix and a valid severity. Most checks need a live {@code
 * MinecraftServer} (the structure and biome registries) and cannot run
 * here — {@link #checkRadialCurves} reads config alone, and {@link
 * #unknownSuppressedIds} takes the registry lookup as a predicate, and {@link
 * #checkPoolFloor} decides on counts alone, so all three run against plain
 * data. Lint's overall runtime and the registry-backed
 * checks (want/pool cross-checks, suppress-list resolution) need a running
 * server and are exercised by {@code /customdim lint} against one instead.
 */
class DimensionLintTest {

    private static final Gson GSON = new Gson();
    private static final Set<String> VALID_SEVERITIES =
            Set.of(DimensionLint.ERROR, DimensionLint.WARN, DimensionLint.INFO);

    private static DimensionConfig configWithRadial(String json) {
        DimensionConfig config = GSON.fromJson(json, DimensionConfig.class);
        config.setName("the_test");
        return config;
    }

    private static void assertEveryFindingIsActionable(List<DimensionLint.Finding> findings) {
        for (DimensionLint.Finding f : findings) {
            assertTrue(VALID_SEVERITIES.contains(f.severity()),
                    f.check() + " has an invalid severity: " + f.severity());
            assertTrue(f.fix() != null && !f.fix().isBlank(),
                    f.check() + " carries no fix a human can act on");
            assertTrue(f.message() != null && !f.message().isBlank(),
                    f.check() + " carries no message");
        }
    }

    @Test
    void aWellFormedCurveProducesNoFindings() {
        DimensionConfig config = configWithRadial("{\"type\": \"multi_biome\", \"structures\": "
                + "{\"radial\": {\"dungeons\": [0.1,0.2,0.3,0.4,0.5,0.6,0.7,0.8,0.9,1.0]}}}");
        List<DimensionLint.Finding> findings = DimensionLint.checkRadialCurves(config);
        assertEquals(List.of(), findings);
    }

    @Test
    void aCurveOfTheWrongLengthIsActionable() {
        DimensionConfig config = configWithRadial("{\"type\": \"multi_biome\", \"structures\": "
                + "{\"radial\": {\"dungeons\": [1.0, 2.0]}}}");
        List<DimensionLint.Finding> findings = DimensionLint.checkRadialCurves(config);
        assertFalse(findings.isEmpty());
        assertTrue(findings.stream().anyMatch(f -> f.check().equals("radial_wrong_length")));
        assertEveryFindingIsActionable(findings);
    }

    @Test
    void aCurveWithAnOutOfRangePointIsActionable() {
        DimensionConfig config = configWithRadial("{\"type\": \"multi_biome\", \"structures\": "
                + "{\"radial\": {\"dungeons\": [0,0,0,0,0,0,0,0,0,99]}}}");
        List<DimensionLint.Finding> findings = DimensionLint.checkRadialCurves(config);
        assertFalse(findings.isEmpty());
        assertTrue(findings.stream().anyMatch(f -> f.check().equals("radial_out_of_range")));
        assertEveryFindingIsActionable(findings);
    }

    @Test
    void anAllZeroCurveIsActionable() {
        DimensionConfig config = configWithRadial("{\"type\": \"multi_biome\", \"structures\": "
                + "{\"radial\": {\"dungeons\": [0,0,0,0,0,0,0,0,0,0]}}}");
        List<DimensionLint.Finding> findings = DimensionLint.checkRadialCurves(config);
        assertFalse(findings.isEmpty());
        assertTrue(findings.stream().anyMatch(f -> f.check().equals("radial_all_zero")));
        assertEveryFindingIsActionable(findings);
    }

    @Test
    void multipleFaultsOnTheSameGroupAreAllReportedAndAllActionable() {
        // Wrong length AND (had it been the right length) out of range: only
        // the length fault can fire since the value loop is guarded on
        // size() == 10, but a config with faults across TWO groups must
        // report both, independently.
        DimensionConfig config = configWithRadial("{\"type\": \"multi_biome\", \"structures\": "
                + "{\"radial\": {\"dungeons\": [1.0, 2.0], "
                + "\"settlements\": [0,0,0,0,0,0,0,0,0,0]}}}");
        List<DimensionLint.Finding> findings = DimensionLint.checkRadialCurves(config);
        assertTrue(findings.stream().anyMatch(f -> f.subject().equals("dungeons")));
        assertTrue(findings.stream().anyMatch(f -> f.subject().equals("settlements")));
        assertEveryFindingIsActionable(findings);
    }

    // ---------------------------------------------------------- suppress list

    private static final java.util.function.Predicate<Identifier> KNOWN_VILLAGE_PLAINS =
            Set.of(Identifier.of("minecraft", "village_plains"))::contains;

    @Test
    void everySuppressedIdResolvingLeavesNothingUnknown() {
        List<String> suppressed = List.of("minecraft:village_plains");
        assertEquals(List.of(), DimensionLint.unknownSuppressedIds(suppressed, KNOWN_VILLAGE_PLAINS));
    }

    @Test
    void aMalformedSuppressedIdIsUnknown() {
        List<String> suppressed = List.of("not a valid id");
        assertEquals(List.of("not a valid id"),
                DimensionLint.unknownSuppressedIds(suppressed, KNOWN_VILLAGE_PLAINS));
    }

    @Test
    void aWellFormedButUnresolvedSuppressedIdIsUnknown() {
        List<String> suppressed = List.of("minecraft:nonexistent_set");
        assertEquals(List.of("minecraft:nonexistent_set"),
                DimensionLint.unknownSuppressedIds(suppressed, KNOWN_VILLAGE_PLAINS));
    }

    @Test
    void severalUnknownIdsAreAllReported() {
        List<String> suppressed = List.of("minecraft:village_plains", "mvs:barn", "typo:oops");
        assertEquals(List.of("mvs:barn", "typo:oops"),
                DimensionLint.unknownSuppressedIds(suppressed, KNOWN_VILLAGE_PLAINS));
    }

    @Test
    void blankAndNullEntriesAreSkippedRatherThanFlagged() {
        List<String> suppressed = java.util.Arrays.asList("", "   ", null, "minecraft:village_plains");
        assertEquals(List.of(), DimensionLint.unknownSuppressedIds(suppressed, KNOWN_VILLAGE_PLAINS));
    }

    private static Map<String, Integer> groups(Object... pairs) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], (Integer) pairs[i + 1]);
        }
        return out;
    }

    @Test
    void aGroupAtOrAboveTheFloorProducesNoFindings() {
        assertEquals(List.of(), DimensionLint.checkPoolFloor("the_catalyst_maw",
                groups("deco", DimensionLint.POOL_FLOOR, "loot", 22)));
    }

    @Test
    void aThinGroupIsAnErrorNamingTheGroupAndItsSize() {
        List<DimensionLint.Finding> findings = DimensionLint.checkPoolFloor(
                "the_blossom_gardens", groups("maritime", 1, "deco", 18));
        assertEquals(1, findings.size());
        DimensionLint.Finding f = findings.get(0);
        assertEquals("pool_below_floor", f.check());
        assertEquals(DimensionLint.ERROR, f.severity());
        assertEquals("maritime", f.subject());
        assertTrue(f.message().contains("the_blossom_gardens"), f.message());
        assertTrue(f.message().contains("maritime"), f.message());
        assertTrue(f.message().contains("only 1 structure"), f.message());
        assertEveryFindingIsActionable(findings);
    }

    @Test
    void anAbsentGroupCountIsNotAFinding() {
        // Absent and zero mean the same thing: the group is not active here.
        assertEquals(List.of(),
                DimensionLint.checkPoolFloor("the_test", groups("dungeons", null)));
    }

    @Test
    void anEmptyPoolIsNotAFinding() {
        // A landlocked dimension has no maritime structures. The pool builder
        // skips the group; lint must not call that a fault.
        assertEquals(List.of(), DimensionLint.checkPoolFloor(
                "the_greenreach", java.util.Map.of("maritime", 0)));
    }

    @Test
    void reservedDimensionsAreNotExempt() {
        for (String reserved : List.of("overworld", "the_nether", "the_end", "paradise_lost")) {
            List<DimensionLint.Finding> findings =
                    DimensionLint.checkPoolFloor(reserved, groups("dungeons", 2));
            assertEquals(1, findings.size(), reserved + " was skipped");
            assertEquals(DimensionLint.ERROR, findings.get(0).severity());
            assertEquals(reserved, findings.get(0).dimension());
            assertEveryFindingIsActionable(findings);
        }
    }
}
