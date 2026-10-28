package com.customdimensions.core.score;

import com.customdimensions.core.facts.Measured;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every criterion the roller scores, read from sampled {@link RollFacts} and a
 * definition's {@link RollSpec}. Each has a passing case, a failing case and an
 * absent-input case in {@code CriteriaTest}.
 */
public final class Criteria {

    /** The dimensions whose progression structure is a gate. */
    public static final String NETHER = "the_nether";
    public static final String END = "the_end";

    private Criteria() {}

    public static List<Criterion> all() {
        return List.of(
                new SpawnReadsAsNamesake(),
                new HeadlineBiomeDominatesAppropriately(),
                new BiomeVarietyPresent(),
                new BiomeEdgesNearSpawn(),
                new TerrainMatchesPreset(),
                new WaterMatchesIntent(),
                new HeightRangeMatchesIntent(),
                new PlayableGroundCoversTheDisc(),
                new FortressReachableInNether(),
                new EndCityReachableInEnd(),
                new EndSuppliesEnoughElytra(),
                new SpawnIsPlayable());
    }

    /** {@link #all()} plus one criterion per want, per shun and per zone that sets biomes. */
    public static List<Criterion> forTarget(RollTarget target, List<String> zoneIds) {
        List<Criterion> out = new ArrayList<>(all());
        for (Map.Entry<String, String> e : target.spec().wants().entrySet()) {
            out.add(new WantedStructure(e.getKey(), RollSpec.Band.of(e.getValue())));
        }
        for (String name : target.spec().shuns()) out.add(new ShunnedStructure(name));
        for (String id : zoneIds) out.add(new ZoneReadsAsIntended(id));
        return List.copyOf(out);
    }

    static final class SpawnReadsAsNamesake implements Criterion {
        static final double NATIVE_SPAWN_BONUS = 0.25;

        /** Balanced coverage of the named biomes (geometric mean), lifted when spawn stands in one. */
        static double namesakeMark(double[] shares, boolean spawnIsNamesake) {
            double logSum = 0.0;
            int present = 0;
            for (double s : shares) {
                if (s > 0.0) {
                    logSum += Math.log(s);
                    present++;
                }
            }
            if (present == 0) return spawnIsNamesake ? NATIVE_SPAWN_BONUS : 0.0;
            double balanced = Math.min(1.0, present * Math.exp(logSum / present));
            double base = balanced * present / shares.length;
            return spawnIsNamesake ? base + (1.0 - base) * NATIVE_SPAWN_BONUS : base;
        }

        public String id() {
            return "spawn_reads_as_namesake";
        }

        public Group group() {
            return Group.THEME;
        }

        public Tier tier() {
            return Tier.CONFIGURED;
        }

        public String target(RollTarget def) {
            List<String> want = def.spec().spawnFilter();
            return want.isEmpty() ? "no spawn filter configured" : "spawn is one of: " + String.join(", ", want);
        }

        public boolean applicable(RollTarget def) {
            return !def.spec().spawnFilter().isEmpty();
        }

        public Result evaluate(RollFacts facts, RollTarget def) {
            List<String> want = def.spec().spawnFilter();
            Measured<String> biome = facts.spawn().biome();
            if (!biome.isPresent()) return new Result.Unmeasured(biome.reason());
            boolean native_ = want.contains(biome.orThrow());
            double[] named = new double[want.size()];
            double combined = 0.0;
            int present = 0;
            for (int i = 0; i < want.size(); i++) {
                Double s = facts.biomes().shares().get(want.get(i));
                named[i] = s == null ? 0.0 : s;
                combined += named[i];
                if (named[i] > 0.0) present++;
            }
            String ev = String.format(Locale.ROOT, "spawn is %s%s; %d of %d named biome(s) present, covering %.1f%% of the disc",
                    biome.orThrow(), native_ ? ", a namesake" : ", not a namesake", present, want.size(), combined * 100.0);
            return new Result.Score(namesakeMark(named, native_), ev);
        }
    }

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

