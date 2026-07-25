package com.customdimensions.portal;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.PortalDefinition;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.gen.feature.ConfiguredFeature;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Portal auras: themed environmental spread around portal pairs. The
 * DEFAULT (no config) is a derived bi-directional leak — at link time each
 * side's surroundings are sampled (terrain histogram, flora, log→tree
 * mapping, surface fluids) and the palettes leak through: the source side
 * slowly takes on the target's nature and vice versa. A portal.aura block
 * overrides any of it (palette/flora/trees/fluids/conversions/fireChance)
 * or switches it off.
 *
 * Engineering rules (mods/.ideas/portal-auras.md — learned the hard way):
 * the exclusion set (interior + frame ring) is never converted; passes run
 * from the world tick with a chunk-loaded guard and bounded work; every
 * write uses NOTIFY_LISTENERS | FORCE_STATE; budgets persist so restarts
 * resume rather than re-burn; fluids only form in depressions and count
 * double; feature-placement failures are silent no-ops.
 */
public final class PortalAuraManager {

    /** Terrain palette size cap (top-N histogram entries). */
    private static final int PALETTE_SIZE = 5;
    private static final int SAMPLE_RADIUS_H = 4;
    private static final int SAMPLE_RADIUS_V = 2;
    private static final double FLORA_CHANCE = 0.35;
    private static final double TREE_CHANCE = 0.04;
    private static final double FLUID_CHANCE = 0.03;
    /** Persist budget progress at most this often (ticks) — plus on exhaustion. */
    private static final int SAVE_EVERY_TICKS = 1200;

    /** Vanilla log/stem -> ConfiguredFeature id, for derived tree palettes. */
    private static final Map<String, String> LOG_TO_TREE = Map.ofEntries(
            Map.entry("minecraft:oak_log", "minecraft:oak"),
            Map.entry("minecraft:birch_log", "minecraft:birch"),
            Map.entry("minecraft:spruce_log", "minecraft:spruce"),
            Map.entry("minecraft:jungle_log", "minecraft:jungle_tree"),
            Map.entry("minecraft:acacia_log", "minecraft:acacia"),
            Map.entry("minecraft:dark_oak_log", "minecraft:dark_oak"),
            Map.entry("minecraft:cherry_log", "minecraft:cherry"),
            Map.entry("minecraft:mangrove_log", "minecraft:mangrove"),
            Map.entry("minecraft:crimson_stem", "minecraft:crimson_fungus"),
            Map.entry("minecraft:warped_stem", "minecraft:warped_fungus"));

    /** Small plants recognised for the derived flora palette (besides tags). */
    private static final Set<String> FLORA_IDS = Set.of(
            "minecraft:short_grass", "minecraft:tall_grass", "minecraft:fern",
            "minecraft:large_fern", "minecraft:moss_carpet", "minecraft:pink_petals",
            "minecraft:crimson_roots", "minecraft:warped_roots", "minecraft:nether_sprouts",
            "minecraft:crimson_fungus", "minecraft:warped_fungus", "minecraft:dead_bush");

    private PortalAuraManager() {
    }

    // === Link-time palette derivation =====================================

