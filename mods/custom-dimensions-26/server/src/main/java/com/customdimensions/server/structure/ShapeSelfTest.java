package com.customdimensions.server.structure;

import com.customdimensions.core.structure.SetPieceShapes;
import com.customdimensions.server.level.LevelManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The set-piece shapes, seats and setting, part of the {@code setpieces}
 * section: {@code selftest_shapes} (overworld ground: a crater with its path,
 * a crown, a pinned platform, a house on its footings, a lantern seated on a
 * pillar, a mooring on water, age by distance, the lived-in layer),
 * {@code selftest_shapes_nether} (a lava fall on a pit wall flowing within 20
 * ticks, a roof lamp, a wall-seated fall, a platform, and filtered starts that
 * stand on the floor under the roof) and {@code selftest_shapes_end} (a
 * platform over the void; a roof piece that declines). Each placed start's
 * chunk and two rings round it generate, and the blocks are read back.
 */
public final class ShapeSelfTest {

    static final int LOAD_CAP_TICKS = 6000;
    static final int FLOW_TICKS = 20;
    static final String OVER = "selftest_shapes";
    static final String NETHER = "selftest_shapes_nether";
    static final String END = "selftest_shapes_end";

    private record Wait(ServerLevel level, List<ChunkPos> chunks, CompletableFuture<Void> done, int[] ticks, int after) {}

    private final LevelManager manager;
    private final List<String> lines = new ArrayList<>();
    private final List<Wait> waits = new ArrayList<>();
    private final List<String> platforms = new ArrayList<>();
    private boolean platformsOk = true;

    private ShapeSelfTest(LevelManager manager) {
        this.manager = manager;
    }

    public static CompletableFuture<List<String>> run(MinecraftServer server) {
        LevelManager manager = LevelManager.get().orElse(null);
        if (manager == null) return CompletableFuture.completedFuture(List.of("FAIL setpieces shapes: the level manager did not start"));
        ShapeSelfTest test = new ShapeSelfTest(manager);
        ServerTickEvents.END_SERVER_TICK.register(s -> test.tick());
        return test.level(OVER).thenCompose(v -> test.level(NETHER)).thenCompose(v -> test.level(END)).handle((v, error) -> {
            if (error != null) test.lines.add("FAIL setpieces shapes: the check chain broke: " + error);
            test.lines.add((test.platformsOk && test.platforms.size() == 3 ? "PASS" : "FAIL")
                    + " setpieces platform pinned on three seeds: " + test.platforms);
            return List.copyOf(test.lines);
        });
    }

