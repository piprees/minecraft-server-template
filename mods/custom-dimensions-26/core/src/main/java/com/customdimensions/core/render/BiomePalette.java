package com.customdimensions.core.render;

import java.util.Map;

/**
 * A fixed colour for every vanilla 26.3 biome; any other id takes a colour
 * from its hash (hue from the hash, saturation 0.45–0.75, lightness
 * 0.40–0.60), the same on every run and every server.
 */
public final class BiomePalette {
    private static final Map<String, Integer> VANILLA = Map.ofEntries(
            Map.entry("minecraft:badlands", 0xD9773A),
            Map.entry("minecraft:bamboo_jungle", 0x6E9E1E),
            Map.entry("minecraft:basalt_deltas", 0x5E5A60),
            Map.entry("minecraft:beach", 0xF0E2A0),
            Map.entry("minecraft:birch_forest", 0x5FA85A),
            Map.entry("minecraft:cherry_grove", 0xF2A9C9),
            Map.entry("minecraft:cold_ocean", 0x2E4F9E),
            Map.entry("minecraft:crimson_forest", 0xA3242A),
            Map.entry("minecraft:dappled_forest", 0x4C8A3A),
            Map.entry("minecraft:dark_forest", 0x2F5A1E),
            Map.entry("minecraft:deep_cold_ocean", 0x1F3A7A),
            Map.entry("minecraft:deep_dark", 0x0E2A30),
            Map.entry("minecraft:deep_frozen_ocean", 0x3A4E8A),
            Map.entry("minecraft:deep_lukewarm_ocean", 0x0E5A86),
            Map.entry("minecraft:deep_ocean", 0x14307A),
            Map.entry("minecraft:desert", 0xE8D27A),
            Map.entry("minecraft:dripstone_caves", 0x8A6A4A),
            Map.entry("minecraft:end_barrens", 0x9C96B8),
            Map.entry("minecraft:end_highlands", 0xD8D2A0),
            Map.entry("minecraft:end_midlands", 0xC2BC8E),
            Map.entry("minecraft:eroded_badlands", 0xE8552A),
            Map.entry("minecraft:flower_forest", 0x7CB342),
            Map.entry("minecraft:forest", 0x3E8E2E),
            Map.entry("minecraft:frozen_ocean", 0x7088C8),
            Map.entry("minecraft:frozen_peaks", 0xC8DCF0),
            Map.entry("minecraft:frozen_river", 0xA8C0F0),
            Map.entry("minecraft:grove", 0x9EC4B0),
            Map.entry("minecraft:ice_spikes", 0xB4E0F0),
            Map.entry("minecraft:jagged_peaks", 0xE6E6EE),
            Map.entry("minecraft:jungle", 0x3E9E10),
            Map.entry("minecraft:lukewarm_ocean", 0x1A6EB0),
            Map.entry("minecraft:lush_caves", 0x60B040),
            Map.entry("minecraft:mangrove_swamp", 0x4A6E3A),
            Map.entry("minecraft:meadow", 0x9AD06A),
            Map.entry("minecraft:mushroom_fields", 0xA070B0),
            Map.entry("minecraft:nether_wastes", 0x8A3A2A),
            Map.entry("minecraft:ocean", 0x2040A0),
            Map.entry("minecraft:old_growth_birch_forest", 0x74BC6A),
            Map.entry("minecraft:old_growth_pine_taiga", 0x5A6E3A),
            Map.entry("minecraft:old_growth_spruce_taiga", 0x4E6232),
            Map.entry("minecraft:pale_garden", 0xB8BCB0),
            Map.entry("minecraft:plains", 0x8DB360),
            Map.entry("minecraft:river", 0x3060D8),
            Map.entry("minecraft:savanna", 0xBDB25F),
            Map.entry("minecraft:savanna_plateau", 0xA79D64),
            Map.entry("minecraft:small_end_islands", 0x4A4660),
            Map.entry("minecraft:snowy_beach", 0xF0F0D8),
            Map.entry("minecraft:snowy_plains", 0xF2F6FA),
            Map.entry("minecraft:snowy_slopes", 0xDCE6F0),
            Map.entry("minecraft:snowy_taiga", 0x30594A),
            Map.entry("minecraft:soul_sand_valley", 0x5E4A3A),
            Map.entry("minecraft:sparse_jungle", 0x64B020),
            Map.entry("minecraft:stony_peaks", 0x9A9A9A),
            Map.entry("minecraft:stony_shore", 0x7A7A7A),
            Map.entry("minecraft:sulfur_caves", 0xC8C040),
            Map.entry("minecraft:sunflower_plains", 0xB5DB60),
            Map.entry("minecraft:swamp", 0x4C7A4A),
            Map.entry("minecraft:taiga", 0x3C6E52),
            Map.entry("minecraft:the_end", 0x8070A0),
            Map.entry("minecraft:the_void", 0x000000),
            Map.entry("minecraft:warm_ocean", 0x00A0C8),
            Map.entry("minecraft:warped_forest", 0x168A84),
            Map.entry("minecraft:windswept_forest", 0x5A7A5A),
            Map.entry("minecraft:windswept_gravelly_hills", 0x888E88),
            Map.entry("minecraft:windswept_hills", 0x7A8A7A),
            Map.entry("minecraft:windswept_savanna", 0xE0D080),
            Map.entry("minecraft:wooded_badlands", 0xB09A5A));

    private BiomePalette() {}

    /** Every biome with a fixed colour. */
    public static java.util.Set<String> fixedIds() {
        return VANILLA.keySet();
    }

    public static boolean fixed(String id) {
        return VANILLA.containsKey(id);
    }

    /** RGB for {@code id}. */
    public static int colour(String id) {
        Integer fixed = VANILLA.get(id);
        return fixed != null ? fixed : hashed(id);
    }

    static int hashed(String id) {
        int h = id.hashCode() * 0x9E3779B1;
        float hue = ((h >>> 16) & 0xFFFF) / 65536f;
        float saturation = 0.45f + ((h >>> 8) & 0xFF) / 255f * 0.30f;
        float lightness = 0.40f + (h & 0xFF) / 255f * 0.20f;
        return hsl(hue, saturation, lightness);
    }

    /** HSL in 0–1 to RGB. */
    static int hsl(float h, float s, float l) {
        float q = l < 0.5f ? l * (1 + s) : l + s - l * s;
        float p = 2 * l - q;
        int r = Math.round(channel(p, q, h + 1f / 3) * 255);
        int g = Math.round(channel(p, q, h) * 255);
        int b = Math.round(channel(p, q, h - 1f / 3) * 255);
        return r << 16 | g << 8 | b;
    }

    private static float channel(float p, float q, float t) {
        if (t < 0) t += 1;
        if (t > 1) t -= 1;
        if (t < 1f / 6) return p + (q - p) * 6 * t;
        if (t < 0.5f) return q;
        if (t < 2f / 3) return p + (q - p) * (2f / 3 - t) * 6;
        return p;
    }

    public static String hex(int rgb) {
        return String.format("#%06x", rgb & 0xFFFFFF);
    }
}
