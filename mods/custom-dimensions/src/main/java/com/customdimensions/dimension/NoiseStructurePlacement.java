package com.customdimensions.dimension;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.gen.chunk.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.gen.chunk.placement.SpreadType;
import net.minecraft.world.gen.chunk.placement.StructurePlacement;
import net.minecraft.world.gen.chunk.placement.StructurePlacementCalculator;
import net.minecraft.world.gen.chunk.placement.StructurePlacementType;

import java.util.List;
import java.util.Optional;

/**
 * Noise-driven placement for one structure group: a structure generates where
 * the group's noise field peaks, weighted by a radial curve, rather than on a
 * fixed grid.
 *
 * Extends {@link RandomSpreadStructurePlacement} for the reasons
 * {@code FixedStructurePlacement} documents at length: vanilla's
 * {@code locateStructure} only searches concentric-ring and random-spread
 * placements, and its random-spread search probes chunk positions in
 * spacing-sized rings, calling {@link #getStartChunk} for each probe. Both
 * overrides together make generation and {@code /locate} agree.
 * {@code DimensionStructures.rescale} guards on
 * {@code getClass() != RandomSpreadStructurePlacement.class}, so these are
 * automatically exempt from density rescaling — a placement that has already
 * consumed the density profile must not have it applied twice.
 *
 * SPACING is the exclusion radius doubled, so a locate cell holds at most one
 * placement and none are stepped over. All the maths lives in
 * {@link NoiseFieldIndex} so it can be tested without Minecraft's Bootstrap.
 *
 * Instances exist only inside per-world rebuilt StructurePlacementCalculators
 * and are never serialised into level.dat, but the type is registered so
 * {@link #getType()} stays honest.
 */
public class NoiseStructurePlacement extends RandomSpreadStructurePlacement {

    /**
     * Codec exists only to satisfy the placement-type contract. These
     * placements are built from live config, never round-tripped through
     * data, so it serialises the resolved positions rather than trying to
     * re-derive them from a seed the codec has no access to.
     */
    public static final MapCodec<NoiseStructurePlacement> CODEC =
            RecordCodecBuilder.mapCodec(instance -> instance.group(
                    Codec.list(Codec.INT.listOf())
                            .fieldOf("positions")
                            .forGetter(p -> List.of())
            ).apply(instance, positions -> {
                throw new UnsupportedOperationException(
                        "NoiseStructurePlacement is runtime-only and cannot be deserialised");
            }));

    public static final StructurePlacementType<NoiseStructurePlacement> TYPE = () -> CODEC;

    private final NoiseFieldIndex index;
    private final String group;

    public NoiseStructurePlacement(String group, NoiseFieldIndex index) {
        super(Vec3i.ZERO, StructurePlacement.FrequencyReductionMethod.DEFAULT,
                1.0f, 0, Optional.empty(),
                index.spacing(), index.spacing() / 2, SpreadType.LINEAR);
        this.group = group;
        this.index = index;
    }

    public NoiseStructurePlacement(String group, long noiseSeed, NoiseProfile profile,
                                   int exclusion, double[] radial, int radiusChunks,
                                   int spawnChunkX, int spawnChunkZ) {
        this(group, new NoiseFieldIndex(noiseSeed, profile, exclusion, radial,
                radiusChunks, spawnChunkX, spawnChunkZ));
    }

    @Override
    public ChunkPos getStartChunk(long seed, int chunkX, int chunkZ) {
        return index.startFor(chunkX, chunkZ);
    }

    @Override
    protected boolean isStartChunk(StructurePlacementCalculator calculator, int chunkX, int chunkZ) {
        return index.isPlacement(chunkX, chunkZ);
    }

    @Override
    public StructurePlacementType<?> getType() {
        return TYPE;
    }

    public String group() {
        return group;
    }

    public NoiseFieldIndex index() {
        return index;
    }
}
