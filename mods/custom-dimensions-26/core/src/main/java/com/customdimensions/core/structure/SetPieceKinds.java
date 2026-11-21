package com.customdimensions.core.structure;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The assemblies and the things inside (places/set-pieces.md § SP4,
 * lore/placement.md G10–G12): each kind's parameters checked at compile time
 * and its reach measured for the {@code size} check. Assemblies expand into
 * blocks, templates and things through {@link Assemblies}; things are placed
 * whole by the runtime. Every text is held to {@link Words}. Game-free.
 */
public final class SetPieceKinds {

    public static final Set<String> ASSEMBLIES = Set.of("wreck", "skeleton", "camp", "hovel", "farm", "works", "cell", "gate");
    public static final Set<String> THINGS = Set.of("mob", "archaeology", "banner", "stand", "frame", "lectern", "pot", "remains");
    public static final Set<String> FIELDS = Set.of("name", "persistent", "attributes", "equipment", "effects", "ride", "share",
            "depth", "color", "patterns", "facing", "turn", "arms", "small", "plate", "item", "glow", "behind", "lines", "pool", "pick",
            "title", "sherds", "pose", "gear", "skull", "wearer", "arrangement", "tilt", "angle", "trench", "debris", "length", "parts",
            "curve", "step", "state", "tents", "tent", "fire", "stores", "width", "height", "roof", "door", "crop", "plots", "irrigation",
            "fence", "plot", "hovel", "columns", "machines", "slag", "bars", "captive", "holder", "frame", "blocks", "marker", "study",
            "target", "precursor", "drops", "long", "half", "frameBlock", "aura");
    public static final Set<String> SLOTS = Set.of("head", "chest", "legs", "feet", "mainhand", "offhand", "body");
    public static final Set<String> FACINGS = Set.of("north", "south", "east", "west");
    public static final Set<String> STATES = Set.of("living", "abandoned", "burned");
    public static final Set<String> ROOFS = Set.of("flat", "pitched", "dome");
    public static final Set<String> HOLDERS = Set.of("floor", "minecart", "boat");
    public static final Set<String> RIDES = Set.of("none", "minecart", "boat");
    public static final Set<String> GATE_STATES = Set.of("broken", "attempt");
    public static final Set<String> FRAMES = Set.of("nether", "end", "custom");
    public static final Set<String> POSES = Set.of("lying", "seated");
    public static final Set<String> WEARERS = Set.of("stand", "skeleton", "none");
    public static final Set<String> SHERD_SIDES = Set.of("front", "back", "left", "right");
    public static final Set<String> IRRIGATION = Set.of("water", "lava", "none");
    /** Each crop and the block it is planted in. */
    public static final Map<String, String> CROPS = Map.of(
            "nether_wart", "minecraft:soul_sand", "crimson_fungus", "minecraft:crimson_nylium", "warped_fungus", "minecraft:warped_nylium",
            "wheat", "minecraft:farmland[moisture=7]", "carrots", "minecraft:farmland[moisture=7]", "potatoes", "minecraft:farmland[moisture=7]",
            "beetroots", "minecraft:farmland[moisture=7]", "mushroom", "minecraft:mycelium");
    /** An igniter's precursor for a study's chest: what comes before the thing that lights the portal. */
    public static final Map<String, String> PRECURSORS = Map.ofEntries(
            Map.entry("minecraft:flint_and_steel", "minecraft:flint"), Map.entry("minecraft:ender_eye", "minecraft:ender_pearl"),
            Map.entry("minecraft:gold_ingot", "minecraft:gold_nugget"), Map.entry("minecraft:netherite_scrap", "minecraft:ancient_debris"),
            Map.entry("minecraft:netherite_ingot", "minecraft:netherite_scrap"), Map.entry("minecraft:amethyst_shard", "minecraft:small_amethyst_bud"),
            Map.entry("minecraft:diamond", "minecraft:coal"), Map.entry("minecraft:torch", "minecraft:stick"),
            Map.entry("minecraft:water_bucket", "minecraft:bucket"), Map.entry("minecraft:blaze_rod", "minecraft:blaze_powder"),
            Map.entry("minecraft:diamond_sword", "minecraft:diamond"), Map.entry("minecraft:crossbow", "minecraft:string"),
            Map.entry("minecraft:fire_charge", "minecraft:gunpowder"));
    public static final int MAX_LINES = 8;
    public static final int MAX_LINE = 120;
    public static final int CHEST_SLOTS = 27;

