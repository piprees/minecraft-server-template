package com.customdimensions.facts;

import com.customdimensions.command.Artefacts;
import com.customdimensions.command.SpikeSampler;
import com.customdimensions.config.DimensionConfig;
import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.dimension.DimensionStructures;
import com.customdimensions.dimension.NoiseGroupPlan;
import com.customdimensions.dimension.NoisePoolBuilder;
import com.customdimensions.dimension.NoiseStructurePlacement;
import com.customdimensions.dimension.StructurePick;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.structure.StructureSet;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Measures one (dimension, seed) with no {@code ServerWorld}, using the mod's
 * own worldgen classes rather than a mirror of them.
 *
 * <p>Everything here is a measurement. Nothing here is a judgement — no
 * weights, no targets, no verdicts. The separation is what keeps "this seed is
 * poor", "this config is broken" and "this could not be measured" three
 * distinct answers instead of one number.
 *
 * <p>Sampling is a square grid clipped to the playable disc. The step is
 * derived from the radius so a small dimension is not measured more coarsely
 * than a large one in relative terms, and the grid resolution is recorded so a
 * consumer can tell a 400-sample fact from a 40-sample one.
 */
public final class FactsEngine {

    /**
     * Block spacing for the nine columns probed around spawn. One chunk, so
     * "local relief" is relief a standing player could see, not a hillside
     * measured across a fifth of the world.
     */
    private static final int SPAWN_PROBE_STEP = 16;

    /** Grid samples across the diameter. Odd, so spawn is a sample. */
    public static final int GRID = 41;

    /** Groups whose placements count as hostile for the challenge facts. */
    private static final java.util.Set<String> HOSTILE_GROUPS =
            java.util.Set.of("dungeons", "endgame");

    private FactsEngine() {
    }

    public static SeedFacts measure(MinecraftServer server, Identifier dimensionId, long seed) {
        DimensionConfig def = MultiverseConfig.getInstance().getDimension(dimensionId.getPath());
        if (def == null) {
            def = MultiverseConfig.getInstance().getBaseWorld(dimensionId.toString());
        }
        int radius = def != null ? def.getPlayerBorderRadius() : 8192;

        SpikeSampler.Base base = SpikeSampler.base(server, dimensionId);
        String fingerprint = def != null ? String.valueOf(def.getBiomePatchesFingerprint()) : "";
        if (!base.ok()) {
            String why = "the dimension's generator could not be built: " + base.error();
            return unmeasurable(dimensionId, seed, radius, fingerprint, why);
        }
        SpikeSampler.Rig rig = SpikeSampler.forSeed(server, base, seed);

        // One grid pass feeds spawn, biome and terrain facts. Sampling three
        // times over the same columns would be three chances to disagree with
        // itself.
        Grid grid = sampleGrid(rig, radius);

        return new SeedFacts(
                Artefacts.stackVersion(),
                dimensionId.toString(), seed, Instant.now().toString(), fingerprint, radius,
                spawnFacts(rig, spawnColumn(def)),
                biomeFacts(grid),
                terrainFacts(grid, rig),
                structureFacts(server, def, base, seed, radius));
    }

    // ------------------------------------------------------------------ grid

    /**
     * One pass over the playable disc. Nulls mark cells that answered nothing.
     * Package-private so the pure grid computations below can be pinned
     * against hand-built layouts — the alternative is verifying relief,
     * grain, shares and edge density only through a live parity run, which
     * tells you the whole pipeline agrees with itself and nothing about
     * whether the arithmetic is what was intended.
     */
    record Grid(String[] biome, Integer[] height, int side, int step,
                int sampled, String absentReason) {
    }

