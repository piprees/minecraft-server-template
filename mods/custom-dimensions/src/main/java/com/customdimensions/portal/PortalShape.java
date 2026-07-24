package com.customdimensions.portal;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.Set;

/**
 * Named portal shape presets ("shape" in the portal config): geometry
 * validation applied AFTER the flood-fill finds a candidate interior.
 * Absent/"standard" keeps today's free-form behaviour; a named preset
 * constrains what the player may build. Pure geometry — registry-free and
 * unit-testable.
 *
 * Shapes also imply an orientation default (door/doorway are vertical,
 * end_exit is horizontal) — an explicit "orientation" field always wins.
 * Unknown shape names never match any fill, so ignition simply fails until
 * the config is fixed (WARN at boot via PortalSafetyValidator — never
 * crash, never auto-fix).
 */
public final class PortalShape {

    public static final String STANDARD = "standard";
    public static final String DOOR = "door";
    public static final String DOORWAY = "doorway";
    public static final String END_EXIT = "end_exit";

    public static final Set<String> KNOWN = Set.of(STANDARD, DOOR, DOORWAY, END_EXIT);

    private PortalShape() {
    }

    /** Normalised shape name; null/blank collapse to "standard". */
    public static String normalise(String shape) {
        return shape == null || shape.isBlank() ? STANDARD : shape.trim();
    }

    /**
     * Orientation implied by the shape when the config doesn't set one:
     * door/doorway are vertical, end_exit horizontal, everything else null
     * (no implication — the "any" default applies).
     */
    public static String impliedOrientation(String shape) {
        switch (normalise(shape)) {
            case DOOR:
            case DOORWAY:
                return "vertical";
            case END_EXIT:
                return "horizontal";
            default:
                return null;
        }
    }

    /**
     * Does a discovered interior satisfy the shape on this axis?
     * "standard" accepts anything the flood-fill produced; unknown names
     * accept nothing.
     */
    public static boolean matches(String shape, Set<BlockPos> interior, Direction.Axis axis) {
        if (interior == null || interior.isEmpty()) {
            return false;
        }
        switch (normalise(shape)) {
            case STANDARD:
                return true;
            case DOOR:
                return isDoor(interior, axis);
            case DOORWAY:
                return isDoorway(interior, axis);
            case END_EXIT:
                // Thematic, not prescriptive: any horizontal ring the player
                // builds is valid — the preset's job is forcing the Y plane.
                return axis == Direction.Axis.Y;
            default:
                return false;
        }
    }

    // 1x2 vertical interior: exactly two blocks, same column, one apart.
    private static boolean isDoor(Set<BlockPos> interior, Direction.Axis axis) {
        if (axis == Direction.Axis.Y || interior.size() != 2) {
            return false;
        }
        var it = interior.iterator();
        BlockPos a = it.next();
        BlockPos b = it.next();
        return a.getX() == b.getX() && a.getZ() == b.getZ()
                && Math.abs(a.getY() - b.getY()) == 1;
    }

    // 2x3 vertical interior (the vanilla Nether portal opening): exactly six
    // blocks filling a 2-wide (in-plane) by 3-tall bounding box, one deep.
    private static boolean isDoorway(Set<BlockPos> interior, Direction.Axis axis) {
        if (axis == Direction.Axis.Y || interior.size() != 6) {
            return false;
        }
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : interior) {
            minX = Math.min(minX, p.getX());
            maxX = Math.max(maxX, p.getX());
            minY = Math.min(minY, p.getY());
            maxY = Math.max(maxY, p.getY());
            minZ = Math.min(minZ, p.getZ());
            maxZ = Math.max(maxZ, p.getZ());
        }
        int spanX = maxX - minX + 1;
        int spanY = maxY - minY + 1;
        int spanZ = maxZ - minZ + 1;
        // size()==6 plus a 2x3x1 bounding box means the box is exactly full.
        if (axis == Direction.Axis.X) {
            return spanX == 2 && spanY == 3 && spanZ == 1;
        }
        return spanZ == 2 && spanY == 3 && spanX == 1;
    }

    /**
     * The interior position closest to the bounding-box centre — where
     * end_exit's centreBlock goes. Falls back across the interior so odd
     * ring shapes still get a deterministic pedestal cell.
     */
    public static BlockPos centreOf(Set<BlockPos> interior) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : interior) {
            minX = Math.min(minX, p.getX());
            maxX = Math.max(maxX, p.getX());
            minY = Math.min(minY, p.getY());
            maxY = Math.max(maxY, p.getY());
            minZ = Math.min(minZ, p.getZ());
            maxZ = Math.max(maxZ, p.getZ());
        }
        BlockPos ideal = new BlockPos(
                Math.floorDiv(minX + maxX, 2),
                Math.floorDiv(minY + maxY, 2),
                Math.floorDiv(minZ + maxZ, 2));
        if (interior.contains(ideal)) {
            return ideal;
        }
        BlockPos best = null;
        int bestDist = Integer.MAX_VALUE;
        for (BlockPos p : interior) {
            int dist = p.getManhattanDistance(ideal);
            // Ties break on BlockPos ordering so the pick is deterministic.
            if (dist < bestDist || (dist == bestDist && best != null && p.compareTo(best) < 0)) {
                best = p;
                bestDist = dist;
            }
        }
        return best;
    }
}
