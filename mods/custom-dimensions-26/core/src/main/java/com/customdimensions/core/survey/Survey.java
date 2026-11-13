package com.customdimensions.core.survey;

import com.customdimensions.core.render.BiomePalette;
import com.customdimensions.core.survey.PatchAnalysis.ColumnClass;
import com.customdimensions.core.survey.PatchAnalysis.Layer;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A dimension's survey dossier from its analysed patches: shares, vegetation,
 * terrain, the vertical profile, each layer's places (k-medoids over cell
 * features) and landmarks, notable clusters, biome expression and the
 * render cross-check, written as {@code dossier.json}, {@code dossier.md} and
 * layer maps under {@code layers/}. Every sentence in the summary is built from
 * a number in the dossier.
 */
public final class Survey {
    /** The common blocks: everything else is notable. The only fixed block list in the survey. */
    public static final Set<String> COMMON = Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:stone",
            "minecraft:deepslate", "minecraft:dirt", "minecraft:grass_block", "minecraft:sand", "minecraft:gravel", "minecraft:water",
            "minecraft:bedrock", "minecraft:granite", "minecraft:diorite", "minecraft:andesite", "minecraft:tuff", "minecraft:netherrack",
            "minecraft:end_stone");
    public static final int CLOUD_Y = 192;
    public static final int MAX_TYPES = 8;
    /** Land columns (a quarter hectare) a tree biome must cover before having no trees flags it. */
    public static final int TREELESS_COLUMNS = 2500;

    /**
     * What the server knows that the patches do not. {@code rockBiomes}: biomes whose surface rule lays bare rock on
     * open land ({@link SurfaceGround}); {@code waterBiomes}: biomes whose role is the sea or a river; {@code voidNatured}: the
     * dimension's nature is islands over a void (the end preset, a terrain over a void), so a biome is judged on its land.
     */
    public record Meta(String dimension, long seed, int minY, int height, int seaLevel, boolean ceiling, JsonObject atmosphere,
                       List<String> listedBiomes, Set<String> treeBiomes, Set<String> rockBiomes, Set<String> waterBiomes,
                       List<SurveyPlan.Patch> plan, Map<String, Long> timings, boolean voidNatured) {

        public Meta(String dimension, long seed, int minY, int height, int seaLevel, boolean ceiling, JsonObject atmosphere,
                    List<String> listedBiomes, Set<String> treeBiomes, Set<String> rockBiomes, Set<String> waterBiomes,
                    List<SurveyPlan.Patch> plan, Map<String, Long> timings) {
            this(dimension, seed, minY, height, seaLevel, ceiling, atmosphere, listedBiomes, treeBiomes, rockBiomes, waterBiomes, plan, timings,
                    false);
        }

        public Meta(String dimension, long seed, int minY, int height, int seaLevel, boolean ceiling, JsonObject atmosphere,
                    List<String> listedBiomes, Set<String> treeBiomes, List<SurveyPlan.Patch> plan, Map<String, Long> timings) {
            this(dimension, seed, minY, height, seaLevel, ceiling, atmosphere, listedBiomes, treeBiomes, Set.of(), Set.of(), plan, timings);
        }
    }

    /** One render-versus-world comparison at a column. */
    public record Check(int x, int z, int renderHeight, String renderBiome, int worldHeight, String worldBiome) {}

    private Survey() {}

    public static JsonObject dossier(Meta meta, Landforms landforms, List<PatchAnalysis> patches, List<Check> checks) {
        JsonObject d = new JsonObject();
        d.addProperty("kind", "survey");
        d.addProperty("dimension", meta.dimension());
        d.addProperty("seed", meta.seed());
        long columns = patches.stream().mapToLong(p -> (long) p.side * p.side).sum();
        double hectares = columns / 10_000.0;
        JsonObject plan = new JsonObject();
        plan.addProperty("patches", patches.size());
        plan.addProperty("columns", columns);
        plan.addProperty("hectares", Stats.round1(hectares));
        JsonArray list = new JsonArray();
        for (SurveyPlan.Patch p : meta.plan()) {
            JsonObject o = new JsonObject();
            o.addProperty("index", p.index());
            o.addProperty("x", p.blockX());
            o.addProperty("z", p.blockZ());
            o.addProperty("side", p.side());
            o.addProperty("origin", p.origin());
            list.add(o);
        }
        plan.add("list", list);
        d.add("plan", plan);
        JsonObject dim = new JsonObject();
        dim.addProperty("minY", meta.minY());
        dim.addProperty("height", meta.height());
        dim.addProperty("seaLevel", meta.seaLevel());
        dim.addProperty("ceiling", meta.ceiling());
        d.add("dimensionType", dim);
        d.add("atmosphere", meta.atmosphere() == null ? new JsonObject() : meta.atmosphere());
        if (landforms != null) d.add("landforms", landforms.toJson());
        d.add("crossCheck", crossCheck(checks));
        d.add("vertical", vertical(patches, meta, columns));
        d.add("surface", surface(patches, columns));
        d.add("vegetation", vegetation(patches, hectares));
        d.add("terrain", terrain(patches, meta));
        d.add("underground", underground(patches));
        d.add("notable", notable(patches));
        d.add("clusters", clusters(patches));
        d.add("structures", counts(merge(patches, p -> p.structures), 50));
        d.add("biomeExpression", biomeExpression(meta, patches));
        d.add("places", places(patches, landforms));
        d.add("timingsMillis", toJson(meta.timings()));
        return d;
    }

    // ---- sections -------------------------------------------------------------------------------

    private static JsonObject crossCheck(List<Check> checks) {
        JsonObject o = new JsonObject();
        o.addProperty("columns", checks.size());
        int within = 0, biome = 0;
        List<Double> diffs = new ArrayList<>();
        for (Check c : checks) {
            int diff = Math.abs(c.renderHeight() - c.worldHeight());
            diffs.add((double) diff);
            if (diff <= 2) within++;
            if (c.renderBiome().equals(c.worldBiome())) biome++;
        }
        o.addProperty("heightWithin2Percent", Stats.percent(within, checks.size()));
        o.addProperty("heightDiffP50", Stats.percentile(diffs.stream().mapToDouble(Double::doubleValue).toArray(), 50));
        o.addProperty("heightDiffP95", Stats.percentile(diffs.stream().mapToDouble(Double::doubleValue).toArray(), 95));
        o.addProperty("biomeMatchPercent", Stats.percent(biome, checks.size()));
        o.addProperty("method", "noise render surface (one above the first motion-blocking block from the top or the roof's underside) "
                + "against the generated column's first solid-to-air boundary below the top or the roof");
        return o;
    }

    private static JsonObject vertical(List<PatchAnalysis> patches, Meta meta, long columns) {
        JsonObject o = new JsonObject();
        Map<String, Long> classes = new LinkedHashMap<>();
        for (ColumnClass c : ColumnClass.values()) classes.put(c.name().toLowerCase(Locale.ROOT), 0L);
        long over = 0, stacked = 0, cave = 0;
        Map<Layer, Long> layerColumns = new LinkedHashMap<>();
        for (Layer l : Layer.values()) layerColumns.put(l, 0L);
        for (PatchAnalysis p : patches) {
            for (int c = 0; c < p.columnClass.length; c++) {
                classes.merge(ColumnClass.values()[p.columnClass[c]].name().toLowerCase(Locale.ROOT), 1L, Long::sum);
                if (p.overhang[c]) over++;
                if (p.stacked[c]) stacked++;
                if (p.cave[c]) cave++;
                if (p.surface[c] != PatchAnalysis.NONE) layerColumns.merge(Layer.SURFACE, 1L, Long::sum);
                if (p.skyTop[c] != PatchAnalysis.NONE) layerColumns.merge(Layer.SKY, 1L, Long::sum);
                if (p.roofUnderside[c] != PatchAnalysis.NONE) layerColumns.merge(Layer.ROOF, 1L, Long::sum);
                if (p.undergroundCells[c] > 0) layerColumns.merge(Layer.UNDERGROUND, 1L, Long::sum);
            }
        }
        JsonObject cls = new JsonObject();
        classes.forEach((k, v) -> cls.addProperty(k, Stats.percent(v, columns)));
        o.add("columnClassPercent", cls);
        o.addProperty("overhangPercent", Stats.percent(over, columns));
        o.addProperty("stackedPercent", Stats.percent(stacked, columns));
        o.addProperty("cavePercent", Stats.percent(cave, columns));
        JsonObject layers = new JsonObject();
        layerColumns.forEach((k, v) -> layers.addProperty(k.name().toLowerCase(Locale.ROOT), Stats.percent(v, columns)));
        o.add("layerColumnsPercent", layers);
        o.addProperty("definitions", "runs of collision blocks; runs closer than " + PatchAnalysis.GAP + " form one block; the lowest block's top "
                + "is the surface, tops above it are sky; overhang: an air pocket within " + PatchAnalysis.GAP + " of the top block's top; "
                + "cave: a pocket of 3+ deeper in the surface block; roofed: under the dimension's ceiling");
        JsonArray bands = new JsonArray();
        int nb = patches.isEmpty() ? 0 : patches.get(0).bands.length;
        for (int b = 0; b < nb; b++) {
            long[] sum = new long[4];
            for (PatchAnalysis p : patches) for (int i = 0; i < 4; i++) sum[i] += p.bands[b][i];
            long total = sum[0] + sum[1] + sum[3];
            JsonObject band = new JsonObject();
            band.addProperty("y", meta.minY() + b * 16);
            band.addProperty("solidPercent", Stats.percent(sum[0], total));
            band.addProperty("openPercent", Stats.percent(sum[1], total));
            band.addProperty("skyExposedPercent", Stats.percent(sum[2], total));
            band.addProperty("fluidPercent", Stats.percent(sum[3], total));
            bands.add(band);
        }
        o.add("yBands", bands);
        return o;
    }

    /** Depth in blocks of the water over the surface block, across water-topped columns. */
    static JsonObject waterDepth(List<Integer> depths) {
        JsonObject o = new JsonObject();
        int[] d = depths.stream().mapToInt(Integer::intValue).toArray();
        o.addProperty("columns", d.length);
        long shallow = depths.stream().filter(x -> x >= 1 && x <= 4).count(), deep = depths.stream().filter(x -> x > 12).count();
        for (int p : new int[] {25, 50, 75, 95}) o.addProperty("p" + p, d.length == 0 ? 0 : Stats.percentile(d, p));
        o.addProperty("max", d.length == 0 ? 0 : java.util.Arrays.stream(d).max().getAsInt());
        o.addProperty("shallow1To4Percent", Stats.percent(shallow, d.length));
        o.addProperty("over12Percent", Stats.percent(deep, d.length));
        return o;
    }

    private static JsonObject surface(List<PatchAnalysis> patches, long columns) {
        Map<String, Long> biomes = new HashMap<>(), tops = new HashMap<>(), grounds = new HashMap<>(), fluids = new HashMap<>(), plants = new HashMap<>();
        long snow = 0, canopy = 0, bare = 0, none = 0, exposed = 0, rain = 0, snowy = 0, dry = 0, surfaced = 0;
        List<Integer> waterDepths = new ArrayList<>();
        List<Integer> bandHeights = new ArrayList<>();
        List<String> bandGrounds = new ArrayList<>();
        for (PatchAnalysis p : patches) {
            for (int c = 0; c < p.surface.length; c++) {
                if (p.surface[c] == PatchAnalysis.NONE) {
                    none++;
                    continue;
                }
                surfaced++;
                bandHeights.add(p.surface[c]);
                bandGrounds.add(p.ground[c]);
                biomes.merge(p.biome[c], 1L, Long::sum);
                tops.merge(p.topBlock[c], 1L, Long::sum);
                grounds.merge(p.ground[c], 1L, Long::sum);
                if (p.fluid[c] != null) fluids.merge(p.fluid[c], 1L, Long::sum);
                if ("water".equals(p.fluid[c])) waterDepths.add(p.fluidDepth[c]);
                if (p.plant[c] != null) plants.merge(p.plant[c], 1L, Long::sum);
                if (p.snow[c]) snow++;
                if (p.canopy[c]) canopy++;
                if (p.bare[c]) bare++;
                if (p.skyExposed[c]) exposed++;
                if (p.precipitation[c] == 2) snowy++;
                else if (p.precipitation[c] == 1) rain++;
                else dry++;
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("columns", surfaced);
        o.addProperty("note", "shares are % of all sampled columns; 'top' is the highest non-air block above the surface (leaves, plants, snow, "
                + "liquids count), 'ground' the surface block itself");
        o.add("biomesPercent", shares(biomes, columns, 20));
        o.add("topBlocksPercent", shares(tops, columns, 15));
        o.add("groundBlocksPercent", shares(grounds, columns, 15));
        o.add("groundBlocksPercentByBand", SurveyMetrics.groundByBand(bandHeights.stream().mapToInt(Integer::intValue).toArray(),
                bandGrounds.toArray(new String[0]), 8));
        JsonObject f = new JsonObject();
        for (String k : List.of("water", "lava", "other")) f.addProperty(k, Stats.percent(fluids.getOrDefault(k, 0L), columns));
        o.add("fluidCoverPercent", f);
        o.add("waterDepth", waterDepth(waterDepths));
        o.addProperty("snowIcePercent", Stats.percent(snow, columns));
        o.addProperty("canopyPercent", Stats.percent(canopy, columns));
        o.addProperty("bareRockPercent", Stats.percent(bare, columns));
        o.addProperty("voidPercent", Stats.percent(none, columns));
        o.addProperty("skyExposedPercent", Stats.percent(exposed, columns));
        JsonObject pr = new JsonObject();
        pr.addProperty("snow", Stats.percent(snowy, columns));
        pr.addProperty("rain", Stats.percent(rain, columns));
        pr.addProperty("none", Stats.percent(dry, columns));
        o.add("precipitationPercent", pr);
        o.add("groundPlantsPercent", shares(plants, columns, 10));
        return o;
    }

    private static JsonObject vegetation(List<PatchAnalysis> patches, double hectares) {
        Map<String, List<PatchAnalysis.Tree>> bySpecies = new LinkedHashMap<>();
        for (PatchAnalysis p : patches) for (PatchAnalysis.Tree t : p.trees) bySpecies.computeIfAbsent(t.species(), k -> new ArrayList<>()).add(t);
        JsonObject o = new JsonObject();
        o.addProperty("method", "trees are 26-connected components of log and stem blocks, named by their commonest log id; spacing is the "
                + "mean distance to the nearest tree of the same species within the same patch");
        JsonObject species = new JsonObject();
        int total = 0;
        List<Map.Entry<String, List<PatchAnalysis.Tree>>> sorted = new ArrayList<>(bySpecies.entrySet());
        sorted.sort((a, b) -> b.getValue().size() - a.getValue().size());
        for (Map.Entry<String, List<PatchAnalysis.Tree>> e : sorted) {
            total += e.getValue().size();
            JsonObject s = new JsonObject();
            s.addProperty("count", e.getValue().size());
            s.addProperty("perHectare", Stats.round1(e.getValue().size() / Math.max(1e-9, hectares)));
            s.addProperty("meanSpacing", Stats.round1(spacing(e.getValue(), t -> new double[] {t.x(), t.z()}, PatchAnalysis.Tree::patch)));
            Map<String, Long> layers = new HashMap<>();
            for (PatchAnalysis.Tree t : e.getValue()) layers.merge(t.layer().name().toLowerCase(Locale.ROOT), 1L, Long::sum);
            s.add("layers", toJson(layers));
            species.add(e.getKey(), s);
        }
        o.addProperty("trees", total);
        o.addProperty("treesPerHectare", Stats.round1(total / Math.max(1e-9, hectares)));
        JsonObject byLayer = new JsonObject();
        for (Layer layer : Layer.values()) {
            long n = patches.stream().flatMap(p -> p.trees.stream()).filter(t -> t.layer() == layer).count();
            byLayer.addProperty(layer.name().toLowerCase(Locale.ROOT), Stats.round1(n / Math.max(1e-9, hectares)));
        }
        o.add("treesPerHectareByLayer", byLayer);
        o.add("species", species);
        return o;
    }

    /** Mean nearest-neighbour distance computed within each patch, weighted by points. */
    private static <T> double spacing(List<T> items, java.util.function.Function<T, double[]> xy, java.util.function.ToIntFunction<T> patch) {
        Map<Integer, List<double[]>> byPatch = new HashMap<>();
        for (T t : items) byPatch.computeIfAbsent(patch.applyAsInt(t), k -> new ArrayList<>()).add(xy.apply(t));
        double sum = 0;
        int n = 0;
        for (List<double[]> pts : byPatch.values()) {
            if (pts.size() < 2) continue;
            sum += Stats.meanNearestNeighbour(pts) * pts.size();
            n += pts.size();
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    private static JsonObject terrain(List<PatchAnalysis> patches, Meta meta) {
        List<Double> heights = new ArrayList<>();
        JsonArray relief = new JsonArray();
        List<Double> reliefs = new ArrayList<>();
        int flat = 0, moderate = 0, steep = 0, cliff = 0, measured = 0;
        long clouds = 0, belowSea = 0, belowZero = 0;
        for (PatchAnalysis p : patches) {
            int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
            for (int h : p.surface) {
                if (h == PatchAnalysis.NONE) continue;
                heights.add((double) h);
                min = Math.min(min, h);
                max = Math.max(max, h);
                if (h >= CLOUD_Y) clouds++;
                if (h < meta.seaLevel()) belowSea++;
                if (h < 0) belowZero++;
            }
            int r = max < min ? 0 : max - min;
            relief.add(r);
            reliefs.add((double) r);
            Stats.Slopes s = Stats.slopes(p.surface, p.side);
            flat += s.flat();
            moderate += s.moderate();
            steep += s.steep();
            cliff += s.cliff();
            measured += s.measured();
        }
        double[] h = heights.stream().mapToDouble(Double::doubleValue).toArray();
        JsonObject o = new JsonObject();
        JsonObject pct = new JsonObject();
        for (int p : new int[] {5, 25, 50, 75, 95}) pct.addProperty("p" + p, Stats.percentile(h, p));
        pct.addProperty("max", h.length == 0 ? Double.NaN : java.util.Arrays.stream(h).max().getAsDouble());
        o.add("surfaceHeight", pct);
        o.add("reliefPerPatch", relief);
        o.addProperty("reliefMedian", Stats.percentile(reliefs.stream().mapToDouble(Double::doubleValue).toArray(), 50));
        JsonObject slopes = new JsonObject();
        slopes.addProperty("flatUnder15Percent", Stats.percent(flat, measured));
        slopes.addProperty("from15To45Percent", Stats.percent(moderate, measured));
        slopes.addProperty("over45Percent", Stats.percent(steep, measured));
        slopes.addProperty("cliffPercent", Stats.percent(cliff, measured));
        o.add("slopes", slopes);
        o.addProperty("aboveCloudsPercent", Stats.percent(clouds, h.length));
        o.addProperty("belowSeaLevelPercent", Stats.percent(belowSea, h.length));
        o.addProperty("belowZeroPercent", Stats.percent(belowZero, h.length));
        o.addProperty("seaLevel", meta.seaLevel());
        o.addProperty("cloudY", CLOUD_Y);
        return o;
    }

    private static JsonObject underground(List<PatchAnalysis> patches) {
        long air = 0, fluid = 0, cells = 0;
        for (PatchAnalysis p : patches) {
            for (int c = 0; c < p.undergroundCells.length; c++) {
                air += p.undergroundAir[c];
                fluid += p.undergroundFluid[c];
                cells += p.undergroundCells[c];
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("band", "below surface - " + PatchAnalysis.NEAR_BELOW + ", down to the surface block's bottom");
        o.addProperty("cells", cells);
        o.addProperty("airPercent", Stats.percent(air, cells));
        o.addProperty("fluidPercent", Stats.percent(fluid, cells));
        Map<String, Long> biomes = merge(patches, p -> p.undergroundBiomes);
        long total = biomes.values().stream().mapToLong(Long::longValue).sum();
        o.add("caveBiomesPercent", shares(biomes, total, 15));
        o.add("exposedOres", counts(merge(patches, p -> p.exposedOres), 20));
        return o;
    }

    private static JsonObject notable(List<PatchAnalysis> patches) {
        JsonObject o = new JsonObject();
        o.addProperty("common", String.join(", ", new java.util.TreeSet<>(COMMON)));
        for (Layer l : Layer.values()) {
            Map<String, Long> m = merge(patches, p -> p.notable.get(l));
            o.add(l.name().toLowerCase(Locale.ROOT), counts(m, 25));
        }
        return o;
    }

    private static JsonObject clusters(List<PatchAnalysis> patches) {
        Map<String, List<PatchAnalysis.Found>> byId = new LinkedHashMap<>();
        Map<String, Set<Integer>> present = new HashMap<>();
        for (PatchAnalysis p : patches) {
            for (PatchAnalysis.Found f : p.clusters) {
                byId.computeIfAbsent(f.id(), k -> new ArrayList<>()).add(f);
                present.computeIfAbsent(f.id(), k -> new LinkedHashSet<>()).add(p.patch.index());
            }
            for (String s : p.saturated.keySet()) present.computeIfAbsent(s, k -> new LinkedHashSet<>()).add(p.patch.index());
        }
        JsonObject o = new JsonObject();
        o.addProperty("method", "26-connected clusters of one notable block within a patch; spacing is the mean horizontal distance between "
                + "cluster centres to the nearest in the same patch; a block in only one patch is not reported here");
        long totalPatches = patches.size();
        List<Map.Entry<String, List<PatchAnalysis.Found>>> entries = new ArrayList<>(byId.entrySet());
        entries.sort(Comparator.comparingInt(e -> e.getValue().size()));
        JsonObject types = new JsonObject();
        for (Map.Entry<String, List<PatchAnalysis.Found>> e : entries) {
            int patchesWith = present.get(e.getKey()).size();
            if (patchesWith < 2) continue;
            List<PatchAnalysis.Found> fs = e.getValue();
            JsonObject t = new JsonObject();
            t.addProperty("clusters", fs.size());
            t.addProperty("patches", patchesWith);
            t.addProperty("patchSharePercent", Stats.percent(patchesWith, totalPatches));
            double[] sizes = fs.stream().mapToDouble(PatchAnalysis.Found::size).toArray();
            t.addProperty("sizeP50", Stats.percentile(sizes, 50));
            t.addProperty("sizeP90", Stats.percentile(sizes, 90));
            t.addProperty("sizeMax", Stats.percentile(sizes, 100));
            t.addProperty("blocks", (long) java.util.Arrays.stream(sizes).sum());
            t.addProperty("meanSpacing", Stats.round1(spacing(fs, f -> new double[] {f.x(), f.z()}, PatchAnalysis.Found::patch)));
            Map<String, Long> layers = new HashMap<>();
            for (PatchAnalysis.Found f : fs) layers.merge(f.layer().name().toLowerCase(Locale.ROOT), (long) f.size(), Long::sum);
            t.add("layerBlocks", toJson(layers));
            t.addProperty("distinctness", Math.round((1 - patchesWith / (double) Math.max(1, totalPatches)) * 100) / 100.0);
            JsonArray places = new JsonArray();
            fs.stream().filter(f -> f.camera() != null).sorted((a, b) -> b.size() - a.size()).limit(2).forEach(f -> places.add(found(f)));
            t.add("places", places);
            types.add(e.getKey(), t);
        }
        o.add("types", types);
        return o;
    }

    private static JsonObject found(PatchAnalysis.Found f) {
        JsonObject o = new JsonObject();
        o.addProperty("size", f.size());
        o.addProperty("layer", f.layer().name().toLowerCase(Locale.ROOT));
        o.add("at", arr(Math.floor(f.x()), Math.round(f.y()), Math.floor(f.z())));
        double[] c = f.camera();
        o.add("camera", arr(c[0], c[1], c[2]));
        return o;
    }

    // ---- biome expression -----------------------------------------------------------------------

    private static JsonObject biomeExpression(Meta meta, List<PatchAnalysis> patches) {
        Map<String, long[]> s = new LinkedHashMap<>();
        Map<String, Map<String, Long>> tops = new HashMap<>();
        Map<String, Integer> trees = new HashMap<>();
        // index: 0 columns, 1 land, 2 water, 3 lava, 4 void, 5 roofed, 6 sky, 7 snow, 8 canopy, 9 plants, 10 bare, 11 precipSnow, 12 notable
        for (PatchAnalysis p : patches) {
            for (int c = 0; c < p.surface.length; c++) {
                if (p.surface[c] == PatchAnalysis.NONE) {
                    long[] a = s.computeIfAbsent(p.biome[c], k -> new long[13]);
                    a[0]++;
                    a[4]++;
                    if (p.roofUnderside[c] != PatchAnalysis.NONE) a[5]++;
                } else {
                    long[] a = s.computeIfAbsent(p.biome[c], k -> new long[13]);
                    a[0]++;
                    if (p.fluid[c] == null) a[1]++;
                    else if (p.fluid[c].equals("lava")) a[3]++;
                    else a[2]++;
                    if (p.roofUnderside[c] != PatchAnalysis.NONE) a[5]++;
                    if (p.snow[c]) a[7]++;
                    if (p.canopy[c]) a[8]++;
                    if (p.plant[c] != null) a[9]++;
                    if (p.bare[c] && p.fluid[c] == null) a[10]++;
                    if (p.precipitation[c] == 2) a[11]++;
                    tops.computeIfAbsent(p.biome[c], k -> new HashMap<>()).merge(p.topBlock[c], 1L, Long::sum);
                }
                if (p.skyBiome[c] != null) {
                    long[] a = s.computeIfAbsent(p.skyBiome[c], k -> new long[13]);
                    a[0]++;
                    a[6]++;
                }
            }
            for (PatchAnalysis.Tree t : p.trees) if (t.layer() == Layer.SURFACE || t.layer() == Layer.SKY) trees.merge(t.biome(), 1, Integer::sum);
        }
        Map<String, Long> cave = merge(patches, p -> p.undergroundBiomes);
        JsonObject o = new JsonObject();
        o.addProperty("method", "placement shares are % of the biome's own surface columns (plus sky-island tops); flags: mostly water or lava (but for a sea or river biome) or "
                + "void (50%+; over a void-natured dimension, the End or sky islands, a biome is judged on its own surface columns and void is no flag), mostly bare rock (50%+ of land, but for a biome whose surface rule lays rock), a snowing climate (50%+) under 5% snow cover, a biome whose placed features are named for trees with "
                + "none on a quarter hectare or more of its land; judged from 100+ columns");
        JsonObject biomes = new JsonObject();
        int placed = 0, expressed = 0;
        List<Map.Entry<String, long[]>> sorted = new ArrayList<>(s.entrySet());
        sorted.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        for (Map.Entry<String, long[]> e : sorted) {
            long[] a = e.getValue();
            placed++;
            JsonObject b = new JsonObject();
            b.addProperty("columns", a[0]);
            JsonObject where = new JsonObject();
            where.addProperty("land", Stats.percent(a[1], a[0]));
            where.addProperty("water", Stats.percent(a[2], a[0]));
            where.addProperty("lava", Stats.percent(a[3], a[0]));
            where.addProperty("void", Stats.percent(a[4], a[0]));
            where.addProperty("roofed", Stats.percent(a[5], a[0]));
            where.addProperty("sky", Stats.percent(a[6], a[0]));
            b.add("placementPercent", where);
            JsonObject carry = new JsonObject();
            carry.addProperty("snowPercent", Stats.percent(a[7], a[1] + a[2] + a[3]));
            carry.addProperty("canopyPercent", Stats.percent(a[8], a[1]));
            carry.addProperty("plantsPercent", Stats.percent(a[9], a[1]));
            carry.addProperty("bareRockPercent", Stats.percent(a[10], a[1]));
            carry.addProperty("snowingClimatePercent", Stats.percent(a[11], a[1] + a[2] + a[3]));
            double density = a[1] == 0 ? 0 : trees.getOrDefault(e.getKey(), 0) / (a[1] / 10_000.0);
            carry.addProperty("treesPerHectare", Stats.round1(density));
            carry.add("topBlocksPercent", shares(tops.getOrDefault(e.getKey(), Map.of()), a[0], 3));
            b.add("carries", carry);
            JsonArray flags = new JsonArray();
            long ground = meta.voidNatured() ? a[0] - a[4] : a[0];
            boolean judged = ground >= 100;
            if (judged) {
                if (a[2] * 2 >= ground && !meta.waterBiomes().contains(e.getKey())) flags.add("mostly water");
                if (a[3] * 2 >= ground && !meta.waterBiomes().contains(e.getKey())) flags.add("mostly lava");
                if (!meta.voidNatured() && a[4] * 2 >= a[0]) flags.add("mostly void");
                if (a[1] > 0 && a[10] * 2 >= a[1] && !meta.rockBiomes().contains(e.getKey())) flags.add("mostly bare rock");
                long wet = a[1] + a[2] + a[3];
                if (wet > 0 && a[11] * 2 >= wet && a[7] * 20 < wet) flags.add("snowing climate but snow cover under 5%");
                if (meta.treeBiomes().contains(e.getKey()) && a[1] >= TREELESS_COLUMNS && density == 0) {
                    flags.add("features name trees, none on " + Stats.round1(a[1] / 10_000.0) + " ha of land");
                }
            }
            b.add("flags", flags);
            String state = !judged ? "too few columns to judge" : flags.isEmpty() ? "expressed" : "placed, not expressed";
            if (judged && flags.isEmpty()) expressed++;
            b.addProperty("state", state);
            biomes.add(e.getKey(), b);
        }
        for (Map.Entry<String, Long> e : cave.entrySet()) {
            if (biomes.has(e.getKey())) continue;
            JsonObject b = new JsonObject();
            b.addProperty("caveQuarts", e.getValue());
            b.addProperty("state", "underground only");
            b.add("flags", new JsonArray());
            biomes.add(e.getKey(), b);
        }
        o.addProperty("placed", placed);
        o.addProperty("expressed", expressed);
        JsonArray missing = new JsonArray();
        for (String id : meta.listedBiomes()) if (!biomes.has(id)) missing.add(id);
        o.add("listedNotFound", missing);
        o.add("biomes", biomes);
        return o;
    }

    // ---- places ---------------------------------------------------------------------------------

    private static JsonObject places(List<PatchAnalysis> patches, Landforms landforms) {
        JsonObject o = new JsonObject();
        o.addProperty("method", "per layer, 16x16 cells described by numeric features (Gower-scaled to each feature's own 5th-95th percentile range) "
                + "and categorical ones; k-medoids with k by the best silhouette (2-" + MAX_TYPES + ", one type under "
                + KMedoids.MIN_SILHOUETTE + "); landmarks are cells farther from their medoid than both the 95th percentile and twice the median");
        for (Layer l : Layer.values()) {
            List<PatchAnalysis.Cell> cells = new ArrayList<>();
            for (PatchAnalysis p : patches) for (PatchAnalysis.Cell c : p.cells) if (c.layer() == l && c.presence() >= 0.25) cells.add(c);
            o.add(l.name().toLowerCase(Locale.ROOT), layerPlaces(cells, patches));
        }
        if (landforms != null && landforms.vista() != null) {
            int[] v = landforms.vista();
            JsonObject vista = new JsonObject();
            vista.add("summit", arr(v[0], v[1], v[2]));
            vista.add("lookAt", arr(v[3], v[4], v[5]));
            vista.addProperty("note", (landforms.roofed() ? "the coarse map's highest land cell with " + Landforms.OPEN_UNDER_ROOF
                    + " or more blocks open under the roof" : "the coarse map's highest land cell")
                    + " and the point 160 blocks off in the direction of its largest drop");
            o.add("vista", vista);
        }
        return o;
    }

    private static JsonObject layerPlaces(List<PatchAnalysis.Cell> cells, List<PatchAnalysis> patches) {
        JsonObject o = new JsonObject();
        o.addProperty("cells", cells.size());
        if (cells.isEmpty()) {
            o.add("types", new JsonArray());
            o.add("landmarks", new JsonArray());
            return o;
        }
        List<String> numKeys = new ArrayList<>(cells.get(0).numeric().keySet());
        List<String> catKeys = new ArrayList<>(cells.get(0).categorical().keySet());
        double[][] num = new double[cells.size()][numKeys.size()];
        String[][] cat = new String[cells.size()][catKeys.size()];
        for (int i = 0; i < cells.size(); i++) {
            for (int f = 0; f < numKeys.size(); f++) num[i][f] = cells.get(i).numeric().getOrDefault(numKeys.get(f), 0.0);
            for (int f = 0; f < catKeys.size(); f++) cat[i][f] = cells.get(i).categorical().get(catKeys.get(f));
        }
        double[] mean = new double[numKeys.size()], sd = new double[numKeys.size()];
        for (int f = 0; f < numKeys.size(); f++) {
            double s = 0, s2 = 0;
            for (double[] r : num) {
                s += r[f];
                s2 += r[f] * r[f];
            }
            mean[f] = s / num.length;
            sd[f] = Math.sqrt(Math.max(0, s2 / num.length - mean[f] * mean[f]));
        }
        double[][] d = KMedoids.gower(num, cat);
        KMedoids.Result r = KMedoids.cluster(d, MAX_TYPES);
        o.addProperty("k", r.k());
        o.addProperty("silhouette", Math.round(r.silhouette() * 1000) / 1000.0);
        JsonArray types = new JsonArray();
        List<Integer> order = new ArrayList<>();
        for (int k = 0; k < r.k(); k++) order.add(k);
        order.sort((a, b) -> r.size(b) - r.size(a));
        for (int k : order) {
            int medoid = r.medoids()[k];
            double distinct = 0;
            int others = 0;
            for (int m = 0; m < r.k(); m++) {
                if (m == k) continue;
                distinct += d[medoid][r.medoids()[m]];
                others++;
            }
            JsonObject t = new JsonObject();
            t.addProperty("type", types.size() + 1);
            t.addProperty("sharePercent", Stats.percent(r.size(k), cells.size()));
            t.addProperty("distinctness", Stats.round1(others == 0 ? 0 : distinct / others * 100) / 100.0);
            double[] typeMean = new double[numKeys.size()];
            Map<String, Map<String, Integer>> modes = new LinkedHashMap<>();
            int n = 0;
            for (int i = 0; i < cells.size(); i++) {
                if (r.assignment()[i] != k) continue;
                n++;
                for (int f = 0; f < numKeys.size(); f++) typeMean[f] += num[i][f];
                for (int f = 0; f < catKeys.size(); f++) {
                    if (cat[i][f] != null) modes.computeIfAbsent(catKeys.get(f), x -> new HashMap<>()).merge(cat[i][f], 1, Integer::sum);
                }
            }
            JsonObject numbers = new JsonObject();
            for (int f = 0; f < numKeys.size(); f++) {
                typeMean[f] /= Math.max(1, n);
                numbers.addProperty(numKeys.get(f), Math.round(typeMean[f] * 100) / 100.0);
            }
            JsonObject dominant = new JsonObject();
            modes.forEach((key, m) -> dominant.addProperty(key, Stats.dominant(m)));
            t.add("dominant", dominant);
            t.add("numbers", numbers);
            t.addProperty("description", describe(numKeys, typeMean, mean, sd, dominant, r.size(k), cells.size()));
            JsonArray coords = new JsonArray();
            List<Integer> members = new ArrayList<>();
            for (int i = 0; i < cells.size(); i++) if (r.assignment()[i] == k) members.add(i);
            members.sort(Comparator.comparingDouble(i -> d[i][medoid]));
            for (int i : members) {
                if (coords.size() >= 2) break;
                if (cells.get(i).camera() == null) continue;
                coords.add(cellPlace(cells.get(i)));
            }
            t.add("coordinates", coords);
            types.add(t);
        }
        o.add("types", types);
        JsonArray landmarks = new JsonArray();
        for (int i : KMedoids.outliers(r)) {
            if (landmarks.size() >= 5) break;
            PatchAnalysis.Cell c = cells.get(i);
            int medoid = r.medoids()[r.assignment()[i]];
            JsonObject lm = cellPlace(c);
            lm.addProperty("distanceToType", Math.round(r.toMedoid()[i] * 100) / 100.0);
            lm.addProperty("nearestType", order.indexOf(r.assignment()[i]) + 1);
            lm.addProperty("description", landmark(numKeys, num[i], num[medoid], sd, catKeys, cat[i], cat[medoid]));
            landmarks.add(lm);
        }
        o.add("landmarks", landmarks);
        return o;
    }

    private static JsonObject cellPlace(PatchAnalysis.Cell c) {
        JsonObject o = new JsonObject();
        o.add("cell", arr(c.x(), c.z()));
        o.addProperty("patch", c.patch());
        if (c.camera() != null) {
            double[] k = c.camera();
            o.add("camera", arr(k[0], k[1], k[2]));
            o.addProperty("yaw", Math.round(k[3] * 10) / 10.0);
            o.addProperty("pitch", k[4]);
        }
        return o;
    }

    private static final Map<String, String> LABEL = Map.ofEntries(Map.entry("presence", "layer present in"), Map.entry("height", "height"),
            Map.entry("relief4", "neighbour step"), Map.entry("relief16", "relief over 16 blocks"), Map.entry("relief48", "relief over the patch"),
            Map.entry("trees", "trees per cell"), Map.entry("notable", "notable-block density (log)"), Map.entry("canopy", "canopy"),
            Map.entry("plants", "ground plants"), Map.entry("fluid", "liquid cover"), Map.entry("snow", "snow or ice"),
            Map.entry("bare", "bare rock"), Map.entry("skyExposed", "open to the sky"), Map.entry("overhang", "overhangs"),
            Map.entry("stacked", "stacked layers"), Map.entry("cave", "cave pockets"), Map.entry("roofed", "under a roof"),
            Map.entry("islands", "island layers above"), Map.entry("caveAir", "cave air"), Map.entry("caveFluid", "cave liquid"));

    private static final Set<String> SHARES = Set.of("presence", "canopy", "plants", "fluid", "snow", "bare", "skyExposed", "overhang", "stacked",
            "cave", "roofed", "caveAir", "caveFluid");

    private static String value(String key, double v) {
        return SHARES.contains(key) ? Math.round(v * 100) + "%" : String.format(Locale.ROOT, "%.1f", v);
    }

    /** A type's plain description: its dominant categories, then each feature at least half a standard deviation from the layer's mean. */
    static String describe(List<String> keys, double[] typeMean, double[] mean, double[] sd, JsonObject dominant, int n, int total) {
        StringBuilder b = new StringBuilder();
        b.append(Stats.percent(n, total)).append("% of cells");
        for (Map.Entry<String, JsonElement> e : dominant.entrySet()) {
            if (e.getValue().isJsonNull()) continue;
            b.append("; ").append(e.getKey()).append(" ").append(short_(e.getValue().getAsString()));
        }
        int hi = keys.indexOf("height");
        if (hi >= 0) b.append("; height ").append(Math.round(typeMean[hi]));
        for (int f = 0; f < keys.size(); f++) {
            if (keys.get(f).equals("height") || sd[f] <= 0) continue;
            double z = (typeMean[f] - mean[f]) / sd[f];
            if (Math.abs(z) < 0.5) continue;
            b.append("; ").append(LABEL.getOrDefault(keys.get(f), keys.get(f))).append(" ").append(value(keys.get(f), typeMean[f]))
                    .append(" (layer ").append(value(keys.get(f), mean[f])).append(")");
        }
        return b.toString();
    }

    private static String landmark(List<String> keys, double[] cell, double[] medoid, double[] sd, List<String> catKeys, String[] cc, String[] mc) {
        List<int[]> diffs = new ArrayList<>();
        for (int f = 0; f < keys.size(); f++) {
            if (sd[f] <= 0) continue;
            diffs.add(new int[] {f, (int) Math.round(Math.abs(cell[f] - medoid[f]) / sd[f] * 100)});
        }
        diffs.sort((a, b) -> b[1] - a[1]);
        StringBuilder b = new StringBuilder();
        for (int f = 0; f < catKeys.size(); f++) {
            if (cc[f] != null && !cc[f].equals(mc[f])) b.append(b.isEmpty() ? "" : "; ").append(catKeys.get(f)).append(" ").append(short_(cc[f]))
                    .append(" (type ").append(mc[f] == null ? "none" : short_(mc[f])).append(")");
        }
        for (int i = 0; i < Math.min(3, diffs.size()); i++) {
            int f = diffs.get(i)[0];
            b.append(b.isEmpty() ? "" : "; ").append(LABEL.getOrDefault(keys.get(f), keys.get(f))).append(" ").append(value(keys.get(f), cell[f]))
                    .append(" (type ").append(value(keys.get(f), medoid[f])).append(")");
        }
        return b.toString();
    }

    static String short_(String id) {
        return id == null ? "none" : id.startsWith("minecraft:") ? id.substring(10) : id;
    }

    // ---- helpers --------------------------------------------------------------------------------

    private static Map<String, Long> merge(List<PatchAnalysis> patches, java.util.function.Function<PatchAnalysis, Map<String, ? extends Number>> f) {
        Map<String, Long> out = new HashMap<>();
        for (PatchAnalysis p : patches) f.apply(p).forEach((k, v) -> out.merge(k, v.longValue(), Long::sum));
        return out;
    }

    private static JsonObject shares(Map<String, Long> counts, long total, int limit) {
        JsonObject o = new JsonObject();
        counts.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(limit)
                .forEach(e -> o.addProperty(e.getKey(), Stats.percent(e.getValue(), total)));
        return o;
    }

    private static JsonObject counts(Map<String, Long> counts, int limit) {
        JsonObject o = new JsonObject();
        counts.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(limit).forEach(e -> o.addProperty(e.getKey(), e.getValue()));
        return o;
    }

    private static JsonObject toJson(Map<String, ? extends Number> m) {
        JsonObject o = new JsonObject();
        if (m != null) m.forEach(o::addProperty);
        return o;
    }

    static JsonArray arr(double... v) {
        JsonArray a = new JsonArray();
        for (double x : v) a.add(x == Math.rint(x) ? (Number) (long) x : (Number) (Math.round(x * 100) / 100.0));
        return a;
    }

    // ---- files ----------------------------------------------------------------------------------

    /** Writes dossier.json, dossier.md and the layer maps into {@code dir}; returns the dossier path. */
    public static Path write(Path dir, JsonObject dossier, List<PatchAnalysis> patches, Landforms landforms) throws IOException {
        Files.createDirectories(dir.resolve("layers"));
        JsonObject maps = LayerMaps.write(dir.resolve("layers"), patches, landforms);
        dossier.add("layerMaps", maps);
        Path json = dir.resolve("dossier.json");
        Files.writeString(json, new GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues().create().toJson(dossier));
        Files.writeString(dir.resolve("dossier.md"), SurveyText.markdown(dossier));
        return json;
    }

    static int biomeColour(String id) {
        return BiomePalette.colour(id);
    }

    static void png(Path out, int w, int h, int[] rgb) throws IOException {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, w, h, rgb, 0, w);
        javax.imageio.ImageIO.write(img, "png", out.toFile());
    }
}
