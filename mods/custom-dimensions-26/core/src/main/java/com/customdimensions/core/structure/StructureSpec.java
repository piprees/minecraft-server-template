package com.customdimensions.core.structure;

import com.customdimensions.core.dimension.NoiseProfile;
import com.customdimensions.core.dimension.StructureAliases;
import com.customdimensions.core.dimension.StructureGroupRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One dimension's structure control, resolved from its definition's
 * {@code structures} block: what the runtime applies to the level's structure
 * state. Game-free; the compiler writes it with {@link #toJson()} and the
 * runtime reads it back with {@link #fromJson(JsonObject)}.
 *
 * <p>Ids are structure ids or {@code #tags} for {@code force}, {@code wants},
 * {@code shuns}, {@code replace} and {@code terrainAdaptation} (which also
 * takes a group name); structure SET ids for {@code list}, {@code exclude},
 * {@code include}, {@code rarity} and {@code spacing}.
 */
public record StructureSpec(
        String level,
        String density,
        boolean noise,
        String suppressedReason,
        int radiusChunks,
        int spawnChunkX,
        int spawnChunkZ,
        Map<String, Group> groups,
        String mode,
        List<String> list,
        List<String> exclude,
        List<String> include,
        Map<String, String> rarity,
        Map<String, Spacing> spacing,
        List<Force> force,
        List<Want> wants,
        List<String> shuns,
        Map<String, String> replace,
        Map<String, String> terrainAdaptation) {

    public static final Set<String> DENSITIES = Set.of("dense", "normal", "sparse", "none");
    public static final Set<String> MODES = Set.of("allow", "reject", "none");
    public static final Set<String> RARITIES = Set.of("common", "uncommon", "rare", "endgame");
    /** Vanilla's terrain adjustments; the runtime applies each as it is. */
    public static final Set<String> VANILLA_ADAPTATIONS = Set.of("none", "beard_thin", "beard_box", "bury", "encapsulate");
    /** The 1.21.1 kernels, applied as the vanilla adjustment of the same intent. */
    public static final Map<String, String> KERNEL_ADAPTATIONS = Map.of(
            "pedestal", "beard_box", "platform_skirt", "beard_box", "ground_blend", "beard_thin");
    /** The 1.21.1 kernels with no vanilla counterpart. */
    public static final Set<String> UNSUPPORTED_ADAPTATIONS = Set.of("moat", "drain");
    public static final Set<String> FIELDS = Set.of("density", "wants", "shuns", "spacing", "mode", "list", "force",
            "clearSpawnRadius", "terrainAdaptation", "noise", "radial", "rarity", "exclude", "include", "replace");

    /** One noise group: its profile, radial curve (null is even), exclusion in chunks and cleared spawn disc. */
    public record Group(String profile, double[] radial, int exclusion, int clearSpawnChunks) {}

    /** A set's own grid, kept outside the noise groups. */
    public record Spacing(int spacing, int separation) {}

    /** A structure placed at a block position (its chunk), or in a chunk within {@code radius} blocks of it. */
    public record Force(String structure, int x, int z, Integer y, int radius, boolean exclusive) {
        public int chunkX() {
            return Math.floorDiv(x, 16);
        }

        public int chunkZ() {
            return Math.floorDiv(z, 16);
        }
    }

    /** A structure guaranteed between {@code min} and {@code max} blocks of spawn. */
    public record Want(String name, String structure, int min, int max) {}

    /** The parsed spec and what the compile report says about it. */
    public record Result(StructureSpec spec, List<String> applied, List<String> warnings) {}

    /** What the resolver needs from the rest of the definition. */
    public record Context(String slug, String level, String worldType, double mobMultiplier,
                          int borderRadiusBlocks, int spawnX, int spawnZ) {}

    public boolean isEmpty() {
        return "normal".equals(density) && !noise && mode == null && exclude.isEmpty() && include.isEmpty()
                && rarity.isEmpty() && spacing.isEmpty() && force.isEmpty() && wants.isEmpty() && shuns.isEmpty()
                && replace.isEmpty() && terrainAdaptation.isEmpty();
    }

    // ---- reading a definition ------------------------------------------------------------

    /**
     * Resolves a definition's {@code structures} block (null for none). A
     * malformed value stops the compile with the slug in the message; an
     * unknown group or an id the alias table cannot resolve is a warning.
     */
    public static Result resolve(Context ctx, JsonObject block) {
        JsonObject b = block == null ? new JsonObject() : block;
        List<String> applied = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (String key : b.keySet()) {
            if (!FIELDS.contains(key)) {
                throw bad(ctx, key, "is not a structures field");
            }
        }
        String density = string(ctx, b, "density", "normal");
        if (!DENSITIES.contains(density)) {
            throw bad(ctx, "density", "must be one of " + DENSITIES);
        }
        String mode = string(ctx, b, "mode", null);
        if (mode != null && !MODES.contains(mode)) {
            throw bad(ctx, "mode", "must be one of " + MODES);
        }
        List<String> list = strings(ctx, b, "list");
        List<String> exclude = strings(ctx, b, "exclude");
        List<String> include = strings(ctx, b, "include");
        Map<String, String> rarity = stringMap(ctx, b, "rarity");
        rarity.forEach((set, tier) -> {
            if (!RARITIES.contains(tier)) {
                throw bad(ctx, "rarity." + set, "must be one of " + RARITIES);
            }
        });
        Map<String, Spacing> spacing = spacing(ctx, b);
        int clearSpawn = b.has("clearSpawnRadius") ? integer(ctx, b.get("clearSpawnRadius"), "clearSpawnRadius") : 0;
        if (clearSpawn < 0) {
            throw bad(ctx, "clearSpawnRadius", "must be 0 or more");
        }

        int radiusChunks = Math.max(1, ctx.borderRadiusBlocks() / 16);
        int spawnChunkX = Math.floorDiv(ctx.spawnX(), 16);
        int spawnChunkZ = Math.floorDiv(ctx.spawnZ(), 16);
        GroupPlan plan = groups(ctx, b, density, mode, clearSpawn, warnings);

        List<Force> force = force(ctx, b, warnings);
        List<Want> wants = wants(ctx, b, warnings);
        List<String> shuns = new ArrayList<>();
        if (b.has("shuns")) {
            JsonObject s = object(ctx, b.get("shuns"), "shuns");
            for (String name : s.keySet()) {
                String id = resolveName(name);
                if (id == null) {
                    warnings.add("shuns: `" + name + "` is not a structure id, tag or known name");
                    continue;
                }
                shuns.add(id);
            }
        }
        Map<String, String> replace = stringMap(ctx, b, "replace");
        Map<String, String> adaptation = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : stringMap(ctx, b, "terrainAdaptation").entrySet()) {
            String kind = e.getValue();
            String target = e.getKey();
            if (!target.contains(":") && !StructureGroupRegistry.knownGroups().contains(target)) {
                warnings.add("terrainAdaptation: `" + target + "` is neither a structure id nor a group");
                continue;
            }
            if (VANILLA_ADAPTATIONS.contains(kind)) {
                adaptation.put(target, kind);
            } else if (KERNEL_ADAPTATIONS.containsKey(kind)) {
                adaptation.put(target, KERNEL_ADAPTATIONS.get(kind));
                applied.add("terrainAdaptation: `" + target + "` " + kind + " as vanilla " + KERNEL_ADAPTATIONS.get(kind));
            } else if (UNSUPPORTED_ADAPTATIONS.contains(kind)) {
                warnings.add("terrainAdaptation: `" + target + "` " + kind + " has no 26.3 counterpart and is not applied");
            } else {
                throw bad(ctx, "terrainAdaptation." + target, "must be one of " + VANILLA_ADAPTATIONS
                        + ", " + KERNEL_ADAPTATIONS.keySet() + " or " + UNSUPPORTED_ADAPTATIONS);
            }
        }
        for (Force f : force) {
            if (shuns.contains(f.structure())) {
                warnings.add("force: `" + f.structure() + "` is also shunned; the forced start still places");
            }
        }

        StructureSpec spec = new StructureSpec(ctx.level(), density, plan.noise, plan.reason, radiusChunks,
                spawnChunkX, spawnChunkZ, plan.groups, mode, list, exclude, include, rarity, spacing, force, wants,
                shuns, replace, adaptation);
        describe(spec, applied);
        return new Result(spec, applied, warnings);
    }

    /** The world type names of {@code structure_type_defaults.json} for a v2 generator. */
    public static String worldType(String generatorType, String preset) {
        if (generatorType == null) {
            return null;
        }
        return switch (generatorType) {
            case "noise" -> switch (preset == null ? "overworld" : preset) {
                case "caves" -> "cave";
                case "floating_islands" -> "sky_islands";
                default -> preset == null ? "overworld" : preset;
            };
            case "flat" -> "superflat";
            default -> generatorType;
        };
    }

    /** A wants or shuns name to a structure id or {@code #tag}: an id or tag as written, else the alias table's. */
    public static String resolveName(String name) {
        if (name.startsWith("#") || name.contains(":")) {
            return name;
        }
        return StructureAliases.resolve(name);
    }

    private record GroupPlan(boolean noise, String reason, Map<String, Group> groups) {}

    private static GroupPlan groups(Context ctx, JsonObject b, String density, String mode, int clearSpawn,
                                    List<String> warnings) {
        JsonElement noise = b.get("noise");
        if ("none".equals(density)) {
            return new GroupPlan(false, "density none", Map.of());
        }
        if ("none".equals(mode)) {
            return new GroupPlan(false, "mode none", Map.of());
        }
        if (noise != null && noise.isJsonPrimitive() && noise.getAsJsonPrimitive().isBoolean() && !noise.getAsBoolean()) {
            return new GroupPlan(false, "noise false", Map.of());
        }
        if (noise != null && !(noise.isJsonObject() || noise.isJsonPrimitive())) {
            throw bad(ctx, "noise", "must be a profile name, a map of group to profile, or false");
        }
        StructureGroupRegistry.TypeDefaults defaults = StructureGroupRegistry.defaults();
        List<String> enabled = new ArrayList<>(StructureGroupRegistry.groupsForType(ctx.worldType()));
        Map<String, String> perGroup = new LinkedHashMap<>();
        if (noise != null && noise.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : noise.getAsJsonObject().entrySet()) {
                if (!e.getValue().isJsonPrimitive()) {
                    throw bad(ctx, "noise." + e.getKey(), "must be a profile name");
                }
                if (!StructureGroupRegistry.knownGroups().contains(e.getKey())) {
                    warnings.add("noise: `" + e.getKey() + "` is not a group " + StructureGroupRegistry.knownGroups());
                    continue;
                }
                perGroup.put(e.getKey(), e.getValue().getAsString());
                if (!enabled.contains(e.getKey())) {
                    enabled.add(e.getKey());
                }
            }
        }
        if (enabled.isEmpty()) {
            return new GroupPlan(false, "type " + ctx.worldType() + " enables no groups", Map.of());
        }
        String global = noise != null && noise.isJsonPrimitive() && noise.getAsJsonPrimitive().isString()
                ? noise.getAsString() : "normal".equals(density) ? null : density;
        StructureGroupRegistry.TypeEntry type = defaults.types().get(ctx.worldType());
        Map<String, List<Double>> radialOverrides = new LinkedHashMap<>();
        if (b.has("radial")) {
            for (Map.Entry<String, JsonElement> e : object(ctx, b.get("radial"), "radial").entrySet()) {
                if (!e.getValue().isJsonArray() || e.getValue().getAsJsonArray().size() != 10) {
                    throw bad(ctx, "radial." + e.getKey(), "must be 10 numbers from spawn to border");
                }
                List<Double> curve = new ArrayList<>();
                for (JsonElement v : e.getValue().getAsJsonArray()) {
                    double d = v.getAsDouble();
                    if (Double.isNaN(d) || d < 0 || d > 3) {
                        throw bad(ctx, "radial." + e.getKey(), "values must lie in 0.0-3.0");
                    }
                    curve.add(d);
                }
                radialOverrides.put(e.getKey(), curve);
            }
        }
        Map<String, Group> out = new LinkedHashMap<>();
        for (String group : enabled) {
            StructureGroupRegistry.GroupDefault gd = StructureGroupRegistry.groupDefault(group);
            if (gd == null) {
                continue;
            }
            String profileName = gd.profile();
            if (type != null && type.profiles().containsKey(group)) {
                profileName = type.profiles().get(group);
            }
            if (global != null) {
                profileName = global;
            }
            if (ctx.mobMultiplier() <= defaults.peacefulMaxMobMultiplier() && defaults.peacefulProfiles().containsKey(group)) {
                profileName = defaults.peacefulProfiles().get(group);
            }
            if (perGroup.containsKey(group)) {
                profileName = perGroup.get(group);
            }
            String name = profileName;
            NoiseProfile profile = NoiseProfile.fromString(profileName, unknown -> {
                throw bad(ctx, "noise", "`" + name + "` is not a profile (natural, dense, packed, sparse, cluster, none)");
            });
            if (profile == null) {
                continue;
            }
            double[] radial;
            if (radialOverrides.containsKey(group)) {
                radial = radialOverrides.get(group).stream().mapToDouble(Double::doubleValue).toArray();
            } else {
                String curve = gd.radial();
                if (type != null && type.radial().containsKey(group)) {
                    curve = type.radial().get(group);
                }
                if (ctx.mobMultiplier() >= defaults.hostileMinMobMultiplier() && defaults.hostileRadial().containsKey(group)) {
                    curve = defaults.hostileRadial().get(group);
                }
                radial = StructureGroupRegistry.curve(curve);
            }
            int exclusion = Math.max(1, (int) Math.round(gd.exclusion() * profile.exclusionMultiplier()));
            int clear = clearSpawn > 0 && Set.of("dungeons", "endgame").contains(group) ? (clearSpawn + 15) / 16 : 0;
            out.put(group, new Group(profile.id(), radial, exclusion, clear));
        }
        for (String group : radialOverrides.keySet()) {
            if (!out.containsKey(group)) {
                warnings.add("radial: group `" + group + "` is not placed in this dimension");
            }
        }
        if (out.isEmpty()) {
            return new GroupPlan(false, "every group resolved to none", Map.of());
        }
        return new GroupPlan(true, null, out);
    }

    private static List<Force> force(Context ctx, JsonObject b, List<String> warnings) {
        List<Force> out = new ArrayList<>();
        if (!b.has("force")) {
            return out;
        }
        if (!b.get("force").isJsonArray()) {
            throw bad(ctx, "force", "must be an array");
        }
        int i = 0;
        for (JsonElement e : b.getAsJsonArray("force")) {
            String field = "force[" + i++ + "]";
            JsonObject f = object(ctx, e, field);
            for (String key : f.keySet()) {
                if (!Set.of("structure", "x", "z", "y", "radius", "exclusive").contains(key)) {
                    throw bad(ctx, field + "." + key, "is not a force field (structure, x, z, y, radius, exclusive)");
                }
            }
            if (!f.has("structure") || !f.has("x") || !f.has("z")) {
                throw bad(ctx, field, "needs structure, x and z");
            }
            String structure = f.get("structure").getAsString();
            if (!structure.contains(":") || structure.startsWith("#")) {
                throw bad(ctx, field + ".structure", "must be a structure id");
            }
            int radius = f.has("radius") ? integer(ctx, f.get("radius"), field + ".radius") : 0;
            if (radius < 0) {
                throw bad(ctx, field + ".radius", "must be 0 or more");
            }
            Integer y = f.has("y") && !f.get("y").isJsonNull() ? integer(ctx, f.get("y"), field + ".y") : null;
            if (y != null) {
                warnings.add(field + ": `y` is not applied; the structure finds its own height");
            }
            boolean exclusive = !f.has("exclusive") || f.get("exclusive").getAsBoolean();
            out.add(new Force(structure, integer(ctx, f.get("x"), field + ".x"), integer(ctx, f.get("z"), field + ".z"),
                    y, radius, exclusive));
        }
        return out;
    }

    private static List<Want> wants(Context ctx, JsonObject b, List<String> warnings) {
        List<Want> out = new ArrayList<>();
        if (!b.has("wants")) {
            return out;
        }
        for (Map.Entry<String, JsonElement> e : object(ctx, b.get("wants"), "wants").entrySet()) {
            String field = "wants." + e.getKey();
            JsonObject w = object(ctx, e.getValue(), field);
            int min = w.has("min") ? integer(ctx, w.get("min"), field + ".min") : 0;
            int max = w.has("max") ? integer(ctx, w.get("max"), field + ".max") : ctx.borderRadiusBlocks();
            if (min < 0 || max <= min) {
                throw bad(ctx, field, "needs 0 <= min < max");
            }
            String id = resolveName(e.getKey());
            if (id == null) {
                warnings.add("wants: `" + e.getKey() + "` is not a structure id, tag or known name");
                continue;
            }
            out.add(new Want(e.getKey(), id, min, max));
        }
        return out;
    }

    private static Map<String, Spacing> spacing(Context ctx, JsonObject b) {
        Map<String, Spacing> out = new LinkedHashMap<>();
        if (!b.has("spacing")) {
            return out;
        }
        for (Map.Entry<String, JsonElement> e : object(ctx, b.get("spacing"), "spacing").entrySet()) {
            String field = "spacing." + e.getKey();
            JsonObject s = object(ctx, e.getValue(), field);
            int sp = s.has("spacing") ? integer(ctx, s.get("spacing"), field + ".spacing") : -1;
            int sep = s.has("separation") ? integer(ctx, s.get("separation"), field + ".separation") : -1;
            if (sp < 1 || sp > 4096 || sep < 0 || sep >= sp) {
                throw bad(ctx, field, "needs 1 <= spacing <= 4096 and 0 <= separation < spacing");
            }
            out.put(e.getKey(), new Spacing(sp, sep));
        }
        return out;
    }

    private static void describe(StructureSpec s, List<String> applied) {
        applied.add(0, "density " + s.density() + (s.noise()
                ? "; noise placement: " + groupsLine(s.groups())
                : "; vanilla grids" + (s.suppressedReason() != null ? " (" + s.suppressedReason() + ")" : "")));
        if (s.mode() != null) {
            applied.add("mode " + s.mode() + (s.list().isEmpty() ? "" : " " + s.list()));
        }
        if (!s.exclude().isEmpty()) {
            applied.add("exclude " + s.exclude());
        }
        if (!s.include().isEmpty()) {
            applied.add("include " + s.include());
        }
        if (!s.rarity().isEmpty()) {
            applied.add("rarity " + s.rarity());
        }
        s.spacing().forEach((set, sp) -> applied.add("spacing `" + set + "` " + sp.spacing() + "/" + sp.separation()));
        for (Force f : s.force()) {
            applied.add("force `" + f.structure() + "` at " + f.x() + "," + f.z()
                    + (f.radius() > 0 ? " within " + f.radius() : "") + (f.exclusive() ? ", exclusive" : ""));
        }
        for (Want w : s.wants()) {
            applied.add("want `" + w.structure() + "` within " + w.min() + "-" + w.max() + " blocks of spawn");
        }
        if (!s.shuns().isEmpty()) {
            applied.add("shuns " + s.shuns());
        }
        s.replace().forEach((from, to) -> applied.add("replace `" + from + "` with `" + to + "`"));
        s.terrainAdaptation().forEach((t, k) -> applied.add("terrainAdaptation `" + t + "` " + k));
    }

    private static String groupsLine(Map<String, Group> groups) {
        List<String> parts = new ArrayList<>();
        groups.forEach((name, g) -> parts.add(name + "=" + g.profile() + "/" + g.exclusion()
                + (g.clearSpawnChunks() > 0 ? "/clear" + g.clearSpawnChunks() : "")));
        return String.join(" ", parts);
    }

    // ---- JSON round trip ----------------------------------------------------------------

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("level", level);
        o.addProperty("density", density);
        o.addProperty("noise", noise);
        if (suppressedReason != null) {
            o.addProperty("suppressedReason", suppressedReason);
        }
        o.addProperty("radiusChunks", radiusChunks);
        JsonArray spawn = new JsonArray();
        spawn.add(spawnChunkX);
        spawn.add(spawnChunkZ);
        o.add("spawnChunk", spawn);
        JsonObject g = new JsonObject();
        groups.forEach((name, group) -> {
            JsonObject j = new JsonObject();
            j.addProperty("profile", group.profile());
            if (group.radial() != null) {
                JsonArray r = new JsonArray();
                for (double d : group.radial()) {
                    r.add(d);
                }
                j.add("radial", r);
            }
            j.addProperty("exclusion", group.exclusion());
            j.addProperty("clearSpawnChunks", group.clearSpawnChunks());
            g.add(name, j);
        });
        o.add("groups", g);
        o.add("mode", mode == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(mode));
        o.add("list", array(list));
        o.add("exclude", array(exclude));
        o.add("include", array(include));
        o.add("rarity", map(rarity));
        JsonObject sp = new JsonObject();
        spacing.forEach((set, s) -> {
            JsonObject j = new JsonObject();
            j.addProperty("spacing", s.spacing());
            j.addProperty("separation", s.separation());
            sp.add(set, j);
        });
        o.add("spacing", sp);
        JsonArray fa = new JsonArray();
        for (Force f : force) {
            JsonObject j = new JsonObject();
            j.addProperty("structure", f.structure());
            j.addProperty("x", f.x());
            j.addProperty("z", f.z());
            if (f.y() != null) {
                j.addProperty("y", f.y());
            }
            j.addProperty("radius", f.radius());
            j.addProperty("exclusive", f.exclusive());
            fa.add(j);
        }
        o.add("force", fa);
        JsonArray wa = new JsonArray();
        for (Want w : wants) {
            JsonObject j = new JsonObject();
            j.addProperty("name", w.name());
            j.addProperty("structure", w.structure());
            j.addProperty("min", w.min());
            j.addProperty("max", w.max());
            wa.add(j);
        }
        o.add("wants", wa);
        o.add("shuns", array(shuns));
        o.add("replace", map(replace));
        o.add("terrainAdaptation", map(terrainAdaptation));
        return o;
    }

    public static StructureSpec fromJson(JsonObject o) {
        Map<String, Group> groups = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("groups").entrySet()) {
            JsonObject j = e.getValue().getAsJsonObject();
            double[] radial = null;
            if (j.has("radial")) {
                JsonArray r = j.getAsJsonArray("radial");
                radial = new double[r.size()];
                for (int i = 0; i < r.size(); i++) {
                    radial[i] = r.get(i).getAsDouble();
                }
            }
            groups.put(e.getKey(), new Group(j.get("profile").getAsString(), radial, j.get("exclusion").getAsInt(),
                    j.get("clearSpawnChunks").getAsInt()));
        }
        Map<String, Spacing> spacing = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("spacing").entrySet()) {
            JsonObject j = e.getValue().getAsJsonObject();
            spacing.put(e.getKey(), new Spacing(j.get("spacing").getAsInt(), j.get("separation").getAsInt()));
        }
        List<Force> force = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("force")) {
            JsonObject j = e.getAsJsonObject();
            force.add(new Force(j.get("structure").getAsString(), j.get("x").getAsInt(), j.get("z").getAsInt(),
                    j.has("y") ? j.get("y").getAsInt() : null, j.get("radius").getAsInt(), j.get("exclusive").getAsBoolean()));
        }
        List<Want> wants = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("wants")) {
            JsonObject j = e.getAsJsonObject();
            wants.add(new Want(j.get("name").getAsString(), j.get("structure").getAsString(), j.get("min").getAsInt(),
                    j.get("max").getAsInt()));
        }
        JsonArray spawn = o.getAsJsonArray("spawnChunk");
        return new StructureSpec(o.get("level").getAsString(), o.get("density").getAsString(), o.get("noise").getAsBoolean(),
                o.has("suppressedReason") ? o.get("suppressedReason").getAsString() : null, o.get("radiusChunks").getAsInt(),
                spawn.get(0).getAsInt(), spawn.get(1).getAsInt(), groups,
                o.get("mode").isJsonNull() ? null : o.get("mode").getAsString(), list(o, "list"), list(o, "exclude"),
                list(o, "include"), stringMap(o.getAsJsonObject("rarity")), spacing, force, wants, list(o, "shuns"),
                stringMap(o.getAsJsonObject("replace")), stringMap(o.getAsJsonObject("terrainAdaptation")));
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static IllegalArgumentException bad(Context ctx, String field, String why) {
        return new IllegalArgumentException(ctx.slug() + ": structures." + field + " " + why);
    }

    private static String string(Context ctx, JsonObject o, String key, String fallback) {
        if (!o.has(key) || o.get(key).isJsonNull()) {
            return fallback;
        }
        if (!o.get(key).isJsonPrimitive()) {
            throw bad(ctx, key, "must be a string");
        }
        return o.get(key).getAsString().trim().toLowerCase(Locale.ROOT);
    }

    private static int integer(Context ctx, JsonElement e, String field) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw bad(ctx, field, "must be a whole number");
        }
        double d = e.getAsDouble();
        if (d != Math.rint(d)) {
            throw bad(ctx, field, "must be a whole number");
        }
        return (int) d;
    }

    private static JsonObject object(Context ctx, JsonElement e, String field) {
        if (e == null || !e.isJsonObject()) {
            throw bad(ctx, field, "must be an object");
        }
        return e.getAsJsonObject();
    }

    private static List<String> strings(Context ctx, JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (!o.has(key)) {
            return out;
        }
        if (!o.get(key).isJsonArray()) {
            throw bad(ctx, key, "must be an array of ids");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (JsonElement e : o.getAsJsonArray(key)) {
            seen.add(e.getAsString());
        }
        out.addAll(seen);
        return out;
    }

    private static Map<String, String> stringMap(Context ctx, JsonObject o, String key) {
        if (!o.has(key)) {
            return new LinkedHashMap<>();
        }
        JsonObject m = object(ctx, o.get(key), key);
        for (Map.Entry<String, JsonElement> e : m.entrySet()) {
            if (!e.getValue().isJsonPrimitive()) {
                throw bad(ctx, key + "." + e.getKey(), "must be a string");
            }
        }
        return stringMap(m);
    }

    private static Map<String, String> stringMap(JsonObject m) {
        Map<String, String> out = new LinkedHashMap<>();
        m.entrySet().forEach(e -> out.put(e.getKey(), e.getValue().getAsString().trim().toLowerCase(Locale.ROOT)));
        return out;
    }

    private static List<String> list(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        o.getAsJsonArray(key).forEach(e -> out.add(e.getAsString()));
        return out;
    }

    private static JsonArray array(List<String> values) {
        JsonArray a = new JsonArray();
        values.forEach(a::add);
        return a;
    }

    private static JsonObject map(Map<String, String> m) {
        JsonObject o = new JsonObject();
        m.forEach(o::addProperty);
        return o;
    }
}
