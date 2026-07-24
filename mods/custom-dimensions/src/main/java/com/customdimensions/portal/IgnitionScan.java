package com.customdimensions.portal;

import com.customdimensions.config.PortalDefinition;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.Set;

/**
 * Valid flood-fills per axis at one ignition candidate position,
 * respecting the definition's orientation constraint (disallowed axes are
 * never even filled). Lives outside PortalIgnitionMixin because mixin
 * classes must stay thin (and nested types in mixins invite synthetic-
 * accessor trouble).
 */
public record IgnitionScan(Set<BlockPos> xFill, Set<BlockPos> zFill, Set<BlockPos> yFill) {

    /** Null when no allowed axis yields a valid bounded fill. */
    public static IgnitionScan discover(ServerWorld world, BlockPos candidate, FrameMatcher matcher,
            PortalDefinition def) {
        Set<BlockPos> x = validFill(world, candidate, matcher, def, Direction.Axis.X);
        Set<BlockPos> z = validFill(world, candidate, matcher, def, Direction.Axis.Z);
        Set<BlockPos> y = validFill(world, candidate, matcher, def, Direction.Axis.Y);
        return x == null && z == null && y == null ? null : new IgnitionScan(x, z, y);
    }

    private static Set<BlockPos> validFill(ServerWorld world, BlockPos candidate, FrameMatcher matcher,
            PortalDefinition def, Direction.Axis axis) {
        if (!def.allowsAxis(axis)) {
            return null;
        }
        Set<BlockPos> fill = PortalHelper.floodFill(world, candidate, matcher, axis);
        if (fill.isEmpty() || !PortalHelper.isAreaBoundedByFrame(world, fill, matcher, axis)) {
            return null;
        }
        // Shape presets constrain the geometry the flood-fill found —
        // "standard" (absent) accepts anything, unknown names accept
        // nothing (validator warns at boot; ignition just fails).
        if (!PortalShape.matches(def.getShape(), fill, axis)) {
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
