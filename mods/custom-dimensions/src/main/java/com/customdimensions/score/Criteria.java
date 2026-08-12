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
 * <p>A criterion that reads a fact nothing computes is a number invented at
 * scoring time, so a criterion arrives only with its facts. Traversability,
 * line-of-sight and spawn buildability all want block-level column probes the
 * sampler does not do, and are absent here for that reason. Progression
 * reachability has a narrow slice that needs no column probe — whether a
 * fortress or end city sits within reach in blocks — and that slice is below;
 * the block-level version (can a player actually WALK there) is still absent
 * for the same reason as the other three.
 *
 * <p>Every criterion is pure: facts and config in, a result out. No world, no
 * registry, no randomness — so each is testable against a hand-built facts
 * record, and its tests state what it is supposed to mean.
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
                new WaterMatchesIntent(),
                new HeightRangeMatchesIntent(),
                new PlayableGroundCoversTheDisc(),
                new FortressReachableInNether(),
                new EndCityReachableInEnd(),
                new NothingIsImmediatelyLethal(),
                new SpawnIsSafeToBuildOn());
    }

    // ----------------------------------------------------------------- theme

    /**
     * A GATE, not a score. The spawn filter rejects every candidate that fails
     * it during measurement, so a graded version is 1.0 for everything that
     * reaches scoring: weight that ranks nothing. As a gate it costs none.
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
         * The biomes the config asked for — all of them, not a fixed floor.
         *
         * <p>A fixed floor is trivially cleared by anything carrying a biome
         * list, which makes the criterion a constant. The question that ranks is
         * not "are there several biomes" but "did the dimension deliver the
         * palette its author chose", and that target differs per config.
         */
        static int want(DimensionConfig def) {
            return def.getBiomes() == null ? 0 : def.getBiomes().size();
        }

        /**
         * A dimension whose biomes come from its noise settings rather than a
         * list has stated no palette, so there is no intent to measure against.
         * Inventing a target for it would be marking it down unasked.
         */
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
     * Structures in a pocket are a PLACE; structures evenly spread are scenery.
     * Each group is judged against what its own profile asked for.
     *
     * <p>Judged PER GROUP, and that is the whole of the criterion. A group is an
     * independent point process — its own noise field, frequency and exclusion
     * radius — and Clark-Evans over every group at once is the statistic of a
     * superposition, which tends toward a random scatter whatever the parts look
     * like. Only the per-group figure can say whether a group forms pockets.
     *
     * <p>Two branches, because a group's profile is a statement of intent and the
     * mission is to prove a dimension is what its author said it is:
     *
     * <ul>
     * <li>A {@code cluster} group asked for pockets, so it is scored on whether
     *     it produced them: {@link #POCKET} or below is the answer in full, 1.0
     *     (a random scatter) is none of it.</li>
     * <li>Every other profile asked for an even spread at its own rate, and
     *     Poisson-disc placement cannot fall below 1.0 by construction — so it is
     *     scored on staying as loose as its spacing allows, from 1.0 down to
     *     {@link #LATTICE}, the maximum dispersion there is.</li>
     * </ul>
     *
     * <p>Groups are weighted by how many placements they hold, so a two-placement
     * group cannot swing the answer with a Clark-Evans that is mostly noise, and
     * the group carrying most of what a player finds carries most of the mark.
     */
    static final class StructuresFormPlacesNotNoise implements Criterion {
        /** Clark-Evans for a perfect triangular lattice — maximum dispersion. */
        static final double LATTICE = 2.1491;

        /**
         * What a {@code cluster} group has to reach to be scored as pockets.
         *
         * <p>Mean nearest-neighbour spacing at half the random expectation, i.e.
         * four times the local density: the conventional reading of "strongly
         * clustered", not a value fitted to any pack.
         */
        static final double POCKET = 0.5;

        public String id() {
            return "structures_form_places_not_noise";
        }

        public Group group() {
            return Group.INTEREST;
        }

        public String target(DimensionConfig def) {
            List<String> clustered = clusterGroups(def);
            String spaced = "groups on an even profile stay as loose as their spacing "
                    + "allows (Clark-Evans near 1.0, not toward " + LATTICE + ")";
            return clustered.isEmpty() ? spaced
                    : "the cluster group(s) " + String.join(", ", clustered)
                            + " reach Clark-Evans " + POCKET + " or below; " + spaced;
        }

        public boolean applicable(DimensionConfig def) {
            return structuresEnabled(def);
        }

        public Result evaluate(SeedFacts facts, DimensionConfig def) {
            Measured<Map<String, Double>> byGroup = facts.structures().clusteringByGroup();
            if (!byGroup.isPresent()) {
                return new Result.Unmeasured(byGroup.reason());
            }
            Measured<Map<String, Integer>> counts = facts.structures().byGroup();
            if (!counts.isPresent()) {
                return new Result.Unmeasured(counts.reason());
            }
            List<String> clustered = clusterGroups(def);

            double weighted = 0.0;
            double weight = 0.0;
            String bestGroup = null;
            String worstGroup = null;
            double best = Double.MAX_VALUE;
            double worst = -Double.MAX_VALUE;
            for (Map.Entry<String, Double> e : byGroup.orThrow().entrySet()) {
                Integer n = counts.orThrow().get(e.getKey());
                if (n == null || n <= 0) {
                    continue;
                }
                double ce = e.getValue();
                double s = clustered.contains(e.getKey())
                        ? ramp(1.0 - ce, 0.0, 1.0 - POCKET)
                        : ramp(LATTICE - ce, 0.0, LATTICE - 1.0);
                weighted += s * n;
                weight += n;
                if (ce < best) {
                    best = ce;
                    bestGroup = e.getKey();
                }
                if (ce > worst) {
                    worst = ce;
                    worstGroup = e.getKey();
                }
            }
            if (weight <= 0.0) {
                return new Result.Unmeasured(
                        "no group has both a placement count and a measured spacing");
            }
            return new Result.Score(weighted / weight, String.format(Locale.ROOT,
                    "most pocketed %s at %.3f, most spread %s at %.3f, over %d placements",
                    bestGroup, best, worstGroup, worst, (long) weight));
        }

        /**
         * The groups this config puts on the {@code cluster} profile, resolved
         * through the same precedence chain placement uses. Config only — no
         * facts, no seed — so it can also be stated in the target.
         */
        static List<String> clusterGroups(DimensionConfig def) {
            List<String> out = new java.util.ArrayList<>();
            try {
                var plan = com.customdimensions.dimension.NoiseGroupPlan.resolve(def);
                for (var e : plan.groups().entrySet()) {
                    if (e.getValue().profile() != null && e.getValue().profile().isCluster()) {
                        out.add(e.getKey());
                    }
                }
            } catch (RuntimeException ignored) {
                // A config the plan cannot resolve asks for no pockets as far as
                // this criterion is concerned; lint is where that gets reported.
            }
            return out;
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
            // The wanted range in blocks, so the map can draw the ring this
            // criterion is actually asking about.
            double[] band = {0.05 * radius, 0.30 * radius};
            if (frac >= 0.05 && frac <= 0.30) {
                return new Result.Score(1.0, ev + " — inside the band", band);
            }
            if (frac < 0.05) {
                return new Result.Score(ramp(frac, 0.0, 0.05), ev + " — on top of spawn", band);
            }
            // Mirrors the near branch: 1.0 at the band edge, decaying to 0 at
            // the border. The anchors run low-to-high in the ramp's own terms —
            // reversed, the whole upper tail clamps to zero and "just outside the
            // band" ranks the same as "at the world's edge".
            return new Result.Score(ramp(1.0 - frac, 0.0, 1.0 - 0.30),
                    ev + " — a long walk", band);
        }
    }

    // ----------------------------------------------------------- appropriate

    /**
     * Terrain should match what the dimension asked for. The target comes from
     * {@code seedRoll.terrain}'s plain-English word — already well-calibrated
     * by hand, so it is reused rather than rebuilt.
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

    /**
     * The dimension's water share should match what {@code seedRoll.water}
     * asked for. {@code terrain().waterFraction()} is measured on every grid
     * pass and read by no other criterion — this is that fact's only
     * consumer. Bands are recovered from the deleted Python roller
     * ({@code git show 3dfb057^:scripts/seed/dimension_profiles.py}), not
     * invented: {@code seedRoll.water} overrode the water target to exactly
     * these three windows regardless of {@code noiseSettings}, and they read
     * as real statements about how wet "sea", "high" and "none" mean rather
     * than numbers fitted to one pack.
     */
    static final class WaterMatchesIntent implements Criterion {
        /** word -> (fraction low, fraction high) of the grid's sampled columns that are wet. */
        static final Map<String, double[]> BANDS = Map.of(
                "sea", new double[] {0.5, 1.0},
                "high", new double[] {0.25, 0.8},
                "none", new double[] {0.0, 0.10});

        public String id() {
            return "water_matches_intent";
        }

        public Group group() {
            return Group.APPROPRIATE;
        }

        public String target(DimensionConfig def) {
            String word = waterWord(def);
            if (word == null) {
                return "no water preference configured";
            }
            double[] band = BANDS.get(word);
            return band == null ? "unknown water preference '" + word + "'"
                    : word + ": water fraction between " + band[0] + " and " + band[1];
        }

        public boolean applicable(DimensionConfig def) {
            String word = waterWord(def);
            return word != null && BANDS.containsKey(word);
        }

        public Result evaluate(SeedFacts facts, DimensionConfig def) {
            String word = waterWord(def);
            double[] band = BANDS.get(word);
            Measured<Double> water = facts.terrain().waterFraction();
            if (!water.isPresent()) {
                return new Result.Unmeasured(water.reason());
            }
            double v = water.orThrow();
            String ev = String.format(Locale.ROOT, "water fraction %.3f, %s wants %.2f-%.2f",
                    v, word, band[0], band[1]);
            if (v >= band[0] && v <= band[1]) {
                return new Result.Score(1.0, ev + " — inside the band");
            }
            double width = band[1] - band[0];
            double miss = v < band[0] ? band[0] - v : v - band[1];
            return new Result.Score(ramp(width - miss, 0.0, width), ev + " — outside the band");
        }
    }

    /**
     * The measured terrain should actually use the vertical envelope
     * {@code seedRoll.heightRange} declared, not sit in a narrow sliver of
     * it. Unlike {@link WaterMatchesIntent}, this is not a recovered band:
     * {@code heightRange} was parsed into the deleted Python roller's
     * profile dict and never once read by its scoring code (checked across
     * every file under the deleted {@code scripts/seed/} at {@code git show
     * 3dfb057^}) — there is no prior art to reuse, so the comparison below
     * (overlap between measured and configured range, as a share of the
     * configured span) is authored fresh from what the field is named for.
     */
    static final class HeightRangeMatchesIntent implements Criterion {
        public String id() {
            return "height_range_matches_intent";
        }

        public Group group() {
            return Group.APPROPRIATE;
        }

        public String target(DimensionConfig def) {
            int[] range = heightRange(def);
            return range == null ? "no height range configured"
                    : "measured min/max height overlaps " + range[0] + " to " + range[1];
        }

        public boolean applicable(DimensionConfig def) {
            return heightRange(def) != null;
        }

        public Result evaluate(SeedFacts facts, DimensionConfig def) {
            int[] range = heightRange(def);
            Measured<Integer> minM = facts.terrain().minHeight();
            Measured<Integer> maxM = facts.terrain().maxHeight();
            if (!minM.isPresent()) {
                return new Result.Unmeasured(minM.reason());
            }
            if (!maxM.isPresent()) {
                return new Result.Unmeasured(maxM.reason());
            }
            int measMin = minM.orThrow();
            int measMax = maxM.orThrow();
            int confLow = range[0];
            int confHigh = range[1];
            double span = confHigh - confLow;
            String ev = String.format(Locale.ROOT, "measured %d-%d, configured %d-%d",
                    measMin, measMax, confLow, confHigh);
            if (span <= 0.0) {
                return new Result.Unmeasured("configured height range " + confLow + "-" + confHigh
                        + " has no positive span to overlap against");
            }
            double overlap = Math.min(measMax, confHigh) - Math.max(measMin, confLow);
            double v = Math.max(0.0, Math.min(1.0, overlap / span));
            return new Result.Score(v, ev + String.format(Locale.ROOT, " — %.0f%% overlap", v * 100.0));
        }
    }

    /**
     * The measured grid's floor coverage over the playable disc. {@code
     * SeedFacts.Grid.heightMeasured()} / {@code sampled()} is exactly the
     * ratio {@code PROBES.md} asked for: of the columns the grid pass
     * actually attempted inside the disc (excluding the corner cells the
     * square grid samples outside a circular border — {@code sampled}
     * already excludes those), how many resolved a floor. Scored as that
     * fraction directly, with no band around it: a coverage ratio already
     * has units a person reads without translation, and a band would only
     * be a threshold invented on top of a number that already means
     * something on its own.
     *
     * <p>Not applicable when {@code seedRoll.terrain} states {@code "void"}
     * or {@code "islands"}: both are the dimension's own author declaring a
     * deliberately non-solid terrain, and a void world or a field of
     * floating islands is SUPPOSED to have most of its disc answer with no
     * floor. Scoring that as a defect would reject the very shape the
     * config asked for — the same reasoning {@link WaterMatchesIntent} and
     * {@link TerrainMatchesPreset} apply to their own words.
     */
    static final class PlayableGroundCoversTheDisc implements Criterion {
        public String id() {
            return "playable_ground_covers_the_disc";
        }

        public Group group() {
            return Group.APPROPRIATE;
        }

        public String target(DimensionConfig def) {
            return "every sampled column inside the playable disc resolves a floor";
        }

        public boolean applicable(DimensionConfig def) {
            String word = terrainWord(def);
            return !("void".equals(word) || "islands".equals(word));
        }

        public Result evaluate(SeedFacts facts, DimensionConfig def) {
            Measured<SeedFacts.Grid> gridM = facts.grid();
            if (!gridM.isPresent()) {
                return new Result.Unmeasured(gridM.reason());
            }
            SeedFacts.Grid grid = gridM.orThrow();
            if (grid.sampled() <= 0) {
                return new Result.Unmeasured(
                        "the grid pass attempted zero columns inside the playable disc");
            }
            double coverage = grid.heightMeasured() / (double) grid.sampled();
            return new Result.Score(coverage, String.format(Locale.ROOT,
                    "%d of %d sampled columns resolved a floor (%.1f%%)",
                    grid.heightMeasured(), grid.sampled(), coverage * 100.0));
        }
    }

    // ------------------------------------------------- progression floor

    /**
     * A GATE. Blaze rods have no source but a fortress, so one that exists in
     * the pool but sits beyond reach is not a scoring deduction — the seed is
     * broken regardless of everything else it measures. The floor is the
     * reachability number the deleted Python roller carried for the Nether
     * (recovered at {@code git show 2e0bb83}); it stood up to re-reading as a
     * statement about progression, not a number that happened to pass on one
     * world, so it moves here unchanged.
     *
     * <p>Scoped to the literal {@code minecraft:the_nether}, not every
     * nether-type dimension: a custom nether-flavoured pocket is optional
     * adventure content a player is never forced into, and several ship their
     * progression structures via {@code structures.force} — a fixed position,
     * not a per-seed risk this gate exists to catch.
     */
    static final class FortressReachableInNether implements Criterion {
        static final String STRUCTURE = "minecraft:fortress";
        static final double WITHIN_BLOCKS = 512.0;

        public String id() {
            return "fortress_reachable_in_nether";
        }

        public Group group() {
            return Group.APPROPRIATE;
        }

        public String target(DimensionConfig def) {
            return "a fortress sits within " + (int) WITHIN_BLOCKS + " blocks of spawn";
        }

        public boolean applicable(DimensionConfig def) {
            return "minecraft:the_nether".equals(def.getDimensionId());
        }

        public boolean gate() {
            return true;
        }

        public double[] band(DimensionConfig def) {
            return new double[] {0.0, WITHIN_BLOCKS};
        }

        public Result evaluate(SeedFacts facts, DimensionConfig def) {
            return reachabilityGate(facts, STRUCTURE, WITHIN_BLOCKS);
        }
    }

    /**
     * A GATE, same reasoning as {@link FortressReachableInNether}: elytra have
     * no source but an end city. Scoped to the literal {@code minecraft:the_end}
     * for the same reason custom end-type dimensions are excluded there —
     * optional content, not the progression path.
     */
    static final class EndCityReachableInEnd implements Criterion {
        static final String STRUCTURE = "minecraft:end_city";
        static final double WITHIN_BLOCKS = 2048.0;

        public String id() {
            return "end_city_reachable_in_end";
        }

        public Group group() {
            return Group.APPROPRIATE;
        }

        public String target(DimensionConfig def) {
            return "an end city sits within " + (int) WITHIN_BLOCKS + " blocks of spawn";
        }

        public boolean applicable(DimensionConfig def) {
            return "minecraft:the_end".equals(def.getDimensionId());
        }

        public boolean gate() {
            return true;
        }

        public double[] band(DimensionConfig def) {
            return new double[] {0.0, WITHIN_BLOCKS};
        }

        public Result evaluate(SeedFacts facts, DimensionConfig def) {
            return reachabilityGate(facts, STRUCTURE, WITHIN_BLOCKS);
        }
    }

    /**
     * Shared gate logic. Passes within the floor, fails beyond it. Unmeasured
     * (never a fail) when the structure is not in this dimension's pool at
     * all — a config-time fact true for every seed, not evidence about this
     * one — or when the seed's own placement left {@code nearestByStructure}
     * with no entry for it: the fact could not be measured, which this
     * codebase never treats as evidence of poor quality.
     */
    private static Criterion.Result reachabilityGate(SeedFacts facts, String structureId, double withinBlocks) {
        Measured<Map<String, Integer>> pool = facts.structures().pool();
        if (!pool.isPresent()) {
            return new Criterion.Result.Unmeasured(pool.reason());
        }
        if (!pool.orThrow().containsKey(structureId)) {
            return new Criterion.Result.Unmeasured(structureId + " is not in this dimension's structure pool");
        }
        Measured<Map<String, Double>> nearest = facts.structures().nearestByStructure();
        if (!nearest.isPresent()) {
            return new Criterion.Result.Unmeasured(nearest.reason());
        }
        Double blocks = nearest.orThrow().get(structureId);
        if (blocks == null) {
            return new Criterion.Result.Unmeasured(structureId
                    + " is in the pool but this seed's nearestByStructure has no entry for it");
        }
        String ev = String.format(Locale.ROOT, "nearest %s is %.0f blocks from spawn", structureId, blocks);
        return blocks <= withinBlocks
                ? new Criterion.Result.Pass(ev)
                : new Criterion.Result.Fail(
                        ev + ", beyond the " + (int) withinBlocks + "-block reachability floor",
                        "reachability floor: " + (int) withinBlocks + " blocks");
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

    /**
     * A GATE, same register as {@link NothingIsImmediatelyLethal}: whether a
     * player can actually start playing at spawn is a yes/no question, not a
     * quality axis a good structure elsewhere buys back. "A spawn ringed by
     * lava is not a bad world, it is an unusable one" — the same argument
     * that makes a sheer drop a gate rather than a deduction.
     *
     * <p>{@code nearbyGround} lists one entry per probed column that
     * answered both a height and a ground read — up to the same nine
     * columns {@code localRelief} already visits (one probe step, 16
     * blocks, per neighbouring chunk), in no stated order, so this reads
     * the list as a whole rather than any single position in it: the exact
     * spawn column cannot be picked out of it.
     *
     * <p>Two failure conditions, not one, because "safe to build on" is two
     * different questions:
     *
     * <ul>
     * <li><b>Any {@code HAZARDOUS_FLUID} column fails outright.</b> Lava or
     *     fire within one probe step is close enough that a player getting
     *     their bearings will plausibly walk into it — this is not one
     *     event in a set to be averaged, it is a death, and a death is not
     *     a matter of degree. One occurrence among nine is already the
     *     failure; there is no "mostly avoided the lava" reading of this
     *     that makes a seed usable.</li>
     * <li><b>Fewer than half the probed columns being {@code SOLID} also
     *     fails.</b> Absence of active hazard is not the same as having
     *     somewhere to stand — a spawn where most of the immediate
     *     surroundings are open water has no reliable nearby platform even
     *     though nothing there kills the player outright. Half is the
     *     threshold because it is the least that still guarantees solid
     *     ground reachable in at least one direction from spawn without
     *     already being outnumbered by water; softer than that and "safe to
     *     build on" stops being a claim about the immediate footprint at
     *     all.</li>
     * </ul>
     */
    static final class SpawnIsSafeToBuildOn implements Criterion {
        public String id() {
            return "spawn_is_safe_to_build_on";
        }

        public Group group() {
            return Group.FUN;
        }

        public String target(DimensionConfig def) {
            return "no lava or fire within a probe step of spawn, and at least half "
                    + "of the probed columns are solid ground";
        }

        public boolean gate() {
            return true;
        }

        public Result evaluate(SeedFacts facts, DimensionConfig def) {
            Measured<List<SeedFacts.GroundKind>> groundM = facts.spawn().nearbyGround();
            if (!groundM.isPresent()) {
                return new Result.Unmeasured(groundM.reason());
            }
            List<SeedFacts.GroundKind> ground = groundM.orThrow();
            int total = ground.size();
            long hazard = ground.stream()
                    .filter(g -> g == SeedFacts.GroundKind.HAZARDOUS_FLUID).count();
            long solid = ground.stream()
                    .filter(g -> g == SeedFacts.GroundKind.SOLID).count();
            String ev = String.format(Locale.ROOT, "%d/%d solid, %d/%d hazardous fluid",
                    solid, total, hazard, total);
            if (hazard > 0) {
                return new Result.Fail(ev + " — lava or fire within a probe step of spawn",
                        "no hazardous fluid within a probe step");
            }
            if (solid * 2 < total) {
                return new Result.Fail(ev + " — fewer than half the probed columns are solid",
                        "at least half of the probed columns must be solid ground");
            }
            return new Result.Pass(ev);
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

    static String waterWord(DimensionConfig def) {
        DimensionConfig.SeedRoll sr = def.getSeedRoll();
        return sr == null || sr.water == null || sr.water.isBlank()
                ? null : sr.water.trim().toLowerCase(Locale.ROOT);
    }

    /** {@code seedRoll.heightRange} as {min, max}, or null when unset or malformed. */
    static int[] heightRange(DimensionConfig def) {
        DimensionConfig.SeedRoll sr = def.getSeedRoll();
        if (sr == null || sr.heightRange == null || sr.heightRange.length != 2) {
            return null;
        }
        int lo = Math.min(sr.heightRange[0], sr.heightRange[1]);
        int hi = Math.max(sr.heightRange[0], sr.heightRange[1]);
        return lo == hi ? null : new int[] {lo, hi};
    }
}
