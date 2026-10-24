package com.customdimensions.server.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

class CompanionPayloadsTest {

    private static <T> T roundTrip(StreamCodec<RegistryFriendlyByteBuf, T> codec, T value) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        codec.encode(buf, value);
        T back = codec.decode(buf);
        assertEquals(0, buf.readableBytes(), "the decoder reads exactly what the encoder wrote");
        return back;
    }

    @Test
    void channelIdsMatchTheCompanionClient() {
        assertEquals("customdimensions:handshake/v2", CompanionPayloads.Hello.TYPE.id().toString());
        assertEquals("customdimensions:portal-view/v2", CompanionPayloads.PortalView.TYPE.id().toString());
        assertEquals("customdimensions:preloaded/v1", CompanionPayloads.PreloadedTransfer.TYPE.id().toString());
        assertEquals("customdimensions:portal-frame/v1", CompanionPayloads.PortalFrame.TYPE.id().toString());
        assertEquals("customdimensions:destination-chunk/v1", CompanionPayloads.DestinationChunk.TYPE.id().toString());
        assertEquals("customdimensions:destination-entities/v1",
                CompanionPayloads.DestinationEntities.TYPE.id().toString());
        assertEquals("customdimensions:entity-handover/v1", CompanionPayloads.EntityHandover.TYPE.id().toString());
        assertEquals("customdimensions:projection-clear/v1", CompanionPayloads.ProjectionClear.TYPE.id().toString());
    }

    @Test
    void helloRoundTrips() {
        CompanionPayloads.Hello hello = new CompanionPayloads.Hello("6.0.0-rc.1");
        assertEquals(hello, roundTrip(CompanionPayloads.Hello.CODEC, hello));
    }

    @Test
    void portalViewRoundTripsInTheClientsFieldOrder() {
        CompanionPayloads.PortalView view = new CompanionPayloads.PortalView(true, false, 16, 96);
        assertEquals(view, roundTrip(CompanionPayloads.PortalView.CODEC, view));
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        CompanionPayloads.PortalView.CODEC.encode(buf, view);
        assertEquals(1, buf.readByte());
        assertEquals(0, buf.readByte());
        assertEquals(16, buf.readVarInt());
        assertEquals(96, buf.readVarInt());
    }

    @Test
    void preloadedAndClearRoundTrip() {
        CompanionPayloads.PreloadedTransfer p = new CompanionPayloads.PreloadedTransfer(
                Identifier.fromNamespaceAndPath("adventure", "the_ashgrove"));
        assertEquals(p, roundTrip(CompanionPayloads.PreloadedTransfer.CODEC, p));
        CompanionPayloads.ProjectionClear c = new CompanionPayloads.ProjectionClear(new BlockPos(-30, -64, 2_000_000));
        assertEquals(c, roundTrip(CompanionPayloads.ProjectionClear.CODEC, c));
    }

    @Test
    void portalFrameRoundTripsWithNegativeOffsetsAndUnsetColours() {
        List<BlockPos> aperture = List.of(new BlockPos(10, 64, -5), new BlockPos(11, 64, -5), new BlockPos(10, 65, -5));
        CompanionPayloads.PortalFrame frame = new CompanionPayloads.PortalFrame(
                Identifier.fromNamespaceAndPath("adventure", "the_ashgrove"),
                Identifier.fromNamespaceAndPath("adventure", "the_ashgrove"),
                aperture.get(0), aperture, 0, 3, -1200, 7, 400_000, -1, 0x77AADD);
        assertEquals(frame, roundTrip(CompanionPayloads.PortalFrame.CODEC, frame));
    }

    @Test
    void aFrameNamingAnImpossibleApertureIsAProtocolBreak() {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        buf.writeIdentifier(Identifier.fromNamespaceAndPath("a", "b"));
        buf.writeIdentifier(Identifier.fromNamespaceAndPath("a", "b"));
        buf.writeBlockPos(BlockPos.ZERO);
        buf.writeVarInt(-3);
        assertThrows(IllegalStateException.class, () -> CompanionPayloads.PortalFrame.CODEC.decode(buf));
    }

    @Test
    void boundsRefuseNegativeAndOversizedCounts() {
        assertEquals(3, CompanionPayloads.bounded(3, 3, "x"));
        assertThrows(IllegalStateException.class, () -> CompanionPayloads.bounded(4, 3, "x"));
        assertThrows(IllegalStateException.class, () -> CompanionPayloads.bounded(-1, 3, "x"));
    }
}
