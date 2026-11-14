package com.customdimensions.server.map;

import com.customdimensions.core.render.map.ExploredChunk;
import com.customdimensions.core.render.map.ExploredRegion;
import com.customdimensions.core.render.map.MapImages;
import com.customdimensions.core.render.map.MapShade;
import com.customdimensions.core.render.map.MapTiles;
import com.customdimensions.core.render.map.TilePyramid;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * The web map's explored layer for one dimension: every finished saved chunk
 * inside the border, drawn from above with the generated terrain's palette,
 * tints, water and relief ({@link ExploredChunk}, {@link MapShade}) and cut
 * into {@code explored/<z>/<x>/<y>.png} at the same zooms, transparent where
 * no chunk is saved; {@value #JSON} lists the tiles written by zoom. Incremental: a region file is read again only when its
 * modification time differs from the one recorded in
 * {@code .cache/explored/state.json}; its cells are cached beside it
 * ({@link ExploredRegion}), and the tiles are rebuilt from the caches when
 * any region changed, staged and swapped in whole.
 */
public final class ExploredExport {
    public static final String DIR = "explored";
    public static final String JSON = "explored.json";
    static final String CACHE = ".cache/explored";
    private static final Pattern REGION = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

    private ExploredExport() {}

    public record Result(String dimension, boolean skipped, int regions, int regionsRead, long chunks, long chunksUnreadable,
                         long chunksUnfinished, long readMillis, long columnsPerSecond, long tiles, long bytes, long millis) {}

    public static Result run(String dimension, ServerLevel level, Path regionDir, MapColours colours, int radius, Path out,
                             ExecutorService pool, boolean force) throws IOException {
        long started = System.nanoTime();
        MapTiles plan = MapTiles.of(radius);
        int step = plan.finest();
        Path cache = out.resolve(CACHE);
        Files.createDirectories(cache);
        Path stateFile = cache.resolve("state.json");
        Map<String, Long> state = new HashMap<>();
        if (Files.isRegularFile(stateFile) && !force) {
            JsonParser.parseString(Files.readString(stateFile)).getAsJsonObject().entrySet()
                    .forEach(e -> state.put(e.getKey(), e.getValue().getAsLong()));
        }
        Map<String, Long> seen = new HashMap<>();
        List<Path> changed = new ArrayList<>();
        if (Files.isDirectory(regionDir)) {
            try (Stream<Path> files = Files.list(regionDir)) {
                for (Path f : files.sorted().toList()) {
                    Matcher m = REGION.matcher(f.getFileName().toString());
                    if (!m.matches()) continue;
                    int rx = Integer.parseInt(m.group(1));
                    int rz = Integer.parseInt(m.group(2));
                    if (!overlaps(rx, rz, radius)) continue;
                    String name = f.getFileName().toString();
                    long mtime = Files.getLastModifiedTime(f).toMillis();
                    seen.put(name, mtime);
                    if (force || !Long.valueOf(mtime).equals(state.get(name)) || !Files.isRegularFile(cacheFile(cache, rx, rz))) changed.add(f);
                }
            }
        }
        boolean removed = false;
        for (String name : state.keySet()) {
            if (seen.containsKey(name)) continue;
            Matcher m = REGION.matcher(name);
            if (m.matches()) Files.deleteIfExists(cacheFile(cache, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
            removed = true;
        }
        if (changed.isEmpty() && !removed && Files.isRegularFile(out.resolve(JSON))) {
            return new Result(dimension, true, seen.size(), 0, 0, 0, 0, 0, 0, 0, 0, (System.nanoTime() - started) / 1_000_000);
        }

        AtomicLong chunks = new AtomicLong();
        AtomicLong unreadable = new AtomicLong();
        AtomicLong unfinished = new AtomicLong();
        Map<String, Long> read = new ConcurrentHashMap<>();
        long t = System.nanoTime();
        List<CompletableFuture<Void>> work = new ArrayList<>();
        for (Path f : changed) {
            work.add(CompletableFuture.runAsync(() -> {
                Matcher m = REGION.matcher(f.getFileName().toString());
                if (!m.matches()) return;
                int rx = Integer.parseInt(m.group(1));
                int rz = Integer.parseInt(m.group(2));
                try {
                    long mtime = Files.getLastModifiedTime(f).toMillis();
                    ExploredRegion region = new ExploredRegion(rx, rz, step);
                    boolean complete = true;
                    for (int index : SavedChunks.present(f)) {
                        ChunkPos pos = new ChunkPos(rx * 32 + (index & 31), rz * 32 + (index >> 5));
                        if (!inside(pos, radius)) continue;
                        Optional<CompoundTag> tag = SavedChunks.read(level, f, pos);
                        if (tag.isEmpty()) {
                            unreadable.incrementAndGet();
                            complete = false;
                            continue;
                        }
                        if (!SavedChunks.full(tag.get())) {
                            unfinished.incrementAndGet();
                            continue;
                        }
                        region.add(index & 31, index >> 5, ExploredChunk.columns(SavedChunks.sections(tag.get()), colours));
                        chunks.incrementAndGet();
                    }
                    Path file = cacheFile(cache, rx, rz);
                    Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                    try (OutputStream o = Files.newOutputStream(tmp)) {
                        region.write(o);
                    }
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    if (complete) read.put(f.getFileName().toString(), mtime);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }, pool));
        }
        CompletableFuture.allOf(work.toArray(CompletableFuture[]::new)).join();
        long readMillis = Math.max(1, (System.nanoTime() - t) / 1_000_000);
        Map<String, Long> next = new HashMap<>();
        seen.forEach((name, mtime) -> {
            Long done = read.get(name);
            if (done != null) next.put(name, done);
            else if (state.containsKey(name) && !changed.stream().anyMatch(p -> p.getFileName().toString().equals(name))) next.put(name, state.get(name));
        });
        JsonObject stateJson = new JsonObject();
        next.forEach(stateJson::addProperty);
        write(stateFile, stateJson);

        Map<Integer, List<String>> index = new java.util.TreeMap<>();
        long[] tileStats = tiles(plan, cache, out, index);
        long millis = (System.nanoTime() - started) / 1_000_000;
        long cps = chunks.get() * 256 * 1000 / readMillis;
        JsonObject json = new JsonObject();
        json.addProperty("dimension", dimension);
        json.addProperty("updatedAt", System.currentTimeMillis() / 1000);
        json.addProperty("path", DIR);
        json.addProperty("rule", "finished saved chunks inside the border, read without loading them; each pixel the mean of its "
                + step + " x " + step + " columns from above, in the generated terrain's palette and relief; transparent where no chunk is saved");
        json.addProperty("regions", seen.size());
        json.addProperty("regionsRead", changed.size());
        json.addProperty("chunksRead", chunks.get());
        json.addProperty("chunksUnreadable", unreadable.get());
        json.addProperty("chunksUnfinished", unfinished.get());
        json.addProperty("readMillis", readMillis);
        json.addProperty("columnsPerSecond", cps);
        json.addProperty("tiles", tileStats[0]);
        json.addProperty("bytes", tileStats[1]);
        JsonObject indexJson = new JsonObject();
        index.forEach((z, keys) -> {
            com.google.gson.JsonArray a = new com.google.gson.JsonArray();
            keys.stream().sorted().forEach(a::add);
            indexJson.add(String.valueOf(z), a);
        });
        json.add("index", indexJson);
        json.addProperty("millis", millis);
        write(out.resolve(JSON), json);
        return new Result(dimension, false, seen.size(), changed.size(), chunks.get(), unreadable.get(), unfinished.get(), readMillis, cps,
                tileStats[0], tileStats[1], millis);
    }

    /** Rebuilds every explored tile from the region caches into {@code index} ({@code x/y} by zoom); returns tiles and bytes written. */
    static long[] tiles(MapTiles plan, Path cache, Path out, Map<Integer, List<String>> index) throws IOException {
        int step = plan.finest();
        int cellsPerRegion = ExploredRegion.BLOCKS / step;
        Map<Long, Path> caches = new HashMap<>();
        try (Stream<Path> files = Files.list(cache)) {
            for (Path f : files.toList()) {
                Matcher m = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.bin").matcher(f.getFileName().toString());
                if (m.matches()) caches.put(key(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))), f);
            }
        }
        Path staging = out.resolve(".staging-" + DIR);
        com.customdimensions.server.render.fast.MapExport.deleteTree(staging);
        AtomicLong bytes = new AtomicLong();
        TilePyramid pyramid = new TilePyramid(plan, false, (z, x, y, argb) -> {
            Path file = staging.resolve(z + "/" + x + "/" + y + ".png");
            MapImages.png(file, MapTiles.TILE, MapTiles.TILE, argb);
            bytes.addAndGet(Files.size(file));
            index.computeIfAbsent(z, k -> new ArrayList<>()).add(x + "/" + y);
        });
        int side = plan.side(plan.maxZoom());
        int piece = com.customdimensions.server.render.fast.MapExport.PIECE;
        int margin = 1;
        for (int py0 = 0; py0 < side; py0 += piece) {
            for (int px0 = 0; px0 < side; px0 += piece) {
                int w = Math.min(piece, side - px0);
                int h = Math.min(piece, side - py0);
                int sw = w + 2 * margin;
                int sh = h + 2 * margin;
                int bx0 = plan.block(plan.maxZoom(), px0 - margin);
                int bz0 = plan.block(plan.maxZoom(), py0 - margin);
                int rx0 = Math.floorDiv(bx0, ExploredRegion.BLOCKS);
                int rx1 = Math.floorDiv(bx0 + sw * step - 1, ExploredRegion.BLOCKS);
                int rz0 = Math.floorDiv(bz0, ExploredRegion.BLOCKS);
                int rz1 = Math.floorDiv(bz0 + sh * step - 1, ExploredRegion.BLOCKS);
                int[] rgb = new int[sw * sh];
                short[] heights = new short[sw * sh];
                boolean[] water = new boolean[sw * sh];
                java.util.Arrays.fill(heights, MapShade.NO_HEIGHT);
                boolean any = false;
                for (int rz = rz0; rz <= rz1; rz++) {
                    for (int rx = rx0; rx <= rx1; rx++) {
                        Path f = caches.get(key(rx, rz));
                        if (f == null) continue;
                        ExploredRegion region;
                        try (InputStream in = Files.newInputStream(f)) {
                            region = ExploredRegion.read(in);
                        } catch (IOException e) {
                            continue;
                        }
                        if (region.filledCells() == 0) continue;
                        for (int cz = 0; cz < cellsPerRegion; cz++) {
                            int y = Math.floorDiv(rz * ExploredRegion.BLOCKS + cz * step - bz0, step);
                            if (y < 0 || y >= sh) continue;
                            for (int cx = 0; cx < cellsPerRegion; cx++) {
                                int x = Math.floorDiv(rx * ExploredRegion.BLOCKS + cx * step - bx0, step);
                                if (x < 0 || x >= sw) continue;
                                int cell = cz * cellsPerRegion + cx;
                                if (!region.filled(cell)) continue;
                                int i = y * sw + x;
                                rgb[i] = region.rgb(cell);
                                heights[i] = region.height(cell);
                                water[i] = region.water(cell);
                                any = true;
                            }
                        }
                    }
                }
                if (!any) continue;
                int[] shaded = MapShade.relief(rgb, heights, water, sw, sh, step);
                int[] inner = new int[w * h];
                for (int y = 0; y < h; y++) System.arraycopy(shaded, (y + margin) * sw + margin, inner, y * w, w);
                pyramid.piece(px0, py0, w, h, inner);
            }
        }
        pyramid.finish();
        Path dir = out.resolve(DIR);
        Path old = out.resolve(".old-" + DIR);
        com.customdimensions.server.render.fast.MapExport.deleteTree(old);
        if (Files.exists(dir)) Files.move(dir, old);
        Files.createDirectories(staging);
        Files.move(staging, dir);
        com.customdimensions.server.render.fast.MapExport.deleteTree(old);
        return new long[] {pyramid.written(), bytes.get()};
    }

    private static long key(int rx, int rz) {
        return (long) rx << 32 | (rz & 0xFFFFFFFFL);
    }

    private static Path cacheFile(Path cache, int rx, int rz) {
        return cache.resolve("r." + rx + "." + rz + ".bin");
    }

    /** Whether region {@code (rx, rz)} reaches inside the border square. */
    static boolean overlaps(int rx, int rz, int radius) {
        int x0 = rx * ExploredRegion.BLOCKS;
        int z0 = rz * ExploredRegion.BLOCKS;
        return x0 < radius && x0 + ExploredRegion.BLOCKS > -radius && z0 < radius && z0 + ExploredRegion.BLOCKS > -radius;
    }

    static boolean inside(ChunkPos pos, int radius) {
        int x0 = pos.x() * 16;
        int z0 = pos.z() * 16;
        return x0 < radius && x0 + 16 > -radius && z0 < radius && z0 + 16 > -radius;
    }

    private static void write(Path out, JsonObject root) throws IOException {
        Files.createDirectories(out.getParent());
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
        Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + "\n");
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
