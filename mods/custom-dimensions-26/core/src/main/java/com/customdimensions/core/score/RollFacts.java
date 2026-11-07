package com.customdimensions.core.score;

import com.customdimensions.core.facts.Measured;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the sampler measured for one (dimension, seed), and nothing judged.
 * Built by {@link FactsBuilder} from sampled columns and structure starts.
 *
 * @param sampledRadius   the radius round spawn the biome and terrain facts cover
 * @param structureRadius the radius round spawn structure starts were looked for out to
 */
public record RollFacts(String dimension, long seed, int playableRadius, int sampledRadius, int structureRadius,
                        Spawn spawn, Biomes biomes, Terrain terrain, Structures structures, List<ZoneShare> zones) {

    public RollFacts {
        zones = zones == null ? List.of() : List.copyOf(zones);
    }

    /**
     * The spawn column and the patch round it.
     *
     * @param safeColumnFraction of the patch's columns, how many a player can
     *                           start on: ground, no fluid, no step to a
     *                           neighbour taller than {@link FactsBuilder#SAFE_STEP}
     */
    public record Spawn(int x, int z, Measured<String> biome, Measured<Integer> height,
                        Measured<Double> safeColumnFraction) {}

    /**
     * The biome layout over the sampled disc.
     *
     * @param edgeDensityNearSpawn fraction of adjacent pairs in the spawn patch,
     *                             sampled at a fixed step, whose biomes differ
     */
    public record Biomes(Map<String, Double> shares, int distinct, double headlineShare,
                         Measured<Double> edgeDensityNearSpawn) {}

    /**
     * The shape of the ground over the sampled disc. Relief and heights are
     * over the columns that have ground.
     *
     * @param relief    interquartile range of the ground heights
     * @param lowHeight the 2nd percentile of the ground heights
     * @param highHeight the 98th percentile
     */
    public record Terrain(double groundFraction, double waterFraction, Measured<Double> relief,
                          Measured<Integer> lowHeight, Measured<Integer> highHeight) {}

    /** One structure start the generator's placement state would attempt, with its biome check passed. */
    public record Start(String structure, int x, int z, double distance) {}

    /**
     * Structures in this dimension.
     *
     * @param pool    every structure id the generator's placement state can place here
     * @param targets want or shun name to the structure ids it means on this game; an
     *                empty list means the name resolves to nothing here, a missing key
     *                that the name is not known at all
     * @param starts  the candidate starts of the targeted structures within {@code structureRadius}, nearest first,
     *                at most a fixed number of each structure (the roller keeps the nearest, and 64 end cities)
     */
    public record Structures(Set<String> pool, Map<String, List<String>> targets, List<Start> starts) {

        public Structures {
            pool = Set.copyOf(pool);
            targets = Map.copyOf(targets);
            starts = List.copyOf(starts);
        }

        /** The nearest start among {@code ids}, or null. */
        public Start nearest(List<String> ids) {
            Start best = null;
            for (Start s : starts) {
                if (ids.contains(s.structure()) && (best == null || s.distance() < best.distance())) best = s;
            }
            return best;
        }

        public int count(List<String> ids) {
            int n = 0;
            for (Start s : starts) if (ids.contains(s.structure())) n++;
            return n;
        }
    }

    /**
     * How much of one zone reads as its biomes.
     *
     * @param columns  sampled columns inside the zone at full strength
     * @param intended of those, how many carry one of the zone's biomes
     */
    public record ZoneShare(String id, List<String> biomes, int columns, int intended) {

        public double share() {
            return columns == 0 ? 0.0 : intended / (double) columns;
        }
    }
}
