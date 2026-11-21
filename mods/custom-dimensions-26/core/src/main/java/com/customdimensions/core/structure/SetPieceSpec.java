package com.customdimensions.core.structure;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One set piece (places/set-pieces.md): a place of our own, built at worldgen
 * time from vanilla blocks and vanilla's own templates by id. Resolved from a
 * definition's {@code setPieces.<name>}, its {@code like} chain through the
 * shared library ({@code definitions/set-pieces/<name>.json}) merged under
 * it; the compiler writes it into the structure {@code <ns>:set_piece/<slug>/<name>}
 * and the runtime reads it back with {@link #fromJson}. Game-free.
 */
public record SetPieceSpec(String key, String name, String seat, double support, SiteFilter site, Integer bandMin,
                           Integer bandMax, int clear, Map<String, List<Weighted>> palette, List<Element> elements,
                           String group, String rarity, Integer repetition, String terrainAdaptation, int size, Age age,
                           SetPieceSetting setting) {

    public static final Set<String> FIELDS = union(Set.of("name", "like", "seat", "support", "slope", "biomes", "site", "band", "clear",
            "elements", "palette", "group", "rarity", "repetition", "terrainAdaptation", "size", "age"), SetPieceSetting.FIELDS);
    public static final Set<String> SEATS = Set.of("ground", "floor", "pinned", "roof", "wall", "water");
    public static final Set<String> KINDS = union(Set.of("template", "scatter", "block", "chest", "spawner"), SetPieceShapes.KINDS, SetPieceKinds.ASSEMBLIES,
            SetPieceKinds.THINGS);
    public static final Set<String> ELEMENT_FIELDS = union(Set.of("kind", "offset", "count", "chance", "around", "age", "weight",
            "id", "rotation", "mirror", "processors", "integrity", "embed",
            "radius", "items", "spacing", "block", "loot", "entity", "delay", "range", "name", "on", "at"), SetPieceShapes.FIELDS, SetPieceKinds.FIELDS);
    /** Where an element seated {@code on} another stands: on its top, or inside its footprint at its own seat (lore/placement.md G7). */
    public static final Set<String> ON = Set.of("top", "inside");
    public static final Set<String> ROTATIONS = Set.of("random", "none", "clockwise_90", "180", "counterclockwise_90");
    public static final Set<String> MIRRORS = Set.of("none", "left_right", "front_back", "random");
    public static final Set<String> CHEST_KINDS = Set.of("chest", "barrel", "trapped_chest", "decorated_pot");
    public static final Set<String> ADAPTATIONS = Set.of("none", "beard_thin", "beard_box", "bury", "encapsulate", "footing", "terrace");
    public static final int MAX_SIZE = 64;
    public static final double DEFAULT_SUPPORT = 0.8;
    public static final int DEFAULT_CLEAR = 6;
    static final int MAX_LIKE_DEPTH = 8;

    @SafeVarargs
    static <T> Set<T> union(Set<T>... sets) {
        java.util.Set<T> out = new java.util.HashSet<>();
        for (Set<T> s : sets) out.addAll(s);
        return Set.copyOf(out);
    }

    /** One weighted entry of a palette: a block state string and its weight. */
    public record Weighted(String block, int weight) {}

    /**
     * One element: its kind, offset from the origin in the piece's rotation,
     * copies ({@code count}, each kept at {@code chance}, on a ring of
     * {@code around} blocks when it is above 0), its own age, and the kind's
     * parameters as written.
     */
    public record Element(String kind, int[] offset, int count, double chance, int around, Age age, int weight, JsonObject params,
                          List<Element> items) {
        public String string(String key, String fallback) {
            return params.has(key) ? params.get(key).getAsString() : fallback;
        }

        public Double number(String key) {
            return params.has(key) ? params.get(key).getAsDouble() : null;
        }

        public int integer(String key, int fallback) {
            return params.has(key) ? params.get(key).getAsInt() : fallback;
        }

        /** The index of the earlier element this one is seated on, or null. */
        public Integer on() {
            return params.has("on") && params.get("on").getAsJsonPrimitive().isNumber() ? params.get("on").getAsInt() : null;
        }
    }

    /** Each element's {@code on} as the index of an earlier element, named or numbered; {@code at} checked. */
    private static void layered(String where, List<Element> elements) {
        Map<String, Integer> names = new java.util.HashMap<>();
        for (int i = 0; i < elements.size(); i++) {
            Element e = elements.get(i);
            JsonObject p = e.params();
            if (p.has("at") && !ON.contains(p.get("at").getAsString())) throw bad(where, "elements[" + i + "].at must be one of " + ON);
            if (p.has("at") && !p.has("on")) throw bad(where, "elements[" + i + "].at needs on");
            if (p.has("on")) {
                JsonElement on = p.get("on");
                Integer index = on.getAsJsonPrimitive().isNumber() ? Integer.valueOf(on.getAsInt()) : names.get(on.getAsString());
                if (index == null || index < 0 || index >= i) {
                    throw bad(where, "elements[" + i + "].on `" + on.getAsString() + "` is not an earlier element");
                }
                if ("scatter".equals(elements.get(index).kind())) throw bad(where, "elements[" + i + "].on cannot name a scatter");
                p.addProperty("on", index);
            }
            if (p.has("name") && names.put(p.get("name").getAsString(), i) != null) {
                throw bad(where, "two elements are named `" + p.get("name").getAsString() + "`");
            }
        }
    }

    /** What the resolver asks of the game data; ids outside {@code minecraft:} are not checked. */
    public interface Catalogue {
        /** A vanilla structure template's size {x, y, z}, or null when vanilla has none by that id. */
        int[] templateSize(String id);

        boolean block(String id);

        boolean entity(String id);

        boolean lootTable(String id);

        boolean processorList(String id);

        /** Whether registry {@code registry} (an item, attribute, mob effect, trim, banner pattern or map decoration) holds {@code id}. */
        default boolean known(String registry, String id) {
            return true;
        }

        /** Every dimension's slug, for the words check (RB-43). */
        default Set<String> dimensions() {
            return Set.of();
        }

        /** The first portal of dimension {@code slug}, or null: what a gate's {@code target} takes its frame from. */
        default JsonObject portal(String slug) {
            return null;
        }
    }

    /** Every check passes and every template measures 16 cubed: for tests of the shape alone. */
    public static final Catalogue ANY = new Catalogue() {
        public int[] templateSize(String id) {
            return new int[] {16, 16, 16};
        }

        public boolean block(String id) {
            return true;
        }

        public boolean entity(String id) {
            return true;
        }

        public boolean lootTable(String id) {
            return true;
        }

        public boolean processorList(String id) {
            return true;
        }
    };

    // ---- resolving -------------------------------------------------------------------------

    /**
     * The set piece {@code name} of definition {@code slug}: its library chain
     * merged under it, every field checked, and its elements' reach held to its
     * {@code size}. An unknown field or kind, a template, block, entity, loot
     * table or processor list vanilla lacks, or a reach past {@code size} stops
     * the compile with the slug and the set piece's name.
     */
    public static SetPieceSpec resolve(String slug, String name, JsonObject raw, Map<String, JsonObject> library,
                                       Age dimensionAge, Catalogue catalogue) {
        String where = slug + ": setPieces." + name;
        JsonObject o = merged(where, raw, library, 0);
        for (String key : o.keySet()) {
            if (!FIELDS.contains(key)) throw bad(where, key + " is not a set-piece field " + new java.util.TreeSet<>(FIELDS));
        }
        String display = o.has("name") ? o.get("name").getAsString() : name;
        String seat = o.has("seat") ? o.get("seat").getAsString() : "ground";
        if (!SEATS.contains(seat)) throw bad(where, "seat `" + seat + "` must be one of " + SEATS);
        double support = o.has("support") ? o.get("support").getAsDouble() : DEFAULT_SUPPORT;
        if (support < 0 || support > 1) throw bad(where, "support must be between 0 and 1");
        if (!o.has("size")) throw bad(where, "size is required (the bounding half-extent in blocks, at most " + MAX_SIZE + ")");
        int size = o.get("size").getAsInt();
        if (size < 1 || size > MAX_SIZE) throw bad(where, "size must be 1 to " + MAX_SIZE);
        JsonObject siteJson = o.has("site") ? o.getAsJsonObject("site").deepCopy() : new JsonObject();
        if (o.has("slope")) siteJson.add("slope", o.get("slope"));
        if (o.has("biomes")) siteJson.add("biomes", o.get("biomes"));
        SiteFilter site;
        try {
            site = SiteFilter.read(where + ".site", siteJson);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        site = site.withRadius(siteJson.has("radius") ? site.radius() : Math.max(1, size));
        Integer bandMin = null;
        Integer bandMax = null;
        if (o.has("band")) {
            JsonArray b = o.getAsJsonArray("band");
            if (b.size() != 2) throw bad(where, "band must be [minY, maxY]");
            bandMin = b.get(0).getAsInt();
            bandMax = b.get(1).getAsInt();
            if (bandMin > bandMax) throw bad(where, "band's minY is above its maxY");
        }
        if (o.has("band") && !Set.of("floor", "roof", "wall").contains(seat)) throw bad(where, "band applies to the floor, roof and wall seats only");
        int clear = o.has("clear") ? o.get("clear").getAsInt() : DEFAULT_CLEAR;
        Age age = o.has("age") ? age(where, o.get("age").getAsString()) : dimensionAge;
        Map<String, List<Weighted>> palette = palette(where, o, catalogue);
        if (!o.has("elements") || !o.get("elements").isJsonArray() || o.getAsJsonArray("elements").isEmpty()) {
            throw bad(where, "elements must be a non-empty list");
        }
        List<Element> elements = new ArrayList<>();
        JsonArray arr = o.getAsJsonArray("elements");
        for (int i = 0; i < arr.size(); i++) {
            elements.add(element(where + ".elements[" + i + "]", arr.get(i), palette, catalogue, false));
        }
        layered(where, elements);
        SetPieceSetting setting = SetPieceSetting.read(where, o, seat, size);
        String group = o.has("group") ? o.get("group").getAsString() : null;
        String rarity = o.has("rarity") ? o.get("rarity").getAsString() : null;
        if (rarity != null && !StructureSpec.RARITIES.contains(rarity)) throw bad(where, "rarity must be one of " + StructureSpec.RARITIES);
        if (rarity != null && group == null) throw bad(where, "rarity needs a group");
        Integer repetition = o.has("repetition") ? o.get("repetition").getAsInt() : null;
        if (repetition != null && repetition < 1) throw bad(where, "repetition must be 1 or more");
        if (repetition != null && group == null) throw bad(where, "repetition needs a group");
        String adaptation = o.has("terrainAdaptation") ? o.get("terrainAdaptation").getAsString()
                : elements.stream().anyMatch(e -> SetPieceShapes.KINDS.contains(e.kind())) ? "none"
                : age == Age.ANCIENT || age == Age.DEEP ? "bury" : "beard_thin";
        if (!ADAPTATIONS.contains(adaptation)) throw bad(where, "terrainAdaptation must be one of " + ADAPTATIONS);
        SetPieceSpec spec = new SetPieceSpec(slug + "/" + name, display, seat, support, site, bandMin, bandMax, clear, palette,
                elements, group, rarity, repetition, adaptation, size, age, setting);
        for (int i = 0; i < elements.size(); i++) {
            int[] r = reach(elements.get(i), catalogue);
            Integer on = elements.get(i).on();
            if (on != null) {
                int[] base = reach(elements.get(on), catalogue);
                r = new int[] {r[0] + base[0], r[1] + base[1]};
            }
            if (r[0] > size || r[1] > size) {
                throw bad(where, "elements[" + i + "] (" + elements.get(i).kind() + ") reaches " + r[0] + " blocks across and "
                        + r[1] + " up, past its size " + size);
            }
        }
        return spec;
    }

    /** The raw spec with its {@code like} chain merged under it: elements and scalars replaced, palette and site merged by key. */
    static JsonObject merged(String where, JsonObject raw, Map<String, JsonObject> library, int depth) {
        if (!raw.has("like")) return raw.deepCopy();
        if (depth >= MAX_LIKE_DEPTH) throw bad(where, "like chains more than " + MAX_LIKE_DEPTH + " deep");
        String like = raw.get("like").getAsString();
        JsonObject base = library.get(like);
        if (base == null) throw bad(where, "like `" + like + "` is not in the set-piece library " + new java.util.TreeSet<>(library.keySet()));
        JsonObject out = merged(where, base, library, depth + 1);
        for (Map.Entry<String, JsonElement> e : raw.entrySet()) {
            if (e.getKey().equals("like")) continue;
            if ((e.getKey().equals("palette") || e.getKey().equals("site")) && out.has(e.getKey()) && e.getValue().isJsonObject()) {
                JsonObject m = out.getAsJsonObject(e.getKey());
                e.getValue().getAsJsonObject().entrySet().forEach(p -> m.add(p.getKey(), p.getValue().deepCopy()));
            } else {
                out.add(e.getKey(), e.getValue().deepCopy());
            }
        }
        out.remove("like");
        return out;
    }

    private static Map<String, List<Weighted>> palette(String where, JsonObject o, Catalogue catalogue) {
        Map<String, List<Weighted>> out = new LinkedHashMap<>();
        if (!o.has("palette")) return out;
        for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("palette").entrySet()) {
            if (e.getKey().contains(":")) throw bad(where, "palette." + e.getKey() + ": a palette name has no namespace");
            List<Weighted> list = new ArrayList<>();
            JsonArray a = e.getValue().isJsonArray() ? e.getValue().getAsJsonArray() : null;
            if (a == null || a.isEmpty()) throw bad(where, "palette." + e.getKey() + " must be a non-empty list");
            for (JsonElement v : a) {
                String block;
                int weight = 1;
                if (v.isJsonObject()) {
                    block = v.getAsJsonObject().get("block").getAsString();
                    weight = v.getAsJsonObject().has("weight") ? v.getAsJsonObject().get("weight").getAsInt() : 1;
                } else {
                    block = v.getAsString();
                }
                checkBlock(where + ".palette." + e.getKey(), block, catalogue);
                if (weight < 1) throw bad(where, "palette." + e.getKey() + ": weights are 1 or more");
                list.add(new Weighted(block, weight));
            }
            out.put(e.getKey(), List.copyOf(list));
        }
        return out;
    }

    private static Element element(String where, JsonElement e, Map<String, List<Weighted>> palette, Catalogue catalogue,
                                   boolean item) {
        if (!e.isJsonObject()) throw bad(where, "must be an object");
        JsonObject o = e.getAsJsonObject();
        for (String key : o.keySet()) {
            if (!ELEMENT_FIELDS.contains(key)) throw bad(where, key + " is not an element field");
        }
        String kind = o.has("kind") ? o.get("kind").getAsString() : o.has("block") ? "block" : null;
        if (kind == null || !KINDS.contains(kind)) throw bad(where, "kind `" + kind + "` must be one of " + KINDS);
        int[] offset = {0, 0, 0};
        if (o.has("offset")) {
            JsonArray a = o.getAsJsonArray("offset");
            if (a.size() != 3) throw bad(where, "offset must be [x, y, z]");
            for (int i = 0; i < 3; i++) offset[i] = a.get(i).getAsInt();
        }
        int count = o.has("count") && !"scatter".equals(kind) ? o.get("count").getAsInt() : 1;
        if (count < 1) throw bad(where, "count must be 1 or more");
        double chance = o.has("chance") ? o.get("chance").getAsDouble() : 1.0;
        if (chance <= 0 || chance > 1) throw bad(where, "chance must be above 0 and at most 1");
        int around = o.has("around") ? o.get("around").getAsInt() : 0;
        if (around < 0) throw bad(where, "around must be 0 or more");
        Age age = o.has("age") ? age(where, o.get("age").getAsString()) : null;
        int weight = o.has("weight") ? o.get("weight").getAsInt() : 1;
        if (weight < 1) throw bad(where, "weight must be 1 or more");
        if (o.has("weight") && !item) throw bad(where, "weight applies to a scatter's items only");
        List<Element> items = List.of();
        switch (kind) {
            case "template" -> {
                String id = required(where, o, "id");
                if (id.startsWith("minecraft:") && catalogue.templateSize(id) == null) {
                    throw bad(where, "template `" + id + "` is not a vanilla structure template");
                }
                if (o.has("rotation") && !ROTATIONS.contains(o.get("rotation").getAsString())) throw bad(where, "rotation must be one of " + ROTATIONS);
                if (o.has("mirror") && !MIRRORS.contains(o.get("mirror").getAsString())) throw bad(where, "mirror must be one of " + MIRRORS);
                if (o.has("processors")) {
                    String p = o.get("processors").getAsString();
                    if (p.startsWith("minecraft:") && !catalogue.processorList(p)) throw bad(where, "processors `" + p + "` is not a vanilla processor list");
                }
                if (o.has("integrity")) {
                    double v = o.get("integrity").getAsDouble();
                    if (v < 0 || v > 1) throw bad(where, "integrity must be between 0 and 1");
                }
                if (o.has("embed") && o.get("embed").getAsInt() < 0) throw bad(where, "embed must be 0 or more");
            }
            case "scatter" -> {
                if (item) throw bad(where, "a scatter's items cannot be scatters");
                int n = o.has("count") ? o.get("count").getAsInt() : 0;
                if (n < 1) throw bad(where, "a scatter needs a count of 1 or more");
                if (!o.has("radius") || o.get("radius").getAsInt() < 0) throw bad(where, "a scatter needs a radius of 0 or more");
                if (!o.has("items") || !o.get("items").isJsonArray() || o.getAsJsonArray("items").isEmpty()) {
                    throw bad(where, "a scatter needs a non-empty items list");
                }
                List<Element> list = new ArrayList<>();
                JsonArray a = o.getAsJsonArray("items");
                for (int i = 0; i < a.size(); i++) list.add(element(where + ".items[" + i + "]", a.get(i), palette, catalogue, true));
                items = List.copyOf(list);
            }
            case "block" -> {
                String block = required(where, o, "block");
                if (!palette.containsKey(block)) checkBlock(where, block, catalogue);
            }
            case "chest" -> {
                String loot = o.has("arrangement") && !o.has("loot") ? "" : required(where, o, "loot");
                if (loot.startsWith("minecraft:") && !catalogue.lootTable(loot)) throw bad(where, "loot `" + loot + "` is not a vanilla loot table");
                String k = o.has("block") ? o.get("block").getAsString() : "chest";
                if (!CHEST_KINDS.contains(k)) throw bad(where, "a chest's block must be one of " + CHEST_KINDS);
                if (o.has("arrangement")) SetPieceKinds.arrangement(where, o, k, catalogue);
            }
            case "spawner" -> {
                String entity = required(where, o, "entity");
                if (entity.startsWith("minecraft:") && !catalogue.entity(entity)) throw bad(where, "entity `" + entity + "` is not a vanilla entity type");
                for (String k : List.of("delay", "range", "count")) {
                    if (o.has(k) && o.get(k).getAsInt() < 1) throw bad(where, k + " must be 1 or more");
                }
            }
            default -> {
                if (SetPieceShapes.KINDS.contains(kind)) {
                    if (item) throw bad(where, "a scatter's items are blocks, templates, chests and spawners, not shapes");
                    SetPieceShapes.validate(where, kind, o, b -> {
                        if (!palette.containsKey(b)) checkBlock(where, b, catalogue);
                    });
                } else {
                    SetPieceKinds.check(where, kind, o, palette, catalogue);
                }
            }
        }
        if (o.has("on") && item) throw bad(where, "a scatter's items cannot be seated on an element");
        if ("spawner".equals(kind) || "cell".equals(kind)) count = 1;
        return new Element(kind, offset, count, chance, around, age, weight, o.deepCopy(), items);
    }

    /** How far an element can reach from the origin: {blocks across, blocks up or down}. */
    static int[] reach(Element e, Catalogue catalogue) {
        int across = Math.max(Math.abs(e.offset()[0]), Math.abs(e.offset()[2])) + e.around();
        int up = Math.abs(e.offset()[1]);
        switch (e.kind()) {
            case "template" -> {
                int[] s = catalogue.templateSize(e.string("id", ""));
                if (s == null) s = new int[] {1, 1, 1};
                across += (Math.max(s[0], s[2]) + 1) / 2;
                up += s[1];
            }
            case "scatter" -> {
                int itemAcross = 0;
                int itemUp = 0;
                for (Element i : e.items()) {
                    int[] r = reach(i, catalogue);
                    itemAcross = Math.max(itemAcross, r[0]);
                    itemUp = Math.max(itemUp, r[1]);
                }
                across += e.integer("radius", 0) + itemAcross;
                up += itemUp;
            }
            case "block", "chest", "spawner" -> up += 1;
            default -> {
                int[] r = SetPieceShapes.KINDS.contains(e.kind()) ? SetPieceShapes.reach(e.kind(), e.params()) : SetPieceKinds.reach(e, catalogue);
                across += r[0];
                up += r[1];
            }
        }
        return new int[] {across, up};
    }

    private static void checkBlock(String where, String block, Catalogue catalogue) {
        String id = block.contains("[") ? block.substring(0, block.indexOf('[')) : block;
        if (!id.contains(":")) throw bad(where, "block `" + block + "` is neither a palette name nor a block id");
        if (id.startsWith("minecraft:") && !catalogue.block(id)) throw bad(where, "block `" + id + "` is not a vanilla block");
    }

    private static String required(String where, JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive()) throw bad(where, key + " is required");
        return o.get(key).getAsString();
    }

    private static Age age(String where, String name) {
        try {
            return Age.parse(name);
        } catch (IllegalArgumentException e) {
            throw bad(where, e.getMessage());
        }
    }

    private static IllegalArgumentException bad(String where, String why) {
        return new IllegalArgumentException(where + ": " + why);
    }

    // ---- reading and writing ---------------------------------------------------------------

    /** The resolved spec as the compiler writes it into the structure. */
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("key", key);
        o.addProperty("name", name);
        o.addProperty("seat", seat);
        o.addProperty("support", support);
        o.add("site", site.toJson());
        if (bandMin != null) {
            JsonArray b = new JsonArray();
            b.add(bandMin);
            b.add(bandMax);
            o.add("band", b);
        }
        o.addProperty("clear", clear);
        JsonObject p = new JsonObject();
        palette.forEach((k, list) -> {
            JsonArray a = new JsonArray();
            for (Weighted w : list) {
                JsonObject j = new JsonObject();
                j.addProperty("block", w.block());
                j.addProperty("weight", w.weight());
                a.add(j);
            }
            p.add(k, a);
        });
        o.add("palette", p);
        JsonArray els = new JsonArray();
        elements.forEach(e -> els.add(e.params().deepCopy()));
        o.add("elements", els);
        if (group != null) o.addProperty("group", group);
        if (rarity != null) o.addProperty("rarity", rarity);
        if (repetition != null) o.addProperty("repetition", repetition);
        o.addProperty("terrainAdaptation", terrainAdaptation);
        o.addProperty("size", size);
        if (age != null) o.addProperty("age", age.id());
        setting.write(o);
        if (setting.bands() != null) o.add("bands", setting.bands().toJson());
        return o;
    }

    /** This spec with the dimension's age by distance, for a piece that names no age of its own. */
    public SetPieceSpec withBands(SetPieceSetting.AgeBands bands) {
        return new SetPieceSpec(key, name, seat, support, site, bandMin, bandMax, clear, palette, elements, group, rarity, repetition,
                terrainAdaptation, size, age, setting.withBands(bands));
    }

    /** The spec as {@link #toJson} wrote it; trusted, so only the shape is read. */
    public static SetPieceSpec fromJson(JsonObject o) {
        String key = o.get("key").getAsString();
        String slug = key.substring(0, key.indexOf('/'));
        String name = key.substring(key.indexOf('/') + 1);
        JsonObject raw = new JsonObject();
        raw.addProperty("name", o.get("name").getAsString());
        raw.addProperty("seat", o.get("seat").getAsString());
        raw.addProperty("support", o.get("support").getAsDouble());
        raw.add("site", o.getAsJsonObject("site"));
        if (o.has("band")) raw.add("band", o.get("band"));
        raw.addProperty("clear", o.get("clear").getAsInt());
        raw.add("palette", o.getAsJsonObject("palette"));
        raw.add("elements", o.getAsJsonArray("elements"));
        for (String k : List.of("group", "rarity", "repetition", "terrainAdaptation", "size", "age", "reach", "rise", "depth", "bed",
                "approach", "lived")) {
            if (o.has(k)) raw.add(k, o.get(k));
        }
        SetPieceSpec spec = resolve(slug, name, raw, Map.of(), null, TRUSTED);
        return o.has("bands") ? spec.withBands(SetPieceSetting.AgeBands.fromJson(o.getAsJsonObject("bands"))) : spec;
    }

    /** As {@link #ANY}, with template sizes unknown so the reach check measures templates as a block. */
    private static final Catalogue TRUSTED = new Catalogue() {
        public int[] templateSize(String id) {
            return new int[] {1, 1, 1};
        }

        public boolean block(String id) {
            return true;
        }

        public boolean entity(String id) {
            return true;
        }

        public boolean lootTable(String id) {
            return true;
        }

        public boolean processorList(String id) {
            return true;
        }
    };
}
