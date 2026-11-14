package com.customdimensions.core.render.map;

import java.util.List;
import java.util.Set;

/**
 * One saved chunk as the web map sees it from above: per column the colour
 * of what lies on top (part-transparent blocks laid over what is under them,
 * water by depth over its floor, all through {@link MapShade}), the height of
 * the highest visible block, and whether that block is water. Sections hold
 * vanilla's saved form: a block palette of ids (a waterlogged state passed as
 * {@value #WATER}) with indices packed into longs, and a biome palette over
 * 4 × 4 × 4 cells packed the same way.
 */
public final class ExploredChunk {
    public static final String WATER = "minecraft:water";
    /** Blocks seen through as nothing. */
    public static final Set<String> INVISIBLE = Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:light",
            "minecraft:barrier", "minecraft:structure_void");
    /** Blocks that are water to the eye: water and the plants that only grow in it. */
    public static final Set<String> WATERY = Set.of(WATER, "minecraft:bubble_column", "minecraft:seagrass", "minecraft:tall_seagrass",
            "minecraft:kelp", "minecraft:kelp_plant");
    /** Water deeper than this is drawn over black. */
    public static final int MAX_WATER = 64;

    /** Colours for ids: a block's palette entry (never null) and a biome's tint (never null). */
    public interface Resolver {
        MapPalette.Entry block(String id);

        BiomeTint biome(String id);
    }

    /** A saved section at section index {@code y} (block y {@code 16 * y} up). Data may be null when its palette has one entry. */
    public record Section(int y, List<String> blocks, long[] blockData, List<String> biomes, long[] biomeData) {}

    /** The chunk's 256 columns, z-major: colour RGB, height ({@link MapShade#NO_HEIGHT} when nothing is visible), water on top. */
    public record Columns(int[] rgb, short[] height, boolean[] water) {}

    private ExploredChunk() {}

    public static Columns columns(List<Section> sections, Resolver resolver) {
        List<Section> sorted = sections.stream().filter(s -> !onlyInvisible(s)).sorted((a, b) -> Integer.compare(b.y(), a.y())).toList();
        int n = sorted.size();
        int[][] blocks = new int[n][];
        int[][] biomes = new int[n][];
        for (int s = 0; s < n; s++) {
            Section sec = sorted.get(s);
            blocks[s] = PackedIndices.unpack(sec.blockData(), sec.blocks().size(), 4096, 4);
            biomes[s] = PackedIndices.unpack(sec.biomeData(), sec.biomes() == null ? 1 : sec.biomes().size(), 64, 1);
        }
        Columns out = new Columns(new int[256], new short[256], new boolean[256]);
        java.util.Arrays.fill(out.height(), MapShade.NO_HEIGHT);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) column(sorted, blocks, biomes, x, z, resolver, out);
        }
        return out;
    }

    private static boolean onlyInvisible(Section s) {
        for (String id : s.blocks()) if (!INVISIBLE.contains(id)) return false;
        return true;
    }

    private static void column(List<Section> sections, int[][] blocks, int[][] biomes, int x, int z, Resolver resolver, Columns out) {
        int i = z * 16 + x;
        double r = 0, g = 0, b = 0, cover = 0;
        int top = Integer.MIN_VALUE;
        int waterDepth = 0;
        int waterColour = 0;
        boolean done = false;
        for (int s = 0; s < sections.size() && !done; s++) {
            Section sec = sections.get(s);
            for (int ly = 15; ly >= 0 && !done; ly--) {
                String id = sec.blocks().get(blocks[s][(ly * 16 + z) * 16 + x]);
                if (INVISIBLE.contains(id)) continue;
                int y = sec.y() * 16 + ly;
                if (top == Integer.MIN_VALUE) {
                    top = y;
                    out.water()[i] = WATERY.contains(id);
                }
                BiomeTint tint = null;
                if (WATERY.contains(id)) {
                    if (waterDepth == 0) {
                        tint = tint(sec, biomes[s], x, ly, z, resolver);
                        waterColour = MapPalette.colour(resolver.block(WATER), tint);
                    }
                    if (++waterDepth >= MAX_WATER) {
                        int c = MapShade.water(waterColour, 0, waterDepth);
                        double w = 1 - cover;
                        r += w * (c >> 16 & 0xFF);
                        g += w * (c >> 8 & 0xFF);
                        b += w * (c & 0xFF);
                        cover = 1;
                        done = true;
                    }
                    continue;
                }
                MapPalette.Entry e = resolver.block(id);
                if (e.alpha() == 0) continue;
                if (tint == null) tint = e.tint() == MapPalette.Tint.NONE ? null : tint(sec, biomes[s], x, ly, z, resolver);
                int c = MapPalette.colour(e, tint == null ? BiomeTint.PLAINS : tint);
                double a = e.alpha() / 255.0;
                if (waterDepth > 0) {
                    c = MapShade.water(waterColour, c, waterDepth);
                    a = 1;
                    waterDepth = 0;
                }
                double w = (1 - cover) * a;
                r += w * (c >> 16 & 0xFF);
                g += w * (c >> 8 & 0xFF);
                b += w * (c & 0xFF);
                cover += w;
                if (cover >= 0.99) done = true;
            }
        }
        if (top == Integer.MIN_VALUE) return;
        if (waterDepth > 0) {
            int c = MapShade.water(waterColour, 0, waterDepth);
            double w = 1 - cover;
            r += w * (c >> 16 & 0xFF);
            g += w * (c >> 8 & 0xFF);
            b += w * (c & 0xFF);
            cover = 1;
        }
        if (cover <= 0) return;
        out.rgb()[i] = (int) Math.round(r / cover) << 16 | (int) Math.round(g / cover) << 8 | (int) Math.round(b / cover);
        out.height()[i] = (short) top;
    }

    private static BiomeTint tint(Section sec, int[] biomes, int x, int ly, int z, Resolver resolver) {
        if (sec.biomes() == null || sec.biomes().isEmpty()) return BiomeTint.PLAINS;
        return resolver.biome(sec.biomes().get(biomes[((ly >> 2) * 4 + (z >> 2)) * 4 + (x >> 2)]));
    }
}
