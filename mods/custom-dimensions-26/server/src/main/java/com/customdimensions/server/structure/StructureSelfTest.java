package com.customdimensions.server.structure;

import com.customdimensions.core.structure.StructureSites;
import com.customdimensions.server.level.LevelManager;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * The structure-control section: the fixture {@code selftest_structures}
 * (forced starts, a want, shuns, a replacement, a spacing override, a rejected
 * set, an adaptation override) and the five {@code selftest_density_*}
 * fixtures. Placement checks read the level's structure state over the 100x100
 * chunks round spawn (10,000 candidate chunks); generation checks load real
 * chunks and read their structure starts.
 */
public final class StructureSelfTest {

    static final int LOAD_CAP_TICKS = 6000;
    static final int SIDE = 100;
    static final int SHUN_SAMPLE = 24;
    private static final String FIXTURE = "selftest_structures";

    private record Load(ServerLevel level, List<ChunkPos> chunks, CompletableFuture<List<LevelChunk>> done, int[] ticks) {}

    private final MinecraftServer server;
    private final LevelManager manager;
    private final List<String> lines = new ArrayList<>();
    private final List<Load> loading = new ArrayList<>();
    private final Map<String, Long> candidates = new LinkedHashMap<>();

    private StructureSelfTest(MinecraftServer server, LevelManager manager) {
        this.server = server;
        this.manager = manager;
    }

    public static CompletableFuture<List<String>> run(MinecraftServer server) {
        LevelManager manager = LevelManager.get().orElse(null);
        if (manager == null) {
            return CompletableFuture.completedFuture(List.of("FAIL structures: the level manager did not start"));
        }
        StructureSelfTest test = new StructureSelfTest(server, manager);
        ServerTickEvents.END_SERVER_TICK.register(s -> test.tick());
        return test.run();
    }

    private CompletableFuture<List<String>> run() {
        int pause = holdAwake();
        lines.add(StructureReflection.check() ? "PASS structures reflection: every private member resolves"
                : "FAIL structures reflection: a private member is missing");
        lines.add(overworldLine());
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        chain = chain.thenCompose(v -> fixture());
        for (String d : List.of("dense", "normal", "sparse", "grid_normal", "grid_sparse")) {
            chain = chain.thenCompose(v -> count("selftest_density_" + d));
        }
        return chain.handle((v, error) -> {
            if (error != null) lines.add("FAIL structures: the check chain broke: " + cause(error));
            lines.add(densityLine());
            restorePause(pause);
            return List.copyOf(lines);
        });
    }

    /** The overworld takes noise placement by default, and its groups have sites. */
    private String overworldLine() {
        return StructureRuntime.applied("minecraft:overworld").map(a -> {
            boolean ok = !a.groupSites().isEmpty() && a.groupSites().values().stream().allMatch(n -> n > 0);
            return (ok ? "PASS" : "FAIL") + " structures overworld noise placement: groups " + a.groupSites() + ", "
                    + a.kept() + " set(s) kept, " + a.dropped() + " dropped " + a.droppedSets() + ", built in " + a.millis() + " ms";
        }).orElse("FAIL structures overworld noise placement: no structure control was applied to minecraft:overworld");
    }

