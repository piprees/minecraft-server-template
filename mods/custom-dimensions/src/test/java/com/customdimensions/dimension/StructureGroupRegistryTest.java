package com.customdimensions.dimension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads the real jar resources from the build output, so this also gates
 * that gen-structure-groups.py's output is loadable.
 */
class StructureGroupRegistryTest {

    @BeforeEach
    void clearCaches() {
        StructureGroupRegistry.reset();
        StructureThemes.reset();
    }

    // --- rarity ----------------------------------------------------------

    @Test
    void rarityThresholdsMatchTheGenerator() {
        assertEquals("common", StructureGroupRegistry.rarityForSpacing(0));
        assertEquals("common", StructureGroupRegistry.rarityForSpacing(24));
        assertEquals("uncommon", StructureGroupRegistry.rarityForSpacing(25));
        assertEquals("uncommon", StructureGroupRegistry.rarityForSpacing(45));
        assertEquals("rare", StructureGroupRegistry.rarityForSpacing(46));
        assertEquals("rare", StructureGroupRegistry.rarityForSpacing(80));
        assertEquals("endgame", StructureGroupRegistry.rarityForSpacing(81));
        assertEquals("endgame", StructureGroupRegistry.rarityForSpacing(600));
    }

    @Test
    void nonRandomSpreadPlacementsLandOnTheMiddleTier() {
        // -1 means "not a random_spread placement, no spacing to read". It
        // must not be treated as spacing 0 (which would say `common` and let
        // an unclassifiable set flood its group).
        assertEquals("uncommon", StructureGroupRegistry.rarityForSpacing(-1));
    }

    // --- known sets ------------------------------------------------------

    @Test
    void knownSetsComeFromTheRegistryNotInference() {
        Map<String, String> expected = Map.of(
                "minecraft:villages", "settlements",
                "minecraft:shipwrecks", "maritime",
                "minecraft:igloos", "landmarks",
                "minecraft:jungle_temples", "landmarks",
                "minecraft:mineshafts", "dungeons",
                "minecraft:buried_treasures", "loot",
                "mns:mega_fortress", "endgame",
                "epic:large_dungeons", "endgame");
        for (Map.Entry<String, String> e : expected.entrySet()) {
            var entry = StructureGroupRegistry.classify(e.getKey(), 32);
            assertEquals(e.getValue(), entry.group(), e.getKey());
            assertEquals(StructureGroupRegistry.Source.REGISTRY, entry.source(), e.getKey());
        }
    }

    @Test
    void registryRarityWinsOverTheLivePlacementSpacing() {
        // The baked rarity is derived from the pinned jar's spacing. A live
        // placement rescaled by a datapack must not silently reclassify it.
        var entry = StructureGroupRegistry.classify("nova_structures:shrine_tower", 10);
        assertEquals("endgame", entry.rarity());
        assertEquals(StructureGroupRegistry.Source.REGISTRY, entry.source());
    }

    // --- inference -------------------------------------------------------

    @Test
    void unknownSetsBecomeDecoWithAnInferredRarity() {
        var entry = StructureGroupRegistry.classify("somemod:brand_new_thing", 61);
        assertEquals("deco", entry.group());
        assertEquals("rare", entry.rarity());
        assertEquals(StructureGroupRegistry.Source.INFERRED, entry.source());
    }

    @Test
    void unknownSetsAreRecordedForTheAuditCommand() {
        StructureGroupRegistry.classify("somemod:one", 12);
        StructureGroupRegistry.classify("somemod:two", 300);
        StructureGroupRegistry.classify("somemod:one", 12);   // repeat
        List<StructureGroupRegistry.Entry> inferred = StructureGroupRegistry.inferred();
        List<String> ids = inferred.stream().map(StructureGroupRegistry.Entry::setId).toList();
        assertTrue(ids.contains("somemod:one"));
        assertTrue(ids.contains("somemod:two"));
        assertEquals(ids.size(), ids.stream().distinct().count(),
                "a repeated lookup logged the same set twice");
        assertFalse(ids.contains("minecraft:villages"),
                "a known set was recorded as inferred");
    }

