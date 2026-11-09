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
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * What a rig camera sees, measured: a {@value #COLUMNS}×{@value #ROWS} grid of
 * rays from the eye across the view (the vertical field of view from the
 * options, the window's aspect), each clipped against block outlines and any
 * fluid out to the render distance. A ray that hits records its block, the
 * biome there and its distance; one that misses is sky, or {@code unloaded}
 * when it passed a chunk the client does not hold below the loaded chunks' skyline. Shares are of all rays,
 * except biome shares, which are of the rays that hit.
 */
final class FrameCensus {
    static final int COLUMNS = 64;
    static final int ROWS = 36;
    static final double NEAR = 32;
    static final double FAR = 128;

    private FrameCensus() {}

    /** Casts the rays and writes {@code <dir>/<name>.frame.json}; returns the caption. */
    static String write(Minecraft mc, File dir, String name, String dimension) throws IOException {
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
        int sky = 0, unloaded = 0, hits = 0, rays = COLUMNS * ROWS;
        int skyline = skyline(mc, max);
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLUMNS; c++) {
                double u = ((c + 0.5) / COLUMNS * 2 - 1) * tx, v = (1 - (r + 0.5) / ROWS * 2) * ty;
                Vec3 ray = forward.add(right.scale(u)).add(up.scale(v)).normalize();
                Vec3 to = eye.add(ray.scale(max));
                BlockHitResult hit = mc.level.clip(new ClipContext(eye, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, mc.player));
                if (hit.getType() == HitResult.Type.MISS) {
                    if (passesUnloaded(mc, eye, ray, max, skyline)) unloaded++;
                    else sky++;
                    continue;
                }
                hits++;
                BlockPos pos = hit.getBlockPos();
                BlockState state = mc.level.getBlockState(pos);
                String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                String biome = mc.level.getBiome(pos).unwrapKey().map(k -> k.identifier().toString()).orElse("unregistered");
                double distance = hit.getLocation().distanceTo(eye);
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
        String caption = caption(percent(sky, rays), percent(unloaded, rays), bands, rays, biomes, hits);
        o.addProperty("caption", caption);
        Files.createDirectories(dir.toPath());
        Files.writeString(new File(dir, name + ".frame.json").toPath(), new GsonBuilder().setPrettyPrinting().create().toJson(o));
        return caption;
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

    static String caption(double sky, double unloaded, List<Map<String, Integer>> bands, int rays, Map<String, Integer> biomes, int hits) {
        List<String> parts = new ArrayList<>();
        parts.add("sky " + fmt(sky) + "%");
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
