package com.customdimensions.client.dev;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * What a rig camera sees, measured: a {@value #COLUMNS}×{@value #ROWS} grid of
 * rays from the eye across the view (the vertical field of view from the
 * options, the window's aspect), each clipped against block outlines and any
 * fluid out to the render distance. A ray that hits records its block, the
 * biome there and its distance; one that misses is {@code unloaded} when it passed a chunk the client does not hold
 * below the loaded chunks' skyline, {@code void} when it points below the horizon and left the bottom of the world or
 * ends over a loaded column with no block in it (a void hole), and sky otherwise. Shares are of all rays, except biome
 * shares, which are of the rays that hit. It also gives
 * {@link FrameGate} its ray measures: the median ray distance (a miss counts as the render distance), the block covering
 * most rays within {@link FrameGate#NEAR} blocks, the share of rays that reach the world border before any block, and
 * the fog colour at the camera.
 */
final class FrameCensus {
    static final int COLUMNS = 64;
    static final int ROWS = 36;
    static final double NEAR = 32;
    static final double FAR = 128;

    private FrameCensus() {}

    /** A frame's census: the frame.json body, its caption, the gate's ray measures (image measures zero) and the fog colour. */
    record Census(JsonObject json, String caption, FrameGate.Measures rays, int fogRgb) {
        FrameGate.Measures with(double[] image) {
            return new FrameGate.Measures(rays.medianDistance(), rays.nearBlock(), rays.nearBlockPercent(), rays.skyPercent(),
                    rays.voidPercent(), rays.unloadedPercent(), rays.borderPercent(), image[0], image[1]);
        }
    }

    /** Casts the rays and writes {@code <dir>/<name>.frame.json}; returns the caption. */
    static String write(Minecraft mc, File dir, String name, String dimension) throws IOException {
        Census census = measure(mc, name, dimension);
        write(dir, name, census.json());
        return census.caption();
    }

    static void write(File dir, String name, JsonObject json) throws IOException {
        Files.createDirectories(dir.toPath());
        Files.writeString(new File(dir, name + ".frame.json").toPath(), new GsonBuilder().setPrettyPrinting().create().toJson(json));
    }

    /** Casts the rays; writes nothing. */
    static Census measure(Minecraft mc, String name, String dimension) {
        Vec3 eye = mc.player.getEyePosition();
        float yaw = mc.player.getYRot(), pitch = mc.player.getXRot();
        double fov = mc.options.fov().get();
        double aspect = mc.getWindow().getWidth() / (double) Math.max(1, mc.getWindow().getHeight());
        double max = mc.options.getEffectiveRenderDistance() * 16.0;
        Vec3 forward = Vec3.directionFromRotation(pitch, yaw);
        Vec3 right = Vec3.directionFromRotation(0, yaw + 90);
        Vec3 up = Vec3.directionFromRotation(pitch - 90, yaw);
        double ty = Math.tan(Math.toRadians(fov / 2)), tx = ty * aspect;
        Map<String, Integer> blocks = new HashMap<>(), biomes = new HashMap<>();
        List<Map<String, Integer>> bands = List.of(new HashMap<>(), new HashMap<>(), new HashMap<>());
        int[] bandRays = new int[3];
        int sky = 0, unloaded = 0, voided = 0, border = 0, hits = 0, rays = COLUMNS * ROWS;
        int skyline = skyline(mc, max);
        double[] distances = new double[rays];
        Map<String, Integer> near = new HashMap<>();
        WorldBorder wb = mc.level.getWorldBorder();
        double half = wb.getSize() / 2;
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLUMNS; c++) {
                double u = ((c + 0.5) / COLUMNS * 2 - 1) * tx, v = (1 - (r + 0.5) / ROWS * 2) * ty;
                Vec3 ray = forward.add(right.scale(u)).add(up.scale(v)).normalize();
                Vec3 to = eye.add(ray.scale(max));
                BlockHitResult hit = mc.level.clip(new ClipContext(eye, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, mc.player));
                double reach = hit.getType() == HitResult.Type.MISS ? max : hit.getLocation().distanceTo(eye);
                distances[r * COLUMNS + c] = reach;
                double horizontal = Math.sqrt(ray.x * ray.x + ray.z * ray.z);
                if (horizontal > 1e-6 && FrameGate.borderDistance(eye.x, eye.z, ray.x / horizontal, ray.z / horizontal, wb.getCenterX(),
                        wb.getCenterZ(), half) / horizontal < reach) {
                    border++;
                }
                if (hit.getType() == HitResult.Type.MISS) {
                    if (passesUnloaded(mc, eye, ray, max, skyline)) unloaded++;
                    else if (ray.y < 0 && (eye.y + ray.y * max < mc.level.getMinY() || emptyColumn(mc, to))) voided++;
                    else sky++;
                    continue;
                }
                hits++;
                BlockPos pos = hit.getBlockPos();
                BlockState state = mc.level.getBlockState(pos);
                String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                String biome = mc.level.getBiome(pos).unwrapKey().map(k -> k.identifier().toString()).orElse("unregistered");
                double distance = reach;
                if (distance < FrameGate.NEAR) near.merge(id, 1, Integer::sum);
                int band = distance < NEAR ? 0 : distance <= FAR ? 1 : 2;
                blocks.merge(id, 1, Integer::sum);
                biomes.merge(biome, 1, Integer::sum);
                bands.get(band).merge(id, 1, Integer::sum);
                bandRays[band]++;
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("dimension", dimension);
        JsonObject camera = new JsonObject();
        camera.addProperty("x", eye.x);
        camera.addProperty("y", eye.y);
        camera.addProperty("z", eye.z);
        camera.addProperty("yaw", yaw);
        camera.addProperty("pitch", pitch);
        o.add("eye", camera);
        o.addProperty("fov", fov);
        o.addProperty("aspect", Math.round(aspect * 1000) / 1000.0);
        o.addProperty("grid", COLUMNS + "x" + ROWS);
        o.addProperty("maxDistance", max);
        o.addProperty("rays", rays);
        o.addProperty("skyPercent", percent(sky, rays));
        o.addProperty("unloadedPercent", percent(unloaded, rays));
        o.addProperty("voidPercent", percent(voided, rays));
        o.addProperty("borderPercent", percent(border, rays));
        java.util.Arrays.sort(distances);
        double median = (distances[rays / 2 - 1] + distances[rays / 2]) / 2;
        o.addProperty("medianDistance", Math.round(median * 10) / 10.0);
        Map.Entry<String, Integer> nearTop = near.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(Map.entry("none", 0));
        o.addProperty("nearBlock", nearTop.getKey());
        o.addProperty("nearBlockPercent", percent(nearTop.getValue(), rays));
        org.joml.Vector3fc fog = mc.gameRenderer.mainCamera().attributeProbe().getValue(EnvironmentAttributes.FOG_COLOR, 1f);
        int fogRgb = (Math.round(fog.x() * 255) & 0xFF) << 16 | (Math.round(fog.y() * 255) & 0xFF) << 8 | Math.round(fog.z() * 255) & 0xFF;
        o.addProperty("fogColor", String.format(Locale.ROOT, "#%06x", fogRgb));
        o.add("blocksPercent", shares(blocks, rays, 30));
        o.add("biomesPercentOfHits", shares(biomes, hits, 20));
        JsonObject b = new JsonObject();
        String[] names = {"near", "mid", "far"};
        for (int i = 0; i < 3; i++) {
            JsonObject band = new JsonObject();
            band.addProperty("percent", percent(bandRays[i], rays));
            band.add("blocksPercent", shares(bands.get(i), rays, 10));
            b.add(names[i], band);
        }
        o.add("bands", b);
        o.addProperty("bandLimits", "near under " + (int) NEAR + ", mid " + (int) NEAR + "-" + (int) FAR + ", far over " + (int) FAR + " blocks");
        String caption = caption(percent(sky, rays), percent(unloaded, rays), percent(voided, rays), bands, rays, biomes, hits);
        o.addProperty("caption", caption);
        FrameGate.Measures measures = new FrameGate.Measures(median, nearTop.getKey(), percent(nearTop.getValue(), rays), percent(sky, rays),
                percent(voided, rays), percent(unloaded, rays), percent(border, rays), 0, 0);
        return new Census(o, caption, measures, fogRgb);
    }

    /**
     * Whether a missed ray passed a chunk the client does not hold below the
     * skyline (the highest motion-blocking height in the loaded chunks, sampled
     * every 8 blocks): above it the ray could only have met sky.
     */
    private static boolean passesUnloaded(Minecraft mc, Vec3 eye, Vec3 dir, double max, int skyline) {
        for (double s = 8; s <= max; s += 8) {
            Vec3 p = eye.add(dir.scale(s));
            if (p.y < mc.level.getMinY() || p.y > mc.level.getMaxY()) return false;
            if (!mc.level.getChunkSource().hasChunk(((int) Math.floor(p.x)) >> 4, ((int) Math.floor(p.z)) >> 4)) return p.y <= skyline;
        }
        return false;
    }

    /** Whether {@code p} lies over a loaded column that holds no motion-blocking block: the open air of a void hole. */
    private static boolean emptyColumn(Minecraft mc, Vec3 p) {
        int x = (int) Math.floor(p.x), z = (int) Math.floor(p.z);
        return mc.level.getChunkSource().hasChunk(x >> 4, z >> 4)
                && mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z) <= mc.level.getMinY();
    }

    private static int skyline(Minecraft mc, double max) {
        int top = mc.level.getMinY();
        int cx = (int) Math.floor(mc.player.getX()), cz = (int) Math.floor(mc.player.getZ());
        for (int dx = -(int) max; dx <= max; dx += 8) {
            for (int dz = -(int) max; dz <= max; dz += 8) {
                int x = cx + dx, z = cz + dz;
                if (!mc.level.getChunkSource().hasChunk(x >> 4, z >> 4)) continue;
                top = Math.max(top, mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z));
            }
        }
        return top;
    }

    static String caption(double sky, double unloaded, double voided, List<Map<String, Integer>> bands, int rays, Map<String, Integer> biomes,
            int hits) {
        List<String> parts = new ArrayList<>();
        parts.add("sky " + fmt(sky) + "%");
        if (voided > 0) parts.add("void " + fmt(voided) + "%");
        if (unloaded > 0) parts.add("unloaded " + fmt(unloaded) + "%");
        String[] names = {"near", "mid", "far"};
        for (int i = 0; i < 3; i++) {
            String top = top(bands.get(i), rays, 2, 1);
            if (!top.isEmpty()) parts.add(names[i] + ": " + top);
        }
        String b = top(biomes, hits, 3, 5);
        if (!b.isEmpty()) parts.add("biomes: " + b);
        return String.join("; ", parts);
    }

    private static String top(Map<String, Integer> counts, int total, int limit, double minPercent) {
        List<String> out = new ArrayList<>();
        counts.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(limit).forEach(e -> {
            double p = percent(e.getValue(), total);
            if (p >= minPercent) out.add(strip(e.getKey()) + " " + fmt(p) + "%");
        });
        return String.join(", ", out);
    }

    private static JsonObject shares(Map<String, Integer> counts, int total, int limit) {
        JsonObject o = new JsonObject();
        Map<String, Integer> sorted = new LinkedHashMap<>();
        counts.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(limit).forEach(e -> sorted.put(e.getKey(), e.getValue()));
        sorted.forEach((k, v) -> o.addProperty(k, percent(v, total)));
        return o;
    }

    private static double percent(int n, int total) {
        return total == 0 ? 0 : Math.round(n * 1000.0 / total) / 10.0;
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }

    private static String strip(String id) {
        return id.startsWith("minecraft:") ? id.substring(10) : id;
    }
}
