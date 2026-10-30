package com.customdimensions.compiler.datapack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Multi-noise parameter entries ({@code {"biome", "parameters"}}) as vanilla
 * writes them: each climate axis is a point or a {@code [min, max]} range.
 */
public final class MultiNoise {

    /** The climate axes; {@code offset} is a fitness penalty, not a region. */
    public static final List<String> AXES =
            List.of("temperature", "humidity", "continentalness", "erosion", "weirdness", "depth");

    private MultiNoise() {
    }

    /** One entry covering all of climate space. */
    public static JsonObject fullRange(String biome) {
        JsonObject parameters = new JsonObject();
        for (String axis : List.of("continentalness", "depth", "erosion", "humidity")) {
            parameters.add(axis, range(-2.0, 2.0));
        }
        parameters.addProperty("offset", 0.0);
        for (String axis : List.of("temperature", "weirdness")) {
            parameters.add(axis, range(-2.0, 2.0));
        }
        JsonObject entry = new JsonObject();
        entry.addProperty("biome", biome);
        entry.add("parameters", parameters);
        return entry;
    }

    /**
     * True when two entries share climate space of positive extent on every axis:
     * ranges overlap past a shared endpoint, a point lies strictly inside a range,
     * or two points are equal.
     */
    public static boolean overlap(JsonObject a, JsonObject b) {
        JsonObject pa = a.getAsJsonObject("parameters");
        JsonObject pb = b.getAsJsonObject("parameters");
        for (String axis : AXES) {
            if (!overlapAxis(bounds(pa.get(axis)), bounds(pb.get(axis)))) {
                return false;
            }
        }
        return true;
    }

    /** The first overlapping pair's indices, or null when the entries are disjoint. */
    public static int[] firstOverlap(JsonArray entries) {
        int n = entries.size();
        double[][][] b = new double[n][AXES.size()][];
        for (int i = 0; i < n; i++) {
            JsonObject p = entries.get(i).getAsJsonObject().getAsJsonObject("parameters");
            for (int k = 0; k < AXES.size(); k++) {
                b[i][k] = bounds(p.get(AXES.get(k)));
            }
        }
        for (int i = 0; i < n; i++) {
            next:
            for (int j = i + 1; j < n; j++) {
                for (int k = 0; k < AXES.size(); k++) {
                    if (!overlapAxis(b[i][k], b[j][k])) {
                        continue next;
                    }
                }
                return new int[] {i, j};
            }
        }
        return null;
    }

    /**
     * The entries made disjoint: where a later entry overlaps an earlier one, it
     * is replaced by the boxes of itself that lie outside the earlier entry, so
     * earlier entries keep their climate space. Disjoint input comes back equal.
     */
    public static JsonArray carve(JsonArray entries) {
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement e : entries) {
            out.add(e.getAsJsonObject());
        }
        int limit = 64 + 16 * out.size();
        for (int guard = 0; guard < limit; guard++) {
            JsonArray current = new JsonArray();
            out.forEach(current::add);
            int[] pair = firstOverlap(current);
            if (pair == null) {
                return current;
            }
            JsonObject earlier = out.get(pair[0]);
            JsonObject later = out.remove(pair[1]);
            out.addAll(pair[1], subtract(later, earlier));
        }
        throw new IllegalStateException("multi-noise entries still overlap after " + limit + " carves");
    }

    /** {@code b} minus {@code a}: up to two boxes per axis, none overlapping {@code a}. */
    static List<JsonObject> subtract(JsonObject b, JsonObject a) {
        JsonObject pa = a.getAsJsonObject("parameters");
        JsonObject pb = b.getAsJsonObject("parameters");
        double[][] rest = new double[AXES.size()][];
        for (int k = 0; k < AXES.size(); k++) {
            rest[k] = bounds(pb.get(AXES.get(k)));
        }
        List<JsonObject> pieces = new ArrayList<>();
        for (int k = 0; k < AXES.size(); k++) {
            double[] cut = bounds(pa.get(AXES.get(k)));
            double[] r = rest[k];
            if (r[0] < cut[0]) {
                pieces.add(box(b, rest, k, new double[] {r[0], cut[0]}));
            }
            if (cut[1] < r[1]) {
                pieces.add(box(b, rest, k, new double[] {cut[1], r[1]}));
            }
            rest[k] = new double[] {Math.max(r[0], cut[0]), Math.min(r[1], cut[1])};
        }
        return pieces;
    }

    private static JsonObject box(JsonObject template, double[][] rest, int axis, double[] range) {
        JsonObject parameters = new JsonObject();
        for (Map.Entry<String, JsonElement> e : template.getAsJsonObject("parameters").entrySet()) {
            int k = AXES.indexOf(e.getKey());
            if (k < 0) {
                parameters.add(e.getKey(), e.getValue().deepCopy());
                continue;
            }
            double[] v = k == axis ? range : rest[k];
            if (v[0] == v[1]) {
                parameters.addProperty(e.getKey(), v[0]);
            } else {
                parameters.add(e.getKey(), range(v[0], v[1]));
            }
        }
        JsonObject entry = new JsonObject();
        entry.add("biome", template.get("biome").deepCopy());
        entry.add("parameters", parameters);
        return entry;
    }

    static boolean overlapAxis(double[] a, double[] b) {
        boolean pointA = a[0] == a[1];
        boolean pointB = b[0] == b[1];
        if (pointA && pointB) {
            return a[0] == b[0];
        }
        if (pointA) {
            return b[0] < a[0] && a[0] < b[1];
        }
        if (pointB) {
            return a[0] < b[0] && b[0] < a[1];
        }
        return Math.max(a[0], b[0]) < Math.min(a[1], b[1]);
    }

    private static double[] bounds(JsonElement value) {
        if (value == null) {
            return new double[] {0.0, 0.0};
        }
        if (value.isJsonArray()) {
            return new double[] {value.getAsJsonArray().get(0).getAsDouble(), value.getAsJsonArray().get(1).getAsDouble()};
        }
        double v = value.getAsDouble();
        return new double[] {v, v};
    }

    private static JsonArray range(double min, double max) {
        JsonArray a = new JsonArray();
        a.add(min);
        a.add(max);
        return a;
    }
}
