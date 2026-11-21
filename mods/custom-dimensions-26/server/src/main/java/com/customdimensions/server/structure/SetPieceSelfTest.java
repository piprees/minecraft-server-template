package com.customdimensions.server.structure;

import com.customdimensions.core.structure.SiteFilter;
import com.customdimensions.server.level.LevelManager;
import com.customdimensions.server.mixin.BaseSpawnerAccessor;
import com.customdimensions.server.survey.NamedPlaces;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The {@code setpieces} section: {@code selftest_set_pieces} (the End: a ground
 * piece on the central island, one over the void that declines, a pinned
 * overlook over the void, a wanted old house kept on ground by its site
 * filter) and {@code selftest_set_pieces_floor} (the Nether: a floor piece
 * and a group piece with a repetition ceiling). Each forced or wanted start's
 * chunk and its neighbours generate, and the blocks are read back.
 */
public final class SetPieceSelfTest {

    static final int LOAD_CAP_TICKS = 6000;
    static final String END = "selftest_set_pieces";
    static final String FLOOR = "selftest_set_pieces_floor";

    private record Load(ServerLevel level, List<ChunkPos> chunks, CompletableFuture<Void> done, int[] ticks) {}

    private final LevelManager manager;
    private final List<String> lines = new ArrayList<>();
    private final List<Load> loading = new ArrayList<>();

    private SetPieceSelfTest(LevelManager manager) {
        this.manager = manager;
    }

    public static CompletableFuture<List<String>> run(MinecraftServer server) {
        LevelManager manager = LevelManager.get().orElse(null);
        if (manager == null) return CompletableFuture.completedFuture(List.of("FAIL setpieces: the level manager did not start"));
        SetPieceSelfTest test = new SetPieceSelfTest(manager);
        ServerTickEvents.END_SERVER_TICK.register(s -> test.tick());
        lines(test, "setpieces types", () -> {
            boolean ok = BuiltInRegistries.STRUCTURE_TYPE.getKey(SetPieceStructure.TYPE) != null
                    && BuiltInRegistries.STRUCTURE_PIECE.getKey(SetPiecePart.TYPE) != null;
            return (ok ? "PASS" : "FAIL") + " customdimensions:set_piece is " + BuiltInRegistries.STRUCTURE_TYPE.getKey(SetPieceStructure.TYPE)
                    + ", customdimensions:set_piece_part is " + BuiltInRegistries.STRUCTURE_PIECE.getKey(SetPiecePart.TYPE);
        });
        return test.level(END).thenCompose(v -> test.level(FLOOR)).thenCompose(v -> AssemblySelfTest.run(server)).thenAccept(test.lines::addAll)
                .handle((v, error) -> {
            if (error != null) test.lines.add("FAIL setpieces: the check chain broke: " + error);
            return List.copyOf(test.lines);
        });
    }

