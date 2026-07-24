package com.customdimensions.dimension;

import com.customdimensions.MultiverseServer;
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

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A structure placement that generates at EXACT configured chunk positions
 * ("structures": {"force": [{structure, x, z}]} in a dimension config).
 *
 * Extends RandomSpreadStructurePlacement deliberately — vanilla's
 * locateStructure only searches concentric_rings and random_spread
 * placements, and its random-spread search probes chunk positions in
 * spacing-sized rings, calling {@link #getStartChunk(long, int, int)} on
 * each probe (ChunkGenerator.locateRandomSpreadStructure). Overriding
 * getStartChunk (region -> forced position) and isStartChunk (exact
 * membership) makes both generation and /locate exact. A second bonus:
 * DimensionStructures' rescale() guards on
 * {@code getClass() != RandomSpreadStructurePlacement.class}, so fixed
 * placements are automatically exempt from density/theme rescaling.
 *
 * One forced position per spacing-region is locatable; extra positions in
 * the same region still GENERATE (isStartChunk is a set-membership test)
 * but locate returns the region's registered one — warned at build time.
 *
 * Instances live only in per-world rebuilt StructurePlacementCalculators
 * (never serialised into level.dat), but the type is registered anyway so
 * getType() stays honest.
 */
public class FixedStructurePlacement extends RandomSpreadStructurePlacement {

    /** Ring probes cover spacing*locate-radius chunks; 32 matches vanilla
     *  village coverage (~51k blocks at the default 100-ring radius). */
    public static final int SPACING = 32;

    public static final MapCodec<FixedStructurePlacement> CODEC =
            RecordCodecBuilder.mapCodec(instance -> instance.group(
                    Codec.list(Codec.INT.listOf())
                            .fieldOf("positions")
                            .forGetter(FixedStructurePlacement::positionPairs)
            ).apply(instance, FixedStructurePlacement::fromPairs));

    public static final StructurePlacementType<FixedStructurePlacement> TYPE = () -> CODEC;

    /**
     * The pure region/membership maths, separated so unit tests never touch
     * StructurePlacement's registry-bound static init (Bootstrap-only). The
     * roller mirror in scripts/seed/structure_placement.py follows the same
     * contract: forced positions are constants.
     */
    static final class Index {
        private final List<ChunkPos> positions;
        private final Set<Long> chunkKeys = new HashSet<>();
        private final Map<Long, ChunkPos> byRegion = new HashMap<>();

        Index(List<ChunkPos> positions) {
            this.positions = List.copyOf(positions);
            for (ChunkPos pos : this.positions) {
                chunkKeys.add(pos.toLong());
                long region = regionKey(Math.floorDiv(pos.x, SPACING), Math.floorDiv(pos.z, SPACING));
                ChunkPos existing = byRegion.putIfAbsent(region, pos);
                if (existing != null) {
                    MultiverseServer.LOGGER.warn(
                            "Fixed structure placements {} and {} share a {}-chunk region — "
                            + "both generate, but /locate only finds {}",
                            existing, pos, SPACING, existing);
                }
            }
        }

        private static long regionKey(int regionX, int regionZ) {
            return ((long) regionX << 32) ^ (regionZ & 0xFFFFFFFFL);
        }

        ChunkPos startFor(int chunkX, int chunkZ) {
            int regionX = Math.floorDiv(chunkX, SPACING);
            int regionZ = Math.floorDiv(chunkZ, SPACING);
            ChunkPos forcedPos = byRegion.get(regionKey(regionX, regionZ));
            if (forcedPos != null) {
                return forcedPos;
            }
            // Empty region: any position that can never pass isForced works;
            // the region origin is cheap and stable.
            return new ChunkPos(regionX * SPACING, regionZ * SPACING);
        }

        boolean isForced(int chunkX, int chunkZ) {
            return chunkKeys.contains(ChunkPos.toLong(chunkX, chunkZ));
        }

        List<ChunkPos> positions() {
            return positions;
        }
    }

    private final Index index;

    public FixedStructurePlacement(List<ChunkPos> positions) {
        super(Vec3i.ZERO, StructurePlacement.FrequencyReductionMethod.DEFAULT,
                1.0f, 0, Optional.empty(), SPACING, SPACING / 2, SpreadType.LINEAR);
        this.index = new Index(positions);
    }

    private static FixedStructurePlacement fromPairs(List<List<Integer>> pairs) {
        return new FixedStructurePlacement(
                pairs.stream().map(p -> new ChunkPos(p.get(0), p.get(1))).toList());
    }

    private List<List<Integer>> positionPairs() {
        return index.positions().stream().map(p -> List.of(p.x, p.z)).toList();
    }

    @Override
    public ChunkPos getStartChunk(long seed, int chunkX, int chunkZ) {
        return index.startFor(chunkX, chunkZ);
    }

    @Override
    protected boolean isStartChunk(StructurePlacementCalculator calculator, int chunkX, int chunkZ) {
        return index.isForced(chunkX, chunkZ);
    }

    @Override
    public StructurePlacementType<?> getType() {
        return TYPE;
    }
}
