package com.customdimensions.server.lod;

import com.customdimensions.server.CustomDimensions;
import com.customdimensions.server.render.fast.LodColumn;
import com.customdimensions.server.render.fast.LodSource;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBiomeWrapper;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Distant Horizons' world generator for one level, answered from the
 * fast-render core ({@link LodSource}) instead of generated chunks: each
 * requested data source of 64 × 64 columns at any detail level is filled
 * with its columns' segments as block-sized data points, from the level's
 * floor to its top, marked {@link EDhApiWorldGenerationStep#LIGHT} so DH
 * takes them as finished. Runs on DH's world generator pool; each thread
 * samples with its own {@code ColumnModel}. Whatever generator mode DH is set
 * to, the answer is the same: noise terrain with its surface, fluids and
 * biomes, without structures, features or carvers.
 */
public final class DhLodGenerator implements IDhApiWorldGenerator {
    /** The coarsest detail level answered (a column every 4,096 blocks), as DH's own surface generator. */
    public static final byte LARGEST_DETAIL = 12;
    /** {@code true} has DH check every column it is given; the default leaves it off, as DH advises for a release. */
    public static final String VALIDATE_PROPERTY = "customdimensions.dh.validate";

    private final LodSource source;
    private final IDhApiLevelWrapper level;
    private final boolean validate = Boolean.getBoolean(VALIDATE_PROPERTY);
    private final Map<BlockState, IDhApiBlockStateWrapper> blocks = new ConcurrentHashMap<>();
    private final Map<Holder<Biome>, IDhApiBiomeWrapper> biomes = new ConcurrentHashMap<>();
    private final AtomicLongArray logged = new AtomicLongArray(LARGEST_DETAIL + 1);
    private final AtomicLong failures = new AtomicLong();

    public DhLodGenerator(LodSource source, IDhApiLevelWrapper level) {
        this.source = source;
        this.level = level;
    }

    public LodSource source() {
        return source;
    }

    public long failures() {
        return failures.get();
    }

    @Override
    public byte getSmallestDataDetailLevel() {
        return 0;
    }

    @Override
    public byte getLargestDataDetailLevel() {
        return LARGEST_DETAIL;
    }

    @Override
    public boolean runApiValidation() {
        return validate;
    }

    @Override
    public EDhApiWorldGeneratorReturnType getReturnType() {
        return EDhApiWorldGeneratorReturnType.API_DATA_SOURCES;
    }

    @Override
    public CompletableFuture<Void> generateLod(int chunkPosMinX, int chunkPosMinZ, int lodPosX, int lodPosZ, byte detailLevel,
                                               IDhApiFullDataSource pooledFullDataSource, EDhApiDistantGeneratorMode generatorMode,
                                               ExecutorService worldGeneratorThreadPool, Consumer<IDhApiFullDataSource> resultConsumer) {
        return CompletableFuture.runAsync(() -> {
            try {
                fill(chunkPosMinX * 16, chunkPosMinZ * 16, detailLevel, pooledFullDataSource);
            } catch (RuntimeException e) {
                if (failures.getAndIncrement() == 0) {
                    CustomDimensions.LOG.error("Distant Horizons LODs for {}: detail {} at {},{} failed", source.level(), detailLevel,
                            chunkPosMinX * 16, chunkPosMinZ * 16, e);
                }
                throw e;
            }
            resultConsumer.accept(pooledFullDataSource);
        }, worldGeneratorThreadPool);
    }

    /** Fills {@code target} with the columns from block {@code (x0, z0)} at {@code detail}. */
    void fill(int x0, int z0, int detail, IDhApiFullDataSource target) {
        int width = target.getWidthInDataColumns();
        if (width != LodSource.WIDTH) {
            throw new IllegalStateException("a data source " + width + " columns wide; the generator answers " + LodSource.WIDTH);
        }
        LodSource.Column[] columns = source.columns(x0, z0, detail);
        int minY = source.minY();
        List<DhApiTerrainDataPoint> points = new ArrayList<>();
        for (int j = 0; j < width; j++) {
            for (int i = 0; i < width; i++) {
                points.clear();
                for (LodColumn.Segment<BlockState, Holder<Biome>> s : source.segments(columns[i + width * j])) {
                    points.add(DhApiTerrainDataPoint.create((byte) 0, s.blockLight(), s.skyLight(), s.bottom() - minY, s.top() - minY,
                            block(s.block()), biome(s.biome())));
                }
                target.setApiDataPointColumn(i, j, EDhApiWorldGenerationStep.LIGHT, points);
            }
        }
        if (logged.getAndIncrement(Math.min(detail, LARGEST_DETAIL)) == 0) {
            CustomDimensions.LOG.info("Distant Horizons LODs for {} come from customdimensions: first detail {} request at {},{} "
                    + "({} requests so far)", source.level(), detail, x0, z0, source.requests());
        }
    }

    private IDhApiBlockStateWrapper block(BlockState state) {
        return blocks.computeIfAbsent(state, s -> s.isAir() ? DhApi.Delayed.wrapperFactory.getAirBlockStateWrapper()
                : DhApi.Delayed.wrapperFactory.getBlockStateWrapper(new Object[] {s}, level));
    }

    private IDhApiBiomeWrapper biome(Holder<Biome> biome) {
        return biomes.computeIfAbsent(biome, b -> DhApi.Delayed.wrapperFactory.getBiomeWrapper(new Object[] {b}, level));
    }

    @Override
    public void preGeneratorTaskStart() {}

    @Override
    public void close() {
        source.close();
    }
}
