package com.customdimensions.client;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * The wire contract between this mod and the custom-dimensions-client
 * companion. The two mods share no code, so both copies must stay
 * byte-identical: same channel ids, same codec order, same field types.
 * Never widen a record in place — add /v2 beside it.
 */
public final class CompanionPayloads {
    private CompanionPayloads() {}

    /**
     * Client to server, once on join: "I can handle companion payloads, and
     * this is the release I was built from."
     *
     * <p>{@code modVersion} is the sender's own {@code fabric.mod.json}
     * version, which is the whole of the protocol version — one release tag
     * stamps both mods. The server admits a client whose string equals its own
     * and serves every other one as vanilla, so the two sides never speak a
     * format only one of them was built for.
     */
    public record Hello(String modVersion) implements CustomPayload {
        public static final CustomPayload.Id<Hello> ID =
                new CustomPayload.Id<>(Identifier.of("customdimensions", "handshake/v2"));

        public static final PacketCodec<RegistryByteBuf, Hello> CODEC = PacketCodec.tuple(
                PacketCodecs.STRING, Hello::modVersion,
                Hello::new);

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /**
     * Client to server, on join and again whenever the player changes it: what
     * this client will draw for itself.
     *
     * <p>Not part of {@link Hello} on purpose. The handshake asks "can you
     * speak this protocol" once and the answer never changes; this is a
     * setting behind a key the player can press at any moment, so it is its
     * own message and arrives again every time they press it.
     *
     * <p>A client that never sends one is served exactly as it is today — the
     * server's block slab — so a vanilla client and an older companion both
     * keep working with no branch of their own.
     */
    public record PortalView(
            boolean renderLocally,
            boolean keepSlab,
            int maxRenderDistance) implements CustomPayload {

        public static final CustomPayload.Id<PortalView> ID =
                new CustomPayload.Id<>(Identifier.of("customdimensions", "portal-view/v1"));

        public static final PacketCodec<RegistryByteBuf, PortalView> CODEC = PacketCodec.tuple(
                PacketCodecs.BOOL, PortalView::renderLocally,
                PacketCodecs.BOOL, PortalView::keepSlab,
                PacketCodecs.VAR_INT, PortalView::maxRenderDistance,
                PortalView::new);

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /**
     * Server to client, immediately before a managed traversal: the destination
     * world and its arrival chunks are already resident, so the loading screen
     * is ceremony rather than a wait.
     */
    public record PreloadedTransfer(Identifier destination) implements CustomPayload {
        public static final CustomPayload.Id<PreloadedTransfer> ID =
                new CustomPayload.Id<>(Identifier.of("customdimensions", "preloaded/v1"));

        public static final PacketCodec<RegistryByteBuf, PreloadedTransfer> CODEC = PacketCodec.tuple(
                Identifier.PACKET_CODEC, PreloadedTransfer::destination,
                PreloadedTransfer::new);

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /**
     * Server to client: one portal's destination, described well enough for the
     * client to render it itself. This replaces the fake-block projection for a
     * companion — never both, or the client draws the destination behind a slab
     * of blocks describing the same thing.
     *
     * <p>Every coordinate is a SOURCE-world block position: the destination has
     * already been mapped through the portal's scale transform, so the client
     * needs no knowledge of it. {@code origin} is the volume's minimum corner,
     * grid index is {@code ((x * sizeZ) + z) * sizeY + y} with y varying
     * fastest, and both arrays are run-length encoded on the wire along that
     * order — vertical runs of stone and air are what make the payload small.
     *
     * <p>{@code light} packs the destination's own sky and block light as
     * {@code sky << 4 | block}. {@code skyColor} and {@code fogColor} are ARGB
     * with -1 meaning "not configured, use the client's own", and come from the
     * dimension config's {@code environment} block over the arrival biome's.
     *
     * <p>Grass, foliage and water are per COLUMN, so a view spanning two biomes
     * tints each of them its own. {@code tintPalette} holds the distinct triples
     * flattened in {@link #TINT_GRASS}, {@link #TINT_FOLIAGE}, {@link #TINT_WATER}
     * order; {@code columnTints} is one palette index per column, indexed
     * {@code x * sizeZ + z} and run-length encoded along that order. A column
     * whose destination chunk is not resident indexes a triple of -1, and holds
     * no blocks to draw either.
     *
     * <p>{@code ambientLight} is the destination {@code DimensionType}'s own,
     * -1 meaning "unset". The levels above are the destination's but the
     * lightmap colouring them is the source's, which is where a dimension's
     * ambient floor lives; the client re-expresses one in the other.
     */
    public record Projection(
            Identifier destination,
            BlockPos apertureOrigin,
            List<BlockPos> aperture,
            int portalAxis,
            int normal,
            BlockPos origin,
            int sizeX,
            int sizeY,
            int sizeZ,
            int[] states,
            byte[] light,
            int skyColor,
            int fogColor,
            int[] tintPalette,
            int[] columnTints,
            float ambientLight) implements CustomPayload {

        /** Channels in one column's tint, and their order in the palette. */
        public static final int TINT_CHANNELS = 3;
        public static final int TINT_GRASS = 0;
        public static final int TINT_FOLIAGE = 1;
        public static final int TINT_WATER = 2;

        public static final CustomPayload.Id<Projection> ID =
                new CustomPayload.Id<>(Identifier.of("customdimensions", "projection/v3"));

        public static final PacketCodec<RegistryByteBuf, Projection> CODEC =
                PacketCodec.of(Projection::write, Projection::read);

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }

        public int cellCount() {
            return this.sizeX * this.sizeY * this.sizeZ;
        }

        public int columnCount() {
            return this.sizeX * this.sizeZ;
        }

        /** One column's tint on one channel, -1 where none was resolved. */
        public int columnTint(int column, int channel) {
            if (column < 0 || column >= this.columnTints.length
                    || channel < 0 || channel >= TINT_CHANNELS) {
                return -1;
            }
            int at = this.columnTints[column] * TINT_CHANNELS + channel;
            return at < 0 || at >= this.tintPalette.length ? -1 : this.tintPalette[at];
        }

        private void write(RegistryByteBuf buf) {
            buf.writeIdentifier(this.destination);
            buf.writeBlockPos(this.apertureOrigin);
            buf.writeVarInt(this.aperture.size());
            for (BlockPos pos : this.aperture) {
                buf.writeBlockPos(pos);
            }
            buf.writeVarInt(this.portalAxis);
            buf.writeVarInt(this.normal);
            buf.writeBlockPos(this.origin);
            buf.writeVarInt(this.sizeX);
            buf.writeVarInt(this.sizeY);
            buf.writeVarInt(this.sizeZ);
            writeRuns(buf, this.states);
            writeRuns(buf, unsigned(this.light));
            buf.writeInt(this.skyColor);
            buf.writeInt(this.fogColor);
            buf.writeVarInt(this.tintPalette.length);
            for (int value : this.tintPalette) {
                buf.writeInt(value);
            }
            writeRuns(buf, this.columnTints);
            buf.writeFloat(this.ambientLight);
        }

        private static Projection read(RegistryByteBuf buf) {
            Identifier destination = buf.readIdentifier();
            BlockPos apertureOrigin = buf.readBlockPos();
            int apertureSize = buf.readVarInt();
            List<BlockPos> aperture = new ArrayList<>(apertureSize);
            for (int i = 0; i < apertureSize; i++) {
                aperture.add(buf.readBlockPos());
            }
            int portalAxis = buf.readVarInt();
            int normal = buf.readVarInt();
            BlockPos origin = buf.readBlockPos();
            int sizeX = buf.readVarInt();
            int sizeY = buf.readVarInt();
            int sizeZ = buf.readVarInt();
            int cells = sizeX * sizeY * sizeZ;
            int[] states = readRuns(buf, cells);
            int[] lightValues = readRuns(buf, cells);
            byte[] light = new byte[cells];
            for (int i = 0; i < cells; i++) {
                light[i] = (byte) lightValues[i];
            }
            int skyColor = buf.readInt();
            int fogColor = buf.readInt();
            int columns = sizeX * sizeZ;
            int[] tintPalette = new int[boundedPalette(buf.readVarInt(), columns)];
            for (int i = 0; i < tintPalette.length; i++) {
                tintPalette[i] = buf.readInt();
            }
            int[] columnTints = readRuns(buf, columns);
            return new Projection(destination, apertureOrigin, aperture, portalAxis, normal, origin,
                    sizeX, sizeY, sizeZ, states, light,
                    skyColor, fogColor, tintPalette, columnTints,
                    buf.readFloat());
        }

        /**
         * A palette carrying part of a triple, or more triples than the columns
         * plus the unresolved one, is a protocol break rather than a big frame.
         */
        static int boundedPalette(int length, int columns) {
            if (length < 0 || length % TINT_CHANNELS != 0
                    || length > (columns + 1) * TINT_CHANNELS) {
                throw new IllegalStateException("projection palette names " + length
                        + " tint values for " + columns + " columns");
            }
            return length;
        }

        /**
         * The highest cell in one column of a {@code states} grid holding
         * anything but air, or -1. That block's biome is the one whose grass,
         * foliage and water the column shows; a column of air shows none, and
         * has nothing to draw them on either.
         */
        public static int topSolid(int[] states, int sizeY, int sizeZ, int x, int z, int airId) {
            int base = ((x * sizeZ) + z) * sizeY;
            for (int y = sizeY - 1; y >= 0; y--) {
                if (states[base + y] != airId) {
                    return y;
                }
            }
            return -1;
        }

        /**
         * Builds {@code tintPalette} and {@code columnTints} for one volume.
         * Entry 0 is the unresolved triple, so a column nothing is set for
         * reads as -1 on every channel. Distinct triples are few, so the
         * palette is scanned rather than hashed.
         */
        public static final class TintGrid {
            private final int sizeZ;
            private final int[] columns;
            private final List<int[]> entries = new ArrayList<>();

            public TintGrid(int sizeX, int sizeZ) {
                this.sizeZ = sizeZ;
                this.columns = new int[sizeX * sizeZ];
                this.entries.add(new int[] {-1, -1, -1});
            }

            public void set(int x, int z, int grass, int foliage, int water) {
                this.columns[(x * this.sizeZ) + z] = entryFor(grass, foliage, water);
            }

            private int entryFor(int grass, int foliage, int water) {
                for (int i = 0; i < this.entries.size(); i++) {
                    int[] entry = this.entries.get(i);
                    if (entry[TINT_GRASS] == grass && entry[TINT_FOLIAGE] == foliage
                            && entry[TINT_WATER] == water) {
                        return i;
                    }
                }
                this.entries.add(new int[] {grass, foliage, water});
                return this.entries.size() - 1;
            }

            public int[] palette() {
                int[] out = new int[this.entries.size() * TINT_CHANNELS];
                for (int i = 0; i < this.entries.size(); i++) {
                    System.arraycopy(this.entries.get(i), 0, out, i * TINT_CHANNELS, TINT_CHANNELS);
                }
                return out;
            }

            public int[] columns() {
                return this.columns;
            }
        }

        private static int[] unsigned(byte[] values) {
            int[] out = new int[values.length];
            for (int i = 0; i < values.length; i++) {
                out[i] = values[i] & 0xFF;
            }
            return out;
        }

        /** Run-length pairs: run count, then (length, value) per run. */
        static void writeRuns(RegistryByteBuf buf, int[] values) {
            int runs = 0;
            for (int i = 0; i < values.length; i++) {
                if (i == 0 || values[i] != values[i - 1]) {
                    runs++;
                }
            }
            buf.writeVarInt(runs);
            int start = 0;
            for (int i = 1; i <= values.length; i++) {
                if (i == values.length || values[i] != values[start]) {
                    buf.writeVarInt(i - start);
                    buf.writeVarInt(values[start]);
                    start = i;
                }
            }
        }

        /**
         * Decode exactly {@code cells} values. A payload that describes more or
         * fewer is a protocol break, not a recoverable state — an under-length
         * grid would be read out of bounds by the renderer.
         */
        static int[] readRuns(RegistryByteBuf buf, int cells) {
            int[] out = new int[cells];
            int runs = buf.readVarInt();
            int at = 0;
            for (int r = 0; r < runs; r++) {
                int length = buf.readVarInt();
                int value = buf.readVarInt();
                if (length < 0 || at + length > cells) {
                    throw new IllegalStateException("projection run overruns " + cells + " cells");
                }
                for (int i = 0; i < length; i++) {
                    out[at++] = value;
                }
            }
            if (at != cells) {
                throw new IllegalStateException("projection describes " + at + " of " + cells + " cells");
            }
            return out;
        }
    }

    /**
     * Server to client: one portal's geometry and where it leads, with no
     * block data at all. What a client rendering the destination itself is
     * sent in place of {@link Projection} — never both, or the client draws
     * the far side twice.
     *
     * <p>{@code dx}/{@code dy}/{@code dz} are the WHOLE transform:
     * {@code destination = source + (dx, dy, dz)}, mirroring
     * {@code ProjectionVolume.toTarget} exactly. The per-dimension scale is
     * deliberately NOT sent — it is already spent deriving these three
     * numbers, and a client that re-applied it would divide twice. A preview
     * is never scaled: a block walked on the far side is a block here.
     *
     * <p>{@code dimensionType} names the destination's own
     * {@code DimensionType}, which is what the client needs to stand a world
     * up for it. Colours are ARGB, -1 meaning "not configured, use the
     * client's own".
     */
    public record PortalFrame(
            Identifier destination,
            Identifier dimensionType,
            BlockPos apertureOrigin,
            List<BlockPos> aperture,
            int portalAxis,
            int normal,
            int dx,
            int dy,
            int dz,
            int skyColor,
            int fogColor) implements CustomPayload {

        public static final CustomPayload.Id<PortalFrame> ID =
                new CustomPayload.Id<>(Identifier.of("customdimensions", "portal-frame/v1"));

        public static final PacketCodec<RegistryByteBuf, PortalFrame> CODEC =
                PacketCodec.of(PortalFrame::write, PortalFrame::read);

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }

        private void write(RegistryByteBuf buf) {
            buf.writeIdentifier(this.destination);
            buf.writeIdentifier(this.dimensionType);
            buf.writeBlockPos(this.apertureOrigin);
            buf.writeVarInt(this.aperture.size());
            for (BlockPos pos : this.aperture) {
                buf.writeBlockPos(pos);
            }
            buf.writeVarInt(this.portalAxis);
            buf.writeVarInt(this.normal);
            buf.writeInt(this.dx);
            buf.writeInt(this.dy);
            buf.writeInt(this.dz);
            buf.writeInt(this.skyColor);
            buf.writeInt(this.fogColor);
        }

        private static PortalFrame read(RegistryByteBuf buf) {
            Identifier destination = buf.readIdentifier();
            Identifier dimensionType = buf.readIdentifier();
            BlockPos apertureOrigin = buf.readBlockPos();
            int apertureSize = buf.readVarInt();
            List<BlockPos> aperture = new ArrayList<>(apertureSize);
            for (int i = 0; i < apertureSize; i++) {
                aperture.add(buf.readBlockPos());
            }
            return new PortalFrame(destination, dimensionType, apertureOrigin, aperture,
                    buf.readVarInt(), buf.readVarInt(),
                    buf.readInt(), buf.readInt(), buf.readInt(),
                    buf.readInt(), buf.readInt());
        }
    }

