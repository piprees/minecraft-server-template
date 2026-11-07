package com.customdimensions.core.portal;

import com.customdimensions.core.BlockPos;

/**
 * The two questions ignition asks of a position: may a portal fill this cell,
 * and does this cell satisfy a frame's accept forms. Every block read on the
 * ignition path is one of them, so the geometry runs against a fixture.
 */
public interface FrameView {

    boolean isFillable(BlockPos pos);

    boolean matches(BlockPos pos, FrameForms frame);
}
