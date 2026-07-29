package com.customdimensions.dimension;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The hand-off between "this chunk was claimed by a forced placement" and
 * "skip the biome predicate for the structure start about to be created".
 *
 * <p>Why a hand-off is needed at all: {@code structures.force} is expressed as
 * a {@link FixedStructurePlacement}, and a StructurePlacement only decides
 * WHICH CHUNKS ARE CANDIDATES. The structure's own biome list is checked
 * afterwards, in the {@code Predicate<RegistryEntry<Biome>>} that
 * {@code ChunkGenerator.trySetStructureStart} builds from
 * {@code Structure.getValidBiomes()} and hands to
 * {@code Structure.createStructureStart}. By the time that predicate is built,
 * the placement is out of scope — {@code trySetStructureStart} receives a
 * {@code StructureSet.WeightedEntry}, not the set — so the fact that the chunk
 * was forced has to be carried across the two calls.
 *
 * <p>Vanilla puts them adjacent and in a fixed order (1.21.1
 * {@code ChunkGenerator.setStructureStarts}):
 *
 * <pre>
 *   if (structurePlacement.shouldGenerate(placementCalculator, chunkPos.x, chunkPos.z)) {
 *       ... this.trySetStructureStart(...)      // one call, or a weighted loop
 *   }
 * </pre>
 *
 * so arming on EVERY {@code StructurePlacement.shouldGenerate} return — with
 * the dimension name for a fixed placement that said yes, and null for
 * everything else — leaves the value freshly correct immediately before each
 * start attempt. Arming only on the fixed-and-true case would go stale:
 * {@code StructureAccessor.getStructurePresence} calls {@code shouldGenerate}
 * on the locate path with no start attempt behind it, and the chunk it then
 * generates would inherit the arm. Disarming for every placement closes that.
 *
 * <p>Thread-local because chunk generation runs on the worker pool while
 * {@code /locate} runs on the server thread. Reentrancy is safe: an exclusion
 * zone makes {@code shouldGenerate} call {@code shouldGenerate} on another
 * placement, but the nested call RETURNS first, so the outer placement's
 * answer is always the one left armed.
 *
 * <p>Minecraft-free on purpose, like {@code FixedStructurePlacement.Index} —
 * unit tests must never drag registry-bound static init in.
 */
public final class ForcedBiomeBypass {

    private ForcedBiomeBypass() {
    }

    /** Dimension name of the forced placement that claimed the current chunk, or null. */
    private static final ThreadLocal<String> ARMED = new ThreadLocal<>();

    /** Dimension name the bypass was actually handed to, or null. */
    private static final ThreadLocal<String> APPLIED = new ThreadLocal<>();

    /**
     * Log dedupe. A forced placement is a handful of positions per dimension,
     * so this stays tiny in practice; the cap only stops a pathological config
     * turning an INFO line into a memory leak.
     */
    static final int LOG_CAP = 4096;

    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    /** Called for every placement, on every chunk. Null means "not forced". */
    public static void arm(String dimensionName) {
        ARMED.set(dimensionName);
    }

    /** The dimension whose forced placement claimed this chunk, or null. */
    public static String armed() {
        return ARMED.get();
    }

    /**
     * Records whether the bypass was handed to the start attempt now running.
     * Written on every attempt (not only the bypassed ones) so a value can
     * never survive into the next one.
     */
    public static void markApplied(String dimensionName) {
        APPLIED.set(dimensionName);
    }

    /** The dimension the bypass was applied for, or null; clears as it reads. */
    public static String consumeApplied() {
        String applied = APPLIED.get();
        if (applied != null) {
            APPLIED.set(null);
        }
        return applied;
    }

    /**
     * True the first time this exact forced placement is seen generating.
     * Pure enough to unit test: no Minecraft types, no registry lookups.
     */
    public static boolean firstSighting(String dimensionName, String structureId,
                                        int chunkX, int chunkZ) {
        if (LOGGED.size() >= LOG_CAP) {
            return false;
        }
        return LOGGED.add(dimensionName + '/' + structureId + '@' + chunkX + ',' + chunkZ);
    }

    /** Test hook — the log-dedupe set is process-lifetime state otherwise. */
    static void resetForTests() {
        LOGGED.clear();
        ARMED.remove();
        APPLIED.remove();
    }
}
