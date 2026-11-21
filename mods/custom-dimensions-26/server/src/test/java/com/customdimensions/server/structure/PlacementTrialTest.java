package com.customdimensions.server.structure;

import com.customdimensions.core.structure.SiteFilter;
import com.google.gson.JsonParser;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a forced start's or a want's chooser and a site filter read, on
 * vanilla's generators: a candidate generated as its level would generate it
 * (a mineshaft stands on the overworld, not over the End's void), a site
 * filter's surface under the Nether's roof (the floor, not the roof), and an
 * underground structure's own start heights.
 */
class PlacementTrialTest {

    static final long SEED = 20261022L;
    static HolderLookup.Provider lookup;
    static NoiseBasedChunkGenerator end, overworld, nether;
    static RandomState endRandom, overworldRandom, netherRandom;

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        lookup = VanillaRegistries.createWorldLookup();
        var noise = lookup.lookupOrThrow(Registries.NOISE);
        var presets = lookup.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);
        Holder<NoiseGeneratorSettings> e = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.END);
        end = new NoiseBasedChunkGenerator(TheEndBiomeSource.create(lookup.lookupOrThrow(Registries.BIOME)), e);
        endRandom = RandomState.create(noise, SEED, e.value());
        Holder<NoiseGeneratorSettings> o = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        overworld = new NoiseBasedChunkGenerator(MultiNoiseBiomeSource.createFromPreset(presets.getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD)), o);
        overworldRandom = RandomState.create(noise, SEED, o.value());
        Holder<NoiseGeneratorSettings> n = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.NETHER);
        nether = new NoiseBasedChunkGenerator(MultiNoiseBiomeSource.createFromPreset(presets.getOrThrow(MultiNoiseBiomeSourceParameterLists.NETHER)), n);
        netherRandom = RandomState.create(noise, SEED, n.value());
    }

    static Holder<Structure> structure(net.minecraft.resources.ResourceKey<Structure> key) {
        return lookup.lookupOrThrow(Registries.STRUCTURE).getOrThrow(key);
    }

    static LevelHeightAccessor height(NoiseBasedChunkGenerator g) {
        return LevelHeightAccessor.create(g.getMinY(), g.getGenDepth());
    }

    static String trial(NoiseBasedChunkGenerator g, RandomState random, int cx, int cz) {
        return StructureRuntime.trial(Level.OVERWORLD, RegistryAccess.EMPTY, g, random, SEED, null, height(g),
                structure(BuiltinStructures.MINESHAFT), null, null).refusal(cx, cz);
    }

    @Test
    void aCandidateIsGeneratedAsItsLevelWouldGenerateIt() {
        assertNull(trial(overworld, overworldRandom, 0, 0), "a mineshaft stands under the overworld");
        assertNotNull(trial(end, endRandom, 37, 0), "a mineshaft over the End's void ring does not");
    }

    /** A column over the Nether's lava sea: lava at sea level - 1, air for the headroom above it. */
    static int lavaSea() {
        LevelHeightAccessor h = height(nether);
        int sea = nether.getSeaLevel();
        for (int x = 0; x < 20000; x += 16) {
            NoiseColumn c = nether.getBaseColumn(x, 0, h, netherRandom);
            if (!c.getBlock(sea - 1).getFluidState().isEmpty() && c.getBlock(sea).isAir() && c.getBlock(sea + 8).isAir()
                    && GeneratorTerrain.roofedFloor(nether, netherRandom, h, x, 0) == sea) {
                return x;
            }
        }
        throw new AssertionError("no column of the fixture's line lies over the open lava sea");
    }

    @Test
    void underTheNethersRoofAFilterReadsTheFloorNotTheRoof() {
        int x = lavaSea();
        GeneratorTerrain terrain = GeneratorTerrain.of(nether, netherRandom, nether.getBiomeSource().createUncachedResolver(netherRandom));
        int roof = nether.getBaseHeight(x, 0, Heightmap.Types.WORLD_SURFACE_WG, height(nether), netherRandom);
        assertTrue(roof >= nether.getMinY() + nether.getGenDepth() - GeneratorTerrain.ROOF_MARGIN, "the column is roofed: " + roof);
        assertEquals(nether.getSeaLevel(), terrain.height(x, 0), "the open lava sea reads as its own surface");
        SiteFilter offTheLava = SiteFilter.read("t", JsonParser.parseString("{\"height\": {\"min\": 36}}").getAsJsonObject());
        assertNotNull(offTheLava.refusal(terrain, x, 0), "a bastion's height filter keeps it off the lava sea");
    }

    @Test
    void aRoofedFloorHasHeadroomOverSolidGround() {
        LevelHeightAccessor h = height(nether);
        int found = 0;
        for (int x = 0; x < 4000 && found < 20; x += 8) {
            int y = GeneratorTerrain.roofedFloor(nether, netherRandom, h, x, 0);
            if (y == nether.getSeaLevel()) continue;
            NoiseColumn c = nether.getBaseColumn(x, 0, h, netherRandom);
            assertTrue(!c.getBlock(y - 1).isAir() && c.getBlock(y - 1).getFluidState().isEmpty(), x + ": solid under " + y);
            for (int k = 0; k < GeneratorTerrain.HEADROOM; k++) assertTrue(c.getBlock(y + k).isAir(), x + ": air at " + (y + k));
            found++;
        }
        assertTrue(found > 0, "some columns of the fixture's line have land under the roof");
    }

    @Test
    void anUndergroundStructuresOwnStartHeightsAreRead() {
        int[] trial = UndergroundGround.startHeights(structure(BuiltinStructures.TRIAL_CHAMBERS).value(), overworld, height(overworld));
        assertNotNull(trial);
        assertTrue(trial[0] >= -40 && trial[2] <= -20 && trial[0] < trial[2], java.util.Arrays.toString(trial));
        assertNull(UndergroundGround.startHeights(structure(BuiltinStructures.MINESHAFT).value(), overworld, height(overworld)),
                "a mineshaft holds no start height");
    }
}
