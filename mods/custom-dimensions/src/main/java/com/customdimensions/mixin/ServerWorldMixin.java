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
        PortalHelper.restoreZones(world);

        List<PortalHelper.PortalZone> sourceZones = PortalHelper.getSourceZones(worldKey);
        if (!sourceZones.isEmpty()) {
            // Snapshot: getSourceZones returns the live backing list;
            // removeZone modifies it, so iterating directly is a CME.
            List<PortalHelper.PortalZone> snapshot = new ArrayList<>(sourceZones);
            List<PortalHelper.PortalZone> zones = new ArrayList<>();
            for (PortalHelper.PortalZone zone : snapshot) {
                if (!PortalHelper.isZoneValid(world, zone)) {
                    PortalHelper.clearInteriorPortals(world, zone);
                    PortalHelper.removeZone(zone);
                    continue;
                }
                // Single-use countdown, armed at first traversal and resumed
                // from portal_links.json after a restart (-1 = never traversed).
                if (zone.singleUseTicksLeft >= 0) {
                    if (zone.singleUseTicksLeft > 0) {
                        zone.singleUseTicksLeft--;
                    }
                    if (zone.singleUseTicksLeft == 0) {
                        PortalHelper.expireSingleUse(world, zone);
                        continue;
                    }
                }
                zones.add(zone);
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

                        // Anchor dimensions: every source portal lands at one
                        // fixed position and no per-source target portal is
                        // ever created — the single anchor arrival portal is
                        // built on first arrival and rebuilt if broken.
                        if (def.hasAnchor()) {
                            teleportToAnchor(world, targetWorld, player, zone, def, pos);
                            PortalHelper.startSingleUseCountdown(zone);
                            continue playerLoop;
                        }

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

                        BlockPos existing = com.customdimensions.portal.PortalShape.END_GATEWAY.equals(def.getShape())
                                ? PortalHelper.findExistingGateway(targetWorld, targetCenterX, surfaceY, targetCenterZ, 5, 16)
                                : PortalHelper.findExistingPortal(targetWorld, targetCenterX, surfaceY, targetCenterZ, 5, 16, zone.axis);
                        int portalCooldown = def.getCooldown();

                        if (existing != null) {
                            playPortalSound(world, pos, def.getEnterSound());
                            player.setPortalCooldown(portalCooldown);
                            PortalHelper.setPlayerOrigin(player.getUuid(), worldKey, pos);
                            double landY = isHorizontal ? existing.getY() + 1 : existing.getY();
                            player.teleport(targetWorld, existing.getX() + 0.5, landY, existing.getZ() + 0.5, Set.of(), player.getYaw(), player.getPitch());
                            playPortalSound(targetWorld, existing, def.getExitSound());
                            PortalHelper.startSingleUseCountdown(zone);
                            // Older zones may pre-date auras: link them on
                            // first reuse (both worlds loaded right now).
                            com.customdimensions.portal.PortalAuraManager.onLink(
                                    world, zone, targetWorld, PortalHelper.collectPortalArea(targetWorld, existing));
                            continue playerLoop;
                        }

                        PortalHelper.createTargetPortal(targetWorld, adjustedInterior, zone.axis, def, worldKey, pos.getY());
                        com.customdimensions.portal.PortalAuraManager.onLink(
                                world, zone, targetWorld, adjustedInterior);
                        MultiverseServer.LOGGER.info("Created portal in {} at ({}, {}, {})",
                                targetKey.getValue(), targetCenterX, surfaceY, targetCenterZ);
                        playPortalSound(world, pos, def.getEnterSound());
                        player.setPortalCooldown(portalCooldown);
                        PortalHelper.setPlayerOrigin(player.getUuid(), worldKey, pos);
                        double landY = isHorizontal ? surfaceY + 1 : surfaceY;
                        player.teleport(targetWorld, targetCenterX + 0.5, landY, targetCenterZ + 0.5, Set.of(), player.getYaw(), player.getPitch());
                        playPortalSound(targetWorld, new BlockPos(targetCenterX, (int) landY, targetCenterZ), def.getExitSound());
                        PortalHelper.startSingleUseCountdown(zone);
                    } catch (Exception e) {
                        MultiverseServer.LOGGER.error("Failed portal teleport for player {} in {}", player.getName().getString(), worldKey.getValue(), e);
                    }
                    continue playerLoop;
                }
            }
        }

        PortalHelper.spawnTargetPortalParticles(world);

        // Exit portals: periodic exists/rebuild check for dimensions that
        // declare one (block placement from a world tick is safe; only the
        // worlds-map mutation rule below applies here).
        com.customdimensions.portal.ExitPortalManager.tick(world);

        // Portal auras: bounded environmental spread around portal pairs
        // (chunk-loaded guard + budgets inside; same safety envelope as
        // the exit-portal tick).
        com.customdimensions.portal.PortalAuraManager.tick(world);

        // Exit conditions ("exits" block): void + fallFrom triggers. Runs
        // at tick HEAD, so a configured void exit fires BEFORE vanilla void
        // damage (Entity.tickInVoid runs later, during entity ticking).
        com.customdimensions.dimension.ExitConditions.tick(world);

        // Exit shrines: light + register any beacons the chunk-load scan
        // queued (block mutation belongs in the tick, not the load event).
        com.customdimensions.portal.ExitShrineManager.processQueued(world);

        DimensionManager.getInstance().updatePlayerPresence(worldKey, !world.getPlayers().isEmpty());

        // Idle unload is driven by ServerTickEvents.END_SERVER_TICK (see
        // MultiverseServer) — never from here: this injection runs inside
        // MinecraftServer.tickWorlds' iteration of the worlds map, and
        // removing worlds mid-iteration is a ConcurrentModificationException.
    }

    // Anchor arrival: skip scaled-coordinate mapping entirely, surface-resolve
    // the anchor column, and reuse (or rebuild) the one anchor arrival portal.
    // Its return targets carry the anchor's exit mode ("origin"/"bed"/
    // "worldSpawn") — EntityTickPortalMixin resolves them on the way out.
    private static void teleportToAnchor(ServerWorld world, ServerWorld targetWorld,
            ServerPlayerEntity player, PortalHelper.PortalZone zone, PortalDefinition def, BlockPos pos) {
        int[] anchor = def.getAnchorPos();
        int anchorX = anchor[0];
        int anchorZ = anchor[2];
        int surfaceY = PortalHelper.findSurfaceY(targetWorld, anchorX, anchorZ);

        BlockPos existing = com.customdimensions.portal.PortalShape.END_GATEWAY.equals(def.getShape())
                ? PortalHelper.findExistingGateway(targetWorld, anchorX, surfaceY, anchorZ, 5, 16)
                : PortalHelper.findExistingPortal(targetWorld, anchorX, surfaceY, anchorZ, 5, 16, zone.axis);
        if (existing == null && zone.axis != Direction.Axis.Y
                && !com.customdimensions.portal.PortalShape.END_GATEWAY.equals(def.getShape())) {
            // A previous arrival may have built the portal on the other
            // horizontal axis (first source's shape wins) — reuse it.
            Direction.Axis other = zone.axis == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
            existing = PortalHelper.findExistingPortal(targetWorld, anchorX, surfaceY, anchorZ, 5, 16, other);
        }

        boolean isHorizontal = zone.axis == Direction.Axis.Y;
        RegistryKey<World> worldKey = world.getRegistryKey();
        if (existing == null) {
            // Rebuild from this zone's shape, translated onto the anchor.
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            for (BlockPos p : zone.interior) {
                minX = Math.min(minX, p.getX());
                minY = Math.min(minY, p.getY());
                minZ = Math.min(minZ, p.getZ());
            }
            HashSet<BlockPos> anchorInterior = new HashSet<>();
            for (BlockPos p : zone.interior) {
                anchorInterior.add(new BlockPos(
                        anchorX + (p.getX() - minX),
                        surfaceY + (p.getY() - minY),
                        anchorZ + (p.getZ() - minZ)));
            }
            PortalHelper.createTargetPortal(targetWorld, anchorInterior, zone.axis, def, worldKey, pos.getY(), def.getAnchorExit());
            MultiverseServer.LOGGER.info("Created anchor portal in {} at ({}, {}, {})",
                    zone.targetWorld.getValue(), anchorX, surfaceY, anchorZ);
            // Anchor arrivals are shared by many sources: onLink samples
            // once and the first link wins (immutable snapshot).
            com.customdimensions.portal.PortalAuraManager.onLink(world, zone, targetWorld, anchorInterior);
        } else {
            com.customdimensions.portal.PortalAuraManager.onLink(
                    world, zone, targetWorld, PortalHelper.collectPortalArea(targetWorld, existing));
        }

        playPortalSound(world, pos, def.getEnterSound());
        player.setPortalCooldown(def.getCooldown());
        PortalHelper.setPlayerOrigin(player.getUuid(), worldKey, pos);
        double landX = (existing != null ? existing.getX() : anchorX) + 0.5;
        double landY = existing != null
                ? (isHorizontal ? existing.getY() + 1 : existing.getY())
                : (isHorizontal ? surfaceY + 1 : surfaceY);
        double landZ = (existing != null ? existing.getZ() : anchorZ) + 0.5;
        player.teleport(targetWorld, landX, landY, landZ, Set.of(), player.getYaw(), player.getPitch());
        playPortalSound(targetWorld, BlockPos.ofFloored(landX, landY, landZ), def.getExitSound());
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
