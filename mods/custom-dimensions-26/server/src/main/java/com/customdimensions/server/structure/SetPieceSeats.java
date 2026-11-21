package com.customdimensions.server.structure;

import com.customdimensions.core.structure.SetPiecePlan;
import com.customdimensions.core.structure.SetPieceShapes;
import com.customdimensions.core.structure.SetPieceSpec;
import com.customdimensions.server.CustomDimensions;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

import java.util.ArrayList;
import java.util.List;

/**
 * The seats {@code roof}, {@code wall} and {@code water} (places/set-pieces.md
 * § Seats), read from the level's base columns as the other seats are, and the
 * points a shape resolves when its start is made: a fall's outward axis, a
 * line's two ends ({@code ground} and {@code roof} found at their columns), a
 * crown's tops.
 */
final class SetPieceSeats {

    private SetPieceSeats() {
    }

    static final int ROOF = 8;
    static final int HEADROOM = 4;

    /**
     * Under a roof (the column's surface heightmap within {@value #ROOF} of the
     * generator's top), the floor: the first air over solid ground from sea
     * level up with {@value #HEADROOM} blocks of headroom. Null where the
     * column has no roof or no such floor.
     */
    static Integer roofedFloor(ChunkGenerator generator, RandomState random, int x, int z) {
        LevelHeightAccessor height = LevelHeightAccessor.create(generator.getMinY(), generator.getGenDepth());
        if (generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, height, random) < height.getMaxY() - ROOF) return null;
        NoiseColumn column = generator.getBaseColumn(x, z, height, random);
        for (int y = Math.max(height.getMinY() + 1, generator.getSeaLevel()); y <= height.getMaxY() - ROOF - HEADROOM; y++) {
            BlockState below = column.getBlock(y - 1);
            if (below.isAir() || !below.getFluidState().isEmpty()) continue;
            boolean open = true;
            for (int k = 0; k < HEADROOM && open; k++) open = column.getBlock(y + k).isAir();
            if (open) return y;
        }
        return null;
    }

    /** Whether a piece's shapes find their ground by scanning the column near the origin (floor, roof, a banded wall) rather than the surface. */
    static boolean scans(SetPieceSpec spec) {
        return "floor".equals(spec.seat()) || "roof".equals(spec.seat()) || "wall".equals(spec.seat()) && spec.bandMin() != null;
    }

    private static boolean solid(BlockState s) {
        return !s.isAir() && s.getFluidState().isEmpty();
    }

    /** The lowest block of the ceiling over the floor at this column, within the spec's reach, or null. */
    static Integer roof(SetPieceSpec spec, ChunkGenerator generator, LevelHeightAccessor height, RandomState random, int x, int z) {
        Integer floor = SetPieceStructure.floor(spec, generator, height, random, x, z, null);
        if (floor == null) return null;
        NoiseColumn column = generator.getBaseColumn(x, z, height, random);
        for (int y = floor; y <= Math.min(height.getMaxY(), floor + spec.setting().reach()); y++) {
            if (solid(column.getBlock(y))) return y;
        }
        return null;
    }

    /** The lowest block of a ceiling nearest {@code near} at this column (solid with air under it), or null. */
    static Integer ceiling(ChunkGenerator generator, LevelHeightAccessor height, RandomState random, int x, int z, int near) {
        NoiseColumn column = generator.getBaseColumn(x, z, height, random);
        Integer best = null;
        for (int y = Math.max(height.getMinY() + 1, near - 16); y <= Math.min(height.getMaxY(), near + 16); y++) {
            if (!solid(column.getBlock(y)) || !column.getBlock(y - 1).isAir()) continue;
            if (best == null || Math.abs(y - near) < Math.abs(best - near)) best = y;
        }
        return best;
    }

    /** The water surface (its bed with {@code bed}) where the water is the spec's depth or more, or null. */
    static Integer water(SetPieceSpec spec, ChunkGenerator generator, LevelHeightAccessor height, RandomState random, int x, int z) {
        int top = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, height, random);
        int bed = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, height, random);
        if (top - bed < spec.setting().depth()) return null;
        return spec.setting().bed() ? bed : top;
    }

    /** An element's ground in a water piece: the water where there is enough, else the surface. */
    static Integer waterOrSurface(SetPieceSpec spec, ChunkGenerator generator, LevelHeightAccessor height, RandomState random, int x, int z) {
        Integer w = water(spec, generator, height, random, x, z);
        return w != null ? w : SetPieceStructure.surface(generator, height, random, x, z);
    }

    private static Integer base(SetPieceSpec spec, ChunkGenerator generator, LevelHeightAccessor height, RandomState random, int x, int z,
                                Integer near) {
        return spec.bandMin() != null ? SetPieceStructure.floor(spec, generator, height, random, x, z, near)
                : SetPieceStructure.surface(generator, height, random, x, z);
    }

    /**
     * The nearest face along the four axes within the spec's reach: a column
     * whose ground stands {@code rise} or more above its neighbour's. The
     * origin moves onto that face's lip, its height the lip's, turned so the
     * piece's local x points out over the drop.
     */
    static SetPieceStructure.Seat wall(SetPieceSpec spec, ChunkGenerator generator, LevelHeightAccessor height, RandomState random, int x, int z) {
        int reach = spec.setting().reach();
        int rise = spec.setting().rise();
        Integer h0 = base(spec, generator, height, random, x, z, null);
        if (h0 == null) return new SetPieceStructure.Seat(null, "no ground under its origin to look for a face from");
        int best = -1;
        int[] dir = null;
        int lip = 0;
        for (int[] d : new int[][] {{1, 0}, {0, 1}, {-1, 0}, {0, -1}}) {
            int prev = h0;
            for (int k = 1; k <= reach && (best < 0 || k < best); k++) {
                Integer hk = base(spec, generator, height, random, x + d[0] * k, z + d[1] * k, prev);
                if (hk == null) break;
                if (hk - prev >= rise) {
                    best = k;
                    dir = d;
                    lip = hk;
                    break;
                }
                prev = hk;
            }
        }
        if (dir == null) return new SetPieceStructure.Seat(null, "no face rising " + rise + " or more within " + reach + " blocks");
        int rotation = 0;
        for (int k = 0; k < 4; k++) {
            int[] v = SetPiecePlan.rotate(1, 0, k);
            if (v[0] == -dir[0] && v[1] == -dir[1]) rotation = k;
        }
        return new SetPieceStructure.Seat(lip, null, x + dir[0] * best, z + dir[1] * best, rotation);
    }

    /** The shape a placement draws, with its points resolved from the base columns; null when a line's end finds no roof. */
    static SetPieceShapes.Shape shape(SetPieceSpec spec, SetPiecePlan.Placement p, BlockPos origin, int x, int y, int z,
                                      ChunkGenerator generator, LevelHeightAccessor height, RandomState random) {
        JsonObject params = p.source().params();
        List<int[]> points = new ArrayList<>();
        switch (p.kind()) {
            case "fall" -> {
                if (p.dx() != 0 || p.dz() != 0) {
                    points.add(Math.abs(p.dx()) >= Math.abs(p.dz()) ? new int[] {-Integer.signum(p.dx()), 0} : new int[] {0, -Integer.signum(p.dz())});
                } else {
                    points.add(SetPiecePlan.rotate(1, 0, p.rotation()));
                }
            }
            case "line" -> {
                for (String key : List.of("from", "to")) {
                    int[] end = end(spec, p, params.getAsJsonArray(key), origin, x, y, z, generator, height, random);
                    if (end == null) {
                        CustomDimensions.LOG.info("set piece {}: a line's {} finds no roof above y {}; the line is not drawn", spec.key(), key, y);
                        return null;
                    }
                    points.add(end);
                }
            }
            case "crown" -> points.addAll(SetPieceShapes.tops((cx, cz) -> {
                Integer v = SetPieceStructure.groundAt(spec, generator, height, random, cx, cz, origin.getY());
                return v == null ? SetPieceShapes.NO_GROUND : v;
            }, x, z, params.get("radius").getAsInt(), params.has("window") ? params.get("window").getAsInt() : 6,
                    params.has("rise") ? params.get("rise").getAsInt() : 4, 2));
            default -> {
            }
        }
        return new SetPieceShapes.Shape(p.kind(), x, y, z, p.rotation(), p.seed(), params, spec.palette(), p.age(), points);
    }

    /** A line's end: its offset turned with the element, its y above the anchor, or the ground or the roof at its column. */
    private static int[] end(SetPieceSpec spec, SetPiecePlan.Placement p, JsonArray a, BlockPos origin, int x, int y, int z,
                             ChunkGenerator generator, LevelHeightAccessor height, RandomState random) {
        int[] d = SetPiecePlan.rotate(a.get(0).getAsInt(), a.get(2).getAsInt(), p.rotation());
        int ex = x + d[0];
        int ez = z + d[1];
        JsonElement v = a.get(1);
        if (v.getAsJsonPrimitive().isNumber()) return new int[] {ex, y + v.getAsInt(), ez};
        if (v.getAsString().equals("ground")) {
            Integer g = SetPieceStructure.groundAt(spec, generator, height, random, ex, ez, origin.getY());
            return g == null ? null : new int[] {ex, g, ez};
        }
        NoiseColumn column = generator.getBaseColumn(ex, ez, height, random);
        for (int k = y; k <= Math.min(height.getMaxY(), y + spec.setting().reach()); k++) {
            if (solid(column.getBlock(k))) return new int[] {ex, k - 1, ez};
        }
        return null;
    }
}
