package com.customdimensions.compiler.datapack;

import com.customdimensions.core.definition.BiomeEntry;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code biomeLayout} {@code "regions"}: a biome list laid out as large regions
 * on slow climate axes instead of bands.
 *
 * <ul>
 *   <li>A listed biome with a {@code climate} is <em>bound</em>: it keeps that
 *       box, which wins over every region (an ocean on continentalness, a cave
 *       on depth, a peak on erosion).</li>
 *   <li>Every other listed biome is <em>regional</em>: it takes a cell of the
 *       region axes in rank space, where −1 is the lowest value the seed's
 *       climate reaches inside the world border and 1 the highest
 *       ({@code customdimensions:zoned}'s {@code regions} maps the sampled
 *       climate there). A cell's share of rank space is the biome's
 *       {@code weights} entry over their sum (1 when absent), so its share of
 *       the border is that weight over the sum for any seed and any {@code terrain.scales}.</li>
 *   <li>With two axes the regional biomes fill columns along the first axis
 *       in list order and rows along the second, the direction alternating by
 *       column, so consecutive biomes in the list share a border.</li>
 * </ul>
 */
public final class RegionLayout {
    public static final String MODE = "regions";
    public static final List<String> FIELDS = List.of("mode", "axes", "weights", "roles", "sea", "over", "places");
    /** Axes a region may be cut on; depth varies down a column, so it stays with bound biomes. */
    public static final List<String> REGION_AXES = List.of("temperature", "humidity", "continentalness", "erosion", "weirdness");
    public static final List<String> DEFAULT_AXES = List.of("temperature", "humidity");
    /** The radius regions are measured over when a definition gives no border. */
    public static final int DEFAULT_RADIUS = 1024;

    /**
     * The multi-noise entries, the runtime's {@code regions} object for {@code customdimensions:zoned}, and the
     * places whose field becomes the router's first region axis (null without {@code places}).
     */
    public record Plan(JsonArray entries, JsonObject regions, PlaceLayout.Places places) {}

    private RegionLayout() {
    }

    /**
     * The radius players see, which regions and places are laid over and the survey samples: the player border, else
     * the generation border, else {@link #DEFAULT_RADIUS}. The generation border limits only pre-generation.
     */
    public static int radius(com.customdimensions.core.definition.Borders borders) {
        if (borders != null && borders.player() != null) return borders.player();
        if (borders != null && borders.generation() != null) return borders.generation();
        return DEFAULT_RADIUS;
    }

    /** Throws when {@code layout} is malformed; {@code slug} names the definition. */
    static void validate(String slug, JsonObject layout) {
        for (String key : layout.keySet()) {
            if (!FIELDS.contains(key)) throw error(slug, "biomeLayout." + key + " is not a field; fields: " + String.join(", ", FIELDS));
        }
        JsonElement mode = layout.get("mode");
        if (mode == null || !mode.isJsonPrimitive() || !MODE.equals(mode.getAsString())) {
            throw error(slug, "biomeLayout.mode must be \"" + MODE + "\"");
        }
        axes(slug, layout);
        weights(slug, layout);
    }

    static List<String> axes(String slug, JsonObject layout) {
        JsonElement e = layout.get("axes");
        if (e == null) return DEFAULT_AXES;
        if (!e.isJsonArray() || e.getAsJsonArray().isEmpty() || e.getAsJsonArray().size() > 2) {
            throw error(slug, "biomeLayout.axes must list one or two of " + String.join(", ", REGION_AXES));
        }
        List<String> axes = new ArrayList<>();
        for (JsonElement a : e.getAsJsonArray()) {
            String axis = a.isJsonPrimitive() ? a.getAsString() : "";
            if (!REGION_AXES.contains(axis) || axes.contains(axis)) {
                throw error(slug, "biomeLayout.axes must list one or two different axes of " + String.join(", ", REGION_AXES));
            }
            axes.add(axis);
        }
        return axes;
    }

