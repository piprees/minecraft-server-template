package com.customdimensions.server.structure;

import com.customdimensions.core.structure.SetPieceSpec;
import com.google.gson.JsonParser;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The roof, wall and water seats, and the roofed floor a site filter reads, on vanilla's Nether and End generators. */
class SetPieceRoofSeatTest {

    static final long SEED = 20261022L;
    static final LevelHeightAccessor NETHER_HEIGHT = LevelHeightAccessor.create(0, 128);
    static final LevelHeightAccessor END_HEIGHT = LevelHeightAccessor.create(0, 256);
    static NoiseBasedChunkGenerator nether;
    static NoiseBasedChunkGenerator end;
    static RandomState netherRandom;
    static RandomState endRandom;

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        HolderLookup.Provider lookup = VanillaRegistries.createWorldLookup();
        Holder<NoiseGeneratorSettings> n = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.NETHER);
        nether = new NoiseBasedChunkGenerator(MultiNoiseBiomeSource.createFromPreset(lookup.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getOrThrow(MultiNoiseBiomeSourceParameterLists.NETHER)), n);
        netherRandom = RandomState.create(lookup.lookupOrThrow(Registries.NOISE), SEED, n.value());
        Holder<NoiseGeneratorSettings> e = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.END);
        end = new NoiseBasedChunkGenerator(TheEndBiomeSource.create(lookup.lookupOrThrow(Registries.BIOME)), e);
        endRandom = RandomState.create(lookup.lookupOrThrow(Registries.NOISE), SEED, e.value());
    }

    static SetPieceSpec spec(String seat, String extra) {
        return SetPieceSpec.resolve("t", "p", JsonParser.parseString("{\"seat\": \"" + seat + "\", \"size\": 8, \"support\": 0.3" + extra
                + ", \"elements\": [{\"kind\": \"block\", \"block\": \"minecraft:stone\"}]}").getAsJsonObject(), Map.of(), null, SetPieceSpec.ANY);
    }

    @Test
    void aRoofSeatFindsTheNethersCeilingAndDeclinesInTheEnd() {
        SetPieceSpec roof = spec("roof", "");
        int found = 0;
        for (int i = 0; i < 12; i++) {
            SetPieceStructure.Seat s = SetPieceStructure.seat(roof, nether, NETHER_HEIGHT, netherRandom, i * 64, 32);
            if (s.y() == null) continue;
            found++;
            var column = nether.getBaseColumn(i * 64, 32, NETHER_HEIGHT, netherRandom);
            assertTrue(!column.getBlock(s.y()).isAir() && column.getBlock(s.y() - 1).isAir(), "the lowest block of a ceiling at " + s.y());
        }
        assertTrue(found > 0, "a ceiling somewhere along the line");
        SetPieceStructure.Seat island = SetPieceStructure.seat(roof, end, END_HEIGHT, endRandom, 24, 24);
        assertNull(island.y());
        assertTrue(island.why().contains("ceiling"), island.why());
    }

    @Test
    void aWallSeatStandsOnAFaceAndFacesOut() {
        SetPieceSpec wall = spec("wall", ", \"band\": [32, 110], \"rise\": 5, \"reach\": 64");
        SetPieceStructure.Seat found = null;
        for (int i = 0; i < 16 && found == null; i++) {
            SetPieceStructure.Seat s = SetPieceStructure.seat(wall, nether, NETHER_HEIGHT, netherRandom, i * 48, -40);
            if (s.y() != null) found = s;
        }
        assertNotNull(found, "a face somewhere along the line");
        int[] out = com.customdimensions.core.structure.SetPiecePlan.rotate(1, 0, found.rotation());
        Integer lip = SetPieceStructure.floor(wall, nether, NETHER_HEIGHT, netherRandom, found.x(), found.z(), found.y());
        assertEquals(found.y(), lip, "the origin is on the lip");
        assertNotNull(found.rotation());
        assertTrue(Math.abs(out[0]) + Math.abs(out[1]) == 1);
        assertNull(SetPieceStructure.seat(spec("wall", ", \"rise\": 120"), end, END_HEIGHT, endRandom, 24, 24).y());
    }

    @Test
    void aWaterSeatNeedsWater() {
        SetPieceStructure.Seat s = SetPieceStructure.seat(spec("water", ", \"depth\": 2"), end, END_HEIGHT, endRandom, 24, 24);
        assertNull(s.y());
        assertTrue(s.why().contains("water 2 or more blocks deep"), s.why());
    }

    @Test
    void aFilteredStartUnderARoofSeatsOnTheFloor() {
        int roofed = 0;
        for (int i = 0; i < 12; i++) {
            int x = i * 64;
            int top = nether.getBaseHeight(x, 32, Heightmap.Types.WORLD_SURFACE_WG, NETHER_HEIGHT, netherRandom);
            Integer floor = SetPieceSeats.roofedFloor(nether, netherRandom, x, 32);
            if (floor == null) continue;
            roofed++;
            assertTrue(top >= 120 && floor < 120, "the roof's heightmap " + top + ", the floor " + floor);
            var column = nether.getBaseColumn(x, 32, NETHER_HEIGHT, netherRandom);
            assertTrue(column.getBlock(floor).isAir() && !column.getBlock(floor - 1).isAir());
            assertEquals(floor, Integer.valueOf(new PinnedGround(nether, floor).getBaseHeight(x, 32, Heightmap.Types.WORLD_SURFACE_WG, NETHER_HEIGHT,
                    netherRandom)), "a start seating on the heightmap sees the floor");
        }
        assertTrue(roofed > 0);
        assertNull(SetPieceSeats.roofedFloor(end, endRandom, 24, 24), "no roof in the End");
    }
}
