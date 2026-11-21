package com.customdimensions.core.structure;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;

import static com.customdimensions.core.structure.SetPieceKinds.flag;
import static com.customdimensions.core.structure.SetPieceKinds.integer;
import static com.customdimensions.core.structure.SetPieceKinds.list;
import static com.customdimensions.core.structure.SetPieceKinds.number;
import static com.customdimensions.core.structure.SetPieceKinds.string;

/**
 * An assembly placement (places/set-pieces.md § Assemblies) and a
 * {@code remains} preset expanded into the blocks, templates and things it is
 * made of, decided from the placement's seed alone. Each part's offset is
 * from the set piece's origin in its rotation, and its height is above the
 * seat at the assembly's own column, or at the part's column when its params
 * say {@code follow}. A {@code burned} state swaps wood for its burned
 * palette, and an age drops structural blocks to its integrity. Game-free.
 */
public final class Assemblies {

    public static final List<String> SPINES = List.of("minecraft:fossil/spine_1", "minecraft:fossil/spine_2", "minecraft:fossil/spine_3",
            "minecraft:fossil/spine_4");
    public static final String SKULL = "minecraft:fossil/skull_1";
    public static final List<String> TENTS = List.of("minecraft:pillager_outpost/feature_tent1", "minecraft:pillager_outpost/feature_tent2");
    public static final List<String> BURNED = List.of("minecraft:coal_block", "minecraft:blackstone", "minecraft:basalt");
    public static final List<String> ASH = List.of("minecraft:gray_concrete_powder", "minecraft:black_concrete_powder", "minecraft:coarse_dirt");
    public static final List<String> SLAG = List.of("minecraft:magma_block", "minecraft:blackstone", "minecraft:basalt", "minecraft:coal_block");
    public static final List<String> MACHINES = List.of("minecraft:blast_furnace", "minecraft:smoker", "minecraft:cauldron", "minecraft:anvil",
            "minecraft:hopper", "minecraft:observer", "minecraft:piston", "minecraft:stonecutter");
    /** Notes for a study when it names none: in the voice of someone who never understood it either (RB-43). */
    public static final List<String> STUDY_LINES = List.of("The frame holds, but it will not wake.", "Not the right stone. Or not the right order.",
            "It answered once, I think. I could not make it answer again.", "Tried the light again. Nothing.",
            "The ground round it is changing. Slowly.", "Something is missing from the middle.");
    public static final String TEMPLATE_BLOCK = "@template";
    public static final int SPINE_STEP = 11;
    public static final int CAMP_RADIUS = 7;
    public static final int WORKS_STEP = 7;
    public static final int CELL_STEP = 4;
    static final String[] FACING = {"north", "east", "south", "west"};

    private Assemblies() {
    }

    /** Whether {@code kind} is expanded here rather than placed whole. */
    public static boolean expands(String kind) {
        return SetPieceKinds.ASSEMBLIES.contains(kind) || "remains".equals(kind);
    }

    static int debrisRadius(int[] size) {
        return Math.max(size[0], size[2]) / 2 + 6;
    }

    /** The parts of assembly placement {@code at} of {@code spec}. */
    public static List<SetPiecePlan.Placement> expand(SetPieceSpec spec, SetPiecePlan.Placement at) {
        Out out = new Out(spec, at);
        JsonObject o = at.source().params();
        switch (at.kind()) {
            case "wreck" -> wreck(out, o);
            case "skeleton" -> skeleton(out, o);
            case "camp" -> camp(out, o);
            case "hovel" -> hovel(out, o, 0, 0, integer(o, "width", 2), string(o, "state", "living"));
            case "farm" -> farm(out, o);
            case "works" -> works(out, o);
            case "cell" -> cell(out, o);
            case "gate" -> gate(out, o);
            case "remains" -> remains(out, o, 0, 0);
            default -> throw new IllegalArgumentException("kind `" + at.kind() + "` is not an assembly");
        }
        return List.copyOf(out.parts);
    }

    // ---- the builder ------------------------------------------------------------------------

    /** Collects parts in the assembly's own frame: x forward, z to the right, y above its seat. */
    static final class Out {
        final SetPieceSpec spec;
        final SetPiecePlan.Placement at;
        final List<SetPiecePlan.Placement> parts = new ArrayList<>();
        final SplittableRandom random;
        final double integrity;
        boolean burned;

