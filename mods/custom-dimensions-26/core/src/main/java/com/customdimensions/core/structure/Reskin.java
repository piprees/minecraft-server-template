package com.customdimensions.core.structure;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * A dimension's re-skin of placed structures (lore/placement.md G13,
 * RB-45): {@code structures.reskin}, by structure id, #tag or group name,
 * each a {@code swap} of block ids or #block tags to a block or a
 * {@code materials.<slot>} of the dimension, and an {@code age} whose
 * weathering it wears as our own set pieces do. A swapped block keeps the
 * properties the new block shares; the runtime applies it to every template
 * block and every block a hard-coded piece places inside a matching
 * structure. Game-free.
 */
public record Reskin(String target, Map<String, String> swap, Age age) {

    public static final Set<String> FIELDS = Set.of("swap", "age");
    public static final Set<String> SLOTS = Set.of("defaultBlock", "top", "filler", "deep", "cliffs", "beaches", "ceilings");

    /** {@code structures.reskin} of {@code slug}, its {@code materials.<slot>} targets resolved against {@code materials}. */
    public static List<Reskin> read(String slug, JsonElement block, JsonObject materials, Predicate<String> knownBlock) {
        String where = slug + ": structures.reskin";
        if (!block.isJsonObject()) throw new IllegalArgumentException(where + " must be an object");
        List<Reskin> out = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : block.getAsJsonObject().entrySet()) {
            String w = where + "." + e.getKey();
            if (!e.getValue().isJsonObject()) throw new IllegalArgumentException(w + " must be an object {swap, age}");
            JsonObject o = e.getValue().getAsJsonObject();
            for (String k : o.keySet()) {
                if (!FIELDS.contains(k)) throw new IllegalArgumentException(w + ": " + k + " is not a re-skin field " + FIELDS);
            }
            Map<String, String> swap = new LinkedHashMap<>();
            if (o.has("swap")) {
                for (Map.Entry<String, JsonElement> s : o.getAsJsonObject("swap").entrySet()) {
                    String from = s.getKey();
                    if (!from.startsWith("#")) checkBlock(w, from, knownBlock);
                    String to = s.getValue().getAsString();
                    if (to.startsWith("materials.")) {
                        String slot = to.substring("materials.".length());
                        if (!SLOTS.contains(slot)) throw new IllegalArgumentException(w + ": " + to + " is not a materials slot " + SLOTS);
                        JsonElement v = materials == null ? null : materials.get(slot);
                        if (v == null || !v.isJsonPrimitive()) throw new IllegalArgumentException(w + ": " + to + " is not set in this dimension's materials");
                        to = v.getAsString();
                    }
                    checkBlock(w, to, knownBlock);
                    swap.put(from, to);
                }
            }
            Age age = o.has("age") ? Age.parse(o.get("age").getAsString()) : null;
            if (swap.isEmpty() && age == null) throw new IllegalArgumentException(w + " needs a swap or an age");
            out.add(new Reskin(e.getKey(), Map.copyOf(swap), age));
        }
        return List.copyOf(out);
    }

    private static void checkBlock(String where, String id, Predicate<String> knownBlock) {
        if (!id.contains(":")) throw new IllegalArgumentException(where + ": `" + id + "` is not a block id");
        if (id.startsWith("minecraft:") && !knownBlock.test(id)) throw new IllegalArgumentException(where + ": `" + id + "` is not a vanilla block");
    }

    /**
     * The block {@code id} re-skinned: its swap (an exact id first, then the
     * first #tag that holds it), then weathered to the age at two rolls; the
     * id itself when nothing applies.
     */
    public String apply(String id, BiPredicate<String, String> inTag, double roll, double roll2) {
        String to = swap.get(id);
        if (to == null) {
            for (Map.Entry<String, String> s : swap.entrySet()) {
                if (s.getKey().startsWith("#") && inTag.test(s.getKey().substring(1), id)) {
                    to = s.getValue();
                    break;
                }
            }
        }
        String out = to != null ? to : id;
        return age != null ? age.weather(out, roll, roll2) : out;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("target", target);
        JsonObject s = new JsonObject();
        swap.forEach(s::addProperty);
        o.add("swap", s);
        if (age != null) o.addProperty("age", age.id());
        return o;
    }

    public static Reskin fromJson(JsonObject o) {
        Map<String, String> swap = new LinkedHashMap<>();
        o.getAsJsonObject("swap").entrySet().forEach(e -> swap.put(e.getKey(), e.getValue().getAsString()));
        return new Reskin(o.get("target").getAsString(), Map.copyOf(swap), o.has("age") ? Age.parse(o.get("age").getAsString()) : null);
    }

    public static JsonArray toJson(List<Reskin> list) {
        JsonArray a = new JsonArray();
        list.forEach(r -> a.add(r.toJson()));
        return a;
    }
}