    static Map<String, Double> weights(String slug, JsonObject layout) {
        Map<String, Double> out = new LinkedHashMap<>();
        JsonElement e = layout.get("weights");
        if (e == null) return out;
        if (!e.isJsonObject()) throw error(slug, "biomeLayout.weights must be an object of biome id to weight");
        for (Map.Entry<String, JsonElement> kv : e.getAsJsonObject().entrySet()) {
            JsonElement v = kv.getValue();
            if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber() || !(v.getAsDouble() > 0) || Double.isInfinite(v.getAsDouble())) {
                throw error(slug, "biomeLayout.weights." + kv.getKey() + " must be a number above 0");
            }
            out.put(kv.getKey(), v.getAsDouble());
        }
        return out;
    }

    /**
     * Where a terrain's sea meets its land on router continentalness, and the bands its shore and river biomes
     * take: a river band is {@link #RIVER_WEIRDNESS} either side of weirdness 0 and {@code riverLow} to
     * {@code riverHigh} on continentalness. {@code water}: the sea is water, so it may take a default ocean.
     */
    public record Coast(double line, double shoreLow, double shoreHigh, double riverLow, double riverHigh, boolean water) {
        static final double RIVER_WEIRDNESS = 0.05;

        /** Vanilla's router: its coast band holds the beaches, rivers run inland along weirdness 0. */
        static final Coast VANILLA = new Coast(BiomeRoles.COAST_LOW, BiomeRoles.COAST_LOW, BiomeRoles.COAST_HIGH,
                BiomeRoles.COAST_HIGH, BiomeLayout.HIGH, true);

        /**
         * A library terrain whose sea level sits at {@code shore[0]} and whose shore runs from {@code shore[1]} to
         * {@code shore[2]}; it has no river valleys, so a river takes its mouth, where weirdness 0 crosses the coast.
         */
        static Coast terrain(double[] shore) {
            return new Coast(shore[0], shore[1], shore[2], r(shore[0] - 0.08), r(shore[0] + 0.02), true);
        }

        Coast dry() {
            return new Coast(line, shoreLow, shoreHigh, riverLow, riverHigh, false);
        }

        private static double r(double v) {
            return Math.round(v * 10000) / 10000.0;
        }
    }

    /** {@code biomeLayout.over}: rank regions over every cell, or over land alone. */
    public static final List<String> OVER = List.of("all", "land");
    /** Router depth below an island's top at which its underside starts: 8 blocks at 128 a unit. */
    static final double UNDERSIDE_DEPTH = 0.0625;
    /** The End's island density from which vanilla's End lays its midlands: where its islands hold land. */
    static final double END_LAND = -0.0625;

    /**
     * What the biomes of a dimension over a void stand on: the router {@code axis} that reads land, and the
     * value from which it is land; whether the terrain shapes basins and has island undersides.
     */
    record Ground(String axis, double min, boolean basins, boolean undersides) {}

    /**
     * The ground of {@code def}: a terrain over a void reads land on its place cue
     * ({@link com.customdimensions.compiler.terrain.TerrainFamily.Shape#place}), the End's own islands on its
     * erosion, which is their density; null for a dimension on the ground.
     */
    static Ground ground(String slug, com.customdimensions.core.definition.Definition def) {
        var spec = com.customdimensions.compiler.terrain.TerrainSpec.parse(slug, def.terrain());
        if (spec != null && spec.airborne()) {
            return new Ground("continentalness", com.customdimensions.compiler.terrain.TerrainFamily.PLACE_HELD,
                    spec.family().shapesBasins(), true);
        }
        boolean end = "end".equals(def.generator().preset());
        if (end && (spec == null || spec.family() == com.customdimensions.compiler.terrain.TerrainFamily.END)) {
            return new Ground("erosion", END_LAND, false, false);
        }
        return null;
    }

    /** Whether {@code layout} ranks its regions over land alone ({@code over: land}). */
    static boolean overLand(String slug, JsonObject layout) {
        JsonElement e = layout.get("over");
        if (e == null) return false;
        if (!e.isJsonPrimitive() || !OVER.contains(e.getAsString())) throw error(slug, "biomeLayout.over must be one of " + OVER);
        return "land".equals(e.getAsString());
    }

    /**
     * What {@code def}'s biome layout asks of its terrain's router: the place cue where regions are ranked over
     * land or a biome takes the basins, island depth where one takes the undersides.
     */
    static com.customdimensions.compiler.terrain.TerrainLibrary.Biomes biomes(String slug, com.customdimensions.core.definition.Definition def,
                                                                              BiomeRoles roles) {
        JsonObject layout = def.biomeLayout();
        Ground ground = layout == null ? null : ground(slug, def);
        if (ground == null || !"continentalness".equals(ground.axis())) return com.customdimensions.compiler.terrain.TerrainLibrary.Biomes.NONE;
        boolean land = overLand(slug, layout);
        Map<String, String> overrides = roles(slug, layout, def.biomes());
        boolean basin = false, underside = false;
        for (BiomeEntry b : def.biomes()) {
            if (b.climate() != null) continue;
            String r = role(b.id(), overrides, roles, land, ground);
            basin |= BiomeRoles.BASIN.equals(r);
            underside |= BiomeRoles.UNDERSIDE.equals(r);
        }
        return new com.customdimensions.compiler.terrain.TerrainLibrary.Biomes(land || basin, underside);
    }

    /** {@code id}'s role over a void: its own, but an ocean takes the basins of a terrain that shapes them when regions are over land. */
    private static String role(String id, Map<String, String> overrides, BiomeRoles roles, boolean overLand, Ground ground) {
        String r = overrides.containsKey(id) || roles == null ? overrides.getOrDefault(id, BiomeRoles.LAND) : roles.role(id, overrides);
        if (overLand && ground != null && ground.basins() && BiomeRoles.OCEAN.equals(r) && !overrides.containsKey(id)) return BiomeRoles.BASIN;
        return r;
    }

    /** Depth a cave area starts at: vanilla's cave biomes' lowest. */
    static final double CAVE_DEPTH = 0.2;
    /** Role groups in carving order: the first wins where areas overlap. */
    static final List<String> GROUPS = List.of(BiomeRoles.RIVER, BiomeRoles.SHORE, BiomeRoles.CAVE, BiomeRoles.LAND, BiomeRoles.OCEAN);

    /**
     * The coast of {@code def}'s terrain, or null when it has no sea or the region axes include continentalness
     * or weirdness, which the role areas are cut on: a library terrain's {@link
     * com.customdimensions.compiler.terrain.TerrainFamily#coastline()}, else vanilla's for the overworld presets.
     */
    static Coast coast(String slug, com.customdimensions.core.definition.Definition def, List<String> axes) {
        if (axes.contains("continentalness") || axes.contains("weirdness")) return null;
        var spec = com.customdimensions.compiler.terrain.TerrainSpec.parse(slug, def.terrain());
        Coast coast;
        if (spec != null) {
            double[] shore = spec.family().shore(spec);
            coast = shore == null ? null : Coast.terrain(shore);
        } else {
            coast = List.of("overworld", "large_biomes", "amplified").contains(def.generator().preset()) ? Coast.VANILLA : null;
        }
        var l = def.liquids();
        boolean water = l == null || ((l.defaultFluid() == null || "minecraft:water".equals(l.defaultFluid())) && !"dry".equals(l.aquifers()));
        if (l != null && l.seaLevel() != null && seaUnderLand(spec, l.seaLevel())) water = false;
        return coast == null || water ? coast : coast.dry();
    }

    /** The open-ocean floor of the overworld presets' own terrain, as a library land terrain's {@code seaFloor} defaults to. */
    static final double VANILLA_SEA_FLOOR = 36;
    /** Blocks the overworld presets' deepest ocean basins lie below {@link #VANILLA_SEA_FLOOR}, as a library terrain's do. */
    static final double VANILLA_BASIN_DEPTH = 20;

    /**
     * Whether a sea at {@code seaLevel} lies under the land: below the deepest ocean basin, where the terrain's
     * sea-relative heights build from the sea floor instead ({@code Ctx.shore()}), so its ocean area is dry land.
     */
    static boolean seaUnderLand(com.customdimensions.compiler.terrain.TerrainSpec spec, int seaLevel) {
        return spec != null ? spec.seaUnderLand(seaLevel) : seaLevel < VANILLA_SEA_FLOOR - VANILLA_BASIN_DEPTH;
    }

    /** As {@link #plan(String, List, JsonObject, int, BiomeLibrary.Known, CompileReport.Entry, Coast, BiomeRoles)} with no coast. */
    static Plan plan(String slug, List<BiomeEntry> biomes, JsonObject layout, int radius, BiomeLibrary.Known known,
                     CompileReport.Entry entry) {
        return plan(slug, biomes, layout, radius, known, entry, null, null);
    }

    /**
     * The entries for {@code biomes} under {@code layout}: bound boxes first, then the regional cells, carved
     * disjoint. {@code radius} is the border the runtime measures the climate over. With a {@code coast}, each
     * regional biome is laid out in its role's area (see {@link #GROUPS}), weights normalised per area.
     */
    static Plan plan(String slug, List<BiomeEntry> biomes, JsonObject layout, int radius, BiomeLibrary.Known known,
                     CompileReport.Entry entry, Coast coast, BiomeRoles roles) {
        return plan(slug, biomes, layout, radius, known, entry, coast, roles, null);
    }

    /**
     * As {@link #plan(String, List, JsonObject, int, BiomeLibrary.Known, CompileReport.Entry, Coast, BiomeRoles)} over
     * {@code ground}, a dimension over a void (null: on the ground). With {@code over: land} the runtime ranks the
     * region axes over the cells that hold land; a biome with the role {@code underside} takes the islands below
     * their tops and one with {@code basin} the open water of the basins, each ranked over its own ground.
     */
    static Plan plan(String slug, List<BiomeEntry> biomes, JsonObject layout, int radius, BiomeLibrary.Known known,
                     CompileReport.Entry entry, Coast coast, BiomeRoles roles, Ground ground) {
        validate(slug, layout);
        List<String> axes = axes(slug, layout);
        Map<String, Double> weights = weights(slug, layout);
        Map<String, String> overrides = roles(slug, layout, biomes);
        boolean overLand = overLand(slug, layout);
        if (overLand && ground == null) {
            throw error(slug, "biomeLayout.over land ranks regions over the islands of the end preset or of a terrain over a void; "
                    + "on the ground, role areas already rank each biome over its own ground");
        }
        for (Map.Entry<String, String> o : overrides.entrySet()) {
            if (BiomeRoles.BASIN.equals(o.getValue()) && (ground == null || !ground.basins())) {
                throw error(slug, "biomeLayout.roles." + o.getKey() + " is basin, which is the open water of basin_islands' lake masses");
            }
            if (BiomeRoles.UNDERSIDE.equals(o.getValue()) && (ground == null || !ground.undersides())) {
                throw error(slug, "biomeLayout.roles." + o.getKey() + " is underside, which is the underside of a terrain's islands over a void");
            }
        }
        boolean landTakesSea = false;
        if (coast != null && !coast.water() && biomes.stream().allMatch(b -> b.climate() != null
                || BiomeRoles.LAND.equals(group(roles.role(b.id(), overrides))))) {
            coast = null;
            landTakesSea = true;
        }
        JsonArray bound = new JsonArray();
        List<String> regional = new ArrayList<>();
        List<String> boundIds = new ArrayList<>();
        for (BiomeEntry b : biomes) {
            if (!known.contains(b.id())) {
                entry.skippedBiome(b.id(), DatapackCompiler.UNKNOWN_BIOME);
                continue;
            }
            if (b.climate() != null) {
                bound.add(BiomeLayout.entry(b.id(), BiomeLayout.climate(b.id(), b.climate())));
                if (!boundIds.contains(b.id())) boundIds.add(b.id());
            } else if (!regional.contains(b.id())) {
                regional.add(b.id());
            }
        }
        for (String id : weights.keySet()) {
            if (!regional.contains(id)) {
                throw error(slug, "biomeLayout.weights." + id + " names no listed biome without a climate");
            }
        }
        JsonElement seaField = layout.get("sea");
        if (seaField != null && (coast == null || !coast.water() || !seaField.isJsonPrimitive() || !known.contains(seaField.getAsString()))) {
            throw error(slug, coast == null || !coast.water() ? "biomeLayout.sea needs a terrain with a water sea"
                    : "biomeLayout.sea must name a biome the game has, not " + seaField);
        }

        JsonArray kept = new JsonArray();
        bound.forEach(kept::add);
        JsonObject regions = new JsonObject();
        JsonArray axesJson = new JsonArray();
        axes.forEach(axesJson::add);
        regions.add("axes", axesJson);
        String note;
        PlaceLayout.Places places = null;
        List<String> under = new ArrayList<>(), basin = new ArrayList<>(), held = new ArrayList<>();
        if (ground != null) {
            for (String id : regional) {
                String r = role(id, overrides, roles, overLand, ground);
                (BiomeRoles.UNDERSIDE.equals(r) ? under : BiomeRoles.BASIN.equals(r) ? basin : held).add(id);
            }
        }
        if (overLand) {
            JsonObject land = new JsonObject();
            land.addProperty("axis", ground.axis());
            land.addProperty("min", ground.min());
            regions.add("land", land);
        }
        if (!under.isEmpty() || !basin.isEmpty()) {
            double[] surface = under.isEmpty() ? null : new double[]{BiomeLayout.LOW, UNDERSIDE_DEPTH};
            JsonArray areas = new JsonArray();
            StringBuilder parts = new StringBuilder();
            places = places(slug, layout, axes, held, regional, weights, biomes, radius);
            Object[][] groups = {
                    {BiomeRoles.UNDERSIDE, under, null, new double[]{UNDERSIDE_DEPTH, BiomeLayout.HIGH}},
                    {BiomeRoles.BASIN, basin, new double[]{ground.min(), com.customdimensions.compiler.terrain.TerrainFamily.PLACE_SHORE}, surface},
                    {BiomeRoles.LAND, held, null, surface}};
            for (Object[] g : groups) {
                @SuppressWarnings("unchecked") List<String> ids = (List<String>) g[1];
                if (ids.isEmpty()) continue;
                double[] cont = (double[]) g[2], depth = (double[]) g[3];
                Cells cells = cells(places, ids, weights, axes, area(cont, null, depth));
                cells.entries().forEach(kept::add);
                JsonObject a = new JsonObject();
                a.addProperty("role", (String) g[0]);
                if (cont != null) a.add("continentalness", BiomeLayout.range(cont[0], cont[1]));
                if (depth != null) a.add("depth", BiomeLayout.range(depth[0], depth[1]));
                if (!cells.cuts().isEmpty()) a.add("columns", cuts(cells.cuts()));
                areas.add(a);
                parts.append(parts.isEmpty() ? "" : "; ").append(g[0]).append(' ').append(cells.columns());
            }
            regions.add("areas", areas);
            note = "Biome regions on " + String.join(" × ", axes) + " over a " + radius + "-block radius, by the islands' ground: " + parts
                    + (overLand ? "; ranked over land" : "") + (overrides.isEmpty() ? "" : "; roles given: " + overrides);
        } else if (coast == null) {
            places = places(slug, layout, axes, regional, regional, weights, biomes, radius);
            Cells cells = cells(places, regional, weights, axes, area(null, null, null));
            cells.entries().forEach(kept::add);
            if (!cells.cuts().isEmpty()) regions.add("columns", cuts(cells.cuts()));
            note = "Biome regions on " + String.join(" × ", axes) + " over a " + radius + "-block radius: "
                    + regional.size() + " regional biome(s) in " + cells.columns().size() + " column(s) " + cells.columns()
                    + (overLand ? "; ranked over the cells whose " + ground.axis() + " is at least " + ground.min() + " (land)" : "")
                    + (landTakesSea ? "; the land biomes take the sea too, which is not water" : "");
        } else {
            Map<String, List<String>> groups = new LinkedHashMap<>();
            GROUPS.forEach(g -> groups.put(g, new ArrayList<>()));
            for (String id : regional) groups.get(group(roles.role(id, overrides))).add(id);
            Map<String, Double> w = new LinkedHashMap<>(weights);
            String sea = null;
            boolean boundOcean = boundIds.stream().anyMatch(id -> BiomeRoles.OCEAN.equals(roles.role(id, overrides)));
            if (groups.get(BiomeRoles.OCEAN).isEmpty() && !groups.get(BiomeRoles.LAND).isEmpty() && coast.water() && !boundOcean) {
                sea = seaField != null ? seaField.getAsString() : BiomeRoles.sea(meanTemperature(groups.get(BiomeRoles.LAND), weights, roles));
                groups.get(BiomeRoles.OCEAN).add(sea);
            }
            places = places(slug, layout, axes, groups.get(BiomeRoles.LAND), regional, weights, biomes, radius);
            boolean caves = !groups.get(BiomeRoles.CAVE).isEmpty();
            boolean landless = groups.get(BiomeRoles.LAND).isEmpty();
            boolean sealess = groups.get(BiomeRoles.OCEAN).isEmpty() && !boundOcean;
            JsonArray areas = new JsonArray();
            StringBuilder parts = new StringBuilder();
            for (String g : GROUPS) {
                List<String> ids = groups.get(g);
                if (ids.isEmpty()) continue;
                double[] cont, weird = null, depth = null;
                switch (g) {
                    case BiomeRoles.RIVER -> {
                        cont = new double[]{coast.riverLow(), coast.riverHigh()};
                        weird = new double[]{-Coast.RIVER_WEIRDNESS, Coast.RIVER_WEIRDNESS};
                    }
                    case BiomeRoles.SHORE -> cont = new double[]{coast.shoreLow(), coast.shoreHigh()};
                    case BiomeRoles.CAVE -> {
                        cont = new double[]{BiomeLayout.LOW, BiomeLayout.HIGH};
                        depth = new double[]{CAVE_DEPTH, BiomeLayout.HIGH};
                    }
                    case BiomeRoles.LAND -> cont = new double[]{sealess ? BiomeLayout.LOW : coast.line(), BiomeLayout.HIGH};
                    default -> cont = new double[]{BiomeLayout.LOW, landless ? BiomeLayout.HIGH : coast.line()};
                }
                if (caves && (g.equals(BiomeRoles.LAND) || g.equals(BiomeRoles.OCEAN))) depth = new double[]{BiomeLayout.LOW, CAVE_DEPTH};
                Cells cells = cells(places, ids, w, axes, area(cont, weird, depth));
                cells.entries().forEach(kept::add);
                JsonObject a = new JsonObject();
                a.addProperty("role", g);
                a.add("continentalness", BiomeLayout.range(cont[0], cont[1]));
                if (weird != null) a.add("weirdness", BiomeLayout.range(weird[0], weird[1]));
                if (depth != null) a.add("depth", BiomeLayout.range(depth[0], depth[1]));
                if (!cells.cuts().isEmpty()) a.add("columns", cuts(cells.cuts()));
                areas.add(a);
                parts.append(parts.isEmpty() ? "" : "; ").append(g).append(' ').append(cells.columns());
            }
            regions.add("areas", areas);
            note = "Biome regions on " + String.join(" × ", axes) + " over a " + radius + "-block radius, by role (coast at continentalness "
                    + coast.line() + "): " + parts
                    + (sea == null ? "" : "; the sea takes `" + sea + "`, no ocean biome being listed")
                    + (sealess && !landless ? "; the land biomes take the sea too, which is not water" : "")
                    + (overrides.isEmpty() ? "" : "; roles given: " + overrides);
        }
        if (kept.isEmpty()) {
            kept.add(MultiNoise.fullRange("minecraft:plains"));
            entry.note(DatapackCompiler.FALLBACK_NOTE + ": no listed biome is known, so `minecraft:plains` covers all of climate space");
        }
        JsonArray carved = MultiNoise.carve(kept);
        int[] pair = MultiNoise.firstOverlap(carved);
        if (pair != null) {
            throw new IllegalStateException("multi-noise entries " + pair[0] + " and " + pair[1] + " still overlap");
        }
        regions.addProperty("radius", radius);
        if (places != null) {
            regions.addProperty("places", true);
            note += "; laid out over a " + radius + "-block radius as " + PlaceLayout.describe(places);
        }
        entry.note(note + (boundIds.isEmpty() ? "" : "; bound by their climate: " + boundIds));
        List<String> shadowed = new ArrayList<>(regional);
        carved.forEach(e -> shadowed.remove(e.getAsJsonObject().get("biome").getAsString()));
        if (!shadowed.isEmpty()) {
            entry.note("Regional biome(s) " + shadowed + " are never placed: bound climate boxes cover their whole area; narrow those boxes");
        }
        return new Plan(carved, regions, places);
    }

    /** The layout group a role's regional biomes share: peaks and wetlands are land. */
    static String group(String role) {
        return switch (role) {
            case BiomeRoles.PEAK, BiomeRoles.WETLAND -> BiomeRoles.LAND;
            default -> role;
        };
    }

    /** {@code biomeLayout.roles}, each naming a listed biome and a role. */
    static Map<String, String> roles(String slug, JsonObject layout, List<BiomeEntry> biomes) {
        Map<String, String> out = new LinkedHashMap<>();
        JsonElement e = layout.get("roles");
        if (e == null) return out;
        if (!e.isJsonObject()) throw error(slug, "biomeLayout.roles must be an object of biome id to role");
        for (Map.Entry<String, JsonElement> kv : e.getAsJsonObject().entrySet()) {
            JsonElement v = kv.getValue();
            if (!v.isJsonPrimitive() || !BiomeRoles.ROLES.contains(v.getAsString())) {
                throw error(slug, "biomeLayout.roles." + kv.getKey() + " must be one of " + BiomeRoles.ROLES);
            }
            if (biomes.stream().noneMatch(b -> b.id().equals(kv.getKey()))) {
                throw error(slug, "biomeLayout.roles." + kv.getKey() + " names no listed biome");
            }
            out.put(kv.getKey(), v.getAsString());
        }
        return out;
    }

    private static double meanTemperature(List<String> ids, Map<String, Double> weights, BiomeRoles roles) {
        double sum = 0, total = 0;
        for (String id : ids) {
            var t = roles.temperature(id);
            if (t.isEmpty()) continue;
            double w = weights.getOrDefault(id, 1.0);
            sum += t.get() * w;
            total += w;
        }
        return total == 0 ? 0.8 : sum / total;
    }

    /** One group's cells, the first axis's column cuts in rank space, and its biomes by column. */
    private record Cells(List<JsonObject> entries, List<Double> cuts, List<List<String>> columns) {}

    /** Every axis full, but continentalness, weirdness and depth bounded where given. */
    private static JsonObject area(double[] cont, double[] weird, double[] depth) {
        JsonObject p = new JsonObject();
        for (String axis : MultiNoise.AXES) p.add(axis, BiomeLayout.range(BiomeLayout.LOW, BiomeLayout.HIGH));
        if (cont != null) p.add("continentalness", BiomeLayout.range(cont[0], cont[1]));
        if (weird != null) p.add("weirdness", BiomeLayout.range(weird[0], weird[1]));
        if (depth != null) p.add("depth", BiomeLayout.range(depth[0], depth[1]));
        return p;
    }

    private static JsonArray cuts(List<Double> cuts) {
        JsonArray out = new JsonArray();
        cuts.forEach(out::add);
        return out;
    }

    /** The places of {@code layout} over {@code placeable}, or null without {@code places}. */
    private static PlaceLayout.Places places(String slug, JsonObject layout, List<String> axes, List<String> placeable, List<String> regional,
                                             Map<String, Double> weights, List<BiomeEntry> biomes, int radius) {
        return PlaceLayout.present(layout) ? PlaceLayout.build(slug, layout, axes, placeable, regional, weights, biomes, radius) : null;
    }

    /** {@code ids} cut by place when there are {@code places}, else into rank cells. */
    private static Cells cells(PlaceLayout.Places places, List<String> ids, Map<String, Double> weights, List<String> axes, JsonObject area) {
        if (places == null) return cells(ids, weights, axes, area);
        PlaceLayout.Cells c = PlaceLayout.cells(places, ids, weights, axes, area);
        return new Cells(c.entries(), c.cuts(), c.columns());
    }

    /** {@code ids} cut into rank cells of {@code area} on {@code axes}, each a share of its weight over the group's sum. */
    private static Cells cells(List<String> ids, Map<String, Double> weights, List<String> axes, JsonObject area) {
        List<JsonObject> out = new ArrayList<>();
        List<Double> columnCuts = new ArrayList<>();
        List<List<String>> columns = new ArrayList<>();
        if (ids.isEmpty()) return new Cells(out, columnCuts, columns);
        double[] w = new double[ids.size()];
        double total = 0;
        for (int i = 0; i < w.length; i++) {
            w[i] = weights.getOrDefault(ids.get(i), 1.0);
            total += w[i];
        }
        for (int i = 0; i < w.length; i++) w[i] /= total;
        int[] groups = axes.size() == 1 ? new int[]{ids.size()} : columns(w, Math.max(1, (int) Math.round(Math.sqrt(w.length))));
        int start = 0;
        double x = 0;
        for (int c = 0; c < groups.length; c++) {
            int end = start + groups[c];
            double width = 0;
            for (int i = start; i < end; i++) width += w[i];
            double x1 = c == groups.length - 1 ? 1 : x + width;
            List<String> column = new ArrayList<>();
            List<Integer> order = new ArrayList<>();
            for (int i = start; i < end; i++) order.add(i);
            if (c % 2 == 1) java.util.Collections.reverse(order);
            double y = 0;
            for (int k = 0; k < order.size(); k++) {
                int i = order.get(k);
                double y1 = k == order.size() - 1 ? 1 : y + w[i] / width;
                JsonObject p = area.deepCopy();
                if (axes.size() == 1) {
                    p.add(axes.get(0), rankRange(y, y1));
                } else {
                    p.add(axes.get(0), rankRange(x, x1));
                    p.add(axes.get(1), rankRange(y, y1));
                }
                p.addProperty("offset", 0.0);
                out.add(BiomeLayout.entry(ids.get(i), p));
                column.add(ids.get(i));
                y = y1;
            }
            columns.add(column);
            if (c < groups.length - 1) columnCuts.add(rank(x1));
            x = x1;
            start = end;
        }
        return new Cells(out, columnCuts, columns);
    }

    /**
     * Sizes of {@code k} contiguous groups of the shares {@code w} (summing to 1) whose sums are closest to
     * {@code 1 / k} in the least-squares sense; each group holds at least one share.
     */
    static int[] columns(double[] w, int k) {
        int n = w.length;
        k = Math.min(k, n);
        double target = 1.0 / k;
        double[] prefix = new double[n + 1];
        for (int i = 0; i < n; i++) prefix[i + 1] = prefix[i] + w[i];
        double[][] cost = new double[k + 1][n + 1];
        int[][] cut = new int[k + 1][n + 1];
        for (double[] row : cost) java.util.Arrays.fill(row, Double.POSITIVE_INFINITY);
        cost[0][0] = 0;
        for (int g = 1; g <= k; g++) {
            for (int j = g; j <= n - (k - g); j++) {
                for (int i = g - 1; i < j; i++) {
                    if (cost[g - 1][i] == Double.POSITIVE_INFINITY) continue;
                    double d = prefix[j] - prefix[i] - target;
                    double c = cost[g - 1][i] + d * d;
                    if (c < cost[g][j] - 1e-15) {
                        cost[g][j] = c;
                        cut[g][j] = i;
                    }
                }
            }
        }
        int[] sizes = new int[k];
        int j = n;
        for (int g = k; g >= 1; g--) {
            int i = cut[g][j];
            sizes[g - 1] = j - i;
            j = i;
        }
        return sizes;
    }

    /** Rank-space bounds of the share interval [{@code f0}, {@code f1}]; the outer edges reach the axis bounds. */
    private static JsonArray rankRange(double f0, double f1) {
        return BiomeLayout.range(f0 <= 0 ? BiomeLayout.LOW : rank(f0), f1 >= 1 ? BiomeLayout.HIGH : rank(f1));
    }

    /** The rank-space value of cumulative share {@code f}. */
    static double rank(double f) {
        return Math.round((-1 + 2 * f) * 10000) / 10000.0;
    }

    private static IllegalArgumentException error(String slug, String message) {
        return new IllegalArgumentException(slug + ": " + message);
    }
}
