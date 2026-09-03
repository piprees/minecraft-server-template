package com.customdimensions.companion;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
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
    /** Bumped only alongside a channel id. */
    public static final int PROTOCOL_VERSION = 1;

    private CompanionPayloads() {}

    /** Client to server, once on join: "I can handle companion payloads." */
    public record Hello(int protocolVersion) implements CustomPayload {
        public static final CustomPayload.Id<Hello> ID =
                new CustomPayload.Id<>(Identifier.of("customdimensions", "handshake/v1"));

        public static final PacketCodec<RegistryByteBuf, Hello> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, Hello::protocolVersion,
                Hello::new);

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
     * {@code sky << 4 | block}. The colours are ARGB with -1 meaning "not
     * configured, use the client's own"; sky and fog come from the dimension
     * config's {@code environment} block, the three tints from the biome at the
     * arrival column.
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
            int grassColor,
            int foliageColor,
            int waterColor) implements CustomPayload {

        public static final CustomPayload.Id<Projection> ID =
                new CustomPayload.Id<>(Identifier.of("customdimensions", "projection/v1"));

        public static final PacketCodec<RegistryByteBuf, Projection> CODEC =
                PacketCodec.of(Projection::write, Projection::read);

        @Override
        public CustomPayload.Id<? extends CustomPayload> getId() {
            return ID;
        }

        public int cellCount() {
            return this.sizeX * this.sizeY * this.sizeZ;
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
            buf.writeInt(this.grassColor);
            buf.writeInt(this.foliageColor);
            buf.writeInt(this.waterColor);
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
            return new Projection(destination, apertureOrigin, aperture, portalAxis, normal, origin,
                    sizeX, sizeY, sizeZ, states, light,
                    buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt());
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
