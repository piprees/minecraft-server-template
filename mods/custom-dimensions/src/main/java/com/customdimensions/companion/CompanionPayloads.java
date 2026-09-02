package com.customdimensions.companion;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

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
}
