package com.customdimensions.client.feed;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;

/** One fed destination chunk, read out of vanilla's chunk packet into sections and light. */
public final class DecodedChunk {
    private final int minY;
    private final LevelChunkSection[] sections;
    private final LightLayers sky;
    private final LightLayers block;
    private final int nonAir;

    private DecodedChunk(int minY, LevelChunkSection[] sections, LightLayers sky, LightLayers block, int nonAir) {
        this.minY = minY;
        this.sections = sections;
        this.sky = sky;
        this.block = block;
        this.nonAir = nonAir;
    }

    /** Reads every section the destination's height holds, the way vanilla's own client does. */
    public static DecodedChunk decode(ClientboundLevelChunkWithLightPacket packet, PalettedContainerFactory factory,
                                      int minY, int height) {
        int count = height >> 4;
        LevelChunkSection[] sections = new LevelChunkSection[count];
        FriendlyByteBuf buf = packet.chunkData().getReadBuffer();
        int nonAir = 0;
        for (int i = 0; i < count; i++) {
            LevelChunkSection s = new LevelChunkSection(factory);
            if (buf.isReadable()) {
                s.read(buf);
            }
            sections[i] = s;
            if (!s.hasOnlyAir()) {
                nonAir++;
            }
        }
        ClientboundLightUpdatePacketData light = packet.lightData();
        LightLayers sky = LightLayers.of(count, light.skyYMask(), light.emptySkyYMask(), light.skyUpdates(), 15);
        LightLayers block = LightLayers.of(count, light.blockYMask(), light.emptyBlockYMask(), light.blockUpdates(), 0);
        return new DecodedChunk(minY, sections, sky, block, nonAir);
    }

    public BlockState state(int x, int y, int z) {
        int i = (y - minY) >> 4;
        if (y < minY || i >= sections.length) {
            return Blocks.AIR.defaultBlockState();
        }
        return sections[i].getBlockState(x & 15, y & 15, z & 15);
    }

    public int skyLight(int x, int y, int z) {
        return sky.get(Math.floorDiv(y - minY, 16), x, y, z);
    }

    public int blockLight(int x, int y, int z) {
        return block.get(Math.floorDiv(y - minY, 16), x, y, z);
    }

    /** Sections holding anything but air: the count the feed log reports. */
    public int nonAirSections() {
        return nonAir;
    }
}
