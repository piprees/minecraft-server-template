package com.customdimensions.server.structure;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.core.Holder;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * A level's generator as a forced start with a {@code y} sees it while it
 * finds its generation point and builds its pieces: every column is solid
 * stone below {@code y} and air from it, so the first free height is
 * {@code y} everywhere. A structure that stands on the ground or projects onto
 * a heightmap starts there, over void or terrain alike; a jigsaw whose start
 * height is absolute keeps its own height, and a structure whose own rule
 * refuses that height (an End city below 60) does not start. Everything else
 * answers as the level's generator does.
 */
final class PinnedGround extends ChunkGenerator {

    private final ChunkGenerator level;
    private final int y;

    PinnedGround(ChunkGenerator level, int y) {
        super(level.getBiomeSource());
        this.level = level;
        this.y = y;
    }

    int y() {
        return y;
    }

    /** Stone below {@code y}, air from it, over the accessor's height. */
    static BlockState[] column(int minY, int height, int y) {
        BlockState[] states = new BlockState[height];
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int i = 0; i < height; i++) {
            states[i] = minY + i < y ? stone : air;
        }
        return states;
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor heightAccessor, RandomState randomState) {
        return Math.clamp(y, heightAccessor.getMinY(), heightAccessor.getMaxY() + 1);
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor heightAccessor, RandomState randomState) {
        return new NoiseColumn(heightAccessor.getMinY(), column(heightAccessor.getMinY(), heightAccessor.getHeight(), y));
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        throw new UnsupportedOperationException("a pinned ground is never saved");
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion region) {
        level.spawnOriginalMobs(region);
    }

    @Override
    public int getGenDepth() {
        return level.getGenDepth();
    }

    @Override
    public CompletableFuture<ChunkAccess> buildTerrain(ChunkAccess chunk, Blender blender, RandomState randomState,
                                                      StructureManager structureManager, BiomeManager biomeManager,
                                                      WorldGenRegion carverBiomeRegion, Set<Holder<Biome>> possibleBiomes) {
        return level.buildTerrain(chunk, blender, randomState, structureManager, biomeManager, carverBiomeRegion, possibleBiomes);
    }

    @Override
    public int getSeaLevel() {
        return level.getSeaLevel();
    }

    @Override
    public int getMinY() {
        return level.getMinY();
    }

    @Override
    public void addDebugScreenInfo(List<String> result, RandomState randomState, BlockPos feetPos, SamplerContext samplerContext) {
        level.addDebugScreenInfo(result, randomState, feetPos, samplerContext);
    }
}
