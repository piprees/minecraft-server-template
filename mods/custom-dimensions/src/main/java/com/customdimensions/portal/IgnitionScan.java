package com.customdimensions.portal;

import com.customdimensions.config.PortalDefinition;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.Set;

/**
 * Valid flood-fills per axis at one ignition candidate position, and the
 * candidate sweep behind a single click. Lives outside PortalIgnitionMixin
 * because mixin classes must stay thin (and nested types in mixins invite
 * synthetic-accessor trouble).
 *
 * <p>Every gate here answers with an {@link IgnitionRefusal} when it declines,
 * and a sweep keeps the refusal that got FURTHEST — one click reaches 349
 * candidate cells on three axes, so the first refusal is noise and the
 * closest miss is the answer.
 */
public record IgnitionScan(Set<BlockPos> xFill, Set<BlockPos> zFill, Set<BlockPos> yFill) {

    /** How far the fallback box reaches from the clicked block on each axis. */
    public static final int BOX_RADIUS = 3;

    /** What a sweep found: the fill, its axis, and where the ignite sound plays. */
    public record Site(BlockPos soundPos, Set<BlockPos> fill, Direction.Axis axis) {
    }

    /**
     * Why a sweep found nothing: the closest miss, the cell it judged, and —
     * when a single block is the whole answer — that block's position.
     */
    public record Refusal(IgnitionRefusal reason, BlockPos at, Direction.Axis axis, int cells,
            BlockPos blockedBy) {

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

    /** One axis at one candidate cell: the fill, or why there is none. */
    private record AxisFill(Set<BlockPos> fill, Refusal refusal) {
    }

    /** One candidate cell across all three axes. */
    private record Scan(IgnitionScan fills, Refusal refusal) {
    }

    /** Null when no allowed axis yields a valid bounded fill. */
    public static IgnitionScan discover(ServerWorld world, BlockPos candidate, FrameMatcher matcher,
            PortalDefinition def) {
        return discover(FrameView.of(world), candidate, matcher, def);
    }

    /** Null when no allowed axis yields a valid bounded fill. */
    public static IgnitionScan discover(FrameView view, BlockPos candidate, FrameMatcher matcher,
            PortalDefinition def) {
        return scan(view, candidate, matcher, def).fills();
    }

    private static Scan scan(FrameView view, BlockPos candidate, FrameMatcher matcher,
            PortalDefinition def) {
        AxisFill x = validFill(view, candidate, matcher, def, Direction.Axis.X);
        AxisFill z = validFill(view, candidate, matcher, def, Direction.Axis.Z);
        AxisFill y = validFill(view, candidate, matcher, def, Direction.Axis.Y);
        if (x.fill() == null && z.fill() == null && y.fill() == null) {
            return new Scan(null,
                    Refusal.furthest(Refusal.furthest(x.refusal(), z.refusal()), y.refusal()));
        }
        return new Scan(new IgnitionScan(x.fill(), z.fill(), y.fill()), null);
    }

    /**
     * Every candidate one click reaches: the clicked block's six neighbours
     * first, preferring the clicked face's axis, then a box around it for the
     * player who lit the frame from the outside. Null when no candidate
     * yields a frame this definition accepts — which is what lets an ender
     * eye fall through to vanilla and socket into a stronghold.
     */
    public static Site sweep(FrameView view, BlockPos clickedPos, FrameMatcher matcher,
            PortalDefinition def) {
        return sweepDetailed(view, clickedPos, matcher, def).site();
    }

    /** {@link #sweep}, keeping the reason when it finds nothing. */
    public static Attempt sweepDetailed(FrameView view, BlockPos clickedPos, FrameMatcher matcher,
            PortalDefinition def) {
        if (matcher.isEmpty()) {
            return new Attempt(null,
                    new Refusal(IgnitionRefusal.NO_FRAME_MATERIAL, clickedPos, null, 0));
        }
        Refusal closest = null;
        for (Direction dir : Direction.values()) {
            BlockPos candidate = clickedPos.offset(dir);
            if (!view.isFillable(candidate)) {
                continue;
            }
            Scan found = scan(view, candidate, matcher, def);
            if (found.fills() == null) {
                closest = Refusal.furthest(closest, found.refusal());
                continue;
            }
            Direction.Axis axis = found.fills().pick(dir.getAxis());
            return new Attempt(new Site(clickedPos, found.fills().get(axis), axis), null);
        }

        for (int dx = -BOX_RADIUS; dx <= BOX_RADIUS; dx++) {
            for (int dy = -BOX_RADIUS; dy <= BOX_RADIUS; dy++) {
                for (int dz = -BOX_RADIUS; dz <= BOX_RADIUS; dz++) {
                    BlockPos candidate = clickedPos.add(dx, dy, dz);
                    if (!view.isFillable(candidate)) {
                        continue;
                    }
                    Scan found = scan(view, candidate, matcher, def);
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
                : new Refusal(IgnitionRefusal.NO_CANDIDATE_CELL, clickedPos, null, 0));
    }

    private static AxisFill validFill(FrameView view, BlockPos candidate, FrameMatcher matcher,
            PortalDefinition def, Direction.Axis axis) {
        PortalHelper.Fill filled = PortalHelper.floodFillOutcome(view, candidate, matcher, axis);
        if (filled.refusal() != null) {
            return new AxisFill(null, new Refusal(filled.refusal(), candidate, axis,
                    filled.reached(), filled.blockedBy()));
        }
        Set<BlockPos> fill = filled.cells();
        if (!PortalHelper.isAreaBoundedByFrame(view, fill, matcher, axis)) {
            return refuse(IgnitionRefusal.FRAME_INCOMPLETE, candidate, axis, fill.size());
        }
        // Shape presets constrain the geometry the flood-fill found —
        // "standard" (absent) accepts anything, unknown names accept
        // nothing (validator warns at boot; ignition just fails). Pattern
        // shapes overlay their template (frame cells checked against the
        // live world through the matcher).
        if (PortalShape.PATTERN.equals(def.getShape())) {
            if (!PortalShape.matchesPattern(def.getShapeTemplate(), def.getShapeLegend(),
                    fill, axis, p -> view.matches(p, matcher))) {
                return refuse(IgnitionRefusal.PATTERN_MISMATCH, candidate, axis, fill.size());
            }
        } else if (!PortalShape.matches(def.getShape(), fill, axis)) {
            return refuse(IgnitionRefusal.SHAPE_MISMATCH, candidate, axis, fill.size());
        }
        // Per-part materials: each ring position must satisfy ITS part's
        // matcher (uniform frames and Y-axis fills pass through unchanged).
        if (def.hasPartMaterials()
                && !PortalHelper.isAreaBoundedByFrameParts(view, fill, def, axis)) {
            return refuse(IgnitionRefusal.FRAME_PART_MISMATCH, candidate, axis, fill.size());
        }
        // Orientation is policy, not geometry, so it is asked LAST: a frame
        // that is right in every other way is told which way it may stand,
        // instead of being blamed for whichever axis leaked first.
        if (!def.allowsAxis(axis)) {
            return refuse(IgnitionRefusal.AXIS_NOT_ALLOWED, candidate, axis, fill.size());
        }
        return new AxisFill(fill, null);
    }

    private static AxisFill refuse(IgnitionRefusal reason, BlockPos at, Direction.Axis axis, int cells) {
        return new AxisFill(null, new Refusal(reason, at, axis, cells));
    }

    /** Clicked-face axis first (when valid), then the Y, X, Z priority. */
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
        if (axis == Direction.Axis.X) {
            return this.xFill;
        }
        if (axis == Direction.Axis.Z) {
            return this.zFill;
        }
        return this.yFill;
    }
}
