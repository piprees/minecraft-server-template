package com.customdimensions.dimension;

import com.customdimensions.config.DimensionConfig;
import com.customdimensions.config.DimensionConfigLoader;
import net.minecraft.util.math.ChunkPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real shipped dimension configs, checked against the per-dimension noise
 * expectations a live census would show: which groups are active, the radial
 * curve assigned to a group, spawn-biased groups, and the forced placements a
 * config declares. All of it resolves through {@link NoiseGroupPlan#resolve}
 * and {@link NoiseFieldIndex}, which need no world — the position field a
 * group produces depends only on config, never on which real structure ends
 * up at a position.
 *
 * <p>What is NOT here, because it needs the live Minecraft structure registry
 * (Bootstrap) rather than pure config: whether a specific structure id
 * actually reaches a group's pool ({@code present}/{@code absent}), whether a
 * forced structure is actually removed from that pool, and structure-specific
 * reachability floors (e.g. "a fortress within 512 blocks of nether spawn") —
 * those depend on which real structure a position gets assigned, which comes
 * from the ~150-mod structure-set registry. {@code /customdim facts <dim>
 * <seed>} measures a dimension's structure census headlessly against that
 * registry (see {@link com.customdimensions.facts.FactsEngine});
 * {@code /customdim structure-census <dim>} (see
 * {@link com.customdimensions.command.CensusCommands}) cross-checks it
 * against a loaded world's own live placement — this codebase's
 * live-vs-headless parity effort, not this test.
 */
class NoiseRegressionTest {

    /** Repo root, from the Gradle project directory (mods/custom-dimensions). */
    private static final Path CONFIG_ROOT = Path.of("..", "..", "config", "custom-dimensions");

    @BeforeEach
    void reset() {
        StructureGroupRegistry.reset();
        StructureThemes.reset();
    }

    private Map<String, DimensionConfig> shipped() {
        assertTrue(Files.isDirectory(CONFIG_ROOT.resolve("dimensions")),
                "shipped dimension configs not found at " + CONFIG_ROOT.toAbsolutePath());
        Map<String, DimensionConfig> dims =
                DimensionConfigLoader.loadAll(CONFIG_ROOT, null, "adventure");
        assertFalse(dims.isEmpty(), "loaded no dimensions at all");
        return dims;
    }

    private DimensionConfig dim(String slug) {
        DimensionConfig config = shipped().get(slug);
        assertNotNull(config, "no shipped config for " + slug);
        return config;
    }

    // --- shared machinery --------------------------------------------------

    /**
     * The exact position field {@link DimensionStructures} builds for one
     * active group at world load: same noise seed derivation
     * ({@code worldSeed ^ dimensionSalt ^ groupSalt}), same radius
     * ({@code borders.player / 16}), same spawn-centred origin.
     */
    private static NoiseFieldIndex fieldFor(DimensionConfig config, NoiseGroupPlan.Group group) {
        long noiseSeed = config.getSeed() ^ DimensionStructures.saltOf(config.getName())
                ^ DimensionStructures.saltOf(group.name());
        int radiusChunks = config.getPlayerBorderRadius() / 16;
        return new NoiseFieldIndex(noiseSeed, group.profile(), group.exclusion(),
                group.radial(), radiusChunks, 0, 0, group.clearSpawnChunks());
    }

    private static int totalPositions(DimensionConfig config, NoiseGroupPlan plan) {
        int total = 0;
        for (NoiseGroupPlan.Group group : plan.groups().values()) {
            total += fieldFor(config, group).size();
        }
        return total;
    }

    /** Structure ids named under structures.force, in file order. */
    private static List<String> forcedStructureIds(DimensionConfig config) {
        List<String> out = new ArrayList<>();
        DimensionConfig.Structures block = config.getStructures();
        if (block != null && block.force != null) {
            for (DimensionConfig.ForcedStructure f : block.force) {
                if (f != null && f.structure != null) {
                    out.add(f.structure);
                }
            }
        }
        return out;
    }

    /**
     * The fraction of a curve's area-weighted density (2r dr) that falls
     * inside half the radius. `even` gives 0.25 — the flat-disc answer.
     */
    private static double curveInnerShare(double[] curve) {
        int steps = 2000;
        int last = curve.length - 1;
        double total = 0.0;
        double inside = 0.0;
        for (int i = 0; i < steps; i++) {
            double r = (i + 0.5) / steps;
            double pos = r * last;
            int lo = (int) pos;
            int hi = Math.min(lo + 1, last);
            double t = pos - lo;
            double area = (curve[lo] * (1 - t) + curve[hi] * t) * 2 * r;
            total += area;
            if (r <= 0.5) {
                inside += area;
            }
        }
        return total > 0 ? inside / total : 0.0;
    }

    /**
     * Asserts a group's measured positions are biased toward spawn at least
     * as much as its own radial curve predicts, with two binomial standard
     * deviations of headroom (a census is a sample).
     */
    private static void assertInnerBias(DimensionConfig config, NoiseGroupPlan plan, String groupName) {
        NoiseGroupPlan.Group group = plan.groups().get(groupName);
        assertNotNull(group, groupName + " is not an active group for " + config.getName());
        List<ChunkPos> positions = fieldFor(config, group).positions();
        assertFalse(positions.isEmpty(), groupName + " produced no positions to judge bias with");
        double radius = Math.max(1, config.getPlayerBorderRadius() / 16.0);
        long inner = positions.stream().filter(p -> Math.hypot(p.x, p.z) <= radius * 0.5).count();
        double[] curve = group.radial();
        double expected = curve != null ? curveInnerShare(curve) : 0.25;
        int n = positions.size();
        double observed = inner / (double) n;
        double floor = expected - 2.0 * Math.sqrt(expected * (1 - expected) / n);
        assertTrue(observed >= floor,
                groupName + ": curve predicts " + expected + " inside half-radius (floor " + floor
                + "), measured " + inner + "/" + n + " = " + observed);
    }

    // --- the_dustbowl: structureDensity none + force, the escape hatch -----

    @Test
    void theDustbowlHasNoNoiseGroupsAndKeepsItsForcedFarmstead() {
        DimensionConfig config = dim("the_dustbowl");
        NoiseGroupPlan plan = NoiseGroupPlan.resolve(config);
        assertEquals(Set.of(), plan.groups().keySet());
        assertEquals(List.of("explorify:farmstead"), forcedStructureIds(config));
        assertTrue(NoisePoolBuilder.forcedExclusiveStructureIds(config)
                .contains("explorify:farmstead"));
    }

    // --- the_gilded_pit: dense nether pocket with nine forced placements ---

    @Test
    void theGildedPitEnablesItsNetherGroupsAndKeepsNineForcedPlacements() {
        DimensionConfig config = dim("the_gilded_pit");
        NoiseGroupPlan plan = NoiseGroupPlan.resolve(config);
        assertTrue(plan.groups().keySet().containsAll(Set.of("deco", "settlements", "dungeons")),
                plan.groups().keySet().toString());
        List<String> forced = forcedStructureIds(config);
        assertEquals(9, forced.size(), forced.toString());
        assertEquals(Set.copyOf(forced), NoisePoolBuilder.forcedExclusiveStructureIds(config),
                "every forced placement here defaults to exclusive");
    }

    // --- the_overgrowth: jungle multi_biome, the biome filter is zero-config

    @Test
    void theOvergrowthEnablesSettlementsDungeonsAndLandmarksOnTheirTypeCurves() {
        DimensionConfig config = dim("the_overgrowth");
        NoiseGroupPlan plan = NoiseGroupPlan.resolve(config);
        assertTrue(plan.groups().keySet().containsAll(
                Set.of("deco", "settlements", "dungeons", "landmarks")));
        assertEquals(StructureGroupRegistry.curve("inner"),
                plan.groups().get("settlements").radial());
        assertEquals(StructureGroupRegistry.curve("outer"),
                plan.groups().get("dungeons").radial());
        assertEquals(StructureGroupRegistry.curve("mid"),
                plan.groups().get("landmarks").radial());
    }

    @Test
    void theOvergrowthsSettlementsAreBiasedTowardSpawn() {
        DimensionConfig config = dim("the_overgrowth");
        NoiseGroupPlan plan = NoiseGroupPlan.resolve(config);
        assertInnerBias(config, plan, "settlements");
    }

    // --- the_burning_archipelago: large nether_islands, spread across the world

    @Test
    void theBurningArchipelagoEnablesItsNetherIslandsGroups() {
        DimensionConfig config = dim("the_burning_archipelago");
        NoiseGroupPlan plan = NoiseGroupPlan.resolve(config);
        assertTrue(plan.groups().keySet().containsAll(Set.of("deco", "settlements", "dungeons")),
                plan.groups().keySet().toString());
    }

    @Test
    void theBurningArchipelagoPlacesStructuresBeyond500Blocks() {
        DimensionConfig config = dim("the_burning_archipelago");
        NoiseGroupPlan plan = NoiseGroupPlan.resolve(config);
        int total = 0;
        boolean farExists = false;
        for (NoiseGroupPlan.Group group : plan.groups().values()) {
            for (ChunkPos p : fieldFor(config, group).positions()) {
                total++;
                if (Math.hypot(p.x, p.z) * 16 > 500) {
                    farExists = true;
                }
            }
        }
        assertTrue(total > 0, "no placements at all");
        assertTrue(farExists, "every placement stayed within 500 blocks of spawn");
    }

    // --- the_frozen_strait: pocket maritime -----------------------------

    @Test
    void theFrozenStraitEnablesMaritimeAndDeco() {
        DimensionConfig config = dim("the_frozen_strait");
        NoiseGroupPlan plan = NoiseGroupPlan.resolve(config);
        assertTrue(plan.groups().keySet().containsAll(Set.of("maritime", "deco")),
                plan.groups().keySet().toString());
    }

    // --- the_blackstone_keep: nether, bastion forced ------------------------

    @Test
    void theBlackstoneKeepEnablesItsNetherGroupsAndForcesTheBastion() {
        DimensionConfig config = dim("the_blackstone_keep");
        NoiseGroupPlan plan = NoiseGroupPlan.resolve(config);
        assertTrue(plan.groups().keySet().containsAll(Set.of("deco", "dungeons", "settlements")),
                plan.groups().keySet().toString());
        assertEquals(List.of("minecraft:bastion_remnant"), forcedStructureIds(config));
        assertEquals(Set.of("minecraft:bastion_remnant"),
                NoisePoolBuilder.forcedExclusiveStructureIds(config));
    }

    // --- the_end_citadel: end + dense, at the 4096 border most large dims use

    @Test
    void theEndCitadelEnablesDecoDungeonsAndEndgame() {
        DimensionConfig config = dim("the_end_citadel");
        NoiseGroupPlan plan = NoiseGroupPlan.resolve(config);
        assertTrue(plan.groups().keySet().containsAll(Set.of("deco", "dungeons", "endgame")),
                plan.groups().keySet().toString());
    }

    @Test
    void theEndCitadelPlacesSeveralThousandPositions() {
        DimensionConfig config = dim("the_end_citadel");
        NoiseGroupPlan plan = NoiseGroupPlan.resolve(config);
        assertTrue(totalPositions(config, plan) >= 3_000,
                "got " + totalPositions(config, plan));
    }

    // --- the_luminous_caverns: peaceful cave, an explicit override outranks
    //     the peaceful shift ------------------------------------------------

    @Test
    void theLuminousCavernsKeepsDungeonsBecauseOfTheExplicitOverride() {
        DimensionConfig config = dim("the_luminous_caverns");
        NoiseGroupPlan plan = NoiseGroupPlan.resolve(config);
        assertTrue(plan.groups().keySet().containsAll(
                Set.of("deco", "loot", "dungeons", "landmarks")), plan.groups().keySet().toString());
        assertFalse(plan.groups().containsKey("endgame"));
        assertFalse(plan.groups().containsKey("settlements"));
        assertFalse(plan.groups().containsKey("maritime"));
        assertEquals(NoiseProfile.SPARSE, plan.groups().get("dungeons").profile(),
                "structures.noise.dungeons must outrank the peaceful shift");
    }

    // --- the_shattered_skies: sky_islands, no maritime at all --------------

    @Test
    void theShatteredSkiesHasNoMaritimeGroup() {
        DimensionConfig config = dim("the_shattered_skies");
        NoiseGroupPlan plan = NoiseGroupPlan.resolve(config);
        assertTrue(plan.groups().keySet().containsAll(
                Set.of("deco", "landmarks", "settlements", "loot", "endgame")),
                plan.groups().keySet().toString());
        assertFalse(plan.groups().containsKey("maritime"));
    }

    // --- the_sunken_temple: paradise_lost clone, its own mod's structures --

    @Test
    void theSunkenTempleAddsDungeonsAndMaritimeButNotLootOrEndgame() {
        DimensionConfig config = dim("the_sunken_temple");
        NoiseGroupPlan plan = NoiseGroupPlan.resolve(config);
        assertTrue(plan.groups().keySet().containsAll(Set.of(
                "deco", "landmarks", "settlements", "dungeons", "maritime")),
                plan.groups().keySet().toString());
        assertFalse(plan.groups().containsKey("endgame"));
        assertFalse(plan.groups().containsKey("loot"));
    }

    // --- reserved dimensions: managed exactly like custom dimensions --------

    @Test
    void theOverworldEnablesItsFullGroupSetOnItsOwnCurves() {
        DimensionConfig config = dim("overworld");
        NoiseGroupPlan plan = NoiseGroupPlan.resolve(config);
        assertTrue(plan.groups().keySet().containsAll(
                Set.of("deco", "settlements", "dungeons", "landmarks", "maritime")),
                plan.groups().keySet().toString());
        assertEquals(StructureGroupRegistry.curve("inner"),
                plan.groups().get("settlements").radial());
        assertEquals(StructureGroupRegistry.curve("outer"),
                plan.groups().get("dungeons").radial());
    }

    @Test
    void theNetherEnablesDecoAndDungeons() {
        DimensionConfig config = dim("the_nether");
        NoiseGroupPlan plan = NoiseGroupPlan.resolve(config);
        assertTrue(plan.groups().keySet().containsAll(Set.of("deco", "dungeons")),
                plan.groups().keySet().toString());
    }

    @Test
    void theEndEnablesDecoAndEndgame() {
        DimensionConfig config = dim("the_end");
        NoiseGroupPlan plan = NoiseGroupPlan.resolve(config);
        assertTrue(plan.groups().keySet().containsAll(Set.of("deco", "endgame")),
                plan.groups().keySet().toString());
    }

    @Test
    void paradiseLostAddsDungeonsButNotEndgameMaritimeOrLoot() {
        DimensionConfig config = dim("paradise_lost");
        NoiseGroupPlan plan = NoiseGroupPlan.resolve(config);
        assertTrue(plan.groups().keySet().containsAll(
                Set.of("deco", "landmarks", "dungeons")), plan.groups().keySet().toString());
        assertFalse(plan.groups().containsKey("endgame"));
        assertFalse(plan.groups().containsKey("maritime"));
        assertFalse(plan.groups().containsKey("loot"));
    }
}
