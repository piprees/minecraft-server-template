package com.customdimensions.portal;

import com.customdimensions.config.PortalDefinition;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.Set;

/**
 * Valid flood-fills per axis at one ignition candidate position,
 * respecting the definition's orientation constraint (disallowed axes are
 * never even filled), and the candidate sweep behind a single click. Lives
 * outside PortalIgnitionMixin because mixin classes must stay thin (and
 * nested types in mixins invite synthetic-accessor trouble).
 */
public record IgnitionScan(Set<BlockPos> xFill, Set<BlockPos> zFill, Set<BlockPos> yFill) {

    /** How far the fallback box reaches from the clicked block on each axis. */
    public static final int BOX_RADIUS = 3;

    /** What a sweep found: the fill, its axis, and where the ignite sound plays. */
    public record Site(BlockPos soundPos, Set<BlockPos> fill, Direction.Axis axis) {
    }

    /** Null when no allowed axis yields a valid bounded fill. */
    public static IgnitionScan discover(ServerWorld world, BlockPos candidate, FrameMatcher matcher,
            PortalDefinition def) {
        return discover(FrameView.of(world), candidate, matcher, def);
    }

    /** Null when no allowed axis yields a valid bounded fill. */
    public static IgnitionScan discover(FrameView view, BlockPos candidate, FrameMatcher matcher,
            PortalDefinition def) {
        Set<BlockPos> x = validFill(view, candidate, matcher, def, Direction.Axis.X);
        Set<BlockPos> z = validFill(view, candidate, matcher, def, Direction.Axis.Z);
        Set<BlockPos> y = validFill(view, candidate, matcher, def, Direction.Axis.Y);
        return x == null && z == null && y == null ? null : new IgnitionScan(x, z, y);
    }

    /**
     * Every candidate one click reaches: the clicked block's six neighbours
     * first, preferring the clicked face's axis, then a box around it for the
     * player who lit the frame from the outside. Null when no candidate
     * yields a frame this definition accepts — which is what lets an ender
     * eye fall through to vanilla and socket into a stronghold.
     */
    public static Site sweep(FrameView view, BlockPos clickedPos, FrameMatcher matcher, PortalDefinition def) {
        if (matcher.isEmpty()) {
            return null;
        }
        for (Direction dir : Direction.values()) {
            BlockPos candidate = clickedPos.offset(dir);
            if (!view.isFillable(candidate)) {
                continue;
            }
            IgnitionScan fills = discover(view, candidate, matcher, def);
            if (fills == null) {
                continue;
            }
            Direction.Axis axis = fills.pick(dir.getAxis());
            return new Site(clickedPos, fills.get(axis), axis);
        }

        for (int dx = -BOX_RADIUS; dx <= BOX_RADIUS; dx++) {
            for (int dy = -BOX_RADIUS; dy <= BOX_RADIUS; dy++) {
                for (int dz = -BOX_RADIUS; dz <= BOX_RADIUS; dz++) {
                    BlockPos candidate = clickedPos.add(dx, dy, dz);
                    if (!view.isFillable(candidate)) {
                        continue;
                    }
                    IgnitionScan fills = discover(view, candidate, matcher, def);
                    if (fills == null) {
                        continue;
                    }
                    Direction.Axis axis = fills.pick(null);
                    return new Site(candidate, fills.get(axis), axis);
                }
            }
        }
        return null;
    }

    private static Set<BlockPos> validFill(FrameView view, BlockPos candidate, FrameMatcher matcher,
            PortalDefinition def, Direction.Axis axis) {
        if (!def.allowsAxis(axis)) {
            return null;
        }
        Set<BlockPos> fill = PortalHelper.floodFill(view, candidate, matcher, axis);
        if (fill.isEmpty() || !PortalHelper.isAreaBoundedByFrame(view, fill, matcher, axis)) {
            return null;
        }
        // Shape presets constrain the geometry the flood-fill found —
        // "standard" (absent) accepts anything, unknown names accept
        // nothing (validator warns at boot; ignition just fails). Pattern
        // shapes overlay their template (frame cells checked against the
        // live world through the matcher).
        if (PortalShape.PATTERN.equals(def.getShape())) {
            if (!PortalShape.matchesPattern(def.getShapeTemplate(), def.getShapeLegend(),
                    fill, axis, p -> view.matches(p, matcher))) {
                return null;
            }
        } else if (!PortalShape.matches(def.getShape(), fill, axis)) {
            return null;
        }
        // Per-part materials: each ring position must satisfy ITS part's
        // matcher (uniform frames and Y-axis fills pass through unchanged).
        if (def.hasPartMaterials()
                && !PortalHelper.isAreaBoundedByFrameParts(view, fill, def, axis)) {
            return null;
        }
        return fill;
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
