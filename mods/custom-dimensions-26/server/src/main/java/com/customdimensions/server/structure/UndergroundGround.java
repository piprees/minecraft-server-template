package com.customdimensions.server.structure;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An underground structure starts only where its level has ground under it.
 * Mineshafts, trial chambers, ancient cities and strongholds choose their
 * height without reading the terrain, so under a sky world's islands they
 * would hang in the void. A start whose structure generates in an underground
 * step, or a stronghold, stands when every column at its box's corners and centre has a block
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

    /** Whether {@code structure} generates in an underground step, or is a stronghold (whose own step is the surface's). */
    public static boolean underground(Structure structure) {
        return UNDERGROUND.contains(structure.step()) || structure.type() == StructureType.STRONGHOLD;
    }

    /** Whether an underground {@code structure} with this box would hang over the void; a structure above ground never does. */
    public static boolean floats(Structure structure, BoundingBox box, ChunkGenerator generator, RandomState random,
                                 LevelHeightAccessor height) {
        return underground(structure) && !grounded(box, generator, random, height);
    }

    /** Whether every column at the box's centre and corners has a block other than air at or below the box's floor; a box reaching below the level's floor stands on it. */
    public static boolean grounded(BoundingBox box, ChunkGenerator generator, RandomState random, LevelHeightAccessor height) {
        int[][] columns = {
                {(box.minX() + box.maxX()) >> 1, (box.minZ() + box.maxZ()) >> 1},
                {box.minX(), box.minZ()}, {box.maxX(), box.minZ()}, {box.minX(), box.maxZ()}, {box.maxX(), box.maxZ()}};
        int floor = Math.max(height.getMinY(), Math.min(box.minY(), height.getMaxY()));
        for (int[] c : columns) {
            if (!groundBelow(generator.getBaseColumn(c[0], c[1], height, random), height.getMinY(), floor)) {
                return false;
            }
        }
        return true;
    }

    /** Whether the column at ({@code x}, {@code z}) has a block other than air between {@code lo} and {@code hi}. */
    public static boolean spans(ChunkGenerator generator, RandomState random, LevelHeightAccessor height, int x, int z, int lo, int hi) {
        int from = Math.max(lo, height.getMinY());
        int to = Math.min(hi, height.getMaxY());
        if (from > to) return false;
        NoiseColumn column = generator.getBaseColumn(x, z, height, random);
        for (int y = from; y <= to; y++) {
            if (!column.getBlock(y).isAir()) return true;
        }
        return false;
    }

    private static final java.util.Map<Structure, int[]> HEIGHTS = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * The lowest, middle and highest of a structure's own start heights, read
     * from the first {@link HeightProvider} it holds (a jigsaw's
     * {@code start_height}), or null when it holds none.
     */
    public static int[] startHeights(Structure structure, ChunkGenerator generator, LevelHeightAccessor height) {
        int[] cached = HEIGHTS.get(structure);
        if (cached != null) return cached.length == 0 ? null : cached;
        HeightProvider provider = heightProvider(structure);
        int[] out = new int[0];
        if (provider != null) {
            WorldGenerationContext context = new WorldGenerationContext(generator, height);
            int lo = Integer.MAX_VALUE;
            int hi = Integer.MIN_VALUE;
            for (int i = 0; i < 16; i++) {
                int y = provider.sample(RandomSource.create(i), context);
                lo = Math.min(lo, y);
                hi = Math.max(hi, y);
            }
            out = new int[] {lo, (lo + hi) >> 1, hi};
        }
        HEIGHTS.put(structure, out);
        return out.length == 0 ? null : out;
    }

    private static HeightProvider heightProvider(Structure structure) {
        for (Class<?> c = structure.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || !HeightProvider.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    return (HeightProvider) f.get(structure);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return null;
                }
            }
        }
        return null;
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
