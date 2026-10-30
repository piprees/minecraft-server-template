package com.customdimensions.core.structure;

import com.customdimensions.core.zone.ZoneMask;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Wants and shuns scoped to zones: a zone's {@code structures} block names
 * structures it wants or shuns inside its shape. A shunned structure does not
 * start where its zone's weight is at least {@link #INSIDE}; a want's site is
 * chosen where its zone's weight is. Which zones hold a point comes from a
 * {@link ZoneQuery}.
 */
public record ZoneStructureRules(List<Rule> rules) {

    /** The blend weight from which a point counts as inside a zone. */
    public static final double INSIDE = 0.5;

    /** Which zones hold a point, by index in the definition's {@code zones}, with their blend weights. */
    @FunctionalInterface
    public interface ZoneQuery {
        double weight(int zone, double x, double z);

        /** The query over masks in zone order. */
        static ZoneQuery of(List<ZoneMask> masks) {
            return (zone, x, z) -> zone >= 0 && zone < masks.size() ? masks.get(zone).at(x, z) : 0;
        }
    }

    /** One zone's wants and shuns, ids or {@code #tags} resolved as {@link StructureSpec#resolveName}. */
    public record Rule(int zone, List<String> wants, List<String> shuns) {}

    public static final ZoneStructureRules NONE = new ZoneStructureRules(List.of());

    /** The rules of a definition's zones; a zone without {@code structures} adds none. */
    public static ZoneStructureRules read(String slug, List<JsonObject> zones) {
        List<Rule> out = new ArrayList<>();
        for (int i = 0; i < zones.size(); i++) {
            JsonElement block = zones.get(i).get("structures");
            if (block == null) continue;
            if (!block.isJsonObject()) {
                throw new IllegalArgumentException(slug + ": zones[" + i + "].structures must be an object");
            }
            out.add(new Rule(i, names(slug, i, block.getAsJsonObject(), "wants"), names(slug, i, block.getAsJsonObject(), "shuns")));
        }
        return new ZoneStructureRules(List.copyOf(out));
    }

    /** Whether a structure (matched by {@code is}) may not start at this point. */
    public boolean shunned(Predicate<String> is, double x, double z, ZoneQuery zones) {
        for (Rule r : rules) {
            if (r.shuns().stream().anyMatch(is) && zones.weight(r.zone(), x, z) >= INSIDE) {
                return true;
            }
        }
        return false;
    }

    /** Whether a want for a structure (matched by {@code is}) accepts this point: inside one of the zones that want it. */
    public boolean wantAccepts(Predicate<String> is, double x, double z, ZoneQuery zones) {
        boolean scoped = false;
        for (Rule r : rules) {
            if (r.wants().stream().anyMatch(is)) {
                scoped = true;
                if (zones.weight(r.zone(), x, z) >= INSIDE) return true;
            }
        }
        return !scoped;
    }

    public JsonArray toJson() {
        JsonArray a = new JsonArray();
        for (Rule r : rules) {
            JsonObject o = new JsonObject();
            o.addProperty("zone", r.zone());
            JsonArray w = new JsonArray();
            r.wants().forEach(w::add);
            JsonArray s = new JsonArray();
            r.shuns().forEach(s::add);
            o.add("wants", w);
            o.add("shuns", s);
            a.add(o);
        }
        return a;
    }

    public static ZoneStructureRules fromJson(JsonArray a) {
        List<Rule> out = new ArrayList<>();
        for (JsonElement e : a) {
            JsonObject o = e.getAsJsonObject();
            List<String> w = new ArrayList<>();
            o.getAsJsonArray("wants").forEach(x -> w.add(x.getAsString()));
            List<String> s = new ArrayList<>();
            o.getAsJsonArray("shuns").forEach(x -> s.add(x.getAsString()));
            out.add(new Rule(o.get("zone").getAsInt(), w, s));
        }
        return new ZoneStructureRules(List.copyOf(out));
    }

    private static List<String> names(String slug, int zone, JsonObject block, String key) {
        List<String> out = new ArrayList<>();
        JsonElement v = block.get(key);
        if (v == null) return out;
        Iterable<?> items;
        if (v.isJsonArray()) {
            List<String> names = new ArrayList<>();
            v.getAsJsonArray().forEach(e -> names.add(e.getAsString()));
            items = names;
        } else if (v.isJsonObject()) {
            items = v.getAsJsonObject().keySet();
        } else {
            throw new IllegalArgumentException(slug + ": zones[" + zone + "].structures." + key + " must be a list or an object of names");
        }
        for (Object name : items) {
            String id = StructureSpec.resolveName((String) name);
            if (id == null) {
                throw new IllegalArgumentException(slug + ": zones[" + zone + "].structures." + key + " `" + name + "` is not a structure id, tag or known name");
            }
            out.add(id);
        }
        return out;
    }
}
