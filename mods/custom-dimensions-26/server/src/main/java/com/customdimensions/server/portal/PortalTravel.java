package com.customdimensions.server.portal;

import com.customdimensions.core.config.DimensionConfig;
import com.customdimensions.core.config.PortalDefinition;
import com.customdimensions.core.dimension.ExitTarget;
import com.customdimensions.core.portal.ArrivalSite;
import com.customdimensions.core.portal.PortalScaling;
import com.customdimensions.core.messages.Messages;
import com.customdimensions.server.CustomDimensions;
import com.customdimensions.server.MessageFile;
import com.customdimensions.server.companion.CompanionNetwork;
import com.customdimensions.server.level.LevelManager;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/**
 * Where an entity in one of our portal blocks goes.
 * <p>
 * Out of a source zone: the target column (scaled, or the anchor), then the
 * existing arrival there, the portal a player lit at that end, or a new
 * arrival at an open or carved site. Out of an arrival: its exit mode
 * ({@code bed}, {@code worldSpawn}, {@code origin}, a dimension link), the
 * player's origin, or the column of the portal that built it. Each of the
 * three ways out is a link, and the first link samples both ends' auras.
 * <p>
 * Nothing here waits. A player bound for a lazy destination opens it through
 * {@link LevelManager#activate}; anything else crosses only into a level that
 * is already open, so a mob or a hopper line never keeps one loaded. A cold
 * arrival column gets a ticket, and
 * until both are ready the answer is "not yet": no transition, and the
 * entity's portal cooldown is cleared so the next tick asks again.
 */
public final class PortalTravel {
    /** How far from the target column an existing arrival or lit portal is reused. */
    static final int REUSE_RADIUS = 5;

    private static final Set<String> LOGGED_FAILURES = new HashSet<>();

    private PortalTravel() {
    }

    public static TeleportTransition destination(ServerLevel level, Entity entity, BlockPos pos) {
        PortalZone zone = PortalRegistry.get().zoneAt(level.dimension(), pos);
        if (zone == null) {
            return null;
        }
        try {
            return zone.arrival ? back(level, entity, pos, zone) : out(level, entity, pos, zone);
        } catch (RuntimeException e) {
            CustomDimensions.LOG.warn("portal travel from {} at {} failed", level.dimension().identifier(), pos, e);
            return null;
        }
    }

    /** "Not yet": the next tick asks again. */
    private static TeleportTransition hold(Entity entity) {
        entity.setPortalCooldown(0);
        return null;
    }

    /** The level behind {@code key}; a lazy one is opened for a player and empty until ready. */
    static Optional<ServerLevel> ready(MinecraftServer server, ResourceKey<Level> key, Entity entity) {
        Optional<LevelManager> manager = LevelManager.get();
        Optional<String> slug = manager.flatMap(m -> m.slugOf(key));
        if (slug.isPresent()) {
            LevelManager m = manager.get();
            if (m.state(slug.get()) == LevelManager.State.ACTIVE) {
                return m.level(slug.get());
            }
            if (!(entity instanceof ServerPlayer)) {
                return Optional.empty();
            }
            m.activate(slug.get()).whenComplete((a, error) -> {
                if (error != null && LOGGED_FAILURES.add(slug.get())) {
                    CustomDimensions.LOG.warn("portal destination {} did not open: {}", slug.get(), error.toString());
                }
            });
            return Optional.empty();
        }
        return Optional.ofNullable(server.getLevel(key));
    }

