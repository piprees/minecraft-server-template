package com.customdimensions.core.structure;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The shapes of a set piece (places/set-pieces.md § Shapes): {@code crater},
 * {@code pit}, {@code fall}, {@code wall}, {@code line}, {@code column},
 * {@code field}, {@code crown} and {@code platform}, and the {@code approach}
 * a place carries (lore/placement.md G8). A shape is drawn column by column:
 * what a column receives follows from the shape's anchor, its parameters, its
 * points (resolved when the start is made), the seed, and that column's own
 * ground alone, and every random choice is a roll of the position, so a shape
 * split across chunks is continuous and one seed draws the same blocks every
 * time. Game-free; the runtime supplies the {@link World}.
 */
public final class SetPieceShapes {

    public static final Set<String> KINDS = Set.of("crater", "pit", "fall", "wall", "line", "column", "field", "crown", "platform");
    /** The parameters shapes read, beside the element fields every kind has. */
    public static final Set<String> FIELDS = Set.of("radius", "depth", "rim", "lining", "scorch", "core", "coreRadius", "shape",
            "fill", "fillDepth", "walls", "ledges", "liquid", "drop", "width", "pool", "length", "height", "thickness", "towers",
            "breaches", "palette", "from", "to", "style", "sag", "taper", "top", "edge", "cover", "over", "find", "rise", "window",
            "growth", "rail", "stairs", "footing", "post", "deck");
    public static final Set<String> LINE_STYLES = Set.of("chain", "pipe", "bars", "bridge", "rail", "path");
    public static final Set<String> COLUMN_STYLES = Set.of("chimney", "pillar", "spire", "stack", "cage");
    public static final Set<String> LIQUIDS = Set.of("lava", "water");
    public static final Set<String> PIT_SHAPES = Set.of("round", "rift");
    /** What {@link World#ground} answers for a column with no ground (the void). */
    public static final int NO_GROUND = Integer.MIN_VALUE;
    public static final int MAX_STAIRS = 16;
    public static final int MAX_FOOTING = 32;
    public static final int MAX_POST = 32;
    static final String AIR = "minecraft:air";
    static final String[] FACINGS = {"north", "east", "south", "west"};

    /** Approach point operations, as {@link SetPieceSetting.Approach#ops} writes them. */
    public static final int PATH = 0, STAIR = 1, DECK = 2, POST = 3, CHAIN = 4, LADDER = 5, DEBRIS = 6;

    private SetPieceShapes() {
    }

    /** The world a shape is drawn into. */
    public interface World {
        /** The first free block above the solid ground at a column, or {@link #NO_GROUND}. */
        int ground(int x, int z);

        /** The block id (no properties) at a position. */
        String block(int x, int y, int z);

        void set(int x, int y, int z, String state);

        /** The dimension's own stone, for footings. */
        String stone();
    }

    /** The base heights a start's points are resolved from. */
    public interface Heights {
        int at(int x, int z);
    }

    /**
     * One shape as a start resolved it: its kind, its anchor (the column's
     * seat height plus the element's {@code dy}), its rotation in quarter
     * turns, its seed, its parameters, the palette, its age, and its points
     * (a fall's outward axis, a line's two ends, a crown's tops, an
     * approach's operations).
     */
    public record Shape(String kind, int x, int y, int z, int rotation, long seed, JsonObject params,
                        Map<String, List<SetPieceSpec.Weighted>> palette, Age age, List<int[]> points) {

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("shape", kind);
            o.addProperty("x", x);
            o.addProperty("y", y);
            o.addProperty("z", z);
            o.addProperty("rotation", rotation);
            o.addProperty("seed", seed);
            o.add("params", params);
            JsonObject p = new JsonObject();
            palette.forEach((k, list) -> {
                JsonArray a = new JsonArray();
                for (SetPieceSpec.Weighted w : list) {
                    JsonObject j = new JsonObject();
                    j.addProperty("block", w.block());
                    j.addProperty("weight", w.weight());
                    a.add(j);
                }
                p.add(k, a);
            });
            o.add("palette", p);
            if (age != null) o.addProperty("age", age.id());
            JsonArray pts = new JsonArray();
            for (int[] pt : points) {
                JsonArray a = new JsonArray();
                for (int v : pt) a.add(v);
                pts.add(a);
            }
            o.add("points", pts);
            return o;
        }