    /**
     * Called once per new portal link (arrival creation or first reuse),
     * with BOTH worlds loaded. Samples each side and stores the palettes:
     * the target's nature on the source zone (it leaks back through), the
     * source's nature on an aura site at the arrival (it leaks forward).
     * First link wins — anchor arrivals shared by many sources keep the
     * first sample (immutable snapshot, like every other zone field).
     */
    public static void onLink(ServerWorld sourceWorld, PortalHelper.PortalZone zone,
            ServerWorld targetWorld, Set<BlockPos> arrivalInterior) {
        PortalDefinition def = zone.definition;
        if (def == null || !def.isAuraEnabled()) {
            return;
        }
        PortalDefinition.AuraSettings settings = def.getAura();
        BlockPos sourceCentre = centreOf(zone.interior);
        BlockPos arrivalCentre = centreOf(arrivalInterior);

        boolean changed = false;
        if (zone.auraPalette == null && settings.affectsSource()) {
            // Source side takes on the TARGET's nature: explicit emission
            // palette when the config sets one, else sampled around the
            // arrival. An empty sample (void) means no derived aura.
            Sampled sampled = settings.palette != null
                    ? Sampled.explicit(settings)
                    : sample(targetWorld, arrivalCentre,
                            exclusionFor(arrivalInterior, zone.axis),
                            frameMaterialsOf(zone.definition));
            zone.auraPalette = sampled.terrain;
            zone.auraFlora = sampled.flora;
            zone.auraTrees = sampled.trees;
            zone.auraFluids = sampled.fluids;
            changed = true;
        }
        RegistryKey<net.minecraft.world.World> targetKey = targetWorld.getRegistryKey();
        BlockPos siteKey = minCorner(arrivalInterior);
        Map<BlockPos, PortalHelper.AuraSite> sites =
                PortalHelper.getAuraSites().computeIfAbsent(targetKey, k -> new HashMap<>());
        if (!sites.containsKey(siteKey) && settings.affectsTarget()) {
            // Target side takes on the SOURCE's nature. Base-world sources
            // have no portal config, so this side usually samples; a
            // chained dimension's own aura.palette overrides its emission.
            PortalDefinition.AuraSettings sourceEmission = emissionOverrideFor(sourceWorld);
            Sampled sampled = sourceEmission != null
                    ? Sampled.explicit(sourceEmission)
                    : sample(sourceWorld, sourceCentre,
                            exclusionFor(zone.interior, zone.axis),
                            frameMaterialsOf(zone.definition));
            PortalHelper.AuraSite site = new PortalHelper.AuraSite();
            site.setInterior(arrivalInterior);
            site.palette = sampled.terrain;
            site.flora = sampled.flora;
            site.trees = sampled.trees;
            site.fluids = sampled.fluids;
            site.settings = settings;
            sites.put(siteKey, site);
            changed = true;
        }
        if (changed) {
            PortalHelper.savePortalLinks();
        }
    }

    /**
     * The aura settings of the dimension a world belongs to, when that
     * config sets an explicit emission palette — null means "sample".
     */
    private static PortalDefinition.AuraSettings emissionOverrideFor(ServerWorld world) {
        com.customdimensions.config.DimensionConfig config =
                com.customdimensions.config.MultiverseConfig.getInstance()
                        .getDimension(world.getRegistryKey().getValue().getPath());
        if (config == null || config.getPortal() == null) {
            return null;
        }
        PortalDefinition def = config.toPortalDefinition();
        return def != null && def.getAura().palette != null ? def.getAura() : null;
    }

    // === Per-world tick ====================================================

    public static void tick(ServerWorld world) {
        RegistryKey<net.minecraft.world.World> worldKey = world.getRegistryKey();
        long time = world.getTime();
        boolean saveDue = time % SAVE_EVERY_TICKS == 0;
        boolean dirty = false;

        for (PortalHelper.PortalZone zone : new ArrayList<>(PortalHelper.getSourceZones(worldKey))) {
            PortalDefinition def = zone.definition;
            if (def == null || !def.isAuraEnabled() || zone.auraPalette == null) {
                continue;
            }
            PortalDefinition.AuraSettings s = def.getAura();
            if (!s.affectsSource() || time % s.getInterval() != 0) {
                continue;
            }
            int budget = s.getBudget();
            if (budget >= 0 && zone.auraBudgetSpent >= budget) {
                continue;
            }
            int spent = runPass(world, centreOf(zone.interior),
                    exclusionFor(zone.interior, zone.axis),
                    zone.auraPalette, zone.auraFlora, zone.auraTrees, zone.auraFluids,
                    s, budget < 0 ? Integer.MAX_VALUE : budget - zone.auraBudgetSpent);
            if (spent > 0) {
                zone.auraBudgetSpent += spent;
                dirty = true;
                if (budget >= 0 && zone.auraBudgetSpent >= budget) {
                    saveDue = true; // budget exhausted: persist immediately
                }
            }
        }

        Map<BlockPos, PortalHelper.AuraSite> sites = PortalHelper.getAuraSites().get(worldKey);
        if (sites != null) {
            for (PortalHelper.AuraSite site : sites.values()) {
                PortalDefinition.AuraSettings s =
                        site.settings != null ? site.settings : PortalDefinition.AuraSettings.DEFAULTS;
                Set<BlockPos> interior = site.interiorPositions();
                if (!s.affectsTarget() || time % s.getInterval() != 0 || interior.isEmpty()) {
                    continue;
                }
                int budget = s.getBudget();
                if (budget >= 0 && site.budgetSpent >= budget) {
                    continue;
                }
                int spent = runPass(world, centreOf(interior),
                        exclusionForArrival(world, interior),
                        site.palette, site.flora, site.trees, site.fluids,
                        s, budget < 0 ? Integer.MAX_VALUE : budget - site.budgetSpent);
                if (spent > 0) {
                    site.budgetSpent += spent;
                    dirty = true;
                    if (budget >= 0 && site.budgetSpent >= budget) {
                        saveDue = true;
                    }
                }
            }
        }

        if (dirty && saveDue) {
            PortalHelper.savePortalLinks();
        }
    }

