package com.customdimensions.core.structure;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The words check (lore/placement.md G20, RB-43): no text a set piece places
 * (a lectern's lines and title, a mob's name) names a dimension or a people.
 * A line is refused when it holds, as whole words in any case, a dimension's
 * slug, a people's name in the singular or plural, or the dimension's
 * display name ("The Gilded Pit") with or without its "The" when the rest is
 * two or more words or five or more letters ("gilded pit", "aviary"); a
 * shorter one-word name counts only capitalised ("End", not "the end of it").
 * Game-free.
 */
public final class Words {

    /** The peoples, singular then plural. */
    public static final List<String> PEOPLES = List.of("piglin", "piglins", "enderman", "endermen", "villager", "villagers",
            "human", "humans", "illager", "illagers", "witch", "witches");

    private Words() {
    }

    /** The display name of a slug: {@code the_gilded_pit} is "The Gilded Pit". */
    public static String displayName(String slug) {
        StringBuilder b = new StringBuilder();
        for (String w : slug.split("_")) {
            if (w.isEmpty()) continue;
            if (!b.isEmpty()) b.append(' ');
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }

    /** The phrases {@code text} may not hold for these dimension slugs, lower case. */
    static List<String> forbidden(Collection<String> slugs) {
        List<String> out = new ArrayList<>(PEOPLES);
        for (String slug : slugs) {
            out.add(slug.toLowerCase(Locale.ROOT));
            String name = displayName(slug).toLowerCase(Locale.ROOT);
            String core = name.startsWith("the ") ? name.substring(4) : name;
            if (core.contains(" ") || core.length() >= 5) {
                out.add(name);
                out.add(core);
            }
        }
        return out;
    }

    /** The first forbidden phrase {@code text} holds, or null when it names nothing. */
    public static String named(String text, Collection<String> slugs) {
        String t = text.toLowerCase(Locale.ROOT);
        for (String phrase : forbidden(slugs)) {
            if (Pattern.compile("(?<![a-z0-9_])" + Pattern.quote(phrase) + "(?![a-z0-9_])").matcher(t).find()) return phrase;
        }
        for (String slug : slugs) {
            String name = displayName(slug);
            String core = name.startsWith("The ") ? name.substring(4) : name;
            if (core.contains(" ") || core.length() >= 5) continue;
            if (Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(core) + "(?![A-Za-z0-9_])").matcher(text).find()) return core;
        }
        return null;
    }

    /** Refuses {@code text} at {@code where} when it names a dimension or a people. */
    public static void check(String where, String text, Collection<String> slugs) {
        String found = named(text, slugs);
        if (found != null) {
            throw new IllegalArgumentException(where + ": `" + text + "` names `" + found
                    + "`; nothing in the world names a dimension, a people, an epoch or a person (RB-43)");
        }
    }
}