    private CompletableFuture<Void> fixture() {
        return manager.activate(FIXTURE).thenApply(LevelManager.Activation::level).thenCompose(level -> {
            StructureRuntime.Applied applied = StructureRuntime.applied(level.dimension().identifier().toString()).orElse(null);
            if (applied == null) {
                lines.add("FAIL structures fixture: no structure control was applied to " + level.dimension().identifier());
                return CompletableFuture.completedFuture(null);
            }
            ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
            lines.add(guard("structures filters", () -> filters(level, state, applied)));
            lines.add(guard("structures shun placements", () -> shunPlacements(level, state)));
            List<ChunkPos> fixedChunks = applied.placed().stream().map(p -> new ChunkPos(p.chunkX(), p.chunkZ())).toList();
            List<ChunkPos> shunSample = shunSample(level, state);
            List<ChunkPos> all = new ArrayList<>(fixedChunks);
            shunSample.stream().filter(c -> !all.contains(c)).forEach(all::add);
            long beforeAdaptation = StructureAdaptation.overridden();
            return load(level, all).handle((chunks, error) -> {
                if (error != null) {
                    lines.add("FAIL structures generation: " + cause(error));
                } else {
                    Map<Long, LevelChunk> byPos = new LinkedHashMap<>();
                    chunks.forEach(c -> byPos.put(c.getPos().pack(), c));
                    for (StructureRuntime.Placed p : applied.placed()) {
                        lines.add(guard("structures " + p.kind() + " " + p.structure(), () -> placedLine(level, p, byPos)));
                    }
                    lines.add(guard("structures shun generation", () -> shunGeneration(level, shunSample, byPos)));
                    long overrides = StructureAdaptation.overridden() - beforeAdaptation;
                    Structure igloo = structure(level, "minecraft:igloo");
                    boolean bury = StructureRuntime.adaptation(level.dimension(), igloo) == TerrainAdjustment.BURY
                            && StructureRuntime.adaptation(server.overworld().dimension(), igloo) == igloo.terrainAdaptation();
                    lines.add((bury && overrides > 0 ? "PASS" : "FAIL") + " structures terrain adaptation: the igloo takes bury here and "
                            + igloo.terrainAdaptation().getSerializedName() + " in the overworld; the Beardifier took " + overrides
                            + " override(s) while the fixture's chunks generated");
                }
                all.forEach(c -> level.setChunkForced(c.x(), c.z(), false));
                return null;
            });
        }).thenCompose(v -> manager.deactivate(FIXTURE).thenAccept(t -> {})).exceptionally(error -> {
            lines.add("FAIL structures fixture: " + cause(error));
            return null;
        });
    }

    private String filters(ServerLevel level, ChunkGeneratorStructureState state, StructureRuntime.Applied applied) {
        Set<String> present = new HashSet<>();
        boolean pyramidSpaced = false;
        for (Holder<StructureSet> set : state.possibleStructureSets()) {
            for (StructureSet.StructureSelectionEntry e : set.value().structures()) {
                String id = id(level, e.structure().value());
                present.add(id);
                if ("minecraft:desert_pyramid".equals(id) && set.value().placement() instanceof RandomSpreadStructurePlacement r
                        && r.spacing() == 12 && r.separation() == 4) {
                    pyramidSpaced = true;
                }
            }
        }
        List<String> problems = new ArrayList<>();
        for (String gone : List.of("minecraft:mineshaft", "minecraft:mineshaft_mesa", "minecraft:trail_ruins", "minecraft:ruined_portal_desert")) {
            if (present.contains(gone)) problems.add(gone + " still placed");
        }
        long igloos = state.possibleStructureSets().stream().filter(s -> s.value().structures().stream()
                .anyMatch(e -> "minecraft:igloo".equals(id(level, e.structure().value())))).count();
        if (igloos != 1) problems.add(igloos + " sets place the igloo, expected only its forced start");
        if (!pyramidSpaced) problems.add("no desert pyramid set at spacing 12, separation 4");
        if (applied.replaced() < 1) problems.add("nothing was replaced");
        String detail = state.possibleStructureSets().size() + " set(s), " + applied.dropped() + " dropped, "
                + applied.replaced() + " replaced, groups " + applied.groupSites();
        return problems.isEmpty() ? "PASS mineshafts rejected, trail ruins excluded, desert portals replaced, the igloo only forced, pyramids at 12/4; " + detail
                : "FAIL " + problems + "; " + detail;
    }

    /** No set can place a shunned structure, and no candidate chunk of 10,000 holds one. */
    private String shunPlacements(ServerLevel level, ChunkGeneratorStructureState state) {
        Set<Structure> shunned = shunned(level);
        int entries = 0;
        long chunks = 0;
        for (Holder<StructureSet> set : state.possibleStructureSets()) {
            boolean placesShunned = set.value().structures().stream().anyMatch(e -> shunned.contains(e.structure().value()));
            if (!placesShunned) continue;
            entries++;
            for (int x = -SIDE / 2; x < SIDE / 2; x++) for (int z = -SIDE / 2; z < SIDE / 2; z++) {
                if (set.value().placement().isStructureChunk(state, x, z)) chunks++;
            }
        }
        String detail = shunned.size() + " shunned structure(s) (villages, the outpost); " + entries + " set(s) of "
                + state.possibleStructureSets().size() + " place one; " + chunks + " candidate chunk(s) of " + SIDE * SIDE;
        return entries == 0 && chunks == 0 && shunned.size() > 1 ? "PASS " + detail : "FAIL " + detail;
    }

