package com.customdimensions.server.render;

import com.customdimensions.core.score.RollFacts;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

/**
 * Where a seed's structures would start, read from the generator's own
 * placement state ({@link Sampler#structureState()}) without generating a
 * chunk. A start is a chunk the placement picks, with the structure chosen
 * from its set as {@code ChunkGenerator.createStructures} chooses (weighted,
 * seeded by the chunk) and whose biome at the chunk's middle, on the sampled
 * surface or (for a structure placed below the surface step) in the column
 * under it, is one the structure allows. Checks the structure makes later — a
 * terrain height, a jigsaw that does not fit — are not made, so a start is a
 * candidate that generation can still drop.
 */
public final class StructureStarts {

    private StructureStarts() {}

    /** Every structure id the state's structure sets can place. */
    public static Set<String> pool(ChunkGeneratorStructureState state) {
        Set<String> out = new LinkedHashSet<>();
        for (Holder<StructureSet> set : state.possibleStructureSets()) {
            for (StructureSet.StructureSelectionEntry e : set.value().structures()) out.add(id(e.structure()));
        }
        return out;
    }

    /** The starts of the structures {@code wanted} accepts, within {@code radius} blocks of {@code (cx, cz)}, nearest first. */
    public static List<RollFacts.Start> find(Sampler sampler, ChunkGeneratorStructureState state, Predicate<String> wanted,
                                             int cx, int cz, int radius) {
        return find(sampler, state, wanted, cx, cz, radius, null, Integer.MAX_VALUE);
    }

    /** The most starts a roll keeps of each structure: every criterion reads the nearest, or (elytra) up to 60. */
    public static final int CAP = 64;
    /** Sites whose biomes are sampled together, nearest first. */
    static final int BATCH = 256;

    /** What the biome check reads at a chunk's middle: the surface and its biome. */
    record Probe(int x, int z, int surface, Holder<Biome> biome) {}

    /**
     * As {@link #find(Sampler, ChunkGeneratorStructureState, Predicate, int, int, int)}, sampling biomes on
     * {@code pool} when given, nearest sites first, keeping at most {@code cap} starts of each structure.
     */
    public static List<RollFacts.Start> find(Sampler sampler, ChunkGeneratorStructureState state, Predicate<String> wanted,
                                             int cx, int cz, int radius, ExecutorService pool, int cap) {
        int minChunk = Math.floorDiv(cx - radius, 16);
        int maxChunk = Math.floorDiv(cx + radius, 16);
        int minChunkZ = Math.floorDiv(cz - radius, 16);
        int maxChunkZ = Math.floorDiv(cz + radius, 16);
        long seed = state.getLevelSeed();
        record Site(List<StructureSet.StructureSelectionEntry> entries, ChunkPos chunk, double distance) {}
        List<Site> sites = new ArrayList<>();
        for (Holder<StructureSet> set : state.possibleStructureSets()) {
            List<StructureSet.StructureSelectionEntry> entries = set.value().structures();
            if (entries.stream().noneMatch(e -> wanted.test(id(e.structure())))) continue;
            for (ChunkPos chunk : candidates(state, set.value().placement(), seed, minChunk, maxChunk, minChunkZ, maxChunkZ)) {
                double distance = Math.hypot(chunk.getMiddleBlockX() - cx, chunk.getMiddleBlockZ() - cz);
                if (distance <= radius) sites.add(new Site(entries, chunk, distance));
            }
        }
        sites.sort(java.util.Comparator.comparingDouble(Site::distance));
        Map<String, Integer> kept = new HashMap<>();
        Map<Long, Probe> probes = new ConcurrentHashMap<>();
        List<RollFacts.Start> out = new ArrayList<>();
        int next = 0;
        while (next < sites.size()) {
            List<Site> batch = new ArrayList<>();
            while (next < sites.size() && batch.size() < BATCH) {
                Site site = sites.get(next++);
                if (site.entries().stream().anyMatch(e -> wanted.test(id(e.structure())) && kept.getOrDefault(id(e.structure()), 0) < cap)) {
                    batch.add(site);
                }
            }
            List<Long> todo = batch.stream().map(x -> x.chunk().pack()).distinct().filter(c -> !probes.containsKey(c)).toList();
            run(todo.size(), pool, i -> probes.put(todo.get(i), probe(sampler, todo.get(i))));
            for (Site site : batch) {
                Probe probe = probes.get(site.chunk().pack());
                Holder<Structure> chosen = choose(site.entries(), seed, site.chunk(), structure -> allowed(sampler, structure, probe));
                if (chosen == null || !wanted.test(id(chosen))) continue;
                int n = kept.merge(id(chosen), 1, Integer::sum);
                if (n <= cap) out.add(new RollFacts.Start(id(chosen), probe.x(), probe.z(), site.distance()));
            }
        }
        return out;
    }

