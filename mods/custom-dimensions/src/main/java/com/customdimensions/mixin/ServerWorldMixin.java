package com.customdimensions.mixin;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.ImmersiveSettings;
import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.config.PortalDefinition;
import com.customdimensions.dimension.DimensionManager;
import com.customdimensions.immersive.ImmersivePreloader;
import com.customdimensions.portal.PortalHelper;
import com.customdimensions.portal.PortalShape;
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
                    // A portal is one thing with two ends. Breaking only this
                    // frame would leave the arrival standing in the
                    // destination — still a real portal block, still
                    // registered, still returning anyone who stepped into it
                    // to a doorway that no longer exists. Take both.
                    //
                    // This lives HERE and not in removeZone on purpose:
                    // expireSingleUse calls removeZone too, and "the way in
                    // crumbles behind you" must never crumble the way home.
                    PortalHelper.breakLinkedArrival(world, zone);
                    PortalHelper.clearInteriorPortals(world, zone);
                    PortalHelper.removeZone(zone);
                    // Persist the removal now rather than at shutdown: without
                    // this, a broken portal would live on in portal_links.json
                    // until a clean stop and reappear after a crash. Rare
                    // enough to cost nothing — a zone goes invalid once, then
                    // it is gone.
                    PortalHelper.savePortalLinks();
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

            // Immersive portals: once a player gets within activationRange
            // of an immersive zone, pre-load its target world and
            // pre-generate the arrival chunks, so stepping through feels
            // instant instead of pausing on first visit. Zones without
            // "immersive" configured skip this entirely.
            for (ServerPlayerEntity player : world.getPlayers()) {
                BlockPos playerPos = player.getBlockPos();
                for (PortalHelper.PortalZone zone : zones) {
                    ImmersiveSettings imm = zone.definition.getImmersive();
                    if (imm == null) {
                        continue;
                    }
                    BlockPos centre = PortalShape.centreOf(zone.interior);
                    if (centre == null || !centre.isWithinDistance(playerPos, imm.activationRange())) {
                        continue;
                    }
                    RegistryKey<World> targetKey = zone.targetWorld;
                    ServerWorld targetWorld = world.getServer().getWorld(targetKey);
                    if (targetWorld == null) {
                        DimensionManager.getInstance().requestWorldLoad(targetKey.getValue().getPath());
                        continue;
                    }
                    ImmersivePreloader.preloadIfNeeded(targetWorld, zone, zone.definition);
                }
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
                        // Shared with PortalBreakLink so symmetric breaking
                        // matches on the same number setSourceColumn stamps.
                        // Two copies of this average would drift and the break
                        // would silently match nothing.
                        int[] sourceColumn =
                                com.customdimensions.portal.PortalBreakLink.centreColumn(zone.interior);
                        int portalCenterX = sourceColumn != null ? sourceColumn[0] : 0;
                        int portalCenterZ = sourceColumn != null ? sourceColumn[1] : 0;
                        // DIVIDE on entry — "8 nether : 1 over". See
                        // ProjectionVolume.scaledMapping for the full note.
                        int targetCenterX = (int) Math.round(portalCenterX / scale);
                        int targetCenterZ = (int) Math.round(portalCenterZ / scale);
                        // NB: there is deliberately no dx/dz here. The
                        // difference (targetCentre - portalCentre) is the
                        // PROJECTION offset — what ProjectionVolume adds to a
                        // SOURCE position to reach its target counterpart. The
                        // arrival centre is targetCentreX/Z and nothing else;
                        // adding the offset to it applies the shift twice
                        // (2*target - source) and builds the portal hundreds of
                        // blocks from where the player is teleported.

                        // Arrival height comes from the target column's own
                        // surface — the SCALED centre, since source-portal
                        // coordinates are the wrong column for scale != 1.
                        int surfaceY = PortalHelper.findSurfaceY(targetWorld, targetCenterX, targetCenterZ);

                        // Arrivals are a STANDARD size at an OPEN site, not a
                        // copy of whatever frame the player built at a
                        // heightmap that lies in a ceilinged world. See
                        // PortalSite — this is the "arrived encased in
                        // calcite at y=248" fix, and the reason the way home
                        // looks the same wherever you came from.
                        int siteY = com.customdimensions.portal.PortalSite.findArrivalY(
                                targetWorld, targetCenterX, targetCenterZ, zone.axis, surfaceY);
                        boolean carved = siteY == com.customdimensions.portal.PortalSite.NO_SITE;
                        if (carved) {
                            // No open pocket anywhere in the band, so carve one.
                            //
                            // Never fall back to `siteY = surfaceY` here — the
                            // MOTION_BLOCKING_NO_LEAVES heightmap reads the ROOF
                            // in a ceilinged dimension, which would put players
                            // on that roof and silently undo everything
                            // PortalSite is for. A fallback to a number known
                            // to be wrong is not a fallback.
                            siteY = com.customdimensions.portal.PortalSite.findCarveY(
                                    targetWorld, targetCenterX, targetCenterZ, zone.axis, surfaceY);
                        }
                        if (siteY == com.customdimensions.portal.PortalSite.NO_SITE) {
                            // Nothing open and nothing carveable: bedrock or
                            // block entities all the way down. Refusing the
                            // traversal is strictly better than teleporting
                            // someone into a place we know we cannot open.
                            MultiverseServer.LOGGER.error(
                                    "No viable arrival site in {} at column ({}, {}) — refusing traversal",
                                    targetKey.getValue(), targetCenterX, targetCenterZ);
                            player.sendMessage(net.minecraft.text.Text.literal(
                                    "The portal cannot find anywhere safe to put you."), true);
                            continue playerLoop;
                        }
                        surfaceY = siteY;
                        HashSet<BlockPos> adjustedInterior = new HashSet<>(
                                com.customdimensions.portal.PortalSite.standardInterior(
                                        targetCenterX, siteY, targetCenterZ, zone.axis));

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
                        // Tell the new arrival which column it came from, so its
                        // immersive preview translates to the real source portal
                        // instead of to its own column. One mapping, both ways.
                        PortalHelper.setSourceColumn(targetKey, adjustedInterior, portalCenterX, portalCenterZ);
                        com.customdimensions.portal.PortalAuraManager.onLink(
                                world, zone, targetWorld, adjustedInterior);
                        // Says HOW the site was chosen, not just that one was:
                        // "carved" appearing on every arrival in a dimension
                        // means its search band is wrong again.
                        MultiverseServer.LOGGER.info("Created portal in {} at ({}, {}, {}) [{} site]",
                                targetKey.getValue(), targetCenterX, surfaceY, targetCenterZ,
                                carved ? "carved" : "open");
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

        // Symmetric breaking: clear counterpart portal cells whose chunks
        // were cold when their other end was broken. Loaded chunks only —
        // never sync-loads, so a destination nobody has visited simply
        // waits until somebody does.
        PortalHelper.processPendingBreaks(world);

        PortalHelper.spawnTargetPortalParticles(world);

        // Exit portals: periodic exists/rebuild check for dimensions that
        // declare one (block placement from a world tick is safe; only the
        // worlds-map mutation rule below applies here).
        com.customdimensions.portal.ExitPortalManager.tick(world);

        // Portal auras: bounded environmental spread around portal pairs
        // (chunk-loaded guard + budgets inside; same safety envelope as
        // the exit-portal tick).
        com.customdimensions.portal.PortalAuraManager.tick(world);

        // Immersive portals: per-player fake block projection of the far
        // dimension through the frame. Must run AFTER the player teleport
        // loop above (a player who stepped through this tick is already in
        // the target world) and after the aura pass (so the projection
        // samples post-aura blocks). Fetches its own zones and returns
        // immediately when none are immersive.
        com.customdimensions.immersive.ImmersiveProjector.tick(world);

        // Immersive portals: items, projectiles, XP orbs and falling blocks
        // crossing an immersive source zone with their velocity intact.
        // After the projector so a crossing entity sees the same zone state
        // the projection was built from, and before ExitConditions. Fetches
        // its own zones and does nothing at all for non-immersive ones.
        com.customdimensions.immersive.EntityPassthrough.tick(world);

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
        // The same arrival dance as the per-source path: the raw heightmap
        // reports the ROOF in a ceilinged dimension (the exact bug
        // PortalSite exists to prevent), so resolve a real site — open
        // pocket first, carve second, refuse third. The anchor's
        // configured Y stays a hint only.
        int surfaceY = PortalHelper.findSurfaceY(targetWorld, anchorX, anchorZ);
        int siteY = com.customdimensions.portal.PortalSite.findArrivalY(
                targetWorld, anchorX, anchorZ, zone.axis, surfaceY);
        if (siteY == com.customdimensions.portal.PortalSite.NO_SITE) {
            siteY = com.customdimensions.portal.PortalSite.findCarveY(
                    targetWorld, anchorX, anchorZ, zone.axis, surfaceY);
        }
        if (siteY == com.customdimensions.portal.PortalSite.NO_SITE) {
            MultiverseServer.LOGGER.error(
                    "No viable anchor arrival site in {} at column ({}, {}) — refusing traversal",
                    targetWorld.getRegistryKey().getValue(), anchorX, anchorZ);
            player.sendMessage(net.minecraft.text.Text.literal(
                    "The portal cannot find anywhere safe to put you."), true);
            return;
        }
        surfaceY = siteY;

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
