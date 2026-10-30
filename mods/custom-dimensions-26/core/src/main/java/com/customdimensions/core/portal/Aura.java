package com.customdimensions.core.portal;

import com.customdimensions.core.BlockPos;
import com.customdimensions.core.Direction;
import com.customdimensions.core.config.PortalDefinition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Portal auras: each end of a link slowly takes on the far side's nature. At
 * link time the far side is sampled (top terrain blocks, plants, logs as tree
 * features, still fluids); passes then convert a few cells at a time around
 * the near end, within a per-pass and a lifetime budget.
 * <p>
 * Two gates stand before every write: a claimed chunk is never touched, and a
 * replacement needs the {@link AuraPolicy} of the dimension whose nature is
 * leaking. The opening and its surrounding cells are never candidates, and a
 * fluid forms only in a hollow and costs two units.
 */
public final class Aura {

    /** Terrain palette size: the top entries of the sampled histogram. */
    public static final int PALETTE_SIZE = 5;
    public static final int SAMPLE_RADIUS_H = 4;
    public static final int SAMPLE_RADIUS_V = 2;
    public static final double FLORA_CHANCE = 0.35;
    public static final double TREE_CHANCE = 0.04;
    public static final double FLUID_CHANCE = 0.03;
    /** Budget progress is written at most this often (ticks), and at once on exhaustion. */
    public static final int SAVE_EVERY_TICKS = 1200;
    public static final int FLUID_COST = 2;

    /** Vanilla log or stem to its {@code worldgen/feature} tree. */
    public static final Map<String, String> LOG_TO_TREE = Map.ofEntries(
            Map.entry("minecraft:oak_log", "minecraft:oak"),
            Map.entry("minecraft:birch_log", "minecraft:birch"),
            Map.entry("minecraft:spruce_log", "minecraft:spruce"),
            Map.entry("minecraft:jungle_log", "minecraft:jungle_tree"),
            Map.entry("minecraft:acacia_log", "minecraft:acacia"),
            Map.entry("minecraft:dark_oak_log", "minecraft:dark_oak"),
            Map.entry("minecraft:pale_oak_log", "minecraft:pale_oak"),
            Map.entry("minecraft:cherry_log", "minecraft:cherry"),
            Map.entry("minecraft:mangrove_log", "minecraft:mangrove"),
            Map.entry("minecraft:crimson_stem", "minecraft:crimson_fungus"),
            Map.entry("minecraft:warped_stem", "minecraft:warped_fungus"));

    /** Small plants counted as flora besides the flower and sapling tags. */
    public static final Set<String> FLORA_IDS = Set.of(
            "minecraft:short_grass", "minecraft:tall_grass", "minecraft:fern",
            "minecraft:large_fern", "minecraft:moss_carpet", "minecraft:pink_petals",
            "minecraft:crimson_roots", "minecraft:warped_roots", "minecraft:nether_sprouts",
            "minecraft:crimson_fungus", "minecraft:warped_fungus", "minecraft:dead_bush");

    private static final String FIRE = "minecraft:fire";

    private Aura() {
    }

    /** What one pass did: budget units spent and candidates a claim vetoed. */
    public record Pass(int spent, int claimed) {
        public static final Pass NONE = new Pass(0, 0);
    }

    // === Budgets ===========================================================

    /** Units left of a lifetime budget; {@code budget < 0} is endless. */
    public static int remaining(int budget, int spent) {
        return budget < 0 ? Integer.MAX_VALUE : Math.max(0, budget - spent);
    }

    public static boolean exhausted(int budget, int spent) {
        return budget >= 0 && spent >= budget;
    }

    /** A pass for these settings is due on this tick. */
    public static boolean due(long time, PortalDefinition.AuraSettings settings) {
        return time % settings.getInterval() == 0;
    }

    public static boolean saveDue(long time) {
        return time % SAVE_EVERY_TICKS == 0;
    }

    // === Geometry ==========================================================

    /**
     * The cells an aura never touches: the opening and every face neighbour of
     * it, which covers the frame ring on any axis and the platform under a
     * horizontal opening.
     */
    public static Set<BlockPos> exclusion(Set<BlockPos> interior) {
        Set<BlockPos> out = new HashSet<>(interior);
        for (BlockPos p : interior) {
            for (Direction dir : Direction.values()) {
                out.add(PortalGeometry.offset(p, dir));
            }
        }
        return out;
    }

