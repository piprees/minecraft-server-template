package com.customdimensions.mixin;

import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.config.PortalDefinition;
import com.customdimensions.portal.PortalHelper;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

@Mixin(ServerWorld.class)
public class ServerWorldMixin {
    @Inject(method = "save", at = @At("HEAD"))
    private void onSave(CallbackInfo ci) {
        MultiverseConfig.getInstance().markDirty();
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void onTick(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        ServerWorld world = (ServerWorld) (Object) this;
        RegistryKey<World> worldKey = world.getRegistryKey();

        if (MultiverseConfig.getInstance().isDirty()) {
            MultiverseConfig.getInstance().save();
        }

        List<PortalHelper.PortalZone> zones = PortalHelper.getSourceZones(worldKey);
        if (!zones.isEmpty()) {
            Iterator<PortalHelper.PortalZone> zoneIter = zones.iterator();
            while (zoneIter.hasNext()) {
                PortalHelper.PortalZone zone = zoneIter.next();
                if (!PortalHelper.isZoneValid(world, zone)) {
                    PortalHelper.clearInteriorPortals(world, zone);
                    PortalHelper.removeZone(zone);
                    zoneIter = zones.iterator();
                    if (!zoneIter.hasNext()) {
                        break;
                    }
                }
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

                        int yOffset = PortalHelper.findSafeYOffset(targetWorld, zone.interior);
                        int targetY = targetWorld.getBottomY() + yOffset;

                        HashSet<BlockPos> adjustedInterior = new HashSet<>();
                        for (BlockPos p : zone.interior) {
                            adjustedInterior.add(new BlockPos(p.getX() + dx, targetY, p.getZ() + dz));
                        }

                        boolean isHorizontal = zone.axis == Direction.Axis.Y;

                        BlockPos existing = PortalHelper.findExistingPortal(targetWorld, targetCenterX, targetY + 1, targetCenterZ, 5);
                        if (existing != null) {
                            player.setPortalCooldown(40);
                            PortalHelper.setPlayerOrigin(player.getUuid(), worldKey, pos);
                            double landY = isHorizontal ? existing.getY() + 1 : existing.getY();
                            player.teleport(targetWorld, existing.getX() + 0.5, landY, existing.getZ() + 0.5, Set.of(), player.getYaw(), player.getPitch(), true);
                            continue playerLoop;
                        }

                        PortalHelper.createTargetPortal(targetWorld, adjustedInterior, zone.axis, def, worldKey, pos.getY());
                        player.setPortalCooldown(40);
                        PortalHelper.setPlayerOrigin(player.getUuid(), worldKey, pos);
                        double landY = isHorizontal ? targetY + 1 : targetY;
                        player.teleport(targetWorld, targetCenterX + 0.5, landY, targetCenterZ + 0.5, Set.of(), player.getYaw(), player.getPitch(), true);
                    } catch (Exception ignored) {
                    }
                    continue playerLoop;
                }
            }
        }

        PortalHelper.spawnTargetPortalParticles(world);
    }
}
