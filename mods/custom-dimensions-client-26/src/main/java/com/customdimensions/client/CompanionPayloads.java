package com.customdimensions.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.resources.Identifier;

/**
 * The wire contract between this mod and the Custom Dimensions server mod.
 * The two mods share no code, so both copies keep the same channel ids, field order and
 * field types; a record is never widened in place, a /v(n+1) goes beside it.
 * Bodies that are vanilla packets are written by vanilla's own codec.
 */
public final class CompanionPayloads {
    public static final String NAMESPACE = "customdimensions";

    private CompanionPayloads() {
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> channel(String path) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(NAMESPACE, path));
    }

    /** Client to server, once on join: the release the client was built from. */
    public record Hello(String modVersion) implements CustomPacketPayload {
        public static final Type<Hello> TYPE = channel("handshake/v2");
        public static final StreamCodec<RegistryFriendlyByteBuf, Hello> CODEC =
                StreamCodec.composite(ByteBufCodecs.STRING_UTF8, Hello::modVersion, Hello::new);

        @Override
        public Type<Hello> type() {
            return TYPE;
        }
    }

    /** Client to server, on join and on every change: what the client draws for itself. */
    public record PortalView(boolean renderLocally, boolean keepSlab, int maxRenderDistance, int viewDepth)
            implements CustomPacketPayload {
        public static final Type<PortalView> TYPE = channel("portal-view/v2");
        public static final StreamCodec<RegistryFriendlyByteBuf, PortalView> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, PortalView::renderLocally,
                ByteBufCodecs.BOOL, PortalView::keepSlab,
                ByteBufCodecs.VAR_INT, PortalView::maxRenderDistance,
                ByteBufCodecs.VAR_INT, PortalView::viewDepth,
                PortalView::new);

        @Override
        public Type<PortalView> type() {
            return TYPE;
        }
    }

    /** Server to client, just before a crossing: the destination and its arrival are resident. */
    public record PreloadedTransfer(Identifier destination) implements CustomPacketPayload {
        public static final Type<PreloadedTransfer> TYPE = channel("preloaded/v1");
        public static final StreamCodec<RegistryFriendlyByteBuf, PreloadedTransfer> CODEC =
                StreamCodec.composite(Identifier.STREAM_CODEC, PreloadedTransfer::destination, PreloadedTransfer::new);

        @Override
        public Type<PreloadedTransfer> type() {
            return TYPE;
        }
    }

    /**
     * Server to client: one opening's geometry and where it leads, with no
     * blocks. {@code destination = source + (dx, dy, dz)}; the scale is already
     * spent deriving the offset. Colours are RGB, -1 meaning "use the client's own".
     */
    public record PortalFrame(Identifier destination, Identifier dimensionType, BlockPos apertureOrigin,
                              List<BlockPos> aperture, int portalAxis, int normal, int dx, int dy, int dz,
                              int skyColor, int fogColor) implements CustomPacketPayload {
        public static final Type<PortalFrame> TYPE = channel("portal-frame/v1");
        public static final StreamCodec<RegistryFriendlyByteBuf, PortalFrame> CODEC =
                StreamCodec.ofMember(PortalFrame::write, PortalFrame::read);

        public PortalFrame {
            aperture = List.copyOf(aperture);
        }

        @Override
        public Type<PortalFrame> type() {
            return TYPE;
        }

        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeIdentifier(destination);
            buf.writeIdentifier(dimensionType);
            buf.writeBlockPos(apertureOrigin);
            writePositions(buf, aperture);
            buf.writeVarInt(portalAxis);
            buf.writeVarInt(normal);
            buf.writeInt(dx);
            buf.writeInt(dy);
            buf.writeInt(dz);
            buf.writeInt(skyColor);
            buf.writeInt(fogColor);
        }

        private static PortalFrame read(RegistryFriendlyByteBuf buf) {
            Identifier destination = buf.readIdentifier();
            Identifier dimensionType = buf.readIdentifier();
            BlockPos origin = buf.readBlockPos();
            List<BlockPos> aperture = readPositions(buf);
            return new PortalFrame(destination, dimensionType, origin, aperture, buf.readVarInt(), buf.readVarInt(),
                    buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt());
        }
    }

    /** Server to client: one destination chunk, as vanilla's own chunk packet behind the dimension id. */
    public record DestinationChunk(Identifier destination, ClientboundLevelChunkWithLightPacket chunk)
            implements CustomPacketPayload {
        public static final Type<DestinationChunk> TYPE = channel("destination-chunk/v1");
        public static final StreamCodec<RegistryFriendlyByteBuf, DestinationChunk> CODEC = StreamCodec.composite(
                Identifier.STREAM_CODEC, DestinationChunk::destination,
                ClientboundLevelChunkWithLightPacket.STREAM_CODEC, DestinationChunk::chunk,
                DestinationChunk::new);

        @Override
        public Type<DestinationChunk> type() {
            return TYPE;
        }
    }

    /**
     * Server to client: a whole snapshot of the entities near one destination's
     * far portal, in destination coordinates. {@code tracked} carries each
     * entity's non-default synched data, matched by id; {@code departed} names
     * ids the client should stop showing.
     */
    public record DestinationEntities(Identifier destination, List<ClientboundAddEntityPacket> present,
                                      List<ClientboundSetEntityDataPacket> tracked, int[] departed)
            implements CustomPacketPayload {
        public static final int MAX_ENTRIES = 256;
        public static final Type<DestinationEntities> TYPE = channel("destination-entities/v1");
        public static final StreamCodec<RegistryFriendlyByteBuf, DestinationEntities> CODEC =
                StreamCodec.ofMember(DestinationEntities::write, DestinationEntities::read);

        @Override
        public Type<DestinationEntities> type() {
            return TYPE;
        }

        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeIdentifier(destination);
            buf.writeVarInt(present.size());
            for (ClientboundAddEntityPacket p : present) {
                ClientboundAddEntityPacket.STREAM_CODEC.encode(buf, p);
            }
            buf.writeVarInt(tracked.size());
            for (ClientboundSetEntityDataPacket p : tracked) {
                ClientboundSetEntityDataPacket.STREAM_CODEC.encode(buf, p);
            }
            buf.writeVarInt(departed.length);
            for (int id : departed) {
                buf.writeVarInt(id);
            }
        }

        private static DestinationEntities read(RegistryFriendlyByteBuf buf) {
            Identifier destination = buf.readIdentifier();
            int n = bounded(buf.readVarInt(), MAX_ENTRIES, "entities");
            List<ClientboundAddEntityPacket> present = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                present.add(ClientboundAddEntityPacket.STREAM_CODEC.decode(buf));
            }
            int t = bounded(buf.readVarInt(), MAX_ENTRIES, "tracker entries");
            List<ClientboundSetEntityDataPacket> tracked = new ArrayList<>(t);
            for (int i = 0; i < t; i++) {
                tracked.add(ClientboundSetEntityDataPacket.STREAM_CODEC.decode(buf));
            }
            int d = bounded(buf.readVarInt(), MAX_ENTRIES, "departures");
            int[] departed = new int[d];
            for (int i = 0; i < d; i++) {
                departed[i] = buf.readVarInt();
            }
            return new DestinationEntities(destination, present, tracked, departed);
        }
    }

    /**
     * Server to client, on the tick a non-player entity crosses into a
     * destination this viewer is drawing: the id it had, the entity it became
     * (vanilla's spawn packet, destination coordinates) and the tick.
     */
    public record EntityHandover(Identifier destination, int fromId, long tick, ClientboundAddEntityPacket arrival,
                                 List<ClientboundSetEntityDataPacket> tracked) implements CustomPacketPayload {
        public static final int MAX_TRACKED = 1;
        public static final Type<EntityHandover> TYPE = channel("entity-handover/v1");
        public static final StreamCodec<RegistryFriendlyByteBuf, EntityHandover> CODEC =
                StreamCodec.ofMember(EntityHandover::write, EntityHandover::read);

        @Override
        public Type<EntityHandover> type() {
            return TYPE;
        }

        public int toId() {
            return arrival.getId();
        }

        private void write(RegistryFriendlyByteBuf buf) {
            buf.writeIdentifier(destination);
            buf.writeVarInt(fromId);
            buf.writeVarLong(tick);
            ClientboundAddEntityPacket.STREAM_CODEC.encode(buf, arrival);
            buf.writeVarInt(tracked.size());
            for (ClientboundSetEntityDataPacket p : tracked) {
                ClientboundSetEntityDataPacket.STREAM_CODEC.encode(buf, p);
            }
        }

        private static EntityHandover read(RegistryFriendlyByteBuf buf) {
            Identifier destination = buf.readIdentifier();
            int fromId = buf.readVarInt();
            long tick = buf.readVarLong();
            ClientboundAddEntityPacket arrival = ClientboundAddEntityPacket.STREAM_CODEC.decode(buf);
            int n = bounded(buf.readVarInt(), MAX_TRACKED, "tracker entries");
            List<ClientboundSetEntityDataPacket> tracked = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                tracked.add(ClientboundSetEntityDataPacket.STREAM_CODEC.decode(buf));
            }
            return new EntityHandover(destination, fromId, tick, arrival, tracked);
        }
    }

    /** Server to client: this opening is no longer fed; drop its frame and what it showed. */
    public record ProjectionClear(BlockPos apertureOrigin) implements CustomPacketPayload {
        public static final Type<ProjectionClear> TYPE = channel("projection-clear/v1");
        public static final StreamCodec<RegistryFriendlyByteBuf, ProjectionClear> CODEC =
                StreamCodec.composite(BlockPos.STREAM_CODEC, ProjectionClear::apertureOrigin, ProjectionClear::new);

        @Override
        public Type<ProjectionClear> type() {
            return TYPE;
        }
    }

    /** A length the sender never writes is a protocol break, not a big frame. */
    static int bounded(int count, int max, String what) {
        if (count < 0 || count > max) {
            throw new IllegalStateException("companion payload names " + count + " " + what + ", over " + max);
        }
        return count;
    }

    static void writePositions(FriendlyByteBuf buf, List<BlockPos> positions) {
        buf.writeVarInt(positions.size());
        for (BlockPos p : positions) {
            buf.writeBlockPos(p);
        }
    }

    static List<BlockPos> readPositions(FriendlyByteBuf buf) {
        int n = bounded(buf.readVarInt(), 4096, "aperture cells");
        List<BlockPos> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(buf.readBlockPos());
        }
        return out;
    }
}
