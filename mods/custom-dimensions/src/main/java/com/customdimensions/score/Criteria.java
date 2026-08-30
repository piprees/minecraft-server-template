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

    /**
     * The criteria that exist for every dimension, in report order.
     *
     * <p>Not the whole model: a dimension's own {@code seedRoll.wants} and
     * {@code seedRoll.shuns} each contribute a criterion of their own, and
     * those depend on the config. {@link #forConfig} is what a scorer wants;
     * this is the fixed half of it.
     */
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
                new EndSuppliesEnoughElytra(),
                new SpawnIsPlayable());
    }

    /**
     * Every criterion this dimension poses — {@link #all} plus one per
     * {@code seedRoll.wants} entry and one per {@code seedRoll.shuns} entry.
     *
     * <p>A want gets a criterion of its own rather than all of them sharing
     * one, because each is a separate arguable judgement with its own
     * evidence and its own band for the map to draw. Rolled into a single
     * mean they would arrive as one number nobody could take apart, which is
     * the disease the criterion model exists to treat.
     *
     * <p>Every scoring caller must use this. {@link #all} alone silently
     * drops the configured tier, and a scorecard built from it would look
     * complete.
     */
    public static List<Criterion> forConfig(DimensionConfig def) {
        List<Criterion> out = new java.util.ArrayList<>(all());
        DimensionConfig.SeedRoll sr = def == null ? null : def.getSeedRoll();
        if (sr != null && sr.wants != null) {
            for (Map.Entry<String, com.google.gson.JsonElement> e : sr.wants.entrySet()) {
                Band band = Band.of(bandName(e.getValue()));
                if (band != null) {
                    out.add(new WantedStructure(e.getKey(), resolve(e.getKey()), band));
                }
            }
        }
        for (String name : shunNames(def)) {
            out.add(new ShunnedStructure(name, resolve(name)));
        }
        return List.copyOf(out);
    }

    /**
     * A want name as a structure id or {@code #tag}, or null when the alias
     * table knows neither.
     *
     * <p>Resolved HERE and carried on the criterion, not looked up inside
     * {@code evaluate}. A criterion never reads a registry or a jar-baked
     * table — it reads facts and config — and doing the lookup once per roll
     * rather than once per seed is the same rule paying for itself.
     */
    private static String resolve(String name) {
        return com.customdimensions.dimension.StructureAliases.resolve(name);
    }

    /** The band word a {@code seedRoll.wants} value carries, or null. */
    private static String bandName(com.google.gson.JsonElement value) {
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
            return null;
        }
        String s = value.getAsString().trim().toLowerCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    /**
     * The config's shun names, from the one place that resolves them —
     * {@code structures.shuns} first, else {@code seedRoll.shuns}. The facts
     * layer needs the same list to look tags up and the pool builder needs it
     * to lower a weight, and two readers of one config field drift apart
     * silently: a seed marked down for a structure the world is no less likely
     * to make is a roll spent on nothing.
     */
    static List<String> shunNames(DimensionConfig def) {
        return com.customdimensions.dimension.StructureWants.shunNames(def);
    }

    // ----------------------------------------------------------------- theme

    /**
     * Does the dimension look like itself where a player arrives?
     *
     * <p>GRADED, not a gate, and the change is deliberate. As a gate this
     * rejected the seed outright, and it was one of the three gates that
     * between them left eight dimensions with no candidate at all after
     * hundreds of seeds each — several Nether seeds scoring above 3 out of 5
     * died here because spawn landed in an Incendium biome the config's
     * four-entry {@code spawnFilter} did not list.
     *
     * <p>Rejecting on it was also measuring the wrong thing. The spawn column
     * is not fixed: picking a candidate writes the position you were standing
     * in as the dimension's spawn, so a world with a namesake biome anywhere
     * in it can be given a namesake spawn. What ranks is therefore not "did
     * the origin happen to land in one" but "how much of this world is the
     * thing the dimension is named after", and the {@code biomes.shares} fact
     * answers exactly that.
     *
     * <p>The mark is the share of the world that reads as this dimension,
     * uncapped, so it goes on discriminating above a third. A filter is a
     * CONJUNCTION — a dimension naming two biomes wants both — so a combined
     * share is the wrong aggregate: it lets one namesake substitute for
     * another. {@link #namesakeMark} uses a balanced coverage that equals the
     * combined share when the named biomes hold equal ground and falls below
     * it as one crowds the others out, times the fraction of named biomes
     * actually delivered. A spawn already standing in one is a boost toward a
     * perfect world rather than a full mark, because the spawn is relocatable
     * and the world is not. No namesake biome anywhere is zero: that world is
     * not this dimension, and no amount of standing somewhere else will make
     * it one.
     *
     * <p>{@code biomes.shares} is a real measurement at both tiers — tier 1
     * just samples a coarser grid than tier 2 does ({@code FactsEngine}'s
     * {@code SCREEN_GRID} vs {@code GRID}), so this criterion never needs to
     * know which tier measured it.
     */
    static final class SpawnReadsAsNamesake implements Criterion {
        /**
         * How much of the gap to a perfect world a spawn already standing in
         * a namesake closes. Judgement, not measurement: the world is what
         * ranks and the spawn column is relocatable, so this is a thumb on
         * the scale rather than the scale.
         */
        static final double NATIVE_SPAWN_BONUS = 0.25;

        /**
         * The mark for a world holding {@code shares} of the biomes its
         * filter names, in the filter's own order and 0 for one that is
         * absent.
         *
         * <p>{@code present x geometricMean(present)} is the combined share a
         * world would have if the delivered biomes held equal ground; by
         * AM-GM it never exceeds the real combined share, and it falls away
         * as one biome crowds out the rest. That is the conjunction: naming
         * two biomes and delivering one of them twice over is not the same
         * world as delivering both. Multiplying by the delivered fraction
         * penalises naming a biome the world does not have at all, without
         * annihilating a world that merely misses one.
         *
         * <p>Bounded by construction rather than by clamping — a cap is what
         * made the old form blind above a third.
         */
        static double namesakeMark(double[] shares, boolean spawnIsNamesake) {
            double logSum = 0.0;
            int present = 0;
            for (double s : shares) {
                if (s > 0.0) {
                    logSum += Math.log(s);
                    present++;
                }
            }
            if (present == 0) {
                // The grid resolved none of it and the spawn is standing in
                // one — [K7], a patch smaller than the lattice step. Not a
                // separate pick: the branch below tends to exactly
                // NATIVE_SPAWN_BONUS as coverage tends to 0, so this is its
                // limit. Anything higher would score 0% coverage above a world
                // holding 0.0001% of it.
                return spawnIsNamesake ? NATIVE_SPAWN_BONUS : 0.0;
            }
            double balanced = present * Math.exp(logSum / present);
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

        public String target(DimensionConfig def) {
            List<String> want = namesake(def);
            return want.isEmpty() ? "no spawn filter configured"
                    : "spawn is one of: " + String.join(", ", want);
        }

        public boolean applicable(DimensionConfig def) {
            return !namesake(def).isEmpty();
        }

        public Result evaluate(SeedFacts facts, DimensionConfig def) {
            List<String> want = namesake(def);
            Measured<String> biome = facts.spawn().biome();
            if (!biome.isPresent()) {
                return new Result.Unmeasured(biome.reason());
            }
            boolean native_ = want.contains(biome.orThrow());
            Measured<Map<String, Double>> shares = facts.biomes().shares();
            if (!shares.isPresent()) {
                // A namesake spawn answers the question on its own; coverage
                // is what it cannot answer, so it takes the boost alone.
                return native_
                        ? new Result.Score(NATIVE_SPAWN_BONUS, "spawn is " + biome.orThrow()
                                + ", a namesake; coverage unmeasured (" + shares.reason() + ")")
                        : new Result.Unmeasured(shares.reason());
            }
            double[] named = new double[want.size()];
            double combined = 0.0;
            int present = 0;
            for (int i = 0; i < want.size(); i++) {
                Double s = shares.orThrow().get(want.get(i));
                named[i] = s == null ? 0.0 : s;
                combined += named[i];
                if (named[i] > 0.0) {
                    present++;
                }
            }
            String where = native_ ? "spawn is " + biome.orThrow() + ", a namesake"
                    : "spawn is " + biome.orThrow() + ", not a namesake biome";
            String ev = String.format(Locale.ROOT,
                    "%s; %d of %d named biome(s) present, covering %.1f%% of the world",
                    where, present, want.size(), combined * 100.0);
            if (present == 0 && !native_) {
                ev = ev + " — nowhere here reads as this dimension";
            }
            return new Result.Score(namesakeMark(named, native_), ev);
        }
    }

    /**
     * The dimension should look like itself without being one biome.
     *
     * <p>Both failure modes are real and opposite: a headline share near 1.0 is
     * a single-biome world the config said was multi_biome, and a share near
     * 0.1 is a mosaic with no identity. The target band is stated, not a curve
     * fitted to whatever the pack happens to do.
     *
     * <p>The band is a judgement about a PALETTE, so it is only asked of a
     * dimension that declared one — the same rule and the same reason as
     * {@link BiomeVarietyPresent#applicable}. A dimension whose biomes come
     * from its noise settings stated no palette, and there is no share it was
     * aiming for: the overworld carries Terralith's ~1800 biomes and a headline
     * share of 0.088, which no seed can move into a 0.30-0.55 band, so asking
     * was a permanent deduction that ranked nothing. A one-entry list is
     * excluded for the mirror reason — full domination is what that author
     * asked for, and marking it down for delivering it inverts the question.
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

        public boolean applicable(DimensionConfig def) {
            return declaredBiomes(def) > 1;
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

        /** The palette is the author's; whether it arrived is configured intent. */
        public Tier tier() {
            return Tier.CONFIGURED;
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
            return declaredBiomes(def);
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
        /**
         * The edge density that reads as a varied place to arrive in.
         *
         * <p>Read against {@code edgeDensityNearSpawn}, which is sampled at a
         * fixed 48-block step, so the number means the same thing in a pocket
         * dimension as in a full-sized one. The old 0.35 was read against the
         * playable grid's own step — 51 blocks at a 1024 border and 409 at an
         * 8192 one — where the six highest-scoring dimensions in the bank were
         * all the largest and the six lowest were all pockets, ranking them by
         * their sampling step rather than by anything about the worlds.
         *
         * <p>At a 48-block step a boundary falls between two samples about as
         * often as 48 blocks fits into a biome's width, so a world of ordinary
         * few-hundred-block biomes lands near a fifth and one biome to the
         * horizon lands at zero.
         */
        static final double IDEAL = 0.20;

        public String id() {
            return "biome_edges_near_spawn";
        }

        public Group group() {
            return Group.INTEREST;
        }

        public String target(DimensionConfig def) {
            return "more than one biome within a walk of spawn (edge density ~" + IDEAL + ")";
        }

        public Result evaluate(SeedFacts facts, DimensionConfig def) {
            Measured<Double> edges = facts.biomes().edgeDensityNearSpawn();
            if (!edges.isPresent()) {
                return new Result.Unmeasured(edges.reason());
            }
            double v = edges.orThrow();
            return new Result.Score(Math.min(1.0, v / IDEAL),
                    String.format(Locale.ROOT, "edge density %.3f near spawn", v));
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
     * Terrain should match what {@code seedRoll.terrain} asked for.
     *
     * <p>The field speaks TWO vocabularies, and until now the criterion knew
     * only one of them. Six words describe how much vertical spread the
     * terrain has; two — {@code islands} and {@code void} — describe whether
     * there is ground under you at all, which is a different axis entirely.
     * Every config that actually sets the field uses the second vocabulary
     * (nine say {@code islands}, three say {@code void}), so a criterion that
     * knew only relief words was {@code not_applicable} on all 2349 banked
     * candidates: authored intent, measured by nothing.
     *
     * <p>The ground words are read against {@code terrain.groundFraction},
     * which exists for this. Neither vocabulary was deleted — a relief word
     * still means a relief band — and neither was invented: the relief bands
     * are the hand-calibrated ones already here, and the ground bands say the
     * obvious thing about the two words in plain arithmetic.
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

        /**
         * word -> (ground fraction low, high).
         *
         * <p>{@code void} wants almost nothing under you; a tenth is the
         * allowance for the pillars and platforms a void dimension is usually
         * built around rather than being literally empty. {@code islands}
         * wants a field of them: enough ground to land on and stand about,
         * nowhere near enough to walk across. Above 70% the gaps have stopped
         * being what the place is, and it is a continent with lakes.
         */
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

        public String target(DimensionConfig def) {
            String word = terrainWord(def);
            if (word == null) {
                return "no terrain word configured";
            }
            double[] ground = GROUND_BANDS.get(word);
            if (ground != null) {
                return word + ": ground under " + pct(ground[0]) + " to " + pct(ground[1])
                        + " of the playable disc";
            }
            double[] band = BANDS.get(word);
            return band == null ? "unknown terrain word '" + word + "'"
                    : word + ": relief between " + (int) band[0] + " and " + (int) band[1];
        }

        public boolean applicable(DimensionConfig def) {
            String word = terrainWord(def);
            return word != null && (BANDS.containsKey(word) || GROUND_BANDS.containsKey(word));
        }

        public Result evaluate(SeedFacts facts, DimensionConfig def) {
            String word = terrainWord(def);
            double[] ground = GROUND_BANDS.get(word);
            if (ground != null) {
                Measured<Double> fraction = facts.terrain().groundFraction();
                if (!fraction.isPresent()) {
                    return new Result.Unmeasured(fraction.reason());
                }
                double v = fraction.orThrow();
                return scoreInBand(v, ground[0], ground[1], String.format(Locale.ROOT,
                        "ground under %.1f%% of the disc, %s wants %s-%s",
                        v * 100.0, word, pct(ground[0]), pct(ground[1])));
            }
            double[] relief = BANDS.get(word);
            Measured<Double> measured = facts.terrain().relief();
            if (!measured.isPresent()) {
                return new Result.Unmeasured(measured.reason());
            }
            double v = measured.orThrow();
            return scoreInBand(v, relief[0], relief[1], String.format(Locale.ROOT,
                    "relief %.0f, %s wants %d-%d", v, word, (int) relief[0], (int) relief[1]));
        }
    }

    /**
     * Full marks inside {@code [low, high]}, decaying to zero one band-width
     * outside it. The shape three criteria share, so a band means the same
     * thing wherever one is written.
     */
    private static Criterion.Result scoreInBand(double v, double low, double high, String evidence) {
        if (v >= low && v <= high) {
            return new Criterion.Result.Score(1.0, evidence + " — inside the band");
        }
        double width = high - low;
        double miss = v < low ? low - v : v - high;
        return new Criterion.Result.Score(ramp(width - miss, 0.0, width),
                evidence + " — outside the band");
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
        /**
         * word -> (fraction low, fraction high) of the columns WITH GROUND
         * that sit at or below sea level.
         *
         * <p>{@code none}'s upper edge is 0.20 rather than the recovered
         * 0.10, and the reason is a mechanism rather than a fit. Nothing in
         * the generator reads {@code seedRoll.water} — it is checked: no
         * caller outside this file touches the field — so the sea level a
         * dimension actually gets belongs to its noise preset, and an
         * overworld-shaped preset floods every column below it. Measured
         * across the bank, the five dimensions asking for {@code none} land
         * between 0.00 and 0.55, four of them on a default sea level. "None"
         * cannot mean "not one wet column" for a world built on such a
         * preset; it means no body of water worth the name, and a fifth of
         * the disc is ponds rather than an ocean. A dimension that genuinely
         * needs a dry world says so with {@code settingsOverrides.seaLevel},
         * which the generator DOES read.
         */
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
            return scoreInBand(v, band[0], band[1], String.format(Locale.ROOT,
                    "water fraction %.3f, %s wants %.2f-%.2f", v, word, band[0], band[1]));
        }
    }

    /**
     * The terrain should live inside the vertical envelope
     * {@code seedRoll.heightRange} declared.
     *
     * <p>CONTAINMENT, not coverage, and that is the fix. Scored as the share
     * of the CONFIGURED span the measured range filled, this averaged 0.168
     * across the bank and could not do better: all six dimensions that set
     * the field declare {@code [-60, 440]}, a 500-block envelope, and real
     * terrain occupies 70-odd blocks of it — {@code the_abyssal_shrine}
     * measures 90 to 166 and scored 0.15 for it. That is a world behaving
     * exactly as asked being marked down for not being 500 blocks tall.
     *
     * <p>An envelope is a permission, not a quota. The question is what share
     * of the terrain that exists sits inside it, so a world entirely within
     * its envelope scores 1.0 however narrow it is, and one spilling out the
     * top is marked down by exactly the share that spilled.
     */
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

        public String target(DimensionConfig def) {
            int[] range = heightRange(def);
            return range == null ? "no height range configured"
                    : "the terrain that exists sits between " + range[0] + " and " + range[1];
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
            String ev = String.format(Locale.ROOT, "measured %d-%d, configured %d-%d",
                    measMin, measMax, confLow, confHigh);
            double overlap = Math.min(measMax, confHigh) - Math.max(measMin, confLow);
            double measuredSpan = measMax - measMin;
            if (measuredSpan <= 0.0) {
                // A world of one height is either in the envelope or not;
                // there is no share of it to take.
                boolean inside = measMin >= confLow && measMax <= confHigh;
                return new Result.Score(inside ? 1.0 : 0.0,
                        ev + (inside ? " — the one height measured is inside the envelope"
                                : " — the one height measured is outside the envelope"));
            }
            double v = Math.max(0.0, Math.min(1.0, overlap / measuredSpan));
            return new Result.Score(v, ev + String.format(Locale.ROOT,
                    " — %.0f%% of the terrain is inside the envelope", v * 100.0));
        }
    }

    /**
     * Is there ground under the playable disc?
     *
     * <p>Scored as {@code terrain.groundFraction} directly, with no band
     * around it: a coverage ratio already has units a person reads without
     * translation, and a band would only be a threshold invented on top of a
     * number that already means something on its own.
     *
     * <p>It used to read {@code grid.heightMeasured() / grid.sampled()},
     * which is a different and much weaker question — did the generator
     * ANSWER, not is there ground. Vanilla's {@code getHeight} answers the
     * world floor for an empty column, so that ratio is 1.0 for every
     * dimension without a ceiling however empty it is: it read 1.000 for
     * {@code the_icebound_rift}, a void whose every sampled column sits at
     * exactly -64.
     *
     * <p>Not applicable when {@code seedRoll.terrain} states {@code "void"}
     * or {@code "islands"}. That is not a gap: those two words are exactly
     * what {@link TerrainMatchesPreset} reads, against the same fact, with a
     * band the author chose. The two criteria's applicability is disjoint by
     * construction and every dimension is asked one of them — this one asks
     * whether a world that never said otherwise has a floor, and that one
     * asks whether a world that did got the floor it asked for.
     */
    static final class PlayableGroundCoversTheDisc implements Criterion {
        public String id() {
            return "playable_ground_covers_the_disc";
        }

        public Group group() {
            return Group.APPROPRIATE;
        }

        public String target(DimensionConfig def) {
            return "every sampled column inside the playable disc carries ground";
        }

        public boolean applicable(DimensionConfig def) {
            String word = terrainWord(def);
            // Map.of throws on a null key, and no terrain word is the common case.
            return word == null || !TerrainMatchesPreset.GROUND_BANDS.containsKey(word);
        }

        public Result evaluate(SeedFacts facts, DimensionConfig def) {
            Measured<Double> fraction = facts.terrain().groundFraction();
            if (!fraction.isPresent()) {
                return new Result.Unmeasured(fraction.reason());
            }
            double coverage = fraction.orThrow();
            return new Result.Score(coverage, String.format(Locale.ROOT,
                    "ground under %.1f%% of the sampled disc", coverage * 100.0));
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

        public String id() {
            return "fortress_reachable_in_nether";
        }

        public Group group() {
            return Group.APPROPRIATE;
        }

        public String target(DimensionConfig def) {
            return "a fortress sits within " + (int) reachableWithin(def, REACHABLE_FRACTION) + " blocks of spawn";
        }

        public boolean applicable(DimensionConfig def) {
            return "minecraft:the_nether".equals(def.getDimensionId());
        }

        public boolean gate() {
            return true;
        }

        public double[] band(DimensionConfig def) {
            return new double[] {0.0, reachableWithin(def, REACHABLE_FRACTION)};
        }

        public Result evaluate(SeedFacts facts, DimensionConfig def) {
            return reachabilityGate(facts, STRUCTURE, reachableWithin(def, REACHABLE_FRACTION));
        }
    }

    /**
     * How far a progression structure may sit from spawn and still count as
     * reachable, as a fraction of the dimension's own playable radius.
     *
     * <p>A fraction for the reason {@link Band} gives: the pack spans
     * 1024-block borders to 16384-block ones, and one distance in blocks cannot
     * mean the same thing across that range. Half the radius is the Nether's
     * own working figure (0.5 x 1024 = 512).
     */
    public static final double REACHABLE_FRACTION = 0.5;

    /**
     * The End's fraction is the WHOLE radius, and the difference is structural
     * rather than a softening. End cities generate only in the outer islands,
     * so half the radius excludes most of the ground they can occupy: the gate
     * was demanding a city in the ring where the central island and the void
     * gap are the only things that exist. What progression actually requires is
     * an end city somewhere a player can reach at all, and the border is that.
     */
    public static final double END_REACHABLE_FRACTION = 1.0;

    /** The reachability floor in blocks for this dimension's own border. */
    public static double reachableWithin(DimensionConfig def, double fraction) {
        return def.getPlayerBorderRadius() * fraction;
    }

    /**
     * The same floor from a measured playable radius, for callers holding facts
     * rather than a config. One definition, so the roller's verdict and any
     * diagnostic reporting it can never disagree about the same seed.
     */
    public static double reachableWithin(double playableRadius, double fraction) {
        return playableRadius * fraction;
    }

    /**
     * A GATE, same reasoning as {@link FortressReachableInNether}: elytra have
     * no source but an end city. Scoped to the literal {@code minecraft:the_end}
     * for the same reason custom end-type dimensions are excluded there —
     * optional content, not the progression path.
     */
    static final class EndCityReachableInEnd implements Criterion {
        static final String STRUCTURE = "minecraft:end_city";

        public String id() {
            return "end_city_reachable_in_end";
        }

        public Group group() {
            return Group.APPROPRIATE;
        }

        public String target(DimensionConfig def) {
            return "an end city anywhere inside the " + (int) reachableWithin(def, END_REACHABLE_FRACTION) + "-block border";
        }

        public boolean applicable(DimensionConfig def) {
            return "minecraft:the_end".equals(def.getDimensionId());
        }

        public boolean gate() {
            return true;
        }

        public double[] band(DimensionConfig def) {
            return new double[] {0.0, reachableWithin(def, END_REACHABLE_FRACTION)};
        }

        public Result evaluate(SeedFacts facts, DimensionConfig def) {
            return reachabilityGate(facts, STRUCTURE, reachableWithin(def, END_REACHABLE_FRACTION));
        }
    }

    /**
     * Elytra supply, estimated from end city count.
     *
     * <p>Graded, not a gate — {@link EndCityReachableInEnd} refuses a seed with
     * no reachable city.
     *
     * <p>A proxy by necessity: an end ship is a jigsaw piece inside
     * {@code minecraft:end_city}, not a structure, so no
     * {@code minecraft:end_ship} exists in any structure set and
     * {@code structures.byStructure} cannot count one. A real count needs the
     * jigsaw pieces of generated starts; the facts layer samples without
     * generating.
     */
    static final class EndSuppliesEnoughElytra implements Criterion {
        /** Ship rate per end city. */
        static final double CITIES_PER_SHIP = 3.0;
        /** Elytra count at full marks. */
        static final double ELYTRA_TARGET = 20.0;
        static final String STRUCTURE = "minecraft:end_city";

        public String id() {
            return "end_supplies_enough_elytra";
        }

        public Group group() {
            return Group.APPROPRIATE;
        }

        public String target(DimensionConfig def) {
            return "enough end cities for about " + (int) ELYTRA_TARGET
                    + " elytra (~" + (int) (ELYTRA_TARGET * CITIES_PER_SHIP) + " cities)";
        }

        public boolean applicable(DimensionConfig def) {
            return "minecraft:the_end".equals(def.getDimensionId());
        }

        public Result evaluate(SeedFacts facts, DimensionConfig def) {
            Measured<Map<String, Integer>> byStructure = facts.structures().byStructure();
            if (!byStructure.isPresent()) {
                return new Result.Unmeasured(byStructure.reason());
            }
            Integer cities = byStructure.orThrow().get(STRUCTURE);
            int placed = cities == null ? 0 : cities;
            double elytra = placed / CITIES_PER_SHIP;
            String ev = String.format(Locale.ROOT,
                    "%d end cities placed, about %.0f elytra at one ship per %.0f cities",
                    placed, elytra, CITIES_PER_SHIP);
            if (placed <= 0) {
                return new Result.Score(0.0, ev + " — nothing here to fly with");
            }
            return new Result.Score(ramp(elytra, 0.0, ELYTRA_TARGET), ev);
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

    /**
     * Is there anywhere here a player could be put down?
     *
     * <p>GRADED, and it replaces two gates that between them were the last
     * structural rejection in the model. Both asked about the ORIGIN column —
     * one for a cliff, one for lava and somewhere to stand — and both rejected
     * the seed outright. {@code the_bloodroot_wastes} seed 999 scored 72 for
     * intent and 81 for quality and was thrown away for a 64-block drop at a
     * column nobody stands in.
     *
     * <p>They were measuring the wrong thing, in the same way
     * {@link SpawnReadsAsNamesake} was. The spawn column is not fixed: picking
     * a candidate writes the position you were standing in as the dimension's
     * spawn, so a world with a usable column anywhere near the origin can
     * simply be given one. {@code spawn.safeColumnFraction} probes a lattice
     * of candidate columns with the same three tests the gates carried — no
     * cliff, no hazardous fluid, at least half the footprint solid — and
     * reports how many passed. Nothing was relaxed; the question moved from
     * one column to the neighbourhood a picker actually chooses from.
     *
     * <p>Zero is still zero, and that is not a softened gate: a world with no
     * safe column anywhere near its spawn has nowhere to arrive, and now says
     * so with a mark rather than by vanishing from the board with the reason
     * discarded.
     */
    static final class SpawnIsPlayable implements Criterion {
        /**
         * The share of candidate columns at which a safe spawn stops being a
         * hunt. A quarter of a 5x5 lattice is six columns spread over 512
         * blocks — past that the picker has ample choice, and more of it does
         * not make the world better to arrive in.
         */
        static final double AMPLE = 0.25;

        public String id() {
            return "spawn_is_playable";
        }

        public Group group() {
            return Group.FUN;
        }

        public String target(DimensionConfig def) {
            return "somewhere within a few hundred blocks of spawn has no cliff, no "
                    + "lava and solid ground to stand on";
        }

        public boolean applicable(DimensionConfig def) {
            return spawnSafetyAsked(def);
        }

        public Result evaluate(SeedFacts facts, DimensionConfig def) {
            Measured<Double> safe = facts.spawn().safeColumnFraction();
            if (!safe.isPresent()) {
                return new Result.Unmeasured(safe.reason());
            }
            double v = safe.orThrow();
            String ev = String.format(Locale.ROOT,
                    "%.0f%% of the candidate spawn columns are playable", v * 100.0);
            if (v <= 0.0) {
                return new Result.Score(0.0, ev + " — nowhere here to arrive");
            }
            return new Result.Score(ramp(v, 0.0, AMPLE),
                    ev + (v >= AMPLE ? " — ample choice of spawn"
                            : " — a spawn could be placed, but the choice is thin"));
        }
    }

    // ------------------------------------------------------- configured wants

    /**
     * Where a wanted structure should sit, as a FRACTION of the dimension's
     * own playable radius.
     *
     * <p>Never absolute blocks. The pack spans 1024-block borders to
     * 16384-block ones, and one set of distances cannot mean the same thing
     * across that range: 400 blocks is the far side of a pocket dimension and
     * the doorstep of a full-sized one.
     *
     * <p>The bands overlap on purpose. They are three plain-English words an
     * author chose, not a partition — a structure at 0.18 of the radius has
     * plainly satisfied both "near spawn" and "spread", and a hard boundary
     * would make one of those a failure by a hair.
     */
    enum Band {
        NEAR_SPAWN("near_spawn", 0.00, 0.15),
        SPREAD("spread", 0.10, 0.75),
        NEAR_BORDER("near_border", 0.55, 1.00);

        /**
         * How far outside a band, as a fraction of the playable radius, is a
         * total miss.
         *
         * <p>Shared by all three bands rather than each decaying over its own
         * width, which is how the other band criteria work and is wrong here.
         * The bands are different widths — {@code near_spawn} spans 0.15 of
         * the radius and {@code spread} spans 0.65 — so a band-relative decay
         * makes a structure a quarter of the world outside {@code near_spawn}
         * score zero while the same absolute miss on {@code spread} scores
         * 0.6. The two misses are the same distance in the world, and the
         * player walking it does not know which word the config used.
         */
        static final double TOLERANCE = 0.25;

        final String word;
        final double low;
        final double high;

        Band(String word, double low, double high) {
            this.word = word;
            this.low = low;
            this.high = high;
        }

        static Band of(String word) {
            for (Band b : values()) {
                if (b.word.equals(word)) {
                    return b;
                }
            }
            return null;
        }
    }

    /**
     * The structure ids a want or shun name stands for, or the reason it names
     * none: one id for a plain structure, the tag's members for a {@code #tag}.
     *
     * <p>Both criteria go through here so a tag and a plain id take the same
     * route afterwards. A tag is a SET — {@code village} is
     * {@code #minecraft:village} — and "is there a village within reach" is
     * answered by the nearest member, not by a structure called "village",
     * which does not exist.
     */
    private record Targets(List<String> ids, String absent) {
    }

    private static Targets targetsOf(String name, String structureId, SeedFacts facts) {
        if (structureId == null) {
            return new Targets(null, "'" + name + "' is not a known structure name or "
                    + "id — customdim lint reports it");
        }
        if (!structureId.startsWith("#")) {
            return new Targets(List.of(structureId), null);
        }
        Measured<Map<String, List<String>>> tags = facts.structures().tagMembers();
        if (!tags.isPresent()) {
            return new Targets(null, tags.reason());
        }
        List<String> members = tags.orThrow().get(structureId);
        if (members == null) {
            return new Targets(null, "'" + name + "' resolves to the tag " + structureId
                    + ", whose membership was not looked up for this dimension");
        }
        if (members.isEmpty()) {
            return new Targets(null, "the tag " + structureId
                    + " holds no structures on this mod stack");
        }
        return new Targets(members, null);
    }

    /**
     * The nearest of these structures, as {@code {id, blocks}}, or null when
     * this seed placed none of them.
     */
    private static Object[] nearestOf(List<String> ids, Map<String, Double> nearest) {
        String bestId = null;
        double best = Double.MAX_VALUE;
        for (String id : ids) {
            Double at = nearest.get(id);
            if (at != null && at < best) {
                best = at;
                bestId = id;
            }
        }
        return bestId == null ? null : new Object[] {bestId, best};
    }

    /**
     * One {@code seedRoll.wants} entry: this structure, in that band.
     *
     * <p>486 of these are authored across the pack and none of them was read
     * by anything. Everything the judgement needs was already measured or
     * resolvable — {@code StructureAliases} maps the short name to a
     * structure id or a tag, {@code facts.structures.nearestByStructure}
     * carries the distance, {@code structures.tagMembers} says what a tag
     * holds, and {@code borders.player} sets the scale — so this is a wire
     * that was missing, not a measurement that was.
     */
    static final class WantedStructure implements Criterion {
        private final String name;
        private final String structureId;
        private final Band band;

        /** @param structureId the resolved structure id, a {@code #tag}, or null. */
        WantedStructure(String name, String structureId, Band band) {
            this.name = name;
            this.structureId = structureId;
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

        public String target(DimensionConfig def) {
            return name + " sits between " + pct(band.low) + " and " + pct(band.high)
                    + " of the border (" + band.word + ")";
        }

        /**
         * Always applicable: the config named this want, so the question is
         * posed whatever the seed turns out to hold. Whether it can be
         * ANSWERED is a fact question and belongs in {@link #evaluate}.
         */
        public boolean applicable(DimensionConfig def) {
            return true;
        }

        public double[] band(DimensionConfig def) {
            double radius = def.getPlayerBorderRadius();
            return new double[] {band.low * radius, band.high * radius};
        }

        public Result evaluate(SeedFacts facts, DimensionConfig def) {
            Targets targets = targetsOf(name, structureId, facts);
            if (targets.ids() == null) {
                return new Result.Unmeasured(targets.absent()
                        + " — no seed can satisfy it");
            }
            Measured<Map<String, Integer>> pool = facts.structures().pool();
            if (!pool.isPresent()) {
                return new Result.Unmeasured(pool.reason());
            }
            List<String> inPool = targets.ids().stream()
                    .filter(pool.orThrow()::containsKey).toList();
            if (inPool.isEmpty()) {
                // True of every seed of this dimension, not evidence about
                // this one. Scoring it zero would be a permanent deduction
                // for a config line lint already reports.
                return new Result.Unmeasured(describe() + " is not in this dimension's "
                        + "structure pool, so no seed of it can place one");
            }
            double radius = facts.playableRadius();
            if (radius <= 0) {
                return new Result.Unmeasured("the playable radius is not positive");
            }
            double[] blocks = band(def);
            Measured<Map<String, Double>> nearest = facts.structures().nearestByStructure();
            if (!nearest.isPresent()) {
                return new Result.Unmeasured(nearest.reason());
            }
            Object[] best = nearestOf(inPool, nearest.orThrow());
            if (best == null) {
                return new Result.Score(0.0, "in the pool, but this seed placed no "
                        + describe(), blocks);
            }
            String atId = (String) best[0];
            double at = (Double) best[1];
            int count = countOf(facts, inPool);
            double frac = at / radius;
            String ev = String.format(Locale.ROOT,
                    "nearest %s %.0f blocks (%.1f%% of the border), %d placed",
                    atId, at, frac * 100.0, count);
            if (frac >= band.low && frac <= band.high) {
                return new Result.Score(1.0, ev + " — inside the band", blocks);
            }
            double miss = frac < band.low ? band.low - frac : frac - band.high;
            return new Result.Score(ramp(Band.TOLERANCE - miss, 0.0, Band.TOLERANCE),
                    ev + String.format(Locale.ROOT, " — %.0f%% of the border outside the band",
                            miss * 100.0), blocks);
        }

        /** What this want names, for evidence: the id, or the tag it stands for. */
        private String describe() {
            return structureId == null ? name : structureId;
        }

        /** Placements of any of these structures — a tag's count is its members'. */
        private static int countOf(SeedFacts facts, List<String> ids) {
            Measured<Map<String, Integer>> by = facts.structures().byStructure();
            if (!by.isPresent()) {
                return 0;
            }
            int total = 0;
            for (String id : ids) {
                Integer n = by.orThrow().get(id);
                if (n != null) {
                    total += n;
                }
            }
            return total;
        }
    }

    /**
     * One shun entry: the author said not this one.
     *
     * <p>Absent is the answer in full. Present is scored by how much of the
     * world separates a player from it — a shunned structure at the far rim
     * is a different disappointment from one at spawn, and the distance is
     * the whole of the difference. There is no band because there is no
     * region the author wanted it in.
     */
    static final class ShunnedStructure implements Criterion {
        private final String name;
        private final String structureId;

        /** @param structureId the resolved structure id, a {@code #tag}, or null. */
        ShunnedStructure(String name, String structureId) {
            this.name = name;
            this.structureId = structureId;
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

        public String target(DimensionConfig def) {
            return "no " + name + " here, and the further off the better if there is";
        }

        public Result evaluate(SeedFacts facts, DimensionConfig def) {
            Targets targets = targetsOf(name, structureId, facts);
            if (targets.ids() == null) {
                return new Result.Unmeasured(targets.absent());
            }
            Measured<Map<String, Double>> nearest = facts.structures().nearestByStructure();
            if (!nearest.isPresent()) {
                return new Result.Unmeasured(nearest.reason());
            }
            Object[] best = nearestOf(targets.ids(), nearest.orThrow());
            if (best == null) {
                return new Result.Score(1.0, "no " + structureId + " placed");
            }
            double radius = facts.playableRadius();
            if (radius <= 0) {
                return new Result.Unmeasured("the playable radius is not positive");
            }
            double at = (Double) best[1];
            double frac = at / radius;
            return new Result.Score(Math.min(1.0, frac), String.format(Locale.ROOT,
                    "nearest %s %.0f blocks away (%.1f%% of the border)",
                    best[0], at, frac * 100.0));
        }
    }

    // ----------------------------------------------------------------- utils

    /**
     * How many biomes this config declared.
     *
     * <p>The shared input to both palette criteria, so "did this dimension
     * state a palette" is one answer rather than two that can drift.
     */
    static int declaredBiomes(DimensionConfig def) {
        List<String> biomes = def == null ? null : def.getBiomes();
        return biomes == null ? 0 : biomes.size();
    }

    /** A fraction of the border as a percentage, for a target sentence. */
    private static String pct(double fraction) {
        return String.format(Locale.ROOT, "%.0f%%", fraction * 100.0);
    }

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
     * Whether this dimension is asked whether its spawn is playable.
     *
     * <p>{@code seedRoll.allowHazardousSpawn} withdraws the question: a
     * dimension whose whole proposition is danger where you arrive is not
     * marked down for delivering it.
     *
     * <p>The reason a dimension can opt out at all is that its spawn column
     * is not usually where a player lands. Every dimension in the pack is
     * entered through a portal, and the mod builds that arrival: {@code
     * PortalSite} finds an open site or carves one, lays a floor under it,
     * and REFUSES the traversal rather than dropping somebody somewhere
     * unopenable. A player therefore steps out onto a platform the mod made,
     * not onto the column these criteria measure — and for a dimension whose
     * whole proposition is danger, a cliff at that column is scenery.
     *
     * <p>Opt-out, never derived. Every dimension has a portal, so deriving it
     * from that would switch the criterion off for the entire pack in one
     * silent step, and quietly disabling a check is this codebase's worst
     * failure mode. A dimension that relaxes the rule says so in its own
     * file, where the diff shows it.
     */
    static boolean spawnSafetyAsked(DimensionConfig def) {
        DimensionConfig.SeedRoll sr = def == null ? null : def.getSeedRoll();
        return sr == null || !Boolean.TRUE.equals(sr.allowHazardousSpawn);
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
