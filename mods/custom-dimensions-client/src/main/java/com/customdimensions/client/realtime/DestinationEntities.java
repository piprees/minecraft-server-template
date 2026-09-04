package com.customdimensions.client.realtime;

import com.customdimensions.client.CustomDimensionsClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The entities each destination world holds, put there by the server's
 * snapshot feed.
 *
 * <h2>Why the second world needs its own tick</h2>
 * Nothing drives a world the player is not standing in. Vanilla's own
 * interpolation — the lerp that turns a snapshot every few ticks into
 * continuous movement — is spent inside {@code Entity.tick}, and
 * {@code resetPosition} is what stops the renderer smearing an entity across
 * the gap between its last drawn position and its current one. So every
 * managed entity is ticked once per client tick through vanilla's own
 * {@code ClientWorld.tickEntity}, which is what Immersive Portals does for the
 * same reason. Block entities and ambient world ticking are deliberately not
 * driven: the cost is bounded by the entity cap, not by the fed area.
 *
 * <h2>A snapshot, not a stream</h2>
 * The payload names everyone standing near the arrival, so a spawn and a move
 * arrive as the same message and only {@link EntityFeedPlan} tells them apart.
 * A player is the one type {@code EntityType.create} cannot build; it needs the
 * shared player list, which both worlds already share through one connection.
 */
public final class DestinationEntities {

    /** Grepped in the client log for what one destination's entities did. */
    public static final String RECEIVE_MARKER = "companion-client:destination-entities";

    /**
     * Ticks a lerp is spread over, matching the server's own snapshot
     * interval. Shorter and the entity arrives early and stops; longer and it
     * is still moving when the next snapshot overrides it.
     */
    public static final int INTERPOLATION_STEPS = 4;

    /** Per destination, the entity ids this client put into that world. */
    private static final Map<Identifier, Set<Integer>> HELD = new ConcurrentHashMap<>();

    /** What each held id was when it went in, for the line that says it left. */
    private static final Map<Integer, String> PLACED = new ConcurrentHashMap<>();

    private DestinationEntities() {}

    public static int count(Identifier destination) {
        Set<Integer> held = destination == null ? null : HELD.get(destination);
        return held == null ? 0 : held.size();
    }

    public static int total() {
        int sum = 0;
        for (Set<Integer> held : HELD.values()) {
            sum += held.size();
        }
        return sum;
    }

    /**
     * Applies one snapshot to its destination world. Silent when no world is
     * standing for it — the chunks have not arrived either, and the next
     * snapshot four ticks later carries the same entities.
     */
    public static void accept(MinecraftClient client, Identifier destination,
            List<EntitySpawnS2CPacket> present, List<EntityTrackerUpdateS2CPacket> tracked,
            int[] departed) {
        ClientWorld world = DestinationWorlds.get(destination);
        if (world == null || present == null) {
            return;
        }
        Map<Integer, EntitySpawnS2CPacket> byId = new java.util.HashMap<>();
        List<Integer> ids = new ArrayList<>(present.size());
        for (EntitySpawnS2CPacket packet : present) {
            byId.put(packet.getEntityId(), packet);
            ids.add(packet.getEntityId());
        }
        Map<Integer, EntityTrackerUpdateS2CPacket> data = new java.util.HashMap<>();
        if (tracked != null) {
            for (EntityTrackerUpdateS2CPacket packet : tracked) {
                data.put(packet.id(), packet);
            }
        }
        Live sink = new Live(client, world, byId, data);
        Set<Integer> after = EntityFeedPlan.apply(
                HELD.getOrDefault(destination, Set.of()), ids, departed, sink);
        // An entity the client could not build is not held: a player whose
        // list entry has not arrived yet is retried on the next snapshot.
        after.removeAll(sink.refused);
        HELD.put(destination, new LinkedHashSet<>(after));
        if (sink.spawned > 0 || sink.removed > 0 || !sink.refused.isEmpty()) {
            CustomDimensionsClient.LOGGER.info(
                    "{} dimension={} spawned={} moved={} removed={} refused={} holding={} why={}",
                    RECEIVE_MARKER, destination, sink.spawned, sink.moved, sink.removed,
                    sink.refused.size(), after.size(), sink.reasons);
        }
    }

