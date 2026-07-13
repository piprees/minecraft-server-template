package com.customdimensions.mixin;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.config.PortalDefinition;
import com.customdimensions.dimension.DimensionManager;
import com.customdimensions.portal.PortalHelper;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

@Mixin(ServerWorld.class)
public class ServerWorldMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void onTick(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        ServerWorld world = (ServerWorld) (Object) this;
        RegistryKey<World> worldKey = world.getRegistryKey();

        if (MultiverseConfig.getInstance().isDirty()) {
            MultiverseConfig.getInstance().save();
        }

        List<PortalHelper.PortalZone> sourceZones = PortalHelper.getSourceZones(worldKey);
        if (!sourceZones.isEmpty()) {
            List<PortalHelper.PortalZone> zones = new ArrayList<>();
            for (PortalHelper.PortalZone zone : sourceZones) {
                if (PortalHelper.isZoneValid(world, zone)) {
                    zones.add(zone);
                    continue;
                }
                PortalHelper.clearInteriorPortals(world, zone);
                PortalHelper.removeZone(zone);
            }

            for (PortalHelper.PortalZone zone : zones) {
                PortalHelper.spawnParticles(world, zone);
            }

            List<ServerPlayerEntity> players = new ArrayList<>(world.getPlayers());
            playerLoop:
            for (ServerPlayerEntity player : players) {
                if (player.hasVehicle() || player.getPortalCooldown() > 0) {
                    continue;
                }
                BlockPos pos = player.getBlockPos();
                boolean insideAny = false;
                for (PortalHelper.PortalZone zone : zones) {
                    if (PortalHelper.isInsideZone(pos, zone) || PortalHelper.isInsideZone(pos.down(), zone) || PortalHelper.isInsideZone(pos.up(), zone)) {
                        insideAny = true;
                        break;
                    }
                }
                String entryKey = worldKey.toString() + "|" + player.getUuid();
                boolean wasInside = PortalHelper.wasPlayerInZone(entryKey);
                PortalHelper.setPlayerInZone(entryKey, insideAny);
                if (!insideAny || wasInside) {
                    continue;
                }

                for (PortalHelper.PortalZone zone : zones) {
                    if (!PortalHelper.isInsideZone(pos, zone) && !PortalHelper.isInsideZone(pos.down(), zone) && !PortalHelper.isInsideZone(pos.up(), zone)) {
                        continue;
                    }
                    RegistryKey<World> targetKey = zone.targetWorld;
                    if (targetKey == worldKey) {
                        continue;
                    }
                    ServerWorld targetWorld = world.getServer().getWorld(targetKey);
                    if (targetWorld == null) {
                        // Target world not loaded (fresh boot or idle-unloaded).
                        // Creating it here would mutate the worlds map inside
                        // tickWorlds — queue it for END_SERVER_TICK and reset the
                        // player's zone-entry edge so the teleport retriggers
                        // once the world exists.
                        DimensionManager.getInstance().requestWorldLoad(targetKey.getValue().getPath());
                        PortalHelper.setPlayerInZone(entryKey, false);
                        continue;
                    }

                    try {
                        PortalDefinition def = zone.definition;
                        double scale = def.getScale();
                        int portalCenterX = 0;
                        int portalCenterZ = 0;
                        for (BlockPos p : zone.interior) {
                            portalCenterX += p.getX();
                            portalCenterZ += p.getZ();
                        }
                        int count = zone.interior.size();
                        if (count > 0) {
                            portalCenterX /= count;
                            portalCenterZ /= count;
                        }
                        int targetCenterX = (int) Math.round((double) portalCenterX * scale);
                        int targetCenterZ = (int) Math.round((double) portalCenterZ * scale);
                        int dx = targetCenterX - portalCenterX;
                        int dz = targetCenterZ - portalCenterZ;

                        // Arrival height comes from the target column's own
                        // surface — the SCALED centre, since source-portal
                        // coordinates are the wrong column for scale != 1.
                        int surfaceY = PortalHelper.findSurfaceY(targetWorld, targetCenterX, targetCenterZ);

                        // Rebuild the portal with its shape intact: keep each
                        // interior block's height relative to the portal's
                        // bottom layer rather than flattening to one Y.
                        int minInteriorY = Integer.MAX_VALUE;
                        for (BlockPos p : zone.interior) {
                            minInteriorY = Math.min(minInteriorY, p.getY());
                        }
                        HashSet<BlockPos> adjustedInterior = new HashSet<>();
                        for (BlockPos p : zone.interior) {
                            adjustedInterior.add(new BlockPos(p.getX() + dx, surfaceY + (p.getY() - minInteriorY), p.getZ() + dz));
                        }

                        boolean isHorizontal = zone.axis == Direction.Axis.Y;

                        BlockPos existing = PortalHelper.findExistingPortal(targetWorld, targetCenterX, surfaceY, targetCenterZ, 5, 16, zone.axis);
                        int portalCooldown = def.getCooldown();

                        if (existing != null) {
                            playPortalSound(world, pos, def.getEnterSound());
                            player.setPortalCooldown(portalCooldown);
                            PortalHelper.setPlayerOrigin(player.getUuid(), worldKey, pos);
                            double landY = isHorizontal ? existing.getY() + 1 : existing.getY();
                            player.teleport(targetWorld, existing.getX() + 0.5, landY, existing.getZ() + 0.5, Set.of(), player.getYaw(), player.getPitch());
                            playPortalSound(targetWorld, existing, def.getExitSound());
                            continue playerLoop;
                        }

                        PortalHelper.createTargetPortal(targetWorld, adjustedInterior, zone.axis, def, worldKey, pos.getY());
                        MultiverseServer.LOGGER.info("Created portal in {} at ({}, {}, {})",
                                targetKey.getValue(), targetCenterX, surfaceY, targetCenterZ);
                        playPortalSound(world, pos, def.getEnterSound());
                        player.setPortalCooldown(portalCooldown);
                        PortalHelper.setPlayerOrigin(player.getUuid(), worldKey, pos);
                        double landY = isHorizontal ? surfaceY + 1 : surfaceY;
                        player.teleport(targetWorld, targetCenterX + 0.5, landY, targetCenterZ + 0.5, Set.of(), player.getYaw(), player.getPitch());
                        playPortalSound(targetWorld, new BlockPos(targetCenterX, (int) landY, targetCenterZ), def.getExitSound());
                    } catch (Exception e) {
                        MultiverseServer.LOGGER.error("Failed portal teleport for player {} in {}", player.getName().getString(), worldKey.getValue(), e);
                    }
                    continue playerLoop;
                }
            }
        }

        PortalHelper.spawnTargetPortalParticles(world);

        DimensionManager.getInstance().updatePlayerPresence(worldKey, !world.getPlayers().isEmpty());

        // Idle unload is driven by ServerTickEvents.END_SERVER_TICK (see
        // MultiverseServer) — never from here: this injection runs inside
        // MinecraftServer.tickWorlds' iteration of the worlds map, and
        // removing worlds mid-iteration is a ConcurrentModificationException.
    }

    private static void playPortalSound(ServerWorld world, BlockPos pos, String soundName) {
        Identifier soundId = Identifier.tryParse(soundName);
        if (soundId != null) {
            SoundEvent sound = Registries.SOUND_EVENT.get(soundId);
            if (sound != null) {
                world.playSound(null, pos, sound, SoundCategory.BLOCKS, 1.0f, 1.0f);
            }
        }
    }
}
