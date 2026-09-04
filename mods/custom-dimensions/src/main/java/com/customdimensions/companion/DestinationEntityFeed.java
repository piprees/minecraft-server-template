package com.customdimensions.companion;

import com.customdimensions.MultiverseServer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which of a destination's entities a client drawing the far side is told
 * about, how often, and when nothing needs saying.
 *
 * <h2>Bounded three ways</h2>
 * A disc round the arrival ({@link #RADIUS} horizontally, {@link #HEIGHT} each
 * way vertically), nearest-first, capped at {@link #MAX_ENTITIES}. A portal
 * frames a place: a mob two hundred blocks past the opening subtends less than
 * a pixel, and sending it costs the same as sending the one standing in the
 * doorway.
 *
 * <h2>Silent while nothing moves</h2>
 * {@link #changed} compares the new snapshot against the last one sent at the
 * resolution the wire carries, so a still scene sends nothing however many
 * passes run over it. {@link #due} holds the cadence to {@link #INTERVAL} even
 * when the projection pass runs more often.
 *
 * <h2>Resident entities only</h2>
 * {@code World.getOtherEntities} answers from the in-memory entity lookup, so
 * an entity in an unloaded chunk does not exist to ask about and nothing here
 * loads or generates one (mods/AGENTS.md, Rule 1).
 */
public final class DestinationEntityFeed {

    /** Ticks between snapshots. Four is the projection's own base interval. */
    public static final int INTERVAL = 4;

    /** Horizontal radius round the arrival, in blocks. */
    public static final double RADIUS = 48.0;

    /** Vertical reach each way from the arrival, in blocks. */
    public static final double HEIGHT = 32.0;

    /** Entities in one snapshot, however many stand in the disc. */
    public static final int MAX_ENTITIES = 24;

    /**
     * Position quantum for {@link #changed}, in blocks. Vanilla's own relative
     * move packet carries 1/4096 of a block; anything finer than this is below
     * what a viewer can see through an opening.
     */
    public static final double POSITION_QUANTUM = 1.0 / 256.0;

    /** Angle quantum for {@link #changed}, in degrees — vanilla's byte angle. */
    public static final double ANGLE_QUANTUM = 360.0 / 256.0;

    private DestinationEntityFeed() {}

    /**
     * One entity as the feed sees it: an identity and a pose, in DESTINATION
     * world coordinates. The destination client world holds destination
     * coordinates, so nothing here is transformed.
     */
    public record Seen(int id, double x, double y, double z,
            float yaw, float pitch, float headYaw) {}

    /**
     * Whether this pass owes the client a snapshot at all. {@link Long#MIN_VALUE}
     * is "never fed" and is due now — subtracting it would overflow.
     */
    public static boolean due(long tick, long lastTick, int interval) {
        return lastTick == Long.MIN_VALUE || tick - lastTick >= Math.max(1, interval);
    }

    /**
     * The entities to send, nearest to the arrival first and capped. Filters
     * the disc out of the box the world lookup answered with.
     */
    public static List<Seen> select(List<Seen> candidates, double centreX, double centreY,
            double centreZ, double radius, double height, int cap) {
        List<Seen> picked = new ArrayList<>();
        if (candidates == null || cap <= 0) {
            return picked;
        }
        double radiusSq = radius * radius;
        List<Seen> inside = new ArrayList<>();
        List<Double> distances = new ArrayList<>();
        for (Seen one : candidates) {
            double dx = one.x() - centreX;
            double dz = one.z() - centreZ;
            double horizontalSq = dx * dx + dz * dz;
            if (horizontalSq > radiusSq || Math.abs(one.y() - centreY) > height) {
                continue;
            }
            inside.add(one);
            double dy = one.y() - centreY;
            distances.add(horizontalSq + dy * dy);
        }
        Integer[] order = new Integer[inside.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (left, right) -> {
            int byDistance = Double.compare(distances.get(left), distances.get(right));
            return byDistance != 0 ? byDistance
                    : Integer.compare(inside.get(left).id(), inside.get(right).id());
        });
        for (int i = 0; i < order.length && picked.size() < cap; i++) {
            picked.add(inside.get(order[i]));
        }
        return picked;
    }

    /** Whether the new snapshot differs from the last at wire resolution. */
    public static boolean changed(List<Seen> previous, List<Seen> current) {
        if (previous == null || current == null || previous.size() != current.size()) {
            return true;
        }
        for (int i = 0; i < current.size(); i++) {
            if (!samePose(previous.get(i), current.get(i))) {
                return true;
            }
        }
        return false;
    }

    /** Ids the client holds that this snapshot no longer carries. */
    public static int[] departed(List<Seen> previous, List<Seen> current) {
        if (previous == null || previous.isEmpty()) {
            return new int[0];
        }
        Set<Integer> stillHere = idsOf(current == null ? List.of() : current);
        List<Integer> gone = new ArrayList<>();
        for (Integer id : idsOf(previous)) {
            if (!stillHere.contains(id)) {
                gone.add(id);
            }
        }
        int[] out = new int[gone.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = gone.get(i);
        }
        return out;
    }

    private static long quantise(double value, double quantum) {
        return Math.round(value / quantum);
    }

    private static boolean samePose(Seen a, Seen b) {
        return a.id() == b.id()
                && quantise(a.x(), POSITION_QUANTUM) == quantise(b.x(), POSITION_QUANTUM)
                && quantise(a.y(), POSITION_QUANTUM) == quantise(b.y(), POSITION_QUANTUM)
                && quantise(a.z(), POSITION_QUANTUM) == quantise(b.z(), POSITION_QUANTUM)
                && quantise(a.yaw(), ANGLE_QUANTUM) == quantise(b.yaw(), ANGLE_QUANTUM)
                && quantise(a.pitch(), ANGLE_QUANTUM) == quantise(b.pitch(), ANGLE_QUANTUM)
                && quantise(a.headYaw(), ANGLE_QUANTUM) == quantise(b.headYaw(), ANGLE_QUANTUM);
    }

    private static Set<Integer> idsOf(List<Seen> seen) {
        Set<Integer> ids = new LinkedHashSet<>();
        for (Seen one : seen) {
            ids.add(one.id());
        }
        return ids;
    }

    private static List<Seen> copy(List<Seen> seen) {
        return new ArrayList<>(seen);
    }

    /** Grepped in the server log for what one destination's entities did. */
    public static final String FEED_MARKER = "companion-send:destination-entities";

    /** Per player, per destination: the last snapshot sent and when. */
    private static final Map<UUID, Map<Identifier, Snapshot>> SENT = new ConcurrentHashMap<>();

    private record Snapshot(long tick, List<Seen> seen) {}

    /** Drops every record for a player (disconnect, world change, zone gone). */
    public static void forget(UUID playerId) {
        SENT.remove(playerId);
    }

    /**
     * Drops one destination's record for one player, so the next pass sends a
     * whole snapshot. Every teardown does this: a client that dropped its copy
     * of the destination while the server still held a record of what it has
     * would be sent deltas against entities it no longer holds.
     */
    public static void forget(UUID playerId, Identifier destination) {
        Map<Identifier, Snapshot> perDestination = SENT.get(playerId);
        if (perDestination != null) {
            perDestination.remove(destination);
        }
    }

    /** Drops every record (server shutdown). */
    public static void clear() {
        SENT.clear();
    }

    /** How many entities this client was last told about for this destination. */
    public static int sentCount(UUID playerId, Identifier destination) {
        Snapshot held = held(playerId, destination);
        return held == null ? 0 : held.seen().size();
    }

    private static Snapshot held(UUID playerId, Identifier destination) {
        Map<Identifier, Snapshot> perDestination = SENT.get(playerId);
        return perDestination == null ? null : perDestination.get(destination);
    }

    /**
     * Sends one destination's nearby entities to one viewer, and answers how
     * many went. Zero means either the cadence held or nothing has moved since
     * the last snapshot — both of which are the point.
     */
    public static int feed(ServerPlayerEntity player, ServerWorld destination,
            double centreX, double centreY, double centreZ, long tick) {
        if (player == null || destination == null) {
            return 0;
        }
        Identifier key = destination.getRegistryKey().getValue();
        Snapshot last = held(player.getUuid(), key);
        if (!due(tick, last == null ? Long.MIN_VALUE : last.tick(), INTERVAL)) {
            return 0;
        }
        Box box = new Box(centreX - RADIUS, centreY - HEIGHT, centreZ - RADIUS,
                centreX + RADIUS, centreY + HEIGHT, centreZ + RADIUS);
        List<Entity> found = destination.getOtherEntities(null, box,
                DestinationEntityFeed::renderable);
        Map<Integer, Entity> byId = new HashMap<>();
        List<Seen> candidates = new ArrayList<>(found.size());
        for (Entity entity : found) {
            byId.put(entity.getId(), entity);
            candidates.add(new Seen(entity.getId(), entity.getX(), entity.getY(), entity.getZ(),
                    entity.getYaw(), entity.getPitch(), entity.getHeadYaw()));
        }
        List<Seen> picked = select(candidates, centreX, centreY, centreZ,
                RADIUS, HEIGHT, MAX_ENTITIES);
        List<Seen> previous = last == null ? List.of() : last.seen();
        int[] departed = departed(previous, picked);
        boolean moved = changed(previous, picked);
        SENT.computeIfAbsent(player.getUuid(), id -> new ConcurrentHashMap<Identifier, Snapshot>())
                .put(key, new Snapshot(tick, copy(picked)));
        if (!moved && departed.length == 0) {
            return 0;
        }
        List<EntitySpawnS2CPacket> present = new ArrayList<>(picked.size());
        List<EntityTrackerUpdateS2CPacket> tracked = new ArrayList<>(picked.size());
        for (Seen one : picked) {
            Entity entity = byId.get(one.id());
            if (entity == null) {
                continue;
            }
            present.add(spawnPacket(entity));
            // getChangedEntries, never getDirtyEntries: the dirty list is
            // consumed by vanilla's own tracker for players actually in that
            // dimension, and reading it here would steal their update.
            List<DataTracker.SerializedEntry<?>> entries =
                    entity.getDataTracker().getChangedEntries();
            if (entries != null && !entries.isEmpty()) {
                tracked.add(new EntityTrackerUpdateS2CPacket(entity.getId(), entries));
            }
        }
        ServerPlayNetworking.send(player,
                new CompanionPayloads.DestinationEntities(key, present, tracked, departed));
        MultiverseServer.LOGGER.debug("{} player={} dimension={} present={} tracked={} departed={} seen={}",
                FEED_MARKER, player.getNameForScoreboard(), key,
                present.size(), tracked.size(), departed.length, candidates.size());
        return present.size();
    }

    /**
     * What a viewer could see: alive, not a spectator, and not the destination
     * world's own weather or marker entities, which have no renderer worth the
     * bandwidth.
     */
    private static boolean renderable(Entity entity) {
        return entity != null && entity.isAlive() && !entity.isSpectator();
    }

    /**
     * One entity as vanilla's own spawn packet. {@code entityData} is 0: the
     * value lives on an {@code EntityTrackerEntry} the feed has no access to,
     * and no mob or player reads it.
     */
    private static EntitySpawnS2CPacket spawnPacket(Entity entity) {
        return new EntitySpawnS2CPacket(
                entity.getId(), entity.getUuid(),
                entity.getX(), entity.getY(), entity.getZ(),
                entity.getPitch(), entity.getYaw(),
                entity.getType(), 0, entity.getVelocity(), entity.getHeadYaw());
    }
}
