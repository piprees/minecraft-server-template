package com.customdimensions.score;

import com.customdimensions.config.DimensionConfig;
import com.customdimensions.facts.Measured;
import com.customdimensions.facts.SeedFacts;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The criteria that consume facts the engine actually measures today.
 *
 * <p>Deliberately fewer than the plan's twenty. A criterion that reads a fact
 * nobody computes would be a number invented at scoring time, which is the
 * exact failure this whole rewrite exists to end. The rest arrive with the
 * facts they need — traversability, line-of-sight, spawn buildability and
 * progression reachability all want block-level column probes the sampler does
 * not do yet.
 *
 * <p>Every criterion here is pure: facts and config in, a result out. No world,
 * no registry, no randomness — so every one is unit-testable against a
 * hand-built facts record, and the tests are the argument about what the
 * criterion should mean.
 */
public final class Criteria {

    private Criteria() {
    }

    /** Every criterion, in report order. */
    public static List<Criterion> all() {
        return List.of(
                new SpawnReadsAsNamesake(),
                new HeadlineBiomeDominatesAppropriately(),
                new BiomeVarietyPresent(),
                new BiomeEdgesNearSpawn(),
                new StructuresFormPlacesNotNoise(),
                new FirstEncounterDistance(),
                new TerrainMatchesPreset(),
                new NothingIsImmediatelyLethal());
    }

    // ----------------------------------------------------------------- theme

    /**
     * A GATE, not a score. The old model scored this and it was 1.0 for the
     * best candidate of all 81 dimensions — a sixth of the scale that ranked
     * nothing, because the spawn filter had already rejected everything else.
     * As a gate it costs no weight and still does its job (P6).
     */
    static final class SpawnReadsAsNamesake implements Criterion {
        public String id() {
            return "spawn_reads_as_namesake";
        }

        public Group group() {
            return Group.THEME;
        }

        public String target(DimensionConfig def) {
            List<String> want = namesake(def);
            return want.isEmpty() ? "no spawn filter configured"
                    : "spawn is one of: " + String.join(", ", want);
        }

        public boolean applicable(DimensionConfig def) {
            return !namesake(def).isEmpty();
        }

        public boolean gate() {
            return true;
        }

        public Result evaluate(SeedFacts facts, DimensionConfig def) {
            List<String> want = namesake(def);
            Measured<String> biome = facts.spawn().biome();
            if (!biome.isPresent()) {
                return new Result.Unmeasured(biome.reason());
            }
            return want.contains(biome.orThrow())
                    ? new Result.Pass("spawn biome is " + biome.orThrow())
                    : new Result.Fail("spawn is " + biome.orThrow()
                            + ", not one of the dimension's namesake biomes",
                            "wanted one of " + want);
        }
    }

    /**
     * The dimension should look like itself without being one biome.
     *
     * <p>Both failure modes are real and opposite: a headline share near 1.0 is
     * a single-biome world the config said was multi_biome, and a share near
     * 0.1 is a mosaic with no identity. The target band is stated, not a curve
     * fitted to whatever the pack happens to do.
     */
    static final class HeadlineBiomeDominatesAppropriately implements Criterion {
        static final double LOW = 0.20;
        static final double IDEAL_LOW = 0.30;
        static final double IDEAL_HIGH = 0.55;
        static final double HIGH = 0.85;

        public String id() {
            return "headline_biome_dominates_appropriately";
        }

        public Group group() {
            return Group.THEME;
        }

        public String target(DimensionConfig def) {
            return "the largest biome share sits between " + IDEAL_LOW + " and " + IDEAL_HIGH;
        }

        public Result evaluate(SeedFacts facts, DimensionConfig def) {
            Measured<Double> share = facts.biomes().headlineShare();
            if (!share.isPresent()) {
                return new Result.Unmeasured(share.reason());
            }
            double v = share.orThrow();
            String ev = String.format(Locale.ROOT, "headline share %.3f", v);
            if (v >= IDEAL_LOW && v <= IDEAL_HIGH) {
                return new Result.Score(1.0, ev + " — inside the band");
            }
            if (v < IDEAL_LOW) {
                return new Result.Score(ramp(v, LOW, IDEAL_LOW),
                        ev + " — no biome carries the theme");
            }
            return new Result.Score(ramp(HIGH - v, 0.0, HIGH - IDEAL_HIGH),
                    ev + " — one biome swamps the dimension");
        }
    }

    // -------------------------------------------------------------- interest

    /** More than one biome, and not so many that none registers. */
    static final class BiomeVarietyPresent implements Criterion {
        public String id() {
            return "biome_variety_present";
        }

        public Group group() {
            return Group.INTEREST;
        }

