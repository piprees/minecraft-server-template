package com.customdimensions.command;

import com.customdimensions.MultiverseServer;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.entry.RegistryEntryList;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.structure.Structure;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Async locate biome/structure for seed rolling.
 *
 * Biome locates run off-thread (noise sampling is read-only, safe without
 * c2me). Structure locates MUST run on the server thread — vanilla's
 * StructureStartsStorage uses a plain HashMap that throws CME from any
 * other thread. Structures are queued and drained one-per-tick from
 * END_SERVER_TICK to keep RCON responsive.
 */
public class LocateManager {

    private static final LocateManager INSTANCE = new LocateManager();
    private static final int MAX_RESULTS = 256;

    private final ConcurrentHashMap<UUID, LocateResult> results = new ConcurrentHashMap<>();
    private final ExecutorService biomeExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "CustomDimensions-Locate-Biome");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService timeoutScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "CustomDimensions-Locate-Timeout");
        t.setDaemon(true);
        return t;
    });

    // Structure locates must run on the server thread (HashMap CME).
    private final ConcurrentLinkedQueue<PendingStructureLocate> pendingStructureLocates = new ConcurrentLinkedQueue<>();

    private record PendingStructureLocate(UUID id, ServerWorld world, Identifier structureId,
                                          boolean isTag, int timeoutSeconds) {}

    public static LocateManager getInstance() {
        return INSTANCE;
    }

    public UUID submitBiomeLocate(ServerWorld world, Identifier biomeId, int timeoutSeconds) {
        UUID id = UUID.randomUUID();
        LocateResult result = new LocateResult();
        results.put(id, result);
        evictOldest();

        Future<?> future = biomeExecutor.submit(() -> {
            try {
                RegistryKey<Biome> biomeKey = RegistryKey.of(RegistryKeys.BIOME, biomeId);
                Registry<Biome> biomeRegistry = world.getRegistryManager().get(RegistryKeys.BIOME);
                Optional<RegistryEntry.Reference<Biome>> entry = biomeRegistry.getEntry(biomeKey);
                if (entry.isEmpty()) {
                    result.complete(LocateResult.Status.ERROR, "biome not in registry: " + biomeId);
                    return;
                }
                BlockPos origin = BlockPos.ORIGIN;
                com.mojang.datafixers.util.Pair<BlockPos, RegistryEntry<Biome>> found =
                        world.locateBiome(e -> e.matchesKey(biomeKey), origin, 6400, 32, 64);
                if (found == null) {
                    result.complete(LocateResult.Status.NOT_FOUND, null);
                } else {
                    BlockPos pos = found.getFirst();
                    int dx = pos.getX() - origin.getX();
                    int dz = pos.getZ() - origin.getZ();
                    int distance = (int) Math.sqrt(dx * (long) dx + dz * (long) dz);
                    result.completeFound(distance, pos.getX(), pos.getY(), pos.getZ());
                }
            } catch (Exception e) {
                MultiverseServer.LOGGER.error("Async biome locate failed for {}", biomeId, e);
                result.complete(LocateResult.Status.ERROR, e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        });

        scheduleTimeout(id, result, future, timeoutSeconds);
        return id;
    }

    public UUID submitStructureLocate(ServerWorld world, Identifier structureId, boolean isTag, int timeoutSeconds) {
        UUID id = UUID.randomUUID();
        LocateResult result = new LocateResult();
        results.put(id, result);
        evictOldest();
        pendingStructureLocates.add(new PendingStructureLocate(id, world, structureId, isTag, timeoutSeconds));
        scheduleTimeout(id, result, null, timeoutSeconds);
        return id;
    }

    /**
     * Drain ONE pending structure locate on the server thread per tick.
     * Structure locates use vanilla's HashMap-backed StructureStartsStorage
     * which throws ConcurrentModificationException from any other thread.
     * One per tick keeps RCON responsive (~2-5s per locate).
     */
    public void processPendingStructureLocates() {
        PendingStructureLocate task = pendingStructureLocates.poll();
        if (task == null) return;
        LocateResult result = results.get(task.id());
        if (result == null || result.status != LocateResult.Status.PENDING) return;

        try {
            Registry<Structure> structureRegistry = task.world().getRegistryManager().get(RegistryKeys.STRUCTURE);
            RegistryEntryList<Structure> entries;

            if (task.isTag()) {
                TagKey<Structure> tag = TagKey.of(RegistryKeys.STRUCTURE, task.structureId());
                Optional<RegistryEntryList.Named<Structure>> tagList = structureRegistry.getEntryList(tag);
                if (tagList.isEmpty()) {
                    result.complete(LocateResult.Status.ERROR, "structure tag not found: #" + task.structureId());
                    return;
                }
                entries = tagList.get();
            } else {
                RegistryKey<Structure> key = RegistryKey.of(RegistryKeys.STRUCTURE, task.structureId());
                Optional<RegistryEntry.Reference<Structure>> entry = structureRegistry.getEntry(key);
                if (entry.isEmpty()) {
                    result.complete(LocateResult.Status.ERROR, "structure not in registry: " + task.structureId());
                    return;
                }
                entries = RegistryEntryList.of(entry.get());
            }

            BlockPos origin = BlockPos.ORIGIN;
            com.mojang.datafixers.util.Pair<BlockPos, RegistryEntry<Structure>> found =
                    task.world().getChunkManager().getChunkGenerator().locateStructure(
                            task.world(), entries, origin, 100, false);

            if (found == null) {
                result.complete(LocateResult.Status.NOT_FOUND, null);
            } else {
                BlockPos pos = found.getFirst();
                int dx = pos.getX() - origin.getX();
                int dz = pos.getZ() - origin.getZ();
                int distance = (int) Math.sqrt(dx * (long) dx + dz * (long) dz);
                result.completeFound(distance, pos.getX(), pos.getY(), pos.getZ());
            }
        } catch (Exception e) {
            MultiverseServer.LOGGER.error("Structure locate failed for {} (id: {})",
                    task.structureId(), task.id(), e);
            result.complete(LocateResult.Status.ERROR, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public LocateResult getResult(UUID id) {
        return results.get(id);
    }

    public String formatResult(UUID id) {
        LocateResult r = results.get(id);
        if (r == null) {
            return "locate:" + id + " unknown";
        }
        return switch (r.status) {
            case PENDING -> "locate:" + id + " pending";
            case DONE -> "locate:" + id + " done " + r.distance + " " + r.x + " " + r.y + " " + r.z;
            case NOT_FOUND -> "locate:" + id + " not_found";
            case TIMED_OUT -> "locate:" + id + " timed_out";
            case ERROR -> "locate:" + id + " error " + (r.error != null ? r.error : "unknown");
        };
    }

    public void shutdown() {
        biomeExecutor.shutdownNow();
        timeoutScheduler.shutdownNow();
        results.clear();
    }

    private void scheduleTimeout(UUID id, LocateResult result, Future<?> future, int timeoutSeconds) {
        timeoutScheduler.schedule(() -> {
            if (result.status == LocateResult.Status.PENDING) {
                result.complete(LocateResult.Status.TIMED_OUT, null);
                if (future != null) future.cancel(true);
                MultiverseServer.LOGGER.warn("Locate timed out after {}s (id: {})", timeoutSeconds, id);
            }
        }, timeoutSeconds, TimeUnit.SECONDS);
    }

    private void evictOldest() {
        if (results.size() > MAX_RESULTS) {
            results.entrySet().removeIf(e ->
                    e.getValue().status != LocateResult.Status.PENDING
                            && results.size() > MAX_RESULTS / 2);
        }
    }

    public static class LocateResult {
        public enum Status { PENDING, DONE, NOT_FOUND, TIMED_OUT, ERROR }

        public volatile Status status = Status.PENDING;
        public volatile int distance;
        public volatile int x, y, z;
        public volatile String error;

        void complete(Status status, String error) {
            this.error = error;
            this.status = status;
        }

        void completeFound(int distance, int x, int y, int z) {
            this.distance = distance;
            this.x = x;
            this.y = y;
            this.z = z;
            this.status = Status.DONE;
        }
    }
}
