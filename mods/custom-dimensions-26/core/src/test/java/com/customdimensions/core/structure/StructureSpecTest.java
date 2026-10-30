package com.customdimensions.core.structure;

import com.customdimensions.core.ChunkPos;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureSpecTest {

    private static StructureSpec.Context ctx(String type, double mob) {
        return new StructureSpec.Context("t", "adventure:t", type, mob, 4096, 0, 0);
    }

    private static StructureSpec.Result resolve(String json) {
        return StructureSpec.resolve(ctx("overworld", 1.0), JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    void noBlockKeepsTheTypeDefaultsOnTheNoisePath() {
        StructureSpec s = StructureSpec.resolve(ctx("overworld", 1.0), null).spec();
        assertTrue(s.noise());
        assertEquals("normal", s.density());
        assertEquals(7, s.groups().size());
        assertEquals("sparse", s.groups().get("dungeons").profile());
        assertEquals(256, s.radiusChunks());
    }

    @Test
    void densitySetsEveryGroupsProfileAndAGroupEntryOutranksIt() {
        StructureSpec s = resolve("{\"density\": \"dense\", \"noise\": {\"dungeons\": \"sparse\", \"maritime\": \"none\"}}").spec();
        assertEquals("dense", s.groups().get("settlements").profile());
        assertEquals("sparse", s.groups().get("dungeons").profile());
        assertFalse(s.groups().containsKey("maritime"));
    }

    @Test
    void denseTightensAndSparseWidensTheExclusion() {
        int normal = resolve("{}").spec().groups().get("settlements").exclusion();
        int dense = resolve("{\"density\": \"dense\"}").spec().groups().get("settlements").exclusion();
        int sparse = resolve("{\"density\": \"sparse\"}").spec().groups().get("settlements").exclusion();
        assertTrue(dense <= normal && normal < sparse, dense + " " + normal + " " + sparse);
    }

    @Test
    void noneDensityModeNoneAndNoiseFalseLeaveTheGrids() {
        assertEquals("density none", resolve("{\"density\": \"none\"}").spec().suppressedReason());
        assertEquals("mode none", resolve("{\"mode\": \"none\"}").spec().suppressedReason());
        assertEquals("noise false", resolve("{\"noise\": false}").spec().suppressedReason());
        assertFalse(StructureSpec.resolve(ctx("void", 1.0), null).spec().noise());
    }

    @Test
    void peacefulDropsTheHostileGroupsAndHostileShiftsTheirCurve() {
        StructureSpec peaceful = StructureSpec.resolve(ctx("overworld", 0.4), null).spec();
        assertFalse(peaceful.groups().containsKey("dungeons"));
        assertFalse(peaceful.groups().containsKey("endgame"));
        StructureSpec hostile = StructureSpec.resolve(ctx("overworld", 2.5), null).spec();
        assertEquals(1.0, hostile.groups().get("dungeons").radial()[0]);
    }

    @Test
    void clearSpawnRadiusAppliesToTheHostileGroupsOnly() {
        StructureSpec s = resolve("{\"clearSpawnRadius\": 100}").spec();
        assertEquals(7, s.groups().get("dungeons").clearSpawnChunks());
        assertEquals(0, s.groups().get("settlements").clearSpawnChunks());
    }

    @Test
    void forceDefaultsToExclusiveAndKeepsItsRadius() {
        StructureSpec.Result r = resolve("{\"force\": [{\"structure\": \"minecraft:igloo\", \"x\": -20, \"z\": 40, \"radius\": 64},"
                + " {\"structure\": \"minecraft:mansion\", \"x\": 0, \"z\": 0, \"exclusive\": false, \"y\": 70}]}");
        StructureSpec.Force igloo = r.spec().force().get(0);
        assertTrue(igloo.exclusive());
        assertEquals(64, igloo.radius());
        assertEquals(-2, igloo.chunkX());
        assertEquals(2, igloo.chunkZ());
        assertFalse(r.spec().force().get(1).exclusive());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("`y` is not applied")));
    }

    @Test
    void wantsAndShunsResolveAliasesAndWarnOnUnknownNames() {
        StructureSpec.Result r = resolve("{\"wants\": {\"bastion\": {\"min\": 0, \"max\": 512}, \"zzz_nothing\": {}},"
                + " \"shuns\": {\"village\": {}, \"monument\": {}}}");
        assertEquals("minecraft:bastion_remnant", r.spec().wants().get(0).structure());
        assertEquals(1, r.spec().wants().size());
        assertEquals(java.util.List.of("#minecraft:village", "minecraft:monument"), r.spec().shuns());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("zzz_nothing")));
    }

    @Test
    void kernelsMapToVanillaAndMoatIsReported() {
        StructureSpec.Result r = resolve("{\"terrainAdaptation\": {\"landmarks\": \"pedestal\", \"minecraft:mansion\": \"bury\","
                + " \"dungeons\": \"moat\", \"nogroup\": \"bury\"}}");
        assertEquals("beard_box", r.spec().terrainAdaptation().get("landmarks"));
        assertEquals("bury", r.spec().terrainAdaptation().get("minecraft:mansion"));
        assertFalse(r.spec().terrainAdaptation().containsKey("dungeons"));
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("moat")));
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("nogroup")));
    }

    @Test
    void malformedValuesStopTheCompileWithTheSlug() {
        for (String bad : new String[] {
                "{\"density\": \"huge\"}", "{\"mode\": \"maybe\"}", "{\"radial\": {\"dungeons\": [1, 2]}}",
                "{\"radial\": {\"dungeons\": [1,1,1,1,1,1,1,1,1,9]}}", "{\"force\": [{\"structure\": \"minecraft:igloo\"}]}",
                "{\"force\": [{\"structure\": \"igloo\", \"x\": 0, \"z\": 0}]}", "{\"spacing\": {\"minecraft:villages\": {\"spacing\": 8, \"separation\": 8}}}",
                "{\"wants\": {\"bastion\": {\"min\": 50, \"max\": 10}}}", "{\"rarity\": {\"minecraft:villages\": \"often\"}}",
                "{\"noise\": \"loud\"}", "{\"terrainAdaptation\": {\"landmarks\": \"wobble\"}}", "{\"extra\": 1}",
                "{\"clearSpawnRadius\": -3}", "{\"force\": [{\"structure\": \"minecraft:igloo\", \"x\": 0.5, \"z\": 0}]}"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> resolve(bad), bad);
            assertTrue(e.getMessage().startsWith("t: structures."), e.getMessage());
        }
    }

    @Test
    void jsonRoundTripKeepsEveryField() {
        StructureSpec s = resolve("{\"density\": \"sparse\", \"mode\": \"reject\", \"list\": [\"minecraft:villages\"],"
                + " \"exclude\": [\"minecraft:igloos\"], \"rarity\": {\"minecraft:trial_chambers\": \"common\"},"
                + " \"spacing\": {\"minecraft:shipwrecks\": {\"spacing\": 10, \"separation\": 4}},"
                + " \"force\": [{\"structure\": \"minecraft:igloo\", \"x\": 5, \"z\": 6}], \"wants\": {\"shipwreck\": {\"max\": 300}},"
                + " \"shuns\": {\"monument\": {}}, \"replace\": {\"minecraft:igloo\": \"minecraft:desert_pyramid\"},"
                + " \"terrainAdaptation\": {\"minecraft:mansion\": \"bury\"}, \"radial\": {\"loot\": [0,0,1,1,1,1,1,1,1,1]}}").spec();
        JsonObject json = s.toJson();
        StructureSpec back = StructureSpec.fromJson(json);
        assertEquals(json, back.toJson());
        assertEquals("reject", back.mode());
        assertEquals(new StructureSpec.Spacing(10, 4), back.spacing().get("minecraft:shipwrecks"));
        assertEquals(0.0, back.groups().get("loot").radial()[0]);
    }

    @Test
    void worldTypesFollowTheGenerator() {
        assertEquals("overworld", StructureSpec.worldType("noise", null));
        assertEquals("cave", StructureSpec.worldType("noise", "caves"));
        assertEquals("sky_islands", StructureSpec.worldType("noise", "floating_islands"));
        assertEquals("superflat", StructureSpec.worldType("flat", null));
        assertEquals("checkerboard", StructureSpec.worldType("checkerboard", null));
        assertNull(StructureSpec.worldType(null, null));
    }

    @Test
    void sitesPreferAMatchingChunkInsideTheRing() {
        StructureSites.Site site = StructureSites.choose(42L, 0, 0, 200, 400, (x, z) -> x > 0 && z > 0);
        assertTrue(site.matched());
        double d = StructureSites.distance(site.chunk(), 0, 0);
        assertTrue(d >= 200 && d <= 400, "distance " + d);
        assertTrue(site.chunk().x() > 0 && site.chunk().z() > 0);
        assertEquals(site.chunk().toLong(), StructureSites.choose(42L, 0, 0, 200, 400, (x, z) -> x > 0 && z > 0).chunk().toLong());
    }

    @Test
    void sitesFallBackToTheFirstCandidateAndRadiusZeroIsTheOwnChunk() {
        StructureSites.Site none = StructureSites.choose(7L, 1000, -1000, 0, 64, (x, z) -> false);
        assertFalse(none.matched());
        assertTrue(StructureSites.distance(none.chunk(), 1000, -1000) <= 64);
        StructureSites.Site own = StructureSites.choose(7L, -17, 33, 0, 0, (x, z) -> true);
        assertEquals(new ChunkPos(-2, 2).toLong(), own.chunk().toLong());
        assertNotNull(StructureSites.choose(1L, 0, 0, 0, 1, (x, z) -> true).chunk());
    }
}