    private static TeleportTransition out(ServerLevel level, Entity entity, BlockPos pos, PortalZone zone) {
        PortalDefinition def = zone.definition;
        Optional<ServerLevel> found = ready(level.getServer(), zone.target, entity);
        if (found.isEmpty()) {
            return hold(entity);
        }
        ServerLevel target = found.get();
        int[] column = def.hasAnchor()
                ? new int[]{def.getAnchorPos()[0], def.getAnchorPos()[2]}
                : PortalScaling.arrivalColumn(zone.coreInterior(), PortalCatalogue.scaleOf(zone.world, def),
                        PortalCatalogue.scaleOf(zone.target, def));
        int x = column[0];
        int z = column[1];
        PortalTickets.hold(target, x, z);
        if (!PortalWorld.arrivalResident(target, x, z)) {
            return hold(entity);
        }
        String exitMode = def.hasAnchor() ? def.getAnchorExit() : null;
        PortalZone existing = PortalRegistry.get().arrivalNear(target.dimension(), x, z, REUSE_RADIUS,
                a -> leadsBackTo(a, zone.world, exitMode));
        if (existing != null) {
            // A recorded arrival is checked before it is reused: one whose frame is gone is closed and built anew.
            if (!Portals.loaded(target, existing)) {
                BlockPos at = existing.landingCell();
                PortalTickets.hold(target, at.getX(), at.getZ());
                return hold(entity);
            }
            Portals.validate(target, existing);
            if (!PortalRegistry.get().zones(target.dimension()).contains(existing)) {
                existing = null;
            }
        }
        BlockPos landing;
        if (existing != null) {
            landing = existing.landing();
            PortalAuras.onLink(level, zone, target, existing.interior);
        } else {
            PortalZone counterpart = def.hasAnchor() ? null
                    : PortalRegistry.get().sourceNear(target.dimension(), zone.world, x, z, REUSE_RADIUS);
            if (counterpart != null) {
                landing = counterpart.landing();
                PortalAuras.onLink(level, zone, target, counterpart.interior);
            } else {
                PortalZone built = buildArrival(target, zone, pos, x, z, exitMode);
                if (built == null) {
                    CustomDimensions.LOG.error("No viable arrival site in {} at column ({}, {}); refusing traversal",
                            target.dimension().identifier(), x, z);
                    if (entity instanceof ServerPlayer player) {
                        player.sendSystemMessage(Component.literal(
                                MessageFile.get().template(Messages.PORTAL_NO_SITE)), true);
                    }
                    return null;
                }
                landing = built.landing();
                PortalAuras.onLink(level, zone, target, built.interior);
            }
        }
        if (entity instanceof ServerPlayer player) {
            PortalRegistry.get().setOrigin(entity.getUUID(), zone.world, pos);
            CompanionNetwork.preloaded(player, target.dimension());
        }
        Portals.playSound(level, pos, def.getEnterSound());
        Portals.startSingleUse(zone);
        int cooldown = def.getCooldown();
        String exitSound = def.getExitSound();
        return transition(target, landing, entity, e -> {
            e.setPortalCooldown(cooldown);
            Portals.playSound(target, landing, exitSound);
        });
    }

    /**
     * The column a zone's far side stands on in its target, from the registry
     * and the scaling maths alone; null for an arrival that leads anywhere but
     * back to the portal that built it.
     */
    public static int[] farColumn(PortalZone zone) {
        if (zone.definition == null) {
            return null;
        }
        if (zone.arrival) {
            PortalZone.ReturnTarget t = PortalRegistry.get().target(zone.world, zone.landingCell());
            return t == null || !t.breaksSymmetrically() ? null : returnColumn(t, zone.landingCell(), zone, zone.world);
        }
        PortalDefinition def = zone.definition;
        return def.hasAnchor() ? new int[]{def.getAnchorPos()[0], def.getAnchorPos()[2]}
                : PortalScaling.arrivalColumn(zone.coreInterior(), PortalCatalogue.scaleOf(zone.world, def),
                        PortalCatalogue.scaleOf(zone.target, def));
    }

    /** The recorded opening at {@code zone}'s far side near {@code column}, or null before a crossing builds one. */
    public static PortalZone farZone(PortalZone zone, int[] column) {
        PortalRegistry registry = PortalRegistry.get();
        if (zone.arrival) {
            return registry.sourceNear(zone.target, zone.world, column[0], column[1], REUSE_RADIUS);
        }
        String exitMode = zone.definition.hasAnchor() ? zone.definition.getAnchorExit() : null;
        PortalZone found = registry.arrivalNear(zone.target, column[0], column[1], REUSE_RADIUS,
                a -> leadsBackTo(a, zone.world, exitMode));
        return found != null || zone.definition.hasAnchor() ? found
                : registry.sourceNear(zone.target, zone.world, column[0], column[1], REUSE_RADIUS);
    }

