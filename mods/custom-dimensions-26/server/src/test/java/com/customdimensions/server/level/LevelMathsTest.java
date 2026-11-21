package com.customdimensions.server.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void aLevelWithoutChunksTakesTheDefinitionsSeedAndRecordsIt() {
        assertEquals(new LevelMaths.SeedChoice(7, true, null), LevelMaths.chooseSeed("a:x", 7, null, false, 1));
        assertEquals(new LevelMaths.SeedChoice(7, true, null), LevelMaths.chooseSeed("a:x", 7, 5L, false, 1));
        assertEquals(new LevelMaths.SeedChoice(7, false, null), LevelMaths.chooseSeed("a:x", 7, 7L, false, 1));
    }

    @Test
    void aLevelWithChunksKeepsTheRecordedSeedAndWarnsWhenTheDefinitionDiffers() {
        assertEquals(new LevelMaths.SeedChoice(7, false, null), LevelMaths.chooseSeed("a:x", 7, 7L, true, 1));
        LevelMaths.SeedChoice kept = LevelMaths.chooseSeed("minecraft:overworld", 9, 7L, true, 1);
        assertEquals(7, kept.seed());
        assertEquals(false, kept.write());
        assertTrue(kept.warning().startsWith("minecraft:overworld: its region folder holds chunks generated from seed 7, and its definition's seed is 9"),
                kept.warning());
        assertTrue(kept.warning().contains("reset-seed") && kept.warning().contains("set the definition's seed to 7"), kept.warning());
    }

    @Test
    void chunksOlderThanAnyRecordKeepTheSeedTheLevelHadAndRecordIt() {
        LevelMaths.SeedChoice base = LevelMaths.chooseSeed("minecraft:the_end", 9, null, true, 20261022);
        assertEquals(20261022, base.seed());
        assertTrue(base.write());
        assertTrue(base.warning().contains("generated from seed 20261022"), base.warning());
        assertEquals(new LevelMaths.SeedChoice(9, true, null), LevelMaths.chooseSeed("adventure:x", 9, null, true, 9));
    }

    @Test
    void lodReachIsTheLargerBorderEachFallingBackToTheDefaults() {
        JsonObject defaults = JsonParser.parseString("{\"borders\":{\"player\":8192,\"generation\":2048}}").getAsJsonObject();
        assertEquals(8192, LevelMaths.lodReach(null, defaults));
        assertEquals(2048, LevelMaths.lodReach(new Borders(512, null), defaults), "the default generation border is larger");
        assertEquals(4096, LevelMaths.lodReach(new Borders(4096, 1024), defaults));
        assertEquals(3000, LevelMaths.lodReach(new Borders(1000, 3000), null));
        assertEquals(600, LevelMaths.lodReach(new Borders(null, 600), null));
        assertNull(LevelMaths.lodReach(null, null));
    }

    private static void assertChunk(int x, int z, ChunkPos chunk) {
        assertEquals(x, chunk.x(), "x");
        assertEquals(z, chunk.z(), "z");
    }
}
