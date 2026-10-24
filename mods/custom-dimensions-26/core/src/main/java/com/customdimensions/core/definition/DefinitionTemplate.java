package com.customdimensions.core.definition;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A new definition from an existing one, for {@code /customdim create}: the
 * template's fields with a fresh seed, one new portal colour on every portal,
 * and without what belongs to the template's own world (its rolled
 * {@code spawn}, portal {@code anchor}s fixed in a source world, and its
 * {@code wins}, whose advancement ids are the template's).
 */
public final class DefinitionTemplate {
    /** The template {@code create} copies when none is named: vanilla's overworld generator with the overworld's portal. */
    public static final String DEFAULT = "overworld";
    private static final Pattern SLUG = Pattern.compile("[a-z0-9_]{1,64}");

    private DefinitionTemplate() {
    }

    /** A dimension path the definitions folder and the level id both accept, and not {@code settings}. */
    public static boolean validSlug(String slug) {
        return SLUG.matcher(slug).matches() && !slug.equals("settings");
    }

    public static JsonObject derive(JsonObject template, long seed, String colour) {
        JsonObject out = template.deepCopy();
        out.addProperty("seed", seed);
        out.remove("spawn");
        out.remove("wins");
        if (out.has("portals")) {
            for (JsonElement portal : out.getAsJsonArray("portals")) {
                JsonObject p = portal.getAsJsonObject();
                p.addProperty("color", colour);
                p.remove("anchor");
            }
        }
        return out;
    }

    /** A random {@code RRGGBB} no definition's portal uses, compared case-insensitively. */
    public static String freshColour(Random random, Collection<String> used) {
        Set<String> taken = new HashSet<>();
        used.forEach(c -> taken.add(c.toUpperCase(Locale.ROOT)));
        while (true) {
            String colour = String.format(Locale.ROOT, "%06X", random.nextInt(0x1000000));
            if (!taken.contains(colour)) return colour;
        }
    }

    /** Every portal colour {@code definition} uses. */
    public static Set<String> colours(JsonObject definition) {
        Set<String> out = new HashSet<>();
        if (definition.has("portals")) {
            for (JsonElement portal : definition.getAsJsonArray("portals")) {
                JsonElement colour = portal.getAsJsonObject().get("color");
                if (colour != null && colour.isJsonPrimitive()) out.add(colour.getAsString());
            }
        }
        return out;
    }
}