    private static boolean leadsBackTo(PortalZone arrival, ResourceKey<Level> world, String exitMode) {
        PortalZone.ReturnTarget t = PortalRegistry.get().target(arrival.world, arrival.landingCell());
        if (t == null) {
            return false;
        }
        return exitMode == null ? t.exitMode() == null && t.leadsTo().equals(world)
                : exitMode.equals(t.exitMode());
    }

    /** A standard arrival at the column's open or carved site; null when there is none. */
    private static PortalZone buildArrival(ServerLevel target, PortalZone zone, BlockPos from, int x, int z,
                                           String exitMode) {
        PortalDefinition def = zone.definition;
        com.customdimensions.core.Direction.Axis axis = Pos.core(zone.axis);
        int surface = PortalWorld.surfaceY(target, x, z);
        ArrivalSite.Probe probe = PortalWorld.probe(target);
        int y = ArrivalSite.findOpenY(PortalWorld.column(target), x, z, axis, surface, probe);
        boolean carved = y == ArrivalSite.NO_SITE;
        if (carved) {
            y = ArrivalSite.findCarveY(PortalWorld.column(target), x, z, axis, surface, probe);
        }
        if (y == ArrivalSite.NO_SITE) {
            return null;
        }
        Set<BlockPos> interior = def.hasAnchor() ? translated(zone.interior, x, y, z)
                : Pos.mc(ArrivalSite.standardInterior(x, y, z, axis));
        int[] sourceColumn = zone.centreColumn();
        PortalZone arrival = build(target, interior, zone.axis, def, zone.world, from.getY(), exitMode,
                sourceColumn[0], sourceColumn[1]);
        CustomDimensions.LOG.info("Created portal in {} at ({}, {}, {}) [{} site]", target.dimension().identifier(),
                x, y, z, carved ? "carved" : "open");
        return arrival;
    }

    /** The zone's own shape moved so its lowest corner sits at ({@code x}, {@code y}, {@code z}). */
    private static Set<BlockPos> translated(Set<BlockPos> interior, int x, int y, int z) {
        int minX = interior.stream().mapToInt(BlockPos::getX).min().orElse(0);
        int minY = interior.stream().mapToInt(BlockPos::getY).min().orElse(0);
        int minZ = interior.stream().mapToInt(BlockPos::getZ).min().orElse(0);
        Set<BlockPos> out = new HashSet<>();
        interior.forEach(p -> out.add(new BlockPos(x + p.getX() - minX, y + p.getY() - minY, z + p.getZ() - minZ)));
        return out;
    }

    /**
     * Registers and builds an arrival: return targets and zone first, then the
     * site is emptied, the frame raised and the opening filled. The colour and
     * particles are those of the world it leads back to.
     */
    static PortalZone build(ServerLevel level, Set<BlockPos> interior, Direction.Axis axis, PortalDefinition def,
                            ResourceKey<Level> leadsTo, int sourceY, String exitMode, Integer sourceX, Integer sourceZ) {
        PortalDefinition presentation = com.customdimensions.core.config.MultiverseConfig.getInstance()
                .getPortalFor(com.customdimensions.core.Id.parse(leadsTo.identifier().toString()));
        int color = presentation != null ? Portals.parseColor(presentation.getColor()) : Portals.NEUTRAL_COLOR;
        String particle = presentation != null ? presentation.getParticleType() : null;
        PortalZone.ReturnTarget target = new PortalZone.ReturnTarget(leadsTo, sourceY, color, def.getCooldown(), particle,
                exitMode, sourceX, sourceZ);
        PortalRegistry registry = PortalRegistry.get();
        for (BlockPos p : interior) {
            registry.putTarget(level.dimension(), p, target);
        }
        PortalZone arrival = new PortalZone(interior, def, axis, level.dimension(), leadsTo, true);
        registry.addIfAbsent(arrival);
        PortalWorld.clearArrival(level, interior, axis);
        PortalWorld.buildFrame(level, interior, axis, def);
        PortalWorld.fill(level, interior, axis);
        registry.save();
        return arrival;
    }

