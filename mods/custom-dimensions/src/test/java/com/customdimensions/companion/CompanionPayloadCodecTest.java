package com.customdimensions.companion;

import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T1.1, server half. The two mods hold independent copies of these records and
 * share no code, and a round-trip alone cannot see that: encode and decode move
 * together, so a codec swapped in one module still round-trips there.
 *
 * <p>The wire-format cases are what actually pin the contract. The client copy
 * asserts the same bytes against its own records, so either module drifting
 * turns that module red.
 */
class CompanionPayloadCodecTest {
    private static final String DESTINATION_ID = "adventure:the_violet_spire";

    private static RegistryByteBuf buf() {
        return new RegistryByteBuf(Unpooled.buffer(), null);
    }

    private static byte[] drain(RegistryByteBuf buf) {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        return bytes;
    }

    @Test
    void helloRoundTrips() {
        RegistryByteBuf buf = buf();
        CompanionPayloads.Hello.CODEC.encode(buf, new CompanionPayloads.Hello(1));
        assertEquals(new CompanionPayloads.Hello(1), CompanionPayloads.Hello.CODEC.decode(buf));
        assertEquals(0, buf.readableBytes(), "decode did not consume everything encode wrote");
    }

    @Test
    void preloadedTransferRoundTripsTheIdentifier() {
        Identifier destination = Identifier.of("adventure", "the_violet_spire");
        RegistryByteBuf buf = buf();
        CompanionPayloads.PreloadedTransfer.CODEC.encode(
                buf, new CompanionPayloads.PreloadedTransfer(destination));

        CompanionPayloads.PreloadedTransfer decoded =
                CompanionPayloads.PreloadedTransfer.CODEC.decode(buf);

        assertEquals(destination, decoded.destination());
        assertEquals(0, buf.readableBytes(), "decode did not consume everything encode wrote");
    }

    @Test
    void helloIsAVarIntOnTheWire() {
        RegistryByteBuf buf = buf();
        CompanionPayloads.Hello.CODEC.encode(buf, new CompanionPayloads.Hello(300));
        assertArrayEquals(new byte[] {(byte) 0xAC, 0x02}, drain(buf),
                "protocol version is not a VAR_INT any more");
    }

    @Test
    void preloadedTransferIsALengthPrefixedIdentifierOnTheWire() {
        RegistryByteBuf buf = buf();
        CompanionPayloads.PreloadedTransfer.CODEC.encode(
                buf, new CompanionPayloads.PreloadedTransfer(Identifier.of(DESTINATION_ID)));

        byte[] bytes = drain(buf);
        assertEquals(DESTINATION_ID.length() + 1, bytes.length);
        assertEquals(DESTINATION_ID.length(), bytes[0], "identifier lost its length prefix");
        assertEquals(DESTINATION_ID,
                new String(bytes, 1, DESTINATION_ID.length(), StandardCharsets.UTF_8));
    }

    @Test
    void channelIdsAreTheAgreedLiterals() {
        assertEquals("customdimensions:handshake/v1",
                CompanionPayloads.Hello.ID.id().toString());
        assertEquals("customdimensions:preloaded/v1",
                CompanionPayloads.PreloadedTransfer.ID.id().toString());
        assertEquals("customdimensions:destination-entities/v1",
                CompanionPayloads.DestinationEntities.ID.id().toString());
        assertEquals(2, CompanionPayloads.PROTOCOL_VERSION);
    }

    @Test
    void portalViewRoundTrips() {
        RegistryByteBuf buf = buf();
        CompanionPayloads.PortalView sent = new CompanionPayloads.PortalView(true, false, 24);
        CompanionPayloads.PortalView.CODEC.encode(buf, sent);

        assertEquals(sent, CompanionPayloads.PortalView.CODEC.decode(buf));
        assertEquals(0, buf.readableBytes(), "decode did not consume everything encode wrote");
    }

    @Test
    void portalViewIsTwoBooleansThenAVarIntOnTheWire() {
        RegistryByteBuf buf = buf();
        CompanionPayloads.PortalView.CODEC.encode(
                buf, new CompanionPayloads.PortalView(true, false, 300));
        assertArrayEquals(new byte[] {1, 0, (byte) 0xAC, 0x02}, drain(buf),
                "the two halves of the pack no longer agree on the portal-view wire shape");
    }

    @Test
    void portalViewChannelIdIsTheAgreedLiteral() {
        assertEquals("customdimensions:portal-view/v1",
                CompanionPayloads.PortalView.ID.id().toString());
    }

    @Test
    void portalFrameRoundTripsIncludingItsTransform() {
        RegistryByteBuf buf = buf();
        CompanionPayloads.PortalFrame sent = new CompanionPayloads.PortalFrame(
                Identifier.of(DESTINATION_ID), Identifier.of("adventure:wide"),
                new BlockPos(1500, 101, 1500),
                java.util.List.of(new BlockPos(1500, 101, 1500), new BlockPos(1501, 103, 1500)),
                2, 5, -750, -44, -750, 0xAF2B2B, -1);
        CompanionPayloads.PortalFrame.CODEC.encode(buf, sent);

        CompanionPayloads.PortalFrame back = CompanionPayloads.PortalFrame.CODEC.decode(buf);

        assertEquals(sent, back);
        assertEquals(0, buf.readableBytes(), "decode did not consume everything encode wrote");
    }

    /**
     * The three offsets are the whole source-to-destination transform, and a
     * negative one is the normal case for a scaled portal. A VarInt would
     * cost five bytes for every one of them.
     */
    @Test
    void portalFrameOffsetsAreSignedAndSurviveBeingNegative() {
        RegistryByteBuf buf = buf();
        CompanionPayloads.PortalFrame.CODEC.encode(buf, new CompanionPayloads.PortalFrame(
                Identifier.of(DESTINATION_ID), Identifier.of("adventure:wide"),
                BlockPos.ORIGIN, java.util.List.of(), 2, 5,
                -750, -44, -750, -1, -1));

        CompanionPayloads.PortalFrame back = CompanionPayloads.PortalFrame.CODEC.decode(buf);

        assertEquals(-750, back.dx());
        assertEquals(-44, back.dy());
        assertEquals(-750, back.dz());
    }

    @Test
    void portalFrameChannelIdIsTheAgreedLiteral() {
        assertEquals("customdimensions:portal-frame/v1",
                CompanionPayloads.PortalFrame.ID.id().toString());
    }
}
