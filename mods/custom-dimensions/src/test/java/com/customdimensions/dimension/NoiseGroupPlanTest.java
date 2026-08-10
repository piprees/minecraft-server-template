package com.customdimensions.dimension;

import com.customdimensions.config.DimensionConfig;
import com.google.gson.Gson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The config -> type-defaults precedence chain, and the backwards-
 * compatibility rules. Pure, no Bootstrap.
 */
class NoiseGroupPlanTest {

    private static final Gson GSON = new Gson();

    @BeforeEach
    void reset() {
        StructureGroupRegistry.reset();
        StructureThemes.reset();
    }

    private static NoiseGroupPlan plan(String json) {
        DimensionConfig config = GSON.fromJson(json, DimensionConfig.class);
        config.setName("the_test");
        return NoiseGroupPlan.resolve(config);
    }

    // --- zero config -----------------------------------------------------

    @Test
    void typeAndBiomesAloneGetsTheFullTypeDefaults() {
        NoiseGroupPlan p = plan("{\"type\": \"multi_biome\"}");
        assertFalse(p.isSuppressed(), p.reason());
        assertEquals(StructureGroupRegistry.knownGroups(), p.groups().keySet());
        assertSame(NoiseProfile.NATURAL, p.groups().get("settlements").profile());
        assertSame(NoiseProfile.SPARSE, p.groups().get("dungeons").profile());
        // settlements -> inner, dungeons -> outer per the type table. Compared
        // against the named curves rather than against literal values: what
        // this test owns is the MAPPING, so retuning a curve's numbers should
        // not read as a broken precedence chain.
        assertArrayEquals(StructureGroupRegistry.curve("inner"),
                p.groups().get("settlements").radial(), 1e-9);
        assertArrayEquals(StructureGroupRegistry.curve("outer"),
                p.groups().get("dungeons").radial(), 1e-9);
    }

    @Test
    void aTypeWithNoGroupsIsSuppressed() {
        assertTrue(plan("{\"type\": \"void\"}").isSuppressed());
        assertTrue(plan("{\"type\": \"superflat\"}").isSuppressed());
        assertTrue(plan("{}").isSuppressed(), "no type at all");
        assertTrue(plan("{\"type\": \"not_a_type\"}").isSuppressed());
    }

    /**
     * The void/superflat leak: "type enables no groups" means NO organic
     * structures, so the legacy path must drop every set. The other
     * suppression flavours keep their own meanings — density none drops in
     * the legacy loop itself, mode none likewise, and noise=false
     * deliberately keeps the vanilla grids.
     */
    @Test
    void onlyTypeWithoutGroupsSuppressesAllSets() {
        assertTrue(plan("{\"type\": \"void\"}").suppressesAllSets());
        assertTrue(plan("{\"type\": \"superflat\"}").suppressesAllSets());
        assertTrue(plan("{}").suppressesAllSets());
        assertTrue(plan("{\"type\": \"not_a_type\"}").suppressesAllSets());
        assertFalse(plan("{\"type\": \"multi_biome\", \"structureDensity\": \"none\"}")
                .suppressesAllSets());
        assertFalse(plan("{\"type\": \"multi_biome\", \"structures\": {\"mode\": \"none\"}}")
                .suppressesAllSets());
        assertFalse(plan("{\"type\": \"multi_biome\", \"structures\": {\"noise\": false}}")
                .suppressesAllSets());
        assertFalse(plan("{\"type\": \"multi_biome\"}").suppressesAllSets(), "not suppressed at all");
    }

    @Test
    void netherEnablesItsOwnGroupsAtItsOwnProfiles() {
        NoiseGroupPlan p = plan("{\"type\": \"nether\"}");
        assertEquals(java.util.Set.of("deco", "settlements", "dungeons", "landmarks", "endgame"),
                p.groups().keySet());
        assertSame(NoiseProfile.NATURAL, p.groups().get("dungeons").profile(),
                "nether overrides dungeons to natural");
    }

    // --- structureDensity ------------------------------------------------

    @Test
    void densityNoneSuppressesEverything() {
        NoiseGroupPlan p = plan("{\"type\": \"multi_biome\", \"structureDensity\": \"none\"}");
        assertTrue(p.isSuppressed());
        assertEquals("structureDensity=none", p.reason());
        assertTrue(p.groups().isEmpty());
    }

