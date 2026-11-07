package com.customdimensions.core.portal;

import com.customdimensions.core.BlockPos;
import com.customdimensions.core.Direction;

import java.util.HashSet;
import java.util.Set;

/**
 * Where an arrival portal goes in a column: a STANDARD opening (2x3 upright,
 * 3x3 flat) at an OPEN site, else a carved one, else none.
 *
 * <p>Ask the column, never the dimension type. In a ceilinged world the band
 * runs from just under the roof, measured live, down to the world floor, so
 * "standing on the roof" is unreachable by construction; never start from a
 * logical height. Elsewhere the band starts at the surface and reaches
 * {@link #SCAN_DEPTH} down. When nothing is open, carve rather than fall back
 * to the heightmap, which reads the roof in a ceilinged world.
 */
public final class ArrivalSite {

    public static final int NO_SITE = Integer.MIN_VALUE;
    public static final int STANDARD_WIDTH = 2;
    public static final int STANDARD_HEIGHT = 3;
    public static final int STANDARD_PAD = 3;
    public static final int SCAN_DEPTH = 48;
    public static final int CEILING_CLEARANCE = 2;
    public static final int EGRESS_DEPTH = 1;

    /** The block reads a site search makes. */
    public interface Probe {
        /** Air or replaceable, and no fluid. */
        boolean isClear(BlockPos pos);

        /** A full opaque cube. */
        boolean isOpaque(BlockPos pos);

        /** Not bedrock and holds no block entity. */
        boolean isCarveable(BlockPos pos);
    }

    /** The column's vertical facts: {@code topY} is exclusive. */
    public record Column(int bottomY, int topY, boolean hasCeiling) {
    }

    private ArrivalSite() {
    }

    public static Set<BlockPos> standardInterior(int centreX, int baseY, int centreZ, Direction.Axis axis) {
        Set<BlockPos> out = new HashSet<>();
        if (axis == Direction.Axis.Y) {
            for (int dx = 0; dx < STANDARD_PAD; dx++) {
                for (int dz = 0; dz < STANDARD_PAD; dz++) {
                    out.add(new BlockPos(centreX + dx, baseY, centreZ + dz));
                }
            }
            return out;
        }
        for (int w = 0; w < STANDARD_WIDTH; w++) {
            for (int h = 0; h < STANDARD_HEIGHT; h++) {
                out.add(axis == Direction.Axis.X
                        ? new BlockPos(centreX + w, baseY + h, centreZ)
                        : new BlockPos(centreX, baseY + h, centreZ + w));
            }
        }
        return out;
    }

    /** The highest open base Y in the column's band, or {@link #NO_SITE}. */
    public static int findOpenY(Column column, int centreX, int centreZ, Direction.Axis axis, int surfaceY, Probe probe) {
        int floor = column.bottomY() + 1;
        int highest;
        int lowest;
        if (column.hasCeiling()) {
            int ceilingY = findCeilingY(centreX, centreZ, column.topY() - 1, floor, probe);
            highest = ceilingY == NO_SITE
                    ? Math.min(surfaceY, column.topY() - STANDARD_HEIGHT - CEILING_CLEARANCE)
                    : ceilingY - STANDARD_HEIGHT - CEILING_CLEARANCE;
            lowest = floor;
        } else {
            highest = Math.min(surfaceY, column.topY() - STANDARD_HEIGHT - CEILING_CLEARANCE);
            lowest = Math.max(floor, highest - SCAN_DEPTH);
        }
        for (int y = highest; y >= lowest; y--) {
            if (fits(centreX, y, centreZ, axis, probe)) {
                return y;
            }
        }
        return NO_SITE;
    }