        Out(SetPieceSpec spec, SetPiecePlan.Placement at) {
            this.spec = spec;
            this.at = at;
            this.random = new SplittableRandom(at.seed());
            Double fixed = at.source().number("integrity");
            this.integrity = fixed != null ? fixed : at.age() == null ? 1.0 : at.age().integrity(at.roll());
        }

        double roll() {
            return random.nextDouble();
        }

        /** A palette entry: the spec's palette {@code name} when it has one, else a pick from {@code fallback}. */
        String pick(String name, List<String> fallback) {
            List<SetPieceSpec.Weighted> list = spec.palette().get(name);
            if (list == null) return fallback.get(random.nextInt(fallback.size()));
            int total = 0;
            for (SetPieceSpec.Weighted w : list) total += w.weight();
            int n = random.nextInt(total);
            for (SetPieceSpec.Weighted w : list) {
                if ((n -= w.weight()) < 0) return w.block();
            }
            return list.getLast().block();
        }

        /** A block param: a palette name, a block, or {@code fallback}. */
        String block(JsonObject o, String key, String fallback) {
            String b = string(o, key, null);
            if (b == null) return fallback;
            return spec.palette().containsKey(b) ? pick(b, List.of(fallback)) : b;
        }

        void block(int lx, int ly, int lz, String state, boolean follow, boolean decays) {
            if (state == null) return;
            if (decays && roll() >= integrity) return;
            String s = burned ? burn(state, roll(), spec.palette().containsKey("burned") ? null : BURNED, this) : state;
            if (s == null) return;
            JsonObject p = new JsonObject();
            p.addProperty("kind", "block");
            p.addProperty("block", rotateState(s, at.rotation()));
            add("block", lx, ly, lz, p, follow, at.rotation(), p.get("block").getAsString());
        }

        void thing(String kind, int lx, int ly, int lz, JsonObject params, boolean follow) {
            JsonObject p = params.deepCopy();
            p.addProperty("kind", kind);
            if (p.has("facing") && SetPieceKinds.FACINGS.contains(p.get("facing").getAsString())) {
                p.addProperty("facing", turn(p.get("facing").getAsString(), at.rotation()));
            }
            if (p.has("turn")) p.addProperty("turn", (p.get("turn").getAsInt() + 4 * at.rotation()) & 15);
            String block = "chest".equals(kind) ? "minecraft:" + string(p, "block", "chest") : null;
            add(kind, lx, ly, lz, p, follow, at.rotation(), block);
        }

        void template(int lx, int ly, int lz, JsonObject params, int own, boolean follow) {
            JsonObject p = params.deepCopy();
            p.addProperty("kind", "template");
            if (burned) p.addProperty("burned", true);
            add("template", lx, ly, lz, p, follow, (own + at.rotation()) & 3, null);
        }

        private void add(String kind, int lx, int ly, int lz, JsonObject params, boolean follow, int rotation, String block) {
            if (follow) params.addProperty("follow", true);
            int[] d = SetPiecePlan.rotate(lx, lz, at.rotation());
            SetPieceSpec.Element e = new SetPieceSpec.Element(kind, new int[] {0, 0, 0}, 1, 1.0, 0, at.age(), 1, params, List.of());
            parts.add(new SetPiecePlan.Placement(at.element(), kind, at.dx() + d[0], at.dy() + ly, at.dz() + d[1], e, block, rotation,
                    at.mirror(), at.age(), random.nextLong(), random.nextDouble()));
        }
    }

    // ---- the assemblies ---------------------------------------------------------------------

    private static void wreck(Out out, JsonObject o) {
        JsonObject t = new JsonObject();
        for (String k : List.of("id", "tilt", "angle", "embed", "integrity", "processors")) if (o.has(k)) t.add(k, o.get(k));
        out.template(0, 0, 0, t, 0, false);
        boolean longZ = !"x".equals(string(o, "long", "z"));
        int half = integer(o, "half", 8);
        int trench = integer(o, "trench", 0);
        for (int s = -half - trench; trench > 0 && s <= half; s++) {
            int depth = s > half - 4 ? 3 : s > -half ? 2 : 1;
            for (int w = -1; w <= 1; w++) {
                int lx = longZ ? w : s;
                int lz = longZ ? s : w;
                for (int k = 1; k <= depth; k++) out.block(lx, -k, lz, "minecraft:air", true, false);
                out.block(lx, -depth - 1, lz, out.pick("scorch", List.of("minecraft:coarse_dirt", "minecraft:gravel")), true, false);
            }
        }
        int debris = integer(o, "debris", 12);
        int radius = integer(o, "radius", half + 6);
        for (int i = 0; i < debris; i++) {
            double a = out.roll() * 2 * Math.PI;
            double d = radius * Math.sqrt(0.2 + 0.8 * out.roll());
            out.block((int) Math.round(d * Math.cos(a)), 0, (int) Math.round(d * Math.sin(a)), TEMPLATE_BLOCK, true, false);
        }
    }