    // === One aura pass =====================================================

    /** Returns budget units spent (fluids count double). */
    private static int runPass(ServerWorld world, BlockPos centre, Set<BlockPos> exclusion,
            List<String> palette, List<String> flora, List<String> trees, List<String> fluids,
            PortalDefinition.AuraSettings s, int remaining) {
        if (!world.getChunkManager().isChunkLoaded(centre.getX() >> 4, centre.getZ() >> 4)) {
            return 0; // never spread into (or from) unloaded terrain
        }
        int radius = s.getRadius();
        Random random = Random.create(centre.asLong() ^ world.getTime() * 0x9E3779B97F4A7C15L);
        int spent = 0;
        for (int i = 0; i < s.getBlocksPerPass() && spent < remaining; i++) {
            BlockPos pos = centre.add(
                    random.nextBetween(-radius, radius),
                    random.nextBetween(-4, 5),
                    random.nextBetween(-radius, radius));
            if (exclusion.contains(pos)
                    || !world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)
                    || PortalHelper.isRegisteredPortalPosition(world.getRegistryKey(), pos)) {
                continue;
            }
            spent += convertAt(world, pos, palette, flora, trees, fluids, s, random);
        }
        return spent;
    }

    /** One conversion attempt at one position; returns budget units spent. */
    private static int convertAt(ServerWorld world, BlockPos pos,
            List<String> palette, List<String> flora, List<String> trees, List<String> fluids,
            PortalDefinition.AuraSettings s, Random random) {
        BlockState state = world.getBlockState(pos);
        int flags = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;

        if (state.isAir()) {
            boolean solidBelow = world.getBlockState(pos.down()).isOpaqueFullCube(world, pos.down());
            if (!solidBelow) {
                return 0;
            }
            // Fire first (explicitly configured aggression), then fluids in
            // depressions, then the odd tree, then flora.
            if (s.getFireChance() > 0 && random.nextDouble() < s.getFireChance()) {
                world.setBlockState(pos, Blocks.FIRE.getDefaultState(), flags);
                return 1;
            }
            if (fluids != null && !fluids.isEmpty() && random.nextDouble() < FLUID_CHANCE
                    && isDepression(p -> world.getBlockState(p).isOpaqueFullCube(world, p), pos)) {
                Block fluid = blockOf(fluids.get(random.nextInt(fluids.size())));
                if (fluid != null) {
                    world.setBlockState(pos, fluid.getDefaultState(), flags);
                    return 2; // the grief vector counts double
                }
                return 0;
            }
            // Trees are planted ONLY from an explicit aura.trees config.
            //
            // Derived tree palettes are no longer produced (see sample()), but
            // records persisted before that change still carry them, and those
            // records outlive the code — a portal linked yesterday would go on
            // planting dark oaks forever. Gating at the point of use fixes
            // already-affected worlds on the next boot instead of needing a
            // migration, and costs one list check.
            // `s.trees` is the EXPLICIT config list; `trees` is whatever was
            // persisted, which for older records is a derived palette.
            List<String> explicitTrees = s.trees;
            if (explicitTrees != null && !explicitTrees.isEmpty()
                    && random.nextDouble() < TREE_CHANCE) {
                return generateTree(world, pos,
                        explicitTrees.get(random.nextInt(explicitTrees.size())), random) ? 1 : 0;
            }
            if (flora != null && !flora.isEmpty() && random.nextDouble() < FLORA_CHANCE) {
                Block plant = blockOf(flora.get(random.nextInt(flora.size())));
                if (plant != null && plant.getDefaultState().canPlaceAt(world, pos)) {
                    world.setBlockState(pos, plant.getDefaultState(), flags);
                    return 1;
                }
            }
            return 0;
        }

        // Never touch containers/machines, unbreakables, or portal blocks.
        if (world.getBlockEntity(pos) != null || state.isOf(Blocks.BEDROCK)
                || PortalHelper.isPortalBlock(state)) {
            return 0;
        }
        // Explicit conversions win (obsidian -> crying_obsidian, etc.).
        String conversion = resolveConversion(state, s.getConversions());
        if (conversion != null) {
            Block to = blockOf(conversion);
            if (to != null) {
                world.setBlockState(pos, to.getDefaultState(), flags);
                return 1;
            }
            return 0;
        }
        // Terrain leak: like-for-like-ish — exposed positions take the
        // palette's dominant (surface) block, buried ones a random member.
        // Conversion OUTPUTS are immune, or the leak would slowly eat the
        // explicit conversions (crying_obsidian -> obsidian on a re-hit).
        String stateId = Registries.BLOCK.getId(state.getBlock()).toString();
        if (palette != null && !palette.isEmpty()
                && state.isOpaqueFullCube(world, pos)
                && !palette.contains(stateId)
                && !s.getConversions().containsValue(stateId)) {
            boolean exposed = !world.getBlockState(pos.up()).isOpaqueFullCube(world, pos.up());
            String pick = exposed || palette.size() == 1
                    ? palette.get(0)
                    : palette.get(1 + random.nextInt(palette.size() - 1));
            Block to = blockOf(pick);
            if (to != null) {
                world.setBlockState(pos, to.getDefaultState(), flags);
                return 1;
            }
        }
        return 0;
    }

    private static boolean generateTree(ServerWorld world, BlockPos pos, String featureId, Random random) {
        Identifier id = Identifier.tryParse(featureId);
        if (id == null) {
            return false;
        }
        ConfiguredFeature<?, ?> feature = world.getRegistryManager()
                .get(RegistryKeys.CONFIGURED_FEATURE).get(id);
        if (feature == null) {
            return false;
        }
        // generate() returning false (invalid ground) is a silent no-op.
        return feature.generate(world, world.getChunkManager().getChunkGenerator(), random, pos);
    }

    // === Sampling ==========================================================

    /** A derived (or explicit) palette bundle. Lists may be empty, never null. */
    static final class Sampled {
        List<String> terrain = new ArrayList<>();
        List<String> flora = new ArrayList<>();
        List<String> trees = new ArrayList<>();
        List<String> fluids = new ArrayList<>();

        static Sampled explicit(PortalDefinition.AuraSettings s) {
            Sampled out = new Sampled();
            out.terrain = s.palette != null ? new ArrayList<>(s.palette) : out.terrain;
            out.flora = s.flora != null ? new ArrayList<>(s.flora) : out.flora;
            out.trees = s.trees != null ? new ArrayList<>(s.trees) : out.trees;
            out.fluids = s.fluids != null ? new ArrayList<>(s.fluids) : out.fluids;
            return out;
        }
    }

    /**
     * Sample the real loaded terrain around a portal: histogram of solid
     * blocks (top 5 = terrain palette), small plants, logs mapped to tree
     * features, still surface fluids. Registry surface rules aren't
     * queryable, so sampling what is genuinely there is the mod-proof way.
     */
    /**
     * Block ids the histogram must never pick up, however much of them is
     * standing around: the portal's own building materials.
     *
     * <p>Position-based exclusion only covers the frame RING. A player who
     * builds their portal into a wall, a tower or a plinth — which is most
     * portals anyone bothers to decorate — leaves plenty of the frame
     * material inside the 9x7x9 sample box, so it wins a slot in the terrain
     * histogram and the far side starts sprouting it. Reported in-game
     * 2026-07-25: a cherry_planks portal seeded cherry_planks across the
     * destination, which reads less like an ecological leak and more like a
     * joke. Obsidian or stone would have hidden the same bug.
     *
     * <p>Covers the accept forms AND the placement block, so a tag- or
     * colour-group-framed portal excludes what it is actually built from.
     * Plain ids only — a "#tag" accept form has no single block to exclude
     * and is skipped rather than guessed at.
     */
    private static Set<String> frameMaterialsOf(PortalDefinition definition) {
        if (definition == null) {
            return Set.of();
        }
        Set<String> ids = new java.util.LinkedHashSet<>();
        for (String form : definition.getFrameAccepts()) {
            if (form != null && !form.startsWith("#")) {
                ids.add(form);
            }
        }
        for (String part : definition.getFramePartAccepts().keySet()) {
            String place = definition.getPartPlaceBlock(part);
            if (place != null && !place.startsWith("#")) {
                ids.add(place);
            }
        }
        String place = definition.getFramePlaceBlock();
        if (place != null && !place.startsWith("#")) {
            ids.add(place);
        }
        String centre = definition.getCentreBlock();
        if (centre != null && !centre.startsWith("#")) {
            ids.add(centre);
        }
        return ids;
    }

    static Sampled sample(ServerWorld world, BlockPos centre, Set<BlockPos> exclusion) {
        return sample(world, centre, exclusion, Set.of());
    }

    static Sampled sample(ServerWorld world, BlockPos centre, Set<BlockPos> exclusion,
            Set<String> excludedIds) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Set<String> flora = new java.util.LinkedHashSet<>();
        // Derived trees are collected but NOT returned — see below.
        Set<String> derivedTrees = new java.util.LinkedHashSet<>();
        Set<String> fluids = new java.util.LinkedHashSet<>();
        // Ground-biased vertical window: a vertical portal's centre sits
        // 1-2 blocks above the terrain that actually characterises the
        // place — a symmetric band missed the ground entirely (found live
        // 2026-07-24: the histogram came back empty while eye-level logs
        // were caught).
        for (int dx = -SAMPLE_RADIUS_H; dx <= SAMPLE_RADIUS_H; dx++) {
            for (int dy = -(SAMPLE_RADIUS_V + 2); dy <= SAMPLE_RADIUS_V; dy++) {
                for (int dz = -SAMPLE_RADIUS_H; dz <= SAMPLE_RADIUS_H; dz++) {
                    BlockPos pos = centre.add(dx, dy, dz);
                    if (exclusion.contains(pos)
                            || !world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
                        continue;
                    }
                    BlockState state = world.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    String id = Registries.BLOCK.getId(state.getBlock()).toString();
                    if (excludedIds.contains(id)) {
                        // The portal's own material — see frameMaterialsOf.
                        continue;
                    }
                    if (state.isIn(BlockTags.LOGS)) {
                        String feature = LOG_TO_TREE.get(id);
                        if (feature != null) {
                            // DERIVED TREES ARE DISABLED — see the comment on
                            // the trees list below. Kept as a no-op rather
                            // than deleting the log->feature mapping, which is
                            // still what an explicit aura.trees config needs.
                            derivedTrees.add(feature);
                        }
                    } else if (isFlora(state, id)) {
                        flora.add(id);
                    } else if (!state.getFluidState().isEmpty()) {
                        if (state.getFluidState().isStill()) {
                            fluids.add(state.getFluidState().isIn(net.minecraft.registry.tag.FluidTags.LAVA)
                                    ? "minecraft:lava" : "minecraft:water");
                        }
                    } else if (state.isOpaqueFullCube(world, pos)
                            && !state.isOf(Blocks.BEDROCK)
                            && world.getBlockEntity(pos) == null) {
                        counts.merge(id, 1, Integer::sum);
                    }
                }
            }
        }
        Sampled out = new Sampled();
        out.terrain = topN(counts, PALETTE_SIZE);
        out.flora = new ArrayList<>(flora);
        // TREES ARE NOT DERIVED FROM SAMPLING.
        //
        // A sampled tree palette turns any portal near a forest into a
        // thicket: a dark oak is a 2x2 trunk with an enormous canopy, and at
        // TREE_CHANCE per pass against a 300 budget an 8-radius aura plants
        // roughly a dozen of them. Reported in game 2026-07-25 — "the beach is
        // now completely rammed with dark oak trees and I can barely move
        // around". The aura is meant to make somewhere feel touched by the
        // other side, not to wall it in, and trees are the one palette entry
        // whose footprint is orders of magnitude bigger than the block that
        // seeded it.
        //
        // Trees remain available as a deliberate, per-dimension choice via
        // an explicit `aura.trees` config (Sampled.explicit), which is the
        // path a dimension takes when someone has decided a forest IS the
        // effect. They are simply never inferred.
        out.trees = List.of();
        out.fluids = new ArrayList<>(fluids);
        return out;
    }

    private static boolean isFlora(BlockState state, String id) {
        return state.isIn(BlockTags.FLOWERS) || state.isIn(BlockTags.SAPLINGS) || FLORA_IDS.contains(id);
    }

    // === Pure helpers (unit-tested) =======================================

    /** Top-N histogram keys, by count desc then id (deterministic). */
    static List<String> topN(Map<String, Integer> counts, int n) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(n)
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * Puddle discipline: a fluid may only form where something solid sits
     * below AND at least three of the four horizontal neighbours enclose
     * it — never sheets down a slope.
     */
    static boolean isDepression(Predicate<BlockPos> isSolid, BlockPos pos) {
        if (!isSolid.test(pos.down())) {
            return false;
        }
        int enclosed = 0;
        for (net.minecraft.util.math.Direction dir : net.minecraft.util.math.Direction.Type.HORIZONTAL) {
            if (isSolid.test(pos.offset(dir))) {
                enclosed++;
            }
        }
        return enclosed >= 3;
    }

    /** First matching conversion target for a state, else null. */
    static String resolveConversion(BlockState state, Map<String, String> conversions) {
        if (conversions.isEmpty()) {
            return null;
        }
        String id = Registries.BLOCK.getId(state.getBlock()).toString();
        for (Map.Entry<String, String> entry : conversions.entrySet()) {
            String from = entry.getKey();
            if (from.startsWith("#")) {
                Identifier tagId = Identifier.tryParse(from.substring(1));
                if (tagId != null && state.isIn(TagKey.of(RegistryKeys.BLOCK, tagId))) {
                    return entry.getValue();
                }
            } else if (from.equals(id)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** Interior centre cell (bounding-box centre, snapped to a member). */
    static BlockPos centreOf(Set<BlockPos> interior) {
        return PortalShape.centreOf(interior);
    }

    /** Deterministic site key: the interior's minimum corner. */
    static BlockPos minCorner(Set<BlockPos> interior) {
        BlockPos min = null;
        for (BlockPos p : interior) {
            if (min == null || p.compareTo(min) < 0) {
                min = p;
            }
        }
        return min;
    }

    /** Interior + in-plane frame ring — the aura never eats its own portal. */
    static Set<BlockPos> exclusionFor(Set<BlockPos> interior, net.minecraft.util.math.Direction.Axis axis) {
        Set<BlockPos> out = new HashSet<>(interior);
        for (BlockPos p : interior) {
            for (net.minecraft.util.math.Direction dir : PortalHelper.planeDirections(axis)) {
                out.add(p.offset(dir));
            }
        }
        return out;
    }

    /** Arrival exclusion: interior + every neighbour (axis unknown, be generous). */
    private static Set<BlockPos> exclusionForArrival(ServerWorld world, Set<BlockPos> interior) {
        Set<BlockPos> out = new HashSet<>(interior);
        for (BlockPos p : interior) {
            for (net.minecraft.util.math.Direction dir : net.minecraft.util.math.Direction.values()) {
                out.add(p.offset(dir));
            }
        }
        return out;
    }

    private static Block blockOf(String id) {
        Identifier parsed = id != null ? Identifier.tryParse(id) : null;
        Block block = parsed != null && Registries.BLOCK.containsId(parsed) ? Registries.BLOCK.get(parsed) : null;
        return block != null && block != Blocks.AIR ? block : null;
    }
}
