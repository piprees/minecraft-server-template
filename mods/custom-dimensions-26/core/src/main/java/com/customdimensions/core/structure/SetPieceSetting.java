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
 * How a set piece meets its setting beyond its seat and site: the seat's own
 * reach, rise, depth and bed ({@code roof}, {@code wall}, {@code water}); its
 * {@link Approach}es (lore/placement.md G8); its place in the lived-in layer
 * ({@link Lived}, G6); and the dimension's age by distance ({@link AgeBands}).
 * Game-free.
 */
public record SetPieceSetting(int reach, int rise, int depth, boolean bed, List<Approach> approaches, Lived lived, AgeBands bands) {

    public static final Set<String> FIELDS = Set.of("reach", "rise", "depth", "bed", "approach", "lived");
    public static final int DEFAULT_REACH = 48;
    public static final int DEFAULT_RISE = 6;
    public static final SetPieceSetting NONE = new SetPieceSetting(DEFAULT_REACH, DEFAULT_RISE, 1, false, List.of(), null, null);

    public SetPieceSetting {
        approaches = approaches == null ? List.of() : List.copyOf(approaches);
    }

    public SetPieceSetting withBands(AgeBands b) {
        return new SetPieceSetting(reach, rise, depth, bed, approaches, lived, b);
    }

    /** The setting of a set piece's resolved fields {@code o}, checked against its seat and size. */
    public static SetPieceSetting read(String where, JsonObject o, String seat, int size) {
        int reach = o.has("reach") ? o.get("reach").getAsInt() : DEFAULT_REACH;
        int rise = o.has("rise") ? o.get("rise").getAsInt() : DEFAULT_RISE;
        int depth = o.has("depth") ? o.get("depth").getAsInt() : 1;
        boolean bed = o.has("bed") && o.get("bed").getAsBoolean();
        if (reach < 1 || reach > 128) throw bad(where, "reach must be 1 to 128");
        if (rise < 2) throw bad(where, "rise must be 2 or more");
        if (depth < 1) throw bad(where, "depth must be 1 or more");
        if ((o.has("reach")) && !Set.of("roof", "wall").contains(seat)) throw bad(where, "reach applies to the roof and wall seats only");
        if (o.has("rise") && !"wall".equals(seat)) throw bad(where, "rise applies to the wall seat only");
        if ((o.has("depth") || o.has("bed")) && !"water".equals(seat)) throw bad(where, "depth and bed apply to the water seat only");
        List<Approach> approaches = o.has("approach") ? Approach.readAll(where + ".approach", o.get("approach")) : List.of();
        Lived lived = null;
        if (o.has("lived")) {
            if (size > Lived.MAX_SIZE) throw bad(where, "a lived-in piece is tiny: size at most " + Lived.MAX_SIZE);
            lived = Lived.read(where + ".lived", o.get("lived"));
        }
        return new SetPieceSetting(reach, rise, depth, bed, approaches, lived, null);
    }

    /** Writes this setting's fields into a spec's JSON. */
    public void write(JsonObject o) {
        if (reach != DEFAULT_REACH) o.addProperty("reach", reach);
        if (rise != DEFAULT_RISE) o.addProperty("rise", rise);
        if (depth != 1) o.addProperty("depth", depth);
        if (bed) o.addProperty("bed", true);
        if (!approaches.isEmpty()) {
            JsonArray a = new JsonArray();
            approaches.forEach(p -> a.add(p.toJson()));
            o.add("approach", a);
        }
        if (lived != null) o.add("lived", lived.toJson());
    }

    static IllegalArgumentException bad(String where, String why) {
        return new IllegalArgumentException(where + ": " + why);
    }

    // ---- age by distance --------------------------------------------------------------------

    /**
     * The dimension's age by distance from spawn (lore/placement.md § The
     * overworld as a history map): each age from its inner radius outward,
     * the edges jittered by up to {@code jitter} blocks in 64-block cells so
     * the map never shows a ring. {@code structures.age} as an object.
     */
    public record AgeBands(List<Band> bands, int jitter, int centreX, int centreZ) {

        public record Band(int from, Age age) {}

        public static final int DEFAULT_JITTER = 96;

        /** The age at block ({@code x}, {@code z}) of a start whose seed is {@code seed}. */
        public Age at(int x, int z, long seed) {
            double j = jitter * (2 * SetPiecePlan.roll(seed, x >> 6, 0, z >> 6, 70) - 1);
            double d = Math.hypot(x - centreX, z - centreZ) + j;
            Age out = bands.getFirst().age();
            for (Band b : bands) if (d >= b.from()) out = b.age();
            return out;
        }

