package com.customdimensions.server.structure;

import com.customdimensions.core.structure.SetPieceSpec;
import com.google.gson.JsonParser;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.TheEndBiomeSource;
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

/** Seats on vanilla's End generator: ground on the central island, none over the void ring; pinned anywhere. */
class SetPieceSeatTest {

    static final long SEED = 20261022L;
    static final LevelHeightAccessor HEIGHT = LevelHeightAccessor.create(0, 256);
    static NoiseBasedChunkGenerator end;
    static RandomState random;

    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        HolderLookup.Provider lookup = VanillaRegistries.createWorldLookup();
        Holder<NoiseGeneratorSettings> settings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.END);
        end = new NoiseBasedChunkGenerator(TheEndBiomeSource.create(lookup.lookupOrThrow(Registries.BIOME)), settings);
        random = RandomState.create(lookup.lookupOrThrow(Registries.NOISE), SEED, settings.value());
    }

    static SetPieceSpec spec(String seat, String extra) {
        return SetPieceSpec.resolve("t", "p", JsonParser.parseString("{\"seat\": \"" + seat + "\", \"size\": 8" + extra
                + ", \"elements\": [{\"kind\": \"block\", \"block\": \"minecraft:stone\"}]}").getAsJsonObject(), Map.of(), null, SetPieceSpec.ANY);
    }

    @Test
    void groundStandsOnTheIslandAndDeclinesOverTheVoid() {
        SetPieceStructure.Seat island = SetPieceStructure.seat(spec("ground", ""), end, HEIGHT, random, 24, 24);
        assertNotNull(island.y(), island.why());
        assertTrue(island.y() > 40, "the central island's top, not the floor: " + island.y());
        SetPieceStructure.Seat voidRing = SetPieceStructure.seat(spec("ground", ""), end, HEIGHT, random, 520, 8);
        assertNull(voidRing.y());
        assertTrue(voidRing.why().startsWith("no ground above the void under its origin"), voidRing.why());
    }

    @Test
    void aSlopeBelowTheFootprintsSpreadDeclines() {
        int[] h = new int[9];
        int i = 0;
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) h[i++] = SetPieceStructure.surface(end, HEIGHT, random, 24 + dx * 8, 24 + dz * 8);
        int spread = java.util.Arrays.stream(h).max().getAsInt() - java.util.Arrays.stream(h).min().getAsInt();
        SetPieceStructure.Seat loose = SetPieceStructure.seat(spec("ground", ", \"slope\": " + spread), end, HEIGHT, random, 24, 24);
        assertNotNull(loose.y());
        if (spread > 0) {
            SetPieceStructure.Seat tight = SetPieceStructure.seat(spec("ground", ", \"slope\": " + (spread - 1)), end, HEIGHT, random, 24, 24);
            assertNull(tight.y());
            assertTrue(tight.why().contains("more than its slope"), tight.why());
        }
    }

    @Test
    void pinnedNeverDeclines() {
        SetPieceStructure.Seat pinned = SetPieceStructure.seat(spec("pinned", ""), new PinnedGround(end, 90), HEIGHT, random, 520, 8);
        assertEquals(90, pinned.y());
    }

    @Test
    void floorFindsNoFloorInTheEndsVoidAndOneOnTheIsland() {
        SetPieceSpec floor = spec("floor", ", \"band\": [1, 200], \"clear\": 4");
        assertNull(SetPieceStructure.seat(floor, end, HEIGHT, random, 520, 8).y());
        Integer y = SetPieceStructure.floor(floor, end, HEIGHT, random, 24, 24, null);
        assertNotNull(y, "the island's top is a floor with open sky above");
    }
}
