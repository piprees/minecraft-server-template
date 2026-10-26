package com.customdimensions.compiler.terrain;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.TreeMap;

/**
 * The library's own noises, {@code <namespace>:terrain/<name>}, each seeded
 * apart from vanilla's by its id, and the spread of each one's values, which
 * turns a coverage share into a threshold.
 */
public final class Noises {

    private record Spec(int baseOctave, double[] amplitudes, double sigma) {}

    /** Base octave, octave amplitudes and the standard deviation of the sampled value. */
    private static final Map<String, Spec> SPECS = new TreeMap<>(Map.ofEntries(
            Map.entry("altitude", new Spec(-8, new double[]{1, 0.5}, 0.274)),
            Map.entry("channel", new Spec(-8, new double[]{1, 0.5, 0.25}, 0.248)),
            Map.entry("detail", new Spec(-6, new double[]{1, 0.5, 0.25}, 0.248)),
            Map.entry("gorge", new Spec(-8, new double[]{1, 0.5, 0.25}, 0.248)),
            Map.entry("grade", new Spec(-9, new double[]{1, 0.5}, 0.294)),
            Map.entry("islands", new Spec(-7, new double[]{1, 1, 0.5, 0.25}, 0.293)),
            Map.entry("plates", new Spec(-7, new double[]{1, 0.5}, 0.274)),
            Map.entry("shatter", new Spec(-6, new double[]{1, 0.5, 0.25}, 0.251)),
            Map.entry("spires", new Spec(-5, new double[]{1, 0.3}, 0.254)),
            Map.entry("voids", new Spec(-7, new double[]{1, 0.5, 0.25}, 0.252)),
            Map.entry("wear", new Spec(-8, new double[]{1, 0.5, 0.25}, 0.248))));

    private Noises() {
    }

    static boolean exists(String name) {
        return SPECS.containsKey(name);
    }

    public static Iterable<String> names() {
        return SPECS.keySet();
    }

    /** The standard deviation of a library noise's value, measured by the self-test over 20,000 points. */
    public static double sigma(String name) {
        return SPECS.get(name).sigma();
    }

    /** The noise file in 26.3's format. */
    static JsonObject json(String name) {
        Spec spec = SPECS.get(name);
        JsonObject o = new JsonObject();
        JsonArray amplitudes = new JsonArray();
        for (double a : spec.amplitudes()) {
            amplitudes.add(a);
        }
        o.add("amplitude_modifiers", amplitudes);
        o.addProperty("base_amplitude", 1.0);
        o.addProperty("base_octave", spec.baseOctave());
        o.addProperty("octave_count", spec.amplitudes().length);
        return o;
    }

    /** The value a normally spread noise exceeds over {@code coverage} of the map. */
    static double threshold(double coverage, double sigma) {
        return sigma * inverseNormal(1 - coverage);
    }

    /** The standard normal quantile (Acklam's rational approximation, relative error below 1.2e-9). */
    static double inverseNormal(double p) {
        if (!(p > 0 && p < 1)) {
            throw new IllegalArgumentException("p must be in (0, 1): " + p);
        }
        double[] a = {-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02,
                1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00};
        double[] b = {-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02,
                6.680131188771972e+01, -1.328068155288572e+01};
        double[] c = {-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00,
                -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00};
        double[] d = {7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00};
        double low = 0.02425;
        if (p < low) {
            double q = Math.sqrt(-2 * Math.log(p));
            return (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                    / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        }
        if (p > 1 - low) {
            return -inverseNormal(1 - p);
        }
        double q = p - 0.5;
        double r = q * q;
        return (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q
                / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1);
    }
}
