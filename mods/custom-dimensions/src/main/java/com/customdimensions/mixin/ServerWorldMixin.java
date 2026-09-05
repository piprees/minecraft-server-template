package com.customdimensions.mixin;

import com.customdimensions.MultiverseServer;
import com.customdimensions.config.ImmersiveSettings;
import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.config.PortalDefinition;
import com.customdimensions.dimension.DimensionManager;
import com.customdimensions.immersive.ImmersivePreloader;
import com.customdimensions.immersive.VanillaLinkResolver;
import com.customdimensions.portal.PortalAdoption;
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

    /**
     * Approach re-scan cadence, matching VanillaLinkResolver's own retry
     * interval. Phased per world — the tick counter is the server's, shared by
     * every loaded dimension.
     */
    private static final int APPROACH_SCAN_INTERVAL = 40;

    @Inject(method = "tick", at = @At("HEAD"))
    private void onTick(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        ServerWorld world = (ServerWorld) (Object) this;
        RegistryKey<World> worldKey = world.getRegistryKey();
        PortalHelper.restoreZones(world);

        // Presentation zones for portals vanilla owns, before the lists below
        // are taken, so one registered this tick is drawn this tick. Outside
        // the zone block on purpose: a world with no zones at all is exactly
        // the state this pass exists to leave.
        adoptPortalsOnApproach(world, worldKey);

        List<PortalHelper.PortalZone> sourceZones = PortalHelper.getSourceZones(worldKey);
        // Presentation zones carry a vanillaManaged portal's geometry so the
        // projector has a plane to draw through. An invalid one is dropped and
        // nothing else — never break the far end, never clear the interior:
        // both belong to vanilla, which is also what re-lights the portal.
        List<PortalHelper.PortalZone> presentationZones = new ArrayList<>();
        for (PortalHelper.PortalZone zone
                : new ArrayList<>(PortalHelper.getPresentationZones(worldKey))) {
            if (!PortalHelper.isZoneChunkLoaded(world, zone)) {
                continue;
            }
            if (PortalHelper.isZoneValid(world, zone)) {
                presentationZones.add(zone);
            } else {
                PortalHelper.removePresentationZone(zone);
            }
        }
        if (!sourceZones.isEmpty() || !presentationZones.isEmpty()) {
            // Snapshot: getSourceZones returns the live backing list;
            // removeZone modifies it, so iterating directly is a CME.
            List<PortalHelper.PortalZone> snapshot = new ArrayList<>(sourceZones);
            List<PortalHelper.PortalZone> zones = new ArrayList<>();
            for (PortalHelper.PortalZone zone : snapshot) {
                // A cold zone is skipped whole: validating it would sync-load
                // its chunk every tick, and its single-use countdown holds
                // rather than draining while nobody is there to use it.
                if (!PortalHelper.isZoneChunkLoaded(world, zone)) {
                    continue;
                }
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
            List<PortalHelper.PortalZone> previewZones = new ArrayList<>(zones);
            previewZones.addAll(presentationZones);
            for (ServerPlayerEntity player : world.getPlayers()) {
                BlockPos playerPos = player.getBlockPos();
                for (PortalHelper.PortalZone zone : previewZones) {
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
                    // A player looking through a portal is using the world on
                    // the other side. The idle unloader sees neither them nor
                    // the preloader's PORTAL tickets, so it closes it in use.
                    DimensionManager.getInstance().updatePlayerPresence(targetKey, true);
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
                            if (!teleportToAnchor(world, targetWorld, player, zone, def, pos)) {
                                // Anchor column still generating — retry.
                                PortalHelper.setPlayerInZone(entryKey, false);
                                continue;
                            }
                            PortalHelper.startSingleUseCountdown(zone);
                            continue playerLoop;
                        }

                        // Shared with PortalBreakLink so symmetric breaking
                        // matches on the same number setSourceColumn stamps.
                        // Two copies of this average would drift and the break
                        // would silently match nothing.
                        int[] sourceColumn =
                                com.customdimensions.portal.PortalBreakLink.centreColumn(zone.interior);
                        int portalCenterX = sourceColumn != null ? sourceColumn[0] : 0;
                        int portalCenterZ = sourceColumn != null ? sourceColumn[1] : 0;
                        // Scale describes the DIMENSION, so the transform asks
                        // each side for its own: entering a scale-8 world
                        // divides by 8, leaving one multiplies by 8. The same
                        // call the projection draws with, so the place this
                        // portal SHOWS and the place it PUTS you cannot
                        // disagree.
                        com.customdimensions.immersive.ProjectionVolume.TargetMapping mapping =
                                com.customdimensions.immersive.ImmersiveProjector.mappingFor(zone, def);
                        int targetCenterX = mapping.arrivalX();
                        int targetCenterZ = mapping.arrivalZ();
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
                        //
                        // Nobody has ever been to that column — that is what an
                        // arrival IS — so on a first traversal its chunk is
                        // cold and null comes back. Hand the entry edge back and
                        // let a later tick find it resident.
                        Integer arrivalY =
                                PortalHelper.arrivalSurfaceY(targetWorld, targetCenterX, targetCenterZ);
                        if (arrivalY == null) {
                            PortalHelper.setPlayerInZone(entryKey, false);
                            continue;
                        }
                        int surfaceY = arrivalY;

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

                        // An arrival has no blocks, so the registry is the
                        // only record that one is here. findRegisteredPortalNear
                        // reproduces the old block scan's search order exactly.
                        BlockPos existing = PortalHelper.findRegisteredPortalNear(
                                targetKey, targetCenterX, surfaceY, targetCenterZ, 5, 16);
                        int portalCooldown = def.getCooldown();

                        if (existing != null) {
                            Set<BlockPos> aperture = PortalHelper.registeredAperture(
                                    targetKey, existing, zone.axis);
                            // An arrival built before arrival zones were
                            // recorded gets its zone on first reuse, so its
                            // frame is validated from then on.
                            PortalHelper.ensureArrivalZone(
                                    targetKey, aperture, zone.axis, def, worldKey);
                            playPortalSound(world, pos, def.getEnterSound());
                            player.setPortalCooldown(portalCooldown);
                            PortalHelper.setPlayerOrigin(player.getUuid(), worldKey, pos);
                            double landY = isHorizontal ? existing.getY() + 1 : existing.getY();
                            com.customdimensions.companion.CompanionNetwork.notifyPreloadedTransfer(
                                    player, targetWorld, zone, existing.getX(), existing.getZ());
                            player.teleport(targetWorld, existing.getX() + 0.5, landY, existing.getZ() + 0.5, Set.of(), player.getYaw(), player.getPitch());
                            playPortalSound(targetWorld, existing, def.getExitSound());
                            PortalHelper.startSingleUseCountdown(zone);
                            // Older zones may pre-date auras: link them on
                            // first reuse (both worlds loaded right now).
                            com.customdimensions.portal.PortalAuraManager.onLink(
                                    world, zone, targetWorld, aperture);
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
                        com.customdimensions.companion.CompanionNetwork.notifyPreloadedTransfer(
                                player, targetWorld, zone, targetCenterX, targetCenterZ);
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

        // Arrival zones: same frame check the source side gets. Break one
        // frame block of an arrival and the portal is gone, both ends of it —
        // there is nothing else it could be, and nothing inside it to break.
        for (PortalHelper.PortalZone arrival
                : new ArrayList<>(PortalHelper.getArrivalZones(worldKey))) {
            if (!PortalHelper.isZoneChunkLoaded(world, arrival)) {
                continue;
            }
            if (!PortalHelper.isZoneValid(world, arrival)) {
                PortalHelper.closeArrival(world, arrival);
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

    /**
     * Immersive portals: items, projectiles, XP orbs and falling blocks
     * crossing an immersive source zone with their velocity intact.
     *
     * <p>At the TAIL rather than with the rest of the immersive pass, because
     * {@code ServerWorld.tick} moves entities near its end: from the HEAD the
     * swept test reads the PREVIOUS tick's step and every crossing teleports a
     * tick late, which the client sees as the entity flying on past the
     * opening. Ordering against the projector and the player loop is unchanged
     * — both run from the HEAD of this same tick. It cannot race
     * {@code ExitConditions}, which only ever touches players.
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void onTickEnd(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        com.customdimensions.immersive.EntityPassthrough.tick((ServerWorld) (Object) this);
    }

    /**
     * A vanilla portal is adopted on CONTACT everywhere else — the player is
     * already standing in it, and vanilla teleports them a tick or two later,
     * so the preview through the frame was never drawn. This offers one on
     * APPROACH instead, off the nether-portal point-of-interest index vanilla
     * keeps anyway.
     *
     * <p>Resident chunks only, twice over: the index is read through its own
     * residency rule, and a hit is skipped unless the whole square adoption
     * would flood-fill and frame-walk is loaded too. Nothing here waits for
     * generation, and a skipped hit is offered again on the next pass. End
     * portals are not points of interest and get nothing from this —
     * {@code VanillaLinkResolver.endPlatform} already answers the End's
     * arrival without an index.
     */
    private static void adoptPortalsOnApproach(ServerWorld world, RegistryKey<World> worldKey) {
        int phase = PortalAdoption.approachPhase(worldKey.getValue().toString(), APPROACH_SCAN_INTERVAL);
        if ((world.getServer().getTicks() + phase) % APPROACH_SCAN_INTERVAL != 0) {
            return;
        }
        // Players first: most of the loaded dimensions are empty most of the
        // time, and presentationRange walks every portal definition.
        if (world.getPlayers().isEmpty()) {
            return;
        }
        MultiverseConfig config = MultiverseConfig.getInstance();
        if (!config.hasVanillaManagedPortals()) {
            return;
        }
        int range = PortalAdoption.presentationRange(config.getPortals());
        if (range <= 0) {
            return;
        }

        int chunkRadius = VanillaLinkResolver.chunkRadiusFor(range);
        PortalAdoption.ColumnResidency resident = PortalHelper.residencyOf(world);
        Set<BlockPos> covered = new HashSet<>();
        for (PortalHelper.PortalZone zone : PortalHelper.getProjectionZones(worldKey)) {
            covered.addAll(zone.interior);
        }
        for (ServerPlayerEntity player : world.getPlayers()) {
            BlockPos playerPos = player.getBlockPos();
            List<BlockPos> known =
                    VanillaLinkResolver.netherPortalsNear(world, playerPos, chunkRadius);
            for (BlockPos hit : PortalAdoption.dueForPresentation(
                    known, playerPos, range, covered, resident)) {
                // One portal is one point of interest per BLOCK, and covered
                // grows as areas are collected, so each portal is walked once.
                if (covered.contains(hit)) {
                    continue;
                }
                Set<BlockPos> area = PortalHelper.collectPortalArea(world, hit);
                if (area.isEmpty()) {
                    continue;
                }
                covered.addAll(area);
                PortalAdoption.adopt(world, area);
            }
        }
    }

    // Anchor arrival: skip scaled-coordinate mapping entirely, surface-resolve
    // the anchor column, and reuse (or rebuild) the one anchor arrival portal.
    // Its return targets carry the anchor's exit mode ("origin"/"bed"/
    // "worldSpawn") — EntityTickPortalMixin resolves them on the way out.
    //
    // Returns false ONLY for "not yet" — the anchor column is still
    // generating and the caller should retry. A refused traversal (no viable
    // site) returns true: it has been decided and told the player, and
    // retrying it every tick would just repeat the message.
    private static boolean teleportToAnchor(ServerWorld world, ServerWorld targetWorld,
            ServerPlayerEntity player, PortalHelper.PortalZone zone, PortalDefinition def, BlockPos pos) {
        int[] anchor = def.getAnchorPos();
        int anchorX = anchor[0];
        int anchorZ = anchor[2];
        // The same arrival dance as the per-source path: the raw heightmap
        // reports the ROOF in a ceilinged dimension (the exact bug
        // PortalSite exists to prevent), so resolve a real site — open
        // pocket first, carve second, refuse third. The anchor's
        // configured Y stays a hint only. Null is the cold-column answer:
        // every read below would block the tick until it generates.
        Integer anchorSurfaceY = PortalHelper.arrivalSurfaceY(targetWorld, anchorX, anchorZ);
        if (anchorSurfaceY == null) {
            return false;
        }
        int surfaceY = anchorSurfaceY;
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
            return true;
        }
        surfaceY = siteY;

        // Axis-agnostic by construction: a previous arrival may have built
        // the anchor on the other horizontal axis, and the registry does not
        // care which.
        BlockPos existing = PortalHelper.findRegisteredPortalNear(
                targetWorld.getRegistryKey(), anchorX, surfaceY, anchorZ, 5, 16);

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
            Set<BlockPos> aperture = PortalHelper.registeredAperture(
                    targetWorld.getRegistryKey(), existing, zone.axis);
            PortalHelper.ensureArrivalZone(
                    targetWorld.getRegistryKey(), aperture, zone.axis, def, worldKey);
            com.customdimensions.portal.PortalAuraManager.onLink(world, zone, targetWorld, aperture);
        }

        playPortalSound(world, pos, def.getEnterSound());
        player.setPortalCooldown(def.getCooldown());
        PortalHelper.setPlayerOrigin(player.getUuid(), worldKey, pos);
        double landX = (existing != null ? existing.getX() : anchorX) + 0.5;
        double landY = existing != null
                ? (isHorizontal ? existing.getY() + 1 : existing.getY())
                : (isHorizontal ? surfaceY + 1 : surfaceY);
        double landZ = (existing != null ? existing.getZ() : anchorZ) + 0.5;
        com.customdimensions.companion.CompanionNetwork.notifyPreloadedTransfer(
                player, targetWorld, zone, (int) Math.floor(landX), (int) Math.floor(landZ));
        player.teleport(targetWorld, landX, landY, landZ, Set.of(), player.getYaw(), player.getPitch());
        playPortalSound(targetWorld, BlockPos.ofFloored(landX, landY, landZ), def.getExitSound());
        return true;
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
