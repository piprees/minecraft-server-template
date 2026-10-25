package com.customdimensions.client.feed;

import com.customdimensions.client.CompanionPayloads;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;

/**
 * What this client holds of the companion feed: the frames keyed by opening
 * origin, each destination's chunks and entities, and the counts the feed log
 * reports. Read and written on the client thread only.
 */
public final class Feed {
    /** One destination entity in destination coordinates. */
    public record Shown(int id, Identifier type, double x, double y, double z, float yRot, float xRot) {
        static Shown of(ClientboundAddEntityPacket p) {
            return new Shown(p.getId(), net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(p.getType()),
                    p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot());
        }
    }

    /** A destination's raw chunk packets and what has been decoded of them; {@code version} moves on every change. */
    public static final class Destination {
        public final Map<Long, CompanionPayloads.DestinationChunk> packets = new HashMap<>();
        public final Map<Long, DecodedChunk> decoded = new HashMap<>();
        public final Map<Integer, Shown> entities = new LinkedHashMap<>();
        public int version;
    }

    private static final Map<BlockPos, CompanionPayloads.PortalFrame> FRAMES = new LinkedHashMap<>();
    private static final Map<Identifier, Destination> DESTINATIONS = new HashMap<>();

    public static long framesReceived;
    public static long chunksReceived;
    public static long entitySnapshots;
    public static long handovers;
    public static long clears;

    private Feed() {
    }

    public static Map<BlockPos, CompanionPayloads.PortalFrame> frames() {
        return FRAMES;
    }

    public static Destination destination(Identifier id) {
        return DESTINATIONS.get(id);
    }

    public static void frame(CompanionPayloads.PortalFrame frame) {
        framesReceived++;
        FRAMES.put(frame.apertureOrigin(), frame);
    }

    public static void chunk(CompanionPayloads.DestinationChunk payload) {
        chunksReceived++;
        Destination d = DESTINATIONS.computeIfAbsent(payload.destination(), k -> new Destination());
        long key = ChunkPos.pack(payload.chunk().x(), payload.chunk().z());
        d.packets.put(key, payload);
        d.decoded.remove(key);
        d.version++;
    }

    public static void entities(CompanionPayloads.DestinationEntities payload) {
        entitySnapshots++;
        Destination d = DESTINATIONS.computeIfAbsent(payload.destination(), k -> new Destination());
        for (int id : payload.departed()) {
            d.entities.remove(id);
        }
        for (ClientboundAddEntityPacket p : payload.present()) {
            d.entities.put(p.getId(), Shown.of(p));
        }
    }

    public static void handover(CompanionPayloads.EntityHandover payload) {
        handovers++;
        Destination d = DESTINATIONS.computeIfAbsent(payload.destination(), k -> new Destination());
        d.entities.put(payload.toId(), Shown.of(payload.arrival()));
    }

    /** A cleared opening drops its frame; a destination no frame leads to any more drops everything. */
    public static void clear(CompanionPayloads.ProjectionClear payload) {
        clears++;
        CompanionPayloads.PortalFrame gone = FRAMES.remove(payload.apertureOrigin());
        if (gone != null && FRAMES.values().stream().noneMatch(f -> f.destination().equals(gone.destination()))) {
            DESTINATIONS.remove(gone.destination());
        }
    }

    public static void reset() {
        FRAMES.clear();
        DESTINATIONS.clear();
    }

    public static int chunksHeld() {
        return DESTINATIONS.values().stream().mapToInt(d -> d.packets.size()).sum();
    }

    public static int entitiesHeld() {
        return DESTINATIONS.values().stream().mapToInt(d -> d.entities.size()).sum();
    }
}
