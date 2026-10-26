package com.customdimensions.core.score;

import com.customdimensions.core.facts.Measured;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Facts and targets for criterion tests: every field set to an unremarkable value unless a test overrides it. */
final class RollFixtures {
    private RollFixtures() {}

    static RollTarget target(String dimension, String rollJson, int radius, int declaredBiomes) {
        JsonObject roll = rollJson == null ? null : JsonParser.parseString(rollJson).getAsJsonObject();
        return new RollTarget(dimension, RollSpec.parse(roll, null), radius, declaredBiomes);
    }

    static RollTarget target(String rollJson) {
        return target("test", rollJson, 1000, 4);
    }

    static RollFacts.Spawn spawn(String biome, Double safe) {
        return new RollFacts.Spawn(0, 0, biome == null ? Measured.absent("no biome") : Measured.of(biome), Measured.of(70),
                safe == null ? Measured.absent("no patch") : Measured.of(safe));
    }

    static RollFacts.Biomes biomes(Map<String, Double> shares, Double edges) {
        double headline = shares.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        return new RollFacts.Biomes(shares, shares.size(), headline,
                edges == null ? Measured.absent("no patch") : Measured.of(edges));
    }

    static RollFacts.Terrain terrain(double ground, double water, Double relief, Integer low, Integer high) {
        return new RollFacts.Terrain(ground, water, relief == null ? Measured.absent("no ground") : Measured.of(relief),
                low == null ? Measured.absent("no ground") : Measured.of(low),
                high == null ? Measured.absent("no ground") : Measured.of(high));
    }

    static RollFacts.Structures structures(Set<String> pool, Map<String, List<String>> targets, List<RollFacts.Start> starts) {
        return new RollFacts.Structures(pool, targets, starts);
    }

    static RollFacts.Start start(String id, double distance) {
        return new RollFacts.Start(id, (int) distance, 0, distance);
    }

    static RollFacts facts(RollFacts.Spawn spawn, RollFacts.Biomes biomes, RollFacts.Terrain terrain,
                           RollFacts.Structures structures, List<RollFacts.ZoneShare> zones) {
        return new RollFacts("test", 1L, 1000, 1000, 1000, spawn, biomes, terrain, structures, zones);
    }

    static RollFacts plain() {
        return facts(spawn("minecraft:plains", 0.5), biomes(Map.of("minecraft:plains", 0.4, "minecraft:forest", 0.35,
                        "minecraft:river", 0.25), 0.12), terrain(1.0, 0.1, 30.0, 50, 120),
                structures(Set.of(), Map.of(), List.of()), List.of());
    }

    static RollFacts withStructures(RollFacts.Structures structures) {
        RollFacts p = plain();
        return new RollFacts(p.dimension(), p.seed(), p.playableRadius(), p.sampledRadius(), p.structureRadius(), p.spawn(),
                p.biomes(), p.terrain(), structures, p.zones());
    }
}
