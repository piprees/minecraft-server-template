package com.customdimensions.server.render.fast;

import com.customdimensions.core.render.BiomePalette;
import com.customdimensions.core.render.RenderGrid;
import com.customdimensions.core.render.SliceChoice;
import com.customdimensions.core.render.WavePlan;
import com.customdimensions.core.render.map.BiomeTint;
import com.customdimensions.core.render.map.MapFingerprint;
import com.customdimensions.core.render.map.MapImages;
import com.customdimensions.core.render.map.MapPalette;
import com.customdimensions.core.render.map.MapShade;
import com.customdimensions.core.render.map.MapTiles;
import com.customdimensions.core.render.map.TilePyramid;
import com.customdimensions.server.map.MapColours;
import com.customdimensions.server.render.RenderJob;
import com.customdimensions.server.render.Sampler;
import com.customdimensions.server.render.StructureStarts;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

/**
 * The web map's generated layers for one dimension: its border (the larger
 * of the player and generation borders) rendered once at the finest scale
 * of its {@link MapTiles} by the fast renderer, in pieces of
 * {@value #PIECE} pixels with {@value #MARGIN} pixels of margin (so relief
 * and the density's cell lattice carry across piece edges), and cut into
 * tiles for every zoom by {@link TilePyramid}: coarser zooms are built from
 * the finest pixels, never sampled again. Layers: {@code terrain} (what is
 * seen from above, texture colours and biome tints, water by depth, relief),
 * {@code biomes}, {@code roofless} (the floor under a roof, on a world
 * roofed over {@value FastRender#LAYER_SHARE} of its columns, listed first
 * and drawn as the thumbnail, with the terrain named Roof), a
 * {@code cut-<name>} per fixed height {@link SliceChoice} picks, and a
 * {@code tier-<n>} per sky band (the highest run whose top lies in it).
 * Structure starts go to {@value #MARKERS}. Writes {@value #LAYERS},
 * {@value #THUMB} and {@code tiles/<layer>/<z>/<x>/<y>.png} under the
 * dimension's folder; the tiles are staged and swapped in whole. Skipped when
 * the fingerprint in {@code .cache/generated.fingerprint} still matches.
 */
public final class MapExport {
    public static final int PIECE = 1024;
    public static final int MARGIN = 4;
    public static final String LAYERS = "layers.json";
    public static final String MARKERS = "markers.json";
    public static final String THUMB = "thumb.png";
    public static final String TILES = "tiles";
    static final String FINGERPRINT = ".cache/generated.fingerprint";

    private MapExport() {}

    /** A layer of tiles: its id (the folder under {@code tiles/}), what it is, its height for a cut or tier, and whether it halves by keeping pixels. */
    public record Layer(String id, String kind, String name, Integer y, Integer bottom, Integer top, boolean categorical) {}

    public record Result(String dimension, boolean skipped, String fingerprint, long millis, long columns, long tiles, long bytes,
                         List<Layer> layers, int markers, Map<String, Long> timings) {}

