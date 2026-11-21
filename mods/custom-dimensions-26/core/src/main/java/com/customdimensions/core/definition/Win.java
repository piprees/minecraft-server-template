package com.customdimensions.core.definition;

import com.customdimensions.core.BlockPos;
import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * One entry of a definition's {@code wins} (decisions.md RB-30): a trigger and
 * the advancement it grants, {@code adventure:wins/<slug>/<id>}. The
 * advancement is the once-per-player record. {@code message} is a key in
 * {@code config/messages.json}; {@code exit} uses the {@code ExitTarget}
 * grammar.
 */
public record Win(String id, Trigger trigger, String title, String description, String icon, String frame,
                  String message, JsonElement exit) {

    public static final String ROOT = "wins/root";
    public static final String DEFAULT_MESSAGE = "win.earned";
    public static final String DEFAULT_ICON = "minecraft:nether_star";
    public static final List<String> FRAMES = List.of("task", "goal", "challenge");

    public enum Type {
        ENTER, REACH, KILL, OBTAIN, SURVIVE, ADVANCEMENT;

        public String json() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        public static Type parse(String value) {
            for (Type t : values()) {
                if (t.json().equals(value)) return t;
            }
            return null;
        }
    }

    /**
     * What fires a win. {@code target} is the entity, item or advancement id
     * ({@code #tag} allowed for entities and items); {@code count} is the
     * kills or items needed; {@code pos}/{@code radius} are for {@code reach};
     * {@code ticks} for {@code survive}.
     */
    public record Trigger(Type type, BlockPos pos, int radius, String target, int count, int ticks) {

        /** True when {@code id}, or one of its {@code tags}, is the trigger's target. */
        public boolean matches(String id, Set<String> tags) {
            if (target == null) return false;
            return target.startsWith("#") ? tags.contains(target.substring(1)) : target.equals(id);
        }

        /** Within {@code radius} blocks of {@code pos}, measured in three dimensions. */
        public boolean reached(double x, double y, double z) {
            double dx = x - (pos.x() + 0.5);
            double dy = y - pos.y();
            double dz = z - (pos.z() + 0.5);
            return dx * dx + dy * dy + dz * dz <= (double) radius * radius;
        }
    }

    /** The advancement path under the definitions' namespace. */
    public String advancementPath(String slug) {
        return "wins/" + slug + "/" + id;
    }

    /**
     * The advancement's impossible criteria: one per kill for a {@code kill}
     * win, so the advancement's own progress counts the kills, else one.
     */
    public List<String> criteria() {
        if (trigger.type() != Type.KILL || trigger.count() <= 1) return List.of("win");
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= trigger.count(); i++) out.add("kill_" + i);
        return out;
    }

    public String messageKey() {
        return message != null ? message : DEFAULT_MESSAGE;
    }
}