    /**
     * One client tick of every destination's entities, so vanilla's own
     * interpolation runs and the renderer has a previous position to draw
     * from. Never throws: a destination that fails is dropped, not repeated.
     */
    public static void tick(MinecraftClient client) {
        if (HELD.isEmpty()) {
            return;
        }
        for (Map.Entry<Identifier, Set<Integer>> entry : HELD.entrySet()) {
            ClientWorld world = DestinationWorlds.get(entry.getKey());
            if (world == null) {
                continue;
            }
            for (Integer id : List.copyOf(entry.getValue())) {
                Entity entity = world.getEntityById(id);
                if (entity == null) {
                    // Held but gone from the world. Stop claiming it, so the
                    // count is what is drawable and the next snapshot builds
                    // it again rather than sending a move nobody can apply.
                    entry.getValue().remove(id);
                    CustomDimensionsClient.LOGGER.info("{} dimension={} entity={} left the world ({})",
                            RECEIVE_MARKER, entry.getKey(), id,
                            PLACED.remove(id));
                    continue;
                }
                if (entity.isRemoved() || entity.hasVehicle()) {
                    continue;
                }
                try {
                    world.tickEntity(entity);
                } catch (Throwable failure) {
                    entry.getValue().remove(id);
                    world.removeEntity(id, Entity.RemovalReason.DISCARDED);
                    CustomDimensionsClient.LOGGER.error("{} dimension={} entity={} dropped after a throw",
                            RECEIVE_MARKER, entry.getKey(), id, failure);
                }
            }
        }
    }

    /** Drops one destination's entities from its world and from the record. */
    public static void drop(Identifier destination) {
        if (destination == null) {
            return;
        }
        Set<Integer> held = HELD.remove(destination);
        ClientWorld world = DestinationWorlds.get(destination);
        if (held == null || world == null) {
            return;
        }
        for (Integer id : held) {
            world.removeEntity(id, Entity.RemovalReason.DISCARDED);
            PLACED.remove(id);
        }
    }

    /** Drops every record (world change, disconnect). */
    public static void clear() {
        for (Identifier destination : List.copyOf(HELD.keySet())) {
            drop(destination);
        }
        HELD.clear();
        PLACED.clear();
    }

    /**
     * What one destination's world actually holds, as {@code id type x,y,z}.
     * Read off the world rather than the record, so an entity that was added
     * and then vanished reads differently from one that was never built.
     */
    public static List<String> listing(Identifier destination) {
        ClientWorld world = DestinationWorlds.get(destination);
        List<String> out = new ArrayList<>();
        if (world == null) {
            return out;
        }
        for (Entity entity : world.getEntities()) {
            out.add(String.format("%d %s %.1f,%.1f,%.1f", entity.getId(),
                    entity.getType().getUntranslatedName(),
                    entity.getX(), entity.getY(), entity.getZ()));
        }
        return out;
    }

    /** Ids the record says this client put into one destination. */
    public static Set<Integer> heldIds(Identifier destination) {
        Set<Integer> held = destination == null ? null : HELD.get(destination);
        return held == null ? Set.of() : new LinkedHashSet<>(held);
    }

    /** Test seam and diagnostics: every destination held, with its entity count. */
    public static Map<Identifier, Integer> counts() {
        Map<Identifier, Integer> out = new java.util.HashMap<>();
        HELD.forEach((destination, held) -> out.put(destination, held.size()));
        return out;
    }

    /** The live half of {@link EntityFeedPlan.Sink}, against one ClientWorld. */
    private static final class Live implements EntityFeedPlan.Sink {

        private final MinecraftClient client;
        private final ClientWorld world;
        private final Map<Integer, EntitySpawnS2CPacket> byId;
        private final Map<Integer, EntityTrackerUpdateS2CPacket> data;
        private final Set<Integer> refused = new LinkedHashSet<>();
        private final List<String> reasons = new ArrayList<>();

        private int spawned;
        private int moved;
        private int removed;

