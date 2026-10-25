package com.customdimensions.core.firewall;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FirewallTest {

    private static Bridge bridge(String mod, boolean reviewed, List<String> sets, List<String> mixins, List<String> registries) {
        return new Bridge(mod, reviewed, sets, List.of(), List.of(), List.of(), mixins, registries);
    }

    private static Firewall.Game game(Set<String> mods, List<Firewall.MixinConfig> mixins, Map<Finding.Kind, Set<String>> registries,
                                      Map<String, Set<String>> owners) {
        return new Firewall.Game(mods, mixins, registries, owners, Set.of());
    }

    @Test
    void worldgenClassesCoverGenerationAndSkipRuntime() {
        assertTrue(WorldgenClasses.isWorldgen("net.minecraft.world.level.levelgen.NoiseChunk"));
        assertTrue(WorldgenClasses.isWorldgen("net/minecraft/world/level/levelgen/DensityFunctions$Noise"));
        assertTrue(WorldgenClasses.isWorldgen("net.minecraft.world.level.biome.MultiNoiseBiomeSource"));
        assertTrue(WorldgenClasses.isWorldgen("net.minecraft.world.level.biome.Climate$Sampler"));
        assertTrue(WorldgenClasses.isWorldgen("net.minecraft.world.level.chunk.ChunkGenerator"));
        assertTrue(WorldgenClasses.isWorldgen("net.minecraft.world.level.chunk.ChunkGeneratorStructureState"));
        assertTrue(WorldgenClasses.isWorldgen("net.minecraft.world.level.chunk.status.ChunkStatusTasks"));
        assertTrue(WorldgenClasses.isWorldgen("net.minecraft.world.level.dimension.LevelStem"));
        assertTrue(WorldgenClasses.isWorldgen("net.minecraft.data.worldgen.SurfaceRuleData"));
        assertFalse(WorldgenClasses.isWorldgen("net.minecraft.world.level.levelgen.PatrolSpawner"));
        assertFalse(WorldgenClasses.isWorldgen("net.minecraft.world.level.biome.Biome"));
        assertFalse(WorldgenClasses.isWorldgen("net.minecraft.world.level.biome.Biome$ClimateSettings"));
        assertFalse(WorldgenClasses.isWorldgen("net.minecraft.world.level.levelgen.Heightmap$Types"));
        assertFalse(WorldgenClasses.isWorldgen("net.minecraft.world.level.chunk.LevelChunk"));
        assertFalse(WorldgenClasses.isWorldgen("net.minecraft.server.level.ServerChunkCache"));
        assertTrue(WorldgenClasses.isWorldgen("net.minecraft.world.level.biome.BiomeSource"), "a class sharing Biome's prefix is not the runtime Biome");
    }

    @Test
    void allowListMatchesIdsAndPrefixes() {
        assertTrue(AllowList.find("c2me-opts-worldgen-vanilla").isPresent());
        assertTrue(AllowList.find("lithium").isPresent());
        assertTrue(AllowList.find("servercore").isPresent());
        assertTrue(AllowList.find("fabric-biome-api-v1").isPresent());
        assertFalse(AllowList.find("lithiumx").isPresent());
        assertFalse(AllowList.find("terrablender").isPresent());
        assertTrue(AllowList.find("carpet").orElseThrow().owner(), "carpet awaits the owner");
        assertFalse(AllowList.find("c2me").orElseThrow().owner());
    }

    @Test
    void cleanGamePasses() {
        Firewall.Verdict v = Firewall.evaluate(game(Set.of("customdimensions", "lithium", "c2me-base"),
                List.of(new Firewall.MixinConfig("lithium", "lithium.mixins.json", Set.of("net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator")),
                        new Firewall.MixinConfig("collective", "collective.mixins.json", Set.of("net.minecraft.world.level.levelgen.PatrolSpawner"))),
                Map.of(Finding.Kind.DIMENSION, Set.of("minecraft:overworld", "adventure:the_aviary"),
                        Finding.Kind.DIMENSION_TYPE, Set.of("fantasy:default")), Map.of()), Map.of());
        assertFalse(v.refused(), v.refusals().toString());
        assertEquals(1, v.allowed().size());
        assertTrue(v.allowed().iterator().next().startsWith("lithium (performance"));
    }

    @Test
    void foreignDimensionIsRefusedAndNamesItsMod() {
        Firewall.Verdict v = Firewall.evaluate(game(Set.of("lostcities"), List.of(),
                Map.of(Finding.Kind.DIMENSION, Set.of("minecraft:overworld", "lostcities:lostcity"),
                        Finding.Kind.DIMENSION_TYPE, Set.of("lostcities:lostcity"),
                        Finding.Kind.NOISE_SETTINGS, Set.of("other:caves"),
                        Finding.Kind.BIOME_SOURCE, Set.of("minecraft:multi_noise", "lostcities:city")),
                Map.of("lostcities", Set.of("lostcities"))), Map.of());
        assertTrue(v.refused());
        assertEquals(List.of(
                "lostcities: dimension lostcities:lostcity",
                "lostcities: dimension type lostcities:lostcity",
                "lostcities: biome source type lostcities:city",
                "unknown (namespace other): noise settings other:caves"), v.refusals().stream().map(Finding::line).toList());
        String message = Firewall.message(v.refusals());
        assertTrue(message.startsWith("custom-dimensions refuses to start: 4 findings"), message);
        assertTrue(message.contains("\n  lostcities: dimension lostcities:lostcity"), message);
        assertTrue(message.contains(Firewall.BRIDGES_DIR), message);
    }

    @Test
    void mixinIntoWorldgenFromUnlistedModIsRefusedOncePerTarget() {
        Firewall.Verdict v = Firewall.evaluate(game(Set.of("shaper"), List.of(new Firewall.MixinConfig("shaper", "shaper.mixins.json",
                Set.of("net/minecraft/world/level/levelgen/NoiseChunk", "net/minecraft/world/level/levelgen/RandomState", "net/minecraft/server/MinecraftServer"))),
                Map.of(), Map.of()), Map.of());
        assertEquals(List.of(
                "shaper: mixin into worldgen class net.minecraft.world.level.levelgen.NoiseChunk (shaper.mixins.json)",
                "shaper: mixin into worldgen class net.minecraft.world.level.levelgen.RandomState (shaper.mixins.json)"),
                v.refusals().stream().map(Finding::line).toList());
    }

    @Test
    void frameworksAreRefusedByPresence() {
        Firewall.Verdict v = Firewall.evaluate(game(Set.of("terrablender", "biolith", "lithostitched", "spark"), List.of(), Map.of(), Map.of()), Map.of());
        assertEquals(3, v.refusals().size());
        assertTrue(v.refusals().stream().allMatch(f -> f.kind() == Finding.Kind.FRAMEWORK));
    }

    @Test
    void reviewedBridgeAdoptsOnlyWhatItLists() {
        Firewall.Game g = game(Set.of("shaper", "terrablender"), List.of(new Firewall.MixinConfig("shaper", "shaper.mixins.json",
                Set.of("net.minecraft.world.level.levelgen.NoiseChunk"))),
                Map.of(Finding.Kind.DIMENSION, Set.of("shaper:one", "shaper:two")), Map.of("shaper", Set.of("shaper")));
        Map<String, Bridge> bridges = Map.of("shaper", bridge("shaper", true, List.of(), List.of("shaper.mixins.json"), List.of("shaper:one")),
                "terrablender", bridge("terrablender", true, List.of(), List.of(), List.of()));
        assertEquals(List.of("shaper: dimension shaper:two"), Firewall.evaluate(g, bridges).refusals().stream().map(Finding::line).toList());
    }

    @Test
    void unreviewedBridgeAdoptsNothingAndIsListed() {
        Firewall.Game g = game(Set.of("shaper"), List.of(), Map.of(Finding.Kind.DIMENSION, Set.of("shaper:one")), Map.of("shaper", Set.of("shaper")));
        Firewall.Verdict v = Firewall.evaluate(g, Map.of("shaper", bridge("shaper", false, List.of(), List.of(), List.of("shaper:one"))));
        assertTrue(v.refused());
        assertEquals(List.of("shaper"), v.unreviewed());
    }

    @Test
    void ownNamespacesFromTheDefinitionsPass() {
        Firewall.Game g = new Firewall.Game(Set.of(), List.of(), Map.of(Finding.Kind.DIMENSION, Set.of("mypack:world")), Map.of(), Set.of("mypack"));
        assertFalse(Firewall.evaluate(g, Map.of()).refused());
    }

    @Test
    void structureSetsWithoutReviewedBridgeAreSwitchedOff() {
        Set<String> sets = Set.of("minecraft:villages", "adventure:x", "mvs:barn", "mvs:mill", "ddd:dungeon", "mypack:y");
        Map<String, Bridge> bridges = Map.of("mvs", bridge("mvs", true, List.of("mvs:barn"), List.of(), List.of()),
                "ddd", bridge("ddd", false, List.of("ddd:dungeon"), List.of(), List.of()));
        assertEquals(Set.of("mvs:mill", "ddd:dungeon"), Firewall.switchedOff(sets, bridges, Set.of("mypack")));
        assertEquals(Set.of(), Firewall.switchedOff(Set.of(), bridges, Set.of()));
    }

    @Test
    void singleFindingReadsSingular() {
        assertTrue(Firewall.message(List.of(new Finding("a", Finding.Kind.FRAMEWORK, "(installed)"))).contains(": 1 finding of"));
    }
}
