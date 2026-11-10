package com.customdimensions.server.level;

import com.customdimensions.compiler.datapack.BiomeLibrary;
import com.customdimensions.compiler.datapack.DatapackCompiler;
import com.customdimensions.server.mixin.ChunkGeneratorAccessor;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

/**
 * The biome library on a running server, over the fixture
 * {@code server/src/selftest/definitions/selftest_biome_library.json}: ashen
 * woodland, crystal hollows and plains in 16-block cells, with diamonds removed
 * and an ore added for the level. Checks the registry holds both library biomes
 * as compiled, that the level's ore edits reach plains and not them, and, over
 * 96x96 generated columns round 0,0, that both appear, the ashen woodland is
 * topped from its palette and carries its birch trunks, and the crystal hollows
 * hold amethyst clusters. Then, for every library biome, over its showcase
 * {@code selftest_biome_<id>} (the biome alone on overworld, Nether or End
 * terrain or over floating islands) and the 96x96 columns round its spawn,
 * which an End showcase puts on the outer islands: the biome is at the surface
 * of every column, its palette's top block tops at least half the land columns
 * where the palette has one (under a roof, the floor of the highest open
 * space), and each of its own features left a block only it, or only it and
 * the siblings named, places ({@link #ownBlock}); the ashen woodland's line also
 * counts its coal on and in the ground ({@link #ashenCoal}), and each water
 * biome's ({@link #WATER}) its lily pads and pool water ({@link #poolWater}). Each line
 * carries its counts.
 */
public final class BiomeLibrarySelfTest {

    static final String SLUG = "selftest_biome_library";
    static final String SHOWCASE = "selftest_biome_";
    /** Keys whose value is the block a feature is made of, in the order they are looked for. */
    static final List<String> MAIN_KEYS = List.of("trunk_provider", "to_place", "state", "state_provider", "ground_state", "provider", "block");
    /** Subtrees that name blocks a feature tests or replaces rather than places. */
    static final Set<String> NOT_PLACED = Set.of("placement", "predicate", "target", "can_place_on", "allowed_placement", "replaceable",
            "below_trunk_provider", "decorators", "can_be_placed_on", "can_be_placed_in", "can_replace", "invalid_blocks",
            "accepted_neighbors", "allowed_search_condition", "allowed_tree_position", "can_grow_through", "cannot_place_on",
            "cannot_replace", "continue_through", "input_predicate", "location_predicate", "muddy_roots_in", "project_through",
            "replaceable_blocks", "root_replaceable", "target_condition", "valid_base_block", "valid_blocks");
    /** Realms whose features stand in caverns or under a roof: the nearest-spawn log reads every height, not only the surface. */
    static final Set<String> UNDERGROUND_REALMS = Set.of("caves", "nether");
    /** Keys whose subtree is what a feature grows on the blocks it lays, read before its main block for a block of its own. */
    static final Set<String> GROWTH_KEYS = Set.of("vegetation_feature");
    /** Blocks the level's terrain and surface rules lay down, which no feature can claim. */
    static final Set<String> TERRAIN = Set.of("minecraft:stone", "minecraft:deepslate", "minecraft:netherrack", "minecraft:end_stone",
            "minecraft:bedrock", "minecraft:grass_block", "minecraft:dirt", "minecraft:sand", "minecraft:sandstone", "minecraft:gravel",
            "minecraft:water", "minecraft:lava");
    /** Feature types that place blocks named by no field of their configuration. */
    static final Map<String, List<String>> IMPLIED = Map.of(
            "minecraft:chorus_plant", List.of("minecraft:chorus_plant", "minecraft:chorus_flower"),
            "minecraft:end_gateway", List.of("minecraft:end_gateway", "minecraft:bedrock"),
            "minecraft:end_island", List.of("minecraft:end_stone"),
            "minecraft:freeze_top_layer", List.of("minecraft:snow", "minecraft:ice"),
            "minecraft:monster_room", List.of("minecraft:cobblestone", "minecraft:mossy_cobblestone", "minecraft:spawner", "minecraft:chest"));
    static final String ASHEN = "ashen_woodland";
    static final String CRYSTAL = "crystal_hollows";
    /** Biomes whose pools, marsh water or river channel must hold unbroken water, with lily pads only on it. */
    static final Set<String> WATER = Set.of("cypress_wetland", "desert_oasis", "orchid_marsh", "muddy_river", "megalake", "sulphur_springs", "fungal_fen", "neon_oasis", "bayou");
    static final int LOAD_CAP_TICKS = 6000;
    /** Share of ashen land columns topped with the palette's top block; gravel and podzol disks take most of the rest. */
    static final double TOP_SHARE = 0.5;
    /** Share topped with any of its ground blocks: the palette's, the disks', the dirt trunks stand on and its coal. */
    static final double GROUND_SHARE = 0.9;
    static final List<Block> GROUND = List.of(Blocks.COARSE_DIRT, Blocks.GRAVEL, Blocks.PODZOL, Blocks.DIRT, Blocks.COAL_BLOCK);
    /** How deep under the ashen ground its coal seams and charred horizons are counted. */
    static final int SEAM_DEPTH = 6;
    static final List<Block> CRYSTALS = List.of(Blocks.AMETHYST_CLUSTER, Blocks.LARGE_AMETHYST_BUD,
            Blocks.MEDIUM_AMETHYST_BUD, Blocks.SMALL_AMETHYST_BUD);

    private final MinecraftServer server;
    private final LevelManager manager;
    private final String namespace;
    private final List<String> lines = new ArrayList<>();
    private ServerLevel level;
    private List<ChunkPos> waiting;
    private CompletableFuture<List<LevelChunk>> loaded;
    private int ticks;

    private BiomeLibrarySelfTest(MinecraftServer server, LevelManager manager) {
        this.server = server;
        this.manager = manager;
        this.namespace = LevelIndex.definitionsDir().map(PackInstaller::namespace).orElse(LevelIndex.DEFAULT_NAMESPACE);
    }

    public static CompletableFuture<List<String>> run(MinecraftServer server) {
        LevelManager manager = LevelManager.get().orElse(null);
        if (manager == null) return CompletableFuture.completedFuture(List.of("FAIL biomes: the level manager did not start"));
        BiomeLibrarySelfTest test = new BiomeLibrarySelfTest(server, manager);
        ServerTickEvents.END_SERVER_TICK.register(s -> test.tick());
        return test.run();
    }

    private CompletableFuture<List<String>> run() {
        int pause = holdAwake();
        lines.add(line("biomes registry", this::registry));
        return manager.activate(SLUG).thenApply(LevelManager.Activation::level)
                .thenCompose(l -> {
                    level = l;
                    lines.add(line("biomes runtime edits (" + SLUG + ")", this::runtimeEdits));
                    return load(area(ChunkPos.ZERO));
                })
                .thenAccept(chunks -> {
                    lines.add(line("biomes present (" + SLUG + ")", () -> present(chunks)));
                    lines.add(line("biomes ashen woodland surface (" + SLUG + ")", () -> ashenSurface(chunks)));
                    lines.add(line("biomes ashen woodland trunks (" + SLUG + ")", () -> ashenTrunks(chunks)));
                    lines.add(line("biomes crystal hollows clusters (" + SLUG + ")", () -> crystals(chunks)));
                })
                .handle((v, error) -> {
                    if (error != null) lines.add("FAIL biomes: " + cause(error));
                    if (level != null) area(ChunkPos.ZERO).forEach(c -> level.setChunkForced(c.x(), c.z(), false));
                    return null;
                })
                .thenCompose(v -> manager.deactivate(SLUG).handle((t, e) -> null))
                .thenCompose(v -> showcases())
                .thenApply(v -> {
                    restorePause(pause);
                    return List.copyOf(lines);
                });
    }

    private interface Check {
        String run();
    }

    private static String line(String name, Check check) {
        try {
            return check.run().replaceFirst("^(PASS|FAIL) ", "$1 " + name + ": ");
        } catch (RuntimeException e) {
            return "FAIL " + name + ": " + e;
        }
    }

    // ---- checks ---------------------------------------------------------------------------------

