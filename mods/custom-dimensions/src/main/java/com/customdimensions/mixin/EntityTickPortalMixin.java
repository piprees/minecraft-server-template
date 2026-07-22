package com.customdimensions.mixin;

import com.customdimensions.portal.PortalHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.TeleportTarget;
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
        if (!PortalHelper.isPortalBlock(state)) {
            state = serverLevel.getBlockState(pos.up());
            if (PortalHelper.isPortalBlock(state)) {
                pos = pos.up();
            } else {
                state = serverLevel.getBlockState(pos.down());
                if (!PortalHelper.isPortalBlock(state)) {
                    return;
                }
                pos = pos.down();
            }
        }

        Set<BlockPos> portalBlocks = PortalHelper.collectPortalArea(serverLevel, pos);
        if (portalBlocks.isEmpty()) {
            return;
        }

        PortalHelper.PortalReturnTarget target = PortalHelper.getPortalTarget(serverLevel.getRegistryKey(), portalBlocks.iterator().next());
        String exitMode = target != null ? target.exitMode : null;

        // Configured exit modes ("bed"/"worldSpawn" — anchor arrivals and
        // mod-built exit portals) win over UUID origin tracking, and clear
        // the stored origin so a later origin-mode trip can't resurrect it.
        if ("bed".equals(exitMode)) {
            ci.cancel();
            player.setPortalCooldown(target.cooldown);
            PortalHelper.clearPlayerOrigin(player.getUuid());
            PortalHelper.startSingleUseCountdownAt(serverLevel, pos);
            // alive=true locates the respawn point without consuming anchor
            // charges; obstruction falls back to world spawn internally.
            TeleportTarget respawn = player.getRespawnTarget(true, TeleportTarget.NO_OP);
            player.teleport(respawn.world(), respawn.pos().x, respawn.pos().y, respawn.pos().z,
                    Set.of(), player.getYaw(), player.getPitch());
            return;
        }
        if ("worldSpawn".equals(exitMode)) {
            ci.cancel();
            player.setPortalCooldown(target.cooldown);
            PortalHelper.clearPlayerOrigin(player.getUuid());
            PortalHelper.startSingleUseCountdownAt(serverLevel, pos);
            ServerWorld overworld = serverLevel.getServer().getOverworld();
            BlockPos spawn = overworld.getSpawnPos();
            player.teleport(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5,
                    Set.of(), player.getYaw(), player.getPitch());
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

        int cooldown = 40;
        if (targetWorldKey == null && target != null) {
            if ("origin".equals(exitMode)) {
                // Explicit origin mode with the origin lost (restart) —
                // never strand: fall back to the overworld spawn.
                ServerWorld overworld = serverLevel.getServer().getOverworld();
                BlockPos spawn = overworld.getSpawnPos();
                ci.cancel();
                player.setPortalCooldown(target.cooldown);
                player.teleport(overworld, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5,
                        Set.of(), player.getYaw(), player.getPitch());
                return;
            }
            targetWorldKey = target.sourceWorld;
            ty = target.sourceY;
            cooldown = target.cooldown;
        }

        if (targetWorldKey == null || targetWorldKey == serverLevel.getRegistryKey()) {
            return;
        }
        ServerWorld targetWorld = serverLevel.getServer().getWorld(targetWorldKey);
        if (targetWorld == null) {
            return;
        }

        ci.cancel();
        player.setPortalCooldown(cooldown);
        PortalHelper.startSingleUseCountdownAt(serverLevel, pos);
        player.teleport(targetWorld, tx, ty, tz, Set.of(), player.getYaw(), player.getPitch());
    }
}