    private static void run(int n, ExecutorService pool, java.util.function.IntConsumer task) {
        if (pool == null || n < 16) {
            for (int i = 0; i < n; i++) task.accept(i);
            return;
        }
        int parts = 16;
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int p = 0; p < parts; p++) {
            int part = p;
            futures.add(CompletableFuture.runAsync(() -> {
                for (int i = part; i < n; i += parts) task.accept(i);
            }, pool));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
    }

    private static Probe probe(Sampler sampler, long packed) {
        ChunkPos chunk = ChunkPos.unpack(packed);
        Sampler.Column c = sampler.sample(chunk.getMiddleBlockX(), chunk.getMiddleBlockZ());
        return new Probe(c.x(), c.z(), c.height(), c.biome());
    }

    /**
     * Whether a structure's biomes allow the chunk: the surface biome, or for
     * a structure placed below the surface step, the biome anywhere in the
     * column under the surface, at an 8-block step.
     */
    static boolean allowed(Sampler sampler, Holder<Structure> structure, Probe probe) {
        var biomes = structure.value().biomes();
        if (biomes.contains(probe.biome())) return true;
        if (structure.value().step() == GenerationStep.Decoration.SURFACE_STRUCTURES) return false;
        for (int y = probe.surface() - 8; y >= sampler.minY(); y -= 8) {
            if (biomes.contains(sampler.biome(probe.x(), y, probe.z()))) return true;
        }
        return false;
    }

    static List<ChunkPos> candidates(ChunkGeneratorStructureState state, StructurePlacement placement, long seed,
                                     int minX, int maxX, int minZ, int maxZ) {
        List<ChunkPos> out = new ArrayList<>();
        if (placement instanceof RandomSpreadStructurePlacement spread) {
            int spacing = spread.spacing();
            for (int rx = Math.floorDiv(minX, spacing); rx <= Math.floorDiv(maxX, spacing); rx++) {
                for (int rz = Math.floorDiv(minZ, spacing); rz <= Math.floorDiv(maxZ, spacing); rz++) {
                    ChunkPos pos = spread.getPotentialStructureChunk(seed, rx * spacing, rz * spacing);
                    if (pos.x() < minX || pos.x() > maxX || pos.z() < minZ || pos.z() > maxZ) continue;
                    if (placement.isStructureChunk(state, pos.x(), pos.z())) out.add(pos);
                }
            }
        } else if (placement instanceof ConcentricRingsStructurePlacement rings) {
            for (ChunkPos pos : state.getRingPositionsFor(rings)) {
                if (pos.x() < minX || pos.x() > maxX || pos.z() < minZ || pos.z() > maxZ) continue;
                if (placement.isStructureChunk(state, pos.x(), pos.z())) out.add(pos);
            }
        } else {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (placement.isStructureChunk(state, x, z)) out.add(new ChunkPos(x, z));
                }
            }
        }
        return out;
    }

    /** The entry {@code createStructures} would generate at {@code chunk}, by weight and biome; null when none fits. */
    static Holder<Structure> choose(List<StructureSet.StructureSelectionEntry> entries, long seed, ChunkPos chunk,
                                    Predicate<Holder<Structure>> fits) {
        if (entries.size() == 1) {
            Holder<Structure> only = entries.getFirst().structure();
            return fits.test(only) ? only : null;
        }
        List<StructureSet.StructureSelectionEntry> left = new ArrayList<>(entries);
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(0L));
        random.setLargeFeatureSeed(seed, chunk.x(), chunk.z());
        int total = 0;
        for (StructureSet.StructureSelectionEntry e : left) total += e.weight();
        while (!left.isEmpty()) {
            int pick = random.nextInt(total);
            int index = 0;
            for (StructureSet.StructureSelectionEntry e : left) {
                pick -= e.weight();
                if (pick < 0) break;
                index++;
            }
            StructureSet.StructureSelectionEntry entry = left.get(index);
            if (fits.test(entry.structure())) return entry.structure();
            left.remove(index);
            total -= entry.weight();
        }
        return null;
    }

    static String id(Holder<Structure> structure) {
        return structure.unwrapKey().map(k -> k.identifier().toString()).orElse("unregistered");
    }
}
