package com.customdimensions.roll;

import com.customdimensions.MultiverseServer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a biome's ground looks like from above.
 *
 * <p>A map is legible because its colours are the colours of the blocks on
 * the ground: netherrack is dark red, grass is green, sand is pale, end
 * stone is bone. This resolves a biome to the surface material its surface
 * rule places, then to that block's Minecraft map colour.
 *
 * <p>The alternative the renderer used to take — blending a biome's fog and
 * sky colours — is not a ground colour at all. It is the colour of the air,
 * and in the nether the modded packs set it to vivid pinks and purples, so a
 * whole dimension came out magenta while carrying no information about the
 * terrain underneath.
 *
 * <p>Table and keyword rules are both ported from the Python renderer this
 * replaced ({@code scripts/seed/surface_rules.py} on {@code main}), which is
 * the version that produced maps people could read. The explicit table
 * covers the vanilla and modded biomes worth naming; everything else falls
 * through ordered keyword tests, which is what keeps a pack of ~1800 biomes
 * from needing an entry each.
 */
public final class SurfacePalette {

    private static final String RESOURCE = "/biome_surface_colours.json";
    private static final int FALLBACK = 0x6D9930;   // grass, the commonest ground there is

    private static volatile Map<String, int[]> materials;
    private static volatile Map<String, String> explicit;
    private static final Map<String, Integer> CACHE = new ConcurrentHashMap<>();

    private SurfacePalette() {
    }

    /** The packed RGB a biome's ground reads as on a map. */
    public static int colourOf(String biomeId) {
        return CACHE.computeIfAbsent(biomeId == null ? "" : biomeId, id -> {
            load();
            int[] rgb = materials.get(materialOf(id));
            if (rgb == null) {
                return FALLBACK;
            }
            return (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
        });
    }

    /** Whether a biome is water-surfaced, which the renderer shades by depth. */
    public static boolean isWater(String biomeId) {
        return materialOf(biomeId == null ? "" : biomeId).startsWith("water");
    }

    /** The surface material key for a biome: the explicit table, then keywords. */
    public static String materialOf(String biomeId) {
        load();
        String named = explicit.get(biomeId);
        if (named != null) {
            return named;
        }
        String id = biomeId.toLowerCase(Locale.ROOT);

        if (id.contains("ocean")) {
            if (id.contains("frozen") || id.contains("cold")) {
                return "water_cold";
            }
            if (id.contains("warm") || id.contains("lukewarm") || id.contains("tropical")) {
                return "water_warm";
            }
            return "water";
        }
        if (id.contains("river")) {
            return id.contains("frozen") ? "water_frozen" : "water";
        }
        if (id.contains("desert") || id.contains("dune")) {
            return "sand";
        }
        if (id.contains("beach") || (id.contains("shore") && !id.contains("stony"))) {
            return "sand";
        }
        if (id.contains("badlands") || id.contains("mesa") || id.contains("red_sand")) {
            return "red_sand";
        }
        if (id.contains("mushroom") || id.contains("mycelium")) {
            return "mycelium";
        }
        if (id.contains("mangrove")) {
            return "mud";
        }
        if (id.contains("ice_spikes") || id.contains("frozen_peak") || id.contains("snowy_slope")
                || id.contains("snowy_plain")) {
            return "snow";
        }
        if (id.contains("stony") || id.contains("rocky") || id.contains("basalt_cliff")) {
            return "stone";
        }
        if (id.contains("gravel")) {
            return "gravel";
        }
        if (id.contains("old_growth") && (id.contains("pine") || id.contains("spruce"))) {
            return "podzol";
        }
        if (id.contains("nether") || id.contains("netherrack")) {
            return "netherrack";
        }
        if (id.contains("crimson")) {
            return "crimson_nylium";
        }
        if (id.contains("warped") && id.contains("forest")) {
            return "warped_nylium";
        }
        if (id.contains("soul")) {
            return "soul_sand";
        }
        if (id.contains("basalt")) {
            return "basalt";
        }
        if (id.contains("end") && (id.contains("highland") || id.contains("midland")
                || id.contains("barren") || id.contains("island") || id.contains("the_end"))) {
            return "end_stone";
        }
        if (id.contains("void") || id.contains("nullscape")) {
            return "void";
        }
        if (id.contains("snow") || id.contains("frozen") || id.contains("ice")) {
            return "snow";
        }
        // Incendium is a whole-Nether overhaul, so anything of its own that
        // reached here is nether ground rather than the grass default.
        if (id.startsWith("incendium:")) {
            return "netherrack";
        }
        return "grass";
    }

    private static void load() {
        if (materials != null) {
            return;
        }
        synchronized (SurfacePalette.class) {
            if (materials != null) {
                return;
            }
            Map<String, int[]> mats = new HashMap<>();
            Map<String, String> biomes = new HashMap<>();
            try (InputStream in = SurfacePalette.class.getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    throw new IllegalStateException(RESOURCE + " missing from the jar");
                }
                JsonObject root = JsonParser.parseReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("materials").entrySet()) {
                    var arr = e.getValue().getAsJsonArray();
                    mats.put(e.getKey(), new int[]{arr.get(0).getAsInt(), arr.get(1).getAsInt(),
                            arr.get(2).getAsInt()});
                }
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("biomes").entrySet()) {
                    biomes.put(e.getKey(), e.getValue().getAsString());
                }
            } catch (Exception e) {
                // A palette that failed to load leaves every cell grass rather
                // than every cell magenta — wrong, but still a readable map.
                MultiverseServer.LOGGER.error("Surface palette unreadable; maps will be flat", e);
            }
            explicit = biomes;
            materials = mats;
        }
    }
}