    /**
     * The same band searched for a site that can be OPENED: carveable cells,
     * preferring solid ground under the floor row, else the highest
     * unsupported one. {@link #NO_SITE} means bedrock all the way.
     */
    public static int findCarveY(Column column, int centreX, int centreZ, Direction.Axis axis, int surfaceY, Probe probe) {
        int floor = column.bottomY() + 1;
        int highest;
        int lowest;
        if (column.hasCeiling()) {
            int ceilingY = findCeilingY(centreX, centreZ, column.topY() - 1, floor, probe);
            highest = ceilingY == NO_SITE
                    ? Math.min(surfaceY, column.topY() - STANDARD_HEIGHT - CEILING_CLEARANCE)
                    : ceilingBandTop(centreX, centreZ, ceilingY, floor, probe);
            lowest = floor;
        } else {
            highest = Math.min(surfaceY, column.topY() - STANDARD_HEIGHT - CEILING_CLEARANCE);
            lowest = Math.max(floor, highest - SCAN_DEPTH);
        }
        int unsupported = NO_SITE;
        for (int y = highest; y >= lowest; y--) {
            boolean carveable = true;
            boolean supported = false;
            for (BlockPos p : standardInterior(centreX, y, centreZ, axis)) {
                if (!probe.isCarveable(p)) {
                    carveable = false;
                    break;
                }
                if (p.getY() == y && probe.isOpaque(p.add(0, -1, 0))) {
                    supported = true;
                }
            }
            if (!carveable) {
                continue;
            }
            if (supported) {
                return y;
            }
            if (unsupported == NO_SITE) {
                unsupported = y;
            }
        }
        return unsupported;
    }

    /** The highest opaque block in the column, or {@link #NO_SITE}. */
    public static int findCeilingY(int centreX, int centreZ, int fromY, int toY, Probe probe) {
        for (int y = fromY; y >= toY; y--) {
            if (probe.isOpaque(new BlockPos(centreX, y, centreZ))) {
                return y;
            }
        }
        return NO_SITE;
    }

    /** The first open block below the contiguous roof slab, or {@link #NO_SITE}. */
    public static int findRoofUndersideY(int centreX, int centreZ, int ceilingY, int toY, Probe probe) {
        for (int y = ceilingY; y >= toY; y--) {
            if (!probe.isOpaque(new BlockPos(centreX, y, centreZ))) {
                return y;
            }
        }
        return NO_SITE;
    }

    private static int ceilingBandTop(int centreX, int centreZ, int ceilingY, int floor, Probe probe) {
        int underside = findRoofUndersideY(centreX, centreZ, ceilingY, floor, probe);
        int belowHighestSolid = ceilingY - STANDARD_HEIGHT - CEILING_CLEARANCE;
        return underside == NO_SITE ? belowHighestSolid : Math.min(belowHighestSolid, underside - STANDARD_HEIGHT);
    }

    static boolean fits(int centreX, int baseY, int centreZ, Direction.Axis axis, Probe probe) {
        boolean supported = false;
        for (BlockPos p : standardInterior(centreX, baseY, centreZ, axis)) {
            if (!probe.isClear(p)) {
                return false;
            }
            if (p.getY() == baseY && probe.isOpaque(p.add(0, -1, 0))) {
                supported = true;
            }
        }
        return supported;
    }

    public static boolean isClear(boolean air, boolean replaceable, boolean fluid) {
        return (air || replaceable) && !fluid;
    }

    /** The cells either side of the opening along its normal, {@code depth} deep. */
    public static Set<BlockPos> egressCells(Set<BlockPos> interior, Direction.Axis axis, int depth) {
        Set<BlockPos> out = new HashSet<>();
        if (interior == null || interior.isEmpty()) {
            return out;
        }
        Direction plus = axis == Direction.Axis.X ? Direction.SOUTH : axis == Direction.Axis.Z ? Direction.EAST : Direction.UP;
        Direction minus = axis == Direction.Axis.X ? Direction.NORTH : axis == Direction.Axis.Z ? Direction.WEST : Direction.DOWN;
        for (BlockPos p : interior) {
            for (int step = 1; step <= depth; step++) {
                BlockPos a = PortalGeometry.offset(p, plus, step);
                BlockPos b = PortalGeometry.offset(p, minus, step);
                if (!interior.contains(a)) {
                    out.add(a);
                }
                if (!interior.contains(b)) {
                    out.add(b);
                }
            }
        }
        return out;
    }

    /** The interior and its egress: everything an arrival empties before it builds. */
    public static Set<BlockPos> arrivalCells(Set<BlockPos> interior, Direction.Axis axis) {
        Set<BlockPos> out = egressCells(interior, axis, EGRESS_DEPTH);
        out.addAll(interior);
        return out;
    }
}