    private static TeleportTransition back(ServerLevel level, Entity entity, BlockPos pos, PortalZone zone) {
        PortalZone.ReturnTarget t = PortalRegistry.get().target(level.dimension(), pos);
        if (t == null) {
            return null;
        }
        MinecraftServer server = level.getServer();
        String mode = t.exitMode();
        TeleportTransition.PostTeleportTransition after = e -> e.setPortalCooldown(t.cooldown());
        if ("bed".equals(mode) && entity instanceof ServerPlayer player) {
            PortalRegistry.get().clearOrigin(player.getUUID());
            // false finds the respawn point without spending a respawn anchor's charge.
            TeleportTransition respawn = player.findRespawnPositionAndUseSpawnBlock(false, after);
            return respawn;
        }
        if ("bed".equals(mode) || "worldSpawn".equals(mode)) {
            PortalRegistry.get().clearOrigin(entity.getUUID());
            return worldSpawn(server, entity, after);
        }
        if (mode != null && mode.startsWith("dim!")) {
            ExitTarget link = ExitTarget.parse(mode);
            if (link != null && link.getKind() == ExitTarget.Kind.DIMENSION) {
                return dimensionLink(server, entity, link, after);
            }
        }
        ResourceKey<Level> worldKey;
        BlockPos land;
        PortalRegistry.Origin origin = PortalRegistry.get().origin(entity.getUUID());
        if (origin != null) {
            worldKey = origin.world();
            land = origin.pos();
        } else if ("origin".equals(mode)) {
            return worldSpawn(server, entity, after);
        } else {
            worldKey = t.leadsTo();
            int[] column = returnColumn(t, pos, zone, level.dimension());
            land = new BlockPos(column[0], t.sourceY(), column[1]);
        }
        if (worldKey.equals(level.dimension())) {
            return null;
        }
        Optional<ServerLevel> found = ready(server, worldKey, entity);
        if (found.isEmpty()) {
            return hold(entity);
        }
        ServerLevel home = found.get();
        PortalTickets.hold(home, land.getX(), land.getZ());
        if (!PortalWorld.resident(home, land.getX(), land.getZ(), land.getX(), land.getZ())) {
            return hold(entity);
        }
        return transition(home, land, entity, after);
    }

    /**
     * Where an exit target ({@code bed}, {@code worldSpawn}, {@code origin}, a
     * dimension link) puts {@code entity}; null while it is not ready, and the
     * next call asks again.
     */
    public static TeleportTransition exitTransition(MinecraftServer server, Entity entity, ExitTarget target,
                                                    TeleportTransition.PostTeleportTransition after) {
        switch (target.getKind()) {
            case BED:
                if (entity instanceof ServerPlayer player) {
                    return player.findRespawnPositionAndUseSpawnBlock(false, after);
                }
                return worldSpawn(server, entity, after);
            case WORLD_SPAWN:
                return worldSpawn(server, entity, after);
            case DIMENSION:
                return dimensionLink(server, entity, target, after);
            default:
                break;
        }
        PortalRegistry.Origin origin = PortalRegistry.get().origin(entity.getUUID());
        if (origin == null) {
            return worldSpawn(server, entity, after);
        }
        Optional<ServerLevel> found = ready(server, origin.world(), entity);
        if (found.isEmpty()) {
            return hold(entity);
        }
        ServerLevel home = found.get();
        BlockPos land = origin.pos();
        PortalTickets.hold(home, land.getX(), land.getZ());
        if (!PortalWorld.resident(home, land.getX(), land.getZ(), land.getX(), land.getZ())) {
            return hold(entity);
        }
        return transition(home, land, entity, after);
    }