        /** {@code structures.age} as bands, or null when it is a single age or absent. */
        public static AgeBands read(String where, JsonElement e, int centreX, int centreZ) {
            if (e == null || e.isJsonPrimitive()) {
                if (e != null && !Age.NAMES.contains(e.getAsString())) throw bad(where, "must be one of " + Age.NAMES + " or an object of age to inner radius");
                return null;
            }
            if (!e.isJsonObject()) throw bad(where, "must be an age or an object of age to inner radius");
            List<Band> bands = new ArrayList<>();
            int jitter = DEFAULT_JITTER;
            for (Map.Entry<String, JsonElement> b : e.getAsJsonObject().entrySet()) {
                if (b.getKey().equals("jitter")) {
                    jitter = b.getValue().getAsInt();
                    if (jitter < 0) throw bad(where, "jitter must be 0 or more");
                    continue;
                }
                if (!Age.NAMES.contains(b.getKey())) throw bad(where, "`" + b.getKey() + "` is neither an age " + Age.NAMES + " nor jitter");
                int from = b.getValue().getAsInt();
                if (from < 0) throw bad(where, b.getKey() + " starts at 0 blocks or more");
                bands.add(new Band(from, Age.parse(b.getKey())));
            }
            if (bands.isEmpty()) throw bad(where, "names no age");
            bands.sort(java.util.Comparator.comparingInt(Band::from));
            return new AgeBands(List.copyOf(bands), jitter, centreX, centreZ);
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            bands.forEach(b -> o.addProperty(b.age().id(), b.from()));
            o.addProperty("jitter", jitter);
            o.addProperty("centreX", centreX);
            o.addProperty("centreZ", centreZ);
            return o;
        }

        public static AgeBands fromJson(JsonObject o) {
            JsonObject b = o.deepCopy();
            int x = b.remove("centreX").getAsInt();
            int z = b.remove("centreZ").getAsInt();
            return read("bands", b, x, z);
        }
    }

    // ---- the lived-in layer -----------------------------------------------------------------