        public static Shape fromJson(JsonObject o) {
            Map<String, List<SetPieceSpec.Weighted>> palette = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("palette").entrySet()) {
                List<SetPieceSpec.Weighted> list = new ArrayList<>();
                for (JsonElement w : e.getValue().getAsJsonArray()) {
                    list.add(new SetPieceSpec.Weighted(w.getAsJsonObject().get("block").getAsString(), w.getAsJsonObject().get("weight").getAsInt()));
                }
                palette.put(e.getKey(), List.copyOf(list));
            }
            List<int[]> points = new ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("points")) {
                JsonArray a = e.getAsJsonArray();
                int[] pt = new int[a.size()];
                for (int i = 0; i < pt.length; i++) pt[i] = a.get(i).getAsInt();
                points.add(pt);
            }
            return new Shape(o.get("shape").getAsString(), o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt(),
                    o.get("rotation").getAsInt(), o.get("seed").getAsLong(), o.getAsJsonObject("params"), palette,
                    o.has("age") ? Age.parse(o.get("age").getAsString()) : null, points);
        }
    }

    // ---- checking ---------------------------------------------------------------------------

    /**
     * Checks a shape element's parameters; {@code block} checks a palette
     * name or block id. Throws with {@code where} on the first fault.
     */
    public static void validate(String where, String kind, JsonObject o, Consumer<String> block) {
        switch (kind) {
            case "crater" -> {
                positive(where, o, "radius", true);
                positive(where, o, "depth", true);
                atLeast(where, o, "rim", 0);
                atLeast(where, o, "scorch", 0);
                positive(where, o, "coreRadius", false);
                blocks(o, block, "lining", "core");
            }
            case "pit" -> {
                positive(where, o, "radius", true);
                positive(where, o, "depth", true);
                if (o.has("shape") && !PIT_SHAPES.contains(o.get("shape").getAsString())) throw bad(where, "shape must be one of " + PIT_SHAPES);
                atLeast(where, o, "fillDepth", 0);
                atLeast(where, o, "ledges", 0);
                positive(where, o, "width", false);
                if (o.has("fillDepth") && o.get("fillDepth").getAsInt() >= o.get("depth").getAsInt()) throw bad(where, "fillDepth must be less than depth");
                if (o.has("fillDepth") && o.get("fillDepth").getAsInt() > 0 && !o.has("fill")) throw bad(where, "fillDepth needs a fill");
                blocks(o, block, "fill", "walls");
            }
            case "fall" -> {
                String liquid = o.has("liquid") ? o.get("liquid").getAsString() : "lava";
                if (!LIQUIDS.contains(liquid)) throw bad(where, "liquid must be one of " + LIQUIDS);
                atLeast(where, o, "drop", 0);
                positive(where, o, "width", false);
                atLeast(where, o, "pool", 0);
            }
            case "wall" -> {
                positive(where, o, "length", true);
                positive(where, o, "height", true);
                positive(where, o, "thickness", false);
                atLeast(where, o, "towers", 0);
                atLeast(where, o, "breaches", 0);
                if (o.has("towers") && o.get("towers").getAsInt() == 1) throw bad(where, "towers is the spacing in blocks: 0 for none, 2 or more");
                blocks(o, block, "palette");
            }
            case "line" -> {
                String style = required(where, o, "style");
                if (!LINE_STYLES.contains(style)) throw bad(where, "style must be one of " + LINE_STYLES);
                anchor(where + ".from", o, "from");
                anchor(where + ".to", o, "to");
                atLeast(where, o, "sag", 0);
                positive(where, o, "width", false);
                if (o.has("width") && o.get("width").getAsInt() > 5) throw bad(where, "width is at most 5");
                blocks(o, block, "palette", "post", "deck");
            }
            case "column" -> {
                if (!o.has("height") || o.get("height").getAsInt() == 0) throw bad(where, "height is required and not 0 (below 0 hangs down)");
                atLeast(where, o, "radius", 0);
                if (o.has("taper")) {
                    double t = o.get("taper").getAsDouble();
                    if (t < 0 || t > 1) throw bad(where, "taper must be between 0 and 1");
                }
                if (o.has("style") && !COLUMN_STYLES.contains(o.get("style").getAsString())) throw bad(where, "style must be one of " + COLUMN_STYLES);
                if (o.has("style") && o.get("style").getAsString().equals("chimney") && o.has("radius") && o.get("radius").getAsInt() < 1) {
                    throw bad(where, "a chimney needs a radius of 1 or more");
                }
                blocks(o, block, "palette", "top");
            }
            case "field" -> {
                positive(where, o, "radius", true);
                required(where, o, "cover");
                if (o.has("edge")) {
                    double e = o.get("edge").getAsDouble();
                    if (e < 0 || e > 1) throw bad(where, "edge must be between 0 and 1");
                }
                blocks(o, block, "cover", "over");
            }
            case "crown" -> {
                positive(where, o, "radius", true);
                required(where, o, "growth");
                if (o.has("find") && !"tops".equals(o.get("find").getAsString())) throw bad(where, "find must be tops");
                positive(where, o, "rise", false);
                positive(where, o, "window", false);
                blocks(o, block, "growth");
            }
            case "platform" -> {
                positive(where, o, "width", true);
                positive(where, o, "depth", true);
                blocks(o, block, "palette");
                if (o.has("rail") && !o.get("rail").getAsJsonPrimitive().isBoolean()) block.accept(o.get("rail").getAsString());
                if (o.has("stairs") && !o.get("stairs").getAsJsonPrimitive().isBoolean()) block.accept(o.get("stairs").getAsString());
            }
            default -> throw bad(where, "`" + kind + "` is not a shape");
        }
    }

    private static void anchor(String where, JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonArray() || o.getAsJsonArray(key).size() != 3) {
            throw bad(where, "must be [x, y, z], y a number or \"ground\" or \"roof\"");
        }
        JsonArray a = o.getAsJsonArray(key);
        if (!a.get(0).getAsJsonPrimitive().isNumber() || !a.get(2).getAsJsonPrimitive().isNumber()) throw bad(where, "x and z are numbers");
        JsonElement y = a.get(1);
        if (!y.getAsJsonPrimitive().isNumber() && !Set.of("ground", "roof").contains(y.getAsString())) {
            throw bad(where, "y is a number, \"ground\" or \"roof\"");
        }
    }

    private static void blocks(JsonObject o, Consumer<String> block, String... keys) {
        for (String k : keys) if (o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsJsonPrimitive().isString()) block.accept(o.get(k).getAsString());
    }

    private static void positive(String where, JsonObject o, String key, boolean required) {
        if (!o.has(key)) {
            if (required) throw bad(where, key + " is required");
            return;
        }
        if (o.get(key).getAsInt() < 1) throw bad(where, key + " must be 1 or more");
    }

    private static void atLeast(String where, JsonObject o, String key, int min) {
        if (o.has(key) && o.get(key).getAsInt() < min) throw bad(where, key + " must be " + min + " or more");
    }

    private static String required(String where, JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive()) throw bad(where, key + " is required");
        return o.get(key).getAsString();
    }

    private static IllegalArgumentException bad(String where, String why) {
        return new IllegalArgumentException(where + ": " + why);
    }

    // ---- extent -----------------------------------------------------------------------------

    /** How far a shape reaches from its anchor: {blocks across, blocks up or down}. */
    public static int[] reach(String kind, JsonObject o) {
        return switch (kind) {
            case "crater" -> {
                int r = i(o, "radius", 1);
                yield new int[] {Math.max(Math.max(r + rimWidth(r), i(o, "scorch", 0)), i(o, "coreRadius", 0)),
                        Math.max(i(o, "depth", 1) + 2, i(o, "rim", 2) + 1)};
            }
            case "pit" -> new int[] {i(o, "radius", 1) + 2, i(o, "depth", 1) + 2};
            case "fall" -> new int[] {Math.max((i(o, "width", 1) + 1) / 2, 2 * i(o, "pool", 2) + 2), i(o, "drop", 0) + 3};
            case "wall" -> new int[] {i(o, "length", 1) / 2 + (i(o, "thickness", 1) + 1) / 2 + 2, i(o, "height", 1) + 3};
            case "line" -> {
                JsonArray f = o.getAsJsonArray("from");
                JsonArray t = o.getAsJsonArray("to");
                int across = Math.max(Math.max(Math.abs(f.get(0).getAsInt()), Math.abs(f.get(2).getAsInt())),
                        Math.max(Math.abs(t.get(0).getAsInt()), Math.abs(t.get(2).getAsInt()))) + i(o, "width", 3) / 2 + 1;
                int up = Math.max(f.get(1).getAsJsonPrimitive().isNumber() ? Math.abs(f.get(1).getAsInt()) : 0,
                        t.get(1).getAsJsonPrimitive().isNumber() ? Math.abs(t.get(1).getAsInt()) : 0) + i(o, "sag", 0) + 1;
                yield new int[] {across, up};
            }
            case "column" -> new int[] {i(o, "radius", 1) + 1, Math.abs(i(o, "height", 1)) + 1};
            case "field" -> new int[] {i(o, "radius", 1), 2};
            case "crown" -> new int[] {i(o, "radius", 1), 3};
            case "platform" -> new int[] {Math.max(i(o, "width", 1) / 2 + 1, i(o, "depth", 1) + (stairs(o) ? MAX_STAIRS : 0)), 2};
            default -> new int[] {0, 1};
        };
    }

    static int rimWidth(int radius) {
        return Math.max(2, Math.round(radius / 4f));
    }

    private static boolean stairs(JsonObject o) {
        return o.has("stairs") && (!o.get("stairs").getAsJsonPrimitive().isBoolean() || o.get("stairs").getAsBoolean());
    }

    /** The shape's bounding box {minX, minY, minZ, maxX, maxY, maxZ}: every column it can write, and the heights round its anchor. */
    public static int[] box(Shape s) {
        if (s.kind().equals("approach") || s.kind().equals("line") || s.kind().equals("crown")) {
            int minX = s.x(), minZ = s.z(), maxX = s.x(), maxZ = s.z(), minY = s.y(), maxY = s.y();
            int pad = s.kind().equals("line") ? i(s.params(), "width", 3) / 2 + 1 : 1;
            for (int[] p : s.points()) {
                if (s.kind().equals("crown")) {
                    minX = Math.min(minX, p[0]);
                    maxX = Math.max(maxX, p[0]);
                    minZ = Math.min(minZ, p[1]);
                    maxZ = Math.max(maxZ, p[1]);
                } else {
                    minX = Math.min(minX, p[0]);
                    maxX = Math.max(maxX, p[0]);
                    minY = Math.min(minY, p[1]);
                    maxY = Math.max(maxY, p[1]);
                    minZ = Math.min(minZ, p[2]);
                    maxZ = Math.max(maxZ, p[2]);
                }
            }
            return new int[] {minX - pad, minY - MAX_POST - i(s.params(), "sag", 0), minZ - pad, maxX + pad, maxY + 8, maxZ + pad};
        }
        int[] r = reach(s.kind(), s.params());
        int across = r[0] + 1;
        return new int[] {s.x() - across, s.y() - r[1] - MAX_FOOTING, s.z() - across, s.x() + across, s.y() + r[1] + 16, s.z() + across};
    }

    /** The height a later element seats on when it names this shape's {@code top} (lore/placement.md G7). */
    public static int top(Shape s) {
        JsonObject o = s.params();
        return switch (s.kind()) {
            case "column" -> i(o, "height", 1) > 0 ? s.y() + i(o, "height", 1) : s.y();
            case "wall" -> s.y() + i(o, "height", 1);
            case "platform" -> s.y();
            case "crater", "pit" -> s.y() - i(o, "depth", 1) + i(o, "fillDepth", 0);
            default -> s.y() + 1;
        };
    }

    // ---- drawing ----------------------------------------------------------------------------

    /** Draws {@code s} into the columns {@code minX..maxX}, {@code minZ..maxZ} of {@code w}; nothing outside them is written or read. */
    public static void draw(Shape s, int minX, int minZ, int maxX, int maxZ, World w) {
        Ctx c = new Ctx(s, minX, minZ, maxX, maxZ, w);
        switch (s.kind()) {
            case "crater" -> crater(c);
            case "pit" -> pit(c);
            case "fall" -> fall(c);
            case "wall" -> wall(c);
            case "line" -> line(c);
            case "column" -> column(c);
            case "field" -> field(c);
            case "crown" -> crown(c);
            case "platform" -> platform(c);
            case "approach" -> approach(c);
            default -> throw new IllegalArgumentException("`" + s.kind() + "` is not a shape");
        }
    }

    private record Ctx(Shape s, int minX, int minZ, int maxX, int maxZ, World w) {
        boolean in(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        void put(int x, int y, int z, String state) {
            if (state != null && in(x, z)) w.set(x, y, z, state);
        }

        String block(int x, int y, int z) {
            return w.block(x, y, z);
        }

        int ground(int x, int z) {
            return w.ground(x, z);
        }

        /** A block from the palette entry {@code ref} (or {@code ref} as an id, or {@code fallback}), weathered to the shape's age. */
        String pick(String ref, String fallback, int x, int y, int z, int channel) {
            String chosen = ref == null ? fallback : ref;
            if (chosen == null) return null;
            List<SetPieceSpec.Weighted> list = s.palette().get(chosen);
            if (list != null) {
                int total = 0;
                for (SetPieceSpec.Weighted e : list) total += e.weight();
                double n = SetPiecePlan.roll(s.seed(), x, y, z, channel) * total;
                chosen = list.getLast().block();
                for (SetPieceSpec.Weighted e : list) {
                    if ((n -= e.weight()) < 0) {
                        chosen = e.block();
                        break;
                    }
                }
            }
            if (s.age() == null) return chosen;
            String id = chosen.contains("[") ? chosen.substring(0, chosen.indexOf('[')) : chosen;
            String weathered = s.age().weather(id, SetPiecePlan.roll(s.seed(), x, y, z, channel + 50), SetPiecePlan.roll(s.seed(), x, y, z, channel + 51));
            return weathered.equals(id) ? chosen : weathered;
        }

        double roll(int x, int y, int z, int channel) {
            return SetPiecePlan.roll(s.seed(), x, y, z, channel);
        }

        /** The world offset of local ({@code u}, {@code v}) in the shape's rotation. */
        int[] world(int u, int v) {
            int[] d = SetPiecePlan.rotate(u, v, s.rotation());
            return new int[] {s.x() + d[0], s.z() + d[1]};
        }

        /** The local ({@code u}, {@code v}) of world column ({@code x}, {@code z}). */
        int[] local(int x, int z) {
            return SetPiecePlan.rotate(x - s.x(), z - s.z(), (4 - s.rotation()) & 3);
        }

        String str(String key) {
            JsonObject o = s.params();
            return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsJsonPrimitive().isString() ? o.get(key).getAsString() : null;
        }

        int num(String key, int fallback) {
            return i(s.params(), key, fallback);
        }
    }

    static int i(JsonObject o, String key, int fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsJsonPrimitive().isNumber() ? o.get(key).getAsInt() : fallback;
    }

    static double d(JsonObject o, String key, double fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() && o.get(key).getAsJsonPrimitive().isNumber() ? o.get(key).getAsDouble() : fallback;
    }

    static boolean air(String id) {
        return id == null || id.equals(AIR) || id.equals("minecraft:cave_air") || id.equals("minecraft:void_air");
    }

    static boolean liquid(String id) {
        return id != null && (id.equals("minecraft:water") || id.equals("minecraft:lava"));
    }

    /** A bowl carved into the seat: its floor a paraboloid {@code depth} deep, a broken raised rim, a scorched ring, a core at the bottom. */
    private static void crater(Ctx c) {
        Shape s = c.s();
        int r = c.num("radius", 1);
        int depth = c.num("depth", 1);
        int rim = c.num("rim", 2);
        int rimW = rimWidth(r);
        int scorch = c.num("scorch", 0);
        String lining = c.str("lining");
        String scorchRef = s.palette().containsKey("scorch") ? "scorch" : lining;
        int ext = Math.max(r + rimW, scorch);
        for (int x = Math.max(c.minX(), s.x() - ext); x <= Math.min(c.maxX(), s.x() + ext); x++) {
            for (int z = Math.max(c.minZ(), s.z() - ext); z <= Math.min(c.maxZ(), s.z() + ext); z++) {
                double d = Math.hypot(x - s.x(), z - s.z());
                if (d > ext) continue;
                int g = c.ground(x, z);
                if (g == NO_GROUND) continue;
                int top = g - 1;
                if (d <= r) {
                    int floor = s.y() - (int) Math.round(depth * (1 - (d / r) * (d / r)));
                    if (floor <= top) {
                        for (int y = floor; y <= top; y++) c.put(x, y, z, AIR);
                        if (lining != null && !air(c.block(x, floor - 1, z))) c.put(x, floor - 1, z, c.pick(lining, null, x, floor - 1, z, 1));
                        top = floor - 1;
                    }
                } else if (d <= r + rimW && rim > 0) {
                    double t = (d - r) / rimW;
                    int h = (int) Math.round(rim * (1 - Math.abs(2 * t - 1)));
                    if (h > 0 && c.roll(x, 0, z, 3) < 0.8) {
                        String own = c.block(x, top, z);
                        for (int y = g; y < g + h; y++) {
                            c.put(x, y, z, lining != null ? c.pick(lining, null, x, y, z, 2) : air(own) ? null : own);
                        }
                        top = g + h - 1;
                    }
                }
                if (scorchRef != null && scorch > r && d > r && d <= scorch && c.roll(x, 1, z, 4) < 1 - (d - r) / (scorch - r + 1.0)) {
                    if (!air(c.block(x, top, z))) c.put(x, top, z, c.pick(scorchRef, null, x, top, z, 5));
                }
            }
        }
        String core = c.str("core");
        if (core != null) {
            int cr = c.num("coreRadius", Math.max(1, r / 6));
            int cy = s.y() - depth + cr - 1;
            for (int x = s.x() - cr; x <= s.x() + cr; x++) {
                for (int z = s.z() - cr; z <= s.z() + cr; z++) {
                    for (int y = cy - cr; y <= cy + cr; y++) {
                        if (Math.sqrt((x - s.x()) * (x - s.x()) + (y - cy) * (y - cy) + (z - s.z()) * (z - s.z())) <= cr + 0.25) {
                            c.put(x, y, z, c.pick(core, null, x, y, z, 6));
                        }
                    }
                }
            }
        }
    }

    /** Whether local ({@code u}, {@code v}) is inside a pit of {@code r} (a rift's half-width {@code w}) grown by {@code grow}. */
    private static boolean inPit(boolean rift, int u, int v, int r, int w, double grow) {
        if (!rift) return Math.hypot(u, v) <= r + grow;
        double a = r + grow;
        double b = w + grow;
        return (u / a) * (u / a) + (v / b) * (v / b) <= 1;
    }

    /** A shaft down from the seat: its floor filled, its walls dressed, ledges round its sides. */
    private static void pit(Ctx c) {
        Shape s = c.s();
        int r = c.num("radius", 1);
        boolean rift = "rift".equals(c.str("shape"));
        int w = c.num("width", Math.max(1, Math.round(r / 4f)));
        int depth = c.num("depth", 1);
        String fill = c.str("fill");
        int fillDepth = c.num("fillDepth", fill != null ? 1 : 0);
        String walls = c.str("walls");
        int ledges = c.num("ledges", 0);
        int bottom = s.y() - depth;
        int ext = r + 2;
        for (int x = Math.max(c.minX(), s.x() - ext); x <= Math.min(c.maxX(), s.x() + ext); x++) {
            for (int z = Math.max(c.minZ(), s.z() - ext); z <= Math.min(c.maxZ(), s.z() + ext); z++) {
                int[] l = c.local(x, z);
                boolean inside = inPit(rift, l[0], l[1], r, w, 0);
                boolean near = !inside && inPit(rift, l[0], l[1], r, w, 1.5);
                if (!inside && !near) continue;
                int g = c.ground(x, z);
                if (g == NO_GROUND) continue;
                if (inside) {
                    for (int y = bottom; y < g; y++) c.put(x, y, z, AIR);
                    for (int y = bottom; y < bottom + fillDepth; y++) c.put(x, y, z, c.pick(fill, null, x, y, z, 7));
                    if (walls != null && fillDepth == 0 && !air(c.block(x, bottom - 1, z))) c.put(x, bottom - 1, z, c.pick(walls, null, x, bottom - 1, z, 8));
                    boolean edge = !inPit(rift, l[0], l[1], r, w, -1.5);
                    for (int k = 0; k < ledges && edge; k++) {
                        int yk = bottom + fillDepth + (int) Math.round((depth - fillDepth) * (k + 1) / (ledges + 1.0));
                        double ak = 2 * Math.PI * SetPiecePlan.roll(s.seed(), k, 1, 0, 41);
                        double a = Math.atan2(z - s.z(), x - s.x());
                        double diff = Math.abs(Math.atan2(Math.sin(a - ak), Math.cos(a - ak)));
                        if (diff < 0.7) c.put(x, yk, z, c.pick(walls, c.w().stone(), x, yk, z, 9));
                    }
                } else if (walls != null) {
                    for (int y = bottom; y <= Math.min(g - 1, s.y() - 1); y++) {
                        if (!air(c.block(x, y, z))) c.put(x, y, z, c.pick(walls, null, x, y, z, 10));
                    }
                }
            }
        }
    }

    /** A source set into the top of a face, a notch open in front of it, and a pool at its foot; the curtain is the liquid's own flow. */
    private static void fall(Ctx c) {
        Shape s = c.s();
        String liquid = "minecraft:" + (c.str("liquid") != null ? c.str("liquid") : "lava");
        int width = c.num("width", 1);
        int pool = c.num("pool", 2);
        int drop = c.num("drop", 0);
        int[] out = s.points().isEmpty() ? new int[] {1, 0} : s.points().getFirst();
        int px = -out[1];
        int pz = out[0];
        int sy = s.y() - 2;
        for (int j = 0; j < width; j++) {
            int o = j - (width - 1) / 2;
            int x = s.x() + o * px;
            int z = s.z() + o * pz;
            c.put(x, sy, z, liquid);
            int fx = x + out[0];
            int fz = z + out[1];
            if (c.in(fx, fz) && !air(c.block(fx, sy, fz))) c.put(fx, sy, fz, AIR);
        }
        if (pool <= 0) return;
        int cx = s.x() + out[0] * (pool + 1);
        int cz = s.z() + out[1] * (pool + 1);
        for (int x = Math.max(c.minX(), cx - pool); x <= Math.min(c.maxX(), cx + pool); x++) {
            for (int z = Math.max(c.minZ(), cz - pool); z <= Math.min(c.maxZ(), cz + pool); z++) {
                if (Math.hypot(x - cx, z - cz) > pool + 0.25) continue;
                int g = c.ground(x, z);
                if (g == NO_GROUND || drop > 0 && g > sy - drop + 2) continue;
                c.put(x, g - 1, z, liquid);
            }
        }
    }

    /** A curtain wall along the piece's local x that follows the ground, towers every {@code towers} blocks, breaches broken through. */
    private static void wall(Ctx c) {
        Shape s = c.s();
        int length = c.num("length", 1);
        int height = c.num("height", 1);
        int thick = c.num("thickness", 1);
        int towers = c.num("towers", 0);
        int breaches = c.num("breaches", 0);
        String palette = c.str("palette");
        int half = length / 2;
        int vLo = -(thick - 1) / 2;
        int vHi = vLo + thick - 1;
        int tHalf = (thick + 1) / 2;
        int[][] gaps = new int[breaches][];
        for (int k = 0; k < breaches; k++) {
            gaps[k] = new int[] {-half + (int) (SetPiecePlan.roll(s.seed(), k, 2, 0, 42) * length), 2 + (int) (SetPiecePlan.roll(s.seed(), k, 3, 0, 43) * 3)};
        }
        int ext = half + tHalf + 2;
        for (int x = Math.max(c.minX(), s.x() - ext); x <= Math.min(c.maxX(), s.x() + ext); x++) {
            for (int z = Math.max(c.minZ(), s.z() - ext); z <= Math.min(c.maxZ(), s.z() + ext); z++) {
                int[] l = c.local(x, z);
                int u = l[0];
                int v = l[1];
                boolean tower = false;
                if (towers > 0) {
                    int ut = (int) Math.round(u / (double) towers) * towers;
                    tower = Math.abs(ut) <= half && Math.abs(u - ut) <= tHalf && v >= vLo - 1 && v <= vHi + 1;
                }
                boolean curtain = Math.abs(u) <= half && v >= vLo && v <= vHi;
                if (!tower && !curtain) continue;
                int h = tower ? height + 3 : height;
                if (!tower) {
                    for (int[] gap : gaps) {
                        if (Math.abs(u - gap[0]) * 2 <= gap[1]) h = (int) (c.roll(x, 0, z, 44) * 2);
                    }
                }
                int g = c.ground(x, z);
                if (g == NO_GROUND) continue;
                for (int y = g; y < g + h; y++) c.put(x, y, z, c.pick(palette, "minecraft:cobblestone", x, y, z, 11));
            }
        }
    }

    /** The points of a line from its first to its second point, sagging by {@code sag} at its middle, without repeats. */
    public static List<int[]> linePoints(int[] a, int[] b, int sag) {
        int n = Math.max(1, Math.max(Math.abs(b[0] - a[0]), Math.max(Math.abs(b[1] - a[1]), Math.abs(b[2] - a[2]))) * 2);
        List<int[]> out = new ArrayList<>();
        for (int k = 0; k <= n; k++) {
            double t = k / (double) n;
            int[] p = {(int) Math.round(a[0] + t * (b[0] - a[0])), (int) Math.round(a[1] + t * (b[1] - a[1]) - sag * 4 * t * (1 - t)),
                    (int) Math.round(a[2] + t * (b[2] - a[2]))};
            if (!out.isEmpty() && java.util.Arrays.equals(out.getLast(), p)) continue;
            out.add(p);
        }
        return out;
    }

    /** A run of blocks between the line's two points: chain, pipe on trestles, bars, a railed bridge, a rail on its bed, or a worn path. */
    private static void line(Ctx c) {
        Shape s = c.s();
        int[] a = s.points().get(0);
        int[] b = s.points().get(1);
        String style = c.str("style");
        List<int[]> pts = linePoints(a, b, c.num("sag", 0));
        int dx = b[0] - a[0];
        int dy = b[1] - a[1];
        int dz = b[2] - a[2];
        String axis = Math.abs(dy) >= Math.abs(dx) && Math.abs(dy) >= Math.abs(dz) ? "y" : Math.abs(dx) >= Math.abs(dz) ? "x" : "z";
        int px = Math.abs(dx) >= Math.abs(dz) ? 0 : 1;
        int pz = 1 - px;
        int width = c.num("width", "bridge".equals(style) ? 3 : 1);
        String palette = c.str("palette");
        String post = c.str("post");
        String deck = c.str("deck");
        if ("path".equals(style)) {
            Set<Long> done = new java.util.HashSet<>();
            for (int[] p : pts) {
                for (int o = -(width - 1) / 2; o <= width / 2; o++) {
                    int x = p[0] + o * px;
                    int z = p[2] + o * pz;
                    if (c.in(x, z) && done.add(((long) x << 32) ^ (z & 0xffffffffL))) path(c, x, z, 0.8);
                }
            }
            return;
        }
        for (int k = 0; k < pts.size(); k++) {
            int[] p = pts.get(k);
            switch (style) {
                case "chain" -> c.put(p[0], p[1], p[2], "minecraft:iron_chain[axis=" + axis + "]");
                case "bars" -> c.put(p[0], p[1], p[2], c.pick(palette, "minecraft:iron_bars", p[0], p[1], p[2], 12));
                case "pipe" -> {
                    int g = k % 6 == 0 && c.in(p[0], p[2]) ? c.ground(p[0], p[2]) : NO_GROUND;
                    c.put(p[0], p[1], p[2], c.pick(palette, "minecraft:waxed_cut_copper", p[0], p[1], p[2], 12));
                    for (int y = p[1] - 1; g != NO_GROUND && y >= Math.max(g, p[1] - MAX_POST); y--) {
                        c.put(p[0], y, p[2], c.pick(post, "minecraft:spruce_fence", p[0], y, p[2], 13));
                    }
                }
                case "bridge" -> {
                    int lo = -(width - 1) / 2;
                    int hi = width / 2;
                    for (int o = lo; o <= hi; o++) {
                        int x = p[0] + o * px;
                        int z = p[2] + o * pz;
                        c.put(x, p[1] - 1, z, c.pick(deck, "minecraft:spruce_planks", x, p[1] - 1, z, 14));
                        if (width > 1 && (o == lo || o == hi)) c.put(x, p[1], z, c.pick(post, "minecraft:spruce_fence", x, p[1], z, 15));
                    }
                }
                case "rail" -> {
                    if (c.in(p[0], p[2]) && air(c.block(p[0], p[1] - 1, p[2]))) {
                        c.put(p[0], p[1] - 1, p[2], c.pick(deck, "minecraft:stone_bricks", p[0], p[1] - 1, p[2], 16));
                    }
                    c.put(p[0], p[1], p[2], "minecraft:rail");
                }
                default -> {
                }
            }
        }
    }

    /** One worn-path column: the ground's top turned to path, coarse dirt, or gravel on sand and gravel, kept at {@code keep}. */
    private static void path(Ctx c, int x, int z, double keep) {
        int g = c.ground(x, z);
        if (g == NO_GROUND || c.roll(x, 0, z, 45) >= keep) return;
        String top = c.block(x, g - 1, z);
        if (air(top) || liquid(top)) return;
        String b = top.contains("sand") || top.contains("gravel") ? "minecraft:gravel"
                : c.roll(x, 1, z, 46) < 0.6 ? "minecraft:dirt_path" : "minecraft:coarse_dirt";
        if (b.equals("minecraft:dirt_path") && !air(c.block(x, g, z))) b = "minecraft:coarse_dirt";
        c.put(x, g - 1, z, b);
    }

    /** A vertical: pillar, tapering spire, stack of the palette's entries in turn, hollow chimney over a campfire, or a cage of bars. */
    private static void column(Ctx c) {
        Shape s = c.s();
        int height = c.num("height", 1);
        int n = Math.abs(height);
        int r = c.num("radius", 1);
        String style = c.str("style") != null ? c.str("style") : "pillar";
        double taper = d(s.params(), "taper", "spire".equals(style) ? 1 : 0);
        String palette = c.str("palette");
        String fallback = switch (style) {
            case "chimney" -> "minecraft:bricks";
            case "cage" -> "minecraft:calcite";
            default -> "minecraft:stone_bricks";
        };
        List<SetPieceSpec.Weighted> stack = palette == null ? null : s.palette().get(palette);
        if (height > 0 && !(s.params().has("footing") && !s.params().get("footing").getAsBoolean())) {
            for (int x = s.x() - r; x <= s.x() + r; x++) {
                for (int z = s.z() - r; z <= s.z() + r; z++) {
                    if (Math.hypot(x - s.x(), z - s.z()) > r + 0.5 || !c.in(x, z)) continue;
                    footing(c, x, z, s.y() - 1, c.ground(x, z), c.w().stone());
                }
            }
        }
        for (int k = 0; k < n; k++) {
            int y = height > 0 ? s.y() + k : s.y() - 1 - k;
            double rr = r * (1 - taper * k / (double) n);
            int ext = (int) Math.ceil(rr);
            for (int x = s.x() - ext; x <= s.x() + ext; x++) {
                for (int z = s.z() - ext; z <= s.z() + ext; z++) {
                    double d = Math.hypot(x - s.x(), z - s.z());
                    if (d > rr + 0.5) continue;
                    boolean shell = d > rr - 1;
                    String b = switch (style) {
                        case "stack" -> stack != null ? stack.get(k % stack.size()).block() : c.pick(palette, fallback, x, y, z, 17);
                        case "chimney" -> shell ? c.pick(palette, fallback, x, y, z, 17) : k == 0 && d < 0.5 ? "minecraft:campfire" : AIR;
                        case "cage" -> k == 0 || k == n - 1 ? c.pick(palette, fallback, x, y, z, 17) : shell ? "minecraft:iron_bars" : AIR;
                        default -> c.pick(palette, fallback, x, y, z, 17);
                    };
                    c.put(x, y, z, b);
                }
            }
        }
        String top = c.str("top");
        if (top != null) {
            int y = height > 0 ? s.y() + n : s.y() - n - 1;
            c.put(s.x(), y, s.z(), c.pick(top, null, s.x(), y, s.z(), 18));
        }
    }

    /**
     * Fills a column from {@code fromY} down to its ground {@code g} (read
     * before the shape wrote there) with {@code block}, never past
     * {@value #MAX_FOOTING} blocks or over the void.
     */
    private static void footing(Ctx c, int x, int z, int fromY, int g, String block) {
        if (g == NO_GROUND || g > fromY) return;
        for (int y = fromY; y >= Math.max(g, fromY - MAX_FOOTING + 1); y--) {
            if (air(c.block(x, y, z)) || liquid(c.block(x, y, z))) c.put(x, y, z, block);
        }
    }

    /** The edge of a field at angle {@code a}: its radius less up to {@code edge} of it, from twelve seeded knots smoothly joined. */
    static double fieldEdge(long seed, double radius, double edge, double a) {
        double t = (a / (2 * Math.PI) + 1) % 1 * 12;
        int k = (int) Math.floor(t);
        double f = t - k;
        double v0 = SetPiecePlan.roll(seed, k % 12, 0, 0, 46);
        double v1 = SetPiecePlan.roll(seed, (k + 1) % 12, 0, 0, 46);
        double m = (1 - Math.cos(Math.PI * f)) / 2;
        return radius * (1 - edge * (v0 * (1 - m) + v1 * m));
    }

    /** A surface treatment: the ground's top re-skinned with {@code cover} inside a noisy edge, {@code over} on top of half of it. */
    private static void field(Ctx c) {
        Shape s = c.s();
        int r = c.num("radius", 1);
        double edge = d(s.params(), "edge", 0.3);
        String cover = c.str("cover");
        String over = c.str("over");
        for (int x = Math.max(c.minX(), s.x() - r); x <= Math.min(c.maxX(), s.x() + r); x++) {
            for (int z = Math.max(c.minZ(), s.z() - r); z <= Math.min(c.maxZ(), s.z() + r); z++) {
                double d = Math.hypot(x - s.x(), z - s.z());
                if (d > fieldEdge(s.seed(), r, edge, Math.atan2(z - s.z(), x - s.x()))) continue;
                int g = c.ground(x, z);
                if (g == NO_GROUND) continue;
                String top = c.block(x, g - 1, z);
                if (air(top) || liquid(top)) continue;
                c.put(x, g - 1, z, c.pick(cover, null, x, g - 1, z, 19));
                if (over != null && air(c.block(x, g, z)) && c.roll(x, g, z, 20) < 0.5) c.put(x, g, z, c.pick(over, null, x, g, z, 21));
            }
        }
    }

    /**
     * The local maxima round ({@code cx}, {@code cz}) within {@code radius}
     * on a grid of {@code step}: columns no lower than any other within
     * {@code window} (ties go to the first in x then z), standing {@code rise}
     * or more above the lowest of them.
     */
    public static List<int[]> tops(Heights base, int cx, int cz, int radius, int window, int rise, int step) {
        Map<Long, Integer> cache = new HashMap<>();
        java.util.function.BiFunction<Integer, Integer, Integer> h = (x, z) -> cache.computeIfAbsent(((long) x << 32) ^ (z & 0xffffffffL),
                k -> base.at(x, z));
        List<int[]> out = new ArrayList<>();
        for (int x = cx - radius; x <= cx + radius; x += step) {
            for (int z = cz - radius; z <= cz + radius; z += step) {
                if (Math.hypot(x - cx, z - cz) > radius) continue;
                int here = h.apply(x, z);
                if (here == NO_GROUND) continue;
                int lo = here;
                boolean max = true;
                for (int ox = -window; ox <= window && max; ox += step) {
                    for (int oz = -window; oz <= window && max; oz += step) {
                        if (ox == 0 && oz == 0) continue;
                        int there = h.apply(x + ox, z + oz);
                        if (there == NO_GROUND) continue;
                        if (there > here || there == here && (ox < 0 || ox == 0 && oz < 0)) max = false;
                        lo = Math.min(lo, there);
                    }
                }
                if (max && here - lo >= rise) out.add(new int[] {x, z});
            }
        }
        return out;
    }

    /** Growth on each of the crown's tops: one or two blocks of {@code growth} on the column's ground. */
    private static void crown(Ctx c) {
        String growth = c.str("growth");
        for (int[] t : c.s().points()) {
            if (!c.in(t[0], t[1])) continue;
            int g = c.ground(t[0], t[1]);
            if (g == NO_GROUND) continue;
            int n = c.roll(t[0], 0, t[1], 47) < 0.4 ? 2 : 1;
            for (int k = 0; k < n; k++) c.put(t[0], g + k, t[1], c.pick(growth, null, t[0], g + k, t[1], 22));
        }
    }

    /** A deck {@code width} across and {@code depth} out at the anchor, a rail round its edge, stairs from its front down to the ground. */
    private static void platform(Ctx c) {
        Shape s = c.s();
        int width = c.num("width", 1);
        int depth = c.num("depth", 1);
        String palette = c.str("palette");
        boolean rail = !s.params().has("rail") || !s.params().get("rail").getAsJsonPrimitive().isBoolean() || s.params().get("rail").getAsBoolean();
        String railBlock = c.str("rail");
        boolean stairs = stairs(s.params());
        String stairBlock = c.str("stairs") != null ? c.str("stairs") : "minecraft:stone_brick_stairs";
        boolean footing = s.params().has("footing") && s.params().get("footing").getAsBoolean();
        int lo = -(width / 2);
        int hi = lo + width - 1;
        int deckY = s.y() - 1;
        for (int u = lo; u <= hi; u++) {
            for (int v = 0; v < depth; v++) {
                int[] p = c.world(u, v);
                int g = footing && c.in(p[0], p[1]) ? c.ground(p[0], p[1]) : NO_GROUND;
                c.put(p[0], deckY, p[1], c.pick(palette, "minecraft:stone_bricks", p[0], deckY, p[1], 23));
                boolean edge = u == lo || u == hi || v == 0 || v == depth - 1;
                boolean gap = stairs && v == depth - 1 && u == 0;
                if (rail && edge && !gap) c.put(p[0], s.y(), p[1], c.pick(railBlock, "minecraft:stone_brick_wall", p[0], s.y(), p[1], 24));
                footing(c, p[0], p[1], deckY - 1, g, c.w().stone());
            }
        }
        if (!stairs) return;
        int[] back = SetPiecePlan.rotate(0, -1, s.rotation());
        String facing = facing(back[0], back[1]);
        for (int k = 0; k < MAX_STAIRS; k++) {
            int[] p = c.world(0, depth + k);
            int y = deckY - k;
            if (!c.in(p[0], p[1])) continue;
            int g = c.ground(p[0], p[1]);
            if (g == NO_GROUND || y < g) continue;
            c.put(p[0], y, p[1], stairBlock + "[facing=" + facing + "]");
            footing(c, p[0], p[1], y - 1, g, c.pick(palette, "minecraft:stone_bricks", p[0], y - 1, p[1], 25));
        }
    }

    /** The horizontal facing of a unit step. */
    public static String facing(int dx, int dz) {
        return dz < 0 ? "north" : dx > 0 ? "east" : dz > 0 ? "south" : "west";
    }

    /** An approach's operations, each at its point: path columns on their own ground, stairs, deck and posts, chain, ladder, debris. */
    private static void approach(Ctx c) {
        String block = c.str("block");
        for (int[] p : c.s().points()) {
            int x = p[0];
            int y = p[1];
            int z = p[2];
            if (!c.in(x, z)) continue;
            String facing = p.length > 4 ? FACINGS[p[4] & 3] : "north";
            switch (p[3]) {
                case PATH -> path(c, x, z, 0.85);
                case STAIR -> c.put(x, y, z, (c.str("stairs") != null ? c.str("stairs") : "minecraft:cobblestone_stairs") + "[facing=" + facing + "]");
                case DECK -> c.put(x, y, z, c.pick(c.str("deck"), "minecraft:spruce_planks", x, y, z, 27));
                case POST -> c.put(x, y, z, c.pick(c.str("post"), "minecraft:spruce_fence", x, y, z, 28));
                case CHAIN -> c.put(x, y, z, "minecraft:iron_chain[axis=y]");
                case LADDER -> c.put(x, y, z, "minecraft:ladder[facing=" + facing + "]");
                case DEBRIS -> {
                    int g = c.ground(x, z);
                    if (g != NO_GROUND) c.put(x, g, z, c.pick(block, "minecraft:cobblestone", x, g, z, 29));
                }
                default -> {
                }
            }
        }
    }
}
