package com.customdimensions.core.portal;

import com.customdimensions.core.BlockPos;
import com.customdimensions.core.Direction;
import com.customdimensions.core.config.PortalDefinition;

import java.util.Set;

/**
 * The candidate sweep behind one click, and the valid fills per axis at each
 * candidate. Every gate answers with an {@link IgnitionRefusal}, and a sweep
 * keeps the refusal that got FURTHEST — one click reaches 349 candidate cells
 * on three axes, so the first refusal is noise and the closest miss is the answer.
 */
public record IgnitionScan(Set<BlockPos> xFill, Set<BlockPos> zFill, Set<BlockPos> yFill) {

    /** How far the fallback box reaches from the clicked block on each axis. */
    public static final int BOX_RADIUS = 3;

    /** What a sweep found: the fill, its axis, and where the ignite sound plays. */
    public record Site(BlockPos soundPos, Set<BlockPos> fill, Direction.Axis axis) {
    }

    /** Why a sweep found nothing: the closest miss, the cell it judged, and the block that stopped it. */
    public record Refusal(IgnitionRefusal reason, BlockPos at, Direction.Axis axis, int cells, BlockPos blockedBy) {

        public Refusal(IgnitionRefusal reason, BlockPos at, Direction.Axis axis, int cells) {
            this(reason, at, axis, cells, null);
        }

        /** The one that came closer to a portal; the earlier wins a tie. */
        public static Refusal furthest(Refusal a, Refusal b) {
            if (a == null) {
                return b;
            }
            if (b == null) {
                return a;
            }
            return IgnitionRefusal.furthest(a.reason(), b.reason()) == a.reason() ? a : b;
        }
    }

    /** A sweep's outcome: exactly one of {@code site} and {@code refusal} is set. */
    public record Attempt(Site site, Refusal refusal) {
    }

    private record AxisFill(Set<BlockPos> fill, Refusal refusal) {
    }

    private record Scan(IgnitionScan fills, Refusal refusal) {
    }

    private static Scan scan(FrameView view, BlockPos candidate, FrameForms frame, PortalDefinition def) {
        AxisFill x = validFill(view, candidate, frame, def, Direction.Axis.X);
        AxisFill z = validFill(view, candidate, frame, def, Direction.Axis.Z);
        AxisFill y = validFill(view, candidate, frame, def, Direction.Axis.Y);
        if (x.fill() == null && z.fill() == null && y.fill() == null) {
            return new Scan(null, Refusal.furthest(Refusal.furthest(x.refusal(), z.refusal()), y.refusal()));
        }
        return new Scan(new IgnitionScan(x.fill(), z.fill(), y.fill()), null);
    }

    /**
     * Every candidate one click reaches: the clicked block's six neighbours
     * first, preferring the clicked face's axis, then a box around it for a
     * player who lit the frame from outside. No site when no candidate yields
     * a frame this definition accepts — which is what lets an ender eye fall
     * through to vanilla and socket into a stronghold.
     */
    public static Attempt sweep(FrameView view, BlockPos clicked, PortalDefinition def) {
        FrameForms frame = def.resolveFrameForms();
        if (frame.isEmpty()) {
            return new Attempt(null, new Refusal(IgnitionRefusal.NO_FRAME_MATERIAL, clicked, null, 0));
        }
        Refusal closest = null;
        for (Direction dir : Direction.values()) {
            BlockPos candidate = PortalGeometry.offset(clicked, dir);
            if (!view.isFillable(candidate)) {
                continue;
            }
            Scan found = scan(view, candidate, frame, def);
            if (found.fills() == null) {
                closest = Refusal.furthest(closest, found.refusal());
                continue;
            }
            Direction.Axis axis = found.fills().pick(dir.getAxis());
            return new Attempt(new Site(clicked, found.fills().get(axis), axis), null);
        }
        for (int dx = -BOX_RADIUS; dx <= BOX_RADIUS; dx++) {
            for (int dy = -BOX_RADIUS; dy <= BOX_RADIUS; dy++) {
                for (int dz = -BOX_RADIUS; dz <= BOX_RADIUS; dz++) {
                    BlockPos candidate = clicked.add(dx, dy, dz);
                    if (!view.isFillable(candidate)) {
                        continue;
                    }
                    Scan found = scan(view, candidate, frame, def);
                    if (found.fills() == null) {
                        closest = Refusal.furthest(closest, found.refusal());
                        continue;
                    }
                    Direction.Axis axis = found.fills().pick(null);
                    return new Attempt(new Site(candidate, found.fills().get(axis), axis), null);
                }
            }
        }
        return new Attempt(null, closest != null ? closest
                : new Refusal(IgnitionRefusal.NO_CANDIDATE_CELL, clicked, null, 0));
    }

    private static AxisFill validFill(FrameView view, BlockPos candidate, FrameForms frame, PortalDefinition def,
                                      Direction.Axis axis) {
        PortalGeometry.Fill filled = PortalGeometry.floodFill(view, candidate, frame, axis);
        if (filled.refusal() != null) {
            return new AxisFill(null, new Refusal(filled.refusal(), candidate, axis, filled.reached(), filled.blockedBy()));
        }
        Set<BlockPos> fill = filled.cells();
        if (!PortalGeometry.isBoundedByFrame(view, fill, frame, axis)) {
            return refuse(IgnitionRefusal.FRAME_INCOMPLETE, candidate, axis, fill.size());
        }
        if (PortalShape.PATTERN.equals(def.getShape())) {
            if (!PortalShape.matchesPattern(def.getShapeTemplate(), def.getShapeLegend(), fill, axis,
                    p -> view.matches(p, frame))) {
                return refuse(IgnitionRefusal.PATTERN_MISMATCH, candidate, axis, fill.size());
            }
        } else if (!PortalShape.matches(def.getShape(), fill, axis)) {
            return refuse(IgnitionRefusal.SHAPE_MISMATCH, candidate, axis, fill.size());
        }
        if (def.hasPartMaterials() && !PortalGeometry.isBoundedByFrameParts(view, fill, def, axis)) {
            return refuse(IgnitionRefusal.FRAME_PART_MISMATCH, candidate, axis, fill.size());
        }
        // Orientation is policy, not geometry, so it is asked last: a frame
        // right in every other way is told which way it may stand.
        if (!def.allowsAxis(axis)) {
            return refuse(IgnitionRefusal.AXIS_NOT_ALLOWED, candidate, axis, fill.size());
        }
        return new AxisFill(fill, null);
    }

    private static AxisFill refuse(IgnitionRefusal reason, BlockPos at, Direction.Axis axis, int cells) {
        return new AxisFill(null, new Refusal(reason, at, axis, cells));
    }

    /** The clicked face's axis first when valid, then Y, X, Z. */
    public Direction.Axis pick(Direction.Axis clickedAxis) {
        if (clickedAxis != null && this.get(clickedAxis) != null) {
            return clickedAxis;
        }
        if (this.yFill != null) {
            return Direction.Axis.Y;
        }
        if (this.xFill != null) {
            return Direction.Axis.X;
        }
        return Direction.Axis.Z;
    }

    public Set<BlockPos> get(Direction.Axis axis) {
        return switch (axis) {
            case X -> this.xFill;
            case Z -> this.zFill;
            case Y -> this.yFill;
        };
    }
}
