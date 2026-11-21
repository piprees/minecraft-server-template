package com.customdimensions.core.structure;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * An item a set piece places (lore/placement.md G11): in a chest's
 * arrangement, a frame, a stand's or a mob's gear, a pot. Written as an id or
 * as {@code {id, count, trim, dye, banner, map}}; every id it names is checked
 * against the game data at compile time, and the components it carries are
 * the vanilla ones (trim pattern and material, dyed colour, banner patterns,
 * map decorations). An item never carries a name (RB-43). Game-free.
 */
public record ItemSpec(String id, int count, String trimPattern, String trimMaterial, Integer dye, List<Layer> banner,
                       Integer mapScale, List<Decoration> decorations) {

    public static final Set<String> FIELDS = Set.of("id", "count", "trim", "dye", "banner", "map");
    public static final List<String> DYES = List.of("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black");
    /** Vanilla's dye colours as the dyed-colour component's RGB, in {@link #DYES} order. */
    static final int[] DYE_RGB = {0xF9FFFE, 0xF9801D, 0xC74EBD, 0x3AB3DA, 0xFED83D, 0x80C71F, 0xF38BAA, 0x474F52, 0x9D9D97,
            0x169C9C, 0x8932B8, 0x3C44AA, 0x835432, 0x5E7C16, 0xB02E26, 0x1D1D21};
    static final List<String> ARMOUR = List.of("_helmet", "_chestplate", "_leggings", "_boots");

    /** One banner pattern layer: a pattern id and a dye colour name. */
    public record Layer(String pattern, String color) {}

    /** One map decoration: a decoration type id at a block position, rotated in degrees. */
    public record Decoration(String type, int x, int z, float rotation) {}

    /** Every id an item names, checked against the game data. */
    public interface Ids {
        boolean known(String registry, String id);
    }

    /** {@code e} read and checked at {@code where}. */
    public static ItemSpec read(String where, JsonElement e, Ids ids) {
        JsonObject o;
        if (e != null && e.isJsonPrimitive()) {
            o = new JsonObject();
            o.add("id", e);
        } else if (e != null && e.isJsonObject()) {
            o = e.getAsJsonObject();
        } else {
            throw bad(where, "an item is an id or an object with an id");
        }
        for (String key : o.keySet()) {
            if (!FIELDS.contains(key)) throw bad(where, key + " is not an item field " + new java.util.TreeSet<>(FIELDS));
        }
        if (!o.has("id")) throw bad(where, "id is required");
        String id = o.get("id").getAsString();
        if (!id.contains(":")) id = "minecraft:" + id;
        if (!ids.known("minecraft:item", id)) throw bad(where, "item `" + id + "` is not an item");
        int count = o.has("count") ? o.get("count").getAsInt() : 1;
        if (count < 1 || count > 99) throw bad(where, "count must be 1 to 99");
        String pattern = null;
        String material = null;
        if (o.has("trim")) {
            if (!armour(id)) throw bad(where, "trim applies to armour only, not `" + id + "`");
            JsonObject t = o.getAsJsonObject("trim");
            pattern = qualified(required(where + ".trim", t, "pattern"));
            material = qualified(required(where + ".trim", t, "material"));
            if (!ids.known("minecraft:trim_pattern", pattern)) throw bad(where, "trim pattern `" + pattern + "` is not a vanilla trim pattern");
            if (!ids.known("minecraft:trim_material", material)) throw bad(where, "trim material `" + material + "` is not a vanilla trim material");
        }
        Integer dye = null;
        if (o.has("dye")) {
            if (!dyeable(id)) throw bad(where, "dye applies to leather armour and wolf armour only, not `" + id + "`");
            dye = rgb(where, o.get("dye"));
        }
        List<Layer> banner = List.of();
        if (o.has("banner")) {
            if (!id.endsWith("_banner") && !id.equals("minecraft:shield")) throw bad(where, "banner patterns apply to a banner or a shield, not `" + id + "`");
            banner = layers(where + ".banner", o.get("banner"), ids);
        }
        Integer scale = null;
        List<Decoration> decorations = List.of();
        if (o.has("map")) {
            if (!id.equals("minecraft:filled_map")) throw bad(where, "map applies to minecraft:filled_map only");
            JsonObject m = o.getAsJsonObject("map");
            for (String key : m.keySet()) {
                if (!Set.of("scale", "decorations").contains(key)) throw bad(where, "map." + key + " is not a map field");
            }
            scale = m.has("scale") ? m.get("scale").getAsInt() : 2;
            if (scale < 0 || scale > 4) throw bad(where, "map.scale must be 0 to 4");
            List<Decoration> list = new ArrayList<>();
            if (m.has("decorations")) {
                for (JsonElement d : m.getAsJsonArray("decorations")) {
                    JsonObject dj = d.getAsJsonObject();
                    String type = qualified(required(where + ".map.decorations", dj, "type"));
                    if (!ids.known("minecraft:map_decoration_type", type)) throw bad(where, "map decoration `" + type + "` is not a vanilla decoration type");
                    if (!dj.has("x") || !dj.has("z")) throw bad(where, "a map decoration needs x and z");
                    list.add(new Decoration(type, dj.get("x").getAsInt(), dj.get("z").getAsInt(),
                            dj.has("rotation") ? dj.get("rotation").getAsFloat() : 180f));
                }
            }
            decorations = List.copyOf(list);
        }
        return new ItemSpec(id, count, pattern, material, dye, banner, scale, decorations);
    }

    /** Banner pattern layers, each {@code {pattern, color}}, at most 16. */
    public static List<Layer> layers(String where, JsonElement e, Ids ids) {
        if (!e.isJsonArray()) throw bad(where, "must be a list of {pattern, color}");
        List<Layer> out = new ArrayList<>();
        for (JsonElement l : e.getAsJsonArray()) {
            JsonObject lj = l.getAsJsonObject();
            String pattern = qualified(required(where, lj, "pattern"));
            String color = dye(where, required(where, lj, "color"));
            if (!ids.known("minecraft:banner_pattern", pattern)) throw bad(where, "banner pattern `" + pattern + "` is not a vanilla banner pattern");
            out.add(new Layer(pattern, color));
        }
        if (out.size() > 16) throw bad(where, "at most 16 banner layers");
        return List.copyOf(out);
    }

    /** A dye colour name, checked. */
    public static String dye(String where, String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.startsWith("minecraft:")) n = n.substring(10);
        if (!DYES.contains(n)) throw bad(where, "colour `" + name + "` must be one of " + DYES);
        return n;
    }

    static int rgb(String where, JsonElement e) {
        if (e.getAsJsonPrimitive().isNumber()) {
            int v = e.getAsInt();
            if (v < 0 || v > 0xFFFFFF) throw bad(where, "dye must be 0 to 16777215");
            return v;
        }
        String s = e.getAsString();
        if (s.matches("#[0-9A-Fa-f]{6}")) return Integer.parseInt(s.substring(1), 16);
        return DYE_RGB[DYES.indexOf(dye(where, s))];
    }

    static boolean armour(String id) {
        if (id.equals("minecraft:turtle_helmet")) return true;
        for (String a : ARMOUR) if (id.endsWith(a)) return true;
        return false;
    }

    static boolean dyeable(String id) {
        return id.startsWith("minecraft:leather_") && (armour(id) || id.equals("minecraft:leather_horse_armor")) || id.equals("minecraft:wolf_armor");
    }

    static String qualified(String id) {
        return id.contains(":") ? id : "minecraft:" + id;
    }

    private static String required(String where, JsonObject o, String key) {
        if (!o.has(key)) throw bad(where, key + " is required");
        return o.get(key).getAsString();
    }

    private static IllegalArgumentException bad(String where, String why) {
        return new IllegalArgumentException(where + ": " + why);
    }

    /** The item as {@link #fromJson} reads it back at runtime. */
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        if (count != 1) o.addProperty("count", count);
        if (trimPattern != null) {
            JsonObject t = new JsonObject();
            t.addProperty("pattern", trimPattern);
            t.addProperty("material", trimMaterial);
            o.add("trim", t);
        }
        if (dye != null) o.addProperty("dye", dye);
        if (!banner.isEmpty()) {
            JsonArray a = new JsonArray();
            for (Layer l : banner) {
                JsonObject lj = new JsonObject();
                lj.addProperty("pattern", l.pattern());
                lj.addProperty("color", l.color());
                a.add(lj);
            }
            o.add("banner", a);
        }
        if (mapScale != null) {
            JsonObject m = new JsonObject();
            m.addProperty("scale", mapScale);
            JsonArray a = new JsonArray();
            for (Decoration d : decorations) {
                JsonObject dj = new JsonObject();
                dj.addProperty("type", d.type());
                dj.addProperty("x", d.x());
                dj.addProperty("z", d.z());
                dj.addProperty("rotation", d.rotation());
                a.add(dj);
            }
            m.add("decorations", a);
            o.add("map", m);
        }
        return o;
    }

    /** An item as written by {@link #toJson} or by an author; trusted, so ids are not checked. */
    public static ItemSpec fromJson(JsonElement e) {
        return read("item", e, (registry, id) -> true);
    }
}
