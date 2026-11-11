package com.customdimensions.server.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

class BiomeLibrarySelfTestTest {
    @Test
    void theFairSampleHoldsTheShowcaseAreaAtItsMiddle() {
        ChunkPos centre = new ChunkPos(2, -1);
        List<ChunkPos> showcase = BiomeLibrarySelfTest.area(centre, 6);
        List<ChunkPos> wide = BiomeLibrarySelfTest.area(centre, BiomeLibrarySelfTest.FAIR_SIDE);
        assertEquals(36, showcase.size());
        assertEquals(BiomeLibrarySelfTest.FAIR_SIDE * BiomeLibrarySelfTest.FAIR_SIDE, wide.size());
        Set<ChunkPos> all = new HashSet<>(wide);
        assertEquals(wide.size(), all.size());
        assertTrue(all.containsAll(showcase));
        int minX = wide.stream().mapToInt(ChunkPos::x).min().orElseThrow();
        int maxX = wide.stream().mapToInt(ChunkPos::x).max().orElseThrow();
        assertEquals(centre.x() - BiomeLibrarySelfTest.FAIR_SIDE / 2, minX);
        assertEquals(centre.x() + BiomeLibrarySelfTest.FAIR_SIDE / 2 - 1, maxX);
    }

    @Test
    void theFairSampleGivesAFeatureAtTwoAndAHalfAHectareSeveralToCount() {
        double hectares = Math.pow(BiomeLibrarySelfTest.FAIR_SIDE * 16, 2) / 10_000;
        assertTrue(2.4 * hectares >= 8, "expected willows over the wider sample: " + 2.4 * hectares);
        assertTrue(BiomeLibrarySelfTest.FAIR_COUNT > 1, "one block can be a neighbouring chunk's spill");
    }
}
