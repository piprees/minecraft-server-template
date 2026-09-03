package com.customdimensions.portal;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * The two questions the ignition scan asks of a position — may a portal fill
 * this cell, and does this cell satisfy a frame matcher. Every block read on
 * the ignition path is one of them, so the geometry can be exercised against
 * a fixture instead of a live world.
 */
public interface FrameView {

    boolean isFillable(BlockPos pos);

    boolean matches(BlockPos pos, FrameMatcher matcher);

    static FrameView of(ServerWorld world) {
        return new FrameView() {
            @Override
            public boolean isFillable(BlockPos pos) {
                return PortalHelper.isPortalFillable(world.getBlockState(pos));
            }

            @Override
            public boolean matches(BlockPos pos, FrameMatcher matcher) {
                return matcher.matches(world.getBlockState(pos));
            }
        };
    }
}
