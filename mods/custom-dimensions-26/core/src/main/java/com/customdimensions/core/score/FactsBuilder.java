package com.customdimensions.core.score;

import com.customdimensions.core.facts.Measured;
import com.customdimensions.core.render.ColumnSamples;
import com.customdimensions.core.render.RenderGrid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns sampled columns into {@link RollFacts}. Two grids, both centred on
 * spawn: the disc grid (biome shares, terrain, water, zones) and the spawn
 * patch at a fixed fine step (spawn biome, safe columns, biome edges).
 */
public final class FactsBuilder {

    /** The spawn patch: radius and step, in blocks. */
    public static final int PATCH_RADIUS = 128;
    public static final int PATCH_STEP = 8;
    /** The tallest step, in blocks, between neighbouring patch columns a player walks. */
    public static final int SAFE_STEP = 3;

    private FactsBuilder() {}

    public static RenderGrid patchGrid(int spawnX, int spawnZ) {
        return new RenderGrid(spawnX, spawnZ, PATCH_RADIUS, PATCH_STEP);
    }

    public static RollFacts build(RollTarget target, long seed, ColumnSamples disc, ColumnSamples patch,
                                  int structureRadius, RollFacts.Structures structures, ZoneMap zones) {
        RenderGrid grid = disc.grid();
        int radius = Math.min(grid.radius(), target.playableRadius());
        long r2 = (long) radius * radius;
        Map<String, Integer> counts = new HashMap<>();
        List<ZoneMap.Zone> zoneList = zones.zones();
        int[] zoneColumns = new int[zoneList.size()];
        int[] zoneIntended = new int[zoneList.size()];
        int attempted = 0;
        int ground = 0;
        int water = 0;
        int[] heights = new int[grid.side() * grid.side()];
        int n = 0;
        for (int py = 0; py < grid.side(); py++) {
            for (int px = 0; px < grid.side(); px++) {
                long dx = grid.x(px) - grid.centreX();
                long dz = grid.z(py) - grid.centreZ();
                if (dx * dx + dz * dz > r2) continue;
                attempted++;
                String biome = disc.biome(px, py);
                counts.merge(biome, 1, Integer::sum);
                int zone = zones.zoneAt(grid.x(px), grid.z(py));
                if (zone >= 0) {
                    zoneColumns[zone]++;
                    if (zoneList.get(zone).biomes().contains(biome)) zoneIntended[zone]++;
                }
                if (disc.empty(px, py)) continue;
                ground++;
                heights[n++] = disc.height(px, py);
                if (disc.fluid(px, py) == ColumnSamples.Fluid.WATER) water++;
            }
        }
        Map<String, Double> shares = new LinkedHashMap<>();
        double headline = 0.0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            double share = attempted == 0 ? 0.0 : e.getValue() / (double) attempted;
            shares.put(e.getKey(), share);
            headline = Math.max(headline, share);
        }
        RollFacts.Biomes biomes = new RollFacts.Biomes(shares, counts.size(), headline, edgeDensity(patch));
        int[] sorted = Arrays.copyOf(heights, n);
        Arrays.sort(sorted);
        String noGround = "no sampled column inside the disc has ground";
        RollFacts.Terrain terrain = new RollFacts.Terrain(
                attempted == 0 ? 0.0 : ground / (double) attempted,
                attempted == 0 ? 0.0 : water / (double) attempted,
                n == 0 ? Measured.absent(noGround) : Measured.of((double) (percentile(sorted, 0.75) - percentile(sorted, 0.25))),
                n == 0 ? Measured.absent(noGround) : Measured.of(percentile(sorted, 0.02)),
                n == 0 ? Measured.absent(noGround) : Measured.of(percentile(sorted, 0.98)));
        List<RollFacts.ZoneShare> zoneShares = new ArrayList<>();
        for (int i = 0; i < zoneList.size(); i++) {
            zoneShares.add(new RollFacts.ZoneShare(zoneList.get(i).id(), List.copyOf(zoneList.get(i).biomes()),
                    zoneColumns[i], zoneIntended[i]));
        }
        return new RollFacts(target.dimension(), seed, target.playableRadius(), radius, structureRadius,
                spawn(patch), biomes, terrain, structures, zoneShares);
    }

    static RollFacts.Spawn spawn(ColumnSamples patch) {
        RenderGrid g = patch.grid();
        int c = g.side() / 2;
        boolean hasGround = !patch.empty(c, c);
        int safe = 0;
        int total = 0;
        for (int py = 0; py < g.side(); py++) {
            for (int px = 0; px < g.side(); px++) {
                total++;
                if (safe(patch, px, py)) safe++;
            }
        }
        return new RollFacts.Spawn(g.x(c), g.z(c), Measured.of(patch.biome(c, c)),
                hasGround ? Measured.of(patch.height(c, c)) : Measured.absent("the spawn column has no ground"),
                total == 0 ? Measured.absent("the spawn patch is empty") : Measured.of(safe / (double) total));
    }

    /** Ground, no fluid on it, and no step taller than {@link #SAFE_STEP} to any neighbour in the patch. */
    static boolean safe(ColumnSamples s, int px, int py) {
        if (s.empty(px, py) || s.fluid(px, py) != ColumnSamples.Fluid.NONE) return false;
        int h = s.height(px, py);
        int side = s.grid().side();
        int[][] next = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : next) {
            int x = px + d[0];
            int y = py + d[1];
            if (x < 0 || y < 0 || x >= side || y >= side) continue;
            if (s.empty(x, y) || Math.abs(s.height(x, y) - h) > SAFE_STEP) return false;
        }
        return true;
    }

    static Measured<Double> edgeDensity(ColumnSamples s) {
        int side = s.grid().side();
        int pairs = 0;
        int edges = 0;
        for (int py = 0; py < side; py++) {
            for (int px = 0; px < side; px++) {
                String b = s.biome(px, py);
                if (px + 1 < side) {
                    pairs++;
                    if (!b.equals(s.biome(px + 1, py))) edges++;
                }
                if (py + 1 < side) {
                    pairs++;
                    if (!b.equals(s.biome(px, py + 1))) edges++;
                }
            }
        }
        return pairs == 0 ? Measured.absent("the spawn patch has no adjacent pairs") : Measured.of(edges / (double) pairs);
    }

    static int percentile(int[] sorted, double p) {
        return sorted[(int) Math.round((sorted.length - 1) * p)];
    }
}
