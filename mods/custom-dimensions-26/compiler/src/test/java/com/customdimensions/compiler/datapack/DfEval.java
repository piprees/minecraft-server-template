package com.customdimensions.compiler.datapack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.Map;
import java.util.SplittableRandom;

/**
 * Evaluates compiled density-function JSON at a block column in float, as the game's 26.3 samplers do, for the
 * types the place field and the climate use. Noises are stand-ins: smooth two-octave value noise with the
 * library warp noises' spread (0.274), seeded by {@code seed} and the noise id, so a {@code xz_scale} 0 noise is
 * one constant per seed as the game's is.
 */
final class DfEval {
    private final Map<String, String> files;
    private final long seed;
    private final Map<String, JsonElement> parsed = new HashMap<>();
    private final Map<String, double[]> noises = new HashMap<>();

    DfEval(Map<String, String> files, long seed) {
        this.files = files;
        this.seed = seed;
    }

    JsonElement function(String id) {
        return parsed.computeIfAbsent(id, k -> {
            int colon = k.indexOf(':');
            String text = files.get("data/" + k.substring(0, colon) + "/worldgen/density_function/" + k.substring(colon + 1) + ".json");
            if (text == null) throw new IllegalArgumentException("no density function " + k);
            return JsonParser.parseString(text);
        });
    }

    float at(JsonElement f, int x, int z) {
        if (f.isJsonPrimitive()) {
            if (f.getAsJsonPrimitive().isNumber()) return f.getAsFloat();
            return at(function(f.getAsString()), x, z);
        }
        JsonObject o = f.getAsJsonObject();
        String type = o.get("type").getAsString().substring("minecraft:".length());
        return switch (type) {
            case "add" -> at(o.get("argument1") != null ? o.get("argument1") : o.get("left"), x, z) + at(o.get("right"), x, z);
            case "sub" -> at(o.get("left"), x, z) - at(o.get("right"), x, z);
            case "mul" -> at(o.get("left"), x, z) * at(o.get("right"), x, z);
            case "min" -> Math.min(at(o.get("left"), x, z), at(o.get("right"), x, z));
            case "max" -> Math.max(at(o.get("left"), x, z), at(o.get("right"), x, z));
            case "abs" -> Math.abs(at(o.get("input"), x, z));
            case "square" -> {
                float v = at(o.get("input"), x, z);
                yield v * v;
            }
            case "floor" -> (float) Math.floor(at(o.get("input"), x, z));
            case "cache" -> at(o.get("input"), x, z);
            case "clamp" -> Math.max(o.get("min").getAsFloat(), Math.min(o.get("max").getAsFloat(), at(o.get("input"), x, z)));
            case "gradient" -> gradient(o, x, z);
            case "range_choice" -> {
                float v = at(o.get("input"), x, z);
                yield v >= o.get("min_inclusive").getAsDouble() && v < o.get("max_exclusive").getAsDouble()
                        ? at(o.get("when_in_range"), x, z) : at(o.get("when_out_of_range"), x, z);
            }
            case "noise" -> (float) noise(o.get("noise").getAsString(), x * o.get("xz_scale").getAsDouble(), z * o.get("xz_scale").getAsDouble());
            default -> throw new IllegalArgumentException("DfEval has no " + type);
        };
    }

    private static float gradient(JsonObject o, int x, int z) {
        String axis = o.get("axis").getAsString();
        double c = "x".equals(axis) ? x : "z".equals(axis) ? z : 64;
        double from = o.get("from_coordinate").getAsDouble(), to = o.get("to_coordinate").getAsDouble();
        double t = Math.max(0, Math.min(1, (c - from) / (to - from)));
        return (float) (o.get("from_value").getAsDouble() + t * (o.get("to_value").getAsDouble() - o.get("from_value").getAsDouble()));
    }

    /** A value noise of base feature 256 blocks at scale 1, octaves 1 and 0.5, spread 0.274, offset per seed and id. */
    private double noise(String id, double x, double z) {
        double[] p = noises.computeIfAbsent(id, k -> {
            SplittableRandom r = new SplittableRandom(seed * 31 + k.hashCode());
            return new double[]{r.nextDouble(0, 4096), r.nextDouble(0, 4096), r.nextLong()};
        });
        long salt = (long) p[2];
        double v = octave(x / 256 + p[0], z / 256 + p[1], salt) + 0.5 * octave(x / 128 + p[1], z / 128 + p[0], salt ^ 0x5DEECE66DL);
        return v * 0.274 / 0.477;
    }

    private static double octave(double x, double z, long salt) {
        long xi = (long) Math.floor(x), zi = (long) Math.floor(z);
        double fx = x - xi, fz = z - zi;
        double sx = fx * fx * (3 - 2 * fx), sz = fz * fz * (3 - 2 * fz);
        double a = lattice(xi, zi, salt), b = lattice(xi + 1, zi, salt), c = lattice(xi, zi + 1, salt), d = lattice(xi + 1, zi + 1, salt);
        return (a + (b - a) * sx) + ((c + (d - c) * sx) - (a + (b - a) * sx)) * sz;
    }

    private static double lattice(long x, long z, long salt) {
        long h = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL ^ salt;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return ((h >>> 11) * 0x1.0p-53) * 2 - 1;
    }
}
