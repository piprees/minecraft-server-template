package com.customdimensions.server.structure;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Underground starts over the void, on vanilla's End (islands over void) and
 * overworld (a floor everywhere) generators: the End's void ring has no ground
 * under a mineshaft's box, its central island has, and the overworld always has.
 */
class UndergroundGroundTest {

    static final long SEED = 20261022L;
    static final LevelHeightAccessor END_HEIGHT = LevelHeightAccessor.create(0, 256);
    static final LevelHeightAccessor OVERWORLD_HEIGHT = LevelHeightAccessor.create(-64, 384);

    static HolderLookup.Provider lookup;
    static NoiseBasedChunkGenerator end;
    static RandomState endRandom;
    static NoiseBasedChunkGenerator overworld;
    static RandomState overworldRandom;

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        lookup = VanillaRegistries.createWorldLookup();
        Holder<NoiseGeneratorSettings> endSettings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.END);
        end = new NoiseBasedChunkGenerator(TheEndBiomeSource.create(lookup.lookupOrThrow(Registries.BIOME)), endSettings);
        endRandom = RandomState.create(lookup.lookupOrThrow(Registries.NOISE), SEED, endSettings.value());
        Holder<NoiseGeneratorSettings> owSettings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        overworld = new NoiseBasedChunkGenerator(MultiNoiseBiomeSource.createFromPreset(lookup.lookupOrThrow(
                Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST).getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD)), owSettings);
        overworldRandom = RandomState.create(lookup.lookupOrThrow(Registries.NOISE), SEED, owSettings.value());
    }

    static Structure structure(net.minecraft.resources.ResourceKey<Structure> key) {
        return lookup.lookupOrThrow(Registries.STRUCTURE).getOrThrow(key).value();
    }

    /** The highest block other than air in an End column, or the level's floor minus one. */
    static int top(int x, int z) {
        NoiseColumn column = end.getBaseColumn(x, z, END_HEIGHT, endRandom);
        for (int y = END_HEIGHT.getMaxY(); y >= END_HEIGHT.getMinY(); y--) {
            if (!column.getBlock(y).isAir()) return y;
        }
        return END_HEIGHT.getMinY() - 1;
    }

    @Test
    void theStepsReadAsUnderground() {
        assertTrue(UndergroundGround.underground(structure(BuiltinStructures.MINESHAFT)));
        assertTrue(UndergroundGround.underground(structure(BuiltinStructures.TRIAL_CHAMBERS)));
        assertTrue(UndergroundGround.underground(structure(BuiltinStructures.ANCIENT_CITY)));
        assertTrue(UndergroundGround.underground(structure(BuiltinStructures.STRONGHOLD)), "a stronghold, though its step is the surface's");
        assertTrue(UndergroundGround.underground(structure(BuiltinStructures.TRAIL_RUINS)));
        assertFalse(UndergroundGround.underground(structure(BuiltinStructures.END_CITY)));
        assertFalse(UndergroundGround.underground(structure(BuiltinStructures.VILLAGE_PLAINS)));
    }

    @Test
    void aMineshaftInTheEndsVoidRingFloats() {
        int x = 600;
        assertTrue(top(x, 0) < END_HEIGHT.getMinY(), "the fixture's column is void");
        BoundingBox box = new BoundingBox(x - 20, 20, -20, x + 20, 40, 20);
        assertFalse(UndergroundGround.grounded(box, end, endRandom, END_HEIGHT));
        assertTrue(UndergroundGround.floats(structure(BuiltinStructures.MINESHAFT), box, end, endRandom, END_HEIGHT));
        assertFalse(UndergroundGround.floats(structure(BuiltinStructures.END_CITY), box, end, endRandom, END_HEIGHT),
                "a structure above ground is never refused");
    }

    @Test
    void aMineshaftInsideTheCentralIslandStands() {
        int floor = Integer.MAX_VALUE;
        for (int[] c : new int[][] {{0, 0}, {-8, -8}, {8, -8}, {-8, 8}, {8, 8}}) floor = Math.min(floor, top(c[0], c[1]));
        assertTrue(floor > END_HEIGHT.getMinY(), "the central island is there");
        BoundingBox box = new BoundingBox(-8, floor, -8, 8, floor + 10, 8);
        assertTrue(UndergroundGround.grounded(box, end, endRandom, END_HEIGHT));
        assertFalse(UndergroundGround.floats(structure(BuiltinStructures.MINESHAFT), box, end, endRandom, END_HEIGHT));
    }

    @Test
    void aBoxReachingFromTheIslandOverTheVoidFloats() {
        int floor = top(0, 0);
        BoundingBox box = new BoundingBox(-8, floor, -8, 600, floor + 10, 8);
        assertFalse(UndergroundGround.grounded(box, end, endRandom, END_HEIGHT));
    }

    @Test
    void aBoxReachingBelowTheLevelsFloorStandsOnIt() {
        BoundingBox box = new BoundingBox(-30, OVERWORLD_HEIGHT.getMinY() - 16, -30, 30, OVERWORLD_HEIGHT.getMinY() + 10, 30);
        assertTrue(UndergroundGround.grounded(box, overworld, overworldRandom, OVERWORLD_HEIGHT));
        BoundingBox voidBox = new BoundingBox(580, END_HEIGHT.getMinY() - 16, -20, 620, 20, 20);
        assertFalse(UndergroundGround.grounded(voidBox, end, endRandom, END_HEIGHT), "the End's void ring has nothing at its floor");
    }

    @Test
    void theOverworldHasAFloorEverywhere() {
        for (int[] c : new int[][] {{0, 0}, {5000, -3000}, {-12000, 700}}) {
            BoundingBox box = new BoundingBox(c[0] - 30, -50, c[1] - 30, c[0] + 30, -30, c[1] + 30);
            assertTrue(UndergroundGround.grounded(box, overworld, overworldRandom, OVERWORLD_HEIGHT), c[0] + "," + c[1]);
            box = new BoundingBox(c[0] - 30, 10, c[1] - 30, c[0] + 30, 40, c[1] + 30);
            assertTrue(UndergroundGround.grounded(box, overworld, overworldRandom, OVERWORLD_HEIGHT), "mid-depth " + c[0] + "," + c[1]);
        }
    }
}