    private static void skeleton(Out out, JsonObject o) {
        int n = integer(o, "length", 3);
        int step = integer(o, "step", SPINE_STEP);
        double curve = Math.toRadians(number(o, "curve", 0));
        List<String> parts = list(o, "parts", SPINES);
        double heading = -curve * (n - 1) / 2.0;
        double x = -step * (n - 1) / 2.0 * Math.cos(heading);
        double z = -step * (n - 1) / 2.0 * Math.sin(heading);
        for (int i = 0; i <= n; i++) {
            JsonObject t = new JsonObject();
            boolean skull = i == n;
            t.addProperty("id", skull ? string(o, "skull", SKULL) : parts.get(out.random.nextInt(parts.size())));
            int quarter = Math.floorMod((int) Math.round(heading / (Math.PI / 2)) + 1, 4);
            out.template((int) Math.round(x), 0, (int) Math.round(z), t, quarter, true);
            double advance = skull ? 0 : i == n - 1 ? step * 0.75 : step;
            x += advance * Math.cos(heading);
            z += advance * Math.sin(heading);
            heading += curve;
        }
    }

    private static void camp(Out out, JsonObject o) {
        String state = string(o, "state", "abandoned");
        int radius = integer(o, "radius", CAMP_RADIUS);
        boolean burned = state.equals("burned");
        if (flag(o, "fire", true)) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    out.block(dx, 0, dz, out.pick("ring", List.of("minecraft:cobblestone")), false, state.equals("abandoned"));
                }
            }
            if (burned) {
                out.block(0, -1, 0, "minecraft:netherrack", false, false);
                out.block(0, 0, 0, "minecraft:fire", false, false);
            } else {
                out.block(0, 0, 0, "minecraft:campfire[lit=" + state.equals("living") + "]", false, false);
            }
        }
        out.burned = burned;
        out.block(3, 0, 0, out.pick("seats", List.of("minecraft:stripped_spruce_log[axis=z]")), false, false);
        out.block(-3, 0, 0, out.pick("seats", List.of("minecraft:stripped_spruce_log[axis=z]")), false, false);
        for (int i = 0; i < 3; i++) out.block(1 - i, 0, -radius + 1, "minecraft:spruce_log[axis=x]", true, !state.equals("living"));
        out.block(0, 1, -radius + 1, "minecraft:spruce_log[axis=x]", true, !state.equals("living"));
        int tents = integer(o, "tents", 2);
        List<String> ids = list(o, "tent", TENTS);
        for (int i = 0; i < tents; i++) {
            double a = 2 * Math.PI * (i + 0.5) / Math.max(1, tents) + Math.PI / 2;
            JsonObject t = new JsonObject();
            t.addProperty("id", ids.get(out.random.nextInt(ids.size())));
            int quarter = Math.floorMod((int) Math.round(a / (Math.PI / 2)) + 1, 4);
            out.template((int) Math.round((radius - 1) * Math.cos(a)), 0, (int) Math.round((radius - 1) * Math.sin(a)), t, quarter, true);
        }
        out.burned = false;
        if (o.has("stores")) {
            JsonArray stores = o.getAsJsonArray("stores");
            for (int i = 0; i < stores.size(); i++) {
                double a = 2 * Math.PI * (i + 0.5) / Math.max(1, stores.size()) - Math.PI / 4;
                JsonObject s = stores.get(i).getAsJsonObject();
                out.thing("chest", (int) Math.round((radius - 4) * Math.cos(a)), 0, (int) Math.round((radius - 4) * Math.sin(a)), s, true);
            }
        }
        if (burned) {
            for (int i = 0; i < radius * 3; i++) {
                double a = out.roll() * 2 * Math.PI;
                double d = radius * Math.sqrt(out.roll());
                out.block((int) Math.round(d * Math.cos(a)), -1, (int) Math.round(d * Math.sin(a)), out.pick("ash", ASH), true, false);
            }
        }
    }

    private static void hovel(Out out, JsonObject o, int cx, int cz, int w, String state) {
        int h = integer(o, "height", 3);
        String roof = string(o, "roof", "pitched");
        boolean living = state.equals("living");
        out.burned = state.equals("burned");
        double roofKept = out.burned ? 0.3 : 1.0;
        int r = w + 1;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                boolean wall = Math.abs(x) == r || Math.abs(z) == r;
                boolean corner = Math.abs(x) == r && Math.abs(z) == r;
                out.block(cx + x, -1, cz + z, out.pick(wall ? "walls" : "floor", List.of(wall ? "minecraft:cobblestone" : "minecraft:coarse_dirt")), false, false);
                if (!wall) continue;
                for (int y = 0; y < h; y++) {
                    boolean door = x == r && z == 0 && y < 2;
                    boolean window = z == -r && x == 0 && y == 1;
                    if (door) {
                        if (living) out.block(cx + x, y, cz + z, out.pick("door", List.of("minecraft:spruce_door")) + "[facing=west,half="
                                + (y == 0 ? "lower" : "upper") + ",hinge=left,open=false]", false, false);
                        continue;
                    }
                    if (window) {
                        if (living) out.block(cx + x, y, cz + z, "minecraft:glass_pane", false, false);
                        continue;
                    }
                    String b = corner ? out.pick("corners", List.of("minecraft:spruce_log[axis=y]")) : out.pick("walls", List.of("minecraft:cobblestone"));
                    out.block(cx + x, y, cz + z, b, false, !living);
                }
            }
        }
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                int y = switch (roof) {
                    case "flat" -> h;
                    case "dome" -> h + (int) Math.round(Math.sqrt(Math.max(0, r * r - x * x - z * z)) * 0.6);
                    default -> h + (r - Math.abs(z));
                };
                if (out.roll() >= roofKept) continue;
                out.block(cx + x, y, cz + z, out.pick("roof", List.of("minecraft:spruce_planks")), false, !living);
                if (roof.equals("pitched") && Math.abs(x) == r) {
                    for (int g = h; g < y; g++) out.block(cx + x, g, cz + z, out.pick("walls", List.of("minecraft:cobblestone")), false, !living);
                }
            }
        }
        if (living) {
            out.block(cx - w, 0, cz + w, "minecraft:lantern", false, false);
            out.block(cx - w, 0, cz - w, "minecraft:barrel[facing=up]", false, false);
        } else if (!out.burned) {
            out.block(cx - w, h - 1, cz + w, "minecraft:cobweb", false, false);
            out.block(cx + w, h - 1, cz - w, "minecraft:cobweb", false, false);
        }
        out.burned = false;
    }

    private static void farm(Out out, JsonObject o) {
        String crop = string(o, "crop", "wheat");
        String soil = SetPieceKinds.CROPS.get(crop);
        boolean nether = crop.equals("nether_wart") || crop.endsWith("_fungus");
        String irrigation = string(o, "irrigation", crop.equals("nether_wart") ? "lava" : nether || crop.equals("mushroom") ? "none" : "water");
        int plot = integer(o, "plot", 5) | 1;
        int plots = integer(o, "plots", 2);
        int half = plot / 2;
        int x0 = -(plots * (plot + 1) - 1) / 2;
        int x1 = x0 + plots * (plot + 1) - 2;
        for (int i = 0; i < plots; i++) {
            int px = x0 + i * (plot + 1);
            for (int x = px; x < px + plot; x++) {
                for (int z = -half; z <= half; z++) {
                    out.block(x, -2, z, nether ? "minecraft:netherrack" : "minecraft:dirt", false, false);
                    if (z == 0 && !irrigation.equals("none")) {
                        out.block(x, -1, z, "minecraft:" + irrigation, false, false);
                        continue;
                    }
                    out.block(x, -1, z, soil, false, false);
                    out.block(x, 0, z, cropState(crop, x + z), false, false);
                }
            }
            if (i < plots - 1) {
                for (int z = -half; z <= half; z++) out.block(px + plot, -1, z, nether ? "minecraft:soul_soil" : "minecraft:dirt_path", false, false);
            }
        }
        String fence = o.has("fence") && o.get("fence").isJsonPrimitive() && o.get("fence").getAsJsonPrimitive().isBoolean()
                ? (o.get("fence").getAsBoolean() ? null : "none") : string(o, "fence", null);
        if (fence == null) fence = nether ? "minecraft:nether_brick_fence" : "minecraft:oak_fence";
        if (!fence.equals("none")) {
            Set<Long> ring = new HashSet<>();
            for (int x = x0 - 1; x <= x1 + 1; x++) {
                for (int z = -half - 1; z <= half + 1; z++) {
                    boolean edge = x == x0 - 1 || x == x1 + 1 || Math.abs(z) == half + 1;
                    boolean gate = x == x1 + 1 && z == 0;
                    if (edge && !gate) ring.add(key(x, z));
                }
            }
            String base = out.spec.palette().containsKey(fence) ? out.pick(fence, List.of("minecraft:oak_fence")) : fence;
            for (long k : ring) {
                int x = (int) (k >> 32);
                int z = (int) k;
                out.block(x, 0, z, connected(base, ring, x, z), false, true);
            }
            out.block(x0 - 2, 0, 0, "minecraft:composter", false, false);
        }
        if (flag(o, "hovel", false)) hovel(out, o, 0, half + 5, 1, "living");
    }

    static String cropState(String crop, int parity) {
        return switch (crop) {
            case "nether_wart" -> "minecraft:nether_wart[age=3]";
            case "beetroots" -> "minecraft:beetroots[age=3]";
            case "crimson_fungus", "warped_fungus" -> "minecraft:" + crop;
            case "mushroom" -> (parity & 1) == 0 ? "minecraft:red_mushroom" : "minecraft:brown_mushroom";
            default -> "minecraft:" + crop + "[age=7]";
        };
    }

    private static void works(Out out, JsonObject o) {
        int columns = integer(o, "columns", 2);
        int height = integer(o, "height", 10);
        int lines = integer(o, "lines", 1);
        int[] cx = new int[columns];
        for (int i = 0; i < columns; i++) {
            cx[i] = (int) Math.round((i - (columns - 1) / 2.0) * WORKS_STEP);
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    out.block(cx[i] + x, -1, z, out.pick("chimney", List.of("minecraft:bricks")), false, false);
                    if (x == 0 && z == 0) continue;
                    for (int y = 0; y < height; y++) {
                        if (x == 1 && z == 0 && y < 2) continue;
                        out.block(cx[i] + x, y, z, out.pick("chimney", List.of("minecraft:bricks")), false, y > 2);
                    }
                }
            }
            out.block(cx[i], 0, 0, "minecraft:campfire[lit=true,signal_fire=true]", false, false);
        }
        for (int l = 0; l < lines; l++) {
            int y = 4 + 2 * l;
            int from = columns > 0 ? cx[0] + 2 : -4;
            int to = columns > 1 ? cx[columns - 1] - 2 : from + 8;
            int z = columns > 1 ? 0 : 2;
            for (int x = from; x <= to; x++) {
                out.block(x, y, z, out.pick("pipe", List.of("minecraft:cut_copper")), false, true);
                if ((x - from) % 3 == 1) for (int t = 0; t < y; t++) out.block(x, t, z, out.pick("trestle", List.of("minecraft:iron_bars")), false, true);
            }
        }
        int machines = integer(o, "machines", 4);
        for (int m = 0; m < machines; m++) {
            int x = (int) Math.round((m - (machines - 1) / 2.0) * 2);
            out.block(x, 0, 3, out.pick("machines", MACHINES), false, false);
        }
        int slag = integer(o, "slag", 6);
        for (int x = -slag; x <= slag; x++) {
            for (int z = -slag; z <= slag; z++) {
                if (x * x + z * z > slag * slag) continue;
                double r = out.roll();
                if (r < 0.45) out.block(x, -1, z - 4 - slag / 2, out.pick("slag", SLAG), true, false);
                if (r < 0.08) out.block(x, 0, z - 4 - slag / 2, out.pick("slag", SLAG), true, false);
            }
        }
        if (flag(o, "pool", true) && columns > 0) {
            int px = cx[columns - 1] + 4;
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) {
                    boolean rim = Math.abs(x) == 2 || Math.abs(z) == 2;
                    out.block(px + x, -2, z + 4, out.pick("chimney", List.of("minecraft:bricks")), false, false);
                    out.block(px + x, -1, z + 4, rim ? out.pick("chimney", List.of("minecraft:bricks")) : "minecraft:water", false, false);
                }
            }
        }
    }

    private static void cell(Out out, JsonObject o) {
        int count = integer(o, "count", 1);
        String bars = out.block(o, "bars", "minecraft:iron_bars");
        Set<Long> ring = new HashSet<>();
        int[] centres = new int[count];
        for (int i = 0; i < count; i++) {
            centres[i] = (int) Math.round((i - (count - 1) / 2.0) * CELL_STEP);
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) if (Math.abs(x) == 2 || Math.abs(z) == 2) ring.add(key(centres[i] + x, z));
            }
        }
        int minX = centres[0] - 2;
        int maxX = centres[count - 1] + 2;
        for (int x = minX; x <= maxX; x++) {
            for (int z = -2; z <= 2; z++) {
                out.block(x, -1, z, out.pick("floor", List.of("minecraft:stone_bricks")), false, false);
                out.block(x, 4, z, out.pick("floor", List.of("minecraft:stone_bricks")), false, false);
                if (!ring.contains(key(x, z))) continue;
                for (int y = 0; y < 4; y++) out.block(x, y, z, connected(bars, ring, x, z), false, false);
            }
        }
        if (!o.has("captive")) return;
        for (int c : centres) {
            JsonObject m = o.getAsJsonObject("captive").deepCopy();
            m.addProperty("holder", string(o, "holder", "floor"));
            out.thing("mob", c, 0, 0, m, false);
        }
    }

    private static void gate(Out out, JsonObject o) {
        String state = string(o, "state", "broken");
        boolean marker = flag(o, "marker", false);
        int w = marker ? 2 : integer(o, "width", 2);
        int h = marker ? 3 : integer(o, "height", 3);
        String frameKind = string(o, "frame", "nether");
        List<String> blocks = o.has("blocks") ? list(o, "blocks", List.of()) : o.has("frameBlock") ? List.of(o.get("frameBlock").getAsString())
                : frameKind.equals("end") ? List.of("minecraft:end_stone_bricks") : List.of("minecraft:obsidian");
        String main = blocks.getFirst();
        double kept = marker ? 1.0 : o.has("integrity") ? o.get("integrity").getAsDouble() : state.equals("attempt") ? 0.65 : Math.min(out.integrity, 0.55);
        List<int[]> ring = new ArrayList<>();
        for (int x = 0; x < w; x++) {
            ring.add(new int[] {x, 1});
            ring.add(new int[] {x, h + 2});
        }
        for (int y = 2; y <= h + 1; y++) {
            ring.add(new int[] {-1, y});
            ring.add(new int[] {w, y});
        }
        int dropped = 0;
        for (int[] p : ring) {
            boolean keep = marker || out.roll() < kept || (state.equals("attempt") && p[1] <= 1 + h / 2);
            if (!keep) {
                dropped++;
                continue;
            }
            String b = marker ? main : frameKind.equals("nether") && !o.has("frameBlock") && !o.has("blocks") && out.roll() < 0.2
                    ? "minecraft:crying_obsidian" : blocks.get(out.random.nextInt(blocks.size()));
            out.block(p[0], p[1], 0, b, false, false);
        }
        String plinth = state.equals("attempt") ? "minecraft:bedrock" : main;
        for (int x = -1; x <= w; x++) out.block(x, 0, 0, marker && x == 0 ? "minecraft:beacon" : plinth, false, false);
        if (marker) {
            for (int x = 0; x < w; x++) for (int y = 2; y <= h + 1; y++) out.block(x, y, 0, "minecraft:air", false, false);
        }
        for (int i = 0; i < dropped / 2; i++) {
            double a = out.roll() * 2 * Math.PI;
            double d = 2 + 3 * out.roll();
            out.block((int) Math.round(d * Math.cos(a)), 0, (int) Math.round(d * Math.sin(a)) + 2, main, true, false);
        }
        if (state.equals("broken") && frameKind.equals("nether") && !o.has("frameBlock") && !o.has("blocks")) {
            transform(out, List.of("minecraft:netherrack", "minecraft:netherrack", "minecraft:magma_block"), 4, 0.4);
        }
        if (o.has("aura")) transform(out, list(o, "aura", List.of()), 5, 0.35);
        if (state.equals("attempt")) {
            out.block(-1, h + 3, 0, "minecraft:end_rod[facing=up]", false, false);
            out.block(w, h + 3, 0, "minecraft:end_rod[facing=up]", false, false);
            out.block(-2, 0, 1, "minecraft:brewing_stand", false, false);
            out.block(w + 1, 0, -1, "minecraft:brewing_stand", false, false);
        }
        JsonElement study = o.get("study");
        if (study == null || (study.isJsonPrimitive() && !study.getAsBoolean())) return;
        JsonObject s = study.isJsonObject() ? study.getAsJsonObject() : new JsonObject();
        out.block(0, 0, 4, "minecraft:enchanting_table", false, false);
        for (int x = -2; x <= 2; x++) for (int y = 0; y < 2; y++) out.block(x, y, 6, "minecraft:bookshelf", false, true);
        JsonObject lectern = new JsonObject();
        if (s.has("lines")) {
            lectern.add("lines", s.get("lines"));
        } else {
            JsonArray pool = new JsonArray();
            (s.has("pool") ? list(s, "pool", STUDY_LINES) : STUDY_LINES).forEach(pool::add);
            lectern.add("pool", pool);
            lectern.addProperty("pick", s.has("pick") ? s.get("pick").getAsInt() : 2);
        }
        if (s.has("title")) lectern.add("title", s.get("title"));
        lectern.addProperty("facing", "north");
        out.thing("lectern", 2, 0, 4, lectern, false);
        JsonObject chest = new JsonObject();
        chest.addProperty("block", "chest");
        if (s.has("loot")) chest.add("loot", s.get("loot"));
        if (o.has("precursor")) {
            JsonArray a = new JsonArray();
            JsonObject slot = new JsonObject();
            slot.addProperty("slot", 13);
            slot.add("item", o.get("precursor"));
            a.add(slot);
            chest.add("arrangement", a);
        }
        out.thing("chest", -2, 0, 4, chest, false);
    }

    /** The ground round the assembly part-turned to {@code blocks}: the seat's top block in {@code radius}, at {@code share}. */
    private static void transform(Out out, List<String> blocks, int radius, double share) {
        if (blocks.isEmpty()) return;
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if (x * x + z * z > radius * radius || out.roll() >= share) continue;
                out.block(x, -1, z, blocks.get(out.random.nextInt(blocks.size())), true, false);
            }
        }
    }

    private static void remains(Out out, JsonObject o, int cx, int cz) {
        boolean seated = string(o, "pose", "lying").equals("seated");
        String skull = string(o, "skull", "minecraft:skeleton_skull");
        if (!skull.contains("[")) skull += "[rotation=4]";
        if (seated) {
            out.block(cx, 0, cz, "minecraft:bone_block[axis=y]", true, false);
            out.block(cx, 1, cz, skull, true, false);
        } else {
            out.block(cx, 0, cz, "minecraft:bone_block[axis=x]", true, false);
            out.block(cx - 1, 0, cz, skull, true, false);
        }
        String wearer = string(o, "wearer", "stand");
        if (wearer.equals("none")) return;
        JsonObject p = new JsonObject();
        if (o.has("gear")) p.add("equipment", o.get("gear"));
        if (wearer.equals("skeleton")) {
            p.addProperty("entity", "minecraft:skeleton");
            p.addProperty("persistent", true);
            p.addProperty("drops", true);
            out.thing("mob", cx + 1, 0, cz + 1, p, true);
        } else {
            p.addProperty("facing", "west");
            p.addProperty("arms", true);
            out.thing("stand", cx + 1, 0, cz + 1, p, true);
        }
    }

    // ---- block states -----------------------------------------------------------------------

    /** {@code state} burned at {@code roll}: wood to a pick from {@code fallback} (or the spec's burned palette), cloth and thin wood gone. */
    static String burn(String state, double roll, List<String> fallback, Out out) {
        String id = state.contains("[") ? state.substring(0, state.indexOf('[')) : state;
        if (!wooden(id)) return state;
        String path = id.substring(id.indexOf(':') + 1);
        if (path.endsWith("_wool") || path.endsWith("_carpet")) return null;
        boolean thin = !(path.endsWith("_log") || path.endsWith("_wood") || path.endsWith("_stem") || path.endsWith("_hyphae") || path.endsWith("_planks"));
        if (thin && roll < 0.6) return null;
        if (!thin && roll < 0.3) return null;
        String pick = fallback != null ? fallback.get((int) (roll * 1000) % fallback.size()) : out.pick("burned", BURNED);
        String axis = property(state, "axis");
        boolean axial = pick.equals("minecraft:basalt") || pick.equals("minecraft:polished_basalt") || pick.endsWith("_log");
        return axis != null && axial && !pick.contains("[") ? pick + "[axis=" + axis + "]" : pick;
    }

    /** The block {@code id} burned with the default burned palette, for a template's blocks: a new id, null for gone, or {@code id}. */
    public static String burnBlock(String id, double roll) {
        String b = burn(id, roll, BURNED, null);
        return b == null ? Age.AIR : b;
    }

    /** Whether {@code id} burns: logs, planks and their shapes, fences, doors, wool, carpets, barrels, bookshelves, ladders. */
    public static boolean wooden(String id) {
        String path = id.substring(id.indexOf(':') + 1);
        if (path.endsWith("_wool") || path.endsWith("_carpet")) return true;
        if (path.endsWith("_nylium") || path.endsWith("_fungus") || path.endsWith("_roots") || path.endsWith("_wart_block")) return false;
        if (Set.of("barrel", "crafting_table", "bookshelf", "ladder", "scaffolding", "composter", "hay_block").contains(path)) return true;
        if (path.contains("stripped_") || path.endsWith("_log") || path.endsWith("_wood") || path.endsWith("_stem") || path.endsWith("_hyphae")) return true;
        for (String w : Age.WOODS) if (path.startsWith(w + "_")) return true;
        return false;
    }

    static String property(String state, String key) {
        int open = state.indexOf('[');
        if (open < 0) return null;
        for (String kv : state.substring(open + 1, state.length() - 1).split(",")) {
            String[] p = kv.split("=");
            if (p.length == 2 && p[0].trim().equals(key)) return p[1].trim();
        }
        return null;
    }

    static String turn(String facing, int quarter) {
        for (int i = 0; i < 4; i++) {
            if (FACING[i].equals(facing)) return FACING[(i + quarter) & 3];
        }
        return facing;
    }

    /** {@code state} turned {@code quarter} quarter turns clockwise: its facing, axis, rotation and side connections. */
    public static String rotateState(String state, int quarter) {
        int q = quarter & 3;
        int open = state.indexOf('[');
        if (q == 0 || open < 0) return state;
        String[] props = state.substring(open + 1, state.length() - 1).split(",");
        Map<String, String> sides = new java.util.HashMap<>();
        List<String> out = new ArrayList<>();
        for (String kv : props) {
            String[] p = kv.split("=");
            if (p.length != 2) continue;
            String k = p[0].trim();
            String v = p[1].trim();
            switch (k) {
                case "facing" -> out.add("facing=" + turn(v, q));
                case "axis" -> out.add("axis=" + ((q & 1) == 1 && !v.equals("y") ? (v.equals("x") ? "z" : "x") : v));
                case "rotation" -> out.add("rotation=" + ((Integer.parseInt(v) + 4 * q) & 15));
                case "north", "east", "south", "west" -> sides.put(turn(k, q), v);
                default -> out.add(k + "=" + v);
            }
        }
        sides.forEach((k, v) -> out.add(k + "=" + v));
        return state.substring(0, open) + "[" + String.join(",", out) + "]";
    }

    /** {@code block} connected to its neighbours in {@code ring}, as a fence or bars line joins. */
    static String connected(String block, Set<Long> ring, int x, int z) {
        String id = block.contains("[") ? block.substring(0, block.indexOf('[')) : block;
        if (!id.endsWith("_fence") && !id.endsWith("_bars") && !id.endsWith("glass_pane")) return block;
        return id + "[north=" + ring.contains(key(x, z - 1)) + ",south=" + ring.contains(key(x, z + 1)) + ",east=" + ring.contains(key(x + 1, z))
                + ",west=" + ring.contains(key(x - 1, z)) + "]";
    }

    static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
