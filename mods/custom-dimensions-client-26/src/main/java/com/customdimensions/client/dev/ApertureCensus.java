package com.customdimensions.client.dev;

import com.customdimensions.client.view.Opening;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What the picture inside a portal frame is made of, measured on the fed
 * destination rather than on pixels: a grid of rays from the eye through the
 * aperture, each carried across by the frame's offset and walked voxel by
 * voxel through the destination's fed chunks, from the layer past the far
 * portal's own (where the renderer starts) out to {@code depth} blocks. A ray
 * {@code hits} the first non-air block, is {@code unfed} when it enters a
 * chunk the client does not hold before any block, and is {@code open} when it
 * reaches the depth through air: sky, void or a long view. No Minecraft types,
 * so it is tested against a fake scene.
 */
public final class ApertureCensus {
    public static final int GRID = 12;
    static final double EPSILON = 1.0e-6;

    /** The fed destination, read in destination coordinates. */
    public interface Scene {
        /** The block id there, or null when its chunk is not held. */
        String block(int x, int y, int z);

        /** The biome id there, or null when its chunk is not held. */
        String biome(int x, int y, int z);
    }

    public record Hit(String block, String biome, double distance) {
    }

    /** The census: ray counts by outcome, then block and biome counts over the rays that hit. */
    public record Result(int rays, int hits, int unfed, int open, Map<String, Integer> blocks,
                         Map<String, Integer> biomes, Double medianHitDistance) {
    }

    private ApertureCensus() {
    }

    static boolean air(String id) {
        return id.equals("minecraft:air") || id.equals("minecraft:cave_air") || id.equals("minecraft:void_air");
    }

    /**
     * Casts {@code grid}×{@code grid} rays across the opening's rectangle,
     * keeping those that pass through one of its {@code cells} (a shaped frame's
     * aperture need not fill its rectangle).
     */
    public static Result measure(double[] eye, List<int[]> cells, int portalAxis, int[] offset, Scene scene, int grid,
                                 int depth) {
        Opening op = Opening.of(cells, portalAxis);
        int n = op.normalAxis();
        int a = Opening.aAxis(n);
        int b = Opening.bAxis(n);
        boolean high = op.towardsHigh(eye[n]);
        double s = high ? op.plane() : op.plane() + 1.0;
        Set<Long> aperture = new HashSet<>();
        for (int[] c : cells) {
            aperture.add(pack(c[a], c[b]));
        }
        int hits = 0;
        int unfed = 0;
        int open = 0;
        int rays = 0;
        Map<String, Integer> blocks = new TreeMap<>();
        Map<String, Integer> biomes = new TreeMap<>();
        List<Double> distances = new ArrayList<>();
        for (int i = 0; i < grid; i++) {
            for (int j = 0; j < grid; j++) {
                double ua = op.a0() + (op.a1() - op.a0()) * (i + 0.5) / grid;
                double ub = op.b0() + (op.b1() - op.b0()) * (j + 0.5) / grid;
                if (!aperture.contains(pack((int) Math.floor(ua), (int) Math.floor(ub)))) {
                    continue;
                }
                double[] onSurface = op.point(ua, ub, s);
                double[] dir = new double[3];
                double len = 0;
                for (int k = 0; k < 3; k++) {
                    dir[k] = onSurface[k] - eye[k];
                    len += dir[k] * dir[k];
                }
                len = Math.sqrt(len);
                if (len < EPSILON || Math.abs(dir[n]) < EPSILON) {
                    continue;
                }
                for (int k = 0; k < 3; k++) {
                    dir[k] /= len;
                }
                rays++;
                double[] start = new double[3];
                for (int k = 0; k < 3; k++) {
                    start[k] = onSurface[k] + offset[k];
                }
                int farLayer = op.plane() + offset[n];
                Object outcome = march(start, dir, n, farLayer, scene, depth);
                if (outcome instanceof Hit h) {
                    hits++;
                    blocks.merge(h.block(), 1, Integer::sum);
                    biomes.merge(h.biome() == null ? "unknown" : h.biome(), 1, Integer::sum);
                    distances.add(h.distance());
                } else if (outcome == Outcome.UNFED) {
                    unfed++;
                } else {
                    open++;
                }
            }
        }
        Collections.sort(distances);
        Double median = distances.isEmpty() ? null : distances.get(distances.size() / 2);
        return new Result(rays, hits, unfed, open, blocks, biomes, median);
    }

    enum Outcome { UNFED, OPEN }

    /** Amanatides–Woo voxel walk from {@code start} along {@code dir}; the far portal's own layer is skipped. */
    static Object march(double[] start, double[] dir, int normalAxis, int farLayer, Scene scene, int depth) {
        int[] cell = new int[3];
        int[] step = new int[3];
        double[] tMax = new double[3];
        double[] tDelta = new double[3];
        for (int k = 0; k < 3; k++) {
            double p = start[k] + dir[k] * EPSILON;
            cell[k] = (int) Math.floor(p);
            if (dir[k] > 0) {
                step[k] = 1;
                tMax[k] = (cell[k] + 1 - start[k]) / dir[k];
                tDelta[k] = 1 / dir[k];
            } else if (dir[k] < 0) {
                step[k] = -1;
                tMax[k] = (start[k] - cell[k]) / -dir[k];
                tDelta[k] = -1 / dir[k];
            } else {
                tMax[k] = Double.MAX_VALUE;
                tDelta[k] = Double.MAX_VALUE;
            }
        }
        double t = 0;
        while (t <= depth) {
            if (cell[normalAxis] != farLayer) {
                String id = scene.block(cell[0], cell[1], cell[2]);
                if (id == null) {
                    return Outcome.UNFED;
                }
                if (!air(id)) {
                    return new Hit(id, scene.biome(cell[0], cell[1], cell[2]), t);
                }
            }
            int k = tMax[0] < tMax[1] ? (tMax[0] < tMax[2] ? 0 : 2) : (tMax[1] < tMax[2] ? 1 : 2);
            t = tMax[k];
            cell[k] += step[k];
            tMax[k] += tDelta[k];
        }
        return Outcome.OPEN;
    }

    private static long pack(int a, int b) {
        return ((long) a << 32) ^ (b & 0xFFFFFFFFL);
    }
}