    @Test
    void nullSetIdIsHandled() {
        var entry = StructureGroupRegistry.classify(null, 30);
        assertEquals("deco", entry.group());
        assertEquals("uncommon", entry.rarity());
    }

    // --- type defaults ---------------------------------------------------

    @Test
    void typeDefaultsLoadFromTheJarResource() {
        var d = StructureGroupRegistry.defaults();
        assertFalse(d.types().isEmpty(), "structure_type_defaults.json did not load");
        assertFalse(d.curves().isEmpty());
        assertFalse(d.groupDefaults().isEmpty());
    }

    @Test
    void groupsForTypeMatchesTheSpikeTable() {
        assertEquals(List.of("deco", "dungeons", "loot"),
                StructureGroupRegistry.groupsForType("cave"));
        assertEquals(List.of("deco", "settlements", "dungeons", "landmarks", "endgame"),
                StructureGroupRegistry.groupsForType("nether"));
        assertEquals(List.of("deco", "dungeons", "landmarks", "maritime", "endgame"),
                StructureGroupRegistry.groupsForType("end"));
        assertEquals(List.of("deco", "settlements", "landmarks"),
                StructureGroupRegistry.groupsForType("paradise_lost:paradise_lost"));
        assertEquals(7, StructureGroupRegistry.groupsForType("multi_biome").size());
    }

    @Test
    void voidAndSuperflatEnableNothing() {
        assertTrue(StructureGroupRegistry.groupsForType("void").isEmpty());
        assertTrue(StructureGroupRegistry.groupsForType("superflat").isEmpty());
    }

    @Test
    void unknownTypeEnablesNothingRatherThanEverything() {
        assertTrue(StructureGroupRegistry.groupsForType("not_a_type").isEmpty());
        assertTrue(StructureGroupRegistry.groupsForType(null).isEmpty());
    }

    @Test
    void groupDefaultsResolve() {
        for (String group : StructureGroupRegistry.knownGroups()) {
            var gd = StructureGroupRegistry.groupDefault(group);
            assertNotNull(gd, group);
            assertNotNull(NoiseProfile.fromString(gd.profile()), group + " profile");
            assertNotNull(StructureGroupRegistry.curve(gd.radial()), group + " curve");
            assertTrue(gd.exclusion() >= 1, group + " exclusion");
        }
    }

    @Test
    void curvesAreTenPoints() {
        for (String name : List.of("inner", "outer", "even", "mid")) {
            double[] curve = StructureGroupRegistry.curve(name);
            assertNotNull(curve, name);
            assertEquals(10, curve.length, name);
        }
    }

    @Test
    void rarityShareDecreasesWithRarity() {
        double common = StructureGroupRegistry.rarityShare("common");
        double uncommon = StructureGroupRegistry.rarityShare("uncommon");
        double rare = StructureGroupRegistry.rarityShare("rare");
        double endgame = StructureGroupRegistry.rarityShare("endgame");
        assertTrue(common > uncommon && uncommon > rare && rare > endgame,
                common + " " + uncommon + " " + rare + " " + endgame);
        assertEquals(1.0, StructureGroupRegistry.rarityShare("nonsense"), 1e-9,
                "an unknown tier must be neutral, not zero");
    }

    @Test
    void difficultyShiftsAreLoaded() {
        var d = StructureGroupRegistry.defaults();
        assertEquals(2.0, d.hostileMinMobMultiplier(), 1e-9);
        assertEquals("even", d.hostileRadial().get("dungeons"));
        assertEquals("mid", d.hostileRadial().get("endgame"));
        assertEquals(0.5, d.peacefulMaxMobMultiplier(), 1e-9);
        assertEquals("none", d.peacefulProfiles().get("dungeons"));
        assertEquals("none", d.peacefulProfiles().get("endgame"));
    }

    @Test
    void everyEnabledGroupHasADefault() {
        var d = StructureGroupRegistry.defaults();
        for (var type : d.types().entrySet()) {
            for (String group : type.getValue().groups()) {
                assertNotNull(StructureGroupRegistry.groupDefault(group),
                        type.getKey() + " enables " + group + " with no default");
            }
        }
    }
}
