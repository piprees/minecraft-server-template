package com.customdimensions.mixin;

import com.customdimensions.portal.PortalHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.WorldAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps REGISTERED custom portal blocks alive through neighbour updates.
 *
 * Vanilla NetherPortalBlock.getStateForNeighborUpdate re-validates the
 * portal's frame (obsidian only) whenever an adjacent block changes and
 * pops the portal to air otherwise. Our arrival/anchor/exit portals are
 * framed with configured blocks, so ANY neighbour update in the frame
 * plane silently deletes them — netherportalspread's nether-corruption
 * conversions did exactly that within seconds of portal creation (found
 * 2026-07-23 via a portal-pop stack trace during Tier-1 verification:
 * Util.spreadNetherToBlock -> NeighborUpdater -> portal popped).
 *
 * Only positions registered in PortalHelper's return-target map are
 * protected — player-built vanilla nether portals behave exactly as
 * vanilla. The lookup is side-effect free (isRegisteredPortalPosition).
 */
@Mixin(NetherPortalBlock.class)
public class NetherPortalProtectionMixin {

    @Inject(method = "getStateForNeighborUpdate", at = @At("HEAD"), cancellable = true)
    private void customdimensions$keepRegisteredPortals(BlockState state, Direction direction,
            BlockState neighborState, WorldAccess world, BlockPos pos, BlockPos neighborPos,
            CallbackInfoReturnable<BlockState> cir) {
        if (world instanceof ServerWorld serverWorld
                && PortalHelper.isRegisteredPortalPosition(serverWorld.getRegistryKey(), pos)) {
            cir.setReturnValue(state);
        }
    }
}
