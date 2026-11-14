package com.customdimensions.server.structure;

import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An underground structure starts only where its level has ground under it.
 * Mineshafts, trial chambers, ancient cities and strongholds choose their
 * height without reading the terrain, so under a sky world's islands they
 * would hang in the void. A start whose structure generates in an underground
 * step stands when every column at its box's corners and centre has a block
 * other than air at or below the box's floor in the generator's base terrain;
 * otherwise it does not start. Terrain with a floor everywhere (the overworld,
 * the Nether) always passes.
 */
public final class UndergroundGround {

    static final Set<GenerationStep.Decoration> UNDERGROUND = EnumSet.of(GenerationStep.Decoration.UNDERGROUND_STRUCTURES,
            GenerationStep.Decoration.UNDERGROUND_DECORATION, GenerationStep.Decoration.STRONGHOLDS);

    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    private UndergroundGround() {
    }

    /** Whether this is the first refusal of {@code structure} in {@code level} in this process, so the log names each once. */
    public static boolean firstRefusal(String level, String structure) {
        return LOGGED.add(level + " " + structure);
    }

    /** Whether {@code structure} generates in an underground step. */
    public static boolean underground(Structure structure) {
        return UNDERGROUND.contains(structure.step());
    }

    /** Whether an underground {@code structure} with this box would hang over the void; a structure above ground never does. */
    public static boolean floats(Structure structure, BoundingBox box, ChunkGenerator generator, RandomState random,
                                 LevelHeightAccessor height) {
        return underground(structure) && !grounded(box, generator, random, height);
    }

    /** Whether every column at the box's centre and corners has a block other than air at or below the box's floor. */
    public static boolean grounded(BoundingBox box, ChunkGenerator generator, RandomState random, LevelHeightAccessor height) {
        int[][] columns = {
                {(box.minX() + box.maxX()) >> 1, (box.minZ() + box.maxZ()) >> 1},
                {box.minX(), box.minZ()}, {box.maxX(), box.minZ()}, {box.minX(), box.maxZ()}, {box.maxX(), box.maxZ()}};
        int floor = Math.min(box.minY(), height.getMaxY());
        for (int[] c : columns) {
            if (!groundBelow(generator.getBaseColumn(c[0], c[1], height, random), height.getMinY(), floor)) {
                return false;
            }
        }
        return true;
    }

    private static boolean groundBelow(NoiseColumn column, int minY, int floor) {
        for (int y = floor; y >= minY; y--) {
            if (!column.getBlock(y).isAir()) {
                return true;
            }
        }
        return false;
    }
}
