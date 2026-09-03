package com.customdimensions.portal;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where in a portal's opening a particle may be emitted, and how often.
 *
 * <p>Pure geometry and arithmetic: no world, no {@code Random}. The emission
 * decision is a hash of (tick, cell), so a cell's pattern is reproducible off
 * a running server and can be asserted without one.
 *
 * <h2>Why edge weighting</h2>
 * A portal here is a frame, an empty interior, and the effects. An even fill
 * of the interior reads as a coloured SURFACE stretched across the frame; the
 * same particle count pushed towards the rim reads as an opening, because the
 * middle of the plane stays clear enough to see the destination through.
 * {@link #rimDepths} measures how far into the opening each cell sits and
 * {@link #edgeWeight} turns that into an emission chance.
 *
 * <h2>The cap is the guarantee</h2>
 * Density and edge bias are config, so neither can promise anything. {@link
 * #emissionCap} is a hard ceiling on how many cells may emit in one pass
 * whatever the config asks for — no configuration can fill the plane.
 */
public final class PortalAperture {

    /**
     * Largest share of an opening's cells that may emit in a single pass.
     * Half a plane of dust still reads through; a full one does not.
     */
    public static final double MAX_FILL = 0.5;

    /** Fill share of a plane below which the opening reads as a hole, not a pane. */
    public static final double DEFAULT_DENSITY = 0.35;

    /** Per-cell-of-depth multiplier; 1.0 is an even fill, 0.0 is the rim only. */
    public static final double DEFAULT_EDGE_BIAS = 0.45;

    private PortalAperture() {
    }

    /**
     * In-plane distance from each interior cell to the frame: 0 for a cell
     * touching a non-interior neighbour, growing inwards.
     *
     * <p>A one-cell-thick opening is all rim, which is correct — a vanilla-
     * sized portal has no middle to clear.
     */
    public static Map<BlockPos, Integer> rimDepths(Set<BlockPos> interior, Direction.Axis axis) {
        Map<BlockPos, Integer> depths = new HashMap<>();
        if (interior == null || interior.isEmpty()) {
            return depths;
        }
        Direction[] planeDirs = PortalHelper.planeDirections(axis);
        Deque<BlockPos> queue = new ArrayDeque<>();
        for (BlockPos cell : interior) {
            for (Direction dir : planeDirs) {
                if (!interior.contains(cell.offset(dir))) {
                    depths.put(cell, 0);
                    queue.add(cell);
                    break;
                }
            }
        }
        while (!queue.isEmpty()) {
            BlockPos cell = queue.removeFirst();
            int next = depths.get(cell) + 1;
            for (Direction dir : planeDirs) {
                BlockPos neighbour = cell.offset(dir);
                if (interior.contains(neighbour) && !depths.containsKey(neighbour)) {
                    depths.put(neighbour, next);
                    queue.add(neighbour);
                }
            }
        }
        return depths;
    }

    /**
     * Emission chance for a cell at {@code rimDepth}, before density.
     * {@code edgeBias} raised to the depth: 1.0 leaves every cell equal, 0.0
     * leaves only the rim emitting.
     */
    public static double edgeWeight(int rimDepth, double edgeBias) {
        if (rimDepth <= 0) {
            return 1.0;
        }
        double bias = clamp01(edgeBias);
        return Math.pow(bias, rimDepth);
    }

    /**
     * Does this cell emit on this pass? A deterministic dither rather than a
     * roll: the same cell on the same tick always answers the same, so a
     * density is reproducible and a whole pass can be counted in a test.
     */
    public static boolean emits(long tick, BlockPos cell, double probability) {
        if (probability <= 0.0) {
            return false;
        }
        if (probability >= 1.0) {
            return true;
        }
        return unitHash(tick, cell, 0x9E3779B97F4A7C15L) < probability;
    }

    /**
     * Which way this cell's dust leaves the plane when nobody's position
     * decides it: an even split, so both sides are fed. The tie-break for
     * {@link #driftSignToward}, whose viewer is level with the plane.
     */
    public static int driftSign(long tick, BlockPos cell) {
        return unitHash(tick, cell, 0xD6E8FEB86659FD93L) < 0.5 ? -1 : 1;
    }

    /**
     * Which way this cell's dust leaves the plane FOR ONE VIEWER: towards
     * them, so it drifts out of the opening rather than into an immersive
     * portal's projection, which always stands on their far side. Same
     * block-coordinate side test as {@code ProjectionVolume.viewerFarSide},
     * so the drift and the slab can never disagree.
     *
     * <p>Outward has no meaning without a viewer, and no single meaning for
     * two of them standing on opposite sides of one frame — hence a sign per
     * viewer rather than a rule per pass. Level with the plane is the doorway
     * itself, which has no near side: {@link #driftSign} feeds both.
     */
    public static int driftSignToward(int planeCoord, int viewerCoord, long tick, BlockPos cell) {
        if (viewerCoord < planeCoord) {
            return -1;
        }
        if (viewerCoord > planeCoord) {
            return 1;
        }
        return driftSign(tick, cell);
    }

    /** Hard ceiling on emitting cells per pass, whatever density asks for. */
    public static int emissionCap(int interiorSize) {
        if (interiorSize <= 0) {
            return 0;
        }
        return Math.max(1, (int) Math.floor(interiorSize * MAX_FILL));
    }

    /**
     * The cells emitting on one pass: rim-weighted, density-scaled, and
     * truncated at {@link #emissionCap}.
     *
     * <p>The whole pass in one pure call, so a density claim is a countable
     * assertion rather than a description. Ordered rim-first, so an opening
     * large enough to reach the cap spends its budget on the edge, which is
     * where it reads from.
     */
    public static List<BlockPos> emittingCells(Set<BlockPos> interior, Direction.Axis axis,
            long tick, double density, double edgeBias) {
        if (interior == null || interior.isEmpty()) {
            return List.of();
        }
        double scaled = clamp01(density);
        if (scaled <= 0.0) {
            return List.of();
        }
        Map<BlockPos, Integer> depths = rimDepths(interior, axis);
        List<BlockPos> chosen = new ArrayList<>();
        for (Map.Entry<BlockPos, Integer> entry : depths.entrySet()) {
            if (emits(tick, entry.getKey(), edgeWeight(entry.getValue(), edgeBias) * scaled)) {
                chosen.add(entry.getKey());
            }
        }
        chosen.sort(Comparator.<BlockPos>comparingInt(depths::get).thenComparingLong(BlockPos::asLong));
        int cap = emissionCap(interior.size());
        return chosen.size() <= cap ? chosen : new ArrayList<>(chosen.subList(0, cap));
    }

    /** The axis a portal plane faces: an X-axis plane is crossed along Z. */
    public static Direction.Axis normalAxis(Direction.Axis planeAxis) {
        return switch (planeAxis) {
            case X -> Direction.Axis.Z;
            case Z -> Direction.Axis.X;
            case Y -> Direction.Axis.Y;
        };
    }

    /**
     * A deterministic offset in [-spread, +spread] for one cell, pass and
     * channel. {@code count = 0} on a particle packet buys per-particle
     * velocity at the cost of vanilla's own scatter, so the scatter is done
     * here — without it every particle sits on a block-centre lattice, which
     * is what makes a plane read as tiling rather than haze.
     */
    public static double jitter(long tick, BlockPos cell, int channel, double spread) {
        return (unitHash(tick, cell, 0x2545F4914F6CDD1DL * (channel + 1)) * 2.0 - 1.0) * spread;
    }

    public static double clamp01(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    /** SplitMix64 finaliser over the cell and tick, mapped to [0, 1). */
    private static double unitHash(long tick, BlockPos cell, long salt) {
        long z = tick * 0x2545F4914F6CDD1DL
                + cell.asLong() * 0xBF58476D1CE4E5B9L
                + salt;
        z ^= z >>> 30;
        z *= 0xBF58476D1CE4E5B9L;
        z ^= z >>> 27;
        z *= 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 11) * 0x1.0p-53;
    }
}
