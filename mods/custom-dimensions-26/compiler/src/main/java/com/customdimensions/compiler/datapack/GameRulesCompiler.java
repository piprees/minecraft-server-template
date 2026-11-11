package com.customdimensions.compiler.datapack;

import com.customdimensions.core.definition.GameRuleValues;
import java.util.Map;

/**
 * Checks a definition's {@code gameRules} and {@code settings.json}'s
 * {@code defaults.gameRules} against the game's rules (decisions.md RB-38).
 * A {@code minecraft} rule must be one the game has, with a value of its
 * type and within its bounds, and must not be one of
 * {@link GameRuleValues#SERVER_WIDE}. A rule of another namespace belongs to
 * a mod the compiler cannot see; the server checks it against the live
 * registry when it starts.
 */
public final class GameRulesCompiler {
    private GameRulesCompiler() {
    }

    /** Refuses the first bad rule in {@code values}; {@code where} names the definition or the defaults. */
    public static void check(String where, GameRuleValues values, VanillaData vanilla) {
        if (values == null || values.isEmpty()) return;
        Map<String, VanillaData.GameRuleType> known = vanilla.gameRules();
        for (Map.Entry<String, Object> e : values.values().entrySet()) {
            String id = e.getKey();
            if (GameRuleValues.SERVER_WIDE.contains(id)) {
                throw new IllegalArgumentException(where + ": gameRules." + id + " is server-wide: "
                        + (id.endsWith("advance_time") || id.endsWith("advance_weather")
                        ? "the world clocks and the weather are one for every dimension"
                        : "the client holds one value for every dimension")
                        + "; set it with /gamerule");
            }
            if (!id.startsWith("minecraft:")) continue;
            VanillaData.GameRuleType type = known.get(id);
            if (type == null) {
                throw new IllegalArgumentException(where + ": gameRules." + id + " is not a game rule of this game");
            }
            Object value = e.getValue();
            if (type.bool() != (value instanceof Boolean)) {
                throw new IllegalArgumentException(where + ": gameRules." + id + " takes " + (type.bool() ? "true or false" : "a whole number")
                        + ", got " + value);
            }
            if (value instanceof Integer n && ((type.min() != null && n < type.min()) || (type.max() != null && n > type.max()))) {
                throw new IllegalArgumentException(where + ": gameRules." + id + " is " + n + ", outside "
                        + (type.min() == null ? "" : type.min()) + ".." + (type.max() == null ? "" : type.max()));
            }
        }
    }
}
