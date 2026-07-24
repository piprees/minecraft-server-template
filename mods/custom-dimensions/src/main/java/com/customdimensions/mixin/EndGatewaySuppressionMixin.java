package com.customdimensions.mixin;

import com.customdimensions.portal.PortalHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.EndGatewayBlock;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Custom END_GATEWAY portals ("end_gateway" shape) keep vanilla's iconic
 * block and beam but not its teleport: vanilla gateway travel starts in
 * EndGatewayBlock.onEntityCollision (the 1.21 Portal-interface path,
 * which would fling the player at End islands). Cancelling it for
 * positions the mod owns keeps all custom travel in the existing zone
 * tick (ServerWorldMixin) and return path (EntityTickPortalMixin, which
 * recognises END_GATEWAY via isPortalBlock). Player-placed vanilla
 * gateways elsewhere keep vanilla rules.
 */
@Mixin(EndGatewayBlock.class)
public class EndGatewaySuppressionMixin {

    @Inject(method = "onEntityCollision", at = @At("HEAD"), cancellable = true)
    private void suppressVanillaTravelForCustomGateways(BlockState state, World world,
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

    // Cancelling collision also removes vanilla's "standing in a portal
    // keeps the cooldown topped up" behaviour, so an arriving player's
    // cooldown decayed INSIDE the arrival gateway and the return trip
    // fired instantly — a teleport bounce (found live 2026-07-24). Reset
    // it here exactly like vanilla portal contact does: you must step out
    // and back in before travelling again.
    private static void suppress(Entity entity, CallbackInfo ci) {
        if (entity.getPortalCooldown() > 0) {
            entity.resetPortalCooldown();
        }
        ci.cancel();
    }
}
