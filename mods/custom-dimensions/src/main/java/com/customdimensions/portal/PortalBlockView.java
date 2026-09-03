package com.customdimensions.portal;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * The two questions the vanilla-portal fill asks of a position. Every block
 * read {@link PortalHelper#collectPortalArea} makes is one of them, so the
 * walk and its bound can be exercised against a fixture that records exactly
 * which positions were asked for.
 */
public interface PortalBlockView {

    boolean isEndPortal(BlockPos pos);

    /** The axis of the nether portal block here, or null if this is not one. */
    Direction.Axis netherPortalAxis(BlockPos pos);

    static PortalBlockView of(ServerWorld world) {
        return new PortalBlockView() {
            @Override
            public boolean isEndPortal(BlockPos pos) {
                return world.getBlockState(pos).isOf(Blocks.END_PORTAL);
            }

            @Override
            public Direction.Axis netherPortalAxis(BlockPos pos) {
                BlockState state = world.getBlockState(pos);
                if (!state.isOf(Blocks.NETHER_PORTAL) || !state.contains(NetherPortalBlock.AXIS)) {
                    return null;
                }
                return state.get(NetherPortalBlock.AXIS);
            }
        };
    }
}
