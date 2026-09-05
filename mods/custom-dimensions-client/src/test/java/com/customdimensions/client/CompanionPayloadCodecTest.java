package com.customdimensions.client;

import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * T1.1, client half. Deliberately the same body as the server module's copy,
 * run against this module's own records: two green runs over two independent
 * definitions is what proves the two halves agree on channel id, field order,
 * field type and bytes.
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
        Identifier destination = Identifier.of(DESTINATION_ID);
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
        assertEquals("customdimensions:projection/v2",
                CompanionPayloads.Projection.ID.id().toString());
        assertEquals("customdimensions:entity-handover/v1",
                CompanionPayloads.EntityHandover.ID.id().toString());
        assertEquals(4, CompanionPayloads.PROTOCOL_VERSION);
    }

    /**
     * A length the sender never writes is a protocol break. The handover
     * carries one entity, so anything past one tracker entry means the two
     * copies of the record have stopped agreeing and the rest of the buffer is
     * being read as something it is not.
     */
    @Test
    void aHandoverRefusesMoreTrackerEntriesThanOneEntityCanHave() {
        assertEquals(1, CompanionPayloads.EntityHandover.boundedTracked(1));
        assertThrows(IllegalStateException.class,
                () -> CompanionPayloads.EntityHandover.boundedTracked(2));
        assertThrows(IllegalStateException.class,
                () -> CompanionPayloads.EntityHandover.boundedTracked(-1));
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

    /**
     * {@code ambientLight} is the destination dimension's own and the field the
     * client lights the far side by, so it is pinned on the wire like the
     * colours beside it. The record compares its arrays by reference, so the
     * fields are read back one at a time.
     */
    @Test
    void projectionRoundTripsItsAmbientLight() {
        RegistryByteBuf buf = buf();
        CompanionPayloads.Projection sent = new CompanionPayloads.Projection(
                Identifier.of(DESTINATION_ID), new BlockPos(1500, 101, 1500),
                java.util.List.of(new BlockPos(1500, 101, 1500)),
                2, 5, new BlockPos(1490, 95, 1490), 2, 1, 1,
                new int[] {3, 3}, new byte[] {(byte) 0xF0, 0x00},
                0xAF2B2B, 0x0E2A44, -1, -1, -1, 0.15f);
        CompanionPayloads.Projection.CODEC.encode(buf, sent);

        CompanionPayloads.Projection back = CompanionPayloads.Projection.CODEC.decode(buf);

        assertEquals(0.15f, back.ambientLight(), 0.0f);
        assertEquals(0x0E2A44, back.fogColor());
        assertArrayEquals(new int[] {3, 3}, back.states());
        assertEquals(0, buf.readableBytes(), "decode did not consume everything encode wrote");
    }

    /** -1 is "unset", and it must survive as -1 rather than clamping to 0. */
    @Test
    void projectionCarriesAnUnsetAmbientLight() {
        RegistryByteBuf buf = buf();
        CompanionPayloads.Projection.CODEC.encode(buf, new CompanionPayloads.Projection(
                Identifier.of(DESTINATION_ID), BlockPos.ORIGIN, java.util.List.of(),
                2, 5, BlockPos.ORIGIN, 1, 1, 1, new int[] {0}, new byte[] {0},
                -1, -1, -1, -1, -1, -1.0f));

        assertEquals(-1.0f, CompanionPayloads.Projection.CODEC.decode(buf).ambientLight(), 0.0f);
    }

    @Test
    void portalFrameChannelIdIsTheAgreedLiteral() {
        assertEquals("customdimensions:portal-frame/v1",
                CompanionPayloads.PortalFrame.ID.id().toString());
    }
}
