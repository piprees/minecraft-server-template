package com.customdimensions.server.structure;

import com.customdimensions.core.dimension.NoiseFieldIndex;
import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

/**
 * The two placements structure control adds to a level's structure state.
 * Both live only in that state: they are never registered or written, so
 * their codecs refuse to decode.
 */
public final class StructurePlacements {

    private StructurePlacements() {
    }

    /** One chunk: a forced start or a want. */
    public record Fixed(int chunkX, int chunkZ) implements StructurePlacement {
        private static final MapCodec<Fixed> CODEC = MapCodec.unit(() -> {
            throw new UnsupportedOperationException("a fixed structure placement is runtime-only");
        });

        @Override
        public boolean isStructureChunk(ChunkGeneratorStructureState state, int x, int z) {
            return x == chunkX && z == chunkZ;
        }

        @Override
        public MapCodec<? extends StructurePlacement> codec() {
            return CODEC;
        }
    }

    /** A noise group's sites: the chunks its {@link NoiseFieldIndex} places. */
    public record Noise(String group, NoiseFieldIndex index) implements StructurePlacement {
        private static final MapCodec<Noise> CODEC = MapCodec.unit(() -> {
            throw new UnsupportedOperationException("a noise structure placement is runtime-only");
        });

        @Override
        public boolean isStructureChunk(ChunkGeneratorStructureState state, int x, int z) {
            return index.isPlacement(x, z);
        }

        @Override
        public MapCodec<? extends StructurePlacement> codec() {
            return CODEC;
        }
    }
}
