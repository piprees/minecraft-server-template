package com.customdimensions.server.structure;

import com.customdimensions.core.ChunkPos;
import com.customdimensions.core.dimension.NoiseFieldIndex;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

    /**
     * A noise group's sites: the chunks its {@link NoiseFieldIndex} places,
     * each settled where a pooled structure's biome is
     * ({@link com.customdimensions.core.structure.StructureSites#settle}).
     */
    public record Noise(String group, NoiseFieldIndex index, List<ChunkPos> sites, Set<Long> keys) implements StructurePlacement {
        private static final MapCodec<Noise> CODEC = MapCodec.unit(() -> {
            throw new UnsupportedOperationException("a noise structure placement is runtime-only");
        });

        public Noise(String group, NoiseFieldIndex index, List<ChunkPos> sites) {
            this(group, index, List.copyOf(sites), keysOf(sites));
        }

        /** The index's own sites, unsettled. */
        public Noise(String group, NoiseFieldIndex index) {
            this(group, index, index.positions());
        }

        private static Set<Long> keysOf(List<ChunkPos> sites) {
            Set<Long> out = new HashSet<>();
            for (ChunkPos p : sites) out.add(net.minecraft.world.level.ChunkPos.pack(p.x(), p.z()));
            return Set.copyOf(out);
        }

        @Override
        public boolean isStructureChunk(ChunkGeneratorStructureState state, int x, int z) {
            return keys.contains(net.minecraft.world.level.ChunkPos.pack(x, z));
        }

        @Override
        public MapCodec<? extends StructurePlacement> codec() {
            return CODEC;
        }
    }
}