    /** The opening's minimum corner in {@code BlockPos} order: an arrival site's key. */
    public static BlockPos minCorner(Set<BlockPos> interior) {
        BlockPos min = null;
        for (BlockPos p : interior) {
            if (min == null || p.compareTo(min) < 0) {
                min = p;
            }
        }
        return min;
    }

    /**
     * The portal's own building materials as plain ids, kept out of the
     * histogram: a portal built into a wall leaves frame material inside the
     * sample box that would otherwise seed the far side.
     */
    public static Set<String> frameMaterials(PortalDefinition definition) {
        Set<String> ids = new LinkedHashSet<>();
        if (definition == null) {
            return ids;
        }
        definition.getFrameAccepts().forEach(form -> addPlain(ids, form));
        definition.getFramePartAccepts().keySet().forEach(part -> addPlain(ids, definition.getPartPlaceBlock(part)));
        addPlain(ids, definition.getFramePlaceBlock());
        addPlain(ids, definition.getCentreBlock());
        return ids;
    }

    private static void addPlain(Set<String> ids, String form) {
        if (form != null && !form.startsWith("#")) {
            ids.add(form);
        }
    }

    // === Sampling ==========================================================

    /**
     * Samples the resident terrain around {@code centre}: a histogram of
     * solid blocks (its top entries are the terrain palette), small plants,
     * logs as their tree features and still fluids. The window leans down,
     * because a vertical opening's centre sits above the ground that
     * characterises the place. An empty terrain palette takes
     * {@code fallbackBlock} when there is one.
     */
    public static AuraState sample(AuraWorld world, BlockPos centre, Set<BlockPos> exclusion,
                                   Set<String> excludedIds, String fallbackBlock) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Set<String> flora = new LinkedHashSet<>();
        Set<String> trees = new LinkedHashSet<>();
        Set<String> fluids = new LinkedHashSet<>();
        for (int dx = -SAMPLE_RADIUS_H; dx <= SAMPLE_RADIUS_H; dx++) {
            for (int dy = -(SAMPLE_RADIUS_V + 2); dy <= SAMPLE_RADIUS_V; dy++) {
                for (int dz = -SAMPLE_RADIUS_H; dz <= SAMPLE_RADIUS_H; dz++) {
                    BlockPos pos = centre.add(dx, dy, dz);
                    if (exclusion.contains(pos) || !world.resident(pos) || world.isAir(pos)) {
                        continue;
                    }
                    String id = world.blockId(pos);
                    if (excludedIds.contains(id)) {
                        continue;
                    }
                    if (world.isLog(pos)) {
                        String tree = LOG_TO_TREE.get(id);
                        if (tree != null) {
                            trees.add(tree);
                        }
                    } else if (world.isFlora(pos) || FLORA_IDS.contains(id)) {
                        flora.add(id);
                    } else if (world.hasFluid(pos)) {
                        String still = world.stillFluid(pos);
                        if (still != null) {
                            fluids.add(still);
                        }
                    } else if (world.isSolid(pos) && !world.isFixed(pos) && !world.hasBlockEntity(pos)) {
                        counts.merge(id, 1, Integer::sum);
                    }
                }
            }
        }
        List<String> terrain = topN(counts, PALETTE_SIZE);
        if (terrain.isEmpty() && fallbackBlock != null && !fallbackBlock.isBlank()) {
            terrain = List.of(fallbackBlock);
        }
        return new AuraState(terrain, new ArrayList<>(flora), new ArrayList<>(trees), new ArrayList<>(fluids), 0);
    }

    /** Top-N histogram keys, by count descending then id. */
    public static List<String> topN(Map<String, Integer> counts, int n) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(n)
                .map(Map.Entry::getKey)
                .toList();
    }

    // === Passes ============================================================

    /**
     * One pass around {@code centre}: up to {@code blocksPerPass} candidate
     * cells, stopping once {@code remaining} units are spent. Does nothing
     * when the centre's chunk is not resident.
     */
    public static Pass pass(AuraWorld world, BlockPos centre, Set<BlockPos> exclusion, AuraState state,
                            PortalDefinition.AuraSettings settings, String subsume, int remaining, Random random) {
        if (remaining <= 0 || !world.resident(centre)) {
            return Pass.NONE;
        }
        int radius = settings.getRadius();
        int spent = 0;
        int claimed = 0;
        for (int i = 0; i < settings.getBlocksPerPass() && spent < remaining; i++) {
            BlockPos pos = centre.add(
                    between(random, -radius, radius),
                    between(random, -4, 5),
                    between(random, -radius, radius));
            if (exclusion.contains(pos) || !world.resident(pos)) {
                continue;
            }
            if (world.claimed(pos)) {
                claimed++;
                continue;
            }
            spent += convert(world, pos, state, settings, subsume, remaining - spent, random);
        }
        return new Pass(spent, claimed);
    }

    /** One attempt at one cell; returns the units spent. */
    static int convert(AuraWorld world, BlockPos pos, AuraState state, PortalDefinition.AuraSettings settings,
                       String subsume, int left, Random random) {
        if (world.isAir(pos)) {
            return fillAir(world, pos, state, settings, subsume, left, random);
        }
        if (world.hasBlockEntity(pos) || world.isFixed(pos)) {
            return 0;
        }
        // Everything below replaces a block, conversions included: the policy decides once for all of it.
        if (!AuraPolicy.allowsReplacement(subsume, world.isProtected(pos))) {
            return 0;
        }
        String id = world.blockId(pos);
        Map<String, String> conversions = settings.getConversions();
        String conversion = resolveConversion(id, tag -> world.inTag(pos, tag), conversions);
        if (conversion != null) {
            return world.set(pos, conversion) ? 1 : 0;
        }
        // Conversion outputs are immune, or a re-hit would undo them.
        List<String> terrain = state.terrain;
        if (terrain.isEmpty() || !world.isSolid(pos) || terrain.contains(id) || conversions.containsValue(id)) {
            return 0;
        }
        boolean exposed = !world.isSolid(pos.up());
        String pick = exposed || terrain.size() == 1 ? terrain.get(0) : terrain.get(1 + random.nextInt(terrain.size() - 1));
        return world.set(pos, pick) ? 1 : 0;
    }

    /** Fire, then a fluid in a hollow, then a configured tree, then a plant; only on solid ground. */
    private static int fillAir(AuraWorld world, BlockPos pos, AuraState state, PortalDefinition.AuraSettings settings,
                               String subsume, int left, Random random) {
        if (!world.isSolid(below(pos))) {
            return 0;
        }
        boolean hazards = AuraPolicy.allowsHazardousAdditions(subsume);
        if (hazards && settings.getFireChance() > 0 && random.nextDouble() < settings.getFireChance()) {
            return world.set(pos, FIRE) ? 1 : 0;
        }
        if (hazards && !state.fluids.isEmpty() && random.nextDouble() < FLUID_CHANCE) {
            if (left < FLUID_COST || !isDepression(world::isSolid, pos)) {
                return 0;
            }
            return world.set(pos, state.fluids.get(random.nextInt(state.fluids.size()))) ? FLUID_COST : 0;
        }
        // Trees come only from the configured aura.trees: a sampled tree palette turns a forest-side portal into a thicket.
        List<String> trees = settings.trees;
        if (trees != null && !trees.isEmpty() && random.nextDouble() < TREE_CHANCE) {
            return world.growFeature(pos, trees.get(random.nextInt(trees.size()))) ? 1 : 0;
        }
        if (!state.flora.isEmpty() && random.nextDouble() < FLORA_CHANCE) {
            String plant = state.flora.get(random.nextInt(state.flora.size()));
            return world.canPlace(pos, plant) && world.set(pos, plant) ? 1 : 0;
        }
        return 0;
    }

    /** A fluid forms only over something solid with at least three of four sides enclosed. */
    public static boolean isDepression(Predicate<BlockPos> isSolid, BlockPos pos) {
        if (!isSolid.test(below(pos))) {
            return false;
        }
        int enclosed = 0;
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            if (isSolid.test(PortalGeometry.offset(pos, dir))) {
                enclosed++;
            }
        }
        return enclosed >= 3;
    }

    /** The first conversion whose key names {@code blockId} or a tag it is in ({@code #ns:path}), else null. */
    public static String resolveConversion(String blockId, Predicate<String> inTag, Map<String, String> conversions) {
        for (Map.Entry<String, String> entry : conversions.entrySet()) {
            String from = entry.getKey();
            if (from.startsWith("#") ? inTag.test(from.substring(1)) : from.equals(blockId)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static BlockPos below(BlockPos pos) {
        return pos.add(0, -1, 0);
    }

    private static int between(Random random, int min, int max) {
        return min + random.nextInt(max - min + 1);
    }
}
