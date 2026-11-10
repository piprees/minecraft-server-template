package com.customdimensions.core.survey;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The ground a biome's surface rule lays on open land: the block the rule
 * places at the top of a dry column a few blocks above sea level, away from
 * noise patches, slopes and depth gradients. A rule encoded as 26.3 JSON
 * ({@code minecraft:sequence}, {@code condition}, {@code block},
 * {@code bandlands}, and rules or tests named by id, looked up in the
 * {@code rules} and {@code conditions} given) is walked with each test read for that column:
 * {@code biome} by membership, the top of the floor ({@code stone_depth} floor
 * at offset 0), {@code water} and {@code above_preliminary_surface} true,
 * {@code y_above} against the column's height, and every patch-like test
 * ({@code noise_threshold}, {@code steep}, {@code hole}, {@code temperature},
 * {@code vertical_gradient}, a deeper or ceiling {@code stone_depth}, any other
 * type) false. With no block placed, the column keeps the default block.
 */
public final class SurfaceGround {
    /** Blocks above sea level the column's top sits. */
    public static final int ABOVE_SEA = 8;

    private SurfaceGround() {}

    /** Named rules and tests by id; either may be empty. */
    public record Named(Map<String, JsonElement> rules, Map<String, JsonElement> conditions) {
        public static final Named NONE = new Named(Map.of(), Map.of());
    }

    /** The block the rule lays at the top of {@code biome}'s open land, or {@code defaultBlock}. */
    public static String top(JsonElement rule, String biome, String defaultBlock, int y, int minY, int height) {
        return top(rule, Named.NONE, biome, defaultBlock, y, minY, height);
    }

    public static String top(JsonElement rule, Named named, String biome, String defaultBlock, int y, int minY, int height) {
        String b = eval(rule, new Column(biome, y, minY, height, named), 0);
        return b != null ? b : defaultBlock;
    }

    /** The {@code biomes} whose open land the rule lays in a {@code bare} block: their normal ground is rock. */
    public static Set<String> rockBiomes(JsonElement rule, Named named, Collection<String> biomes, String defaultBlock, int seaLevel, int minY,
                                         int height, Predicate<String> bare) {
        Set<String> out = new HashSet<>();
        for (String id : biomes) {
            if (bare.test(top(rule, named, id, defaultBlock, seaLevel + ABOVE_SEA, minY, height))) out.add(id);
        }
        return out;
    }

    /** Named lookups nest no deeper than this; a cycle places nothing. */
    private static final int MAX_DEPTH = 64;

    private record Column(String biome, int y, int minY, int height, Named named) {}

    private static String eval(JsonElement rule, Column c, int depth) {
        if (rule == null || depth > MAX_DEPTH) return null;
        if (rule.isJsonPrimitive()) return eval(c.named().rules().get(rule.getAsString()), c, depth + 1);
        if (!rule.isJsonObject()) return null;
        JsonObject o = rule.getAsJsonObject();
        switch (type(o)) {
            case "sequence" -> {
                for (JsonElement e : o.getAsJsonArray("sequence")) {
                    String b = eval(e, c, depth + 1);
                    if (b != null) return b;
                }
                return null;
            }
            case "condition" -> {
                return test(o.get("if_true"), c, depth + 1) ? eval(o.get("then_run"), c, depth + 1) : null;
            }
            case "block" -> {
                JsonElement s = o.get("result_state");
                return s != null && s.isJsonObject() ? s.getAsJsonObject().get("Name").getAsString() : null;
            }
            case "bandlands" -> {
                return "minecraft:terracotta";
            }
            default -> {
                return null;
            }
        }
    }

    private static boolean test(JsonElement condition, Column c, int depth) {
        if (condition == null || depth > MAX_DEPTH) return false;
        if (condition.isJsonPrimitive()) return test(c.named().conditions().get(condition.getAsString()), c, depth + 1);
        if (!condition.isJsonObject()) return false;
        JsonObject o = condition.getAsJsonObject();
        return switch (type(o)) {
            case "biome" -> {
                JsonElement is = o.get("biome_is");
                if (is != null && is.isJsonPrimitive()) yield is.getAsString().equals(c.biome());
                if (is != null && is.isJsonArray()) for (JsonElement e : is.getAsJsonArray()) if (e.getAsString().equals(c.biome())) yield true;
                yield false;
            }
            case "not" -> !test(o.get("invert"), c, depth + 1);
            case "stone_depth" -> o.get("offset").getAsInt() == 0
                    && !"ceiling".equals(o.has("surface_type") ? o.get("surface_type").getAsString() : "floor");
            case "water", "above_preliminary_surface" -> true;
            case "y_above" -> c.y() >= anchor(o.get("anchor"), c);
            default -> false;
        };
    }

    private static int anchor(JsonElement anchor, Column c) {
        if (anchor == null || !anchor.isJsonObject()) return Integer.MAX_VALUE;
        JsonObject a = anchor.getAsJsonObject();
        if (a.has("absolute")) return a.get("absolute").getAsInt();
        if (a.has("above_bottom")) return c.minY() + a.get("above_bottom").getAsInt();
        if (a.has("below_top")) return c.minY() + c.height() - 1 - a.get("below_top").getAsInt();
        return Integer.MAX_VALUE;
    }

    private static String type(JsonObject o) {
        String t = o.has("type") ? o.get("type").getAsString() : "";
        return t.startsWith("minecraft:") ? t.substring("minecraft:".length()) : t;
    }
}