    /** Both library biomes are in the registry with the compiled file's attributes, climate fields and features, none stripped. */
    private String registry() {
        Registry<Biome> biomes = server.registryAccess().lookupOrThrow(Registries.BIOME);
        Map<String, JsonObject> files = BiomeLibrary.shipped().files(namespace);
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        List<String> found = new ArrayList<>();
        for (String id : BiomeLibrary.shipped().entries().keySet()) {
            String full = namespace + ":" + id;
            Holder<Biome> holder = biomes.get(ResourceKey.create(Registries.BIOME, Identifier.parse(full)))
                    .orElseThrow(() -> new AssertionError(full + " is not in the biome registry"));
            JsonObject expected = files.get(DatapackCompiler.dataPath(full, "worldgen/biome"));
            JsonObject actual = Biome.DIRECT_CODEC.encodeStart(ops, holder.value()).getOrThrow().getAsJsonObject();
            Set<String> wantKeys = expected.getAsJsonObject("attributes").keySet();
            Set<String> haveKeys = actual.getAsJsonObject("attributes").keySet();
            if (!haveKeys.equals(wantKeys)) return "FAIL " + full + " attributes " + haveKeys + ", library " + wantKeys;
            for (String key : List.of("temperature", "downfall")) {
                if (expected.get(key).getAsFloat() != actual.get(key).getAsFloat()) {
                    return "FAIL " + full + " " + key + " " + actual.get(key) + ", library " + expected.get(key);
                }
            }
            if (expected.get("has_precipitation").getAsBoolean() != actual.get("has_precipitation").getAsBoolean()) {
                return "FAIL " + full + " has_precipitation " + actual.get("has_precipitation");
            }
            JsonArray features = featureIds(holder.value().getGenerationSettings());
            if (!features.equals(expected.getAsJsonArray("features"))) {
                return "FAIL " + full + " features " + features + ", library " + expected.getAsJsonArray("features");
            }
            String fog = expected.getAsJsonObject("attributes").get("minecraft:visual/fog_color").getAsString();
            found.add(full + " (" + haveKeys.size() + " attributes, fog " + fog + ", " + count(features) + " placed features in "
                    + features.size() + " steps)");
        }
        return "PASS " + found.size() + " library biomes: " + String.join("; ", found) + "; the firewall stripped nothing";
    }

    /** The level's generator lists plains without diamonds and with the added ore; each library biome keeps its own list. */
    private String runtimeEdits() {
        String levelId = namespace + ":" + SLUG;
        WorldgenRuntime.Spec spec = WorldgenRuntime.spec(levelId).orElseThrow(() -> new AssertionError("no runtime spec for " + levelId));
        Function<Holder<Biome>, BiomeGenerationSettings> getter =
                ((ChunkGeneratorAccessor) (Object) level.getChunkSource().getGenerator()).customdimensions$generationSettingsGetter();
        Registry<Biome> biomes = server.registryAccess().lookupOrThrow(Registries.BIOME);
        Holder<Biome> plains = biomes.get(ResourceKey.create(Registries.BIOME, Identifier.parse("minecraft:plains"))).orElseThrow();
        String plainsList = featureIds(getter.apply(plains)).toString();
        boolean plainsEdited = !plainsList.contains("\"minecraft:ore_diamond\"") && spec.addUndergroundOres().stream().allMatch(plainsList::contains);
        List<String> kept = new ArrayList<>();
        boolean intact = true;
        for (String id : List.of(ASHEN, CRYSTAL)) {
            Holder<Biome> holder = biomes.get(ResourceKey.create(Registries.BIOME, Identifier.parse(namespace + ":" + id))).orElseThrow();
            JsonArray seen = featureIds(getter.apply(holder));
            boolean same = seen.equals(featureIds(holder.value().getGenerationSettings()));
            boolean diamonds = seen.toString().contains("\"minecraft:ore_diamond\"");
            intact &= same && diamonds;
            kept.add(id + " " + count(seen) + " features, diamonds " + (diamonds ? "kept" : "gone") + (same ? "" : ", list changed"));
        }
        String counts = "removes " + spec.removeFeatures().size() + " and adds " + spec.addUndergroundOres().size()
                + "; plains " + (plainsEdited ? "edited" : "not edited: " + plainsList) + "; " + String.join("; ", kept);
        return plainsEdited && intact ? "PASS " + counts : "FAIL " + counts;
    }

    private String present(List<LevelChunk> chunks) {
        Map<String, Integer> columns = new TreeMap<>();
        forColumns(chunks, (chunk, x, z, biome) -> columns.merge(biome, 1, Integer::sum));
        boolean both = columns.containsKey(full(ASHEN)) && columns.containsKey(full(CRYSTAL));
        return (both ? "PASS " : "FAIL ") + "columns by biome at the surface: " + columns;
    }

    /** The ashen woodland's land columns are topped with its palette's top block, or the ground its disks and trunks leave. */
    private String ashenSurface(List<LevelChunk> chunks) {
        Block top = BuiltInRegistries.BLOCK.getValue(Identifier.parse(
                BiomeLibrary.shipped().entries().get(ASHEN).palette().blocks().get("top")));
        Map<String, Integer> tops = new TreeMap<>();
        int[] counts = new int[3];
        forColumns(chunks, (chunk, x, z, biome) -> {
            if (!biome.equals(full(ASHEN))) return;
            BlockPos pos = land(chunk, x, z);
            if (pos == null) return;
            BlockState s = chunk.getBlockState(pos);
            counts[0]++;
            if (s.is(top)) counts[1]++;
            if (GROUND.stream().anyMatch(s::is)) counts[2]++;
            tops.merge(BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath(), 1, Integer::sum);
        });
        double share = share(counts[1], counts[0]);
        double ground = share(counts[2], counts[0]);
        String text = counts[0] + " ashen land columns: " + BuiltInRegistries.BLOCK.getKey(top).getPath() + " tops " + counts[1]
                + " (" + pct(share) + "), ground blocks " + counts[2] + " (" + pct(ground) + "); want " + pct(TOP_SHARE) + " and "
                + pct(GROUND_SHARE) + "; tops " + tops;
        return counts[0] > 0 && share >= TOP_SHARE && ground >= GROUND_SHARE ? "PASS " + text : "FAIL " + text;
    }

    /** Upright birch and stripped birch logs over the ashen woodland's columns: its trunk feature placed them. */
    private String ashenTrunks(List<LevelChunk> chunks) {
        long[] logs = new long[4];
        forColumns(chunks, (chunk, x, z, biome) -> {
            if (!biome.equals(full(ASHEN))) return;
            BlockPos pos = land(chunk, x, z);
            if (pos == null) return;
            for (int y = pos.getY() + 1; y <= pos.getY() + 16; y++) {
                BlockState s = chunk.getBlockState(pos.atY(y));
                boolean upright = s.hasProperty(BlockStateProperties.AXIS) && s.getValue(BlockStateProperties.AXIS) == Direction.Axis.Y;
                if (s.is(Blocks.BIRCH_LOG) && upright) logs[0]++;
                else if (s.is(Blocks.STRIPPED_BIRCH_LOG) && upright) logs[1]++;
                else if (s.is(Blocks.STRIPPED_BIRCH_LOG)) logs[2]++;
                else if (s.is(Blocks.BIRCH_LEAVES)) logs[3]++;
            }
        });
        String text = "upright birch logs " + logs[0] + ", upright stripped birch logs " + logs[1] + ", fallen stripped logs "
                + logs[2] + ", birch leaves " + logs[3];
        return logs[0] > 0 && logs[1] > 0 ? "PASS " + text : "FAIL " + text;
    }

