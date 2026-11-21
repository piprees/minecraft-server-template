package com.customdimensions.server.structure;

import com.customdimensions.core.structure.SetPieceSetting;
import com.customdimensions.core.structure.SetPieceShapes;
import com.customdimensions.core.structure.SetPieceSpec;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Approaches (lore/placement.md G8) joined to a start as it is made, for a set
 * piece's own {@code approach} and for any structure {@code structures.approaches}
 * names: one shape part per approach, laid out from the start's bounding box
 * towards its target over the base columns (the surface near the box, or the
 * floor nearest its bottom in a world with a roof), drawn column by column like
 * every shape.
 */
public final class Approaches {

    private static final Map<String, Integer> ADDED = new ConcurrentHashMap<>();
    static final int NEAR = 48;

    private Approaches() {
    }

    /** Approach parts added since the server started, by structure id. */
    public static Map<String, Integer> added() {
        return Map.copyOf(ADDED);
    }

    public static StructureStart with(ResourceKey<Level> level, Holder<Structure> holder, StructureStart start, ChunkGenerator generator,
                                      RandomState random, LevelHeightAccessor height) {
        if (!start.isValid()) return start;
        Structure structure = holder.value();
        List<SetPieceSetting.Approach> list;
        Map<String, List<SetPieceSpec.Weighted>> palette = Map.of();
        com.customdimensions.core.structure.Age age = null;
        String key = holder.getRegisteredName();
        if (structure instanceof SetPieceStructure piece) {
            list = piece.spec().setting().approaches();
            palette = piece.spec().palette();
            age = piece.spec().age();
        } else {
            list = StructureRuntime.approaches(level, structure);
        }
        if (list.isEmpty()) return start;
        BoundingBox box = start.getBoundingBox();
        int cx = (box.minX() + box.maxX()) / 2;
        int cz = (box.minZ() + box.maxZ()) / 2;
        int floorY = box.minY();
        int surface = generator.getBaseHeight(cx, cz, Heightmap.Types.WORLD_SURFACE_WG, height, random);
        boolean scan = Math.abs(surface - floorY) > NEAR;
        SetPieceShapes.Heights base = (x, z) -> scan ? floorNear(generator, height, random, x, z, floorY) : surface(generator, height, random, x, z);
        List<StructurePiece> pieces = new ArrayList<>(start.getPieces());
        long seed = start.getChunkPos().pack() * 0x9E3779B97F4A7C15L ^ key.hashCode();
        for (int i = 0; i < list.size(); i++) {
            SetPieceSetting.Approach a = list.get(i);
            int[] target = target(level, a, cx, cz, box);
            List<int[]> ops = a.ops(base, new int[] {box.minX(), box.minZ(), box.maxX(), box.maxZ()}, target[0], target[1], seed + i);
            if (ops.isEmpty()) continue;
            SetPieceShapes.Shape shape = new SetPieceShapes.Shape("approach", ops.getFirst()[0], floorY, ops.getFirst()[2], 0, seed + i,
                    a.toJson(), palette, age, ops);
            pieces.add(SetPiecePart.shape(key, floorY, shape, scan));
            ADDED.merge(key, 1, Integer::sum);
        }
        if (pieces.size() == start.getPieces().size()) return start;
        return new StructureStart(structure, start.getChunkPos(), start.getReferences(), new PiecesContainer(pieces));
    }

    /** Where an approach heads: its offset from the box's centre, spawn, or the level's nearest other site (spawn when none). */
    static int[] target(ResourceKey<Level> level, SetPieceSetting.Approach a, int cx, int cz, BoundingBox box) {
        if (a.offset() != null) return new int[] {cx + a.offset()[0], cz + a.offset()[1]};
        int[] spawn = StructureRuntime.spawn(level);
        if ("spawn".equals(a.to())) return spawn;
        int[] near = StructureRuntime.nearestTarget(level, cx, cz, box);
        return near != null ? near : spawn;
    }

    private static int surface(ChunkGenerator generator, LevelHeightAccessor height, RandomState random, int x, int z) {
        int y = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, height, random);
        return y <= height.getMinY() + SetPieceStructure.VOID_BAND ? SetPieceShapes.NO_GROUND : y;
    }

    private static int floorNear(ChunkGenerator generator, LevelHeightAccessor height, RandomState random, int x, int z, int near) {
        NoiseColumn column = generator.getBaseColumn(x, z, height, random);
        Integer best = null;
        for (int y = Math.max(height.getMinY() + 1, near - 40); y <= Math.min(height.getMaxY() - 1, near + 16); y++) {
            var below = column.getBlock(y - 1);
            if (!column.getBlock(y).isAir() || below.isAir() || !below.getFluidState().isEmpty()) continue;
            if (best == null || Math.abs(y - near) < Math.abs(best - near)) best = y;
        }
        return best == null ? SetPieceShapes.NO_GROUND : best;
    }
}
