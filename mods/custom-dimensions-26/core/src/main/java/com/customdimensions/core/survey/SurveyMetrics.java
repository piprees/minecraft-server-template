package com.customdimensions.core.survey;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Survey metrics read from the whole border rather than the patches: the
 * structure census from placement, spawn relief, ground blocks by height
 * band, and the luminance of the definition's sky and fog colours.
 */
public final class SurveyMetrics {
    /** Rings the census counts sites in, from spawn to the border. */
    public static final int RINGS = 5;

    private SurveyMetrics() {
    }

    /** One structure site: the structure id and its chunk's centre in blocks. */
    public record Site(String structure, int x, int z) {
    }

    /**
     * {@code structures.census}: per structure, its sites inside the border ({@code radius} round the origin,
     * square), the nearest and mean distance from spawn in blocks, and counts in {@link #RINGS} rings of equal
     * width from spawn to {@code radius} (a site past the last ring, in a corner, counts in it).
     */
    public static JsonObject census(List<Site> sites, int spawnX, int spawnZ, int radius) {
        Map<String, List<Double>> by = new TreeMap<>();
        for (Site s : sites) {
            if (Math.abs(s.x()) > radius || Math.abs(s.z()) > radius) continue;
            by.computeIfAbsent(s.structure(), k -> new ArrayList<>()).add(Math.hypot(s.x() - spawnX, s.z() - spawnZ));
        }
        JsonObject out = new JsonObject();
        double width = (double) radius / RINGS;
        for (Map.Entry<String, List<Double>> e : by.entrySet()) {
            double[] d = e.getValue().stream().mapToDouble(Double::doubleValue).toArray();
            JsonObject o = new JsonObject();
            o.addProperty("count", d.length);
            o.addProperty("nearestBlocks", Math.round(Arrays.stream(d).min().orElse(0)));
            o.addProperty("meanBlocks", Math.round(Arrays.stream(d).average().orElse(0)));
            int[] rings = new int[RINGS];
            for (double v : d) rings[Math.min(RINGS - 1, (int) (v / width))]++;
            JsonArray r = new JsonArray();
            for (int n : rings) r.add(n);
            o.add("byRing", r);
            out.add(e.getKey(), o);
        }
        return out;
    }

    /** The ring bounds in blocks from spawn, for the census note. */
    public static List<Integer> ringBounds(int radius) {
        List<Integer> out = new ArrayList<>();
        for (int k = 1; k <= RINGS; k++) out.add((int) Math.round((double) radius * k / RINGS));
        return out;
    }

    /** {@code height} minus the median of {@code heights} (NaN entries, the void, left out); null when either is missing. */
    public static Double heightAboveMedian(double height, double[] heights) {
        double[] h = Arrays.stream(heights).filter(v -> !Double.isNaN(v)).toArray();
        if (h.length == 0 || Double.isNaN(height)) return null;
        return Stats.round1(height - Stats.percentile(h, 50));
    }

    /**
     * {@code surface.groundBlocksPercentByBand}: the columns split into thirds of surface height (at the 33rd
     * and 67th percentiles of {@code heights}), and in each band the share of its columns per ground block,
     * at most {@code limit} blocks; {@code bounds} gives the two cut heights.
     */
    public static JsonObject groundByBand(int[] heights, String[] grounds, int limit) {
        JsonObject out = new JsonObject();
        if (heights.length == 0) return out;
        double[] h = Arrays.stream(heights).asDoubleStream().toArray();
        double low = Stats.percentile(h, 100.0 / 3), high = Stats.percentile(h, 200.0 / 3);
        List<Map<String, Long>> counts = List.of(new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>());
        long[] totals = new long[3];
        for (int i = 0; i < heights.length; i++) {
            int band = heights[i] < low ? 0 : heights[i] < high ? 1 : 2;
            totals[band]++;
            if (grounds[i] != null) counts.get(band).merge(grounds[i], 1L, Long::sum);
        }
        JsonArray bounds = new JsonArray();
        bounds.add(Stats.round1(low));
        bounds.add(Stats.round1(high));
        out.add("bounds", bounds);
        String[] names = {"low", "middle", "high"};
        for (int b = 0; b < 3; b++) {
            JsonObject o = new JsonObject();
            long total = totals[b];
            counts.get(b).entrySet().stream().sorted((x, y) -> Long.compare(y.getValue(), x.getValue())).limit(limit)
                    .forEach(e -> o.addProperty(e.getKey(), Stats.percent(e.getValue(), total)));
            out.add(names[b], o);
        }
        return out;
    }

    /** The relative luminance (0 black to 1 white, sRGB as WCAG defines it) of {@code #rrggbb} or {@code rrggbb}; null for anything else. */
    public static Double luminance(String colour) {
        if (colour == null) return null;
        String hex = colour.startsWith("#") ? colour.substring(1) : colour;
        if (hex.length() == 8) hex = hex.substring(2);
        if (!hex.matches("[0-9a-fA-F]{6}")) return null;
        double[] c = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16) / 255.0;
            c[i] = v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return Math.round((0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]) * 1000) / 1000.0;
    }
}
