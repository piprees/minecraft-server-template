package com.customdimensions.dimension;

import com.customdimensions.config.DimensionConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DimensionStructuresTest {

    /** Derived shrine spacing must stay bit-identical to the roller's mirror. */
    @Test
    void derivedShrineSpacingMatchesRollerMirror() {
        assertSpacing(256, 12, 6);     // small pocket clamps up to 12
        assertSpacing(384, 12, 6);     // 384/32 = 12 exactly
        assertSpacing(512, 16, 8);
        assertSpacing(1024, 32, 16);
        assertSpacing(1536, 48, 24);   // 48 exactly
        assertSpacing(8192, 48, 24);   // default border clamps down to 48
    }

    private static void assertSpacing(int radius, int spacing, int separation) {
        DimensionConfig.SpacingOverride out = DimensionStructures.derivedShrineSpacing(radius);
        assertEquals(spacing, out.spacing, "spacing for radius " + radius);
        assertEquals(separation, out.separation, "separation for radius " + radius);
    }

    @Test
    void normalizedModeValidation() {
        assertNull(DimensionStructures.normalizedMode("d", null));
        assertNull(DimensionStructures.normalizedMode("d", block(null)));
        assertNull(DimensionStructures.normalizedMode("d", block("")));
        assertNull(DimensionStructures.normalizedMode("d", block("bogus"))); // warn + off
        assertEquals("allow", DimensionStructures.normalizedMode("d", block("ALLOW")));
        assertEquals("reject", DimensionStructures.normalizedMode("d", block("reject")));
        assertEquals("none", DimensionStructures.normalizedMode("d", block("none")));
    }

    private static DimensionConfig.Structures block(String mode) {
        DimensionConfig.Structures s = new DimensionConfig.Structures();
        s.mode = mode;
        return s;
    }

    /**
     * keepSet is the one filter both the legacy mode path and the noise
     * path's pass-through loop share — pass-throughs escape NoisePoolBuilder,
     * so this is the only place structures.mode/exclude can reach them.
     */
    @Test
    void keepSetModeFilter() {
        var list = java.util.Set.of("moogs_structures:oasis_temple");
        var none = java.util.Set.<String>of();
        // no mode, no exclude: everything survives
        assertTrue(DimensionStructures.keepSet("a:b", null, none, none));
        assertTrue(DimensionStructures.keepSet(null, null, none, none));
        // allow keeps listed only; a keyless set can never match
        assertTrue(DimensionStructures.keepSet("moogs_structures:oasis_temple", "allow", list, none));
        assertFalse(DimensionStructures.keepSet("a:b", "allow", list, none));
        assertFalse(DimensionStructures.keepSet(null, "allow", list, none));
        // reject drops listed only; keyless sets survive
        assertFalse(DimensionStructures.keepSet("moogs_structures:oasis_temple", "reject", list, none));
        assertTrue(DimensionStructures.keepSet("a:b", "reject", list, none));
        assertTrue(DimensionStructures.keepSet(null, "reject", list, none));
        // "none" drops everything organic
        assertFalse(DimensionStructures.keepSet("a:b", "none", none, none));
    }

    @Test
    void keepSetExcludeIsCaseInsensitiveAndBeatsMode() {
        var exclude = java.util.Set.of("moogs_structures:oasis_temple"); // pre-lowercased
        assertFalse(DimensionStructures.keepSet("moogs_structures:oasis_temple", null,
                java.util.Set.of(), exclude));
        assertFalse(DimensionStructures.keepSet("Moogs_Structures:Oasis_Temple", null,
                java.util.Set.of(), exclude));
        // excluded even when mode allow lists it
        assertFalse(DimensionStructures.keepSet("moogs_structures:oasis_temple", "allow",
                java.util.Set.of("moogs_structures:oasis_temple"), exclude));
        assertTrue(DimensionStructures.keepSet("a:b", "reject",
                java.util.Set.of("c:d"), exclude));
    }

    /**
     * FixedStructurePlacement.Index contract (Index, not the placement
     * itself: StructurePlacement's static init needs Bootstrap, which unit
     * tests deliberately avoid).
     */
    @Test
    void fixedPlacementRegionAndMembership() {
        var index = new FixedStructurePlacement.Index(java.util.List.of(
                new net.minecraft.util.math.ChunkPos(100, -150),
                new net.minecraft.util.math.ChunkPos(-7, 3)));

        // any probe chunk in the containing 32-region resolves to the forced pos
        assertEquals(new net.minecraft.util.math.ChunkPos(100, -150),
                index.startFor(96, -160));
        assertEquals(new net.minecraft.util.math.ChunkPos(100, -150),
                index.startFor(127, -129));
        assertEquals(new net.minecraft.util.math.ChunkPos(-7, 3),
                index.startFor(-1, 0));
        // empty region: returns its origin, which is not a member
        var elsewhere = index.startFor(5000, 5000);
        assertFalse(index.isForced(elsewhere.x, elsewhere.z));
        // exact membership only
        assertTrue(index.isForced(100, -150));
        assertTrue(index.isForced(-7, 3));
        assertFalse(index.isForced(101, -150));
        assertFalse(index.isForced(0, 0));
    }

    @Test
    void fixedPlacementSharedRegionKeepsFirstForLocate() {
        var a = new net.minecraft.util.math.ChunkPos(10, 10);
        var b = new net.minecraft.util.math.ChunkPos(20, 20); // same 32-region
        var index = new FixedStructurePlacement.Index(java.util.List.of(a, b));
        assertEquals(a, index.startFor(10, 10));
        // both still generate
        assertTrue(index.isForced(10, 10));
        assertTrue(index.isForced(20, 20));
    }

    /**
     * `exclusive` defaults to true — forcing a structure removes it from the
     * noise pool everywhere else; `"exclusive": false` keeps organic copies.
     * Malformed entries never poison the set.
     */
    @Test
    void forcedExclusiveDefaultsTrueAndHonoursOptOut() {
        DimensionConfig cfg = new com.google.gson.Gson().fromJson("""
                {"structures": {"force": [
                    {"structure": "minecraft:fortress", "x": 0, "z": 0},
                    {"structure": "minecraft:igloo", "x": 0, "z": 0, "exclusive": false},
                    {"structure": "minecraft:swamp_hut", "x": 0, "z": 0, "exclusive": true},
                    {"x": 0, "z": 0}
                ]}}""", DimensionConfig.class);

        var exclusive = NoisePoolBuilder.forcedExclusiveStructureIds(cfg);
        assertTrue(exclusive.contains("minecraft:fortress"), "default is exclusive");
        assertTrue(exclusive.contains("minecraft:swamp_hut"));
        assertFalse(exclusive.contains("minecraft:igloo"), "exclusive:false keeps organic copies");
        assertEquals(2, exclusive.size());

        // no structures block at all: nothing exclusive
        assertTrue(NoisePoolBuilder.forcedExclusiveStructureIds(
                new com.google.gson.Gson().fromJson("{}", DimensionConfig.class)).isEmpty());
    }
}
