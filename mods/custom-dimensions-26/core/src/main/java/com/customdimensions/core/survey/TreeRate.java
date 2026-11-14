package com.customdimensions.core.survey;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * A placed feature's expected placements a chunk, read from its encoded {@code placement} modifiers: the mean of each
 * {@code count} and {@code count_on_every_layer}, the mean of a {@code noise_threshold_count}'s two counts, and one in
 * {@code chance} for a {@code rarity_filter}; every other modifier passes or drops a position and counts as one.
 */
public final class TreeRate {
    private TreeRate() {}

    /** Expected placements a chunk of a placed feature with these modifiers. */
    public static double perChunk(JsonArray placement) {
        double n = 1;
        if (placement == null) return n;
        for (JsonElement e : placement) {
            if (!e.isJsonObject()) continue;
            JsonObject m = e.getAsJsonObject();
            String type = m.has("type") ? m.get("type").getAsString().replace("minecraft:", "") : "";
            switch (type) {
                case "count", "count_on_every_layer" -> n *= mean(m.get("count"));
                case "rarity_filter" -> n /= Math.max(1, m.get("chance").getAsDouble());
                case "noise_threshold_count" -> n *= (m.get("below_noise").getAsDouble() + m.get("above_noise").getAsDouble()) / 2;
                default -> { }
            }
        }
        return n;
    }

    /** The mean of an encoded int provider: a constant, uniform, biased, clamped, trapezoid or weighted list. */
    static double mean(JsonElement p) {
        if (p == null || p.isJsonNull()) return 1;
        if (p.isJsonPrimitive()) return p.getAsDouble();
        JsonObject o = p.getAsJsonObject();
        String type = o.has("type") ? o.get("type").getAsString().replace("minecraft:", "") : "";
        return switch (type) {
            case "constant" -> o.get("value").getAsDouble();
            case "uniform", "biased_to_bottom" -> (o.get("min_inclusive").getAsDouble() + o.get("max_inclusive").getAsDouble()) / 2;
            case "clamped" -> Math.clamp(mean(o.get("source")), o.get("min_inclusive").getAsDouble(), o.get("max_inclusive").getAsDouble());
            case "clamped_normal" -> o.get("mean").getAsDouble();
            case "trapezoid" -> (o.get("min").getAsDouble() + o.get("max").getAsDouble()) / 2;
            case "weighted_list" -> {
                double sum = 0, weight = 0;
                for (JsonElement d : o.getAsJsonArray("distribution")) {
                    JsonObject w = d.getAsJsonObject();
                    double wt = w.get("weight").getAsDouble();
                    sum += wt * mean(w.get("data"));
                    weight += wt;
                }
                yield weight == 0 ? 0 : sum / weight;
            }
            default -> 1;
        };
    }
}
