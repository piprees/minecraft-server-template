package com.customdimensions.mixin;

import com.customdimensions.immersive.VanillaLinkResolver;
import com.customdimensions.portal.PortalHelper;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.TeleportTarget;
import net.minecraft.world.dimension.PortalManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla never picks a destination for a portal this mod owns.
 *
 * NetherPortalBlock sends every non-Nether world to the Nether, so any tick
 * the mod declined used to teleport the player there and scatter vanilla
 * portals. A null target is a clean no-op — tickPortalTeleportation jumps
 * past the teleport — and resetPortalCooldown has already run, so the
 * cooldown and PortalManager expiry stay outside this decision.
 *
 * A destination vanilla DOES pick names both ends of a portal the mod only
 * presents, so it is handed to VanillaLinkResolver: the preview then knows
 * where the portal goes without searching for it.
 */
@Mixin(Entity.class)
public class PortalDestinationMixin {
    @WrapOperation(
            method = "tickPortalTeleportation",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/dimension/PortalManager;createTeleportTarget"
                            + "(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/entity/Entity;)"
                            + "Lnet/minecraft/world/TeleportTarget;"))
    private TeleportTarget customdimensions$refuseVanillaDestination(
            PortalManager manager, ServerWorld world, Entity entity,
            Operation<TeleportTarget> original) {
        if (PortalHelper.isManagedPortal(world.getRegistryKey(), manager.getPortalPos())) {
            return null;
        }
        TeleportTarget target = original.call(manager, world, entity);
        VanillaLinkResolver.recordVanillaCrossing(world, manager.getPortalPos(), target);
        return target;
    }
}
