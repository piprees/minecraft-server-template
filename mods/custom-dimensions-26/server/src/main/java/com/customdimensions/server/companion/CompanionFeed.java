package com.customdimensions.server.companion;

import com.customdimensions.core.companion.EntitySnapshot;
import com.customdimensions.core.companion.FeedBudget;
import com.customdimensions.core.companion.FeedPlan;
import com.customdimensions.core.companion.ViewPreference;
import com.customdimensions.server.CustomDimensions;
import com.customdimensions.server.level.LevelManager;
import com.customdimensions.server.portal.PortalRegistry;
import com.customdimensions.server.portal.PortalTickets;
import com.customdimensions.server.portal.PortalTravel;
import com.customdimensions.server.portal.PortalZone;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

/**
 * Streams the far side of the nearest portal to each companion standing at
 * one: its frame, its chunks nearest-first through the opening, and the
 * entities near the far portal. Everything is read from what is already
 * resident (TROUBLESHOOTING.md T126); the only loads are the arrival tickets
 * the pre-load holds, which {@code LevelManager} releases before it unloads
 * a level (T118). A lazy destination is opened only for a companion player
 * (RB-29), once per approach, and never held past the idle unload.
 */
public final class CompanionFeed {
    public static final String START_MARKER = "companion-feed:start";
    public static final String STOP_MARKER = "companion-feed:stop";
    public static final String PRELOAD_MARKER = "companion-feed:pre-load";

    /** Ticks between passes; also the entity snapshot cadence. */
    public static final int PASS_INTERVAL = EntitySnapshot.INTERVAL;
    /** How far from the nearest opening cell an eye may be, in blocks, for the portal to be fed. */
    public static final double RANGE = 16.0;
    /** Ticks between refreshes of the arrival ticket, inside its {@link PortalTickets#TIMEOUT_TICKS}. */
    static final int TICKET_REFRESH = 100;
    /** Nearest unsent chunks looked at per pass, so columns not yet resident never hold the queue. */
    static final int CANDIDATES = 64;

    /** One portal being fed to one viewer. */
    static final class Watch {
        final PortalZone zone;
        final ResourceKey<Level> target;
        CompanionPayloads.PortalFrame frame;
        List<EntitySnapshot.Seen> seen = List.of();
        long entityTick = Long.MIN_VALUE;
        long ticketTick = Long.MIN_VALUE;

        Watch(PortalZone zone, ResourceKey<Level> target) {
            this.zone = zone;
            this.target = target;
        }
    }

    /** One companion's feed state. */
    static final class Viewer {
        ServerPlayer player;
        Watch watch;
        final Map<ResourceKey<Level>, Set<Long>> sent = new HashMap<>();
        final Set<String> requested = new HashSet<>();
        final FeedBudget budget = FeedBudget.standard();
    }

    private static final Map<UUID, Viewer> VIEWERS = new ConcurrentHashMap<>();
    private static long handovers;

    private CompanionFeed() {
    }

