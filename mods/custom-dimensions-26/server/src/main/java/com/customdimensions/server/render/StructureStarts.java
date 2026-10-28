package com.customdimensions.server.render;

import com.customdimensions.core.score.RollFacts;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

/**
 * Where a seed's structures start, read from the level's placement state
 * with its structure control ({@link Sampler#structureState()}) without
 * generating a chunk. A start is a chunk the placement picks, with the
 * structure chosen from its set as {@code ChunkGenerator.createStructures}
 * chooses (weighted, seeded by the chunk, the next pick tried when one does
 * not start) and confirmed as generation confirms it: the structure's own
 * generation point, biome and terrain checks ({@link Sampler#starts}).
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

    /** The most starts kept of each structure for a contact sheet's markers, and of end cities for scoring. */
    public static final int CAP = 64;
    /** Sites confirmed together, nearest first; small, so few are confirmed past a structure's cap. */
    static final int BATCH = 32;

    /**
     * As {@link #find(Sampler, ChunkGeneratorStructureState, Predicate, int, int, int)}, confirming starts on
     * {@code pool} when given, nearest sites first, keeping at most {@code cap} starts of each structure.
     */
    public static List<RollFacts.Start> find(Sampler sampler, ChunkGeneratorStructureState state, Predicate<String> wanted,
                                             int cx, int cz, int radius, ExecutorService pool, int cap) {
        return find(sampler, state, wanted, cx, cz, radius, pool, id -> cap);
    }

    /** What a roll's scoring reads: the nearest start of each structure, and up to {@link #CAP} end cities, which it counts. */
    public static int scoringCap(String id) {
        return "minecraft:end_city".equals(id) ? CAP : 1;
    }

    /** As above, keeping at most {@code capOf.applyAsInt(id)} starts of each structure. */
    public static List<RollFacts.Start> find(Sampler sampler, ChunkGeneratorStructureState state, Predicate<String> wanted,
                                             int cx, int cz, int radius, ExecutorService pool,
                                             java.util.function.ToIntFunction<String> capOf) {
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
        List<RollFacts.Start> out = new ArrayList<>();
        int next = 0;
        while (next < sites.size()) {
            List<Site> batch = new ArrayList<>();
            while (next < sites.size() && batch.size() < BATCH) {
                Site site = sites.get(next++);
                if (site.entries().stream().anyMatch(e -> wanted.test(id(e.structure())) && kept.getOrDefault(id(e.structure()), 0) < capOf.applyAsInt(id(e.structure())))) {
                    batch.add(site);
                }
            }
            java.util.concurrent.atomic.AtomicReferenceArray<Holder<Structure>> chosen = new java.util.concurrent.atomic.AtomicReferenceArray<>(batch.size());
            run(batch.size(), pool, i -> chosen.set(i, choose(batch.get(i).entries(), seed, batch.get(i).chunk(),
                    structure -> sampler.starts(structure, batch.get(i).chunk()))));
            for (int i = 0; i < batch.size(); i++) {
                Site site = batch.get(i);
                Holder<Structure> picked = chosen.get(i);
                if (picked == null || !wanted.test(id(picked))) continue;
                int n = kept.merge(id(picked), 1, Integer::sum);
                if (n <= capOf.applyAsInt(id(picked))) out.add(new RollFacts.Start(id(picked), site.chunk().getMiddleBlockX(), site.chunk().getMiddleBlockZ(), site.distance()));
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
