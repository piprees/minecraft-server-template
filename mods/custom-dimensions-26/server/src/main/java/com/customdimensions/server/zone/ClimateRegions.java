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
import java.util.Optional;
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
 * column of the first, so every cell holds its share exactly. With
 * {@code areas}, a point is ranked among the samples of the first area it
 * falls in (each a box on continentalness, weirdness and depth, with its own
 * columns), so each role's biomes hold their shares of their own ground.
 */
public record ClimateRegions(List<String> axes, List<Float> columns, int radius, List<Area> areas) {
    /** Samples per side of the measuring grid. */
    static final int GRID = 96;
    /** A column with fewer samples ranks its second axis among all of them. */
    static final int MIN_COLUMN_SAMPLES = 16;
    /** An area with fewer samples ranks among all of them. */
    static final int MIN_AREA_SAMPLES = 16;

    /** One role's ground; an absent bound is everything. Depth bounds a lookup only: the samples are surface climate. */
    public record Area(String role, Optional<List<Float>> continentalness, Optional<List<Float>> weirdness,
                       Optional<List<Float>> depth, List<Float> columns) {
        public static final Codec<Area> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.optionalFieldOf("role", "").forGetter(Area::role),
                Codec.FLOAT.listOf(2, 2).optionalFieldOf("continentalness").forGetter(Area::continentalness),
                Codec.FLOAT.listOf(2, 2).optionalFieldOf("weirdness").forGetter(Area::weirdness),
                Codec.FLOAT.listOf(2, 2).optionalFieldOf("depth").forGetter(Area::depth),
                Codec.FLOAT.listOf().optionalFieldOf("columns", List.of()).forGetter(Area::columns)).apply(i, Area::new));

        /** Whether quantised continentalness {@code c}, weirdness {@code w} and depth {@code d} lie inside. */
        boolean contains(long c, long w, long d) {
            return within(continentalness, c) && within(weirdness, w) && within(depth, d);
        }

        boolean containsSample(long c, long w) {
            return within(continentalness, c) && within(weirdness, w);
        }

        private static boolean within(Optional<List<Float>> range, long v) {
            return range.isEmpty() || (v >= Climate.quantizeCoord(range.get().get(0)) && v <= Climate.quantizeCoord(range.get().get(1)));
        }
    }

    public static final Codec<ClimateRegions> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.listOf(1, 2).fieldOf("axes").forGetter(ClimateRegions::axes),
            Codec.FLOAT.listOf().optionalFieldOf("columns", List.of()).forGetter(ClimateRegions::columns),
            Codec.intRange(16, 30_000_000).fieldOf("radius").forGetter(ClimateRegions::radius),
            Area.CODEC.listOf().optionalFieldOf("areas", List.of()).forGetter(ClimateRegions::areas)).apply(i, ClimateRegions::new));

    public ClimateRegions {
        for (String axis : axes) {
            if (!List.of("temperature", "humidity", "continentalness", "erosion", "weirdness").contains(axis)) {
                throw new IllegalArgumentException("regions axis " + axis);
            }
        }
        axes = List.copyOf(axes);
        columns = List.copyOf(columns);
        areas = List.copyOf(areas);
    }

    public ClimateRegions(List<String> axes, List<Float> columns, int radius) {
        this(axes, columns, radius, List.of());
    }

    /** The areas a point is ranked in, in order; one covering everything without {@code areas}. */
    List<Area> effectiveAreas() {
        return areas.isEmpty() ? List.of(new Area("", Optional.empty(), Optional.empty(), Optional.empty(), columns)) : areas;
    }

    /** One area's measured ranks. */
    static final class AreaRanks {
        final long[][] marginal;
        final long[] cuts;
        final long[][] conditional;

        AreaRanks(long[][] marginal, long[] cuts, long[][] conditional) {
            this.marginal = marginal;
            this.cuts = cuts;
            this.conditional = conditional;
        }
    }

    /** One level's measured ranks, per area. */
    public static final class Ranks {
        final List<AreaRanks> areas;

        Ranks(List<AreaRanks> areas) {
            this.areas = areas;
        }
    }

    /** Keyed by the level's temperature sampler, which a level's random state compiles once. */
    private static final Map<Object, Ranks> MEASURED = Collections.synchronizedMap(new WeakHashMap<>());

    /** {@code point}, its zone channel already removed, with the region axes in rank space. */
    Climate.TargetPoint ranked(Climate.TargetPoint point, Ranks ranks) {
        List<Area> list = effectiveAreas();
        int index = list.size() - 1;
        for (int k = 0; k < list.size(); k++) {
            if (list.get(k).contains(point.continentalness(), point.weirdness(), point.depth())) {
                index = k;
                break;
            }
        }
        AreaRanks area = ranks.areas.get(index);
        long[] v = values(point);
        long first = rank(v[0], area.marginal[0]);
        long second = 0;
        if (axes.size() == 2) {
            long[] among = area.marginal[1];
            if (area.conditional != null) {
                int column = column(first, area.cuts);
                if (area.conditional[column] != null) among = area.conditional[column];
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
        DensitySampler.Bound cont = new DensitySampler.Bound(sampler.continentalness().sampler(), SamplerContext.EMPTY_UNCACHED);
        DensitySampler.Bound weird = new DensitySampler.Bound(sampler.weirdness().sampler(), SamplerContext.EMPTY_UNCACHED);
        boolean placed = !areas.isEmpty();
        int n = GRID * GRID;
        long[][] samples = new long[axes.size()][n];
        long[] conts = new long[placed ? n : 0], weirds = new long[placed ? n : 0];
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
                if (placed) {
                    conts[i * GRID + j] = Climate.quantizeCoord(cont.sampleValue(x, y, z));
                    weirds[i * GRID + j] = Climate.quantizeCoord(weird.sampleValue(x, y, z));
                }
            }
        }
        List<AreaRanks> out = new ArrayList<>();
        StringBuilder counts = new StringBuilder();
        for (Area area : effectiveAreas()) {
            int m = 0;
            int[] in = new int[n];
            for (int s = 0; s < n; s++) if (!placed || area.containsSample(conts[s], weirds[s])) in[m++] = s;
            if (m < MIN_AREA_SAMPLES) {
                for (int s = 0; s < n; s++) in[s] = s;
                m = n;
            }
            long[][] own = new long[axes.size()][m];
            for (int a = 0; a < axes.size(); a++) for (int k = 0; k < m; k++) own[a][k] = samples[a][in[k]];
            out.add(measureArea(own, area.columns()));
            if (placed) counts.append(counts.isEmpty() ? "" : ", ").append(area.role().isEmpty() ? "area" : area.role()).append(' ').append(m);
        }
        CustomDimensions.LOG.info("biome regions: measured {} over a {}-block radius from {} samples in {} ms{}", axes, radius, n,
                (System.nanoTime() - start) / 1_000_000, placed ? " (" + counts + ")" : "");
        return new Ranks(out);
    }

    /** The marginal and per-column ranks of {@code samples} (one array per region axis). */
    static AreaRanks measureArea(long[][] samples, List<Float> columnCuts) {
        int n = samples[0].length;
        int dims = samples.length;
        long[][] marginal = new long[dims][];
        for (int a = 0; a < dims; a++) {
            marginal[a] = samples[a].clone();
            Arrays.sort(marginal[a]);
        }
        long[] cuts = new long[columnCuts.size()];
        for (int k = 0; k < cuts.length; k++) cuts[k] = Climate.quantizeCoord(columnCuts.get(k));
        long[][] conditional = null;
        if (dims == 2 && cuts.length > 0) {
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
        return new AreaRanks(marginal, cuts, conditional);
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
