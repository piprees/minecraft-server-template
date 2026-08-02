package com.customdimensions.dimension;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which (world, chunk, structure) triples carry a {@code structures.force}
 * placement, installed per world at calculator rebuild and consulted by
 * {@code ChunkGeneratorForcedStartMixin} at the head of every structure-start
 * attempt.
 *
 * <p>A registry lookup rather than a thread-local hand-off: the start attempt
 * has the chunk position and the structure in hand, so the question "is this
 * exact attempt forced?" is answerable directly — no arm/disarm choreography,
 * no staleness window, no dependence on callbacks other mods' transforms can
 * starve (see TROUBLESHOOTING.md#t24 for that failure class).
 *
 * <p>Minecraft-free on purpose, like {@code FixedStructurePlacement.Index} —
 * unit tests must never drag registry-bound static init in. Chunk keys are
 * {@code ChunkPos.toLong} values computed by callers.
 */
public final class ForcedStartOverride {

    private ForcedStartOverride() {
    }

    /** A world's forced placements: display name + chunkKey -> structure ids. */
    record WorldForces(String dimensionName, Map<Long, Set<String>> byChunk) {
    }

    private static final Map<String, WorldForces> FORCED = new ConcurrentHashMap<>();

    /**
     * Log dedupe for the generated/failed INFO/WARN lines. A forced placement
     * is a handful of positions per dimension, so this stays tiny in practice;
     * the cap only stops a pathological config turning a log line into a
     * memory leak.
     */
    static final int LOG_CAP = 4096;

    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    /**
     * Installs (or clears, when the map is empty) a world's forced placements.
     * Called for every managed world at calculator rebuild, so a config that
     * drops its forces also drops its registry entries.
     */
    public static void install(String worldId, String dimensionName,
                               Map<Long, Set<String>> byChunk) {
        if (byChunk == null || byChunk.isEmpty()) {
            FORCED.remove(worldId);
            return;
        }
        Map<Long, Set<String>> copy = new HashMap<>();
        byChunk.forEach((key, ids) -> copy.put(key, Set.copyOf(ids)));
        FORCED.put(worldId, new WorldForces(dimensionName, Map.copyOf(copy)));
    }

    /** Whether this exact (world, chunk, structure) attempt is forced. */
    public static boolean isForced(String worldId, long chunkKey, String structureId) {
        WorldForces forces = FORCED.get(worldId);
        if (forces == null || structureId == null) {
            return false;
        }
        Set<String> ids = forces.byChunk().get(chunkKey);
        return ids != null && ids.contains(structureId);
    }

    /** The dimension's display name for log lines, or the world id itself. */
    public static String dimensionName(String worldId) {
        WorldForces forces = FORCED.get(worldId);
        return forces != null ? forces.dimensionName() : worldId;
    }

    /**
     * True the first time this exact forced placement is seen generating.
     * Pure enough to unit test: no Minecraft types, no registry lookups.
     */
    public static boolean firstSighting(String dimensionName, String structureId,
                                        int chunkX, int chunkZ) {
        return logOnce("ok/" + dimensionName + '/' + structureId + '@' + chunkX + ',' + chunkZ);
    }

    /** True the first time this exact forced placement is seen failing. */
    public static boolean firstFailure(String dimensionName, String structureId,
                                       int chunkX, int chunkZ) {
        return logOnce("fail/" + dimensionName + '/' + structureId + '@' + chunkX + ',' + chunkZ);
    }

    private static boolean logOnce(String key) {
        if (LOGGED.size() >= LOG_CAP) {
            return false;
        }
        return LOGGED.add(key);
    }

    /** Set of forced chunk keys for one build, exposed for unit tests. */
    static Set<Long> forcedChunks(String worldId) {
        WorldForces forces = FORCED.get(worldId);
        return forces == null ? Set.of() : Set.copyOf(forces.byChunk().keySet());
    }

    /** Test hook — the registry and log-dedupe set are process-lifetime state. */
    static void resetForTests() {
        FORCED.clear();
        LOGGED.clear();
    }

    /** Builds the chunk map install() wants from parsed forced entries. */
    public static Map<Long, Set<String>> byChunk(
            java.util.List<ForcedEntry> entries) {
        Map<Long, Set<String>> map = new HashMap<>();
        for (ForcedEntry entry : entries) {
            map.computeIfAbsent(entry.chunkKey(), k -> new HashSet<>())
                    .add(entry.structureId());
        }
        return map;
    }

    /** One parsed forced placement: structure id + chunk key. */
    public record ForcedEntry(String structureId, long chunkKey) {
    }
}