    private CompletableFuture<Void> level(String slug) {
        return manager.activate(slug).thenApply(LevelManager.Activation::level).thenCompose(level -> {
            StructureRuntime.Applied applied = StructureRuntime.applied(level.dimension().identifier().toString()).orElse(null);
            if (applied == null) {
                lines.add("FAIL setpieces shapes " + slug + ": no structure control was applied");
                return CompletableFuture.completedFuture(null);
            }
            List<ChunkPos> chunks = new ArrayList<>();
            for (StructureRuntime.Placed p : applied.placed()) {
                for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) {
                    ChunkPos c = new ChunkPos(p.chunkX() + dx, p.chunkZ() + dz);
                    if (!chunks.contains(c)) chunks.add(c);
                }
            }
            return wait(level, chunks, 0).thenCompose(v -> {
                for (StructureRuntime.Placed p : applied.placed()) check(level, slug, p, null);
                if (slug.equals(OVER)) check(this, "setpieces lived-in layer " + slug, () -> lived(level));
                if (!slug.equals(NETHER)) return CompletableFuture.completedFuture(null);
                List<BlockPos[]> falls = new ArrayList<>();
                for (StructureRuntime.Placed p : applied.placed()) {
                    if (p.structure().endsWith("/pit_fall")) check(level, slug, p, falls);
                }
                return wait(level, List.of(), FLOW_TICKS).thenRun(() -> check(this, "setpieces fall flows " + slug, () -> flows(level, falls)));
            }).thenRun(() -> chunks.forEach(c -> level.setChunkForced(c.x(), c.z(), false)));
        });
    }

    private static void check(ShapeSelfTest test, String name, java.util.concurrent.Callable<String> check) {
        try {
            test.lines.add(check.call().replaceFirst("^(PASS|FAIL) ", "$1 " + name + ": "));
        } catch (Exception e) {
            test.lines.add("FAIL " + name + ": " + e);
        }
    }

    /** One placed start's checks; with {@code falls}, only records the pit fall's source and front for the flow check. */
    private void check(ServerLevel level, String slug, StructureRuntime.Placed p, List<BlockPos[]> falls) {
        String name = p.structure().substring(Math.max(p.structure().lastIndexOf('/'), p.structure().indexOf(':')) + 1);
        if (falls != null) {
            StructureStart start = start(level, p);
            JsonObject fall = start == null ? null : shapes(start, "fall").stream().findFirst().orElse(null);
            if (fall != null) {
                JsonArray out = fall.getAsJsonArray("points").get(0).getAsJsonArray();
                BlockPos source = new BlockPos(fall.get("x").getAsInt(), fall.get("y").getAsInt() - 2, fall.get("z").getAsInt());
                falls.add(new BlockPos[] {source, source.offset(out.get(0).getAsInt(), 0, out.get(1).getAsInt())});
            }
            return;
        }
        check(this, "setpieces " + slug + " " + name, () -> switch (name) {
            case "crater_site" -> crater(level, p);
            case "hill_crown" -> crown(level, p);
            case "overlook" -> platform(level, p, slug);
            case "stilt_house" -> footing(level, p);
            case "layered_pillar" -> layered(level, p);
            case "mooring" -> mooring(level, p);
            case "far_marker" -> marker(level, p);
            case "pit_fall", "face_fall" -> source(level, p);
            case "roof_lamp" -> roof(level, p);
            case "floor_cairn", "village_plains" -> floor(level, p);
            case "no_roof" -> declined(level, p);
            default -> "PASS not checked here";
        });
    }

    private static Structure structure(ServerLevel level, String id) {
        return level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValue(ResourceKey.create(Registries.STRUCTURE, Identifier.parse(id)));
    }

    private static StructureStart start(ServerLevel level, StructureRuntime.Placed p) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(p.chunkX(), p.chunkZ());
        StructureStart start = chunk == null ? null : chunk.getStartForStructure(structure(level, p.structure()));
        return start != null && start.isValid() ? start : null;
    }

    /** The saved shapes of {@code kind} in a start. */
    private static List<JsonObject> shapes(StructureStart start, String kind) {
        List<JsonObject> out = new ArrayList<>();
        for (StructurePiece sp : start.getPieces()) {
            if (sp instanceof SetPiecePart part && part.drawsShape()) {
                JsonObject s = part.data().getAsJsonObject("shape");
                if (s.get("shape").getAsString().equals(kind)) out.add(s);
            }
        }
        return out;
    }

    private static Block block(ServerLevel level, int x, int y, int z) {
        return level.getBlockState(new BlockPos(x, y, z)).getBlock();
    }

    private static boolean loaded(ServerLevel level, int x, int z) {
        return level.getChunkSource().getChunkNow(x >> 4, z >> 4) != null;
    }

    private static int surface(ServerLevel level, int x, int z) {
        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return y;
    }

    private String crater(ServerLevel level, StructureRuntime.Placed p) {
        StructureStart start = start(level, p);
        if (start == null) return "FAIL no start at " + p.chunkX() + "," + p.chunkZ();
        JsonObject c = shapes(start, "crater").getFirst();
        int x = c.get("x").getAsInt();
        int y = c.get("y").getAsInt();
        int z = c.get("z").getAsInt();
        boolean bowl = level.getBlockState(new BlockPos(x, y - 1, z)).isAir() && level.getBlockState(new BlockPos(x, y - 4, z)).isAir()
                && !level.getBlockState(new BlockPos(x, y - 6, z)).isAir();
        int scorch = 0;
        for (int dx = -18; dx <= 18; dx++) for (int dz = -18; dz <= 18; dz++) {
            if (!loaded(level, x + dx, z + dz)) continue;
            Block b = block(level, x + dx, surface(level, x + dx, z + dz) - 1, z + dz);
            if (b == Blocks.BASALT || b == Blocks.BLACKSTONE) scorch++;
        }
        List<JsonObject> approach = shapes(start, "approach");
        int paths = 0;
        int ops = 0;
        for (JsonObject a : approach) {
            for (var e : a.getAsJsonArray("points")) {
                JsonArray op = e.getAsJsonArray();
                if (op.get(3).getAsInt() != SetPieceShapes.PATH) continue;
                int ox = op.get(0).getAsInt();
                int oz = op.get(2).getAsInt();
                if (!loaded(level, ox, oz)) continue;
                ops++;
                Block b = block(level, ox, surface(level, ox, oz) - 1, oz);
                if (b == Blocks.DIRT_PATH || b == Blocks.COARSE_DIRT || b == Blocks.GRAVEL) paths++;
            }
        }
        String age = c.has("age") ? c.get("age").getAsString() : "none";
        boolean ok = bowl && scorch > 0 && paths > 0 && age.equals("recent");
        return (ok ? "PASS" : "FAIL") + " bowl open at " + x + "," + (y - 4) + "," + z + " " + bowl + ", " + scorch + " scorched columns, approach "
                + approach.size() + " part(s), " + paths + " of " + ops + " loaded path columns worn, age by distance " + age;
    }

    private String crown(ServerLevel level, StructureRuntime.Placed p) {
        StructureStart start = start(level, p);
        if (start == null) return "FAIL no start at " + p.chunkX() + "," + p.chunkZ();
        JsonObject c = shapes(start, "crown").getFirst();
        int cx = c.get("x").getAsInt();
        int cz = c.get("z").getAsInt();
        GeneratorTerrain t = GeneratorTerrain.of(level.getChunkSource().getGenerator(), level.getChunkSource().randomState(),
                level.getChunkSource().getGenerator().getBiomeSource().createUncachedResolver(level.getChunkSource().randomState()));
        java.util.Set<Long> tops = new java.util.HashSet<>();
        int maxima = 0;
        for (var e : c.getAsJsonArray("points")) {
            int x = e.getAsJsonArray().get(0).getAsInt();
            int z = e.getAsJsonArray().get(1).getAsInt();
            tops.add(((long) x << 32) ^ (z & 0xffffffffL));
            int h = t.height(x, z);
            boolean max = true;
            for (int dx = -4; dx <= 4 && max; dx += 2) for (int dz = -4; dz <= 4 && max; dz += 2) max = t.height(x + dx, z + dz) <= h;
            if (max) maxima++;
        }
        int growth = 0;
        int stray = 0;
        for (int dx = -24; dx <= 24; dx++) for (int dz = -24; dz <= 24; dz++) {
            int x = cx + dx;
            int z = cz + dz;
            if (!loaded(level, x, z)) continue;
            int s = surface(level, x, z);
            for (int y = s - 3; y <= s; y++) {
                if (block(level, x, y, z) != Blocks.AMETHYST_BLOCK) continue;
                if (tops.contains(((long) x << 32) ^ (z & 0xffffffffL))) growth++;
                else stray++;
            }
        }
        boolean ok = !tops.isEmpty() && maxima == tops.size() && growth > 0 && stray == 0;
        return (ok ? "PASS" : "FAIL") + " " + tops.size() + " top(s), " + maxima + " local maxima of the base heights, " + growth
                + " growth blocks on tops, " + stray + " elsewhere";
    }

    private String platform(ServerLevel level, StructureRuntime.Placed p, String slug) {
        StructureStart start = start(level, p);
        if (start == null) {
            platformsOk = false;
            platforms.add(slug + ": no start");
            return "FAIL no start";
        }
        JsonObject s = shapes(start, "platform").getFirst();
        int y = s.get("y").getAsInt() - 1;
        int deck = 0;
        for (int dx = -6; dx <= 6; dx++) for (int dz = -6; dz <= 6; dz++) {
            if (block(level, s.get("x").getAsInt() + dx, y, s.get("z").getAsInt() + dz) == Blocks.POLISHED_ANDESITE) deck++;
        }
        boolean ok = deck == 20;
        platformsOk &= ok;
        platforms.add(slug + " seed " + level.getSeed() + ": " + deck + " deck blocks at y " + y);
        return (ok ? "PASS" : "FAIL") + " " + deck + " of 20 deck blocks at y " + y;
    }

    private String footing(ServerLevel level, StructureRuntime.Placed p) {
        StructureStart start = start(level, p);
        if (start == null) return "FAIL no start";
        BoundingBox box = null;
        for (StructurePiece sp : start.getPieces()) if (sp instanceof SetPiecePart part && part.data().get("kind").getAsString().equals("template")) box = sp.getBoundingBox();
        if (box == null) return "FAIL no template";
        int footed = 0;
        int gaps = 0;
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                if (!loaded(level, x, z)) continue;
                int bottom = Integer.MIN_VALUE;
                for (int y = box.minY(); y <= box.minY() + 3; y++) {
                    if (!level.getBlockState(new BlockPos(x, y, z)).canBeReplaced()) {
                        bottom = y;
                        break;
                    }
                }
                if (bottom == Integer.MIN_VALUE) continue;
                if (level.getBlockState(new BlockPos(x, bottom - 1, z)).isAir()) gaps++;
                else footed++;
            }
        }
        long[] counts = Footings.counts();
        boolean ok = footed > 0 && gaps == 0 && counts[0] > 0;
        return (ok ? "PASS" : "FAIL") + " floor at y " + box.minY() + ": " + footed + " column(s) stand on footings, " + gaps + " on air; "
                + counts[0] + " footing blocks laid in all";
    }

    private String layered(ServerLevel level, StructureRuntime.Placed p) {
        StructureStart start = start(level, p);
        if (start == null) return "FAIL no start";
        JsonObject c = shapes(start, "column").getFirst();
        int top = c.get("y").getAsInt() + c.getAsJsonObject("params").get("height").getAsInt();
        Block on = block(level, c.get("x").getAsInt(), top, c.get("z").getAsInt());
        Block under = block(level, c.get("x").getAsInt(), top - 1, c.get("z").getAsInt());
        boolean ok = on == Blocks.LANTERN && under != Blocks.AIR && "ancient".equals(c.get("age").getAsString());
        return (ok ? "PASS" : "FAIL") + " the lantern (recent) is " + on + " at y " + top + " on the ancient pillar's top " + under;
    }

    private String mooring(ServerLevel level, StructureRuntime.Placed p) {
        StructureStart start = start(level, p);
        if (start == null) return "FAIL no start (no water two deep at the chosen site)";
        JsonObject c = shapes(start, "column").getFirst();
        BlockPos at = new BlockPos(c.get("x").getAsInt(), c.get("y").getAsInt(), c.get("z").getAsInt());
        boolean ok = level.getFluidState(at.below()).is(Fluids.WATER) && block(level, at.getX(), at.getY(), at.getZ()) == Blocks.SPRUCE_LOG;
        return (ok ? "PASS" : "FAIL") + " seat water at y " + at.getY() + ": below " + level.getBlockState(at.below()).getBlock()
                + ", post " + block(level, at.getX(), at.getY(), at.getZ());
    }

    private String marker(ServerLevel level, StructureRuntime.Placed p) {
        StructureStart start = start(level, p);
        if (start == null) return "FAIL no start";
        JsonObject c = shapes(start, "column").getFirst();
        double d = Math.hypot(c.get("x").getAsInt(), c.get("z").getAsInt());
        String age = c.has("age") ? c.get("age").getAsString() : "none";
        return (age.equals("ancient") ? "PASS" : "FAIL") + " at " + Math.round(d) + " blocks from spawn the bands give it age " + age;
    }

    private String source(ServerLevel level, StructureRuntime.Placed p) {
        StructureStart start = start(level, p);
        if (start == null) return "FAIL no start at " + p.chunkX() + "," + p.chunkZ();
        JsonObject f = shapes(start, "fall").getFirst();
        BlockPos s = new BlockPos(f.get("x").getAsInt(), f.get("y").getAsInt() - 2, f.get("z").getAsInt());
        BlockState state = level.getBlockState(s);
        boolean ok = state.getFluidState().is(Fluids.LAVA);
        return (ok ? "PASS" : "FAIL") + " seat " + ((SetPieceStructure) start.getStructure()).spec().seat() + ", source at " + s.toShortString()
                + " is " + state.getBlock();
    }

    private String flows(ServerLevel level, List<BlockPos[]> falls) {
        if (falls.isEmpty()) return "FAIL no pit fall to watch";
        List<String> found = new ArrayList<>();
        boolean ok = true;
        for (BlockPos[] f : falls) {
            boolean flowing = false;
            for (int k = 0; k <= 6 && !flowing; k++) {
                var fluid = level.getFluidState(f[1].below(k));
                flowing = fluid.is(Fluids.LAVA) && !fluid.isSource() && k > 0;
            }
            found.add("source " + f[0].toShortString() + (level.getFluidState(f[0]).isSource() ? " (source)" : " (not a source)")
                    + (flowing ? ", flowing lava below its level in front" : ", nothing flowing in front"));
            ok &= flowing;
        }
        return (ok ? "PASS" : "FAIL") + " within " + FLOW_TICKS + " ticks of loading: " + found;
    }

    private String roof(ServerLevel level, StructureRuntime.Placed p) {
        StructureStart start = start(level, p);
        if (start == null) return "FAIL no start";
        JsonObject c = shapes(start, "column").getFirst();
        int x = c.get("x").getAsInt();
        int y = c.get("y").getAsInt();
        int z = c.get("z").getAsInt();
        int lamps = 0;
        for (int k = 1; k <= 4; k++) if (block(level, x, y - k, z) == Blocks.SHROOMLIGHT) lamps++;
        boolean ok = !level.getBlockState(new BlockPos(x, y, z)).isAir() && lamps == 4;
        return (ok ? "PASS" : "FAIL") + " ceiling at y " + y + " is " + block(level, x, y, z) + ", " + lamps + " of 4 lamps hang under it";
    }

    /** A filtered start under the roof stands on the floor in its height window, not on the roof. */
    private String floor(ServerLevel level, StructureRuntime.Placed p) {
        StructureStart start = start(level, p);
        if (start == null) return "FAIL no start at " + p.chunkX() + "," + p.chunkZ();
        BoundingBox box = start.getBoundingBox();
        boolean ok = box.minY() >= 32 && box.minY() <= 100;
        return (ok ? "PASS" : "FAIL") + " box " + box.minY() + " to " + box.maxY() + " against the site's heights 36 to 100 under a roof at "
                + level.getChunkSource().getGenerator().getGenDepth();
    }

    private String declined(ServerLevel level, StructureRuntime.Placed p) {
        StructureStart start = start(level, p);
        Structure s = structure(level, p.structure());
        String why = s instanceof SetPieceStructure piece ? SetPieceStructure.declined().get(piece.spec().key() + " " + p.chunkX() + "," + p.chunkZ()) : null;
        boolean ok = start == null && why != null && why.contains("ceiling");
        return (ok ? "PASS" : "FAIL") + " a roof seat without a roof declines: start " + (start == null ? "none" : "valid") + ", log: " + why;
    }

    private String lived(ServerLevel level) {
        String id = level.dimension().identifier().toString();
        Map<String, Integer> counts = StructureRuntime.lived(id);
        LivedPlacement placement = level.getChunkSource().getGeneratorState().possibleStructureSets().stream()
                .map(h -> h.value().placement()).filter(pl -> pl instanceof LivedPlacement).map(pl -> (LivedPlacement) pl).findFirst().orElse(null);
        if (placement == null) return "FAIL no lived-in placement: " + counts;
        Structure s = structure(level, placement.structure());
        var lived = ((SetPieceStructure) s).spec().setting().lived();
        GeneratorTerrain t = GeneratorTerrain.of(level.getChunkSource().getGenerator(), level.getChunkSource().randomState(),
                level.getChunkSource().getGenerator().getBiomeSource().createUncachedResolver(level.getChunkSource().randomState()));
        int sited = 0;
        int inside = 0;
        for (int[] c : placement.sites()) {
            if (lived.sited(t, c[0] * 16 + 8, c[1] * 16 + 8, List.of())) sited++;
            if (Math.hypot(c[0] * 16 + 8, c[1] * 16 + 8) <= 512) inside++;
        }
        double ceiling = lived.density() * Math.PI * 512 * 512 / 1_000_000;
        int n = placement.sites().size();
        boolean ok = n > 0 && sited == n && inside == n && n <= ceiling * 1.3;
        return (ok ? "PASS" : "FAIL") + " " + n + " site(s) (at most about " + Math.round(ceiling) + " at " + lived.density() + " per km² before siting), "
                + sited + " by water, " + inside + " inside the border";
    }

    private CompletableFuture<Void> wait(ServerLevel level, List<ChunkPos> chunks, int after) {
        chunks.forEach(c -> level.setChunkForced(c.x(), c.z(), true));
        Wait w = new Wait(level, chunks, new CompletableFuture<>(), new int[1], after);
        waits.add(w);
        return w.done;
    }

    private void tick() {
        for (Wait w : List.copyOf(waits)) {
            int got = 0;
            for (ChunkPos c : w.chunks) if (w.level.getChunkSource().getChunkNow(c.x(), c.z()) != null) got++;
            w.ticks[0]++;
            if (got == w.chunks.size() && w.ticks[0] >= w.after) {
                waits.remove(w);
                w.done.complete(null);
            } else if (w.ticks[0] >= LOAD_CAP_TICKS) {
                waits.remove(w);
                w.done.completeExceptionally(new IllegalStateException(got + "/" + w.chunks.size() + " chunks loaded after " + LOAD_CAP_TICKS + " ticks"));
            }
        }
    }
}
