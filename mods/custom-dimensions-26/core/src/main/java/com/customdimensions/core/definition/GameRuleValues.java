package com.customdimensions.core.definition;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * A definition's {@code gameRules}, or {@code settings.json}'s
 * {@code defaults.gameRules} (decisions.md RB-38): 26.3 game rule ids to the
 * value the dimension holds, a boolean or a whole number. A key without a
 * namespace is a {@code minecraft} rule; ids are stored namespaced. A level
 * reads the definition's value, else the defaults' value, else the
 * server-wide store's ({@link #resolve}).
 */
public record GameRuleValues(SortedMap<String, Object> values) {
    /** The rules the definition cannot set: what reads them has no level, or the client holds one value for every dimension. */
    public static final Set<String> SERVER_WIDE = Set.of(
            "minecraft:advance_time", "minecraft:advance_weather", "minecraft:immediate_respawn",
            "minecraft:limited_crafting", "minecraft:reduced_debug_info");

    public static final GameRuleValues NONE = new GameRuleValues(new TreeMap<>());

    public GameRuleValues {
        values = Collections.unmodifiableSortedMap(new TreeMap<>(values));
    }

    /** {@code keep_inventory} as {@code minecraft:keep_inventory}; a namespaced id unchanged. */
    public static String id(String key) {
        return key.indexOf(':') < 0 ? "minecraft:" + key : key;
    }

    /**
     * Reads a {@code gameRules} object; {@code where} names it in messages
     * ({@code "<file>: gameRules"}). Each value must be a boolean or a whole
     * number that fits an int, and no rule may be named twice.
     */
    public static GameRuleValues parse(JsonElement json, String where) {
        if (json == null || json.isJsonNull()) return NONE;
        if (!json.isJsonObject()) throw new DefinitionFormatException(where + " must be an object of game rule ids to values");
        SortedMap<String, Object> out = new TreeMap<>();
        for (Map.Entry<String, JsonElement> e : json.getAsJsonObject().entrySet()) {
            String id = id(e.getKey());
            if (!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                throw new DefinitionFormatException(where + "." + e.getKey() + " is not a game rule id");
            }
            if (out.containsKey(id)) throw new DefinitionFormatException(where + " names " + id + " twice");
            out.put(id, value(e.getValue(), where + "." + e.getKey()));
        }
        return new GameRuleValues(out);
    }

    private static Object value(JsonElement v, String where) {
        if (v.isJsonPrimitive()) {
            JsonPrimitive p = v.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isNumber()) {
                BigDecimal n = p.getAsBigDecimal();
                try {
                    return n.intValueExact();
                } catch (ArithmeticException e) {
                    throw new DefinitionFormatException(where + " must be true, false or a whole number that fits an int, got " + v);
                }
            }
        }
        throw new DefinitionFormatException(where + " must be true, false or a whole number, got " + v);
    }

    /** The values in force in a level: {@code definition} over {@code defaults}; either may be null. */
    public static GameRuleValues resolve(GameRuleValues defaults, GameRuleValues definition) {
        SortedMap<String, Object> out = new TreeMap<>();
        if (defaults != null) out.putAll(defaults.values());
        if (definition != null) out.putAll(definition.values());
        return new GameRuleValues(out);
    }

    /** {@code settings.json}'s {@code defaults.gameRules}; none when absent. */
    public static GameRuleValues fromDefaults(JsonObject defaults, String source) {
        return defaults == null ? NONE : parse(defaults.get("gameRules"), source + ": defaults.gameRules");
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    /** The value as JSON, for reports and writers. */
    public JsonObject toJson() {
        JsonObject out = new JsonObject();
        values.forEach((k, v) -> {
            if (v instanceof Boolean b) out.addProperty(k, b);
            else out.addProperty(k, (Integer) v);
        });
        return out;
    }
}
