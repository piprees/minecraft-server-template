package com.customdimensions.core.render;

import java.util.Map;

/**
 * A fixed colour for every vanilla 26.3 biome and every library biome, the
 * library's chosen so no two biomes of one realm read alike on a map; any
 * other id takes a colour from its hash (hue from the hash, saturation 0.45–0.75, lightness
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

    private static final Map<String, Integer> LIBRARY = Map.ofEntries(
            Map.entry("adventure:ash_barrens", 0xB4B2A8),
            Map.entry("adventure:blackstone_basin", 0x26242C),
            Map.entry("adventure:bone_reef", 0xE6DAB0),
            Map.entry("adventure:crimson_pinewood", 0x5C0E2E),
            Map.entry("adventure:gravel_desert", 0x9C8E7C),
            Map.entry("adventure:magma_land", 0xE8641A),
            Map.entry("adventure:mycotoxic_undergrowth", 0x7CCB2A),
            Map.entry("adventure:nether_grasslands", 0xB08A2A),
            Map.entry("adventure:nether_jungle", 0x7E8A1C),
            Map.entry("adventure:quartz_flats", 0xF2EEE4),
            Map.entry("adventure:redstone_abyss", 0xFF4F7A),
            Map.entry("adventure:upside_down_forest", 0xD2389A),
            Map.entry("adventure:withered_forest", 0x4C5E7A),
            Map.entry("adventure:chorus_forest", 0x8A4FB0),
            Map.entry("adventure:dust_wastelands", 0xD8C27A),
            Map.entry("adventure:foggy_mushroomland", 0x5A7FA8),
            Map.entry("adventure:glowing_grasslands", 0x3FCF6A),
            Map.entry("adventure:ice_starfield", 0x9FE3F5),
            Map.entry("adventure:lantern_woods", 0xB0B8C8),
            Map.entry("adventure:megalake", 0x2F5FD0),
            Map.entry("adventure:neon_oasis", 0x1FE0D0),
            Map.entry("adventure:painted_mountains", 0xE07A4A),
            Map.entry("adventure:sheared_highlands", 0x707A86),
            Map.entry("adventure:sulphur_springs", 0xE3E04A),
            Map.entry("adventure:umbral_wastes", 0x1C1428),
            Map.entry("adventure:umbrella_jungle", 0xF07FC0),
            Map.entry("adventure:bioshroom_caves", 0x3FA0E0),
            Map.entry("adventure:buried_dunes", 0xD9B870),
            Map.entry("adventure:cinnabar_veins", 0xB0302A),
            Map.entry("adventure:crystal_hollows", 0xA070E0),
            Map.entry("adventure:frosted_caves", 0xC8ECFF),
            Map.entry("adventure:gelatinous_caves", 0xF0A0D0),
            Map.entry("adventure:lichen_caves", 0x7A9A60),
            Map.entry("adventure:magmatic_caves", 0xD04A10),
            Map.entry("adventure:mineral_strata", 0x8A7A6A),
            Map.entry("adventure:root_hollows", 0x7A5A3A),
            Map.entry("adventure:salt_caves", 0xF4C8D0),
            Map.entry("adventure:sculk_depths", 0x0E3A44),
            Map.entry("adventure:cloud_reef", 0xE8F0FF),
            Map.entry("adventure:golden_skygrove", 0xE8C030),
            Map.entry("adventure:hanging_gardens", 0x4FAF5A),
            Map.entry("adventure:mirage_isles", 0xF4F0D8),
            Map.entry("adventure:sky_highlands", 0x8FC0A0),
            Map.entry("adventure:windswept_isles", 0x9AA8B8),
            Map.entry("adventure:alpha_grove", 0x4E9E2E),
            Map.entry("adventure:alpine_meadow", 0xA0C870),
            Map.entry("adventure:alpine_scree", 0x9A9A94),
            Map.entry("adventure:ashen_savanna", 0xA89A70),
            Map.entry("adventure:ashen_woodland", 0x7A7468),
            Map.entry("adventure:aspen_forest", 0xD8C850),
            Map.entry("adventure:baobab_savanna", 0xC0A040),
            Map.entry("adventure:barley_fields", 0xE0C878),
            Map.entry("adventure:bayou", 0x4A6A3A),
            Map.entry("adventure:carnation_fields", 0xE05A80),
            Map.entry("adventure:cedar_forest", 0x3A6A4A),
            Map.entry("adventure:chalk_cliffs", 0xECECE4),
            Map.entry("adventure:cloud_forest", 0x6AA090),
            Map.entry("adventure:cypress_wetland", 0x3E7A5A),
            Map.entry("adventure:desert_oasis", 0x30C0B0),
            Map.entry("adventure:dry_steppe", 0xC8B488),
            Map.entry("adventure:dying_forest", 0xA07850),
            Map.entry("adventure:fungal_fen", 0x8A5A9A),
            Map.entry("adventure:highland_fells", 0x7A8A6A),
            Map.entry("adventure:joshua_desert", 0xD8B060),
            Map.entry("adventure:lavender_fields", 0xB08AE0),
            Map.entry("adventure:magnolia_woodland", 0xF0B0D0),
            Map.entry("adventure:maple_woodland", 0xD0501E),
            Map.entry("adventure:moonlight_grove", 0x5A6AC0),
            Map.entry("adventure:muddy_river", 0x6A5A40),
            Map.entry("adventure:old_growth_oakwood", 0x2E6A1E),
            Map.entry("adventure:orchid_marsh", 0x40A0C0),
            Map.entry("adventure:parched_forest", 0xB07A3A),
            Map.entry("adventure:prairie", 0xB8C860),
            Map.entry("adventure:pumpkin_fields", 0xE08A20),
            Map.entry("adventure:redwood_grove", 0x8A3A1E),
            Map.entry("adventure:rock_pools", 0x6A8A9A),
            Map.entry("adventure:saguaro_desert", 0xE89A58),
            Map.entry("adventure:sculk_spread", 0x1A4A50),
            Map.entry("adventure:sugi_forest", 0x2A5A3A),
            Map.entry("adventure:temperate_rainforest", 0x1E7A3A),
            Map.entry("adventure:tropical_shores", 0x40D0A0),
            Map.entry("adventure:wisteria_grove", 0x9A6AD0));

    private BiomePalette() {}

    /** Every biome with a fixed colour. */
    public static java.util.Set<String> fixedIds() {
        java.util.Set<String> ids = new java.util.TreeSet<>(VANILLA.keySet());
        ids.addAll(LIBRARY.keySet());
        return ids;
    }

    public static boolean fixed(String id) {
        return VANILLA.containsKey(id) || LIBRARY.containsKey(id);
    }

    /** RGB for {@code id}. */
    public static int colour(String id) {
        Integer fixed = VANILLA.get(id);
        if (fixed == null) fixed = LIBRARY.get(id);
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