        public String target(DimensionConfig def) {
            return "at least " + want(def) + " distinct biomes appear inside the playable border";
        }

        /**
         * A dimension that lists two biomes cannot produce four, and must not
         * be marked down for obeying its own config. The target scales to what
         * the config permits, and a one-biome dimension is not asked at all.
         */
        static int want(DimensionConfig def) {
            int listed = def.getBiomes() == null ? 0 : def.getBiomes().size();
            return listed > 0 ? Math.min(4, listed) : 4;
        }

        public boolean applicable(DimensionConfig def) {
            return want(def) > 1;
        }

        public Result evaluate(SeedFacts facts, DimensionConfig def) {
            Measured<Integer> n = facts.biomes().distinctCount();
            if (!n.isPresent()) {
                return new Result.Unmeasured(n.reason());
            }
            int want = want(def);
            return new Result.Score(Math.min(1.0, n.orThrow() / (double) want),
                    n.orThrow() + " distinct biomes, target " + want);
        }
    }

    /**
     * A mosaic reads as somewhere to explore; two hemispheres do not — and a
     * biome COUNT cannot tell them apart, which is why edge density is a fact.
     */
    static final class BiomeEdgesNearSpawn implements Criterion {
        static final double IDEAL = 0.35;

        public String id() {
            return "biome_edges_near_spawn";
        }

        public Group group() {
            return Group.INTEREST;
        }

        public String target(DimensionConfig def) {
            return "biome edges are common enough to read as a mosaic (density ~" + IDEAL + ")";
        }

        public Result evaluate(SeedFacts facts, DimensionConfig def) {
            Measured<Double> edges = facts.biomes().edgeDensity();
            if (!edges.isPresent()) {
                return new Result.Unmeasured(edges.reason());
            }
            double v = edges.orThrow();
            return new Result.Score(Math.min(1.0, v / IDEAL),
                    String.format(Locale.ROOT, "edge density %.3f", v));
        }
    }

    /**
     * Three structures in a pocket is a PLACE; three evenly spread is scenery.
     * Clark-Evans below 1 is clustered — that is the direction worth having.
     */
    static final class StructuresFormPlacesNotNoise implements Criterion {
        public String id() {
            return "structures_form_places_not_noise";
        }

        public Group group() {
            return Group.INTEREST;
        }

        public String target(DimensionConfig def) {
            return "placements cluster into pockets rather than spreading evenly";
        }

        public boolean applicable(DimensionConfig def) {
            return structuresEnabled(def);
        }

        public Result evaluate(SeedFacts facts, DimensionConfig def) {
            Measured<Double> c = facts.structures().clustering();
            if (!c.isPresent()) {
                return new Result.Unmeasured(c.reason());
            }
            double v = c.orThrow();
            // 1.0 is a uniform scatter; 0.5 is strongly pocketed. Anything
            // above uniform is scenery and scores zero rather than negative —
            // a criterion returns 0..1 and says so in its evidence.
            double score = v >= 1.0 ? 0.0 : Math.min(1.0, (1.0 - v) / 0.5);
            return new Result.Score(score,
                    String.format(Locale.ROOT, "Clark-Evans %.3f (%s)", v,
                            v < 1.0 ? "pocketed" : "evenly spread"));
        }
    }

    // ------------------------------------------------------------- challenge

    /**
     * The first hostile thing should be far enough to be a decision and near
     * enough to be an adventure. The band scales with the playable radius,
     * because 300 blocks is next door in an 8192 world and the far side of a
     * 512 one.
     */
    static final class FirstEncounterDistance implements Criterion {
        public String id() {
            return "first_encounter_distance";
        }

        public Group group() {
            return Group.CHALLENGE;
        }

        public String target(DimensionConfig def) {
            return "the nearest hostile placement sits between 5% and 30% of the border";
        }

        public boolean applicable(DimensionConfig def) {
            return structuresEnabled(def);
        }

        public Result evaluate(SeedFacts facts, DimensionConfig def) {
            Measured<Double> nearest = facts.structures().nearestHostile();
            if (!nearest.isPresent()) {
                return new Result.Unmeasured(nearest.reason());
            }
            double radius = facts.playableRadius();
            if (radius <= 0) {
                return new Result.Unmeasured("the playable radius is not positive");
            }
            double frac = nearest.orThrow() / radius;
            String ev = String.format(Locale.ROOT,
                    "nearest hostile %.0f blocks (%.1f%% of the border)",
                    nearest.orThrow(), frac * 100.0);
            if (frac >= 0.05 && frac <= 0.30) {
                return new Result.Score(1.0, ev + " — inside the band");
            }
            if (frac < 0.05) {
                return new Result.Score(ramp(frac, 0.0, 0.05), ev + " — on top of spawn");
            }
            return new Result.Score(ramp(1.0 - frac, 0.70, 1.0), ev + " — a long walk");
        }
    }

