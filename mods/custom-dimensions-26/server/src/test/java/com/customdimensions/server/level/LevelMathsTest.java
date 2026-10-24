package com.customdimensions.server.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.customdimensions.core.BlockPos;
import com.customdimensions.core.ChunkPos;
import com.customdimensions.core.definition.Borders;
import com.customdimensions.core.definition.Seed;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class LevelMathsTest {
    @Test
    void borderSizeIsTwiceTheRadius() {
        assertEquals(16384.0, LevelMaths.borderSize(8192));
        assertEquals(1024.0, LevelMaths.borderSize(512));
        assertEquals(2.0, LevelMaths.borderSize(1));
    }

    @Test
    void borderSizeStopsAtVanillasMaximum() {
        assertEquals(LevelMaths.MAX_BORDER_SIZE, LevelMaths.borderSize(Integer.MAX_VALUE));
        assertEquals(LevelMaths.MAX_BORDER_SIZE, LevelMaths.borderSize(29_999_984));
    }

    @Test
    void borderRadiusMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> LevelMaths.borderSize(0));
        assertThrows(IllegalArgumentException.class, () -> LevelMaths.borderSize(-5));
    }

    @Test
    void envSeedIsTheWorldSeed() {
        assertEquals(20261022L, LevelMaths.resolveSeed(Seed.env(), 20261022L));
        assertEquals(-7L, LevelMaths.resolveSeed(Seed.env(), -7L));
    }

    @Test
    void fixedSeedIsKept() {
        assertEquals(8949861888281359583L, LevelMaths.resolveSeed(Seed.of(8949861888281359583L), 20261022L));
        assertEquals(0L, LevelMaths.resolveSeed(Seed.of(0L), 20261022L), "a fixed zero is a seed, not env");
    }

    @Test
    void noSeedIsTheWorldSeed() {
        assertEquals(42L, LevelMaths.resolveSeed(null, 42L));
    }

    @Test
    void spawnChunkFloorsNegativeCoordinates() {
        assertChunk(24, -12, LevelMaths.spawnChunk(new BlockPos(384, -64, -192)));
        assertChunk(-1, -1, LevelMaths.spawnChunk(new BlockPos(-1, 64, -16)));
        assertChunk(-2, 0, LevelMaths.spawnChunk(new BlockPos(-17, 64, 15)));
        assertChunk(0, 0, LevelMaths.spawnChunk(null));
    }

    @Test
    void playerRadiusComesFromTheDefinitionThenTheDefaults() {
        JsonObject defaults = JsonParser.parseString("{\"borders\": {\"player\": 8192, \"generation\": 2048}}").getAsJsonObject();
        assertEquals(512, LevelMaths.playerBorderRadius(new Borders(512, 512), defaults));
        assertEquals(8192, LevelMaths.playerBorderRadius(new Borders(null, 512), defaults));
        assertEquals(8192, LevelMaths.playerBorderRadius(null, defaults));
        assertNull(LevelMaths.playerBorderRadius(null, new JsonObject()));
        assertNull(LevelMaths.playerBorderRadius(null, null));
    }

    private static void assertChunk(int x, int z, ChunkPos chunk) {
        assertEquals(x, chunk.x(), "x");
        assertEquals(z, chunk.z(), "z");
    }
}
