package com.customdimensions.server.structure;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A lived-in piece's sites ({@link com.customdimensions.core.structure.SetPieceSetting.Lived}):
 * the chunks drawn for it when its level's structure control is built, each
 * already sited, at its density per km² by distance and biome.
 */
public record LivedPlacement(String structure, List<int[]> sites, Set<Long> keys) implements StructurePlacement {
    private static final MapCodec<LivedPlacement> CODEC = MapCodec.unit(() -> {
        throw new UnsupportedOperationException("a lived-in structure placement is runtime-only");
    });

    public LivedPlacement(String structure, List<int[]> sites) {
        this(structure, List.copyOf(sites), keysOf(sites));
    }

    private static Set<Long> keysOf(List<int[]> sites) {
        Set<Long> out = new HashSet<>();
        for (int[] s : sites) out.add(net.minecraft.world.level.ChunkPos.pack(s[0], s[1]));
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