        private Live(MinecraftClient client, ClientWorld world,
                Map<Integer, EntitySpawnS2CPacket> byId,
                Map<Integer, EntityTrackerUpdateS2CPacket> data) {
            this.client = client;
            this.world = world;
            this.byId = byId;
            this.data = data;
        }

        /**
         * The entity's tracked data, before its first tick. Ordering is
         * load-bearing: {@code ItemEntity.tick} answers an empty stack with
         * {@code discard()}, so an item that ticks before its stack arrives
         * deletes itself and no later snapshot brings it back.
         */
        private void applyTracked(Entity entity, int id) {
            EntityTrackerUpdateS2CPacket packet = this.data.get(id);
            if (packet != null) {
                entity.getDataTracker().writeUpdatedEntries(packet.trackedValues());
            }
        }

        @Override
        public void spawn(int id) {
            EntitySpawnS2CPacket packet = this.byId.get(id);
            if (packet == null) {
                refuse(id, "no packet");
                return;
            }
            Entity entity = create(packet);
            if (entity == null) {
                refuse(id, "cannot build " + packet.getEntityType());
                return;
            }
            // Vanilla's own spawn application: position, both angles, the id,
            // the uuid and the velocity, with lastRender set so the first
            // frame draws it where it is rather than lerping it in from zero.
            entity.onSpawnPacket(packet);
            applyTracked(entity, id);
            puppet(entity);
            this.world.addEntity(entity);
            PLACED.put(id, String.format("%s at %.1f,%.1f,%.1f",
                    entity.getType().getUntranslatedName(),
                    entity.getX(), entity.getY(), entity.getZ()));
            this.spawned++;
        }

        @Override
        public void move(int id) {
            EntitySpawnS2CPacket packet = this.byId.get(id);
            Entity entity = this.world.getEntityById(id);
            if (packet == null || entity == null) {
                refuse(id, packet == null ? "no packet" : "held but not in the world");
                return;
            }
            applyTracked(entity, id);
            puppet(entity);
            entity.updateTrackedPositionAndAngles(packet.getX(), packet.getY(), packet.getZ(),
                    packet.getYaw(), packet.getPitch(), INTERPOLATION_STEPS);
            entity.updateTrackedHeadRotation(packet.getHeadYaw(), INTERPOLATION_STEPS);
            this.moved++;
        }

        @Override
        public void remove(int id) {
            this.world.removeEntity(id, Entity.RemovalReason.DISCARDED);
            PLACED.remove(id);
            this.removed++;
        }

        /**
         * A fed entity is driven entirely by the server's snapshots, so it
         * must not run physics of its own. Gravity is the one that costs a
         * whole entity: the destination world holds only the fed chunks, an
         * entity standing over an unfed column has no floor under it, and
         * {@code Entity.attemptTickInVoid} answers a long fall with
         * {@code discard()} — the entity leaves the world and never comes
         * back, because a still scene sends no new snapshot to rebuild it.
         *
         * <p>{@code ignoreCameraFrustum} is what gets it DRAWN.
         * {@code EntityRenderer.shouldRender} returns true on that field
         * before it reaches {@code Frustum.isVisible}, and that call is the
         * one Sodium wraps to cull against a renderer resolved globally to
         * the player's own world — which no destination entity is in. The
         * feed is capped and inside the disc by construction, so the frustum
         * test buys nothing here and costs the entity.
         */
        private static void puppet(Entity entity) {
            entity.setNoGravity(true);
            entity.noClip = true;
            entity.ignoreCameraFrustum = true;
        }

        private void refuse(int id, String reason) {
            this.refused.add(id);
            this.reasons.add(id + ": " + reason);
        }

        /**
         * A player has no entity-type factory: it is built from the shared
         * player list, which both worlds reach through the one connection.
         */
        private Entity create(EntitySpawnS2CPacket packet) {
            EntityType<?> type = packet.getEntityType();
            if (type != EntityType.PLAYER) {
                return type.create(this.world);
            }
            ClientPlayNetworkHandler handler =
                    this.client == null ? null : this.client.getNetworkHandler();
            PlayerListEntry listed =
                    handler == null ? null : handler.getPlayerListEntry(packet.getUuid());
            return listed == null ? null : new OtherClientPlayerEntity(this.world, listed.getProfile());
        }
    }
}
