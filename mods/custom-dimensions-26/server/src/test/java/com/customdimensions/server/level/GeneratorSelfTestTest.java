package com.customdimensions.server.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.customdimensions.core.BlockPos;
import java.util.List;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

class GeneratorSelfTestTest {

    @Test
    void aSkyWorldIsSampledRoundItsSpawn() {
        List<ChunkPos> area = GeneratorSelfTest.squareAround(new BlockPos(0, 79, -408), 8);
        assertEquals(64, area.size());
        assertTrue(area.contains(new ChunkPos(0, -408 >> 4)), "the spawn's chunk is sampled");
        assertTrue(area.stream().allMatch(c -> Math.abs(c.x()) <= 4 && Math.abs(c.z() - (-408 >> 4)) <= 4), "the square is centred on the spawn");
    }

    @Test
    void withoutASpawnTheSquareIsCentredOnTheOrigin() {
        List<ChunkPos> area = GeneratorSelfTest.squareAround(null, 8);
        assertTrue(area.contains(new ChunkPos(-4, -4)) && area.contains(new ChunkPos(3, 3)));
    }
}
