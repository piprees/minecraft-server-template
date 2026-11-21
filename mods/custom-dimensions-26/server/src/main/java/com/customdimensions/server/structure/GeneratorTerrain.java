package com.customdimensions.server.structure;

import com.customdimensions.core.structure.SiteFilter;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.List;

/**
 * A level's base terrain as a {@link SiteFilter} reads it: heights from the
 * generator's base columns (no features, as a structure's own siting reads
 * them), biomes from the level's biome resolver. Under a roof the surface is
 * the floor a seat stands on ({@link #roofedSurface}), not the roof.
 * Every answer is computed from noise, so it is the same before and after the
 * chunk generates.
 */
record GeneratorTerrain(ChunkGenerator generator, LevelHeightAccessor height, RandomState random, BiomeResolver biomes)
        implements SiteFilter.Terrain {

    static GeneratorTerrain of(ChunkGenerator generator, RandomState random, BiomeResolver biomes) {
        return new GeneratorTerrain(generator, LevelHeightAccessor.create(generator.getMinY(), generator.getGenDepth()), random, biomes);
    }

    @Override
    public int height(int x, int z) {
        int h = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, height, random);
        return roofed(h) ? roofedSurface(generator, random, x, z) : h;
    }

    @Override
    public int floor(int x, int z) {
        int h = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, height, random);
        return roofed(h) ? roofedSurface(generator, random, x, z)
                : generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, height, random);
    }

    /** As {@link SetPieceSeats#roofedFloor} decides it: a surface within {@value SetPieceSeats#ROOF} of the top is a roof. */
    private boolean roofed(int surface) {
        return surface >= height.getMaxY() - SetPieceSeats.ROOF;
    }

    /**
     * The surface under a roof: the floor a seat stands on ({@link SetPieceSeats#roofedFloor}), or sea level where
     * there is none, so an open lava or water sea reads as its own surface.
     */
    static int roofedSurface(ChunkGenerator generator, RandomState random, int x, int z) {
        Integer floor = SetPieceSeats.roofedFloor(generator, random, x, z);
        return floor != null ? floor : generator.getSeaLevel();
    }

    @Override
    public boolean inBiomes(int x, int y, int z, List<String> ids) {
        Holder<Biome> here = biomes.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z));
        return matches(here, ids);
    }

    static boolean matches(Holder<Biome> biome, List<String> ids) {
        for (String id : ids) {
            if (id.startsWith("#")) {
                if (biome.is(TagKey.create(Registries.BIOME, Identifier.parse(id.substring(1))))) return true;
            } else if (biome.unwrapKey().map(k -> k.identifier().toString().equals(id)).orElse(false)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the column has solid rock between 4 and 24 blocks above {@code y} with air just above {@code y}. */
    @Override
    public boolean overhang(int x, int z, int y) {
        NoiseColumn column = generator.getBaseColumn(x, z, height, random);
        int top = Math.min(height.getMaxY(), y + 24);
        if (y + 1 > height.getMaxY() || !column.getBlock(y + 1).isAir()) return false;
        for (int k = y + 4; k <= top; k++) {
            if (!column.getBlock(k).isAir() && column.getBlock(k).getFluidState().isEmpty()) return true;
        }
        return false;
    }
}