    private static Grid sampleGrid(SpikeSampler.Rig rig, int radius) {
        int side = GRID;
        int step = Math.max(1, (radius * 2) / (side - 1));
        String[] biome = new String[side * side];
        Integer[] height = new Integer[side * side];
        int sampled = 0;
        int half = side / 2;
        for (int gz = 0; gz < side; gz++) {
            for (int gx = 0; gx < side; gx++) {
                int dx = (gx - half) * step;
                int dz = (gz - half) * step;
                if ((long) dx * dx + (long) dz * dz > (long) radius * radius) {
                    continue;   // outside the playable disc
                }
                SpikeSampler.Sample s = SpikeSampler.sample(rig, dx, dz);
                biome[gz * side + gx] = s.biome();
                height[gz * side + gx] = s.surfaceHeight();
                sampled++;
            }
        }
        return new Grid(biome, height, side, step, sampled, null);
    }

    // ----------------------------------------------------------------- spawn

    /**
     * Where a player arrives, as {@code {x, z}}.
     *
     * <p>Not the origin: {@code ExitTarget} resolves an arrival to the
     * dimension's declared {@code spawn}, so that is the only column whose
     * biome, height and relief describe what a player meets.
     */
    private static SeedFacts.Column spawnColumn(DimensionConfig def) {
        int[] spawn = def != null ? def.getSpawn() : null;
        return spawn != null && spawn.length >= 3
                ? new SeedFacts.Column(spawn[0], spawn[2], true)
                : new SeedFacts.Column(0, 0, false);
    }