    /**
     * Server to client: one chunk of a destination the client is drawing for
     * itself, addressed by dimension rather than by the connection's own.
     *
     * <p>The body is a plain {@code ChunkDataS2CPacket} written by VANILLA's
     * own codec, so the client decodes it with vanilla code and the two sides
     * cannot drift on the chunk format. Only the dimension id in front of it
     * is ours.
     */
    public record DestinationChunk(Identifier destination, ChunkDataS2CPacket chunk)
            implements CustomPayload {

        public static final CustomPayload.Id<DestinationChunk> ID =
                new CustomPayload.Id<>(Identifier.of("customdimensions", "destination-chunk/v1"));

        public static final PacketCodec<RegistryByteBuf, DestinationChunk> CODEC =
                PacketCodec.tuple(
                        Identifier.PACKET_CODEC, DestinationChunk::destination,
                        ChunkDataS2CPacket.CODEC, DestinationChunk::chunk,
                        DestinationChunk::new);

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /**
     * Server to client: the entities standing near one destination's arrival,
     * for a client drawing that far side itself.
     *
     * <p>A whole snapshot, not a stream of events. {@code present} carries
     * every entity the client should be showing — a spawn and a move are the
     * same message — and {@code departed} names the ones it should stop
     * showing. Position and angles are DESTINATION world coordinates, matching
     * {@link DestinationChunk}; nothing here is mapped through the portal.
     *
     * <p>The body of each entry is a plain {@code EntitySpawnS2CPacket} written
     * by VANILLA's own codec, so the client decodes it with vanilla code and
     * the two sides cannot drift on the entity format. {@code entityData} is
     * not available outside an {@code EntityTrackerEntry} and rides as 0, so a
     * type that renders from it (a falling block, a painting) arrives with its
     * default; a mob and a player do not read it.
     *
     * <p>{@code tracked} carries each entity's non-default tracked data,
     * matched to {@code present} by entity id and shorter than it whenever an
     * entity has none. It is not decoration: {@code ItemEntity.tick} answers an
     * empty stack with {@code discard()}, so an item fed without its tracker
     * deletes itself on its first tick. It is also what makes a baby a baby, a
     * sheep the right colour and a pose a pose.
     */
    public record DestinationEntities(
            Identifier destination,
            List<EntitySpawnS2CPacket> present,
            List<EntityTrackerUpdateS2CPacket> tracked,
            int[] departed) implements CustomPayload {

        /** Entries one payload may carry, in either list. */
        public static final int MAX_ENTRIES = 256;

        public static final CustomPayload.Id<DestinationEntities> ID =
                new CustomPayload.Id<>(Identifier.of("customdimensions", "destination-entities/v1"));

        public static final PacketCodec<RegistryByteBuf, DestinationEntities> CODEC =
                PacketCodec.of(DestinationEntities::write, DestinationEntities::read);

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }

        private void write(RegistryByteBuf buf) {
            buf.writeIdentifier(this.destination);
            buf.writeVarInt(this.present.size());
            for (EntitySpawnS2CPacket entry : this.present) {
                EntitySpawnS2CPacket.CODEC.encode(buf, entry);
            }
            buf.writeVarInt(this.tracked.size());
            for (EntityTrackerUpdateS2CPacket entry : this.tracked) {
                EntityTrackerUpdateS2CPacket.CODEC.encode(buf, entry);
            }
            buf.writeVarInt(this.departed.length);
            for (int id : this.departed) {
                buf.writeVarInt(id);
            }
        }

        private static DestinationEntities read(RegistryByteBuf buf) {
            Identifier destination = buf.readIdentifier();
            int presentCount = bounded(buf.readVarInt());
            List<EntitySpawnS2CPacket> present = new ArrayList<>(presentCount);
            for (int i = 0; i < presentCount; i++) {
                present.add(EntitySpawnS2CPacket.CODEC.decode(buf));
            }
            int trackedCount = bounded(buf.readVarInt());
            List<EntityTrackerUpdateS2CPacket> tracked = new ArrayList<>(trackedCount);
            for (int i = 0; i < trackedCount; i++) {
                tracked.add(EntityTrackerUpdateS2CPacket.CODEC.decode(buf));
            }
            int departedCount = bounded(buf.readVarInt());
            int[] departed = new int[departedCount];
            for (int i = 0; i < departedCount; i++) {
                departed[i] = buf.readVarInt();
            }
            return new DestinationEntities(destination, present, tracked, departed);
        }

        /** A length the sender never writes is a protocol break, not a big frame. */
        static int bounded(int count) {
            if (count < 0 || count > MAX_ENTRIES) {
                throw new IllegalStateException("destination entities names " + count
                        + " entries, over " + MAX_ENTRIES);
            }
            return count;
        }
    }