    /** The chunks nearest spawn where vanilla's village grid would start a village in this level. */
    private List<ChunkPos> shunSample(ServerLevel level, ChunkGeneratorStructureState state) {
        StructureSet villages = level.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET)
                .getValueOrThrow(ResourceKey.create(Registries.STRUCTURE_SET, Identifier.parse("minecraft:villages")));
        List<ChunkPos> out = new ArrayList<>();
        for (int x = -128; x < 128; x++) for (int z = -128; z < 128; z++) {
            if (villages.placement().isStructureChunk(state, x, z)) out.add(new ChunkPos(x, z));
        }
        return out.stream().sorted(Comparator.comparingLong(c -> (long) c.x() * c.x() + (long) c.z() * c.z()))
                .limit(SHUN_SAMPLE).toList();
    }

    private String shunGeneration(ServerLevel level, List<ChunkPos> sample, Map<Long, LevelChunk> chunks) {
        if (sample.isEmpty()) return "FAIL vanilla's village grid has no candidate here to sample";
        Set<Structure> shunned = shunned(level);
        int withStarts = 0;
        int shunnedStarts = 0;
        Map<String, Integer> kinds = new LinkedHashMap<>();
        for (ChunkPos c : sample) {
            LevelChunk chunk = chunks.get(c.pack());
            Map<Structure, StructureStart> starts = chunk.getAllStarts();
            boolean any = false;
            for (Map.Entry<Structure, StructureStart> s : starts.entrySet()) {
                if (!s.getValue().isValid()) continue;
                any = true;
                kinds.merge(id(level, s.getKey()), 1, Integer::sum);
                if (shunned.contains(s.getKey())) shunnedStarts++;
            }
            if (any) withStarts++;
        }
        String detail = sample.size() + " chunks where vanilla's village grid starts a village generated, " + withStarts + " hold a start " + kinds
                + ", " + shunnedStarts + " shunned";
        return shunnedStarts == 0 ? "PASS " + detail : "FAIL " + detail;
    }

    private String placedLine(ServerLevel level, StructureRuntime.Placed p, Map<Long, LevelChunk> chunks) {
        LevelChunk chunk = chunks.get(ChunkPos.pack(p.chunkX(), p.chunkZ()));
        Structure s = structure(level, p.structure());
        StructureStart start = chunk.getStartForStructure(s);
        boolean ok = start != null && start.isValid();
        String where = "chunk " + p.chunkX() + "," + p.chunkZ() + (p.matched() ? " (its biome)" : " (biome check bypassed)");
        String spot;
        if ("want".equals(p.kind())) {
            var want = StructureRuntime.spec(level.dimension().identifier().toString()).orElseThrow().wants().getFirst();
            double d = StructureSites.distance(new com.customdimensions.core.ChunkPos(p.chunkX(), p.chunkZ()), 8, 8);
            ok &= d >= want.min() - 12 && d <= want.max() + 12;
            spot = String.format("%.0f blocks from spawn (wanted %d-%d)", d, want.min(), want.max());
        } else {
            var force = StructureRuntime.spec(level.dimension().identifier().toString()).orElseThrow().force().stream()
                    .filter(f -> f.structure().equals(p.structure())).findFirst().orElseThrow();
            double d = StructureSites.distance(new com.customdimensions.core.ChunkPos(p.chunkX(), p.chunkZ()), force.x(), force.z());
            ok &= force.radius() == 0 ? p.chunkX() == force.chunkX() && p.chunkZ() == force.chunkZ() : d <= force.radius() + 12;
            spot = String.format("asked for %d,%d%s, start %.0f blocks off", force.x(), force.z(),
                    force.radius() > 0 ? " within " + force.radius() : "", d);
        }
        String box = start != null && start.isValid() ? ", bounding box " + start.getBoundingBox() : ", no start in the chunk";
        return (ok ? "PASS " : "FAIL ") + where + ", " + spot + box;
    }

    // ---- density ---------------------------------------------------------------------------

    private CompletableFuture<Void> count(String slug) {
        return manager.activate(slug).thenApply(LevelManager.Activation::level).thenAccept(level -> {
            ChunkGeneratorStructureState state = level.getChunkSource().getGeneratorState();
            StructureRuntime.Applied applied = StructureRuntime.applied(level.dimension().identifier().toString()).orElseThrow();
            if (!applied.groupSites().isEmpty()) {
                candidates.put(slug.substring("selftest_density_".length()),
                        (long) applied.groupSites().values().stream().mapToInt(Integer::intValue).sum());
                return;
            }
            long n = 0;
            for (Holder<StructureSet> set : state.possibleStructureSets()) {
                for (int x = -SIDE / 2; x < SIDE / 2; x++) for (int z = -SIDE / 2; z < SIDE / 2; z++) {
                    if (set.value().placement().isStructureChunk(state, x, z)) n++;
                }
            }
            candidates.put(slug.substring("selftest_density_".length()), n);
        }).thenCompose(v -> manager.deactivate(slug).thenAccept(t -> {})).exceptionally(error -> {
            lines.add("FAIL structures density " + slug + ": " + cause(error));
            return null;
        });
    }

    private String densityLine() {
        Long dense = candidates.get("dense");
        Long normal = candidates.get("normal");
        Long sparse = candidates.get("sparse");
        Long gridNormal = candidates.get("grid_normal");
        Long gridSparse = candidates.get("grid_sparse");
        if (dense == null || normal == null || sparse == null || gridNormal == null || gridSparse == null) {
            return "FAIL structures density: missing counts " + candidates;
        }
        boolean ok = dense > normal && normal > sparse && gridNormal > gridSparse;
        return (ok ? "PASS" : "FAIL") + " structures density: noise sites within the 64-chunk radius, dense " + dense
                + " > normal " + normal + " > sparse " + sparse + "; vanilla-grid candidate chunks in " + SIDE * SIDE
                + ", normal " + gridNormal + " > sparse " + gridSparse;
    }

    // ---- helpers ------------------------------------------------------------------------------

    private Set<Structure> shunned(ServerLevel level) {
        Set<Structure> out = new HashSet<>();
        var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        registry.get(TagKey.create(Registries.STRUCTURE, Identifier.parse("minecraft:village")))
                .ifPresent(set -> set.forEach(h -> out.add(h.value())));
        out.add(structure(level, "minecraft:pillager_outpost"));
        return out;
    }

    private static Structure structure(ServerLevel level, String id) {
        return level.registryAccess().lookupOrThrow(Registries.STRUCTURE)
                .getValueOrThrow(ResourceKey.create(Registries.STRUCTURE, Identifier.parse(id)));
    }

    private static String id(ServerLevel level, Structure s) {
        Identifier key = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getKey(s);
        return key == null ? "?" : key.toString();
    }

    private static String guard(String name, java.util.concurrent.Callable<String> check) {
        try {
            return check.call().replaceFirst("^(PASS|FAIL) ", "$1 " + name + ": ");
        } catch (Exception e) {
            return "FAIL " + name + ": " + e;
        }
    }

    private CompletableFuture<List<LevelChunk>> load(ServerLevel level, List<ChunkPos> chunks) {
        chunks.forEach(c -> level.setChunkForced(c.x(), c.z(), true));
        Load l = new Load(level, chunks, new CompletableFuture<>(), new int[1]);
        loading.add(l);
        return l.done;
    }

    private void tick() {
        for (Load l : List.copyOf(loading)) {
            List<LevelChunk> got = new ArrayList<>();
            for (ChunkPos c : l.chunks) {
                LevelChunk chunk = l.level.getChunkSource().getChunkNow(c.x(), c.z());
                if (chunk != null) got.add(chunk);
            }
            if (got.size() == l.chunks.size()) {
                loading.remove(l);
                l.done.complete(got);
            } else if (++l.ticks[0] >= LOAD_CAP_TICKS) {
                loading.remove(l);
                l.done.completeExceptionally(new IllegalStateException(got.size() + "/" + l.chunks.size()
                        + " chunks loaded after " + LOAD_CAP_TICKS + " ticks"));
            }
        }
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
