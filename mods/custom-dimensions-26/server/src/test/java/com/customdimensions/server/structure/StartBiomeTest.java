package com.customdimensions.server.structure;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.structures.IglooStructure;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A forced start's or a want's biome test on vanilla's overworld generator: a
 * chunk whose biome near sea level is not the one at its surface (a mountain
 * over a cave biome) does not suit a structure above ground that takes only the
 * sea-level biome.
 */
class StartBiomeTest {

    static NoiseBasedChunkGenerator generator;
    static RandomState random;
    static BiomeResolver sampler;

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var lookup = VanillaRegistries.createWorldLookup();
        Holder<NoiseGeneratorSettings> settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        generator = new NoiseBasedChunkGenerator(MultiNoiseBiomeSource.createFromPreset(lookup.lookupOrThrow(
                Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST).getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD)), settings);
        random = RandomState.create(lookup.lookupOrThrow(Registries.NOISE), 20261022L, settings.value());
        sampler = generator.getBiomeSource().createUncachedResolver(random);
    }

    static Holder<Biome> biome(int x, int y, int z) {
        return sampler.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z));
    }

    static Structure surfaceStructureIn(Holder<Biome> biome) {
        return new IglooStructure(new Structure.StructureSettings(HolderSet.direct(biome)));
    }

    @Test
    void theSurfaceBiomeDecidesForAStructureAboveGround() {
        int sea = generator.getSeaLevel();
        LevelHeightAccessor height = LevelHeightAccessor.create(generator.getMinY(), generator.getGenDepth());
        for (int cx = 0; cx < 4000; cx += 7) {
            int x = cx * 16 + 8;
            int z = 8;
            int y = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, height, random);
            Holder<Biome> low = biome(x, sea, z);
            Holder<Biome> top = biome(x, y, z);
            if (low.equals(top) || !low.equals(biome(x, sea + 24, z)) || !low.equals(biome(x, sea - 24, z))) continue;
            Structure lowOnly = surfaceStructureIn(low);
            assertTrue(StructureRuntime.biomeAt(lowOnly, sampler, cx, 0, sea), "the sea-level test alone takes it");
            assertFalse(StructureRuntime.startBiomeAt(lowOnly, generator, random, sampler, cx, 0, sea),
                    "chunk " + cx + ",0: sea level " + low.getRegisteredName() + ", surface " + top.getRegisteredName());
            return;
        }
        throw new AssertionError("no chunk on the fixture's line has a surface biome other than its sea-level band's");
    }
}
