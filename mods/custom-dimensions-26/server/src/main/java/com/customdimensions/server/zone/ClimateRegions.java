package com.customdimensions.server.zone;

import com.customdimensions.server.CustomDimensions;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.LongUnaryOperator;

/**
 * {@code regions} on {@code customdimensions:zoned}: the region axes mapped to
 * rank space before the base layout is looked up, so the compiler's region
 * cells (the compiler's {@code RegionLayout}) take their share of the
 * border whatever the seed. A level's climate is sampled once on a
 * {@link #GRID}-square grid over the world border ({@code radius} round the origin);
 * a value maps to its interpolated rank among the samples, from −1 to 1. With
 * {@code columns}, the second axis is ranked among the samples in the same
 * column of the first, so every cell holds its share exactly.
 */
public record ClimateRegions(List<String> axes, List<Float> columns, int radius) {
    /** Samples per side of the measuring grid. */
    static final int GRID = 96;
    /** A column with fewer samples ranks its second axis among all of them. */
    static final int MIN_COLUMN_SAMPLES = 16;

    public static final Codec<ClimateRegions> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.listOf(1, 2).fieldOf("axes").forGetter(ClimateRegions::axes),
            Codec.FLOAT.listOf().optionalFieldOf("columns", List.of()).forGetter(ClimateRegions::columns),
            Codec.intRange(16, 30_000_000).fieldOf("radius").forGetter(ClimateRegions::radius)).apply(i, ClimateRegions::new));

    public ClimateRegions {
        for (String axis : axes) {
            if (!List.of("temperature", "humidity", "continentalness", "erosion", "weirdness").contains(axis)) {
                throw new IllegalArgumentException("regions axis " + axis);
            }
        }
        axes = List.copyOf(axes);
        columns = List.copyOf(columns);
    }

    /** One level's measured ranks. */
    public static final class Ranks {
        final long[][] marginal;
        final long[] cuts;
        final long[][] conditional;

        Ranks(long[][] marginal, long[] cuts, long[][] conditional) {
            this.marginal = marginal;
            this.cuts = cuts;
            this.conditional = conditional;
        }
    }

    /** Keyed by the level's temperature sampler, which a level's random state compiles once. */
    private static final Map<Object, Ranks> MEASURED = Collections.synchronizedMap(new WeakHashMap<>());

    /** {@code point}, its zone channel already removed, with the region axes in rank space. */
    Climate.TargetPoint ranked(Climate.TargetPoint point, Ranks ranks) {
        long[] v = values(point);
        long first = rank(v[0], ranks.marginal[0]);
        long second = 0;
        if (axes.size() == 2) {
            long[] among = ranks.marginal[1];
            if (ranks.conditional != null) {
                int column = column(first, ranks.cuts);
                if (ranks.conditional[column] != null) among = ranks.conditional[column];
            }
            second = rank(v[1], among);
        }
        long t = point.temperature(), h = point.humidity(), c = point.continentalness(), e = point.erosion(), w = point.weirdness();
        for (int a = 0; a < axes.size(); a++) {
            long r = a == 0 ? first : second;
            switch (axes.get(a)) {
                case "temperature" -> t = r;
                case "humidity" -> h = r;
                case "continentalness" -> c = r;
                case "erosion" -> e = r;
                default -> w = r;
            }
        }
        return new Climate.TargetPoint(t, h, c, e, point.depth(), w);
    }

    /** The ranks for the level {@code sampler} belongs to, measured on first use; {@code unchannel} removes a zone channel from a temperature. */
    Ranks ranks(Climate.Sampler sampler, LongUnaryOperator unchannel) {
        Object key = sampler.temperature().sampler();
        return MEASURED.computeIfAbsent(key, k -> measure(sampler, unchannel));
    }

    private Ranks measure(Climate.Sampler sampler, LongUnaryOperator unchannel) {
        long start = System.nanoTime();
        List<DensitySampler.Bound> bounds = new ArrayList<>();
        for (String axis : axes) bounds.add(new DensitySampler.Bound(bound(sampler, axis).sampler(), SamplerContext.EMPTY_UNCACHED));
        int n = GRID * GRID;
        long[][] samples = new long[axes.size()][n];
        double step = 2.0 * radius / (GRID - 1);
        int y = QuartPos.toBlock(QuartPos.fromBlock(64));
        for (int i = 0; i < GRID; i++) {
            int x = QuartPos.toBlock(QuartPos.fromBlock((int) Math.round(-radius + i * step)));
            for (int j = 0; j < GRID; j++) {
                int z = QuartPos.toBlock(QuartPos.fromBlock((int) Math.round(-radius + j * step)));
                for (int a = 0; a < axes.size(); a++) {
                    long q = Climate.quantizeCoord(bounds.get(a).sampleValue(x, y, z));
                    samples[a][i * GRID + j] = "temperature".equals(axes.get(a)) ? unchannel.applyAsLong(q) : q;
                }
            }
        }
        long[][] marginal = new long[axes.size()][];
        for (int a = 0; a < axes.size(); a++) {
            marginal[a] = samples[a].clone();
            Arrays.sort(marginal[a]);
        }
        long[] cuts = new long[columns.size()];
        for (int k = 0; k < cuts.length; k++) cuts[k] = Climate.quantizeCoord(columns.get(k));
        long[][] conditional = null;
        if (axes.size() == 2 && cuts.length > 0) {
            List<List<Long>> byColumn = new ArrayList<>();
            for (int k = 0; k <= cuts.length; k++) byColumn.add(new ArrayList<>());
            for (int s = 0; s < n; s++) {
                byColumn.get(column(rank(samples[0][s], marginal[0]), cuts)).add(samples[1][s]);
            }
            conditional = new long[cuts.length + 1][];
            for (int k = 0; k <= cuts.length; k++) {
                List<Long> c = byColumn.get(k);
                if (c.size() < MIN_COLUMN_SAMPLES) continue;
                conditional[k] = c.stream().mapToLong(Long::longValue).sorted().toArray();
            }
        }
        CustomDimensions.LOG.info("biome regions: measured {} over a {}-block radius from {} samples in {} ms", axes, radius, n,
                (System.nanoTime() - start) / 1_000_000);
        return new Ranks(marginal, cuts, conditional);
    }

    private long[] values(Climate.TargetPoint p) {
        long[] v = new long[axes.size()];
        for (int a = 0; a < axes.size(); a++) {
            v[a] = switch (axes.get(a)) {
                case "temperature" -> p.temperature();
                case "humidity" -> p.humidity();
                case "continentalness" -> p.continentalness();
                case "erosion" -> p.erosion();
                default -> p.weirdness();
            };
        }
        return v;
    }

    private static DensitySampler.Bound bound(Climate.Sampler s, String axis) {
        return switch (axis) {
            case "temperature" -> s.temperature();
            case "humidity" -> s.humidity();
            case "continentalness" -> s.continentalness();
            case "erosion" -> s.erosion();
            default -> s.weirdness();
        };
    }

    /** The column a first-axis rank falls in: the number of cuts at or below it. */
    static int column(long rank, long[] cuts) {
        int c = 0;
        while (c < cuts.length && rank >= cuts[c]) c++;
        return c;
    }

    /**
     * {@code value}'s rank among {@code sorted}, interpolated between neighbouring samples and quantised, from
     * −1 (at or below the lowest) to 1 (at or above the highest).
     */
    static long rank(long value, long[] sorted) {
        int n = sorted.length;
        if (n == 0) return 0;
        double f;
        if (value <= sorted[0]) {
            f = 0;
        } else if (value >= sorted[n - 1]) {
            f = 1;
        } else {
            int hi = Arrays.binarySearch(sorted, value);
            int lo;
            if (hi >= 0) {
                lo = hi;
                while (lo > 0 && sorted[lo - 1] == value) lo--;
                while (hi < n - 1 && sorted[hi + 1] == value) hi++;
                f = (lo + hi) / 2.0 / (n - 1);
            } else {
                hi = -hi - 1;
                lo = hi - 1;
                f = (lo + (double) (value - sorted[lo]) / (sorted[hi] - sorted[lo])) / (n - 1);
            }
        }
        return Climate.quantizeCoord((float) (-1 + 2 * f));
    }
}
