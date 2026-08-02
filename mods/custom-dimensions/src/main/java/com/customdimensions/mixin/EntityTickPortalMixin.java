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
            // Immersive portals (Phase 3d): an item, projectile, XP orb,
            // falling block or living entity standing in an ARRIVAL portal
            // block goes back the way it came. Cancel ONLY when it actually
            // teleported — this callback fires for every non-player entity in
            // the game every tick, and cancelling it otherwise would break
            // vanilla's own portal handling for all of them. All gating
            // (entity type, portal block, immersive config, registered target,
            // entry edge) lives in EntityPassthrough, which orders its checks
            // so a mob costs a class check and a cached block state before
            // anything more expensive is touched.
            //
            // Returning false is load-bearing for NON-immersive dimensions:
            // vanilla then runs its own tickPortalTeleportation, which is
            // what has always happened for entities in those worlds.
            if (com.customdimensions.immersive.EntityPassthrough
                    .tryReturnFromArrivalPortal(self, this.world)) {
                ci.cancel();
            }
            return;
        }
        if (!(this.world instanceof ServerWorld serverLevel)) {
            return;
        }

        BlockPos pos = player.getBlockPos();
        boolean inPortal = true;
        BlockState state = serverLevel.getBlockState(pos);
        if (!PortalHelper.isPortalBlock(state)) {
            state = serverLevel.getBlockState(pos.up());
            if (PortalHelper.isPortalBlock(state)) {
                pos = pos.up();
            } else {
                state = serverLevel.getBlockState(pos.down());
                if (PortalHelper.isPortalBlock(state)) {
                    pos = pos.down();
                } else {
                    inPortal = false;
                }
            }
        }

        // The return gate. Sampled on EVERY tick, in a portal or not, so the
        // player's presence record follows them from world to world — that is
        // what makes arriving through a portal read as a first sighting in the
        // new dimension, with no hook needed in ServerWorldMixin's outbound
        // teleport. See PortalHelper.enteredArrivalPortal for why this replaced
        // the old `getPortalCooldown() > 0` early return: vanilla re-pins that
        // value every tick an entity stands in a portal block, so it never
        // clears for a player who arrived INSIDE the arrival portal, and the
        // return could not fire at all. The cooldown is still consulted — as
        // the seed that tells a teleport arrival apart from a walk-in — just
        // not as the gate.
        RegistryKey<World> worldKey = serverLevel.getRegistryKey();
        int now = serverLevel.getServer().getTicks();
        boolean entered = PortalHelper.enteredArrivalPortal(
                worldKey, player.getUuid(), inPortal, player.getPortalCooldown() > 0, now);
        if (!inPortal || !entered) {
            return;
        }
        // From here the edge has FIRED, and it is a one-shot. Every path that
        // does not teleport hands it back with rearmArrivalPortalEntry, or the
        // player stands in the portal waiting for a retry that was already
        // spent — the level-check behaviour the old cooldown gate got for
        // free. Same rule as ci.cancel(): consume it only when you teleported.
        if (player.hasVehicle()) {
            PortalHelper.rearmArrivalPortalEntry(worldKey, player.getUuid(), now);
            return;
        }

        Set<BlockPos> portalBlocks = PortalHelper.collectPortalArea(serverLevel, pos);
        if (portalBlocks.isEmpty()) {
            PortalHelper.rearmArrivalPortalEntry(worldKey, player.getUuid(), now);
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
            PortalHelper.markArrivedInPortal(respawn.world().getRegistryKey(), player.getUuid(), now);
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
            PortalHelper.markArrivedInPortal(overworld.getRegistryKey(), player.getUuid(), now);
            return;
        }
        // Dimension-link targets ("dim!ns:slug!arrival" — exits leading to
        // ANY dimension, the chains/hubs feature). An unloaded runtime
        // target queues its world load and retries next tick: no cooldown
        // is set and the tick isn't cancelled, so the player simply stands
        // in the portal until the world is ready (a few ticks).
        if (exitMode != null && exitMode.startsWith("dim!")) {
            com.customdimensions.dimension.ExitTarget link =
                    com.customdimensions.dimension.ExitTarget.parse(exitMode);
            if (link != null) {
                com.customdimensions.dimension.ExitTarget.Destination dest =
                        link.resolve(player, serverLevel);
                if (dest == null) {
                    ci.cancel();  // world still loading — swallow this tick, retry
                    PortalHelper.rearmArrivalPortalEntry(worldKey, player.getUuid(), now);
                    return;
                }
                ci.cancel();
                player.setPortalCooldown(target.cooldown);
                PortalHelper.clearPlayerOrigin(player.getUuid());
                PortalHelper.startSingleUseCountdownAt(serverLevel, pos);
                player.teleport(dest.world(), dest.pos().x, dest.pos().y, dest.pos().z,
                        Set.of(), player.getYaw(), player.getPitch());
                // Chain hops land ON the next dimension's arrival portal by
                // design, so this one is not belt-and-braces — without it the
                // hop would immediately read as an entry and bounce back.
                PortalHelper.markArrivedInPortal(dest.world().getRegistryKey(), player.getUuid(), now);
                return;
            }
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
                PortalHelper.markArrivedInPortal(overworld.getRegistryKey(), player.getUuid(), now);
                return;
            }
            targetWorldKey = target.sourceWorld;
            ty = target.sourceY;
            cooldown = target.cooldown;
        }

        if (targetWorldKey == null || targetWorldKey == serverLevel.getRegistryKey()) {
            PortalHelper.rearmArrivalPortalEntry(worldKey, player.getUuid(), now);
            return;
        }
        ServerWorld targetWorld = serverLevel.getServer().getWorld(targetWorldKey);
        if (targetWorld == null) {
            // Idle-unloaded target: the retry is the whole point of rearming.
            PortalHelper.rearmArrivalPortalEntry(worldKey, player.getUuid(), now);
            return;
        }

        ci.cancel();
        player.setPortalCooldown(cooldown);
        PortalHelper.startSingleUseCountdownAt(serverLevel, pos);
        player.teleport(targetWorld, tx, ty, tz, Set.of(), player.getYaw(), player.getPitch());
        PortalHelper.markArrivedInPortal(targetWorldKey, player.getUuid(), now);
    }
}
