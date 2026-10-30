package com.customdimensions.core.score;

import com.customdimensions.core.render.ColumnSamples;
import com.customdimensions.core.render.RenderGrid;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FactsBuilderTest {

    interface Column {
        void fill(int x, int z, ColumnSamples s, int px, int py);
    }

    private static ColumnSamples grid(RenderGrid g, Column c) {
        ColumnSamples s = new ColumnSamples(g, -64, 319, 63);
        for (int py = 0; py < g.side(); py++) for (int px = 0; px < g.side(); px++) c.fill(g.x(px), g.z(py), s, px, py);
        return s;
    }

    private static final RollFacts.Structures NO_STRUCTURES = new RollFacts.Structures(Set.of(), Map.of(), List.of());

    /** A zone map double: one zone, every column with x below zero. */
    private static final ZoneMap WEST = new ZoneMap() {
        public List<Zone> zones() {
            return List.of(new Zone("west", Set.of("minecraft:desert")));
        }

        public int zoneAt(int x, int z) {
            return x < 0 ? 0 : -1;
        }
    };

    @Test
    void sharesTerrainAndZonesComeFromTheDiscColumns() {
        RenderGrid g = new RenderGrid(0, 0, 64, 8);
        ColumnSamples disc = grid(g, (x, z, s, px, py) -> {
            boolean west = x < 0;
            boolean sea = z > 32;
            s.set(px, py, sea ? 50 : 70 + (west ? 10 : 0), sea ? ColumnSamples.Fluid.WATER : ColumnSamples.Fluid.NONE,
                    west ? "minecraft:desert" : "minecraft:plains");
        });
        ColumnSamples patch = grid(FactsBuilder.patchGrid(0, 0), (x, z, s, px, py) ->
                s.set(px, py, 70, ColumnSamples.Fluid.NONE, "minecraft:plains"));
        RollFacts f = FactsBuilder.build(new RollTarget("t", RollSpec.NONE, 1000, 2), 7L, disc, patch, 500, NO_STRUCTURES, WEST);
        assertEquals(64, f.sampledRadius());
        assertEquals(2, f.biomes().distinct());
        assertEquals(1.0, f.biomes().shares().values().stream().mapToDouble(Double::doubleValue).sum(), 1e-9);
        assertEquals(1.0, f.terrain().groundFraction());
        assertTrue(f.terrain().waterFraction() > 0.05 && f.terrain().waterFraction() < 0.3);
        assertEquals(50, f.terrain().lowHeight().orThrow());
        assertEquals(80, f.terrain().highHeight().orThrow());
        RollFacts.ZoneShare west = f.zones().getFirst();
        assertTrue(west.columns() > 0);
        assertEquals(west.columns(), west.intended());
        assertEquals(1.0, f.spawn().safeColumnFraction().orThrow());
        assertEquals(0.0, f.biomes().edgeDensityNearSpawn().orThrow());
        assertEquals("minecraft:plains", f.spawn().biome().orThrow());
    }

    @Test
    void anEmptyDiscHasNoGroundAndAbsentHeights() {
        RenderGrid g = new RenderGrid(0, 0, 32, 8);
        ColumnSamples disc = grid(g, (x, z, s, px, py) -> s.set(px, py, -64, ColumnSamples.Fluid.NONE, "minecraft:the_void"));
        ColumnSamples patch = grid(FactsBuilder.patchGrid(0, 0), (x, z, s, px, py) -> s.set(px, py, -64, ColumnSamples.Fluid.NONE, "minecraft:the_void"));
        RollFacts f = FactsBuilder.build(new RollTarget("t", RollSpec.NONE, 1000, 1), 1L, disc, patch, 500, NO_STRUCTURES, ZoneMap.NONE);
        assertEquals(0.0, f.terrain().groundFraction());
        assertFalse(f.terrain().relief().isPresent());
        assertFalse(f.spawn().height().isPresent());
        assertEquals(0.0, f.spawn().safeColumnFraction().orThrow());
    }

    @Test
    void cliffsLavaAndWaterAreNotSafeAndBiomeEdgesAreCounted() {
        ColumnSamples patch = grid(FactsBuilder.patchGrid(0, 0), (x, z, s, px, py) -> {
            ColumnSamples.Fluid fluid = x < -64 ? ColumnSamples.Fluid.LAVA : x > 64 ? ColumnSamples.Fluid.WATER : ColumnSamples.Fluid.NONE;
            int h = z > 64 ? 70 + (px % 2) * 10 : 70;
            s.set(px, py, h, fluid, x < 0 ? "minecraft:desert" : "minecraft:plains");
        });
        RollFacts.Spawn spawn = FactsBuilder.spawn(patch);
        double safe = spawn.safeColumnFraction().orThrow();
        assertTrue(safe > 0.2 && safe < 0.5, "safe " + safe);
        double edges = FactsBuilder.edgeDensity(patch).orThrow();
        assertTrue(edges > 0.0 && edges < 0.05, "edges " + edges);
    }

    @Test
    void theDiscIsClippedToThePlayableRadius() {
        RenderGrid g = new RenderGrid(0, 0, 256, 16);
        ColumnSamples disc = grid(g, (x, z, s, px, py) -> s.set(px, py, 70, ColumnSamples.Fluid.NONE,
                Math.hypot(x, z) <= 100 ? "minecraft:plains" : "minecraft:ocean"));
        ColumnSamples patch = grid(FactsBuilder.patchGrid(0, 0), (x, z, s, px, py) -> s.set(px, py, 70, ColumnSamples.Fluid.NONE, "minecraft:plains"));
        RollFacts f = FactsBuilder.build(new RollTarget("t", RollSpec.NONE, 100, 1), 1L, disc, patch, 100, NO_STRUCTURES, ZoneMap.NONE);
        assertEquals(100, f.sampledRadius());
        assertEquals(Set.of("minecraft:plains"), f.biomes().shares().keySet());
    }
}