    private SetPieceKinds() {
    }

    /** Checks a kind's parameters at {@code where}; may add resolved values to {@code o} (a gate's target frame). */
    static void check(String where, String kind, JsonObject o, Map<String, List<SetPieceSpec.Weighted>> palette,
                      SetPieceSpec.Catalogue catalogue) {
        ItemSpec.Ids ids = catalogue::known;
        switch (kind) {
            case "mob" -> mob(where, o, catalogue);
            case "archaeology" -> {
                double share = number(o, "share", 0.3);
                if (share <= 0 || share > 1) throw bad(where, "share must be above 0 and at most 1");
                loot(where, required(where, o, "loot"), catalogue);
                if (o.has("block") && !Set.of("sand", "gravel", "auto").contains(o.get("block").getAsString())) {
                    throw bad(where, "an archaeology block is sand, gravel or auto");
                }
                range(where, o, "radius", 0, 32);
                range(where, o, "depth", 1, 8);
            }
            case "banner" -> {
                ItemSpec.dye(where, required(where, o, "color"));
                if (o.has("patterns")) ItemSpec.layers(where + ".patterns", o.get("patterns"), ids);
                facing(where, o);
                range(where, o, "turn", 0, 15);
            }
            case "stand" -> {
                equipment(where + ".equipment", o, "equipment", ids);
                facing(where, o);
            }
            case "frame" -> {
                ItemSpec.read(where + ".item", o.get("item"), ids);
                if (o.has("facing") && !Set.of("north", "south", "east", "west", "up", "down").contains(o.get("facing").getAsString())) {
                    throw bad(where, "a frame's facing is north, south, east, west, up or down");
                }
                if (o.has("behind")) block(where, o.get("behind").getAsString(), palette, catalogue);
            }
            case "lectern" -> {
                lines(where, o, catalogue);
                facing(where, o);
            }
            case "pot" -> {
                if (o.has("sherds")) {
                    JsonObject s = o.getAsJsonObject("sherds");
                    for (Map.Entry<String, JsonElement> e : s.entrySet()) {
                        if (!SHERD_SIDES.contains(e.getKey())) throw bad(where, "sherds." + e.getKey() + " must be one of " + SHERD_SIDES);
                        String id = ItemSpec.qualified(e.getValue().getAsString());
                        if (!id.endsWith("_pottery_sherd") && !id.equals("minecraft:brick")) throw bad(where, "sherds." + e.getKey() + " is a sherd or minecraft:brick");
                        if (!catalogue.known("minecraft:item", id)) throw bad(where, "item `" + id + "` is not an item");
                    }
                }
                if (o.has("item")) ItemSpec.read(where + ".item", o.get("item"), ids);
                if (o.has("loot")) loot(where, o.get("loot").getAsString(), catalogue);
                if (o.has("item") && o.has("loot")) throw bad(where, "a pot holds an item or a loot table, not both");
                facing(where, o);
            }
            case "remains" -> {
                oneOf(where, o, "pose", POSES);
                oneOf(where, o, "wearer", WEARERS);
                equipment(where + ".gear", o, "gear", ids);
                if (o.has("skull")) block(where, o.get("skull").getAsString(), palette, catalogue);
            }
            case "wreck" -> {
                template(where, o, catalogue);
                int[] s = catalogue.templateSize(o.get("id").getAsString());
                if (s != null && !o.has("long")) {
                    o.addProperty("long", s[2] >= s[0] ? "z" : "x");
                    o.addProperty("half", Math.max(s[0], s[2]) / 2);
                    if (!o.has("radius")) o.addProperty("radius", Assemblies.debrisRadius(s));
                }
                if (!Tilt.KINDS.contains(string(o, "tilt", "none"))) throw bad(where, "tilt must be one of " + Tilt.KINDS);
                if (o.has("angle") && (o.get("angle").getAsDouble() <= 0 || o.get("angle").getAsDouble() > 90)) throw bad(where, "angle is above 0 and at most 90 degrees");
                range(where, o, "trench", 0, 48);
                range(where, o, "debris", 0, 128);
                range(where, o, "radius", 0, 48);
            }
            case "skeleton" -> {
                range(where, o, "length", 1, 12);
                range(where, o, "step", 2, 16);
                if (o.has("curve") && Math.abs(o.get("curve").getAsDouble()) > 60) throw bad(where, "curve is at most 60 degrees a segment");
                for (String id : list(o, "parts", Assemblies.SPINES)) templateId(where, id, catalogue);
                templateId(where, string(o, "skull", Assemblies.SKULL), catalogue);
            }
            case "camp" -> {
                oneOf(where, o, "state", STATES);
                range(where, o, "tents", 0, 8);
                range(where, o, "radius", 3, 24);
                for (String id : list(o, "tent", Assemblies.TENTS)) templateId(where, id, catalogue);
                if (o.has("stores")) {
                    JsonArray stores = o.getAsJsonArray("stores");
                    for (int i = 0; i < stores.size(); i++) {
                        JsonObject s = stores.get(i).getAsJsonObject();
                        loot(where + ".stores[" + i + "]", required(where + ".stores[" + i + "]", s, "loot"), catalogue);
                        String b = string(s, "block", "chest");
                        if (!SetPieceSpec.CHEST_KINDS.contains(b)) throw bad(where, "a store's block must be one of " + SetPieceSpec.CHEST_KINDS);
                        if (s.has("arrangement")) arrangement(where + ".stores[" + i + "]", s, b, catalogue);
                    }
                }
            }
            case "hovel" -> {
                range(where, o, "width", 1, 6);
                range(where, o, "height", 2, 6);
                oneOf(where, o, "roof", ROOFS);
                oneOf(where, o, "state", STATES);
            }
            case "farm" -> {
                String crop = required(where, o, "crop");
                if (!CROPS.containsKey(crop)) throw bad(where, "crop must be one of " + new java.util.TreeSet<>(CROPS.keySet()));
                range(where, o, "plots", 1, 6);
                range(where, o, "plot", 3, 9);
                oneOf(where, o, "irrigation", IRRIGATION);
                if (o.has("fence") && o.get("fence").isJsonPrimitive() && o.get("fence").getAsJsonPrimitive().isString()) {
                    block(where, o.get("fence").getAsString(), palette, catalogue);
                }
            }
            case "works" -> {
                range(where, o, "columns", 0, 6);
                range(where, o, "height", 3, 32);
                range(where, o, "lines", 0, 6);
                range(where, o, "machines", 0, 16);
                range(where, o, "slag", 0, 24);
            }
            case "cell" -> {
                range(where, o, "count", 1, 8);
                if (o.has("bars")) block(where, o.get("bars").getAsString(), palette, catalogue);
                oneOf(where, o, "holder", HOLDERS);
                if (o.has("captive")) {
                    if (!o.get("captive").isJsonObject()) throw bad(where, "captive is a mob: {entity, name, persistent, attributes, equipment, effects}");
                    mob(where + ".captive", o.getAsJsonObject("captive"), catalogue);
                }
            }
            case "gate" -> gate(where, o, palette, catalogue);
            default -> throw bad(where, "kind `" + kind + "` is not built");
        }
    }