    @Test
    void densityNormalMeansTypeDefaultsNotAProfile() {
        NoiseGroupPlan normal = plan("{\"type\": \"multi_biome\", \"structureDensity\": \"normal\"}");
        NoiseGroupPlan absent = plan("{\"type\": \"multi_biome\"}");
        assertEquals(absent.groups().get("dungeons").profile(),
                normal.groups().get("dungeons").profile());
    }

    @Test
    void densityAppliesOneProfileToEveryGroup() {
        NoiseGroupPlan p = plan("{\"type\": \"multi_biome\", \"structureDensity\": \"dense\"}");
        for (var g : p.groups().values()) {
            assertSame(NoiseProfile.DENSE, g.profile(), g.name());
        }
    }

    @Test
    void unknownDensityFallsBackToNormal() {
        NoiseGroupPlan p = plan("{\"type\": \"multi_biome\", \"structureDensity\": \"gibberish\"}");
        assertFalse(p.isSuppressed());
        assertSame(NoiseProfile.SPARSE, p.groups().get("dungeons").profile());
    }

    // --- structures.noise ------------------------------------------------

    @Test
    void noiseStringFormAppliesToEveryGroup() {
        NoiseGroupPlan p = plan(
                "{\"type\": \"multi_biome\", \"structures\": {\"noise\": \"sparse\"}}");
        for (var g : p.groups().values()) {
            assertSame(NoiseProfile.SPARSE, g.profile(), g.name());
        }
    }

    @Test
    void noiseMapFormOverridesPerGroup() {
        NoiseGroupPlan p = plan("{\"type\": \"multi_biome\", \"structures\": {\"noise\": "
                + "{\"dungeons\": \"cluster\", \"settlements\": \"none\"}}}");
        assertSame(NoiseProfile.CLUSTER, p.groups().get("dungeons").profile());
        assertFalse(p.groups().containsKey("settlements"), "\"none\" must drop the group");
        assertSame(NoiseProfile.NATURAL, p.groups().get("deco").profile(),
                "unmentioned groups keep the type default");
    }

    @Test
    void perGroupNoiseBeatsTheGlobalDensity() {
        NoiseGroupPlan p = plan("{\"type\": \"multi_biome\", \"structureDensity\": \"dense\","
                + " \"structures\": {\"noise\": {\"dungeons\": \"sparse\"}}}");
        assertSame(NoiseProfile.SPARSE, p.groups().get("dungeons").profile());
        assertSame(NoiseProfile.DENSE, p.groups().get("deco").profile());
    }

    @Test
    void unknownProfileNameSuppressesTheGroupRatherThanGuessing() {
        NoiseGroupPlan p = plan("{\"type\": \"multi_biome\", \"structures\": "
                + "{\"noise\": {\"dungeons\": \"nonsense\"}}}");
        assertFalse(p.groups().containsKey("dungeons"));
        assertTrue(p.groups().containsKey("deco"), "other groups are unaffected");
    }

    @Test
    void unknownGroupNameIsIgnoredNotFatal() {
        NoiseGroupPlan p = plan("{\"type\": \"multi_biome\", \"structures\": "
                + "{\"noise\": {\"badgroup\": \"natural\"}}}");
        assertFalse(p.isSuppressed());
        assertEquals(StructureGroupRegistry.knownGroups(), p.groups().keySet());
    }

    @Test
    void noiseFalseIsTheGridEscapeHatch() {
        NoiseGroupPlan p = plan("{\"type\": \"multi_biome\", \"structures\": {\"noise\": false}}");
        assertTrue(p.isSuppressed());
        assertTrue(p.reason().contains("grid mode"));
    }

    // --- backwards compatibility ------------------------------------------

    @Test
    void deprecatedModeNoneStillSuppresses() {
        // the_dustbowl's shape before noise existed.
        NoiseGroupPlan p = plan("{\"type\": \"multi_biome\", \"structures\": {\"mode\": \"none\","
                + " \"force\": [{\"structure\": \"explorify:farmstead\", \"x\": -87, \"z\": -312}]}}");
        assertTrue(p.isSuppressed());
        assertTrue(p.reason().contains("deprecated"));
    }

