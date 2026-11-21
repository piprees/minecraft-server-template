package com.customdimensions.core.portal;

import com.customdimensions.core.BlockPos;
import com.customdimensions.core.Direction;
import com.customdimensions.core.config.PortalDefinition;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The plane geometry of a portal: the flood fill that finds an opening, the
 * frame checks around it, and the ring of frame positions. Every block read
 * goes through a {@link FrameView}, so all of it runs against a fixture.
 */
public final class PortalGeometry {

    /** The most cells a portal opening may carry. */
    public static final int MAX_PORTAL_BLOCKS = 128;

    private PortalGeometry() {
    }

    /** The four in-plane directions of a portal standing on {@code axis}. */
    public static Direction[] planeDirections(Direction.Axis axis) {
        if (axis == Direction.Axis.X) {
            return new Direction[]{Direction.WEST, Direction.EAST, Direction.UP, Direction.DOWN};
        }
        if (axis == Direction.Axis.Y) {
            return new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        }
        return new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.UP, Direction.DOWN};
    }

    /** {@code pos} moved {@code steps} blocks towards {@code dir}. */
    public static BlockPos offset(BlockPos pos, Direction dir, int steps) {
        return switch (dir) {
            case DOWN -> pos.add(0, -steps, 0);
            case UP -> pos.add(0, steps, 0);
            case NORTH -> pos.add(0, 0, -steps);
            case SOUTH -> pos.add(0, 0, steps);
            case WEST -> pos.add(-steps, 0, 0);
            case EAST -> pos.add(steps, 0, 0);
        };
    }

    public static BlockPos offset(BlockPos pos, Direction dir) {
        return offset(pos, dir, 1);
    }

    /**
     * A flood fill and, when it found no opening, which of the two ways it
     * failed. {@code refusal} is null exactly when {@code cells} is a real
     * opening; {@code blockedBy} names the block that stopped a leaking fill.
     */
    public record Fill(Set<BlockPos> cells, IgnitionRefusal refusal, int reached, BlockPos blockedBy) {
    }

    public static Fill floodFill(FrameView view, BlockPos start, FrameForms frame, Direction.Axis axis) {
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(start);
        Direction[] directions = planeDirections(axis);
        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            if (visited.size() > MAX_PORTAL_BLOCKS) {
                return new Fill(Set.of(), IgnitionRefusal.OPENING_TOO_LARGE, visited.size(), null);
            }
            for (Direction dir : directions) {
                BlockPos neighbour = offset(pos, dir);
                if (visited.contains(neighbour) || view.matches(neighbour, frame)) {
                    continue;
                }
                if (!view.isFillable(neighbour)) {
                    return new Fill(Set.of(), IgnitionRefusal.OPENING_NOT_ENCLOSED, visited.size(), neighbour);
                }
                visited.add(neighbour);
                queue.add(neighbour);
            }
        }
        return new Fill(visited, null, visited.size(), null);
    }

    /** Every in-plane neighbour of the opening is opening or frame. */
    public static boolean isBoundedByFrame(FrameView view, Set<BlockPos> area, FrameForms frame, Direction.Axis axis) {
        for (BlockPos pos : area) {
            for (Direction dir : planeDirections(axis)) {
                BlockPos neighbour = offset(pos, dir);
                if (!area.contains(neighbour) && !view.matches(neighbour, frame)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Which frame part a ring position belongs to: below the opening's lowest
     * row is "bottom", above its highest is "top", everything else "sides".
     */
    public static String classifyFramePart(BlockPos framePos, int interiorMinY, int interiorMaxY) {
        if (framePos.getY() < interiorMinY) {
            return "bottom";
        }
        if (framePos.getY() > interiorMaxY) {
            return "top";
        }
        return "sides";
    }

    /**
     * Per-part frame validation ({@code frameMaterials}): every ring position
     * satisfies ITS part. Uniform definitions and horizontal openings check
     * the union instead; top, bottom and sides mean nothing on a flat ring.
     */
    public static boolean isBoundedByFrameParts(FrameView view, Set<BlockPos> area, PortalDefinition def,
                                                Direction.Axis axis) {
        if (!def.hasPartMaterials() || axis == Direction.Axis.Y) {
            return isBoundedByFrame(view, area, def.resolveFrameForms(), axis);
        }
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (BlockPos p : area) {
            minY = Math.min(minY, p.getY());
            maxY = Math.max(maxY, p.getY());
        }
        for (BlockPos pos : area) {
            for (Direction dir : planeDirections(axis)) {
                BlockPos neighbour = offset(pos, dir);
                if (area.contains(neighbour)) {
                    continue;
                }
                if (!view.matches(neighbour, def.resolvePartForms(classifyFramePart(neighbour, minY, maxY)))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The in-plane frame ring around an opening, sorted so partial decay picks deterministically. */
    public static List<BlockPos> frameRing(Set<BlockPos> interior, Direction.Axis axis) {
        Set<BlockPos> ring = new HashSet<>();
        for (BlockPos p : interior) {
            for (Direction dir : planeDirections(axis)) {
                BlockPos neighbour = offset(p, dir);
                if (!interior.contains(neighbour)) {
                    ring.add(neighbour);
                }
            }
        }
        List<BlockPos> sorted = new ArrayList<>(ring);
        sorted.sort(null);
        return sorted;
    }

    /**
     * The one definition of an opening's column: the integer mean of its
     * cells. Both ends of a link match on it, so it exists once.
     */
    public static int[] centreColumn(Collection<BlockPos> interior) {
        if (interior == null || interior.isEmpty()) {
            return null;
        }
        int x = 0;
        int z = 0;
        for (BlockPos p : interior) {
            x += p.getX();
            z += p.getZ();
        }
        return new int[]{x / interior.size(), z / interior.size()};
    }

    /** The lowest cell of an opening in {@code BlockPos} order. */
    public static BlockPos keyCell(Collection<BlockPos> interior) {
        BlockPos key = null;
        for (BlockPos p : interior) {
            if (key == null || p.compareTo(key) < 0) {
                key = p;
            }
        }
        return key;
    }
}