    private static void mob(String where, JsonObject o, SetPieceSpec.Catalogue catalogue) {
        String entity = ItemSpec.qualified(required(where, o, "entity"));
        if (!catalogue.entity(entity)) throw bad(where, "entity `" + entity + "` is not a vanilla entity type");
        if (o.has("name")) Words.check(where + ".name", o.get("name").getAsString(), catalogue.dimensions());
        oneOf(where, o, "ride", RIDES);
        if (o.has("attributes")) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("attributes").entrySet()) {
                String id = ItemSpec.qualified(e.getKey());
                if (!catalogue.known("minecraft:attribute", id)) throw bad(where, "attribute `" + id + "` is not a vanilla attribute");
                if (!e.getValue().isJsonPrimitive() || !e.getValue().getAsJsonPrimitive().isNumber()) throw bad(where, "attribute " + id + " is a number");
            }
        }
        equipment(where + ".equipment", o, "equipment", catalogue::known);
        if (o.has("effects")) {
            for (JsonElement e : o.getAsJsonArray("effects")) {
                JsonObject ej = e.getAsJsonObject();
                String id = ItemSpec.qualified(required(where + ".effects", ej, "id"));
                if (!catalogue.known("minecraft:mob_effect", id)) throw bad(where, "effect `" + id + "` is not a vanilla mob effect");
                if (ej.has("amplifier") && (ej.get("amplifier").getAsInt() < 0 || ej.get("amplifier").getAsInt() > 255)) throw bad(where, "an effect's amplifier is 0 to 255");
            }
        }
    }

    private static void gate(String where, JsonObject o, Map<String, List<SetPieceSpec.Weighted>> palette, SetPieceSpec.Catalogue catalogue) {
        String state = string(o, "state", "broken");
        if (!GATE_STATES.contains(state)) throw bad(where, "a gate's state must be one of " + GATE_STATES);
        oneOf(where, o, "frame", FRAMES);
        boolean marker = o.has("marker") && o.get("marker").getAsBoolean();
        if (marker && o.has("width") && o.get("width").getAsInt() != 2) throw bad(where, "a marker gate's opening is 2 wide (the exit shrine contract)");
        if (marker && o.has("height") && o.get("height").getAsInt() != 3) throw bad(where, "a marker gate's opening is 3 tall (the exit shrine contract)");
        range(where, o, "width", 1, 8);
        range(where, o, "height", 2, 12);
        if (o.has("target") && !o.has("frameBlock")) {
            String target = o.get("target").getAsString();
            JsonObject portal = catalogue.portal(target);
            if (portal == null) throw bad(where, "target `" + target + "` is not a dimension with a portal");
            JsonElement fb = portal.get("frameBlock");
            String frame = fb == null ? null : fb.isJsonArray() ? fb.getAsJsonArray().get(0).getAsString() : fb.isJsonPrimitive() ? fb.getAsString() : null;
            if (frame == null) throw bad(where, "target `" + target + "`'s first portal names no frame block");
            o.addProperty("frameBlock", frame);
            if (!o.has("precursor") && portal.has("igniterItem")) {
                String p = PRECURSORS.get(portal.get("igniterItem").getAsString());
                if (p != null) o.addProperty("precursor", p);
            }
            if (portal.has("aura") && portal.get("aura").isJsonObject() && portal.getAsJsonObject("aura").has("palette")
                    && !"none".equals(string(portal.getAsJsonObject("aura"), "subsume", "everything"))) {
                o.add("aura", portal.getAsJsonObject("aura").get("palette").deepCopy());
            }
        }
        if ("custom".equals(string(o, "frame", "nether")) && !o.has("blocks") && !o.has("frameBlock")) {
            throw bad(where, "a custom frame names its blocks, or a target whose frame block it takes");
        }
        if (o.has("blocks")) for (JsonElement b : o.getAsJsonArray("blocks")) block(where, b.getAsString(), palette, catalogue);
        if (o.has("precursor")) ItemSpec.read(where + ".precursor", o.get("precursor"), catalogue::known);
        if (o.has("study")) {
            JsonElement s = o.get("study");
            if (!"attempt".equals(state)) throw bad(where, "a study stands beside an attempted gate (state attempt)");
            if (s.isJsonObject()) {
                JsonObject sj = s.getAsJsonObject();
                for (String key : sj.keySet()) {
                    if (!Set.of("lines", "pool", "pick", "title", "loot").contains(key)) throw bad(where, "study." + key + " is not a study field");
                }
                lines(where + ".study", sj, catalogue);
                if (sj.has("loot")) loot(where + ".study", sj.get("loot").getAsString(), catalogue);
            } else if (!s.isJsonPrimitive() || !s.getAsJsonPrimitive().isBoolean()) {
                throw bad(where, "study is true or {lines, pool, pick, title, loot}");
            }
        }
    }

    /** A lectern's book: fixed {@code lines} or a {@code pool} it draws {@code pick} from, each held to the words check. */
    private static void lines(String where, JsonObject o, SetPieceSpec.Catalogue catalogue) {
        if (o.has("lines") && o.has("pool")) throw bad(where, "lines or pool, not both");
        if (!o.has("lines") && !o.has("pool")) throw bad(where, "a book needs lines or a pool");
        JsonArray a = o.has("lines") ? o.getAsJsonArray("lines") : o.getAsJsonArray("pool");
        if (a.isEmpty() || (o.has("lines") && a.size() > MAX_LINES)) throw bad(where, "a book holds 1 to " + MAX_LINES + " lines");
        for (int i = 0; i < a.size(); i++) {
            String line = a.get(i).getAsString();
            if (line.isBlank() || line.length() > MAX_LINE) throw bad(where, "lines[" + i + "] is 1 to " + MAX_LINE + " characters");
            Words.check(where + ".lines[" + i + "]", line, catalogue.dimensions());
        }
        if (o.has("pick")) {
            int pick = o.get("pick").getAsInt();
            if (!o.has("pool") || pick < 1 || pick > Math.min(MAX_LINES, a.size())) throw bad(where, "pick is 1 to the pool's size, from a pool");
        }
        if (o.has("title")) {
            String title = o.get("title").getAsString();
            if (title.isBlank() || title.length() > 32) throw bad(where, "a title is 1 to 32 characters");
            Words.check(where + ".title", title, catalogue.dimensions());
        }
    }

    /** A chest's {@code arrangement}: {@code [{slot, item}]}, slots inside the container and each used once. */
    static void arrangement(String where, JsonObject o, String block, SetPieceSpec.Catalogue catalogue) {
        if ("decorated_pot".equals(block)) throw bad(where, "a decorated pot has one slot and no arrangement");
        JsonArray a = o.getAsJsonArray("arrangement");
        Set<Integer> used = new java.util.HashSet<>();
        for (int i = 0; i < a.size(); i++) {
            JsonObject s = a.get(i).getAsJsonObject();
            int slot = s.has("slot") ? s.get("slot").getAsInt() : -1;
            if (slot < 0 || slot >= CHEST_SLOTS) throw bad(where, "arrangement[" + i + "].slot must be 0 to " + (CHEST_SLOTS - 1));
            if (!used.add(slot)) throw bad(where, "arrangement slot " + slot + " is used twice");
            ItemSpec.read(where + ".arrangement[" + i + "].item", s.get("item"), catalogue::known);
        }
    }

    private static void equipment(String where, JsonObject o, String key, ItemSpec.Ids ids) {
        if (!o.has(key)) return;
        for (Map.Entry<String, JsonElement> e : o.getAsJsonObject(key).entrySet()) {
            if (!SLOTS.contains(e.getKey())) throw bad(where, e.getKey() + " must be one of " + SLOTS);
            ItemSpec.read(where + "." + e.getKey(), e.getValue(), ids);
        }
    }

    private static void template(String where, JsonObject o, SetPieceSpec.Catalogue catalogue) {
        templateId(where, required(where, o, "id"), catalogue);
        if (o.has("rotation") && !SetPieceSpec.ROTATIONS.contains(o.get("rotation").getAsString())) throw bad(where, "rotation must be one of " + SetPieceSpec.ROTATIONS);
        if (o.has("processors")) {
            String p = o.get("processors").getAsString();
            if (p.startsWith("minecraft:") && !catalogue.processorList(p)) throw bad(where, "processors `" + p + "` is not a vanilla processor list");
        }
        if (o.has("integrity") && (o.get("integrity").getAsDouble() < 0 || o.get("integrity").getAsDouble() > 1)) throw bad(where, "integrity must be between 0 and 1");
        range(where, o, "embed", 0, 64);
    }

    private static void templateId(String where, String id, SetPieceSpec.Catalogue catalogue) {
        if (id.startsWith("minecraft:") && catalogue.templateSize(id) == null) throw bad(where, "template `" + id + "` is not a vanilla structure template");
    }

    private static void loot(String where, String loot, SetPieceSpec.Catalogue catalogue) {
        if (loot.startsWith("minecraft:") && !catalogue.lootTable(loot)) throw bad(where, "loot `" + loot + "` is not a vanilla loot table");
    }

    private static void block(String where, String block, Map<String, List<SetPieceSpec.Weighted>> palette, SetPieceSpec.Catalogue catalogue) {
        if (palette.containsKey(block)) return;
        String id = block.contains("[") ? block.substring(0, block.indexOf('[')) : block;
        if (!id.contains(":")) throw bad(where, "block `" + block + "` is neither a palette name nor a block id");
        if (id.startsWith("minecraft:") && !catalogue.block(id)) throw bad(where, "block `" + id + "` is not a vanilla block");
    }

    private static void facing(String where, JsonObject o) {
        oneOf(where, o, "facing", FACINGS);
    }

    private static void oneOf(String where, JsonObject o, String key, Set<String> values) {
        if (o.has(key) && !values.contains(o.get(key).getAsString())) throw bad(where, key + " must be one of " + new java.util.TreeSet<>(values));
    }

    private static void range(String where, JsonObject o, String key, int min, int max) {
        if (!o.has(key)) return;
        JsonElement e = o.get(key);
        if (!e.isJsonPrimitive() || !((JsonPrimitive) e).isNumber()) throw bad(where, key + " is a whole number");
        int v = e.getAsInt();
        if (v < min || v > max) throw bad(where, key + " must be " + min + " to " + max);
    }

    // ---- reach ------------------------------------------------------------------------------

    /** How far a SP4 element reaches from its offset: {blocks across, blocks up}. */
    static int[] reach(SetPieceSpec.Element e, SetPieceSpec.Catalogue catalogue) {
        JsonObject o = e.params();
        return switch (e.kind()) {
            case "wreck" -> {
                int[] s = size(catalogue, string(o, "id", ""));
                int[] t = Tilt.reach(string(o, "tilt", "none"), o.has("angle") ? o.get("angle").getAsDouble() : null, s[0], s[1], s[2]);
                int debris = integer(o, "debris", 12) > 0 ? integer(o, "radius", Assemblies.debrisRadius(s)) : 0;
                yield new int[] {Math.max(Math.max(t[0], debris), integer(o, "trench", 0) + (Math.max(s[0], s[2]) + 1) / 2), t[1]};
            }
            case "skeleton" -> {
                int step = integer(o, "step", Assemblies.SPINE_STEP);
                int across = step * (integer(o, "length", 3) + 1) / 2 + (int) Math.ceil(step * 0.25);
                int[] skull = size(catalogue, string(o, "skull", Assemblies.SKULL));
                yield new int[] {across + Math.max(skull[0], skull[2]), Math.max(skull[1], 6)};
            }
            case "camp" -> {
                int tent = 0;
                int up = 3;
                for (String id : list(o, "tent", Assemblies.TENTS)) {
                    int[] s = size(catalogue, id);
                    tent = Math.max(tent, (Math.max(s[0], s[2]) + 1) / 2 + 1);
                    up = Math.max(up, s[1]);
                }
                yield new int[] {integer(o, "radius", Assemblies.CAMP_RADIUS) + tent, up};
            }
            case "hovel" -> new int[] {integer(o, "width", 2) + 2, integer(o, "height", 3) + 4};
            case "farm" -> {
                int plot = integer(o, "plot", 5);
                int plots = integer(o, "plots", 2);
                yield new int[] {(plots * (plot + 1)) / 2 + 2 + (o.has("hovel") && o.get("hovel").getAsBoolean() ? 6 : 0), 6};
            }
            case "works" -> new int[] {Math.max(integer(o, "columns", 2) * Assemblies.WORKS_STEP / 2 + 3, integer(o, "slag", 6)) + 2,
                    integer(o, "height", 10) + 2};
            case "cell" -> new int[] {integer(o, "count", 1) * Assemblies.CELL_STEP / 2 + 3, 6};
            case "gate" -> new int[] {integer(o, "width", 2) / 2 + 6, integer(o, "height", 3) + 3};
            case "remains" -> new int[] {2, 2};
            case "archaeology" -> new int[] {integer(o, "radius", 3), 1};
            default -> new int[] {0, 1};
        };
    }

    private static int[] size(SetPieceSpec.Catalogue catalogue, String id) {
        int[] s = catalogue.templateSize(id);
        return s == null ? new int[] {1, 1, 1} : s;
    }

    // ---- reading helpers --------------------------------------------------------------------

    static String string(JsonObject o, String key, String fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : fallback;
    }

    static int integer(JsonObject o, String key, int fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsJsonPrimitive().isNumber() ? o.get(key).getAsInt() : fallback;
    }

    static double number(JsonObject o, String key, double fallback) {
        return o.has(key) ? o.get(key).getAsDouble() : fallback;
    }

    static boolean flag(JsonObject o, String key, boolean fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsJsonPrimitive().isBoolean() ? o.get(key).getAsBoolean() : fallback;
    }

    static List<String> list(JsonObject o, String key, List<String> fallback) {
        if (!o.has(key)) return fallback;
        JsonElement e = o.get(key);
        if (e.isJsonPrimitive()) return List.of(e.getAsString());
        List<String> out = new java.util.ArrayList<>();
        for (JsonElement x : e.getAsJsonArray()) out.add(x.getAsString());
        return out;
    }

    private static String required(String where, JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive()) throw bad(where, key + " is required");
        return o.get(key).getAsString();
    }

    private static IllegalArgumentException bad(String where, String why) {
        return new IllegalArgumentException(where + ": " + why);
    }
}