    static void register() {
        ServerTickEvents.END_SERVER_TICK.register(CompanionFeed::tick);
        LevelManager.onClose(CompanionFeed::onClose);
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, from, to) -> {
            Viewer v = VIEWERS.get(player.getUUID());
            if (v != null) {
                v.watch = null;
                v.sent.clear();
            }
        });
        ServerEntityLevelChangeEvents.AFTER_ENTITY_CHANGE_LEVEL.register(CompanionFeed::handover);
    }

    static void clear() {
        VIEWERS.clear();
    }

    static void forget(UUID id) {
        VIEWERS.remove(id);
    }

    // --- Queries for the self-test -----------------------------------------------------------

    /** The aperture origin this viewer is being fed, or null. */
    public static BlockPos watching(UUID id) {
        Viewer v = VIEWERS.get(id);
        return v == null || v.watch == null || v.watch.frame == null ? null : v.watch.frame.apertureOrigin();
    }

    public static int sentCount(UUID id, ResourceKey<Level> destination) {
        Viewer v = VIEWERS.get(id);
        return v == null ? 0 : v.sent.getOrDefault(destination, Set.of()).size();
    }

    public static long handovers() {
        return handovers;
    }

    // --- The pass ----------------------------------------------------------------------------

    private static void tick(MinecraftServer server) {
        if (CompanionNetwork.companions().isEmpty()) {
            return;
        }
        long tick = server.getTickCount();
        if (tick % PASS_INTERVAL != 0) {
            return;
        }
        for (Map.Entry<UUID, CompanionNetwork.Companion> e : CompanionNetwork.companions().entrySet()) {
            ServerPlayer live = server.getPlayerList().getPlayer(e.getKey());
            ServerPlayer player = live != null ? live : e.getValue().player();
            if (player == null || player.isRemoved()) {
                continue;
            }
            Viewer viewer = VIEWERS.computeIfAbsent(e.getKey(), k -> new Viewer());
            viewer.player = player;
            try {
                pass(server, player, viewer, e.getValue().view(), tick);
            } catch (RuntimeException ex) {
                CustomDimensions.LOG.warn("companion feed for {} failed", player.getName().getString(), ex);
                viewer.watch = null;
            }
        }
    }

    static void pass(MinecraftServer server, ServerPlayer player, Viewer viewer, ViewPreference view, long tick) {
        ServerLevel here = player.level();
        PortalZone zone = view.feeds() ? nearest(here, player.getEyePosition()) : null;
        Watch w = viewer.watch;
        if (w != null && (w.zone != zone || !PortalRegistry.get().contains(w.zone))) {
            stop(viewer, !view.feeds() ? "view declined" : !PortalRegistry.get().contains(w.zone) ? "portal broken"
                    : zone == null ? "out of range" : "nearer portal");
            w = null;
        }
        if (zone == null) {
            viewer.requested.clear();
            return;
        }
        ServerLevel target = openTarget(server, zone, player, viewer);
        if (target == null) {
            if (w != null) {
                stop(viewer, "destination not open");
            }
            return;
        }
        if (w == null) {
            w = new Watch(zone, target.dimension());
            viewer.watch = w;
        }
        viewer.budget.startPass(tick);
        int[] column = PortalTravel.farColumn(zone);
        if (column == null) {
            return;
        }
        if (w.ticketTick == Long.MIN_VALUE || tick - w.ticketTick >= TICKET_REFRESH) {
            PortalTickets.hold(target, column[0], column[1]);
            w.ticketTick = tick;
        }
        BlockPos far = farLanding(zone, target, column);
        if (far == null) {
            return;
        }
        Sight sight = sight(zone, player.getEyePosition());
        BlockPos near = zone.landingCell();
        int dx = far.getX() - near.getX();
        int dy = far.getY() - near.getY();
        int dz = far.getZ() - near.getZ();
        CompanionPayloads.PortalFrame frame = frame(zone, target, sight.normal(), dx, dy, dz);
        if (frame == null) {
            return;
        }
        if (!frame.equals(w.frame)) {
            if (CompanionNetwork.send(player, frame)) {
                viewer.budget.charge(CompanionNetwork.size(frame, CompanionPayloads.PortalFrame.CODEC,
                        server.registryAccess()));
                if (w.frame == null) {
                    CustomDimensions.LOG.info("{} player={} portal={} -> {} offset=({}, {}, {})", START_MARKER,
                            player.getName().getString(), frame.apertureOrigin().toShortString(),
                            target.dimension().identifier(), dx, dy, dz);
                }
                w.frame = frame;
            }
        }
        feedChunks(server, player, viewer, target, zone, sight, dx, dz, view);
        feedEntities(server, player, viewer, w, target, far, tick);
    }

    /** The nearest lit opening in {@code level} within {@link #RANGE} of the eye. */
    static PortalZone nearest(ServerLevel level, Vec3 eye) {
        PortalRegistry registry = PortalRegistry.get();
        PortalZone best = null;
        double bestSq = RANGE * RANGE;
        for (PortalZone zone : registry.zones(level.dimension())) {
            if (!registry.contains(zone)) {
                continue;
            }
            for (BlockPos p : zone.interior) {
                double d = eye.distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
                if (d <= bestSq) {
                    bestSq = d;
                    best = zone;
                }
            }
        }
        return best;
    }

    /** The open level behind the zone; a lazy one is asked open once per approach, for this player only. */
    private static ServerLevel openTarget(MinecraftServer server, PortalZone zone, ServerPlayer player, Viewer viewer) {
        Optional<LevelManager> manager = LevelManager.get();
        Optional<String> slug = manager.flatMap(m -> m.slugOf(zone.target));
        if (slug.isEmpty()) {
            return server.getLevel(zone.target);
        }
        LevelManager m = manager.get();
        LevelManager.State state = m.state(slug.get());
        if (state == LevelManager.State.ACTIVE) {
            return m.level(slug.get()).orElse(null);
        }
        String key = approachKey(zone);
        if (state == LevelManager.State.LAZY && viewer.requested.add(key)) {
            CustomDimensions.LOG.info("{} player={} destination={}", PRELOAD_MARKER, player.getName().getString(),
                    slug.get());
            m.activate(slug.get()).whenComplete((a, error) -> {
                if (error != null) {
                    CustomDimensions.LOG.warn("companion pre-load of {} did not open: {}", slug.get(), error.toString());
                }
            });
        }
        return null;
    }

    /** One portal approached: the pre-load asks its destination open at most once per key. */
    private static String approachKey(PortalZone zone) {
        return zone.world.identifier() + "|" + zone.landingCell().toShortString();
    }

    /** The far opening's landing cell, or the arrival column's resident surface before one is built. */
    private static BlockPos farLanding(PortalZone zone, ServerLevel target, int[] column) {
        PortalZone farZone = PortalTravel.farZone(zone, column);
        if (farZone != null) {
            return farZone.landingCell();
        }
        LevelChunk chunk = target.getChunkSource().getChunkNow(column[0] >> 4, column[1] >> 4);
        if (chunk == null) {
            return null;
        }
        int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, column[0] & 15, column[1] & 15) + 1;
        return new BlockPos(column[0], y, column[1]);
    }

    /** One opening from one eye: the far side's normal and the horizontal wedge through the opening. */
    record Sight(Direction normal, FeedPlan.Sight plan, int minX, int minZ) {
    }

    static Sight sight(PortalZone zone, Vec3 eye) {
        Direction.Axis normalAxis = switch (zone.axis) {
            case X -> Direction.Axis.Z;
            case Z -> Direction.Axis.X;
            case Y -> Direction.Axis.Y;
        };
        Direction.Axis axisA = normalAxis == Direction.Axis.Z ? Direction.Axis.X : Direction.Axis.Z;
        double plane = zone.interior.iterator().next().get(normalAxis);
        double eyeN = eye.get(normalAxis);
        boolean towardsHigh = eyeN < plane + 0.5;
        double a0 = Double.MAX_VALUE;
        double a1 = -Double.MAX_VALUE;
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        for (BlockPos p : zone.interior) {
            a0 = Math.min(a0, p.get(axisA));
            a1 = Math.max(a1, p.get(axisA) + 1.0);
            minX = Math.min(minX, p.getX());
            minZ = Math.min(minZ, p.getZ());
        }
        FeedPlan.Normal n = switch (normalAxis) {
            case X -> FeedPlan.Normal.X;
            case Y -> FeedPlan.Normal.Y;
            case Z -> FeedPlan.Normal.Z;
        };
        Direction normal = Direction.fromAxisAndDirection(normalAxis,
                towardsHigh ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE);
        return new Sight(normal, new FeedPlan.Sight(eye.get(axisA), eyeN, a0, a1, FeedPlan.surface(plane, towardsHigh),
                0, 0, n, towardsHigh), minX, minZ);
    }

    /** The geometry-only description of one opening; null when the destination's type has no registry key. */
    static CompanionPayloads.PortalFrame frame(PortalZone zone, ServerLevel target, Direction normal,
                                               int dx, int dy, int dz) {
        Optional<Identifier> type = target.dimensionTypeRegistration().unwrapKey().map(ResourceKey::identifier);
        if (type.isEmpty()) {
            return null;
        }
        List<BlockPos> aperture = new ArrayList<>(zone.interior);
        aperture.sort(Comparator.<BlockPos>naturalOrder());
        return new CompanionPayloads.PortalFrame(target.dimension().identifier(), type.get(), aperture.get(0),
                aperture, zone.axis.ordinal(), normal.ordinal(), dx, dy, dz,
                colour(target, true), colour(target, false));
    }

    /** The destination's dimension-wide sky or fog colour as RGB, -1 when it has none. */
    static int colour(ServerLevel level, boolean sky) {
        try {
            Vector3fc c = level.environmentAttributes().getDimensionValue(
                    sky ? EnvironmentAttributes.SKY_COLOR : EnvironmentAttributes.FOG_COLOR);
            if (c == null) {
                return -1;
            }
            return (channel(c.x()) << 16) | (channel(c.y()) << 8) | channel(c.z());
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static int channel(float v) {
        return Math.max(0, Math.min(255, Math.round(v * 255.0f)));
    }

    private static void feedChunks(MinecraftServer server, ServerPlayer player, Viewer viewer, ServerLevel target,
                                   PortalZone zone, Sight sight, int dx, int dz, ViewPreference view) {
        FeedPlan.Sight s = sight.plan();
        FeedPlan.Sight mapped = new FeedPlan.Sight(s.eyeA(), s.eyeN(), s.a0(), s.a1(), s.planeN(), dx, dz,
                s.normal(), s.towardsHigh());
        Set<Long> sent = viewer.sent.computeIfAbsent(target.dimension(), k -> new HashSet<>());
        List<Long> wanted = FeedPlan.nextChunks((sight.minX() + dx) >> 4, (sight.minZ() + dz) >> 4,
                view.maxRenderDistance(), mapped, FeedPlan.coreDepth(view.viewDepth()), sent, CANDIDATES);
        Identifier id = target.dimension().identifier();
        for (long key : wanted) {
            LevelChunk chunk = target.getChunkSource().getChunkNow(FeedPlan.chunkX(key), FeedPlan.chunkZ(key));
            if (chunk == null) {
                continue;
            }
            CompanionPayloads.DestinationChunk payload = new CompanionPayloads.DestinationChunk(id,
                    new ClientboundLevelChunkWithLightPacket(chunk, target.getLightEngine(), null, null));
            int size = CompanionNetwork.size(payload, CompanionPayloads.DestinationChunk.CODEC, server.registryAccess());
            if (!viewer.budget.trySpendChunk(size)) {
                break;
            }
            if (CompanionNetwork.send(player, payload)) {
                sent.add(key);
            }
        }
    }

    private static void feedEntities(MinecraftServer server, ServerPlayer player, Viewer viewer, Watch w,
                                     ServerLevel target, BlockPos far, long tick) {
        if (!EntitySnapshot.due(tick, w.entityTick, EntitySnapshot.INTERVAL)) {
            return;
        }
        double cx = far.getX() + 0.5;
        double cy = far.getY();
        double cz = far.getZ() + 0.5;
        AABB box = new AABB(cx - EntitySnapshot.RADIUS, cy - EntitySnapshot.HEIGHT, cz - EntitySnapshot.RADIUS,
                cx + EntitySnapshot.RADIUS, cy + EntitySnapshot.HEIGHT, cz + EntitySnapshot.RADIUS);
        Map<Integer, Entity> byId = new HashMap<>();
        List<EntitySnapshot.Seen> candidates = new ArrayList<>();
        for (Entity e : target.getEntities((Entity) null, box, e -> e.isAlive() && !e.isSpectator())) {
            byId.put(e.getId(), e);
            candidates.add(new EntitySnapshot.Seen(e.getId(), e.getX(), e.getY(), e.getZ(),
                    e.getYRot(), e.getXRot(), e.getYHeadRot()));
        }
        List<EntitySnapshot.Seen> picked = EntitySnapshot.select(candidates, cx, cy, cz,
                EntitySnapshot.RADIUS, EntitySnapshot.HEIGHT, EntitySnapshot.MAX_ENTITIES);
        int[] departed = EntitySnapshot.departed(w.seen, picked);
        boolean first = w.entityTick == Long.MIN_VALUE;
        boolean moved = EntitySnapshot.changed(w.seen, picked);
        w.seen = List.copyOf(picked);
        w.entityTick = tick;
        if (!first && !moved && departed.length == 0) {
            return;
        }
        List<ClientboundAddEntityPacket> present = new ArrayList<>();
        List<ClientboundSetEntityDataPacket> tracked = new ArrayList<>();
        for (EntitySnapshot.Seen s : picked) {
            Entity e = byId.get(s.id());
            present.add(addPacket(e));
            ClientboundSetEntityDataPacket data = dataPacket(e);
            if (data != null) {
                tracked.add(data);
            }
        }
        CompanionPayloads.DestinationEntities payload = new CompanionPayloads.DestinationEntities(
                target.dimension().identifier(), present, tracked, departed);
        if (CompanionNetwork.send(player, payload)) {
            viewer.budget.charge(CompanionNetwork.size(payload, CompanionPayloads.DestinationEntities.CODEC,
                    server.registryAccess()));
        }
    }

    /** Vanilla's spawn packet for one entity; the data field rides as 0, which no mob reads. */
    static ClientboundAddEntityPacket addPacket(Entity e) {
        return new ClientboundAddEntityPacket(e.getId(), e.getUUID(), e.getX(), e.getY(), e.getZ(), e.getXRot(),
                e.getYRot(), e.getType(), 0, e.getDeltaMovement(), e.getYHeadRot());
    }

    /** Non-default synched data, never the dirty list vanilla's own tracker consumes. */
    static ClientboundSetEntityDataPacket dataPacket(Entity e) {
        List<SynchedEntityData.DataValue<?>> values = e.getEntityData().getNonDefaultValues();
        return values == null || values.isEmpty() ? null : new ClientboundSetEntityDataPacket(e.getId(), values);
    }

    /** Stops one viewer's watch, telling its client to drop the frame and what it showed. */
    private static void stop(Viewer viewer, String reason) {
        Watch w = viewer.watch;
        viewer.watch = null;
        if (w == null || w.frame == null) {
            return;
        }
        CompanionPayloads.ProjectionClear clear = new CompanionPayloads.ProjectionClear(w.frame.apertureOrigin());
        if (CompanionNetwork.send(viewer.player, clear)) {
            viewer.budget.charge(CompanionNetwork.size(clear, CompanionPayloads.ProjectionClear.CODEC,
                    viewer.player.level().registryAccess()));
        }
        CustomDimensions.LOG.info("{} player={} portal={} reason={}", STOP_MARKER,
                viewer.player == null ? "?" : viewer.player.getName().getString(),
                w.frame.apertureOrigin().toShortString(), reason);
    }

    /** A level about to unload: every watch into it stops, and what was sent from it is forgotten. */
    private static void onClose(ServerLevel level) {
        for (Viewer v : VIEWERS.values()) {
            if (v.watch != null && v.watch.target.equals(level.dimension())) {
                v.requested.add(approachKey(v.watch.zone));
                stop(v, "destination unloaded");
            }
            v.sent.remove(level.dimension());
        }
    }

    /** A block changed: every viewer is sent that chunk again on its next pass. */
    public static void invalidate(ResourceKey<Level> level, int chunkX, int chunkZ) {
        if (VIEWERS.isEmpty()) {
            return;
        }
        long key = FeedPlan.chunkKey(chunkX, chunkZ);
        for (Viewer v : VIEWERS.values()) {
            Set<Long> sent = v.sent.get(level);
            if (sent != null) {
                sent.remove(key);
            }
        }
    }

    /** A non-player crossed into a destination a viewer is drawing: name the new id at once. */
    private static void handover(Entity original, Entity arrived, ServerLevel from, ServerLevel to) {
        if (VIEWERS.isEmpty() || arrived instanceof ServerPlayer) {
            return;
        }
        CompanionPayloads.EntityHandover payload = null;
        for (Viewer v : VIEWERS.values()) {
            Watch w = v.watch;
            if (w == null || w.entityTick == Long.MIN_VALUE || !w.target.equals(to.dimension())) {
                continue;
            }
            if (payload == null) {
                ClientboundSetEntityDataPacket data = dataPacket(arrived);
                payload = new CompanionPayloads.EntityHandover(to.dimension().identifier(), original.getId(),
                        to.getGameTime(), addPacket(arrived), data == null ? List.of() : List.of(data));
            }
            if (CompanionNetwork.send(v.player, payload)) {
                handovers++;
            }
        }
    }
}
