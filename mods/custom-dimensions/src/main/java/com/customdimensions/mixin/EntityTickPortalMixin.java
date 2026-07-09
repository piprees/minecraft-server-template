package com.customdimensions.mixin;

import com.customdimensions.portal.PortalHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

@Mixin(Entity.class)
public abstract class EntityTickPortalMixin {
    @Shadow
    public World world;

    @Inject(method = "tickPortalTeleportation", at = @At("HEAD"), cancellable = true)
    private void onTickPortal(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (!(self instanceof ServerPlayerEntity player)) {
            return;
        }
        if (!(this.world instanceof ServerWorld serverLevel)) {
            return;
        }
        if (player.hasVehicle()) {
            return;
        }
        if (player.getPortalCooldown() > 0) {
            return;
        }

        BlockPos pos = player.getBlockPos();
        BlockState state = serverLevel.getBlockState(pos);
        if (!state.isOf(Blocks.NETHER_PORTAL)) {
            state = serverLevel.getBlockState(pos.up());
            if (!state.isOf(Blocks.NETHER_PORTAL)) {
                return;
            }
            pos = pos.up();
        }

        Set<BlockPos> portalBlocks = PortalHelper.collectPortalArea(serverLevel, pos);
        if (portalBlocks.isEmpty()) {
            return;
        }

        RegistryKey<World> targetWorldKey = null;
        double tx = pos.getX() + 0.5;
        double ty = pos.getY();
        double tz = pos.getZ() + 0.5;

        PortalHelper.PlayerOrigin origin = PortalHelper.getPlayerOrigin(player.getUuid());
        if (origin != null) {
            targetWorldKey = origin.world;
            tx = origin.pos.getX() + 0.5;
            ty = origin.pos.getY();
            tz = origin.pos.getZ() + 0.5;
        }

        if (targetWorldKey == null) {
            PortalHelper.PortalReturnTarget target = PortalHelper.getPortalTarget(portalBlocks.iterator().next());
            if (target != null) {
                targetWorldKey = target.sourceWorld;
                ty = target.sourceY;
            }
        }

        if (targetWorldKey == null || targetWorldKey == serverLevel.getRegistryKey()) {
            return;
        }
        ServerWorld targetWorld = serverLevel.getServer().getWorld(targetWorldKey);
        if (targetWorld == null) {
            return;
        }

        ci.cancel();
        player.setPortalCooldown(300);
        player.teleport(targetWorld, tx, ty, tz, Set.of(), player.getYaw(), player.getPitch(), true);
    }
}