    /**
     * Holds a ticket on an exit target's arrival column when its level is
     * already open, so a death exit can leave on the tick it happens. Opens
     * nothing; {@code bed} and {@code origin} depend on the player and are left alone.
     */
    public static void warmExit(MinecraftServer server, ExitTarget target) {
        if (target.getKind() == ExitTarget.Kind.WORLD_SPAWN) {
            BlockPos spawn = server.overworld().getRespawnData().pos();
            PortalTickets.hold(server.overworld(), spawn.getX(), spawn.getZ());
        } else if (target.getKind() == ExitTarget.Kind.DIMENSION) {
            ResourceKey<Level> key = PortalRegistry.key(target.getDimensionId());
            ServerLevel level = server.getLevel(key);
            if (level != null) {
                int[] pos = arrivalColumn(key, target);
                PortalTickets.hold(level, pos[0], pos[2]);
            }
        }
    }

    /** A dimension link's arrival: the explicit position, the anchor, the definition's spawn, else 0 64 0. */
    private static int[] arrivalColumn(ResourceKey<Level> key, ExitTarget link) {
        int[] pos = link.getArrivalPos();
        DimensionConfig config = PortalCatalogue.dimension(key);
        if (pos == null && "anchor".equals(link.getArrival()) && config != null && config.toPortalDefinition() != null
                && config.toPortalDefinition().hasAnchor()) {
            pos = config.toPortalDefinition().getAnchorPos();
        }
        if (pos == null) {
            int[] spawn = config != null ? config.getSpawn() : null;
            pos = spawn != null ? spawn : new int[]{0, 64, 0};
        }
        return pos;
    }

    /** The source portal's column when the arrival recorded it, else the arrival's own column scaled back. */
    static int[] returnColumn(PortalZone.ReturnTarget t, BlockPos cell, PortalZone zone, ResourceKey<Level> here) {
        if (t.sourceX() != null && t.sourceZ() != null) {
            return new int[]{t.sourceX(), t.sourceZ()};
        }
        double hereScale = PortalCatalogue.scaleOf(here, zone.definition);
        double thereScale = PortalCatalogue.scaleOf(t.leadsTo(), zone.definition);
        return new int[]{PortalScaling.scale(cell.getX(), hereScale, thereScale),
                PortalScaling.scale(cell.getZ(), hereScale, thereScale)};
    }

    private static TeleportTransition worldSpawn(MinecraftServer server, Entity entity,
                                                 TeleportTransition.PostTeleportTransition after) {
        ServerLevel overworld = server.overworld();
        BlockPos spawn = overworld.getRespawnData().pos();
        PortalTickets.hold(overworld, spawn.getX(), spawn.getZ());
        if (!PortalWorld.resident(overworld, spawn.getX(), spawn.getZ(), spawn.getX(), spawn.getZ())) {
            return hold(entity);
        }
        BlockPos land = new BlockPos(spawn.getX(), PortalWorld.surfaceY(overworld, spawn.getX(), spawn.getZ()), spawn.getZ());
        return transition(overworld, land, entity, after);
    }

    /** A dimension link: that dimension's anchor, its spawn, or an explicit position. */
    private static TeleportTransition dimensionLink(MinecraftServer server, Entity entity, ExitTarget link,
                                                    TeleportTransition.PostTeleportTransition after) {
        ResourceKey<Level> key = PortalRegistry.key(link.getDimensionId());
        Optional<ServerLevel> found = ready(server, key, entity);
        if (found.isEmpty()) {
            return hold(entity);
        }
        ServerLevel level = found.get();
        int[] pos = arrivalColumn(key, link);
        PortalTickets.hold(level, pos[0], pos[2]);
        if (!PortalWorld.resident(level, pos[0], pos[2], pos[0], pos[2])) {
            return hold(entity);
        }
        BlockPos land = new BlockPos(pos[0], PortalWorld.surfaceY(level, pos[0], pos[2]), pos[2]);
        return transition(level, land, entity, after);
    }

    private static TeleportTransition transition(ServerLevel target, BlockPos landing, Entity entity,
                                                 TeleportTransition.PostTeleportTransition after) {
        Vec3 at = new Vec3(landing.getX() + 0.5, landing.getY(), landing.getZ() + 0.5);
        return new TeleportTransition(target, at, entity.getDeltaMovement(), entity.getYRot(), entity.getXRot(), after);
    }
}
