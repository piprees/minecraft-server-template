package com.customdimensions.server.companion;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The bytes of every companion payload, pinned in
 * {@code src/test/resources/companion-fixtures/}. The companion client keeps an
 * identical copy of these files and decodes and re-encodes each one, so a
 * change to either side's codec fails a test on that side.
 * {@code -Dcustomdimensions.fixtures.write=<dir>} writes the files instead.
 */
class CompanionWireFixturesTest {
    private static RegistryAccess access;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        access = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("adventure", path);
    }

    static ClientboundAddEntityPacket zombie() {
        return new ClientboundAddEntityPacket(41, new UUID(0x1234L, 0x5678L), 100.5, 64.0, -30.25, 10f, 90f,
                EntityTypes.ZOMBIE, 0, new Vec3(0.1, 0.0, -0.05), 45.0);
    }

    static ClientboundAddEntityPacket arrow() {
        return new ClientboundAddEntityPacket(42, new UUID(0x9abcL, 0xdefL), 101.0, 66.5, -29.0, -5f, 180f,
                EntityTypes.ARROW, 41, new Vec3(0.0, -0.2, 1.5), 0.0);
    }

    static ClientboundSetEntityDataPacket onFire(int entity) {
        return new ClientboundSetEntityDataPacket(entity,
                List.of(new SynchedEntityData.DataValue<>(0, EntityDataSerializers.BYTE, (byte) 1)));
    }

    /** x 3, z -2, no heightmaps, no section bytes, no block entities, empty light masks and arrays. */
    static byte[] emptyChunkBody() {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        buf.writeInt(3);
        buf.writeInt(-2);
        buf.writeVarInt(0);
        buf.writeVarInt(0);
        buf.writeVarInt(0);
        for (int i = 0; i < 6; i++) {
            buf.writeVarInt(0);
        }
        return ByteBufUtil.getBytes(buf);
    }

    private static <T> String hex(StreamCodec<RegistryFriendlyByteBuf, T> codec, T value) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), access);
        codec.encode(buf, value);
        return ByteBufUtil.hexDump(buf);
    }

    private static <T> T decode(StreamCodec<RegistryFriendlyByteBuf, T> codec, String hex) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(hex)),
                access);
        T value = codec.decode(buf);
        assertEquals(0, buf.readableBytes(), "the decoder reads the whole fixture");
        return value;
    }

    private static String fixture(String name) throws IOException {
        try (InputStream in = CompanionWireFixturesTest.class.getResourceAsStream("/companion-fixtures/" + name + ".hex")) {
            if (in == null) {
                throw new IOException("missing fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.US_ASCII).trim();
        }
    }

    private static <T> void pin(String name, StreamCodec<RegistryFriendlyByteBuf, T> codec, T value) throws IOException {
        String encoded = hex(codec, value);
        String dir = System.getProperty("customdimensions.fixtures.write", "");
        if (!dir.isEmpty()) {
            Files.createDirectories(Path.of(dir));
            Files.writeString(Path.of(dir, name + ".hex"), encoded + "\n");
            return;
        }
        String pinned = fixture(name);
        assertEquals(pinned, encoded, name + " encodes to the pinned bytes");
        assertEquals(pinned, hex(codec, decode(codec, pinned)), name + " re-encodes what it decodes");
    }

    @Test
    void everyPayloadMatchesItsPinnedBytes() throws IOException {
        pin("handshake", CompanionPayloads.Hello.CODEC, new CompanionPayloads.Hello("0.1.0"));
        pin("portal-view", CompanionPayloads.PortalView.CODEC, new CompanionPayloads.PortalView(true, false, 12, 64));
        pin("preloaded", CompanionPayloads.PreloadedTransfer.CODEC,
                new CompanionPayloads.PreloadedTransfer(id("the_ashgrove")));
        List<BlockPos> aperture = List.of(new BlockPos(3072, 200, 3072), new BlockPos(3073, 200, 3072),
                new BlockPos(3072, 201, 3072), new BlockPos(3073, 201, 3072));
        pin("portal-frame", CompanionPayloads.PortalFrame.CODEC, new CompanionPayloads.PortalFrame(id("the_ashgrove"),
                id("the_ashgrove_type"), aperture.get(0), aperture, 0, 2, -2688, -120, -2688, 0x78A7FF, -1));
        ClientboundLevelChunkWithLightPacket chunk = ClientboundLevelChunkWithLightPacket.STREAM_CODEC.decode(
                new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(emptyChunkBody()), access));
        pin("destination-chunk", CompanionPayloads.DestinationChunk.CODEC,
                new CompanionPayloads.DestinationChunk(id("the_ashgrove"), chunk));
        pin("destination-entities", CompanionPayloads.DestinationEntities.CODEC,
                new CompanionPayloads.DestinationEntities(id("the_ashgrove"), List.of(zombie(), arrow()),
                        List.of(onFire(41)), new int[] {7, 300}));
        pin("entity-handover", CompanionPayloads.EntityHandover.CODEC, new CompanionPayloads.EntityHandover(
                id("the_ashgrove"), 9, 123_456_789L, zombie(), List.of(onFire(41))));
        pin("projection-clear", CompanionPayloads.ProjectionClear.CODEC,
                new CompanionPayloads.ProjectionClear(new BlockPos(3072, 200, 3072)));
    }
}
