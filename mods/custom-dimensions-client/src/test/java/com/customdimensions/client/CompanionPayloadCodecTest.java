package com.customdimensions.client;

import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
        assertEquals(1, CompanionPayloads.PROTOCOL_VERSION);
    }
}