        public String target(RollTarget def) {
            return "the largest biome share sits between " + IDEAL_LOW + " and " + IDEAL_HIGH;
        }

        public boolean applicable(RollTarget def) {
            return def.declaredBiomes() > 1;
        }

        public Result evaluate(RollFacts facts, RollTarget def) {
            double v = facts.biomes().headlineShare();
            if (facts.biomes().shares().isEmpty()) return new Result.Unmeasured("no column was sampled inside the disc");
            String ev = String.format(Locale.ROOT, "headline share %.3f", v);
            if (v >= IDEAL_LOW && v <= IDEAL_HIGH) return new Result.Score(1.0, ev + " — inside the band");
            if (v < IDEAL_LOW) return new Result.Score(ramp(v, LOW, IDEAL_LOW), ev + " — no biome carries the theme");
            return new Result.Score(ramp(HIGH - v, 0.0, HIGH - IDEAL_HIGH), ev + " — one biome swamps the dimension");
        }
    }

    static final class BiomeVarietyPresent implements Criterion {
        public String id() {
            return "biome_variety_present";
        }

        public Group group() {
            return Group.INTEREST;
        }

        public String target(RollTarget def) {
            return "at least " + def.declaredBiomes() + " distinct biomes appear inside the sampled disc";
        }

        public boolean applicable(RollTarget def) {
            return def.declaredBiomes() > 1;
        }

        public Result evaluate(RollFacts facts, RollTarget def) {
            int n = facts.biomes().distinct();
            if (n == 0) return new Result.Unmeasured("no column was sampled inside the disc");
            return new Result.Score(Math.min(1.0, n / (double) def.declaredBiomes()),
                    n + " distinct biomes, target " + def.declaredBiomes());
        }
    }

    static final class BiomeEdgesNearSpawn implements Criterion {
        /** Edge density at the patch's 8-block step that reads as more than one biome within a walk. */
        static final double IDEAL = 0.10;

        public String id() {
            return "biome_edges_near_spawn";
        }

        public Group group() {
            return Group.INTEREST;
        }

        public String target(RollTarget def) {
            return "more than one biome within a walk of spawn (edge density " + IDEAL + " at an "
                    + FactsBuilder.PATCH_STEP + "-block step)";
        }

        public Result evaluate(RollFacts facts, RollTarget def) {
            Measured<Double> edges = facts.biomes().edgeDensityNearSpawn();
            if (!edges.isPresent()) return new Result.Unmeasured(edges.reason());
            double v = edges.orThrow();
            return new Result.Score(Math.min(1.0, v / IDEAL), String.format(Locale.ROOT, "edge density %.3f near spawn", v));
        }
    }

    static final class TerrainMatchesPreset implements Criterion {
        /** Interquartile range of ground heights, in blocks, each terrain word asks for. */
        static final Map<String, double[]> BANDS = Map.of(
                "flat", new double[] {0, 12},
                "gently_rolling", new double[] {8, 30},
                "rolling", new double[] {20, 55},
                "hilly", new double[] {40, 90},
                "mountainous", new double[] {75, 200},
                "extreme", new double[] {120, 400});

        /** Fraction of the disc with ground each terrain word asks for. */
        static final Map<String, double[]> GROUND_BANDS = Map.of(
                "void", new double[] {0.00, 0.10},
                "islands", new double[] {0.05, 0.70});

        public String id() {
            return "terrain_matches_preset";
        }

        public Group group() {
            return Group.APPROPRIATE;
        }

        public Tier tier() {
            return Tier.CONFIGURED;
        }

        public String target(RollTarget def) {
            String word = def.spec().terrain();
            if (word == null) return "no terrain word configured";
            double[] ground = GROUND_BANDS.get(word);
            if (ground != null) return word + ": ground under " + pct(ground[0]) + " to " + pct(ground[1]) + " of the disc";
            double[] band = BANDS.get(word);
            return band == null ? "unknown terrain word '" + word + "'"
                    : word + ": relief between " + (int) band[0] + " and " + (int) band[1];
        }

        public boolean applicable(RollTarget def) {
            String word = def.spec().terrain();
            return word != null && (BANDS.containsKey(word) || GROUND_BANDS.containsKey(word));
        }

        public Result evaluate(RollFacts facts, RollTarget def) {
            String word = def.spec().terrain();
            double[] ground = GROUND_BANDS.get(word);
            if (ground != null) {
                double v = facts.terrain().groundFraction();
                return scoreInBand(v, ground[0], ground[1], String.format(Locale.ROOT,
                        "ground under %.1f%% of the disc, %s wants %s-%s", v * 100.0, word, pct(ground[0]), pct(ground[1])));
            }
            double[] relief = BANDS.get(word);
            Measured<Double> measured = facts.terrain().relief();
            if (!measured.isPresent()) return new Result.Unmeasured(measured.reason());
            double v = measured.orThrow();
            return scoreInBand(v, relief[0], relief[1], String.format(Locale.ROOT,
                    "relief %.0f, %s wants %d-%d", v, word, (int) relief[0], (int) relief[1]));
        }
    }

    static final class WaterMatchesIntent implements Criterion {
        /** Fraction of the disc under surface water each water word asks for. */
        static final Map<String, double[]> BANDS = Map.of(
                "sea", new double[] {0.5, 1.0},
                "high", new double[] {0.25, 0.8},
                "none", new double[] {0.0, 0.20});

        public String id() {
            return "water_matches_intent";
        }

        public Group group() {
            return Group.APPROPRIATE;
        }

        public Tier tier() {
            return Tier.CONFIGURED;
        }

        public String target(RollTarget def) {
            String word = def.spec().water();
            if (word == null) return "no water preference configured";
            double[] band = BANDS.get(word);
            return band == null ? "unknown water preference '" + word + "'"
                    : word + ": water fraction between " + band[0] + " and " + band[1];
        }

        public boolean applicable(RollTarget def) {
            String word = def.spec().water();
            return word != null && BANDS.containsKey(word);
        }

        public Result evaluate(RollFacts facts, RollTarget def) {
            String word = def.spec().water();
            double[] band = BANDS.get(word);
            double v = facts.terrain().waterFraction();
            return scoreInBand(v, band[0], band[1], String.format(Locale.ROOT,
                    "water fraction %.3f, %s wants %.2f-%.2f", v, word, band[0], band[1]));
        }
    }

    static final class HeightRangeMatchesIntent implements Criterion {
        public String id() {
            return "height_range_matches_intent";
        }

        public Group group() {
            return Group.APPROPRIATE;
        }

        public Tier tier() {
            return Tier.CONFIGURED;
        }

        public String target(RollTarget def) {
            int[] range = def.spec().heightRange();
            return range == null ? "no height range configured"
                    : "the ground (2nd to 98th percentile) sits between " + range[0] + " and " + range[1];
        }

        public boolean applicable(RollTarget def) {
            return def.spec().heightRange() != null;
        }

        public Result evaluate(RollFacts facts, RollTarget def) {
            int[] range = def.spec().heightRange();
            Measured<Integer> lowM = facts.terrain().lowHeight();
            Measured<Integer> highM = facts.terrain().highHeight();
            if (!lowM.isPresent()) return new Result.Unmeasured(lowM.reason());
            int low = lowM.orThrow();
            int high = highM.orThrow();
            String ev = String.format(Locale.ROOT, "measured %d-%d, configured %d-%d", low, high, range[0], range[1]);
            double span = high - low;
            if (span <= 0.0) {
                boolean inside = low >= range[0] && high <= range[1];
                return new Result.Score(inside ? 1.0 : 0.0, ev + (inside ? " — inside the envelope" : " — outside the envelope"));
            }
            double overlap = Math.min(high, range[1]) - Math.max(low, range[0]);
            double v = Math.max(0.0, Math.min(1.0, overlap / span));
            return new Result.Score(v, ev + String.format(Locale.ROOT, " — %.0f%% of the ground is inside the envelope", v * 100.0));
        }
    }

    static final class PlayableGroundCoversTheDisc implements Criterion {
        public String id() {
            return "playable_ground_covers_the_disc";
        }

        public Group group() {
            return Group.APPROPRIATE;
        }

        public String target(RollTarget def) {
            return "every sampled column inside the disc carries ground";
        }

        public boolean applicable(RollTarget def) {
            String word = def.spec().terrain();
            return word == null || !TerrainMatchesPreset.GROUND_BANDS.containsKey(word);
        }

        public Result evaluate(RollFacts facts, RollTarget def) {
            double coverage = facts.terrain().groundFraction();
            return new Result.Score(coverage, String.format(Locale.ROOT, "ground under %.1f%% of the sampled disc", coverage * 100.0));
        }
    }

    /** Fraction of the playable radius within which the Nether's fortress must sit. */
    public static final double REACHABLE_FRACTION = 0.5;
    /** Fraction of the playable radius within which the End's city must sit. */
    public static final double END_REACHABLE_FRACTION = 1.0;

    static final class FortressReachableInNether implements Criterion {
        static final String STRUCTURE = "minecraft:fortress";

        public String id() {
            return "fortress_reachable_in_nether";
        }

        public Group group() {
            return Group.CHALLENGE;
        }

        public String target(RollTarget def) {
            return "a fortress within " + (int) (def.playableRadius() * REACHABLE_FRACTION) + " blocks of spawn";
        }

        public boolean applicable(RollTarget def) {
            return NETHER.equals(def.dimension());
        }

        public boolean gate() {
            return true;
        }

        public double[] band(RollTarget def) {
            return new double[] {0.0, def.playableRadius() * REACHABLE_FRACTION};
        }

        public Result evaluate(RollFacts facts, RollTarget def) {
            return reachabilityGate(facts, STRUCTURE, def.playableRadius() * REACHABLE_FRACTION);
        }
    }

    static final class EndCityReachableInEnd implements Criterion {
        static final String STRUCTURE = "minecraft:end_city";

        public String id() {
            return "end_city_reachable_in_end";
        }

        public Group group() {
            return Group.CHALLENGE;
        }

        public String target(RollTarget def) {
            return "an end city inside the " + (int) (def.playableRadius() * END_REACHABLE_FRACTION) + "-block border";
        }

        public boolean applicable(RollTarget def) {
            return END.equals(def.dimension());
        }

        public boolean gate() {
            return true;
        }

        public double[] band(RollTarget def) {
            return new double[] {0.0, def.playableRadius() * END_REACHABLE_FRACTION};
        }

        public Result evaluate(RollFacts facts, RollTarget def) {
            return reachabilityGate(facts, STRUCTURE, def.playableRadius() * END_REACHABLE_FRACTION);
        }
    }

    static final class EndSuppliesEnoughElytra implements Criterion {
        static final double CITIES_PER_SHIP = 3.0;
        static final double ELYTRA_TARGET = 20.0;
        static final String STRUCTURE = "minecraft:end_city";

        public String id() {
            return "end_supplies_enough_elytra";
        }

        public Group group() {
            return Group.FUN;
        }

        public String target(RollTarget def) {
            return "enough end cities for about " + (int) ELYTRA_TARGET + " elytra (~"
                    + (int) (ELYTRA_TARGET * CITIES_PER_SHIP) + " cities)";
        }

        public boolean applicable(RollTarget def) {
            return END.equals(def.dimension());
        }

        public Result evaluate(RollFacts facts, RollTarget def) {
            if (!facts.structures().pool().contains(STRUCTURE)) {
                return new Result.Unmeasured(STRUCTURE + " is not in this dimension's structure pool");
            }
            int placed = facts.structures().count(List.of(STRUCTURE));
            double elytra = placed / CITIES_PER_SHIP;
            String ev = String.format(Locale.ROOT, "%d end city starts within %d blocks, about %.0f elytra at one ship per %.0f cities",
                    placed, facts.structureRadius(), elytra, CITIES_PER_SHIP);
            if (placed <= 0) return new Result.Score(0.0, ev + " — nothing here to fly with");
            return new Result.Score(ramp(elytra, 0.0, ELYTRA_TARGET), ev);
        }
    }

    private static Criterion.Result reachabilityGate(RollFacts facts, String structureId, double withinBlocks) {
        if (!facts.structures().pool().contains(structureId)) {
            return new Criterion.Result.Unmeasured(structureId + " is not in this dimension's structure pool");
        }
        RollFacts.Start nearest = facts.structures().nearest(List.of(structureId));
        if (nearest == null) {
            if (facts.structureRadius() < withinBlocks) {
                return new Criterion.Result.Unmeasured("no " + structureId + " within the " + facts.structureRadius()
                        + " blocks searched, short of the " + (int) withinBlocks + "-block floor");
            }
            return new Criterion.Result.Fail("no " + structureId + " within " + facts.structureRadius() + " blocks",
                    "reachability floor: " + (int) withinBlocks + " blocks");
        }
        String ev = String.format(Locale.ROOT, "nearest %s is %.0f blocks from spawn", structureId, nearest.distance());
        return nearest.distance() <= withinBlocks
                ? new Criterion.Result.Pass(ev)
                : new Criterion.Result.Fail(ev + ", beyond the " + (int) withinBlocks + "-block reachability floor",
                        "reachability floor: " + (int) withinBlocks + " blocks");
    }

    static final class SpawnIsPlayable implements Criterion {
        /** The share of safe patch columns that reads as an ample choice of spawn. */
        static final double AMPLE = 0.25;

        public String id() {
            return "spawn_is_playable";
        }

        public Group group() {
            return Group.FUN;
        }

        public String target(RollTarget def) {
            return "the " + FactsBuilder.PATCH_RADIUS + "-block patch round spawn has dry, walkable ground";
        }

        public boolean applicable(RollTarget def) {
            return !def.spec().allowHazardousSpawn();
        }

        public Result evaluate(RollFacts facts, RollTarget def) {
            Measured<Double> safe = facts.spawn().safeColumnFraction();
            if (!safe.isPresent()) return new Result.Unmeasured(safe.reason());
            double v = safe.orThrow();
            String ev = String.format(Locale.ROOT, "%.0f%% of the spawn patch is dry, walkable ground", v * 100.0);
            if (v <= 0.0) return new Result.Score(0.0, ev + " — nowhere here to arrive");
            return new Result.Score(ramp(v, 0.0, AMPLE), ev + (v >= AMPLE ? " — ample choice of spawn"
                    : " — a spawn could be placed, but the choice is thin"));
        }
    }

    static final class WantedStructure implements Criterion {
        private final String name;
        private final RollSpec.Band band;

        WantedStructure(String name, RollSpec.Band band) {
            this.name = name;
            this.band = band;
        }

        public String id() {
            return "wants:" + name;
        }

        public Group group() {
            return Group.THEME;
        }

        public Tier tier() {
            return Tier.CONFIGURED;
        }

        public String target(RollTarget def) {
            return name + " sits between " + pct(band.low) + " and " + pct(band.high) + " of the border (" + band.word + ")";
        }

        public double[] band(RollTarget def) {
            return new double[] {band.low * def.playableRadius(), band.high * def.playableRadius()};
        }

        public Result evaluate(RollFacts facts, RollTarget def) {
            List<String> ids = facts.structures().targets().get(name);
            if (ids == null) return new Result.Unmeasured("'" + name + "' is not a known structure name or id");
            List<String> inPool = ids.stream().filter(facts.structures().pool()::contains).toList();
            if (inPool.isEmpty()) {
                return new Result.Unmeasured("'" + name + "' is not in this dimension's structure pool, so no seed can place one");
            }
            double radius = def.playableRadius();
            double[] blocks = band(def);
            RollFacts.Start best = facts.structures().nearest(inPool);
            if (best == null) {
                if (facts.structureRadius() < blocks[1]) {
                    return new Result.Unmeasured("no " + name + " within the " + facts.structureRadius()
                            + " blocks searched; the band reaches " + (int) blocks[1]);
                }
                return new Result.Score(0.0, "in the pool, but this seed starts no " + name + " within "
                        + facts.structureRadius() + " blocks", blocks);
            }
            double frac = best.distance() / radius;
            String ev = String.format(Locale.ROOT, "nearest %s %.0f blocks (%.1f%% of the border), searched to %d blocks",
                    best.structure(), best.distance(), frac * 100.0, facts.structureRadius());
            if (frac >= band.low && frac <= band.high) return new Result.Score(1.0, ev + " — inside the band", blocks);
            double miss = frac < band.low ? band.low - frac : frac - band.high;
            return new Result.Score(ramp(RollSpec.Band.TOLERANCE - miss, 0.0, RollSpec.Band.TOLERANCE),
                    ev + String.format(Locale.ROOT, " — %.0f%% of the border outside the band", miss * 100.0), blocks);
        }
    }

    static final class ShunnedStructure implements Criterion {
        private final String name;

        ShunnedStructure(String name) {
            this.name = name;
        }

        public String id() {
            return "shuns:" + name;
        }

        public Group group() {
            return Group.THEME;
        }

        public Tier tier() {
            return Tier.CONFIGURED;
        }

        public String target(RollTarget def) {
            return "no " + name + " here, and the further off the better if there is";
        }

        public Result evaluate(RollFacts facts, RollTarget def) {
            List<String> ids = facts.structures().targets().get(name);
            if (ids == null) return new Result.Unmeasured("'" + name + "' is not a known structure name or id");
            RollFacts.Start best = facts.structures().nearest(ids);
            if (best == null) return new Result.Score(1.0, "no " + name + " starts within " + facts.structureRadius() + " blocks");
            double frac = best.distance() / def.playableRadius();
            return new Result.Score(Math.min(1.0, frac), String.format(Locale.ROOT,
                    "nearest %s %.0f blocks away (%.1f%% of the border)", best.structure(), best.distance(), frac * 100.0));
        }
    }

    static final class ZoneReadsAsIntended implements Criterion {
        private final String zone;

        ZoneReadsAsIntended(String zone) {
            this.zone = zone;
        }

        public String id() {
            return "zone_reads_as_intended:" + zone;
        }

        public Group group() {
            return Group.THEME;
        }

        public Tier tier() {
            return Tier.CONFIGURED;
        }

        public String target(RollTarget def) {
            return "the columns inside zone " + zone + " carry its biomes";
        }

        public Result evaluate(RollFacts facts, RollTarget def) {
            RollFacts.ZoneShare share = facts.zones().stream().filter(z -> z.id().equals(zone)).findFirst().orElse(null);
            if (share == null) return new Result.Unmeasured("zone " + zone + " was not mapped");
            if (share.columns() == 0) return new Result.Unmeasured("no sampled column falls inside zone " + zone);
            return new Result.Score(share.share(), String.format(Locale.ROOT, "%d of %d columns inside %s carry %s",
                    share.intended(), share.columns(), zone, String.join(", ", share.biomes())));
        }
    }

    static Criterion.Result scoreInBand(double v, double low, double high, String evidence) {
        if (v >= low && v <= high) return new Criterion.Result.Score(1.0, evidence + " — inside the band");
        double width = high - low;
        double miss = v < low ? low - v : v - high;
        return new Criterion.Result.Score(ramp(width - miss, 0.0, width), evidence + " — outside the band");
    }

    private static String pct(double fraction) {
        return String.format(Locale.ROOT, "%.0f%%", fraction * 100.0);
    }

    static double ramp(double v, double lo, double hi) {
        if (hi <= lo) return v >= hi ? 1.0 : 0.0;
        double t = (v - lo) / (hi - lo);
        return t < 0.0 ? 0.0 : (t > 1.0 ? 1.0 : t);
    }
}