    @Test
    void densityNoneWithForceIsUnchangedFromToday() {
        NoiseGroupPlan p = plan("{\"type\": \"multi_biome\", \"structureDensity\": \"none\","
                + " \"structures\": {\"force\": [{\"structure\": \"a:b\", \"x\": 0, \"z\": 0}]}}");
        assertTrue(p.isSuppressed());
    }

    // --- difficulty shifts -------------------------------------------------

    @Test
    void peacefulWorldsDropDungeonsAndEndgame() {
        NoiseGroupPlan p = plan("{\"type\": \"multi_biome\", "
                + "\"difficulty\": {\"mobMultiplier\": 0.0}}");
        assertFalse(p.groups().containsKey("dungeons"));
        assertFalse(p.groups().containsKey("endgame"));
        assertTrue(p.groups().containsKey("settlements"));
        assertTrue(p.groups().containsKey("deco"));
    }

    @Test
    void hostileWorldsSpreadDungeonsEvenlyAndBringEndgameInwards() {
        NoiseGroupPlan p = plan("{\"type\": \"multi_biome\", "
                + "\"difficulty\": {\"mobMultiplier\": 2.5}}");
        assertArrayEquals(StructureGroupRegistry.curve("even"),
                p.groups().get("dungeons").radial(), 1e-9,
                "hostile dungeons should use `even`");
        double[] endgame = p.groups().get("endgame").radial();
        assertArrayEquals(StructureGroupRegistry.curve("mid"), endgame, 1e-9,
                "hostile endgame should use `mid`");
        assertTrue(endgame[4] > endgame[9], "`mid` peaks away from the border");
    }

    @Test
    void explicitConfigBeatsThePeacefulShift() {
        NoiseGroupPlan p = plan("{\"type\": \"multi_biome\", "
                + "\"difficulty\": {\"mobMultiplier\": 0.0}, "
                + "\"structures\": {\"noise\": {\"dungeons\": \"dense\"}}}");
        assertSame(NoiseProfile.DENSE, p.groups().get("dungeons").profile(),
                "an author who names a profile means it");
    }

    @Test
    void middlingDifficultyTriggersNeitherShift() {
        NoiseGroupPlan p = plan("{\"type\": \"multi_biome\", "
                + "\"difficulty\": {\"mobMultiplier\": 1.0}}");
        assertTrue(p.groups().containsKey("dungeons"));
        assertArrayEquals(StructureGroupRegistry.curve("outer"),
                p.groups().get("dungeons").radial(), 1e-9, "still `outer`");
    }

    // --- radial overrides --------------------------------------------------

    @Test
    void explicitRadialCurveWins() {
        NoiseGroupPlan p = plan("{\"type\": \"multi_biome\", \"structures\": {\"radial\": "
                + "{\"dungeons\": [0.1,0.2,0.3,0.4,0.5,0.6,0.7,0.8,0.9,1.0]}}}");
        assertEquals(0.1, p.groups().get("dungeons").radial()[0], 1e-6);
        assertEquals(1.0, p.groups().get("dungeons").radial()[9], 1e-6);
    }

    @Test
    void malformedRadialCurveFallsBackInsteadOfCrashing() {
        // Wrong length
        NoiseGroupPlan shortCurve = plan("{\"type\": \"multi_biome\", "
                + "\"structures\": {\"radial\": {\"dungeons\": [1.0, 2.0]}}}");
        assertArrayEquals(StructureGroupRegistry.curve("outer"),
                shortCurve.groups().get("dungeons").radial(), 1e-9,
                "a malformed curve falls back to the type default");
        // Out of range
        NoiseGroupPlan wild = plan("{\"type\": \"multi_biome\", \"structures\": {\"radial\": "
                + "{\"dungeons\": [0,0,0,0,0,0,0,0,0,99]}}}");
        assertArrayEquals(StructureGroupRegistry.curve("outer"),
                wild.groups().get("dungeons").radial(), 1e-9);
    }

    @Test
    void curveValidatorAcceptsTheBoundsAndRejectsOutside() {
        assertNotNull(NoiseGroupPlan.toCurve("d", "g",
                java.util.List.of(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 3.0)));
        assertNull(NoiseGroupPlan.toCurve("d", "g",
                java.util.List.of(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 3.01)));
        assertNull(NoiseGroupPlan.toCurve("d", "g",
                java.util.List.of(-0.01, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0)));
        assertNull(NoiseGroupPlan.toCurve("d", "g", null));
    }

