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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.util.ArrayList;
import java.util.List;
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
 * hold amethyst clusters. Each line carries its counts.
 */
public final class BiomeLibrarySelfTest {

    static final String SLUG = "selftest_biome_library";
    static final String ASHEN = "ashen_woodland";
    static final String CRYSTAL = "crystal_hollows";
    static final int LOAD_CAP_TICKS = 6000;
    /** Share of ashen land columns topped with the palette's top block; gravel and podzol disks take most of the rest. */
    static final double TOP_SHARE = 0.5;
    /** Share topped with any of its ground blocks: the palette's, the disks' and the dirt trunks stand on. */
    static final double GROUND_SHARE = 0.9;
    static final List<Block> GROUND = List.of(Blocks.COARSE_DIRT, Blocks.GRAVEL, Blocks.PODZOL, Blocks.DIRT);
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
                    return load(area());
                })
                .thenAccept(chunks -> {
                    lines.add(line("biomes present (" + SLUG + ")", () -> present(chunks)));
                    lines.add(line("biomes ashen woodland surface (" + SLUG + ")", () -> ashenSurface(chunks)));
                    lines.add(line("biomes ashen woodland trunks (" + SLUG + ")", () -> ashenTrunks(chunks)));
                    lines.add(line("biomes crystal hollows clusters (" + SLUG + ")", () -> crystals(chunks)));
                })
                .handle((v, error) -> {
                    if (error != null) lines.add("FAIL biomes: " + cause(error));
                    if (level != null) area().forEach(c -> level.setChunkForced(c.x(), c.z(), false));
                    return null;
                })
                .thenCompose(v -> manager.deactivate(SLUG).handle((t, e) -> null))
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
        for (String id : List.of(ASHEN, CRYSTAL)) {
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
        return "PASS " + String.join("; ", found) + "; the firewall stripped nothing";
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

    /** The column's highest solid block past plants, leaves and logs, or null where water lies on it. */
    private static BlockPos land(LevelChunk chunk, int x, int z) {
        int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(chunk.getPos().getMinBlockX() + x, y, chunk.getPos().getMinBlockZ() + z);
        for (; y > chunk.getMinY(); y--) {
            pos.setY(y);
            BlockState s = chunk.getBlockState(pos);
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

    private static List<ChunkPos> area() {
        List<ChunkPos> out = new ArrayList<>();
        for (int x = -3; x < 3; x++) for (int z = -3; z < 3; z++) out.add(new ChunkPos(x, z));
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
