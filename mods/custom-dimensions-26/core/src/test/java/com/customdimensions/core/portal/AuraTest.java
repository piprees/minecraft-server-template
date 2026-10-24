package com.customdimensions.core.portal;

import com.customdimensions.core.BlockPos;
import com.customdimensions.core.config.PortalDefinition;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuraTest {

    /** A map of cells; anything absent is air. Columns at x >= 1000 are not resident. */
    static final class FakeWorld implements AuraWorld {
        final Map<BlockPos, String> blocks = new HashMap<>();
        final Set<BlockPos> blockEntities = new HashSet<>();
        final Set<String> protectedIds = Set.of("minecraft:cobblestone", "minecraft:oak_planks");
        final Set<Integer> claimedChunks = new HashSet<>();
        final Set<String> knownFeatures = new HashSet<>();
        int writes;

        @Override public boolean resident(BlockPos pos) { return pos.getX() < 1000; }
        @Override public boolean isAir(BlockPos pos) { return !blocks.containsKey(pos); }
        @Override public String blockId(BlockPos pos) { return blocks.getOrDefault(pos, "minecraft:air"); }
        @Override public boolean isSolid(BlockPos pos) {
            String id = blocks.get(pos);
            return id != null && !id.contains("water") && !id.contains("lava") && !id.contains("flower")
                    && !id.contains("log") && !id.equals("minecraft:fire");
        }
        @Override public boolean hasBlockEntity(BlockPos pos) { return blockEntities.contains(pos); }
        @Override public boolean isFixed(BlockPos pos) {
            String id = blocks.get(pos);
            return "minecraft:bedrock".equals(id) || "customdimensions:portal".equals(id);
        }
        @Override public boolean isProtected(BlockPos pos) { return protectedIds.contains(blockId(pos)); }
        @Override public boolean inTag(BlockPos pos, String tag) {
            return "minecraft:base_stone_overworld".equals(tag) && blockId(pos).equals("minecraft:stone");
        }
        @Override public boolean isLog(BlockPos pos) { return blockId(pos).endsWith("_log"); }
        @Override public boolean isFlora(BlockPos pos) { return blockId(pos).contains("flower"); }
        @Override public boolean hasFluid(BlockPos pos) { return blockId(pos).contains("water") || blockId(pos).contains("lava"); }
        @Override public String stillFluid(BlockPos pos) {
            String id = blockId(pos);
            return id.equals("minecraft:water") || id.equals("minecraft:lava") ? id : null;
        }
        @Override public boolean claimed(BlockPos pos) { return claimedChunks.contains(Math.floorDiv(pos.getX(), 16)); }
        @Override public boolean canPlace(BlockPos pos, String blockId) { return true; }
        @Override public boolean set(BlockPos pos, String blockId) {
            if (!resident(pos) || blockId.startsWith("unknown:")) {
                return false;
            }
            blocks.put(pos, blockId);
            writes++;
            return true;
        }
        @Override public boolean growFeature(BlockPos pos, String featureId) { return knownFeatures.contains(featureId); }
    }

    private static final BlockPos CENTRE = new BlockPos(0, 64, 0);

    /** A solid cube of {@code id} filling the whole pass volume around the centre. */
    private static FakeWorld filled(String id, int radius) {
        FakeWorld world = new FakeWorld();
        for (int x = -radius; x <= radius; x++) {
            for (int y = 60 - 1; y <= 70; y++) {
                for (int z = -radius; z <= radius; z++) {
                    world.blocks.put(new BlockPos(x, y, z), id);
                }
            }
        }
        return world;
    }

    private static PortalDefinition.AuraSettings settings(int radius, int perPass) {
        PortalDefinition.AuraSettings s = new PortalDefinition.AuraSettings();
        s.radius = radius;
        s.blocksPerPass = perPass;
        return s;
    }

    private static AuraState palette(String... terrain) {
        return new AuraState(List.of(terrain), List.of(), List.of(), List.of(), 0);
    }

    private static int count(FakeWorld world, String id) {
        return (int) world.blocks.values().stream().filter(id::equals).count();
    }

    @Test
    void topNIsCountDescendingThenId() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("minecraft:dirt", 5);
        counts.put("minecraft:stone", 9);
        counts.put("minecraft:grass_block", 5);
        counts.put("minecraft:sand", 1);
        assertEquals(List.of("minecraft:stone", "minecraft:dirt", "minecraft:grass_block"), Aura.topN(counts, 3));
        assertEquals(List.of("minecraft:stone"), Aura.topN(counts, 1));
        assertTrue(Aura.topN(Map.of(), 5).isEmpty());
    }

    @Test
    void depressionNeedsFloorAndThreeWalls() {
        BlockPos pos = new BlockPos(0, 64, 0);
        Set<BlockPos> solid = new HashSet<>(Set.of(pos.add(0, -1, 0), pos.add(0, 0, -1), pos.add(0, 0, 1), pos.add(1, 0, 0)));
        Predicate<BlockPos> isSolid = solid::contains;
        assertTrue(Aura.isDepression(isSolid, pos));
        solid.remove(pos.add(1, 0, 0));
        assertFalse(Aura.isDepression(isSolid, pos));
        solid.add(pos.add(1, 0, 0));
        solid.remove(pos.add(0, -1, 0));
        assertFalse(Aura.isDepression(isSolid, pos));
    }

    @Test
    void auraSettingsDefaultsAndClamps() {
        PortalDefinition.AuraSettings s = new PortalDefinition.AuraSettings();
        assertEquals(8, s.getRadius());
        assertEquals(40, s.getInterval());
        assertEquals(2, s.getBlocksPerPass());
        assertEquals(300, s.getBudget());
        assertTrue(s.affectsSource() && s.affectsTarget());
        s.radius = 9999;
        s.interval = 1;
        s.blocksPerPass = 999;
        s.budget = -5;
        s.fireChance = 3.0;
        s.sides = "sideways";
        assertEquals(32, s.getRadius());
        assertEquals(10, s.getInterval());
        assertEquals(16, s.getBlocksPerPass());
        assertEquals(-1, s.getBudget());
        assertEquals(1.0, s.getFireChance());
        assertEquals("both", s.getSides());
        s.sides = "source";
        assertTrue(s.affectsSource());
        assertFalse(s.affectsTarget());
    }

    @Test
    void minCornerIsDeterministic() {
        Set<BlockPos> interior = Set.of(new BlockPos(3, 64, 3), new BlockPos(2, 64, 3), new BlockPos(2, 63, 4));
        assertEquals(new BlockPos(2, 63, 4), Aura.minCorner(interior));
    }

    @Test
    void exclusionCoversInteriorRingAndPlatform() {
        Set<BlockPos> vertical = Set.of(new BlockPos(0, 64, 0), new BlockPos(0, 65, 0));
        Set<BlockPos> exclusion = Aura.exclusion(vertical);
        assertTrue(exclusion.containsAll(vertical));
        assertTrue(exclusion.contains(new BlockPos(0, 63, 0)));
        assertTrue(exclusion.contains(new BlockPos(0, 66, 0)));
        assertTrue(exclusion.contains(new BlockPos(1, 64, 0)));
        assertTrue(exclusion.contains(new BlockPos(-1, 65, 0)));
        assertFalse(exclusion.contains(new BlockPos(0, 64, 2)));
        assertFalse(exclusion.contains(new BlockPos(1, 66, 0)));
        Set<BlockPos> flat = Set.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0));
        assertTrue(Aura.exclusion(flat).contains(new BlockPos(1, 63, 0)));
        assertTrue(Aura.exclusion(flat).contains(new BlockPos(0, 64, -1)));
    }

    @Test
    void budgetsAreEndlessBelowZeroAndNeverNegative() {
        assertEquals(Integer.MAX_VALUE, Aura.remaining(-1, 50));
        assertEquals(0, Aura.remaining(300, 310));
        assertEquals(10, Aura.remaining(300, 290));
        assertTrue(Aura.exhausted(300, 300));
        assertFalse(Aura.exhausted(-1, 1_000_000));
        assertTrue(Aura.saveDue(2400));
        assertFalse(Aura.saveDue(2401));
    }

    @Test
    void sampleTakesTopTerrainPlantsTreesAndStillFluidsButNotTheFrame() {
        FakeWorld world = new FakeWorld();
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                world.blocks.put(new BlockPos(x, 62, z), "minecraft:stone");
                world.blocks.put(new BlockPos(x, 63, z), "minecraft:grass_block");
            }
        }
        world.blocks.put(new BlockPos(2, 64, 2), "minecraft:poppy_flower");
        world.blocks.put(new BlockPos(-2, 64, 2), "minecraft:birch_log");
        world.blocks.put(new BlockPos(3, 63, 3), "minecraft:water");
        world.blocks.put(new BlockPos(-3, 63, 3), "minecraft:flowing_water");
        world.blocks.put(new BlockPos(1, 64, 0), "minecraft:obsidian");
        world.blocks.put(new BlockPos(1, 65, 1), "minecraft:obsidian");
        world.blocks.put(new BlockPos(0, 61, 0), "minecraft:bedrock");
        AuraState s = Aura.sample(world, CENTRE, Aura.exclusion(Set.of(CENTRE)), Set.of("minecraft:obsidian"), null);
        assertEquals(List.of("minecraft:stone", "minecraft:grass_block"), s.terrain);
        assertEquals(List.of("minecraft:poppy_flower"), s.flora);
        assertEquals(List.of("minecraft:birch"), s.trees);
        assertEquals(List.of("minecraft:water"), s.fluids);
    }

    @Test
    void anUnsampledSideFallsBackToTheDefinitionsDefaultBlock() {
        FakeWorld world = new FakeWorld();
        BlockPos cold = new BlockPos(2000, 64, 0);
        assertEquals(List.of("minecraft:netherrack"), Aura.sample(world, cold, Set.of(), Set.of(), "minecraft:netherrack").terrain);
        assertTrue(Aura.sample(world, cold, Set.of(), Set.of(), null).terrain.isEmpty());
    }

    @Test
    void policiesDecideWhatAWallBecomes() {
        for (String policy : List.of(AuraPolicy.NONE, AuraPolicy.NATURAL, AuraPolicy.EVERYTHING)) {
            FakeWorld world = filled("minecraft:cobblestone", 8);
            world.blocks.put(new BlockPos(3, 64, 3), "minecraft:stone");
            for (int i = 0; i < 400; i++) {
                Aura.pass(world, CENTRE, Set.of(), palette("minecraft:netherrack"), settings(8, 16), policy, 1000, new Random(i));
            }
            int cobble = count(world, "minecraft:cobblestone");
            switch (policy) {
                case AuraPolicy.NONE -> assertEquals(0, world.writes);
                case AuraPolicy.NATURAL -> {
                    assertEquals("minecraft:netherrack", world.blockId(new BlockPos(3, 64, 3)));
                    assertEquals(1, count(world, "minecraft:netherrack"));
                }
                default -> assertTrue(cobble < 17 * 12 * 17 / 2, "everything converts the wall, left " + cobble);
            }
        }
    }

    @Test
    void conversionsNeedThePolicyAndTheirOutputsAreImmune() {
        PortalDefinition.AuraSettings s = settings(2, 16);
        s.conversions = new LinkedHashMap<>(Map.of("#minecraft:base_stone_overworld", "minecraft:blackstone"));
        FakeWorld world = filled("minecraft:stone", 2);
        for (int i = 0; i < 200; i++) {
            Aura.pass(world, CENTRE, Set.of(), palette("minecraft:blackstone", "minecraft:basalt"), s, AuraPolicy.NATURAL,
                    1000, new Random(i));
        }
        assertEquals(0, count(world, "minecraft:basalt"));
        assertTrue(count(world, "minecraft:blackstone") > 0);
        assertNull(Aura.resolveConversion("minecraft:dirt", t -> false, s.getConversions()));
        assertEquals("minecraft:x", Aura.resolveConversion("minecraft:dirt", t -> false, Map.of("minecraft:dirt", "minecraft:x")));
    }

    @Test
    void aPassStopsAtBlocksPerPassAndAtTheRemainingBudget() {
        FakeWorld world = filled("minecraft:stone", 8);
        Aura.Pass pass = Aura.pass(world, CENTRE, Set.of(), palette("minecraft:netherrack"), settings(8, 3),
                AuraPolicy.EVERYTHING, 1000, new Random(1));
        assertTrue(pass.spent() <= 3);
        assertEquals(pass.spent(), world.writes);
        FakeWorld capped = filled("minecraft:stone", 8);
        Aura.Pass one = Aura.pass(capped, CENTRE, Set.of(), palette("minecraft:netherrack"), settings(8, 16),
                AuraPolicy.EVERYTHING, 1, new Random(1));
        assertEquals(1, one.spent());
        assertEquals(1, capped.writes);
        assertEquals(Aura.Pass.NONE, Aura.pass(capped, CENTRE, Set.of(), palette("minecraft:netherrack"), settings(8, 16),
                AuraPolicy.EVERYTHING, 0, new Random(1)));
    }

    @Test
    void theOpeningAndItsFrameAreNeverTouched() {
        FakeWorld world = filled("minecraft:obsidian", 3);
        Set<BlockPos> interior = Set.of(CENTRE, CENTRE.up());
        interior.forEach(p -> world.blocks.put(p, "customdimensions:portal"));
        Set<BlockPos> exclusion = Aura.exclusion(interior);
        for (int i = 0; i < 2000; i++) {
            Aura.pass(world, CENTRE, exclusion, palette("minecraft:netherrack"), settings(3, 16), AuraPolicy.EVERYTHING,
                    Integer.MAX_VALUE, new Random(i));
        }
        for (BlockPos p : exclusion) {
            assertEquals(interior.contains(p) ? "customdimensions:portal" : "minecraft:obsidian", world.blockId(p));
        }
        assertTrue(count(world, "minecraft:netherrack") > 100);
    }

    @Test
    void claimsVetoEveryPolicyAndAreCounted() {
        FakeWorld world = filled("minecraft:stone", 8);
        for (int chunk = -1; chunk <= 0; chunk++) {
            world.claimedChunks.add(chunk);
        }
        int claimed = 0;
        for (int i = 0; i < 100; i++) {
            claimed += Aura.pass(world, CENTRE, Set.of(), palette("minecraft:netherrack"), settings(8, 16),
                    AuraPolicy.EVERYTHING, 1000, new Random(i)).claimed();
        }
        assertEquals(0, world.writes);
        assertTrue(claimed > 0);
    }

    @Test
    void blockEntitiesBedrockAndColdChunksAreNeverTouched() {
        FakeWorld world = filled("minecraft:bedrock", 3);
        BlockPos chest = new BlockPos(1, 64, 1);
        world.blocks.put(chest, "minecraft:chest");
        world.blockEntities.add(chest);
        for (int i = 0; i < 500; i++) {
            Aura.pass(world, CENTRE, Set.of(), palette("minecraft:netherrack"), settings(3, 16), AuraPolicy.EVERYTHING,
                    1000, new Random(i));
        }
        assertEquals(0, world.writes);
        FakeWorld cold = filled("minecraft:stone", 3);
        assertEquals(Aura.Pass.NONE, Aura.pass(cold, new BlockPos(1500, 64, 0), Set.of(), palette("minecraft:netherrack"),
                settings(3, 16), AuraPolicy.EVERYTHING, 1000, new Random(1)));
    }

    @Test
    void fluidsFormOnlyInHollowsCostTwoAndNeedTwoUnitsLeft() {
        AuraState water = new AuraState(List.of(), List.of(), List.of(), List.of("minecraft:water"), 0);
        FakeWorld flat = new FakeWorld();
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                flat.blocks.put(new BlockPos(x, 63, z), "minecraft:stone");
            }
        }
        for (int i = 0; i < 3000; i++) {
            Aura.pass(flat, CENTRE, Set.of(), water, settings(8, 16), AuraPolicy.NATURAL, 1000, new Random(i));
        }
        assertEquals(0, count(flat, "minecraft:water"), "no hollows on open ground");

        FakeWorld pits = new FakeWorld();
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                pits.blocks.put(new BlockPos(x, 63, z), "minecraft:stone");
                if ((x + z) % 2 != 0) {
                    pits.blocks.put(new BlockPos(x, 64, z), "minecraft:stone");
                }
            }
        }
        int spent = 0;
        for (int i = 0; i < 3000; i++) {
            spent += Aura.pass(pits, CENTRE, Set.of(), water, settings(8, 16), AuraPolicy.NATURAL, 1000, new Random(i)).spent();
        }
        int puddles = count(pits, "minecraft:water");
        assertTrue(puddles > 0);
        assertEquals(2 * puddles, spent);

        FakeWorld last = new FakeWorld();
        last.blocks.putAll(pits.blocks);
        last.blocks.values().removeIf("minecraft:water"::equals);
        for (int i = 0; i < 3000; i++) {
            Aura.pass(last, CENTRE, Set.of(), water, settings(8, 16), AuraPolicy.NATURAL, 1, new Random(i));
        }
        assertEquals(0, count(last, "minecraft:water"), "one unit left buys no fluid");

        FakeWorld none = new FakeWorld();
        none.blocks.putAll(last.blocks);
        for (int i = 0; i < 3000; i++) {
            Aura.pass(none, CENTRE, Set.of(), water, settings(8, 16), AuraPolicy.NONE, 1000, new Random(i));
        }
        assertEquals(0, none.writes, "none withholds fluids");
    }

    @Test
    void anUnknownTreeOrBlockIsASilentNoOp() {
        PortalDefinition.AuraSettings s = settings(8, 16);
        s.trees = List.of("minecraft:not_a_tree");
        FakeWorld world = new FakeWorld();
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                world.blocks.put(new BlockPos(x, 63, z), "minecraft:stone");
            }
        }
        int spent = 0;
        for (int i = 0; i < 1000; i++) {
            spent += Aura.pass(world, CENTRE, Set.of(), palette("unknown:block"), s, AuraPolicy.EVERYTHING, 1000,
                    new Random(i)).spent();
        }
        assertEquals(0, spent);
        assertEquals(0, world.writes);
    }

    @Test
    void auraStateAndSitesRoundTripThroughTheLinksFile() {
        String legacy = """
                [{"recordType":"source-zone-v1","sourceWorld":"minecraft:overworld","targetWorld":"adventure:the_ashgrove",
                  "axis":"X","definition":{"name":"p"},"interior":[{"x":1,"y":64,"z":2}],
                  "auraPalette":["minecraft:podzol"],"auraFlora":[],"auraTrees":[],"auraFluids":["minecraft:water"],
                  "auraBudgetSpent":17},
                 {"recordType":"aura-site-v1","world":"adventure:the_ashgrove","interior":[{"x":8,"y":70,"z":8}],
                  "palette":["minecraft:grass_block"],"flora":["minecraft:poppy"],"trees":[],"fluids":[],
                  "settings":{"budget":50,"subsume":"everything"},"budgetSpent":42}]
                """;
        PortalLinksFile.Contents read = PortalLinksFile.parse(JsonParser.parseString(legacy));
        assertTrue(read.dropped().isEmpty(), read.dropped().toString());
        assertTrue(read.other().isEmpty());
        AuraState zoneAura = read.zones().get(0).aura();
        assertNotNull(zoneAura);
        assertEquals(List.of("minecraft:podzol"), zoneAura.terrain);
        assertEquals(17, zoneAura.spent);
        PortalLinksFile.AuraSite site = read.auraSites().get(0);
        assertEquals("adventure:the_ashgrove", site.world());
        assertEquals(42, site.state().spent);
        assertEquals(50, site.settings().getBudget());
        assertEquals(AuraPolicy.EVERYTHING, site.settings().getSubsume());

        JsonArray written = PortalLinksFile.toJson(read);
        PortalLinksFile.Contents again = PortalLinksFile.parse(written);
        assertEquals(17, again.zones().get(0).aura().spent);
        assertEquals(42, again.auraSites().get(0).state().spent);
        assertEquals(Set.of(new BlockPos(8, 70, 8)), again.auraSites().get(0).interior());
        assertNull(PortalLinksFile.parse(JsonParser.parseString(
                "[{\"recordType\":\"source-zone-v1\",\"sourceWorld\":\"minecraft:overworld\",\"targetWorld\":\"minecraft:the_nether\","
                        + "\"axis\":\"X\",\"definition\":{\"name\":\"p\"},\"interior\":[{\"x\":1,\"y\":64,\"z\":2}]}]"))
                .zones().get(0).aura());
        assertEquals(1, PortalLinksFile.parse(JsonParser.parseString(
                "[{\"recordType\":\"aura-site-v1\",\"world\":\"adventure:x\"}]")).dropped().size());
    }
}
