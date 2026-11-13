package com.customdimensions.server.render.fast;

import com.customdimensions.core.render.BiomePalette;
import com.customdimensions.core.render.ContactSheet;
import com.customdimensions.core.render.RenderFiles;
import com.customdimensions.core.render.RenderGrid;
import com.customdimensions.core.render.SliceChoice;
import com.customdimensions.core.render.WavePlan;
import com.customdimensions.server.render.RenderJob;
import com.customdimensions.server.render.Sampler;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * {@code /customdim fastrender <dimension> wave}: a dimension's whole area in
 * passes ({@link WavePlan}), roughest first, each written as soon as it is
 * done: the fast render's layers, a top-down slice at each fixed height
 * {@link SliceChoice} picks from one pre-scan, a contact sheet and
 * {@value #JSON}. A roofed world's first pass is scanned from its floor
 * ({@link ScanWindow#FLOOR}) when that reads the pre-scan's own floors
 * within one block in {@value FastRender#FLOOR_FIRST_AGREEMENT} of its
 * columns, unless {@value #FLOOR_FIRST_PROPERTY} is {@code off}.
 */
public final class FastWave {
    public static final String JSON = "wave.json";
    /** {@code off} scans a roofed world's first pass in whole columns instead of from its floor. */
    public static final String FLOOR_FIRST_PROPERTY = "customdimensions.fastrender.floorfirst";
    /** Biomes the sheet's legend names, most pixels first. */
    public static final int LEGEND_BIOMES = 24;
    /** Layers a sheet leaves out: the height layer repeats the hillshade, the cut the slices; a roofed world's map (its roof) too. */
    static final List<String> NOT_ON_SHEET = List.of("height", "cut", "zones");

    private FastWave() {}

    /** One pass done: its files and times, {@code cumulativeMillis} from the wave's start. */
    public record Pass(WavePlan.Pass pass, long millis, long sampleMillis, long cumulativeMillis, long columnsPerSecond, boolean floorFirst,
                       Path sheet, Path sidecar) {}

    public record Result(String dimension, long seed, WavePlan plan, List<SliceChoice.Slice> slices, List<Pass> passes, Path json,
                         long totalMillis) {}

    /** The folder a wave of {@code seed} writes to, under the dimension's render folder. */
    public static Path dir(Path renders, long seed) {
        return renders.resolve("wave-" + seed);
    }

    static boolean floorFirstDefault() {
        return !"off".equals(System.getProperty(FLOOR_FIRST_PROPERTY));
    }

    /** Renders every pass of {@code plan} for {@code sampler} into {@code dir}, calling {@code onPass} after each. */
    public static Result run(Sampler sampler, WavePlan plan, ExecutorService pool, int threads, Path dir, RenderJob job,
                             IntConsumer progress, Consumer<Pass> onPass) throws IOException {
        return run(sampler, plan, pool, threads, dir, job, progress, onPass, floorFirstDefault());
    }

    static Result run(Sampler sampler, WavePlan plan, ExecutorService pool, int threads, Path dir, RenderJob job, IntConsumer progress,
                      Consumer<Pass> onPass, boolean floorFirst) throws IOException {
        long started = System.nanoTime();
        Files.createDirectories(dir);
        WavePlan.Pass first = plan.passes().getFirst();
        FastRender.Prescan pre = prescan(sampler, RenderGrid.wide(0, 0, plan.radius(), first.stride()), pool, prescanSpacing(first));
        long prescanMillis = (System.nanoTime() - started) / 1_000_000;
        List<SliceChoice.Slice> slices = SliceChoice.choose(pre.profile());
        List<Pass> passes = new ArrayList<>();
        Map<String, Integer> biomeColours = new LinkedHashMap<>();
        JsonArray passJson = new JsonArray();
        Path json = dir.resolve(JSON);
        for (WavePlan.Pass p : plan.passes()) {
            long t = System.nanoTime();
            boolean last = p.index() == plan.passes().size();
            RenderGrid grid = RenderGrid.wide(0, 0, plan.radius(), p.stride());
            String base = "pass" + p.index() + "-s" + p.stride();
            FastRender.Options options = new FastRender.Options(base, false, last, false, slices, pre, floorFirst && p.index() == 1);
            FastRender.Rendered r = FastRender.render(sampler, grid, pool, threads, dir, job, progress, t, options);
            Path sheet = dir.resolve(base + "-sheet.png");
            Map<String, Integer> counts = new HashMap<>(r.samples().biomeCounts());
            for (int s = 0; s < r.slices().size(); s++) r.slices().biomeCounts(s).forEach((k, v) -> counts.merge(k, v, Integer::sum));
            counts.keySet().forEach(id -> biomeColours.putIfAbsent(id, BiomePalette.colour(id)));
            sheet(sheet, sampler, plan, p, r, slices, counts, (System.nanoTime() - t) / 1_000_000);
            long millis = (System.nanoTime() - t) / 1_000_000;
            long cumulative = (System.nanoTime() - started) / 1_000_000;
            boolean cheap = !r.windowMode().equals(pre.window().mode());
            Pass done = new Pass(p, millis, r.result().sampleMillis(), cumulative, r.result().columnsPerSecond(), cheap, sheet,
                    r.result().sidecar());
            passes.add(done);
            passJson.add(passJson(done, r));
            writeJson(json, sampler, plan, pre, slices, passJson, biomeColours, prescanMillis, cumulative, floorFirst);
            if (onPass != null) onPass.accept(done);
        }
        return new Result(sampler.dimension(), sampler.seed(), plan, slices, passes, json, (System.nanoTime() - started) / 1_000_000);
    }

    /** Blocks between pre-scan columns: {@value FastRender#COMPARE_SPACING}, or four first-pass strides when wider. */
    static int prescanSpacing(WavePlan.Pass first) {
        return Math.max(FastRender.COMPARE_SPACING, 4 * first.stride());
    }

    /** One pre-scan of {@code grid} on fresh models every {@code spacing} blocks, closed after. */
    static FastRender.Prescan prescan(Sampler sampler, RenderGrid grid, ExecutorService pool, int spacing) {
        var factory = FastRender.models(sampler);
        ConcurrentLinkedQueue<ColumnModel> made = new ConcurrentLinkedQueue<>();
        ThreadLocal<ColumnModel> models = ThreadLocal.withInitial(() -> {
            ColumnModel m = factory.get();
            made.add(m);
            return m;
        });
        try {
            return FastRender.prescan(grid, pool, models, sampler.noiseBottom(), sampler.noiseTop(), sampler.minY(), sampler.seaLevel(),
                    spacing);
        } finally {
            for (ColumnModel m : made) m.close();
        }
    }

    private static void sheet(Path out, Sampler sampler, WavePlan plan, WavePlan.Pass p, FastRender.Rendered r,
                              List<SliceChoice.Slice> slices, Map<String, Integer> counts, long millis) throws IOException {
        int side = r.samples().grid().side();
        List<ContactSheet.Panel> panels = new ArrayList<>();
        Map<String, Integer> sliceY = new HashMap<>();
        for (SliceChoice.Slice s : slices) sliceY.put("slice-" + s.name(), s.y());
        for (Map.Entry<String, int[]> e : r.rgb().entrySet()) {
            String name = e.getKey();
            if (NOT_ON_SHEET.contains(name) || e.getValue() == null) continue;
            if (name.equals("map") && r.rgb().containsKey("roofoff")) continue;
            String title = name;
            for (Map.Entry<String, Integer> s : sliceY.entrySet()) {
                if (name.startsWith(s.getKey())) title = name + " y " + s.getValue();
            }
            if (name.equals("roofoff") && !r.rgb().containsKey("map")) title = "floor (roof off)";
            panels.add(new ContactSheet.Panel(title, side, e.getValue()));
        }
        List<ContactSheet.Entry> legend = new ArrayList<>();
        legend.add(new ContactSheet.Entry(FastSlices.CAVE_COLOUR, "slice: open under ground"));
        legend.add(new ContactSheet.Entry(FastSlices.SKY_COLOUR, "slice: open sky"));
        legend.add(new ContactSheet.Entry(-1, "slice: ground and fluids by map colour"));
        legend.add(new ContactSheet.Entry(-1, "noise " + sampler.noiseBottom() + " to " + sampler.noiseTop() + ", sea " + sampler.seaLevel()));
        counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(LEGEND_BIOMES)
                .forEach(e -> legend.add(new ContactSheet.Entry(BiomePalette.colour(e.getKey()), e.getKey().replace("minecraft:", ""))));
        String heading = sampler.dimension() + " seed " + sampler.seed() + " pass " + p.index() + "/" + plan.passes().size()
                + " stride " + p.stride() + " " + p.side() + "px radius " + plan.radius() + " " + String.format(java.util.Locale.ROOT,
                "%.1f s", millis / 1000.0);
        ContactSheet.Image image = ContactSheet.compose(heading, panels, legend);
        RenderFiles.png(out, image.width(), image.height(), image.rgb());
    }

    private static JsonObject passJson(Pass p, FastRender.Rendered r) {
        JsonObject o = new JsonObject();
        o.addProperty("pass", p.pass().index());
        o.addProperty("stride", p.pass().stride());
        o.addProperty("side", p.pass().side());
        o.addProperty("columns", (long) p.pass().side() * p.pass().side());
        o.addProperty("millis", p.millis());
        o.addProperty("cumulativeMillis", p.cumulativeMillis());
        o.addProperty("columnsPerSecond", p.columnsPerSecond());
        o.addProperty("floorFirst", p.floorFirst());
        o.addProperty("window", r.windowMode());
        JsonObject timings = new JsonObject();
        r.timings().forEach(timings::addProperty);
        o.add("timings", timings);
        JsonObject images = new JsonObject();
        r.images().forEach((k, v) -> images.addProperty(k, v.getFileName().toString()));
        o.add("images", images);
        o.addProperty("sheet", p.sheet().getFileName().toString());
        o.addProperty("sidecar", p.sidecar().getFileName().toString());
        return o;
    }

    private static void writeJson(Path out, Sampler sampler, WavePlan plan, FastRender.Prescan pre, List<SliceChoice.Slice> slices,
                                  JsonArray passes, Map<String, Integer> biomes, long prescanMillis, long totalMillis, boolean floorFirst)
            throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("kind", "fastrender-wave");
        root.addProperty("dimension", sampler.dimension());
        root.addProperty("seed", sampler.seed());
        root.addProperty("seedSource", sampler.seedSource());
        root.addProperty("radius", plan.radius());
        root.addProperty("radiusRule", "the larger of the player border (the world border) and the generation border, else "
                + RenderGrid.DEFAULT_RADIUS + " blocks; rounded up to 16");
        root.addProperty("passRule", "pass 1 at the stride giving about " + WavePlan.FIRST_SIDE + " pixels across, each next at half "
                + "the stride (rounded up), until " + plan.passes().getLast().side() + " pixels");
        root.addProperty("layout", "pass<N>-s<stride>-<layer>.png, pass<N>-s<stride>-sheet.png, pass<N>-s<stride>.json");
        JsonArray noise = new JsonArray();
        noise.add(sampler.noiseBottom());
        noise.add(sampler.noiseTop());
        root.add("noiseRange", noise);
        root.addProperty("minY", sampler.minY());
        root.addProperty("seaLevel", sampler.seaLevel());
        JsonObject p = new JsonObject();
        SliceChoice.Profile profile = pre.profile();
        p.addProperty("millis", prescanMillis);
        p.addProperty("spacing", prescanSpacing(plan.passes().getFirst()));
        p.addProperty("columns", profile.columns());
        p.addProperty("solidColumns", profile.solidColumns());
        p.addProperty("window", pre.window().mode());
        p.addProperty("roofedShare", Math.round(profile.roofedShare() * 10000) / 10000.0);
        p.addProperty("floatingShare", Math.round(profile.floatingShare() * 10000) / 10000.0);
        if (profile.medianSurface() != null) p.addProperty("medianSurface", profile.medianSurface());
        if (profile.roofUnderside() != null) p.addProperty("roofUnderside", profile.roofUnderside());
        p.addProperty("roofThickness", pre.roofThickness());
        p.addProperty("floorWindowTop", pre.window().start());
        p.addProperty("floorAgreement", Math.round(pre.floorAgreement() * 10000) / 10000.0);
        JsonArray bands = new JsonArray();
        for (SliceChoice.Band b : SliceChoice.bands(profile)) {
            JsonObject o = new JsonObject();
            o.addProperty("bottom", b.bottom());
            o.addProperty("top", b.top());
            o.addProperty("middle", b.middle());
            bands.add(o);
        }
        p.add("solidBands", bands);
        JsonArray byY = new JsonArray();
        for (long count : profile.solid()) byY.add(count);
        p.add("solidByY", byY);
        JsonArray middles = new JsonArray();
        for (long count : profile.middles()) middles.add(count);
        p.add("runMiddlesByY", middles);
        p.addProperty("solidByYRule", "pre-scan columns solid at each height from the noise floor up; runMiddlesByY the runs whose "
                + "middle block is at each height");
        root.add("prescan", p);
        JsonArray sliceJson = new JsonArray();
        for (SliceChoice.Slice s : slices) {
            JsonObject o = new JsonObject();
            o.addProperty("name", s.name());
            o.addProperty("y", s.y());
            o.addProperty("rule", s.rule());
            sliceJson.add(o);
        }
        root.add("slices", sliceJson);
        root.addProperty("floorFirst", floorFirst && pre.floorFirstHolds());
        root.addProperty("floorFirstRule", "on a roofed world whose pre-scan columns read their floor within one block from a floor "
                + "window in at least " + FastRender.FLOOR_FIRST_AGREEMENT + " of columns, pass 1 scans from the floor to under the roof "
                + "and draws the floor in place of the roof; " + FLOOR_FIRST_PROPERTY + "=off scans whole columns");
        root.add("passes", passes);
        root.addProperty("totalMillis", totalMillis);
        JsonObject legend = new JsonObject();
        JsonObject colours = new JsonObject();
        colours.addProperty("openUnderGround", BiomePalette.hex(FastSlices.CAVE_COLOUR));
        colours.addProperty("openSky", BiomePalette.hex(FastSlices.SKY_COLOUR));
        legend.add("slices", colours);
        JsonObject biomeJson = new JsonObject();
        biomes.forEach((k, v) -> biomeJson.addProperty(k, BiomePalette.hex(v)));
        legend.add("biomes", biomeJson);
        root.add("legend", legend);
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
        Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + "\n");
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