    // ----------------------------------------------------------- appropriate

    /**
     * Terrain should match what the dimension asked for. The target comes from
     * {@code seedRoll.terrain}'s plain-English word, which is the one part of
     * the old model that was honestly fitted and is worth keeping.
     */
    static final class TerrainMatchesPreset implements Criterion {
        /** word -> (relief low, relief high). Blocks of vertical spread. */
        static final Map<String, double[]> BANDS = Map.of(
                "flat", new double[] {0, 12},
                "gently_rolling", new double[] {8, 30},
                "rolling", new double[] {20, 55},
                "hilly", new double[] {40, 90},
                "mountainous", new double[] {75, 200},
                "extreme", new double[] {120, 400});

        public String id() {
            return "terrain_matches_preset";
        }

        public Group group() {
            return Group.APPROPRIATE;
        }

        public String target(DimensionConfig def) {
            String word = terrainWord(def);
            if (word == null) {
                return "no terrain word configured";
            }
            double[] band = BANDS.get(word);
            return band == null ? "unknown terrain word '" + word + "'"
                    : word + ": relief between " + (int) band[0] + " and " + (int) band[1];
        }

        public boolean applicable(DimensionConfig def) {
            String word = terrainWord(def);
            return word != null && BANDS.containsKey(word);
        }

        public Result evaluate(SeedFacts facts, DimensionConfig def) {
            String word = terrainWord(def);
            double[] band = BANDS.get(word);
            Measured<Double> relief = facts.terrain().relief();
            if (!relief.isPresent()) {
                return new Result.Unmeasured(relief.reason());
            }
            double v = relief.orThrow();
            String ev = String.format(Locale.ROOT, "relief %.0f, %s wants %d-%d",
                    v, word, (int) band[0], (int) band[1]);
            if (v >= band[0] && v <= band[1]) {
                return new Result.Score(1.0, ev + " — inside the band");
            }
            double width = band[1] - band[0];
            double miss = v < band[0] ? band[0] - v : v - band[1];
            return new Result.Score(ramp(width - miss, 0.0, width), ev + " — outside the band");
        }
    }

    // ------------------------------------------------------------------- fun

    /** A GATE. Landing inside a wall of lava is not a low score, it is a no. */
    static final class NothingIsImmediatelyLethal implements Criterion {
        public String id() {
            return "nothing_is_immediately_lethal";
        }

        public Group group() {
            return Group.FUN;
        }

        public String target(DimensionConfig def) {
            return "spawn is not a sheer drop (local relief under 32 blocks)";
        }

        public boolean gate() {
            return true;
        }

        public Result evaluate(SeedFacts facts, DimensionConfig def) {
            Measured<Double> relief = facts.spawn().localRelief();
            if (!relief.isPresent()) {
                return new Result.Unmeasured(relief.reason());
            }
            double v = relief.orThrow();
            return v <= 32.0
                    ? new Result.Pass(String.format(Locale.ROOT, "local relief %.0f", v))
                    : new Result.Fail(String.format(Locale.ROOT,
                            "spawn sits on a %.0f-block cliff", v),
                            "local relief over 32 blocks");
        }
    }

    // ----------------------------------------------------------------- utils

    /** 0 at {@code lo}, 1 at {@code hi}, clamped. Linear and boring on purpose. */
    static double ramp(double v, double lo, double hi) {
        if (hi <= lo) {
            return v >= hi ? 1.0 : 0.0;
        }
        double t = (v - lo) / (hi - lo);
        return t < 0.0 ? 0.0 : (t > 1.0 ? 1.0 : t);
    }

    static List<String> namesake(DimensionConfig def) {
        DimensionConfig.SeedRoll sr = def.getSeedRoll();
        return sr == null || sr.spawnFilter == null ? List.of() : sr.spawnFilter;
    }

    /**
     * Whether this dimension generates organic structures at all. A void world
     * that has switched them off is not asked structure questions rather than
     * scoring zero on them.
     */
    static boolean structuresEnabled(DimensionConfig def) {
        DimensionConfig.Structures s = def.getStructures();
        if (s != null && s.mode != null && "none".equalsIgnoreCase(s.mode.trim())) {
            return false;
        }
        String density = def.getStructureDensity();
        return density == null || !"none".equalsIgnoreCase(density.trim());
    }

    static String terrainWord(DimensionConfig def) {
        DimensionConfig.SeedRoll sr = def.getSeedRoll();
        return sr == null || sr.terrain == null || sr.terrain.isBlank()
                ? null : sr.terrain.trim().toLowerCase(Locale.ROOT);
    }
}