    /**
     * A tiny piece of the lived-in layer (lore/placement.md G6): its density
     * per km², scaled by distance from spawn ({@code radial}, ten points spawn
     * to border) and by biome, sited by any of its relations: on a path line
     * between the level's sites and spawn, at a view edge, by water, in a
     * hollow. Sites are drawn one per 128-block cell at most, so density tops
     * out near 61 per km².
     */
    public record Lived(double density, double[] radial, Map<String, Double> biomes, double biomeDefault, List<String> siting,
                        int pathWidth) {

        public static final Set<String> FIELDS = Set.of("density", "radial", "biomes", "siting", "pathWidth");
        public static final Set<String> SITINGS = Set.of("path", "view", "water", "hollow");
        public static final int MAX_SIZE = 8;
        public static final int CELL = 128;
        public static final double CELL_KM2 = CELL * CELL / 1_000_000.0;
        static final int TRIES = 4;
        public static final int WATER_REACH = 24;
        public static final int VIEW_REACH = 16;

        public static Lived read(String where, JsonElement e) {
            if (!e.isJsonObject()) throw bad(where, "must be an object");
            JsonObject o = e.getAsJsonObject();
            for (String k : o.keySet()) if (!FIELDS.contains(k)) throw bad(where, k + " is not a lived field " + FIELDS);
            if (!o.has("density")) throw bad(where, "density (per km²) is required");
            double density = o.get("density").getAsDouble();
            if (density <= 0 || density > 1 / CELL_KM2) throw bad(where, "density must be above 0 and at most " + (int) (1 / CELL_KM2) + " per km²");
            double[] radial = null;
            if (o.has("radial")) {
                JsonArray a = o.getAsJsonArray("radial");
                if (a.size() != 10) throw bad(where, "radial is 10 numbers from spawn to border");
                radial = new double[10];
                for (int i = 0; i < 10; i++) {
                    radial[i] = a.get(i).getAsDouble();
                    if (radial[i] < 0 || radial[i] > 3) throw bad(where, "radial values lie in 0-3");
                }
            }
            Map<String, Double> biomes = new LinkedHashMap<>();
            double fallback = 1;
            if (o.has("biomes")) {
                for (Map.Entry<String, JsonElement> b : o.getAsJsonObject("biomes").entrySet()) {
                    double f = b.getValue().getAsDouble();
                    if (f < 0 || f > 3) throw bad(where, "biomes." + b.getKey() + " is a factor in 0-3");
                    if (b.getKey().equals("default")) fallback = f;
                    else if (!b.getKey().contains(":")) throw bad(where, "biomes." + b.getKey() + " must be a biome id, a #tag or default");
                    else biomes.put(b.getKey(), f);
                }
            }
            List<String> siting = new ArrayList<>();
            if (o.has("siting")) {
                JsonElement s = o.get("siting");
                if (s.isJsonArray()) s.getAsJsonArray().forEach(v -> siting.add(v.getAsString()));
                else siting.add(s.getAsString());
                for (String k : siting) if (!SITINGS.contains(k)) throw bad(where, "siting `" + k + "` must be one of " + SITINGS);
            }
            int pathWidth = o.has("pathWidth") ? o.get("pathWidth").getAsInt() : 24;
            if (pathWidth < 1) throw bad(where, "pathWidth must be 1 or more");
            return new Lived(density, radial, Map.copyOf(biomes), fallback, List.copyOf(siting), pathWidth);
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("density", density);
            if (radial != null) {
                JsonArray a = new JsonArray();
                for (double v : radial) a.add(v);
                o.add("radial", a);
            }
            if (!biomes.isEmpty() || biomeDefault != 1) {
                JsonObject b = new JsonObject();
                biomes.forEach(b::addProperty);
                if (biomeDefault != 1) b.addProperty("default", biomeDefault);
                o.add("biomes", b);
            }
            if (!siting.isEmpty()) {
                JsonArray a = new JsonArray();
                siting.forEach(a::add);
                o.add("siting", a);
            }
            if (pathWidth != 24) o.addProperty("pathWidth", pathWidth);
            return o;
        }

        /** The radial factor at {@code f} of the way from spawn to border. */
        public double radialAt(double f) {
            if (radial == null) return 1;
            double t = Math.max(0, Math.min(1, f)) * 9;
            int i = (int) Math.floor(t);
            if (i >= 9) return radial[9];
            return radial[i] + (radial[i + 1] - radial[i]) * (t - i);
        }

        public double biomeAt(SiteFilter.Terrain t, int x, int y, int z) {
            for (Map.Entry<String, Double> e : biomes.entrySet()) {
                if (t.inBiomes(x, y, z, List.of(e.getKey()))) return e.getValue();
            }
            return biomeDefault;
        }

        /** Whether block ({@code x}, {@code z}) meets any siting relation; true when none is named. */
        public boolean sited(SiteFilter.Terrain t, int x, int z, List<int[]> segments) {
            if (siting.isEmpty()) return true;
            int h = t.height(x, z);
            for (String s : siting) {
                switch (s) {
                    case "water" -> {
                        int d = SiteFilter.nearestWater(t, x, z, new SiteFilter.Distance("water", null, WATER_REACH, 0));
                        if (d >= 0 && d <= WATER_REACH) return true;
                    }
                    case "hollow" -> {
                        if (SiteFilter.sheltered(t, x, z, h, "hollow")) return true;
                    }
                    case "view" -> {
                        int lower = 0;
                        for (int v : SiteFilter.ring(t, x, z, VIEW_REACH)) if (v <= h - 3) lower++;
                        if (lower >= 4 && SiteFilter.nearestCliff(t, x, z, new SiteFilter.Distance("cliff", null, 12, 6)) >= 0) return true;
                    }
                    case "path" -> {
                        for (int[] seg : segments) if (segmentDistance(x, z, seg) <= pathWidth) return true;
                    }
                    default -> {
                    }
                }
            }
            return false;
        }

        /** The distance from ({@code x}, {@code z}) to the segment {x1, z1, x2, z2}. */
        public static double segmentDistance(int x, int z, int[] s) {
            double dx = s[2] - s[0];
            double dz = s[3] - s[1];
            double len = dx * dx + dz * dz;
            double t = len == 0 ? 0 : Math.max(0, Math.min(1, ((x - s[0]) * dx + (z - s[1]) * dz) / len));
            return Math.hypot(x - (s[0] + t * dx), z - (s[1] + t * dz));
        }

        /**
         * The path lines among {@code sites} (block x, z) and spawn: spawn to
         * each site, and each site to its nearest other.
         */
        public static List<int[]> segments(int spawnX, int spawnZ, List<int[]> sites) {
            List<int[]> out = new ArrayList<>();
            for (int[] s : sites) {
                out.add(new int[] {spawnX, spawnZ, s[0], s[1]});
                int[] near = null;
                double best = Double.MAX_VALUE;
                for (int[] o : sites) {
                    double d = Math.hypot(o[0] - s[0], o[1] - s[1]);
                    if (o != s && d > 0 && d < best) {
                        best = d;
                        near = o;
                    }
                }
                if (near != null) out.add(new int[] {s[0], s[1], near[0], near[1]});
            }
            return out;
        }

        /**
         * The chunks this layer places whole within {@code radius} blocks of spawn:
         * at most one per 128-block cell, the cell kept at its density there
         * (per km², by radial and biome), the first of four seeded chunks in it
         * that meets the siting and {@code accepts}.
         */
        public List<int[]> sites(long seed, int spawnX, int spawnZ, int radius, SiteFilter.Terrain t, List<int[]> segments,
                                 java.util.function.BiPredicate<Integer, Integer> accepts) {
            List<int[]> out = new ArrayList<>();
            int cells = radius / CELL + 1;
            int baseX = Math.floorDiv(spawnX, CELL);
            int baseZ = Math.floorDiv(spawnZ, CELL);
            for (int cx = baseX - cells; cx <= baseX + cells; cx++) {
                for (int cz = baseZ - cells; cz <= baseZ + cells; cz++) {
                    double keep = SetPiecePlan.roll(seed, cx, 0, cz, 60);
                    for (int k = 0; k < TRIES; k++) {
                        int chunkX = Math.floorDiv(cx * CELL, 16) + (int) (SetPiecePlan.roll(seed, cx, k, cz, 61) * (CELL / 16));
                        int chunkZ = Math.floorDiv(cz * CELL, 16) + (int) (SetPiecePlan.roll(seed, cx, k, cz, 62) * (CELL / 16));
                        int x = chunkX * 16 + 8;
                        int z = chunkZ * 16 + 8;
                        double d = Math.hypot(x - spawnX, z - spawnZ);
                        if (d > radius - 16) continue;
                        double lambda = density * CELL_KM2 * radialAt(d / radius);
                        if (keep >= lambda) continue;
                        if (!biomes.isEmpty() || biomeDefault != 1) {
                            lambda *= biomeAt(t, x, t.height(x, z), z);
                            if (keep >= lambda) continue;
                        }
                        if (!sited(t, x, z, segments) || !accepts.test(chunkX, chunkZ)) continue;
                        out.add(new int[] {chunkX, chunkZ});
                        break;
                    }
                }
            }
            return out;
        }
    }