    private CompletableFuture<Void> level(String slug) {
        return manager.activate(slug).thenApply(LevelManager.Activation::level).thenCompose(level -> {
            String id = level.dimension().identifier().toString();
            StructureRuntime.Applied applied = StructureRuntime.applied(id).orElse(null);
            if (applied == null) {
                lines.add("FAIL setpieces " + slug + ": no structure control was applied");
                return CompletableFuture.completedFuture(null);
            }
            List<ChunkPos> chunks = new ArrayList<>();
            for (StructureRuntime.Placed p : applied.placed()) {
                for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                    ChunkPos c = new ChunkPos(p.chunkX() + dx, p.chunkZ() + dz);
                    if (!chunks.contains(c)) chunks.add(c);
                }
            }
            return load(level, chunks).thenRun(() -> {
                for (StructureRuntime.Placed p : applied.placed()) {
                    lines(this, "setpieces " + p.kind() + " " + p.structure(), () -> placed(level, p));
                }
                applied.unplaced().forEach((key, account) -> {
                    if (key.endsWith("/void_yard")) {
                        lines(this, "setpieces " + key, () -> declinedEarly("a ground piece over the void", account, "ground"));
                    }
                });
                lines(this, "setpieces places.named " + slug, () -> named(level, applied));
                if (slug.equals(FLOOR)) lines(this, "setpieces group " + slug, () -> group(level, applied));
                lines(this, "setpieces mixin StructureCheckBypassMixin site refusal " + slug, () -> predicted(level, slug.equals(FLOOR)));
                chunks.forEach(c -> level.setChunkForced(c.x(), c.z(), false));
            });
        });
    }

    private static void lines(SetPieceSelfTest test, String name, java.util.concurrent.Callable<String> check) {
        try {
            test.lines.add(check.call().replaceFirst("^(PASS|FAIL) ", "$1 " + name + ": "));
        } catch (Exception e) {
            test.lines.add("FAIL " + name + ": " + e);
        }
    }

    /**
     * A forced piece its chooser never placed because every candidate's own
     * seat declined, the decline's reason ({@code reason}) carried into the
     * chooser's account and its WARN.
     */
    static String declinedEarly(String what, String account, String reason) {
        boolean ok = account.contains("its own rules start none there: ") && account.contains(reason);
        return (ok ? "PASS " : "FAIL ") + what + " declines in its chooser: not placed (" + account + ")";
    }

    /** One forced or wanted set piece read back from its generated chunks. */
    private String placed(ServerLevel level, StructureRuntime.Placed p) {
        Structure structure = level.registryAccess().lookupOrThrow(Registries.STRUCTURE)
                .getValue(ResourceKey.create(Registries.STRUCTURE, Identifier.parse(p.structure())));
        if (!(structure instanceof SetPieceStructure piece)) return "FAIL " + p.structure() + " is not a set piece in the registry";
        LevelChunk chunk = level.getChunkSource().getChunkNow(p.chunkX(), p.chunkZ());
        StructureStart start = chunk == null ? null : chunk.getStartForStructure(structure);
        String name = p.structure().substring(p.structure().lastIndexOf('/') + 1);
        if (name.equals("void_yard")) {
            String why = SetPieceStructure.declined().get(piece.spec().key() + " " + p.chunkX() + "," + p.chunkZ());
            boolean ok = (start == null || !start.isValid()) && why != null;
            return (ok ? "PASS" : "FAIL") + " a ground piece over the void declines: start " + (start != null && start.isValid() ? "valid" : "none")
                    + ", log: " + why;
        }
        if (start == null || !start.isValid()) return "FAIL no start at chunk " + p.chunkX() + ", " + p.chunkZ();
        List<String> found = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        int origin = Integer.MIN_VALUE;
        for (StructurePiece sp : start.getPieces()) {
            if (!(sp instanceof SetPiecePart part)) continue;
            JsonObject d = part.data();
            origin = d.get("origin").getAsInt();
            if (d.get("kind").getAsString().equals("template")) {
                Map<Block, Integer> counts = counts(level, sp.getBoundingBox());
                int solid = counts.entrySet().stream().filter(e -> e.getKey() != Blocks.AIR).mapToInt(Map.Entry::getValue).sum();
                found.add("template " + d.get("id").getAsString() + " resolved during generation, " + solid + " blocks in its box"
                        + (d.has("age") ? " (" + d.get("age").getAsString() + ")" : ""));
                if (solid == 0) problems.add("template " + d.get("id").getAsString() + " placed nothing");
                if (d.get("id").getAsString().contains("fossil") && counts.getOrDefault(Blocks.BONE_BLOCK, 0) == 0) problems.add("no bone in the fossil");
                if (name.equals("old_house")) {
                    int weathered = counts.getOrDefault(Blocks.MOSSY_COBBLESTONE, 0) + counts.getOrDefault(Blocks.MOSSY_COBBLESTONE_STAIRS, 0)
                            + counts.getOrDefault(Blocks.MOSSY_COBBLESTONE_SLAB, 0) + counts.getOrDefault(Blocks.MOSSY_COBBLESTONE_WALL, 0);
                    found.add("age old weathered " + weathered + " cobblestone to mossy, " + counts.getOrDefault(Blocks.COBBLESTONE, 0)
                            + " left, " + counts.getOrDefault(Blocks.COBWEB, 0) + " cobwebs, " + counts.getOrDefault(Blocks.VINE, 0) + " vines");
                    if (weathered == 0) problems.add("an old house kept every cobblestone");
                }
            } else {
                problems.addAll(items(level, d.getAsJsonArray("items"), found));
            }
        }
        if (name.equals("overlook") && origin != 90) problems.add("the pinned overlook stands at " + origin + ", not 90");
        if (name.equals("old_house")) {
            SiteFilter filter = piece.spec().site();
            GeneratorTerrain terrain = GeneratorTerrain.of(level.getChunkSource().getGenerator(), level.getChunkSource().randomState(),
                    level.getChunkSource().getGenerator().getBiomeSource().createUncachedResolver(level.getChunkSource().randomState()));
            int h = terrain.height(p.chunkX() * 16 + 8, p.chunkZ() * 16 + 8);
            String why = filter.refusal(terrain, p.chunkX() * 16 + 8, p.chunkZ() * 16 + 8);
            found.add("want site at surface " + h + (p.matched() ? ", matched" : ", unmatched"));
            if (why != null || !p.matched()) problems.add("the want's site fails its filter: " + why);
        }
        return (problems.isEmpty() ? "PASS " : "FAIL ") + "seat " + piece.spec().seat() + " at y " + origin + "; " + String.join("; ", found)
                + (problems.isEmpty() ? "" : "; problems: " + problems);
    }

    /** Each block, chest and spawner of a blocks part, checked in the world. */
    private static List<String> items(ServerLevel level, JsonArray items, List<String> found) {
        List<String> problems = new ArrayList<>();
        int blocks = 0;
        int matched = 0;
        for (JsonElement e : items) {
            JsonObject i = e.getAsJsonObject();
            BlockPos pos = new BlockPos(i.get("x").getAsInt(), i.get("y").getAsInt(), i.get("z").getAsInt());
            String kind = i.get("kind").getAsString();
            String block = i.get("block").getAsString();
            String placed = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
            switch (kind) {
                case "chest" -> {
                    var loot = level.getBlockEntity(pos) instanceof RandomizableContainer c ? c.getLootTable() : null;
                    String want = i.get("loot").getAsString();
                    found.add(placed + " at " + pos.toShortString() + " with loot " + (loot == null ? "none" : loot.identifier()));
                    if (loot == null || !loot.identifier().toString().equals(want)) problems.add("chest at " + pos.toShortString() + " lacks " + want);
                }
                case "spawner" -> {
                    if (!(level.getBlockEntity(pos) instanceof SpawnerBlockEntity spawner)) {
                        problems.add("no spawner at " + pos.toShortString());
                        continue;
                    }
                    Entity shown = spawner.getSpawner().getOrCreateDisplayEntity(level, pos);
                    String type = shown == null ? "none" : BuiltInRegistries.ENTITY_TYPE.getKey(shown.getType()).toString();
                    BaseSpawnerAccessor timing = (BaseSpawnerAccessor) spawner.getSpawner();
                    found.add("spawner of " + type + " at " + pos.toShortString() + " (delay " + timing.customdimensions$spawnDelay()
                            + ", range " + timing.customdimensions$spawnRange() + ")");
                    if (!type.equals(i.get("entity").getAsString())) problems.add("spawner holds " + type);
                    if (i.has("delay") && timing.customdimensions$spawnDelay() != i.get("delay").getAsInt()) problems.add("spawner delay not set");
                    if (i.has("range") && timing.customdimensions$spawnRange() != i.get("range").getAsInt()) problems.add("spawner range not set");
                }
                default -> {
                    blocks++;
                    String id = block.contains("[") ? block.substring(0, block.indexOf('[')) : block;
                    if (placed.equals(id)) matched++;
                }
            }
        }
        if (blocks > 0) {
            found.add(matched + " of " + blocks + " scattered blocks in place");
            if (matched < blocks * 0.7) problems.add("only " + matched + " of " + blocks + " scattered blocks in place");
        }
        return problems;
    }

    private static Map<Block, Integer> counts(ServerLevel level, BoundingBox box) {
        Map<Block, Integer> out = new java.util.HashMap<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) continue;
                    out.merge(level.getBlockState(pos.set(x, y, z)).getBlock(), 1, Integer::sum);
                }
            }
        }
        return out;
    }

    /** Every forced and wanted set piece is named, each with a name and coordinates; those generated carry their seat height. */
    private String named(ServerLevel level, StructureRuntime.Applied applied) {
        JsonArray named = NamedPlaces.named(level, 1024);
        List<String> names = new ArrayList<>();
        int withY = 0;
        for (JsonElement e : named) {
            JsonObject o = e.getAsJsonObject();
            names.add(o.get("name").getAsString() + " (" + o.get("kind").getAsString() + " " + o.get("x").getAsInt() + "," + o.get("z").getAsInt()
                    + (o.has("y") ? " y " + o.get("y").getAsInt() : "") + ")");
            if (o.has("y")) withY++;
        }
        long fixed = applied.placed().stream().filter(p -> p.structure().contains(":set_piece/")).count();
        long namedFixed = named.asList().stream().filter(e -> !e.getAsJsonObject().get("kind").getAsString().equals("group")).count();
        boolean ok = namedFixed == fixed && fixed > 0 && withY > 0;
        return (ok ? "PASS " : "FAIL ") + named.size() + " named, " + withY + " with a seat height: " + names;
    }

    /** The group piece has sites in the census and places.named, no more of them than its repetition allows to start. */
    private String group(ServerLevel level, StructureRuntime.Applied applied) {
        String id = level.dimension().identifier().toString();
        StructureRuntime.SetPieceEntry piece = StructureRuntime.setPieces(id).stream().filter(p -> p.set() != null).findFirst().orElse(null);
        if (piece == null) return "FAIL no group set piece in the control file";
        Structure structure = level.registryAccess().lookupOrThrow(Registries.STRUCTURE)
                .getValue(ResourceKey.create(Registries.STRUCTURE, Identifier.parse(piece.structure())));
        long census = com.customdimensions.server.survey.StructureCensus.sites(level, 512).stream()
                .filter(s -> s.structure().equals(piece.structure())).count();
        long named = NamedPlaces.named(level, 512).asList().stream().filter(e -> e.getAsJsonObject().get("kind").getAsString().equals("group")).count();
        var cap = StructureRuntime.cap(id, structure);
        Integer sites = applied.groupSites().get(piece.group());
        boolean ok = census > 0 && cap != null && !cap.isEmpty() && cap.size() <= piece.repetition() && named == cap.size();
        return (ok ? "PASS " : "FAIL ") + piece.name() + " in group " + piece.group() + ": " + sites + " group site(s), " + census
                + " in the census, " + named + " in places.named, repetition " + piece.repetition() + " leaves it " + (cap == null ? "no" : cap.size())
                + " site(s)";
    }

    /**
     * The structure search's fast check predicts each group set piece absent
     * at every group site its site filter or repetition ceiling refuses, as
     * generation refuses it there; and, with {@code present}, present at one or
     * more of the sites left to it.
     */
    private String predicted(ServerLevel level, boolean present) {
        String id = level.dimension().identifier().toString();
        List<String> parts = new ArrayList<>();
        List<String> wrong = new ArrayList<>();
        int refused = 0;
        int predicted = 0;
        for (StructureRuntime.SetPieceEntry piece : StructureRuntime.setPieces(id)) {
            if (piece.set() == null) continue;
            Structure structure = level.registryAccess().lookupOrThrow(Registries.STRUCTURE)
                    .getValue(ResourceKey.create(Registries.STRUCTURE, Identifier.parse(piece.structure())));
            StructurePlacements.Noise noise = level.getChunkSource().getGeneratorState().possibleStructureSets().stream()
                    .map(h -> h.value().placement()).filter(p -> p instanceof StructurePlacements.Noise n && n.group().equals(piece.group()))
                    .map(p -> (StructurePlacements.Noise) p).findFirst().orElse(null);
            if (noise == null) return "FAIL no " + piece.group() + " placement for " + piece.name();
            int r = 0;
            int a = 0;
            for (com.customdimensions.core.ChunkPos c : noise.sites()) {
                ChunkPos chunk = new ChunkPos(c.x(), c.z());
                String why = StructureRuntime.refusal(level.dimension(), structure, chunk, level.getChunkSource().getGenerator(),
                        level.getChunkSource().randomState());
                var result = level.structureManager().checkStructurePresence(chunk, structure, noise, false);
                boolean absent = result == net.minecraft.world.level.levelgen.structure.StructureCheckResult.START_NOT_PRESENT;
                if (why != null) {
                    r++;
                    if (!absent) wrong.add(piece.name() + " at " + c.x() + "," + c.z() + " " + result + " (" + why + ")");
                } else if (!absent) {
                    a++;
                }
            }
            refused += r;
            predicted += a;
            long named = NamedPlaces.named(level, 1024).asList().stream().map(JsonElement::getAsJsonObject)
                    .filter(o -> o.get("kind").getAsString().equals("group") && o.get("id").getAsString().equals(piece.structure())).count();
            if (named > noise.sites().size() - r) wrong.add(piece.name() + " named at " + named + " sites, more than the " + (noise.sites().size() - r) + " left to it");
            parts.add(piece.name() + ": " + r + " of " + noise.sites().size() + " site(s) refused, " + a + " predicted present, " + named + " named");
        }
        boolean ok = refused > 0 && wrong.isEmpty() && (!present || predicted > 0);
        return (ok ? "PASS " : "FAIL ") + String.join("; ", parts) + (wrong.isEmpty() ? "" : "; predicted present though refused: " + wrong);
    }

    private CompletableFuture<Void> load(ServerLevel level, List<ChunkPos> chunks) {
        chunks.forEach(c -> level.setChunkForced(c.x(), c.z(), true));
        Load l = new Load(level, chunks, new CompletableFuture<>(), new int[1]);
        loading.add(l);
        return l.done;
    }

    private void tick() {
        for (Load l : List.copyOf(loading)) {
            int got = 0;
            for (ChunkPos c : l.chunks) if (l.level.getChunkSource().getChunkNow(c.x(), c.z()) != null) got++;
            if (got == l.chunks.size()) {
                loading.remove(l);
                l.done.complete(null);
            } else if (++l.ticks[0] >= LOAD_CAP_TICKS) {
                loading.remove(l);
                l.done.completeExceptionally(new IllegalStateException(got + "/" + l.chunks.size() + " chunks loaded after " + LOAD_CAP_TICKS + " ticks"));
            }
        }
    }

}
