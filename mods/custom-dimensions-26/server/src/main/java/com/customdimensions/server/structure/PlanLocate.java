package com.customdimensions.server.structure;

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheckResult;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Vanilla's structure search ({@code ChunkGenerator.findNearestMapStructure}:
 * {@code /locate}, explorer and treasure maps, eyes of ender, dolphins) walks
 * only concentric-ring and random-spread placements. This walks the plan's own
 * ({@link StructurePlacements.Noise} sites, {@link StructurePlacements.Fixed}
 * forced starts and wants) nearest first, within the caller's radius in the
 * placement's own sectors, and checks each site for a real start as vanilla
 * does: the stored or predicted start, then the chunk at structure starts.
 * Only sites nearer than vanilla's own answer are checked. An answer
 * outside the level's world border, vanilla's or the plan's, is never given:
 * a map, an eye or {@code /locate} would point past the edge of the world.
 */
public final class PlanLocate {

    /** The sector a single-chunk placement's radius counts in, in chunks. */
    static final int FIXED_SPACING = 16;

    /** A site to check: its chunk, its distance from the search's origin, and what may start there. */
    record Candidate(ChunkPos chunk, double distSqr, StructurePlacement placement, Set<Holder<Structure>> structures) {}

    private PlanLocate() {
    }

    /** The plan's sites of {@code placement}, or null when it is vanilla's. */
    static List<com.customdimensions.core.ChunkPos> sites(StructurePlacement placement) {
        if (placement instanceof StructurePlacements.Noise noise) return noise.sites();
        if (placement instanceof StructurePlacements.Fixed fixed) {
            return List.of(new com.customdimensions.core.ChunkPos(fixed.chunkX(), fixed.chunkZ()));
        }
        return null;
    }

    /** The sector the caller's radius counts in for {@code placement}, in chunks. */
    static int spacing(StructurePlacement placement) {
        if (placement instanceof StructurePlacements.Noise noise) return Math.max(1, noise.index().spacing());
        return FIXED_SPACING;
    }

    /**
     * The sites within {@code maxSearchRadius} sectors (Chebyshev, in chunks)
     * of {@code origin}'s chunk and nearer than {@code bestDistSqr}, nearest
     * first by the distance vanilla compares: {@code origin} to the
     * placement's locate position, which {@code inside} holds for.
     */
    static List<Candidate> candidates(Map<StructurePlacement, Set<Holder<Structure>>> scans, BlockPos origin,
                                      int maxSearchRadius, double bestDistSqr, java.util.function.Predicate<BlockPos> inside) {
        int cx = SectionPos.blockToSectionCoord(origin.getX());
        int cz = SectionPos.blockToSectionCoord(origin.getZ());
        List<Candidate> out = new ArrayList<>();
        for (Map.Entry<StructurePlacement, Set<Holder<Structure>>> e : scans.entrySet()) {
            StructurePlacement placement = e.getKey();
            long bound = (long) maxSearchRadius * spacing(placement);
            for (com.customdimensions.core.ChunkPos site : sites(placement)) {
                if (Math.max(Math.abs((long) site.x() - cx), Math.abs((long) site.z() - cz)) > bound) continue;
                ChunkPos chunk = new ChunkPos(site.x(), site.z());
                BlockPos at = placement.getLocatePos(chunk);
                double d = origin.distSqr(at);
                if (d < bestDistSqr && inside.test(at)) out.add(new Candidate(chunk, d, placement, e.getValue()));
            }
        }
        out.sort(Comparator.comparingDouble(Candidate::distSqr));
        return out;
    }

    /** The plan's placements among those of {@code wanted}, each with the wanted structures it places. */
    static Map<StructurePlacement, Set<Holder<Structure>>> scans(ChunkGeneratorStructureState state, HolderSet<Structure> wanted) {
        Map<StructurePlacement, Set<Holder<Structure>>> out = new IdentityHashMap<>();
        for (Holder<Structure> structure : wanted) {
            for (StructurePlacement placement : state.getPlacementsForStructure(structure)) {
                if (sites(placement) != null) out.computeIfAbsent(placement, p -> new LinkedHashSet<>()).add(structure);
            }
        }
        return out;
    }

    /** {@code found}, or null when its position lies outside {@code border}. */
    static Pair<BlockPos, Holder<Structure>> inside(net.minecraft.world.level.border.WorldBorder border,
                                                     Pair<BlockPos, Holder<Structure>> found) {
        return found == null || border.isWithinBounds(found.getFirst()) ? found : null;
    }

    /**
     * {@code found} (vanilla's answer, or null) when it lies inside the level's
     * world border, or the plan's nearest start inside the border when it is
     * nearer; null when neither is inside.
     */
    public static Pair<BlockPos, Holder<Structure>> nearer(ServerLevel level, HolderSet<Structure> wanted, BlockPos origin,
                                                          int maxSearchRadius, boolean createReference,
                                                          Pair<BlockPos, Holder<Structure>> vanilla) {
        net.minecraft.world.level.border.WorldBorder border = level.getWorldBorder();
        Pair<BlockPos, Holder<Structure>> found = inside(border, vanilla);
        if (!level.getServer().getWorldGenSettings().options().generateStructures()) return found;
        Map<StructurePlacement, Set<Holder<Structure>>> scans = scans(level.getChunkSource().getGeneratorState(), wanted);
        if (scans.isEmpty()) return found;
        double best = found == null ? Double.MAX_VALUE : origin.distSqr(found.getFirst());
        for (Candidate c : candidates(scans, origin, maxSearchRadius, best, border::isWithinBounds)) {
            Pair<BlockPos, Holder<Structure>> start = startAt(level, c, createReference);
            if (start != null) return start;
        }
        return found;
    }

    /** A wanted structure's start at the candidate's chunk, as vanilla's {@code getStructureGeneratingAt} reads it. */
    private static Pair<BlockPos, Holder<Structure>> startAt(ServerLevel level, Candidate c, boolean createReference) {
        StructureManager manager = level.structureManager();
        for (Holder<Structure> structure : c.structures()) {
            StructureCheckResult fast = manager.checkStructurePresence(c.chunk(), structure.value(), c.placement(), createReference);
            if (fast == StructureCheckResult.START_NOT_PRESENT) continue;
            if (!createReference && fast == StructureCheckResult.START_PRESENT) {
                return Pair.of(c.placement().getLocatePos(c.chunk()), structure);
            }
            ChunkAccess chunk = level.getChunk(c.chunk().x(), c.chunk().z(), ChunkStatus.STRUCTURE_STARTS);
            StructureStart start = manager.getStartForStructure(structure.value(), chunk);
            if (start != null && start.isValid() && (!createReference || reference(manager, start))) {
                return Pair.of(c.placement().getLocatePos(start.getChunkPos()), structure);
            }
        }
        return null;
    }

    private static boolean reference(StructureManager manager, StructureStart start) {
        if (!start.canBeReferenced()) return false;
        manager.addReference(start);
        return true;
    }
}