    // ---- approaches -------------------------------------------------------------------------

    /**
     * How a place is reached (lore/placement.md G8): a worn {@code path}
     * towards the nearest site (steps where the ground climbs two or more,
     * a bridge where it drops more than {@code gap} and comes back within 32
     * blocks), {@code steps} or a {@code bridge} alone, a {@code ladder} or
     * {@code chain} down the first face it meets, or a {@code debris} line.
     * Carried by a set piece's {@code approach} or {@code structures.approaches}.
     */
    public record Approach(String kind, String to, int[] offset, int length, int width, int gap, int count, int[] direction,
                           JsonObject blocks) {

        public static final Set<String> KINDS = Set.of("path", "steps", "bridge", "ladder", "chain", "debris");
        public static final Set<String> FIELDS = Set.of("kind", "to", "length", "width", "gap", "count", "direction", "block", "stairs",
                "deck", "post");
        public static final int MAX_LENGTH = 96;
        public static final int SPAN = 32;
        public static final int FACE = 4;

        public static List<Approach> readAll(String where, JsonElement e) {
            List<Approach> out = new ArrayList<>();
            if (e.isJsonArray()) {
                for (int i = 0; i < e.getAsJsonArray().size(); i++) out.add(read(where + "[" + i + "]", e.getAsJsonArray().get(i)));
            } else {
                out.add(read(where, e));
            }
            return out;
        }

        public static Approach read(String where, JsonElement e) {
            if (!e.isJsonObject()) throw bad(where, "must be an object");
            JsonObject o = e.getAsJsonObject();
            for (String k : o.keySet()) if (!FIELDS.contains(k)) throw bad(where, k + " is not an approach field " + FIELDS);
            String kind = o.has("kind") ? o.get("kind").getAsString() : "path";
            if (!KINDS.contains(kind)) throw bad(where, "kind must be one of " + KINDS);
            String to = "site";
            int[] offset = null;
            if (o.has("to")) {
                if (o.get("to").isJsonArray()) {
                    JsonArray a = o.getAsJsonArray("to");
                    if (a.size() != 2) throw bad(where, "to is site, spawn or [dx, dz]");
                    offset = new int[] {a.get(0).getAsInt(), a.get(1).getAsInt()};
                    to = "offset";
                } else {
                    to = o.get("to").getAsString();
                    if (!Set.of("site", "spawn").contains(to)) throw bad(where, "to is site, spawn or [dx, dz]");
                }
            }
            int length = o.has("length") ? o.get("length").getAsInt() : 48;
            if (length < 1 || length > MAX_LENGTH) throw bad(where, "length must be 1 to " + MAX_LENGTH);
            int width = o.has("width") ? o.get("width").getAsInt() : 1;
            if (width < 1 || width > 3) throw bad(where, "width must be 1 to 3");
            int gap = o.has("gap") ? o.get("gap").getAsInt() : 4;
            if (gap < 2) throw bad(where, "gap must be 2 or more");
            int count = o.has("count") ? o.get("count").getAsInt() : 12;
            if (count < 1) throw bad(where, "count must be 1 or more");
            int[] direction = null;
            if (o.has("direction")) {
                JsonArray a = o.getAsJsonArray("direction");
                if (a.size() != 2 || a.get(0).getAsInt() == 0 && a.get(1).getAsInt() == 0) throw bad(where, "direction is [dx, dz], not [0, 0]");
                direction = new int[] {a.get(0).getAsInt(), a.get(1).getAsInt()};
            }
            JsonObject blocks = new JsonObject();
            for (String k : List.of("block", "stairs", "deck", "post")) if (o.has(k)) blocks.addProperty(k, o.get(k).getAsString());
            return new Approach(kind, to, offset, length, width, gap, count, direction, blocks);
        }

        public JsonObject toJson() {
            JsonObject o = blocks.deepCopy();
            o.addProperty("kind", kind);
            if (offset != null) {
                JsonArray a = new JsonArray();
                a.add(offset[0]);
                a.add(offset[1]);
                o.add("to", a);
            } else {
                o.addProperty("to", to);
            }
            o.addProperty("length", length);
            o.addProperty("width", width);
            o.addProperty("gap", gap);
            o.addProperty("count", count);
            if (direction != null) {
                JsonArray a = new JsonArray();
                a.add(direction[0]);
                a.add(direction[1]);
                o.add("direction", a);
            }
            return o;
        }

        /**
         * The operations ({x, y, z, op, facing}, op one of
         * {@link SetPieceShapes#PATH} … {@link SetPieceShapes#DEBRIS}) of this
         * approach out of the box {minX, minZ, maxX, maxZ} towards
         * ({@code tx}, {@code tz}), read from {@code base} heights.
         */
        public List<int[]> ops(SetPieceShapes.Heights base, int[] box, int tx, int tz, long seed) {
            double cx = (box[0] + box[2]) / 2.0;
            double cz = (box[1] + box[3]) / 2.0;
            double ux = tx - cx;
            double uz = tz - cz;
            if (direction != null && kind.equals("debris")) {
                ux = direction[0];
                uz = direction[1];
            }
            double len = Math.hypot(ux, uz);
            if (len < 1e-6) {
                ux = 1;
                uz = 0;
            } else {
                ux /= len;
                uz /= len;
            }
            double t0 = 0;
            while (inside(box, cx + ux * t0, cz + uz * t0)) t0 += 1;
            int fx = Math.abs(ux) >= Math.abs(uz) ? (int) Math.signum(ux) : 0;
            int fz = fx == 0 ? (int) Math.signum(uz) : 0;
            int facing = facingIndex(fx, fz);
            int px = -fz;
            int pz = fx;
            List<int[]> out = new ArrayList<>();
            List<int[]> line = new ArrayList<>();
            for (int i = 0; i < length; i++) {
                int x = (int) Math.round(cx + ux * (t0 + i));
                int z = (int) Math.round(cz + uz * (t0 + i));
                if (!line.isEmpty() && line.getLast()[0] == x && line.getLast()[1] == z) continue;
                line.add(new int[] {x, z});
            }
            if (line.isEmpty()) return out;
            int lo = -(width - 1) / 2;
            int hi = width / 2;
            switch (kind) {
                case "debris" -> {
                    for (int k = 0; k < count; k++) {
                        double t = (k + 0.5) / count * length;
                        double j = (SetPiecePlan.roll(seed, k, 0, 0, 80) - 0.5) * 2 * (1 + 3 * t / length);
                        out.add(new int[] {(int) Math.round(cx + ux * (t0 + t) + px * j), 0, (int) Math.round(cz + uz * (t0 + t) + pz * j),
                                SetPieceShapes.DEBRIS, facing});
                    }
                }
                case "ladder", "chain" -> {
                    int prev = base.at(line.getFirst()[0], line.getFirst()[1]);
                    for (int i = 1; i < line.size(); i++) {
                        int h = base.at(line.get(i)[0], line.get(i)[1]);
                        if (prev == SetPieceShapes.NO_GROUND || h == SetPieceShapes.NO_GROUND) break;
                        if (h <= prev - FACE) {
                            for (int y = h; y < prev; y++) {
                                out.add(new int[] {line.get(i)[0], y, line.get(i)[1], kind.equals("ladder") ? SetPieceShapes.LADDER : SetPieceShapes.CHAIN, facing});
                            }
                            break;
                        }
                        prev = h;
                    }
                }
                default -> {
                    int prev = base.at(line.getFirst()[0], line.getFirst()[1]);
                    if (prev == SetPieceShapes.NO_GROUND) return out;
                    boolean paths = !kind.equals("bridge");
                    if (paths) lateral(out, line.getFirst(), 0, SetPieceShapes.PATH, facing, px, pz, lo, hi);
                    for (int i = 1; i < line.size(); i++) {
                        int[] p = line.get(i);
                        int h = base.at(p[0], p[1]);
                        if (h == SetPieceShapes.NO_GROUND) break;
                        if (h < prev - gap && !kind.equals("steps")) {
                            int j = -1;
                            for (int k = i + 1; k < Math.min(line.size(), i + SPAN); k++) {
                                int hk = base.at(line.get(k)[0], line.get(k)[1]);
                                if (hk != SetPieceShapes.NO_GROUND && hk >= prev - 1) {
                                    j = k;
                                    break;
                                }
                            }
                            if (j < 0) break;
                            for (int k = i; k < j; k++) {
                                lateral(out, line.get(k), prev - 1, SetPieceShapes.DECK, facing, px, pz, lo - 1, hi + 1);
                                out.add(new int[] {line.get(k)[0] + px * (lo - 1), prev, line.get(k)[1] + pz * (lo - 1), SetPieceShapes.POST, facing});
                                out.add(new int[] {line.get(k)[0] + px * (hi + 1), prev, line.get(k)[1] + pz * (hi + 1), SetPieceShapes.POST, facing});
                            }
                            i = j;
                            prev = base.at(line.get(j)[0], line.get(j)[1]);
                            if (paths) lateral(out, line.get(j), 0, SetPieceShapes.PATH, facing, px, pz, lo, hi);
                            continue;
                        }
                        if (kind.equals("bridge")) {
                            prev = h;
                            continue;
                        }
                        if (Math.abs(h - prev) >= 2) {
                            int[] at = h > prev ? p : line.get(i - 1);
                            int f = h > prev ? facing : (facing + 2) & 3;
                            lateral(out, at, Math.max(h, prev) - 1, SetPieceShapes.STAIR, f, px, pz, lo, hi);
                        } else {
                            lateral(out, p, 0, SetPieceShapes.PATH, facing, px, pz, lo, hi);
                        }
                        prev = h;
                    }
                }
            }
            return out;
        }

        private static void lateral(List<int[]> out, int[] p, int y, int op, int facing, int px, int pz, int lo, int hi) {
            for (int o = lo; o <= hi; o++) out.add(new int[] {p[0] + px * o, y, p[1] + pz * o, op, facing});
        }

        private static boolean inside(int[] box, double x, double z) {
            return x >= box[0] && x <= box[2] && z >= box[1] && z <= box[3];
        }

        static int facingIndex(int dx, int dz) {
            return dz < 0 ? 0 : dx > 0 ? 1 : dz > 0 ? 2 : 3;
        }
    }
}
