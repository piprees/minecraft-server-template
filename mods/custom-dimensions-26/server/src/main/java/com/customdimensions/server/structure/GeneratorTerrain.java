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
 * them), biomes from the level's biome resolver. Every answer is computed from
 * noise, so it is the same before and after the chunk generates. Under a roof
 * (a column solid within {@value #ROOF} blocks of the top) the height is the
 * floor: the first air over solid ground from sea level up with
 * {@value #HEADROOM} blocks of headroom, where a start on that level stands.
 */
record GeneratorTerrain(ChunkGenerator generator, LevelHeightAccessor height, RandomState random, BiomeResolver biomes)
        implements SiteFilter.Terrain {

    static GeneratorTerrain of(ChunkGenerator generator, RandomState random, BiomeResolver biomes) {
        return new GeneratorTerrain(generator, LevelHeightAccessor.create(generator.getMinY(), generator.getGenDepth()), random, biomes);
    }

    static final int ROOF = 8;
    static final int HEADROOM = 4;

    @Override
    public int height(int x, int z) {
        int top = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, height, random);
        Integer floor = top >= height.getMaxY() - ROOF ? roofedFloor(x, z) : null;
        return floor != null ? floor : top;
    }

    @Override
    public int floor(int x, int z) {
        int top = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, height, random);
        Integer floor = top >= height.getMaxY() - ROOF ? roofedFloor(x, z) : null;
        return floor != null ? floor : generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, height, random);
    }

    /** Whether the column at ({@code x}, {@code z}) is under a roof. */
    boolean roofed(int x, int z) {
        return generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, height, random) >= height.getMaxY() - ROOF;
    }

    /** The floor under a roof: the first air over solid ground from sea level up with headroom, or null. */
    Integer roofedFloor(int x, int z) {
        NoiseColumn column = generator.getBaseColumn(x, z, height, random);
        for (int y = Math.max(height.getMinY() + 1, generator.getSeaLevel()); y <= height.getMaxY() - ROOF - HEADROOM; y++) {
            var below = column.getBlock(y - 1);
            if (below.isAir() || !below.getFluidState().isEmpty()) continue;
            boolean open = true;
            for (int k = 0; k < HEADROOM && open; k++) open = column.getBlock(y + k).isAir();
            if (open) return y;
        }
        return null;
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