    // --- exclusion ---------------------------------------------------------

    @Test
    void exclusionScalesWithTheProfileMultiplier() {
        // deco base exclusion is 3: dense x1.6 -> 5, sparse x2.6 -> 8.
        NoiseGroupPlan dense = plan(
                "{\"type\": \"multi_biome\", \"structures\": {\"noise\": \"dense\"}}");
        NoiseGroupPlan sparse = plan(
                "{\"type\": \"multi_biome\", \"structures\": {\"noise\": \"sparse\"}}");
        assertEquals(5, dense.groups().get("deco").exclusion());
        assertEquals(8, sparse.groups().get("deco").exclusion());
        assertTrue(sparse.groups().get("endgame").exclusion()
                > dense.groups().get("endgame").exclusion());
    }

    @Test
    void exclusionNeverDropsBelowOne() {
        NoiseGroupPlan p = plan("{\"type\": \"cave\", \"structures\": {\"noise\": \"cluster\"}}");
        for (var g : p.groups().values()) {
            assertTrue(g.exclusion() >= 1, g.name() + " exclusion " + g.exclusion());
        }
    }

    // --- base worlds -------------------------------------------------------

    @Test
    void aBaseWorldResolvesItsFamilysGroupsFromItsNameAlone() {
        // A base-world file names no type — its generator is vanilla's — so
        // the family comes from the filename the loader stamped.
        DimensionConfig config = GSON.fromJson(
                "{\"seed\": 42, \"borders\": {\"player\": 1024}}", DimensionConfig.class);
        config.setName("the_nether");
        NoiseGroupPlan p = NoiseGroupPlan.resolve(config);
        assertFalse(p.isSuppressed(), p.reason());
        assertEquals(java.util.Set.of("deco", "settlements", "dungeons", "landmarks", "endgame"),
                p.groups().keySet());
    }

    @Test
    void eachBaseWorldCanonicalTypeResolvesToItsFamilysGroups() {
        assertEquals(StructureGroupRegistry.knownGroups(),
                plan("{\"type\": \"overworld\"}").groups().keySet());
        assertEquals(java.util.Set.of("deco", "settlements", "dungeons", "landmarks", "endgame"),
                plan("{\"type\": \"nether\"}").groups().keySet());
        assertEquals(java.util.Set.of("deco", "dungeons", "landmarks", "maritime", "endgame"),
                plan("{\"type\": \"end\"}").groups().keySet());
        assertEquals(java.util.Set.of("deco", "settlements", "landmarks"),
                plan("{\"type\": \"paradise_lost:paradise_lost\"}").groups().keySet());
    }

    @Test
    void baseWorldDifficultyStillDrivesTheShifts() {
        // the_end ships mobMultiplier 1.5 and the_nether higher still — the
        // shifts must apply to a base world exactly as to any other dimension.
        NoiseGroupPlan hostile = plan("{\"type\": \"nether\", "
                + "\"difficulty\": {\"mobMultiplier\": 2.5}}");
        assertEquals(1.0, hostile.groups().get("dungeons").radial()[0], 1e-6);
        NoiseGroupPlan peaceful = plan("{\"type\": \"overworld\", "
                + "\"difficulty\": {\"mobMultiplier\": 0.0}}");
        assertFalse(peaceful.groups().containsKey("dungeons"));
    }

    // --- salts -------------------------------------------------------------

    @Test
    void saltsDifferForSimilarNames() {
        long a = DimensionStructures.saltOf("the_overgrowth");
        long b = DimensionStructures.saltOf("the_overgrowti");
        long c = DimensionStructures.saltOf("deco");
        long d = DimensionStructures.saltOf("dungeons");
        assertTrue(Math.abs(a - b) > 1_000_000L,
                "similar dimension names produced adjacent salts");
        assertTrue(Math.abs(c - d) > 1_000_000L,
                "group salts are adjacent");
        assertEquals(0L, DimensionStructures.saltOf(null));
        assertEquals(a, DimensionStructures.saltOf("the_overgrowth"), "salt must be stable");
    }
}