    private static SeedFacts.SpawnFacts spawnFacts(SpikeSampler.Rig rig, SeedFacts.Column at0) {
        SpikeSampler.Sample at = SpikeSampler.sample(rig, at0.x(), at0.z());
        Measured<Integer> h = at.surfaceHeight() == null
                ? Measured.absent(at.heightAbsent() != null ? at.heightAbsent()
                        : "the generator answered no surface height at spawn")
                : Measured.of(at.surfaceHeight());

        // Local relief: the spread over the chunk the player lands in. Sampled
        // at SPAWN_PROBE_STEP rather than reused from the coarse grid, whose
        // neighbours are (radius * 2) / (GRID - 1) apart — 204 blocks at a 4096
        // radius, where a 34-block rise is a gentle slope, not a cliff.
        List<Integer> around = new ArrayList<>();
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                Integer v = SpikeSampler.sample(rig, at0.x() + dx * SPAWN_PROBE_STEP,
                        at0.z() + dz * SPAWN_PROBE_STEP).surfaceHeight();
                if (v != null) {
                    around.add(v);
                }
            }
        }
        Measured<Double> relief = around.size() < 2
                ? Measured.absent("fewer than two columns near spawn answered a height")
                : Measured.of((double) (java.util.Collections.max(around)
                        - java.util.Collections.min(around)));

        Measured<Boolean> aboveSea;
        if (at.surfaceHeight() == null) {
            aboveSea = Measured.absent("no surface height at spawn to compare with sea level");
        } else if (!(rig.generator() instanceof NoiseChunkGenerator noiseGen)) {
            aboveSea = Measured.absent(
                    "a flat generator has no sea level to compare against");
        } else {
            aboveSea = Measured.of(at.surfaceHeight() > noiseGen.getSettings().value().seaLevel());
        }

        return new SeedFacts.SpawnFacts(
                Measured.of(at0),
                at.biome() == null
                        ? Measured.absent(at.biomeAbsent() != null ? at.biomeAbsent()
                                : "the biome source answered nothing at spawn")
                        : Measured.of(at.biome()),
                h, relief, aboveSea);
    }

    // ---------------------------------------------------------------- biomes

    static SeedFacts.BiomeFacts biomeFacts(Grid grid) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int total = 0;
        for (String b : grid.biome()) {
            if (b != null) {
                counts.merge(b, 1, Integer::sum);
                total++;
            }
        }
        if (total == 0) {
            String why = "no column in the playable disc answered a biome";
            return new SeedFacts.BiomeFacts(Measured.absent(why), Measured.absent(why),
                    Measured.absent(why), Measured.absent(why));
        }
        Map<String, Double> shares = new TreeMap<>();
        double max = 0.0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            double share = e.getValue() / (double) total;
            shares.put(e.getKey(), share);
            max = Math.max(max, share);
        }

        // Edge density: adjacent sample pairs whose biomes differ. This is the
        // fact that separates a mosaic from two hemispheres — a biome COUNT
        // cannot, because both give the same count.
        int pairs = 0;
        int edges = 0;
        int side = grid.side();
        for (int gz = 0; gz < side; gz++) {
            for (int gx = 0; gx < side; gx++) {
                String here = grid.biome()[gz * side + gx];
                if (here == null) {
                    continue;
                }
                if (gx + 1 < side) {
                    String east = grid.biome()[gz * side + gx + 1];
                    if (east != null) {
                        pairs++;
                        if (!here.equals(east)) {
                            edges++;
                        }
                    }
                }
                if (gz + 1 < side) {
                    String south = grid.biome()[(gz + 1) * side + gx];
                    if (south != null) {
                        pairs++;
                        if (!here.equals(south)) {
                            edges++;
                        }
                    }
                }
            }
        }
        return new SeedFacts.BiomeFacts(
                Measured.of(shares),
                Measured.of(counts.size()),
                Measured.of(max),
                pairs == 0
                        ? Measured.absent("no two adjacent columns both answered a biome")
                        : Measured.of(edges / (double) pairs));
    }

    // --------------------------------------------------------------- terrain

    private static SeedFacts.TerrainFacts terrainFacts(Grid grid, SpikeSampler.Rig rig) {
        Integer sea = rig.generator() instanceof NoiseChunkGenerator noiseGen
                ? noiseGen.getSettings().value().seaLevel()
                : null;
        return terrainFacts(grid, sea);
    }

    /**
     * @param seaLevel the generator's sea level, or null when it has none — a
     *                 flat generator's water fraction is absent, not zero.
     */
    static SeedFacts.TerrainFacts terrainFacts(Grid grid, Integer seaLevel) {
        List<Integer> heights = new ArrayList<>();
        for (Integer h : grid.height()) {
            if (h != null) {
                heights.add(h);
            }
        }
        if (heights.size() < 2) {
            String why = seaLevel != null
                    ? "fewer than two columns in the playable disc answered a height"
                    : "this dimension's generator places no terrain to measure";
            return new SeedFacts.TerrainFacts(Measured.absent(why), Measured.absent(why),
                    Measured.absent(why), Measured.absent(why), Measured.absent(why));
        }
        int min = java.util.Collections.min(heights);
        int max = java.util.Collections.max(heights);

        // Grain: mean absolute step between adjacent samples. Relief says how
        // tall the world is, grain says how choppy — a plateau and a spike
        // field can share a relief and read nothing alike.
        long grainSum = 0;
        int grainPairs = 0;
        int side = grid.side();
        for (int gz = 0; gz < side; gz++) {
            for (int gx = 0; gx < side; gx++) {
                Integer here = grid.height()[gz * side + gx];
                if (here == null) {
                    continue;
                }
                if (gx + 1 < side) {
                    Integer east = grid.height()[gz * side + gx + 1];
                    if (east != null) {
                        grainSum += Math.abs(here - east);
                        grainPairs++;
                    }
                }
                if (gz + 1 < side) {
                    Integer south = grid.height()[(gz + 1) * side + gx];
                    if (south != null) {
                        grainSum += Math.abs(here - south);
                        grainPairs++;
                    }
                }
            }
        }

        Measured<Double> water;
        if (seaLevel != null) {
            long below = heights.stream().filter(h -> h <= seaLevel).count();
            water = Measured.of(below / (double) heights.size());
        } else {
            water = Measured.absent(
                    "a flat generator has no sea level, so a water fraction has no meaning");
        }

        return new SeedFacts.TerrainFacts(
                Measured.of((double) (max - min)),
                grainPairs == 0
                        ? Measured.absent("no two adjacent columns both answered a height")
                        : Measured.of(grainSum / (double) grainPairs),
                water,
                Measured.of(min),
                Measured.of(max));
    }

    // ------------------------------------------------------------ structures

    /**
     * The full noise census for an arbitrary seed, built from the mod's own
     * pool builder, noise field and pick — the same three classes generation
     * runs, so this is not a model of the placement, it is the placement.
     */
    private static SeedFacts.StructureFacts structureFacts(
            MinecraftServer server, DimensionConfig def, SpikeSampler.Base base,
            long seed, int radius) {
        if (def == null) {
            String why = "no dimension config, so its structure plan cannot be resolved";
            return absentStructures(why);
        }
        NoiseGroupPlan plan = NoiseGroupPlan.resolve(def);
        if (plan.isSuppressed()) {
            String why = "this dimension places no noise-managed structures ("
                    + plan.reason() + ")";
            return absentStructures(why);
        }

        // Vanilla's own StructurePlacementCalculator.create drops a SET whose
        // structures' valid biomes miss the biome source, BEFORE the mod's pool
        // builder ever sees it — so a live world's pool is built from a
        // prefiltered list, not the full registry. Skipping that step produces
        // a pool that is a strict superset of the live one: identical positions
        // (the field does not depend on the pool) but a different weighted pick,
        // since the extra structures compete for probability mass a live
        // world's pick would never have given them. The same rule is applied
        // here, then the wanted sets are re-admitted exactly as
        // DimensionStructures re-admits them.
        var setRegistry = server.getRegistryManager().get(RegistryKeys.STRUCTURE_SET);
        java.util.Set<Identifier> dimensionBiomes =
                NoisePoolBuilder.biomeIds(base.generator().getBiomeSource());
        java.util.Set<String> wanted = NoisePoolBuilder.wantedStructureIds(def);
        List<RegistryEntry<StructureSet>> sets = new ArrayList<>();
        for (var e : setRegistry.getIndexedEntries()) {
            if (survivesVanillaPrefilter(e, dimensionBiomes, wanted)) {
                sets.add(e);
            }
        }
        java.util.Set<String> exclude = new java.util.HashSet<>(NoisePoolBuilder.lowerSet(
                def.getStructures() == null ? null : def.getStructures().exclude));
        exclude.addAll(NoisePoolBuilder.lowerSet(
                MultiverseConfig.getInstance().getSuppressedStructureSets()));

        NoisePoolBuilder.Result pools = NoisePoolBuilder.build(
                def, sets, base.generator().getBiomeSource(), plan, exclude, null, wanted);

        int radiusChunks = radius / 16;
        long dimensionSalt = DimensionStructures.saltOf(def.getName());

        Map<String, Integer> poolWeights = new TreeMap<>();
        Map<String, Integer> byGroup = new TreeMap<>();
        Map<String, Integer> byStructure = new TreeMap<>();
        Map<String, Double> nearest = new TreeMap<>();
        Map<String, List<long[]>> positionsByGroup = new TreeMap<>();
        List<long[]> allPositions = new ArrayList<>();
        List<long[]> hostilePositions = new ArrayList<>();
        int total = 0;

        for (var groupEntry : plan.groups().entrySet()) {
            String group = groupEntry.getKey();
            NoiseGroupPlan.Group settings = groupEntry.getValue();
            NoisePoolBuilder.Pool pool = pools.pools().get(group);
            if (pool == null || pool.entries().isEmpty()) {
                continue;
            }
            long noiseSeed = seed ^ dimensionSalt ^ DimensionStructures.saltOf(group);
            NoiseStructurePlacement placement = new NoiseStructurePlacement(
                    group, noiseSeed, settings.profile(), settings.exclusion(),
                    settings.radial(), radiusChunks, 0, 0, settings.clearSpawnChunks());

            List<StructurePick.PoolEntry> pickPool = new ArrayList<>();
            for (var weighted : pool.entries()) {
                weighted.structure().getKey().ifPresent(k -> pickPool.add(
                        new StructurePick.PoolEntry(k.getValue().toString(), weighted.weight())));
            }
            for (StructurePick.PoolEntry pe : pickPool) {
                poolWeights.merge(pe.structureId(), pe.weight(), Integer::sum);
            }
            List<StructurePick.PoolEntry> sorted = StructurePick.sortedPool(pickPool);

            int count = 0;
            List<long[]> groupPositions = new ArrayList<>();
            positionsByGroup.put(group, groupPositions);
            for (ChunkPos pos : placement.index().positions()) {
                String assigned = StructurePick.assignedStructure(
                        noiseSeed, pos.x, pos.z, sorted);
                count++;
                total++;
                groupPositions.add(new long[] {pos.x, pos.z});
                allPositions.add(new long[] {pos.x, pos.z});
                if (HOSTILE_GROUPS.contains(group)) {
                    hostilePositions.add(new long[] {pos.x, pos.z});
                }
                if (assigned != null) {
                    byStructure.merge(assigned, 1, Integer::sum);
                    double blocks = Math.hypot(pos.x * 16.0, pos.z * 16.0);
                    nearest.merge(assigned, blocks, Math::min);
                }
            }
            byGroup.put(group, count);
        }

        if (total == 0) {
            String why = "every enabled group produced an empty pool or an empty field";
            return new SeedFacts.StructureFacts(
                    Measured.of(poolWeights), Measured.of(byGroup), Measured.of(byStructure),
                    Measured.of(nearest), Measured.absent(why),
                    Measured.absent(why), Measured.absent(why), Measured.of(0));
        }

        return new SeedFacts.StructureFacts(
                Measured.of(poolWeights),
                Measured.of(byGroup),
                Measured.of(byStructure),
                Measured.of(nearest),
                clusteringByGroup(positionsByGroup, radiusChunks),
                clustering(allPositions, radiusChunks),
                hostilePositions.isEmpty()
                        ? Measured.absent("this dimension places no dungeons or endgame structures")
                        : Measured.of(nearestDistanceBlocks(hostilePositions)),
                Measured.of(total));
    }

    /**
     * Clark-Evans for each group on its own.
     *
     * <p>The pooled figure cannot answer whether a group forms pockets. Each
     * group is an independent point process — its own noise field, frequency and
     * exclusion radius — and superimposing several independent processes drives
     * the combined statistic toward that of a random scatter whatever the parts
     * look like.
     *
     * <p>A group with fewer than two placements contributes no entry rather than
     * a filler value — the map states what it could measure.
     */
    static Measured<Map<String, Double>> clusteringByGroup(
            Map<String, List<long[]>> positionsByGroup, int radiusChunks) {
        Map<String, Double> out = new TreeMap<>();
        for (Map.Entry<String, List<long[]>> e : positionsByGroup.entrySet()) {
            Measured<Double> c = clustering(e.getValue(), radiusChunks);
            if (c.isPresent()) {
                out.put(e.getKey(), c.orThrow());
            }
        }
        return out.isEmpty()
                ? Measured.absent("no group has two placements, so no group has a spacing "
                        + "to characterise")
                : Measured.of(out);
    }

    /**
     * Whether a set would survive vanilla's biome prefilter — at least one of
     * its structures can generate in one of this dimension's biomes — or is
     * re-admitted because it carries a wanted structure.
     */
    private static boolean survivesVanillaPrefilter(
            RegistryEntry<StructureSet> entry, java.util.Set<Identifier> dimensionBiomes,
            java.util.Set<String> wanted) {
        for (StructureSet.WeightedEntry weighted : entry.value().structures()) {
            String id = weighted.structure().getKey()
                    .map(k -> k.getValue().toString()).orElse(null);
            if (id != null && wanted.contains(id)) {
                return true;
            }
            if (intersectsBiomes(weighted.structure(), dimensionBiomes)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Vanilla's own prefilter test: does this structure list a biome the
     * source produces?
     *
     * <p>NOT {@code biomeAffinity > 0}, and the difference is the whole of a
     * parity failure. Affinity answers 1.0 for a structure with NO valid
     * biomes at all, on the reading "no predicate, so it generates anywhere" —
     * which is right for weighting a structure already in a pool. Vanilla's
     * filter is an {@code anyMatch} over the list, and an empty list matches
     * nothing, so it drops the set: a structure with no biome list of its own
     * survives affinity but fails this prefilter.
     */
    private static boolean intersectsBiomes(
            RegistryEntry<net.minecraft.world.gen.structure.Structure> structure,
            java.util.Set<Identifier> dimensionBiomes) {
        if (dimensionBiomes.isEmpty()) {
            return true;   // biome source undeterminable: filter nothing
        }
        try {
            for (RegistryEntry<net.minecraft.world.biome.Biome> biome
                    : structure.value().getValidBiomes()) {
                Identifier id = biome.getKey().map(k -> k.getValue()).orElse(null);
                if (id != null && dimensionBiomes.contains(id)) {
                    return true;
                }
            }
        } catch (Exception e) {
            return true;   // a broken structure is not ours to fail on
        }
        return false;
    }

    private static SeedFacts.StructureFacts absentStructures(String why) {
        return new SeedFacts.StructureFacts(
                Measured.absent(why), Measured.absent(why), Measured.absent(why),
                Measured.absent(why), Measured.absent(why), Measured.absent(why),
                Measured.absent(why), Measured.absent(why));
    }

    private static double nearestDistanceBlocks(List<long[]> positions) {
        double best = Double.MAX_VALUE;
        for (long[] p : positions) {
            best = Math.min(best, Math.hypot(p[0] * 16.0, p[1] * 16.0));
        }
        return best;
    }

    /**
     * Mean nearest-neighbour distance over the distance a uniform scatter of
     * the same count in the same area would give (Clark-Evans).
     *
     * <p>Below 1 means the placements sit in pockets — a PLACE a player finds
     * and explores. At or above 1 means an even spread — scenery. The radial
     * curve cannot answer this: a curve describes density against distance from
     * spawn, and both a clustered and an even layout can share one.
     *
     * <p>Every placement is measured; there is no subsample. Thinning a
     * clustered pattern removes members of each pocket, so its mean
     * nearest-neighbour distance rises toward the random expectation — a
     * systematic error toward "evenly spread", worst on the dimensions with the
     * most placements. The nearest-neighbour search is therefore bucketed into a
     * uniform grid rather than capped: expected linear in the number of
     * placements, and exact.
     */
    static Measured<Double> clustering(List<long[]> positions, int radiusChunks) {
        int n = positions.size();
        if (n < 2) {
            return Measured.absent(
                    "fewer than two placements, so there is no spacing to characterise");
        }
        double sum = 0.0;
        NearestNeighbours nn = new NearestNeighbours(positions);
        for (int i = 0; i < n; i++) {
            sum += Math.sqrt(nn.nearestSquared(i));
        }
        double observed = sum / n;
        double area = Math.PI * (double) radiusChunks * radiusChunks;
        if (area <= 0.0) {
            return Measured.absent("the playable radius is zero, so density is undefined");
        }
        double expected = 0.5 / Math.sqrt(n / area);
        if (expected <= 0.0) {
            return Measured.absent("the expected spacing for this density is not positive");
        }
        return Measured.of(observed / expected);
    }

    /**
     * Exact nearest-neighbour distances over a set of chunk positions, bucketed
     * into a uniform grid so the whole set can be measured rather than a
     * subsample of it.
     *
     * <p>Duplicates are kept and matched by index, so two groups placing in the
     * same chunk answer zero. Dropping one would report a spacing the world does
     * not have.
     */
    private static final class NearestNeighbours {

        private final long[] xs;
        private final long[] zs;
        private final int cell;
        private final long minX;
        private final long minZ;
        private final int cols;
        private final int rows;
        /** CSR: cellStart[c]..cellStart[c+1] indexes into {@link #byCell}. */
        private final int[] cellStart;
        private final int[] byCell;

        NearestNeighbours(List<long[]> positions) {
            int n = positions.size();
            this.xs = new long[n];
            this.zs = new long[n];
            long lowX = Long.MAX_VALUE;
            long lowZ = Long.MAX_VALUE;
            long highX = Long.MIN_VALUE;
            long highZ = Long.MIN_VALUE;
            for (int i = 0; i < n; i++) {
                long[] p = positions.get(i);
                xs[i] = p[0];
                zs[i] = p[1];
                lowX = Math.min(lowX, p[0]);
                lowZ = Math.min(lowZ, p[1]);
                highX = Math.max(highX, p[0]);
                highZ = Math.max(highZ, p[1]);
            }
            this.minX = lowX;
            this.minZ = lowZ;
            // Roughly one point per cell: the ring search then examines a
            // handful of cells per query whatever the density.
            double span = (double) (highX - lowX + 1) * (double) (highZ - lowZ + 1);
            this.cell = Math.max(1, (int) Math.ceil(Math.sqrt(span / n)));
            this.cols = (int) ((highX - lowX) / cell) + 1;
            this.rows = (int) ((highZ - lowZ) / cell) + 1;

            int[] counts = new int[cols * rows + 1];
            for (int i = 0; i < n; i++) {
                counts[cellOf(i) + 1]++;
            }
            for (int c = 0; c < cols * rows; c++) {
                counts[c + 1] += counts[c];
            }
            this.cellStart = counts;
            this.byCell = new int[n];
            int[] cursor = new int[cols * rows];
            for (int i = 0; i < n; i++) {
                int c = cellOf(i);
                byCell[cellStart[c] + cursor[c]++] = i;
            }
        }

        private int cellOf(int i) {
            int cx = (int) ((xs[i] - minX) / cell);
            int cz = (int) ((zs[i] - minZ) / cell);
            return cz * cols + cx;
        }

        /**
         * Squared distance from point {@code i} to the nearest other point, in
         * chunks. Exact: rings of cells are examined outward until every
         * unexamined cell is provably farther than the best found so far.
         */
        double nearestSquared(int i) {
            int cx = (int) ((xs[i] - minX) / cell);
            int cz = (int) ((zs[i] - minZ) / cell);
            double best = Double.MAX_VALUE;
            int maxRing = Math.max(cols, rows);
            for (int r = 0; r <= maxRing; r++) {
                for (int gz = cz - r; gz <= cz + r; gz++) {
                    if (gz < 0 || gz >= rows) {
                        continue;
                    }
                    boolean edgeRow = gz == cz - r || gz == cz + r;
                    for (int gx = cx - r; gx <= cx + r; gx++) {
                        if (gx < 0 || gx >= cols) {
                            continue;
                        }
                        // Interior cells belong to a smaller ring.
                        if (!edgeRow && gx != cx - r && gx != cx + r) {
                            continue;
                        }
                        int c = gz * cols + gx;
                        for (int k = cellStart[c]; k < cellStart[c + 1]; k++) {
                            int j = byCell[k];
                            if (j == i) {
                                continue;
                            }
                            double dx = xs[i] - xs[j];
                            double dz = zs[i] - zs[j];
                            double d = dx * dx + dz * dz;
                            if (d < best) {
                                best = d;
                            }
                        }
                    }
                }
                // A point in an unexamined cell sits at Chebyshev ring r+1 or
                // beyond, so no closer than r cells away.
                double bound = (double) r * cell;
                if (best <= bound * bound) {
                    return best;
                }
            }
            return best;
        }
    }

    // ------------------------------------------------------------ unmeasured

    private static SeedFacts unmeasurable(Identifier id, long seed, int radius,
                                          String fingerprint, String why) {
        return new SeedFacts(Artefacts.stackVersion(),
                id.toString(), seed, Instant.now().toString(), fingerprint, radius,
                new SeedFacts.SpawnFacts(Measured.absent(why), Measured.absent(why),
                        Measured.absent(why), Measured.absent(why), Measured.absent(why)),
                new SeedFacts.BiomeFacts(Measured.absent(why), Measured.absent(why),
                        Measured.absent(why), Measured.absent(why)),
                new SeedFacts.TerrainFacts(Measured.absent(why), Measured.absent(why),
                        Measured.absent(why), Measured.absent(why), Measured.absent(why)),
                absentStructures(why));
    }
}