    /**
     * Server to client, on the tick a non-player entity crosses into a
     * destination this viewer is drawing: the id it had, the entity it became,
     * and the tick it happened on.
     *
     * <p>Separate from {@link DestinationEntities} and never folded into it.
     * A snapshot describes who is standing near an arrival and a later poll
     * rediscovers the same fact; this names a correspondence between two
     * network ids that stops existing before the next poll —
     * {@code Entity.teleportTo} destroys the source entity and builds a
     * different one, and nothing on the wire links the two.
     *
     * <p>{@code arrival} is the new entity as vanilla's own spawn packet, in
     * DESTINATION world coordinates, so the client inserts the carried copy
     * itself at the crossing instead of waiting up to
     * {@code DestinationEntityFeed.INTERVAL} ticks for a snapshot to carry it.
     * The new id is {@link #toId()} — it lives in the packet and is not
     * repeated as a field of its own.
     *
     * <p>{@code tracked} is the arrival's non-default tracked data, for the
     * same reason {@link DestinationEntities} carries it: an item fed without
     * its stack answers its first tick with {@code discard()}.
     */
    public record EntityHandover(
            Identifier destination,
            int fromId,
            long tick,
            EntitySpawnS2CPacket arrival,
            List<EntityTrackerUpdateS2CPacket> tracked) implements CustomPayload {

        /** Tracker entries one handover may carry: one entity's, and no more. */
        public static final int MAX_TRACKED = 1;

        public static final CustomPayload.Id<EntityHandover> ID =
                new CustomPayload.Id<>(Identifier.of("customdimensions", "entity-handover/v1"));

        public static final PacketCodec<RegistryByteBuf, EntityHandover> CODEC =
                PacketCodec.of(EntityHandover::write, EntityHandover::read);

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }

        /** The id the entity carries on the far side. */
        public int toId() {
            return this.arrival.getEntityId();
        }

        private void write(RegistryByteBuf buf) {
            buf.writeIdentifier(this.destination);
            buf.writeVarInt(this.fromId);
            buf.writeVarLong(this.tick);
            EntitySpawnS2CPacket.CODEC.encode(buf, this.arrival);
            buf.writeVarInt(this.tracked.size());
            for (EntityTrackerUpdateS2CPacket entry : this.tracked) {
                EntityTrackerUpdateS2CPacket.CODEC.encode(buf, entry);
            }
        }

        private static EntityHandover read(RegistryByteBuf buf) {
            Identifier destination = buf.readIdentifier();
            int fromId = buf.readVarInt();
            long tick = buf.readVarLong();
            EntitySpawnS2CPacket arrival = EntitySpawnS2CPacket.CODEC.decode(buf);
            int trackedCount = boundedTracked(buf.readVarInt());
            List<EntityTrackerUpdateS2CPacket> tracked = new ArrayList<>(trackedCount);
            for (int i = 0; i < trackedCount; i++) {
                tracked.add(EntityTrackerUpdateS2CPacket.CODEC.decode(buf));
            }
            return new EntityHandover(destination, fromId, tick, arrival, tracked);
        }

        /** A length the sender never writes is a protocol break, not a big frame. */
        static int boundedTracked(int count) {
            if (count < 0 || count > MAX_TRACKED) {
                throw new IllegalStateException("entity handover names " + count
                        + " tracker entries, over " + MAX_TRACKED);
            }
            return count;
        }
    }

    /** Server to client: this portal is no longer projecting; drop its volume. */
    public record ProjectionClear(BlockPos apertureOrigin) implements CustomPayload {
        public static final CustomPayload.Id<ProjectionClear> ID =
                new CustomPayload.Id<>(Identifier.of("customdimensions", "projection-clear/v1"));

        public static final PacketCodec<RegistryByteBuf, ProjectionClear> CODEC = PacketCodec.tuple(
                BlockPos.PACKET_CODEC, ProjectionClear::apertureOrigin,
                ProjectionClear::new);

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }
    }
}
