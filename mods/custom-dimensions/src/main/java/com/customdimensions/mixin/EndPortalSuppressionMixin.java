package com.customdimensions.mixin;

import com.customdimensions.portal.PortalHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.EndPortalBlock;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Custom END_EXIT portals keep vanilla's block but not its travel.
 *
 * A player arriving in the End lands INSIDE the arrival portal's own
 * end_portal blocks, and vanilla's collision reads that as leaving the End:
 * it rolls the credits and ejects them to the overworld spawn before they
 * see anything. Player-placed vanilla end portals elsewhere keep vanilla
 * rules, exactly as gateways do.
 */
@Mixin(EndPortalBlock.class)
public class EndPortalSuppressionMixin {

    @Inject(method = "onEntityCollision", at = @At("HEAD"), cancellable = true)
    private void suppressVanillaTravelForCustomEndPortals(BlockState state, World world,
            BlockPos pos, Entity entity, CallbackInfo ci) {
        if (!(world instanceof ServerWorld serverWorld)) {
            return;
        }
        if (PortalHelper.isRegisteredPortalPosition(serverWorld.getRegistryKey(), pos)) {
            suppress(entity, ci);
            return;
        }
        for (PortalHelper.PortalZone zone : PortalHelper.getSourceZones(serverWorld.getRegistryKey())) {
            if (zone.interior.contains(pos)) {
                suppress(entity, ci);
                return;
            }
        }
    }

    // Cancelling collision drops vanilla's cooldown top-up, so an arrival's
    // cooldown would decay inside the portal and bounce them straight back.
    private static void suppress(Entity entity, CallbackInfo ci) {
        if (entity.getPortalCooldown() > 0) {
            entity.resetPortalCooldown();
        }
        ci.cancel();
    }
}