    /** The fingerprint of {@code sampler}'s generated tiles at {@code radius}. */
    public static String fingerprint(Sampler sampler, int radius) {
        List<String> parts = new ArrayList<>();
        RegistryOps<com.google.gson.JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, sampler.registryAccess());
        ChunkGenerator generator = sampler.generator();
        parts.add(ChunkGenerator.CODEC.encodeStart(ops, generator).result().map(Object::toString).orElse(generator.getClass().getName()));
        if (generator instanceof NoiseBasedChunkGenerator noise) {
            parts.add(NoiseGeneratorSettings.DIRECT_CODEC.encodeStart(ops, noise.generatorSettings().value()).result().map(Object::toString)
                    .orElse(""));
        }
        parts.add(sampler.minY() + " " + sampler.maxY() + " " + sampler.seaLevel());
        return MapFingerprint.of(sampler.seed(), radius, MapPalette.vanilla().digest(), parts.toArray(String[]::new));
    }

    public static Result run(Sampler sampler, int radius, ExecutorService pool, int threads, Path out, RenderJob job, boolean force)
            throws IOException {
        long started = System.nanoTime();
        String fp = fingerprint(sampler, radius);
        Path fpFile = out.resolve(FINGERPRINT);
        if (!force && Files.isRegularFile(out.resolve(LAYERS)) && Files.isRegularFile(fpFile)
                && Files.readString(fpFile).strip().equals(fp)) {
            return new Result(sampler.dimension(), true, fp, (System.nanoTime() - started) / 1_000_000, 0, 0, 0, List.of(), 0, Map.of());
        }
        Map<String, Long> timings = new LinkedHashMap<>();
        MapTiles plan = MapTiles.of(radius);
        int finest = plan.finest();
        WavePlan.Pass first = WavePlan.of(radius, WavePlan.FINAL_SIDE).passes().getFirst();
        long t = System.nanoTime();
        FastRender.Prescan pre = FastWave.prescan(sampler, RenderGrid.wide(0, 0, radius, first.stride()), pool, FastWave.prescanSpacing(first));
        timings.put("prescan", ms(t));
        List<SliceChoice.Slice> slices = SliceChoice.choose(pre.profile());
        List<SliceChoice.Band> bands = slices.stream().anyMatch(s -> s.name().startsWith("sky-")) ? SliceChoice.bands(pre.profile()) : List.of();
        boolean roofed = pre.profile().roofedShare() >= FastRender.LAYER_SHARE;

        List<Layer> layers = new ArrayList<>();
        if (roofed) layers.add(new Layer("roofless", "roofless", "Roofless", null, null, null, false));
        layers.add(new Layer("terrain", "terrain", roofed ? "Roof" : "Terrain", null, null, null, false));
        layers.add(new Layer("biomes", "biomes", "Biomes", null, null, null, true));
        for (SliceChoice.Slice s : slices) layers.add(new Layer("cut-" + s.name(), "cut", "Cut: " + title(s.name()), s.y(), null, null, false));
        for (int b = 0; b < bands.size(); b++) {
            SliceChoice.Band band = bands.get(b);
            layers.add(new Layer("tier-" + (b + 1), "tier", "Sky tier " + (b + 1), band.middle(), band.bottom(), band.top(), false));
        }

        Path staging = out.resolve(".staging-" + TILES);
        deleteTree(staging);
        AtomicLong bytes = new AtomicLong();
        int[][] zoomZero = new int[1][];
        String thumbLayer = layers.getFirst().id();
        Map<String, TilePyramid> pyramids = new LinkedHashMap<>();
        for (Layer layer : layers) {
            Path dir = staging.resolve(layer.id());
            pyramids.put(layer.id(), new TilePyramid(plan, layer.categorical(), (z, x, y, argb) -> {
                Path file = dir.resolve(z + "/" + x + "/" + y + ".png");
                MapImages.png(file, MapTiles.TILE, MapTiles.TILE, argb);
                bytes.addAndGet(Files.size(file));
                if (z == 0 && layer.id().equals(thumbLayer)) zoomZero[0] = argb;
            }));
        }

        MapColours colours = new MapColours(sampler.registryAccess());
        Map<String, Long> biomeCounts = new HashMap<>();
        int side = plan.side(plan.maxZoom());
        long columns = 0;
        Path work = out.resolve(".cache/work");
        t = System.nanoTime();
        for (int py0 = 0; py0 < side; py0 += PIECE) {
            for (int px0 = 0; px0 < side; px0 += PIECE) {
                int w = Math.min(PIECE, side - px0);
                int h = Math.min(PIECE, side - py0);
                int s = Math.max(w, h) + 2 * MARGIN;
                int half = s * finest / 2;
                RenderGrid grid = RenderGrid.wide(plan.block(plan.maxZoom(), px0 - MARGIN) + half, plan.block(plan.maxZoom(), py0 - MARGIN) + half,
                        half, finest);
                FastRender.Options options = new FastRender.Options("map", false, false, false, slices, pre, false, false);
                FastRender.Rendered r = FastRender.render(sampler, grid, pool, threads, work, job, null, System.nanoTime(), options);
                columns += grid.columns();
                Piece piece = new Piece(r, colours, s, w, h, finest);
                for (Layer layer : layers) pyramids.get(layer.id()).piece(px0, py0, w, h, piece.paint(layer, slices, bands, biomeCounts));
            }
        }
        timings.put("pieces", ms(t));
        t = System.nanoTime();
        for (TilePyramid p : pyramids.values()) p.finish();
        timings.put("pyramid", ms(t));
        long tiles = pyramids.values().stream().mapToLong(TilePyramid::written).sum();

        Path tilesDir = out.resolve(TILES);
        Path old = out.resolve(".old-" + TILES);
        deleteTree(old);
        if (Files.exists(tilesDir)) Files.move(tilesDir, old);
        Files.createDirectories(staging);
        Files.move(staging, tilesDir);
        deleteTree(old);

        t = System.nanoTime();
        List<JsonObject> markers = markers(sampler, radius, pool);
        timings.put("structures", ms(t));
        writeJson(out.resolve(MARKERS), markersJson(markers));
        if (zoomZero[0] != null) {
            int s0 = plan.side(0);
            MapImages.png(out.resolve(THUMB), s0, s0, MapImages.crop(zoomZero[0], MapTiles.TILE, s0, s0));
        }
        long millis = (System.nanoTime() - started) / 1_000_000;
        timings.put("total", millis);
        writeJson(out.resolve(LAYERS), layersJson(sampler, plan, layers, slices, biomeCounts, fp, millis, columns, tiles, bytes.get(),
                markers.size(), timings));
        Files.createDirectories(fpFile.getParent());
        Files.writeString(fpFile, fp + "\n");
        deleteTree(work);
        return new Result(sampler.dimension(), false, fp, millis, columns, tiles, bytes.get(), layers, markers.size(), timings);
    }

    /** One piece's pixels and the colours of its palette, painting each layer's inner {@code w × h}. */
    private record Piece(FastRender.Rendered r, MapColours colours, int s, int w, int h, int finest, MapPalette.Entry[] entries,
                         int[] plain) {
        Piece(FastRender.Rendered r, MapColours colours, int s, int w, int h, int finest) {
            this(r, colours, s, w, h, finest, entries(r.blocks(), colours), plainColours(r.blocks(), colours));
        }

        private static MapPalette.Entry[] entries(List<BlockState> blocks, MapColours colours) {
            MapPalette.Entry[] out = new MapPalette.Entry[blocks.size()];
            for (int i = 0; i < out.length; i++) out[i] = colours.block(blocks.get(i));
            return out;
        }

        private static int[] plainColours(List<BlockState> blocks, MapColours colours) {
            int[] out = new int[blocks.size()];
            for (int i = 0; i < out.length; i++) out[i] = MapPalette.colour(colours.block(blocks.get(i)), BiomeTint.PLAINS);
            return out;
        }

        int[] paint(Layer layer, List<SliceChoice.Slice> slices, List<SliceChoice.Band> bands, Map<String, Long> biomeCounts) {
            return switch (layer.kind()) {
                case "terrain" -> surface(r.pixels().top, r.pixels().topBlock, r.pixels().topGround, r.pixels().topDepth, false);
                case "roofless" -> surface(null, r.pixels().floorBlock, r.pixels().floorGround, r.pixels().floorDepth, true);
                case "biomes" -> biomes(biomeCounts);
                case "cut" -> cut(slices.indexOf(slices.stream().filter(sl -> ("cut-" + sl.name()).equals(layer.id())).findFirst().orElseThrow()));
                case "tier" -> tier(bands, Integer.parseInt(layer.id().substring("tier-".length())) - 1);
                default -> throw new IllegalArgumentException(layer.kind());
            };
        }

        private BiomeTint tint(int px, int py) {
            return colours.biome(r.samples().biome(px, py));
        }

        private int[] surface(short[] tops, short[] blocks, short[] ground, byte[] depths, boolean floor) {
            int n = s * s;
            int[] rgb = new int[n];
            short[] heights = new short[n];
            boolean[] flat = new boolean[n];
            for (int py = 0; py < s; py++) {
                for (int px = 0; px < s; px++) {
                    int i = py * s + px;
                    short b = blocks[i];
                    short y = floor ? (r.samples().empty(px, py) ? FastPixels.NO_Y : (short) (r.samples().height(px, py) - 1)) : tops[i];
                    if (b < 0 || y == FastPixels.NO_Y) {
                        heights[i] = MapShade.NO_HEIGHT;
                        continue;
                    }
                    BiomeTint tint = tint(px, py);
                    int depth = depths[i] & 0xFF;
                    MapPalette.Entry e = entries[b];
                    if (depth > 0) {
                        flat[i] = true;
                        int under = ground[i] >= 0 ? MapPalette.colour(entries[ground[i]], tint) : 0;
                        rgb[i] = e.tint() == MapPalette.Tint.WATER ? MapShade.water(MapPalette.colour(e, tint), under, depth) : MapPalette.colour(e, tint);
                    } else {
                        rgb[i] = MapPalette.colour(e, tint);
                    }
                    heights[i] = y;
                }
            }
            return inner(MapShade.relief(rgb, heights, flat, s, s, finest));
        }

        private int[] biomes(Map<String, Long> counts) {
            int[] out = new int[w * h];
            Map<String, Integer> local = new HashMap<>();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int px = x + MARGIN;
                    int py = y + MARGIN;
                    if (r.samples().empty(px, py) && r.pixels().topBlock[py * s + px] < 0) continue;
                    String id = r.samples().biome(px, py);
                    local.merge(id, 1, Integer::sum);
                    out[y * w + x] = 0xFF000000 | BiomePalette.colour(id);
                }
            }
            synchronized (counts) {
                local.forEach((k, v) -> counts.merge(k, (long) v, Long::sum));
            }
            return out;
        }

        private int[] cut(int index) {
            int[] full = r.slices().paint(index, plain);
            int[] out = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) out[y * w + x] = 0xFF000000 | full[(y + MARGIN) * s + x + MARGIN];
            }
            return out;
        }

        private int[] tier(List<SliceChoice.Band> bands, int band) {
            FastPixels p = r.pixels();
            int n = s * s;
            int[] rgb = new int[n];
            short[] heights = new short[n];
            for (int py = 0; py < s; py++) {
                for (int px = 0; px < s; px++) {
                    int i = py * s + px;
                    heights[i] = MapShade.NO_HEIGHT;
                    for (int run = 0; run <= FastPixels.TIERS; run++) {
                        short top = run == 0 ? p.top[i] : p.tierTop[run - 1][i];
                        short b = run == 0 ? p.topBlock[i] : p.tierBlock[run - 1][i];
                        if (top == FastPixels.NO_Y || b < 0) break;
                        if (bandOf(bands, top) != band) continue;
                        rgb[i] = MapPalette.colour(entries[b], tint(px, py));
                        heights[i] = top;
                        break;
                    }
                }
            }
            return inner(MapShade.relief(rgb, heights, null, s, s, finest));
        }

        private int[] inner(int[] full) {
            int[] out = new int[w * h];
            for (int y = 0; y < h; y++) System.arraycopy(full, (y + MARGIN) * s + MARGIN, out, y * w, w);
            return out;
        }
    }

    /** The band (top first) whose bottom is the highest at or under {@code top}; -1 under every band. */
    static int bandOf(List<SliceChoice.Band> bands, int top) {
        for (int b = 0; b < bands.size(); b++) if (top >= bands.get(b).bottom()) return b;
        return -1;
    }

    private static List<JsonObject> markers(Sampler sampler, int radius, ExecutorService pool) {
        List<JsonObject> out = Collections.synchronizedList(new ArrayList<>());
        try {
            int reach = (int) Math.ceil(radius * Math.sqrt(2));
            StructureStarts.find(sampler, sampler.structureState(), id -> true, 0, 0, reach, pool, id -> StructureStarts.CAP, id -> reach, null,
                    (start, box) -> {
                        int x = box != null ? (box.minX() + box.maxX()) / 2 : start.x();
                        int z = box != null ? (box.minZ() + box.maxZ()) / 2 : start.z();
                        if (Math.abs(x) > radius || Math.abs(z) > radius) return;
                        JsonObject o = new JsonObject();
                        o.addProperty("type", start.structure());
                        o.addProperty("name", structureName(start.structure()));
                        o.addProperty("x", x);
                        o.addProperty("z", z);
                        if (box != null) {
                            JsonArray b = new JsonArray();
                            b.add(box.minX());
                            b.add(box.minZ());
                            b.add(box.maxX());
                            b.add(box.maxZ());
                            o.add("box", b);
                        }
                        out.add(o);
                    });
        } catch (RuntimeException e) {
            JsonObject o = new JsonObject();
            o.addProperty("error", String.valueOf(e));
            return List.of(o);
        }
        List<JsonObject> sorted = new ArrayList<>(out);
        sorted.sort(Comparator.comparing((JsonObject o) -> o.get("type").getAsString()).thenComparingInt(o -> o.get("x").getAsInt())
                .thenComparingInt(o -> o.get("z").getAsInt()));
        return sorted;
    }

    /** {@code minecraft:village_plains} reads "Village Plains". */
    public static String structureName(String id) {
        return title(id.substring(id.indexOf(':') + 1).replace('/', ' '));
    }

    static String title(String name) {
        StringBuilder out = new StringBuilder();
        for (String word : name.split("[_\\- ]+")) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    private static JsonObject markersJson(List<JsonObject> markers) {
        JsonObject root = new JsonObject();
        JsonArray list = new JsonArray();
        String error = null;
        for (JsonObject m : markers) {
            if (m.has("error")) error = m.get("error").getAsString();
            else list.add(m);
        }
        root.addProperty("rule", "structure starts within the border, from the dimension's own structure state (its structure control "
                + "applied); x and z the centre of the start's bounding box");
        if (error != null) root.addProperty("error", error);
        root.add("structures", list);
        return root;
    }

    private static JsonObject layersJson(Sampler sampler, MapTiles plan, List<Layer> layers, List<SliceChoice.Slice> slices,
                                         Map<String, Long> biomeCounts, String fp, long millis, long columns, long tiles, long bytes,
                                         int markers, Map<String, Long> timings) {
        JsonObject root = new JsonObject();
        root.addProperty("dimension", sampler.dimension());
        root.addProperty("seed", sampler.seed());
        root.addProperty("radius", plan.radius());
        root.addProperty("tileSize", MapTiles.TILE);
        root.addProperty("maxZoom", plan.maxZoom());
        JsonArray bpp = new JsonArray();
        JsonArray sides = new JsonArray();
        for (int z = 0; z <= plan.maxZoom(); z++) {
            bpp.add(plan.blocksPerPixel(z));
            sides.add(plan.side(z));
        }
        root.add("blocksPerPixel", bpp);
        root.add("side", sides);
        root.addProperty("rule", "pixel i of zoom z is the block -radius + i * blocksPerPixel[z] (x west to east, z north to south); "
                + "tiles/<layer>/<z>/<x>/<y>.png, a missing tile transparent");
        root.addProperty("seaLevel", sampler.seaLevel());
        JsonArray range = new JsonArray();
        range.add(sampler.noiseBottom());
        range.add(sampler.noiseTop());
        root.add("noiseRange", range);
        JsonArray list = new JsonArray();
        for (Layer l : layers) {
            JsonObject o = new JsonObject();
            o.addProperty("id", l.id());
            o.addProperty("kind", l.kind());
            o.addProperty("name", l.name());
            if (l.y() != null) o.addProperty("y", l.y());
            if (l.bottom() != null) o.addProperty("bottom", l.bottom());
            if (l.top() != null) o.addProperty("top", l.top());
            o.addProperty("path", TILES + "/" + l.id());
            list.add(o);
        }
        root.add("layers", list);
        JsonArray sliceRules = new JsonArray();
        for (SliceChoice.Slice s : slices) {
            JsonObject o = new JsonObject();
            o.addProperty("id", "cut-" + s.name());
            o.addProperty("y", s.y());
            o.addProperty("rule", s.rule());
            sliceRules.add(o);
        }
        root.add("cuts", sliceRules);
        JsonObject legend = new JsonObject();
        JsonArray biomes = new JsonArray();
        biomeCounts.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).forEach(e -> {
            JsonObject o = new JsonObject();
            o.addProperty("id", e.getKey());
            o.addProperty("name", title(e.getKey().substring(e.getKey().indexOf(':') + 1)));
            o.addProperty("colour", BiomePalette.hex(BiomePalette.colour(e.getKey())));
            o.addProperty("pixels", e.getValue());
            biomes.add(o);
        });
        legend.add("biomes", biomes);
        JsonArray cutLegend = new JsonArray();
        cutLegend.add(entry("Open under ground", FastSlices.CAVE_COLOUR));
        cutLegend.add(entry("Open sky", FastSlices.SKY_COLOUR));
        legend.add("cuts", cutLegend);
        root.add("legend", legend);
        root.addProperty("markers", MARKERS);
        root.addProperty("markerCount", markers);
        root.addProperty("thumb", THUMB);
        root.addProperty("fingerprint", fp);
        root.addProperty("generatedAt", System.currentTimeMillis() / 1000);
        JsonObject stats = new JsonObject();
        stats.addProperty("millis", millis);
        stats.addProperty("columns", columns);
        stats.addProperty("tiles", tiles);
        stats.addProperty("bytes", bytes);
        JsonObject tj = new JsonObject();
        timings.forEach(tj::addProperty);
        stats.add("timings", tj);
        root.add("stats", stats);
        return root;
    }

    private static JsonObject entry(String name, int colour) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("colour", BiomePalette.hex(colour));
        return o;
    }

    static void writeJson(Path out, JsonObject root) throws IOException {
        Files.createDirectories(out.getParent());
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
        Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + "\n");
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    private static long ms(long since) {
        return (System.nanoTime() - since) / 1_000_000;
    }
}