    /**
     * Amethyst clusters and buds, budding amethyst and calcite below the surface,
     * per column of each biome. Geodes and patches reach across 16-block cells, so
     * the neighbouring cells hold some; the crystal hollows must hold over twice as many.
     */
    private String crystals(List<LevelChunk> chunks) {
        Map<String, long[]> by = new TreeMap<>();
        forColumns(chunks, (chunk, x, z, biome) -> {
            long[] c = by.computeIfAbsent(biome, k -> new long[4]);
            c[0]++;
            int surface = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z);
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int y = chunk.getMinY(); y < surface - 4; y++) {
                pos.set(chunk.getPos().getMinBlockX() + x, y, chunk.getPos().getMinBlockZ() + z);
                BlockState s = chunk.getBlockState(pos);
                if (CRYSTALS.stream().anyMatch(s::is)) c[1]++;
                else if (s.is(Blocks.BUDDING_AMETHYST)) c[2]++;
                else if (s.is(Blocks.CALCITE)) c[3]++;
            }
        });
        List<String> parts = new ArrayList<>();
        by.forEach((biome, c) -> parts.add(biome + ": " + c[0] + " columns, " + c[1] + " clusters and buds, " + c[2]
                + " budding, " + c[3] + " calcite"));
        long[] crystal = by.getOrDefault(full(CRYSTAL), new long[4]);
        double density = share(crystal[1], crystal[0]);
        boolean densest = by.entrySet().stream().filter(e -> !e.getKey().equals(full(CRYSTAL)))
                .allMatch(e -> density > 2 * share(e.getValue()[1], e.getValue()[0]));
        boolean ok = crystal[1] > 0 && crystal[2] > 0 && densest;
        return (ok ? "PASS " : "FAIL ") + String.join("; ", parts)
                + "; want clusters and budding amethyst in the crystal hollows, its clusters per column over twice any other biome's";
    }

    // ---- showcases ------------------------------------------------------------------------------

    /** Each library biome's showcase level in turn, one at a time. */
    private CompletableFuture<Void> showcases() {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (String id : BiomeLibrary.shipped().entries().keySet()) {
            chain = chain.thenCompose(v -> showcase(id));
        }
        return chain;
    }

    private CompletableFuture<Void> showcase(String id) {
        String slug = SHOWCASE + id;
        String name = "biomes library " + id + " (" + slug + ")";
        level = null;
        BlockPos spawn = manager.spawn(slug).map(p -> new BlockPos(p.x(), p.y(), p.z())).orElse(BlockPos.ZERO);
        ChunkPos centre = ChunkPos.containing(spawn);
        return manager.activate(slug).thenApply(LevelManager.Activation::level)
                .thenCompose(l -> {
                    level = l;
                    return load(area(centre));
                })
                .thenAccept(chunks -> lines.add(line(name, () -> showcaseChecks(id, chunks, spawn))))
                .thenCompose(v -> !BASIN_SHOWCASES.contains(id) ? CompletableFuture.<Void>completedFuture(null)
                        : load(basinArea(centre)).thenAccept(chunks -> lines.add(line(name + " basins", () -> basinChecks(chunks, spawn))))
                        .whenComplete((v2, e) -> basinArea(centre).forEach(c -> level.setChunkForced(c.x(), c.z(), false)))
                        .thenCompose(v2 -> gapAt == null ? CompletableFuture.<Void>completedFuture(null) : load(gapArea(gapAt))
                                .thenCompose(chunks -> after(GAP_FLOW_TICKS).thenApply(t -> chunks))
                                .thenAccept(chunks -> lines.add(line(name + " rim gap", () -> gapFall(chunks, gapAt))))
                                .whenComplete((v3, e) -> gapArea(gapAt).forEach(c -> level.setChunkForced(c.x(), c.z(), false)))))
                .handle((v, error) -> {
                    if (error != null) lines.add("FAIL " + name + ": " + cause(error));
                    if (level != null) area(centre).forEach(c -> level.setChunkForced(c.x(), c.z(), false));
                    return null;
                })
                .thenCompose(v -> manager.deactivate(slug).handle((t, e) -> null));
    }

    /** The biome tops every column, its palette's top block at least half the land, and each own feature a block only it places. */
    private String showcaseChecks(String id, List<LevelChunk> chunks, BlockPos spawn) {
        BiomeLibrary.Entry entry = BiomeLibrary.shipped().entries().get(id);
        List<String> failures = new ArrayList<>();
        int[] surface = new int[2];
        forColumns(chunks, (chunk, x, z, biome) -> {
            surface[0]++;
            if (biome.equals(full(id))) surface[1]++;
        });
        if (surface[1] != surface[0]) failures.add("surface");
        String text = "surface " + surface[1] + "/" + surface[0] + " columns";

        String topId = entry.palette().blocks().get("top");
        if (topId != null) {
            Block top = BuiltInRegistries.BLOCK.getValue(Identifier.parse(topId));
            int[] counts = new int[2];
            Map<String, Integer> tops = new TreeMap<>();
            forColumns(chunks, (chunk, x, z, biome) -> {
                BlockPos pos = land(chunk, x, z);
                if (pos == null) return;
                counts[0]++;
                BlockState state = chunk.getBlockState(pos);
                if (state.is(top)) counts[1]++;
                tops.merge(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath(), 1, Integer::sum);
            });
            double share = share(counts[1], counts[0]);
            if (counts[0] == 0 || share < TOP_SHARE) failures.add("palette top");
            tops.values().removeIf(n -> n < counts[0] / 100);
            text += "; palette top " + Identifier.parse(topId).getPath() + " " + counts[1] + "/" + counts[0] + " land columns (" + pct(share)
                    + ", want " + pct(TOP_SHARE) + "; tops over 1% " + tops + ")";
        }

        Map<String, Own> owns = new LinkedHashMap<>();
        for (String feature : entry.features().keySet()) owns.put(feature, ownBlock(entry, feature));
        Map<Block, Long> found = new LinkedHashMap<>();
        for (Own own : owns.values()) {
            if (own.block() != null) found.put(BuiltInRegistries.BLOCK.getValue(Identifier.parse(own.block())), 0L);
        }
        Map<Block, BlockPos> nearest = new LinkedHashMap<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        boolean underground = UNDERGROUND_REALMS.contains(entry.realm());
        forColumns(chunks, (chunk, x, z, biome) -> {
            int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            int floor = underground ? chunk.getMinY() : chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) - 2;
            for (int y = chunk.getMinY(); y <= top; y++) {
                pos.set(chunk.getPos().getMinBlockX() + x, y, chunk.getPos().getMinBlockZ() + z);
                Block block = chunk.getBlockState(pos).getBlock();
                Long n = found.get(block);
                if (n == null) continue;
                found.put(block, n + 1);
                BlockPos best = nearest.get(block);
                if (y >= floor && standsClear(chunk, pos) && (best == null || pos.distSqr(spawn.atY(y)) < best.distSqr(spawn.atY(best.getY())))) {
                    nearest.put(block, pos.immutable());
                }
            }
        });
        nearest.forEach((block, at) -> com.customdimensions.server.CustomDimensions.LOG.info("biomes showcase {}: {} tops a standing column nearest spawn at {} {} {}",
                id, BuiltInRegistries.BLOCK.getKey(block).getPath(), at.getX(), at.getY(), at.getZ()));
        List<String> parts = new ArrayList<>();
        owns.forEach((feature, own) -> {
            if (own.block() == null) {
                parts.add(feature + " places no block of its own (" + own.shared() + "), not counted");
                return;
            }
            long n = found.get(BuiltInRegistries.BLOCK.getValue(Identifier.parse(own.block())));
            if (n == 0) failures.add(feature);
            parts.add(feature + " " + Identifier.parse(own.block()).getPath() + (own.with().isEmpty() ? "" : " (with " + String.join(", ", own.with()) + ")") + " " + n);
        });
        text += "; features (a block only it, or it and the siblings named, places; count over the columns): " + String.join(", ", parts);
        if (id.equals(ASHEN)) text += "; " + ashenCoal(chunks, failures);
        if (WATER.contains(id)) text += "; " + poolWater(chunks, failures);
        return failures.isEmpty() ? "PASS " + text : "FAIL " + text + "; failed: " + String.join(", ", failures);
    }

    /**
     * Coal blocks on or above the ashen ground (its charred trunks, stubs and coal logs), and coal
     * blocks and coal ore in the {@link #SEAM_DEPTH} blocks under it (its seams and charred horizons).
     * The ground is a column's highest block of its ground other than coal.
     */
    private String ashenCoal(List<LevelChunk> chunks, List<String> failures) {
        long[] n = new long[3];
        forColumns(chunks, (chunk, x, z, biome) -> {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(chunk.getPos().getMinBlockX() + x, 0, chunk.getPos().getMinBlockZ() + z);
            int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            for (; y > chunk.getMinY(); y--) {
                BlockState s = chunk.getBlockState(pos.setY(y));
                if (s.is(Blocks.COAL_BLOCK)) n[0]++;
                else if (GROUND.stream().anyMatch(s::is) || s.is(Blocks.STONE)) break;
            }
            for (int d = 1; d <= SEAM_DEPTH; d++) {
                BlockState s = chunk.getBlockState(pos.setY(y - d));
                if (s.is(Blocks.COAL_BLOCK)) n[1]++;
                else if (s.is(Blocks.COAL_ORE)) n[2]++;
            }
        });
        if (n[0] == 0) failures.add("coal on the ground");
        if (n[1] == 0) failures.add("coal in the ground");
        return "coal blocks on or above the ground " + n[0] + ", in the " + SEAM_DEPTH + " blocks under it coal blocks " + n[1]
                + " and coal ore " + n[2] + " (want coal blocks in both)";
    }

    /**
     * A water biome's water: lily pads with no water under them (want none); pool cells, the top
     * water of a column, with a side open to neither water nor a solid block (holes it spills
     * through, want none); dirt, grass, mud or clay at a pool's level with water on all four sides (islets); and the
     * share of pool columns whose water has no hole and runs unbroken down to a solid floor. Flowing
     * water is a stream, not a pool; a source in a cliff face just under the surface, open on one
     * side, is a spring, which pours into a lake below it or falls into the void.
     */
    private String poolWater(List<LevelChunk> chunks, List<String> failures) {
        long[] n = new long[9];
        forColumns(chunks, (chunk, x, z, biome) -> {
            var level = chunk.getLevel();
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(chunk.getPos().getMinBlockX() + x, 0, chunk.getPos().getMinBlockZ() + z);
            int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
            spring(level, pos.immutable().atY(y), n);
            BlockState s = Blocks.AIR.defaultBlockState();
            for (; y > chunk.getMinY(); y--) {
                s = chunk.getBlockState(pos.setY(y));
                if (s.is(Blocks.LILY_PAD)) {
                    n[0]++;
                    if (!chunk.getBlockState(pos.below()).getFluidState().is(FluidTags.WATER)) n[1]++;
                    continue;
                }
                if (s.isAir() || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || s.getFluidState().isEmpty() && !s.isSolid()) continue;
                break;
            }
            if (s.getFluidState().is(FluidTags.WATER) && !s.getFluidState().isSource()) {
                n[6]++;
                return;
            }
            if (!s.getFluidState().is(FluidTags.WATER)) {
                if (!s.is(BlockTags.DIRT) && !s.is(Blocks.CLAY)) return;
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    if (!level.getBlockState(pos.relative(d)).getFluidState().is(FluidTags.WATER)) return;
                }
                n[4]++;
                return;
            }
            n[2]++;
            boolean hole = false;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockState side = level.getBlockState(pos.relative(d));
                if (!side.getFluidState().is(FluidTags.WATER) && !side.isSolid()) hole = true;
            }
            if (hole) n[3]++;
            while (chunk.getBlockState(pos).getFluidState().is(FluidTags.WATER)) pos.move(Direction.DOWN);
            if (!hole && chunk.getBlockState(pos).isSolid()) n[5]++;
        });
        if (n[1] > 0) failures.add("lily pads off water");
        if (n[2] == 0) failures.add("no pool water");
        if (n[3] > 0) failures.add("holes");
        return "lily pads " + n[0] + ", with no water under them " + n[1] + " (want 0); pool columns " + n[2] + ", holes " + n[3] + " (want 0)"
                + ", islets " + n[4] + ", unbroken " + n[5] + " (" + pct(share(n[5], n[2])) + "); pools cover " + pct(share(n[2], chunks.size() * 256L))
                + " of the columns; stream columns " + n[6] + ", springs pouring into a lake " + n[7] + ", falling into the void " + n[8];
    }

    /** Columns on a side of the area round a basin showcase's spawn, and the span its largest lake must reach. */
    static final int BASIN_SIDE = 256;
    static final int LAKE_SPAN = 256;

    /** The chunks round a basin showcase's spawn, {@link #BASIN_SIDE} columns on a side. */
    private static List<ChunkPos> basinArea(ChunkPos centre) {
        List<ChunkPos> out = new ArrayList<>();
        int half = BASIN_SIDE / 32;
        for (int x = -half; x < half; x++) for (int z = -half; z < half; z++) out.add(new ChunkPos(centre.x() + x, centre.z() + z));
        return out;
    }

    /**
     * A basin showcase's lake over {@link #BASIN_SIDE} columns square, and its terrain's masses over
     * {@link #MASS_RADIUS} blocks round spawn. Pool columns are topped by still water; the
     * largest body joins them four-ways, its extent is the longer side it spans, and the
     * islands it joins are the land components of {@link #ISLAND_COLUMNS} or more, topped at or
     * above the water line (by ground, or by an island's own pond or spring), that touch it and
     * no void column (the rim touches the void). A void
     * column holds no block; a column whose water has nothing solid under it is open to the void,
     * and each eight-way cluster of them must touch a void column, a fall off the edge rather
     * than a leak through a floor. Springs are counted as {@link #poolWater} counts them. The
     * masses are the basin function's four-way components above {@link #RIM_OUTSIDE}, sampled
     * every {@link #MASS_STEP} blocks; a gap is an eight-way cluster of crest samples (basin
     * within {@link #CREST} of 0) whose floor lies under the water line, spanning the crest from
     * its outer half to its inner; each mass wholly inside the scan and {@link #LAKE_SPAN} or
     * more across, a lake rather than a pond, counts its gaps, every
     * mass wants one, the median 1 to 2 and none more than 3. Its islands are the eight-way
     * components of base heights over the water line inside its shore (basin 1 and over),
     * sampled every second census point; the median mass wants 3.
     */
    private String basinChecks(List<LevelChunk> chunks, BlockPos spawn) {
        var gen = level.getChunkSource().getGenerator();
        if (!(gen instanceof net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator noise)
                || !(((com.customdimensions.server.mixin.NoiseBasedChunkGeneratorAccessor) (Object) noise).customdimensions$globalFluidPicker().get()
                        instanceof BasinFluidPicker basins)) {
            return "FAIL the level's fluid rule fills no basins (liquids.basins did not reach the generator)";
        }
        int line = basins.level();
        int minX = chunks.stream().mapToInt(c -> c.getPos().getMinBlockX()).min().orElse(0);
        int minZ = chunks.stream().mapToInt(c -> c.getPos().getMinBlockZ()).min().orElse(0);
        int side = BASIN_SIDE;
        byte[] kind = new byte[side * side];
        long[] n = new long[9];
        int minY = level.getMinY();
        for (LevelChunk chunk : chunks) {
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                int bx = chunk.getPos().getMinBlockX() + x, bz = chunk.getPos().getMinBlockZ() + z;
                int i = (bx - minX) * side + (bz - minZ);
                BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(bx, 0, bz);
                int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                spring(level, pos.immutable().atY(y), n);
                BlockState s = Blocks.AIR.defaultBlockState();
                for (; y > minY; y--) {
                    s = chunk.getBlockState(pos.setY(y));
                    if (s.isAir() || s.is(Blocks.LILY_PAD) || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || s.getFluidState().isEmpty() && !s.isSolid()) continue;
                    break;
                }
                if (y <= minY) {
                    kind[i] = VOID;
                    continue;
                }
                int lowestWater = Integer.MAX_VALUE;
                for (int wy = y; wy > minY; wy--) {
                    BlockState w = chunk.getBlockState(pos.setY(wy));
                    if (w.getFluidState().is(FluidTags.WATER)) lowestWater = wy;
                }
                boolean groundBelow = true;
                if (lowestWater != Integer.MAX_VALUE) {
                    groundBelow = false;
                    for (int gy = lowestWater - 1; gy > minY; gy--) {
                        if (chunk.getBlockState(pos.setY(gy)).isSolid()) {
                            groundBelow = true;
                            break;
                        }
                    }
                }
                if (!groundBelow) kind[i] = OPEN_WATER;
                else if (s.getFluidState().is(FluidTags.WATER) && s.getFluidState().isSource() && y < line) kind[i] = POOL;
                else if (y >= line && (s.isSolid() || s.getFluidState().is(FluidTags.WATER))) kind[i] = LAND;
                else kind[i] = OTHER;
            }
        }
        long pools = 0;
        for (byte k : kind) if (k == POOL) pools++;
        double poolShare = share(pools, kind.length);
        int[] body = new int[kind.length];
        java.util.Arrays.fill(body, -1);
        int biggest = -1, biggestSize = 0, biggestExtent = 0, bodies = 0;
        for (int i = 0; i < kind.length; i++) {
            if (kind[i] != POOL || body[i] >= 0) continue;
            int[] box = {side, -1, side, -1};
            int size = flood(kind, body, i, side, POOL, bodies, false, box);
            int extent = Math.max(box[1] - box[0], box[3] - box[2]) + 1;
            if (size > biggestSize) {
                biggest = bodies;
                biggestSize = size;
                biggestExtent = extent;
            }
            bodies++;
        }
        int[] land = new int[kind.length];
        java.util.Arrays.fill(land, -1);
        int islands = 0, components = 0;
        for (int i = 0; i < kind.length; i++) {
            if (kind[i] != LAND || land[i] >= 0) continue;
            int size = flood(kind, land, i, side, LAND, components, false, new int[] {side, -1, side, -1});
            boolean touchesBody = false, touchesVoid = false;
            for (int j = 0; j < kind.length; j++) {
                if (land[j] != components) continue;
                int jx = j / side, jz = j % side;
                for (int[] d : NEIGHBOURS_8) {
                    int qx = jx + d[0], qz = jz + d[1];
                    if (qx < 0 || qz < 0 || qx >= side || qz >= side) continue;
                    int q = qx * side + qz;
                    if (kind[q] == VOID) touchesVoid = true;
                    if (body[q] == biggest && biggest >= 0) touchesBody = true;
                }
            }
            if (touchesBody && !touchesVoid && size >= ISLAND_COLUMNS) islands++;
            components++;
        }
        int[] open = new int[kind.length];
        java.util.Arrays.fill(open, -1);
        int falls = 0, leaks = 0;
        long openColumns = 0;
        for (int i = 0; i < kind.length; i++) {
            if (kind[i] != OPEN_WATER || open[i] >= 0) continue;
            openColumns += flood(kind, open, i, side, OPEN_WATER, falls + leaks, true, new int[] {side, -1, side, -1});
            int id = falls + leaks;
            boolean edge = false;
            for (int j = 0; j < kind.length && !edge; j++) {
                if (open[j] != id) continue;
                int jx = j / side, jz = j % side;
                for (int[] d : NEIGHBOURS_8) {
                    int qx = jx + d[0], qz = jz + d[1];
                    if (qx >= 0 && qz >= 0 && qx < side && qz < side && kind[qx * side + qz] == VOID) edge = true;
                }
            }
            if (edge) falls++;
            else leaks++;
        }
        int[] mass = masses(basins, spawn, line);
        gapAt = mass[0] == 0 && mass[5] == 0 && mass[6] == 0 ? null : new BlockPos(mass[5], line, mass[6]);
        List<String> failures = new ArrayList<>();
        if (poolShare < 0.6) failures.add("pool share");
        if (biggestExtent < LAKE_SPAN) failures.add("lake extent");
        if (islands < 3) failures.add("islands joined");
        if (leaks > 0) failures.add("leaks into the void");
        if (n[7] < 2) failures.add("pours");
        if (mass[0] == 0 || mass[2] < 1 || mass[3] > 2 || mass[4] > 3) failures.add("gaps per mass");
        if (mass[8] < 3) failures.add("islands per mass");
        String text = side + "x" + side + " columns round " + spawn.getX() + ", " + spawn.getZ() + ": pools cover " + pct(poolShare) + " (want 60%) in "
                + bodies + " bodies, the largest " + biggestSize + " columns spanning " + biggestExtent + " blocks (want " + LAKE_SPAN
                + ") and joining " + islands + " islands (land of " + ISLAND_COLUMNS + "+ columns at or above the water line y " + line
                + " touching it and not the void; want 3); water open to the void " + openColumns + " columns in " + falls
                + " falls off the edge and " + leaks + " leaks through a floor (want 0); springs pouring into a lake " + n[7]
                + " (want 2), falling into the void " + n[8] + "; masses within " + MASS_RADIUS + " blocks (every " + MASS_STEP + "): "
                + mass[0] + " whole of " + LAKE_SPAN + "+ blocks, rim gaps per mass min " + mass[2] + " median " + mass[3] + " max " + mass[4]
                + " (want every mass 1 or more, median 1 to 2, none over 3), " + mass[1] + " masses with 1 to 3; islands per mass (land over the water line inside the shore, every "
                + 2 * MASS_STEP + " blocks) min " + mass[7] + " median " + mass[8] + " max " + mass[9] + " (median wants 3); nearest gap " + mass[5] + ", " + mass[6];
        return failures.isEmpty() ? "PASS " + text : "FAIL " + text + "; failed: " + String.join(", ", failures);
    }

    /** The rim gap nearest a basin showcase's spawn, found by {@link #basinChecks}; null when it found none. */
    private BlockPos gapAt;

    /** Ticks the water at a rim gap is given to spill before it is counted: a fall runs a block every five. */
    static final int GAP_FLOW_TICKS = 400;
    private CompletableFuture<Void> delay;
    private int delayTicks;

    /** Completes on the server thread {@code ticks} ticks from now. */
    private CompletableFuture<Void> after(int ticks) {
        delayTicks = ticks;
        delay = new CompletableFuture<>();
        return delay;
    }

    /** The 4 by 4 chunks round a rim gap. */
    private static List<ChunkPos> gapArea(BlockPos gap) {
        ChunkPos c = ChunkPos.containing(gap);
        List<ChunkPos> out = new ArrayList<>();
        for (int x = -2; x < 2; x++) for (int z = -2; z < 2; z++) out.add(new ChunkPos(c.x() + x, c.z() + z));
        return out;
    }

    /**
     * Water spilling through a rim gap, {@link #GAP_FLOW_TICKS} ticks after its chunks load:
     * columns round the gap whose water has nothing solid under it, in eight-way clusters, and the lowest water block among them, which a fall
     * reaches as it runs down toward the bottom of the world.
     */
    private String gapFall(List<LevelChunk> chunks, BlockPos gap) {
        int minY = level.getMinY();
        java.util.Set<Long> open = new java.util.HashSet<>();
        int lowest = Integer.MAX_VALUE;
        BlockPos deepest = null;
        for (LevelChunk chunk : chunks) {
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(chunk.getPos().getMinBlockX() + x, 0, chunk.getPos().getMinBlockZ() + z);
                int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                int water = Integer.MAX_VALUE;
                for (int y = top; y > minY; y--) {
                    if (chunk.getBlockState(pos.setY(y)).getFluidState().is(FluidTags.WATER)) water = y;
                }
                if (water == Integer.MAX_VALUE) continue;
                boolean ground = false;
                for (int y = water - 1; y > minY && !ground; y--) ground = chunk.getBlockState(pos.setY(y)).isSolid();
                if (!ground) {
                    open.add(BlockPos.asLong(pos.getX(), 0, pos.getZ()));
                    if (water < lowest) {
                        lowest = water;
                        deepest = new BlockPos(pos.getX(), water, pos.getZ());
                    }
                }
            }
        }
        int clusters = 0;
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (long start : open) {
            if (!seen.add(start)) continue;
            clusters++;
            java.util.ArrayDeque<Long> stack = new java.util.ArrayDeque<>(List.of(start));
            while (!stack.isEmpty()) {
                BlockPos p = BlockPos.of(stack.pop());
                for (int[] d : NEIGHBOURS_8) {
                    long q = BlockPos.asLong(p.getX() + d[0], 0, p.getZ() + d[1]);
                    if (open.contains(q) && seen.add(q)) stack.push(q);
                }
            }
        }
        long[] fluid = new long[2];
        for (LevelChunk chunk : chunks) {
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = minY; y < level.getMaxY(); y++) {
                var f = chunk.getFluidState(chunk.getPos().getMinBlockX() + x, y, chunk.getPos().getMinBlockZ() + z);
                if (f.is(FluidTags.WATER)) fluid[f.isSource() ? 0 : 1]++;
            }
        }
        StringBuilder profile = new StringBuilder();
        for (int y = gap.getY() + 6; y >= gap.getY() - 8; y--) {
            profile.append(BuiltInRegistries.BLOCK.getKey(level.getBlockState(new BlockPos(gap.getX(), y, gap.getZ())).getBlock()).getPath().replace("minecraft:", ""))
                    .append(y == gap.getY() - 8 ? "" : " ");
        }
        var gen = level.getChunkSource().getGenerator();
        long scheduled = gen instanceof net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator noise
                && ((com.customdimensions.server.mixin.NoiseBasedChunkGeneratorAccessor) (Object) noise).customdimensions$globalFluidPicker().get()
                instanceof BasinFluidPicker b ? b.scheduledCount() : -1;
        String text = "source water " + fluid[0] + ", flowing " + fluid[1] + ", basin cells scheduled to flow so far " + scheduled
                + "; the gap column from y " + (gap.getY() + 6) + " down: " + profile + "; 64x64 columns round the gap at " + gap.getX() + ", " + gap.getZ() + ": " + open.size()
                + " columns of water with nothing under them in " + clusters + " falls (want 1 to 3), the lowest water "
                + (deepest == null ? "none" : "at " + deepest.getX() + ", " + deepest.getY() + ", " + deepest.getZ());
        return (clusters >= 1 && clusters <= 3 ? "PASS " : "FAIL ") + text;
    }

    static final Set<String> BASIN_SHOWCASES = Set.of("megalake");
    static final int ISLAND_COLUMNS = 16;
    static final int MASS_RADIUS = 2048;
    static final int MASS_STEP = 4;
    /** The basin value at a mass's outer lip, past which no rim stands: the lakes threshold, in the basin's units. */
    static final double RIM_OUTSIDE = -0.7;
    /** The rim's crest, from its outer to its inner shoulder, in the basin's units: -0.33 to 0.33. */
    static final double CREST = 0.33;
    private static final byte VOID = 1, POOL = 2, LAND = 3, OPEN_WATER = 4, OTHER = 5;
    private static final int[][] NEIGHBOURS_4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] NEIGHBOURS_8 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /** Labels the component of {@code want} cells holding {@code start} with {@code id}; its size, and its bounding box in {@code box}. */
    private static int flood(byte[] kind, int[] label, int start, int side, byte want, int id, boolean eight, int[] box) {
        java.util.ArrayDeque<Integer> stack = new java.util.ArrayDeque<>();
        stack.push(start);
        label[start] = id;
        int size = 0;
        while (!stack.isEmpty()) {
            int c = stack.pop();
            size++;
            int cx = c / side, cz = c % side;
            box[0] = Math.min(box[0], cx);
            box[1] = Math.max(box[1], cx);
            box[2] = Math.min(box[2], cz);
            box[3] = Math.max(box[3], cz);
            for (int[] d : eight ? NEIGHBOURS_8 : NEIGHBOURS_4) {
                int qx = cx + d[0], qz = cz + d[1];
                if (qx < 0 || qz < 0 || qx >= side || qz >= side) continue;
                int q = qx * side + qz;
                if (kind[q] == want && label[q] < 0) {
                    label[q] = id;
                    stack.push(q);
                }
            }
        }
        return size;
    }

    /**
     * The terrain's masses round spawn, from the basin function: {whole masses, those with 1 to 3
     * rim gaps, the fewest gaps, the median, the most, and the nearest gap's x and z}.
     */
    private int[] masses(BasinFluidPicker basins, BlockPos spawn, int line) {
        int side = 2 * MASS_RADIUS / MASS_STEP;
        byte[] inside = new byte[side * side];
        byte[] gap = new byte[side * side];
        float[] at = new float[side * side];
        for (int i = 0; i < side; i++) for (int j = 0; j < side; j++) {
            int x = spawn.getX() - MASS_RADIUS + i * MASS_STEP, z = spawn.getZ() - MASS_RADIUS + j * MASS_STEP;
            double b = basins.basin(x, z);
            int k = i * side + j;
            at[k] = (float) b;
            inside[k] = b > RIM_OUTSIDE ? (byte) 1 : 0;
            gap[k] = b > -CREST && b < CREST && basins.floorAnywhere(x, z) < line - 1 ? (byte) 1 : 0;
        }
        int[] mass = new int[inside.length];
        java.util.Arrays.fill(mass, -1);
        int count = 0;
        List<int[]> boxes = new ArrayList<>();
        for (int i = 0; i < inside.length; i++) {
            if (inside[i] != 1 || mass[i] >= 0) continue;
            int[] box = {side, -1, side, -1};
            flood(inside, mass, i, side, (byte) 1, count++, false, box);
            boxes.add(box);
        }
        int[] gapId = new int[inside.length];
        java.util.Arrays.fill(gapId, -1);
        int[] gaps = new int[count];
        List<Integer> breaches = new ArrayList<>();
        int g = 0;
        for (int i = 0; i < inside.length; i++) {
            if (gap[i] != 1 || gapId[i] >= 0) continue;
            flood(gap, gapId, i, side, (byte) 1, g, true, new int[] {side, -1, side, -1});
            float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
            for (int j = 0; j < inside.length; j++) {
                if (gapId[j] != g) continue;
                lo = Math.min(lo, at[j]);
                hi = Math.max(hi, at[j]);
            }
            if (lo < -CREST / 2 && hi > CREST / 2) {
                gaps[mass[i]]++;
                breaches.add(i);
            }
            g++;
        }
        int coarse = side / 2;
        byte[] land = new byte[coarse * coarse];
        var generator = level.getChunkSource().getGenerator();
        var random = level.getChunkSource().randomState();
        for (int i = 0; i < coarse; i++) for (int j = 0; j < coarse; j++) {
            int k = 2 * i * side + 2 * j;
            if (at[k] < 1) continue;
            int x = spawn.getX() - MASS_RADIUS + 2 * i * MASS_STEP, z = spawn.getZ() - MASS_RADIUS + 2 * j * MASS_STEP;
            if (generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, random) > line) land[i * coarse + j] = 1;
        }
        int[] islandId = new int[land.length];
        java.util.Arrays.fill(islandId, -1);
        int[] islands = new int[count];
        int n = 0;
        for (int i = 0; i < land.length; i++) {
            if (land[i] != 1 || islandId[i] >= 0) continue;
            flood(land, islandId, i, coarse, (byte) 1, n++, true, new int[] {coarse, -1, coarse, -1});
            islands[mass[2 * (i / coarse) * side + 2 * (i % coarse)]]++;
        }
        List<Integer> whole = new ArrayList<>();
        List<Integer> wholeIslands = new ArrayList<>();
        for (int m = 0; m < count; m++) {
            int[] b = boxes.get(m);
            if (b[0] > 0 && b[2] > 0 && b[1] < side - 1 && b[3] < side - 1
                    && Math.max(b[1] - b[0], b[3] - b[2]) * MASS_STEP >= LAKE_SPAN) {
                whole.add(gaps[m]);
                wholeIslands.add(islands[m]);
            }
        }
        java.util.Collections.sort(wholeIslands);
        byte[] kind = new byte[inside.length];
        for (int i : breaches) kind[i] = 2;
        java.util.Collections.sort(whole);
        int ok = (int) whole.stream().filter(v -> v >= 1 && v <= 3).count();
        int nearest = -1;
        long best = Long.MAX_VALUE;
        for (int i = 0; i < kind.length; i++) {
            if (kind[i] != 2) continue;
            long dx = i / side - side / 2, dz = i % side - side / 2;
            if (dx * dx + dz * dz < best) {
                best = dx * dx + dz * dz;
                nearest = i;
            }
        }
        int gx = nearest < 0 ? 0 : spawn.getX() - MASS_RADIUS + nearest / side * MASS_STEP;
        int gz = nearest < 0 ? 0 : spawn.getZ() - MASS_RADIUS + nearest % side * MASS_STEP;
        return whole.isEmpty() ? new int[] {0, 0, 0, 0, 0, gx, gz, 0, 0, 0}
                : new int[] {whole.size(), ok, whole.get(0), whole.get(whole.size() / 2), whole.get(whole.size() - 1), gx, gz,
                        wholeIslands.get(0), wholeIslands.get(wholeIslands.size() / 2), wholeIslands.get(wholeIslands.size() - 1)};
    }

    /** True where source water lies within four blocks across of where a spring's fall lands, at that level or one above. */
    private static boolean lakeNear(net.minecraft.world.level.Level level, BlockPos landing) {
        for (BlockPos c : BlockPos.betweenClosed(landing.offset(-4, 0, -4), landing.offset(4, 1, 4))) {
            if (level.getFluidState(c).isSource() && level.getFluidState(c).is(FluidTags.WATER)) return true;
        }
        return false;
    }

    /** Counts a spring in the cliff face under a column's surface: a water source under solid ground, open on exactly one side. */
    private static void spring(net.minecraft.world.level.Level level, BlockPos top, long[] n) {
        for (BlockPos p = top; p.getY() > top.getY() - 6; p = p.below()) {
            if (!level.getFluidState(p).isSource() || !level.getFluidState(p).is(FluidTags.WATER) || !level.getBlockState(p.above()).isSolid()) continue;
            BlockPos open = null;
            int sides = 0;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos q = p.relative(d);
                if (!level.getBlockState(q).isSolid() && !level.getFluidState(q).isSource()) {
                    open = q;
                    sides++;
                }
            }
            if (sides != 1) continue;
            BlockPos q = open.below();
            while (q.getY() >= level.getMinY() && !level.getBlockState(q).isSolid() && !level.getFluidState(q).isSource()) q = q.below();
            if (q.getY() < level.getMinY()) n[8]++;
            else if (open.getY() - q.getY() <= 24 && lakeNear(level, q)) n[7]++;
            return;
        }
    }

    /**
     * True where the block tops a column of its own kind with open air above and beside it:
     * it stands above the ground rather than lying flush in it or lining a hollow.
     */
    private static boolean standsClear(LevelChunk chunk, BlockPos pos) {
        var level = chunk.getLevel();
        BlockState here = level.getBlockState(pos);
        if (!level.getBlockState(pos.above()).isAir() || !level.getBlockState(pos.below()).is(here.getBlock())) return false;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (level.getBlockState(pos.relative(d)).isAir()) return true;
        }
        return false;
    }

    /** A feature's block of its own, the sibling features that place it too, or null with what it shares instead. */
    record Own(String block, List<String> with, String shared) {}

    /**
     * The first block a feature places that nothing else in its showcase does: what it grows
     * ({@link #GROWTH_KEYS}) or tops a column with, then its {@link #mainBlock}, then any other
     * block it places. Nothing else means the level's {@link #TERRAIN}, the palette, the vanilla
     * features the biome lists and its other own features; failing that, the first block only
     * it and some of its sibling features place, counted for them together.
     */
    Own ownBlock(BiomeLibrary.Entry entry, String name) {
        JsonElement feature = expand(entry, entry.features().get(name).get("feature"), 0);
        Set<String> outside = new java.util.HashSet<>(TERRAIN);
        outside.addAll(entry.palette().blocks().values());
        for (JsonElement step : entry.biome().getAsJsonArray("features")) {
            for (JsonElement id : step.getAsJsonArray()) {
                if (id.getAsString().startsWith("~")) continue;
                JsonElement vanilla = resolve(entry, id.getAsString());
                if (vanilla != null) placed(expand(entry, vanilla, 0), outside);
            }
        }
        Map<String, List<String>> siblings = new java.util.HashMap<>();
        for (String other : entry.features().keySet()) {
            if (other.equals(name)) continue;
            Set<String> theirs = new java.util.HashSet<>();
            placed(expand(entry, entry.features().get(other).get("feature"), 0), theirs);
            theirs.forEach(block -> siblings.computeIfAbsent(block, k -> new ArrayList<>()).add(other));
        }
        Set<String> candidates = new java.util.LinkedHashSet<>();
        growth(feature, candidates);
        mainBlock(entry, entry.features().get(name).get("feature")).ifPresent(candidates::add);
        placed(feature, candidates);
        for (String block : candidates) {
            if (!outside.contains(block) && !siblings.containsKey(block)) return new Own(block, List.of(), null);
        }
        for (String block : candidates) {
            if (!outside.contains(block)) return new Own(block, siblings.get(block), null);
        }
        List<String> mine = candidates.stream().map(b -> Identifier.parse(b).getPath()).toList();
        return new Own(null, List.of(), mine.isEmpty() ? "it names no block" : String.join(", ", mine) + " shared with the terrain, palette or vanilla features");
    }

    /** The blocks under {@link #GROWTH_KEYS} and the tip layer of each block column, heaviest first. */
    private static void growth(JsonElement e, Set<String> out) {
        if (e.isJsonArray()) {
            e.getAsJsonArray().forEach(x -> growth(x, out));
        } else if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            for (Map.Entry<String, JsonElement> kv : o.entrySet()) {
                if (GROWTH_KEYS.contains(kv.getKey())) placed(kv.getValue(), out);
            }
            if (o.has("layers") && o.get("layers").isJsonArray() && !o.getAsJsonArray("layers").isEmpty()) {
                JsonArray layers = o.getAsJsonArray("layers");
                placed(layers.get(layers.size() - 1), out);
            }
            for (Map.Entry<String, JsonElement> kv : o.entrySet()) {
                if (!GROWTH_KEYS.contains(kv.getKey()) && !NOT_PLACED.contains(kv.getKey())) growth(kv.getValue(), out);
            }
        }
    }

    /** Every block a feature places, depth first and heaviest entry first, outside what it only tests or replaces. */
    static void placed(JsonElement e, Set<String> out) {
        if (e.isJsonArray()) {
            e.getAsJsonArray().forEach(x -> placed(x, out));
            return;
        }
        if (e.isJsonPrimitive()) {
            blockOf(e).ifPresent(out::add);
            return;
        }
        if (!e.isJsonObject()) return;
        JsonObject o = e.getAsJsonObject();
        if (o.has("type") && o.get("type").isJsonPrimitive()) {
            IMPLIED.getOrDefault(o.get("type").getAsString(), List.of()).forEach(out::add);
        }
        for (String key : List.of("id", "Name")) {
            if (o.has(key) && o.get(key).isJsonPrimitive()) blockOf(o.get(key)).ifPresent(out::add);
        }
        if (o.has("entries") && o.get("entries").isJsonArray()) {
            List<JsonObject> entries = new ArrayList<>();
            o.getAsJsonArray("entries").forEach(x -> entries.add(x.getAsJsonObject()));
            entries.sort(java.util.Comparator.comparingInt((JsonObject x) -> x.has("weight") ? x.get("weight").getAsInt() : 1).reversed());
            entries.forEach(x -> placed(x.get("data"), out));
        }
        for (Map.Entry<String, JsonElement> kv : o.entrySet()) {
            String key = kv.getKey();
            if (NOT_PLACED.contains(key) || key.equals("type") || key.equals("id") || key.equals("Name") || key.equals("entries")) continue;
            placed(kv.getValue(), out);
        }
    }

    /**
     * The block a feature mostly places, with the features it names expanded (its own
     * {@code ~name}s and vanilla ids) and what it only tests or replaces skipped: the
     * shallowest value under one of {@link #MAIN_KEYS}, the earlier key first at the same
     * depth, else the first block anywhere; a weighted provider gives its heaviest entry.
     * Air is never a main block.
     */
    Optional<String> mainBlock(BiomeLibrary.Entry entry, JsonElement feature) {
        List<JsonElement> level = List.of(expand(entry, feature, 0));
        while (!level.isEmpty()) {
            for (String key : MAIN_KEYS) {
                for (JsonElement e : level) {
                    if (e.isJsonObject() && e.getAsJsonObject().has(key)) {
                        Optional<String> block = blockOf(e.getAsJsonObject().get(key));
                        if (block.isPresent()) return block;
                    }
                }
            }
            List<JsonElement> next = new ArrayList<>();
            for (JsonElement e : level) {
                if (e.isJsonArray()) e.getAsJsonArray().forEach(next::add);
                else if (e.isJsonObject()) e.getAsJsonObject().entrySet().stream()
                        .filter(kv -> !NOT_PLACED.contains(kv.getKey())).forEach(kv -> next.add(kv.getValue()));
            }
            level = next;
        }
        return anyBlock(expand(entry, feature, 0));
    }

    private JsonElement expand(BiomeLibrary.Entry entry, JsonElement e, int depth) {
        if (depth > 8) return e;
        if (e.isJsonPrimitive()) {
            JsonElement resolved = resolve(entry, e.getAsString());
            return resolved == null ? e : expand(entry, resolved, depth + 1);
        }
        if (e.isJsonArray()) {
            JsonArray out = new JsonArray();
            e.getAsJsonArray().forEach(x -> out.add(x.isJsonPrimitive() ? x : expand(entry, x, depth)));
            return out;
        }
        if (!e.isJsonObject()) return e;
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> kv : e.getAsJsonObject().entrySet()) {
            JsonElement v = kv.getValue();
            boolean names = kv.getKey().equals("feature") || kv.getKey().equals("default") || kv.getKey().equals("features");
            if (kv.getKey().equals("features") && v.isJsonArray()) {
                JsonArray list = new JsonArray();
                for (JsonElement item : v.getAsJsonArray()) {
                    if (item.isJsonObject() && item.getAsJsonObject().has("data")) {
                        JsonObject copy = item.getAsJsonObject().deepCopy();
                        copy.add("data", expand(entry, copy.get("data"), depth + 1));
                        list.add(copy);
                    } else {
                        list.add(expand(entry, item, depth + 1));
                    }
                }
                out.add(kv.getKey(), list);
            } else if (names && v.isJsonPrimitive()) {
                out.add(kv.getKey(), expand(entry, v, depth + 1));
            } else if (v.isJsonObject() || v.isJsonArray()) {
                out.add(kv.getKey(), NOT_PLACED.contains(kv.getKey()) ? v : expand(entry, v, depth));
            } else {
                out.add(kv.getKey(), v);
            }
        }
        return out;
    }

    /** A feature name as JSON: one of the biome's own ({@code ~name}), else a configured or placed feature in the registries. */
    private JsonElement resolve(BiomeLibrary.Entry entry, String name) {
        if (name.startsWith("~")) {
            JsonObject own = entry.features().get(name.substring(1));
            return own == null ? null : own.get("feature");
        }
        Identifier id = Identifier.tryParse(name);
        if (id == null) return null;
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        Optional<Holder.Reference<Feature>> configured =
                server.registryAccess().lookupOrThrow(Registries.FEATURE).get(ResourceKey.create(Registries.FEATURE, id));
        if (configured.isPresent()) return Feature.DIRECT_CODEC.encodeStart(ops, configured.get().value()).result().orElse(null);
        Optional<Holder.Reference<PlacedFeature>> placed =
                server.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE).get(ResourceKey.create(Registries.PLACED_FEATURE, id));
        return placed.flatMap(p -> PlacedFeature.DIRECT_CODEC.encodeStart(ops, p.value()).result()).orElse(null);
    }

    /** The first block anywhere, depth first, outside the subtrees that are not placed. */
    private static Optional<String> anyBlock(JsonElement e) {
        if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) {
                Optional<String> b = anyBlock(x);
                if (b.isPresent()) return b;
            }
        } else if (e.isJsonObject()) {
            Optional<String> own = blockOf(e);
            if (own.isPresent()) return own;
            for (Map.Entry<String, JsonElement> kv : e.getAsJsonObject().entrySet()) {
                if (NOT_PLACED.contains(kv.getKey())) continue;
                Optional<String> b = kv.getValue().isJsonPrimitive() ? blockOf(kv.getValue()) : anyBlock(kv.getValue());
                if (b.isPresent()) return b;
            }
        }
        return Optional.empty();
    }

    /** A block id from a block, a block state or a state provider; a weighted provider gives its heaviest entry. */
    private static Optional<String> blockOf(JsonElement e) {
        if (e.isJsonPrimitive()) {
            Identifier id = Identifier.tryParse(e.getAsString());
            return id != null && BuiltInRegistries.BLOCK.containsKey(id) && !BuiltInRegistries.BLOCK.getValue(id).defaultBlockState().isAir()
                    ? Optional.of(id.toString()) : Optional.empty();
        }
        if (!e.isJsonObject()) return Optional.empty();
        JsonObject o = e.getAsJsonObject();
        if (o.has("id") && o.get("id").isJsonPrimitive()) return blockOf(o.get("id"));
        if (o.has("state")) return blockOf(o.get("state"));
        if (o.has("source")) return blockOf(o.get("source"));
        if (o.has("entries") && o.get("entries").isJsonArray()) {
            JsonElement best = null;
            int weight = -1;
            for (JsonElement x : o.getAsJsonArray("entries")) {
                JsonObject entry = x.getAsJsonObject();
                int w = entry.has("weight") ? entry.get("weight").getAsInt() : 1;
                if (w > weight && blockOf(entry.get("data")).isPresent()) {
                    best = entry.get("data");
                    weight = w;
                }
            }
            return best == null ? Optional.empty() : blockOf(best);
        }
        return Optional.empty();
    }

    // ---- scanning -------------------------------------------------------------------------------

    private interface ColumnVisitor {
        void visit(LevelChunk chunk, int x, int z, String biome);
    }

    /** Each column with its biome at the surface. */
    private static void forColumns(List<LevelChunk> chunks, ColumnVisitor visitor) {
        for (LevelChunk chunk : chunks) {
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                BlockPos pos = new BlockPos(chunk.getPos().getMinBlockX() + x, y, chunk.getPos().getMinBlockZ() + z);
                String biome = chunk.getLevel().getBiome(pos).unwrapKey().map(k -> k.identifier().toString()).orElse("");
                visitor.visit(chunk, x, z, biome);
            }
        }
    }

    /**
     * The column's highest solid block past plants, leaves and logs, under the roof where the level
     * has one (the floor of its highest open space), or null where a fluid lies on it.
     */
    private static BlockPos land(LevelChunk chunk, int x, int z) {
        int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(chunk.getPos().getMinBlockX() + x, y, chunk.getPos().getMinBlockZ() + z);
        boolean underRoof = chunk.getLevel().dimensionType().hasCeiling();
        for (; y > chunk.getMinY(); y--) {
            pos.setY(y);
            BlockState s = chunk.getBlockState(pos);
            if (underRoof) {
                underRoof = !s.isAir();
                continue;
            }
            if (!s.getFluidState().isEmpty()) return null;
            if (s.isAir() || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || !s.isSolid()) continue;
            return pos.immutable();
        }
        return null;
    }

    private static JsonArray featureIds(BiomeGenerationSettings settings) {
        JsonArray steps = new JsonArray();
        for (HolderSet<PlacedFeature> step : settings.features()) {
            JsonArray ids = new JsonArray();
            for (Holder<PlacedFeature> f : step) ids.add(f.unwrapKey().map(k -> k.identifier().toString()).orElse("<direct>"));
            steps.add(ids);
        }
        return steps;
    }

    private static int count(JsonArray steps) {
        int n = 0;
        for (JsonElement step : steps) n += step.getAsJsonArray().size();
        return n;
    }

    private String full(String id) {
        return namespace + ":" + id;
    }

    /** 6x6 chunks, 96x96 columns, round {@code centre}. */
    private static List<ChunkPos> area(ChunkPos centre) {
        List<ChunkPos> out = new ArrayList<>();
        for (int x = -3; x < 3; x++) for (int z = -3; z < 3; z++) out.add(new ChunkPos(centre.x() + x, centre.z() + z));
        return out;
    }

    private CompletableFuture<List<LevelChunk>> load(List<ChunkPos> chunks) {
        chunks.forEach(c -> level.setChunkForced(c.x(), c.z(), true));
        waiting = chunks;
        ticks = 0;
        loaded = new CompletableFuture<>();
        return loaded;
    }

    private void tick() {
        if (delay != null && !delay.isDone() && --delayTicks <= 0) delay.complete(null);
        if (loaded == null || loaded.isDone()) return;
        List<LevelChunk> got = new ArrayList<>();
        for (ChunkPos c : waiting) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(c.x(), c.z());
            if (chunk != null) got.add(chunk);
        }
        if (got.size() == waiting.size()) {
            loaded.complete(got);
        } else if (++ticks >= LOAD_CAP_TICKS) {
            loaded.completeExceptionally(new IllegalStateException(got.size() + "/" + waiting.size() + " chunks loaded after " + LOAD_CAP_TICKS + " ticks"));
        }
    }

    private static double share(long part, long whole) {
        return whole == 0 ? 0 : (double) part / whole;
    }

    private static String pct(double share) {
        return String.format("%.1f%%", share * 100);
    }

    private int holdAwake() {
        if (!(server instanceof DedicatedServer dedicated)) return -1;
        int seconds = dedicated.pauseWhenEmptySeconds();
        if (seconds > 0) dedicated.setPauseWhenEmptySeconds(0);
        return seconds;
    }

    private void restorePause(int seconds) {
        if (seconds > 0 && server instanceof DedicatedServer dedicated) dedicated.setPauseWhenEmptySeconds(seconds);
    }

    private static String cause(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }
}
